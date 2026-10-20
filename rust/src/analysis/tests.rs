//! Analyser tests, mirroring the Java version's suite.

use super::SourceAnalyzer;
use crate::model::{Analysis, Language, TypeInfo};
use std::collections::HashMap;
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

fn deps<'a>(a: &'a Analysis, fqn: &str) -> &'a Vec<String> {
    &a.dependencies[fqn]
}

#[test]
fn java_classic_metrics_and_resolution() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "src/main/java/shop/Cart.java", r#"
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
"#);
    write(r, "src/main/java/shop/Item.java", "package shop;\npublic record Item(int price, int qty) {}\n");
    write(r, "src/main/java/a/A.java", "package a;\nimport b.B;\npublic class A { B b; java.util.Map<String, C> m; void f() { Util.go(); } }\n");
    write(r, "src/main/java/a/C.java", "package a; class C {}");
    write(r, "src/main/java/a/Util.java", "package a; class Util { static void go() {} }");
    write(r, "src/main/java/b/B.java", "package b; public class B {}");
    write(r, "core/src/test/java/a/ATest.java", "package a; class ATest {}");
    let a = analyze(r);
    let t = by_fqn(&a);
    let cart = t["shop.Cart"];
    assert_eq!((cart.kind, cart.noa, cart.nom(), cart.complexity()), ("class", 3, 2, 6));
    assert!(!cart.test);
    assert_eq!((t["shop.Item"].kind, t["shop.Item"].noa), ("record", 2));
    assert_eq!((t["shop.Cart.Status"].kind, t["shop.Cart.Status"].noa), ("enum", 2));
    assert_eq!(deps(&a, "a.A"), &vec!["a.C".to_string(), "a.Util".to_string(), "b.B".to_string()]);
    assert!(t["a.ATest"].test);
    assert_eq!(t["a.ATest"].module, "core");
}

#[test]
fn typescript_buildings_and_imports() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "package.json", "{}");
    write(r, "src/shop/cart.ts", r#"
import { Item } from './item';

export abstract class Cart {
  private items: Item[] = [];
  constructor(private readonly owner: string) {}
  onChange = (e: Event) => { if (e && this.items.length > 0) { return 1; } return 0; };
  abstract total(): number;
  add(item: Item, qty: number) {
    for (const i of this.items) { if (i === item) { return; } }
    this.items.push(qty > 1 ? item : item);
  }
}

export enum Status { Open, Paid = 2 }
interface Totals { net: number; gross: number; format(): string }
export type Point = { x: number; y: number };

export function discount(c: Cart): number { return c ? 1 : 0; }
export const TAX = 0.2;
"#);
    write(r, "src/shop/item.ts", "export interface Item { sku: string; price: number }\n");
    write(r, "src/components/index.ts", "export default class Button {}\n");
    write(r, "src/lib/db.ts", "export class Db {}\n");
    write(r, "src/App.tsx", "import Button from './components';\nimport { Db } from '@/lib/db';\nexport const App = () => <div><Button /></div>;\nclass Store { db: Db; }\n");
    write(r, "src/App.test.tsx", "import { App } from './App';\ntest('x', () => { App(); });\n");
    write(r, "types/global.d.ts", "interface Window { x: number }\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    let cart = t["src/shop/cart.ts:Cart"];
    assert_eq!(cart.language, Language::TypeScript);
    assert_eq!((cart.kind, cart.package.as_str(), cart.noa, cart.nom()), ("abstract", "src/shop", 2, 4));
    let cx = |n: &str| cart.methods.iter().find(|m| m.name == n).unwrap().complexity;
    assert_eq!((cx("onChange"), cx("add")), (3, 4));
    assert_eq!(t["src/shop/cart.ts:Status"].noa, 2);
    assert_eq!(t["src/shop/cart.ts:Totals"].nom(), 1);
    assert_eq!(t["src/shop/cart.ts:Point"].kind, "type");
    let module = t["src/shop/cart.ts"];
    assert_eq!((module.kind, module.name.as_str(), module.nom(), module.noa), ("module", "cart.ts", 1, 1));
    assert!(deps(&a, "src/shop/cart.ts:Cart").contains(&"src/shop/item.ts:Item".to_string()));
    assert!(deps(&a, "src/App.tsx").contains(&"src/components/index.ts:Button".to_string()));
    assert!(deps(&a, "src/App.tsx:Store").contains(&"src/lib/db.ts:Db".to_string()));
    assert!(t["src/App.test.tsx"].test && !t["src/App.tsx"].test);
    assert_eq!(t["src/App.test.tsx"].methods[0].name, "test('x')");
    assert!(!t.keys().any(|k| k.starts_with("types/")), "declaration files are skipped");
}

#[test]
fn typescript_repeated_declarations_get_unique_ids() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", "interface A { x: number }\ninterface A { y: number }\ninterface A { z: number }\nclass A {}\n");
    let a = analyze(dir.path());
    let mut fqns: Vec<&str> = a.types.iter().map(|t| t.fqn.as_str()).collect();
    fqns.sort();
    assert_eq!(fqns, ["a.ts:A", "a.ts:A#class", "a.ts:A#interface", "a.ts:A#interface2"]);
}

