//! Java support. The source is parsed by the in-house parser in [`super::java_parse`].
//!
//! Packages are districts and every class, interface, enum, record and annotation type (including member types) is a
//! building. The fqn is `package.Outer.Inner`, so it matches what Java itself calls the type.
//!
//! Parsing records, per type, the *raw* names it mentions (`List`, `Map.Entry`, `Util` from `Util.run()`) and the
//! file's imports. [`link`] then resolves those names with Java's own lookup rules (see [`resolve`]); anything that
//! does not resolve to a project type (JDK and library types) is dropped.

use super::java_parse::{self as jp, NodeKind, Tree};
use super::text::{count_loc, in_test_directory, lines};
use super::{Context, Linked, ParsedFile};
use crate::model::{Language, MethodInfo, TypeInfo};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::Path;
use std::sync::Arc;

/// Parses one `.java` file into its types (top-level and member types, depth-first in source order).
pub fn parse(root: &Path, file: &Path, code: &str) -> ParsedFile {
    let tree = jp::parse(code);
    let lines = lines(code);
    let relative = file.strip_prefix(root).unwrap_or(file);
    let module = module_of(relative);
    let test = is_test(relative);

    // `explicit`: single-type imports, resolved first. `wildcard`: packages (or types) imported with `.*`.
    let mut package = String::new();
    let mut explicit = Vec::new();
    let mut wildcard = Vec::new();
    let top = tree.children(0);
    for &n in &top {
        let node = tree.nodes[n];
        match node.kind {
            NodeKind::Package => {
                // Package annotations (allowed in package-info.java) are separate nodes; the Name child is the name.
                if let Some(name) = name_child(&tree, n) {
                    package = strip_ws(name_text(&tree, code, name));
                }
            }
            NodeKind::Import => {
                let Some(name) = name_child(&tree, n) else { continue };
                let is_static = node.flags & jp::IMPORT_STATIC != 0;
                let asterisk = node.flags & jp::IMPORT_ASTERISK != 0;
                // Names may be written with whitespace or line breaks around the dots; normalise to `a.b.C`.
                let name = strip_ws(name_text(&tree, code, name));
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
    let ctx = FileCtx { tree: &tree, code, lines: &lines, file, package: &package, module: &module, test, explicit: &explicit, wildcard: &wildcard };
    for &n in &top {
        if tree.nodes[n].kind == NodeKind::TypeDecl {
            collect_type(&ctx, n, "", &mut types);
        }
    }
    ParsedFile { types, context: Context::None, failed: tree.error }
}

/// The [`NodeKind::Name`] child of a package or import node.
fn name_child(tree: &Tree, n: usize) -> Option<usize> {
    tree.children(n).into_iter().find(|&c| tree.nodes[c].kind == NodeKind::Name)
}

/// Source text of a node's whole token span.
fn name_text<'a>(tree: &Tree, code: &'a str, n: usize) -> &'a str {
    tree.text(code, tree.nodes[n].first, tree.nodes[n].last)
}

/// Source text of a single token, "" for [`jp::NONE`].
fn token_text<'a>(tree: &Tree, code: &'a str, t: u32) -> &'a str {
    if t == jp::NONE { "" } else { tree.text(code, t, t) }
}

/// Per-file data shared by every type declared in the file.
struct FileCtx<'a> {
    tree: &'a Tree,
    code: &'a str,
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
///   enum constant and one per record component (a varargs component `int... xs` is not counted);
/// - methods: methods and constructors (initialiser blocks and compact record constructors are not counted);
/// - `loc`: the whole declaration, including member types, so an outer type's LOC contains its inner types';
/// - references: collected from the whole declaration, member types included.
///
/// The type is pushed before its member types, so `out` is in pre-order.
fn collect_type(ctx: &FileCtx, decl: usize, outer: &str, out: &mut Vec<TypeInfo>) {
    let tree = ctx.tree;
    let node = tree.nodes[decl];
    let simple = token_text(tree, ctx.code, node.name);
    let name = if outer.is_empty() { simple.to_string() } else { format!("{outer}.{simple}") };
    let fqn = if ctx.package.is_empty() { name.clone() } else { format!("{}.{}", ctx.package, name) };

    let mut methods = Vec::new();
    // Record components (`record P(int x, int y)`) are the record's fields; `count` is 0 for other kinds.
    let mut noa = node.count;
    let mut nested = Vec::new();
    for m in tree.children(decl) {
        let member = tree.nodes[m];
        match member.kind {
            NodeKind::Field => noa += member.count,
            NodeKind::EnumConstant => noa += 1,
            NodeKind::Method => methods.push(method(ctx, m)),
            NodeKind::TypeDecl => nested.push(m),
            _ => {}
        }
    }
    let begin = tree.first_line(decl);
    let end = tree.last_line(ctx.code, decl);
    out.push(TypeInfo {
        language: Language::Java,
        fqn,
        name: name.clone(),
        package: ctx.package.to_string(),
        module: ctx.module.to_string(),
        kind: kind_of(node.sub, node.flags),
        file: ctx.file.to_path_buf(),
        line: begin,
        end_line: end,
        loc: count_loc(ctx.lines, begin, end),
        noa,
        test: ctx.test,
        methods,
        references: references(tree, ctx.code, decl),
        explicit_imports: ctx.explicit.to_vec(),
        wildcard_imports: ctx.wildcard.to_vec(),
    });
    for n in nested {
        collect_type(ctx, n, &name, out);
    }
}

