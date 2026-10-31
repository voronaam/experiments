package dev.codecity.desktop;

import dev.codecity.CityModel;
import dev.codecity.CityModel.Building;
import dev.codecity.analysis.FileHistory;
import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.analysis.TypeInfo;
import dev.codecity.github.GitHubInfo;
import dev.codecity.github.GitHubRepo;
import dev.codecity.github.PullRequest;
import dev.codecity.layout.CityLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link ViewRules}: search ranking, heights, shading, district labels, dependency arcs, the inspector's and
 * tooltip's texts, GitHub links and where walk mode starts.
 *
 * <p>Most scenarios run on the city of {@code testdata/java-project} (built without git, like its golden file, so its
 * indices and numbers are fixed). Rust's {@code rules_tests.rs} and D's {@code rules.d} test the same scenarios with
 * the same expected values.
 */
class ViewRulesTest {

    /** 2026-09-30T12:00:00Z, the "now" of every age in these tests. */
    private static final long NOW = 1_790_769_600L;
    private static final long DAY = 86_400;

    /** Building indices in the java-project city (see {@code testdata/golden/java-project.json}). */
    private static final int MAIN = 4;
    private static final int MAIN_TEST = 5;
    private static final int REGISTRY = 11;

    private static final IntPredicate ALL = i -> true;

    /** The city of {@code testdata/<name>}, with test code, without git and GitHub. */
    private static CityModel fixture(String name) throws IOException {
        Path root = Path.of("testdata", name).toRealPath();
        return CityModel.build(new SourceAnalyzer(), root, true, false, null);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-3;
    }

    /** A copy of {@code b} with git statistics and open pull requests. */
    private static Building withHistory(Building b, FileHistory h, int... prs) {
        return new Building(b.index(), b.type(), b.lot(), b.file(), h, b.deps(), b.incoming(), prs, b.methods(), b.hist(),
                b.gone());
    }

    /** A copy of the type of {@code b} with another simple name. */
    private static TypeInfo renamed(TypeInfo t, String name) {
        return new TypeInfo(t.language(), t.fqn(), name, t.packageName(), t.module(), t.kind(), t.file(), t.line(),
                t.endLine(), t.loc(), t.noa(), t.test(), t.methods(), t.references(), t.explicitImports(),
                t.wildcardImports());
    }

    // ------------------------------------------------------------------ search

    @Test
    void searchRanksExactThenPrefixThenSubstringThenQualifiedName() throws IOException {
        CityModel m = fixture("java-project");
        assertArrayEquals(new int[]{11, 16, 14, 17, 12, 15, 13}, ViewRules.search("registry", m.types(), ALL));
        assertArrayEquals(new int[]{11, 16, 14, 17, 12, 15, 13}, ViewRules.search("  REG ", m.types(), ALL));
        assertArrayEquals(new int[]{19, 5, 11, 9, 16, 14, 17, 12, 15, 13}, ViewRules.search("st", m.types(), ALL));
        assertArrayEquals(new int[]{19}, ViewRules.search("acme.core.util", m.types(), ALL));
        assertArrayEquals(new int[0], ViewRules.search("zzz", m.types(), ALL));
    }

    @Test
    void searchNeedsTwoCharactersAndSkipsHiddenTypes() throws IOException {
        CityModel m = fixture("java-project");
        assertNull(ViewRules.search("x", m.types(), ALL));
        assertNull(ViewRules.search("  x  ", m.types(), ALL));
        assertNull(ViewRules.search("", m.types(), ALL));
        // One character outside the BMP is two UTF-16 units, as in the browser: a search, with no hits.
        assertArrayEquals(new int[0], ViewRules.search("😀", m.types(), ALL));
        // "é" is one unit: still too short.
        assertNull(ViewRules.search("é", m.types(), ALL));
        assertArrayEquals(new int[]{MAIN_TEST, 9}, ViewRules.search("test", m.types(), ALL));
        assertArrayEquals(new int[0], ViewRules.search("test", m.types(), i -> !m.types().get(i).test()));
    }

    @Test
    void searchTiesAreOrderedByUtf16Length() throws IOException {
        CityModel m = fixture("java-project");
        List<TypeInfo> types = new ArrayList<>(m.types());
        types.set(0, renamed(types.get(0), "Abcd"));
        types.set(1, renamed(types.get(1), "Äbc"));
        assertArrayEquals(new int[]{1, 0}, ViewRules.search("bc", types, i -> i < 2));
    }

