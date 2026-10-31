package dev.codecity;

import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.history.CityHistory;
import dev.codecity.json.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end regression suite, shared by all three editions: runs the whole pipeline ({@link CityModel#build}, as
 * {@code --export} does) on every fixture under {@code testdata/} and compares the city JSON with its golden file in
 * {@code testdata/golden/}. The Rust ({@code golden_tests.rs}) and D ({@code golden.d}) editions compare with the same
 * files, so the three are held to identical output.
 *
 * <p>The cases: each of {@code java-project}, {@code ts-project}, {@code rust-project} and {@code mixed-project}
 * without git history (the fixtures live inside CodeCity's own repository, whose history keeps changing), with and
 * without test code ({@code <name>.no-tests.json}); and the repository built by {@code testdata/make-history-repo.sh}
 * (fixed authors and dates, so fixed commit ids) with git history ({@code history-repo.json}) and with the history
 * replay ({@code history-repo.history.json}). That repository is built under a fixed directory name, as the city is
 * named after it.
 *
 * <p>Both documents are normalised first, by the rules of {@code scripts/city_json.py} (which the other editions
 * implement too): {@code generatedAt} and {@code buildMillis} are removed, {@code root} becomes {@code "<root>"} and
 * every building's {@code deps} are sorted. A mismatch fails with the first differences, and the actual JSON is kept
 * in {@code target/golden-actual/}. To regenerate the golden files after an intended change, build all three
 * editions and run {@code scripts/parity.sh --update-golden}, which refuses to write them unless the editions agree.
 */
class GoldenCityTest {

    private static final Path TESTDATA = Path.of("testdata").toAbsolutePath();
    private static final Path GOLDEN = TESTDATA.resolve("golden");
    /** At most this many differences are reported, as in {@code city_json.py}. */
    private static final int MAX_DIFFS = 10;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"java-project", "ts-project", "rust-project", "mixed-project"})
    void fixture(String name) throws IOException {
        check(name, TESTDATA.resolve(name), true, false, 0);
    }

    @ParameterizedTest(name = "{0} --no-tests")
    @ValueSource(strings = {"java-project", "ts-project", "rust-project", "mixed-project"})
    void fixtureWithoutTests(String name) throws IOException {
        check(name + ".no-tests", TESTDATA.resolve(name), false, false, 0);
    }

    @Test
    void historyRepoWithGit(@TempDir Path dir) throws Exception {
        check("history-repo", historyRepo(dir), true, true, 0);
    }

    @Test
    void historyRepoReplay(@TempDir Path dir) throws Exception {
        check("history-repo.history", historyRepo(dir), true, true, CityHistory.DEFAULT_SNAPSHOTS);
    }

    /** Builds the history fixture in {@code dir/history-repo}. */
    private static Path historyRepo(Path dir) throws Exception {
        Path repo = dir.resolve("history-repo");
        Process p = new ProcessBuilder("sh", TESTDATA.resolve("make-history-repo.sh").toString(), repo.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "make-history-repo.sh failed:\n" + out);
        return repo;
    }

    /**
     * Builds the city of {@code tree} like the command line does (the real path, no GitHub) and compares its
     * normalised JSON with {@code testdata/golden/<golden>.json}.
     */
    private static void check(String golden, Path tree, boolean includeTests, boolean git, int history)
            throws IOException {
        String json = CityModel.build(new SourceAnalyzer(), tree.toRealPath(), includeTests, git, null, history).json();
        Path actual = Path.of("target", "golden-actual", golden + ".json").toAbsolutePath();
        Files.createDirectories(actual.getParent());
        Files.writeString(actual, json);
        Object expected = normalise(JsonParser.parse(Files.readString(GOLDEN.resolve(golden + ".json"))));
        List<String> diffs = new ArrayList<>();
        diff(expected, normalise(JsonParser.parse(json)), "$", diffs);
        if (!diffs.isEmpty()) {
            fail(golden + ".json: the city differs from the golden file (actual JSON in " + actual + "):\n    "
                    + String.join("\n    ", diffs)
                    + "\nIf the change is intended, regenerate the golden files with scripts/parity.sh --update-golden.");
        }
    }

    /** The normalisation of {@code scripts/city_json.py}, in place. */
    @SuppressWarnings("unchecked")
    static Object normalise(Object city) {
        Map<String, Object> c = (Map<String, Object>) city;
        c.remove("generatedAt");
        c.remove("buildMillis");
        if (c.get("stats") instanceof Map<?, ?> stats) {
            stats.remove("buildMillis");
        }
        if (c.containsKey("root")) {
            c.put("root", "<root>");
        }
        if (c.get("buildings") instanceof List<?> buildings) {
            for (Object b : buildings) {
                if (b instanceof Map<?, ?> m && m.get("deps") instanceof List<?> deps) {
                    ((List<Object>) deps).sort((x, y) -> Double.compare((Double) x, (Double) y));
                }
            }
        }
        return city;
    }

    /**
     * Appends up to {@link #MAX_DIFFS} differences between {@code a} (expected) and {@code b} (actual) to {@code out},
     * depth first, like {@code city_json.py}: object keys in sorted order, numbers by value, and an array of objects
     * with ids (districts, buildings) whose ids differ reported by its missing and unexpected ids.
     */
    static void diff(Object a, Object b, String path, List<String> out) {
        if (out.size() >= MAX_DIFFS) {
            return;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            TreeSet<String> keys = new TreeSet<>();
            ma.keySet().forEach(k -> keys.add((String) k));
            mb.keySet().forEach(k -> keys.add((String) k));
            for (String k : keys) {
                if (!mb.containsKey(k)) {
                    out.add(path + "." + k + ": missing (expected " + label(ma.get(k)) + ")");
                } else if (!ma.containsKey(k)) {
                    out.add(path + "." + k + ": unexpected (got " + label(mb.get(k)) + ")");
                } else {
                    diff(ma.get(k), mb.get(k), path + "." + k, out);
                }
                if (out.size() >= MAX_DIFFS) {
                    return;
                }
            }
        } else if (a instanceof List<?> la && b instanceof List<?> lb) {
            List<Object> ia = ids(la);
            List<Object> ib = ids(lb);
            if (ia != null && ib != null && !ia.equals(ib)) {
                List<Object> missing = ia.stream().filter(x -> !ib.contains(x)).toList();
                List<Object> extra = ib.stream().filter(x -> !ia.contains(x)).toList();
                out.add(path + (missing.isEmpty() && extra.isEmpty() ? ": same ids in a different order"
                        : ": ids differ; missing " + label(missing) + ", unexpected " + label(extra)));
                return;
            }
            if (la.size() != lb.size()) {
                out.add(path + ": expected " + la.size() + " elements, got " + lb.size());
            }
            for (int i = 0; i < Math.min(la.size(), lb.size()); i++) {
                Object x = la.get(i);
                String id = x instanceof Map<?, ?> m && m.containsKey("id") ? "{" + m.get("id") + "}" : "";
                diff(x, lb.get(i), path + "[" + i + "]" + id, out);
                if (out.size() >= MAX_DIFFS) {
                    return;
                }
            }
        } else if (a == null ? b != null : !a.equals(b)) {
            // Numbers are all Doubles here, so equals compares them by value.
            out.add(path + ": expected " + label(a) + ", got " + label(b));
        }
    }

    /** The ids of an array of objects that all have one, else null. */
    private static List<Object> ids(List<?> list) {
        if (list.isEmpty() || !list.stream().allMatch(x -> x instanceof Map<?, ?> m && m.containsKey("id"))) {
            return null;
        }
        return list.stream().map(x -> (Object) ((Map<?, ?>) x).get("id")).toList();
    }

    /** A value as compact JSON, cut to 80 characters, for a difference report. */
    private static String label(Object v) {
        String s = render(v);
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    private static String render(Object v) {
        if (v instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (v instanceof Double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue()) : d.toString();
        }
        if (v instanceof List<?> l) {
            return l.stream().map(GoldenCityTest::render).collect(Collectors.joining(", ", "[", "]"));
        }
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> copy = new LinkedHashMap<>(m);
            return copy.entrySet().stream().map(e -> render(e.getKey()) + ": " + render(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        return String.valueOf(v);
    }
}