#[test]
fn typescript_comments_are_not_members_or_parameters() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", r#"
enum E {
  // first
  A,
  /* second */
  B,
}
function f(a: number, /* the second */ b: number) {}
class C { constructor(private a: number, // note
                      b: number) {} }
const p = 1, q = 2;
"#);
    let a = analyze(dir.path());
    let t = by_fqn(&a);
    assert_eq!(t["a.ts:E"].noa, 2);
    assert_eq!(t["a.ts"].methods[0].parameters, 2);
    assert_eq!((t["a.ts:C"].noa, t["a.ts:C"].methods[0].parameters), (1, 2));
    // function line + one line for the two-declarator const
    assert_eq!((t["a.ts"].noa, t["a.ts"].loc), (2, 2));
}

#[test]
fn typescript_resolves_like_tsc_and_keeps_packages_external() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "src/a.ts", "export class A {}\n");
    write(r, "src/a/index.ts", "export class A {}\n");
    write(r, "src/b.tsx", "export class B {}\n");
    write(r, "src/b.ts", "export class B {}\n");
    write(r, "src/lodash/fp.ts", "export class Fp {}\n");
    write(r, "src/@scope/pkg.ts", "export class Pkg {}\n");
    write(r, "src/main.ts", "import { A } from './a';\nimport { B } from '@/b';\nimport { Fp } from 'lodash/fp';\nimport { Pkg } from '@scope/pkg';\nclass M { a: A; b: B; f: Fp; p: Pkg; }\n");
    write(r, "src/alias.ts", "import { A } from '~/a';\nclass N { a: A; }\n");
    let a = analyze(r);
    assert_eq!(deps(&a, "src/main.ts:M"), &["src/a.ts:A", "src/b.ts:B"]);
    assert_eq!(deps(&a, "src/alias.ts:N"), &["src/a.ts:A"]);
}

#[test]
fn typescript_notices_package_json_changes_between_analyses() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "web/src/App.ts", "export class App {}\n");
    let analyzer = SourceAnalyzer::new();
    let root = std::fs::canonicalize(dir.path()).unwrap();
    let first = analyzer.analyze(&root, true);
    assert_eq!(by_fqn(&first)["web/src/App.ts:App"].module, "");
    write(dir.path(), "web/package.json", "{}");
    let second = analyzer.analyze(&root, true);
    let app = by_fqn(&second)["web/src/App.ts:App"];
    assert_eq!((app.module.as_str(), app.package.as_str()), ("web", "src"));
}

