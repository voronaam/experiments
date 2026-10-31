package dev.codecity;

import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.layout.CityLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules of the plain city model (no git, no GitHub, no history) and its JSON, one per test. The golden suite
 * ({@link GoldenCityTest}) compares whole cities; these tests name the rule that broke. The Rust ({@code city.rs}) and
 * D ({@code city.d}) editions build the same fixture and check the same values.
 */
class CityModelTest {

    @TempDir
    Path dir;

    /**
     * The shared fixture: three Java types in package {@code shop} (one a test that uses {@code Cart}, which uses
     * {@code Item}) and one TypeScript module building in {@code web}, in a directory named {@code shop-city}.
     */
    private Path fixture() throws IOException {
        Path root = dir.resolve("shop-city");
        write(root, "src/main/java/shop/Cart.java", """
                package shop;
                public class Cart {
                    Item item;
                    int a;
                    void add(int n) { if (n > 0) { a += n; } }
                    void clear() {}
                }
                """);
        write(root, "src/main/java/shop/Item.java", "package shop;\npublic class Item {}\n");
        write(root, "src/test/java/shop/CartTest.java", "package shop;\nclass CartTest { Cart c; }\n");
        write(root, "web/util.ts", "export function f(x: number) { return x ? 1 : 0; }\n");
        return root.toRealPath();
    }

