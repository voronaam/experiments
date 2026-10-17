/// Analyser tests, mirroring the Java and Rust editions' suites.
module codecity.analysis.tests;

version (unittest):

import codecity.analysis;
import codecity.git : realPath;
import codecity.model;
import std.algorithm : canFind, sort;
import std.array : array;
import std.file : mkdirRecurse, rmdirRecurse, tempDir, write;
import std.path : buildPath, dirName;
import std.uuid : randomUUID;

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

unittest // Java: classic metrics and resolution
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("src/main/java/shop/Cart.java", `
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
`);
    f.put("src/main/java/shop/Item.java", "package shop;\npublic record Item(int price, int qty) {}\n");
    f.put("src/main/java/a/A.java", "package a;\nimport b.B;\npublic class A { B b; java.util.Map<String, C> m; void f() { Util.go(); } }\n");
    f.put("src/main/java/a/C.java", "package a; class C {}");
    f.put("src/main/java/a/Util.java", "package a; class Util { static void go() {} }");
    f.put("src/main/java/b/B.java", "package b; public class B {}");
    f.put("core/src/test/java/a/ATest.java", "package a; class ATest {}");
    auto a = f.analyze();
    auto cart = get(a, "shop.Cart");
    assert(cart.kind == "class" && cart.noa == 3 && cart.nom == 2 && cart.complexity == 6 && !cart.test);
    assert(get(a, "shop.Item").kind == "record" && get(a, "shop.Item").noa == 2);
    assert(get(a, "shop.Cart.Status").kind == "enum" && get(a, "shop.Cart.Status").noa == 2);
    assert(a.dependencies["a.A"] == ["a.C", "a.Util", "b.B"]);
    assert(get(a, "a.ATest").test && get(a, "a.ATest").module_ == "core");
}

unittest // TypeScript: buildings, imports, aliases, default exports, TSX, test files
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("package.json", "{}");
    f.put("src/shop/cart.ts", `
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
`);
    f.put("src/shop/item.ts", "export interface Item { sku: string; price: number }\n");
    f.put("src/components/index.ts", "export default class Button {}\n");
    f.put("src/lib/db.ts", "export class Db {}\n");
    f.put("src/App.tsx", "import Button from './components';\nimport { Db } from '@/lib/db';\nexport const App = () => <div><Button /></div>;\nclass Store { db: Db; }\n");
    f.put("src/App.test.tsx", "import { App } from './App';\ntest('x', () => { App(); });\n");
    f.put("types/global.d.ts", "interface Window { x: number }\n");
    auto a = f.analyze();
    auto cart = get(a, "src/shop/cart.ts:Cart");
    assert(cart.language == Language.typescript && cart.kind == "abstract" && cart.pkg == "src/shop");
    assert(cart.noa == 2 && cart.nom == 4);
    uint cx(string n) { foreach (m; cart.methods) if (m.name == n) return m.complexity; assert(0); }
    assert(cx("onChange") == 3 && cx("add") == 4);
    assert(get(a, "src/shop/cart.ts:Status").noa == 2);
    assert(get(a, "src/shop/cart.ts:Totals").nom == 1);
    assert(get(a, "src/shop/cart.ts:Point").kind == "type");
    auto module_ = get(a, "src/shop/cart.ts");
    assert(module_.kind == "module" && module_.name == "cart.ts" && module_.nom == 1 && module_.noa == 1);
    assert(a.dependencies["src/shop/cart.ts:Cart"].canFind("src/shop/item.ts:Item"));
    assert(a.dependencies["src/App.tsx"].canFind("src/components/index.ts:Button"));
    assert(a.dependencies["src/App.tsx:Store"].canFind("src/lib/db.ts:Db"));
    assert(get(a, "src/App.test.tsx").test && !get(a, "src/App.tsx").test);
    assert(get(a, "src/App.test.tsx").methods[0].name == "test('x')");
    foreach (t; a.types) assert(!t.fqn.canFind("global.d.ts"));
}

unittest // Rust: types, impl blocks across files, use paths, cfg(test) modules
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"my-shop\"\nversion = \"0.1.0\"\n");
    f.put("src/lib.rs", "pub mod cart;\npub mod pricing;\npub fn version() -> &'static str { \"1\" }\n");
    f.put("src/cart.rs", `
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
`);
    f.put("src/pricing/mod.rs", "pub struct Price(u32);\npub trait Discount { fn applies(&self) -> bool; fn rate(&self) -> u32 { 0 } }\n");
    f.put("src/pricing/extra.rs", "use super::Price;\nimpl crate::cart::Cart { pub fn clear(&mut self) { self.items.clear(); } }\nimpl std::fmt::Display for Price { fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { write!(f, \"{}\", self.0) } }\n");
    auto a = f.analyze();
    auto cart = get(a, "my_shop::cart::Cart");
    assert(cart.kind == "struct" && cart.pkg == "cart" && cart.noa == 2);
    string[] names;
    foreach (m; cart.methods) names ~= m.name;
    names.sort();
    assert(names == ["clear", "new", "total"]);
    foreach (m; cart.methods) if (m.name == "total") assert(m.complexity == 6);
    assert(get(a, "my_shop::cart::Item").noa == 2);
    assert(get(a, "my_shop::cart::State").noa == 3);
    assert(get(a, "my_shop::pricing::Discount").nom == 2);
    assert(get(a, "my_shop::pricing::Price").methods[0].name == "fmt");
    auto tests = get(a, "my_shop::cart::tests::{fn}");
    assert(tests.test && tests.pkg == "cart" && tests.name == "mod tests");
    assert(get(a, "my_shop::{fn}").name == "lib.rs");
    assert(a.dependencies["my_shop::cart::Cart"].canFind("my_shop::pricing::Discount"));
    assert(a.dependencies["my_shop::cart::Item"].canFind("my_shop::pricing::Price"));
    assert(a.dependencies["my_shop::cart::tests::{fn}"].canFind("my_shop::cart::Cart"));
}

unittest // Rust workspaces, crate roots outside src/
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[workspace]\nmembers = [\"crates/*\"]\n");
    f.put("crates/core/Cargo.toml", "[package]\nname = \"core-lib\"\n");
    f.put("crates/core/src/lib.rs", "pub struct Engine;\n");
    f.put("crates/app/Cargo.toml", "[package]\nname = \"app\"\n");
    f.put("crates/app/main.rs", "mod flags;\nuse core_lib::Engine;\nstruct App { e: Engine }\n");
    f.put("crates/app/flags/defs.rs", "pub struct Verbose;\n");
    auto a = f.analyze();
    assert(get(a, "core_lib::Engine").module_ == "core_lib");
    assert(get(a, "app::flags::defs::Verbose").pkg == "flags::defs");
    assert(a.dependencies["app::App"].canFind("core_lib::Engine"));
}

unittest // unchanged files are not re-parsed
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("src/main/java/a/A.java", "package a; class A {}");
    auto analyzer = new SourceAnalyzer;
    analyzer.analyze(f.root, true);
    auto second = analyzer.analyze(f.root, true);
    assert(analyzer.cacheLength == 1 && second.types.length == 1);
}
