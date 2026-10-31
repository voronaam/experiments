//! The documented work caps of the analysis, each tested exactly at its limit (still accepted) and one step past it
//! (cut off as documented), so that an off-by-one or a changed limit is noticed. The adversarial tests in `tests.rs`
//! cover the hostile inputs these caps exist for. The Java (`AnalysisCapsTest`) and D (`analysis/capstests.d`)
//! editions run the same scenarios with the same expectations.
//!
//! Two caps are already tested at their exact boundary in `tests.rs` and are not repeated here: on-demand imports
//! (`wildcard_imports_are_capped`) and Rust glob imports (`glob_imports_are_capped`).

use super::{java, java_parse, rust_lang, rust_parse, text, typescript_parse, SourceAnalyzer, MAX_FILE_BYTES};
use crate::model::Analysis;
use std::collections::BTreeSet;
use std::path::Path;

fn write(root: &Path, rel: &str, code: &str) {
    let f = root.join(rel);
    std::fs::create_dir_all(f.parent().unwrap()).unwrap();
    std::fs::write(f, code).unwrap();
}

fn analyze(root: &Path) -> Analysis {
    SourceAnalyzer::new().analyze(&std::fs::canonicalize(root).unwrap(), true)
}

fn fqns(a: &Analysis) -> BTreeSet<String> {
    a.types.iter().map(|t| t.fqn.clone()).collect()
}

fn set(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|s| s.to_string()).collect()
}

fn deps(a: &Analysis, fqn: &str) -> BTreeSet<String> {
    a.dependencies[fqn].iter().cloned().collect()
}

/// Pads `head` with a trailing line comment to exactly `size` bytes (ASCII only).
fn padded(head: &str, size: u64) -> Vec<u8> {
    let mut b = vec![b'x'; size as usize];
    let h = format!("{head}//");
    b[..h.len()].copy_from_slice(h.as_bytes());
    b
}

/// The limits have their documented values; the tests below use the constants, so a change shows up here.
#[test]
fn limits_have_their_documented_values() {
    assert_eq!(MAX_FILE_BYTES, 16 * 1024 * 1024);
    assert_eq!(text::MAX_FQN_BYTES, 1024);
    assert_eq!(java::MAX_QUALIFIED_PREFIXES, 16);
    assert_eq!(java::MAX_IMPORT_CANDIDATES, 64);
    assert_eq!(java::MAX_TYPE_NESTING, 16);
    assert_eq!(rust_lang::MAX_GLOB_IMPORTS, 64);
    assert_eq!(rust_lang::MAX_MANIFEST_BYTES, 1024 * 1024);
    assert_eq!((java_parse::MAX_DEPTH, typescript_parse::MAX_DEPTH, rust_parse::MAX_DEPTH), (300, 400, 200));
}

/// A source file of exactly MAX_FILE_BYTES is analysed; one byte more and it is not read, and counts as failed.
#[test]
fn file_size_cap_is_inclusive() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    std::fs::write(r.join("At.java"), padded("class At {}\n", MAX_FILE_BYTES)).unwrap();
    std::fs::write(r.join("Past.java"), padded("class Past {}\n", MAX_FILE_BYTES + 1)).unwrap();
    let a = analyze(r);
    assert_eq!(fqns(&a), set(&["At"]));
    assert_eq!(a.failed_files, 1);
}

/// Java: a type whose fqn is exactly MAX_FQN_BYTES long is a building, one byte longer is skipped (its file failed),
/// whether the length comes from the package or from an outer type; the outer type itself is kept.
#[test]
fn java_fqn_cap_is_inclusive() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let max = text::MAX_FQN_BYTES;
    let at_pkg = "a".repeat(max - 2);
    let past_pkg = "b".repeat(max - 1);
    let at_outer = "O".repeat(max - 2);
    let past_outer = "Q".repeat(max - 1);
    write(r, "At.java", &format!("package {at_pkg};\nclass C {{}}\n"));
    write(r, "Past.java", &format!("package {past_pkg};\nclass C {{}}\n"));
    write(r, "AtOuter.java", &format!("class {at_outer} {{ class I {{}} }}\n"));
    write(r, "PastOuter.java", &format!("class {past_outer} {{ class I {{}} }}\n"));
    let a = analyze(r);
    let expected: BTreeSet<String> = [format!("{at_pkg}.C"), at_outer.clone(), format!("{at_outer}.I"), past_outer].into_iter().collect();
    assert_eq!(fqns(&a), expected);
    assert_eq!(a.failed_files, 2);
}