    private static void write(Path root, String rel, String code) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, code);
    }

    private CityModel build(boolean includeTests) throws IOException {
        return CityModel.build(new SourceAnalyzer(), fixture(), includeTests, false, null);
    }

    private static List<String> ids(CityModel m) {
        return m.buildings().stream().map(b -> b.type().fqn()).toList();
    }

    /**
     * The document starts with the city's name, root, time stamp, git flag, platform height and size, then the stats
     * (with the type count per language, most common first), the districts and the buildings, in that order. Optional
     * parts (github, history) are left out rather than written as null.
     */
    @Test
    void jsonHasTheCityHeaderStatsDistrictsAndBuildingsInOrder() throws IOException {
        CityModel m = build(true);
        String json = m.json();
        assertTrue(json.startsWith("{\"name\":\"shop-city\",\"root\":\"" + m.analysis().root().toString()
                .replace("\\", "\\\\") + "\",\"generatedAt\":\""), json);
        assertTrue(json.contains("\",\"hasGit\":false,\"platform\":0.4,\"width\":"), json);
        assertTrue(json.contains(",\"stats\":{\"files\":4,\"failedFiles\":0,\"types\":4,\"packages\":2,\"methods\":3,"
                + "\"loc\":9,\"languages\":{\"java\":3,\"typescript\":1}},\"districts\":[{\"id\":\"\",\"name\":"
                + "\"shop-city\",\"depth\":0,"), json);
        assertTrue(json.indexOf("\"districts\":[") < json.indexOf("\"buildings\":["));
        assertTrue(json.endsWith("]}"));
        for (String absent : List.of("\"github\"", "\"history\"", "\"hist\"", "\"gone\"", "\"prs\"", "\"commits\"")) {
            assertFalse(json.contains(absent), absent);
        }
    }

    /** One district per package below the city, each with its depth and the number of types in its subtree. */
    @Test
    void districtsListPackagesWithDepthAndTypeCounts() throws IOException {
        CityModel m = build(true);
        assertEquals(List.of("shop-city", "shop", "web"), m.districts().stream().map(CityLayout.District::label).toList());
        assertEquals(List.of("", "shop", "web"), m.districts().stream().map(CityLayout.District::id).toList());
        assertEquals(List.of(0, 1, 1), m.districts().stream().map(CityLayout.District::depth).toList());
        assertEquals(List.of(4, 3, 1), m.districts().stream().map(CityLayout.District::types).toList());
        assertTrue(m.json().contains("{\"id\":\"shop\",\"name\":\"shop\",\"depth\":1,"));
    }

    /**
     * Buildings are sorted by id and carry the type's identity and metrics; the file is relative to the root with
     * forward slashes; methods are positional arrays {@code [name, line, loc, cx, parameters]}, most complex first.
     */
    @Test
    void buildingsAreSortedByIdAndCarryTheirFields() throws IOException {
        CityModel m = build(true);
        assertEquals(List.of("shop.Cart", "shop.CartTest", "shop.Item", "web/util.ts"), ids(m));
        String json = m.json();
        assertTrue(json.contains("{\"id\":\"shop.Cart\",\"lang\":\"java\",\"name\":\"Cart\",\"pkg\":\"shop\","
                + "\"module\":\"\",\"kind\":\"class\",\"test\":false,\"file\":\"src/main/java/shop/Cart.java\","
                + "\"line\":2,\"endLine\":7,\"x\":"), json);
        assertTrue(json.contains(",\"district\":1,\"nom\":2,\"noa\":2,\"loc\":6,\"cx\":3,"
                + "\"methods\":[[\"add\",5,1,2,1],[\"clear\",6,1,1,0]],\"deps\":[2]}"), json);
        assertTrue(json.contains("{\"id\":\"shop.CartTest\",\"lang\":\"java\",\"name\":\"CartTest\",\"pkg\":\"shop\","
                + "\"module\":\"\",\"kind\":\"class\",\"test\":true,\"file\":\"src/test/java/shop/CartTest.java\","), json);
        assertTrue(json.contains("{\"id\":\"web/util.ts\",\"lang\":\"typescript\",\"name\":\"util.ts\",\"pkg\":\"web\","
                + "\"module\":\"\",\"kind\":\"module\",\"test\":false,\"file\":\"web/util.ts\",\"line\":1,"
                + "\"endLine\":2,\"x\":"), json);
        assertTrue(json.contains(",\"district\":2,\"nom\":1,\"noa\":0,\"loc\":1,\"cx\":2,"
                + "\"methods\":[[\"f\",1,1,2,1]],\"deps\":[]}"), json);
    }

    /**
     * Dependencies are building indices; the reverse edges (incoming) mirror them exactly, in ascending order.
     */
    @Test
    void dependenciesAreBuildingIndicesWithMatchingReverseEdges() throws IOException {
        CityModel m = build(true);
        List<CityModel.Building> b = m.buildings();
        assertArrayEquals(new int[]{2}, b.get(0).deps());
        assertArrayEquals(new int[]{0}, b.get(1).deps());
        assertArrayEquals(new int[]{1}, b.get(0).incoming());
        assertArrayEquals(new int[]{0}, b.get(2).incoming());
        int edges = 0;
        int reverse = 0;
        for (CityModel.Building x : b) {
            assertEquals(b.indexOf(x), x.index());
            for (int d : x.deps()) {
                int from = x.index();
                assertTrue(Arrays.stream(b.get(d).incoming()).anyMatch(i -> i == from));
            }
            edges += x.deps().length;
            reverse += x.incoming().length;
        }
        assertEquals(edges, reverse);
    }

    /**
     * Without tests, test buildings and every edge to or from them are gone; the stats count the remaining types but
     * still every source file read.
     */
    @Test
    void withoutTestsTheTestBuildingsAndTheirEdgesAreDropped() throws IOException {
        CityModel m = build(false);
        assertEquals(List.of("shop.Cart", "shop.Item", "web/util.ts"), ids(m));
        assertArrayEquals(new int[]{1}, m.buildings().get(0).deps());
        assertArrayEquals(new int[]{}, m.buildings().get(0).incoming());
        assertTrue(m.json().contains("\"stats\":{\"files\":4,\"failedFiles\":0,\"types\":3,\"packages\":2,"
                + "\"methods\":3,\"loc\":8,\"languages\":{\"java\":2,\"typescript\":1}}"), m.json());
        assertEquals(List.of(3, 2, 1), m.districts().stream().map(CityLayout.District::types).toList());
    }
}
