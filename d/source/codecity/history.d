/// History replay: re-analyses the code base at up to N commits sampled from its first-parent history, so that the
/// viewers can show the city growing commit by commit (`--history[=N]`).
///
/// In the pipeline this runs after the normal analysis of the working tree, inside `CityModel.build`
/// (`codecity.city`), which merges the result into the model: the union of present and past types is laid out, and
/// every building and district gets one value per snapshot. This module only produces the per-snapshot analyses:
///
/// 1. `git rev-list --first-parent --reverse HEAD` lists the commits, oldest first; `sampleIndices` picks at most N
///    of them, always including the first and the last (HEAD). One `git log --no-walk` call reads their metadata.
/// 2. A partial (blobless) clone of the clone cache is missing most old file contents; they are fetched in one batch
///    up front (`prefetchMissing`), not lazily one by one.
/// 3. One temporary directory mirrors the analysed root at the current snapshot. The first snapshot writes every
///    relevant blob (`git ls-tree`); each later one applies the difference to its predecessor (`git diff-tree`).
///    Only files the analysis looks at are written: supported sources and the manifests that affect their parse.
///    Contents come from one long-running `git cat-file --batch` (`codecity.git.BlobReader`).
/// 4. The mirror is analysed with the caller's `SourceAnalyzer` after every step. Unchanged files keep their
///    modification time and so hit the parse cache; every written file gets a new, strictly increasing modification
///    time, so it is parsed again even when its size did not change.
///
/// The repository is untrusted. Its git commands are hardened (`codecity.git.gitArgs`); only a clone of the GitHub
/// clone cache may fetch missing objects. Tree entries are only written below the mirror when their path is a plain
/// relative one (`safePath`): a crafted tree can hold entries such as `..` or `/abs/dir` that git itself would never
/// check out. A blob larger than `codecity.git.maxBlobBytes`, which the analysis would not read anyway, is treated
/// like a missing one: its file is absent from that snapshot.
///
/// The Java and Rust editions implement the same rules and produce the same snapshots, so the city JSON with history
/// is identical across editions.
module codecity.history;

import codecity.analysis : languageOf, manifestNames, SourceAnalyzer;
import codecity.git : BlobReader, bytes, gitArgs, gitEnv, lossyUtf8, realPath, run;
import codecity.github : isCached;
import codecity.model;
import core.time : msecs;
import std.algorithm : canFind, startsWith;
import std.array : split;
import std.conv : to;
import std.datetime.stopwatch : AutoStart, StopWatch;
import std.datetime.systime : SysTime;
import std.file : exists, isFile, mkdirRecurse, remove, rmdirRecurse, setTimes, tempDir, write;
import std.path : baseName, buildPath, dirName, relativePath;
import std.stdio : stderr, stdout, writefln;
import std.string : indexOf, strip;

/// Default and allowed range of `--history=N`.
enum defaultSamples = 60;
enum minSamples = 2, maxSamples = 500;

/// One sampled commit, as the viewers' timeline shows it.
struct Snapshot
{
    /// Full 40-hex commit name.
    string commit;
    /// Author time in Unix seconds (`%at`).
    long time;
    /// Author name (`%an`) and the first line of the commit message (`%s`).
    string author;
    string subject;
    /// Number of types in the analysis of this snapshot.
    size_t types;
}

/// A type's four replay metrics in one snapshot, and the file it was declared in there (rewritten to the analysed
/// root like `History.lastSeen`), whose git statistics up to the snapshot are the type's then.
struct Metrics
{
    int nom, noa, loc, cx;
    string file;
}

/// Everything the replay learned about the past, for `CityModel.build` to merge with the present.
struct History
{
    /// The sampled commits, oldest first; the last one is HEAD.
    Snapshot[] snapshots;
    /// `metrics[k]` maps the fqn of every type in snapshot `k` to its metrics there.
    Metrics[string][] metrics;
    /// Every type seen in any snapshot, as it was in the latest snapshot that has it. `TypeDecl.file` is rewritten
    /// from the mirror to the analysed root, so it looks like a path from the normal analysis.
    TypeDecl[string] lastSeen;
    /// The dependencies of `lastSeen` (sorted fqns, all of types of the same snapshot).
    string[][string] lastDeps;
}

