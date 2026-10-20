//! TypeScript support (parsed with the in-house [`super::typescript_parse`]). Directories are districts; classes, interfaces, enums and type aliases are
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

use super::text::{count_loc, in_test_directory, lines, Lines, MAX_FQN_BYTES};
use super::typescript_parse::{self, Field, NodeKind, Tree, DECISION, REF};
use super::{Context as AnyContext, Linked, ParsedFile};
use crate::model::{slashes, Language, MethodInfo, TypeInfo};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};

/// Node kinds of function values: a `const f = () => …` declarator or a class field holding one is treated as a
/// function / method rather than an attribute.
const FUNCTION_VALUES: [NodeKind; 3] = [NodeKind::Arrow, NodeKind::FunctionExpr, NodeKind::GeneratorExpr];

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
    // `.tsx` enables JSX: in plain TypeScript `<T>x` is a type assertion, in TSX it is JSX.
    let tsx = file.extension().is_some_and(|e| e == "tsx");
    let tree = typescript_parse::parse(code, tsx);
    let lines = lines(code);
    let relative = file.strip_prefix(root).unwrap_or(file);
    let path = slashes(relative);
    let project = project_of(root, file.parent());
    let package = package_of(&project, relative);
    let file_name = file.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let mut st = FileState {
        tree: &tree,
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
        next_suffix: HashMap::new(),
        default_export: None,
        functions: vec![],
        module_refs: HashSet::new(),
        module_names: vec![],
        module_attributes: 0,
        module_loc: 0,
        skipped: false,
    };
    st.visit_statements(tree.root, "");
    st.finish_module(&file_name);
    let ctx = Context { path: st.path.clone(), imports: st.imports, names: st.names, default_export: st.default_export };
    ParsedFile { types: st.types, context: AnyContext::TypeScript(ctx), failed: tree.has_error || st.skipped }
}

/// Everything collected while walking one file.
struct FileState<'a> {
    tree: &'a Tree<'a>,
    lines: Lines,
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
    /// `path:name#kind` → the next suffix number [`FileState::id`] tries for it.
    next_suffix: HashMap<String, u32>,
    default_export: Option<String>,
    // the file's "module" building: free functions and top-level constants
    functions: Vec<MethodInfo>,
    module_refs: HashSet<String>,
    module_names: Vec<String>,
    module_attributes: u32,
    module_loc: u32,
    /// Set when a building or namespace was skipped for the length of its fqn (see [`FileState::fits`]).
    skipped: bool,
}

impl<'a> FileState<'a> {
    fn loc(&self, n: u32) -> u32 {
        count_loc(&self.lines, self.tree.first_line(n), self.tree.last_line(n))
    }

