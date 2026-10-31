//! Rule-level tests of the Rust analyser (`rust_lang.rs`): one test per metric, test-detection, crate/module and
//! name-resolution rule, so that a failure points at the rule that changed. The golden suite checks whole cities;
//! these tests pin the individual rules behind them. The Java (`RustRulesTest`) and D (`analysis/rustrulestests.d`)
//! editions run the same scenarios with the same expectations.

use super::SourceAnalyzer;
use crate::model::{Analysis, MethodInfo, TypeInfo};
use std::collections::{BTreeSet, HashMap};
use std::path::Path;

fn write(root: &Path, rel: &str, code: &str) {
    let f = root.join(rel);
    std::fs::create_dir_all(f.parent().unwrap()).unwrap();
    std::fs::write(f, code).unwrap();
}

fn analyze(root: &Path) -> Analysis {
    SourceAnalyzer::new().analyze(&std::fs::canonicalize(root).unwrap(), true)
}

fn by_fqn(a: &Analysis) -> HashMap<&str, &TypeInfo> {
    a.types.iter().map(|t| (t.fqn.as_str(), t)).collect()
}

/// The dependencies of a building as a set, for comparison with an expected list.
fn deps(a: &Analysis, fqn: &str) -> BTreeSet<String> {
    a.dependencies[fqn].iter().cloned().collect()
}

fn set(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|s| s.to_string()).collect()
}

fn method_names(t: &TypeInfo) -> Vec<&str> {
    t.methods.iter().map(|m| m.name.as_str()).collect()
}

fn method<'a>(t: &'a TypeInfo, name: &str) -> &'a MethodInfo {
    t.methods.iter().find(|m| m.name == name).unwrap()
}

/// The shared crate "k" of the name-resolution tests: `a` (a directory module with `D`, the trait `Tr` and the enum
/// `E`) and its child `a::b` (with `C` and a free function), plus the module `user`, whose file each test writes.
fn linking_crate(r: &Path, user: &str) {
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub mod a;\npub mod user;\n");
    write(r, "src/a/mod.rs", "pub mod b;\npub struct D;\npub trait Tr {}\npub enum E { V }\n");
    write(r, "src/a/b.rs", "pub struct C;\nimpl C { pub fn new() -> Self { C } }\npub fn helper() {}\n");
    write(r, "src/user.rs", user);
}

// ------------------------------------------------------------------ metrics

/// NOA is the number of named fields, tuple fields, variants or union fields; a unit or empty struct has none.
#[test]
fn attributes_are_fields_variants_and_union_fields() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub struct Named { a: u8, pub b: u16, c: Vec<u8> }\npub struct Tuple(u8, pub String);\npub struct Unit;\npub struct Empty {}\npub enum E { A, B(u8, u8), C { x: u8 } }\npub union U { f: u32, g: f32 }\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    let noa: Vec<u32> = ["k::Named", "k::Tuple", "k::Unit", "k::Empty", "k::E", "k::U"].iter().map(|f| t[f].noa).collect();
    assert_eq!(noa, [3, 2, 0, 0, 3, 2]);
    let kinds: Vec<&str> = ["k::Named", "k::Unit", "k::E", "k::U"].iter().map(|f| t[f].kind).collect();
    assert_eq!(kinds, ["struct", "struct", "enum", "union"]);
}

/// A trait's methods are its declared and its default methods; associated constants and types are not counted.
#[test]
fn trait_methods_are_declared_and_default_methods() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub trait T {\n    const C: u8;\n    type X;\n    fn declared(&self, x: u8);\n    fn default(&self) -> u8 { if true { 1 } else { 0 } }\n}\n");
    let a = analyze(r);
    let t = by_fqn(&a)["k::T"];
    assert_eq!(t.kind, "trait");
    assert_eq!(method_names(t), ["declared", "default"]);
    assert_eq!(t.noa, 0);
    assert_eq!(t.methods.iter().map(|m| m.complexity).collect::<Vec<_>>(), [1, 2]);
    assert_eq!(method(t, "declared").parameters, 1);
}

/// Impl blocks are merged into their type in file order (paths sorted, then source order), wherever they are; each
/// block's lines of code are added to the type's, while the type keeps its own line range.
#[test]
fn impl_blocks_merge_in_file_order_and_add_their_lines() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub mod a;\npub struct S;\nimpl S {\n    fn one(&self) {}\n}\nimpl S { fn two(&self, x: u8) {} }\n");
    write(r, "src/a.rs", "use crate::S;\nimpl S {\n    // a comment\n\n    fn zero() {}\n}\n");
    let a = analyze(r);
    let s = by_fqn(&a)["k::S"];
    assert_eq!(method_names(s), ["zero", "one", "two"]);
    assert_eq!((s.line, s.end_line), (2, 2));
    // 1 (the struct) + 3 (impl in a.rs, comment and blank line left out) + 3 + 1
    assert_eq!(s.loc, 8);
}

