/// Mines `git log` once for per-file churn, author count and recency.
///
/// Runs on its own thread while the sources are parsed (see `CityModel.build`). The result is keyed by absolute
/// path so it can be matched against `TypeDecl.file`. Also hosts the small git helpers used by `codecity.github` and
/// by the history replay (`codecity.history`): `bytes` for the output of long-running commands and `BlobReader` for
/// reading file contents out of the object database.
///
/// The repository being analysed is untrusted: its `.git/config` can name programs for git to run. Every git command
/// on it is therefore built by `gitArgs`/`gitEnv`, which override the keys that matter for the commands CodeCity runs.
module codecity.git;

import codecity.model : FileHistory;
import core.time : seconds;
import std.conv : to;
import std.path : buildNormalizedPath;
import std.process : Config, kill, pipe, Pid, spawnProcess, tryWait, wait;
import std.stdio : File;
import std.string : strip;

/// One commit touching a file, as the history pass in `load` saw it: its author time (Unix seconds) and author
/// e-mail. The history replay counts these up to each snapshot's time (see `codecity.city`).
struct Touch
{
    long time;
    string author;
}

/// How long a short command (`run`, `gh auth token`) may take before `output` kills it.
private enum timeout = 30.seconds;

/// Largest blob `BlobReader` hands out: the analysers' file-size limit (16 MiB), above which they do not read a file.
enum maxBlobBytes = 16L * 1024 * 1024;

/// Configuration that every git command on an analysed repository gets on its command line. `-c` beats every
/// configuration file (also files pulled in with include/includeIf), and git hands it on to the git processes it
/// starts itself. Of the keys that make git run a program, these are the ones the read-only commands CodeCity runs
/// could reach: the fsmonitor hook (any command that refreshes the index), hooks, and `log.showSignature`, with which
/// `git log` runs `gpg.program` for every signed commit. The others (pager: stdout is never a terminal; external diff
/// and textconv: no patch output is ever asked for; filters: nothing is checked out) are not reached. An empty
/// core.fsmonitor means "off" in every git version.
private immutable string[] hardening = ["-c", "core.fsmonitor=", "-c", "core.hooksPath=/dev/null", "-c",
    "log.showSignature=false"];

/// No transport at all. A repository can claim to be a partial clone, and reading one of its "missing" objects would
/// then make git fetch it from the remote: that runs the remote's upload-pack, ssh, proxy, askpass and
/// credential-helper commands from the configuration. Each built-in transport is named, since a
/// `protocol.<name>.allow` in the repository's configuration would beat `protocol.allow`. Only the clone cache, whose
/// configuration CodeCity wrote itself, is allowed to fetch.
private immutable string[] noTransport = ["-c", "protocol.allow=never", "-c", "protocol.file.allow=never", "-c",
    "protocol.git.allow=never", "-c", "protocol.ssh.allow=never", "-c", "protocol.http.allow=never", "-c",
    "protocol.https.allow=never", "-c", "protocol.ext.allow=never"];

/// The absolute path of the program `name` ("git", "gh") on the `PATH`, looked up once (`findProgram`); null when it
/// is not installed. Programs are always started by this path, never by bare name: a relative or empty `PATH` entry
/// means the current directory, which may be the hostile checkout being analysed (and Windows would search the
/// current directory anyway).
string executable(string name)
{
    import std.array : split;
    import std.process : environment;
    __gshared string[string] found;
    synchronized
    {
        if (auto p = name in found) return *p;
        version (Windows)
        {
            auto pathext = environment.get("PATHEXT", "").strip;
            const extensions = (pathext.length ? pathext : ".COM;.EXE;.BAT;.CMD").split(";");
            enum windows = true;
        }
        else
        {
            const string[] extensions = [""];
            enum windows = false;
        }
        auto p = findProgram(name, environment.get("PATH"), extensions, windows);
        if (p !is null) found[name] = p;
        return p;
    }
}

/// The first `<dir>/<name><extension>` that is a regular file (and, unless `windows`, executable), for the entries
/// `dir` of `path` (split at the system's path separator) in order and each of `extensions`; null when there is none.
/// Empty and relative entries (".", "bin", "") are skipped, so the current directory is never searched. The other
/// editions implement the same rules.
string findProgram(string name, string path, const string[] extensions, bool windows)
{
    import std.array : split;
    import std.file : exists, isFile;
    import std.path : buildPath, isAbsolute, pathSeparator;
    import std.string : toStringz;
    if (path is null) return null;
    foreach (dir; path.split(pathSeparator))
    {
        if (dir.length == 0 || !dir.isAbsolute) continue;
        foreach (ext; extensions)
        {
            const candidate = buildPath(dir, name ~ ext);
            bool runnable = windows;
            version (Posix)
            {
                import core.sys.posix.unistd : access, X_OK;
                runnable = runnable || access(candidate.toStringz, X_OK) == 0;
            }
            try
                if (candidate.exists && candidate.isFile && runnable) return candidate;
            catch (Exception) {}
        }
    }
    return null;
}

