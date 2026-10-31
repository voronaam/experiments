/// CodeCity — explore a Java, TypeScript or Rust code base as an interactive 3D city (D edition).
///
/// Entry point: parses the command line, optionally clones a GitHub repository, builds the city model once and then
/// hands it to one of three outputs: a self-contained HTML file (`--export`), the native desktop window (`--gui`,
/// only in the `gui` dub configuration) or the local web server. The model itself is built by
/// `CityModel.build` (walk → parse/analyse → link dependencies → git history → layout); see `codecity.city`.
///
/// With `--watch`, a background thread re-runs that build whenever sources change and swaps the new model into the
/// server or window. The same `SourceAnalyzer` is reused for every rebuild, so only changed files are parsed again.
///
/// With `--history[=N]`, the build also replays the code base at N sampled commits (`codecity.history`), so the
/// viewers can show the city growing; this is a one-off computation and cannot be combined with `--watch`.
module app;

import codecity.analysis : SourceAnalyzer;
import codecity.city : CityModel;
import codecity.git : realPath;
import codecity.github;
import codecity.history : defaultSamples, maxSamples, minSamples;
import codecity.htmlexport : exportHtml;
import codecity.model : displayName;
import codecity.server : CityServer;
import codecity.watch : watch;
import std.algorithm : map, startsWith;
import std.array : join;
import std.conv : to;
import std.file : exists, isDir;
import std.format : format;
import std.path : absolutePath;
import std.process : browse;
import std.stdio : stderr, write, writefln, writeln;

version (CodeCityGui) import codecity.gui.app : runGui;

enum usage = `CodeCity — explore a Java, TypeScript or Rust code base as an interactive 3D city

Usage: codecity [options] [path | github-url]

  path              source tree to analyse (default: current directory)
  github-url        clone and analyse a GitHub repository, e.g.
                      https://github.com/google/gson
                      https://github.com/owner/repo/tree/branch
                      github:owner/repo[@branch]
  -g, --gui         open a native desktop window instead of the browser viewer
  -p, --port N      port for the local viewer (default: 7070)
  -w, --watch       re-analyse and live-update the viewer when files change
  --no-tests        leave test code out of the city
  --no-git          skip mining git history (churn, authors, recency)
  --no-github       no GitHub links or pull-request overlay
  --history[=N]     replay the city's growth over N sampled commits (default 60)
  --no-open         don't open a browser
  --export FILE     write a self-contained HTML file and exit
  -h, --help        show this help

Metaphor: packages / directories / modules are districts; classes, interfaces, structs,
traits and enums are buildings (plus one "module" building per file of free functions).
  height    = number of methods     (switchable: LOC, complexity, attributes)
  footprint = number of attributes
  colour    = git churn / complexity / recency / authors / open PRs / type kind

GitHub: repositories with a GitHub remote get links to files, blame and history, and an
overlay of the open pull requests touching each class. Set GH_TOKEN (or log in with the
gh CLI) to fetch every open PR; without a token only the most recent ~30 are loaded.
`;

/// The parsed command line. Exactly one of `root` (a local directory) and `remote` (`hasRemote`) is the source.
struct Options
{
    string root;
    bool hasRemote;
    GitHubRepo remote;
    ushort port = 7070;
    bool watch, includeTests = true, git = true, github = true, open = true, gui;
    string export_;
    /// Number of commits to sample for the history replay; 0 when `--history` was not given.
    uint history;
}

