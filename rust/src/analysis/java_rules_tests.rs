//! One test per rule of the Java analyser: each metric (attributes, methods, lines of code, complexity), the building
//! kinds, test detection, build modules, the failed-file flag, and each step of the name lookup in its order. The
//! golden suite checks whole cities; these tests name the rule that broke. The Java edition (`JavaRulesTest`) and the
//! D edition (`analysis/javarulestests.d`) run the same scenarios with the same expectations.

use super::SourceAnalyzer;
use crate::model::{Analysis, TypeInfo};
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::Path;

fn write(root: &Path, rel: &str, code: &str) {
    let f = root.join(rel);
    std::fs::create_dir_all(f.parent().unwrap()).unwrap();
    std::fs::write(f, code).unwrap();
}

/// The lines joined with `\n`, ending with one, like a Java text block.
fn src(lines: &[&str]) -> String {
    lines.iter().map(|l| format!("{l}\n")).collect()
}

fn analyze(root: &Path) -> Analysis {
    SourceAnalyzer::new().analyze(&std::fs::canonicalize(root).unwrap(), true)
}

fn by_fqn(a: &Analysis) -> HashMap<&str, &TypeInfo> {
    a.types.iter().map(|t| (t.fqn.as_str(), t)).collect()
}

fn fqns(a: &Analysis) -> BTreeSet<&str> {
    a.types.iter().map(|t| t.fqn.as_str()).collect()
}

/// The (sorted) dependencies of `fqn`.
fn deps<'a>(a: &'a Analysis, fqn: &str) -> Vec<&'a str> {
    a.dependencies[fqn].iter().map(String::as_str).collect()
}

fn method_names(t: &TypeInfo) -> Vec<&str> {
    t.methods.iter().map(|m| m.name.as_str()).collect()
}

// ------------------------------------------------------------------ metrics

/// Attributes: one per declarator (`int a, b, c;` is three), static fields included; one per enum constant plus the
/// enum's fields; one per record component except a varargs one; one per interface constant.
#[test]
fn attributes_count_declarators_constants_and_record_components() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "F.java", &src(&[
        "class F { int a, b, c; static String s; }",
        "enum E { A, B, C; int x; }",
        "record R(int x, int y, String... rest) {}",
        "interface I { int P = 1, Q = 2; }",
    ]));
    let a = analyze(dir.path());
    let t = by_fqn(&a);
    assert_eq!([t["F"].noa, t["E"].noa, t["R"].noa, t["I"].noa], [4, 4, 2, 2]);
}

/// Methods and constructors are methods; static and instance initialisers and compact record constructors are not.
#[test]
fn methods_count_constructors_but_not_initialisers() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "M.java", &src(&[
        "class M { M() {} M(int x) {} void f() {} static { } { } }",
        "record P(int x) { P { } void g() {} }",
    ]));
    let a = analyze(dir.path());
    let t = by_fqn(&a);
    assert_eq!(method_names(t["M"]), ["M", "M", "f"]);
    assert_eq!(method_names(t["P"]), ["g"]);
}

/// Blank lines, `//` lines and the lines of a block comment do not count; code before or after a comment does.
#[test]
fn lines_of_code_skip_blank_and_comment_lines() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "L.java", &src(&[
        "class L {",
        "",
        "  // comment",
        "  /* block",
        "     still comment */",
        "  int a;",
        "  /* c */ int b;",
        "  int c; // trailing",
        "}",
    ]));
    let a = analyze(dir.path());
    let l = &a.types[0];
    assert_eq!((l.line, l.end_line, l.loc), (1, 9, 5));
}

/// `/*` inside a string opens no comment; `/*/` opens one without closing it; code after the closing `*/` counts.
#[test]
fn lines_of_code_see_through_strings_and_half_closed_comments() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "S.java", "class S {\n  String p = \"/*\";\n  char q = '\"'; int a;\n  /*/ still open\n  int hidden;\n  */ int after;\n}\n");
    assert_eq!(analyze(dir.path()).types[0].loc, 5);
}