/// TypeScript: `path:name` of exactly MAX_FQN_BYTES is a building, one byte longer is skipped.
#[test]
fn type_script_fqn_cap_is_inclusive() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let max = text::MAX_FQN_BYTES;
    let at = "N".repeat(max - "a.ts:".len());
    let past = "P".repeat(max + 1 - "b.ts:".len());
    write(r, "a.ts", &format!("export class {at} {{}}\n"));
    write(r, "b.ts", &format!("export class {past} {{}}\n"));
    let a = analyze(r);
    assert_eq!(fqns(&a), set(&[&format!("a.ts:{at}")]));
    assert_eq!(a.failed_files, 1);
}

/// Rust: an item fqn of exactly MAX_FQN_BYTES is a building, one byte longer is skipped; an inline module is kept
/// while its module building's fqn (`path::{fn}`) fits exactly, and skipped with everything in it beyond.
#[test]
fn rust_fqn_cap_is_inclusive() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let max = text::MAX_FQN_BYTES;
    let at_type = format!("S{}", "s".repeat(max - "k::".len() - 1));
    let at_mod = "m".repeat(max - "k::".len() - "::{fn}".len());
    let past_type = format!("P{}", "p".repeat(max + 1 - "k::past::".len() - 1));
    let past_mod = "q".repeat(max + 1 - "k::past::".len() - "::{fn}".len());
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", &format!("pub mod past;\npub struct {at_type};\nmod {at_mod} {{ fn f() {{}} }}\n"));
    write(r, "src/past.rs", &format!("pub struct {past_type};\nmod {past_mod} {{ fn f() {{}} }}\n"));
    let a = analyze(r);
    assert_eq!(fqns(&a), set(&[&format!("k::{at_type}"), &format!("k::{at_mod}::{{fn}}")]));
    assert_eq!(a.failed_files, 1);
}

/// `s1.s2…sN`.
fn segments(n: usize) -> String {
    (1..=n).map(|i| format!("s{i}")).collect::<Vec<_>>().join(".")
}

/// Java: a qualified name contributes at most MAX_QUALIFIED_PREFIXES of its prefixes as references, the shortest.
/// With one segment more than that, every prefix still counts; with two more, the longest prefix is dropped.
#[test]
fn qualified_name_prefixes_are_capped() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let cap = java::MAX_QUALIFIED_PREFIXES;
    let longest = segments(cap);
    let next = segments(cap + 1);
    write(r, "Longest.java", &format!("package {};\npublic class s{cap} {{}}\n", segments(cap - 1)));
    write(r, "Next.java", &format!("package {};\npublic class s{} {{}}\n", segments(cap), cap + 1));
    write(r, "At.java", &format!("class At {{ {} x; }}\n", segments(cap + 1)));
    write(r, "Past.java", &format!("class Past {{ {} x; }}\n", segments(cap + 2)));
    let a = analyze(r);
    assert_eq!(deps(&a, "At"), set(&[&longest, &next]));
    assert_eq!(deps(&a, "Past"), set(&[&longest]));
}

/// Java: of the single-type imports of one simple name, only the first MAX_IMPORT_CANDIDATES are tried.
#[test]
fn single_type_imports_per_name_are_capped() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let others: String = (0..java::MAX_IMPORT_CANDIDATES - 1).map(|i| format!("import w{i}.D;\n")).collect();
    write(r, "q/D.java", "package q; public class D {}\n");
    write(r, "p/In.java", &format!("package p;\n{others}import q.D;\nclass In {{ D d; }}\n"));
    write(r, "p/Out.java", &format!("package p;\n{others}import x.D;\nimport q.D;\nclass Out {{ D d; }}\n"));
    let a = analyze(r);
    assert_eq!(deps(&a, "p.In"), set(&["q.D"]));
    assert_eq!(deps(&a, "p.Out"), set(&[]));
}

