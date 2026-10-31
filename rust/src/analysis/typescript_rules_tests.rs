//! One test per rule of the TypeScript analyser: class, interface, enum and alias members, the module building of a
//! file's free code, complexity, namespaces, default exports, test detection, projects, accepted file names, the
//! failed-file flag, and each rule of import resolution. The golden suite checks whole cities; these tests name the
//! rule that broke. The Java edition (`TypeScriptRulesTest`) and the D edition (`analysis/typescriptrulestests.d`) run
//! the same scenarios with the same expectations.

use super::SourceAnalyzer;
use crate::model::{Analysis, TypeInfo};
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::Path;

fn write(root: &Path, rel: &str, code: &str) {
    let f = root.join(rel);
    std::fs::create_dir_all(f.parent().unwrap()).unwrap();
    std::fs::write(f, code).unwrap();
}

/// The lines joined with `\n`, ending with one, like a Java text block.
fn src(lines: &[&str]) -> String {
    lines.iter().map(|l| format!("{l}\n")).collect()
}

fn analyze(root: &Path) -> Analysis {
    SourceAnalyzer::new().analyze(&std::fs::canonicalize(root).unwrap(), true)
}

fn by_fqn(a: &Analysis) -> HashMap<&str, &TypeInfo> {
    a.types.iter().map(|t| (t.fqn.as_str(), t)).collect()
}

fn fqns(a: &Analysis) -> BTreeSet<&str> {
    a.types.iter().map(|t| t.fqn.as_str()).collect()
}

/// The (sorted) dependencies of `fqn`.
fn deps<'a>(a: &'a Analysis, fqn: &str) -> Vec<&'a str> {
    a.dependencies[fqn].iter().map(String::as_str).collect()
}

fn method_names(t: &TypeInfo) -> Vec<&str> {
    t.methods.iter().map(|m| m.name.as_str()).collect()
}

fn complexities(t: &TypeInfo) -> Vec<u32> {
    t.methods.iter().map(|m| m.complexity).collect()
}

// ------------------------------------------------------------------ members and metrics

/// Plain fields (static ones too) are attributes; fields holding a function are methods, like static methods.
#[test]
fn function_valued_fields_are_methods_other_fields_attributes() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", &src(&[
        "export class C {",
        "  a = 1;",
        "  static b: number;",
        "  f = () => 1;",
        "  g = function () { return 2; };",
        "  static h() {}",
        "  m() {}",
        "}",
    ]));
    let a = analyze(dir.path());
    let c = by_fqn(&a)["a.ts:C"];
    assert_eq!(c.noa, 2);
    assert_eq!(method_names(c), ["f", "g", "h", "m"]);
}

/// Constructor parameters with an accessibility modifier are attributes; `readonly` alone is not.
#[test]
fn parameter_properties_need_an_accessibility_modifier() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", "class P { constructor(private a: number, public b: string, protected c: number, readonly d: number, e: number) {} }\n");
    let a = analyze(dir.path());
    let p = by_fqn(&a)["a.ts:P"];
    assert_eq!((p.noa, p.methods[0].parameters), (3, 5));
}

/// Abstract methods and overload signatures are methods of complexity 1; the class is "abstract".
#[test]
fn abstract_methods_and_overload_signatures_are_methods() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", &src(&[
        "abstract class A {",
        "  abstract f(): void;",
        "  g(x: number): void;",
        "  g(x: string): void;",
        "  g(x: any) { if (x) {} }",
        "}",
    ]));
    let a = analyze(dir.path());
    let t = by_fqn(&a)["a.ts:A"];
    assert_eq!(t.kind, "abstract");
    assert_eq!(method_names(t), ["f", "g", "g", "g"]);
    assert_eq!(complexities(t), [1, 1, 1, 2]);
}

/// Interface properties are attributes; method, call and construct signatures methods; index signatures neither.
#[test]
fn interface_members_by_signature_kind() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", "interface I { p: number; q?: string; m(): void; (x: number): string; new (x: number): I; [k: string]: any; }\n");
    let a = analyze(dir.path());
    let i = by_fqn(&a)["a.ts:I"];
    assert_eq!((i.noa, i.nom()), (2, 3));
}

