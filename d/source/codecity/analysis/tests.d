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

unittest // TypeScript: repeated declarations get unique ids
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "interface A { x: number }\ninterface A { y: number }\ninterface A { z: number }\nclass A {}\n");
    auto a = f.analyze();
    string[] fqns;
    foreach (t; a.types) fqns ~= t.fqn;
    fqns.sort();
    assert(fqns == ["a.ts:A", "a.ts:A#class", "a.ts:A#interface", "a.ts:A#interface2"]);
}

unittest // TypeScript: comments are not members or parameters; a statement's lines count once
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", `
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
`);
    auto a = f.analyze();
    assert(get(a, "a.ts:E").noa == 2);
    assert(get(a, "a.ts").methods[0].parameters == 2);
    assert(get(a, "a.ts:C").noa == 1 && get(a, "a.ts:C").methods[0].parameters == 2);
    // function line + one line for the two-declarator const
    assert(get(a, "a.ts").noa == 2 && get(a, "a.ts").loc == 2);
}

unittest // TypeScript: resolution order like tsc; scoped and deep package imports stay external
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("src/a.ts", "export class A {}\n");
    f.put("src/a/index.ts", "export class A {}\n");
    f.put("src/b.tsx", "export class B {}\n");
    f.put("src/b.ts", "export class B {}\n");
    f.put("src/lodash/fp.ts", "export class Fp {}\n");
    f.put("src/@scope/pkg.ts", "export class Pkg {}\n");
    f.put("src/main.ts", "import { A } from './a';\nimport { B } from '@/b';\nimport { Fp } from 'lodash/fp';\nimport { Pkg } from '@scope/pkg';\nclass M { a: A; b: B; f: Fp; p: Pkg; }\n");
    f.put("src/alias.ts", "import { A } from '~/a';\nclass N { a: A; }\n");
    auto a = f.analyze();
    assert(a.dependencies["src/main.ts:M"] == ["src/a.ts:A", "src/b.ts:B"]);
    assert(a.dependencies["src/alias.ts:N"] == ["src/a.ts:A"]);
}

unittest // TypeScript: a package.json added between analyses is noticed
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("web/src/App.ts", "export class App {}\n");
    auto analyzer = new SourceAnalyzer;
    auto first = analyzer.analyze(f.root, true);
    assert(get(first, "web/src/App.ts:App").module_ == "");
    f.put("web/package.json", "{}");
    auto second = analyzer.analyze(f.root, true);
    assert(get(second, "web/src/App.ts:App").module_ == "web" && get(second, "web/src/App.ts:App").pkg == "src");
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

unittest // Java: default-package types are invisible from named packages; member types come first
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("src/main/java/Line.java", "class Line {}");
    f.put("src/main/java/Util.java", "class Util {}");
    f.put("src/main/java/Order.java", "class Order { Line l; static class Line {} }");
    f.put("src/main/java/shop/Cart.java", "package shop;\nimport other.Util;\nclass Cart { Line l; Util u; Order o; other.Helper h; }");
    f.put("src/main/java/shop/Line.java", "package shop; class Line {}");
    f.put("src/main/java/other/Util.java", "package other; public class Util {}");
    f.put("src/main/java/other/Helper.java", "package other; public class Helper {}");
    auto a = f.analyze();
    assert(a.dependencies["Order"] == ["Order.Line"]);
    assert(a.dependencies["shop.Cart"] == ["other.Helper", "other.Util", "shop.Line"]);
}

unittest // Java: lines break at \n only (no lone \r breaks); trailing block comments are tracked
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("src/main/java/a/A.java", "package a;\rclass A {\n  int x; /* start\n  comment\n  end */\n  int y;\n}\n");
    auto a = f.analyze();
    auto t = get(a, "a.A");
    assert(t.line == 1 && t.endLine == 6 && t.loc == 4);
}

unittest // a duplicate fqn from a later language is dropped together with its dependencies
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    // The Java type `App.tsx` and the TypeScript module building `App.tsx` share an fqn; Java (first) wins.
    f.put("App.java", "package App; class tsx { Other o; }");
    f.put("Other.java", "package App; class Other {}");
    f.put("App.tsx", "import { f } from \"./b\";\nexport function g() { return f(); }\n");
    f.put("b.ts", "export function f() { return 1; }\n");
    auto a = f.analyze();
    get(a, "b.ts"); // TypeScript module buildings exist, so App.tsx really was produced twice
    assert(get(a, "App.tsx").language == Language.java);
    assert(a.dependencies["App.tsx"] == ["App.Other"]);
}

unittest // cached parses see manifest changes: same .rs file, renamed crate
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    auto analyzer = new SourceAnalyzer;
    f.put("Cargo.toml", "[package]\nname = \"before\"\n");
    f.put("src/lib.rs", "pub struct A;\n");
    auto first = analyzer.analyze(f.root, true);
    get(first, "before::A");
    // Only the manifest changes (a different size, so the change is seen even within the mtime resolution).
    f.put("Cargo.toml", "[package]\nname = \"after_rename\"\n");
    auto second = analyzer.analyze(f.root, true);
    get(second, "after_rename::A");
}

// ------------------------------------------------------------------ hostile input
// Inputs that used to take quadratic time or memory, overflow the stack, or read outside the analysed tree. Each
// analysis must finish well within the limit (the old behaviour took minutes or crashed). The Java edition's
// AdversarialInputTest and the Rust edition's tests.rs run the same cases.

/// Analyses the fixture and checks that it took less than a limit that is generous for these inputs in linear time,
/// but far too short for the quadratic behaviour they guard against. (Unittests run unoptimised, hence the margin.)
private Analysis analyzeInTime(ref Fixture f)
{
    import std.datetime.stopwatch : AutoStart, StopWatch;
    auto sw = StopWatch(AutoStart.yes);
    auto a = f.analyze();
    assert(sw.peek.total!"seconds" < 60);
    return a;
}

private bool has(ref Analysis a, string fqn)
{
    foreach (ref t; a.types) if (t.fqn == fqn) return true;
    return false;
}

unittest // the hand-written Cargo.toml scan finds what the regular expression it replaced found
{
    import codecity.analysis.rustlang : packageName;
    // "" stands for "no name", as packageName returns.
    immutable string[2][] cases = [
        ["[package]\nname = \"a-b\"\n", "a-b"],
        ["[package]\nauthors = [\"x\"]\nname = \"n\"\n", "n"],
        ["[package] # c\n  name\t=  \"x\"", "x"],
        ["[package]\nversion = \"1\"\n\n  \nname = \"x\"\n", "x"],
        ["[package]\r\nname = \"x\"\r\n", "x"],
        ["[package]\nname =\n \"x\"\n", "x"],
        ["[package]\nname = \"\"\nname = \"y\"\n", "y"],
        ["[package]\n[dependencies]\n[package]\nname = \"second\"\n", "second"],
        ["[workspace]\nname = \"w\"\n", ""],
        ["[package]\n[lib]\nname = \"l\"\n", ""],
        ["  [package]\nname = \"x\"\n", ""],
        ["[package]", ""],
        ["[package]\nname = \"x", ""],
        ["[package]\n\x0cname = \"x\"\n", ""],
        ["[package]\nversion = 1", ""],
    ];
    foreach (c; cases) assert(packageName(c[0]) == c[1], c[0]);
}

unittest // a long [package] table (the Java edition's regular expression overflowed the stack on it)
{
    import std.array : replicate;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\n" ~ "a = 1\n".replicate(100_000) ~ "name = \"deep\"\n");
    f.put("src/lib.rs", "pub struct A;\n");
    auto a = analyzeInTime(f);
    assert(a.has("deep::A"));
}

unittest // manifests are read only as regular files within the size cap; a symbolic link could point anywhere
{
    import codecity.analysis.rustlang : maxManifestBytes;
    import std.array : replicate;
    import std.file : remove, symlink;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    auto outside = Fixture.create();
    scope (exit) outside.cleanup();
    outside.put("Cargo.toml", "[package]\nname = \"leak\"\n");
    f.put("a/src/lib.rs", "pub struct A;\n");
    symlink(buildPath(outside.root, "Cargo.toml"), buildPath(f.root, "a/Cargo.toml"));
    f.put("b/Cargo.toml", "[package]\nname = \"big\"\n" ~ "#".replicate(maxManifestBytes));
    f.put("b/src/lib.rs", "pub struct B;\n");
    f.put("web/src/c.ts", "export class C {}\n");
    symlink(buildPath(outside.root, "Cargo.toml"), buildPath(f.root, "web/package.json"));
    auto a = analyzeInTime(f);
    foreach (ref t; a.types)
    {
        import std.algorithm : startsWith;
        assert(!t.fqn.startsWith("leak::") && !t.fqn.startsWith("big::"));
    }
    assert(get(a, "web/src/c.ts:C").module_ == "", "a linked package.json is no project");
}

unittest // all methods on one line: lines of code used to be judged once per method, over the whole line
{
    import std.format : format;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    string code = "class C {";
    foreach (i; 0 .. 50_000) code ~= format(" void m%s() { int x = 1; }", i);
    f.put("C.java", code ~ " }");
    auto a = analyzeInTime(f);
    auto c = get(a, "C");
    assert(c.methods.length == 50_000 && c.loc == 1);
}

unittest // a < a < a …: every `<` was tried as type arguments down to the depth cap, re-lexing each time
{
    import std.array : replicate;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "export const x = " ~ "a < ".replicate(100_000) ~ "b;\nexport class After {}\n");
    auto a = analyzeInTime(f);
    assert(a.has("a.ts:After"));
}

unittest // A.A.A…: every prefix of a qualified name was a reference, quadratic in its length
{
    import codecity.analysis.java : maxQualifiedPrefixes;
    import std.array : replicate;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("C.java", "class C { A" ~ ".A".replicate(200_000) ~ " x; }");
    auto a = analyzeInTime(f);
    assert(get(a, "C").references.length <= maxQualifiedPrefixes + 1);
}

unittest // every reference was checked against every import
{
    import std.format : format;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    string java = "package p;\n", fields, ts;
    foreach (i; 0 .. 20_000)
    {
        java ~= format("import a%s.B;\nimport w%s.*;\n", i, i);
        fields ~= format(" B%s x%s;", i, i);
        ts ~= format("import { B%s } from \"./b%s\";\n", i, i);
    }
    foreach (i; 0 .. 20_000) ts ~= format("export class C%s { x: B%s; }\n", i, i);
    f.put("p/C.java", java ~ "class C {" ~ fields ~ " }");
    f.put("a.ts", ts);
    auto a = analyzeInTime(f);
    assert(a.types.length >= 20_001);
}

unittest // only the first maxImportCandidates on-demand imports of a Java file are used
{
    import codecity.analysis.java : maxImportCandidates;
    import std.array : replicate;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("q/D.java", "package q; public class D {}");
    const first = "import w.*;\n".replicate(maxImportCandidates - 1);
    f.put("p/In.java", "package p;\n" ~ first ~ "import q.*;\nclass In { D d; }");
    f.put("p/Out.java", "package p;\n" ~ first ~ "import w.*;\nimport q.*;\nclass Out { D d; }");
    auto a = analyzeInTime(f);
    assert(a.dependencies["p.In"].canFind("q.D"));
    assert(!a.dependencies["p.Out"].canFind("q.D"));
}

unittest // the same for Rust glob imports, with maxGlobImports per module
{
    import codecity.analysis.rustlang : maxGlobImports;
    import std.array : replicate;
    import std.format : format;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    const first = "use w::*;\n".replicate(maxGlobImports - 1);
    f.put("src/lib.rs", "pub mod q { pub struct D; }\npub mod a {\n" ~ first ~ "use crate::q::*;\npub struct In { d: D }\n}\n"
        ~ "pub mod b {\n" ~ first ~ "use w::*;\nuse crate::q::*;\npub struct Out { d: D }\n}\n");
    string many, fields;
    foreach (i; 0 .. 20_000)
    {
        many ~= format("use g%s::*;\n", i);
        fields ~= format(" x%s: B%s,", i, i);
    }
    f.put("src/many.rs", many ~ "pub struct S {" ~ fields ~ " }\n");
    auto a = analyzeInTime(f);
    assert(a.dependencies["k::a::In"].canFind("k::q::D"));
    assert(!a.dependencies["k::b::Out"].canFind("k::q::D"));
}

unittest // over-long package, outer type, namespace and module names: the buildings inside are skipped
{
    import codecity.analysis.text : maxFqnBytes;
    import std.array : replace, replicate;
    import std.format : format;
    import std.uni : toLower;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    const longName = "N".replicate(maxFqnBytes + 1);
    string classes;
    foreach (i; 0 .. 2_000) classes ~= format("class C%s {}\n", i);
    f.put("p/A.java", "package " ~ longName ~ ";\n" ~ classes);
    f.put("p/B.java", "class " ~ longName ~ " {\n" ~ classes ~ "}\nclass Kept {}\n");
    f.put("a.ts", "namespace " ~ longName ~ " {\n" ~ classes.replace("class", "export class") ~ "}\nexport class Kept {}\n");
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "mod " ~ longName.toLower ~ " {\n" ~ classes.replace("class", "pub struct").replace(" {}", ";")
        ~ "}\npub struct Kept;\n");
    auto a = analyzeInTime(f);
    string[] fqns;
    foreach (ref t; a.types) fqns ~= t.fqn;
    fqns.sort();
    assert(fqns == ["Kept", "a.ts:Kept", "k::Kept"]);
    assert(a.failedFiles == 4);
}

unittest // member types nested deeper than maxTypeNesting are not buildings
{
    import codecity.analysis.java : maxTypeNesting;
    import std.array : replicate;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("C.java", "class C0 {".replicate(maxTypeNesting + 4) ~ "}".replicate(maxTypeNesting + 4));
    auto a = analyzeInTime(f);
    assert(a.types.length == maxTypeNesting && a.failedFiles == 1);
}

unittest // repeated declarations of one name get numbered ids without rescanning from the first
{
    import std.array : replicate;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "interface A {}\n".replicate(30_000));
    auto a = analyzeInTime(f);
    assert(a.has("a.ts:A#interface29999"));
}

unittest // module buildings created for impls are resolvable from types and from impls before or after them
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub trait Tr {}\npub mod a { impl crate::Tr for u8 { fn f() { crate::b::g(); } } }\npub mod b { impl crate::Tr for u16 { fn f() { crate::b::g(); } } }\npub struct User { f: b::helper }\npub mod c { impl crate::Tr for u32 { fn f() { crate::b::g(); } } }\n");
    auto a = f.analyze();
    assert(a.dependencies["k::User"].canFind("k::b::{fn}"));
    assert(a.dependencies["k::c::{fn}"].canFind("k::b::{fn}"));
    assert(a.dependencies["k::a::{fn}"].canFind("k::b::{fn}"));
}
