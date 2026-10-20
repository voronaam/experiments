package dev.codecity.analysis;

import dev.codecity.analysis.JavaParse.Lexed;
import dev.codecity.analysis.JavaParse.NodeKind;
import dev.codecity.analysis.JavaParse.Tok;
import dev.codecity.analysis.JavaParse.Tree;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.codecity.analysis.JavaParse.NodeKind.ANNOTATION_NAME;
import static dev.codecity.analysis.JavaParse.NodeKind.DECISION;
import static dev.codecity.analysis.JavaParse.NodeKind.FIELD;
import static dev.codecity.analysis.JavaParse.NodeKind.IMPORT;
import static dev.codecity.analysis.JavaParse.NodeKind.MEMBER_OBJECT;
import static dev.codecity.analysis.JavaParse.NodeKind.METHOD;
import static dev.codecity.analysis.JavaParse.NodeKind.OTHER;
import static dev.codecity.analysis.JavaParse.NodeKind.PACKAGE;
import static dev.codecity.analysis.JavaParse.NodeKind.SCOPED_TYPE_NAME;
import static dev.codecity.analysis.JavaParse.NodeKind.TYPE_DECL;
import static dev.codecity.analysis.JavaParse.NodeKind.TYPE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tricky syntax for {@link JavaParse}, checked against what tree-sitter-java 0.23 produced (the analyser's previous
 * parser), plus robustness: mutated input, deep nesting. A port of the Rust edition's {@code java_parse.rs} tests.
 */
class JavaParseTest {

