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

/**
 * Rule-level tests of the Rust analyser ({@link RustAnalyzer}): one test per metric, test-detection, crate/module and
 * name-resolution rule, so that a failure points at the rule that changed. The golden suite checks whole cities; these
 * tests pin the individual rules behind them. The Rust ({@code analysis/rust_rules_tests.rs}) and D
 * ({@code analysis/rustrulestests.d}) editions run the same scenarios with the same expectations.
 */
class RustRulesTest {

    @TempDir
    Path root;

    private void write(String rel, String code) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, code);
    }

    private Analysis analyze() throws IOException {
        return new SourceAnalyzer().analyze(root.toRealPath(), true);
    }

    private static Map<String, TypeInfo> byFqn(Analysis a) {
        return a.types().stream().collect(Collectors.toMap(TypeInfo::fqn, Function.identity()));
    }

    private static List<String> methodNames(TypeInfo t) {
        return t.methods().stream().map(MethodInfo::name).toList();
    }

    private static MethodInfo method(TypeInfo t, String name) {
        return t.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * The shared crate "k" of the name-resolution tests: {@code a} (a directory module with {@code D}, the trait
     * {@code Tr} and the enum {@code E}) and its child {@code a::b} (with {@code C} and a free function), plus the module
     * {@code user}, whose file each test writes itself.
     */
    private void linkingCrate(String user) throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", "pub mod a;\npub mod user;\n");
        write("src/a/mod.rs", "pub mod b;\npub struct D;\npub trait Tr {}\npub enum E { V }\n");
        write("src/a/b.rs", "pub struct C;\nimpl C { pub fn new() -> Self { C } }\npub fn helper() {}\n");
        write("src/user.rs", user);
    }

    // ------------------------------------------------------------------ metrics

    /** NOA is the number of named fields, tuple fields, variants or union fields; a unit or empty struct has none. */
    @Test
    void attributesAreFieldsVariantsAndUnionFields() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", """
                pub struct Named { a: u8, pub b: u16, c: Vec<u8> }
                pub struct Tuple(u8, pub String);
                pub struct Unit;
                pub struct Empty {}
                pub enum E { A, B(u8, u8), C { x: u8 } }
                pub union U { f: u32, g: f32 }
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of(3, 2, 0, 0, 3, 2), List.of(t.get("k::Named").noa(), t.get("k::Tuple").noa(),
                t.get("k::Unit").noa(), t.get("k::Empty").noa(), t.get("k::E").noa(), t.get("k::U").noa()));
        assertEquals(List.of("struct", "struct", "enum", "union"), List.of(t.get("k::Named").kind(),
                t.get("k::Unit").kind(), t.get("k::E").kind(), t.get("k::U").kind()));
    }

    /** A trait's methods are its declared and its default methods; associated constants and types are not counted. */
    @Test
    void traitMethodsAreDeclaredAndDefaultMethods() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", """
                pub trait T {
                    const C: u8;
                    type X;
                    fn declared(&self, x: u8);
                    fn default(&self) -> u8 { if true { 1 } else { 0 } }
                }
                """);
        TypeInfo t = byFqn(analyze()).get("k::T");
        assertEquals("trait", t.kind());
        assertEquals(List.of("declared", "default"), methodNames(t));
        assertEquals(0, t.noa());
        assertEquals(List.of(1, 2), t.methods().stream().map(MethodInfo::complexity).toList());
        assertEquals(1, method(t, "declared").parameters());
    }

    /**
     * Impl blocks are merged into their type in file order (paths sorted, then source order), wherever they are; each
     * block's lines of code are added to the type's, while the type keeps its own line range.
     */
    @Test
    void implBlocksMergeInFileOrderAndAddTheirLines() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", """
                pub mod a;
                pub struct S;
                impl S {
                    fn one(&self) {}
                }
                impl S { fn two(&self, x: u8) {} }
                """);
        write("src/a.rs", """
                use crate::S;
                impl S {
                    // a comment

                    fn zero() {}
                }
                """);
        TypeInfo s = byFqn(analyze()).get("k::S");
        assertEquals(List.of("zero", "one", "two"), methodNames(s));
        assertEquals(List.of(2, 2), List.of(s.line(), s.endLine()));
        // 1 (the struct) + 3 (impl in a.rs, comment and blank line left out) + 3 + 1
        assertEquals(8, s.loc());
    }

    /**
     * Each module's free functions, constants and statics form its module building: functions are its methods,
     * constants and statics its attributes, its line range runs from the first to the last of them and its LOC counts
     * only them. A function signature without a body is left out. The building is named after the file, or
     * {@code mod name} for an inline module.
     */
    @Test
    void moduleBuildingHoldsFreeFunctionsConstantsAndStatics() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", """
                pub struct S;
                fn signature();
                pub fn f(a: u8, b: u8) {}
                // a comment
                const C: u8 = 1;
                static T: u8 = 2;
                pub struct After;
                fn g() {}
                mod inner { fn h() {} }
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        TypeInfo m = t.get("k::{fn}");
        assertEquals(List.of("module", "lib.rs", ""), List.of(m.kind(), m.name(), m.packageName()));
        assertEquals(List.of("f", "g"), methodNames(m));
        assertEquals(2, m.noa());
        assertEquals(List.of(3, 8, 4), List.of(m.line(), m.endLine(), m.loc()));
        assertEquals(2, method(m, "f").parameters());
        TypeInfo inner = t.get("k::inner::{fn}");
        assertEquals(List.of("mod inner", "inner", "k"), List.of(inner.name(), inner.packageName(), inner.module()));
        assertEquals(List.of("h"), methodNames(inner));
    }

    /**
     * Cyclomatic complexity: 1 + each if (else if included), while, loop, for, && and ||, plus k - 1 per match with k
     * arms; an empty match cannot take it below 1, {@code ?} is no decision, and closures and nested functions count
     * towards the enclosing function.
     */
    @Test
    void complexityCountsEachRustDecisionPoint() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", """
                fn straight() {}
                fn branches(x: u8) { if x > 0 {} else if x > 1 {} else {} }
                fn loops(x: u8) { while x > 0 {} loop { break; } for _i in 0..x {} }
                fn logic(a: bool, b: bool, c: bool) -> bool { a && b || c }
                fn arms(x: u8) -> u8 { match x { 0 => 1, 1 | 2 => 2, _ => 3 } }
                fn empty(x: Never) -> u8 { match x {} }
                fn question(x: Option<u8>) -> Option<u8> { let y = x?; Some(y) }
                fn nested(v: Vec<u8>) -> usize {
                    let f = |x: u8| if x > 0 { 1 } else { 0 };
                    fn inner(y: u8) -> bool { y > 0 && y < 9 }
                    v.len()
                }
                """);
        TypeInfo m = byFqn(analyze()).get("k::{fn}");
        Map<String, Integer> cx = m.methods().stream().collect(Collectors.toMap(MethodInfo::name, MethodInfo::complexity));
        assertEquals(Map.of("straight", 1, "branches", 3, "loops", 4, "logic", 3, "arms", 3, "empty", 1,
                "question", 1, "nested", 3), cx);
    }

    /** Parameters are counted without the {@code self} receiver, in whatever form it is written. */
    @Test
    void parametersDoNotCountTheSelfReceiver() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", """
                pub struct S;
                impl S {
                    fn a(&self, x: u8) {}
                    fn b(&mut self) {}
                    fn c(mut self, x: u8, y: u8) {}
                    fn d() {}
                }
                fn e(x: u8, (a, b): (u8, u8)) {}
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of(1, 0, 2, 0), t.get("k::S").methods().stream().map(MethodInfo::parameters).toList());
        assertEquals(2, method(t.get("k::{fn}"), "e").parameters());
    }

    // ------------------------------------------------------------------ test detection

    /**
     * Files in a crate's {@code tests/} and {@code benches/} directories are test code; a {@code tests} module
     * directory under {@code src/} is not.
     */
    @Test
    void testsAndBenchesDirectoriesAreTestCode() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", "pub struct L;\n");
        write("src/tests/x.rs", "pub struct X;\n");
        write("tests/it.rs", "pub struct T;\n");
        write("benches/b.rs", "pub struct B;\n");
        Map<String, TypeInfo> t = byFqn(analyze());
        assertFalse(t.get("k::L").test());
        assertFalse(t.get("k::tests::x::X").test());
        assertTrue(t.get("k::tests::it::T").test());
        assertTrue(t.get("k::benches::b::B").test());
    }

    /**
     * {@code #[cfg(test)]} (with any spacing) marks the next item only; further attributes in between keep it, a
     * comment ends it, and {@code #[cfg(all(test, …))]} is not recognised. A test-only free function does not make the
     * module building test code.
     */
    @Test
    void cfgTestAppliesToTheNextItemOnly() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", """
                #[cfg(test)]
                pub struct A;
                pub struct B;
                #[cfg( test )]
                #[derive(Debug)]
                pub struct C;
                #[cfg(test)]
                // a comment
                pub struct D;
                #[cfg(all(test, unix))]
                pub struct E;
                #[cfg(test)]
                fn helper() {}
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of(true, false, true, false, false, false), List.of(t.get("k::A").test(),
                t.get("k::B").test(), t.get("k::C").test(), t.get("k::D").test(), t.get("k::E").test(),
                t.get("k::{fn}").test()));
    }

    /**
     * Everything inside a {@code #[cfg(test)]} module is test code. The module itself stays in its parent's district,
     * but a module nested in it is an ordinary child district again.
     */
    @Test
    void cfgTestModulesKeepTheirParentsDistrict() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", "pub mod cart;\n");
        write("src/cart.rs", """
                pub struct Cart;
                #[cfg(test)]
                mod tests {
                    pub struct Fixture;
                    mod deep { pub struct X; }
                }
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertTrue(t.get("k::cart::tests::Fixture").test());
        assertEquals("cart", t.get("k::cart::tests::Fixture").packageName());
        assertTrue(t.get("k::cart::tests::deep::X").test());
        assertEquals("cart::tests::deep", t.get("k::cart::tests::deep::X").packageName());
    }

    // ------------------------------------------------------------------ crates and modules

    /** The crate is named by its Cargo.toml with dashes made underscores; it is the build module, not the package. */
    @Test
    void crateNamesUseUnderscores() throws IOException {
        write("Cargo.toml", "[package]\nname = \"my-crate\"\n");
        write("src/lib.rs", "pub struct A;\n");
        TypeInfo a = byFqn(analyze()).get("my_crate::A");
        assertEquals(List.of("my_crate", ""), List.of(a.module(), a.packageName()));
    }

    /** In nested crates, the nearest Cargo.toml with a package name decides the crate. */
    @Test
    void theNearestManifestDecidesTheCrate() throws IOException {
        write("Cargo.toml", "[package]\nname = \"outer\"\n");
        write("src/lib.rs", "pub struct B;\n");
        write("inner/Cargo.toml", "[package]\nname = \"inner\"\n");
        write("inner/src/lib.rs", "pub struct A;\n");
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals("outer", t.get("outer::B").module());
        assertEquals("inner", t.get("inner::A").module());
    }

    /** Without a package manifest (here a workspace-only one) the crate is named after the analysed directory. */
    @Test
    void withoutAPackageTheCrateIsNamedAfterTheRoot() throws IOException {
        Path ws = root.resolve("my-ws");
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("Cargo.toml"), "[workspace]\nmembers = []\n");
        Files.writeString(ws.resolve("src/lib.rs"), "pub struct A;\n");
        TypeInfo a = byFqn(new SourceAnalyzer().analyze(ws.toRealPath(), true)).get("my_ws::A");
        assertEquals("my_ws", a.module());
    }

    /**
     * Module paths: relative to the nearest directory holding lib.rs or main.rs; {@code mod.rs} stands for its
     * directory; lib.rs and main.rs at the source root are both the crate root; inline {@code mod} blocks add a segment.
     * A sub-directory holding its own lib.rs is a source root of its own, so its files start again at the crate root.
     * The package is the module path without the crate.
     */
    @Test
    void modulePathsFollowTheFileLayout() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", "pub struct L;\nmod inline { pub struct I; }\n");
        write("src/main.rs", "pub struct M;\n");
        write("src/net/mod.rs", "pub struct N;\n");
        write("src/net/http.rs", "pub struct H;\n");
        write("src/util/lib.rs", "pub struct U;\n");
        write("src/util/x.rs", "pub struct X;\n");
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of("", "", "inline", "net", "net::http", "", "x"), List.of(
                t.get("k::L").packageName(), t.get("k::M").packageName(), t.get("k::inline::I").packageName(),
                t.get("k::net::N").packageName(), t.get("k::net::http::H").packageName(),
                t.get("k::U").packageName(), t.get("k::x::X").packageName()));
    }

    /** A file with a syntax error counts as failed, but the items around the error are kept. */
    @Test
    void syntaxErrorsFlagTheFileButKeepItsItems() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", "pub struct A;\nfn broken( { }\n");
        write("src/ok.rs", "pub struct B;\n");
        Analysis a = analyze();
        assertEquals(1, a.failedFiles());
        assertTrue(byFqn(a).containsKey("k::A"));
        assertTrue(byFqn(a).containsKey("k::ok::B"));
    }

    // ------------------------------------------------------------------ name resolution

    /** {@code use a::b::C} binds C to that path. */
    @Test
    void useBindsTheLastSegment() throws IOException {
        linkingCrate("use crate::a::b::C;\npub struct U { c: C }\n");
        assertEquals(Set.of("k::a::b::C"), analyze().dependencies().get("k::user::U"));
    }

    /** A group binds each of its paths; {@code D as E} binds only E, so a bare D no longer resolves. */
    @Test
    void useGroupsAndAliases() throws IOException {
        linkingCrate("use crate::a::{b::C, D as E};\npub struct U { c: C, e: E }\npub struct V { d: D }\n");
        Map<String, Set<String>> deps = analyze().dependencies();
        assertEquals(Set.of("k::a::b::C", "k::a::D"), deps.get("k::user::U"));
        assertEquals(Set.of(), deps.get("k::user::V"));
    }

    /** {@code self} in a group binds the module itself under its own name. */
    @Test
    void selfInAUseGroupBindsTheModule() throws IOException {
        linkingCrate("use crate::a::{self};\npub struct U { c: a::b::C }\n");
        assertEquals(Set.of("k::a::b::C"), analyze().dependencies().get("k::user::U"));
    }

    /** Groups nest: {@code crate::{a::{b::{C}, D}}}. */
    @Test
    void nestedUseGroups() throws IOException {
        linkingCrate("use crate::{a::{b::{C}, D}};\npub struct U { c: C, d: D }\n");
        assertEquals(Set.of("k::a::b::C", "k::a::D"), analyze().dependencies().get("k::user::U"));
    }

    /** Glob imports make their module's items visible, but the current module's own items come first. */
    @Test
    void globImportsComeAfterTheCurrentModule() throws IOException {
        linkingCrate("use crate::a::b::*;\nuse crate::a::*;\npub struct U { c: C, d: D }\n"
                + "pub mod own { use crate::a::b::*; pub struct C; pub struct W { c: C } }\n");
        Map<String, Set<String>> deps = analyze().dependencies();
        assertEquals(Set.of("k::a::b::C", "k::a::D"), deps.get("k::user::U"));
        assertEquals(Set.of("k::user::own::C"), deps.get("k::user::own::W"));
    }

    /** {@code use x as _} only brings trait methods into scope and binds no name. */
    @Test
    void underscoreImportsBindNothing() throws IOException {
        linkingCrate("use crate::a::D as _;\npub struct U { d: D }\n");
        assertEquals(Set.of(), analyze().dependencies().get("k::user::U"));
    }

    /** {@code crate::}, {@code self::} and {@code super::} paths are absolute; {@code super} applies once per use. */
    @Test
    void crateSelfAndSuperPaths() throws IOException {
        linkingCrate("""
                pub struct Top;
                pub mod inner {
                    pub struct Y;
                    pub struct X { a: crate::a::D, b: self::Y, c: super::Top, d: super::super::a::b::C }
                }
                """);
        assertEquals(Set.of("k::a::D", "k::user::inner::Y", "k::user::Top", "k::a::b::C"),
                analyze().dependencies().get("k::user::inner::X"));
    }

    /** More {@code super} segments than enclosing modules stop at the crate root. */
    @Test
    void superStopsAtTheCrateRoot() throws IOException {
        linkingCrate("pub mod inner { pub struct X { d: super::super::super::super::a::D } }\n");
        assertEquals(Set.of("k::a::D"), analyze().dependencies().get("k::user::inner::X"));
    }

    /** A path that starts with neither a keyword nor a crate name is relative to the current module (2018 edition). */
    @Test
    void relativePathsReachChildModules() throws IOException {
        linkingCrate("pub mod child { pub struct Kid; }\nuse child::Kid as K;\npub struct U { a: child::Kid, b: K }\n");
        assertEquals(Set.of("k::user::child::Kid"), analyze().dependencies().get("k::user::U"));
    }

    /** Another crate of the project is reached by its name; external crates and std never resolve. */
    @Test
    void otherProjectCratesResolveExternalOnesNever() throws IOException {
        write("Cargo.toml", "[workspace]\nmembers = [\"core\", \"app\"]\n");
        write("core/Cargo.toml", "[package]\nname = \"core-lib\"\n");
        write("core/src/lib.rs", "pub struct Engine;\n");
        write("app/Cargo.toml", "[package]\nname = \"app\"\n");
        write("app/src/lib.rs", "use serde::Serialize;\n"
                + "pub struct App { e: core_lib::Engine, s: Serialize, m: std::collections::HashMap<u8, u8> }\n");
        assertEquals(Set.of("core_lib::Engine"), analyze().dependencies().get("app::App"));
    }

    /**
     * A trait impl for a project type attaches its methods to that type and makes the type depend on the trait; an
     * impl for a foreign type goes to the module building of the module it is written in, which is created for it if
     * the module has no free functions.
     */
    @Test
    void implTargetsAndTheirTraits() throws IOException {
        linkingCrate("""
                use crate::a::{b::C, Tr};
                impl Tr for C { fn local(&self) {} }
                impl Tr for u8 {
                    fn foreign(&self) {}
                }
                """);
        Analysis a = analyze();
        Map<String, TypeInfo> t = byFqn(a);
        assertEquals(List.of("new", "local"), methodNames(t.get("k::a::b::C")));
        assertTrue(a.dependencies().get("k::a::b::C").contains("k::a::Tr"));
        TypeInfo m = t.get("k::user::{fn}");
        assertEquals(List.of("module", "user.rs", "user"), List.of(m.kind(), m.name(), m.packageName()));
        assertEquals(List.of("foreign"), methodNames(m));
        assertEquals(List.of(3, 3, 3), List.of(m.line(), m.endLine(), m.loc()));
        assertEquals(Set.of("k::a::Tr"), a.dependencies().get("k::user::{fn}"));
    }

    /** Impl targets are found through generic arguments, references and lifetimes: {@code G<T>}, {@code &'a R}. */
    @Test
    void implTargetsStripGenericsAndReferences() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", """
                pub trait Tr {}
                pub struct G<T>(T);
                impl<T> G<T> { fn g() {} }
                pub struct R;
                impl<'a> Tr for &'a R { fn r() {} }
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of("g"), methodNames(t.get("k::G")));
        assertEquals(List.of("r"), methodNames(t.get("k::R")));
        assertFalse(t.containsKey("k::{fn}"));
    }

    /**
     * A lower-case path into a module points at its module building; an upper-case unknown item does not; a path into
     * a type (associated function, enum variant) points at the type.
     */
    @Test
    void pathsPointAtModuleBuildingsOrTypes() throws IOException {
        linkingCrate("""
                pub fn f() {
                    crate::a::b::helper();
                    let _m = crate::a::b::Missing;
                    let _c = crate::a::b::C::new();
                    let _v = crate::a::E::V;
                }
                """);
        assertEquals(Set.of("k::a::b::{fn}", "k::a::b::C", "k::a::E"),
                analyze().dependencies().get("k::user::{fn}"));
    }

    /**
     * Capitalised identifiers (unit structs, variants used as values) are references; lower-case ones (variables,
     * calls through an imported function name) are not.
     */
    @Test
    void onlyCapitalisedIdentifiersAreReferences() throws IOException {
        linkingCrate("use crate::a::b::{C, helper as h};\npub fn f() { let _x = C; let y = 1; h(); }\n");
        assertEquals(Set.of("k::a::b::C"), analyze().dependencies().get("k::user::{fn}"));
    }
}