    // ------------------------------------------------------------------ heights

    @Test
    void heightsScaleThe95thPercentileToAFifthOfTheRadius() {
        assertEquals(20, ViewRules.radius(17.131, 27.531));
        assertEquals(50, ViewRules.radius(60, 80));
        assertEquals(8, ViewRules.heightScale(new double[0], 20));
        assertEquals(8, ViewRules.heightScale(new double[]{0, 0, 0}, 20));
        double[] hundred = IntStream.rangeClosed(0, 100).asDoubleStream().toArray();
        assertTrue(close(ViewRules.heightScale(hundred, 50), 10.0 / 95));
        assertTrue(close(ViewRules.heightScale(hundred, 10), 8.0 / 95));
        assertTrue(close(ViewRules.heightScale(hundred, 200), 16.0 / 95));
        assertEquals(4, ViewRules.heightScale(new double[]{4, 1, 2}, 20));
        assertEquals(0.35, ViewRules.height(0, 2, 20));
        assertEquals(6.35, ViewRules.height(3, 2, 20));
        assertEquals(15, ViewRules.height(1e9, 1, 20));
    }

    @Test
    void fixtureHeightsFollowTheMethodCount() throws IOException {
        CityModel m = fixture("java-project");
        double r = ViewRules.radius(m.width(), m.depth());
        double s = ViewRules.heightScale(m.types().stream().mapToDouble(TypeInfo::nom).toArray(), r);
        assertEquals(2, s);
        assertEquals(2.35, ViewRules.height(m.types().get(0).nom(), s, r));
        assertEquals(15, ViewRules.height(m.types().get(REGISTRY).nom(), s, r));
    }

    // ------------------------------------------------------------------ colours

    @Test
    void colourMetricFallsBackWhenTheModelLacksItsData() {
        assertEquals(Look.ColorMetric.COMMITS, ViewRules.effectiveColor(null, true, false));
        assertEquals(Look.ColorMetric.CX, ViewRules.effectiveColor(null, false, false));
        assertEquals(Look.ColorMetric.LOC, ViewRules.effectiveColor(Look.ColorMetric.LOC, false, false));
        assertEquals(Look.ColorMetric.AGE, ViewRules.effectiveColor(Look.ColorMetric.AGE, true, false));
        assertEquals(Look.ColorMetric.CX, ViewRules.effectiveColor(Look.ColorMetric.AGE, false, true));
        assertEquals(Look.ColorMetric.COMMITS, ViewRules.effectiveColor(Look.ColorMetric.PRS, true, false));
        assertEquals(Look.ColorMetric.PRS, ViewRules.effectiveColor(Look.ColorMetric.PRS, false, true));
        assertEquals(" (no git)", Look.ColorMetric.AUTHORS.unavailableReason());
        assertEquals(" (no GitHub)", Look.ColorMetric.PRS.unavailableReason());
        assertTrue(Look.ColorMetric.KIND.available(false, false));
    }

    @Test
    void selectionDimsEverythingButItsNeighbours() throws IOException {
        CityModel m = fixture("java-project");
        Building sel = m.buildings().get(REGISTRY);
        assertArrayEquals(new int[]{4, 5, 11, 14, 15, 16, 18},
                IntStream.range(0, m.buildings().size()).filter(i -> ViewRules.related(sel, i)).toArray());
        assertTrue(IntStream.range(0, m.buildings().size()).allMatch(i -> ViewRules.related(null, i)));
    }

    @Test
    void shadingDimsThenLightensTheHoveredBuilding() {
        javafx.scene.paint.Color base = javafx.scene.paint.Color.web("#2b6cb0");
        javafx.scene.paint.Color dim = javafx.scene.paint.Color.web("#d5dbe3");
        assertEquals("#2b6cb0", Look.hex(ViewRules.shade(base, dim, false, false, false)));
        assertEquals("#b3c5d9", Look.hex(ViewRules.shade(base, dim, true, false, false)));
        assertEquals("#b3c5d9", Look.hex(ViewRules.shade(base, dim, false, true, false)));
        assertEquals("#b3c5d9", Look.hex(ViewRules.shade(base, dim, true, true, false)));
        assertEquals("#6b98c8", Look.hex(ViewRules.shade(base, dim, false, false, true)));
        assertEquals("#cad6e4", Look.hex(ViewRules.shade(base, dim, true, false, true)));
    }

    // ------------------------------------------------------------------ districts and labels

