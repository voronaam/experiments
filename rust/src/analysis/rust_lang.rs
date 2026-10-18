//! Rust support (tree-sitter). Crates and modules are districts; structs, enums, unions and traits are buildings
//! whose methods are collected from every `impl` block in the crate; each module's free functions and constants
//! form a "module" building. `use` paths are resolved so dependencies connect across files.
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

use super::syntax::{first_line, last_line, named_children, operator, walk, Src};
use super::text::{count_loc, lines};
use super::{with_parser, Context as AnyContext, Linked, ParsedFile};
use crate::model::{Language, MethodInfo, TypeInfo};
use regex::Regex;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use tree_sitter::Node;

/// Last segment of a module building's fqn. Braces cannot appear in a Rust identifier, so the id never clashes with
/// a real type name.
pub const FUNCTIONS: &str = "{fn}";

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
    let Some(tree) = with_parser("rust", || tree_sitter_rust::LANGUAGE.into(), |p| p.parse(code, None)) else {
        return ParsedFile { types: vec![], context: AnyContext::None, failed: true };
    };
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
        src: Src { code },
        lines: lines(code),
        file,
        module: krate.name.clone(),
        types: vec![],
        ctx: Context::default(),
    };
    let scope = st.add_scope(Scope::new(absolute));
    let file_name = file.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let root_node = tree.root_node();
    st.visit_items(root_node, scope, scope, &file_name, test);
    ParsedFile { types: st.types, context: AnyContext::Rust(st.ctx), failed: root_node.has_error() }
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

/// The `name` in the `[package]` table of `dir/Cargo.toml`, with `-` replaced by `_`.
///
/// A regex instead of a TOML parser: from a line starting with `[package]`, match lazily up to the first line that
/// sets `name`, skipping only lines that do not start with `[` (after indentation), so the match stays inside the
/// table. A `[` elsewhere on a line (e.g. `authors = ["…"]` listed first) does not stop it.
fn read_crate_name(dir: &Path) -> Option<String> {
    static PACKAGE_NAME: OnceLock<Regex> = OnceLock::new();
    let re = PACKAGE_NAME.get_or_init(|| {
        Regex::new(r#"(?m)^\[package\][^\n]*\n(?:[ \t\r]*(?:[^\[\s][^\n]*)?\n)*?[ \t]*name\s*=\s*"([^"]+)""#).unwrap()
    });
    let manifest = std::fs::read_to_string(dir.join("Cargo.toml")).ok()?;
    re.captures(&manifest).map(|c| c[1].replace('-', "_"))
}

/// Everything collected while walking one file.
struct FileState<'a> {
    src: Src<'a>,
    lines: Vec<&'a str>,
    file: &'a Path,
    module: String,
    types: Vec<TypeInfo>,
    ctx: Context,
}

impl<'a> FileState<'a> {
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

    fn loc(&self, n: Node) -> u32 {
        count_loc(&self.lines, first_line(n), last_line(n))
    }

