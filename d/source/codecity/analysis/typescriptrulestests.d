/// One test per rule of the TypeScript analyser: class, interface, enum and alias members, the module building of a
/// file's free code, complexity, namespaces, default exports, test detection, projects, accepted file names, the
/// failed-file flag, and each rule of import resolution. The golden suite checks whole cities; these tests name the
/// rule that broke. The Java edition (`TypeScriptRulesTest`) and the Rust edition (`analysis/typescript_rules_tests.rs`)
/// run the same scenarios with the same expectations.
module codecity.analysis.typescriptrulestests;

version (unittest):

import codecity.analysis;
import codecity.git : realPath;
import codecity.model;
import std.algorithm : all, canFind, map, sort;
import std.array : array, replicate;
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

private const(string)[] methodNames(const(TypeDecl)* t) { return t.methods.map!(m => m.name).array; }

private const(uint)[] complexities(const(TypeDecl)* t) { return t.methods.map!(m => m.complexity).array; }

// ------------------------------------------------------------------ members and metrics

unittest // functionValuedFieldsAreMethodsOtherFieldsAttributes: static members count like instance members
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", src(
        "export class C {",
        "  a = 1;",
        "  static b: number;",
        "  f = () => 1;",
        "  g = function () { return 2; };",
        "  static h() {}",
        "  m() {}",
        "}"));
    auto a = f.analyze();
    const c = get(a, "a.ts:C");
    assert(c.noa == 2);
    assert(methodNames(c) == ["f", "g", "h", "m"]);
}

unittest // parameterPropertiesNeedAnAccessibilityModifier: readonly alone is no attribute
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "class P { constructor(private a: number, public b: string, protected c: number, readonly d: number, e: number) {} }\n");
    auto a = f.analyze();
    const p = get(a, "a.ts:P");
    assert(p.noa == 3 && p.methods[0].parameters == 5);
}

unittest // abstractMethodsAndOverloadSignaturesAreMethods: complexity 1 each; the class is "abstract"
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", src(
        "abstract class A {",
        "  abstract f(): void;",
        "  g(x: number): void;",
        "  g(x: string): void;",
        "  g(x: any) { if (x) {} }",
        "}"));
    auto a = f.analyze();
    const t = get(a, "a.ts:A");
    assert(t.kind == "abstract");
    assert(methodNames(t) == ["f", "g", "g", "g"]);
    assert(complexities(t) == [1, 1, 1, 2]);
}

unittest // interfaceMembersBySignatureKind: properties are attributes; method/call/construct signatures methods; index signatures neither
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "interface I { p: number; q?: string; m(): void; (x: number): string; new (x: number): I; [k: string]: any; }\n");
    auto a = f.analyze();
    const i = get(a, "a.ts:I");
    assert(i.noa == 2 && i.nom == 3);
}

unittest // enumMembersAreAttributes
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "enum E { A, B = 2, C }\n");
    auto a = f.analyze();
    const e = get(a, "a.ts:E");
    assert(e.kind == "enum" && e.noa == 3);
}

unittest // typeAliasMembersOnlyForObjectTypes
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "type O = { x: number; f(): void };\ntype U = 'a' | 'b';\n");
    auto a = f.analyze();
    assert([get(a, "a.ts:O").noa, get(a, "a.ts:O").nom, get(a, "a.ts:U").noa, get(a, "a.ts:U").nom] == [1, 1, 0, 0]);
}

unittest // moduleBuildingCollectsFreeFunctionsAndVariables: functions are methods, variables attributes, lines of free statements only
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", src(
        "import { x } from './x';",
        "export function f(a: number) {",
        "  return a;",
        "}",
        "export const g = (b: number) => b;",
        "const h = function () {};",
        "export const N = 1, M = 2;",
        "const { p, q } = x;",
        "class K {",
        "  k = 1;",
        "}"));
    auto a = f.analyze();
    const m = get(a, "a.ts");
    assert(m.name == "a.ts" && m.kind == "module");
    assert(methodNames(m) == ["f", "g", "h"]);
    assert(m.noa == 3 && m.loc == 7 && m.line == 1 && m.endLine == 12);
}