/// Every enum member is an attribute, with or without an initialiser.
#[test]
fn enum_members_are_attributes() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", "enum E { A, B = 2, C }\n");
    let a = analyze(dir.path());
    let e = by_fqn(&a)["a.ts:E"];
    assert_eq!((e.kind, e.noa), ("enum", 3));
}

/// Only an object-literal type alias has members; a union alias has none.
#[test]
fn type_alias_members_only_for_object_types() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", "type O = { x: number; f(): void };\ntype U = 'a' | 'b';\n");
    let a = analyze(dir.path());
    let t = by_fqn(&a);
    assert_eq!([t["a.ts:O"].noa, t["a.ts:O"].nom(), t["a.ts:U"].noa, t["a.ts:U"].nom()], [1, 1, 0, 0]);
}

/// The module building, named after the file, holds the free functions (declarations and function-valued constants)
/// as methods and the other top-level variables as attributes (a destructuring pattern is one); its lines are those of
/// the free statements only, and it spans the whole file.
#[test]
fn module_building_collects_free_functions_and_variables() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", &src(&[
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
        "}",
    ]));
    let a = analyze(dir.path());
    let m = by_fqn(&a)["a.ts"];
    assert_eq!((m.name.as_str(), m.kind), ("a.ts", "module"));
    assert_eq!(method_names(m), ["f", "g", "h"]);
    assert_eq!((m.noa, m.loc, m.line, m.end_line), (3, 7, 1, 12));
}

/// A file without free code (only classes, interfaces and imports) has no module building.
#[test]
fn no_module_building_without_free_code() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "b.ts", "import { x } from './x';\nexport class B {}\ninterface I {}\n");
    assert_eq!(fqns(&analyze(dir.path())), BTreeSet::from(["b.ts:B", "b.ts:I"]));
}

/// A top-level call with a callback argument is a method named after the callee and its first string argument (quotes
/// kept, cut to 48 code points with an ellipsis); its complexity covers the callbacks. Calls without a callback are
/// not methods, but their lines count.
#[test]
fn top_level_calls_with_callbacks_are_methods() {
    let dir = tempfile::tempdir().unwrap();
    let label = "x".repeat(60);
    write(dir.path(), "a.ts", &format!(
        "describe('cart', () => {{ it('adds', () => {{ if (y) {{}} }}); }});\napp.get(\"/\", handler);\nrun();\ntest('{label}', () => {{}});\nsuite(`tpl`, function () {{}});\n"));
    let a = analyze(dir.path());
    let m = by_fqn(&a)["a.ts"];
    let cut = format!("test('{}…)", "x".repeat(46));
    assert_eq!(method_names(m), ["describe('cart')", cut.as_str(), "suite(`tpl`)"]);
    assert_eq!(complexities(m), [2, 1, 1]);
    assert_eq!(m.loc, 5);
}

/// Complexity is 1 plus one per if, for, for-of, for-in, while, do, non-default case, catch, `?:`, `&&`, `||` and
/// `??`; nested functions count towards the enclosing one.
#[test]
fn complexity_counts_each_decision_kind() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", &src(&[
        "function ifs(a: any) { if (a) {} else if (!a) {} else {} }",
        "function loops(xs: any) { for (;;) {} for (const x of xs) {} for (const k in xs) {} while (xs) {} do {} while (xs); }",
        "function cases(k: number) { switch (k) { case 1: case 2: break; default: } }",
        "function catches() { try {} catch (e) {} finally {} }",
        "function ops(a: any, b: any) { return a ? b : (a && b) || (a ?? b); }",
        "function nested(a: any) { const g = () => { if (a) {} }; function h() { while (a) {} } }",
    ]));
    let a = analyze(dir.path());
    let cx: BTreeMap<&str, u32> = by_fqn(&a)["a.ts"].methods.iter().map(|m| (m.name.as_str(), m.complexity)).collect();
    let expected: BTreeMap<&str, u32> =
        [("ifs", 3), ("loops", 6), ("cases", 3), ("catches", 2), ("ops", 5), ("nested", 3)].into_iter().collect();
    assert_eq!(cx, expected);
}

/// Declarations in namespaces (nested ones too) and ambient modules are named with the namespace path.
#[test]
fn namespaces_prefix_their_declarations() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", &src(&[
        "namespace N { export class A {} export namespace M { export interface B {} } }",
        "declare module \"lib\" { export class C {} }",
    ]));
    let a = analyze(dir.path());
    assert_eq!(fqns(&a), BTreeSet::from(["a.ts:N.A", "a.ts:N.M.B", "a.ts:lib.C"]));
    assert_eq!(by_fqn(&a)["a.ts:N.M.B"].name, "N.M.B");
}

