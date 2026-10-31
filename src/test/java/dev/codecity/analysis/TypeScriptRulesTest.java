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
 * One test per rule of the TypeScript analyser: class, interface, enum and alias members, the module building of a
 * file's free code, complexity, namespaces, default exports, test detection, projects, accepted file names, the
 * failed-file flag, and each rule of import resolution. The golden suite checks whole cities; these tests name the
 * rule that broke. The Rust edition ({@code analysis/typescript_rules_tests.rs}) and the D edition
 * ({@code analysis/typescriptrulestests.d}) run the same scenarios with the same expectations.
 */
class TypeScriptRulesTest {

    @TempDir
    Path root;

    private void write(String rel, String code) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, code);
    }

    private Analysis analyze() throws IOException {
        return new SourceAnalyzer().analyze(root, true);
    }

    private static Map<String, TypeInfo> byFqn(Analysis a) {
        return a.types().stream().collect(Collectors.toMap(TypeInfo::fqn, Function.identity()));
    }

    /** The dependencies of {@code fqn}, sorted, as the Rust and D editions store them. */
    private static List<String> deps(Analysis a, String fqn) {
        return a.dependencies().get(fqn).stream().sorted().toList();
    }

    private static List<String> methodNames(TypeInfo t) {
        return t.methods().stream().map(MethodInfo::name).toList();
    }

    // ------------------------------------------------------------------ members and metrics

    /** Plain fields (static ones too) are attributes; fields holding a function are methods, like static methods. */
    @Test
    void functionValuedFieldsAreMethodsOtherFieldsAttributes() throws IOException {
        write("a.ts", """
                export class C {
                  a = 1;
                  static b: number;
                  f = () => 1;
                  g = function () { return 2; };
                  static h() {}
                  m() {}
                }
                """);
        TypeInfo c = byFqn(analyze()).get("a.ts:C");
        assertEquals(2, c.noa());
        assertEquals(List.of("f", "g", "h", "m"), methodNames(c));
    }

    /** Constructor parameters with an accessibility modifier are attributes; {@code readonly} alone is not. */
    @Test
    void parameterPropertiesNeedAnAccessibilityModifier() throws IOException {
        write("a.ts", "class P { constructor(private a: number, public b: string, protected c: number, readonly d: number, e: number) {} }\n");
        TypeInfo p = byFqn(analyze()).get("a.ts:P");
        assertEquals(3, p.noa());
        assertEquals(5, p.methods().getFirst().parameters());
    }

    /** Abstract methods and overload signatures are methods of complexity 1; the class is "abstract". */
    @Test
    void abstractMethodsAndOverloadSignaturesAreMethods() throws IOException {
        write("a.ts", """
                abstract class A {
                  abstract f(): void;
                  g(x: number): void;
                  g(x: string): void;
                  g(x: any) { if (x) {} }
                }
                """);
        TypeInfo a = byFqn(analyze()).get("a.ts:A");
        assertEquals("abstract", a.kind());
        assertEquals(List.of("f", "g", "g", "g"), methodNames(a));
        assertEquals(List.of(1, 1, 1, 2), a.methods().stream().map(MethodInfo::complexity).toList());
    }

    /** Interface properties are attributes; method, call and construct signatures methods; index signatures neither. */
    @Test
    void interfaceMembersBySignatureKind() throws IOException {
        write("a.ts", "interface I { p: number; q?: string; m(): void; (x: number): string; new (x: number): I; [k: string]: any; }\n");
        TypeInfo i = byFqn(analyze()).get("a.ts:I");
        assertEquals(List.of(2, 3), List.of(i.noa(), i.nom()));
    }

    /** Every enum member is an attribute, with or without an initialiser. */
    @Test
    void enumMembersAreAttributes() throws IOException {
        write("a.ts", "enum E { A, B = 2, C }\n");
        TypeInfo e = byFqn(analyze()).get("a.ts:E");
        assertEquals("enum", e.kind());
        assertEquals(3, e.noa());
    }

    /** Only an object-literal type alias has members; a union alias has none. */
    @Test
    void typeAliasMembersOnlyForObjectTypes() throws IOException {
        write("a.ts", "type O = { x: number; f(): void };\ntype U = 'a' | 'b';\n");
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of(1, 1, 0, 0), List.of(t.get("a.ts:O").noa(), t.get("a.ts:O").nom(),
                t.get("a.ts:U").noa(), t.get("a.ts:U").nom()));
    }

    /**
     * The module building, named after the file, holds the free functions (declarations and function-valued
     * constants) as methods and the other top-level variables as attributes (a destructuring pattern is one); its
     * lines are those of the free statements only, and it spans the whole file.
     */
    @Test
    void moduleBuildingCollectsFreeFunctionsAndVariables() throws IOException {
        write("a.ts", """
                import { x } from './x';
                export function f(a: number) {
                  return a;
                }
                export const g = (b: number) => b;
                const h = function () {};
                export const N = 1, M = 2;
                const { p, q } = x;
                class K {
                  k = 1;
                }
                """);
        TypeInfo m = byFqn(analyze()).get("a.ts");
        assertEquals("a.ts", m.name());
        assertEquals("module", m.kind());
        assertEquals(List.of("f", "g", "h"), methodNames(m));
        assertEquals(List.of(3, 7, 1, 12), List.of(m.noa(), m.loc(), m.line(), m.endLine()));
    }

    /** A file without free code (only classes, interfaces and imports) has no module building. */
    @Test
    void noModuleBuildingWithoutFreeCode() throws IOException {
        write("b.ts", "import { x } from './x';\nexport class B {}\ninterface I {}\n");
        assertEquals(Set.of("b.ts:B", "b.ts:I"), byFqn(analyze()).keySet());
    }

    /**
     * A top-level call with a callback argument is a method named after the callee and its first string argument
     * (quotes kept, cut to 48 code points with an ellipsis); its complexity covers the callbacks. Calls without a
     * callback are not methods, but their lines count.
     */
    @Test
    void topLevelCallsWithCallbacksAreMethods() throws IOException {
        String label = "x".repeat(60);
        write("a.ts", "describe('cart', () => { it('adds', () => { if (y) {} }); });\n"
                + "app.get(\"/\", handler);\n"
                + "run();\n"
                + "test('" + label + "', () => {});\n"
                + "suite(`tpl`, function () {});\n");
        TypeInfo m = byFqn(analyze()).get("a.ts");
        assertEquals(List.of("describe('cart')", "test('" + "x".repeat(46) + "…)", "suite(`tpl`)"), methodNames(m));
        assertEquals(List.of(2, 1, 1), m.methods().stream().map(MethodInfo::complexity).toList());
        assertEquals(5, m.loc());
    }

    /**
     * Complexity is 1 plus one per if, for, for-of, for-in, while, do, non-default case, catch, {@code ?:}, {@code &&},
     * {@code ||} and {@code ??}; nested functions count towards the enclosing one.
     */
    @Test
    void complexityCountsEachDecisionKind() throws IOException {
        write("a.ts", """
                function ifs(a: any) { if (a) {} else if (!a) {} else {} }
                function loops(xs: any) { for (;;) {} for (const x of xs) {} for (const k in xs) {} while (xs) {} do {} while (xs); }
                function cases(k: number) { switch (k) { case 1: case 2: break; default: } }
                function catches() { try {} catch (e) {} finally {} }
                function ops(a: any, b: any) { return a ? b : (a && b) || (a ?? b); }
                function nested(a: any) { const g = () => { if (a) {} }; function h() { while (a) {} } }
                """);
        Map<String, Integer> cx = byFqn(analyze()).get("a.ts").methods().stream()
                .collect(Collectors.toMap(MethodInfo::name, MethodInfo::complexity));
        assertEquals(Map.of("ifs", 3, "loops", 6, "cases", 3, "catches", 2, "ops", 5, "nested", 3), cx);
    }

    /** Declarations in namespaces (nested ones too) and ambient modules are named with the namespace path. */
    @Test
    void namespacesPrefixTheirDeclarations() throws IOException {
        write("a.ts", """
                namespace N { export class A {} export namespace M { export interface B {} } }
                declare module "lib" { export class C {} }
                """);
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(Set.of("a.ts:N.A", "a.ts:N.M.B", "a.ts:lib.C"), t.keySet());
        assertEquals("N.M.B", t.get("a.ts:N.M.B").name());
    }

    /**
     * Default exports: an identifier names a declaration of the file; an anonymous function is the module building's
     * function {@code default}; an anonymous class expression is a building named {@code default}.
     */
    @Test
    void defaultExportForms() throws IOException {
        write("d1.ts", "class A {}\nexport default A;\n");
        write("d2.ts", "export default function () { return 1; }\n");
        write("d3.ts", "export default class {}\n");
        write("u.ts", """
                import A from './d1';
                import F from './d2';
                import C from './d3';
                class U { a: A; c: C; }
                class V { f = F; }
                """);
        Analysis a = analyze();
        Map<String, TypeInfo> t = byFqn(a);
        assertEquals(List.of("default"), methodNames(t.get("d2.ts")));
        assertTrue(t.containsKey("d3.ts:default"));
        assertEquals(List.of("d1.ts:A", "d3.ts:default"), deps(a, "u.ts:U"));
        assertEquals(List.of("d2.ts"), deps(a, "u.ts:V"));
    }

    /** Repeated names: the first keeps the plain id, later ones get {@code #kind}, then {@code #kind2}, … per kind. */
    @Test
    void repeatedDeclarationsAreNumberedPerKind() throws IOException {
        write("a.ts", "interface A {}\nclass A {}\nclass A {}\ninterface A {}\n");
        assertEquals(Set.of("a.ts:A", "a.ts:A#class", "a.ts:A#class2", "a.ts:A#interface"), byFqn(analyze()).keySet());
    }

    // ------------------------------------------------------------------ files, tests, projects

    /**
     * Test code: a {@code .test.} or {@code .spec.} file, or a file under a test directory (test, tests, __tests__ and
     * the others shared with Java). Other names containing "test" or "spec" are not tests.
     */
    @Test
    void testDetectionBySuffixAndDirectory() throws IOException {
        List<String> tests = List.of("src/a.test.ts", "src/b.spec.ts", "src/c.spec.tsx", "src/d.test.mts",
                "src/__tests__/e.ts", "test/f.ts", "tests/g.ts");
        List<String> plain = List.of("src/contest.ts", "src/spec.ts", "src/testing/h.ts", "src/latest.ts");
        for (String f : tests) {
            write(f, "export const v = 1;\n");
        }
        for (String f : plain) {
            write(f, "export const v = 1;\n");
        }
        Map<String, TypeInfo> t = byFqn(analyze());
        tests.forEach(f -> assertTrue(t.get(f).test(), f));
        plain.forEach(f -> assertFalse(t.get(f).test(), f));
    }

    /**
     * The project (build module) is the directory of the nearest package.json, "" for the root's; the package is the
     * file's directory relative to it.
     */
    @Test
    void projectIsTheNearestPackageJson() throws IOException {
        write("web/package.json", "{}");
        write("web/sub/package.json", "{}");
        write("web/src/ui/a.ts", "export const v = 1;\n");
        write("web/b.ts", "export const v = 1;\n");
        write("web/sub/c.ts", "export const v = 1;\n");
        write("root.ts", "export const v = 1;\n");
        write("lib/x/d.ts", "export const v = 1;\n");
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals(List.of("web", "src/ui"), List.of(t.get("web/src/ui/a.ts").module(), t.get("web/src/ui/a.ts").packageName()));
        assertEquals(List.of("web", ""), List.of(t.get("web/b.ts").module(), t.get("web/b.ts").packageName()));
        assertEquals(List.of("web/sub", ""), List.of(t.get("web/sub/c.ts").module(), t.get("web/sub/c.ts").packageName()));
        assertEquals(List.of("", ""), List.of(t.get("root.ts").module(), t.get("root.ts").packageName()));
        assertEquals(List.of("", "lib/x"), List.of(t.get("lib/x/d.ts").module(), t.get("lib/x/d.ts").packageName()));
    }

    /** .ts, .tsx, .mts and .cts are analysed; declaration files (.d.ts, .d.mts, .d.cts) and JavaScript are not. */
    @Test
    void acceptedFileExtensions() throws IOException {
        for (String f : List.of("a.ts", "b.tsx", "c.mts", "d.cts", "e.d.ts", "f.d.mts", "g.d.cts", "h.js")) {
            write(f, "export const v = 1;\n");
        }
        Analysis a = analyze();
        assertEquals(Set.of("a.ts", "b.tsx", "c.mts", "d.cts"), byFqn(a).keySet());
        assertEquals(4, a.files());
    }

    /** A file with a syntax error counts as failed and still yields what was recovered around the error. */
    @Test
    void syntaxErrorsFlagTheFileButKeepItsDeclarations() throws IOException {
        write("good.ts", "export class G {}\n");
        write("bad.ts", "export class A {}\nconst = ;\nexport class B {}\n");
        Analysis a = analyze();
        assertEquals(1, a.failedFiles());
        assertTrue(byFqn(a).keySet().containsAll(Set.of("bad.ts:A", "bad.ts:B", "good.ts:G")));
    }

    // ------------------------------------------------------------------ import resolution

    /** A name declared in the file itself comes before an import of the same name. */
    @Test
    void sameFileDeclarationsBeatImports() throws IOException {
        write("a.ts", "import { B } from './b';\nclass B {}\nclass A { b: B; }\n");
        write("b.ts", "export class B {}\n");
        assertEquals(List.of("a.ts:B"), deps(analyze(), "a.ts:A"));
    }

    /** {@code ./x}, {@code ../x}, {@code .} and {@code ..} resolve against the importing file's directory. */
    @Test
    void relativeSpecifiers() throws IOException {
        write("src/x/a.ts", """
                import { B } from './b';
                import { C } from '../c';
                import { I } from '.';
                import { J } from '..';
                export class A { b: B; c: C; i: I; j: J; }
                """);
        write("src/x/b.ts", "export class B {}\n");
        write("src/c.ts", "export class C {}\n");
        write("src/x/index.ts", "export class I {}\n");
        write("src/index.ts", "export class J {}\n");
        assertEquals(List.of("src/c.ts:C", "src/index.ts:J", "src/x/b.ts:B", "src/x/index.ts:I"),
                deps(analyze(), "src/x/a.ts:A"));
    }

    /** ESM-style specifiers name the JavaScript output: {@code .js}, {@code .jsx}, {@code .mjs}, {@code .cjs}. */
    @Test
    void javaScriptExtensionsInSpecifiersMapToTypeScript() throws IOException {
        write("u.ts", """
                import { B } from './b.js';
                import { C } from './c.jsx';
                import { D } from './d.mjs';
                import { E } from './e.cjs';
                class U { b: B; c: C; d: D; e: E; }
                """);
        write("b.ts", "export class B {}\n");
        write("c.tsx", "export class C {}\n");
        write("d.mts", "export class D {}\n");
        write("e.cts", "export class E {}\n");
        assertEquals(List.of("b.ts:B", "c.tsx:C", "d.mts:D", "e.cts:E"), deps(analyze(), "u.ts:U"));
    }

    /** Files sharing a key are picked like TypeScript: a.ts, a.tsx, a.mts, a.cts, then a/index.ts, a/index.tsx. */
    @Test
    void sameKeyPrefersTsThenTsxMtsCtsThenIndex() throws IOException {
        // Each directory pN imports "./a" and holds a different subset of the candidates.
        List<List<String>> sets = List.of(
                List.of("a.ts", "a.tsx", "a.mts", "a.cts", "a/index.ts"),
                List.of("a.tsx", "a.mts", "a/index.ts"),
                List.of("a.mts", "a.cts"),
                List.of("a.cts", "a/index.ts"),
                List.of("a/index.tsx", "a/index.ts"));
        for (int i = 0; i < sets.size(); i++) {
            write("p" + i + "/u.ts", "import { A } from './a';\nclass U { a: A; }\n");
            for (String f : sets.get(i)) {
                write("p" + i + "/" + f, "export class A {}\n");
            }
        }
        Analysis a = analyze();
        assertEquals(List.of("p0/a.ts:A"), deps(a, "p0/u.ts:U"));
        assertEquals(List.of("p1/a.tsx:A"), deps(a, "p1/u.ts:U"));
        assertEquals(List.of("p2/a.mts:A"), deps(a, "p2/u.ts:U"));
        assertEquals(List.of("p3/a.cts:A"), deps(a, "p3/u.ts:U"));
        assertEquals(List.of("p4/a/index.ts:A"), deps(a, "p4/u.ts:U"));
    }

    /** A relative specifier that climbs above the analysed root is external, even if a file of that name exists. */
    @Test
    void specifiersAboveTheRootAreExternal() throws IOException {
        write("a.ts", "import { B } from '../b';\nclass A { b: B; }\n");
        write("b.ts", "export class B {}\n");
        assertEquals(List.of(), deps(analyze(), "a.ts:A"));
    }

    /** {@code @/}, {@code ~/} and {@code #/} are path aliases matched against the end of a project file's path. */
    @Test
    void pathAliasesMatchBySuffix() throws IOException {
        write("src/lib/db.ts", "export class Db {}\n");
        write("src/ui/u.ts", """
                import { Db as D1 } from '@/lib/db';
                import { Db as D2 } from '~/lib/db';
                import { Db as D3 } from '#/lib/db';
                import { Db as D4 } from '@/db';
                class U1 { d: D1; }
                class U2 { d: D2; }
                class U3 { d: D3; }
                class U4 { d: D4; }
                """);
        Analysis a = analyze();
        for (String u : List.of("U1", "U2", "U3", "U4")) {
            assertEquals(List.of("src/lib/db.ts:Db"), deps(a, "src/ui/u.ts:" + u), u);
        }
    }

    /** An alias that matches two different files is ambiguous and treated as external. */
    @Test
    void ambiguousAliasesAreExternal() throws IOException {
        write("src/lib/db.ts", "export class Db {}\n");
        write("other/db.ts", "export class Db {}\n");
        write("u.ts", "import { Db } from '@/db';\nclass U { d: Db; }\n");
        assertEquals(List.of(), deps(analyze(), "u.ts:U"));
    }

    /** Bare, scoped and deep package specifiers are external, even when a project file has a matching path. */
    @Test
    void packageSpecifiersAreExternal() throws IOException {
        write("react.ts", "export class R {}\n");
        write("@scope/pkg.ts", "export class P {}\n");
        write("lodash/fp.ts", "export class F {}\n");
        write("u.ts", """
                import { R } from 'react';
                import { P } from '@scope/pkg';
                import { F } from 'lodash/fp';
                class U { r: R; p: P; f: F; }
                """);
        assertEquals(List.of(), deps(analyze(), "u.ts:U"));
    }

    /**
     * By import kind: a default import resolves to the default export, a named import (also under an alias) to the
     * export of that name, a namespace import, or a name the file does not declare, to its module building.
     */
    @Test
    void importKindsResolveToTheirTargets() throws IOException {
        write("m.ts", "export default class D {}\nexport class N {}\nexport function f() {}\n");
        write("u.ts", """
                import Def from './m';
                import { N as Alias } from './m';
                import * as NS from './m';
                import { Missing } from './m';
                class U1 { d: Def; }
                class U2 { n: Alias; }
                class U3 { x: NS.N; }
                class U4 { y: Missing; }
                """);
        Analysis a = analyze();
        assertEquals(List.of("m.ts:D"), deps(a, "u.ts:U1"));
        assertEquals(List.of("m.ts:N"), deps(a, "u.ts:U2"));
        assertEquals(List.of("m.ts"), deps(a, "u.ts:U3"));
        assertEquals(List.of("m.ts"), deps(a, "u.ts:U4"));
    }

    /** An edge to the module building of a file that has none (no free code) is dropped. */
    @Test
    void edgesToMissingModuleBuildingsAreDropped() throws IOException {
        write("m.ts", "export class K {}\n");
        write("u.ts", "import * as NS from './m';\nclass V { x: NS.K; }\n");
        assertEquals(List.of(), deps(analyze(), "u.ts:V"));
    }

    /** Re-exports are not followed: importing through a barrel points at the barrel's module building. */
    @Test
    void reExportsAreNotFollowed() throws IOException {
        write("lib/index.ts", "export { X } from './x';\nexport const v = 1;\n");
        write("lib/x.ts", "export class X {}\n");
        write("u.ts", "import { X } from './lib';\nclass U { x: X; }\n");
        assertEquals(List.of("lib/index.ts"), deps(analyze(), "u.ts:U"));
    }

    /** When two imports bind the same local name, the last one wins. */
    @Test
    void lastImportOfALocalNameWins() throws IOException {
        write("a.ts", "export class A {}\n");
        write("b.ts", "export class B {}\n");
        write("u.ts", "import { A } from './a';\nimport { B as A } from './b';\nclass U { x: A; }\n");
        assertEquals(List.of("b.ts:B"), deps(analyze(), "u.ts:U"));
    }
}