/// Java: member types nested exactly MAX_TYPE_NESTING deep are all buildings; one level more is skipped.
#[test]
fn type_nesting_cap_is_inclusive() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let cap = java::MAX_TYPE_NESTING;
    write(r, "A.java", &format!("{}{}", "class A {".repeat(cap), "}".repeat(cap)));
    write(r, "B.java", &format!("{}{}", "class B {".repeat(cap + 1), "}".repeat(cap + 1)));
    let a = analyze(r);
    let f = fqns(&a);
    assert_eq!(f.iter().filter(|x| x.starts_with('A')).count(), cap);
    assert_eq!(f.iter().filter(|x| x.starts_with('B')).count(), cap);
    assert_eq!(a.failed_files, 1);
}

/// A Cargo.toml of exactly MAX_MANIFEST_BYTES is read; one byte more and it names no crate.
#[test]
fn manifest_size_cap_is_inclusive() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    write(r, "a/src/lib.rs", "pub struct A;\n");
    write(r, "b/src/lib.rs", "pub struct B;\n");
    std::fs::write(r.join("a/Cargo.toml"), padded("[package]\nname = \"atlimit\"\n", rust_lang::MAX_MANIFEST_BYTES)).unwrap();
    std::fs::write(r.join("b/Cargo.toml"), padded("[package]\nname = \"past\"\n", rust_lang::MAX_MANIFEST_BYTES + 1)).unwrap();
    let f = fqns(&analyze(r));
    assert!(f.contains("atlimit::A"));
    assert!(!f.iter().any(|x| x.starts_with("past::")));
}

/// Java parser: array initialisers nested MAX_DEPTH - 1 deep (the field takes one level) parse cleanly; one more
/// level reaches the cap and the file is flagged.
#[test]
fn java_parser_depth_cap() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let ok = java_parse::MAX_DEPTH as usize - 1;
    write(r, "At.java", &format!("class At {{ int[] x = {}{}; }}\n", "{".repeat(ok), "}".repeat(ok)));
    write(r, "Past.java", &format!("class Past {{ int[] x = {}{}; }}\n", "{".repeat(ok + 1), "}".repeat(ok + 1)));
    let a = analyze(r);
    assert_eq!(fqns(&a), set(&["At", "Past"]));
    assert_eq!(a.failed_files, 1);
}

/// TypeScript parser: `if (x)` statements nested MAX_DEPTH - 3 deep (the file and the innermost statement take the
/// other levels) parse cleanly; one more reaches the cap and the file is flagged.
#[test]
fn type_script_parser_depth_cap() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let ok = typescript_parse::MAX_DEPTH as usize - 3;
    write(r, "at.ts", &format!("{}y;\n", "if (x) ".repeat(ok)));
    write(r, "past.ts", &format!("{}y;\n", "if (x) ".repeat(ok + 1)));
    let a = analyze(r);
    assert_eq!(a.files, 2);
    assert_eq!(a.failed_files, 1);
}

/// Rust parser: inline modules nested MAX_DEPTH - 1 deep (the file takes one level) parse cleanly; one more reaches
/// the cap and the file is flagged.
#[test]
fn rust_parser_depth_cap() {
    let dir = tempfile::tempdir().unwrap();
    let r = dir.path();
    let ok = rust_parse::MAX_DEPTH as usize - 1;
    write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
    write(r, "src/lib.rs", &format!("pub mod past;\n{}pub struct At;{}\n", "mod a { ".repeat(ok), "}".repeat(ok)));
    write(r, "src/past.rs", &format!("{}pub struct Past;{}\n", "mod a { ".repeat(ok + 1), "}".repeat(ok + 1)));
    let a = analyze(r);
    assert!(fqns(&a).contains(&format!("k{}::At", "::a".repeat(ok))));
    assert_eq!(a.failed_files, 1);
}
