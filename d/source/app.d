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
        case "-p", "--port": o.port = value().to!ushort; break;
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
                    throw new Exception(format("--history needs a number of commits from %d to %d", minSamples, maxSamples));
                o.history = n;
                break;
            }
            if (a.startsWith("-")) throw new Exception("unknown option " ~ a);
            if (o.root.length || o.hasRemote) throw new Exception("only one path may be given");
            if (!(a.exists && a.isDir) && GitHubRepo.parse(a, o.remote)) o.hasRemote = true;
            else o.root = a;
        }
    }
    if (!o.hasRemote)
    {
        const dir = o.root.length ? o.root : ".";
        if (!(dir.exists && dir.isDir)) throw new Exception("not a directory: " ~ dir);
        o.root = realPath(dir.absolutePath);
    }
    // a live-updating city has no fixed history to replay
    if (o.history && o.watch) throw new Exception("--history cannot be combined with --watch");
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
        stderr.writeln("error: ", e.msg, "\n");
        stderr.write(usage);
        return 2;
    }
    try
        return run(opts);
    catch (Exception e)
    {
        stderr.writeln("error: ", e.msg);
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
    writefln("Analysing %s …", root);
    auto model = CityModel.build(analyzer, root, opts.includeTests, opts.git, hasGithub, github, opts.history);
    printSummary(model);
    if (model.buildings.length == 0) stderr.writefln("No Java, TypeScript or Rust types found under %s", root);

    if (opts.export_.length)
    {
        exportHtml(model, opts.export_);
        writefln("Wrote %s", opts.export_.absolutePath);
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

/// Detects a GitHub remote for `root` and fetches its open pull requests. Returns false only when there is no
/// GitHub remote; if just the PR fetch fails, it still returns true (file links work) with `info.hasPulls` unset.
bool loadGithub(string root, out GitHubInfo info)
{
    if (!GitHubInfo.detect(root, info)) return false;
    writefln("GitHub: %s — fetching open pull requests …", info.repo.webUrl);
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
