package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One test per rule of the Java analyser: each metric (attributes, methods, lines of code, complexity), the building
 * kinds, test detection, build modules, the failed-file flag, and each step of the name lookup in its order. The
 * golden suite checks whole cities; these tests name the rule that broke. The Rust edition
 * ({@code analysis/java_rules_tests.rs}) and the D edition ({@code analysis/javarulestests.d}) run the same scenarios
 * with the same expectations.
 */
class JavaRulesTest {

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

    private static Map<String, TypeInfo> byFqn(Analysis a) {
        return a.types().stream().collect(Collectors.toMap(TypeInfo::fqn, Function.identity()));
    }

    /** The dependencies of {@code fqn}, sorted, as the Rust and D editions store them. */
    private static List<String> deps(Analysis a, String fqn) {
        return a.dependencies().get(fqn).stream().sorted().toList();
    }

    /** Complexity of each method of a type, by method name. */
    private static Map<String, Integer> complexities(TypeInfo t) {
        return t.methods().stream().collect(Collectors.toMap(MethodInfo::name, MethodInfo::complexity));
    }

    // ------------------------------------------------------------------ metrics

    /**
     * Attributes: one per declarator ({@code int a, b, c;} is three), static fields included; one per enum constant
     * plus the enum's fields; one per record component except a varargs one; one per interface constant.
     */
    @Test
    void attributesCountDeclaratorsConstantsAndRecordComponents() throws IOException {
        write("F.java", """
                class F { int a, b, c; static String s; }
                enum E { A, B, C; int x; }
                record R(int x, int y, String... rest) {}
                interface I { int P = 1, Q = 2; }
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of(4, 4, 2, 2), List.of(t.get("F").noa(), t.get("E").noa(), t.get("R").noa(), t.get("I").noa()));
    }

    /** Methods and constructors are methods; static and instance initialisers and compact record constructors are not. */
    @Test
    void methodsCountConstructorsButNotInitialisers() throws IOException {
        write("M.java", """
                class M { M() {} M(int x) {} void f() {} static { } { } }
                record P(int x) { P { } void g() {} }
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of("M", "M", "f"), t.get("M").methods().stream().map(MethodInfo::name).toList());
        assertEquals(List.of("g"), t.get("P").methods().stream().map(MethodInfo::name).toList());
    }

    /** Blank lines, {@code //} lines and the lines of a block comment do not count; code before or after a comment does. */
    @Test
    void linesOfCodeSkipBlankAndCommentLines() throws IOException {
        write("L.java", """
                class L {

                  // comment
                  /* block
                     still comment */
                  int a;
                  /* c */ int b;
                  int c; // trailing
                }
                """);
        TypeInfo l = analyze().types().getFirst();
        assertEquals(List.of(1, 9, 5), List.of(l.line(), l.endLine(), l.loc()));
    }

    /**
     * {@code /*} inside a string opens no comment; {@code /*}{@code /} opens one without closing it; code after the
     * closing {@code *}{@code /} counts.
     */
    @Test
    void linesOfCodeSeeThroughStringsAndHalfClosedComments() throws IOException {
        write("S.java", "class S {\n  String p = \"/*\";\n  char q = '\"'; int a;\n  /*/ still open\n  int hidden;\n  */ int after;\n}\n");
        assertEquals(5, analyze().types().getFirst().loc());
    }

    /** CRLF line endings break lines like LF; the {@code \r} is not code. */
    @Test
    void linesOfCodeWithCrlfLineEndings() throws IOException {
        write("C.java", "class C {\r\n  int a;\r\n\r\n}\r\n");
        TypeInfo c = analyze().types().getFirst();
        assertEquals(List.of(1, 4, 3), List.of(c.line(), c.endLine(), c.loc()));
    }

