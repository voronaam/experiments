//! Java support (tree-sitter-java), mirroring the metrics the JavaParser-based Java version computes.
//!
//! Packages are districts and every class, interface, enum, record and annotation type (including member types) is a
//! building. The fqn is `package.Outer.Inner`, so it matches what Java itself calls the type.
//!
//! Parsing records, per type, the *raw* names it mentions (`List`, `Map.Entry`, `Util` from `Util.run()`) and the
//! file's imports. [`link`] then resolves those names with Java's own lookup rules (see [`resolve`]); anything that
//! does not resolve to a project type (JDK and library types) is dropped.

use super::syntax::{first_line, last_line, named_children, walk, Src};
use super::text::{count_loc, in_test_directory, lines};
use super::{with_parser, Context, Linked, ParsedFile};
use crate::model::{Language, MethodInfo, TypeInfo};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::Path;
use std::sync::Arc;
use tree_sitter::Node;

/// Node kinds that declare a type, at the top level or as members of another type's body.
const TYPE_DECLARATIONS: [&str; 5] = [
    "class_declaration",
    "interface_declaration",
    "enum_declaration",
    "record_declaration",
    "annotation_type_declaration",
];

/// Parses one `.java` file into its types (top-level and member types, depth-first in source order).
pub fn parse(root: &Path, file: &Path, code: &str) -> ParsedFile {
    let tree = with_parser("java", || tree_sitter_java::LANGUAGE.into(), |p| p.parse(code, None));
    let Some(tree) = tree else {
        return ParsedFile { types: vec![], context: Context::None, failed: true };
    };
    let src = Src { code };
    let program = tree.root_node();
    let lines = lines(code);
    let relative = file.strip_prefix(root).unwrap_or(file);
    let module = module_of(relative);
    let test = is_test(relative);

    // `explicit`: single-type imports, resolved first. `wildcard`: packages (or types) imported with `.*`.
    let mut package = String::new();
    let mut explicit = Vec::new();
    let mut wildcard = Vec::new();
    for n in named_children(program) {
        match n.kind() {
            "package_declaration" => {
                // Skip package annotations (allowed in package-info.java); the remaining child is the name.
                if let Some(name) = named_children(n).find(|c| c.kind() != "annotation" && c.kind() != "marker_annotation") {
                    package = strip_ws(src.text(name));
                }
            }
            "import_declaration" => {
                // `static` and `*` are tokens of the import_declaration; `*` is a named `asterisk` node.
                let is_static = super::syntax::children(n).any(|c| c.kind() == "static");
                let asterisk = named_children(n).any(|c| c.kind() == "asterisk");
                let Some(name) = named_children(n).find(|c| c.kind() == "scoped_identifier" || c.kind() == "identifier")
                else {
                    continue;
                };
                // Names may be written with whitespace or line breaks around the dots; normalise to `a.b.C`.
                let name = strip_ws(src.text(name));
                // Static imports name members, not types. What matters for dependencies is the class they come from:
                // `import static a.b.C.m;` and `import static a.b.C.*;` both record `a.b.C` as a single-type import.
                if is_static && !asterisk {
                    explicit.push(match name.rfind('.') {
                        Some(dot) => name[..dot].to_string(),
                        None => name,
                    });
                } else if asterisk {
                    if is_static { explicit.push(name) } else { wildcard.push(name) }
                } else {
                    explicit.push(name);
                }
            }
            _ => {}
        }
    }

    let mut types = Vec::new();
    let ctx = FileCtx { src: &src, lines: &lines, file, package: &package, module: &module, test, explicit: &explicit, wildcard: &wildcard };
    for n in named_children(program) {
        if TYPE_DECLARATIONS.contains(&n.kind()) {
            collect_type(&ctx, n, "", &mut types);
        }
    }
    ParsedFile { types, context: Context::None, failed: program.has_error() }
}