/// The indices of the `n` commits to sample out of `m` (oldest first): all of them when `m <= n`, otherwise
/// `(2·k·(m−1) + (n−1)) div (2·(n−1))` for k = 0..n−1, i.e. `k·(m−1)/(n−1)` rounded half up, evenly spread and
/// always including the first (0) and the last (m−1) commit. Integer arithmetic, so all editions pick the same.
size_t[] sampleIndices(size_t m, size_t n) pure nothrow @safe
{
    size_t[] result;
    if (m <= n)
    {
        foreach (i; 0 .. m) result ~= i;
        return result;
    }
    foreach (k; 0 .. n) result ~= (2 * k * (m - 1) + (n - 1)) / (2 * (n - 1));
    return result;
}

/// Printed to stderr when `--history` was asked for but there is no git history to replay.
void warnNoHistory()
{
    stderr.writeln("warning: --history needs git history; replay disabled");
}

/**
 * Analyses `root` at up to `samples` commits of its first-parent history (see the module documentation), using
 * `analyzer` with `includeTests` like the normal run. Prints progress to stdout. Returns false, after printing the
 * warning, when `root` is not inside a git work tree or HEAD has no commits; git failures later on throw.
 *
 * Paths: `root` may lie below the top of the work tree. Only files under its path prefix are used and they are
 * written relative to `root`, so fqns and paths match the normal analysis. The mirror is
 * `<temp>/codecity-history-<random>/<name of root>`: its last component equals root's name because the Rust analyser
 * names a crate without Cargo.toml after the analysed directory. The temporary directory is deleted at the end, also
 * on error.
 */