/// The command line of a hardened `git -C dir args…`: `hardening`, plus `noTransport` unless `fetch`. `fetch` is only
/// for repositories of the clone cache (`codecity.github.isCached`). git is started by its absolute path
/// (`executable`); throws when it is not installed.
string[] gitArgs(string dir, bool fetch, string[] args...)
{
    auto git = executable("git");
    if (git is null) throw new Exception("git not found on the PATH");
    return [git] ~ hardening ~ (fetch ? [] : noTransport) ~ ["-C", dir] ~ args;
}

/// The environment additions for `gitArgs`: git must not prompt for credentials and, from git 2.44 on, must not fetch
/// missing objects lazily (unless `fetch`).
string[string] gitEnv(bool fetch)
{
    string[string] env = ["GIT_TERMINAL_PROMPT": "0"];
    if (!fetch) env["GIT_NO_LAZY_FETCH"] = "1";
    return env;
}

/// Output of the hardened `git -C dir args…` (`gitArgs`), or null if git fails, is unavailable or times out (see
/// `output`).
string run(string dir, string[] args...)
{
    try return output(gitArgs(dir, false, args), gitEnv(false));
    catch (Exception) return null; // git is not installed
}

/// Output of a short command, with surrounding whitespace stripped and invalid UTF-8 replaced (`lossyUtf8`); null if
/// it cannot be started, exits with an error or takes longer than `timeout`. stderr is discarded, so it can neither
/// fill an unread pipe nor end up in the result. stdout is read with `poll` against the deadline, so the limit also
/// covers a command that hangs while keeping stdout open: it is then killed. `env` and `config` are passed on to
/// `spawnProcess` (added variables, or with `Config.newEnv` the whole environment).
string output(string[] cmd, const string[string] env = null, Config config = Config.none)
{
    import core.stdc.errno : errno, EINTR;
    import core.sys.posix.poll : poll, pollfd, POLLIN;
    import core.sys.posix.unistd : read;
    import core.thread : Thread;
    import core.time : MonoTime, msecs;
    Pid pid;
    try
    {
        auto p = pipe();
        pid = spawn(cmd, p.writeEnd, env, config);
        const deadline = MonoTime.currTime + timeout;
        bool timedOut()
        {
            if (MonoTime.currTime < deadline) return false;
            kill(pid);
            wait(pid);
            return true;
        }
        ubyte[] data;
        ubyte[4096] buf;
        while (true)
        {
            if (timedOut()) return null;
            auto fd = pollfd(p.readEnd.fileno, POLLIN, 0);
            if (poll(&fd, 1, cast(int)(deadline - MonoTime.currTime).total!"msecs" + 1) <= 0) continue;
            const n = read(p.readEnd.fileno, buf.ptr, buf.length);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break; // end of output (or a read error)
            data ~= buf[0 .. n];
        }
        // stdout is closed, so the command is about to exit; poll for that until the deadline.
        while (true)
        {
            auto status = tryWait(pid);
            if (status.terminated) return status.status == 0 ? lossyUtf8(cast(char[]) data).strip : null;
            if (timedOut()) return null;
            Thread.sleep(10.msecs);
        }
    }
    catch (Exception)
    {
        if (pid !is null)
            try { kill(pid); wait(pid); } catch (Exception) {}
        return null;
    }
}

/// Starts `cmd` with stdin and stderr on the null device and stdout on `stdout`. `spawnProcess` closes the
/// parent's copies of these files, so the read end of a pipe sees end-of-file once the child is done.
private Pid spawn(string[] cmd, File stdout, const string[string] env = null, Config config = Config.none)
{
    return spawnProcess(cmd, File("/dev/null"), stdout, File("/dev/null", "w"), env, config);
}