/// CRLF line endings break lines like LF; the `\r` is not code.
#[test]
fn lines_of_code_with_crlf_line_endings() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "C.java", "class C {\r\n  int a;\r\n\r\n}\r\n");
    let a = analyze(dir.path());
    let c = &a.types[0];
    assert_eq!((c.line, c.end_line, c.loc), (1, 4, 3));
}

/// Complexity is 1 plus one per decision point: if (else-if is another if), for, for-each, while, do, catch, `?:`,
/// every non-default case label (`case 1, 2` once), `&&` and `||`. Lambdas and anonymous classes add to the enclosing
/// method; a method without a body is 1.
#[test]
fn complexity_counts_each_decision_kind() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "X.java", &src(&[
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
        "}",
    ]));
    let a = analyze(dir.path());
    let cx: BTreeMap<&str, u32> = a.types[0].methods.iter().map(|m| (m.name.as_str(), m.complexity)).collect();
    let expected: BTreeMap<&str, u32> = [
        ("none", 1), ("ifs", 3), ("fors", 2), ("each", 2), ("whiles", 2), ("dos", 2), ("catches", 3),
        ("ternary", 2), ("cases", 3), ("arrows", 2), ("logic", 3), ("lambda", 2), ("anon", 2), ("sig", 1),
    ].into_iter().collect();
    assert_eq!(cx, expected);
}

/// Class, abstract class, interface, enum, record and annotation type each have their own kind.
#[test]
fn kinds_of_java_types() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "K.java", &src(&[
        "class A {}", "abstract class B {}", "interface C {}", "enum D {}", "record E() {}", "@interface F {}",
    ]));
    let a = analyze(dir.path());
    let t = by_fqn(&a);
    let kinds: Vec<&str> = ["A", "B", "C", "D", "E", "F"].iter().map(|n| t[n].kind).collect();
    assert_eq!(kinds, ["class", "abstract", "interface", "enum", "record", "annotation"]);
}

/// Member types are buildings of their own named `Outer.Inner`; the outer type's lines include theirs, but its
/// attributes do not. Local and anonymous classes are no buildings.
#[test]
fn member_types_are_buildings_local_and_anonymous_classes_are_not() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/O.java", &src(&[
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
        "}",
    ]));
    let a = analyze(dir.path());
    assert_eq!(fqns(&a), BTreeSet::from(["p.O", "p.O.In"]));
    let t = by_fqn(&a);
    let (o, inner) = (t["p.O"], t["p.O.In"]);
    assert_eq!((o.line, o.end_line, o.loc, o.noa, o.nom()), (2, 11, 10, 1, 1));
    assert_eq!((inner.line, inner.end_line, inner.loc, inner.noa), (4, 6, 3, 1));
    assert_eq!((inner.name.as_str(), inner.package.as_str()), ("O.In", "p"));
}

/// Test code: a directory named test, tests, it, androidTest, testFixtures or __tests__ anywhere on the path, or a
/// file named *Test, *Tests or *IT. Other names containing "test" are not tests.
#[test]
fn test_detection_by_directory_and_file_name() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    for (path, name) in [
        ("src/test/java/T1.java", "T1"), ("tests/T2.java", "T2"), ("src/it/java/T3.java", "T3"),
        ("app/src/androidTest/java/T4.java", "T4"), ("lib/src/testFixtures/java/T5.java", "T5"),
        ("__tests__/T6.java", "T6"), ("src/main/java/FooTest.java", "FooTest"),
        ("src/main/java/FooTests.java", "FooTests"), ("src/main/java/FooIT.java", "FooIT"),
        ("src/main/java/Testing.java", "Testing"), ("src/main/java/TestFoo.java", "TestFoo"),
        ("src/main/java/testing/N1.java", "N1"), ("contest/N2.java", "N2"),
    ] {
        write(r, path, &format!("class {name} {{}}"));
    }
    let a = analyze(r);
    let t = by_fqn(&a);
    for name in ["T1", "T2", "T3", "T4", "T5", "T6", "FooTest", "FooTests", "FooIT"] {
        assert!(t[name].test, "{name}");
    }
    for name in ["Testing", "TestFoo", "N1", "N2"] {
        assert!(!t[name].test, "{name}");
    }
}

