//! Rust support, on the in-house parser [`rust_parse`]. Crates and modules are districts; structs, enums, unions
//! and traits are buildings whose methods are collected from every `impl` block in the crate; each module's free
//! functions and constants form a "module" building. `use` paths are resolved so dependencies connect across files.
//!
//! # Identifiers
//! Everything is named by its absolute module path starting with the crate name (from `Cargo.toml`, `-` replaced by
//! `_` as Rust does): a type is `shop::cart::Cart`, a module's function building is `shop::cart::{fn}` (see
//! [`FUNCTIONS`]). The package (district) is the module path without the crate, joined with `::`.
//!
//! # Why linking does more work here
//! In Rust a type's methods live in `impl` blocks that may sit in another module or file, and a module's path
//! depends on the file layout. So parsing records, per file, one [`Scope`] per module (with its `use` imports) and
//! every `impl` block; [`link`] then attaches each impl to the type it implements and resolves references
//! module by module ([`Resolver`]).

use super::rust_parse::{self, NodeKind, TokKind, Tree, NONE};
use super::text::{count_loc, lines, Lines, MAX_FQN_BYTES};
use super::{Context as AnyContext, Linked, ParsedFile};
use crate::model::{Language, MethodInfo, TypeInfo};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

/// Last segment of a module building's fqn. Braces cannot appear in a Rust identifier, so the id never clashes with
/// a real type name.
pub const FUNCTIONS: &str = "{fn}";

/// Glob imports (`use a::b::*;`) beyond this many in one module are ignored. A name is tried in each of them, so the
/// cap keeps a crafted file with very many from making the analysis quadratic; real modules have a handful. The Java
/// and D editions use the same limit.
pub const MAX_GLOB_IMPORTS: usize = 64;

/// Name-resolution scope of one module (a file, or an inline `mod x { … }` in it). Scopes do not inherit `use`
/// declarations from their parent module, just as in Rust.
#[derive(Debug, Clone)]
pub struct Scope {
    /// Absolute module path, starting with the crate name.
    pub path: Vec<String>,
    /// alias → path as written in the `use` declaration (`use a::b::C as D` gives `D → [a, b, C]`); made absolute
    /// only when resolving
    pub uses: HashMap<String, Vec<String>>,
    /// paths imported with `::*`
    pub globs: Vec<Vec<String>>,
}

impl Scope {
    fn new(path: Vec<String>) -> Self {
        Scope { path, uses: HashMap::new(), globs: vec![] }
    }
    /// The crate name: the first segment of the absolute path.
    fn krate(&self) -> &str {
        &self.path[0]
    }
}

/// An `impl` block, attached to its type during linking. `ty` and `tr` are the implemented type and trait as written
/// (generics stripped), resolved in the scope `scope` (an index into the file's [`Context::scopes`]). `district` and
/// `label` are the district scope and name a module building created for the impl gets, as in `visit_items`.
#[derive(Debug)]
pub struct Impl {
    scope: usize,
    district: usize,
    label: String,
    ty: String,
    tr: Option<String>,
    methods: Vec<MethodInfo>,
    refs: HashSet<String>,
    loc: u32,
    file: PathBuf,
    line: u32,
    test: bool,
}

/// What the linker needs from one file.
#[derive(Debug, Default)]
pub struct Context {
    /// Index 0 is the file's own module; inline `mod` blocks follow.
    scopes: Vec<Scope>,
    /// type / module-building fqn → index into `scopes`
    scope_of: HashMap<String, usize>,
    impls: Vec<Impl>,
}

/// The crate a file belongs to: its name and the directory holding its `Cargo.toml`.
struct Crate {
    name: String,
    dir: PathBuf,
}

/// Parses one `.rs` file. The file's module path is derived from where it sits in its crate (see [`module_path()`]),
/// not from the `mod` declarations that include it, so every file can be parsed on its own.
pub fn parse(root: &Path, file: &Path, code: &str) -> ParsedFile {
    let tree = rust_parse::parse(code);
    let krate = crate_of(root, file.parent().unwrap_or(root));
    let in_crate = file.strip_prefix(&krate.dir).unwrap_or(file);
    let source_root = source_root(&krate.dir, file.parent().unwrap_or(root));
    let module_path = module_path(file.strip_prefix(&source_root).unwrap_or(in_crate));
    let first = in_crate.components().next().map(|c| c.as_os_str().to_string_lossy().to_string());
    // Integration tests and benchmarks live in the crate's tests/ and benches/ directories.
    let test = matches!(first.as_deref(), Some("tests") | Some("benches"));
    let mut absolute = vec![krate.name.clone()];
    absolute.extend(module_path);

    let mut st = FileState {
        code,
        tree: &tree,
        lines: lines(code),
        file,
        module: krate.name.clone(),
        types: vec![],
        ctx: Context::default(),
        skipped: false,
    };
    if st.fits(&absolute) {
        let scope = st.add_scope(Scope::new(absolute));
        let file_name = file.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        st.visit_items(0, scope, scope, &file_name, test);
    }
    ParsedFile { types: st.types, context: AnyContext::Rust(st.ctx), failed: tree.errors || st.skipped }
}

/// The directory module paths are relative to: the nearest one (within the crate) holding `lib.rs` or `main.rs`.
/// Falls back to the crate directory, e.g. for files under `tests/`, whose module path then starts with `tests`.
fn source_root(crate_dir: &Path, dir: &Path) -> PathBuf {
    let mut d = Some(dir);
    while let Some(cur) = d {
        if !cur.starts_with(crate_dir) {
            break;
        }
        if cur.join("lib.rs").is_file() || cur.join("main.rs").is_file() {
            return cur.to_path_buf();
        }
        if cur == crate_dir {
            break;
        }
        d = cur.parent();
    }
    crate_dir.to_path_buf()
}

