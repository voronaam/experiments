/// An analysed and laid-out city, consumed by both the web viewer (as JSON) and the desktop viewer.
///
/// `CityModel.build` runs the whole pipeline for one source tree: walk and parse the sources and link their
/// dependencies (`codecity.analysis`), mine git history in parallel (`codecity.git`), lay the city out
/// (`codecity.layout`), then join everything per type into `Building`s. A rebuild (`--watch`) does not modify a
/// model: it builds a new one, which replaces the old one as a whole.
///
/// Indices: `Building.index`, `Building.deps`/`incoming` and the JSON `deps` all index `analysis.types` (which is
/// sorted by fqn) and `buildings` alike, because `buildings[i]` is built for `analysis.types[i]`.
module codecity.city;

import codecity.analysis : SourceAnalyzer;
import codecity.git : load;
import codecity.github : GitHubInfo;
import codecity.json : JsonWriter;
import codecity.layout;
import codecity.model;
import core.thread : Thread;
import std.algorithm : sort, SwapStrategy;
import std.array : replace;
import std.datetime.stopwatch : StopWatch, AutoStart;
import std.datetime.systime : Clock;
import std.path : baseName, relativePath;

/// At most this many methods per building are kept (the most complex ones), to bound the JSON size.
enum maxMethods = 300;

/// One building, fully resolved: the type, where it stands, its history and its edges.
struct Building
{
    /// Index into `Analysis.types` (equal to this building's own index in `CityModel.buildings`).
    size_t index;
    Lot lot;
    /// Path relative to the analysed root, with forward slashes.
    string file;
    bool hasHistory;
    /// Only meaningful when `hasHistory`.
    FileHistory history;
    /// Types this one uses / types that use this one, as indices into `Analysis.types`.
    size_t[] deps;
    size_t[] incoming;
    /// Indices into the GitHub info's pull requests.
    size_t[] prs;
    /// Methods sorted by complexity, then size (capped).
    MethodInfo[] methods;
}

struct LanguageCount
{
    Language language;
    size_t count;
}

/// Totals for the whole city, sent to the viewers and printed after each build.
struct Stats
{
    /// `packages` counts distinct (language, module, package) triples, not districts.
    size_t files, failedFiles, types, packages;
    uint methods, loc;
    /// Type count per language, most common first.
    LanguageCount[] languages;
}

/// The complete city: analysis results, layout and GitHub context, ready to be served or drawn.
final class CityModel
{
    string name;
    Analysis analysis;
    District[] districts;
    double width, depth;
    bool hasGithub;
    GitHubInfo github;
    Building[] buildings;
    Stats stats;
    long buildMillis;
    /// Lazily built JSON form; see `json`.
    private string cachedJson;

    /// Runs the full pipeline for `root`. `useGit` enables history mining; `github` (valid when `hasGithub`) was
    /// fetched once by the caller and is copied into every model, so rebuilds do not query GitHub again.
    static CityModel build(SourceAnalyzer analyzer, string root, bool includeTests, bool useGit, bool hasGithub, GitHubInfo github)
    {
        auto sw = StopWatch(AutoStart.yes);
        FileHistory[string] history;
        // git log runs while the sources are parsed. The thread writes `history` (captured by the closure); join()
        // makes that write visible here before it is read.
        auto gitThread = new Thread({ if (useGit) history = load(root); });
        gitThread.start();
        auto analysis = analyzer.analyze(root, includeTests);
        gitThread.join();
        analysis.history = history;

        auto c = new CityModel;
        c.name = root.baseName;
        c.analysis = analysis;
        auto l = layout(c.name, c.analysis.types);
        c.districts = l.districts;
        c.width = l.width;
        c.depth = l.depth;
        c.hasGithub = hasGithub;
        c.github = github;
        c.buildings = resolve(c.analysis, l.lots, hasGithub ? &c.github : null);
        c.stats = computeStats(c.analysis);
        c.buildMillis = sw.peek.total!"msecs";
        return c;
    }