/// The build module is the path before the first `src` directory, "" when there is none.
#[test]
fn module_is_the_path_before_the_first_src() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Root.java", "class Root {}");
    write(r, "src/main/java/a/A.java", "package a; class A {}");
    write(r, "core/api/src/main/java/b/B.java", "package b; class B {}");
    write(r, "x/src/y/src/C.java", "class C {}");
    write(r, "lib/D.java", "class D {}");
    let a = analyze(r);
    let t = by_fqn(&a);
    let modules: Vec<&str> = ["Root", "a.A", "b.B", "C", "D"].iter().map(|n| t[n].module.as_str()).collect();
    assert_eq!(modules, ["", "", "core/api", "x", ""]);
}

/// Only files with syntax errors count as failed; the others are analysed as usual, and so is the broken one.
#[test]
fn only_files_with_syntax_errors_count_as_failed() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "A.java", "class A {}");
    write(r, "B.java", "class B { void f() { } }");
    write(r, "Bad.java", "class Bad { int x = ; }");
    let a = analyze(r);
    assert_eq!((a.files, a.failed_files), (3, 1));
    assert_eq!(fqns(&a), BTreeSet::from(["A", "B", "Bad"]));
}

// ------------------------------------------------------------------ name lookup

/// A member type of the referring type itself comes before any other candidate.
#[test]
fn lookup_finds_member_types_of_the_type_itself_first() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/A.java", "package p; class A { B b; class B {} }");
    write(dir.path(), "p/B.java", "package p; class B {}");
    assert_eq!(deps(&analyze(dir.path()), "p.A"), ["p.A.B"]);
}

/// Then member types of the enclosing types, innermost first.
#[test]
fn lookup_tries_enclosing_types_innermost_first() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/Outer.java", "package p; class Outer { class X {} class Mid { class X {} class In { X x; } } }");
    assert_eq!(deps(&analyze(dir.path()), "p.Outer.Mid.In"), ["p.Outer.Mid.X"]);
}

/// A single-type import comes before a type of the same name in the same package.
#[test]
fn single_type_import_beats_the_same_package() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/A.java", "package p; import q.B; class A { B b; }");
    write(dir.path(), "p/B.java", "package p; class B {}");
    write(dir.path(), "q/B.java", "package q; public class B {}");
    assert_eq!(deps(&analyze(dir.path()), "p.A"), ["q.B"]);
}

/// The same package comes before an on-demand import.
#[test]
fn same_package_beats_on_demand_imports() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/A.java", "package p; import q.*; class A { B b; }");
    write(dir.path(), "p/B.java", "package p; class B {}");
    write(dir.path(), "q/B.java", "package q; public class B {}");
    assert_eq!(deps(&analyze(dir.path()), "p.A"), ["p.B"]);
}

/// On-demand imports are tried in source order: the first package that has the name wins.
#[test]
fn on_demand_imports_are_tried_in_order() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/A.java", "package p; import q.*; import r.*; class A { B b; C c; }");
    write(dir.path(), "q/B.java", "package q; public class B {}");
    write(dir.path(), "r/B.java", "package r; public class B {}");
    write(dir.path(), "r/C.java", "package r; public class C {}");
    assert_eq!(deps(&analyze(dir.path()), "p.A"), ["q.B", "r.C"]);
}

/// A fully qualified name resolves without an import; one that names no project type yields nothing.
#[test]
fn fully_qualified_names_resolve_without_imports() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/A.java", "package p; class A { q.B b; q.Missing m; java.util.List<String> l; }");
    write(dir.path(), "q/B.java", "package q; public class B {}");
    assert_eq!(deps(&analyze(dir.path()), "p.A"), ["q.B"]);
}