/// `s` with every ill-formed UTF-8 sequence replaced by U+FFFD, one per maximal subpart (the longest prefix of a
/// valid sequence, or else a single byte), as Unicode recommends. This is what Java's UTF-8 decoder and Rust's
/// `String::from_utf8_lossy` do, so non-UTF-8 git output (a file name or an e-mail) comes out the same in all
/// editions.
string lossyUtf8(const(char)[] s)
{
    import std.utf : validate;
    try
    {
        validate(s);
        return s.idup;
    }
    catch (Exception) {}
    string result;
    size_t i = 0;
    while (i < s.length)
    {
        const b = cast(ubyte) s[i];
        // Length of the sequence `b` starts, and the allowed range of the byte after it (Unicode table 3-7); the
        // narrower ranges exclude overlong forms, surrogates and code points above U+10FFFF.
        size_t len;
        ubyte lo = 0x80, hi = 0xBF;
        if (b < 0x80) len = 1;
        else if (b >= 0xC2 && b <= 0xDF) len = 2;
        else if (b >= 0xE0 && b <= 0xEF)
        {
            len = 3;
            if (b == 0xE0) lo = 0xA0;
            if (b == 0xED) hi = 0x9F;
        }
        else if (b >= 0xF0 && b <= 0xF4)
        {
            len = 4;
            if (b == 0xF0) lo = 0x90;
            if (b == 0xF4) hi = 0x8F;
        }
        size_t n = 1;
        while (len > 1 && n < len && i + n < s.length)
        {
            const c = cast(ubyte) s[i + n];
            if (c < (n == 1 ? lo : 0x80) || c > (n == 1 ? hi : 0xBF)) break;
            n++;
        }
        if (len > 0 && n == len) result ~= s[i .. i + n];
        else result ~= "\uFFFD";
        i += n;
    }
    return result;
}

/// Empty when `root` is not inside a git work tree or git is unavailable.
///
/// One `git log` over the whole history of the supported source files, streamed line by line. With the format
/// below, every commit starts with a header line `\x1e<author time>\x1f<author e-mail>` (ASCII record and unit
/// separators, which do not occur in a timestamp or a normal e-mail address), followed by a blank line and the names
/// of the files it touched, one per line. Blank lines are skipped; file lines belong to the most recent header.
///
/// Merges are skipped (`--no-merges`). With `--no-renames` a rename is listed as the old and the new path, and the
/// file's history under its new name starts at the rename. Any failure, including a malformed timestamp, yields an
/// empty result rather than an error.
///
/// With `touches` non-null, every commit of every file is also kept there (same keys, newest commit first, the order
/// of `git log`), for the history replay; it stays empty whenever the result is empty.
FileHistory[string] load(string root, Touch[][string]* touches = null)
{
    FileHistory[string] result;
    auto top = run(root, "rev-parse", "--show-toplevel");
    if (top.length == 0) return result;
    // Paths in the log are relative to the top level, and the analysed paths are canonical, so make the top level
    // canonical too.
    top = realPath(top);
    // Per-file accumulator while the log is read; authors are a set of e-mail addresses.
    struct Acc
    {
        uint commits;
        bool[string] authors;
        long last = long.min, first = long.max;
        /// Only filled when the caller asked for `touches`.
        Touch[] touches;
    }
    Acc[string] files;
    try
    {
        // core.quotepath=off: print non-ASCII file names verbatim instead of quoted octal escapes. The pathspecs are
        // the extensions the analysers handle (`codecity.analysis.languageOf`); they are relative to `root`, so a
        // root below the top level only sees its own files.
        // stderr goes to the null device: a pipe that is never read would block git once it is full.
        auto p = pipe();
        // gitArgs hardens git against the repository's own configuration (no gpg for signed commits, ...).
        auto pid = spawn(gitArgs(root, false, "-c", "core.quotepath=off", "log", "--no-merges", "--no-renames",
                "--format=%x1e%at%x1f%aE", "--name-only", "--", "*.java", "*.ts", "*.tsx", "*.mts", "*.cts", "*.rs"),
                p.writeEnd, gitEnv(false));
        long time;
        string author;
        // Each line is decoded lossily: a path or e-mail that is not valid UTF-8 gets U+FFFD in place of the bad
        // bytes, as in the other editions. A trailing "\r" is dropped like Java's readLine and Rust's lines() do.
        foreach (raw; p.readEnd.byLine)
        {
            string line = lossyUtf8(raw.length && raw[$ - 1] == '\r' ? raw[0 .. $ - 1] : raw);
            if (line.length && line[0] == '\x1e')
            {
                import std.string : indexOf;
                const sep = line.indexOf('\x1f');
                time = sep > 0 ? line[1 .. sep].to!long : 0;
                author = sep > 0 ? line[sep + 1 .. $] : "";
            }
            else if (line.strip.length)
            {
                auto acc = line in files;
                if (acc is null)
                {
                    files[line] = Acc();
                    acc = line in files;
                }
                acc.commits++;
                acc.authors[author] = true;
                if (time > acc.last) acc.last = time;
                if (time < acc.first) acc.first = time;
                if (touches !is null) acc.touches ~= Touch(time, author);
            }
        }
        wait(pid);
    }
    catch (Exception)
        return result;
    foreach (rel, a; files)
    {
        const path = buildNormalizedPath(top, rel);
        result[path] = FileHistory(a.commits, cast(uint) a.authors.length, a.last, a.first);
        if (touches !is null) (*touches)[path] = a.touches;
    }
    return result;
}