/// Parses `args` (without the program name) into `o`. Returns false when help was requested; throws with a
/// user-facing message on a bad option or path.
///
/// A positional argument is taken as a GitHub repository only when it is not an existing directory, so a local
/// directory always wins over a URL-like name. A local root is made absolute and canonical (symlinks resolved), so
/// that the analysed file paths match the keys `codecity.git.load` builds from `git rev-parse --show-toplevel` and
/// the prefix `GitHubInfo.detect` computes.
///
/// `--history` takes its optional value only in the attached form `--history=N`, never from the next argument, so
/// a path after it is not mistaken for the count.
bool parseArgs(string[] args, out Options o)
{
    o = Options.init;
    for (size_t i = 0; i < args.length; i++)
    {
        const a = args[i];
        // The option's argument: consumes the next element, so the loop skips it.
        string value()
        {
            if (++i >= args.length) throw new Exception(a ~ " needs a value");
            return args[i];
        }
        switch (a)
        {
        case "-h", "--help": return false;
        case "-p", "--port":
            // Any number from 0 (any free port) to 65535; a word or a negative number is reported like in the
            // other editions rather than with the conversion's own message.
            import std.conv : ConvException;
            try o.port = value().to!ushort;
            catch (ConvException) throw new Exception("invalid port");
            break;
        case "-w", "--watch": o.watch = true; break;
        case "-g", "--gui": o.gui = true; break;
        case "--no-tests": o.includeTests = false; break;
        case "--no-git": o.git = false; break;
        case "--no-github": o.github = false; break;
        case "--no-open": o.open = false; break;
        case "--export": o.export_ = value(); break;
        case "--history": o.history = defaultSamples; break;
        default:
            if (a.startsWith("--history="))
            {
                import std.conv : ConvException;
                uint n;
                try n = a["--history=".length .. $].to!uint;
                catch (ConvException) n = 0;
                if (n < minSamples || n > maxSamples)
                    throw new Exception(format("--history needs a number of snapshots from %d to %d", minSamples, maxSamples));
                o.history = n;
                break;
            }
            if (a.startsWith("-")) throw new Exception("unknown option " ~ a);
            if (o.root.length || o.hasRemote) throw new Exception("only one path may be given");
            if (!(a.exists && a.isDir) && GitHubRepo.parse(a, o.remote)) o.hasRemote = true;
            else o.root = a;
        }
    }
    // A live-updating city has no fixed history to replay. Checked before the path, as in the other editions, so
    // both mistakes at once report this one.
    if (o.history && o.watch) throw new Exception("--history cannot be combined with --watch");
    if (!o.hasRemote)
    {
        const dir = o.root.length ? o.root : ".";
        if (!(dir.exists && dir.isDir)) throw new Exception("not a directory: " ~ dir);
        o.root = realPath(dir.absolutePath);
    }
    return true;
}

/// Exit codes: 0 on success or `--help`, 2 for a command-line error (with the usage text), 1 for any other error.
int main(string[] args)
{
    Options opts;
    try
    {
        if (!parseArgs(args[1 .. $], opts))
        {
            write(usage);
            return 0;
        }
    }
    catch (Exception e)
    {
        stderr.writeln("error: ", printable(e.msg), "\n");
        stderr.write(usage);
        return 2;
    }
    try
        return run(opts);
    catch (Exception e)
    {
        stderr.writeln("error: ", printable(e.msg));
        return 1;
    }
}

/// Builds the city and runs the chosen output. Blocks forever when serving (the server never returns).
int run(Options opts)
{
    // A remote repository is cloned into (or updated in) the cache, then analysed like any local directory.
    if (opts.hasRemote) opts.root = realPath(cloneOrUpdate(opts.remote));
    const root = opts.root;
    // GitHub context (remote, permalink ref, open PRs) is fetched once up front and reused by every rebuild.
    GitHubInfo github;
    const hasGithub = opts.github && loadGithub(root, github);

    auto analyzer = new SourceAnalyzer;
    writefln("Analysing %s …", printable(root));
    auto model = CityModel.build(analyzer, root, opts.includeTests, opts.git, hasGithub, github, opts.history);
    printSummary(model);
    if (model.buildings.length == 0) stderr.writefln("No Java, TypeScript or Rust types found under %s", printable(root));

    if (opts.export_.length)
    {
        // Name the file as the user gave it, like the Java and Rust editions ("cannot write FILE: reason"); a
        // FileException's own message would repeat the name.
        import std.file : FileException;
        try exportHtml(model, opts.export_);
        catch (FileException e)
        {
            import core.stdc.string : strerror;
            import std.string : fromStringz;
            throw new Exception("cannot write " ~ opts.export_ ~ ": "
                ~ (e.errno ? strerror(e.errno).fromStringz.idup : e.msg));
        }
        writefln("Wrote %s", printable(opts.export_.absolutePath));
        return 0;
    }

    // Runs on the watcher's thread (in both server and GUI mode); reuses the analyser and its parse cache.
    CityModel rebuild()
    {
        auto next = CityModel.build(analyzer, root, opts.includeTests, opts.git, hasGithub, github);
        writefln("↻ rebuilt city: %d types in %d ms", next.buildings.length, next.buildMillis);
        return next;
    }

    if (opts.gui)
    {
        version (CodeCityGui)
        {
            writeln("Opening the CodeCity window …");
            return runGui(model, opts.watch ? root : null, &rebuild);
        }
        else
            throw new Exception("this build has no desktop window (built with the `cli` configuration)");
    }

    auto server = new CityServer(model, opts.port);
    version (unittest) synchronized startedServers ~= server;
    writefln("CodeCity is live at %s  (Ctrl+C to stop)", server.url);
    if (opts.watch)
    {
        watch(root, { server.update(rebuild()); });
        writeln("Watching for changes …");
    }
    // Failing to launch a browser is not an error: the URL has been printed above.
    if (opts.open)
        try browse(server.url);
        catch (Exception) {}
    server.run();
    return 0;
}