/// Per-file data shared by every type declared in the file.
struct FileCtx<'a> {
    src: &'a Src<'a>,
    lines: &'a [&'a str],
    file: &'a Path,
    package: &'a str,
    module: &'a str,
    test: bool,
    explicit: &'a [String],
    wildcard: &'a [String],
}

/// Adds a type and, recursively, the member types declared in its body (local classes are not types).
///
/// `outer` is the enclosing type's name relative to the package ("" at the top level), so a member type is named
/// `Outer.Inner`. Metrics:
/// - `noa` (attributes): one per declared variable in fields and interface constants (`int a, b;` is two), one per
///   enum constant and one per record component;
/// - methods: methods and constructors (initialiser blocks are not counted);
/// - `loc`: the whole declaration, including member types, so an outer type's LOC contains its inner types';
/// - references: collected from the whole declaration, member types included.
///
/// The type is pushed before its member types, so `out` is in pre-order.
fn collect_type(ctx: &FileCtx, decl: Node, outer: &str, out: &mut Vec<TypeInfo>) {
    let simple = ctx.src.field_text(decl, "name");
    let name = if outer.is_empty() { simple.to_string() } else { format!("{outer}.{simple}") };
    let fqn = if ctx.package.is_empty() { name.clone() } else { format!("{}.{}", ctx.package, name) };
    let body = decl.child_by_field_name("body");

    let mut methods = Vec::new();
    let mut noa = 0;
    let mut nested = Vec::new();
    // Record components (`record P(int x, int y)`) are the record's fields.
    if decl.kind() == "record_declaration" {
        if let Some(params) = decl.child_by_field_name("parameters") {
            noa += named_children(params).filter(|p| p.kind() == "formal_parameter").count() as u32;
        }
    }
    if let Some(body) = body {
        let mut members: Vec<Node> = named_children(body).collect();
        // enum bodies keep their non-constant members in a nested enum_body_declarations node
        if let Some(extra) = members.iter().position(|m| m.kind() == "enum_body_declarations") {
            let e = members.remove(extra);
            members.extend(named_children(e));
        }
        for m in members {
            match m.kind() {
                // constant_declaration is a field in an interface body.
                "field_declaration" | "constant_declaration" => {
                    noa += named_children(m).filter(|d| d.kind() == "variable_declarator").count() as u32;
                }
                "enum_constant" => noa += 1,
                "method_declaration" | "constructor_declaration" => methods.push(method(ctx, m)),
                k if TYPE_DECLARATIONS.contains(&k) => nested.push(m),
                _ => {}
            }
        }
    }
    let begin = first_line(decl);
    let end = last_line(decl);
    out.push(TypeInfo {
        language: Language::Java,
        fqn,
        name: name.clone(),
        package: ctx.package.to_string(),
        module: ctx.module.to_string(),
        kind: kind_of(ctx.src, decl),
        file: ctx.file.to_path_buf(),
        line: begin,
        end_line: end,
        loc: count_loc(ctx.lines, begin, end),
        noa,
        test: ctx.test,
        methods,
        references: references(ctx.src, decl),
        explicit_imports: ctx.explicit.to_vec(),
        wildcard_imports: ctx.wildcard.to_vec(),
    });
    for n in nested {
        collect_type(ctx, n, &name, out);
    }
}

/// The building kind shown in the viewers. Classes are split into "abstract" and "class" by their `abstract`
/// modifier, which is an anonymous token inside the `modifiers` node.
fn kind_of(src: &Src, decl: Node) -> &'static str {
    match decl.kind() {
        "interface_declaration" => "interface",
        "enum_declaration" => "enum",
        "record_declaration" => "record",
        "annotation_type_declaration" => "annotation",
        _ => {
            let is_abstract = named_children(decl)
                .find(|c| c.kind() == "modifiers")
                .map(|m| super::syntax::children(m).any(|c| src.text(c) == "abstract"))
                .unwrap_or(false);
            if is_abstract { "abstract" } else { "class" }
        }
    }
}