bool collect(SourceAnalyzer analyzer, string root, bool includeTests, uint samples, out History result)
{
    auto sw = StopWatch(AutoStart.yes);
    auto topLevel = run(root, "rev-parse", "--show-toplevel");
    if (topLevel.length == 0)
    {
        warnNoHistory();
        return false;
    }
    // canonical, like `root` itself (see `parseArgs` in app.d), so the prefix below is a plain relative path
    const top = realPath(topLevel);
    const rel = relativePath(root, top);
    const prefix = rel == "." ? "" : rel ~ "/";
    // The path filter for ls-tree / diff-tree, which run in `top`: the prefix without its trailing slash, as a
    // literal pathspec so that glob characters in directory names are not interpreted.
    const string[] pathspec = prefix.length ? ["--", ":(literal)" ~ prefix[0 .. $ - 1]] : [];

    auto revList = bytes(top, "rev-list", "--first-parent", "--reverse", "HEAD");
    string[] commits;
    foreach (line; (cast(string) revList).split('\n')) if (line.strip.length) commits ~= line.strip;
    if (revList is null || commits.length == 0)
    {
        warnNoHistory();
        return false;
    }
    string[] picked;
    foreach (i; sampleIndices(commits.length, samples)) picked ~= commits[i];
    result.snapshots = readSnapshots(top, picked);

    // Only the clone cache may fetch: the configuration of any other repository could run programs on fetch.
    const cached = isCached(top);
    if (cached) prefetchMissing(top);

    // A new directory (never an existing one, which another user of a shared temporary directory could have planted),
    // readable by the user only.
    const base = buildPath(tempDir, "codecity-history-" ~ randomName());
    {
        import core.sys.posix.sys.stat : mkdir;
        import std.conv : octal;
        import std.string : toStringz;
        if (mkdir(base.toStringz, octal!700) != 0) throw new Exception("cannot create " ~ base);
    }
    scope (exit) removeTree(base);
    mkdirRecurse(buildPath(base, root.baseName));
    // canonical too (the system temp directory may be a symbolic link), so analysed paths start with `mirror`
    const mirror = realPath(buildPath(base, root.baseName));
    auto blobs = new BlobReader(top, cached);
    scope (exit) blobs.close();

    // Modification times for written files: a fixed base plus one millisecond per file, so each write gets a time
    // no file had before (whatever the clock does) and the parse cache never mistakes new content for old.
    const stampBase = SysTime.fromUnixTime(1_000_000_000);
    long stamps;
    result.metrics = new Metrics[string][picked.length];
    size_t reported;
    foreach (k, commit; picked)
    {
        // What to change in the mirror: files to delete and files to (re)write with a blob's content, as paths
        // relative to root.
        string[] deletes, writes, shas;
        // A path the analysis would read: a supported source file or a manifest, stored as a regular file (mode
        // 100644 or 100755; symbolic links 120000 and submodules 160000 are skipped).
        bool wanted(string mode, string path)
        {
            Language l;
            return (mode == "100644" || mode == "100755") && (languageOf(path, l) || manifestNames.canFind(path.baseName));
        }
        if (k == 0)
        {
            // `ls-tree -r -z`: "<mode> <type> <sha>\t<path>" per entry, NUL-terminated
            foreach (entry; nulFields(gitOrThrow(top, ["ls-tree", "-r", "-z", commit] ~ pathspec)))
            {
                const tab = entry.indexOf('\t');
                if (tab < 0) continue;
                const meta = entry[0 .. tab].split(' ');
                const path = entry[tab + 1 .. $];
                if (meta.length != 3 || !path.startsWith(prefix) || !safePath(path[prefix.length .. $])) continue;
                if (wanted(meta[0], path))
                {
                    writes ~= path[prefix.length .. $];
                    shas ~= meta[2];
                }
            }
        }
        else
        {
            // `diff-tree -r -z`: ":<old mode> <new mode> <old sha> <new sha> <status>" NUL "<path>" NUL. A (added)
            // and M (modified) write the new blob, D deletes, T (type change) goes by the new mode: a regular file
            // is written, anything else (a symbolic link, a submodule) removes what was there.
            auto fields = nulFields(gitOrThrow(top, ["diff-tree", "-r", "-z", "--no-renames", picked[k - 1], commit] ~ pathspec));
            for (size_t i = 0; i + 1 < fields.length; i += 2)
            {
                const meta = fields[i].split(' ');
                const path = fields[i + 1];
                if (meta.length != 5 || !path.startsWith(prefix) || !safePath(path[prefix.length .. $])) continue;
                const local = path[prefix.length .. $];
                if (meta[4] != "D" && wanted(meta[1], path))
                {
                    writes ~= local;
                    shas ~= meta[3];
                }
                else deletes ~= local;
            }
        }
        // Deletes first: a path can turn from a file into a directory (or back) between two snapshots.
        foreach (d; deletes)
        {
            const f = buildPath(mirror, d);
            if (f.exists && f.isFile) remove(f);
        }
        blobs.each(shas, (i, content, present) {
            const f = buildPath(mirror, writes[i]);
            if (!present)
            {
                // missing, or too large to be analysed: the snapshot lacks the file
                if (f.exists && f.isFile) remove(f);
                return;
            }
            mkdirRecurse(f.dirName);
            write(f, content);
            const stamp = stampBase + (++stamps).msecs;
            setTimes(f, stamp, stamp);
        });

        auto analysis = analyzer.analyze(mirror, includeTests);
        result.snapshots[k].types = analysis.types.length;
        foreach (t; analysis.types)
        {
            // `analyze` returns paths as `mirror` joined with the relative path; the same relative path under root
            // is what the normal analysis would have produced
            t.file = root ~ t.file[mirror.length .. $];
            result.metrics[k][t.fqn] = Metrics(t.nom, t.noa, t.loc, t.complexity, t.file);
            result.lastSeen[t.fqn] = t;
            result.lastDeps[t.fqn] = analysis.dependencies.get(t.fqn, null);
        }

        // one progress line per 10 % (the last snapshot gets the summary line below instead)
        const done = k + 1;
        if (done < picked.length && done * 10 / picked.length > reported)
        {
            reported = done * 10 / picked.length;
            writefln("  history: %d/%d snapshots …", done, picked.length);
            stdout.flush();
        }
    }
    writefln("  history: %d snapshots from %s to %s — %d ms", picked.length, utcDate(result.snapshots[0].time),
        utcDate(result.snapshots[$ - 1].time), sw.peek.total!"msecs");
    stdout.flush();
    return true;
}

