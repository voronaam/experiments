//! Rust support (tree-sitter). Crates and modules are districts; structs, enums, unions and traits are buildings
//! whose methods are collected from every `impl` block in the crate; each module's free functions and constants
//! form a "module" building. `use` paths are resolved so dependencies connect across files.

use super::syntax::{first_line, last_line, named_children, operator, walk, Src};
use super::text::{count_loc, lines};
use super::{with_parser, Context as AnyContext, Linked, ParsedFile};
use crate::model::{Language, MethodInfo, TypeInfo};
use regex::Regex;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use tree_sitter::Node;

pub const FUNCTIONS: &str = "{fn}";

/// Name-resolution scope of one module.
#[derive(Debug, Clone)]
pub struct Scope {
    /// Absolute module path, starting with the crate name.
    pub path: Vec<String>,
    /// alias → path as written in the `use` declaration
    pub uses: HashMap<String, Vec<String>>,
    /// paths imported with `::*`
    pub globs: Vec<Vec<String>>,
}

impl Scope {
    fn new(path: Vec<String>) -> Self {
        Scope { path, uses: HashMap::new(), globs: vec![] }
    }
    fn krate(&self) -> &str {
        &self.path[0]
    }
}

/// An `impl` block, attached to its type during linking.
#[derive(Debug)]
pub struct Impl {
    scope: usize,
    ty: String,
    tr: Option<String>,
    methods: Vec<MethodInfo>,
    refs: HashSet<String>,
    loc: u32,
    file: PathBuf,
    line: u32,
    test: bool,
}

#[derive(Debug, Default)]
pub struct Context {
    scopes: Vec<Scope>,
    /// type / module-building fqn → index into `scopes`
    scope_of: HashMap<String, usize>,
    impls: Vec<Impl>,
}

struct Crate {
    name: String,
    dir: PathBuf,
}

