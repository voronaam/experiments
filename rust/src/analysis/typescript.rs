//! TypeScript support (tree-sitter). Directories are districts; classes, interfaces, enums and type aliases are
//! buildings; each file's top-level functions and constants form one "module" building named after the file.

use super::syntax::{children, first_line, last_line, named_children, operator, walk, Src};
use super::text::{count_loc, in_test_directory, lines};
use super::{with_parser, Context as AnyContext, Linked, ParsedFile};
use crate::model::{slashes, Language, MethodInfo, TypeInfo};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};
use tree_sitter::Node;

const FUNCTION_VALUES: [&str; 4] = ["arrow_function", "function_expression", "function", "generator_function"];
const DECISIONS: [&str; 8] = [
    "if_statement", "for_statement", "for_in_statement", "while_statement", "do_statement", "switch_case",
    "catch_clause", "ternary_expression",
];

pub fn accepts(name: &str) -> bool {
    let source = [".ts", ".tsx", ".mts", ".cts"].iter().any(|e| name.ends_with(e));
    let declaration = [".d.ts", ".d.mts", ".d.cts"].iter().any(|e| name.ends_with(e));
    source && !declaration
}

fn is_test_file(name: &str) -> bool {
    let stem = strip_ext(name, &[".ts", ".tsx", ".mts", ".cts"]);
    stem != name && (stem.ends_with(".test") || stem.ends_with(".spec"))
}

/// An import binding: `local` names `imported` ("default", "*" or an export name) of `source`.
#[derive(Debug, Clone)]
pub struct Import {
    pub local: String,
    pub source: String,
    pub imported: String,
}

/// What the linker needs to know about a file.
#[derive(Debug, Default)]
pub struct Context {
    /// Path relative to the root, e.g. `src/ui/App.tsx`.
    pub path: String,
    pub imports: Vec<Import>,
    /// Top-level declared name → fqn of the building that holds it.
    pub names: HashMap<String, String>,
    pub default_export: Option<String>,
}

pub fn parse(root: &Path, file: &Path, code: &str) -> ParsedFile {
    let tsx = file.extension().is_some_and(|e| e == "tsx");
    let tree = if tsx {
        with_parser("tsx", || tree_sitter_typescript::LANGUAGE_TSX.into(), |p| p.parse(code, None))
    } else {
        with_parser("typescript", || tree_sitter_typescript::LANGUAGE_TYPESCRIPT.into(), |p| p.parse(code, None))
    };
    let Some(tree) = tree else {
        return ParsedFile { types: vec![], context: AnyContext::None, failed: true };
    };
    let src = Src { code };
    let lines = lines(code);
    let relative = file.strip_prefix(root).unwrap_or(file);
    let path = slashes(relative);
    let project = project_of(root, file.parent());
    let dir = relative.parent().map(slashes).unwrap_or_default();
    let package = if project.is_empty() {
        dir
    } else if dir == project {
        String::new()
    } else {
        dir[project.len() + 1..].to_string()
    };
    let file_name = file.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let mut st = FileState {
        src,
        lines,
        file,
        test: in_test_directory(relative) || is_test_file(&file_name),
        path,
        package,
        module: project,
        types: vec![],
        imports: vec![],
        names: HashMap::new(),
        used_ids: HashSet::new(),
        default_export: None,
        functions: vec![],
        module_refs: HashSet::new(),
        module_names: vec![],
        module_attributes: 0,
        module_loc: 0,
    };
    let root_node = tree.root_node();
    st.visit_statements(root_node, "");
    st.finish_module(&file_name);
    let ctx = Context { path: st.path.clone(), imports: st.imports, names: st.names, default_export: st.default_export };
    ParsedFile { types: st.types, context: AnyContext::TypeScript(ctx), failed: root_node.has_error() }
}

struct FileState<'a> {
    src: Src<'a>,
    lines: Vec<&'a str>,
    file: &'a Path,
    path: String,
    package: String,
    module: String,
    test: bool,
    types: Vec<TypeInfo>,
    imports: Vec<Import>,
    names: HashMap<String, String>,
    used_ids: HashSet<String>,
    default_export: Option<String>,
    // the file's "module" building: free functions and top-level constants
    functions: Vec<MethodInfo>,
    module_refs: HashSet<String>,
    module_names: Vec<String>,
    module_attributes: u32,
    module_loc: u32,
}

impl<'a> FileState<'a> {
    fn loc(&self, n: Node) -> u32 {
        count_loc(&self.lines, first_line(n), last_line(n))
    }