/// Commit metadata of `shas`, in the same order, from one `git log --no-walk=unsorted` call. Each commit is one line
/// `%H NUL %at NUL %an NUL %s`: git cuts a subject at a NUL and joins its lines, and a name holds neither, so any
/// other character (also a control character) is kept as it is. Names and subjects are decoded lossily like the rest
/// of git's output (`lossyUtf8`).
private Snapshot[] readSnapshots(string top, string[] shas)
{
    auto raw = gitOrThrow(top, ["log", "--no-walk=unsorted", "--format=%H%x00%at%x00%an%x00%s"] ~ shas);
    Snapshot[] result;
    foreach (line; (cast(string) raw).split('\n'))
    {
        const fields = lossyUtf8(line).split('\0');
        if (fields.length != 4) continue;
        result ~= Snapshot(fields[0], fields[1].to!long, fields[2], fields[3]);
    }
    if (result.length != shas.length) throw new Exception("git log: could not read the sampled commits");
    return result;
}

/// In a partial clone (`remote.origin.promisor` = true, as made by the GitHub clone cache), fetches every missing
/// object in one batch, so that materialising old snapshots does not make git fetch blobs lazily one at a time. Only
/// called for a repository of the clone cache (`isCached`): the configuration of any other repository could run
/// programs on fetch.
///
/// `rev-list --missing=print` lists the missing objects (lines starting with `?`) without fetching them; they are
/// passed to one `git fetch --stdin` (which takes object names as refspecs). The noop negotiation skips the
/// have/want exchange, which is pointless here. Errors are ignored: git can still fetch lazily afterwards.
private void prefetchMissing(string top)
{
    import std.process : Config, pipe, spawnProcess, wait;
    import std.stdio : File;
    if (run(top, "config", "--get", "remote.origin.promisor") != "true") return;
    string[] missing;
    foreach (line; (cast(string) bytes(top, "rev-list", "--objects", "--missing=print", "HEAD")).split('\n'))
        if (line.startsWith("?")) missing ~= line[1 .. $].strip;
    if (missing.length == 0) return;
    writefln("  history: fetching %d missing objects …", missing.length);
    stdout.flush();
    try
    {
        auto input = pipe();
        auto pid = spawnProcess(gitArgs(top, true, "-c", "fetch.negotiationAlgorithm=noop", "fetch", "-q", "origin",
            "--no-tags", "--no-write-fetch-head", "--recurse-submodules=no", "--filter=blob:none", "--stdin"),
            input.readEnd, File("/dev/null", "w"), File("/dev/null", "w"), gitEnv(true));
        foreach (m; missing) input.writeEnd.write(m, "\n");
        input.writeEnd.close();
        wait(pid);
    }
    catch (Exception) {}
}

/// Output of `git -C top args…`; throws when git fails, which aborts the replay with an error.
private ubyte[] gitOrThrow(string top, string[] args)
{
    auto data = bytes(top, args);
    if (data is null) throw new Exception("git " ~ args[0] ~ " failed while building the history");
    return data;
}

/// Whether `rel`, a path from a tree listing relative to the analysed root, may be written below the mirror: every
/// '/'-separated component is non-empty and neither ".", "..", nor ".git" in any case, and none holds a backslash or
/// a colon (path syntax on Windows). git never checks out anything else, but a crafted tree object can hold such
/// entries, and writing "../../x" or "/home/u/x" would put a file outside the mirror.
bool safePath(string rel)
{
    import std.algorithm : any, equal, map;
    import std.ascii : toLower;
    return rel.length > 0 && !rel.split('/').any!(c => c.length == 0 || c == "." || c == ".."
        || c.map!(ch => toLower(ch)).equal(".git") || c.canFind('\\') || c.canFind(':') || c.canFind('\0'));
}

/// The NUL-terminated fields of `-z` output.
private string[] nulFields(ubyte[] data)
{
    auto fields = (cast(string) data).split('\0');
    // the output ends with a NUL, which leaves an empty last field
    if (fields.length && fields[$ - 1].length == 0) fields = fields[0 .. $ - 1];
    return fields;
}