/// Test-only seam: every server `run` starts, so that the end-to-end tests can close the servers they started (in the
/// program, `run` serves until the process ends and never returns).
version (unittest) private __gshared CityServer[] startedServers;

/// `s` with every control character (C0, DEL and C1) other than newline and tab replaced by '?', for printing text
/// that can come from the analysed repository: file names, git output and API errors in exception messages, the
/// GitHub remote from its git config. An ESC or CSI there would otherwise reach the terminal as an escape sequence
/// (colours, cursor moves, retitling the window, writing the clipboard), and a CR could overwrite a line. Invalid
/// UTF-8 is printed as U+FFFD.
string printable(const(char)[] s)
{
    import std.algorithm : map;
    import std.conv : to;
    import std.uni : isControl;
    import std.utf : byDchar;
    return s.byDchar.map!(c => isControl(c) && c != '\n' && c != '\t' ? dchar('?') : c).to!string;
}

unittest
{
    assert(printable("a\x1b[31mred\x1b]0;title\x07\rb\n\tc\u009bd") == "a?[31mred?]0;title??b\n\tc?d");
    assert(printable("ünïcödé ✓ %s %n") == "ünïcödé ✓ %s %n");
}

/// Detects a GitHub remote for `root` and fetches its open pull requests. Returns false only when there is no
/// GitHub remote; if just the PR fetch fails, it still returns true (file links work) with `info.hasPulls` unset.
bool loadGithub(string root, out GitHubInfo info)
{
    if (!GitHubInfo.detect(root, info)) return false;
    writefln("GitHub: %s — fetching open pull requests …", printable(info.repo.webUrl));
    // A failed fetch should not look like "no open PRs": leave the PR features off instead.
    PullRequest[] pulls;
    string source;
    if (fetchPulls(info.repo, pulls, source))
    {
        writefln("  loaded %s", source);
        info.hasPulls = true;
        info.pulls = pulls;
        info.pullsSource = source;
    }
    return true;
}

/// One line of totals after a build, e.g. `120 files, 340 types (Java 300, TypeScript 40), 25 packages — 812 ms`.
void printSummary(CityModel model)
{
    auto a = &model.analysis;
    auto s = &model.stats;
    const languages = s.languages.map!(l => format("%s %d", l.language.displayName, l.count)).join(", ");
    // `s.types`, not `a.types.length`: with the history replay the analysis also holds types that are gone today
    writefln("  %d files, %d types%s, %d packages%s%s — %d ms", a.files, s.types,
        languages.length ? " (" ~ languages ~ ")" : "", s.packages,
        a.failedFiles ? format(", %d with parse errors", a.failedFiles) : "",
        a.history.length ? format(", git history for %d files", a.history.length) : "",
        model.buildMillis);
}

