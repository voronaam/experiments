/// An analysed and laid-out city, consumed by both the web viewer (as JSON) and the desktop viewer.
///
/// `CityModel.build` runs the whole pipeline for one source tree: walk and parse the sources and link their
/// dependencies (`codecity.analysis`), mine git history in parallel (`codecity.git`), lay the city out
/// (`codecity.layout`), then join everything per type into `Building`s. A rebuild (`--watch`) does not modify a
/// model: it builds a new one, which replaces the old one as a whole.
///
/// Indices: `Building.index`, `Building.deps`/`incoming` and the JSON `deps` all index `analysis.types` (which is
/// sorted by fqn) and `buildings` alike, because `buildings[i]` is built for `analysis.types[i]`.
///
/// History replay (`--history`): `analysis.types` is then the union of the present types and the types that only
/// existed in some sampled snapshot ("gone" types, `Building.gone`), still sorted by fqn. Every building carries one
/// value per snapshot (`Building.hist`), every district one flag per snapshot (`CityModel.districtHist`). The
/// stats count present types only.
module codecity.city;

import codecity.analysis : SourceAnalyzer;
import codecity.git : load, Touch;
import codecity.github : GitHubInfo;
import codecity.history;
import codecity.json : JsonWriter;
import codecity.layout;
import codecity.model;
import core.thread : Thread;
import std.algorithm : max, sort, SwapStrategy;
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
    /// History replay only: the type no longer exists today; it is shown as it was when last seen.
    bool gone;
    /// History replay only (empty arrays otherwise): the type in each snapshot.
    TypeHistory hist;
}

/// One building's values per snapshot (index k = `CityModel.snapshots[k]`). The four metrics are -1 where the type
/// does not exist. `commits` / `authors` / `last` are the git statistics of the type's file in that snapshot, counted
/// over the commits up to the snapshot's author time; 0, 0 and -1 when the type does not exist there or no such
/// commit exists.
struct TypeHistory
{
    int[] nom, noa, loc, cx, commits, authors;
    long[] last;

    /// Whether the type exists in snapshot `k`.
    bool exists(size_t k) const { return nom[k] >= 0; }
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
    /// History replay: the sampled commits, oldest first (empty without `--history`), and per district (index into
    /// `districts`) whether it holds a building that exists in each snapshot.
    Snapshot[] snapshots;
    bool[][] districtHist;
    /// Lazily built JSON form; see `json`.
    private string cachedJson;

    /// Runs the full pipeline for `root`. `useGit` enables history mining; `github` (valid when `hasGithub`) was
    /// fetched once by the caller and is copied into every model, so rebuilds do not query GitHub again.
    ///
    /// `historySamples` > 0 adds the history replay over that many sampled commits (see `codecity.history`); it needs
    /// `useGit`, and without git history it only prints a warning.
    static CityModel build(SourceAnalyzer analyzer, string root, bool includeTests, bool useGit, bool hasGithub,
        GitHubInfo github, uint historySamples = 0)
    {
        auto sw = StopWatch(AutoStart.yes);
        FileHistory[string] history;
        // every commit per file, kept only for the replay
        Touch[][string] touches;
        const wantHistory = historySamples > 0 && useGit;
        // git log runs while the sources are parsed. The thread writes `history` (captured by the closure); join()
        // makes that write visible here before it is read.
        auto gitThread = new Thread({ if (useGit) history = load(root, wantHistory ? &touches : null); });
        gitThread.start();
        auto analysis = analyzer.analyze(root, includeTests);
        gitThread.join();
        analysis.history = history;

        auto c = new CityModel;
        c.name = root.baseName;
        // the stats describe today's code base, so they are taken before past types join the analysis
        c.stats = computeStats(analysis);
        // The replay runs after the present analysis, as it reuses the same analyser (which is not reentrant).
        History past;
        if (historySamples > 0 && !useGit) warnNoHistory();
        const replay = wantHistory && collect(analyzer, root, includeTests, historySamples, past);
        uint[] footprint;
        if (replay) footprint = mergeHistory(analysis, past, c.presentTypes);
        c.analysis = analysis;
        auto l = layout(c.name, c.analysis.types, footprint);
        c.districts = l.districts;
        c.width = l.width;
        c.depth = l.depth;
        c.hasGithub = hasGithub;
        c.github = github;
        c.buildings = resolve(c.analysis, l.lots, hasGithub ? &c.github : null);
        if (replay) c.addHistory(past, touches);
        c.buildMillis = sw.peek.total!"msecs";
        return c;
    }

