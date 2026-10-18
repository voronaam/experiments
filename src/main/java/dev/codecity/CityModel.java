package dev.codecity;

import dev.codecity.analysis.Analysis;
import dev.codecity.analysis.FileHistory;
import dev.codecity.analysis.GitHistory;
import dev.codecity.analysis.Language;
import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.analysis.MethodInfo;
import dev.codecity.analysis.TypeInfo;
import dev.codecity.github.GitHubInfo;
import dev.codecity.github.PullRequest;
import dev.codecity.history.CityHistory;
import dev.codecity.history.Snapshot;
import dev.codecity.history.TypeHistory;
import dev.codecity.json.JsonWriter;
import dev.codecity.layout.CityLayout;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * An analysed and laid-out city, consumed by both the web viewer (as JSON) and the desktop viewer (as objects).
 * Immutable; watch mode swaps in a new instance.
 *
 * <p>This is where the pipeline comes together: {@link #build} runs the analysis ({@link SourceAnalyzer}), the git
 * history ({@link GitHistory}) and the layout ({@link CityLayout}), and the constructor then resolves everything the
 * viewers need per building into index-based {@link Building} records. The JSON form is produced lazily by
 * {@link #json}.
 *
 * <p>Buildings are indexed in the order of {@link Analysis#types()}, i.e. sorted by fqn; {@code deps},
 * {@code incoming} and the JSON {@code deps} arrays all use these indices, so the viewers never handle fqns for edges.
 *
 * <h2>History replay</h2>
 * With {@code --history} the model also carries a {@link CityHistory}. The city is then the union of today's types and
 * every type of any snapshot: a type that no longer exists ("gone") is shown with its last snapshot version, is hidden
 * in the present-day view and only appears during replay. Buildings are still sorted by fqn, now over the union, and
 * each lot is sized for the largest attribute count the type ever had. {@link #analysis()}, {@link #types()} and the
 * statistics stay present-day only.
 */
public final class CityModel {

    /**
     * At most this many methods are kept per building (the most complex first), which bounds the JSON size for
     * generated or huge classes. The building's own metrics ({@code nom}, {@code cx}) still count all methods.
     */
    private static final int MAX_METHODS = 300;

    /**
     * One building, fully resolved: the type, where it stands, its history and its edges.
     *
     * @param file     path relative to the analysed root, with forward slashes
     * @param history  git history of its file, or null
     * @param deps     indices of buildings this type uses
     * @param incoming indices of buildings that use this type
     * @param prs      indices into {@link GitHubInfo#pulls()} of open PRs touching its file
     * @param methods  methods sorted by complexity, then size (capped)
     * @param hist     the type's metrics per replay snapshot, or null when the model has no history
     * @param gone     the type exists only in history snapshots, not today
     */
    public record Building(int index, TypeInfo type, CityLayout.Lot lot, String file, FileHistory history,
                           int[] deps, int[] incoming, int[] prs, List<MethodInfo> methods, TypeHistory hist,
                           boolean gone) {
    }

    /**
     * Totals for the viewers' statistics.
     *
     * @param packages  distinct (language, module, package) triples, i.e. districts that hold buildings directly
     * @param languages type count per language, most common first
     */
    public record Stats(int files, int failedFiles, int types, long packages, int methods, int loc,
                        Map<Language, Integer> languages) {
    }

    private final String name;
    private final Analysis analysis;
    private final CityLayout.Result layout;
    private final GitHubInfo github;
    private final List<Building> buildings;
    private final Stats stats;
    private final long buildMillis;
    /** The history replay, or null without {@code --history}. */
    private final CityHistory history;
    /**
     * Per district (in {@link #districts()} order), per snapshot: whether the district or one of its sub-districts
     * holds a building that exists in that snapshot. Null without history.
     */
    private final boolean[][] districtHist;
    /**
     * Cached JSON. Several server threads may call {@link #json} at once; at worst each computes the same string,
     * and volatile makes the published string visible to all of them.
     */
    private volatile String json;

    private CityModel(String name, Analysis analysis, List<TypeInfo> cityTypes, Map<String, Set<String>> cityDeps,
                      CityHistory history, CityLayout.Result layout, GitHubInfo github, long buildMillis) {
        this.name = name;
        this.analysis = analysis;
        this.layout = layout;
        this.github = github;
        this.history = history;
        this.buildings = resolve(analysis, cityTypes, cityDeps, history, layout, github);
        this.districtHist = history == null ? null : districtHist(layout.districts(), buildings,
                history.snapshots().size());
        List<TypeInfo> types = analysis.types();
        this.stats = new Stats(analysis.files(), analysis.failedFiles(), types.size(),
                types.stream().map(t -> t.language() + ":" + t.module() + ":" + t.packageName()).distinct().count(),
                types.stream().mapToInt(TypeInfo::nom).sum(),
                types.stream().mapToInt(TypeInfo::loc).sum(),
                languageCounts(types));
        this.buildMillis = buildMillis;
    }

    /**
     * Runs the whole pipeline for {@code root}. Called once at start-up and, in watch mode, again on the rebuild
     * thread after every change; reusing the same {@code analyzer} lets it re-parse only the changed files.
     *
     * @param useGit false skips {@link GitHistory} (the {@code --no-git} option)
     * @param github GitHub context for permalinks and pull requests, or null
     */
    public static CityModel build(SourceAnalyzer analyzer, Path root, boolean includeTests, boolean useGit,
                                  GitHubInfo github) throws IOException {
        return build(analyzer, root, includeTests, useGit, github, 0);
    }

    /**
     * {@link #build(SourceAnalyzer, Path, boolean, boolean, GitHubInfo)} plus, when {@code historySnapshots > 0}, the
     * history replay over that many sampled commits ({@link CityHistory}). The replay needs git history; without it
     * (no git, {@code --no-git}, not a work tree, no commits) a warning is printed and the model has no history.
     */
    public static CityModel build(SourceAnalyzer analyzer, Path root, boolean includeTests, boolean useGit,
                                  GitHubInfo github, int historySnapshots) throws IOException {
        long start = System.nanoTime();
        // git log runs on its own thread while the sources are parsed: the two are independent, and git mostly waits
        // for I/O while parsing keeps the CPU busy. The raw log is kept only for the replay, which counts each file's
        // commits up to every snapshot.
        FutureTask<Map<Path, List<GitHistory.Touch>>> gitLog = new FutureTask<>(() -> GitHistory.touches(root));
        if (useGit) {
            Thread.ofPlatform().name("git-log").daemon().start(gitLog);
        }
        Analysis parsed;
        try {
            parsed = analyzer.analyze(root, includeTests);
        } catch (IOException | RuntimeException e) {
            gitLog.cancel(true);
            throw e;
        }
        Map<Path, List<GitHistory.Touch>> touches = useGit ? await(gitLog) : Map.of();
        Map<Path, FileHistory> history = GitHistory.summarise(touches);
        // The analyser leaves history empty; add it here so the model has a single, complete Analysis.
        Analysis analysis = new Analysis(root, parsed.types(), parsed.dependencies(), history,
                parsed.files(), parsed.failedFiles());
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        CityHistory replay = null;
        if (historySnapshots > 0) {
            replay = useGit ? CityHistory.compute(root, historySnapshots, includeTests, touches, System.out) : null;
            if (replay == null) {
                System.err.println("warning: --history needs git history; replay disabled");
            }
        }
        if (replay == null) {
            CityLayout.Result layout = CityLayout.layout(name, analysis.types());
            return new CityModel(name, analysis, analysis.types(), analysis.dependencies(), null, layout, github,
                    (System.nanoTime() - start) / 1_000_000);
        }

        // The union city: today's types as they are, plus every gone type in its last snapshot version with that
        // version's dependencies (which all point at types of that snapshot, so into the union).
        Map<String, TypeInfo> union = new TreeMap<>();
        Map<String, Set<String>> deps = new HashMap<>(analysis.dependencies());
        analysis.types().forEach(t -> union.put(t.fqn(), t));
        Set<String> present = Set.copyOf(union.keySet());
        replay.types().forEach((fqn, h) -> {
            if (!union.containsKey(fqn)) {
                union.put(fqn, h.latest());
                Set<String> d = new java.util.HashSet<>(h.latestDeps());
                d.remove(fqn);
                deps.put(fqn, d);
            }
        });
        CityHistory r = replay;
        // Every lot is big enough for the building at its widest, today or in any snapshot.
        CityLayout.Result layout = CityLayout.layout(name, List.copyOf(union.values()), t -> {
            TypeHistory h = r.types().get(t.fqn());
            int max = h == null ? -1 : h.maxNoa();
            return present.contains(t.fqn()) ? Math.max(max, t.noa()) : max;
        });
        return new CityModel(name, analysis, List.copyOf(union.values()), deps, replay,
                presentCounts(layout, present), github, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * The git log mined in the background, or no history if mining failed unexpectedly ({@link GitHistory} already
     * turns the expected failures into an empty result) or the waiting thread is interrupted.
     */
    private static Map<Path, List<GitHistory.Touch>> await(FutureTask<Map<Path, List<GitHistory.Touch>>> gitLog) {
        try {
            return gitLog.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            gitLog.cancel(true);
            return Map.of();
        } catch (ExecutionException e) {
            System.err.println("warning: git history unavailable: " + e.getCause());
            return Map.of();
        }
    }

    public String name() {
        return name;
    }

    public Analysis analysis() {
        return analysis;
    }

    public List<TypeInfo> types() {
        return analysis.types();
    }

    public List<Building> buildings() {
        return buildings;
    }

    public List<CityLayout.District> districts() {
        return layout.districts();
    }

    public double width() {
        return layout.width();
    }

    public double depth() {
        return layout.depth();
    }

    public boolean hasGit() {
        return !analysis.history().isEmpty();
    }

    /** GitHub context, or null when the code is not hosted on GitHub. */
    public GitHubInfo github() {
        return github;
    }

    public Stats stats() {
        return stats;
    }

    public long buildMillis() {
        return buildMillis;
    }

    /** The replay snapshots, oldest first, or null when the model has no history. */
    public List<Snapshot> history() {
        return history == null ? null : history.snapshots();
    }

    public boolean hasHistory() {
        return history != null;
    }

    /**
     * Whether district {@code d} (an index into {@link #districts()}) has an existing building, directly or in a
     * sub-district, in snapshot {@code k}. Always true without history.
     */
    public boolean districtExists(int d, int k) {
        return districtHist == null || districtHist[d][k];
    }

    /**
     * {@code layout} with each district's {@code types} counting only the {@code present} types (in it and its
     * sub-districts), like the statistics: the layout itself counted the gone types of the union too.
     */
    private static CityLayout.Result presentCounts(CityLayout.Result layout, Set<String> present) {
        List<CityLayout.District> districts = layout.districts();
        int[] parent = districtParents(districts);
        int[] counts = new int[districts.size()];
        layout.lots().forEach((fqn, lot) -> {
            if (present.contains(fqn)) {
                for (int d = lot.districtIndex(); d >= 0; d = parent[d]) {
                    counts[d]++;
                }
            }
        });
        List<CityLayout.District> recounted = new ArrayList<>();
        for (int i = 0; i < districts.size(); i++) {
            CityLayout.District d = districts.get(i);
            recounted.add(new CityLayout.District(d.id(), d.label(), d.depth(), d.x(), d.z(), d.w(), d.d(), counts[i]));
        }
        return new CityLayout.Result(List.copyOf(recounted), layout.lots(), layout.width(), layout.depth());
    }

    /**
     * Per district, whether it or one of its sub-districts holds a building matching {@code which}. The desktop viewer
     * uses it to leave out districts that hold nothing but types that exist only in history.
     */
    public boolean[] districtsHolding(java.util.function.Predicate<Building> which) {
        int[] parent = districtParents(layout.districts());
        boolean[] marked = new boolean[parent.length];
        for (Building b : buildings) {
            if (which.test(b)) {
                mark(marked, parent, b.lot().districtIndex());
            }
        }
        return marked;
    }

    /** Marks district {@code d} and its ancestors, stopping at the first one already marked (as are all above it). */
    private static void mark(boolean[] marked, int[] parent, int d) {
        for (; d >= 0 && !marked[d]; d = parent[d]) {
            marked[d] = true;
        }
    }

    /**
     * Index of every district's parent district, -1 for the city. The districts are in pre-order, so a district's
     * parent is the closest earlier district one level up, tracked with a stack.
     */
    private static int[] districtParents(List<CityLayout.District> districts) {
        int[] parent = new int[districts.size()];
        int[] stack = new int[districts.size() + 1];
        for (int i = 0; i < districts.size(); i++) {
            int depth = districts.get(i).depth();
            parent[i] = depth == 0 ? -1 : stack[depth - 1];
            stack[depth] = i;
        }
        return parent;
    }

    /** Marks, for every snapshot, the districts that hold a building existing then, and all their ancestors. */
    private static boolean[][] districtHist(List<CityLayout.District> districts, List<Building> buildings, int count) {
        int[] parent = districtParents(districts);
        boolean[][] byTime = new boolean[count][districts.size()];
        for (Building b : buildings) {
            for (int k = 0; k < count; k++) {
                if (b.hist().exists(k)) {
                    mark(byTime[k], parent, b.lot().districtIndex());
                }
            }
        }
        boolean[][] hist = new boolean[districts.size()][count];
        for (int d = 0; d < districts.size(); d++) {
            for (int k = 0; k < count; k++) {
                hist[d][k] = byTime[k][d];
            }
        }
        return hist;
    }

    /** Types per language, most first; ties keep the Language declaration order, as the sort is stable. */
    private static Map<Language, Integer> languageCounts(List<TypeInfo> types) {
        Map<Language, Integer> counts = new java.util.EnumMap<>(Language.class);
        types.forEach(t -> counts.merge(t.language(), 1, Integer::sum));
        Map<Language, Integer> sorted = new java.util.LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<Language, Integer>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /**
     * The model as served to the web viewer (and embedded by the HTML export); computed on first use. Its
     * {@code generatedAt} is therefore the time of that first call, not of the build.
     */
    public String json() {
        String j = json;
        if (j == null) {
            j = toJson();
            json = j;
        }
        return j;
    }

    /**
     * Turns the fqn-keyed analysis into index-based buildings: outgoing and incoming edges as index arrays, the lot,
     * the file's git history, the open pull requests touching the file, and the capped, sorted method list.
     *
     * <p>Pull requests list files relative to the repository root, while buildings know their path relative to the
     * analysed root; {@link GitHubInfo#prefix()} (the analysed root's path inside the repository) bridges the two.
     */
    private static List<Building> resolve(Analysis analysis, List<TypeInfo> types, Map<String, Set<String>> dependencies,
                                          CityHistory history, CityLayout.Result layout, GitHubInfo github) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < types.size(); i++) {
            index.put(types.get(i).fqn(), i);
        }
        Set<String> present = new java.util.HashSet<>();
        analysis.types().forEach(t -> present.add(t.fqn()));
        Map<String, List<Integer>> pullsByPath = new HashMap<>();
        if (github != null && github.pulls() != null) {
            List<PullRequest> pulls = github.pulls();
            for (int i = 0; i < pulls.size(); i++) {
                for (String file : pulls.get(i).files()) {
                    pullsByPath.computeIfAbsent(file, k -> new ArrayList<>()).add(i);
                }
            }
        }
        List<int[]> deps = new ArrayList<>();
        List<List<Integer>> incoming = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            incoming.add(new ArrayList<>());
        }
        for (int i = 0; i < types.size(); i++) {
            // Unknown targets are skipped defensively; SourceAnalyzer already guarantees that every target exists.
            int[] out = dependencies.getOrDefault(types.get(i).fqn(), Set.of()).stream()
                    .map(index::get).filter(j -> j != null).mapToInt(Integer::intValue).toArray();
            deps.add(out);
            for (int j : out) {
                incoming.get(j).add(i);
            }
        }
        List<Building> result = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            TypeInfo t = types.get(i);
            String file = analysis.root().relativize(t.file()).toString().replace('\\', '/');
            String repoPath = github == null ? file : github.prefix() + file;
            List<MethodInfo> methods = t.methods().stream()
                    .sorted(Comparator.comparingInt(MethodInfo::complexity).reversed()
                            .thenComparing(Comparator.comparingInt(MethodInfo::loc).reversed()))
                    .limit(MAX_METHODS)
                    .toList();
            result.add(new Building(i, t, layout.lots().get(t.fqn()), file,
                    // Same key form as GitHistory: absolute and normalised.
                    analysis.history().get(t.file().toAbsolutePath().normalize()),
                    deps.get(i),
                    incoming.get(i).stream().mapToInt(Integer::intValue).toArray(),
                    pullsByPath.getOrDefault(repoPath, List.of()).stream().mapToInt(Integer::intValue).toArray(),
                    methods,
                    // A present type missing from every snapshot (e.g. not committed yet) never existed in history.
                    history == null ? null : history.types().getOrDefault(t.fqn(),
                            new TypeHistory(history.snapshots().size())),
                    history != null && !present.contains(t.fqn())));
        }
        return List.copyOf(result);
    }

    /**
     * Serialises the model for the web viewer. The format is compact on purpose: edges are building indices, a
     * method is a positional array {@code [name, line, loc, complexity, parameters]}, and optional data (history,
     * pull requests) is left out instead of written as null. Incoming edges are not written; the viewer can derive
     * them from {@code deps}. Coordinates are those of {@link CityLayout}.
     */
    private String toJson() {
        JsonWriter w = new JsonWriter().beginObject()
                .field("name", name)
                .field("root", analysis.root().toString())
                .field("generatedAt", Instant.now().toString())
                .field("hasGit", hasGit())
                .field("platform", CityLayout.PLATFORM)
                .field("width", layout.width())
                .field("depth", layout.depth());

        w.name("stats").beginObject()
                .field("files", stats.files())
                .field("failedFiles", stats.failedFiles())
                .field("types", stats.types())
                .field("packages", stats.packages())
                .field("methods", stats.methods())
                .field("loc", stats.loc());
        w.name("languages").beginObject();
        stats.languages().forEach((lang, n) -> w.field(lang.id, n));
        w.endObject().endObject();

        if (github != null) {
            writeGitHub(w, github);
        }

        w.name("districts").beginArray();
        for (int i = 0; i < layout.districts().size(); i++) {
            CityLayout.District d = layout.districts().get(i);
            w.beginObject()
                    .field("id", d.id())
                    .field("name", d.label())
                    .field("depth", d.depth())
                    .field("x", d.x()).field("z", d.z())
                    .field("w", d.w()).field("d", d.d())
                    .field("types", d.types());
            if (districtHist != null) {
                w.name("hist").beginArray();
                for (boolean e : districtHist[i]) {
                    w.value(e ? 1 : 0);
                }
                w.endArray();
            }
            w.endObject();
        }
        w.endArray();

        w.name("buildings").beginArray();
        for (Building b : buildings) {
            TypeInfo t = b.type();
            CityLayout.Lot lot = b.lot();
            FileHistory h = b.history();
            w.beginObject()
                    .field("id", t.fqn())
                    .field("lang", t.language().id)
                    .field("name", t.name())
                    .field("pkg", t.packageName())
                    .field("module", t.module())
                    .field("kind", t.kind())
                    .field("test", t.test())
                    .field("file", b.file())
                    .field("line", t.line())
                    .field("endLine", t.endLine())
                    .field("x", lot.x()).field("z", lot.z()).field("y", lot.y())
                    .field("side", lot.side())
                    .field("district", lot.districtIndex())
                    .field("nom", t.nom())
                    .field("noa", t.noa())
                    .field("loc", t.loc())
                    .field("cx", t.complexity());
            if (h != null) {
                w.field("commits", h.commits())
                        .field("authors", h.authors())
                        .field("last", h.lastCommit())
                        .field("first", h.firstCommit());
            }
            if (b.prs().length > 0) {
                w.name("prs").beginArray();
                for (int p : b.prs()) {
                    w.value(p);
                }
                w.endArray();
            }
            w.name("methods").beginArray();
            for (MethodInfo m : b.methods()) {
                w.beginArray()
                        .value(m.name()).value(m.line()).value(m.loc()).value(m.complexity()).value(m.parameters())
                        .endArray();
            }
            w.endArray();
            w.name("deps").beginArray();
            for (int d : b.deps()) {
                w.value(d);
            }
            w.endArray();
            if (b.hist() != null) {
                writeHist(w, b.hist());
            }
            if (b.gone()) {
                w.field("gone", true);
            }
            w.endObject();
        }
        w.endArray();
        if (history != null) {
            w.name("history").beginObject().name("snapshots").beginArray();
            for (Snapshot s : history.snapshots()) {
                w.beginObject()
                        .field("commit", s.commit())
                        .field("time", s.time())
                        .field("author", s.author())
                        .field("subject", s.subject())
                        .field("types", s.types())
                        .endObject();
            }
            w.endArray().endObject();
        }
        return w.endObject().toString();
    }

    /** A building's {@code hist} object: one array per metric, one entry per snapshot. */
    private static void writeHist(JsonWriter w, TypeHistory h) {
        w.name("hist").beginObject();
        intArray(w, "nom", h.nom);
        intArray(w, "noa", h.noa);
        intArray(w, "loc", h.loc);
        intArray(w, "cx", h.cx);
        intArray(w, "commits", h.commits);
        intArray(w, "authors", h.authors);
        w.name("last").beginArray();
        for (long v : h.last) {
            w.value(v);
        }
        w.endArray().endObject();
    }

    private static void intArray(JsonWriter w, String name, int[] values) {
        w.name(name).beginArray();
        for (int v : values) {
            w.value(v);
        }
        w.endArray();
    }

    /** Repository info for permalinks; the pull request list only when it was fetched ({@code pulls} non-null). */
    private static void writeGitHub(JsonWriter w, GitHubInfo github) {
        w.name("github").beginObject()
                .field("host", github.repo().host())
                .field("owner", github.repo().owner())
                .field("repo", github.repo().name())
                .field("url", github.repo().webUrl())
                .field("ref", github.ref())
                .field("prefix", github.prefix());
        if (github.pulls() != null) {
            w.field("pullsSource", github.pullsSource());
            w.name("pulls").beginArray();
            for (PullRequest pr : github.pulls()) {
                w.beginObject()
                        .field("number", pr.number())
                        .field("title", pr.title())
                        .field("url", pr.url())
                        .field("author", pr.author())
                        .field("updatedAt", pr.updatedAt())
                        .field("draft", pr.draft())
                        .field("files", pr.files().size())
                        .endObject();
            }
            w.endArray();
        }
        w.endObject();
    }
}