    /// `district`: scope whose module path decides the district; differs from `scope` inside `#[cfg(test)]`
    /// modules, which stay in their parent's district instead of adding one.
    ///
    /// Visits the items of a file or inline module. Types become buildings, impls are recorded for linking, `use`
    /// declarations fill the scope, inline modules recurse with a new scope. Free functions, consts and statics are
    /// gathered into one module building named `label` (the file name, or `mod x` for an inline module). `mod x;`
    /// without a body is skipped: that file is parsed on its own. `test` marks everything in this container as test
    /// code.
    fn visit_items(&mut self, container: Node<'a>, scope: usize, district: usize, label: &str, test: bool) {
        let mut functions = vec![];
        let mut function_refs = HashSet::new();
        let mut constants = 0;
        let mut module_loc = 0;
        // Line span of the free items, reported as the module building's position.
        let mut first = u32::MAX;
        let mut last = 0;
        let mut cfg_test = false;
        for item in named_children(container) {
            let kind = item.kind();
            // Outer attributes are sibling nodes preceding the item they apply to, so remember `#[cfg(test)]` until
            // the next item. Only the exact form `cfg(test)` (spaces removed) is recognised, not e.g.
            // `cfg(all(test, …))`.
            if kind == "attribute_item" {
                let attr: String = self.src.text(item).chars().filter(|c| *c != ' ').collect();
                cfg_test |= attr.contains("cfg(test)");
                continue;
            }
            let is_test = test || cfg_test;
            match kind {
                "use_declaration" => {
                    if let Some(arg) = item.child_by_field_name("argument") {
                        self.expand_use(arg, &[], scope);
                    }
                }
                "struct_item" | "union_item" => {
                    let k = if kind == "union_item" { "union" } else { "struct" };
                    let noa = field_count(item.child_by_field_name("body"));
                    self.add_type(item, scope, district, k, noa, vec![], is_test);
                }
                "enum_item" => {
                    let variants = item
                        .child_by_field_name("body")
                        .map(|b| named_children(b).filter(|v| v.kind() == "enum_variant").count())
                        .unwrap_or(0) as u32;
                    self.add_type(item, scope, district, "enum", variants, vec![], is_test);
                }
                "trait_item" => {
                    let methods = self.functions_in(item.child_by_field_name("body"));
                    self.add_type(item, scope, district, "trait", 0, methods, is_test);
                }
                "impl_item" => self.add_impl(item, scope, district, label, is_test),
                "function_item" | "const_item" | "static_item" => {
                    if kind == "function_item" {
                        functions.push(self.function(item));
                    } else {
                        constants += 1;
                    }
                    collect_refs(&self.src, item, &mut function_refs);
                    module_loc += self.loc(item);
                    first = first.min(first_line(item));
                    last = last.max(last_line(item));
                }
                "mod_item" => {
                    if let Some(body) = item.child_by_field_name("body") {
                        let mut child = self.ctx.scopes[scope].path.clone();
                        let name = self.src.field_text(item, "name").to_string();
                        child.push(name.clone());
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
                explicit_imports: vec![],
                wildcard_imports: vec![],
            });
        }
    }

    #[allow(clippy::too_many_arguments)]
    /// Adds a struct, union, enum or trait building. Its methods (other than a trait's own) arrive in [`link`].
    fn add_type(&mut self, item: Node, scope: usize, district: usize, kind: &'static str, noa: u32,
                methods: Vec<MethodInfo>, test: bool) {
        let name = self.src.field_text(item, "name").to_string();
        let mut refs = HashSet::new();
        collect_refs(&self.src, item, &mut refs);
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
            line: first_line(item),
            end_line: last_line(item),
            loc: self.loc(item),
            noa,
            test,
            methods,
            references: refs,
            explicit_imports: vec![],
            wildcard_imports: vec![],
        });
    }

    /// Records an `impl` block. References come from its body only (the header with type, trait and generics is not
    /// scanned); the trait is resolved separately in [`link`].
    fn add_impl(&mut self, item: Node, scope: usize, district: usize, label: &str, test: bool) {
        let body = item.child_by_field_name("body");
        let mut refs = HashSet::new();
        collect_refs(&self.src, body.unwrap_or(item), &mut refs);
        let imp = Impl {
            scope,
            district,
            label: label.to_string(),
            ty: self.type_name(item.child_by_field_name("type")),
            tr: item.child_by_field_name("trait").map(|t| self.type_name(Some(t))),
            methods: self.functions_in(body),
            refs,
            loc: self.loc(item),
            file: self.file.to_path_buf(),
            line: first_line(item),
            test,
        };
        self.ctx.impls.push(imp);
    }

    /// `S<T>` → `S`; `crate::m::Other` stays a path; references are unwrapped.
    fn type_name(&self, n: Option<Node>) -> String {
        let Some(n) = n else { return String::new() };
        match n.kind() {
            "generic_type" | "reference_type" | "pointer_type" => self.type_name(n.child_by_field_name("type")),
            _ => self.src.text(n).to_string(),
        }
    }

    /// Functions of an impl or trait body; `function_signature_item` is a trait method without a default body.
    fn functions_in(&self, body: Option<Node>) -> Vec<MethodInfo> {
        let Some(body) = body else { return vec![] };
        named_children(body)
            .filter(|m| matches!(m.kind(), "function_item" | "function_signature_item"))
            .map(|m| self.function(m))
            .collect()
    }

    /// Metrics of one function. `self` is a `self_parameter` node and is not counted as a parameter.
    fn function(&self, f: Node) -> MethodInfo {
        let parameters = f
            .child_by_field_name("parameters")
            .map(|p| named_children(p).filter(|c| c.kind() == "parameter").count())
            .unwrap_or(0) as u32;
        MethodInfo {
            name: self.src.field_text(f, "name").to_string(),
            line: first_line(f),
            loc: self.loc(f),
            complexity: f.child_by_field_name("body").map(complexity).unwrap_or(1),
            parameters,
        }
    }

    /// Flattens a use tree into alias → path entries and glob imports.
    ///
    /// `prefix` is the path accumulated from enclosing `a::b::{…}` groups. For example
    /// `use crate::net::{self, http::Client as C, tls::*};` yields `net → [crate, net]`,
    /// `C → [crate, net, http, Client]` and the glob `[crate, net, tls]`.
    fn expand_use(&mut self, n: Node, prefix: &[String], scope: usize) {
        match n.kind() {
            // A single name, at the top level or inside a `{…}` list. `metavariable` is `$crate` in macro_rules.
            "identifier" | "crate" | "self" | "super" | "metavariable" => {
                let name = self.src.text(n).to_string();
                if name == "self" && !prefix.is_empty() {
                    self.ctx.scopes[scope].uses.insert(prefix.last().unwrap().clone(), prefix.to_vec()); // a::{self}
                } else {
                    let mut path = prefix.to_vec();
                    path.push(name.clone());
                    self.ctx.scopes[scope].uses.insert(name, path);
                }
            }
            "scoped_identifier" => {
                let mut path = prefix.to_vec();
                path.extend(self.segments(Some(n)));
                let mut alias = path.last().cloned().unwrap_or_default();
                if alias == "self" {
                    path.pop();
                    alias = path.last().cloned().unwrap_or_else(|| "self".into());
                }
                self.ctx.scopes[scope].uses.insert(alias, path);
            }
            "use_as_clause" => {
                let alias = self.src.field_text(n, "alias").to_string();
                // `use Trait as _` only brings trait methods into scope; there is no name to resolve.
                if alias != "_" {
                    let mut path = prefix.to_vec();
                    path.extend(self.segments(n.child_by_field_name("path")));
                    self.ctx.scopes[scope].uses.insert(alias, path);
                }
            }
            "scoped_use_list" => {
                let mut p = prefix.to_vec();
                p.extend(self.segments(n.child_by_field_name("path")));
                if let Some(list) = n.child_by_field_name("list") {
                    self.expand_use(list, &p, scope);
                }
            }
            "use_list" => {
                for c in named_children(n) {
                    self.expand_use(c, prefix, scope);
                }
            }
            "use_wildcard" => {
                let mut path = prefix.to_vec();
                path.extend(self.segments(n.named_child(0)));
                self.ctx.scopes[scope].globs.push(path);
            }
            _ => {}
        }
    }

    /// Splits a (possibly scoped) path node into its segments: `a::b::c` → [a, b, c].
    fn segments(&self, n: Option<Node>) -> Vec<String> {
        let Some(n) = n else { return vec![] };
        if matches!(n.kind(), "scoped_identifier" | "scoped_type_identifier") {
            let mut s = self.segments(n.child_by_field_name("path"));
            s.push(self.src.field_text(n, "name").to_string());
            s
        } else {
            vec![self.src.text(n).to_string()]
        }
    }
}

