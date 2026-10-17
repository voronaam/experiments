package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAnalyzerTest {

    @TempDir
    Path root;

    private void write(String rel, String code) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, code);
    }

    private Analysis analyze() throws IOException {
        return new JavaAnalyzer().analyze(root, true);
    }

    @Test
    void extractsClassicCodeCityMetrics() throws IOException {
        write("src/main/java/shop/Cart.java", """
                package shop;

                import java.util.List;

                /** A cart. */
                public class Cart {
                    private List<Item> items;
                    private int a, b;

                    // comment only
                    public int total() {
                        int sum = 0;
                        for (Item i : items) {
                            if (i.price() > 0 && i.qty() > 0) {
                                sum += i.price() * i.qty();
                            }
                        }
                        return sum > 100 ? sum - 5 : sum;
                    }

                    Cart() {}

                    enum Status { OPEN, PAID }
                }
                """);
        write("src/main/java/shop/Item.java", """
                package shop;
                public record Item(int price, int qty) {}
                """);
        Map<String, TypeInfo> types = analyze().types().stream()
                .collect(Collectors.toMap(TypeInfo::fqn, Function.identity()));

        TypeInfo cart = types.get("shop.Cart");
        assertEquals("class", cart.kind());
        assertEquals(3, cart.noa());
        assertEquals(2, cart.nom());
        // total(): 1 + for + if + && + ?:  = 5; constructor: 1
        assertEquals(6, cart.complexity());
        assertFalse(cart.test());

        assertEquals("record", types.get("shop.Item").kind());
        assertEquals(2, types.get("shop.Item").noa());
        assertEquals("enum", types.get("shop.Cart.Status").kind());
        assertEquals(2, types.get("shop.Cart.Status").noa());
    }

    @Test
    void resolvesProjectDependenciesOnly() throws IOException {
        write("src/main/java/a/A.java", """
                package a;
                import b.B;
                import java.util.Map;
                public class A { B b; Map<String, C> m; void f() { Util.go(); } }
                """);
        write("src/main/java/a/C.java", "package a; class C {}");
        write("src/main/java/a/Util.java", "package a; class Util { static void go() {} }");
        write("src/main/java/b/B.java", "package b; public class B {}");
        Analysis analysis = analyze();
        assertEquals(Set.of("b.B", "a.C", "a.Util"), analysis.dependencies().get("a.A"));
    }

    @Test
    void detectsTestsAndModules() throws IOException {
        write("core/src/test/java/a/ATest.java", "package a; class ATest {}");
        TypeInfo t = analyze().types().getFirst();
        assertTrue(t.test());
        assertEquals("core", t.module());
    }

    @Test
    void reusesParsesForUnchangedFiles() throws IOException {
        write("src/main/java/a/A.java", "package a; class A {}");
        JavaAnalyzer analyzer = new JavaAnalyzer();
        TypeInfo first = analyzer.analyze(root, true).types().getFirst();
        TypeInfo second = analyzer.analyze(root, true).types().getFirst();
        assertTrue(first == second, "expected cached instance");
    }
}