/// `src/lib.rs` → `[]`, `src/net/mod.rs` → `[net]`, `src/net/http.rs` → `[net, http]`.
///
/// The path is relative to the source root; a leading `src` is dropped in case it is relative to the crate instead.
/// `lib.rs`/`main.rs` directly at that root are the crate root, and `mod.rs` stands for its directory.
pub fn module_path(in_crate: &Path) -> Vec<String> {
    let mut parts: Vec<String> = in_crate.components().map(|c| c.as_os_str().to_string_lossy().to_string()).collect();
    if parts.first().is_some_and(|p| p == "src") {
        parts.remove(0);
    }
    let Some(last) = parts.pop() else { return parts };
    let last = last.strip_suffix(".rs").unwrap_or(&last).to_string();
    let crate_root = parts.is_empty() && (last == "lib" || last == "main");
    if !crate_root && last != "mod" {
        parts.push(last);
    }
    parts
}

/// The nearest crate at or above `dir`, not looking above `root`. A workspace manifest without `[package]` does not
/// count, so the walk continues past it. Without any crate the root directory's name is used.
///
/// Manifest lookups are cached process-wide by directory (see [`crate_cache`]).
fn crate_of(root: &Path, dir: &Path) -> Crate {
    let cache = crate_cache();
    let mut d = Some(dir);
    while let Some(cur) = d {
        if !cur.starts_with(root) {
            break;
        }
        let name = {
            let mut c = cache.lock().unwrap();
            c.entry(cur.to_path_buf()).or_insert_with(|| read_crate_name(cur)).clone()
        };
        if let Some(name) = name {
            return Crate { name, dir: cur.to_path_buf() };
        }
        if cur == root {
            break;
        }
        d = cur.parent();
    }
    let name = root.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "crate".into());
    Crate { name: name.replace('-', "_"), dir: root.to_path_buf() }
}

/// Directory → crate name of its `Cargo.toml` (`None` for "no package here"). Cleared by [`link`], so each analysis
/// reads the manifests afresh and a `Cargo.toml` added, removed or edited in watch mode applies to the files parsed
/// after it.
fn crate_cache() -> &'static Mutex<HashMap<PathBuf, Option<String>>> {
    static CRATES: OnceLock<Mutex<HashMap<PathBuf, Option<String>>>> = OnceLock::new();
    CRATES.get_or_init(Default::default)
}

/// Largest `Cargo.toml` that is read; a bigger one counts as having no package name. Real manifests are a few KiB;
/// the cap keeps a crafted one from exhausting memory. The Java and D editions use the same limit.
pub const MAX_MANIFEST_BYTES: u64 = 1024 * 1024;

/// The `name` in the `[package]` table of `dir/Cargo.toml`, with `-` replaced by `_`; `None` when the manifest is
/// missing, unreadable, not valid UTF-8, larger than [`MAX_MANIFEST_BYTES`] or has no package name. Only a regular
/// file is read: a symbolic link (which could point outside the analysed tree, or at a device such as `/dev/zero`)
/// counts as missing.
pub fn read_crate_name(dir: &Path) -> Option<String> {
    let path = dir.join("Cargo.toml");
    let meta = std::fs::symlink_metadata(&path).ok()?;
    if !meta.is_file() || meta.len() > MAX_MANIFEST_BYTES {
        return None;
    }
    let manifest = std::fs::read_to_string(&path).ok()?;
    package_name(&manifest).map(|n| n.replace('-', "_"))
}

/// The `name = "…"` of the `[package]` table of a `Cargo.toml`. Not a TOML parser, and hand-written rather than a
/// regular expression so that all three editions run the same scan in time linear in the manifest's size (the Java
/// edition's backtracking regex recursed once per skipped line).
///
/// Starting at each line that begins with `[package]`, the following lines are scanned: the first one of the form
/// `[ \t]* name \s* = \s* "value"` gives the name (the value may not be empty and ends at the next `"`, even on a later
/// line). Lines skipped on the way must end with a newline and must not start with `[` after indentation
/// (`[ \t\r]`), which keeps the search inside the table, as the next table header starts with `[`; a `[` elsewhere on
/// a line (`authors = ["…"]`) does not stop it. When a `[package]` line yields nothing, the search goes on at the next
/// one. `\s` is ASCII whitespace and lines end at `\n` only.
pub fn package_name(toml: &str) -> Option<&str> {
    let b = toml.as_bytes();
    let n = b.len();
    let next_line = |i: usize| b[i..].iter().position(|&c| c == b'\n').map_or(n, |p| i + p + 1);
    let mut start = 0;
    while start < n {
        if b[start..].starts_with(b"[package]") {
            let mut line = next_line(start);
            while line < n {
                if let Some(name) = name_value(toml, line) {
                    return Some(name);
                }
                // Skip the line, unless it would leave the table.
                let mut i = line;
                while i < n && matches!(b[i], b' ' | b'\t' | b'\r') {
                    i += 1;
                }
                if i == n || b[i] == b'[' || (b[i] != b'\n' && is_toml_space(b[i])) {
                    break;
                }
                match b[i..].iter().position(|&c| c == b'\n') {
                    Some(p) => line = i + p + 1,
                    None => break,
                }
            }
        }
        start = next_line(start);
    }
    None
}