#[test]
fn rust_types_impls_and_use_paths() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"my-shop\"\nversion = \"0.1.0\"\n");
    write(r, "src/lib.rs", "pub mod cart;\npub mod pricing;\npub fn version() -> &'static str { \"1\" }\n");
    write(r, "src/cart.rs", r#"
use crate::pricing::{Price, Discount as D};
pub struct Cart { items: Vec<Item>, owner: String }
pub struct Item(u32, Price);
pub enum State { Open, Paid { at: u64 }, Cancelled(String) }
impl Cart {
    pub fn new(owner: String) -> Self { Cart { items: vec![], owner } }
    pub fn total(&self, d: &D) -> u32 {
        let mut t = 0;
        for i in &self.items { if i.0 > 0 && d.applies() { t += i.0; } }
        match t { 0 => 0, 1..=9 => t, _ => t - 1 }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn empty() { assert_eq!(Cart::new("a".into()).items.len(), 0); }
}
"#);
    write(r, "src/pricing/mod.rs", "pub struct Price(u32);\npub trait Discount { fn applies(&self) -> bool; fn rate(&self) -> u32 { 0 } }\n");
    write(r, "src/pricing/extra.rs", "use super::Price;\nimpl crate::cart::Cart { pub fn clear(&mut self) { self.items.clear(); } }\nimpl std::fmt::Display for Price { fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { write!(f, \"{}\", self.0) } }\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    let cart = t["my_shop::cart::Cart"];
    assert_eq!((cart.kind, cart.package.as_str(), cart.noa), ("struct", "cart", 2));
    let mut names: Vec<&str> = cart.methods.iter().map(|m| m.name.as_str()).collect();
    names.sort();
    assert_eq!(names, vec!["clear", "new", "total"]);
    assert_eq!(cart.methods.iter().find(|m| m.name == "total").unwrap().complexity, 6);
    assert_eq!(t["my_shop::cart::Item"].noa, 2);
    assert_eq!(t["my_shop::cart::State"].noa, 3);
    assert_eq!(t["my_shop::pricing::Discount"].nom(), 2);
    assert_eq!(t["my_shop::pricing::Price"].methods[0].name, "fmt");
    let tests = t["my_shop::cart::tests::{fn}"];
    assert!(tests.test);
    assert_eq!((tests.package.as_str(), tests.name.as_str()), ("cart", "mod tests"));
    assert_eq!(t["my_shop::{fn}"].name, "lib.rs");
    assert!(deps(&a, "my_shop::cart::Cart").contains(&"my_shop::pricing::Discount".to_string()));
    assert!(deps(&a, "my_shop::cart::Item").contains(&"my_shop::pricing::Price".to_string()));
    assert!(deps(&a, "my_shop::cart::tests::{fn}").contains(&"my_shop::cart::Cart".to_string()));
}

#[test]
fn rust_workspaces_and_crate_roots_outside_src() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[workspace]\nmembers = [\"crates/*\"]\n");
    write(r, "crates/core/Cargo.toml", "[package]\nname = \"core-lib\"\n");
    write(r, "crates/core/src/lib.rs", "pub struct Engine;\n");
    write(r, "crates/app/Cargo.toml", "[package]\nname = \"app\"\n");
    write(r, "crates/app/main.rs", "mod flags;\nuse core_lib::Engine;\nstruct App { e: Engine }\n");
    write(r, "crates/app/flags/defs.rs", "pub struct Verbose;\n");
    let a = analyze(r);
    let t = by_fqn(&a);
    assert_eq!(t["core_lib::Engine"].module, "core_lib");
    assert_eq!(t["app::flags::defs::Verbose"].package, "flags::defs");
    assert!(deps(&a, "app::App").contains(&"core_lib::Engine".to_string()));
}

#[test]
fn reuses_parses_for_unchanged_files() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "src/main/java/a/A.java", "package a; class A {}");
    let analyzer = SourceAnalyzer::new();
    let root = std::fs::canonicalize(dir.path()).unwrap();
    analyzer.analyze(&root, true);
    let before = analyzer.cache_len();
    let second = analyzer.analyze(&root, true);
    assert_eq!((before, analyzer.cache_len(), second.types.len()), (1, 1, 1));
}

