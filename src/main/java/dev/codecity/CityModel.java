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
     */
    public record Building(int index, TypeInfo type, CityLayout.Lot lot, String file, FileHistory history,
                           int[] deps, int[] incoming, int[] prs, List<MethodInfo> methods) {
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
    /**
     * Cached JSON. Several server threads may call {@link #json} at once; at worst each computes the same string,
     * and volatile makes the published string visible to all of them.
     */
    private volatile String json;

    private CityModel(String name, Analysis analysis, CityLayout.Result layout, GitHubInfo github, long buildMillis) {
        this.name = name;
        this.analysis = analysis;
        this.layout = layout;
        this.github = github;
        this.buildings = resolve(analysis, layout, github);
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
        long start = System.nanoTime();
        Analysis parsed = analyzer.analyze(root, includeTests);
        Map<Path, FileHistory> history = useGit ? GitHistory.load(root) : Map.of();
        // The analyser leaves history empty; add it here so the model has a single, complete Analysis.
        Analysis analysis = new Analysis(root, parsed.types(), parsed.dependencies(), history,
                parsed.files(), parsed.failedFiles());
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        CityLayout.Result layout = CityLayout.layout(name, analysis.types());
        return new CityModel(name, analysis, layout, github, (System.nanoTime() - start) / 1_000_000);
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
    private static List<Building> resolve(Analysis analysis, CityLayout.Result layout, GitHubInfo github) {
        List<TypeInfo> types = analysis.types();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < types.size(); i++) {
            index.put(types.get(i).fqn(), i);
        }
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
            int[] out = analysis.dependencies().getOrDefault(types.get(i).fqn(), Set.of()).stream()
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
                    methods));
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
        for (CityLayout.District d : layout.districts()) {
            w.beginObject()
                    .field("id", d.id())
                    .field("name", d.label())
                    .field("depth", d.depth())
                    .field("x", d.x()).field("z", d.z())
                    .field("w", d.w()).field("d", d.d())
                    .field("types", d.types())
                    .endObject();
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
            w.endObject();
        }
        w.endArray();
        return w.endObject().toString();
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