    /** A district for the label scenarios. */
    private static CityLayout.District district(String label, int depth, double x, double w, int types) {
        return new CityLayout.District(label, label, depth, x, 0, w, 2, types);
    }

    @Test
    void labelsGoToTheLargestUncrowdedShallowDistricts() {
        List<CityLayout.District> ds = new ArrayList<>(List.of(district("city", 0, 0, 100, 50),
                district("deep", 3, 0, 50, 1), district("empty", 1, 0, 40, 0)));
        for (int k = 0; k < 30; k++) {
            ds.add(district("p" + k, 1 + k % 2, k == 1 ? 5 : k * 10.0, 30 - k * 0.5, 1));
        }
        List<ViewRules.Label> labels = ViewRules.labels(ds);
        int[] expected = IntStream.concat(IntStream.of(3), IntStream.rangeClosed(5, 19)).toArray();
        assertArrayEquals(expected, labels.stream().mapToInt(ViewRules.Label::district).toArray());
        assertEquals(ViewRules.MAX_LABELS, labels.size());
        assertEquals("p0", labels.get(0).text());
        assertTrue(close(labels.get(0).x(), 0) && close(labels.get(0).y(), 1.0) && close(labels.get(0).z(), 1));
        assertTrue(close(labels.get(2).x(), 30) && close(labels.get(2).y(), 1.4));
    }

    @Test
    void labelsConsiderOnlyThe24LargestCandidates() {
        List<CityLayout.District> ds = new ArrayList<>();
        for (int k = 0; k < 30; k++) {
            ds.add(district("p" + k, 1, k * 10.0, 1 + k, 1));
        }
        assertArrayEquals(IntStream.iterate(29, k -> k - 1).limit(16).toArray(),
                ViewRules.labels(ds).stream().mapToInt(ViewRules.Label::district).toArray());
        List<CityLayout.District> crowded = List.of(district("a", 1, 0, 10, 1), district("b", 1, 7, 9, 1),
                district("c", 1, 14, 8, 1));
        assertArrayEquals(new int[]{0, 2}, ViewRules.labels(crowded).stream().mapToInt(ViewRules.Label::district).toArray());
    }

    @Test
    void fixtureLabelsSkipDistrictsThatWouldOverlap() throws IOException {
        List<ViewRules.Label> labels = ViewRules.labels(fixture("java-project").districts());
        assertEquals(List.of("core", "app"), labels.stream().map(ViewRules.Label::text).toList());
        assertArrayEquals(new int[]{1, 4}, labels.stream().mapToInt(ViewRules.Label::district).toArray());
        ViewRules.Label core = labels.get(0);
        assertTrue(close(core.x(), -0.366) && close(core.y(), 1.0) && close(core.z(), 2.7655));
        assertTrue(close(labels.get(1).x(), -3.4) && close(labels.get(1).z(), 12.766));
    }

    @Test
    void presentDayDistrictsHideOnlyEmptiedOnesOfAHistoryCity() {
        CityLayout.District a = district("a", 1, 0, 1, 2);
        CityLayout.District b = district("b", 1, 0, 1, 0);
        assertTrue(ViewRules.presentDistrict(a, false) && ViewRules.presentDistrict(b, false));
        assertTrue(ViewRules.presentDistrict(a, true) && !ViewRules.presentDistrict(b, true));
    }

    // ------------------------------------------------------------------ dependency arcs

    @Test
    void arcsJoinTheSelectionWithItsVisibleNeighbours() throws IOException {
        CityModel m = fixture("java-project");
        Building sel = m.buildings().get(REGISTRY);
        assertEquals(List.of(new ViewRules.ArcEnds(11, 14, true), new ViewRules.ArcEnds(11, 15, true),
                        new ViewRules.ArcEnds(11, 16, true), new ViewRules.ArcEnds(11, 18, true),
                        new ViewRules.ArcEnds(4, 11, false), new ViewRules.ArcEnds(5, 11, false),
                        new ViewRules.ArcEnds(15, 11, false)),
                // The model's dependency order differs between editions (the golden files sort it): compare sorted.
                ViewRules.arcs(sel, ALL).stream().sorted(java.util.Comparator.comparing((ViewRules.ArcEnds a) -> !a.outgoing())
                        .thenComparingInt(ViewRules.ArcEnds::from).thenComparingInt(ViewRules.ArcEnds::to)).toList());
        IntPredicate noTests = i -> !m.types().get(i).test();
        List<ViewRules.ArcEnds> filtered = ViewRules.arcs(sel, noTests);
        assertEquals(6, filtered.size());
        assertTrue(filtered.stream().noneMatch(a -> a.from() == MAIN_TEST));
        assertTrue(ViewRules.arcs(m.buildings().get(MAIN_TEST), noTests).isEmpty());
        assertTrue(ViewRules.arcs(null, ALL).isEmpty());
        assertEquals(7, ViewRules.arcs(m.buildings().get(MAIN), ALL).size());
    }