/// Metrics of a method or constructor. Parameters include a trailing varargs parameter (`spread_parameter`);
/// the receiver parameter (`Foo this`) is not counted.
fn method(ctx: &FileCtx, m: Node) -> MethodInfo {
    let begin = first_line(m);
    let end = last_line(m);
    let parameters = m
        .child_by_field_name("parameters")
        .map(|p| named_children(p).filter(|c| matches!(c.kind(), "formal_parameter" | "spread_parameter")).count())
        .unwrap_or(0) as u32;
    MethodInfo {
        name: ctx.src.field_text(m, "name").to_string(),
        line: begin,
        loc: count_loc(ctx.lines, begin, end),
        complexity: complexity(m),
        parameters,
    }
}

/// McCabe cyclomatic complexity: 1 + number of decision points.
///
/// Decision points are branches and loops, each `catch`, each `?:`, each non-default `case` label, and each `&&` /
/// `||` (short-circuit operators add a path each). The walk covers everything inside the node, so lambdas and
/// anonymous or local classes in a method add to that method's complexity.
pub fn complexity(node: Node) -> u32 {
    let mut cx = 1;
    walk(node, |n| {
        match n.kind() {
            "if_statement" | "for_statement" | "enhanced_for_statement" | "while_statement" | "do_statement"
            | "catch_clause" | "ternary_expression" => cx += 1,
            "switch_label" => {
                if n.named_child_count() > 0 {
                    cx += 1; // `default` labels have no expression
                }
            }
            "binary_expression" => {
                if matches!(super::syntax::operator(n), "&&" | "||") {
                    cx += 1;
                }
            }
            _ => {}
        }
        true
    });
    cx
}

/// Type names used in a declaration: types, annotations, and upper-case call/field scopes (`Util.run()`).
///
/// Names are recorded as written; [`resolve`] decides later which ones are project types. The upper-case test is the
/// usual Java naming convention: `Util.run()` is a static call on a type, `list.add()` a call on a variable.
fn references(src: &Src, decl: Node) -> HashSet<String> {
    let mut refs = HashSet::new();
    walk(decl, |n| match n.kind() {
        "type_identifier" => {
            refs.insert(src.text(n).to_string());
            true
        }
        "scoped_type_identifier" => {
            // like JavaParser: `Map.Entry` contributes "Map.Entry" and its scope "Map", but not "Entry" alone
            let text = strip_ws(src.text(n));
            let mut prefix = text.as_str();
            while let Some(dot) = prefix.rfind('.') {
                prefix = &prefix[..dot];
                refs.insert(prefix.to_string());
            }
            refs.insert(text);
            // Do not descend: the inner type_identifiers would add "Entry" on its own.
            false
        }
        "marker_annotation" | "annotation" => {
            refs.insert(strip_ws(src.field_text(n, "name")));
            true
        }
        "method_invocation" | "field_access" => {
            // `Outer.this` is a qualified this, not a static member access (JavaParser models it as ThisExpr)
            let qualified_this = n.kind() == "field_access" && n.child_by_field_name("field").is_some_and(|f| f.kind() == "this");
            if let Some(object) = n.child_by_field_name("object").filter(|_| !qualified_this) {
                if object.kind() == "identifier" {
                    let s = src.text(object);
                    if s.chars().next().is_some_and(char::is_uppercase) {
                        refs.insert(s.to_string());
                    }
                }
            }
            true
        }
        _ => true,
    });
    refs
}

/// Removes all whitespace: tree-sitter keeps the source text, and `java.util . List` is a legal way to write a name.
fn strip_ws(s: &str) -> String {
    s.chars().filter(|c| !c.is_whitespace()).collect()
}

/// "core/api" for `core/api/src/main/java/...`; "" when sources sit directly under the root.
///
/// The build module is everything before the first `src` directory, following the Maven/Gradle layout. A file
/// with no `src` directory on its path also gets "".
pub fn module_of(relative: &Path) -> String {
    let mut parts = Vec::new();
    for c in relative.components() {
        let s = c.as_os_str().to_string_lossy();
        if s == "src" {
            return parts.join("/");
        }
        parts.push(s.to_string());
    }
    String::new()
}