unittest // noModuleBuildingWithoutFreeCode
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("b.ts", "import { x } from './x';\nexport class B {}\ninterface I {}\n");
    auto a = f.analyze();
    assert(fqns(a) == ["b.ts:B", "b.ts:I"]);
}

unittest // topLevelCallsWithCallbacksAreMethods: callee plus first string argument, cut to 48 code points
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    const label = "x".replicate(60);
    f.put("a.ts", "describe('cart', () => { it('adds', () => { if (y) {} }); });\napp.get(\"/\", handler);\nrun();\n"
        ~ "test('" ~ label ~ "', () => {});\nsuite(`tpl`, function () {});\n");
    auto a = f.analyze();
    const m = get(a, "a.ts");
    assert(methodNames(m) == ["describe('cart')", "test('" ~ "x".replicate(46) ~ "…)", "suite(`tpl`)"]);
    assert(complexities(m) == [2, 1, 1]);
    assert(m.loc == 5);
}

unittest // complexityCountsEachDecisionKind: if, loops, case, catch, ?:, &&, ||, ??, nested functions
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", src(
        "function ifs(a: any) { if (a) {} else if (!a) {} else {} }",
        "function loops(xs: any) { for (;;) {} for (const x of xs) {} for (const k in xs) {} while (xs) {} do {} while (xs); }",
        "function cases(k: number) { switch (k) { case 1: case 2: break; default: } }",
        "function catches() { try {} catch (e) {} finally {} }",
        "function ops(a: any, b: any) { return a ? b : (a && b) || (a ?? b); }",
        "function nested(a: any) { const g = () => { if (a) {} }; function h() { while (a) {} } }"));
    auto a = f.analyze();
    uint[string] cx;
    foreach (m; get(a, "a.ts").methods) cx[m.name] = m.complexity;
    assert(cx == ["ifs": 3u, "loops": 6, "cases": 3, "catches": 2, "ops": 5, "nested": 3]);
}

unittest // namespacesPrefixTheirDeclarations: nested namespaces and ambient modules
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", src(
        "namespace N { export class A {} export namespace M { export interface B {} } }",
        "declare module \"lib\" { export class C {} }"));
    auto a = f.analyze();
    assert(fqns(a) == ["a.ts:N.A", "a.ts:N.M.B", "a.ts:lib.C"]);
    assert(get(a, "a.ts:N.M.B").name == "N.M.B");
}

unittest // defaultExportForms: identifier, anonymous function `default`, anonymous class `default`
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("d1.ts", "class A {}\nexport default A;\n");
    f.put("d2.ts", "export default function () { return 1; }\n");
    f.put("d3.ts", "export default class {}\n");
    f.put("u.ts", src(
        "import A from './d1';",
        "import F from './d2';",
        "import C from './d3';",
        "class U { a: A; c: C; }",
        "class V { f = F; }"));
    auto a = f.analyze();
    assert(methodNames(get(a, "d2.ts")) == ["default"]);
    get(a, "d3.ts:default");
    assert(a.dependencies["u.ts:U"] == ["d1.ts:A", "d3.ts:default"]);
    assert(a.dependencies["u.ts:V"] == ["d2.ts"]);
}

unittest // repeatedDeclarationsAreNumberedPerKind: #kind, then #kind2, …
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "interface A {}\nclass A {}\nclass A {}\ninterface A {}\n");
    auto a = f.analyze();
    assert(fqns(a) == ["a.ts:A", "a.ts:A#class", "a.ts:A#class2", "a.ts:A#interface"]);
}

// ------------------------------------------------------------------ files, tests, projects

unittest // testDetectionBySuffixAndDirectory: .test./.spec. files and test directories; other "test" names are not tests
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    auto tests = ["src/a.test.ts", "src/b.spec.ts", "src/c.spec.tsx", "src/d.test.mts", "src/__tests__/e.ts", "test/f.ts", "tests/g.ts"];
    auto plain = ["src/contest.ts", "src/spec.ts", "src/testing/h.ts", "src/latest.ts"];
    foreach (p; tests ~ plain) f.put(p, "export const v = 1;\n");
    auto a = f.analyze();
    foreach (p; tests) assert(get(a, p).test, p);
    foreach (p; plain) assert(!get(a, p).test, p);
}