    @Test
    void arcsAreCappedAt200PerDirection() {
        Building b = new Building(0, null, new CityLayout.Lot(0, 0, 1, 0, 0), "", null,
                IntStream.rangeClosed(1, 250).toArray(), IntStream.rangeClosed(251, 260).toArray(), new int[0], List.of(),
                null, false);
        List<ViewRules.ArcEnds> all = ViewRules.arcs(b, ALL);
        assertEquals(ViewRules.MAX_ARCS, all.stream().filter(ViewRules.ArcEnds::outgoing).count());
        assertEquals(10, all.stream().filter(a -> !a.outgoing()).count());
        assertEquals(200, all.get(199).to());
        assertEquals(125, ViewRules.arcs(b, i -> i % 2 == 0 || i > 250).stream().filter(ViewRules.ArcEnds::outgoing).count());
    }

    @Test
    void arcShapeRisesWithLengthAndThinsForBusySelections() {
        double dist = Math.sqrt(36 + 4 + 64);
        assertTrue(close(ViewRules.arcControlHeight(2, 4, dist), 4 + dist * 0.35 + 2));
        assertTrue(close(ViewRules.arcRadius(40, 10), 0.06));
        assertTrue(close(ViewRules.arcRadius(41, 10), 0.035));
        assertTrue(close(ViewRules.particleSpeed(1), 2.8));
        assertTrue(close(ViewRules.particleSpeed(10), 14.0 / 12));
    }

    // ------------------------------------------------------------------ inspector and tooltip

    private static PullRequest pr(int n, String author, String updated, boolean draft, int files) {
        return new PullRequest(n, "PR " + n, "https://github.com/o/r/pull/" + n, author, updated, draft,
                IntStream.range(0, files).mapToObj(k -> "f" + k).toList());
    }

    private static List<String> flat(List<String[]> cells) {
        return cells.stream().map(c -> c[0] + "|" + c[1]).toList();
    }

    @Test
    void inspectorShowsMetricsAndGitHistory() throws IOException {
        CityModel m = fixture("java-project");
        Building reg = m.buildings().get(REGISTRY);
        List<String> plain = List.of("8|methods", "4|attributes", "111|lines of code", "25|complexity", "3.1|cx / method",
                "4 / 3|uses / used by");
        assertEquals(plain, flat(ViewRules.metricCells(reg, false, 4, 3, NOW)));
        List<String> gitless = new ArrayList<>(plain);
        gitless.addAll(List.of("0|commits", "0|authors", "—|last change"));
        assertEquals(gitless, flat(ViewRules.metricCells(reg, true, 4, 3, NOW)));
        Building h = withHistory(reg, new FileHistory(12, 3, NOW - 5 * DAY, NOW - 400 * DAY));
        assertEquals(List.of("12|commits", "3|authors", "5d ago|last change"),
                flat(ViewRules.metricCells(h, true, 4, 3, NOW)).subList(6, 9));
        Building scratch = m.buildings().get(1);
        TypeInfo t = scratch.type();
        TypeInfo big = new TypeInfo(t.language(), t.fqn(), t.name(), t.packageName(), t.module(), t.kind(), t.file(),
                t.line(), t.endLine(), 12_345, t.noa(), t.test(), t.methods(), t.references(), t.explicitImports(),
                t.wildcardImports());
        List<String[]> c = ViewRules.metricCells(new Building(1, big, scratch.lot(), scratch.file(), null, new int[0],
                new int[0], new int[0], List.of(), null, false), false, 0, 0, NOW);
        assertEquals("0", c.get(0)[0]);
        assertEquals("12.3k", c.get(2)[0]);
        assertEquals("0", c.get(4)[0]);
    }