/// The value of a `[ \t]* name \s* = \s* "value"` at byte `i`, or `None` when there is none.
fn name_value(s: &str, mut i: usize) -> Option<&str> {
    let b = s.as_bytes();
    let n = b.len();
    while i < n && matches!(b[i], b' ' | b'\t') {
        i += 1;
    }
    if !b[i..].starts_with(b"name") {
        return None;
    }
    i += 4;
    while i < n && is_toml_space(b[i]) {
        i += 1;
    }
    if i == n || b[i] != b'=' {
        return None;
    }
    i += 1;
    while i < n && is_toml_space(b[i]) {
        i += 1;
    }
    if i == n || b[i] != b'"' {
        return None;
    }
    let close = i + 1 + b[i + 1..].iter().position(|&c| c == b'"')?;
    (close > i + 1).then(|| &s[i + 1..close])
}

/// ASCII whitespace, as `\s` in a Java regular expression: space, `\t \n \x0B \x0C \r`.
fn is_toml_space(c: u8) -> bool {
    c == b' ' || (b'\t'..=b'\r').contains(&c)
}

/// Everything collected while walking one file.
struct FileState<'a> {
    code: &'a str,
    tree: &'a Tree,
    lines: Lines,
    file: &'a Path,
    module: String,
    types: Vec<TypeInfo>,
    ctx: Context,
    /// Set when a module or building was skipped for the length of its fqn (see [`FileState::fits`]).
    skipped: bool,
}

/// The length in bytes of a module path joined with `::`.
fn path_bytes(path: &[String]) -> usize {
    path.iter().map(String::len).sum::<usize>() + 2 * path.len().saturating_sub(1)
}

impl<'a> FileState<'a> {
    /// Whether the module with this path gets a module building fqn (`path::{fn}`) within [`MAX_FQN_BYTES`]. If not,
    /// the module and everything in it are skipped, and the file counts as failed; so no fqn of a building inside it
    /// can be too long either, apart from a single over-long name, which [`FileState::add_type`] checks.
    fn fits(&mut self, path: &[String]) -> bool {
        let fits = path_bytes(path) + 2 + FUNCTIONS.len() <= MAX_FQN_BYTES;
        self.skipped |= !fits;
        fits
    }

    fn add_scope(&mut self, s: Scope) -> usize {
        self.ctx.scopes.push(s);
        self.ctx.scopes.len() - 1
    }

    /// The district path: the scope's module path without the crate name.
    fn package_of(&self, scope: usize) -> String {
        self.ctx.scopes[scope].path[1..].join("::")
    }

    fn fqn(&self, scope: usize, name: &str) -> String {
        format!("{}::{}", self.ctx.scopes[scope].path.join("::"), name)
    }

    fn loc(&self, n: usize) -> u32 {
        count_loc(&self.lines, self.tree.first_line(n), self.tree.last_line(n))
    }