/// `YYYY-MM-DD` of a Unix time, in UTC (for the summary line).
private string utcDate(long epoch)
{
    import std.datetime.timezone : UTC;
    return SysTime.fromUnixTime(epoch, UTC()).toISOExtString[0 .. 10];
}

/// Deletes the temporary directory; a failure (e.g. it is already gone) is ignored, as the replay is done with it.
private void removeTree(string dir) nothrow
{
    try rmdirRecurse(dir);
    catch (Exception) {}
}

/// A random name for the temporary directory.
private string randomName()
{
    import std.uuid : randomUUID;
    return randomUUID.toString;
}

unittest
{
    // the documented examples: M=10, N=4 spreads evenly; with M ≤ N every commit is used
    assert(sampleIndices(10, 4) == [0, 3, 6, 9]);
    assert(sampleIndices(3, 60) == [0, 1, 2]);
    // M=100, N=60: first and last included, strictly increasing
    auto s = sampleIndices(100, 60);
    assert(s.length == 60 && s[0] == 0 && s[$ - 1] == 99);
    foreach (i; 1 .. s.length) assert(s[i] > s[i - 1]);
}

unittest
{
    // The shared replay scenario of all three editions: three commits with fixed names, e-mails and dates.
    //   1. src/A.java: class A with one method
    //   2. adds src/B.java (class B, two methods); A gets a second method
    //   3. deletes B; A gets a field
    import codecity.city : CityModel;
    import codecity.github : GitHubInfo;
    import codecity.layout : side;
    import std.process : Config, execute;
    import std.uuid : randomUUID;
    const dir = buildPath(tempDir, "codecity-history-test-" ~ randomUUID.toString);
    mkdirRecurse(dir);
    scope (exit) rmdirRecurse(dir);
    const script = `set -e
export GIT_AUTHOR_NAME=Ada GIT_AUTHOR_EMAIL=ada@example.com GIT_COMMITTER_NAME=Ada GIT_COMMITTER_EMAIL=ada@example.com
commit() { GIT_AUTHOR_DATE="$1 +0000" GIT_COMMITTER_DATE="$1 +0000" git -c commit.gpgsign=false commit -q -m "$2"; }
git init -q .
mkdir src
echo 'class A { void a() {} }' > src/A.java
git add -A; commit 2020-01-01T00:00:00 "add A"
echo 'class A { void a() {} void b() {} }' > src/A.java
echo 'class B { void x() {} void y() {} }' > src/B.java
git add -A; commit 2020-01-02T00:00:00 "add B"
echo 'class A { int f; void a() {} void b() {} }' > src/A.java
git rm -q src/B.java
git add -A; commit 2020-01-03T00:00:00 "drop B"
`;
    assert(execute(["sh", "-c", script], null, Config.none, size_t.max, dir).status == 0);
    const root = realPath(dir);
    auto model = CityModel.build(new SourceAnalyzer, root, true, true, false, GitHubInfo.init, defaultSamples);

    // three commits, fewer than 60: all of them, oldest first
    assert(model.snapshots.length == 3);
    const shas = (cast(string) bytes(root, "rev-list", "--reverse", "HEAD")).strip.split('\n');
    foreach (k, s; model.snapshots)
    {
        assert(s.commit == shas[k] && s.author == "Ada");
        assert(s.time == 1_577_836_800 + 86_400 * k);
    }
    assert(model.snapshots[0].subject == "add A" && model.snapshots[2].subject == "drop B");
    size_t[] typeCounts;
    foreach (s; model.snapshots) typeCounts ~= s.types;
    assert(typeCounts == [1, 2, 1]);

    // A exists today; B only in the second snapshot, so it is gone but still part of the city
    assert(model.buildings.length == 2);
    auto a = &model.buildings[0], b = &model.buildings[1];
    assert(model.analysis.types[0].fqn == "A" && model.analysis.types[1].fqn == "B");
    assert(a.hist.nom == [1, 2, 2] && a.hist.noa == [0, 0, 1] && !a.gone);
    assert(b.hist.nom == [-1, 2, -1] && b.gone);
    assert(a.hist.commits == [1, 2, 3] && a.hist.authors == [1, 1, 1]);
    assert(a.hist.last == [1_577_836_800, 1_577_923_200, 1_578_009_600]);
    // B's file was touched by commits 2 and 3 (its deletion), but only commit 2 is at or before snapshot 2
    assert(b.hist.commits == [0, 1, 0] && b.hist.last == [-1, 1_577_923_200, -1]);
    // the stats count only today's types
    assert(model.stats.types == 1);
    // the building has its actual size: today's side(1) for A, side(0) as last seen for B
    assert(a.lot.side == side(1) && b.lot.side == side(0));
    // district type counts include only today's types
    assert(model.districts[0].types == 1 && model.districts[1].types == 1);
    // both districts (the city and the default package) hold a type in every snapshot
    assert(model.districts.length == 2);
    foreach (d; model.districtHist) assert(d == [true, true, true]);

    import std.algorithm : canFind;
    const json = model.json;
    assert(json.canFind(`"hist":{"nom":[-1,2,-1],"noa":[-1,0,-1],"loc":[-1,1,-1],"cx":[-1,2,-1],"commits":[0,1,0],"authors":[0,1,0],"last":[-1,1577923200,-1]},"gone":true}`));
    assert(json.canFind(`"hist":[1,1,1]}`));
    assert(json.canFind(`"history":{"snapshots":[{"commit":"` ~ shas[0] ~ `","time":1577836800,"author":"Ada","subject":"add A","types":1}`));
}