    @Test
    void inspectorPackageLineAndMethodNames() throws IOException {
        CityModel m = fixture("java-project");
        assertEquals("com.acme.core · core", ViewRules.packageLine(m.types().get(REGISTRY)));
        assertEquals("(default package)", ViewRules.packageLine(m.types().get(0)));
        CityModel mixed = fixture("mixed-project");
        assertEquals("Counter", mixed.types().get(4).name());
        assertEquals("(root) · mix_native", ViewRules.packageLine(mixed.types().get(4)));
        assertEquals(List.of("retries(…)", "add(…)", "snapshot()"), m.buildings().get(REGISTRY).methods().stream()
                .limit(3).map(x -> ViewRules.methodName(x.name(), x.parameters())).toList());
    }

    @Test
    void inspectorLists15MethodsAndCountsTheRest(@TempDir Path dir) throws IOException {
        assertNull(ViewRules.moreMethods(0));
        assertNull(ViewRules.moreMethods(15));
        assertEquals("+ 1 more", ViewRules.moreMethods(16));
        assertEquals("+ 306 more", ViewRules.moreMethods(321));
        StringBuilder src = new StringBuilder("class Big {\n");
        for (int k = 0; k < 320; k++) {
            src.append("  void m").append(k).append("() {}\n");
        }
        src.append("  int hot(int a) { if (a > 1) { return 1; } if (a > 2) { return 2; } return 0; }\n}\n");
        Files.writeString(dir.resolve("Big.java"), src);
        CityModel m = CityModel.build(new SourceAnalyzer(), dir.toRealPath(), true, false, null);
        assertEquals(321, m.types().get(0).methods().size());
        assertEquals(300, m.buildings().get(0).methods().size());
        assertEquals("hot", m.buildings().get(0).methods().get(0).name());
        assertEquals("+ 306 more", ViewRules.moreMethods(m.types().get(0).methods().size()));
    }

    @Test
    void inspectorDescribesPullRequests() {
        assertEquals("ada · 5d ago · 2 files · draft", ViewRules.prMeta(pr(7, "ada", "2026-09-25T12:00:00Z", true, 2), NOW));
        assertEquals("ghost · today · 1 files", ViewRules.prMeta(pr(8, null, "2026-09-30T11:00:00Z", false, 1), NOW));
        assertEquals("bo · — · 0 files", ViewRules.prMeta(pr(9, "bo", "yesterday", false, 0), NOW));
        assertEquals("bo · — · 0 files", ViewRules.prMeta(pr(9, "bo", null, false, 0), NOW));
        assertEquals("none", ViewRules.noPulls("all open PRs"));
        assertEquals("none among the latest 30 open PRs (no token)", ViewRules.noPulls("latest 30 open PRs (no token)"));
        assertEquals("none among the ", ViewRules.noPulls(null));
    }

    @Test
    void githubLinksEncodeEachPathSegment() throws IOException {
        GitHubRepo repo = GitHubRepo.parse("https://github.com/o/r").orElseThrow();
        GitHubInfo gh = new GitHubInfo(repo, "main", "sub dir/", null, null);
        String file = "core/src/A B#1Ä.java";
        assertEquals("https://github.com/o/r/blob/main/sub%20dir/core/src/A%20B%231%C3%84.java#L3-L9", gh.link("blob", file, 3, 9));
        assertEquals("https://github.com/o/r/blame/main/sub%20dir/core/src/A%20B%231%C3%84.java#L3-L9", gh.link("blame", file, 3, 9));
        assertEquals("https://github.com/o/r/commits/feature/x%20y/a/b.rs",
                new GitHubInfo(repo, "feature/x y", "", null, null).link("commits", "a/b.rs", 1, 2));
        assertTrue(Look.isWebLink(gh.link("blob", file, 1, 1)));
    }

    @Test
    void isoTimestampsWithOffsets() {
        assertEquals(NOW, ViewRules.parseIso("2026-09-30T12:00:00Z"));
        assertEquals(NOW, ViewRules.parseIso("2026-09-30T14:00:00+02:00"));
        assertEquals(NOW, ViewRules.parseIso("2026-09-30T06:30:00.5-05:30"));
        assertEquals(0, ViewRules.parseIso("1970-01-01T00:00:00Z"));
        assertEquals(1_709_164_800L, ViewRules.parseIso("2024-02-29T00:00:00Z"));
        assertEquals(0, ViewRules.parseIso("yesterday"));
        assertEquals(0, ViewRules.parseIso(""));
    }