    /// Text of a token ("" for [`NONE`]).
    fn tok(&self, t: u32) -> &'a str {
        self.tree.tok_text(self.code, t)
    }

    /// The item's name (its `name` token), "" if it has none.
    fn name(&self, n: usize) -> String {
        self.tok(self.tree.nodes[n].name).to_string()
    }

    /// `district`: scope whose module path decides the district; differs from `scope` inside `#[cfg(test)]`
    /// modules, which stay in their parent's district instead of adding one.
    ///
    /// Visits the items of a file or inline module (the children of a `File` or `DeclBody` node). Types become
    /// buildings, impls are recorded for linking, `use` declarations fill the scope, inline modules recurse with a new
    /// scope. Free functions, consts and statics are gathered into one module building named `label` (the file name,
    /// or `mod x` for an inline module). `mod x;` without a body is skipped: that file is parsed on its own. `test`
    /// marks everything in this container as test code.
    fn visit_items(&mut self, container: usize, scope: usize, district: usize, label: &str, test: bool) {
        let mut functions = vec![];
        let mut function_refs = HashSet::new();
        let mut constants = 0;
        let mut module_loc = 0;
        // Line span of the free items, reported as the module building's position.
        let mut first = u32::MAX;
        let mut last = 0;
        let mut cfg_test = false;
        for item in self.tree.children(container) {
            let kind = self.tree.nodes[item].kind;
            // Outer attributes precede the item they apply to, so remember `#[cfg(test)]` until the next item. Only
            // the exact form `cfg(test)` (spaces removed) is recognised, not e.g. `cfg(all(test, …))`. Any other node
            // in between, including a comment (the parser records one as a `Comment` node), ends the attribute's
            // effect.
            if kind == NodeKind::Attr {
                let attr: String = self.tree.node_text(self.code, item).chars().filter(|c| *c != ' ').collect();
                cfg_test |= attr.contains("cfg(test)");
                continue;
            }
            let is_test = test || cfg_test;
            match kind {
                NodeKind::Use => {
                    if let Some(&tree) = self.tree.children(item).last() {
                        self.expand_use(tree, &[], scope);
                    }
                }
                NodeKind::Struct | NodeKind::Union => {
                    let k = if kind == NodeKind::Union { "union" } else { "struct" };
                    let noa = self.tree.nodes[item].value;
                    self.add_type(item, scope, district, k, noa, vec![], is_test);
                }
                NodeKind::Enum => {
                    let variants = self.tree.nodes[item].value;
                    self.add_type(item, scope, district, "enum", variants, vec![], is_test);
                }
                NodeKind::Trait => {
                    let methods = self.functions_in(self.tree.child(item, NodeKind::DeclBody));
                    self.add_type(item, scope, district, "trait", 0, methods, is_test);
                }
                NodeKind::Impl => self.add_impl(item, scope, district, label, is_test),
                // A function without a body is a signature, which does not belong here; valid code has none at module
                // level.
                NodeKind::Fn | NodeKind::Const | NodeKind::Static
                    if kind != NodeKind::Fn || self.tree.child(item, NodeKind::Body).is_some() =>
                {
                    if kind == NodeKind::Fn {
                        functions.push(self.function(item));
                    } else {
                        constants += 1;
                    }
                    collect_refs(self.code, self.tree, item, &mut function_refs);
                    module_loc += self.loc(item);
                    first = first.min(self.tree.first_line(item));
                    last = last.max(self.tree.last_line(item));
                }
                NodeKind::Mod => {
                    if let Some(body) = self.tree.child(item, NodeKind::DeclBody) {
                        let mut child = self.ctx.scopes[scope].path.clone();
                        let name = self.name(item);
                        child.push(name.clone());
                        if !self.fits(&child) {
                            cfg_test = false;
                            continue;
                        }
                        let child_scope = self.add_scope(Scope::new(child));
                        // The module always gets its own scope (its names are `parent::tests::…`), but a cfg(test)
                        // module does not get its own district.
                        let child_district = if cfg_test { district } else { child_scope };
                        self.visit_items(body, child_scope, child_district, &format!("mod {name}"), is_test);
                    }
                }
                _ => {}
            }
            // The attribute applied to this item only.
            cfg_test = false;
        }
        if !functions.is_empty() || constants > 0 {
            let id = self.fqn(scope, FUNCTIONS);
            self.ctx.scope_of.insert(id.clone(), scope);
            self.types.push(TypeInfo {
                language: Language::Rust,
                fqn: id,
                name: label.to_string(),
                package: self.package_of(district),
                module: self.module.clone(),
                kind: "module",
                file: self.file.to_path_buf(),
                line: first,
                end_line: last,
                loc: module_loc,
                noa: constants,
                test,
                methods: functions,
                references: function_refs,
                explicit_imports: Default::default(),
                wildcard_imports: Default::default(),
            });
        }
    }

    #[allow(clippy::too_many_arguments)]
    /// Adds a struct, union, enum or trait building. Its methods (other than a trait's own) arrive in [`link`].
    fn add_type(&mut self, item: usize, scope: usize, district: usize, kind: &'static str, noa: u32,
                methods: Vec<MethodInfo>, test: bool) {
        let name = self.name(item);
        if path_bytes(&self.ctx.scopes[scope].path) + 2 + name.len() > MAX_FQN_BYTES {
            self.skipped = true;
            return;
        }
        let mut refs = HashSet::new();
        collect_refs(self.code, self.tree, item, &mut refs);
        let id = self.fqn(scope, &name);
        self.ctx.scope_of.insert(id.clone(), scope);
        self.types.push(TypeInfo {
            language: Language::Rust,
            fqn: id,
            name,
            package: self.package_of(district),
            module: self.module.clone(),
            kind,
            file: self.file.to_path_buf(),
            line: self.tree.first_line(item),
            end_line: self.tree.last_line(item),
            loc: self.loc(item),
            noa,
            test,
            methods,
            references: refs,
            explicit_imports: Default::default(),
            wildcard_imports: Default::default(),
        });
    }

    /// Records an `impl` block. References come from its body only (the header with type, trait and generics is not
    /// scanned); the trait is resolved separately in [`link`].
    fn add_impl(&mut self, item: usize, scope: usize, district: usize, label: &str, test: bool) {
        let body = self.tree.child(item, NodeKind::DeclBody);
        let mut refs = HashSet::new();
        collect_refs(self.code, self.tree, body.unwrap_or(item), &mut refs);
        let imp = Impl {
            scope,
            district,
            label: label.to_string(),
            ty: self.type_name(self.tree.child(item, NodeKind::ImplType)),
            tr: self.tree.child(item, NodeKind::ImplTrait).map(|t| self.type_name(Some(t))),
            methods: self.functions_in(body),
            refs,
            loc: self.loc(item),
            file: self.file.to_path_buf(),
            line: self.tree.first_line(item),
            test,
        };
        self.ctx.impls.push(imp);
    }

    /// `S<T>` → `S`; `crate::m::Other` stays a path; references and pointers are unwrapped. The parser has
    /// already found this "core" of the `ImplType`/`ImplTrait` node's type (its `name` to `value` tokens).
    fn type_name(&self, n: Option<usize>) -> String {
        let Some(n) = n else { return String::new() };
        let node = self.tree.nodes[n];
        self.tree.range_text(self.code, node.name, node.value).to_string()
    }

    /// Functions of an impl or trait body, with or without a body (a trait method without a default).
    fn functions_in(&self, body: Option<usize>) -> Vec<MethodInfo> {
        let Some(body) = body else { return vec![] };
        self.tree
            .children(body)
            .into_iter()
            .filter(|&m| self.tree.nodes[m].kind == NodeKind::Fn)
            .map(|m| self.function(m))
            .collect()
    }

    /// Metrics of one function. The parser counted its parameters (`self` is not counted).
    fn function(&self, f: usize) -> MethodInfo {
        MethodInfo {
            name: self.name(f),
            line: self.tree.first_line(f),
            loc: self.loc(f),
            complexity: self.tree.child(f, NodeKind::Body).map(|b| complexity(self.tree, b)).unwrap_or(1),
            parameters: self.tree.nodes[f].value,
        }
    }

    /// Flattens a use tree into alias → path entries and glob imports.
    ///
    /// `prefix` is the path accumulated from enclosing `a::b::{…}` groups. For example
    /// `use crate::net::{self, http::Client as C, tls::*};` yields `net → [crate, net]`,
    /// `C → [crate, net, http, Client]` and the glob `[crate, net, tls]`.
    fn expand_use(&mut self, n: usize, prefix: &[String], scope: usize) {
        let node = self.tree.nodes[n];
        match node.kind {
            NodeKind::UsePath if node.first == node.last => {
                // A single name, at the top level or inside a `{…}` list.
                let name = self.tok(node.first).to_string();
                if name == "self" && !prefix.is_empty() {
                    self.add_use(scope, prefix.last().unwrap().clone(), prefix.to_vec()); // a::{self}
                } else {
                    let mut path = prefix.to_vec();
                    path.push(name.clone());
                    self.add_use(scope, name, path);
                }
            }
            NodeKind::UsePath => {
                let mut path = prefix.to_vec();
                path.extend(self.segments(Some(n)));
                let mut alias = path.last().cloned().unwrap_or_default();
                if alias == "self" {
                    path.pop();
                    alias = path.last().cloned().unwrap_or_else(|| "self".into());
                }
                self.add_use(scope, alias, path);
            }
            NodeKind::UseAs => {
                let alias = self.tok(node.name).to_string();
                // `use Trait as _` only brings trait methods into scope; there is no name to resolve.
                if alias != "_" {
                    let mut path = prefix.to_vec();
                    path.extend(self.segments(self.tree.child(n, NodeKind::UsePath)));
                    self.add_use(scope, alias, path);
                }
            }
            NodeKind::UseGroup => {
                let mut p = prefix.to_vec();
                p.extend(self.segments(self.tree.child(n, NodeKind::UsePrefix)));
                // A group under an over-long prefix only holds over-long paths (see add_use).
                if path_bytes(&p) > MAX_FQN_BYTES {
                    return;
                }
                for c in self.tree.children(n) {
                    if self.tree.nodes[c].kind != NodeKind::UsePrefix {
                        self.expand_use(c, &p, scope);
                    }
                }
            }
            NodeKind::UseGlob => {
                let mut path = prefix.to_vec();
                path.extend(self.segments(self.tree.child(n, NodeKind::UsePrefix)));
                let globs = &mut self.ctx.scopes[scope].globs;
                if globs.len() < MAX_GLOB_IMPORTS && path_bytes(&path) <= MAX_FQN_BYTES {
                    globs.push(path);
                }
            }
            _ => {}
        }
    }

    /// Records `use path as alias`, unless the path is longer than [`MAX_FQN_BYTES`]: it is copied into a candidate
    /// for every reference to the alias, and a group of many names under one long prefix would make that quadratic.
    /// (Only a path of mostly `super` could still name a building.)
    fn add_use(&mut self, scope: usize, alias: String, path: Vec<String>) {
        if path_bytes(&path) <= MAX_FQN_BYTES {
            self.ctx.scopes[scope].uses.insert(alias, path);
        }
    }

    /// The segments of a use path: `a::b::c` → [a, b, c] (a leading `::` adds none).
    fn segments(&self, n: Option<usize>) -> Vec<String> {
        let Some(n) = n else { return vec![] };
        let node = self.tree.nodes[n];
        (node.first..=node.last)
            .filter(|&t| self.tree.tokens[t as usize].kind == TokKind::Ident)
            .map(|t| self.tok(t).to_string())
            .collect()
    }
}

