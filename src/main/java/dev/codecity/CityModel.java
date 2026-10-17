package dev.codecity;

import dev.codecity.analysis.Analysis;
import dev.codecity.analysis.FileHistory;
import dev.codecity.analysis.GitHistory;
import dev.codecity.analysis.JavaAnalyzer;
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

/** An analysed and laid-out city, ready to be served as JSON. Immutable; watch mode swaps in a new instance. */
public final class CityModel {

    private static final int MAX_METHODS = 300;

    private final Analysis analysis;
    private final String json;
    private final long buildMillis;

    private CityModel(Analysis analysis, String json, long buildMillis) {
        this.analysis = analysis;
        this.json = json;
        this.buildMillis = buildMillis;
    }

    /** @param github GitHub context for permalinks and pull requests, or null */
    public static CityModel build(JavaAnalyzer analyzer, Path root, boolean includeTests, boolean useGit,
                                  GitHubInfo github) throws IOException {
        long start = System.nanoTime();
        Analysis parsed = analyzer.analyze(root, includeTests);
        Map<Path, FileHistory> history = useGit ? GitHistory.load(root) : Map.of();
        Analysis analysis = new Analysis(root, parsed.types(), parsed.dependencies(), history,
                parsed.files(), parsed.failedFiles());
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        CityLayout.Result layout = CityLayout.layout(name, analysis.types());
        String json = toJson(name, analysis, layout, github);
        return new CityModel(analysis, json, (System.nanoTime() - start) / 1_000_000);
    }

    public Analysis analysis() {
        return analysis;
    }

    public String json() {
        return json;
    }

    public long buildMillis() {
        return buildMillis;
    }

    public List<TypeInfo> types() {
        return analysis.types();
    }

    private static String toJson(String name, Analysis analysis, CityLayout.Result layout, GitHubInfo github) {
        List<TypeInfo> types = analysis.types();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < types.size(); i++) {
            index.put(types.get(i).fqn(), i);
        }
        long packages = types.stream().map(t -> t.module() + ":" + t.packageName()).distinct().count();

        JsonWriter w = new JsonWriter().beginObject()
                .field("name", name)
                .field("root", analysis.root().toString())
                .field("generatedAt", Instant.now().toString())
                .field("hasGit", !analysis.history().isEmpty())
                .field("platform", CityLayout.PLATFORM)
                .field("width", layout.width())
                .field("depth", layout.depth());

        w.name("stats").beginObject()
                .field("files", analysis.files())
                .field("failedFiles", analysis.failedFiles())
                .field("types", types.size())
                .field("packages", packages)
                .field("methods", types.stream().mapToInt(TypeInfo::nom).sum())
                .field("loc", types.stream().mapToInt(TypeInfo::loc).sum())
                .endObject();

        Map<String, List<Integer>> pullsByPath = new HashMap<>();
        if (github != null) {
            writeGitHub(w, github);
            List<PullRequest> pulls = github.pulls() == null ? List.of() : github.pulls();
            for (int i = 0; i < pulls.size(); i++) {
                for (String file : pulls.get(i).files()) {
                    pullsByPath.computeIfAbsent(file, k -> new ArrayList<>()).add(i);
                }
            }
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
        for (TypeInfo t : types) {
            CityLayout.Lot lot = layout.lots().get(t.fqn());
            FileHistory h = analysis.history().get(t.file().toAbsolutePath().normalize());
            w.beginObject()
                    .field("id", t.fqn())
                    .field("name", t.name())
                    .field("pkg", t.packageName())
                    .field("module", t.module())
                    .field("kind", t.kind())
                    .field("test", t.test())
                    .field("file", analysis.root().relativize(t.file()).toString().replace('\\', '/'))
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
            if (github != null) {
                String repoPath = github.prefix() + analysis.root().relativize(t.file()).toString().replace('\\', '/');
                List<Integer> prs = pullsByPath.get(repoPath);
                if (prs != null) {
                    w.name("prs").beginArray();
                    prs.forEach(w::value);
                    w.endArray();
                }
            }
            w.name("methods").beginArray();
            t.methods().stream()
                    .sorted(Comparator.comparingInt(MethodInfo::complexity).reversed()
                            .thenComparing(Comparator.comparingInt(MethodInfo::loc).reversed()))
                    .limit(MAX_METHODS)
                    .forEach(m -> w.beginArray()
                            .value(m.name()).value(m.line()).value(m.loc()).value(m.complexity()).value(m.parameters())
                            .endArray());
            w.endArray();
            w.name("deps").beginArray();
            for (String dep : analysis.dependencies().getOrDefault(t.fqn(), Set.of())) {
                Integer target = index.get(dep);
                if (target != null) {
                    w.value(target);
                }
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
        return w.endObject().toString();
    }

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