/// Default exports: an identifier names a declaration of the file; an anonymous function is the module building's
/// function `default`; an anonymous class expression is a building named `default`.
#[test]
fn default_export_forms() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "d1.ts", "class A {}\nexport default A;\n");
    write(r, "d2.ts", "export default function () { return 1; }\n");
    write(r, "d3.ts", "export default class {}\n");
    write(r, "u.ts", &src(&[
        "import A from './d1';",
        "import F from './d2';",
        "import C from './d3';",
        "class U { a: A; c: C; }",
        "class V { f = F; }",
    ]));
    let a = analyze(r);
    let t = by_fqn(&a);
    assert_eq!(method_names(t["d2.ts"]), ["default"]);
    assert!(t.contains_key("d3.ts:default"));
    assert_eq!(deps(&a, "u.ts:U"), ["d1.ts:A", "d3.ts:default"]);
    assert_eq!(deps(&a, "u.ts:V"), ["d2.ts"]);
}

/// Repeated names: the first keeps the plain id, later ones get `#kind`, then `#kind2`, … per kind.
#[test]
fn repeated_declarations_are_numbered_per_kind() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", "interface A {}\nclass A {}\nclass A {}\ninterface A {}\n");
    assert_eq!(fqns(&analyze(dir.path())), BTreeSet::from(["a.ts:A", "a.ts:A#class", "a.ts:A#class2", "a.ts:A#interface"]));
}

// ------------------------------------------------------------------ files, tests, projects

/// Test code: a `.test.` or `.spec.` file, or a file under a test directory (test, tests, __tests__ and the others
/// shared with Java). Other names containing "test" or "spec" are not tests.
#[test]
fn test_detection_by_suffix_and_directory() {
    let dir = tempfile::tempdir().unwrap();
    let tests = ["src/a.test.ts", "src/b.spec.ts", "src/c.spec.tsx", "src/d.test.mts", "src/__tests__/e.ts", "test/f.ts", "tests/g.ts"];
    let plain = ["src/contest.ts", "src/spec.ts", "src/testing/h.ts", "src/latest.ts"];
    for f in tests.iter().chain(plain.iter()) {
        write(dir.path(), f, "export const v = 1;\n");
    }
    let a = analyze(dir.path());
    let t = by_fqn(&a);
    for f in tests {
        assert!(t[f].test, "{f}");
    }
    for f in plain {
        assert!(!t[f].test, "{f}");
    }
}

/// The project (build module) is the directory of the nearest package.json, "" for the root's; the package is the
/// file's directory relative to it.
#[test]
fn project_is_the_nearest_package_json() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "web/package.json", "{}");
    write(r, "web/sub/package.json", "{}");
    for f in ["web/src/ui/a.ts", "web/b.ts", "web/sub/c.ts", "root.ts", "lib/x/d.ts"] {
        write(r, f, "export const v = 1;\n");
    }
    let a = analyze(r);
    let t = by_fqn(&a);
    let place = |f: &str| (t[f].module.as_str(), t[f].package.as_str());
    assert_eq!(place("web/src/ui/a.ts"), ("web", "src/ui"));
    assert_eq!(place("web/b.ts"), ("web", ""));
    assert_eq!(place("web/sub/c.ts"), ("web/sub", ""));
    assert_eq!(place("root.ts"), ("", ""));
    assert_eq!(place("lib/x/d.ts"), ("", "lib/x"));
}

/// .ts, .tsx, .mts and .cts are analysed; declaration files (.d.ts, .d.mts, .d.cts) and JavaScript are not.
#[test]
fn accepted_file_extensions() {
    let dir = tempfile::tempdir().unwrap();
    for f in ["a.ts", "b.tsx", "c.mts", "d.cts", "e.d.ts", "f.d.mts", "g.d.cts", "h.js"] {
        write(dir.path(), f, "export const v = 1;\n");
    }
    let a = analyze(dir.path());
    assert_eq!(fqns(&a), BTreeSet::from(["a.ts", "b.tsx", "c.mts", "d.cts"]));
    assert_eq!(a.files, 4);
}