unittest // projectIsTheNearestPackageJson: package relative to the project
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("web/package.json", "{}");
    f.put("web/sub/package.json", "{}");
    foreach (p; ["web/src/ui/a.ts", "web/b.ts", "web/sub/c.ts", "root.ts", "lib/x/d.ts"]) f.put(p, "export const v = 1;\n");
    auto a = f.analyze();
    string[2] place(string p) { return [get(a, p).module_, get(a, p).pkg]; }
    assert(place("web/src/ui/a.ts") == ["web", "src/ui"]);
    assert(place("web/b.ts") == ["web", ""]);
    assert(place("web/sub/c.ts") == ["web/sub", ""]);
    assert(place("root.ts") == ["", ""]);
    assert(place("lib/x/d.ts") == ["", "lib/x"]);
}

unittest // acceptedFileExtensions: .ts/.tsx/.mts/.cts, not declaration files or JavaScript
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    foreach (p; ["a.ts", "b.tsx", "c.mts", "d.cts", "e.d.ts", "f.d.mts", "g.d.cts", "h.js"]) f.put(p, "export const v = 1;\n");
    auto a = f.analyze();
    assert(fqns(a) == ["a.ts", "b.tsx", "c.mts", "d.cts"]);
    assert(a.files == 4);
}

unittest // syntaxErrorsFlagTheFileButKeepItsDeclarations
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("good.ts", "export class G {}\n");
    f.put("bad.ts", "export class A {}\nconst = ;\nexport class B {}\n");
    auto a = f.analyze();
    assert(a.failedFiles == 1);
    const all = fqns(a);
    assert(["bad.ts:A", "bad.ts:B", "good.ts:G"].all!(x => all.canFind(x)));
}

// ------------------------------------------------------------------ import resolution

unittest // sameFileDeclarationsBeatImports
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "import { B } from './b';\nclass B {}\nclass A { b: B; }\n");
    f.put("b.ts", "export class B {}\n");
    assert(f.analyze().dependencies["a.ts:A"] == ["a.ts:B"]);
}

unittest // relativeSpecifiers: ./x, ../x, . and ..
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("src/x/a.ts", src(
        "import { B } from './b';",
        "import { C } from '../c';",
        "import { I } from '.';",
        "import { J } from '..';",
        "export class A { b: B; c: C; i: I; j: J; }"));
    f.put("src/x/b.ts", "export class B {}\n");
    f.put("src/c.ts", "export class C {}\n");
    f.put("src/x/index.ts", "export class I {}\n");
    f.put("src/index.ts", "export class J {}\n");
    assert(f.analyze().dependencies["src/x/a.ts:A"] == ["src/c.ts:C", "src/index.ts:J", "src/x/b.ts:B", "src/x/index.ts:I"]);
}

unittest // javaScriptExtensionsInSpecifiersMapToTypeScript: .js, .jsx, .mjs, .cjs
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("u.ts", src(
        "import { B } from './b.js';",
        "import { C } from './c.jsx';",
        "import { D } from './d.mjs';",
        "import { E } from './e.cjs';",
        "class U { b: B; c: C; d: D; e: E; }"));
    f.put("b.ts", "export class B {}\n");
    f.put("c.tsx", "export class C {}\n");
    f.put("d.mts", "export class D {}\n");
    f.put("e.cts", "export class E {}\n");
    assert(f.analyze().dependencies["u.ts:U"] == ["b.ts:B", "c.tsx:C", "d.mts:D", "e.cts:E"]);
}

unittest // sameKeyPrefersTsThenTsxMtsCtsThenIndex
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    // Each directory pN imports "./a" and holds a different subset of the candidates.
    auto sets = [["a.ts", "a.tsx", "a.mts", "a.cts", "a/index.ts"], ["a.tsx", "a.mts", "a/index.ts"],
        ["a.mts", "a.cts"], ["a.cts", "a/index.ts"], ["a/index.tsx", "a/index.ts"]];
    foreach (i, set; sets)
    {
        f.put(format("p%s/u.ts", i), "import { A } from './a';\nclass U { a: A; }\n");
        foreach (p; set) f.put(format("p%s/%s", i, p), "export class A {}\n");
    }
    auto a = f.analyze();
    assert(a.dependencies["p0/u.ts:U"] == ["p0/a.ts:A"]);
    assert(a.dependencies["p1/u.ts:U"] == ["p1/a.tsx:A"]);
    assert(a.dependencies["p2/u.ts:U"] == ["p2/a.mts:A"]);
    assert(a.dependencies["p3/u.ts:U"] == ["p3/a.cts:A"]);
    assert(a.dependencies["p4/u.ts:U"] == ["p4/a/index.ts:A"]);
}

