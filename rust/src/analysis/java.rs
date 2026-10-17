//! Java support (tree-sitter-java), mirroring the metrics the JavaParser-based Java version computes.

use super::syntax::{first_line, last_line, named_children, walk, Src};
use super::text::{count_loc, in_test_directory, lines};
use super::{with_parser, Context, Linked, ParsedFile};
use crate::model::{Language, MethodInfo, TypeInfo};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::Path;
use std::sync::Arc;
use tree_sitter::Node;

const TYPE_DECLARATIONS: [&str; 5] = [
    "class_declaration",
    "interface_declaration",
    "enum_declaration",
    "record_declaration",
    "annotation_type_declaration",
];

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

    let mut package = String::new();
    let mut explicit = Vec::new();
    let mut wildcard = Vec::new();
    for n in named_children(program) {
        match n.kind() {
            "package_declaration" => {
                if let Some(name) = named_children(n).find(|c| c.kind() != "annotation" && c.kind() != "marker_annotation") {
                    package = strip_ws(src.text(name));
                }
            }
            "import_declaration" => {
                let is_static = super::syntax::children(n).any(|c| c.kind() == "static");
                let asterisk = named_children(n).any(|c| c.kind() == "asterisk");
                let Some(name) = named_children(n).find(|c| c.kind() == "scoped_identifier" || c.kind() == "identifier")
                else {
                    continue;
                };
                let name = strip_ws(src.text(name));
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
fn collect_type(ctx: &FileCtx, decl: Node, outer: &str, out: &mut Vec<TypeInfo>) {
    let simple = ctx.src.field_text(decl, "name");
    let name = if outer.is_empty() { simple.to_string() } else { format!("{outer}.{simple}") };
    let fqn = if ctx.package.is_empty() { name.clone() } else { format!("{}.{}", ctx.package, name) };
    let body = decl.child_by_field_name("body");

    let mut methods = Vec::new();
    let mut noa = 0;
    let mut nested = Vec::new();
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

fn strip_ws(s: &str) -> String {
    s.chars().filter(|c| !c.is_whitespace()).collect()
}

/// "core/api" for `core/api/src/main/java/...`; "" when sources sit directly under the root.
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

fn is_test(relative: &Path) -> bool {
    let name = relative.file_name().map(|n| n.to_string_lossy()).unwrap_or_default();
    in_test_directory(relative) || name.ends_with("Test.java") || name.ends_with("Tests.java") || name.ends_with("IT.java")
}

// ------------------------------------------------------------------ linking

pub fn link(files: &[Arc<ParsedFile>]) -> Linked {
    // The same FQN can occur in several modules (or as a test fixture); keep the first.
    let mut by_fqn: BTreeMap<String, &TypeInfo> = BTreeMap::new();
    for f in files {
        for t in &f.types {
            by_fqn.entry(t.fqn.clone()).or_insert(t);
        }
    }
    let known: HashSet<&str> = by_fqn.keys().map(String::as_str).collect();
    let mut dependencies = HashMap::new();
    for t in by_fqn.values() {
        let out: HashSet<String> = t
            .references
            .iter()
            .filter_map(|r| resolve(r, t, &known))
            .filter(|target| *target != t.fqn)
            .collect();
        dependencies.insert(t.fqn.clone(), out);
    }
    Linked { types: by_fqn.into_values().cloned().collect(), dependencies }
}

/// Java's own lookup order: enclosing types, single-type imports, same package, on-demand imports.
fn resolve(reference: &str, from: &TypeInfo, known: &HashSet<&str>) -> Option<String> {
    if known.contains(reference) {
        return Some(reference.to_string());
    }
    let (first, rest) = match reference.find('.') {
        Some(dot) => (&reference[..dot], &reference[dot..]),
        None => (reference, ""),
    };
    let prefix = if from.package.is_empty() { String::new() } else { format!("{}.", from.package) };
    let mut candidates = Vec::new();
    let mut enclosing = from.name.as_str();
    loop {
        candidates.push(format!("{prefix}{enclosing}.{first}"));
        match enclosing.rfind('.') {
            Some(i) => enclosing = &enclosing[..i],
            None => break,
        }
    }
    for imp in &from.explicit_imports {
        if imp == first || imp.ends_with(&format!(".{first}")) {
            candidates.push(imp.clone());
        }
    }
    candidates.push(format!("{prefix}{first}"));
    for w in &from.wildcard_imports {
        candidates.push(format!("{w}.{first}"));
    }
    for c in candidates {
        if !rest.is_empty() {
            let full = format!("{c}{rest}");
            if known.contains(full.as_str()) {
                return Some(full);
            }
        }
        if known.contains(c.as_str()) {
            return Some(c);
        }
    }
    None
}