    fn visit_statements(&mut self, container: Node<'a>, prefix: &str) {
        for child in named_children(container) {
            let mut decl = child;
            let mut is_default = false;
            match child.kind() {
                "import_statement" => {
                    self.add_import(child);
                    continue;
                }
                "export_statement" => {
                    is_default = children(child).any(|c| c.kind() == "default");
                    match child.child_by_field_name("declaration") {
                        Some(d) => decl = d,
                        None => {
                            if let (Some(value), true) = (child.child_by_field_name("value"), is_default) {
                                self.export_default_expression(value);
                            }
                            continue;
                        }
                    }
                }
                "ambient_declaration" | "expression_statement" => {
                    let Some(first) = child.named_child(0) else { continue };
                    decl = first;
                    if child.kind() == "expression_statement" && decl.kind() != "internal_module" {
                        self.add_statement(child, decl);
                        continue;
                    }
                }
                _ => {}
            }
            let declared = self.declaration(decl, prefix);
            if is_default && declared.is_some() {
                self.default_export = declared;
            }
        }
    }

    /// Handles one declaration; returns the declared name (for default exports).
    fn declaration(&mut self, decl: Node<'a>, prefix: &str) -> Option<String> {
        match decl.kind() {
            "class_declaration" | "abstract_class_declaration" | "class" => self.add_class(decl, prefix, None),
            "interface_declaration" => {
                let name = format!("{prefix}{}", self.src.field_text(decl, "name"));
                let mut methods = vec![];
                let attributes = self.count_members(decl.child_by_field_name("body"), &mut methods);
                self.add_type(&name, "interface", decl, attributes, methods);
                Some(name)
            }
            "enum_declaration" => {
                let name = format!("{prefix}{}", self.src.field_text(decl, "name"));
                let variants = decl.child_by_field_name("body").map(|b| b.named_child_count()).unwrap_or(0) as u32;
                self.add_type(&name, "enum", decl, variants, vec![]);
                Some(name)
            }
            "type_alias_declaration" => {
                let name = format!("{prefix}{}", self.src.field_text(decl, "name"));
                let mut methods = vec![];
                let attributes = match decl.child_by_field_name("value") {
                    Some(v) if v.kind() == "object_type" => self.count_members(Some(v), &mut methods),
                    _ => 0,
                };
                self.add_type(&name, "type", decl, attributes, methods);
                Some(name)
            }
            "function_declaration" | "generator_function_declaration" => {
                let name = format!("{prefix}{}", self.src.field_text(decl, "name"));
                self.add_function(&name, decl, decl);
                Some(name)
            }
            "lexical_declaration" | "variable_declaration" => {
                let mut last = None;
                for d in named_children(decl).filter(|d| d.kind() == "variable_declarator") {
                    let simple = self.src.field_text(d, "name").to_string();
                    let name = format!("{prefix}{simple}");
                    match d.child_by_field_name("value") {
                        Some(v) if FUNCTION_VALUES.contains(&v.kind()) => self.add_function(&name, v, decl),
                        Some(v) if v.kind() == "class" => {
                            self.add_class(v, prefix, Some(&simple));
                        }
                        _ => {
                            self.module_attributes += 1;
                            self.module_loc += self.loc(decl);
                            self.module_names.push(name.clone());
                            collect_refs(&self.src, decl, &mut self.module_refs);
                        }
                    }
                    last = Some(name);
                }
                last
            }
            "internal_module" | "module" => {
                let name = self.src.field_text(decl, "name").replace(['\'', '"'], "");
                if let Some(body) = decl.child_by_field_name("body") {
                    self.visit_statements(body, &format!("{prefix}{name}."));
                }
                None
            }
            _ => None,
        }
    }

    fn export_default_expression(&mut self, value: Node<'a>) {
        match value.kind() {
            "identifier" => self.default_export = Some(self.src.text(value).to_string()),
            "arrow_function" | "function_expression" | "function" => {
                self.add_function("default", value, value);
                self.default_export = Some("default".into());
            }
            "class" => self.default_export = self.add_class(value, "", Some("default")),
            _ => {}
        }
    }