unittest // specifiersAboveTheRootAreExternal
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "import { B } from '../b';\nclass A { b: B; }\n");
    f.put("b.ts", "export class B {}\n");
    assert(f.analyze().dependencies["a.ts:A"].length == 0);
}

unittest // pathAliasesMatchBySuffix: @/, ~/ and #/
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("src/lib/db.ts", "export class Db {}\n");
    f.put("src/ui/u.ts", src(
        "import { Db as D1 } from '@/lib/db';",
        "import { Db as D2 } from '~/lib/db';",
        "import { Db as D3 } from '#/lib/db';",
        "import { Db as D4 } from '@/db';",
        "class U1 { d: D1; }",
        "class U2 { d: D2; }",
        "class U3 { d: D3; }",
        "class U4 { d: D4; }"));
    auto a = f.analyze();
    foreach (u; ["U1", "U2", "U3", "U4"]) assert(a.dependencies["src/ui/u.ts:" ~ u] == ["src/lib/db.ts:Db"], u);
}

unittest // ambiguousAliasesAreExternal
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("src/lib/db.ts", "export class Db {}\n");
    f.put("other/db.ts", "export class Db {}\n");
    f.put("u.ts", "import { Db } from '@/db';\nclass U { d: Db; }\n");
    assert(f.analyze().dependencies["u.ts:U"].length == 0);
}

unittest // packageSpecifiersAreExternal: bare, scoped and deep imports
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("react.ts", "export class R {}\n");
    f.put("@scope/pkg.ts", "export class P {}\n");
    f.put("lodash/fp.ts", "export class F {}\n");
    f.put("u.ts", src(
        "import { R } from 'react';",
        "import { P } from '@scope/pkg';",
        "import { F } from 'lodash/fp';",
        "class U { r: R; p: P; f: F; }"));
    assert(f.analyze().dependencies["u.ts:U"].length == 0);
}

unittest // importKindsResolveToTheirTargets: default, named (aliased), namespace, undeclared name
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("m.ts", "export default class D {}\nexport class N {}\nexport function f() {}\n");
    f.put("u.ts", src(
        "import Def from './m';",
        "import { N as Alias } from './m';",
        "import * as NS from './m';",
        "import { Missing } from './m';",
        "class U1 { d: Def; }",
        "class U2 { n: Alias; }",
        "class U3 { x: NS.N; }",
        "class U4 { y: Missing; }"));
    auto a = f.analyze();
    assert(a.dependencies["u.ts:U1"] == ["m.ts:D"]);
    assert(a.dependencies["u.ts:U2"] == ["m.ts:N"]);
    assert(a.dependencies["u.ts:U3"] == ["m.ts"]);
    assert(a.dependencies["u.ts:U4"] == ["m.ts"]);
}

unittest // edgesToMissingModuleBuildingsAreDropped
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("m.ts", "export class K {}\n");
    f.put("u.ts", "import * as NS from './m';\nclass V { x: NS.K; }\n");
    assert(f.analyze().dependencies["u.ts:V"].length == 0);
}

unittest // reExportsAreNotFollowed: a barrel import points at the barrel's module building
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("lib/index.ts", "export { X } from './x';\nexport const v = 1;\n");
    f.put("lib/x.ts", "export class X {}\n");
    f.put("u.ts", "import { X } from './lib';\nclass U { x: X; }\n");
    assert(f.analyze().dependencies["u.ts:U"] == ["lib/index.ts"]);
}

unittest // lastImportOfALocalNameWins
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a.ts", "export class A {}\n");
    f.put("b.ts", "export class B {}\n");
    f.put("u.ts", "import { A } from './a';\nimport { B as A } from './b';\nclass U { x: A; }\n");
    assert(f.analyze().dependencies["u.ts:U"] == ["b.ts:B"]);
}
