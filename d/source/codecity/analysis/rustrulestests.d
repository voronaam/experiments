/// Rule-level tests of the Rust analyser (`rustlang.d`): one test per metric, test-detection, crate/module and
/// name-resolution rule, so that a failure points at the rule that changed. The golden suite checks whole cities;
/// these tests pin the individual rules behind them. The Java (`RustRulesTest`) and Rust (`analysis/rust_rules_tests.rs`)
/// editions run the same scenarios with the same expectations.
module codecity.analysis.rustrulestests;

version (unittest):

import codecity.analysis;
import codecity.git : realPath;
import codecity.model;
import std.algorithm : map, sort;
import std.array : array;
import std.file : mkdirRecurse, rmdirRecurse, tempDir, write;
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

private const(TypeDecl)* get(ref Analysis a, string fqn)
{
    foreach (ref t; a.types) if (t.fqn == fqn) return &t;
    assert(0, "missing " ~ fqn);
}

private bool has(ref Analysis a, string fqn)
{
    foreach (ref t; a.types) if (t.fqn == fqn) return true;
    return false;
}

/// The dependencies of a building, sorted (none when it has no entry), for comparison with a sorted expected list.
private string[] deps(ref Analysis a, string fqn)
{
    auto found = fqn in a.dependencies;
    auto d = found ? (*found).dup : null;
    sort(d);
    return d;
}

private string[] sorted(string[] items)
{
    auto d = items.dup;
    sort(d);
    return d;
}

private const(string)[] methodNames(const(TypeDecl)* t)
{
    return t.methods.map!(m => m.name).array;
}

private const(MethodInfo) method(const(TypeDecl)* t, string name)
{
    foreach (m; t.methods) if (m.name == name) return m;
    assert(0, "missing method " ~ name);
}

/// The shared crate "k" of the name-resolution tests: `a` (a directory module with `D`, the trait `Tr` and the enum
/// `E`) and its child `a::b` (with `C` and a free function), plus the module `user`, whose file each test writes.
private void linkingCrate(ref Fixture f, string user)
{
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub mod a;\npub mod user;\n");
    f.put("src/a/mod.rs", "pub mod b;\npub struct D;\npub trait Tr {}\npub enum E { V }\n");
    f.put("src/a/b.rs", "pub struct C;\nimpl C { pub fn new() -> Self { C } }\npub fn helper() {}\n");
    f.put("src/user.rs", user);
}

// ------------------------------------------------------------------ metrics

unittest // attributesAreFieldsVariantsAndUnionFields: NOA counts fields, tuple fields, variants, union fields
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub struct Named { a: u8, pub b: u16, c: Vec<u8> }\npub struct Tuple(u8, pub String);\npub struct Unit;\npub struct Empty {}\npub enum E { A, B(u8, u8), C { x: u8 } }\npub union U { f: u32, g: f32 }\n");
    auto a = f.analyze();
    assert(["k::Named", "k::Tuple", "k::Unit", "k::Empty", "k::E", "k::U"].map!(x => get(a, x).noa).array
        == [3, 2, 0, 0, 3, 2]);
    assert(["k::Named", "k::Unit", "k::E", "k::U"].map!(x => get(a, x).kind).array
        == ["struct", "struct", "enum", "union"]);
}

unittest // traitMethodsAreDeclaredAndDefaultMethods: associated constants and types are not counted
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub trait T {\n    const C: u8;\n    type X;\n    fn declared(&self, x: u8);\n    fn default(&self) -> u8 { if true { 1 } else { 0 } }\n}\n");
    auto a = f.analyze();
    auto t = get(a, "k::T");
    assert(t.kind == "trait");
    assert(methodNames(t) == ["declared", "default"]);
    assert(t.noa == 0);
    assert(t.methods.map!(m => m.complexity).array == [1, 2]);
    assert(method(t, "declared").parameters == 1);
}