    bool hasHistory() const { return snapshots.length > 0; }

    /// Fills `snapshots`, every building's `gone` flag and `hist`, and `districtHist` from the replay's `past`, sets
    /// every building's footprint side to its actual size and recounts the districts' types without the gone ones;
    /// `touches` are the per-file commits of the git history pass. Runs after `resolve`, so building indices, files
    /// and lots are final.
    private void addHistory(ref History past, Touch[][string] touches)
    {
        snapshots = past.snapshots;
        const n = snapshots.length;
        // Per file: its commits' author times in ascending order, and how many distinct authors the first i + 1 of
        // them have. A snapshot then needs one binary search: the commits with time ≤ T are a prefix.
        struct Timeline
        {
            long[] times;
            uint[] distinct;
        }
        Timeline[string] timelines;
        Timeline timeline(string file)
        {
            if (auto hit = file in timelines) return *hit;
            auto list = touches.get(file, null).dup;
            list.sort!((a, b) => a.time < b.time, SwapStrategy.stable);
            Timeline tl;
            bool[string] seen;
            foreach (t; list)
            {
                seen[t.author] = true;
                tl.times ~= t.time;
                tl.distinct ~= cast(uint) seen.length;
            }
            timelines[file] = tl;
            return tl;
        }
        foreach (ref b; buildings)
        {
            auto t = &analysis.types[b.index];
            b.gone = t.fqn !in presentTypes;
            // The lot was sized for the type's largest attribute count; the building itself has its actual size
            // (today's, or when last seen for a gone type), centred in that lot (the lot's x/z is its centre).
            b.lot.side = side(*t);
            with (b.hist)
            {
                nom = new int[n];
                noa = new int[n];
                loc = new int[n];
                cx = new int[n];
                commits = new int[n];
                authors = new int[n];
                last = new long[n];
            }
            foreach (k; 0 .. n)
            {
                auto m = t.fqn in past.metrics[k];
                if (m is null)
                {
                    b.hist.nom[k] = b.hist.noa[k] = b.hist.loc[k] = b.hist.cx[k] = -1;
                    b.hist.last[k] = -1;
                    continue;
                }
                b.hist.nom[k] = m.nom;
                b.hist.noa[k] = m.noa;
                b.hist.loc[k] = m.loc;
                b.hist.cx[k] = m.cx;
                // The git statistics of the file the type was in at that snapshot (it may have moved since),
                // keyed like the building's own (see `resolve`).
                const tl = timeline(m.file);
                // number of commits with time ≤ the snapshot's (the times are ascending)
                import std.range : assumeSorted;
                const count = tl.times.assumeSorted.lowerBound(snapshots[k].time + 1).length;
                b.hist.commits[k] = cast(int) count;
                b.hist.authors[k] = count ? tl.distinct[count - 1] : 0;
                b.hist.last[k] = count ? tl.times[count - 1] : -1;
            }
        }
        // A district is present in a snapshot when one of its own buildings exists there or one of its
        // sub-districts is present. `districts` is in pre-order, so walking it backwards visits every sub-district
        // before its parent; a district's parent is the closest earlier district one level up.
        districtHist = new bool[][](districts.length, n);
        auto parent = new size_t[districts.length];
        size_t[] stack;
        foreach (i, d; districts)
        {
            stack = stack[0 .. d.depth];
            if (d.depth > 0) parent[i] = stack[$ - 1];
            stack ~= i;
        }
        foreach (ref b; buildings)
            foreach (k; 0 .. n)
                if (b.hist.exists(k)) districtHist[b.lot.district][k] = true;
        // District type counts, like the stats, cover today's types only: recount without the gone ones, each
        // building counting for its district and every enclosing one.
        foreach (ref d; districts) d.types = 0;
        foreach (ref b; buildings)
            if (!b.gone)
                for (size_t d = b.lot.district;; d = parent[d])
                {
                    districts[d].types++;
                    if (d == 0) break;
                }
        foreach_reverse (i; 1 .. districts.length)
            foreach (k; 0 .. n)
                if (districtHist[i][k]) districtHist[parent[i]][k] = true;
    }