    /** Texts of all nodes of {@code kind}, sorted. */
    private static List<String> texts(String code, NodeKind kind) {
        Tree t = JavaParse.parse(code);
        List<String> v = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (t.kind(i) == kind) {
                v.add(t.text(i));
            }
        }
        v.sort(null);
        return v;
    }

    /** {@link #texts} of a statement inside a method body. */
    private static List<String> inMethod(String stmt, NodeKind kind) {
        return texts("class A { void f() { " + stmt + " } }", kind);
    }

    private static boolean ok(String code) {
        return !JavaParse.parse(code).error();
    }

    @Test
    void lexesLiteralsCommentsAndLines() {
        String code = "a /* x\n y */ \"s\\\"t\" 'c' '\\'' 0x1.8p-3 1e+5f .5 1_000L \"\"\"\n tb \"\"\" >>= ->\nb";
        Lexed l = JavaParse.lex(code);
        List<Tok> kinds = Arrays.asList(Arrays.copyOf(l.kind, l.count));
        assertEquals(List.of(Tok.IDENT, Tok.STR, Tok.CHAR, Tok.CHAR, Tok.NUMBER, Tok.NUMBER, Tok.NUMBER, Tok.NUMBER,
                Tok.STR, Tok.OP, Tok.OP, Tok.OP, Tok.IDENT, Tok.EOF), kinds);
        assertFalse(l.error);
        assertEquals(2, l.line[1]);
        assertEquals(4, l.line[12]); // after the text block's line break
        // `>` stays single so that `List<List<X>>` closes twice
        byte[] b = code.getBytes(StandardCharsets.UTF_8);
        List<String> ops = new ArrayList<>();
        for (int k = 9; k < 12; k++) {
            ops.add(new String(b, l.start[k], l.end[k] - l.start[k], StandardCharsets.UTF_8));
        }
        assertEquals(List.of(">", ">=", "->"), ops);
    }

    @Test
    void lexerErrors() {
        assertTrue(JavaParse.lex("a # b").error);
        assertTrue(JavaParse.lex("\"open\nx").error);
        assertTrue(JavaParse.lex("/* open").error);
        assertTrue(JavaParse.lex("f(]").error);
        assertTrue(JavaParse.lex("int\u00a0x;").error); // no-break space is not whitespace
        assertFalse(JavaParse.lex("\ufeffclass A {}").error); // leading byte-order mark
        assertFalse(JavaParse.lex("class \u00e9t\u00e9 { String \u02c7x = \"\0\"; }").error); // NUL in a string is fine
    }

    @Test
    void simpleAndScopedTypeNames() {
        assertEquals(List.of("Map.Entry"), inMethod("Map.Entry<K, V> e;", SCOPED_TYPE_NAME));
        assertEquals(List.of("K", "V"), inMethod("Map.Entry<K, V> e;", TYPE_NAME));
        // leading parts' type arguments and annotations are part of the scoped name, not reported on their own
        assertEquals(List.of("Outer<String>.Inner"), inMethod("Outer<String>.Inner<Integer> x;", SCOPED_TYPE_NAME));
        assertEquals(List.of("Integer"), inMethod("Outer<String>.Inner<Integer> x;", TYPE_NAME));
        assertEquals(List.of("Nn"), inMethod("java.util.@Nn List<@Nn String> l;", ANNOTATION_NAME));
        // `var` and type parameters are type names too
        assertEquals(List.of("B", "C", "T", "U", "var"),
                texts("class A<T extends B & C> { <U> void f() { var x = 1; } }", TYPE_NAME));
    }

    @Test
    void castsAreRecognisedByWhatFollows() {
        assertEquals(List.of("Foo"), inMethod("x = (Foo) y;", TYPE_NAME));
        assertEquals(List.of("Bar", "Baz", "Foo", "Q"),
                inMethod("x = (Foo) !y + (Bar) ++y + (Baz) (y) + (Q) this;", TYPE_NAME));
        // a reference type followed by + or - is a parenthesized expression; a primitive type is still a cast
        assertTrue(inMethod("x = (Foo) - y; x = (foo) + 1; x = (y)++;", TYPE_NAME).isEmpty());
        assertTrue(ok("class A { void f() { x = (int) -y; x = (A & B) () -> 1; } }"));
        assertEquals(List.of("Comparable", "Serializable", "String"),
                inMethod("o = (Comparable<String> & Serializable) () -> 1;", TYPE_NAME));
    }

    @Test
    void memberObjectsClassLiteralsAndMethodReferences() {
        assertEquals(List.of("Foo", "Util", "a"),
                inMethod("Util.run(); Foo.BAR.baz(); a.b.C.d(); Outer.this.x = 1; o.new Inner();", MEMBER_OBJECT));
        assertEquals(List.of("Collections", "Outer"),
                inMethod("Outer.super.m(); Collections.<T>empty();", MEMBER_OBJECT));
        // class literals are types: qualified ones are scoped names, not member accesses
        assertEquals(List.of("a.b.C"),
                inMethod("c = a.b.C.class; d = Foo[].class; e = int.class;", SCOPED_TYPE_NAME));
        assertEquals(List.of(), inMethod("c = a.b.C.class; d = Foo[].class;", MEMBER_OBJECT));
        // `Foo::bar` keeps `Foo` an expression; generic and array forms are types
        assertTrue(inMethod("f = String::valueOf;", TYPE_NAME).isEmpty());
        assertEquals(List.of("Foo", "List", "String"),
                inMethod("f = List<String>::size; g = Foo[]::new; h = Foo.Bar::m;", TYPE_NAME));
        assertEquals(List.of("Foo"), inMethod("h = Foo.Bar::m;", MEMBER_OBJECT));
    }

    @Test
    void decisions() {
        assertEquals(4, inMethod("if (a && b || c) {} else if (d) {} else {}", DECISION).size());
        assertEquals(4, inMethod("for (;;) {} for (var x : xs) {} while (a) {} do {} while (b);", DECISION).size());
        assertEquals(2, inMethod("try {} catch (A | B e) {} catch (C e) {} finally {}", DECISION).size());
        assertEquals(2, inMethod("x = a ? b : c ? d : e; List<?> l;", DECISION).size());
        assertEquals(2, inMethod("switch (x) { case 1: case 2: break; default: }", DECISION).size());
        assertEquals(3, inMethod(
                "y = switch (o) { case Integer i when i > 0 && i < 9 -> 1; case null, default -> 2; };", DECISION).size());
        // lambdas and anonymous classes count towards the enclosing method
        assertEquals(2, inMethod(
                "r = () -> { if (a) {} }; o = new Object() { void g() { while (b) {} } };", DECISION).size());
    }

    @Test
    void patternsAndSwitchRules() {
        String code = """
                class A { Object f(Object o) { return switch (o) {
                    case Point(int x, var y) -> 1;
                    case Box<String>(Pair(var a, var b)) -> 2;
                    case String s -> { yield s; }
                    case Color.RED -> 3;
                    case FOO -> 4;
                    default -> throw new IllegalStateException();
                }; } }""";
        assertTrue(ok(code));
        // a record pattern's plain name is not a type reference, a generic one is
        assertEquals(List.of("Box", "IllegalStateException", "Object", "Object", "String", "String", "var", "var",
                "var"), texts(code, TYPE_NAME));
        assertEquals(List.of("Color"), texts(code, MEMBER_OBJECT));
        assertTrue(ok("class A { void f() { if (o instanceof Point(int a, int b) && o instanceof final List<?> l) {} } }"));
    }

    @Test
    void declarationsAndCounts() {
        String code = """
                @Ann package p;
                import static a.B.c;
                import a.b.*;
                public abstract sealed class A<T> extends B<T>.C implements I permits D {
                  int x, y[] = {1}, z;
                  A(@X A this, int a) {}
                  A(A this) {}
                  <R> R m(String... rest) { return null; }
                  record R(int a, String... more) { R {} }
                  @interface An { int v() default 1; int K = 2; }
                  enum E { P(1) { void g() {} }, Q; E(int i) {} }
                  { } static { }
                }""";
        Tree t = JavaParse.parse(code);
        assertFalse(t.error());
        List<NodeKind> kinds = t.children(0).stream().map(t::kind).toList();
        assertEquals(List.of(PACKAGE, IMPORT, IMPORT, TYPE_DECL), kinds);
        int a = t.children(0).getLast();
        assertEquals(JavaParse.TYPE_ABSTRACT, t.flags(a));
        List<String> members = new ArrayList<>();
        for (int c : t.children(a)) {
            NodeKind k = t.kind(c);
            if (k != TYPE_NAME && k != SCOPED_TYPE_NAME && k != ANNOTATION_NAME) {
                members.add(k + " " + t.count(c));
            }
        }
        // a receiver parameter with modifiers is counted as a parameter
        assertEquals(List.of(FIELD + " 3", METHOD + " 2", METHOD + " 0", METHOD + " 1", TYPE_DECL + " 1",
                TYPE_DECL + " 0", TYPE_DECL + " 0", OTHER + " 0", OTHER + " 0"), members);
        int imp = t.children(0).get(1);
        assertEquals(JavaParse.IMPORT_STATIC, t.flags(imp));
    }

    @Test
    void contextualKeywordsAndTopLevelOddities() {
        assertTrue(ok("class A { void f() { yield = 1; int yield = 2; var record = 3; String sealed = \"s\"; "
                + "f(record, sealed); } }"));
        assertTrue(ok("non-sealed class A {} sealed interface I permits A {} record R() {}"));
        assertTrue(ok("module m.n { requires transitive java.base; exports a.b to c; }"));
        assertTrue(ok("int x = 1; void top() {} class A {}")); // statements and methods are accepted
        assertTrue(ok("class A { void f() { label: for (;;) { break label; } assert x : \"m\"; synchronized (this) {} } }"));
        assertTrue(ok("class A { String s = STR.\"a \\{b}\"; int[] m()[] { return null; } A() { this(1); } "
                + "A(O o) { o.super(); } }"));
    }

    @Test
    void flagsInvalidCode() {
        for (String bad : List.of("class A {", "class A { void f() { x = ; } }", "class A { int }", "class { }", "}",
                "class A { void f() { if x {} } }")) {
            assertTrue(JavaParse.parse(bad).error(), bad);
        }
    }

    @Test
    void recoversDeclarationsAfterErrors() {
        Tree t = JavaParse.parse("class A { void f() { x = ; } int y; void g() {} }");
        assertTrue(t.error());
        int a = t.children(0).getFirst();
        assertEquals(2, t.children(a).stream().filter(c -> t.kind(c) == METHOD).count());
    }

    /** Checks the structural invariants of a tree: spans inside the token array, subtrees properly nested. */
    private static void checkTree(Tree t) {
        int n = t.size();
        for (int i = 0; i < n; i++) {
            assertTrue(t.end(i) > i && t.end(i) <= n);
            assertTrue(t.first(i) <= t.last(i) && t.last(i) < t.tokenCount());
            for (int j = i + 1; j < t.end(i); j = t.end(j)) {
                assertTrue(t.end(j) <= t.end(i));
            }
        }
        assertEquals(n, t.end(0));
    }

    private static final String CORPUS = """
            @Ann(v = Foo.class, o = {Bar.BAZ, @In(1)}) package p.q;
            import static java.util.Map.Entry; import a.*;
            public sealed abstract class T<K extends Comparable<? super K> & java.io.Serializable, V> extends Base<K>.In<V> implements I permits S {
              private static final int A = 1, B[] = {1, 2};
              Map<String, List<Map.Entry<K, V>>> m = new HashMap<>();
              public <X extends Number> T(X t, String... rest) throws IOException { super(t); }
              @Override public <R> R apply(final Function<? super K, ? extends R> f) throws Exception {
                label: for (var e : m.entrySet()) { if (e == null) break label; else if (e.getKey() == null) continue; }
                int x = a.length > 2 ? a[0] : a[1] > 3 ? 4 : 5; x >>>= 1;
                Object o = (Comparable<String> & Serializable) () -> 1;
                Runnable r = () -> { try (var in = new FileInputStream("f")) { } catch (IOException | RuntimeException e) { } finally { } };
                Object y = switch (o) { case Integer i when i > 0 -> "s"; case Point(int px, var py) -> "p"; default -> throw new X(); };
                switch (x) { case 1: case 2: x++; break; default: x = 0; }
                Object anon = new Object() { int f; void g() { if (f > 0) {} } };
                String tb = \"""
                    text "block"
                    \""";
                return (R) Util.<R>cast(T.super.toString(), int[].class, String::valueOf);
              }
              enum E { A(1) { void f() {} }, B; E(int x) {} }
              record R(int x) { R { if (x < 0) throw new IAE(); } }
              @interface An { int value() default 1; }
            }
            """;

    /** Random byte deletions, insertions and truncations of a corpus file must never throw or break the tree. */
    @Test
    void survivesMutatedInput() {
        long[] seed = {0x9E3779B97F4A7C15L};
        java.util.function.IntUnaryOperator next = bound -> {
            long s = seed[0];
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            seed[0] = s;
            return (int) Long.remainderUnsigned(s, Math.max(bound, 1));
        };
        byte[] alphabet = "{}()[]<>;,.:?@=&|-\"'/*\\\n abcAB01".getBytes(StandardCharsets.US_ASCII);
        byte[] corpus = CORPUS.getBytes(StandardCharsets.UTF_8);
        for (int round = 0; round < 3000; round++) {
            List<Byte> b = new ArrayList<>(corpus.length);
            for (byte c : corpus) {
                b.add(c);
            }
            int edits = 1 + next.applyAsInt(4);
            for (int e = 0; e < edits; e++) {
                if (b.isEmpty()) {
                    break;
                }
                int i = next.applyAsInt(b.size());
                switch (next.applyAsInt(4)) {
                    case 0 -> {
                        int k = 1 + next.applyAsInt(8);
                        b.subList(i, Math.min(i + k, b.size())).clear();
                    }
                    case 1 -> b.add(i, alphabet[next.applyAsInt(alphabet.length)]);
                    case 2 -> b.add(i, (byte) next.applyAsInt(256));
                    default -> b.subList(i, b.size()).clear();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(b.size());
            b.forEach(out::write);
            // Decoding replaces invalid UTF-8 with U+FFFD, like Rust's from_utf8_lossy.
            Tree t = JavaParse.parse(out.toString(StandardCharsets.UTF_8));
            checkTree(t);
        }
    }

    /**
     * Pathological nesting must not overflow the stack and must finish. Runs on a thread with a 512 KiB stack, half
     * of the JVM's default, so there is headroom on the analyser's own (default-sized) parse threads.
     */
    /**
     * {@code a < a < a …}: each {@code <} was tried as type arguments down to the depth cap. Once the cap has been hit,
     * the parser no longer speculates, so this stays linear (it took about ten times the limit before).
     */
    @Test
    void comparisonChainPastTheDepthCapIsLinear() {
        String code = "class C { void m() { x = " + "a < ".repeat(1_000_000) + "b; List" + "<List".repeat(400_000)
                + " y; } }";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(JavaParse.parse(code).error()));
    }

    @Test
    void survivesDeepNesting() throws InterruptedException {
        List<String> inputs = List.of(
                "class A { int x = " + "(".repeat(100_000) + "1" + ")".repeat(100_000) + "; }",
                "class A { void f() " + "{".repeat(100_000) + " }",
                "class A { int x = " + "a + ".repeat(100_000) + "1; }",
                "class A { void f() { " + "if (a) {} else ".repeat(20_000) + " {} } }",
                "class A { " + "List<".repeat(50_000) + " x; }",
                "class A { Object x = " + "y -> ".repeat(50_000) + "1; }",
                "class A { int x = " + "(A) -".repeat(50_000) + "1; }",
                "class A { Object x = " + "new A() { int y = ".repeat(20_000) + "; }",
                "class A { ".repeat(50_000),
                "@A(" + "@A(".repeat(50_000) + ") class A {}",
                "class A { int x = " + "a ? b : ".repeat(50_000) + "c; }");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                for (String code : inputs) {
                    checkTree(JavaParse.parse(code));
                }
                // a long but shallow chain parses without error
                assertFalse(JavaParse.parse("class A { void f() { " + "if (a) {} else ".repeat(20_000) + " {} } }")
                        .error());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "deep-nesting", 512 * 1024);
        thread.start();
        thread.join();
        assertNull(failure.get());
    }
}
