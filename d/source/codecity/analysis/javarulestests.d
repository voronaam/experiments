/// One test per rule of the Java analyser: each metric (attributes, methods, lines of code, complexity), the building
/// kinds, test detection, build modules, the failed-file flag, and each step of the name lookup in its order. The
/// golden suite checks whole cities; these tests name the rule that broke. The Java edition (`JavaRulesTest`) and the
/// Rust edition (`analysis/java_rules_tests.rs`) run the same scenarios with the same expectations.
module codecity.analysis.javarulestests;

version (unittest):

import codecity.analysis;
import codecity.git : realPath;
import codecity.model;
import std.algorithm : map, sort;
import std.array : array, join;
import std.file : mkdirRecurse, rmdirRecurse, tempDir, write;
import std.format : format;
import std.path : buildPath, dirName;
import std.uuid : randomUUID;

/// A temporary source tree, removed by `cleanup`.
private struct Fixture
{
    string root;

    static Fixture create()
    {
        auto dir = buildPath(tempDir, "codecity-test-" ~ randomUUID.toString);
        mkdirRecurse(dir);
        return Fixture(realPath(dir));
    }

    void put(string rel, string code)
    {
        const f = buildPath(root, rel);
        mkdirRecurse(f.dirName);
        write(f, code);
    }

    Analysis analyze() { return new SourceAnalyzer().analyze(root, true); }

    void cleanup() { rmdirRecurse(root); }
}

/// The lines joined with `\n`, ending with one, like a Java text block.
private string src(string[] lines...)
{
    string s;
    foreach (l; lines) s ~= l ~ "\n";
    return s;
}

private const(TypeDecl)* get(ref Analysis a, string fqn)
{
    foreach (ref t; a.types) if (t.fqn == fqn) return &t;
    assert(0, "missing " ~ fqn);
}

/// Every fqn, sorted.
private string[] fqns(ref Analysis a)
{
    auto s = a.types.map!(t => t.fqn).array;
    s.sort();
    return s;
}

private const(string)[] methodNames(const(TypeDecl)* t)
{
    return t.methods.map!(m => m.name).array;
}

// ------------------------------------------------------------------ metrics

unittest // attributesCountDeclaratorsConstantsAndRecordComponents: declarators, enum constants and fields, record components but not varargs, interface constants
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("F.java", src(
        "class F { int a, b, c; static String s; }",
        "enum E { A, B, C; int x; }",
        "record R(int x, int y, String... rest) {}",
        "interface I { int P = 1, Q = 2; }"));
    auto a = f.analyze();
    assert([get(a, "F").noa, get(a, "E").noa, get(a, "R").noa, get(a, "I").noa] == [4, 4, 2, 2]);
}

unittest // methodsCountConstructorsButNotInitialisers: initialiser blocks and compact record constructors are no methods
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("M.java", src(
        "class M { M() {} M(int x) {} void f() {} static { } { } }",
        "record P(int x) { P { } void g() {} }"));
    auto a = f.analyze();
    assert(methodNames(get(a, "M")) == ["M", "M", "f"]);
    assert(methodNames(get(a, "P")) == ["g"]);
}

unittest // linesOfCodeSkipBlankAndCommentLines: blank, // and block comment lines do not count; code beside a comment does
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("L.java", src(
        "class L {",
        "",
        "  // comment",
        "  /* block",
        "     still comment */",
        "  int a;",
        "  /* c */ int b;",
        "  int c; // trailing",
        "}"));
    auto a = f.analyze();
    const l = &a.types[0];
    assert(l.line == 1 && l.endLine == 9 && l.loc == 5);
}

unittest // linesOfCodeSeeThroughStringsAndHalfClosedComments: "/*" in a string, "/*/" opening only, code after "*/"
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("S.java", "class S {\n  String p = \"/*\";\n  char q = '\"'; int a;\n  /*/ still open\n  int hidden;\n  */ int after;\n}\n");
    assert(f.analyze().types[0].loc == 5);
}

unittest // linesOfCodeWithCrlfLineEndings: CRLF breaks lines like LF; the \r is not code
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("C.java", "class C {\r\n  int a;\r\n\r\n}\r\n");
    auto a = f.analyze();
    const c = &a.types[0];
    assert(c.line == 1 && c.endLine == 4 && c.loc == 3);
}