/// Each module's free functions, constants and statics form its module building: functions are its methods,
/// constants and statics its attributes, its line range runs from the first to the last of them and its LOC counts
/// only them. A function signature without a body is left out. The building is named after the file, or `mod name`
/// for an inline module.
#[test]
fn module_building_holds_free_functions_constants_and_statics() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub struct S;\nfn signature();\npub fn f(a: u8, b: u8) {}\n// a comment\nconst C: u8 = 1;\nstatic T: u8 = 2;\npub struct After;\nfn g() {}\nmod inner { fn h() {} }\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    let m = t["k::{fn}"];
    assert_eq!((m.kind, m.name.as_str(), m.package.as_str()), ("module", "lib.rs", ""));
    assert_eq!(method_names(m), ["f", "g"]);
    assert_eq!(m.noa, 2);
    assert_eq!((m.line, m.end_line, m.loc), (3, 8, 4));
    assert_eq!(method(m, "f").parameters, 2);
    let inner = t["k::inner::{fn}"];
    assert_eq!((inner.name.as_str(), inner.package.as_str(), inner.module.as_str()), ("mod inner", "inner", "k"));
    assert_eq!(method_names(inner), ["h"]);
}

/// Cyclomatic complexity: 1 + each if (else if included), while, loop, for, && and ||, plus k - 1 per match with k
/// arms; an empty match cannot take it below 1, `?` is no decision, and closures and nested functions count towards
/// the enclosing function.
#[test]
fn complexity_counts_each_rust_decision_point() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", r#"fn straight() {}
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
"#);
    let a = analyze(r);
    let m = by_fqn(&a)["k::{fn}"];
    let cx: HashMap<&str, u32> = m.methods.iter().map(|x| (x.name.as_str(), x.complexity)).collect();
    let expected: HashMap<&str, u32> = [("straight", 1), ("branches", 3), ("loops", 4), ("logic", 3), ("arms", 3), ("empty", 1), ("question", 1), ("nested", 3)].into_iter().collect();
    assert_eq!(cx, expected);
}

/// Parameters are counted without the `self` receiver, in whatever form it is written.
#[test]
fn parameters_do_not_count_the_self_receiver() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub struct S;\nimpl S {\n    fn a(&self, x: u8) {}\n    fn b(&mut self) {}\n    fn c(mut self, x: u8, y: u8) {}\n    fn d() {}\n}\nfn e(x: u8, (a, b): (u8, u8)) {}\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    assert_eq!(t["k::S"].methods.iter().map(|m| m.parameters).collect::<Vec<_>>(), [1, 0, 2, 0]);
    assert_eq!(method(t["k::{fn}"], "e").parameters, 2);
}

// ------------------------------------------------------------------ test detection

/// Files in a crate's `tests/` and `benches/` directories are test code; a `tests` module directory under `src/` is
/// not.
#[test]
fn tests_and_benches_directories_are_test_code() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub struct L;\n");
    write(r, "src/tests/x.rs", "pub struct X;\n");
    write(r, "tests/it.rs", "pub struct T;\n");
    write(r, "benches/b.rs", "pub struct B;\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    assert!(!t["k::L"].test);
    assert!(!t["k::tests::x::X"].test);
    assert!(t["k::tests::it::T"].test);
    assert!(t["k::benches::b::B"].test);
}

/// `#[cfg(test)]` (with any spacing) marks the next item only; further attributes in between keep it, a comment ends
/// it, and `#[cfg(all(test, …))]` is not recognised. A test-only free function does not make the module building test
/// code.
#[test]
fn cfg_test_applies_to_the_next_item_only() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "#[cfg(test)]\npub struct A;\npub struct B;\n#[cfg( test )]\n#[derive(Debug)]\npub struct C;\n#[cfg(test)]\n// a comment\npub struct D;\n#[cfg(all(test, unix))]\npub struct E;\n#[cfg(test)]\nfn helper() {}\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    let tests: Vec<bool> = ["k::A", "k::B", "k::C", "k::D", "k::E", "k::{fn}"].iter().map(|f| t[f].test).collect();
    assert_eq!(tests, [true, false, true, false, false, false]);
}

/// Everything inside a `#[cfg(test)]` module is test code. The module itself stays in its parent's district, but a
/// module nested in it is an ordinary child district again.
#[test]
fn cfg_test_modules_keep_their_parents_district() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub mod cart;\n");
    write(r, "src/cart.rs", "pub struct Cart;\n#[cfg(test)]\nmod tests {\n    pub struct Fixture;\n    mod deep { pub struct X; }\n}\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    assert!(t["k::cart::tests::Fixture"].test);
    assert_eq!(t["k::cart::tests::Fixture"].package, "cart");
    assert!(t["k::cart::tests::deep::X"].test);
    assert_eq!(t["k::cart::tests::deep::X"].package, "cart::tests::deep");
}