/// The raw standard output of the hardened `git -C dir args…` (`gitArgs`), or null when git cannot be started or
/// exits with an error.
///
/// Unlike `run` there is no time limit and nothing is stripped or decoded, so it suits long-running commands and
/// NUL-separated (`-z`) output, e.g. the history replay's `rev-list`, `ls-tree` and `diff-tree`. stderr is
/// discarded and stdin is the null device. An empty but successful output is returned as an empty, non-null array.
ubyte[] bytes(string dir, string[] args...)
{
    try
    {
        auto p = pipe();
        auto pid = spawn(gitArgs(dir, false, args), p.writeEnd, gitEnv(false));
        ubyte[] data = [];
        ubyte[65536] buf;
        while (true)
        {
            const chunk = p.readEnd.rawRead(buf[]);
            if (chunk.length == 0) break;
            data ~= chunk;
        }
        return wait(pid) == 0 ? data : null;
    }
    catch (Exception)
        return null;
}

/// Reads blobs out of a repository's object database through one long-running `git cat-file --batch` process, so
/// that materialising thousands of files costs one process rather than one per file.
///
/// The protocol: for every object name written to its stdin (one per line), git answers with a header line
/// `<sha> <type> <size>` followed by exactly `size` bytes of content and a newline, or with `<sha> missing`. Requests
/// are sent in chunks of `chunk` names and all answers to a chunk are read before the next one is written: 512 names
/// (about 21 KB) always fit into the pipe, so the writer never blocks while git waits for its answers to be read.
/// Blobs larger than `maxBlobBytes`, which the analysis would not read anyway, are skipped unread.
final class BlobReader
{
    private enum chunk = 512;
    private Pid pid;
    /// Our ends of the pipes: requests go to git's stdin, answers come from its stdout.
    private File requests, answers;

    /// Starts the hardened `git cat-file --batch` in `dir`; `fetch`: whether git may fetch missing objects lazily
    /// (only in the clone cache, see `gitArgs`). Throws when git cannot be started.
    this(string dir, bool fetch)
    {
        auto input = pipe(), output = pipe();
        pid = spawnProcess(gitArgs(dir, fetch, "cat-file", "--batch"), input.readEnd, output.writeEnd,
            File("/dev/null", "w"), gitEnv(fetch));
        requests = input.writeEnd;
        answers = output.readEnd;
    }

    /// Calls `each` with the index and content of every blob named by `shas` (40-hex object names), in order, one at a
    /// time (so only one is in memory); `present` is false for a blob that is missing or larger than `maxBlobBytes`.
    /// Throws when the process ended early.
    void each(const string[] shas, scope void delegate(size_t i, const(ubyte)[] data, bool present) each)
    {
        import std.array : split;
        for (size_t start = 0; start < shas.length; start += chunk)
        {
            const part = shas[start .. start + (shas.length - start < chunk ? shas.length - start : chunk)];
            foreach (sha; part) requests.write(sha, "\n");
            requests.flush();
            foreach (j, sha; part)
            {
                const header = answers.readln().strip.split(' ');
                if (header.length != 3)
                {
                    if (header.length == 0) throw new Exception("git cat-file ended early");
                    each(start + j, null, false); // "<sha> missing"
                    continue;
                }
                const size = header[2].to!ulong;
                if (size > maxBlobBytes)
                {
                    // Skip the content and the newline after it.
                    ubyte[65536] buf;
                    for (ulong left = size + 1; left > 0;)
                    {
                        const n = answers.rawRead(buf[0 .. left < buf.length ? cast(size_t) left : buf.length]).length;
                        if (n == 0) throw new Exception("git cat-file: object " ~ sha ~ " cut short");
                        left -= n;
                    }
                    each(start + j, null, false);
                    continue;
                }
                auto data = new ubyte[cast(size_t) size];
                // rawRead loops until the buffer is full (or end of file, which means git died)
                if (data.length && answers.rawRead(data).length != data.length)
                    throw new Exception("git cat-file: object " ~ sha ~ " cut short");
                ubyte[1] newline;
                answers.rawRead(newline[]);
                each(start + j, data, true);
            }
        }
    }