#[test]
fn java_resolution_never_sees_the_default_package() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    // Default package: `Line` inside Order is the member type, not the top-level `Line`.
    write(r, "src/main/java/Line.java", "class Line {}");
    write(r, "src/main/java/Util.java", "class Util {}");
    write(r, "src/main/java/Order.java", "class Order { Line l; static class Line {} }");
    // Named package: default-package types are invisible, imports and the same package are used instead.
    write(r, "src/main/java/shop/Cart.java", "package shop;\nimport other.Util;\nclass Cart { Line l; Util u; Order o; other.Helper h; }");
    write(r, "src/main/java/shop/Line.java", "package shop; class Line {}");
    write(r, "src/main/java/other/Util.java", "package other; public class Util {}");
    write(r, "src/main/java/other/Helper.java", "package other; public class Helper {}");
    let a = analyze(r);
    assert_eq!(deps(&a, "Order"), &vec!["Order.Line".to_string()]);
    assert_eq!(deps(&a, "shop.Cart"), &vec!["other.Helper".to_string(), "other.Util".to_string(), "shop.Line".to_string()]);
}

#[test]
fn java_lines_break_at_newlines_only_and_trailing_block_comments_count() {
    let dir = tempfile::tempdir().unwrap();
    // A lone \r is not a line break; the comment opened after `int x;` hides the next line.
    write(dir.path(), "src/main/java/a/A.java", "package a;\rclass A {\n  int x; /* start\n  comment\n  end */\n  int y;\n}\n");
    let a = analyze(dir.path());
    let t = by_fqn(&a)["a.A"];
    assert_eq!((t.line, t.end_line, t.loc), (1, 6, 4));
}

#[test]
fn duplicate_fqn_across_languages_is_dropped_with_its_dependencies() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    // The Java type `App.tsx` and the TypeScript module building `App.tsx` share an fqn; Java (first) wins.
    write(r, "App.java", "package App; class tsx { Other o; }");
    write(r, "Other.java", "package App; class Other {}");
    write(r, "App.tsx", "import { f } from \"./b\";\nexport function g() { return f(); }\n");
    write(r, "b.ts", "export function f() { return 1; }\n");
    let a = analyze(r);
    // b.ts proves that TypeScript module buildings exist, so App.tsx really was produced twice.
    assert!(by_fqn(&a).contains_key("b.ts"));
    assert_eq!(by_fqn(&a)["App.tsx"].language, Language::Java);
    assert_eq!(deps(&a, "App.tsx"), &vec!["App.Other".to_string()]);
}

#[test]
fn cached_parses_see_manifest_changes() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let root = std::fs::canonicalize(r).unwrap();
    let analyzer = SourceAnalyzer::new();
    write(r, "Cargo.toml", "[package]\nname = \"before\"\n");
    write(r, "src/lib.rs", "pub struct A;\n");
    assert!(by_fqn(&analyzer.analyze(&root, true)).contains_key("before::A"));
    // Only the manifest changes (a different size, so the change is seen even within the mtime resolution).
    write(r, "Cargo.toml", "[package]\nname = \"after_rename\"\n");
    assert!(by_fqn(&analyzer.analyze(&root, true)).contains_key("after_rename::A"));
}

// ------------------------------------------------------------------ hostile input
// Inputs that used to take quadratic time or memory, overflow the stack, or read outside the analysed tree. Each
// analysis must finish well within the limit (the old behaviour took minutes or crashed). The Java edition's
// AdversarialInputTest and the D edition's tests.d run the same cases.

/// Generous for these inputs in linear time, far too short for the quadratic behaviour they guard against.
const LIMIT_SECS: u64 = 20;

fn analyze_in_time(root: &Path) -> Analysis {
    let start = std::time::Instant::now();
    let a = analyze(root);
    assert!(start.elapsed().as_secs() < LIMIT_SECS, "took {:?}", start.elapsed());
    a
}

