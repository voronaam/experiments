//! TypeScript support (tree-sitter). Directories are districts; classes, interfaces, enums and type aliases are
//! buildings; each file's top-level functions and constants form one "module" building named after the file.
//!
//! # Identifiers
//! - A type's fqn is `<path relative to root>:<name>`, e.g. `src/ui/App.tsx:App`; members of a namespace are named
//!   `Ns.Inner`. If the same name is declared twice in a file (declaration merging, e.g. an interface and a class
//!   called `Foo`), the second one gets `#<kind>` appended so that fqns stay unique; further declarations with the
//!   same name and kind get `#<kind>2`, `#<kind>3`, … in source order.
//! - The module building's fqn is the bare path, e.g. `src/ui/App.tsx`.
//! - The nearest directory with a `package.json` is the build module (npm project); the package (district) is the
//!   file's directory relative to that project, using `/` as separator.
//!
//! # Linking
//! TypeScript has no global namespace: a name is either declared in the same file or imported. So parsing records,
//! per file, the top-level names it declares ([`Context::names`]) and its imports, and [`link`] resolves each
//! reference by looking in that order, following an import to the file it names (see [`resolve_source`]).

use super::syntax::{children, first_line, last_line, named_children, operator, walk, Src};
use super::text::{count_loc, in_test_directory, lines};
use super::{with_parser, Context as AnyContext, Linked, ParsedFile};
use crate::model::{slashes, Language, MethodInfo, TypeInfo};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};
use tree_sitter::Node;

/// Node kinds of function values: a `const f = () => …` declarator or a class field holding one is treated as a
/// function / method rather than an attribute.
const FUNCTION_VALUES: [&str; 4] = ["arrow_function", "function_expression", "function", "generator_function"];
/// Node kinds that add one to cyclomatic complexity. `for_in_statement` covers both `for…in` and `for…of`;
/// `switch_case` is a `case` clause (the `default` clause is a separate `switch_default` node and does not count).
const DECISIONS: [&str; 8] = [
    "if_statement", "for_statement", "for_in_statement", "while_statement", "do_statement", "switch_case",
    "catch_clause", "ternary_expression",
];

/// TypeScript sources by file name. Declaration files (`.d.ts`, …) are skipped: they only describe code that lives
/// elsewhere (often generated or third-party).
pub fn accepts(name: &str) -> bool {
    let source = [".ts", ".tsx", ".mts", ".cts"].iter().any(|e| name.ends_with(e));
    let declaration = [".d.ts", ".d.mts", ".d.cts"].iter().any(|e| name.ends_with(e));
    source && !declaration
}

/// `cart.test.ts`, `cart.spec.tsx` and the like (Jest/Vitest naming).
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

/// What the linker needs to know about a file. The file's own types are in [`ParsedFile::types`].
#[derive(Debug, Default)]
pub struct Context {
    /// Path relative to the root, e.g. `src/ui/App.tsx`.
    pub path: String,
    pub imports: Vec<Import>,
    /// Top-level declared name → fqn of the building that holds it. Types map to their own building, functions and
    /// constants to the file's module building. The first declaration of a name wins.
    pub names: HashMap<String, String>,
    /// The declared name (a key of `names`) that `export default` refers to, if any.
    pub default_export: Option<String>,
}