/// Test code: under a test directory (`src/test/java`, …) or named like a JUnit/Failsafe test class.
fn is_test(relative: &Path) -> bool {
    let name = relative.file_name().map(|n| n.to_string_lossy()).unwrap_or_default();
    in_test_directory(relative) || name.ends_with("Test.java") || name.ends_with("Tests.java") || name.ends_with("IT.java")
}

// ------------------------------------------------------------------ linking

/// Resolves every type's references against all Java types of the project. Self-references are dropped.
pub fn link(files: &[Arc<ParsedFile>]) -> Linked {
    // The same FQN can occur in several modules (or as a test fixture); keep the first.
    let mut by_fqn: BTreeMap<String, &TypeInfo> = BTreeMap::new();
    for f in files {
        for t in &f.types {
            by_fqn.entry(t.fqn.clone()).or_insert(t);
        }
    }
    let mut dependencies = HashMap::new();
    for t in by_fqn.values() {
        let out: HashSet<String> = t
            .references
            .iter()
            .filter_map(|r| resolve(r, t, &by_fqn))
            .filter(|target| *target != t.fqn)
            .collect();
        dependencies.insert(t.fqn.clone(), out);
    }
    Linked { types: by_fqn.into_values().cloned().collect(), dependencies }
}

/// Java's own lookup order: enclosing types, single-type imports, same package, on-demand imports.
///
/// A reference may be qualified (`Map.Entry`, `com.acme.Order`). Only its first segment is looked up through the
/// scopes; the rest (`rest`, including its leading dot) is appended to each candidate. For a reference `Line` in
/// type `Order.Item` of package `shop`, the candidates are, in order:
///
/// ```text
/// shop.Order.Item.Line   shop.Order.Line        member types of the type itself and of each enclosing type
/// a.b.Line               (an `import a.b.Line;`)
/// shop.Line              same package
/// x.y.Line               (an `import x.y.*;`)
/// ```
///
/// The first candidate that names a project type wins. Inherited member types are not considered. Only when no
/// candidate is a project type is the reference taken as already fully qualified (`com.acme.Order`). Types in the
/// default package are found only from the default package itself (as its "same package" candidate): as in Java,
/// they are invisible from named packages.
fn resolve(reference: &str, from: &TypeInfo, known: &BTreeMap<String, &TypeInfo>) -> Option<String> {
    let (first, rest) = match reference.find('.') {
        Some(dot) => (&reference[..dot], &reference[dot..]),
        None => (reference, ""),
    };
    let prefix = if from.package.is_empty() { String::new() } else { format!("{}.", from.package) };
    let mut candidates = Vec::new();
    // `from.name` is relative to the package (`Order.Item`), so strip one level at a time.
    let mut enclosing = from.name.as_str();
    loop {
        candidates.push(format!("{prefix}{enclosing}.{first}"));
        match enclosing.rfind('.') {
            Some(i) => enclosing = &enclosing[..i],
            None => break,
        }
    }
    for imp in &from.explicit_imports {
        // Match the import's last segment.
        if imp.ends_with(&format!(".{first}")) {
            candidates.push(imp.clone());
        }
    }
    candidates.push(format!("{prefix}{first}"));
    for w in &from.wildcard_imports {
        candidates.push(format!("{w}.{first}"));
    }
    // For a qualified reference prefer the full name (`a.Map.Entry`), then fall back to the first segment alone
    // (`a.Map`), so the dependency still points at the outer type if the member type is not a project type.
    for c in candidates {
        if !rest.is_empty() {
            let full = format!("{c}{rest}");
            if known.contains_key(full.as_str()) {
                return Some(full);
            }
        }
        if known.contains_key(c.as_str()) {
            return Some(c);
        }
    }
    // Already fully qualified, which a default-package type never is from outside that package.
    known.get(reference).filter(|t| !t.package.is_empty()).map(|_| reference.to_string())
}