unittest
{
    // `--history` alone uses the default; `--history=N` must be within 2..500; the value is never taken from the
    // next argument, so a path after `--history` stays the path; `--history` with `--watch` is refused.
    Options o;
    assert(parseArgs(["--history", "."], o) && o.history == defaultSamples && o.root.length);
    assert(parseArgs(["--history=2"], o) && o.history == 2);
    assert(parseArgs(["--history=500"], o) && o.history == 500);
    assert(parseArgs([], o) && o.history == 0);
    bool fails(string[] args...)
    {
        try parseArgs(args.dup, o);
        catch (Exception e) return true;
        return false;
    }
    assert(fails("--history=1"));
    assert(fails("--history=501"));
    assert(fails("--history=x"));
    assert(fails("--history", "60"));
    assert(fails("--history", "--watch"));
    assert(fails("-w", "--history=10"));
}

version (unittest)
{
    import std.path : buildNormalizedPath, buildPath, dirName;

    /// The repository's `testdata` directory, found from this source file (d/source/app.d).
    private string testdata()
    {
        return buildNormalizedPath(__FILE_FULL_PATH__.dirName, "..", "..", "testdata");
    }

    /// The usage text, the same for all three editions (`testdata/cli/usage.txt`).
    private string sharedUsage()
    {
        import std.file : readText;
        return readText(buildPath(testdata, "cli", "usage.txt"));
    }

    /// A fresh temporary directory for one test; the caller removes it.
    private string tempDirectory()
    {
        import std.file : mkdirRecurse, tempDir;
        import std.uuid : randomUUID;
        const d = buildPath(tempDir, "codecity-cli-test-" ~ randomUUID.toString);
        mkdirRecurse(d);
        return realPath(d);
    }

    /// What a finished run left: its exit code and everything it printed.
    private struct Result
    {
        int code;
        string out_, err;
    }

    /// Runs `main` in this process with stdout and stderr redirected to files in `dir`, as the Java and Rust editions
    /// run the program as a child process. The global `stdout`/`stderr` are swapped and restored afterwards.
    private Result runMain(string dir, string[] args...)
    {
        import std.file : readText;
        import std.stdio : File, stderr, stdout;
        import std.uuid : randomUUID;
        const o = buildPath(dir, "out-" ~ randomUUID.toString), e = o ~ ".err";
        auto savedOut = stdout, savedErr = stderr;
        stdout = File(o, "w");
        stderr = File(e, "w");
        int code;
        {
            scope (exit)
            {
                stdout.close();
                stderr.close();
                stdout = savedOut;
                stderr = savedErr;
            }
            code = main(["codecity"] ~ args.dup);
        }
        return Result(code, readText(o), readText(e));
    }

    /// The message of the usage error `args` cause.
    private string usageError(string[] args...)
    {
        Options o;
        try parseArgs(args.dup, o);
        catch (Exception e) return e.msg;
        assert(false, "no error for " ~ args.to!string);
    }
}

/// Without options: port 7070, tests, git, GitHub and the browser on; no watch, export, window or history. Every flag,
/// in its short and long form, turns on or off exactly its own setting. The Java (`CodeCityTest`) and Rust (`main.rs`)
/// editions have the same tests.
unittest
{
    import std.file : rmdirRecurse;
    const d = tempDirectory();
    scope (exit) rmdirRecurse(d);
    Options o;
    assert(parseArgs([d], o));
    assert(o.root == d && !o.hasRemote && o.port == 7070);
    assert(o.includeTests && o.git && o.github && o.open && !o.watch && !o.gui);
    assert(o.export_ is null && o.history == 0);

    assert(parseArgs(["--no-tests", "--no-git", "--no-github", "--no-open", d], o));
    assert(!o.includeTests && !o.git && !o.github && !o.open && !o.watch && !o.gui);
    assert(parseArgs(["-w", d], o) && o.watch);
    assert(parseArgs(["--watch", d], o) && o.watch);
    assert(parseArgs(["-g", d], o) && o.gui);
    assert(parseArgs(["--gui", d], o) && o.gui);
    assert(parseArgs(["--no-tests", d], o) && !o.includeTests && o.git);
    assert(parseArgs(["--export", "out.html", d], o) && o.export_ == "out.html");
    // Options may come after the path, too.
    assert(parseArgs([d, "--export", "x.html"], o) && o.export_ == "x.html");
}

