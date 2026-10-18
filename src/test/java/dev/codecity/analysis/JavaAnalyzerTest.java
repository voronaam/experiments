package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        return new SourceAnalyzer().analyze(root, true);
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
    void countsJavaSpecificConstructs() throws IOException {
        write("src/main/java/a/Shapes.java", """
                package a;
                public abstract class Shapes {
                    abstract int area(int... sides);
                    int kind(Object o, int k) {
                        Runnable r = () -> { if (k > 0 || k < -1) { } };
                        return switch (k) {
                            case 1, 2 -> 1;
                            case 3 -> o instanceof String s ? 2 : 3;
                            default -> 0;
                        };
                    }
                    void loops(int[] xs) {
                        try { do { } while (xs.length > 0); } catch (RuntimeException e) { } finally { }
                        while (true) { break; }
                    }
                    void receiver(Shapes this, int x) {}
                    { int init = 1; }
                }
                interface Consts { int A = 1, B = 2; }
                enum Color { RED, GREEN; int rgb; Color() {} int value() { return rgb; } }
                """);
        Map<String, TypeInfo> types = analyze().types().stream()
                .collect(Collectors.toMap(TypeInfo::fqn, Function.identity()));

        TypeInfo shapes = types.get("a.Shapes");
        assertEquals("abstract", shapes.kind());
        Map<String, MethodInfo> methods = shapes.methods().stream()
                .collect(Collectors.toMap(MethodInfo::name, Function.identity()));
        // The initialiser block is not a method; the varargs parameter counts, the receiver parameter does not.
        assertEquals(Set.of("area", "kind", "loops", "receiver"), methods.keySet());
        assertEquals(1, methods.get("area").parameters());
        assertEquals(1, methods.get("receiver").parameters());
        // kind(): 1 + lambda's if + || + `case 1, 2` + `case 3` + ?: = 6 (default is no decision)
        assertEquals(6, methods.get("kind").complexity());
        // loops(): 1 + do + catch + while = 4
        assertEquals(4, methods.get("loops").complexity());

        assertEquals("interface", types.get("a.Consts").kind());
        assertEquals(2, types.get("a.Consts").noa());
        // Two constants and one field; the constructor and method sit in the enum's body declarations.
        assertEquals(3, types.get("a.Color").noa());
        assertEquals(2, types.get("a.Color").nom());
    }

    @Test
    void collectsQualifiedStaticAndAnnotationReferences() throws IOException {
        write("src/main/java/a/A.java", """
                package a;
                import static b.Util.go;
                import static b.Consts.*;
                @b.Marker
                public class A {
                    b.Outer . Inner i;
                    void f() { go(); A.this.hashCode(); Helper.run(); }
                    class Nested { Object o = A.this; }
                }
                """);
        write("src/main/java/a/Helper.java", "package a; class Helper { static void run() {} }");
        write("src/main/java/b/Util.java", "package b; public class Util { public static void go() {} }");
        write("src/main/java/b/Consts.java", "package b; public class Consts {}");
        write("src/main/java/b/Marker.java", "package b; public @interface Marker {}");
        write("src/main/java/b/Outer.java", "package b; public class Outer { public class Inner {} }");
        Analysis analysis = analyze();
        // Statically imported members name no type; `A.this` is no static access. `b.Outer . Inner` (with spaces)
        // resolves to the member type, and the scope `b.Outer` is a reference of its own.
        assertEquals(Set.of("a.Helper", "b.Marker", "b.Outer.Inner", "b.Outer"), analysis.dependencies().get("a.A"));
    }

    @Test
    void filesWithSyntaxErrorsAreAnalysedAndCounted() throws IOException {
        write("src/main/java/a/A.java", "package a; class A { int x; void f( { } }");
        Analysis analysis = analyze();
        assertEquals(1, analysis.failedFiles());
        assertEquals("a.A", analysis.types().getFirst().fqn());
    }

    @Test
    void usesTheOptimisedTreeSitterLibrary() {
        Syntax.parser(org.treesitter.TreeSitterJava::new);
        String dir = System.getProperty("tree-sitter-lib");
        assertTrue(dir != null && Files.isDirectory(Path.of(dir, "lib")), "tree-sitter-lib = " + dir);
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
        SourceAnalyzer analyzer = new SourceAnalyzer();
        TypeInfo first = analyzer.analyze(root, true).types().getFirst();
        TypeInfo second = analyzer.analyze(root, true).types().getFirst();
        assertTrue(first == second, "expected cached instance");
    }

    @Test
    void resolutionNeverSeesTheDefaultPackage() throws IOException {
        // Default package: `Line` inside Order is the member type, not the top-level `Line`.
        write("src/main/java/Line.java", "class Line {}");
        write("src/main/java/Util.java", "class Util {}");
        write("src/main/java/Order.java", "class Order { Line l; static class Line {} }");
        // Named package: default-package types are invisible, imports and the same package are used instead.
        write("src/main/java/shop/Cart.java",
                "package shop;\nimport other.Util;\nclass Cart { Line l; Util u; Order o; other.Helper h; }");
        write("src/main/java/shop/Line.java", "package shop; class Line {}");
        write("src/main/java/other/Util.java", "package other; public class Util {}");
        write("src/main/java/other/Helper.java", "package other; public class Helper {}");
        Analysis analysis = analyze();
        assertEquals(Set.of("Order.Line"), analysis.dependencies().get("Order"));
        assertEquals(Set.of("other.Helper", "other.Util", "shop.Line"), analysis.dependencies().get("shop.Cart"));
    }

    @Test
    void linesFollowTreeSitterRowsAndTrailingBlockComments() throws IOException {
        // A lone \r is not a line break; the comment opened after `int x;` hides the next line.
        write("src/main/java/a/A.java", "package a;\rclass A {\n  int x; /* start\n  comment\n  end */\n  int y;\n}\n");
        TypeInfo t = analyze().types().getFirst();
        assertEquals(List.of(1, 6, 4), List.of(t.line(), t.endLine(), t.loc()));
    }

    @Test
    void duplicateFqnAcrossLanguagesIsDroppedWithItsDependencies() throws IOException {
        // The Java type `App.tsx` and the TypeScript module building `App.tsx` share an fqn; Java (first) wins.
        write("App.java", "package App; class tsx { Other o; }");
        write("Other.java", "package App; class Other {}");
        write("App.tsx", "import { f } from \"./b\";\nexport function g() { return f(); }\n");
        write("b.ts", "export function f() { return 1; }\n");
        Analysis analysis = analyze();
        TypeInfo app = analysis.types().stream().filter(t -> t.fqn().equals("App.tsx")).findFirst().orElseThrow();
        // b.ts proves that TypeScript module buildings exist, so App.tsx really was produced twice.
        assertTrue(analysis.types().stream().anyMatch(t -> t.fqn().equals("b.ts")));
        assertEquals(Language.JAVA, app.language());
        assertEquals(Set.of("App.Other"), analysis.dependencies().get("App.tsx"));
    }

    @Test
    void cachedParsesSeeManifestChanges() throws IOException {
        write("Cargo.toml", "[package]\nname = \"before\"\n");
        write("src/lib.rs", "pub struct A;\n");
        SourceAnalyzer analyzer = new SourceAnalyzer();
        assertTrue(analyzer.analyze(root, true).types().stream().anyMatch(t -> t.fqn().equals("before::A")));
        // Only the manifest changes (a different size, so the change is seen even within the mtime resolution).
        write("Cargo.toml", "[package]\nname = \"after_rename\"\n");
        assertTrue(analyzer.analyze(root, true).types().stream().anyMatch(t -> t.fqn().equals("after_rename::A")));
    }
}