/// The building kind shown in the viewers. Classes are split into "abstract" and "class" by their `abstract`
/// modifier.
fn kind_of(sub: u8, flags: u8) -> &'static str {
    match sub {
        jp::TYPE_INTERFACE => "interface",
        jp::TYPE_ENUM => "enum",
        jp::TYPE_RECORD => "record",
        jp::TYPE_ANNOTATION => "annotation",
        _ => {
            if flags & jp::TYPE_ABSTRACT != 0 { "abstract" } else { "class" }
        }
    }
}

/// Metrics of a method or constructor. Parameters include a trailing varargs parameter; the receiver parameter
/// (`Foo this`) is not counted (the parser does both).
fn method(ctx: &FileCtx, m: usize) -> MethodInfo {
    let tree = ctx.tree;
    let begin = tree.first_line(m);
    let end = tree.last_line(ctx.code, m);
    MethodInfo {
        name: token_text(tree, ctx.code, tree.nodes[m].name).to_string(),
        line: begin,
        loc: count_loc(ctx.lines, begin, end),
        complexity: complexity(tree, m),
        parameters: tree.nodes[m].count,
    }
}

/// McCabe cyclomatic complexity: 1 + number of decision points.
///
/// Decision points are branches and loops, each `catch`, each `?:`, each non-default `case` label, and each `&&` /
/// `||` (short-circuit operators add a path each); the parser marks them as [`NodeKind::Decision`] nodes. Everything
/// inside the node counts, so lambdas and anonymous or local classes in a method add to that method's complexity.
pub fn complexity(tree: &Tree, node: usize) -> u32 {
    let end = tree.nodes[node].end as usize;
    1 + tree.nodes[node + 1..end].iter().filter(|n| n.kind == NodeKind::Decision).count() as u32
}

/// Type names used in a declaration: types, annotations, and upper-case call/field scopes (`Util.run()`).
///
/// Names are recorded as written; [`resolve`] decides later which ones are project types. The upper-case test is the
/// usual Java naming convention: `Util.run()` is a static call on a type, `list.add()` a call on a variable.
fn references(tree: &Tree, code: &str, decl: usize) -> HashSet<String> {
    let mut refs = HashSet::new();
    let end = tree.nodes[decl].end as usize;
    for n in &tree.nodes[decl + 1..end] {
        let text = tree.text(code, n.first, n.last);
        match n.kind {
            NodeKind::TypeName => {
                refs.insert(text.to_string());
            }
            NodeKind::ScopedTypeName => {
                // a qualified name: `Map.Entry` contributes "Map.Entry" and its scope "Map", but not "Entry" alone
                // (the parser does not report the parts of a qualified name separately)
                let text = strip_ws(text);
                let mut prefix = text.as_str();
                while let Some(dot) = prefix.rfind('.') {
                    prefix = &prefix[..dot];
                    refs.insert(prefix.to_string());
                }
                refs.insert(text);
            }
            NodeKind::AnnotationName => {
                refs.insert(strip_ws(text));
            }
            NodeKind::MemberObject => {
                if text.chars().next().is_some_and(char::is_uppercase) {
                    refs.insert(text.to_string());
                }
            }
            _ => {}
        }
    }
    refs
}

/// Removes all whitespace: names are taken from the source text, and `java.util . List` is a legal way to write one.
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