    ref const(TypeDecl) type(size_t i) const { return analysis.types[i]; }

    bool hasGit() const { return analysis.history.length > 0; }

    bool hasPulls() const { return hasGithub && github.hasPulls; }

    /// The model as served to the web viewer; computed on first use.
    ///
    /// Called from the server's connection threads at the same time, so the cache is read and written under the
    /// model's monitor: the first caller builds the string, later ones wait for it and then share it.
    string json()
    {
        synchronized (this)
        {
            if (cachedJson is null) cachedJson = toJson();
            return cachedJson;
        }
    }

    /// Serialises the model in the format the web viewer (`app.js`) reads. Coordinates and sizes go through
    /// `JsonWriter.number`, which rounds them to 3 decimals.
    private string toJson()
    {
        auto w = JsonWriter.create();
        w.beginObject()
            .field("name", name)
            .field("root", analysis.root)
            // ISO 8601 in UTC to whole seconds: "YYYY-MM-DDTHH:MM:SS" is 19 characters, then "Z".
            .field("generatedAt", Clock.currTime.toUTC.toISOExtString[0 .. 19] ~ "Z")
            .fieldBool("hasGit", hasGit)
            .fieldNum("platform", platform)
            .fieldNum("width", width)
            .fieldNum("depth", depth);
        w.name("stats").beginObject()
            .field("files", stats.files)
            .field("failedFiles", stats.failedFiles)
            .field("types", stats.types)
            .field("packages", stats.packages)
            .field("methods", stats.methods)
            .field("loc", stats.loc);
        w.name("languages").beginObject();
        foreach (lc; stats.languages) w.field(lc.language.id, lc.count);
        w.endObject().endObject();

        if (hasGithub)
        {
            w.name("github").beginObject()
                .field("host", github.repo.host)
                .field("owner", github.repo.owner)
                .field("repo", github.repo.name)
                .field("url", github.repo.webUrl)
                .field("ref", github.gitRef)
                .field("prefix", github.prefix);
            if (github.hasPulls)
            {
                w.field("pullsSource", github.pullsSource);
                w.name("pulls").beginArray();
                foreach (pr; github.pulls)
                {
                    w.beginObject()
                        .field("number", pr.number)
                        .field("title", pr.title)
                        .field("url", pr.url)
                        .name("author").nullable(pr.author)
                        .field("updatedAt", pr.updatedAt)
                        .fieldBool("draft", pr.draft)
                        .field("files", pr.files.length)
                        .endObject();
                }
                w.endArray();
            }
            w.endObject();
        }

        w.name("districts").beginArray();
        foreach (d; districts)
        {
            w.beginObject()
                .field("id", d.id)
                .field("name", d.label)
                .field("depth", d.depth)
                .fieldNum("x", d.x).fieldNum("z", d.z)
                .fieldNum("w", d.w).fieldNum("d", d.d)
                .field("types", d.types)
                .endObject();
        }
        w.endArray();

        w.name("buildings").beginArray();
        foreach (ref b; buildings)
        {
            auto t = &analysis.types[b.index];
            w.beginObject()
                .field("id", t.fqn)
                .field("lang", t.language.id)
                .field("name", t.name)
                .field("pkg", t.pkg)
                .field("module", t.module_)
                .field("kind", t.kind)
                .fieldBool("test", t.test)
                .field("file", b.file)
                .field("line", t.line)
                .field("endLine", t.endLine)
                .fieldNum("x", b.lot.x).fieldNum("z", b.lot.z).fieldNum("y", b.lot.y)
                .fieldNum("side", b.lot.side)
                // Index into the "districts" array above (pre-order, as produced by the layout).
            .field("district", b.lot.district)
                .field("nom", t.nom)
                .field("noa", t.noa)
                .field("loc", t.loc)
                .field("cx", t.complexity);
            if (b.hasHistory)
            {
                w.field("commits", b.history.commits)
                    .field("authors", b.history.authors)
                    .field("last", b.history.lastCommit)
                    .field("first", b.history.firstCommit);
            }
            if (b.prs.length)
            {
                w.name("prs").beginArray();
                foreach (p; b.prs) w.value(p);
                w.endArray();
            }
            // Methods as compact tuples [name, line, loc, complexity, parameters] to keep the JSON small.
            w.name("methods").beginArray();
            foreach (m; b.methods)
                w.beginArray().value(m.name).value(m.line).value(m.loc).value(m.complexity).value(m.parameters).endArray();
            w.endArray();
            w.name("deps").beginArray();
            foreach (d; b.deps) w.value(d);
            w.endArray();
            w.endObject();
        }
        w.endArray();
        w.endObject();
        return w.finish();
    }
}