    /// Text of the child with the given field, or "".
    fn field_text(&self, n: u32, f: Field) -> &'a str {
        self.tree.field(n, f).map(|c| self.tree.text(c)).unwrap_or("")
    }

    /// Visits the statements of a program or namespace body. `prefix` is the namespace path (`"Ns."`), "" at the top.
    ///
    /// Unwraps the statement forms that wrap a declaration: `export …` (a `Decl` child, or for `export default <expr>`
    /// a `Value` child) and `declare …` (Ambient). Expression statements are top-level code for the module building.
    /// Re-exports (`export { a } from "./b"`) have neither child and are ignored, as is `export declare …` (an
    /// ambient declaration inside an export, which is not unwrapped).
    fn visit_statements(&mut self, container: u32, prefix: &str) {
        let tree = self.tree;
        for &child in tree.children(container) {
            let mut decl = child;
            let mut is_default = false;
            match tree.kind(child) {
                NodeKind::Import => {
                    self.add_import(child);
                    continue;
                }
                NodeKind::Export | NodeKind::ExportDefault => {
                    is_default = tree.kind(child) == NodeKind::ExportDefault;
                    match tree.field(child, Field::Decl) {
                        Some(d) => decl = d,
                        None => {
                            if let (Some(value), true) = (tree.field(child, Field::Value), is_default) {
                                self.export_default_expression(value);
                            }
                            continue;
                        }
                    }
                }
                NodeKind::Ambient => {
                    let Some(&first) = tree.children(child).first() else { continue };
                    decl = first;
                }
                NodeKind::ExprStmt => {
                    let Some(&first) = tree.children(child).first() else { continue };
                    self.add_statement(child, first);
                    continue;
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
    fn declaration(&mut self, decl: u32, prefix: &str) -> Option<String> {
        let tree = self.tree;
        match tree.kind(decl) {
            NodeKind::ClassDecl | NodeKind::AbstractClassDecl | NodeKind::ClassExpr => self.add_class(decl, prefix, None),
            NodeKind::Interface => {
                let name = format!("{prefix}{}", self.field_text(decl, Field::Name));
                let mut methods = vec![];
                let attributes = self.count_members(tree.field(decl, Field::Body), &mut methods);
                self.add_type(&name, "interface", decl, attributes, methods);
                Some(name)
            }
            NodeKind::Enum => {
                let name = format!("{prefix}{}", self.field_text(decl, Field::Name));
                let variants = tree.field(decl, Field::Body).map(|b| tree.children(b).len() as u32).unwrap_or(0);
                self.add_type(&name, "enum", decl, variants, vec![]);
                Some(name)
            }
            NodeKind::TypeAlias => {
                let name = format!("{prefix}{}", self.field_text(decl, Field::Name));
                let mut methods = vec![];
                // Only an object-literal type (`type P = { x: number }`) has members to count; unions etc. have none.
                let attributes = match tree.field(decl, Field::Value) {
                    Some(v) if tree.kind(v) == NodeKind::ObjectType => self.count_members(Some(v), &mut methods),
                    _ => 0,
                };
                self.add_type(&name, "type", decl, attributes, methods);
                Some(name)
            }
            NodeKind::FunctionDecl | NodeKind::GeneratorDecl => {
                let name = format!("{prefix}{}", self.field_text(decl, Field::Name));
                self.add_function(&name, decl, decl);
                self.module_loc += self.loc(decl);
                Some(name)
            }
            // `const`/`let`/`var`, possibly several declarators. The statement's lines count once for the module
            // building, however many declarators it has.
            NodeKind::VarDecl => {
                let mut last = None;
                let mut free = false;
                for &d in tree.children(decl).iter().filter(|&&d| tree.kind(d) == NodeKind::Declarator) {
                    let simple = self.field_text(d, Field::Name).to_string();
                    let name = format!("{prefix}{simple}");
                    match tree.field(d, Field::Value) {
                        Some(v) if FUNCTION_VALUES.contains(&tree.kind(v)) => {
                            self.add_function(&name, v, decl);
                            free = true;
                        }
                        // `const Foo = class { … }`: a class building named after the variable.
                        Some(v) if tree.kind(v) == NodeKind::ClassExpr => {
                            self.add_class(v, prefix, Some(&simple));
                        }
                        // Any other value is a module-level attribute.
                        _ => {
                            self.module_attributes += 1;
                            self.module_names.push(name.clone());
                            collect_refs(tree, decl, &mut self.module_refs);
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
            // `namespace X {}`, `module X {}` and `declare module "x" {}`. Quotes are stripped from string names.
            NodeKind::Module => {
                let name = self.field_text(decl, Field::Name).replace(['\'', '"'], "");
                // A namespace whose own name would make every fqn inside too long is skipped as a whole, so the long
                // prefix is not copied into each name declared in it.
                let inner = format!("{prefix}{name}.");
                if let Some(body) = tree.field(decl, Field::Body).filter(|_| self.fits(&inner)) {
                    self.visit_statements(body, &inner);
                }
                None
            }
            _ => None,
        }
    }

    /// `export default <expression>`: an identifier refers to a name declared elsewhere in the file; an anonymous
    /// function becomes a module-building function named "default"; a class expression becomes a building named
    /// after its own name or "default". Other expressions are not tracked.
    fn export_default_expression(&mut self, value: u32) {
        match self.tree.kind(value) {
            NodeKind::Ident => self.default_export = Some(self.tree.text(value).to_string()),
            NodeKind::Arrow | NodeKind::FunctionExpr => {
                self.add_function("default", value, value);
                self.module_loc += self.loc(value);
                self.default_export = Some("default".into());
            }
            NodeKind::ClassExpr => self.default_export = self.add_class(value, "", Some("default")),
            _ => {}
        }
    }

    /// Records the bindings of an `import` statement. Side-effect imports (`import "./x"`) and
    /// `import x = require("x")` add nothing.
    ///
    /// ```text
    /// import D from "m"            → D = default of m
    /// import * as N from "m"       → N = * (the whole module)
    /// import { a, b as c } from "m" → a = a, c = b
    /// ```
    fn add_import(&mut self, stmt: u32) {
        let tree = self.tree;
        let Some(source) = tree.field(stmt, Field::Source) else { return };
        let from = tree.text(source).trim_matches(|c| c == '\'' || c == '"' || c == '`').to_string();
        for &part in tree.children(stmt) {
            match tree.kind(part) {
                NodeKind::ImportDefault => self.imports.push(Import {
                    local: tree.text(part).into(),
                    source: from.clone(),
                    imported: "default".into(),
                }),
                NodeKind::ImportNamespace => {
                    for &id in tree.children(part) {
                        self.imports.push(Import { local: tree.text(id).into(), source: from.clone(), imported: "*".into() });
                    }
                }
                NodeKind::ImportSpecifier => {
                    let name = self.field_text(part, Field::Name).to_string();
                    let local = tree.field(part, Field::Alias).map(|a| tree.text(a).to_string()).unwrap_or(name.clone());
                    self.imports.push(Import { local, source: from.clone(), imported: name });
                }
                _ => {}
            }
        }
    }

    /// Allocates the fqn for a type building and registers the name for the linker (first declaration wins).
    /// Repeated names get `#kind`, then `#kind2`, `#kind3`, … (see the module comment).
    fn id(&mut self, name: &str, kind: &str) -> String {
        let base = format!("{}:{}", self.path, name);
        let mut id = base.clone();
        // declaration merging: an interface and a class with the same name
        if !self.used_ids.insert(id.clone()) {
            let key = format!("{base}#{kind}");
            // Resume after the last suffix handed out for this name and kind (all before it are taken): starting from
            // the first every time made many repeated declarations quadratic.
            let mut n = self.next_suffix.get(&key).copied().unwrap_or(1);
            loop {
                id = if n == 1 { key.clone() } else { format!("{key}{n}") };
                n += 1;
                if self.used_ids.insert(id.clone()) {
                    break;
                }
            }
            self.next_suffix.insert(key, n);
        }
        self.names.entry(name.to_string()).or_insert_with(|| id.clone());
        id
    }

    /// Whether a building named `name` (namespace prefix included) gets an fqn (`path:name`, before any `#kind`
    /// suffix) within [`MAX_FQN_BYTES`]. If not, it is skipped and the file counts as failed.
    fn fits(&mut self, name: &str) -> bool {
        let fits = self.path.len() + 1 + name.len() <= MAX_FQN_BYTES;
        self.skipped |= !fits;
        fits
    }

    fn add_type(&mut self, name: &str, kind: &'static str, decl: u32, noa: u32, methods: Vec<MethodInfo>) {
        if !self.fits(name) {
            return;
        }
        let mut refs = HashSet::new();
        collect_refs(self.tree, decl, &mut refs);
        let begin = self.tree.first_line(decl);
        let end = self.tree.last_line(decl);
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
            explicit_imports: Default::default(),
            wildcard_imports: Default::default(),
        });
    }

    /// Adds a class building. `fallback` names a class expression without a name of its own (the variable it is
    /// assigned to, or "default"); a nameless class without fallback is skipped.
    ///
    /// Methods: method definitions, abstract and overload signatures, and fields holding a function value (arrow
    /// function properties). Attributes: other fields, plus constructor parameter properties.
    fn add_class(&mut self, decl: u32, prefix: &str, fallback: Option<&str>) -> Option<String> {
        let tree = self.tree;
        let simple = match tree.field(decl, Field::Name) {
            Some(n) => tree.text(n),
            None => fallback?,
        };
        let name = format!("{prefix}{simple}");
        let mut methods = vec![];
        let mut attributes = 0;
        if let Some(body) = tree.field(decl, Field::Body) {
            for &member in tree.children(body) {
                match tree.kind(member) {
                    NodeKind::MethodDef => {
                        methods.push(self.method(member, tree.field(member, Field::Name), Some(member)));
                        if self.field_text(member, Field::Name) == "constructor" {
                            attributes += parameter_properties(tree, member);
                        }
                    }
                    NodeKind::MethodSig => methods.push(self.method(member, tree.field(member, Field::Name), None)),
                    NodeKind::FieldDef => match tree.field(member, Field::Value) {
                        Some(v) if FUNCTION_VALUES.contains(&tree.kind(v)) => {
                            methods.push(self.method(v, tree.field(member, Field::Name), Some(member)))
                        }
                        _ => attributes += 1,
                    },
                    _ => {}
                }
            }
        }
        let kind = if tree.kind(decl) == NodeKind::AbstractClassDecl { "abstract" } else { "class" };
        self.add_type(&name, kind, decl, attributes, methods);
        Some(name)
    }

    /// Members of an interface body or object type: properties are attributes; method, call (`(x): T`) and
    /// construct (`new (x): T`) signatures are methods. Returns the attribute count.
    fn count_members(&self, body: Option<u32>, methods: &mut Vec<MethodInfo>) -> u32 {
        let Some(body) = body else { return 0 };
        let tree = self.tree;
        let mut attributes = 0;
        for &m in tree.children(body) {
            match tree.kind(m) {
                NodeKind::PropertySig => attributes += 1,
                NodeKind::MethodSig | NodeKind::CallSig | NodeKind::ConstructSig => {
                    methods.push(self.method(m, tree.field(m, Field::Name), None))
                }
                _ => {}
            }
        }
        attributes
    }

    /// `body`: node whose complexity is measured (None for signatures without a body). Parameters are the entries
    /// of the node's parameter list (an arrow function's single unparenthesised parameter counts as one).
    fn method(&self, node: u32, name: Option<u32>, body: Option<u32>) -> MethodInfo {
        let tree = self.tree;
        let name = name.map(|n| tree.text(n).to_string()).unwrap_or_else(|| "(anonymous)".into());
        let parameters = tree.field(node, Field::Params).map(|p| tree.children(p).len() as u32).unwrap_or(0);
        MethodInfo {
            name,
            line: tree.first_line(node),
            loc: self.loc(node),
            complexity: body.map(|b| complexity(tree, b)).unwrap_or(1),
            parameters,
        }
    }

    /// Adds a top-level function to the module building. `function` gives the method's own metrics; `statement`
    /// (the whole declaration) is what the module building's references are taken from. The caller adds the
    /// statement's lines to the module building's LOC, once per statement.
    fn add_function(&mut self, name: &str, function: u32, statement: u32) {
        let mut m = self.method(function, None, Some(function));
        m.name = name.to_string();
        self.functions.push(m);
        self.module_names.push(name.to_string());
        collect_refs(self.tree, statement, &mut self.module_refs);
    }

    /// Top-level code: `describe("cart", () => …)` or `app.get("/", handler)` becomes a method named after the
    /// call, so test suites and route tables show up; anything else just adds lines and references.
    fn add_statement(&mut self, statement: u32, expression: u32) {
        let tree = self.tree;
        self.module_loc += self.loc(statement);
        collect_refs(tree, statement, &mut self.module_refs);
        if tree.kind(expression) != NodeKind::Call {
            return;
        }
        let Some(args) = tree.field(expression, Field::Args) else { return };
        if tree.kind(args) != NodeKind::Args {
            return; // a tagged template
        }
        let mut has_callback = false;
        let mut label: Option<&str> = None;
        for &arg in tree.children(args) {
            has_callback |= FUNCTION_VALUES.contains(&tree.kind(arg));
            if label.is_none() && matches!(tree.kind(arg), NodeKind::Str | NodeKind::Template) {
                label = Some(tree.text(arg));
            }
        }
        if !has_callback {
            return;
        }
        // The label is the first string argument, quotes included, shortened to 48 characters.
        let callee = tree.children(expression).first().map(|&c| tree.text(c)).unwrap_or("");
        let label = match label {
            None => String::new(),
            Some(l) if l.chars().count() > 48 => format!("{}…", l.chars().take(47).collect::<String>()),
            Some(l) => l.to_string(),
        };
        self.functions.push(MethodInfo {
            name: format!("{callee}({label})"),
            line: tree.first_line(statement),
            loc: self.loc(statement),
            complexity: complexity(tree, args),
            parameters: 0,
        });
    }

    /// Emits the file's module building, if the file has any top-level functions, variables or code. Its names are
    /// registered only now, after all types, so a type and a function with the same name resolve to the type.
    fn finish_module(&mut self, file_name: &str) {
        if self.functions.is_empty() && self.module_attributes == 0 && self.module_loc == 0 {
            return;
        }
        if self.path.len() > MAX_FQN_BYTES {
            self.skipped = true;
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
            explicit_imports: Default::default(),
            wildcard_imports: Default::default(),
        });
    }
}

/// Constructor parameters with an accessibility modifier (`private x: T`) declare fields. (`readonly` alone does
/// too in TypeScript, but is not counted, to keep the metric stable across CodeCity versions.)
fn parameter_properties(tree: &Tree, constructor: u32) -> u32 {
    let Some(params) = tree.field(constructor, Field::Params) else { return 0 };
    tree.children(params).iter().filter(|&&p| tree.kind(p) == NodeKind::ParamProperty).count() as u32
}

/// Every identifier the parser flagged as a possible reference ([`REF`]) under `node`. Most are locals or members;
/// [`link`] keeps only those that match a declared or imported name of the file. Property names (`obj.x`) are not
/// flagged.
fn collect_refs(tree: &Tree, node: u32, refs: &mut HashSet<String>) {
    for t in tree.token_range(node) {
        if tree.tokens[t].flags & REF != 0 {
            let text = tree.token_text(t);
            if !refs.contains(text) {
                refs.insert(text.to_string());
            }
        }
    }
}

/// McCabe cyclomatic complexity: 1 + decision points (`if`, loops, `case`, `catch`, `?:`, `&&`, `||`, `??`, as
/// flagged [`DECISION`] by the parser). Nested functions inside `node` count towards it.
pub fn complexity(tree: &Tree, node: u32) -> u32 {
    1 + tree.token_range(node).filter(|&t| tree.tokens[t].flags & DECISION != 0).count() as u32
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
        // Only a regular file counts, not a symbolic link (which could point outside the tree), as in the walk.
        if std::fs::symlink_metadata(cur.join("package.json")).is_ok_and(|m| m.is_file()) {
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
    // Per importing file: its imports by local name (the last import of a name wins), each with the file it resolves
    // to ("" if external), filled in when first needed. Kept per file rather than per building, so a file's imports
    // are indexed and resolved once, however many buildings it has.
    let mut imports_by_file: HashMap<&str, HashMap<&str, (&Import, Option<String>)>> = HashMap::new();
    for t in &types {
        let rel = slashes(t.file.strip_prefix(root).unwrap_or(&t.file));
        let Some(&ctx) = by_path.get(rel.as_str()) else { continue };
        let imports = imports_by_file
            .entry(ctx.path.as_str())
            .or_insert_with(|| ctx.imports.iter().map(|i| (i.local.as_str(), (i, None))).collect());
        let mut out = HashSet::new();
        for r in &t.references {
            if let Some(local) = ctx.names.get(r) {
                out.insert(local.clone());
                continue;
            }
            let Some((imp, resolved)) = imports.get_mut(r.as_str()) else { continue };
            let imp: &Import = imp;
            let target =
                resolved.get_or_insert_with(|| resolve_source(&ctx.path, &imp.source, &by_key, &keys_by_last)).clone();
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