    /**
     * Complexity is 1 plus one per decision point: if (else-if is another if), for, for-each, while, do, catch,
     * {@code ?:}, every non-default case label ({@code case 1, 2} once), {@code &&} and {@code ||}. Lambdas and
     * anonymous classes add to the enclosing method; a method without a body is 1.
     */
    @Test
    void complexityCountsEachDecisionKind() throws IOException {
        write("X.java", """
                abstract class X {
                  void none() {}
                  void ifs(boolean a) { if (a) {} else if (!a) {} else {} }
                  void fors() { for (int i = 0; i < 1; i++) {} }
                  void each(int[] xs) { for (int x : xs) {} }
                  void whiles(boolean a) { while (a) {} }
                  void dos(boolean a) { do {} while (a); }
                  void catches() { try {} catch (RuntimeException e) {} catch (Error e) {} finally {} }
                  int ternary(boolean a) { return a ? 1 : 0; }
                  void cases(int k) { switch (k) { case 1, 2: break; case 3: break; default: break; } }
                  int arrows(int k) { return switch (k) { case 1 -> 1; default -> 0; }; }
                  boolean logic(boolean a, boolean b, boolean c) { return a && b || c; }
                  void lambda() { Runnable r = () -> { if (true) {} }; }
                  void anon() { Object o = new Object() { void m(boolean a) { while (a) {} } }; }
                  abstract void sig();
                }
                """);
        Map<String, Integer> cx = complexities(analyze().types().getFirst());
        assertEquals(Map.ofEntries(Map.entry("none", 1), Map.entry("ifs", 3), Map.entry("fors", 2),
                Map.entry("each", 2), Map.entry("whiles", 2), Map.entry("dos", 2), Map.entry("catches", 3),
                Map.entry("ternary", 2), Map.entry("cases", 3), Map.entry("arrows", 2), Map.entry("logic", 3),
                Map.entry("lambda", 2), Map.entry("anon", 2), Map.entry("sig", 1)), cx);
    }

    /** Class, abstract class, interface, enum, record and annotation type each have their own kind. */
    @Test
    void kindsOfJavaTypes() throws IOException {
        write("K.java", """
                class A {}
                abstract class B {}
                interface C {}
                enum D {}
                record E() {}
                @interface F {}
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of("class", "abstract", "interface", "enum", "record", "annotation"),
                List.of("A", "B", "C", "D", "E", "F").stream().map(n -> t.get(n).kind()).toList());
    }

    /**
     * Member types are buildings of their own named {@code Outer.Inner}; the outer type's lines include theirs, but its
     * attributes do not. Local and anonymous classes are no buildings.
     */
    @Test
    void memberTypesAreBuildingsLocalAndAnonymousClassesAreNot() throws IOException {
        write("p/O.java", """
                package p;
                class O {
                  int a;
                  class In {
                    int b;
                  }
                  void f() {
                    class Local {}
                    Object o = new Object() {};
                  }
                }
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(java.util.Set.of("p.O", "p.O.In"), t.keySet());
        TypeInfo o = t.get("p.O");
        TypeInfo in = t.get("p.O.In");
        assertEquals(List.of(2, 11, 10, 1, 1), List.of(o.line(), o.endLine(), o.loc(), o.noa(), o.nom()));
        assertEquals(List.of(4, 6, 3, 1), List.of(in.line(), in.endLine(), in.loc(), in.noa()));
        assertEquals("O.In", in.name());
        assertEquals("p", in.packageName());
    }

    /**
     * Test code: a directory named test, tests, it, androidTest, testFixtures or __tests__ anywhere on the path, or a
     * file named *Test, *Tests or *IT. Other names containing "test" are not tests.
     */
    @Test
    void testDetectionByDirectoryAndFileName() throws IOException {
        write("src/test/java/T1.java", "class T1 {}");
        write("tests/T2.java", "class T2 {}");
        write("src/it/java/T3.java", "class T3 {}");
        write("app/src/androidTest/java/T4.java", "class T4 {}");
        write("lib/src/testFixtures/java/T5.java", "class T5 {}");
        write("__tests__/T6.java", "class T6 {}");
        write("src/main/java/FooTest.java", "class FooTest {}");
        write("src/main/java/FooTests.java", "class FooTests {}");
        write("src/main/java/FooIT.java", "class FooIT {}");
        write("src/main/java/Testing.java", "class Testing {}");
        write("src/main/java/TestFoo.java", "class TestFoo {}");
        write("src/main/java/testing/N1.java", "class N1 {}");
        write("contest/N2.java", "class N2 {}");
        Map<String, TypeInfo> t = byFqn(analyze());
        for (String test : List.of("T1", "T2", "T3", "T4", "T5", "T6", "FooTest", "FooTests", "FooIT")) {
            assertTrue(t.get(test).test(), test);
        }
        for (String plain : List.of("Testing", "TestFoo", "N1", "N2")) {
            assertFalse(t.get(plain).test(), plain);
        }
    }

    /** The build module is the path before the first {@code src} directory, "" when there is none. */
    @Test
    void moduleIsThePathBeforeTheFirstSrc() throws IOException {
        write("Root.java", "class Root {}");
        write("src/main/java/a/A.java", "package a; class A {}");
        write("core/api/src/main/java/b/B.java", "package b; class B {}");
        write("x/src/y/src/C.java", "class C {}");
        write("lib/D.java", "class D {}");
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of("", "", "core/api", "x", ""),
                List.of("Root", "a.A", "b.B", "C", "D").stream().map(n -> t.get(n).module()).toList());
    }