    /// Closes git's stdin, which ends the process, and waits for it.
    void close()
    {
        try
        {
            requests.close();
            wait(pid);
        }
        catch (Exception) {}
    }
}

/// Canonical absolute path (symlinks resolved), like Java's toRealPath / Rust's canonicalize.
/// Returns `path` unchanged when it cannot be resolved (e.g. it does not exist).
string realPath(string path)
{
    import core.sys.posix.stdlib : realpath;
    import core.stdc.stdlib : free;
    import std.string : fromStringz, toStringz;
    // With a null buffer, POSIX realpath allocates the result with malloc; it is freed after copying.
    auto p = realpath(path.toStringz, null);
    if (p is null) return path;
    scope (exit) free(p);
    return p.fromStringz.idup;
}

unittest
{
    // The PATH search that finds git and gh (the Java and Rust editions test the same cases).
    import std.array : join;
    import std.conv : octal;
    import std.file : exists, getcwd, mkdirRecurse, remove, rmdirRecurse, setAttributes, tempDir, write;
    import std.path : buildPath;
    import std.uuid : randomUUID;
    const dir = buildPath(tempDir, "codecity-path-" ~ randomUUID.toString);
    scope (exit) rmdirRecurse(dir);
    string program(string sub, uint mode)
    {
        const d = buildPath(dir, sub);
        mkdirRecurse(d);
        write(buildPath(d, "git"), "#!/bin/sh\n");
        setAttributes(buildPath(d, "git"), mode);
        return d;
    }
    const a = program("a", octal!755), b = program("b", octal!755), c = program("c", octal!644);
    mkdirRecurse(buildPath(dir, "empty"));
    // Relative and empty entries are skipped; the first absolute entry holding the program wins.
    assert(findProgram("git", ["", ".", "a", buildPath(dir, "empty"), a, b].join(":"), [""], false) == buildPath(a, "git"));
    // A non-executable file does not count, and nothing is found without a PATH.
    assert(findProgram("git", c ~ ":" ~ b, [""], false) == buildPath(b, "git"));
    assert(findProgram("git", null, [""], false) is null);
    // Windows-style extensions are tried in order.
    write(buildPath(c, "gh.EXE"), "");
    assert(findProgram("gh", c, [".COM", ".EXE"], true) == buildPath(c, "gh.EXE"));
    // A "git" in the current directory is not found through "." or an empty entry.
    const planted = buildPath(getcwd, "git");
    assert(!planted.exists);
    write(planted, "");
    setAttributes(planted, octal!755);
    const found = findProgram("git", ".::git", [""], false);
    remove(planted);
    assert(found is null);
}

unittest
{
    // Valid text is unchanged; each maximal subpart of an ill-formed sequence becomes one U+FFFD.
    assert(lossyUtf8("a\u00e9\U0001F600") == "a\u00e9\U0001F600");
    assert(lossyUtf8("B\xFF.java") == "B\uFFFD.java");
    assert(lossyUtf8("\xE2\x82a") == "\uFFFDa"); // truncated 3-byte sequence
    assert(lossyUtf8("\xC0\xAF") == "\uFFFD\uFFFD"); // overlong: both bytes invalid on their own
    assert(lossyUtf8("\xED\xA0\x80") == "\uFFFD\uFFFD\uFFFD"); // surrogate
    assert(lossyUtf8("\xF0\x9F\x98") == "\uFFFD"); // truncated 4-byte sequence
}

unittest
{
    // The newest commit adds a file whose name, and whose author's e-mail, are not valid UTF-8 (byte 0xFF). They are
    // read with U+FFFD in place of the bad byte, and the older history after them is still read.
    import std.file : mkdirRecurse, rmdirRecurse, tempDir;
    import std.path : buildPath;
    import std.process : Config, execute;
    import std.uuid : randomUUID;
    const dir = buildPath(tempDir, "codecity-git-" ~ randomUUID.toString);
    mkdirRecurse(dir);
    scope (exit) rmdirRecurse(dir);
    const script = `set -e
git init -q .
git config user.name t
git config user.email t@example.com
echo a > A.java
git add A.java
git -c commit.gpgsign=false commit -q -m a
echo b > "$(printf 'B\377.java')"
git add -A
git -c commit.gpgsign=false commit -q -m b --author="$(printf 'x <x\377@example.com>')"
`;
    assert(execute(["sh", "-c", script], null, Config.none, size_t.max, dir).status == 0);
    const top = realPath(dir);
    auto history = load(top);
    assert(history.length == 2);
    assert(history[buildNormalizedPath(top, "A.java")].commits == 1);
    assert(history[buildNormalizedPath(top, "B\uFFFD.java")].authors == 1);
}