/// Names under node `node` that may refer to project items: type identifiers, whole paths (`net::http::get`,
/// `Order::new`; whitespace removed, not descended into) and capitalised identifiers (e.g. a tuple-struct constructor
/// call `Price(3)`). Lower-case single identifiers are skipped as they are almost always locals. For a qualified path
/// (`<T as Trait>::Item`) the text names nothing resolvable, so the parser emitted the types inside the brackets
/// instead (see `rust_parse`, "Reference leaves"). Paths of `use` declarations inside items count like paths, a single
/// name or an `as` alias like an identifier.
fn collect_refs(code: &str, tree: &Tree, node: usize, refs: &mut HashSet<String>) {
    let mut i = node + 1;
    let end = tree.nodes[node].end as usize;
    while i < end {
        let n = tree.nodes[i];
        match n.kind {
            NodeKind::TypeIdent => {
                add_reference(tree.tok_text(code, n.first).to_string(), refs);
            }
            NodeKind::Ident => add_identifier(tree.tok_text(code, n.first), refs),
            NodeKind::UseAs if n.name != NONE => add_identifier(tree.tok_text(code, n.name), refs),
            NodeKind::UsePath | NodeKind::UsePrefix if n.first == n.last => {
                add_identifier(tree.tok_text(code, n.first), refs)
            }
            NodeKind::Path | NodeKind::UsePath | NodeKind::UsePrefix => {
                add_reference(tree.range_text(code, n.first, n.last).chars().filter(|c| !c.is_whitespace()).collect(), refs);
                i = n.end as usize; // nothing inside a path is a separate reference
                continue;
            }
            _ => {}
        }
        i += 1;
    }
}

/// An identifier is a reference if capitalised. Lifetimes and labels (`'a`) are identifiers without their quote.
fn add_identifier(s: &str, refs: &mut HashSet<String>) {
    let s = s.strip_prefix('\'').unwrap_or(s);
    if s.chars().next().is_some_and(char::is_uppercase) {
        add_reference(s.to_string(), refs);
    }
}