    /** Only files with syntax errors count as failed; the others are analysed as usual, and so is the broken one. */
    @Test
    void onlyFilesWithSyntaxErrorsCountAsFailed() throws IOException {
        write("A.java", "class A {}");
        write("B.java", "class B { void f() { } }");
        write("Bad.java", "class Bad { int x = ; }");
        Analysis a = analyze();
        assertEquals(List.of(3, 1), List.of(a.files(), a.failedFiles()));
        assertEquals(java.util.Set.of("A", "B", "Bad"), byFqn(a).keySet());
    }

    // ------------------------------------------------------------------ name lookup

    /** A member type of the referring type itself comes before any other candidate. */
    @Test
    void lookupFindsMemberTypesOfTheTypeItselfFirst() throws IOException {
        write("p/A.java", "package p; class A { B b; class B {} }");
        write("p/B.java", "package p; class B {}");
        assertEquals(List.of("p.A.B"), deps(analyze(), "p.A"));
    }

    /** Then member types of the enclosing types, innermost first. */
    @Test
    void lookupTriesEnclosingTypesInnermostFirst() throws IOException {
        write("p/Outer.java", "package p; class Outer { class X {} class Mid { class X {} class In { X x; } } }");
        assertEquals(List.of("p.Outer.Mid.X"), deps(analyze(), "p.Outer.Mid.In"));
    }

    /** A single-type import comes before a type of the same name in the same package. */
    @Test
    void singleTypeImportBeatsTheSamePackage() throws IOException {
        write("p/A.java", "package p; import q.B; class A { B b; }");
        write("p/B.java", "package p; class B {}");
        write("q/B.java", "package q; public class B {}");
        assertEquals(List.of("q.B"), deps(analyze(), "p.A"));
    }

    /** The same package comes before an on-demand import. */
    @Test
    void samePackageBeatsOnDemandImports() throws IOException {
        write("p/A.java", "package p; import q.*; class A { B b; }");
        write("p/B.java", "package p; class B {}");
        write("q/B.java", "package q; public class B {}");
        assertEquals(List.of("p.B"), deps(analyze(), "p.A"));
    }

    /** On-demand imports are tried in source order: the first package that has the name wins. */
    @Test
    void onDemandImportsAreTriedInOrder() throws IOException {
        write("p/A.java", "package p; import q.*; import r.*; class A { B b; C c; }");
        write("q/B.java", "package q; public class B {}");
        write("r/B.java", "package r; public class B {}");
        write("r/C.java", "package r; public class C {}");
        assertEquals(List.of("q.B", "r.C"), deps(analyze(), "p.A"));
    }

    /** A fully qualified name resolves without an import; one that names no project type yields nothing. */
    @Test
    void fullyQualifiedNamesResolveWithoutImports() throws IOException {
        write("p/A.java", "package p; class A { q.B b; q.Missing m; java.util.List<String> l; }");
        write("q/B.java", "package q; public class B {}");
        assertEquals(List.of("q.B"), deps(analyze(), "p.A"));
    }