/// Joins the per-type data into `Building`s, one per `a.types` entry and in the same order: the type's lot,
/// root-relative file path, git history, dependency edges in both directions and the open PRs touching its file.
///
/// `github` is null when there is no GitHub context. PR file paths are relative to the repository root while
/// `file` is relative to the analysed root, so `GitHubInfo.prefix` (the root's path inside the repository) is put in
/// front before looking them up. Dependencies on fqns that are not in `a.types` are dropped.
private Building[] resolve(ref Analysis a, Lot[string] lots, const(GitHubInfo)* github)
{
    size_t[string] index;
    foreach (i, ref t; a.types) index[t.fqn] = i;
    // Repository-relative path → indices of the PRs that change it.
    size_t[][string] pullsByPath;
    if (github !is null && github.hasPulls)
        foreach (i, pr; github.pulls)
            foreach (f; pr.files) pullsByPath[f] ~= i;
    auto deps = new size_t[][a.types.length];
    auto incoming = new size_t[][a.types.length];
    foreach (i, ref t; a.types)
        if (auto d = t.fqn in a.dependencies)
            foreach (target; *d)
                if (auto j = target in index)
                {
                    deps[i] ~= *j;
                    incoming[*j] ~= i;
                }
    auto result = new Building[a.types.length];
    foreach (i, ref t; a.types)
    {
        const file = relativePath(t.file, a.root).replace("\\", "/");
        const repoPath = github !is null ? github.prefix ~ file : file;
        // Most complex first, larger first on ties; the stable sort keeps source order among equal methods.
        auto methods = t.methods.dup;
        methods.sort!((x, y) => x.complexity != y.complexity ? x.complexity > y.complexity : x.loc > y.loc, SwapStrategy.stable);
        if (methods.length > maxMethods) methods = methods[0 .. maxMethods];
        // History is keyed by absolute path (git top level + repository-relative path); this matches `t.file`
        // because the analysed root was canonicalised (see `parseArgs` in app.d).
        auto h = t.file in a.history;
        result[i] = Building(i, lots[t.fqn], file, h !is null, h ? *h : FileHistory.init, deps[i], incoming[i],
            pullsByPath.get(repoPath, null), methods);
    }
    return result;
}

private Stats computeStats(ref Analysis a)
{
    bool[string] packages;
    size_t[Language] counts;
    Stats s;
    foreach (ref t; a.types)
    {
        import std.conv : to;
        packages[t.language.id ~ ":" ~ t.module_ ~ ":" ~ t.pkg] = true;
        counts[t.language]++;
        s.methods += t.nom;
        s.loc += t.loc;
    }
    // Fixed language order first, then a stable sort by count, so equal counts keep the order Java, TS, Rust.
    foreach (l; [Language.java, Language.typescript, Language.rust])
        if (auto n = l in counts) s.languages ~= LanguageCount(l, *n);
    s.languages.sort!((x, y) => x.count > y.count, SwapStrategy.stable);
    s.files = a.files;
    s.failedFiles = a.failedFiles;
    s.types = a.types.length;
    s.packages = packages.length;
    return s;
}