    fn add_import(&mut self, stmt: Node) {
        let Some(source) = stmt.child_by_field_name("source") else { return };
        let from = self.src.text(source).trim_matches(|c| c == '\'' || c == '"' || c == '`').to_string();
        for clause in named_children(stmt).filter(|c| c.kind() == "import_clause") {
            for part in named_children(clause) {
                match part.kind() {
                    "identifier" => self.imports.push(Import {
                        local: self.src.text(part).into(),
                        source: from.clone(),
                        imported: "default".into(),
                    }),
                    "namespace_import" => {
                        for id in named_children(part) {
                            self.imports.push(Import { local: self.src.text(id).into(), source: from.clone(), imported: "*".into() });
                        }
                    }
                    "named_imports" => {
                        for spec in named_children(part).filter(|s| s.kind() == "import_specifier") {
                            let name = self.src.field_text(spec, "name").to_string();
                            let local = spec.child_by_field_name("alias").map(|a| self.src.text(a).to_string()).unwrap_or(name.clone());
                            self.imports.push(Import { local, source: from.clone(), imported: name });
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    fn id(&mut self, name: &str, kind: &str) -> String {
        let mut id = format!("{}:{}", self.path, name);
        if !self.used_ids.insert(id.clone()) {
            id = format!("{id}#{kind}"); // declaration merging: an interface and a class with the same name
            self.used_ids.insert(id.clone());
        }
        self.names.entry(name.to_string()).or_insert_with(|| id.clone());
        id
    }

    fn add_type(&mut self, name: &str, kind: &'static str, decl: Node, noa: u32, methods: Vec<MethodInfo>) {
        let mut refs = HashSet::new();
        collect_refs(&self.src, decl, &mut refs);
        let begin = first_line(decl);
        let end = last_line(decl);
        let fqn = self.id(name, kind);
        self.types.push(TypeInfo {
            language: Language::TypeScript,
            fqn,
            name: name.to_string(),
            package: self.package.clone(),
            module: self.module.clone(),
            kind,
            file: self.file.to_path_buf(),
            line: begin,
            end_line: end,
            loc: count_loc(&self.lines, begin, end),
            noa,
            test: self.test,
            methods,
            references: refs,
            explicit_imports: vec![],
            wildcard_imports: vec![],
        });
    }

    fn add_class(&mut self, decl: Node<'a>, prefix: &str, fallback: Option<&str>) -> Option<String> {
        let simple = match decl.child_by_field_name("name") {
            Some(n) => self.src.text(n),
            None => fallback?,
        };
        let name = format!("{prefix}{simple}");
        let mut methods = vec![];
        let mut attributes = 0;
        if let Some(body) = decl.child_by_field_name("body") {
            for member in named_children(body) {
                match member.kind() {
                    "method_definition" => {
                        methods.push(self.method(member, member.child_by_field_name("name"), Some(member)));
                        if self.src.field_text(member, "name") == "constructor" {
                            attributes += parameter_properties(member);
                        }
                    }
                    "abstract_method_signature" | "method_signature" => {
                        methods.push(self.method(member, member.child_by_field_name("name"), None))
                    }
                    "public_field_definition" => match member.child_by_field_name("value") {
                        Some(v) if FUNCTION_VALUES.contains(&v.kind()) => {
                            methods.push(self.method(v, member.child_by_field_name("name"), Some(member)))
                        }
                        _ => attributes += 1,
                    },
                    _ => {}
                }
            }
        }
        let kind = if decl.kind() == "abstract_class_declaration" { "abstract" } else { "class" };
        self.add_type(&name, kind, decl, attributes, methods);
        Some(name)
    }

    fn count_members(&self, body: Option<Node>, methods: &mut Vec<MethodInfo>) -> u32 {
        let Some(body) = body else { return 0 };
        let mut attributes = 0;
        for m in named_children(body) {
            match m.kind() {
                "property_signature" => attributes += 1,
                "method_signature" | "call_signature" | "construct_signature" => {
                    methods.push(self.method(m, m.child_by_field_name("name"), None))
                }
                _ => {}
            }
        }
        attributes
    }

    /// `body`: node whose complexity is measured (None for signatures without a body).
    fn method(&self, node: Node, name: Option<Node>, body: Option<Node>) -> MethodInfo {
        let name = name.map(|n| self.src.text(n).to_string()).unwrap_or_else(|| "(anonymous)".into());
        let parameters = match node.child_by_field_name("parameters") {
            Some(p) => p.named_child_count() as u32,
            None => node.child_by_field_name("parameter").is_some() as u32,
        };
        MethodInfo {
            name,
            line: first_line(node),
            loc: self.loc(node),
            complexity: body.map(complexity).unwrap_or(1),
            parameters,
        }
    }

    fn add_function(&mut self, name: &str, function: Node, statement: Node) {
        let mut m = self.method(function, None, Some(function));
        m.name = name.to_string();
        self.functions.push(m);
        self.module_loc += self.loc(statement);
        self.module_names.push(name.to_string());
        collect_refs(&self.src, statement, &mut self.module_refs);
    }

    /// Top-level code: `describe("cart", () => …)` or `app.get("/", handler)` becomes a method named after the
    /// call, so test suites and route tables show up; anything else just adds lines and references.
    fn add_statement(&mut self, statement: Node, expression: Node) {
        self.module_loc += self.loc(statement);
        collect_refs(&self.src, statement, &mut self.module_refs);
        if expression.kind() != "call_expression" {
            return;
        }
        let Some(args) = expression.child_by_field_name("arguments") else { return };
        let mut has_callback = false;
        let mut label: Option<&str> = None;
        for arg in named_children(args) {
            has_callback |= FUNCTION_VALUES.contains(&arg.kind());
            if label.is_none() && matches!(arg.kind(), "string" | "template_string") {
                label = Some(self.src.text(arg));
            }
        }
        if !has_callback {
            return;
        }
        let callee = self.src.field_text(expression, "function");
        let label = match label {
            None => String::new(),
            Some(l) if l.chars().count() > 48 => format!("{}…", l.chars().take(47).collect::<String>()),
            Some(l) => l.to_string(),
        };
        self.functions.push(MethodInfo {
            name: format!("{callee}({label})"),
            line: first_line(statement),
            loc: self.loc(statement),
            complexity: complexity(args),
            parameters: 0,
        });
    }

    fn finish_module(&mut self, file_name: &str) {
        if self.functions.is_empty() && self.module_attributes == 0 && self.module_loc == 0 {
            return;
        }
        let id = self.path.clone();
        self.used_ids.insert(id.clone());
        for n in std::mem::take(&mut self.module_names) {
            self.names.entry(n).or_insert_with(|| id.clone());
        }
        self.types.push(TypeInfo {
            language: Language::TypeScript,
            fqn: id,
            name: file_name.to_string(),
            package: self.package.clone(),
            module: self.module.clone(),
            kind: "module",
            file: self.file.to_path_buf(),
            line: 1,
            end_line: self.lines.len() as u32,
            loc: self.module_loc,
            noa: self.module_attributes,
            test: self.test,
            methods: std::mem::take(&mut self.functions),
            references: std::mem::take(&mut self.module_refs),
            explicit_imports: vec![],
            wildcard_imports: vec![],
        });
    }
}

/// Constructor parameters with an accessibility modifier (`private x: T`) declare fields.
fn parameter_properties(constructor: Node) -> u32 {
    let Some(params) = constructor.child_by_field_name("parameters") else { return 0 };
    named_children(params)
        .filter(|p| named_children(*p).any(|c| matches!(c.kind(), "accessibility_modifier" | "readonly")))
        .count() as u32
}

fn collect_refs(src: &Src, node: Node, refs: &mut HashSet<String>) {
    walk(node, |n| {
        if matches!(n.kind(), "type_identifier" | "identifier") {
            refs.insert(src.text(n).to_string());
        }
        true
    });
}

pub fn complexity(body: Node) -> u32 {
    let mut cx = 1;
    walk(body, |n| {
        if DECISIONS.contains(&n.kind()) {
            cx += 1;
        } else if n.kind() == "binary_expression" && matches!(operator(n), "&&" | "||" | "??") {
            cx += 1;
        }
        true
    });
    cx
}

/// Directory of the nearest package.json at or above `dir` (relative to root, "" for the root itself).
fn project_of(root: &Path, dir: Option<&Path>) -> String {
    static CACHE: OnceLock<Mutex<HashMap<std::path::PathBuf, String>>> = OnceLock::new();
    let cache = CACHE.get_or_init(Default::default);
    let mut visited = vec![];
    let mut project = String::new();
    let mut d = dir;
    while let Some(cur) = d {
        if !cur.starts_with(root) {
            break;
        }
        if let Some(hit) = cache.lock().unwrap().get(cur) {
            project = hit.clone();
            break;
        }
        visited.push(cur.to_path_buf());
        if cur.join("package.json").is_file() {
            project = if cur == root { String::new() } else { slashes(cur.strip_prefix(root).unwrap()) };
            break;
        }
        if cur == root {
            break;
        }
        d = cur.parent();
    }
    let mut c = cache.lock().unwrap();
    for v in visited {
        c.insert(v, project.clone());
    }
    project
}

// ------------------------------------------------------------------ linking

pub fn link(root: &Path, files: &[Arc<ParsedFile>]) -> Linked {
    let mut by_path: HashMap<&str, &Context> = HashMap::new();
    let mut by_key: HashMap<String, Vec<String>> = HashMap::new();
    let mut keys_by_last: HashMap<String, Vec<String>> = HashMap::new();
    let mut types = vec![];
    for f in files {
        types.extend(f.types.iter().cloned());
        if let AnyContext::TypeScript(c) = &f.context {
            by_path.insert(&c.path, c);
            for key in keys(&c.path) {
                by_key.entry(key.clone()).or_default().push(c.path.clone());
                let last = key.rsplit('/').next().unwrap_or("").to_string();
                keys_by_last.entry(last).or_default().push(key);
            }
        }
    }
    let mut dependencies = HashMap::new();
    let mut resolved: HashMap<(String, String), String> = HashMap::new();
    for t in &types {
        let rel = slashes(t.file.strip_prefix(root).unwrap_or(&t.file));
        let Some(ctx) = by_path.get(rel.as_str()) else { continue };
        let imports: HashMap<&str, &Import> = ctx.imports.iter().map(|i| (i.local.as_str(), i)).collect();
        let mut out = HashSet::new();
        for r in &t.references {
            if let Some(local) = ctx.names.get(r) {
                out.insert(local.clone());
                continue;
            }
            let Some(imp) = imports.get(r.as_str()) else { continue };
            let target = resolved
                .entry((ctx.path.clone(), imp.source.clone()))
                .or_insert_with(|| resolve_source(&ctx.path, &imp.source, &by_key, &keys_by_last))
                .clone();
            if target.is_empty() {
                continue;
            }
            let other = by_path[target.as_str()];
            let symbol = if imp.imported == "default" { other.default_export.clone() } else { Some(imp.imported.clone()) };
            let fqn = match symbol {
                Some(s) if imp.imported != "*" => other.names.get(&s).cloned(),
                _ => None,
            };
            out.insert(fqn.unwrap_or(target)); // whole-module imports point at the module building
        }
        out.remove(&t.fqn);
        dependencies.insert(t.fqn.clone(), out);
    }
    Linked { types, dependencies }
}

/// Module keys a file can be imported by: `src/a/b` for `src/a/b.ts`, and `src/a` for an index file.
fn keys(path: &str) -> Vec<String> {
    let no_ext = strip_ext(path, &[".ts", ".tsx", ".mts", ".cts"]);
    let mut keys = vec![no_ext.to_string()];
    if no_ext == "index" {
        keys.push(String::new());
    } else if let Some(dir) = no_ext.strip_suffix("/index") {
        keys.push(dir.to_string());
    }
    keys
}

fn strip_ext<'s>(s: &'s str, exts: &[&str]) -> &'s str {
    for e in exts {
        if let Some(stripped) = s.strip_suffix(e) {
            return stripped;
        }
    }
    s
}

/// Resolves an import specifier to a project file path, or "" for external packages.
pub fn resolve_source(
    from: &str,
    spec: &str,
    by_key: &HashMap<String, Vec<String>>,
    keys_by_last: &HashMap<String, Vec<String>>,
) -> String {
    let s = strip_ext(spec, &[".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx", ".mts", ".cts"]);
    if s.starts_with("./") || s.starts_with("../") || s == "." || s == ".." {
        let dir = from.rfind('/').map(|i| &from[..i]).unwrap_or("");
        let joined = if dir.is_empty() { s.to_string() } else { format!("{dir}/{s}") };
        return normalize(&joined)
            .and_then(|k| by_key.get(&k))
            .map(|hits| hits[0].clone())
            .unwrap_or_default();
    }
    // Path aliases such as "@/components/Button" or "~/lib/db": match the file whose path ends that way.
    let tail = ["@/", "~/", "#/"].iter().find_map(|p| s.strip_prefix(p)).unwrap_or(s);
    if tail == s && !s.contains('/') {
        return String::new(); // a bare package name like "react"
    }
    let last = tail.rsplit('/').next().unwrap_or("");
    let mut found = String::new();
    for key in keys_by_last.get(last).into_iter().flatten() {
        if key == tail || key.ends_with(&format!("/{tail}")) {
            if !found.is_empty() {
                return String::new(); // ambiguous
            }
            found = by_key[key][0].clone();
        }
    }
    found
}

fn normalize(path: &str) -> Option<String> {
    let mut out: Vec<&str> = vec![];
    for part in path.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                out.pop()?;
            }
            p => out.push(p),
        }
    }
    Some(out.join("/"))
}