unittest
{
    // The hostile repository shared by all three editions (src/test/resources/hostile-repo.sh): its configuration
    // names programs for git to run, and its trees hold paths that escape the work tree. Neither may have any effect.
    import codecity.city : CityModel;
    import codecity.git : load;
    import codecity.github : GitHubInfo;
    import std.algorithm : filter, map;
    import std.array : array;
    import std.file : dirEntries, SpanMode;
    import std.process : Config, execute;
    import std.uuid : randomUUID;
    const dir = buildPath(tempDir, "codecity-hostile-test-" ~ randomUUID.toString);
    const repo = buildPath(dir, "repo"), markers = buildPath(dir, "m");
    mkdirRecurse(repo);
    mkdirRecurse(markers);
    scope (exit) rmdirRecurse(dir);
    // next to the Java edition's sources: <repository>/src/test/resources, three levels above this file
    import std.path : buildNormalizedPath;
    const script = buildNormalizedPath(__FILE_FULL_PATH__.dirName, "..", "..", "..", "src", "test", "resources",
        "hostile-repo.sh");
    assert(execute(["sh", script, realPath(markers)], null, Config.none, size_t.max, repo).status == 0);
    const root = realPath(repo);
    const escA = buildPath(tempDir, "EscA.java");
    if (escA.exists) remove(escA);

    GitHubInfo info;
    assert(GitHubInfo.detect(root, info));
    assert(load(root).length > 0);
    auto model = CityModel.build(new SourceAnalyzer, root, true, true, false, GitHubInfo.init, defaultSamples);

    auto hits = dirEntries(markers, SpanMode.shallow).map!(e => e.name.baseName).filter!(n => n.startsWith("hit-")).array;
    assert(hits.length == 0, hits.to!string);
    assert(!escA.exists && !buildPath(markers, "EscB.java").exists && !buildPath(tempDir, "EscC.java").exists);
    // Every snapshot holds just A: the escaping entries are skipped, and Z's blob is missing (not fetched).
    size_t[] typeCounts;
    foreach (s; model.snapshots) typeCounts ~= s.types;
    assert(typeCounts == [1, 1, 1]);
}

unittest
{
    assert(safePath("src/A.java") && safePath("a.b/..c/.gitx/A.java"));
    foreach (bad; ["../A.java", "a/../../A.java", "/tmp/A.java", "a//A.java", "./A.java", "a/", ".git/A.java",
            "a/.GIT/A.java", `a\..\A.java`, "C:A.java", ""])
        assert(!safePath(bad), bad);
}