/// `-p/--port` takes 0 (any free port) to 65535; anything else is "invalid port". Options that need a value say so.
unittest
{
    import std.file : rmdirRecurse;
    const d = tempDirectory();
    scope (exit) rmdirRecurse(d);
    Options o;
    assert(parseArgs(["-p", "8080", d], o) && o.port == 8080);
    assert(parseArgs(["--port", "0", d], o) && o.port == 0);
    assert(parseArgs(["--port", "65535", d], o) && o.port == 65_535);
    foreach (bad; ["65536", "-1", "abc", "", "80.5", "99999999999"])
        assert(usageError("-p", bad, d) == "invalid port", bad);
    assert(usageError(d, "-p") == "-p needs a value");
    assert(usageError(d, "--port") == "--port needs a value");
    assert(usageError(d, "--export") == "--export needs a value");
}

/// Unknown options, a second path and a path that is not a directory are refused with a message; `-h`/`--help` before
/// an error asks for the usage text; a GitHub spec that is not an existing directory is a repository to clone.
unittest
{
    import std.file : rmdirRecurse, write;
    const d = tempDirectory();
    scope (exit) rmdirRecurse(d);
    assert(usageError("--bogus", d) == "unknown option --bogus");
    assert(usageError(d, "-x") == "unknown option -x");
    assert(usageError("--history-x", d) == "unknown option --history-x");
    assert(usageError(d, d) == "only one path may be given");
    const file = buildPath(d, "file.txt");
    write(file, "x");
    assert(usageError(file) == "not a directory: " ~ file);
    assert(usageError(buildPath(d, "missing")) == "not a directory: " ~ buildPath(d, "missing"));
    // Both mistakes at once: the option conflict is reported first, as in the other editions.
    assert(usageError("--history", "-w", file) == "--history cannot be combined with --watch");

    Options o;
    assert(!parseArgs(["-h"], o));
    assert(!parseArgs(["--port", "1", d, "--help", "--bogus"], o));
    assert(parseArgs(["github:owner/repo@dev"], o));
    assert(o.hasRemote && o.root is null);
    assert(o.remote.owner == "owner" && o.remote.name == "repo" && o.remote.branch == "dev");
}

/// The whole program: `-h`/`--help` print the usage text to stdout and exit with 0; a bad command line exits with 2
/// and prints one `error:` line, a blank line and the usage text to stderr; control characters in a message are
/// printed as '?'. The Java (`CodeCityCliTest`) and Rust (`tests/cli.rs`) editions run the same cases as child
/// processes.
unittest
{
    import std.algorithm : canFind;
    import std.file : rmdirRecurse, write;
    const d = tempDirectory();
    scope (exit) rmdirRecurse(d);
    assert(usage == sharedUsage);
    foreach (h; ["-h", "--help"])
        assert(runMain(d, h) == Result(0, sharedUsage, ""));

    const file = buildPath(d, "file.txt");
    write(file, "x");
    const string[][string] cases = [
        "unknown option --bogus": ["--bogus", d],
        "only one path may be given": [d, d],
        "not a directory: " ~ file: [file],
        "-p needs a value": [d, "-p"],
        "invalid port": ["--port", "x", d],
        "--history needs a number of snapshots from 2 to 500": ["--history=1", d],
        "--history cannot be combined with --watch": ["--history", "--watch", d],
    ];
    foreach (message, args; cases)
        assert(runMain(d, args.dup) == Result(2, "", "error: " ~ message ~ "\n\n" ~ sharedUsage), message);

    const r = runMain(d, buildPath(d, "x\x1b]0;pwned\x07\x1b[31m"));
    assert(r.code == 2);
    assert(r.err.startsWith("error: not a directory: " ~ buildPath(d, "x?]0;pwned??[31m") ~ "\n"), r.err);
    assert(!r.err.canFind('\x1b') && !r.err.canFind('\x07'), r.err);
}