unittest // complexityCountsEachDecisionKind: if, for, for-each, while, do, catch, ?:, case labels, &&, ||, lambdas, anonymous classes, no body
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("X.java", src(
        "abstract class X {",
        "  void none() {}",
        "  void ifs(boolean a) { if (a) {} else if (!a) {} else {} }",
        "  void fors() { for (int i = 0; i < 1; i++) {} }",
        "  void each(int[] xs) { for (int x : xs) {} }",
        "  void whiles(boolean a) { while (a) {} }",
        "  void dos(boolean a) { do {} while (a); }",
        "  void catches() { try {} catch (RuntimeException e) {} catch (Error e) {} finally {} }",
        "  int ternary(boolean a) { return a ? 1 : 0; }",
        "  void cases(int k) { switch (k) { case 1, 2: break; case 3: break; default: break; } }",
        "  int arrows(int k) { return switch (k) { case 1 -> 1; default -> 0; }; }",
        "  boolean logic(boolean a, boolean b, boolean c) { return a && b || c; }",
        "  void lambda() { Runnable r = () -> { if (true) {} }; }",
        "  void anon() { Object o = new Object() { void m(boolean a) { while (a) {} } }; }",
        "  abstract void sig();",
        "}"));
    auto a = f.analyze();
    uint[string] cx;
    foreach (m; a.types[0].methods) cx[m.name] = m.complexity;
    assert(cx == ["none": 1u, "ifs": 3, "fors": 2, "each": 2, "whiles": 2, "dos": 2, "catches": 3,
        "ternary": 2, "cases": 3, "arrows": 2, "logic": 3, "lambda": 2, "anon": 2, "sig": 1]);
}

unittest // kindsOfJavaTypes: class, abstract, interface, enum, record, annotation
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("K.java", src("class A {}", "abstract class B {}", "interface C {}", "enum D {}", "record E() {}", "@interface F {}"));
    auto a = f.analyze();
    string[] kinds;
    foreach (n; ["A", "B", "C", "D", "E", "F"]) kinds ~= get(a, n).kind;
    assert(kinds == ["class", "abstract", "interface", "enum", "record", "annotation"]);
}

unittest // memberTypesAreBuildingsLocalAndAnonymousClassesAreNot: Outer.Inner buildings, outer lines include them
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/O.java", src(
        "package p;",
        "class O {",
        "  int a;",
        "  class In {",
        "    int b;",
        "  }",
        "  void f() {",
        "    class Local {}",
        "    Object o = new Object() {};",
        "  }",
        "}"));
    auto a = f.analyze();
    assert(fqns(a) == ["p.O", "p.O.In"]);
    const o = get(a, "p.O");
    const inner = get(a, "p.O.In");
    assert(o.line == 2 && o.endLine == 11 && o.loc == 10 && o.noa == 1 && o.nom == 1);
    assert(inner.line == 4 && inner.endLine == 6 && inner.loc == 3 && inner.noa == 1);
    assert(inner.name == "O.In" && inner.pkg == "p");
}

unittest // testDetectionByDirectoryAndFileName: test directories anywhere on the path, *Test/*Tests/*IT files
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    foreach (c; [["src/test/java/T1.java", "T1"], ["tests/T2.java", "T2"], ["src/it/java/T3.java", "T3"],
            ["app/src/androidTest/java/T4.java", "T4"], ["lib/src/testFixtures/java/T5.java", "T5"],
            ["__tests__/T6.java", "T6"], ["src/main/java/FooTest.java", "FooTest"],
            ["src/main/java/FooTests.java", "FooTests"], ["src/main/java/FooIT.java", "FooIT"],
            ["src/main/java/Testing.java", "Testing"], ["src/main/java/TestFoo.java", "TestFoo"],
            ["src/main/java/testing/N1.java", "N1"], ["contest/N2.java", "N2"]])
        f.put(c[0], format("class %s {}", c[1]));
    auto a = f.analyze();
    foreach (n; ["T1", "T2", "T3", "T4", "T5", "T6", "FooTest", "FooTests", "FooIT"]) assert(get(a, n).test, n);
    foreach (n; ["Testing", "TestFoo", "N1", "N2"]) assert(!get(a, n).test, n);
}

unittest // moduleIsThePathBeforeTheFirstSrc: "" without a src directory
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Root.java", "class Root {}");
    f.put("src/main/java/a/A.java", "package a; class A {}");
    f.put("core/api/src/main/java/b/B.java", "package b; class B {}");
    f.put("x/src/y/src/C.java", "class C {}");
    f.put("lib/D.java", "class D {}");
    auto a = f.analyze();
    string[] modules;
    foreach (n; ["Root", "a.A", "b.B", "C", "D"]) modules ~= get(a, n).module_;
    assert(modules == ["", "", "core/api", "x", ""]);
}

unittest // onlyFilesWithSyntaxErrorsCountAsFailed: the broken file is still analysed
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("A.java", "class A {}");
    f.put("B.java", "class B { void f() { } }");
    f.put("Bad.java", "class Bad { int x = ; }");
    auto a = f.analyze();
    assert(a.files == 3 && a.failedFiles == 1);
    assert(fqns(a) == ["A", "B", "Bad"]);
}

// ------------------------------------------------------------------ name lookup