/// Attributes of a struct or union: named fields, or the fields of a tuple struct. Unit structs have no body.
fn field_count(body: Option<Node>) -> u32 {
    let Some(body) = body else { return 0 };
    if body.kind() == "field_declaration_list" {
        return named_children(body).filter(|f| f.kind() == "field_declaration").count() as u32;
    }
    // tuple struct (ordered_field_declaration_list): the field types are children under the field name "type",
    // mixed with commas and visibility modifiers, so count by field name
    (0..body.child_count() as u32).filter(|&i| body.field_name_for_child(i) == Some("type")).count() as u32
}

/// Names under `node` that may refer to project items: type identifiers, whole paths (`net::http::get`,
/// `Order::new`; whitespace removed, not descended into) and capitalised identifiers (e.g. a tuple-struct constructor
/// call `Price(3)`). Lower-case single identifiers are skipped as they are almost always locals. For a qualified path
/// (`<T as Trait>::Item`) the text names nothing resolvable, so the types inside the brackets are collected instead.
fn collect_refs(src: &Src, node: Node, refs: &mut HashSet<String>) {
    walk(node, |n| match n.kind() {
        "type_identifier" => {
            refs.insert(src.text(n).to_string());
            true
        }
        "scoped_type_identifier" | "scoped_identifier" => {
            match n.child_by_field_name("path") {
                Some(p) if p.kind() == "bracketed_type" => collect_refs(src, p, refs),
                _ => {
                    refs.insert(src.text(n).chars().filter(|c| !c.is_whitespace()).collect());
                }
            }
            false
        }
        "identifier" => {
            let s = src.text(n);
            if s.chars().next().is_some_and(char::is_uppercase) {
                refs.insert(s.to_string());
            }
            true
        }
        _ => true,
    });
}

