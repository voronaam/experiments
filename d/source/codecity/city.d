/// An analysed and laid-out city, consumed by both the web viewer (as JSON) and the desktop viewer.
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

enum maxMethods = 300;

/// One building, fully resolved: the type, where it stands, its history and its edges.
struct Building
{
    size_t index;
    Lot lot;
    /// Path relative to the analysed root, with forward slashes.
    string file;
    bool hasHistory;
    FileHistory history;
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

struct Stats
{
    size_t files, failedFiles, types, packages;
    uint methods, loc;
    /// Type count per language, most common first.
    LanguageCount[] languages;
}

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
    private string cachedJson;

    static CityModel build(SourceAnalyzer analyzer, string root, bool includeTests, bool useGit, bool hasGithub, GitHubInfo github)
    {
        auto sw = StopWatch(AutoStart.yes);
        FileHistory[string] history;
        // git log runs while the sources are parsed
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
    string json()
    {
        if (cachedJson is null) cachedJson = toJson();
        return cachedJson;
    }

    private string toJson()
    {
        auto w = JsonWriter.create();
        w.beginObject()
            .field("name", name)
            .field("root", analysis.root)
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

private Building[] resolve(ref Analysis a, Lot[string] lots, const(GitHubInfo)* github)
{
    size_t[string] index;
    foreach (i, ref t; a.types) index[t.fqn] = i;
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
        auto methods = t.methods.dup;
        methods.sort!((x, y) => x.complexity != y.complexity ? x.complexity > y.complexity : x.loc > y.loc, SwapStrategy.stable);
        if (methods.length > maxMethods) methods = methods[0 .. maxMethods];
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
    foreach (l; [Language.java, Language.typescript, Language.rust])
        if (auto n = l in counts) s.languages ~= LanguageCount(l, *n);
    s.languages.sort!((x, y) => x.count > y.count, SwapStrategy.stable);
    s.files = a.files;
    s.failedFiles = a.failedFiles;
    s.types = a.types.length;
    s.packages = packages.length;
    return s;
}