/// `Outer.Missing` still depends on `Outer`; `Outer.In` on the member type and its scope.
#[test]
fn qualified_name_with_unknown_member_falls_back_to_the_outer_type() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/A.java", "package p; import q.Outer; class A { Outer.Missing m; }");
    write(dir.path(), "p/B.java", "package p; import q.Outer; class B { Outer.In i; }");
    write(dir.path(), "q/Outer.java", "package q; public class Outer { public class In {} }");
    let a = analyze(dir.path());
    assert_eq!(deps(&a, "p.A"), ["q.Outer"]);
    assert_eq!(deps(&a, "p.B"), ["q.Outer", "q.Outer.In"]);
}

/// A static import makes the member's declaring type a candidate for its simple name, but calling the imported member
/// names no type and adds no edge. The same holds for a static on-demand import.
#[test]
fn static_imports_make_the_declaring_type_a_candidate_only() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "p/Call.java", "package p; import static q.Util.go; class Call { void f() { go(); } }");
    write(r, "p/Name.java", "package p; import static q.Util.go; class Name { Util u; }");
    write(r, "p/Star.java", "package p; import static q.Consts.*; class Star { Consts c; }");
    write(r, "q/Util.java", "package q; public class Util { public static void go() {} }");
    write(r, "q/Consts.java", "package q; public class Consts {}");
    let a = analyze(r);
    assert!(deps(&a, "p.Call").is_empty());
    assert_eq!(deps(&a, "p.Name"), ["q.Util"]);
    assert_eq!(deps(&a, "p.Star"), ["q.Consts"]);
}

/// Default-package types see each other, but a named package never sees them, not even by their plain name.
#[test]
fn default_package_is_visible_only_from_itself() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "D.java", "class D { E e; }");
    write(dir.path(), "E.java", "class E {}");
    write(dir.path(), "q/Z.java", "package q; class Z { E e; }");
    let a = analyze(dir.path());
    assert_eq!(deps(&a, "D"), ["E"]);
    assert!(deps(&a, "q.Z").is_empty());
}

/// A type that mentions itself has no edge to itself.
#[test]
fn self_references_are_dropped() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/A.java", "package p; class A { A next; static A make() { return new A(); } }");
    assert!(deps(&analyze(dir.path()), "p.A").is_empty());
}

/// Only an upper-case call scope (`Util.go()`) is a type reference; `util.go()` is a variable's.
#[test]
fn lower_case_call_scopes_are_no_references() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "p/A.java", "package p; class A { void f(Object util) { util.toString(); Util.go(); } }");
    write(dir.path(), "p/util.java", "package p; class util {}");
    write(dir.path(), "p/Util.java", "package p; class Util { static void go() {} }");
    assert_eq!(deps(&analyze(dir.path()), "p.A"), ["p.Util"]);
}

/// Annotations on types, methods and parameters are references.
#[test]
fn annotations_are_references() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "p/A.java", "package p; @T class A { @M void f(@P int x) {} }");
    write(r, "p/T.java", "package p; @interface T {}");
    write(r, "p/M.java", "package p; @interface M {}");
    write(r, "p/P.java", "package p; @interface P {}");
    assert_eq!(deps(&analyze(r), "p.A"), ["p.M", "p.P", "p.T"]);
}

/// Type arguments and bounds are references, nested ones included.
#[test]
fn generic_arguments_and_bounds_are_references() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "p/A.java", "package p; class A<T extends Base> { java.util.Map<Key, java.util.List<Val>> m; }");
    write(r, "p/Base.java", "package p; class Base {}");
    write(r, "p/Key.java", "package p; class Key {}");
    write(r, "p/Val.java", "package p; class Val {}");
    assert_eq!(deps(&analyze(r), "p.A"), ["p.Base", "p.Key", "p.Val"]);
}
