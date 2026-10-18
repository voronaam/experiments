package dev.codecity.history;

import dev.codecity.CityModel;
import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.json.JsonParser;
import dev.codecity.layout.CityLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared history scenario of all three editions (Java, Rust, D): same repository, same expectations. */
class CityHistoryTest {

    /**
     * Three commits with fixed identities and dates: A appears, B appears and A grows a method, B is deleted and A
     * gains a field.
     */
    private static final String SCRIPT = """
            set -e
            export GIT_AUTHOR_NAME="Ann Author" GIT_AUTHOR_EMAIL=ann@example.com
            export GIT_COMMITTER_NAME="Ann Author" GIT_COMMITTER_EMAIL=ann@example.com
            git init -q .
            mkdir src
            commit() { GIT_AUTHOR_DATE="$1 +0000" GIT_COMMITTER_DATE="$1 +0000" git -c commit.gpgsign=false commit -q -m "$2"; }
            echo 'class A { void a() {} }' > src/A.java
            git add -A && commit "2020-01-01T00:00:00" "add A"
            echo 'class B { void x() {} void y() {} }' > src/B.java
            echo 'class A { void a() {} void b() {} }' > src/A.java
            git add -A && commit "2020-01-02T00:00:00" "add B"
            rm src/B.java
            echo 'class A { int f; void a() {} void b() {} }' > src/A.java
            git add -A && commit "2020-01-03T00:00:00" "drop B"
            """;

    private static Path repo(Path dir) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", SCRIPT).directory(dir.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor());
        return dir.toRealPath();
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.waitFor());
        return out;
    }

    @Test
    void replaysThreeCommits(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        CityModel model = CityModel.build(new SourceAnalyzer(), root, true, true, null, 60);
        assertTrue(model.hasHistory());

        List<Snapshot> snaps = model.history();
        assertEquals(3, snaps.size());
        assertEquals(List.of(git(root, "rev-parse", "HEAD~2"), git(root, "rev-parse", "HEAD~1"),
                git(root, "rev-parse", "HEAD")), snaps.stream().map(Snapshot::commit).toList());
        assertEquals(List.of(1577836800L, 1577923200L, 1578009600L), snaps.stream().map(Snapshot::time).toList());
        assertEquals(List.of("add A", "add B", "drop B"), snaps.stream().map(Snapshot::subject).toList());
        assertEquals("Ann Author", snaps.getFirst().author());
        assertEquals(List.of(1, 2, 1), snaps.stream().map(Snapshot::types).toList());

        CityModel.Building a = model.buildings().stream().filter(b -> b.type().fqn().equals("A")).findFirst().orElseThrow();
        CityModel.Building b = model.buildings().stream().filter(x -> x.type().fqn().equals("B")).findFirst().orElseThrow();
        assertArrayEquals(new int[]{1, 2, 2}, a.hist().nom);
        assertArrayEquals(new int[]{0, 0, 1}, a.hist().noa);
        assertArrayEquals(new int[]{1, 2, 3}, a.hist().commits);
        assertArrayEquals(new int[]{1, 1, 1}, a.hist().authors);
        assertArrayEquals(new long[]{1577836800L, 1577923200L, 1578009600L}, a.hist().last);
        assertFalse(a.gone());
        assertArrayEquals(new int[]{-1, 2, -1}, b.hist().nom);
        assertArrayEquals(new int[]{0, 1, 0}, b.hist().commits);
        assertArrayEquals(new long[]{-1, 1577923200L, -1}, b.hist().last);
        assertTrue(b.gone());
        // The gone type keeps its last version's file, mapped back into the analysed root.
        assertEquals("src/B.java", b.file());

        // Stats count present types only; the footprint is sized for A's largest attribute count.
        assertEquals(1, model.stats().types());
        assertEquals(CityLayout.side(1), a.lot().side(), 1e-9);
        // A gone building keeps the side of its last-seen version; districts count present types only.
        assertEquals(CityLayout.side(0), b.lot().side(), 1e-9);
        assertEquals(1, model.districts().getFirst().types());

        // Every district holding A exists in all three snapshots.
        for (int d = 0; d < model.districts().size(); d++) {
            for (int k = 0; k < 3; k++) {
                assertTrue(model.districtExists(d, k));
            }
        }

        // JSON: "gone" only on B, "hist" on both, districts carry their hist, "history" lists the snapshots.
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) JsonParser.parse(model.json());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buildings = (List<Map<String, Object>>) json.get("buildings");
        Map<String, Object> ja = buildings.stream().filter(x -> x.get("id").equals("A")).findFirst().orElseThrow();
        Map<String, Object> jb = buildings.stream().filter(x -> x.get("id").equals("B")).findFirst().orElseThrow();
        assertFalse(ja.containsKey("gone"));
        assertEquals(Boolean.TRUE, jb.get("gone"));
        assertEquals(List.of(-1.0, 2.0, -1.0), toDoubles(JsonParser.path(jb, "hist", "nom")));
        assertEquals(List.of(1.0, 1.0, 1.0), toDoubles(((Map<?, ?>) ((List<?>) json.get("districts")).getFirst()).get("hist")));
        assertEquals(List.of(1.0, 2.0, 1.0), ((List<?>) JsonParser.path(json, "history", "snapshots")).stream()
                .map(s -> ((Number) ((Map<?, ?>) s).get("types")).doubleValue()).toList());
    }

    private static List<Double> toDoubles(Object list) {
        assertNotNull(list);
        return ((List<?>) list).stream().map(v -> ((Number) v).doubleValue()).toList();
    }

    @Test
    void noHistoryWithoutGit(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        CityModel model = CityModel.build(new SourceAnalyzer(), root, true, false, null, 60);
        assertFalse(model.hasHistory());
        assertFalse(model.json().contains("\"hist\""));
    }

    @Test
    void samplesEvenlyIncludingBothEnds() {
        assertArrayEquals(new int[]{0, 3, 6, 9}, CityHistory.sample(10, 4));
        assertArrayEquals(new int[]{0, 1, 2}, CityHistory.sample(3, 60));
        int[] idx = CityHistory.sample(100, 60);
        assertEquals(60, idx.length);
        assertEquals(0, idx[0]);
        assertEquals(99, idx[59]);
        for (int k = 1; k < idx.length; k++) {
            assertTrue(idx[k] > idx[k - 1]);
        }
    }
}
