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