// ------------------------------------------------------------------ crates and modules

/// The crate is named by its Cargo.toml with dashes made underscores; it is the build module, not the package.
#[test]
fn crate_names_use_underscores() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"my-crate\"\n");
    write(r, "src/lib.rs", "pub struct A;\n");
    let a = analyze(r);
    let t = by_fqn(&a)["my_crate::A"];
    assert_eq!((t.module.as_str(), t.package.as_str()), ("my_crate", ""));
}

/// In nested crates, the nearest Cargo.toml with a package name decides the crate.
#[test]
fn the_nearest_manifest_decides_the_crate() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"outer\"\n");
    write(r, "src/lib.rs", "pub struct B;\n");
    write(r, "inner/Cargo.toml", "[package]\nname = \"inner\"\n");
    write(r, "inner/src/lib.rs", "pub struct A;\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    assert_eq!(t["outer::B"].module, "outer");
    assert_eq!(t["inner::A"].module, "inner");
}

/// Without a package manifest (here a workspace-only one) the crate is named after the analysed directory.
#[test]
fn without_a_package_the_crate_is_named_after_the_root() {
    let dir = tempfile::tempdir().unwrap();
    let ws = dir.path().join("my-ws");
    write(&ws, "Cargo.toml", "[workspace]\nmembers = []\n");
    write(&ws, "src/lib.rs", "pub struct A;\n");
    let a = analyze(&ws);
    assert_eq!(by_fqn(&a)["my_ws::A"].module, "my_ws");
}

/// Module paths: relative to the nearest directory holding lib.rs or main.rs; `mod.rs` stands for its directory;
/// lib.rs and main.rs at the source root are both the crate root; inline `mod` blocks add a segment. A sub-directory
/// holding its own lib.rs is a source root of its own, so its files start again at the crate root. The package is the
/// module path without the crate.
#[test]
fn module_paths_follow_the_file_layout() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub struct L;\nmod inline { pub struct I; }\n");
    write(r, "src/main.rs", "pub struct M;\n");
    write(r, "src/net/mod.rs", "pub struct N;\n");
    write(r, "src/net/http.rs", "pub struct H;\n");
    write(r, "src/util/lib.rs", "pub struct U;\n");
    write(r, "src/util/x.rs", "pub struct X;\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    let packages: Vec<&str> = ["k::L", "k::M", "k::inline::I", "k::net::N", "k::net::http::H", "k::U", "k::x::X"].iter().map(|f| t[f].package.as_str()).collect();
    assert_eq!(packages, ["", "", "inline", "net", "net::http", "", "x"]);
}

/// A file with a syntax error counts as failed, but the items around the error are kept.
#[test]
fn syntax_errors_flag_the_file_but_keep_its_items() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub struct A;\nfn broken( { }\n");
    write(r, "src/ok.rs", "pub struct B;\n");
    let a = analyze(r);
    assert_eq!(a.failed_files, 1);
    assert!(by_fqn(&a).contains_key("k::A"));
    assert!(by_fqn(&a).contains_key("k::ok::B"));
}

// ------------------------------------------------------------------ name resolution

/// `use a::b::C` binds C to that path.
#[test]
fn use_binds_the_last_segment() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "use crate::a::b::C;\npub struct U { c: C }\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::U"), set(&["k::a::b::C"]));
}

/// A group binds each of its paths; `D as E` binds only E, so a bare D no longer resolves.
#[test]
fn use_groups_and_aliases() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "use crate::a::{b::C, D as E};\npub struct U { c: C, e: E }\npub struct V { d: D }\n");
    let a = analyze(dir.path());
    assert_eq!(deps(&a, "k::user::U"), set(&["k::a::b::C", "k::a::D"]));
    assert_eq!(deps(&a, "k::user::V"), set(&[]));
}

/// `self` in a group binds the module itself under its own name.
#[test]
fn self_in_a_use_group_binds_the_module() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "use crate::a::{self};\npub struct U { c: a::b::C }\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::U"), set(&["k::a::b::C"]));
}

/// Groups nest: `crate::{a::{b::{C}, D}}`.
#[test]
fn nested_use_groups() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "use crate::{a::{b::{C}, D}};\npub struct U { c: C, d: D }\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::U"), set(&["k::a::b::C", "k::a::D"]));
}

/// Glob imports make their module's items visible, but the current module's own items come first.
#[test]
fn glob_imports_come_after_the_current_module() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "use crate::a::b::*;\nuse crate::a::*;\npub struct U { c: C, d: D }\npub mod own { use crate::a::b::*; pub struct C; pub struct W { c: C } }\n");
    let a = analyze(dir.path());
    assert_eq!(deps(&a, "k::user::U"), set(&["k::a::b::C", "k::a::D"]));
    assert_eq!(deps(&a, "k::user::own::W"), set(&["k::user::own::C"]));
}