/// A file with a syntax error counts as failed and still yields what was recovered around the error.
#[test]
fn syntax_errors_flag_the_file_but_keep_its_declarations() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "good.ts", "export class G {}\n");
    write(dir.path(), "bad.ts", "export class A {}\nconst = ;\nexport class B {}\n");
    let a = analyze(dir.path());
    assert_eq!(a.failed_files, 1);
    let all = fqns(&a);
    assert!(["bad.ts:A", "bad.ts:B", "good.ts:G"].iter().all(|f| all.contains(f)));
}

// ------------------------------------------------------------------ import resolution

/// A name declared in the file itself comes before an import of the same name.
#[test]
fn same_file_declarations_beat_imports() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", "import { B } from './b';\nclass B {}\nclass A { b: B; }\n");
    write(dir.path(), "b.ts", "export class B {}\n");
    assert_eq!(deps(&analyze(dir.path()), "a.ts:A"), ["a.ts:B"]);
}

/// `./x`, `../x`, `.` and `..` resolve against the importing file's directory.
#[test]
fn relative_specifiers() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "src/x/a.ts", &src(&[
        "import { B } from './b';",
        "import { C } from '../c';",
        "import { I } from '.';",
        "import { J } from '..';",
        "export class A { b: B; c: C; i: I; j: J; }",
    ]));
    write(r, "src/x/b.ts", "export class B {}\n");
    write(r, "src/c.ts", "export class C {}\n");
    write(r, "src/x/index.ts", "export class I {}\n");
    write(r, "src/index.ts", "export class J {}\n");
    assert_eq!(deps(&analyze(r), "src/x/a.ts:A"), ["src/c.ts:C", "src/index.ts:J", "src/x/b.ts:B", "src/x/index.ts:I"]);
}

/// ESM-style specifiers name the JavaScript output: `.js`, `.jsx`, `.mjs`, `.cjs`.
#[test]
fn java_script_extensions_in_specifiers_map_to_type_script() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "u.ts", &src(&[
        "import { B } from './b.js';",
        "import { C } from './c.jsx';",
        "import { D } from './d.mjs';",
        "import { E } from './e.cjs';",
        "class U { b: B; c: C; d: D; e: E; }",
    ]));
    write(r, "b.ts", "export class B {}\n");
    write(r, "c.tsx", "export class C {}\n");
    write(r, "d.mts", "export class D {}\n");
    write(r, "e.cts", "export class E {}\n");
    assert_eq!(deps(&analyze(r), "u.ts:U"), ["b.ts:B", "c.tsx:C", "d.mts:D", "e.cts:E"]);
}

/// Files sharing a key are picked like TypeScript: a.ts, a.tsx, a.mts, a.cts, then a/index.ts, a/index.tsx.
#[test]
fn same_key_prefers_ts_then_tsx_mts_cts_then_index() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    // Each directory pN imports "./a" and holds a different subset of the candidates.
    let sets: [&[&str]; 5] = [
        &["a.ts", "a.tsx", "a.mts", "a.cts", "a/index.ts"],
        &["a.tsx", "a.mts", "a/index.ts"],
        &["a.mts", "a.cts"],
        &["a.cts", "a/index.ts"],
        &["a/index.tsx", "a/index.ts"],
    ];
    for (i, set) in sets.iter().enumerate() {
        write(r, &format!("p{i}/u.ts"), "import { A } from './a';\nclass U { a: A; }\n");
        for f in *set {
            write(r, &format!("p{i}/{f}"), "export class A {}\n");
        }
    }
    let a = analyze(r);
    assert_eq!(deps(&a, "p0/u.ts:U"), ["p0/a.ts:A"]);
    assert_eq!(deps(&a, "p1/u.ts:U"), ["p1/a.tsx:A"]);
    assert_eq!(deps(&a, "p2/u.ts:U"), ["p2/a.mts:A"]);
    assert_eq!(deps(&a, "p3/u.ts:U"), ["p3/a.cts:A"]);
    assert_eq!(deps(&a, "p4/u.ts:U"), ["p4/a/index.ts:A"]);
}

/// A relative specifier that climbs above the analysed root is external, even if a file of that name exists.
#[test]
fn specifiers_above_the_root_are_external() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "a.ts", "import { B } from '../b';\nclass A { b: B; }\n");
    write(dir.path(), "b.ts", "export class B {}\n");
    assert!(deps(&analyze(dir.path()), "a.ts:A").is_empty());
}