    /// History replay: fqns of the types that exist today (the others in `analysis.types` are gone); filled by
    /// `mergeHistory`.
    private bool[string] presentTypes;

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
        foreach (i, d; districts)
        {
            w.beginObject()
                .field("id", d.id)
                .field("name", d.label)
                .field("depth", d.depth)
                .fieldNum("x", d.x).fieldNum("z", d.z)
                .fieldNum("w", d.w).fieldNum("d", d.d)
                .field("types", d.types);
            if (hasHistory)
            {
                // 1 when the district is present in that snapshot, 0 when not
                w.name("hist").beginArray();
                foreach (present; districtHist[i]) w.value(present ? 1 : 0);
                w.endArray();
            }
            w.endObject();
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
            if (hasHistory)
            {
                // one value per snapshot, in the order of "history"."snapshots"
                void list(T)(string key, const T[] values)
                {
                    w.name(key).beginArray();
                    foreach (v; values) w.value(v);
                    w.endArray();
                }
                w.name("hist").beginObject();
                list("nom", b.hist.nom);
                list("noa", b.hist.noa);
                list("loc", b.hist.loc);
                list("cx", b.hist.cx);
                list("commits", b.hist.commits);
                list("authors", b.hist.authors);
                list("last", b.hist.last);
                w.endObject();
                if (b.gone) w.fieldBool("gone", true);
            }
            w.endObject();
        }
        w.endArray();
        if (hasHistory)
        {
            w.name("history").beginObject().name("snapshots").beginArray();
            foreach (s; snapshots)
            {
                w.beginObject()
                    .field("commit", s.commit)
                    .field("time", s.time)
                    .field("author", s.author)
                    .field("subject", s.subject)
                    .field("types", s.types)
                    .endObject();
            }
            w.endArray().endObject();
        }
        w.endObject();
        return w.finish();
    }
}

/// Turns the present analysis `a` into the union city of the history replay: every type of `past` that does not
/// exist today is added as it was last seen, with its dependencies of then restricted to the union, and the types
/// are sorted by fqn again. `present` receives the fqns of today's types. Returns, per type of the union (same
/// order), the attribute count that sizes its lot: the largest over the present and all snapshots.
private uint[] mergeHistory(ref Analysis a, ref History past, out bool[string] present)
{
    foreach (ref t; a.types) present[t.fqn] = true;
    TypeDecl[] gone;
    foreach (fqn, ref t; past.lastSeen) if (fqn !in present) gone ~= t;
    a.types ~= gone;
    a.types.sort!((x, y) => x.fqn < y.fqn);
    bool[string] union_;
    foreach (ref t; a.types) union_[t.fqn] = true;
    foreach (ref t; gone)
    {
        string[] deps;
        foreach (d; past.lastDeps.get(t.fqn, null)) if (d in union_) deps ~= d;
        a.dependencies[t.fqn] = deps;
    }
    auto footprint = new uint[a.types.length];
    foreach (i, ref t; a.types)
    {
        uint noa = t.fqn in present ? t.noa : 0;
        foreach (ref snapshot; past.metrics)
            if (auto m = t.fqn in snapshot) noa = max(noa, cast(uint) m.noa);
        footprint[i] = noa;
    }
    return footprint;
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