/// The hand-written scan finds what the regular expression it replaced found.
#[test]
fn cargo_package_name_scan() {
    let cases: [(&str, Option<&str>); 15] = [
        ("[package]\nname = \"a-b\"\n", Some("a-b")),
        ("[package]\nauthors = [\"x\"]\nname = \"n\"\n", Some("n")),
        ("[package] # c\n  name\t=  \"x\"", Some("x")),
        ("[package]\nversion = \"1\"\n\n  \nname = \"x\"\n", Some("x")),
        ("[package]\r\nname = \"x\"\r\n", Some("x")),
        ("[package]\nname =\n \"x\"\n", Some("x")),
        ("[package]\nname = \"\"\nname = \"y\"\n", Some("y")),
        ("[package]\n[dependencies]\n[package]\nname = \"second\"\n", Some("second")),
        ("[workspace]\nname = \"w\"\n", None),
        ("[package]\n[lib]\nname = \"l\"\n", None),
        ("  [package]\nname = \"x\"\n", None),
        ("[package]", None),
        ("[package]\nname = \"x", None),
        ("[package]\n\x0cname = \"x\"\n", None),
        ("[package]\nversion = 1", None),
    ];
    for (toml, expected) in cases {
        assert_eq!(super::rust_lang::package_name(toml), expected, "{toml:?}");
    }
}

/// The Java edition's regular expression recursed once per line it skipped: a long [package] table overflowed the
/// stack.
#[test]
fn long_cargo_package_table() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", &format!("[package]\n{}name = \"deep\"\n", "a = 1\n".repeat(100_000)));
    write(r, "src/lib.rs", "pub struct A;\n");
    assert!(by_fqn(&analyze_in_time(r)).contains_key("deep::A"));
}

/// Manifests are read only as regular files within the size cap; a symbolic link could point anywhere.
#[test]
fn symlinked_and_oversized_manifests_are_ignored() {
    let dir = tempfile::tempdir().unwrap();
    let outside = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(outside.path(), "[package]\nname = \"leak\"\n").unwrap();
    let r = dir.path();
    write(r, "a/src/lib.rs", "pub struct A;\n");
    std::os::unix::fs::symlink(outside.path(), r.join("a/Cargo.toml")).unwrap();
    let pad = "#".repeat(super::rust_lang::MAX_MANIFEST_BYTES as usize);
    write(r, "b/Cargo.toml", &format!("[package]\nname = \"big\"\n{pad}"));
    write(r, "b/src/lib.rs", "pub struct B;\n");
    write(r, "web/src/c.ts", "export class C {}\n");
    std::os::unix::fs::symlink(outside.path(), r.join("web/package.json")).unwrap();
    let a = analyze_in_time(r);
    assert!(a.types.iter().all(|t| !t.fqn.starts_with("leak::") && !t.fqn.starts_with("big::")));
    assert_eq!(by_fqn(&a)["web/src/c.ts:C"].module, "", "a linked package.json is no project");
}

/// All methods on one line: lines of code used to be judged once per method, over the whole line each time.
#[test]
fn minified_single_line() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let methods: String = (0..50_000).map(|i| format!(" void m{i}() {{ int x = 1; }}")).collect();
    write(r, "C.java", &format!("class C {{{methods} }}"));
    let a = analyze_in_time(r);
    let c = by_fqn(&a)["C"];
    assert_eq!((c.methods.len(), c.loc), (50_000, 1));
}

/// `a < a < a …`: every `<` was tried as type arguments down to the depth cap, re-lexing each time.
#[test]
fn typescript_comparison_chain() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "a.ts", &format!("export const x = {}b;\nexport class After {{}}\n", "a < ".repeat(100_000)));
    assert!(by_fqn(&analyze_in_time(r)).contains_key("a.ts:After"));
}

/// `A.A.A…`: every prefix of a qualified name was a reference, quadratic in its length.
#[test]
fn long_qualified_name() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "C.java", &format!("class C {{ A{} x; }}", ".A".repeat(200_000)));
    let a = analyze_in_time(r);
    assert!(by_fqn(&a)["C"].references.len() <= super::java::MAX_QUALIFIED_PREFIXES + 1);
}

/// Every reference was checked against every import.
#[test]
fn many_imports() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let imports: String = (0..20_000).map(|i| format!("import a{i}.B;\nimport w{i}.*;\n")).collect();
    let fields: String = (0..20_000).map(|i| format!(" B{i} x{i};")).collect();
    write(r, "p/C.java", &format!("package p;\n{imports}class C {{{fields} }}"));
    let ts_imports: String = (0..20_000).map(|i| format!("import {{ B{i} }} from \"./b{i}\";\n")).collect();
    let ts_classes: String = (0..20_000).map(|i| format!("export class C{i} {{ x: B{i}; }}\n")).collect();
    write(r, "a.ts", &format!("{ts_imports}{ts_classes}"));
    assert!(analyze_in_time(r).types.len() >= 20_001);
}