unittest // implBlocksMergeInFileOrderAndAddTheirLines: file order, impl LOC added, own line range kept
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub mod a;\npub struct S;\nimpl S {\n    fn one(&self) {}\n}\nimpl S { fn two(&self, x: u8) {} }\n");
    f.put("src/a.rs", "use crate::S;\nimpl S {\n    // a comment\n\n    fn zero() {}\n}\n");
    auto a = f.analyze();
    auto s = get(a, "k::S");
    assert(methodNames(s) == ["zero", "one", "two"]);
    assert(s.line == 2 && s.endLine == 2);
    // 1 (the struct) + 3 (impl in a.rs, comment and blank line left out) + 3 + 1
    assert(s.loc == 8);
}

unittest // moduleBuildingHoldsFreeFunctionsConstantsAndStatics: fns are methods, consts/statics attributes
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub struct S;\nfn signature();\npub fn f(a: u8, b: u8) {}\n// a comment\nconst C: u8 = 1;\nstatic T: u8 = 2;\npub struct After;\nfn g() {}\nmod inner { fn h() {} }\n");
    auto a = f.analyze();
    auto m = get(a, "k::{fn}");
    assert(m.kind == "module" && m.name == "lib.rs" && m.pkg == "");
    assert(methodNames(m) == ["f", "g"]);
    assert(m.noa == 2);
    assert(m.line == 3 && m.endLine == 8 && m.loc == 4);
    assert(method(m, "f").parameters == 2);
    auto inner = get(a, "k::inner::{fn}");
    assert(inner.name == "mod inner" && inner.pkg == "inner" && inner.module_ == "k");
    assert(methodNames(inner) == ["h"]);
}