/// `@/`, `~/` and `#/` are path aliases matched against the end of a project file's path.
#[test]
fn path_aliases_match_by_suffix() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "src/lib/db.ts", "export class Db {}\n");
    write(r, "src/ui/u.ts", &src(&[
        "import { Db as D1 } from '@/lib/db';",
        "import { Db as D2 } from '~/lib/db';",
        "import { Db as D3 } from '#/lib/db';",
        "import { Db as D4 } from '@/db';",
        "class U1 { d: D1; }",
        "class U2 { d: D2; }",
        "class U3 { d: D3; }",
        "class U4 { d: D4; }",
    ]));
    let a = analyze(r);
    for u in ["U1", "U2", "U3", "U4"] {
        assert_eq!(deps(&a, &format!("src/ui/u.ts:{u}")), ["src/lib/db.ts:Db"], "{u}");
    }
}

/// An alias that matches two different files is ambiguous and treated as external.
#[test]
fn ambiguous_aliases_are_external() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "src/lib/db.ts", "export class Db {}\n");
    write(r, "other/db.ts", "export class Db {}\n");
    write(r, "u.ts", "import { Db } from '@/db';\nclass U { d: Db; }\n");
    assert!(deps(&analyze(r), "u.ts:U").is_empty());
}

/// Bare, scoped and deep package specifiers are external, even when a project file has a matching path.
#[test]
fn package_specifiers_are_external() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "react.ts", "export class R {}\n");
    write(r, "@scope/pkg.ts", "export class P {}\n");
    write(r, "lodash/fp.ts", "export class F {}\n");
    write(r, "u.ts", &src(&[
        "import { R } from 'react';",
        "import { P } from '@scope/pkg';",
        "import { F } from 'lodash/fp';",
        "class U { r: R; p: P; f: F; }",
    ]));
    assert!(deps(&analyze(r), "u.ts:U").is_empty());
}

/// By import kind: a default import resolves to the default export, a named import (also under an alias) to the
/// export of that name, a namespace import, or a name the file does not declare, to its module building.
#[test]
fn import_kinds_resolve_to_their_targets() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "m.ts", "export default class D {}\nexport class N {}\nexport function f() {}\n");
    write(r, "u.ts", &src(&[
        "import Def from './m';",
        "import { N as Alias } from './m';",
        "import * as NS from './m';",
        "import { Missing } from './m';",
        "class U1 { d: Def; }",
        "class U2 { n: Alias; }",
        "class U3 { x: NS.N; }",
        "class U4 { y: Missing; }",
    ]));
    let a = analyze(r);
    assert_eq!(deps(&a, "u.ts:U1"), ["m.ts:D"]);
    assert_eq!(deps(&a, "u.ts:U2"), ["m.ts:N"]);
    assert_eq!(deps(&a, "u.ts:U3"), ["m.ts"]);
    assert_eq!(deps(&a, "u.ts:U4"), ["m.ts"]);
}

/// An edge to the module building of a file that has none (no free code) is dropped.
#[test]
fn edges_to_missing_module_buildings_are_dropped() {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), "m.ts", "export class K {}\n");
    write(dir.path(), "u.ts", "import * as NS from './m';\nclass V { x: NS.K; }\n");
    assert!(deps(&analyze(dir.path()), "u.ts:V").is_empty());
}

/// Re-exports are not followed: importing through a barrel points at the barrel's module building.
#[test]
fn re_exports_are_not_followed() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "lib/index.ts", "export { X } from './x';\nexport const v = 1;\n");
    write(r, "lib/x.ts", "export class X {}\n");
    write(r, "u.ts", "import { X } from './lib';\nclass U { x: X; }\n");
    assert_eq!(deps(&analyze(r), "u.ts:U"), ["lib/index.ts"]);
}

/// When two imports bind the same local name, the last one wins.
#[test]
fn last_import_of_a_local_name_wins() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "a.ts", "export class A {}\n");
    write(r, "b.ts", "export class B {}\n");
    write(r, "u.ts", "import { A } from './a';\nimport { B as A } from './b';\nclass U { x: A; }\n");
    assert_eq!(deps(&analyze(r), "u.ts:U"), ["b.ts:B"]);
}