/// Only the first MAX_IMPORT_CANDIDATES on-demand imports of a Java file are used.
#[test]
fn wildcard_imports_are_capped() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "q/D.java", "package q; public class D {}");
    let first = "import w.*;\n".repeat(super::java::MAX_IMPORT_CANDIDATES - 1);
    write(r, "p/In.java", &format!("package p;\n{first}import q.*;\nclass In {{ D d; }}"));
    write(r, "p/Out.java", &format!("package p;\n{first}import w.*;\nimport q.*;\nclass Out {{ D d; }}"));
    let a = analyze_in_time(r);
    assert!(deps(&a, "p.In").contains(&"q.D".to_string()));
    assert!(!deps(&a, "p.Out").contains(&"q.D".to_string()));
}

/// The same for Rust glob imports, with MAX_GLOB_IMPORTS per module.
#[test]
fn glob_imports_are_capped() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    let first = "use w::*;\n".repeat(super::rust_lang::MAX_GLOB_IMPORTS - 1);
    write(r, "src/lib.rs", &format!(
        "pub mod q {{ pub struct D; }}\npub mod a {{\n{first}use crate::q::*;\npub struct In {{ d: D }}\n}}\n\
         pub mod b {{\n{first}use w::*;\nuse crate::q::*;\npub struct Out {{ d: D }}\n}}\n"));
    let globs: String = (0..20_000).map(|i| format!("use g{i}::*;\n")).collect();
    let fields: String = (0..20_000).map(|i| format!(" x{i}: B{i},")).collect();
    write(r, "src/many.rs", &format!("{globs}pub struct S {{{fields} }}\n"));
    let a = analyze_in_time(r);
    assert!(deps(&a, "k::a::In").contains(&"k::q::D".to_string()));
    assert!(!deps(&a, "k::b::Out").contains(&"k::q::D".to_string()));
}

/// A long package, outer type, namespace or module name used to be copied into the fqn of every building inside it.
/// Such buildings are skipped now (MAX_FQN_BYTES), and their files count as failed.
#[test]
fn over_long_names_are_skipped() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let long_name = "N".repeat(super::text::MAX_FQN_BYTES + 1);
    let classes: String = (0..2_000).map(|i| format!("class C{i} {{}}\n")).collect();
    write(r, "p/A.java", &format!("package {long_name};\n{classes}"));
    write(r, "p/B.java", &format!("class {long_name} {{\n{classes}}}\nclass Kept {{}}\n"));
    write(r, "a.ts", &format!("namespace {long_name} {{\n{}}}\nexport class Kept {{}}\n", classes.replace("class", "export class")));
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    let structs = classes.replace("class", "pub struct").replace(" {}", ";");
    write(r, "src/lib.rs", &format!("mod {} {{\n{structs}}}\npub struct Kept;\n", long_name.to_lowercase()));
    let a = analyze_in_time(r);
    let mut fqns: Vec<&str> = a.types.iter().map(|t| t.fqn.as_str()).collect();
    fqns.sort();
    assert_eq!(fqns, ["Kept", "a.ts:Kept", "k::Kept"]);
    assert_eq!(a.failed_files, 4);
}

/// Member types nested deeper than MAX_TYPE_NESTING are not buildings.
#[test]
fn deep_member_types_are_skipped() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let depth = super::java::MAX_TYPE_NESTING + 4;
    write(r, "C.java", &format!("{}{}", "class C0 {".repeat(depth), "}".repeat(depth)));
    let a = analyze_in_time(r);
    assert_eq!((a.types.len(), a.failed_files), (super::java::MAX_TYPE_NESTING, 1));
}

/// Repeated declarations of one name get numbered ids; finding the next number used to rescan from the first.
#[test]
fn repeated_typescript_declarations() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "a.ts", &"interface A {}\n".repeat(30_000));
    assert!(by_fqn(&analyze_in_time(r)).contains_key("a.ts:A#interface29999"));
}