/// Adds a reference unless it is longer than [`MAX_FQN_BYTES`]: no building's fqn is that long, so it could not
/// resolve, and resolving it would copy it into every candidate tried.
fn add_reference(name: String, refs: &mut HashSet<String>) {
    if name.len() <= MAX_FQN_BYTES {
        refs.insert(name);
    }
}

/// McCabe cyclomatic complexity of a function body: 1 + `if`, `while`, `loop`, `for`, each `&&`/`||`, and k - 1 per
/// `match` with k arms. `else if` counts as another `if`; `?` does not. Closures and nested functions count towards
/// the enclosing function. Clamped to at least 1 because an empty `match` would make the sum go down.
pub fn complexity(tree: &Tree, body: usize) -> u32 {
    let mut cx: i32 = 1;
    for n in &tree.nodes[body + 1..tree.nodes[body].end as usize] {
        match n.kind {
            NodeKind::If | NodeKind::While | NodeKind::Loop | NodeKind::For | NodeKind::Arm | NodeKind::AndOr => cx += 1,
            NodeKind::Match => cx -= 1, // a match with k arms adds k - 1 paths
            _ => {}
        }
    }
    cx.max(1) as u32
}

// ------------------------------------------------------------------ linking

/// Combines all Rust files: attaches impl blocks to their types and resolves references to dependencies.
///
/// 1. Collect types (first occurrence of an fqn wins, in file order), the scope each was declared in, all impls and
///    every crate name seen.
/// 2. Attach each impl to the type its `Self` resolves to. Impls of types that are not project types (`impl Display
///    for Vec<Foo>`, `impl ForeignTrait for u32`) go to the module building of the module they are written in, which
///    is created if that module has no free functions. The impl's methods and LOC are added to the target.
/// 3. Resolve, against all buildings including those created in step 2, each impl's trait and body references (they
///    become its target's dependencies) and each type's own references in its scope.
pub fn link(files: &[Arc<ParsedFile>]) -> Linked {
    // Every file of this analysis is parsed by now: the next analysis reads the manifests again.
    crate_cache().lock().unwrap().clear();
    let mut order: Vec<String> = vec![];
    let mut by_fqn: HashMap<String, TypeInfo> = HashMap::new();
    // type / module-building fqn → scope it was declared in (first occurrence wins)
    let mut scopes: HashMap<String, &Scope> = HashMap::new();
    // (impl, its scope, its district scope)
    let mut impls: Vec<(&Impl, &Scope, &Scope)> = vec![];
    let mut crate_names = HashSet::new();
    for f in files {
        for t in &f.types {
            if !by_fqn.contains_key(&t.fqn) {
                order.push(t.fqn.clone());
                by_fqn.insert(t.fqn.clone(), t.clone());
            }
        }
        if let AnyContext::Rust(c) = &f.context {
            for (fqn, &s) in &c.scope_of {
                scopes.entry(fqn.clone()).or_insert(&c.scopes[s]);
            }
            for imp in &c.impls {
                impls.push((imp, &c.scopes[imp.scope], &c.scopes[imp.district]));
            }
            for s in &c.scopes {
                crate_names.insert(s.krate().to_string());
            }
        }
    }
    let known: HashSet<String> = by_fqn.keys().cloned().collect();
    let resolver = Resolver { known: &known, crate_names: &crate_names };

    // Attach impl blocks to their types; impls of foreign types go to the module's function building.
    let mut attached: HashMap<String, Vec<&Impl>> = HashMap::new();
    let mut extra_deps: HashMap<String, HashSet<String>> = HashMap::new();
    let mut new_buildings: Vec<(String, &Scope)> = vec![];
    // impl → its target, in impl order
    let mut targets: Vec<String> = Vec::with_capacity(impls.len());
    // crate → build module of the module buildings created for impls
    let mut module_of_crate: HashMap<String, String> = HashMap::new();
    for (imp, scope, district) in &impls {
        let mut target = resolver.resolve_type(&imp.ty, scope);
        // An impl never belongs to a module building, even if the name resolves to one.
        if target.as_ref().is_some_and(|t| by_fqn[t].kind == "module") {
            target = None;
        }
        let target = match target {
            Some(t) => t,
            None => {
                let id = format!("{}::{}", scope.path.join("::"), FUNCTIONS);
                if !by_fqn.contains_key(&id) {
                    // The build module (crate name) is copied from any existing type of the same crate. District and
                    // name are those `visit_items` would have given it (a cfg(test) module keeps its parent's district).
                    // Looked up once per crate rather than once per new module building.
                    let module = module_of_crate
                        .entry(scope.krate().to_string())
                        .or_insert_with(|| {
                            by_fqn
                                .values()
                                .find(|t| t.fqn.starts_with(&format!("{}::", scope.krate())))
                                .map(|t| t.module.clone())
                                .unwrap_or_default()
                        })
                        .clone();
                    by_fqn.insert(id.clone(), TypeInfo {
                        language: Language::Rust,
                        fqn: id.clone(),
                        name: imp.label.clone(),
                        package: district.path[1..].join("::"),
                        module,
                        kind: "module",
                        file: imp.file.clone(),
                        line: imp.line,
                        end_line: imp.line,
                        loc: 0,
                        noa: 0,
                        test: imp.test,
                        methods: vec![],
                        references: HashSet::new(),
                        explicit_imports: Default::default(),
                        wildcard_imports: Default::default(),
                    });
                    order.push(id.clone());
                    new_buildings.push((id.clone(), scope));
                }
                id
            }
        };
        attached.entry(target.clone()).or_default().push(imp);
        targets.push(target);
    }
    for (id, scope) in new_buildings {
        scopes.insert(id, scope);
    }
    // References resolve only now, against every building including the module buildings just created, so that the
    // result does not depend on the order of the impls (the editions used to differ here).
    let known: HashSet<String> = by_fqn.keys().cloned().collect();
    let resolver = Resolver { known: &known, crate_names: &crate_names };
    for ((imp, scope, _), target) in impls.iter().zip(targets) {
        let deps = extra_deps.entry(target).or_default();
        if let Some(tr) = &imp.tr {
            if let Some(t) = resolver.resolve_type(tr, scope) {
                deps.insert(t);
            }
        }
        for r in &imp.refs {
            if let Some(t) = resolver.resolve(r, scope) {
                deps.insert(t);
            }
        }
    }

    let mut types = Vec::with_capacity(order.len());
    let mut dependencies = HashMap::new();
    for fqn in order {
        let mut t = by_fqn.remove(&fqn).unwrap();
        for imp in attached.get(&fqn).into_iter().flatten() {
            t.methods.extend(imp.methods.iter().cloned());
            t.loc += imp.loc;
        }
        let mut deps = extra_deps.remove(&fqn).unwrap_or_default();
        if let Some(scope) = scopes.get(&fqn) {
            for r in &t.references {
                if let Some(target) = resolver.resolve(r, scope) {
                    deps.insert(target);
                }
            }
        }
        deps.remove(&fqn);
        dependencies.insert(fqn, deps);
        types.push(t);
    }
    Linked { types, dependencies }
}