/// `--export` analyses, prints the summary line and the absolute path written, exits with 0 and leaves a
/// self-contained page: the Content-Security-Policy filled in and the city JSON inlined. `--no-tests` leaves the test
/// classes out of that city. A file that cannot be written is a run failure: exit 1, one sanitised `error:` line.
unittest
{
    import std.algorithm : canFind, count, endsWith;
    import std.array : array;
    import std.file : mkdirRecurse, readText, rmdirRecurse;
    import std.string : lineSplitter;
    const d = tempDirectory();
    scope (exit) rmdirRecurse(d);
    const project = buildPath(testdata, "java-project"), o = buildPath(d, "sub", "city.html");
    auto r = runMain(d, "--export", o, "--no-git", "--no-github", "--no-open", project);
    assert(r.code == 0 && r.err == "", r.err);
    auto lines = r.out_.lineSplitter.array;
    assert(lines.length == 3, r.out_);
    assert(lines[0] == "Analysing " ~ realPath(project) ~ " …", lines[0]);
    assert(lines[1].startsWith("  13 files, 20 types (Java 20), 4 packages, 1 with parse errors — "), lines[1]);
    assert(lines[1].endsWith(" ms"), lines[1]);
    assert(lines[2] == "Wrote " ~ o, lines[2]);
    auto html = readText(o);
    assert(html.canFind(`<meta http-equiv="Content-Security-Policy"`));
    assert(html.canFind("'sha256-") && !html.canFind("{{inline-script-hashes}}"));
    assert(html.canFind(`<script id="city-data" type="application/json">{`));
    assert(html.canFind(`"com.acme.core.CircleTest"`));

    r = runMain(d, "--export", o, "--no-tests", "--no-git", "--no-github", project);
    assert(r.code == 0, r.err);
    assert(r.out_.canFind("  13 files, 17 types (Java 17), 4 packages, 1 with parse errors — "), r.out_);
    html = readText(o);
    assert(!html.canFind(`"com.acme.core.CircleTest"`) && html.canFind(`"com.acme.core.Circle"`));

    const dir = buildPath(d, "out\x1b[31m.html");
    mkdirRecurse(dir);
    r = runMain(d, "--export", dir, "--no-git", "--no-github", project);
    assert(r.code == 1);
    assert(r.err.startsWith("error: cannot write " ~ buildPath(d, "out?[31m.html") ~ ": Is a directory"), r.err);
    assert(r.err.count('\n') == 1, r.err);
}

/// `--no-git` skips the history (no "git history" in the summary), and `--no-github` skips the GitHub remote (no
/// "GitHub:" line and no network) even though the repository has one.
unittest
{
    import std.algorithm : canFind;
    import std.file : rmdirRecurse;
    import std.process : execute;
    const d = tempDirectory();
    scope (exit) rmdirRecurse(d);
    const repo = buildPath(d, "repo"), o = buildPath(d, "city.html");
    const made = execute(["sh", buildPath(testdata, "make-history-repo.sh"), repo]);
    assert(made.status == 0, made.output);
    assert(execute(["git", "-C", repo, "remote", "add", "origin", "https://github.com/owner/repo.git"]).status == 0);

    auto r = runMain(d, "--export", o, "--no-github", repo);
    assert(r.code == 0, r.err);
    assert(r.out_.canFind(", git history for 6 files — "), r.out_);
    assert(!r.out_.canFind("GitHub"), r.out_);

    r = runMain(d, "--export", o, "--no-git", "--no-github", repo);
    assert(r.code == 0, r.err);
    assert(!r.out_.canFind("git history") && !r.out_.canFind("GitHub"), r.out_);
}

/// `--gui` in a build without the desktop window (the `cli` configuration, and the unittest one) is a run failure with
/// a clear message. (The Java edition always has a window and fails without a display instead; the Rust edition has
/// the same rule without its `gui` feature.)
version (CodeCityGui) {} else unittest
{
    import std.file : rmdirRecurse;
    const d = tempDirectory();
    scope (exit) rmdirRecurse(d);
    const r = runMain(d, "--gui", "--no-git", "--no-github", buildPath(testdata, "java-project"));
    assert(r.code == 1);
    assert(r.err == "error: this build has no desktop window (built with the `cli` configuration)\n", r.err);
}

