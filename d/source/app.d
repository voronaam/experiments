/// CodeCity — explore a Java, TypeScript or Rust code base as an interactive 3D city (D edition).
module app;

import codecity.analysis : SourceAnalyzer;
import codecity.city : CityModel;
import codecity.git : realPath;
import codecity.github;
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

struct Options
{
    string root;
    bool hasRemote;
    GitHubRepo remote;
    ushort port = 7070;
    bool watch, includeTests = true, git = true, github = true, open = true, gui;
    string export_;
}

/// false: help was requested.
bool parseArgs(string[] args, out Options o)
{
    o = Options.init;
    for (size_t i = 0; i < args.length; i++)
    {
        const a = args[i];
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
        default:
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
    return true;
}

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

int run(Options opts)
{
    if (opts.hasRemote) opts.root = realPath(cloneOrUpdate(opts.remote));
    const root = opts.root;
    GitHubInfo github;
    const hasGithub = opts.github && loadGithub(root, github);

    auto analyzer = new SourceAnalyzer;
    writefln("Analysing %s …", root);
    auto model = CityModel.build(analyzer, root, opts.includeTests, opts.git, hasGithub, github);
    printSummary(model);
    if (model.buildings.length == 0) stderr.writefln("No Java, TypeScript or Rust types found under %s", root);

    if (opts.export_.length)
    {
        exportHtml(model, opts.export_);
        writefln("Wrote %s", opts.export_.absolutePath);
        return 0;
    }

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
    if (opts.open)
        try browse(server.url);
        catch (Exception) {}
    server.run();
    return 0;
}

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

void printSummary(CityModel model)
{
    auto a = &model.analysis;
    auto s = &model.stats;
    const languages = s.languages.map!(l => format("%s %d", l.language.displayName, l.count)).join(", ");
    writefln("  %d files, %d types%s, %d packages%s%s — %d ms", a.files, a.types.length,
        languages.length ? " (" ~ languages ~ ")" : "", s.packages,
        a.failedFiles ? format(", %d with parse errors", a.failedFiles) : "",
        a.history.length ? format(", git history for %d files", a.history.length) : "",
        model.buildMillis);
}