unittest // lookupFindsMemberTypesOfTheTypeItselfFirst
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; class A { B b; class B {} }");
    f.put("p/B.java", "package p; class B {}");
    assert(f.analyze().dependencies["p.A"] == ["p.A.B"]);
}

unittest // lookupTriesEnclosingTypesInnermostFirst
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/Outer.java", "package p; class Outer { class X {} class Mid { class X {} class In { X x; } } }");
    assert(f.analyze().dependencies["p.Outer.Mid.In"] == ["p.Outer.Mid.X"]);
}

unittest // singleTypeImportBeatsTheSamePackage
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; import q.B; class A { B b; }");
    f.put("p/B.java", "package p; class B {}");
    f.put("q/B.java", "package q; public class B {}");
    assert(f.analyze().dependencies["p.A"] == ["q.B"]);
}

unittest // samePackageBeatsOnDemandImports
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; import q.*; class A { B b; }");
    f.put("p/B.java", "package p; class B {}");
    f.put("q/B.java", "package q; public class B {}");
    assert(f.analyze().dependencies["p.A"] == ["p.B"]);
}

unittest // onDemandImportsAreTriedInOrder: the first package that has the name wins
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; import q.*; import r.*; class A { B b; C c; }");
    f.put("q/B.java", "package q; public class B {}");
    f.put("r/B.java", "package r; public class B {}");
    f.put("r/C.java", "package r; public class C {}");
    assert(f.analyze().dependencies["p.A"] == ["q.B", "r.C"]);
}

unittest // fullyQualifiedNamesResolveWithoutImports: unknown ones yield nothing
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; class A { q.B b; q.Missing m; java.util.List<String> l; }");
    f.put("q/B.java", "package q; public class B {}");
    assert(f.analyze().dependencies["p.A"] == ["q.B"]);
}

unittest // qualifiedNameWithUnknownMemberFallsBackToTheOuterType: Outer.In also depends on its scope Outer
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; import q.Outer; class A { Outer.Missing m; }");
    f.put("p/B.java", "package p; import q.Outer; class B { Outer.In i; }");
    f.put("q/Outer.java", "package q; public class Outer { public class In {} }");
    auto a = f.analyze();
    assert(a.dependencies["p.A"] == ["q.Outer"]);
    assert(a.dependencies["p.B"] == ["q.Outer", "q.Outer.In"]);
}

unittest // staticImportsMakeTheDeclaringTypeACandidateOnly: calling the imported member adds no edge
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/Call.java", "package p; import static q.Util.go; class Call { void f() { go(); } }");
    f.put("p/Name.java", "package p; import static q.Util.go; class Name { Util u; }");
    f.put("p/Star.java", "package p; import static q.Consts.*; class Star { Consts c; }");
    f.put("q/Util.java", "package q; public class Util { public static void go() {} }");
    f.put("q/Consts.java", "package q; public class Consts {}");
    auto a = f.analyze();
    assert(a.dependencies["p.Call"].length == 0);
    assert(a.dependencies["p.Name"] == ["q.Util"]);
    assert(a.dependencies["p.Star"] == ["q.Consts"]);
}

unittest // defaultPackageIsVisibleOnlyFromItself
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("D.java", "class D { E e; }");
    f.put("E.java", "class E {}");
    f.put("q/Z.java", "package q; class Z { E e; }");
    auto a = f.analyze();
    assert(a.dependencies["D"] == ["E"]);
    assert(a.dependencies["q.Z"].length == 0);
}

unittest // selfReferencesAreDropped
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; class A { A next; static A make() { return new A(); } }");
    assert(f.analyze().dependencies["p.A"].length == 0);
}

unittest // lowerCaseCallScopesAreNoReferences: Util.go() is a type reference, util.toString() is not
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; class A { void f(Object util) { util.toString(); Util.go(); } }");
    f.put("p/util.java", "package p; class util {}");
    f.put("p/Util.java", "package p; class Util { static void go() {} }");
    assert(f.analyze().dependencies["p.A"] == ["p.Util"]);
}

unittest // annotationsAreReferences: on types, methods and parameters
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; @T class A { @M void f(@P int x) {} }");
    f.put("p/T.java", "package p; @interface T {}");
    f.put("p/M.java", "package p; @interface M {}");
    f.put("p/P.java", "package p; @interface P {}");
    assert(f.analyze().dependencies["p.A"] == ["p.M", "p.P", "p.T"]);
}

unittest // genericArgumentsAndBoundsAreReferences: nested ones included
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("p/A.java", "package p; class A<T extends Base> { java.util.Map<Key, java.util.List<Val>> m; }");
    f.put("p/Base.java", "package p; class Base {}");
    f.put("p/Key.java", "package p; class Key {}");
    f.put("p/Val.java", "package p; class Val {}");
    assert(f.analyze().dependencies["p.A"] == ["p.Base", "p.Key", "p.Val"]);
}