/// `use x as _` only brings trait methods into scope and binds no name.
#[test]
fn underscore_imports_bind_nothing() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "use crate::a::D as _;\npub struct U { d: D }\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::U"), set(&[]));
}

/// `crate::`, `self::` and `super::` paths are absolute; `super` applies once per use.
#[test]
fn crate_self_and_super_paths() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "pub struct Top;\npub mod inner {\n    pub struct Y;\n    pub struct X { a: crate::a::D, b: self::Y, c: super::Top, d: super::super::a::b::C }\n}\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::inner::X"), set(&["k::a::D", "k::user::inner::Y", "k::user::Top", "k::a::b::C"]));
}

/// More `super` segments than enclosing modules stop at the crate root.
#[test]
fn super_stops_at_the_crate_root() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "pub mod inner { pub struct X { d: super::super::super::super::a::D } }\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::inner::X"), set(&["k::a::D"]));
}

/// A path that starts with neither a keyword nor a crate name is relative to the current module (2018 edition).
#[test]
fn relative_paths_reach_child_modules() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "pub mod child { pub struct Kid; }\nuse child::Kid as K;\npub struct U { a: child::Kid, b: K }\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::U"), set(&["k::user::child::Kid"]));
}

/// Another crate of the project is reached by its name; external crates and std never resolve.
#[test]
fn other_project_crates_resolve_external_ones_never() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[workspace]\nmembers = [\"core\", \"app\"]\n");
    write(r, "core/Cargo.toml", "[package]\nname = \"core-lib\"\n");
    write(r, "core/src/lib.rs", "pub struct Engine;\n");
    write(r, "app/Cargo.toml", "[package]\nname = \"app\"\n");
    write(r, "app/src/lib.rs", "use serde::Serialize;\npub struct App { e: core_lib::Engine, s: Serialize, m: std::collections::HashMap<u8, u8> }\n");
    assert_eq!(deps(&analyze(r), "app::App"), set(&["core_lib::Engine"]));
}

/// A trait impl for a project type attaches its methods to that type and makes the type depend on the trait; an impl
/// for a foreign type goes to the module building of the module it is written in, which is created for it if the
/// module has no free functions.
#[test]
fn impl_targets_and_their_traits() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "use crate::a::{b::C, Tr};\nimpl Tr for C { fn local(&self) {} }\nimpl Tr for u8 {\n    fn foreign(&self) {}\n}\n");
    let a = analyze(dir.path());
    let t = by_fqn(&a);
    assert_eq!(method_names(t["k::a::b::C"]), ["new", "local"]);
    assert!(deps(&a, "k::a::b::C").contains("k::a::Tr"));
    let m = t["k::user::{fn}"];
    assert_eq!((m.kind, m.name.as_str(), m.package.as_str()), ("module", "user.rs", "user"));
    assert_eq!(method_names(m), ["foreign"]);
    assert_eq!((m.line, m.end_line, m.loc), (3, 3, 3));
    assert_eq!(deps(&a, "k::user::{fn}"), set(&["k::a::Tr"]));
}

/// Impl targets are found through generic arguments, references and lifetimes: `G<T>`, `&'a R`.
#[test]
fn impl_targets_strip_generics_and_references() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", "pub trait Tr {}\npub struct G<T>(T);\nimpl<T> G<T> { fn g() {} }\npub struct R;\nimpl<'a> Tr for &'a R { fn r() {} }\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    assert_eq!(method_names(t["k::G"]), ["g"]);
    assert_eq!(method_names(t["k::R"]), ["r"]);
    assert!(!t.contains_key("k::{fn}"));
}

/// A lower-case path into a module points at its module building; an upper-case unknown item does not; a path into a
/// type (associated function, enum variant) points at the type.
#[test]
fn paths_point_at_module_buildings_or_types() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "pub fn f() {\n    crate::a::b::helper();\n    let _m = crate::a::b::Missing;\n    let _c = crate::a::b::C::new();\n    let _v = crate::a::E::V;\n}\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::{fn}"), set(&["k::a::b::{fn}", "k::a::b::C", "k::a::E"]));
}

/// Capitalised identifiers (unit structs, variants used as values) are references; lower-case ones (variables, calls
/// through an imported function name) are not.
#[test]
fn only_capitalised_identifiers_are_references() {
    let dir = tempfile::tempdir().unwrap();
    linking_crate(dir.path(), "use crate::a::b::{C, helper as h};\npub fn f() { let _x = C; let y = 1; h(); }\n");
    assert_eq!(deps(&analyze(dir.path()), "k::user::{fn}"), set(&["k::a::b::C"]));
}