/// Server mode: prints the URL (with the port it got, here one chosen by the system for `-p 0`), serves the city there
/// and opens the browser, unless `--no-open`. The browser launcher is replaced by a script on the PATH that records its
/// argument. Where the Java and Rust editions kill their child process, this test closes the servers it started.
version (linux) unittest
{
    import core.stdc.stdio : _IONBF;
    import core.thread : Thread;
    import core.time : MonoTime, msecs, seconds;
    import std.algorithm : canFind, findSplitAfter, startsWith;
    import std.conv : octal;
    import std.file : exists, mkdirRecurse, readText, remove, rmdirRecurse, setAttributes, write;
    import std.process : environment;
    import codecity.server : receiveRetrying;
    import std.socket : InternetAddress, SocketOption, SocketOptionLevel, TcpSocket;
    import std.stdio : File, stdout;
    import std.string : indexOf;
    import std.uuid : randomUUID;

    const d = tempDirectory();
    scope (exit) rmdirRecurse(d);
    // Stops the servers started below, so that their threads end with the test.
    scope (exit)
        synchronized foreach (s; startedServers) s.close();
    const bin = buildPath(d, "bin"), opened = buildPath(d, "opened.txt"), launcher = buildPath(bin, "xdg-open");
    mkdirRecurse(bin);
    write(launcher, "#!/bin/sh\necho \"$1\" >> '" ~ opened ~ "'\n");
    setAttributes(launcher, octal!755);
    const project = buildPath(testdata, "java-project");

    const savedPath = environment.get("PATH"), savedBrowser = environment.get("BROWSER");
    environment["PATH"] = bin ~ ":" ~ savedPath;
    environment.remove("BROWSER");
    scope (exit)
    {
        environment["PATH"] = savedPath;
        if (savedBrowser !is null) environment["BROWSER"] = savedBrowser;
    }

    // Starts main in server mode on a daemon thread with stdout going (unbuffered) to a file, waits for the "live"
    // line, checks that the printed URL serves the city and returns the URL.
    string serve(string[] args...)
    {
        const o = buildPath(d, "serve-" ~ randomUUID.toString);
        auto saved = stdout;
        stdout = File(o, "w");
        stdout.setvbuf(0, _IONBF);
        scope (exit) stdout = saved;
        auto argv = ["codecity"] ~ args.dup;
        auto t = new Thread({ main(argv); });
        t.isDaemon = true;
        t.start();
        const end = MonoTime.currTime + 30.seconds;
        string url;
        while (MonoTime.currTime < end)
        {
            auto rest = readText(o).findSplitAfter("CodeCity is live at ");
            if (rest[0].length && rest[1].canFind("  (Ctrl+C to stop)\n"))
            {
                url = rest[1][0 .. rest[1].indexOf("  (Ctrl+C")];
                break;
            }
            Thread.sleep(20.msecs);
        }
        assert(url.startsWith("http://localhost:"), "the server never said it was live");
        const port = url["http://localhost:".length .. $ - 1].to!ushort;
        assert(port > 0);
        auto s = new TcpSocket(new InternetAddress("127.0.0.1", port));
        scope (exit) s.close();
        s.setOption(SocketOptionLevel.SOCKET, SocketOption.RCVTIMEO, 20.seconds); // a stuck server fails the test
        s.send(format("GET /api/city HTTP/1.1\r\nHost: localhost:%d\r\n\r\n", port));
        char[65_536] buf;
        string r;
        for (ptrdiff_t n; (n = receiveRetrying(s, buf[])) > 0;) r ~= buf[0 .. n].idup;
        assert(r.startsWith("HTTP/1.1 200"), r);
        assert(r.canFind(`"com.acme.core.Circle"`));
        return url;
    }

    const url = serve("-p", "0", "--no-git", "--no-github", project);
    const end = MonoTime.currTime + 20.seconds;
    while (!(opened.exists && readText(opened) == url ~ "\n") && MonoTime.currTime < end) Thread.sleep(50.msecs);
    assert(readText(opened) == url ~ "\n");

    remove(opened);
    serve("-p", "0", "--no-open", "--no-git", "--no-github", project);
    // The browser would be launched right after the "live" line; give it ample time not to happen.
    Thread.sleep(1.seconds);
    assert(!opened.exists, "--no-open opened a browser");
}