pub fn parse(root: &Path, file: &Path, code: &str) -> ParsedFile {
    let Some(tree) = with_parser("rust", || tree_sitter_rust::LANGUAGE.into(), |p| p.parse(code, None)) else {
        return ParsedFile { types: vec![], context: AnyContext::None, failed: true };
    };
    let krate = crate_of(root, file.parent().unwrap_or(root));
    let in_crate = file.strip_prefix(&krate.dir).unwrap_or(file);
    let source_root = source_root(&krate.dir, file.parent().unwrap_or(root));
    let module_path = module_path(file.strip_prefix(&source_root).unwrap_or(in_crate));
    let first = in_crate.components().next().map(|c| c.as_os_str().to_string_lossy().to_string());
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

/// `src/lib.rs` → [], `src/net/mod.rs` → [net], `src/net/http.rs` → [net, http].
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

fn crate_of(root: &Path, dir: &Path) -> Crate {
    static CRATES: OnceLock<Mutex<HashMap<PathBuf, Option<String>>>> = OnceLock::new();
    let cache = CRATES.get_or_init(Default::default);
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

fn read_crate_name(dir: &Path) -> Option<String> {
    static PACKAGE_NAME: OnceLock<Regex> = OnceLock::new();
    let re = PACKAGE_NAME.get_or_init(|| Regex::new(r#"(?ms)^\[package\][^\[]*?^\s*name\s*=\s*"([^"]+)""#).unwrap());
    let manifest = std::fs::read_to_string(dir.join("Cargo.toml")).ok()?;
    re.captures(&manifest).map(|c| c[1].replace('-', "_"))
}

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
    fn visit_items(&mut self, container: Node<'a>, scope: usize, district: usize, label: &str, test: bool) {
        let mut functions = vec![];
        let mut function_refs = HashSet::new();
        let mut constants = 0;
        let mut module_loc = 0;
        let mut first = u32::MAX;
        let mut last = 0;
        let mut cfg_test = false;
        for item in named_children(container) {
            let kind = item.kind();
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
                "impl_item" => self.add_impl(item, scope, is_test),
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
                        let child_district = if cfg_test { district } else { child_scope };
                        self.visit_items(body, child_scope, child_district, &format!("mod {name}"), is_test);
                    }
                }
                _ => {}
            }
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

    fn add_impl(&mut self, item: Node, scope: usize, test: bool) {
        let body = item.child_by_field_name("body");
        let mut refs = HashSet::new();
        collect_refs(&self.src, body.unwrap_or(item), &mut refs);
        let imp = Impl {
            scope,
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

    fn functions_in(&self, body: Option<Node>) -> Vec<MethodInfo> {
        let Some(body) = body else { return vec![] };
        named_children(body)
            .filter(|m| matches!(m.kind(), "function_item" | "function_signature_item"))
            .map(|m| self.function(m))
            .collect()
    }

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
    fn expand_use(&mut self, n: Node, prefix: &[String], scope: usize) {
        match n.kind() {
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

fn field_count(body: Option<Node>) -> u32 {
    let Some(body) = body else { return 0 };
    if body.kind() == "field_declaration_list" {
        return named_children(body).filter(|f| f.kind() == "field_declaration").count() as u32;
    }
    // tuple struct: count the "type" fields
    (0..body.child_count() as u32).filter(|&i| body.field_name_for_child(i) == Some("type")).count() as u32
}

fn collect_refs(src: &Src, node: Node, refs: &mut HashSet<String>) {
    walk(node, |n| match n.kind() {
        "type_identifier" => {
            refs.insert(src.text(n).to_string());
            true
        }
        "scoped_type_identifier" | "scoped_identifier" => {
            refs.insert(src.text(n).chars().filter(|c| !c.is_whitespace()).collect());
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

pub fn link(files: &[Arc<ParsedFile>]) -> Linked {
    let mut order: Vec<String> = vec![];
    let mut by_fqn: HashMap<String, TypeInfo> = HashMap::new();
    // (file index, scope index) → scope, plus fqn → scope
    let mut scopes: HashMap<String, &Scope> = HashMap::new();
    let mut impls: Vec<(&Impl, &Scope)> = vec![];
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
                impls.push((imp, &c.scopes[imp.scope]));
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
    for (imp, scope) in &impls {
        let mut target = resolver.resolve_type(&imp.ty, scope);
        if target.as_ref().is_some_and(|t| by_fqn[t].kind == "module") {
            target = None;
        }
        let target = match target {
            Some(t) => t,
            None => {
                let id = format!("{}::{}", scope.path.join("::"), FUNCTIONS);
                if !by_fqn.contains_key(&id) {
                    let module = by_fqn
                        .values()
                        .find(|t| t.fqn.starts_with(&format!("{}::", scope.krate())))
                        .map(|t| t.module.clone())
                        .unwrap_or_default();
                    by_fqn.insert(id.clone(), TypeInfo {
                        language: Language::Rust,
                        fqn: id.clone(),
                        name: imp.file.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
                        package: scope.path[1..].join("::"),
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
struct Resolver<'a> {
    known: &'a HashSet<String>,
    crate_names: &'a HashSet<String>,
}

impl Resolver<'_> {
    fn resolve_type(&self, written: &str, scope: &Scope) -> Option<String> {
        self.resolve(written, scope).filter(|r| !r.ends_with(&format!("::{FUNCTIONS}")))
    }

    fn resolve(&self, written: &str, scope: &Scope) -> Option<String> {
        if written.is_empty() || written == "Self" {
            return None;
        }
        let cleaned = written.split('<').next().unwrap_or(written);
        let segs: Vec<String> = cleaned.split("::").map(str::to_string).collect();
        let first = segs[0].as_str();
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
                let functions = format!("{}::{}", c[..c.len() - 1].join("::"), FUNCTIONS);
                if self.known.contains(&functions) && c.last().unwrap().chars().next().is_some_and(char::is_lowercase) {
                    return Some(functions);
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