/// Parses one TypeScript file into its types, its module building (if it has any top-level code) and the linker
/// context.
pub fn parse(root: &Path, file: &Path, code: &str) -> ParsedFile {
    // `.tsx` needs the TSX grammar: in plain TypeScript `<T>x` is a type assertion, in TSX it is JSX.
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
    let package = package_of(&project, relative);
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

/// Everything collected while walking one file.
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
    /// fqns handed out so far in this file, to detect duplicate names (see [`FileState::id`]).
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

    /// Visits the statements of a program or namespace body. `prefix` is the namespace path (`"Ns."`), "" at the top.
    ///
    /// Unwraps the statement forms that wrap a declaration: `export …` (a `declaration` field, or for
    /// `export default <expr>` a `value` field), `declare …` (ambient_declaration) and the expression_statement the
    /// grammar produces for `namespace X { … }`. Other expression statements are top-level code for the module
    /// building. Re-exports (`export { a } from "./b"`) have neither field and are ignored.
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
                // tree-sitter-typescript parses `namespace X { }` as an expression (internal_module) wrapped in an
                // expression_statement, so such statements have to be checked for a namespace first.
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
    ///
    /// Classes, interfaces, enums and type aliases become buildings; functions and variables go into the module
    /// building; namespaces are walked recursively with a longer prefix and do not become buildings themselves.
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
                // Every named child of the enum body except comments is a member.
                let variants = decl.child_by_field_name("body").map(count_named).unwrap_or(0);
                self.add_type(&name, "enum", decl, variants, vec![]);
                Some(name)
            }
            "type_alias_declaration" => {
                let name = format!("{prefix}{}", self.src.field_text(decl, "name"));
                let mut methods = vec![];
                // Only an object-literal type (`type P = { x: number }`) has members to count; unions etc. have none.
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
                self.module_loc += self.loc(decl);
                Some(name)
            }
            // `const`/`let` (lexical_declaration) and `var` (variable_declaration), possibly several declarators. The
            // statement's lines count once for the module building, however many declarators it has.
            "lexical_declaration" | "variable_declaration" => {
                let mut last = None;
                let mut free = false;
                for d in named_children(decl).filter(|d| d.kind() == "variable_declarator") {
                    let simple = self.src.field_text(d, "name").to_string();
                    let name = format!("{prefix}{simple}");
                    match d.child_by_field_name("value") {
                        Some(v) if FUNCTION_VALUES.contains(&v.kind()) => {
                            self.add_function(&name, v, decl);
                            free = true;
                        }
                        // `const Foo = class { … }`: a class building named after the variable.
                        Some(v) if v.kind() == "class" => {
                            self.add_class(v, prefix, Some(&simple));
                        }
                        // Any other value is a module-level attribute.
                        _ => {
                            self.module_attributes += 1;
                            self.module_names.push(name.clone());
                            collect_refs(&self.src, decl, &mut self.module_refs);
                            free = true;
                        }
                    }
                    last = Some(name);
                }
                if free {
                    self.module_loc += self.loc(decl);
                }
                last
            }
            // `namespace X {}` (internal_module) and `module X {}` / `declare module "x" {}` (module). Quotes are
            // stripped from string module names.
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

    /// `export default <expression>`: an identifier refers to a name declared elsewhere in the file; an anonymous
    /// function becomes a module-building function named "default"; a class expression becomes a building named
    /// after its own name or "default". Other expressions are not tracked.
    fn export_default_expression(&mut self, value: Node<'a>) {
        match value.kind() {
            "identifier" => self.default_export = Some(self.src.text(value).to_string()),
            "arrow_function" | "function_expression" | "function" => {
                self.add_function("default", value, value);
                self.module_loc += self.loc(value);
                self.default_export = Some("default".into());
            }
            "class" => self.default_export = self.add_class(value, "", Some("default")),
            _ => {}
        }
    }

    /// Records the bindings of an `import` statement. Side-effect imports (`import "./x"`) have no import_clause
    /// and add nothing.
    ///
    /// ```text
    /// import D from "m"            → D = default of m
    /// import * as N from "m"       → N = * (the whole module)
    /// import { a, b as c } from "m" → a = a, c = b
    /// ```
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

    /// Allocates the fqn for a type building and registers the name for the linker (first declaration wins).
    /// Repeated names get `#kind`, then `#kind2`, `#kind3`, … (see the module comment).
    fn id(&mut self, name: &str, kind: &str) -> String {
        let base = format!("{}:{}", self.path, name);
        let mut id = base.clone();
        // declaration merging: an interface and a class with the same name
        let mut n = 1;
        while !self.used_ids.insert(id.clone()) {
            id = if n == 1 { format!("{base}#{kind}") } else { format!("{base}#{kind}{n}") };
            n += 1;
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

    /// Adds a class building. `fallback` names a class expression without a name of its own (the variable it is
    /// assigned to, or "default"); a nameless class without fallback is skipped.
    ///
    /// Methods: method definitions, abstract and overload signatures, and fields holding a function value (arrow
    /// function properties). Attributes: other fields, plus constructor parameter properties.
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

    /// Members of an interface body or object type: properties are attributes; method, call (`(x): T`) and
    /// construct (`new (x): T`) signatures are methods. Returns the attribute count.
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
    ///
    /// Parameters are the named children of the `parameters` list, comments excepted; an arrow function with a single
    /// unparenthesised parameter (`x => …`) has a `parameter` field instead, which counts as one.
    fn method(&self, node: Node, name: Option<Node>, body: Option<Node>) -> MethodInfo {
        let name = name.map(|n| self.src.text(n).to_string()).unwrap_or_else(|| "(anonymous)".into());
        let parameters = match node.child_by_field_name("parameters") {
            Some(p) => count_named(p),
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

    /// Adds a top-level function to the module building. `function` gives the method's own metrics; `statement`
    /// (the whole declaration) is what the module building's references are taken from. The caller adds the
    /// statement's lines to the module building's LOC, once per statement.
    fn add_function(&mut self, name: &str, function: Node, statement: Node) {
        let mut m = self.method(function, None, Some(function));
        m.name = name.to_string();
        self.functions.push(m);
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
        // The label is the first string argument, quotes included, shortened to 48 characters.
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

    /// Emits the file's module building, if the file has any top-level functions, variables or code. Its names are
    /// registered only now, after all types, so a type and a function with the same name resolve to the type.
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

/// Constructor parameters with an accessibility modifier (`private x: T`) or `readonly` declare fields.
fn parameter_properties(constructor: Node) -> u32 {
    let Some(params) = constructor.child_by_field_name("parameters") else { return 0 };
    named_children(params)
        .filter(|p| named_children(*p).any(|c| matches!(c.kind(), "accessibility_modifier" | "readonly")))
        .count() as u32
}

/// Named children except comments, which tree-sitter allows between the members of a list.
fn count_named(n: Node) -> u32 {
    named_children(n).filter(|c| c.kind() != "comment").count() as u32
}

/// Every identifier and type identifier under `node`. Most are locals or members; [`link`] keeps only those that
/// match a declared or imported name of the file. Property names (`obj.x`) are `property_identifier` nodes and are
/// not collected.
fn collect_refs(src: &Src, node: Node, refs: &mut HashSet<String>) {
    walk(node, |n| {
        if matches!(n.kind(), "type_identifier" | "identifier") {
            refs.insert(src.text(n).to_string());
        }
        true
    });
}

/// McCabe cyclomatic complexity: 1 + decision points ([`DECISIONS`] plus each `&&`, `||` and `??`). Nested
/// functions inside `body` count towards it.
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
///
/// The walk stops at `root`; without any package.json the result is "". Results are cached process-wide by absolute
/// directory (see [`project_cache`]), and every directory passed on the way gets the same answer, so a deep tree
/// costs one `is_file` per directory.
fn project_of(root: &Path, dir: Option<&Path>) -> String {
    let cache = project_cache();
    let mut visited = vec![];
    let mut project = String::new();
    let mut d = dir;
    while let Some(cur) = d {
        if !cur.starts_with(root) {
            break;
        }
        // The lock is not held across the file-system check, so concurrent workers may both compute a directory;
        // they store the same answer.
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

/// Directory → its project, for [`project_of`]. Cleared at the start of every [`link`], which re-derives the
/// projects, so a package.json added or removed while watching is noticed by the next analysis.
fn project_cache() -> &'static Mutex<HashMap<std::path::PathBuf, String>> {
    static CACHE: OnceLock<Mutex<HashMap<std::path::PathBuf, String>>> = OnceLock::new();
    CACHE.get_or_init(Default::default)
}

/// The package of a file: its directory relative to the npm project. `project` is an ancestor of the file's
/// directory (or the directory itself), so the directory starts with `project/` whenever the two differ.
fn package_of(project: &str, relative: &Path) -> String {
    let dir = relative.parent().map(slashes).unwrap_or_default();
    if project.is_empty() {
        dir
    } else if dir == project {
        String::new()
    } else {
        dir[project.len() + 1..].to_string()
    }
}

// ------------------------------------------------------------------ linking

/// Resolves every type's references: a name declared in the same file points at its building; an imported name is
/// followed to the file it comes from and then to the building exporting it. External packages are ignored.
///
/// Modules and packages are derived again here, with a fresh project cache: in watch mode an unchanged file keeps its
/// cached parse, which may predate a package.json added or removed since.
pub fn link(root: &Path, files: &[Arc<ParsedFile>]) -> Linked {
    project_cache().lock().unwrap().clear();
    // by_path: file path → its context. by_key: module key (see `keys`) → files with that key, in TypeScript's
    // resolution order (see `key_rank`). keys_by_last: last segment of a key → keys, an index for path-alias lookups.
    let mut by_path: HashMap<&str, &Context> = HashMap::new();
    let mut by_key: HashMap<String, Vec<String>> = HashMap::new();
    let mut keys_by_last: HashMap<String, Vec<String>> = HashMap::new();
    let mut types = vec![];
    for f in files {
        // The types of a file share its project; it is looked up from the first one's file.
        let mut project: Option<(String, String)> = None;
        for t in &f.types {
            let (module, package) = project.get_or_insert_with(|| {
                let relative = t.file.strip_prefix(root).unwrap_or(&t.file);
                let module = project_of(root, t.file.parent());
                let package = package_of(&module, relative);
                (module, package)
            });
            let mut t = t.clone();
            t.module = module.clone();
            t.package = package.clone();
            types.push(t);
        }
        if let AnyContext::TypeScript(c) = &f.context {
            by_path.insert(&c.path, c);
            for key in keys(&c.path) {
                let paths = by_key.entry(key.clone()).or_default();
                if paths.is_empty() {
                    let last = key.rsplit('/').next().unwrap_or("").to_string();
                    keys_by_last.entry(last).or_default().push(key.clone());
                }
                paths.push(c.path.clone());
            }
        }
    }
    for (key, paths) in by_key.iter_mut() {
        paths.sort_by_key(|p| key_rank(key, p));
    }
    let mut dependencies = HashMap::new();
    // (importing file, specifier) → resolved path, so each import is resolved once even if several types use it.
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
            // Named imports look up the export's name; default imports look up what the default export refers to.
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
/// A root-level `index.ts` also gets the empty key.
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

/// TypeScript's preference among the files sharing a module key: the file itself before a directory index, and `.ts`
/// before `.tsx`, `.mts` and `.cts`. So `./a` is `a.ts`, then `a.tsx`, …, then `a/index.ts`, `a/index.tsx`, …
fn key_rank(key: &str, path: &str) -> usize {
    let exts = [".ts", ".tsx", ".mts", ".cts"];
    let no_ext = strip_ext(path, &exts);
    let ext = exts.iter().position(|e| *e == &path[no_ext.len()..]).unwrap_or(0);
    if no_ext == key { ext } else { exts.len() + ext }
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
///
/// The extension in the specifier is ignored: ESM-style TypeScript imports name the compiled `.js` file
/// (`import "./a.js"` means `a.ts`). Then:
/// - relative specifiers (`./x`, `../x`, `.`, `..`) are joined to the importing file's directory and looked up as a
///   module key. A path that climbs above the root is unresolved. When several files share the key (`a.ts`,
///   `a.tsx` and `a/index.ts`), the one TypeScript would pick wins (see [`key_rank`]);
/// - path aliases (`@/x`, `~/x`, `#/x`) are matched by suffix without reading tsconfig `paths`: the unique key equal
///   to or ending in `/<tail>`, and from that key's files the one TypeScript would pick. No match or more than one
///   key gives "";
/// - anything else is an external package: a bare name (`react`), a scoped package (`@scope/pkg`) or a deep import
///   (`lodash/fp`).
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
    let Some(tail) = ["@/", "~/", "#/"].iter().find_map(|p| s.strip_prefix(p)) else {
        return String::new(); // a package such as "react", "@scope/pkg" or "lodash/fp"
    };
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

/// Resolves `.` and `..` segments and drops empty ones. `None` when `..` would leave the root.
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