unittest // complexityCountsEachRustDecisionPoint: if, loops, &&/||, match arms - 1, empty match, ?, closures
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", `fn straight() {}
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
`);
    auto a = f.analyze();
    uint[string] cx;
    foreach (m; get(a, "k::{fn}").methods) cx[m.name] = m.complexity;
    assert(cx == ["straight": 1u, "branches": 3, "loops": 4, "logic": 3, "arms": 3, "empty": 1, "question": 1,
        "nested": 3]);
}

unittest // parametersDoNotCountTheSelfReceiver: in whatever form it is written
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub struct S;\nimpl S {\n    fn a(&self, x: u8) {}\n    fn b(&mut self) {}\n    fn c(mut self, x: u8, y: u8) {}\n    fn d() {}\n}\nfn e(x: u8, (a, b): (u8, u8)) {}\n");
    auto a = f.analyze();
    assert(get(a, "k::S").methods.map!(m => m.parameters).array == [1, 0, 2, 0]);
    assert(method(get(a, "k::{fn}"), "e").parameters == 2);
}

// ------------------------------------------------------------------ test detection

unittest // testsAndBenchesDirectoriesAreTestCode: a crate's tests/ and benches/, but not src/tests/
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub struct L;\n");
    f.put("src/tests/x.rs", "pub struct X;\n");
    f.put("tests/it.rs", "pub struct T;\n");
    f.put("benches/b.rs", "pub struct B;\n");
    auto a = f.analyze();
    assert(!get(a, "k::L").test);
    assert(!get(a, "k::tests::x::X").test);
    assert(get(a, "k::tests::it::T").test);
    assert(get(a, "k::benches::b::B").test);
}

unittest // cfgTestAppliesToTheNextItemOnly: other attributes keep it, a comment ends it, cfg(all(test, …)) is not it
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "#[cfg(test)]\npub struct A;\npub struct B;\n#[cfg( test )]\n#[derive(Debug)]\npub struct C;\n#[cfg(test)]\n// a comment\npub struct D;\n#[cfg(all(test, unix))]\npub struct E;\n#[cfg(test)]\nfn helper() {}\n");
    auto a = f.analyze();
    assert(["k::A", "k::B", "k::C", "k::D", "k::E", "k::{fn}"].map!(x => get(a, x).test).array
        == [true, false, true, false, false, false]);
}

unittest // cfgTestModulesKeepTheirParentsDistrict: but a module nested in one is a child district again
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub mod cart;\n");
    f.put("src/cart.rs", "pub struct Cart;\n#[cfg(test)]\nmod tests {\n    pub struct Fixture;\n    mod deep { pub struct X; }\n}\n");
    auto a = f.analyze();
    assert(get(a, "k::cart::tests::Fixture").test);
    assert(get(a, "k::cart::tests::Fixture").pkg == "cart");
    assert(get(a, "k::cart::tests::deep::X").test);
    assert(get(a, "k::cart::tests::deep::X").pkg == "cart::tests::deep");
}

// ------------------------------------------------------------------ crates and modules

unittest // crateNamesUseUnderscores: the crate is the build module, not the package
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"my-crate\"\n");
    f.put("src/lib.rs", "pub struct A;\n");
    auto a = f.analyze();
    auto t = get(a, "my_crate::A");
    assert(t.module_ == "my_crate" && t.pkg == "");
}

unittest // theNearestManifestDecidesTheCrate: in nested crates
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"outer\"\n");
    f.put("src/lib.rs", "pub struct B;\n");
    f.put("inner/Cargo.toml", "[package]\nname = \"inner\"\n");
    f.put("inner/src/lib.rs", "pub struct A;\n");
    auto a = f.analyze();
    assert(get(a, "outer::B").module_ == "outer");
    assert(get(a, "inner::A").module_ == "inner");
}

unittest // withoutAPackageTheCrateIsNamedAfterTheRoot: a workspace-only manifest names no crate
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("my-ws/Cargo.toml", "[workspace]\nmembers = []\n");
    f.put("my-ws/src/lib.rs", "pub struct A;\n");
    auto a = new SourceAnalyzer().analyze(buildPath(f.root, "my-ws"), true);
    assert(get(a, "my_ws::A").module_ == "my_ws");
}

unittest // modulePathsFollowTheFileLayout: source roots, mod.rs, inline modules, a sub-directory with its own lib.rs
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub struct L;\nmod inline { pub struct I; }\n");
    f.put("src/main.rs", "pub struct M;\n");
    f.put("src/net/mod.rs", "pub struct N;\n");
    f.put("src/net/http.rs", "pub struct H;\n");
    f.put("src/util/lib.rs", "pub struct U;\n");
    f.put("src/util/x.rs", "pub struct X;\n");
    auto a = f.analyze();
    assert(["k::L", "k::M", "k::inline::I", "k::net::N", "k::net::http::H", "k::U", "k::x::X"]
        .map!(x => get(a, x).pkg).array == ["", "", "inline", "net", "net::http", "", "x"]);
}

unittest // syntaxErrorsFlagTheFileButKeepItsItems
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub struct A;\nfn broken( { }\n");
    f.put("src/ok.rs", "pub struct B;\n");
    auto a = f.analyze();
    assert(a.failedFiles == 1);
    assert(a.has("k::A") && a.has("k::ok::B"));
}

// ------------------------------------------------------------------ name resolution

unittest // useBindsTheLastSegment: use a::b::C
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "use crate::a::b::C;\npub struct U { c: C }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::U") == ["k::a::b::C"]);
}

unittest // useGroupsAndAliases: `D as E` binds only E
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "use crate::a::{b::C, D as E};\npub struct U { c: C, e: E }\npub struct V { d: D }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::U") == sorted(["k::a::b::C", "k::a::D"]));
    assert(deps(a, "k::user::V").length == 0);
}

unittest // selfInAUseGroupBindsTheModule
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "use crate::a::{self};\npub struct U { c: a::b::C }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::U") == ["k::a::b::C"]);
}

unittest // nestedUseGroups: crate::{a::{b::{C}, D}}
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "use crate::{a::{b::{C}, D}};\npub struct U { c: C, d: D }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::U") == sorted(["k::a::b::C", "k::a::D"]));
}

unittest // globImportsComeAfterTheCurrentModule
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "use crate::a::b::*;\nuse crate::a::*;\npub struct U { c: C, d: D }\npub mod own { use crate::a::b::*; pub struct C; pub struct W { c: C } }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::U") == sorted(["k::a::b::C", "k::a::D"]));
    assert(deps(a, "k::user::own::W") == ["k::user::own::C"]);
}

unittest // underscoreImportsBindNothing: use x as _
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "use crate::a::D as _;\npub struct U { d: D }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::U").length == 0);
}

unittest // crateSelfAndSuperPaths: absolute paths; super applies once per use
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "pub struct Top;\npub mod inner {\n    pub struct Y;\n    pub struct X { a: crate::a::D, b: self::Y, c: super::Top, d: super::super::a::b::C }\n}\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::inner::X") == sorted(["k::a::D", "k::user::inner::Y", "k::user::Top", "k::a::b::C"]));
}

unittest // superStopsAtTheCrateRoot
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "pub mod inner { pub struct X { d: super::super::super::super::a::D } }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::inner::X") == ["k::a::D"]);
}

unittest // relativePathsReachChildModules: 2018-edition paths relative to the current module
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "pub mod child { pub struct Kid; }\nuse child::Kid as K;\npub struct U { a: child::Kid, b: K }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::U") == ["k::user::child::Kid"]);
}

unittest // otherProjectCratesResolveExternalOnesNever
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[workspace]\nmembers = [\"core\", \"app\"]\n");
    f.put("core/Cargo.toml", "[package]\nname = \"core-lib\"\n");
    f.put("core/src/lib.rs", "pub struct Engine;\n");
    f.put("app/Cargo.toml", "[package]\nname = \"app\"\n");
    f.put("app/src/lib.rs", "use serde::Serialize;\npub struct App { e: core_lib::Engine, s: Serialize, m: std::collections::HashMap<u8, u8> }\n");
    auto a = f.analyze();
    assert(deps(a, "app::App") == ["core_lib::Engine"]);
}

unittest // implTargetsAndTheirTraits: trait impl on a project type; impl on a foreign type → (new) module building
{
    import std.algorithm : canFind;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "use crate::a::{b::C, Tr};\nimpl Tr for C { fn local(&self) {} }\nimpl Tr for u8 {\n    fn foreign(&self) {}\n}\n");
    auto a = f.analyze();
    assert(methodNames(get(a, "k::a::b::C")) == ["new", "local"]);
    assert(deps(a, "k::a::b::C").canFind("k::a::Tr"));
    auto m = get(a, "k::user::{fn}");
    assert(m.kind == "module" && m.name == "user.rs" && m.pkg == "user");
    assert(methodNames(m) == ["foreign"]);
    assert(m.line == 3 && m.endLine == 3 && m.loc == 3);
    assert(deps(a, "k::user::{fn}") == ["k::a::Tr"]);
}

unittest // implTargetsStripGenericsAndReferences: G<T>, &'a R
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub trait Tr {}\npub struct G<T>(T);\nimpl<T> G<T> { fn g() {} }\npub struct R;\nimpl<'a> Tr for &'a R { fn r() {} }\n");
    auto a = f.analyze();
    assert(methodNames(get(a, "k::G")) == ["g"]);
    assert(methodNames(get(a, "k::R")) == ["r"]);
    assert(!a.has("k::{fn}"));
}

unittest // pathsPointAtModuleBuildingsOrTypes: lower-case fn path → module building; Missing → nothing; C::new, E::V → type
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "pub fn f() {\n    crate::a::b::helper();\n    let _m = crate::a::b::Missing;\n    let _c = crate::a::b::C::new();\n    let _v = crate::a::E::V;\n}\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::{fn}") == sorted(["k::a::b::{fn}", "k::a::b::C", "k::a::E"]));
}

unittest // onlyCapitalisedIdentifiersAreReferences: unit structs and variants are, variables and calls are not
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    linkingCrate(f, "use crate::a::b::{C, helper as h};\npub fn f() { let _x = C; let y = 1; h(); }\n");
    auto a = f.analyze();
    assert(deps(a, "k::user::{fn}") == ["k::a::b::C"]);
}