/// Resolves names as written in a scope to fqns of project types (or of a module's function building).
///
/// `known` is the set of all fqns; `crate_names` tells a path starting with a project crate (`shop::cart::Cart`)
/// from a relative one. Names from the standard library and external crates resolve to nothing.
struct Resolver<'a> {
    known: &'a HashSet<String>,
    crate_names: &'a HashSet<String>,
}

impl Resolver<'_> {
    /// Like [`Resolver::resolve`], but only types: a function building is never the target of an `impl`.
    fn resolve_type(&self, written: &str, scope: &Scope) -> Option<String> {
        self.resolve(written, scope).filter(|r| !r.ends_with(&format!("::{FUNCTIONS}")))
    }

    /// Candidates, in order of preference:
    /// - the first segment is a `use` alias: the imported path plus the remaining segments (only this one);
    /// - the path starts with `crate`, `self`, `super` or a project crate's name: that absolute path (only this one);
    /// - otherwise: relative to the current module, then under each glob import.
    ///
    /// Each candidate matches either a known fqn, or, if its last segment is lower-case, a function in a module that
    /// has a function building (`net::http::get` → `net::http::{fn}`), or else an associated item or variant of a
    /// known type (`Cart::new`, `Shape::Circle` → the type). `Self` is skipped (it refers to the type itself), and so
    /// is a qualified path (`<T as Trait>::Item`), whose types `collect_refs` collects separately. Prelude and
    /// external names resolve to `None`.
    fn resolve(&self, written: &str, scope: &Scope) -> Option<String> {
        if written.is_empty() || written == "Self" {
            return None;
        }
        // Drop generic arguments: `Foo<Bar>` → `Foo`.
        let cleaned = written.split('<').next().unwrap_or(written);
        let segs: Vec<String> = cleaned.split("::").map(str::to_string).collect();
        let first = segs[0].as_str();
        if first.is_empty() {
            return None;
        }
        let mut candidates: Vec<Vec<String>> = vec![];
        if let Some(used) = scope.uses.get(first) {
            let mut c = self.absolute(used, scope);
            c.extend_from_slice(&segs[1..]);
            candidates.push(c);
        } else if matches!(first, "crate" | "self" | "super") || self.crate_names.contains(first) {
            candidates.push(self.absolute(&segs, scope));
        } else {
            let mut c = scope.path.clone();
            c.extend_from_slice(&segs);
            candidates.push(c);
            for glob in &scope.globs {
                let mut c = self.absolute(glob, scope);
                c.extend_from_slice(&segs);
                candidates.push(c);
            }
        }
        for c in candidates {
            let fqn = c.join("::");
            if self.known.contains(&fqn) {
                return Some(fqn);
            }
            // a function path like net::http::get → the function building of net::http
            if c.len() > 1 {
                let parent = c[..c.len() - 1].join("::");
                let functions = format!("{parent}::{FUNCTIONS}");
                if self.known.contains(&functions) && c.last().unwrap().chars().next().is_some_and(char::is_lowercase) {
                    return Some(functions);
                }
                // an associated item or variant of a type (Cart::new, Shape::Circle) → the type
                if self.known.contains(&parent) {
                    return Some(parent);
                }
            }
        }
        None
    }

    /// Turns a use path into an absolute path that starts with a crate name.
    fn absolute(&self, path: &[String], scope: &Scope) -> Vec<String> {
        let Some(first) = path.first() else { return vec![] };
        match first.as_str() {
            "crate" => std::iter::once(scope.krate().to_string()).chain(path[1..].iter().cloned()).collect(),
            "self" => scope.path.iter().chain(path[1..].iter()).cloned().collect(),
            "super" => {
                let mut base = scope.path.clone();
                let mut i = 0;
                while i < path.len() && path[i] == "super" {
                    // Never climb above the crate root.
                    if base.len() > 1 {
                        base.pop();
                    }
                    i += 1;
                }
                base.extend_from_slice(&path[i..]);
                base
            }
            f if self.crate_names.contains(f) => path.to_vec(),
            _ => scope.path.iter().chain(path.iter()).cloned().collect(), // 2018-edition relative path
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::analysis::SourceAnalyzer;
    use crate::model::{Analysis, TypeInfo};
    use std::path::Path;

    fn write(root: &Path, rel: &str, code: &str) {
        let f = root.join(rel);
        std::fs::create_dir_all(f.parent().unwrap()).unwrap();
        std::fs::write(f, code).unwrap();
    }

    fn analyze(root: &Path) -> Analysis {
        SourceAnalyzer::new().analyze(&std::fs::canonicalize(root).unwrap(), true)
    }

    fn get<'a>(a: &'a Analysis, fqn: &str) -> &'a TypeInfo {
        a.types.iter().find(|t| t.fqn == fqn).unwrap_or_else(|| panic!("missing {fqn}"))
    }

    fn depends(a: &Analysis, from: &str, to: &str) -> bool {
        a.dependencies[from].iter().any(|d| d == to)
    }

    #[test]
    fn qualified_paths_resolve_the_types_inside_the_brackets() {
        let dir = tempfile::tempdir().unwrap();
        let r = dir.path();
        write(r, "Cargo.toml", "[package]\nname = \"q\"\n");
        write(r, "src/lib.rs", "pub fn helper() {}\npub trait Tr { type Item; }\npub struct S;\nimpl S { fn f<T>(x: <T as Tr>::Item) {} }\n");
        assert!(depends(&analyze(r), "q::S", "q::Tr"));
    }

    /// Module buildings created for impls (module b has only an impl) are resolvable like any other building: from a
    /// type declared before them, and from impls attached before or after them. The editions used to differ here.
    #[test]
    fn module_buildings_created_for_impls_are_resolvable() {
        let dir = tempfile::tempdir().unwrap();
        let r = dir.path();
        write(r, "Cargo.toml", "[package]\nname = \"k\"\n");
        write(r, "src/lib.rs", "pub trait Tr {}\npub mod a { impl crate::Tr for u8 { fn f() { crate::b::g(); } } }\npub mod b { impl crate::Tr for u16 { fn f() { crate::b::g(); } } }\npub struct User { f: b::helper }\npub mod c { impl crate::Tr for u32 { fn f() { crate::b::g(); } } }\n");
        let a = analyze(r);
        assert!(depends(&a, "k::User", "k::b::{fn}"));
        assert!(depends(&a, "k::c::{fn}", "k::b::{fn}"));
        assert!(depends(&a, "k::a::{fn}", "k::b::{fn}"));
    }

    #[test]
    fn finds_the_package_name_after_array_values() {
        let dir = tempfile::tempdir().unwrap();
        let r = dir.path();
        write(r, "Cargo.toml", "[package]\nauthors = [\"a\"]\nname = \"named\"\n[dependencies]\nname = \"no\"\n");
        write(r, "src/lib.rs", "pub struct A;\n");
        get(&analyze(r), "named::A");
    }

    #[test]
    fn paths_into_a_type_depend_on_the_type() {
        let dir = tempfile::tempdir().unwrap();
        let r = dir.path();
        write(r, "Cargo.toml", "[package]\nname = \"p\"\n");
        write(r, "src/lib.rs", "pub mod cart;\npub enum Shape { Circle }\n");
        write(r, "src/cart.rs", "pub struct Cart;\nimpl Cart { pub fn new() -> Self { Cart } }\npub struct Shop;\nimpl Shop { fn open(&self) { Cart::new(); let _s = crate::Shape::Circle; } }\n");
        let a = analyze(r);
        assert!(depends(&a, "p::cart::Shop", "p::cart::Cart"), "Cart::new()");
        assert!(depends(&a, "p::cart::Shop", "p::Shape"), "Shape::Circle");
    }

    #[test]
    fn module_buildings_for_impls_are_named_and_placed_like_other_module_buildings() {
        let dir = tempfile::tempdir().unwrap();
        let r = dir.path();
        write(r, "Cargo.toml", "[package]\nname = \"m\"\n");
        write(r, "src/lib.rs", "pub mod cart;\npub trait Probe { fn probe(&self); }\n");
        write(r, "src/cart.rs", "pub struct Cart;\n#[cfg(test)]\nmod tests {\n    impl crate::Probe for u32 { fn probe(&self) {} }\n}\n");
        let a = analyze(r);
        let tests = get(&a, "m::cart::tests::{fn}");
        assert_eq!((tests.package.as_str(), tests.name.as_str(), tests.test), ("cart", "mod tests", true));
        assert!(depends(&a, "m::cart::tests::{fn}", "m::Probe"));
    }

    #[test]
    fn sees_cargo_manifest_changes_on_reanalysis() {
        let dir = tempfile::tempdir().unwrap();
        let r = dir.path();
        let root = std::fs::canonicalize(r).unwrap();
        let analyzer = SourceAnalyzer::new();
        write(r, "Cargo.toml", "[package]\nname = \"before\"\n");
        write(r, "src/lib.rs", "pub struct A;\n");
        get(&analyzer.analyze(&root, true), "before::A");
        write(r, "Cargo.toml", "[package]\nname = \"after\"\n");
        write(r, "src/lib.rs", "pub struct A; // changed, so the file is parsed again\n");
        get(&analyzer.analyze(&root, true), "after::A");
    }
}