/// McCabe cyclomatic complexity of a function body: 1 + `if`, `while`, `loop`, `for`, each `&&`/`||`, and k - 1 per
/// `match` with k arms. `else if` is a nested if_expression and counts; `?` does not. Closures count towards the
/// enclosing function. Clamped to at least 1 because an empty `match` would make the sum go down.
pub fn complexity(body: Node) -> u32 {
    let mut cx: i32 = 1;
    walk(body, |n| {
        match n.kind() {
            "if_expression" | "while_expression" | "loop_expression" | "for_expression" | "match_arm" => cx += 1,
            "match_expression" => cx -= 1, // a match with k arms adds k - 1 paths
            "binary_expression" if matches!(operator(n), "&&" | "||") => cx += 1,
            _ => {}
        }
        true
    });
    cx.max(1) as u32
}

// ------------------------------------------------------------------ linking

/// Combines all Rust files: attaches impl blocks to their types and resolves references to dependencies.
///
/// 1. Collect types (first occurrence of an fqn wins, in file order), the scope each was declared in, all impls and
///    every crate name seen.
/// 2. Attach each impl to the type its `Self` resolves to. Impls of types that are not project types (`impl Display
///    for Vec<Foo>`, `impl ForeignTrait for u32`) go to the module building of the module they are written in, which
///    is created if that module has no free functions. The impl's methods and LOC are added to the target; its trait
///    and body references become the target's dependencies.
/// 3. Resolve each type's own references in its scope.
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
                    let module = by_fqn
                        .values()
                        .find(|t| t.fqn.starts_with(&format!("{}::", scope.krate())))
                        .map(|t| t.module.clone())
                        .unwrap_or_default();
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
                        explicit_imports: vec![],
                        wildcard_imports: vec![],
                    });
                    order.push(id.clone());
                    new_buildings.push((id.clone(), scope));
                }
                id
            }
        };
        attached.entry(target.clone()).or_default().push(imp);
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
    for (id, scope) in new_buildings {
        scopes.insert(id, scope);
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