    /** {@code Outer.Missing} still depends on {@code Outer}; {@code Outer.In} on the member type and its scope. */
    @Test
    void qualifiedNameWithUnknownMemberFallsBackToTheOuterType() throws IOException {
        write("p/A.java", "package p; import q.Outer; class A { Outer.Missing m; }");
        write("p/B.java", "package p; import q.Outer; class B { Outer.In i; }");
        write("q/Outer.java", "package q; public class Outer { public class In {} }");
        Analysis a = analyze();
        assertEquals(List.of("q.Outer"), deps(a, "p.A"));
        assertEquals(List.of("q.Outer", "q.Outer.In"), deps(a, "p.B"));
    }

    /**
     * A static import makes the member's declaring type a candidate for its simple name, but calling the imported
     * member names no type and adds no edge. The same holds for a static on-demand import.
     */
    @Test
    void staticImportsMakeTheDeclaringTypeACandidateOnly() throws IOException {
        write("p/Call.java", "package p; import static q.Util.go; class Call { void f() { go(); } }");
        write("p/Name.java", "package p; import static q.Util.go; class Name { Util u; }");
        write("p/Star.java", "package p; import static q.Consts.*; class Star { Consts c; }");
        write("q/Util.java", "package q; public class Util { public static void go() {} }");
        write("q/Consts.java", "package q; public class Consts {}");
        Analysis a = analyze();
        assertEquals(List.of(), deps(a, "p.Call"));
        assertEquals(List.of("q.Util"), deps(a, "p.Name"));
        assertEquals(List.of("q.Consts"), deps(a, "p.Star"));
    }

    /** Default-package types see each other, but a named package never sees them, not even by their plain name. */
    @Test
    void defaultPackageIsVisibleOnlyFromItself() throws IOException {
        write("D.java", "class D { E e; }");
        write("E.java", "class E {}");
        write("q/Z.java", "package q; class Z { E e; }");
        Analysis a = analyze();
        assertEquals(List.of("E"), deps(a, "D"));
        assertEquals(List.of(), deps(a, "q.Z"));
    }

    /** A type that mentions itself has no edge to itself. */
    @Test
    void selfReferencesAreDropped() throws IOException {
        write("p/A.java", "package p; class A { A next; static A make() { return new A(); } }");
        assertEquals(List.of(), deps(analyze(), "p.A"));
    }

    /** Only an upper-case call scope ({@code Util.go()}) is a type reference; {@code util.go()} is a variable's. */
    @Test
    void lowerCaseCallScopesAreNoReferences() throws IOException {
        write("p/A.java", "package p; class A { void f(Object util) { util.toString(); Util.go(); } }");
        write("p/util.java", "package p; class util {}");
        write("p/Util.java", "package p; class Util { static void go() {} }");
        assertEquals(List.of("p.Util"), deps(analyze(), "p.A"));
    }

    /** Annotations on types, methods and parameters are references. */
    @Test
    void annotationsAreReferences() throws IOException {
        write("p/A.java", "package p; @T class A { @M void f(@P int x) {} }");
        write("p/T.java", "package p; @interface T {}");
        write("p/M.java", "package p; @interface M {}");
        write("p/P.java", "package p; @interface P {}");
        assertEquals(List.of("p.M", "p.P", "p.T"), deps(analyze(), "p.A"));
    }

    /** Type arguments and bounds are references, nested ones included. */
    @Test
    void genericArgumentsAndBoundsAreReferences() throws IOException {
        write("p/A.java", "package p; class A<T extends Base> { java.util.Map<Key, java.util.List<Val>> m; }");
        write("p/Base.java", "package p; class Base {}");
        write("p/Key.java", "package p; class Key {}");
        write("p/Val.java", "package p; class Val {}");
        assertEquals(List.of("p.Base", "p.Key", "p.Val"), deps(analyze(), "p.A"));
    }
}
