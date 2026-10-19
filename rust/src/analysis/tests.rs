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
