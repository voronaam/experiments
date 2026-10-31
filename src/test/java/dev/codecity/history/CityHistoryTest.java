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
     * Runs one of the shared fixture scripts of {@code src/test/resources} in {@code dir} and returns its real path.
     * {@code history-three-commits.sh}: A appears, B appears and A grows a method, B is deleted and A gains a field.
     */
    private static Path repo(Path dir, String script) throws Exception {
        Path sh = Path.of(CityHistoryTest.class.getResource("/" + script).toURI());
        Process p = new ProcessBuilder("sh", sh.toString()).directory(dir.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor());
        return dir.toRealPath();
    }

    private static Path repo(Path dir) throws Exception {
        return repo(dir, "history-three-commits.sh");
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
        // The same, as the exact text the viewers read (the Rust and D editions assert these strings too).
        String text = model.json();
        assertTrue(text.contains("\"hist\":{\"nom\":[1,2,2],\"noa\":[0,0,1],\"loc\":[1,1,1],\"cx\":[1,2,2],"
                + "\"commits\":[1,2,3],\"authors\":[1,1,1],\"last\":[1577836800,1577923200,1578009600]}"));
        assertTrue(text.contains("\"hist\":{\"nom\":[-1,2,-1],\"noa\":[-1,0,-1],\"loc\":[-1,1,-1],\"cx\":[-1,2,-1],"
                + "\"commits\":[0,1,0],\"authors\":[0,1,0],\"last\":[-1,1577923200,-1]},\"gone\":true}"));
        assertTrue(text.contains("\"history\":{\"snapshots\":[{\"commit\":\"" + snaps.getFirst().commit()
                + "\",\"time\":1577836800,\"author\":\"Ann Author\",\"subject\":\"add A\",\"types\":1}"));
        assertTrue(text.contains("\"types\":1,\"hist\":[1,1,1]}"));
    }

    private static List<Double> toDoubles(Object list) {
        assertNotNull(list);
        return ((List<?>) list).stream().map(v -> ((Number) v).doubleValue()).toList();
    }

    /** With git turned off ({@code --no-git}) there is no history, even in a repository. */
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
    /** Without a work tree there is no history, and the city is the normal one. */
    @Test
    void noHistoryOutsideGit(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        java.nio.file.Files.writeString(root.resolve("A.java"), "class A {}");
        CityModel model = CityModel.build(new SourceAnalyzer(), root, true, true, null, 60);
        assertFalse(model.hasHistory());
        assertFalse(model.json().contains("\"hist\""));
        assertEquals(1, model.buildings().size());
    }

    /** A repository without any commit has nothing to replay either. */
    @Test
    void noHistoryWithoutCommits(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Process p = new ProcessBuilder("git", "init", "-q", "--template=", ".").directory(root.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor());
        java.nio.file.Files.writeString(root.resolve("A.java"), "class A {}");
        CityModel model = CityModel.build(new SourceAnalyzer(), root, true, true, null, 60);
        assertFalse(model.hasHistory());
        assertFalse(model.json().contains("\"hist\""));
    }

    /**
     * The sampling formula at its edges: one commit; exactly as many commits as snapshots; two snapshots (both ends
     * only); a true half rounds up (k(m-1)/(n-1) = 1.5 → 2); one commit more than snapshots skips exactly one in the
     * middle; and a long history keeps both ends and strictly increasing indices.
     */
    @Test
    void samplingFormulaEdgeCases() {
        assertArrayEquals(new int[]{}, CityHistory.sample(0, 60));
        assertArrayEquals(new int[]{0}, CityHistory.sample(1, 60));
        assertArrayEquals(new int[]{0, 1}, CityHistory.sample(2, 2));
        assertArrayEquals(new int[]{0, 2}, CityHistory.sample(3, 2));
        assertArrayEquals(new int[]{0, 99_999}, CityHistory.sample(100_000, 2));
        assertArrayEquals(new int[]{0, 2, 3}, CityHistory.sample(4, 3));
        assertArrayEquals(new int[]{0, 2, 3, 5}, CityHistory.sample(6, 4));
        int[] oneMore = CityHistory.sample(61, 60);
        assertEquals(60, oneMore.length);
        assertEquals(60, oneMore[59]);
        assertFalse(java.util.Arrays.stream(oneMore).anyMatch(i -> i == 30), "skips index 30");
        int[] most = CityHistory.sample(100_000, CityHistory.MAX_SNAPSHOTS);
        assertEquals(500, most.length);
        assertEquals(0, most[0]);
        assertEquals(99_999, most[499]);
        for (int k = 1; k < most.length; k++) {
            assertTrue(most[k] > most[k - 1]);
        }
        assertEquals(List.of(60, 2, 500), List.of(CityHistory.DEFAULT_SNAPSHOTS, CityHistory.MIN_SNAPSHOTS,
                CityHistory.MAX_SNAPSHOTS));
    }

    // ------------------------------------------------------------------ rules, on history-replay-repo.sh

    /** Author time of commit {@code n} of history-replay-repo.sh: midnight UTC, one day apart from 2020-01-01. */
    private static long day(int n) {
        return 1_577_836_800L + (n - 1) * 86_400L;
    }

    /**
     * The JSON text of the entry with this id in the model's {@code "districts"} or {@code "buildings"} array: from its
     * opening brace up to the next entry. Every edition slices the JSON the same way, so the assertions read alike.
     */
    private static String entry(String json, String array, String id) {
        int from = json.indexOf("\"" + array + "\":[");
        assertTrue(from >= 0, array);
        int at = json.indexOf("{\"id\":\"" + id + "\",", from);
        assertTrue(at >= 0, "no entry " + id);
        int next = json.indexOf("{\"id\":", at + 1);
        return json.substring(at, next < 0 ? json.length() : next);
    }

    private static CityModel replay(Path root, boolean includeTests, int snapshots) throws Exception {
        CityModel model = CityModel.build(new SourceAnalyzer(), root, includeTests, true, null, snapshots);
        assertTrue(model.hasHistory());
        return model;
    }

    private static List<String> ids(CityModel model) {
        return model.buildings().stream().map(b -> b.type().fqn()).sorted().toList();
    }

    /**
     * The city is the union of today's types and every type of any snapshot. A type deleted and added again (C) is
     * present, with -1 for the snapshot it was missing from; types that no longer exist (D, p.P) are "gone" and keep
     * their last version's file. The statistics count today's types only.
     */
    @Test
    void theCityIsTheUnionOfAllSnapshots(@TempDir Path dir) throws Exception {
        Path root = repo(dir, "history-replay-repo.sh");
        CityModel model = replay(root, true, 60);
        assertEquals(List.of(4, 5, 3, 4), model.history().stream().map(Snapshot::types).toList());
        assertEquals(List.of("A", "ATest", "C", "D", "O", "p.P"), ids(model));
        String json = model.json();
        String c = entry(json, "buildings", "C");
        assertTrue(c.contains("\"hist\":{\"nom\":[-1,0,-1,1],\"noa\":[-1,0,-1,0],"), c);
        assertFalse(c.contains("\"gone\""));
        String d = entry(json, "buildings", "D");
        assertTrue(d.contains("\"nom\":[-1,0,-1,-1]") && d.contains("\"gone\":true"), d);
        String p = entry(json, "buildings", "p.P");
        assertTrue(p.contains("\"file\":\"src/p/P.java\"") && p.contains("\"nom\":[0,-1,-1,-1]")
                && p.contains("\"gone\":true"), p);
        assertEquals(4, model.stats().types());
    }

    /**
     * A type's git activity in snapshot k counts the commits of its file with an author time up to snapshot k's (the
     * committer times, an hour later, play no part): C's file is added, deleted and added again by three authors.
     * Where the type does not exist, the activity is 0, 0, -1 even though its file had commits.
     */
    @Test
    void gitActivityIsCountedUpToEachSnapshot(@TempDir Path dir) throws Exception {
        Path root = repo(dir, "history-replay-repo.sh");
        CityModel model = replay(root, true, 60);
        assertEquals(List.of(day(1), day(2), day(3), day(4)), model.history().stream().map(Snapshot::time).toList());
        assertEquals(List.of("Ann Author", "Bob Builder", "Ann Author", "Cid Coder"),
                model.history().stream().map(Snapshot::author).toList());
        String json = model.json();
        assertTrue(entry(json, "buildings", "A").contains("\"commits\":[1,2,2,2],\"authors\":[1,2,2,2],\"last\":["
                + day(1) + "," + day(2) + "," + day(2) + "," + day(2) + "]}"));
        assertTrue(entry(json, "buildings", "C").contains("\"commits\":[0,1,0,3],\"authors\":[0,1,0,3],\"last\":[-1,"
                + day(2) + ",-1," + day(4) + "]}"));
    }

    /**
     * A district's history marks the snapshots in which it, or a sub-district, holds an existing building: "p" only in
     * the first. Its type count is of today's types, so 0.
     */
    @Test
    void districtHistoryFollowsItsBuildings(@TempDir Path dir) throws Exception {
        Path root = repo(dir, "history-replay-repo.sh");
        String json = replay(root, true, 60).json();
        assertTrue(entry(json, "districts", "p").contains("\"types\":0,\"hist\":[1,0,0,0]}"));
        assertTrue(entry(json, "districts", "(default)").contains("\"types\":4,\"hist\":[1,1,1,1]}"));
        assertTrue(entry(json, "districts", "").contains("\"types\":4,\"hist\":[1,1,1,1]}"));
    }

    /**
     * A's attribute count shrinks from 3 to 1. Its building has today's side, but its lot is reserved for the widest
     * version: the footprint of side(3), centred on the building, keeps a street's width from every other building.
     */
    @Test
    void lotsFitTheWidestVersion(@TempDir Path dir) throws Exception {
        Path root = repo(dir, "history-replay-repo.sh");
        CityModel model = replay(root, true, 60);
        CityModel.Building a = model.buildings().stream().filter(b -> b.type().fqn().equals("A")).findFirst().orElseThrow();
        assertArrayEquals(new int[]{3, 1, 1, 1}, a.hist().noa);
        assertEquals(CityLayout.side(1), a.lot().side(), 1e-9);
        double reserved = CityLayout.side(3);
        boolean touches = false;
        for (CityModel.Building b : model.buildings()) {
            if (b == a || b.lot().districtIndex() != a.lot().districtIndex()) {
                continue;
            }
            double gapX = Math.abs(a.lot().x() - b.lot().x()) - (reserved + b.lot().side()) / 2;
            double gapZ = Math.abs(a.lot().z() - b.lot().z()) - (reserved + b.lot().side()) / 2;
            assertTrue(Math.max(gapX, gapZ) >= CityLayout.GAP - 1e-6, b.type().fqn());
            // Some neighbour must be closer than a street to today's smaller footprint plus the reserved margin;
            // otherwise the test would not tell the reserved lot from the plain one.
            touches |= Math.max(gapX, gapZ) < CityLayout.GAP + (reserved - CityLayout.side(1)) / 2 - 1e-6;
        }
        assertTrue(touches, "a neighbour stands next to A's reserved lot");
    }

    /**
     * With two snapshots only the first and the last commit are replayed. D, which existed only in between, is not
     * part of the city at all; the activity is still counted over every commit up to the snapshot.
     */
    @Test
    void samplingKeepsBothEndsAndDropsTypesInBetween(@TempDir Path dir) throws Exception {
        Path root = repo(dir, "history-replay-repo.sh");
        CityModel model = replay(root, true, 2);
        assertEquals(List.of("one", "four"), model.history().stream().map(Snapshot::subject).toList());
        assertEquals(List.of(git(root, "rev-list", "--max-parents=0", "HEAD"), git(root, "rev-parse", "HEAD")),
                model.history().stream().map(Snapshot::commit).toList());
        assertEquals(List.of("A", "ATest", "C", "O", "p.P"), ids(model));
        String json = model.json();
        assertTrue(entry(json, "buildings", "C").contains("\"nom\":[-1,1],"));
        assertTrue(entry(json, "buildings", "C").contains("\"commits\":[0,3],"));
        assertTrue(entry(json, "buildings", "p.P").contains("\"nom\":[0,-1],"));
        assertTrue(entry(json, "buildings", "A").contains("\"commits\":[1,2],"));
    }

    /** Without tests, test types are left out of every snapshot (ATest is in a test directory). */
    @Test
    void testsAreLeftOutOfEverySnapshot(@TempDir Path dir) throws Exception {
        Path root = repo(dir, "history-replay-repo.sh");
        CityModel model = replay(root, false, 60);
        assertEquals(List.of(3, 4, 2, 3), model.history().stream().map(Snapshot::types).toList());
        assertEquals(List.of("A", "C", "D", "O", "p.P"), ids(model));
    }

    /**
     * Analysing a sub-directory replays only its files (other/O.java never appears), with paths relative to it; the
     * git activity is still found, as the history keys are absolute.
     */
    @Test
    void aSubDirectoryReplaysOnlyItsFiles(@TempDir Path dir) throws Exception {
        Path root = repo(dir, "history-replay-repo.sh").resolve("src");
        CityModel model = replay(root, true, 60);
        assertEquals(List.of(3, 4, 2, 3), model.history().stream().map(Snapshot::types).toList());
        assertEquals(List.of("A", "ATest", "C", "D", "p.P"), ids(model));
        String json = model.json();
        assertTrue(entry(json, "buildings", "p.P").contains("\"file\":\"p/P.java\""));
        assertTrue(entry(json, "buildings", "A").contains("\"commits\":[1,2,2,2],"));
    }
}
