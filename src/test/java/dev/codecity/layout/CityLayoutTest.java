package dev.codecity.layout;

import dev.codecity.analysis.Language;
import dev.codecity.analysis.MethodInfo;
import dev.codecity.analysis.TypeInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout rules, one per test. The Rust ({@code layout.rs}) and D ({@code layout.d}) editions run the same scenarios,
 * on the same synthetic fixtures, with the same expectations (snake_case names in Rust, the Java name in the comment
 * of each D {@code unittest}).
 */
class CityLayoutTest {

    /** A type of the given language in package {@code pkg} and build module {@code module}. */
    private static TypeInfo type(Language lang, String module, String pkg, String name, int noa) {
        String fqn = pkg.isEmpty() ? name : pkg + lang.separator + name;
        return new TypeInfo(lang, fqn, name, pkg, module, "class", Path.of(name + ".java"), 1, 10, 10, noa, false,
                List.of(new MethodInfo("m", 2, 3, 1, 0)), Set.of(), List.of(), List.of());
    }

    private static TypeInfo type(String pkg, String name, int noa) {
        return type(Language.JAVA, "", pkg, name, noa);
    }

    /**
     * The shared fixture of all three editions: 300 Java types spread evenly over six packages (two of them only
     * reachable through empty chains), mostly small, every fifth one larger. Deterministic, so the editions lay out
     * exactly the same input.
     */
    static List<TypeInfo> city() {
        String[] pkgs = {"a", "a.b", "a.b.c", "a.d", "e", "e.f.g"};
        List<TypeInfo> types = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            types.add(type(pkgs[(i * 7) % pkgs.length], "T" + i, (i * 13) % 9 * (i % 5 == 0 ? 9 : 1)));
        }
        return types;
    }

    private static List<String> labels(CityLayout.Result r) {
        return r.districts().stream().map(CityLayout.District::label).toList();
    }

    private static List<String> ids(CityLayout.Result r) {
        return r.districts().stream().map(CityLayout.District::id).toList();
    }

    /** Every type of the input gets exactly one lot, keyed by its fqn. */
    @Test
    void everyTypeGetsALot() {
        List<TypeInfo> types = city();
        CityLayout.Result r = CityLayout.layout("city", types);
        assertEquals(types.size(), r.lots().size());
        types.forEach(t -> assertTrue(r.lots().containsKey(t.fqn()), t.fqn()));
    }

    /** Buildings never overlap: any two lots are at least a street ({@link CityLayout#GAP}) apart along x or z. */
    @Test
    void buildingsInTheSameDistrictDoNotOverlap() {
        CityLayout.Result r = CityLayout.layout("city", city());
        List<CityLayout.Lot> lots = new ArrayList<>(r.lots().values());
        for (int i = 0; i < lots.size(); i++) {
            for (int j = i + 1; j < lots.size(); j++) {
                CityLayout.Lot a = lots.get(i);
                CityLayout.Lot b = lots.get(j);
                double gapX = Math.abs(a.x() - b.x()) - (a.side() + b.side()) / 2;
                double gapZ = Math.abs(a.z() - b.z()) - (a.side() + b.side()) / 2;
                assertTrue(gapX >= CityLayout.GAP - 1e-6 || gapZ >= CityLayout.GAP - 1e-6,
                        "lots " + a + " and " + b + " overlap");
            }
        }
    }

    /**
     * A building stands inside its district with at least the kerb ({@link CityLayout#PAD}) to every edge, on top of
     * the district's platform: {@code y = (depth + 1) * PLATFORM}.
     */
    @Test
    void buildingsStandInsideTheirDistrict() {
        CityLayout.Result r = CityLayout.layout("city", city());
        double pad = CityLayout.PAD - 1e-6;
        for (CityLayout.Lot lot : r.lots().values()) {
            CityLayout.District d = r.districts().get(lot.districtIndex());
            assertTrue(lot.x() - lot.side() / 2 >= d.x() - d.w() / 2 + pad);
            assertTrue(lot.x() + lot.side() / 2 <= d.x() + d.w() / 2 - pad);
            assertTrue(lot.z() - lot.side() / 2 >= d.z() - d.d() / 2 + pad);
            assertTrue(lot.z() + lot.side() / 2 <= d.z() + d.d() / 2 - pad);
            assertEquals((d.depth() + 1) * CityLayout.PLATFORM, lot.y(), 1e-9);
        }
    }

    /**
     * A sub-district lies inside its parent with at least the kerb to every edge, and sibling districts are at
     * least a street apart.
     */
    @Test
    void subDistrictsStandInsideTheirParentWithStreetsBetween() {
        CityLayout.Result r = CityLayout.layout("city", city());
        List<CityLayout.District> ds = r.districts();
        int[] parent = parents(ds);
        double pad = CityLayout.PAD - 1e-6;
        for (int i = 1; i < ds.size(); i++) {
            CityLayout.District c = ds.get(i);
            CityLayout.District p = ds.get(parent[i]);
            assertTrue(c.x() - c.w() / 2 >= p.x() - p.w() / 2 + pad, c.label());
            assertTrue(c.x() + c.w() / 2 <= p.x() + p.w() / 2 - pad, c.label());
            assertTrue(c.z() - c.d() / 2 >= p.z() - p.d() / 2 + pad, c.label());
            assertTrue(c.z() + c.d() / 2 <= p.z() + p.d() / 2 - pad, c.label());
            for (int j = i + 1; j < ds.size(); j++) {
                if (parent[j] == parent[i]) {
                    CityLayout.District s = ds.get(j);
                    double gapX = Math.abs(c.x() - s.x()) - (c.w() + s.w()) / 2;
                    double gapZ = Math.abs(c.z() - s.z()) - (c.d() + s.d()) / 2;
                    assertTrue(gapX >= CityLayout.GAP - 1e-6 || gapZ >= CityLayout.GAP - 1e-6,
                            c.label() + " and " + s.label() + " overlap");
                }
            }
        }
    }

    /** The parent of every district: the closest earlier district one level up (they are in pre-order). */
    private static int[] parents(List<CityLayout.District> ds) {
        int[] parent = new int[ds.size()];
        parent[0] = -1;
        for (int i = 1; i < ds.size(); i++) {
            int p = i - 1;
            while (ds.get(p).depth() != ds.get(i).depth() - 1) {
                p--;
            }
            parent[i] = p;
        }
        return parent;
    }

    /** An empty package chain above the only package collapses into one district named like the package. */
    @Test
    void singleChildPackageChainsAreCollapsed() {
        CityLayout.Result r = CityLayout.layout("city", List.of(type("com.acme.shop", "Cart", 2)));
        assertEquals(List.of("city", "com.acme.shop"), labels(r));
        assertEquals(List.of("", "com.acme.shop"), ids(r));
    }

    /** A packed district comes out roughly square, not as a long row or column. */
    @Test
    void cityIsRoughlySquare() {
        List<TypeInfo> types = new ArrayList<>();
        // A fixed permutation of 0..399 (37 is coprime with 400), so the input is not in size order.
        for (int i = 0; i < 400; i++) {
            int k = i * 37 % 400;
            types.add(type("p", "T" + k, k % 7));
        }
        CityLayout.Result r = CityLayout.layout("city", types);
        double aspect = r.width() / r.depth();
        assertFalse(aspect > 2 || aspect < 0.5, "aspect " + aspect);
    }

    /**
     * The same input always gives exactly the same city. Another input order gives the same districts and the same
     * set of lots; only equally sized buildings may swap places, as the packer keeps their input order (the analysis
     * always hands the types over sorted by fqn).
     */
    @Test
    void layoutIsDeterministic() {
        CityLayout.Result a = CityLayout.layout("city", city());
        CityLayout.Result again = CityLayout.layout("city", city());
        assertEquals(a.districts(), again.districts());
        assertEquals(a.lots(), again.lots());
        CityLayout.Result b = CityLayout.layout("city", new ArrayList<>(city().reversed()));
        assertEquals(a.districts(), b.districts());
        assertEquals(a.width(), b.width());
        assertEquals(a.depth(), b.depth());
        assertEquals(sortedLots(a), sortedLots(b));
    }

    /** The lots as "x z side district" strings, sorted: the set of places regardless of which type got which. */
    private static List<String> sortedLots(CityLayout.Result r) {
        return r.lots().values().stream()
                .map(l -> l.x() + " " + l.z() + " " + l.side() + " " + l.districtIndex()).sorted().toList();
    }

    /** The footprint side is {@code 1.2 + 0.8 * sqrt(noa)}: 1.2 without attributes. */
    @Test
    void footprintGrowsWithTheSquareRootOfTheAttributes() {
        assertEquals(1.2, CityLayout.side(0), 1e-12);
        assertEquals(2.0, CityLayout.side(1), 1e-12);
        assertEquals(2.8, CityLayout.side(4), 1e-12);
        assertEquals(3.6, CityLayout.side(9), 1e-12);
        assertEquals(CityLayout.side(9), CityLayout.side(type("p", "A", 9)), 1e-12);
    }

    /** Without types the city is a single bare platform: just the kerb on both sides, centred on the origin. */
    @Test
    void emptyCityIsOneBareDistrict() {
        CityLayout.Result r = CityLayout.layout("city", List.of());
        assertEquals(List.of(new CityLayout.District("", "city", 0, 0, 0, 2 * CityLayout.PAD, 2 * CityLayout.PAD, 0)),
                r.districts());
        assertTrue(r.lots().isEmpty());
        assertEquals(2 * CityLayout.PAD, r.width());
        assertEquals(2 * CityLayout.PAD, r.depth());
    }

    /**
     * Districts come in pre-order (each after its parent, siblings in packing order: larger first), with their depth,
     * their package as id and the number of types in their whole subtree. {@code e.f.g} has an empty {@code f} in
     * between, which is folded into {@code f.g}.
     */
    @Test
    void districtsArePreOrderWithDepthsIdsAndSubtreeTypeCounts() {
        CityLayout.Result r = CityLayout.layout("city", city());
        assertEquals(List.of("city", "a", "b", "c", "d", "e", "f.g"), labels(r));
        assertEquals(List.of("", "a", "a.b", "a.b.c", "a.d", "e", "e.f.g"), ids(r));
        assertEquals(List.of(0, 1, 2, 3, 2, 1, 2), r.districts().stream().map(CityLayout.District::depth).toList());
        assertEquals(List.of(300, 200, 100, 50, 50, 100, 50),
                r.districts().stream().map(CityLayout.District::types).toList());
    }

    /** A collapsed chain is joined with its language's own separator: "/" for TypeScript, "::" for Rust. */
    @Test
    void collapsedChainsJoinWithTheirLanguageSeparator() {
        CityLayout.Result ts = CityLayout.layout("city", List.of(type(Language.TYPESCRIPT, "", "src/ui/forms", "F", 0)));
        assertEquals(List.of("city", "src/ui/forms"), labels(ts));
        assertEquals(List.of("", "src/ui/forms"), ids(ts));
        CityLayout.Result rust = CityLayout.layout("city", List.of(type(Language.RUST, "", "net::http", "Get", 0)));
        assertEquals(List.of("city", "net::http"), labels(rust));
        assertEquals(List.of("", "net::http"), ids(rust));
    }

    /** A package that holds types itself is not folded into its only sub-package. */
    @Test
    void districtWithOwnTypesIsNotFolded() {
        CityLayout.Result r = CityLayout.layout("city", List.of(type("a", "A", 0), type("a.b", "B", 0)));
        assertEquals(List.of("city", "a", "b"), labels(r));
        assertEquals(List.of("", "a", "a.b"), ids(r));
        assertEquals(1, r.lots().get("a.A").districtIndex());
        assertEquals(2, r.lots().get("a.b.B").districtIndex());
    }

    /**
     * With several build modules, each gets a district on the first level ("(root)" for the root module ""), whose
     * id starts with {@code module:}; a module is never folded into its parent nor with its only child, though the
     * chains below it are.
     */
    @Test
    void modulesGetTheirOwnDistrictAndAreNeverFolded() {
        CityLayout.Result r = CityLayout.layout("city", List.of(
                type(Language.JAVA, "core", "x.y", "X", 0), type(Language.JAVA, "", "z", "Z", 0)));
        assertEquals(List.of("city", "(root)", "z", "core", "x.y"), labels(r));
        assertEquals(List.of("", "module:(root)", "module:(root)/z", "module:core", "module:core/x.y"), ids(r));
        assertEquals(List.of(0, 1, 2, 1, 2), r.districts().stream().map(CityLayout.District::depth).toList());
        assertEquals(4, r.lots().get("x.y.X").districtIndex());
    }

    /** With a single build module there is no module level: the packages sit directly on the city. */
    @Test
    void singleModuleHasNoModuleLevel() {
        CityLayout.Result r = CityLayout.layout("city", List.of(
                type(Language.JAVA, "core", "x.y", "X", 0), type(Language.JAVA, "core", "x.w", "W", 0)));
        assertEquals(List.of("city", "x", "w", "y"), labels(r));
        assertEquals(List.of("", "x", "x.w", "x.y"), ids(r));
    }

    /** Types outside any package stand in "(default)" (Java's default package) or "(root)" (TypeScript, Rust). */
    @Test
    void typesOutsideAPackageStandInADefaultDistrict() {
        CityLayout.Result r = CityLayout.layout("city", List.of(type(Language.JAVA, "", "", "J", 0),
                type(Language.TYPESCRIPT, "", "", "T", 0), type(Language.RUST, "", "", "R", 0)));
        // "(root)" holds two buildings, so it is packed (and listed) first.
        assertEquals(List.of("city", "(root)", "(default)"), labels(r));
        assertEquals(List.of("", "(root)", "(default)"), ids(r));
        assertEquals(2, r.lots().get("J").districtIndex());
        assertEquals(1, r.lots().get("T").districtIndex());
        assertEquals(1, r.lots().get("R").districtIndex());
    }

    /**
     * Lots sized for a larger attribute count (as the history replay does with a type's largest count) are placed
     * exactly as if the types had that count, but each building keeps its own footprint, centred in its lot.
     */
    @Test
    void largerLotsCentreTheBuildingsOwnFootprint() {
        List<TypeInfo> own = List.of(type("p", "A", 0), type("p", "B", 1), type("q", "C", 4));
        List<TypeInfo> grown = List.of(type("p", "A", 9), type("p", "B", 4), type("q", "C", 4));
        CityLayout.Result wide = CityLayout.layout("city", own, t -> t.noa() == 0 ? 9 : t.noa() == 1 ? 4 : t.noa());
        CityLayout.Result reference = CityLayout.layout("city", grown);
        assertEquals(reference.districts(), wide.districts());
        for (TypeInfo t : own) {
            CityLayout.Lot w = wide.lots().get(t.fqn());
            CityLayout.Lot g = reference.lots().get(t.fqn());
            assertEquals(g.x(), w.x(), 1e-9, t.fqn());
            assertEquals(g.z(), w.z(), 1e-9, t.fqn());
            assertEquals(CityLayout.side(t), w.side(), 1e-12, t.fqn());
            assertEquals(g.districtIndex(), w.districtIndex());
        }
    }

    /**
     * Pins the packer on a tiny case: the bigger building goes first at the back-left corner, the smaller one in
     * front of it (a wider strip would put it beside, but the score ties and the narrower strip wins), and the city is
     * centred on the origin.
     */
    @Test
    void twoBuildingsHaveExactCoordinates() {
        CityLayout.Result r = CityLayout.layout("city", List.of(type("p", "A", 0), type("p", "B", 9)));
        assertEquals(7.6, r.width(), 1e-9);
        assertEquals(9.6, r.depth(), 1e-9);
        CityLayout.District p = r.districts().get(1);
        assertEquals(List.of(0.0, 0.0, 5.6, 7.6), List.of(round(p.x()), round(p.z()), round(p.w()), round(p.d())));
        CityLayout.Lot b = r.lots().get("p.B");
        CityLayout.Lot a = r.lots().get("p.A");
        assertEquals(List.of(0.0, -1.0, 3.6, 0.8), List.of(round(b.x()), round(b.z()), round(b.side()), round(b.y())));
        assertEquals(List.of(-1.2, 2.2, 1.2, 0.8), List.of(round(a.x()), round(a.z()), round(a.side()), round(a.y())));
    }

    /** Rounds to 9 decimals, so exact expectations are not upset by floating-point noise. */
    private static double round(double v) {
        return Math.round(v * 1e9) / 1e9;
    }
}