    @Test
    void tooltipsSummariseBuildingsAndDistricts() throws IOException {
        CityModel m = fixture("java-project");
        Building reg = m.buildings().get(REGISTRY);
        assertEquals("8 methods · 4 attrs · 111 loc · cx 25", ViewRules.tooltipRow(reg));
        FileHistory h = new FileHistory(12, 3, NOW, NOW);
        assertEquals("8 methods · 4 attrs · 111 loc · cx 25 · 12 commits · 1 open PR", ViewRules.tooltipRow(withHistory(reg, h, 0)));
        assertTrue(ViewRules.tooltipRow(withHistory(reg, h, 0, 1)).endsWith(" · 2 open PRs"));
        assertNull(ViewRules.districtTooltip(m.districts().get(0), false));
        assertArrayEquals(new String[]{"core", "core", "13 types · double-click to zoom"},
                ViewRules.districtTooltip(m.districts().get(1), false));
        assertArrayEquals(new String[]{"com.acme.core", "core/com.acme.core", "13 types"},
                ViewRules.districtTooltip(m.districts().get(2), true));
    }

    @Test
    void replayTextsMatchTheBrowser() throws IOException {
        CityModel m = fixture("java-project");
        dev.codecity.history.TypeHistory h = new dev.codecity.history.TypeHistory(2);
        h.nom[1] = 3;
        h.noa[1] = 1;
        h.loc[1] = 12_345;
        h.cx[1] = 5;
        h.commits[1] = 4;
        Building reg = m.buildings().get(REGISTRY);
        Building b = new Building(reg.index(), reg.type(), reg.lot(), reg.file(), null, reg.deps(), reg.incoming(),
                new int[0], reg.methods(), h, false);
        // Snapshot 1: the snapshot's numbers and date; commits only for a file with git statistics today.
        assertEquals("3 methods · 1 attrs · 12.3k loc · cx 5 · at 2026-09-30", ViewRules.replayTooltipRow(b, 1, "2026-09-30"));
        Building git = new Building(reg.index(), reg.type(), reg.lot(), reg.file(), new FileHistory(12, 3, NOW, NOW),
                reg.deps(), reg.incoming(), new int[]{0}, reg.methods(), h, false);
        assertEquals("3 methods · 1 attrs · 12.3k loc · cx 5 · 4 commits · at 2026-09-30",
                ViewRules.replayTooltipRow(git, 1, "2026-09-30"));
        // Snapshot 0, where the type does not exist: zeros.
        assertEquals("0 methods · 0 attrs · 0 loc · cx 0 · 0 commits · at 2026-01-01", ViewRules.replayTooltipRow(git, 0, "2026-01-01"));
        assertEquals("Replaying — values at 2026-09-30", ViewRules.replayHeading("2026-09-30"));
        assertEquals(List.of("3|methods", "1|attributes", "12.3k|lines of code", "5|complexity"), flat(ViewRules.replayCells(b, 1)));
        assertNull(ViewRules.replayCells(b, 0));
        assertEquals("Not in this snapshot", ViewRules.NOT_IN_SNAPSHOT);
        assertEquals("Removed before today", ViewRules.REMOVED);
    }

    // ------------------------------------------------------------------ walk mode

    @Test
    void walkingStartsInFrontOfTheSelectionFacingIt() throws IOException {
        CityModel m = fixture("java-project");
        double[] at = ViewRules.walkStart(m.buildings().get(REGISTRY).lot(), 2, 3, 0.6);
        assertTrue(close(at[0], 3.434) && close(at[1], -7.566) && at[2] == 0);
        assertArrayEquals(new double[]{2, 3, 0.6}, ViewRules.walkStart(null, 2, 3, 0.6));
        // The java-project city as walk mode sees it: method-count heights, lot footprints, every district shown.
        double r = ViewRules.radius(m.width(), m.depth());
        double s = ViewRules.heightScale(m.types().stream().mapToDouble(TypeInfo::nom).toArray(), r);
        double[] heights = m.types().stream().mapToDouble(t -> ViewRules.height(t.nom(), s, r)).toArray();
        double[] sides = m.buildings().stream().mapToDouble(b -> b.lot().side()).toArray();
        boolean[] shown = new boolean[m.districts().size()];
        java.util.Arrays.fill(shown, true);
        Walker w = new Walker();
        w.spawn(at[0], at[1], at[2], Walker.World.of(m, heights, sides, shown));
        // The spot is free street, so the walker stands exactly there, on the district platform (core, depth 2: 1.2).
        assertTrue(close(w.x, 3.434) && close(w.z, -7.566), w.x + " " + w.z);
        assertTrue(close(w.feet, 1.2), String.valueOf(w.feet));
        assertEquals(0, w.yaw);
    }
}
