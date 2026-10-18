/// Java support (tree-sitter-java), mirroring the metrics the JavaParser-based Java edition computes.
///
/// `parse` turns one `.java` file into a `TypeDecl` per class, interface, enum, record and annotation type, including
/// member types (`Outer.Inner`), with these metrics: lines of code, number of attributes (fields, record components,
/// enum constants), methods (and constructors) with their LOC, cyclomatic complexity and parameter count. It also
/// records the raw type names each type mentions and the file's imports.
///
/// `link` then resolves those names with (a simplified form of) Java's own scoping rules to the fqns of project
/// types, which gives the dependency edges. Names that resolve to nothing in the project (JDK, libraries) are
/// dropped. No `Context` is needed: the imports travel on every `TypeDecl`.
module codecity.analysis.java;

import codecity.analysis : Linked, ParsedFile;
import codecity.analysis.syntax;
import codecity.analysis.text;
import codecity.c.treesitter;
import codecity.model;
import std.algorithm : canFind, endsWith, filter, sort;
import std.array : array, join;
import std.ascii : isUpper;
import std.path : baseName;
import std.string : indexOf, lastIndexOf;
import std.uni : isWhite;

/// tree-sitter-java node kinds that declare a type (a building).
private immutable string[] typeDeclarations = [
    "class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
    "annotation_type_declaration",
];

/// Analyses one Java file. `root` is the analysed directory, used for the build module (`moduleOf`) and the
/// test-directory check. The file counts as failed when the tree contains syntax errors; whatever was recognised is
/// still returned.
ParsedFile parse(string root, string file, string code)
{
    auto tree = codecity.analysis.syntax.parse("java", &tree_sitter_java, code);
    if (tree is null) return ParsedFile(null, null, true);
    scope (exit) ts_tree_delete(tree);
    auto src = Src(code);
    auto program = ts_tree_root_node(tree);
    auto lines = lines(code);
    const relative = relativeTo(file, root);
    const module_ = moduleOf(relative);
    const test = isTest(relative);

    // First pass over the top level: the package and the imports, which apply to every type in the file.
    string pkg;
    string[] explicit, wildcard;
    foreach (n; namedChildren(program))
    {
        switch (kind(n))
        {
        case "package_declaration":
            // `@Deprecated package a.b;` (package-info.java): skip annotations, the name is the first other child.
            // Whitespace is removed because the grammar allows it between the dots.
            foreach (c; namedChildren(n))
                if (kind(c) != "annotation" && kind(c) != "marker_annotation")
                {
                    pkg = stripWs(src.text(c));
                    break;
                }
            break;
        case "import_declaration":
            // `static` is an anonymous token, `*` a named `asterisk` node; the name is a scoped_identifier
            // (`a.b.C`) or, for a class in the default package, a plain identifier.
            //   import a.B;              → explicit "a.B"
            //   import a.*;              → wildcard "a"
            //   import static a.B.c;     → explicit "a.B" (the member's type)
            //   import static a.B.*;     → explicit "a.B" (all members of that type)
            // Static imports thus make the type they import from a dependency.
            bool isStatic, asterisk;
            foreach (c; children(n)) if (kind(c) == "static") isStatic = true;
            TSNode name;
            bool found;
            foreach (c; namedChildren(n))
            {
                if (kind(c) == "asterisk") asterisk = true;
                if (!found && (kind(c) == "scoped_identifier" || kind(c) == "identifier"))
                {
                    name = c;
                    found = true;
                }
            }
            if (!found) break;
            auto text = stripWs(src.text(name));
            if (isStatic && !asterisk)
            {
                const dot = text.lastIndexOf('.');
                explicit ~= dot >= 0 ? text[0 .. dot] : text;
            }
            else if (asterisk)
            {
                if (isStatic) explicit ~= text;
                else wildcard ~= text;
            }
            else explicit ~= text;
            break;
        default:
            break;
        }
    }

    // Second pass: only top-level type declarations; collectType recurses into member types.
    auto ctx = FileCtx(&src, lines, file, pkg, module_, test, explicit, wildcard);
    TypeDecl[] types;
    foreach (n; namedChildren(program))
        if (typeDeclarations.canFind(kind(n))) collectType(ctx, n, "", types);
    return ParsedFile(types, null, ts_node_has_error(program));
}

/// Per-file facts shared by every type in the file.
private struct FileCtx
{
    Src* src;
    string[] lines;
    string file;
    string pkg;
    string module_;
    bool test;
    string[] explicit;
    string[] wildcard;
}

/// Adds a type and, recursively, the member types declared in its body (local classes are not types).
///
/// `outer` is the enclosing type's package-relative name ("" at top level), so a member type is named `Outer.Inner`
/// and its fqn is `pkg.Outer.Inner`. Only direct members of the body are inspected for attributes, methods and
/// member types; classes declared inside methods, initialisers or anonymous classes are part of the enclosing
/// type. The outer type's LOC span and references cover its member types' text too, because both are computed over
/// the whole declaration. The outer type is emitted before its member types.
private void collectType(ref FileCtx ctx, TSNode decl, string outer, ref TypeDecl[] output)
{
    const simple = ctx.src.fieldText(decl, "name");
    const name = outer.length ? outer ~ "." ~ simple : simple.idup;
    const fqn = ctx.pkg.length ? ctx.pkg ~ "." ~ name : name;
    auto body_ = child(decl, "body");

    MethodInfo[] methods;
    uint noa;
    TSNode[] nested;
    // Record components (`record P(int x, int y)`) are the record's fields; the grammar has them as the
    // declaration's formal parameters.
    if (kind(decl) == "record_declaration")
    {
        auto params = child(decl, "parameters");
        if (!isNull(params))
            foreach (p; namedChildren(params)) if (kind(p) == "formal_parameter") noa++;
    }
    if (!isNull(body_))
    {
        TSNode[] members;
        foreach (m; namedChildren(body_))
        {
            // enum bodies keep their non-constant members in a nested enum_body_declarations node
            if (kind(m) == "enum_body_declarations") members ~= namedChildren(m);
            else members ~= m;
        }
        foreach (m; members)
        {
            const k = kind(m);
            // `int a, b;` declares two attributes: count declarators, not declarations. constant_declaration is a
            // field in an interface (or annotation type) body.
            if (k == "field_declaration" || k == "constant_declaration")
            {
                foreach (d; namedChildren(m)) if (kind(d) == "variable_declarator") noa++;
            }
            else if (k == "enum_constant") noa++;
            else if (k == "method_declaration" || k == "constructor_declaration") methods ~= method(ctx, m);
            else if (typeDeclarations.canFind(k)) nested ~= m;
        }
    }
    const begin = firstLine(decl), end = lastLine(decl);
    TypeDecl t;
    t.language = Language.java;
    t.fqn = fqn;
    t.name = name;
    t.pkg = ctx.pkg;
    t.module_ = ctx.module_;
    t.kind = kindOf(*ctx.src, decl);
    t.file = ctx.file;
    t.line = begin;
    t.endLine = end;
    t.loc = countLoc(ctx.lines, begin, end);
    t.noa = noa;
    t.test = ctx.test;
    t.methods = methods;
    t.references = references(*ctx.src, decl);
    t.explicitImports = ctx.explicit;
    t.wildcardImports = ctx.wildcard;
    output ~= t;
    foreach (n; nested) collectType(ctx, n, name, output);
}

/// The building kind shown in the viewer. Classes with an `abstract` modifier are reported as "abstract".
private string kindOf(ref const Src src, TSNode decl)
{
    switch (kind(decl))
    {
    case "interface_declaration": return "interface";
    case "enum_declaration": return "enum";
    case "record_declaration": return "record";
    case "annotation_type_declaration": return "annotation";
    default:
        foreach (c; namedChildren(decl))
            if (kind(c) == "modifiers")
            {
                foreach (m; children(c)) if (src.text(m) == "abstract") return "abstract";
                break;
            }
        return "class";
    }
}

/// Metrics of one method or constructor. Varargs (`String... args`) is a spread_parameter in the grammar and counts
/// as one parameter; the receiver parameter (`Foo this`) is neither kind and does not count.
private MethodInfo method(ref FileCtx ctx, TSNode m)
{
    const begin = firstLine(m), end = lastLine(m);
    uint parameters;
    auto p = child(m, "parameters");
    if (!isNull(p))
        foreach (c; namedChildren(p))
            if (kind(c) == "formal_parameter" || kind(c) == "spread_parameter") parameters++;
    return MethodInfo(ctx.src.fieldText(m, "name").idup, begin, countLoc(ctx.lines, begin, end), complexity(m), parameters);
}

/// McCabe cyclomatic complexity: 1 + number of decision points.
///
/// Decision points: `if`, the loops, `catch`, `?:`, every non-default `case` label (old `case X:` and arrow
/// `case X ->` both parse as switch_label; `case A, B:` is one label and counts once) and every `&&`/`||`. `else`
/// and `finally` do not branch and do not count. The walk covers the whole subtree, so lambdas and anonymous classes
/// inside a method add to that method.
uint complexity(TSNode node)
{
    uint cx = 1;
    walk(node, (TSNode n) {
        switch (kind(n))
        {
        case "if_statement", "for_statement", "enhanced_for_statement", "while_statement", "do_statement",
                "catch_clause", "ternary_expression":
            cx++;
            break;
        case "switch_label":
            if (ts_node_named_child_count(n) > 0) cx++; // `default` labels have no expression
            break;
        case "binary_expression":
            const op = operator(n);
            if (op == "&&" || op == "||") cx++;
            break;
        default:
            break;
        }
        return true;
    });
    return cx;
}

/// Type names used in a declaration: types, annotations, and upper-case call/field scopes (`Util.run()`).
///
/// The names are raw source text (`List`, `Map.Entry`, `com.acme.Order`); `resolve` turns them into fqns later.
/// For `x.run()` and `Util.run()` the grammar cannot tell a variable from a type, so the Java convention decides: an
/// identifier starting with an upper-case letter is taken to be a type. Unresolvable guesses (constants such as
/// `MAX.foo`) are harmless, since `link` drops names that are not project types.
private bool[string] references(ref const Src src, TSNode decl)
{
    bool[string] refs;
    walk(decl, (TSNode n) {
        switch (kind(n))
        {
        case "type_identifier":
            refs[src.text(n).idup] = true;
            return true;
        case "scoped_type_identifier":
            // like JavaParser: `Map.Entry` contributes "Map.Entry" and its scope "Map", but not "Entry" alone
            auto text = stripWs(src.text(n));
            string prefix = text;
            while (true)
            {
                const dot = prefix.lastIndexOf('.');
                if (dot < 0) break;
                prefix = prefix[0 .. dot];
                refs[prefix] = true;
            }
            refs[text] = true;
            // Do not descend: its type_identifier parts would add "Entry" on its own.
            return false;
        case "marker_annotation", "annotation":
            refs[stripWs(src.fieldText(n, "name"))] = true;
            return true;
        case "method_invocation", "field_access":
            // `Outer.this` is a qualified this, not a static member access (JavaParser models it as ThisExpr)
            auto field = child(n, "field");
            const qualifiedThis = kind(n) == "field_access" && !isNull(field) && kind(field) == "this";
            auto object = child(n, "object");
            if (!qualifiedThis && !isNull(object) && kind(object) == "identifier")
            {
                const s = src.text(object);
                if (s.length && isUpperStart(s)) refs[s.idup] = true;
            }
            return true;
        default:
            return true;
        }
    });
    return refs;
}

/// True when the first code point of `s` is an upper-case letter (Unicode-aware, so `Ärger` counts).
bool isUpperStart(string s) @safe
{
    import std.uni : isUpper;
    import std.utf : decodeFront;
    auto copy = s;
    return copy.length && isUpper(decodeFront(copy));
}

/// `s` without any whitespace: qualified names may be split over lines (`com.acme\n    .Order`).
string stripWs(string s) @safe
{
    string output;
    foreach (dchar c; s) if (!isWhite(c)) output ~= c;
    return output;
}

/// `root`-relative path with forward slashes. Also used by the TypeScript and Rust analysers.
string relativeTo(string file, string root) @safe
{
    import std.path : relativePath;
    import std.array : replace;
    return relativePath(file, root).replace("\\", "/");
}

/// "core/api" for `core/api/src/main/java/...`; "" when sources sit directly under the root.
///
/// The build module is everything before the first `src` path segment (the Maven/Gradle layout). A file without
/// any `src` segment also gets "".
string moduleOf(string relative) @safe
{
    import std.path : pathSplitter;
    string[] parts;
    foreach (p; relative.pathSplitter)
    {
        if (p == "src") return parts.join("/");
        parts ~= p;
    }
    return "";
}

/// Test code: anything in a test directory, plus the JUnit/Failsafe naming conventions `*Test`, `*Tests`, `*IT`.
private bool isTest(string relative) @safe
{
    const name = relative.baseName;
    return inTestDirectory(relative) || name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
}

// ------------------------------------------------------------------ linking

/// Resolves every type's references against all Java types of the project. `files` arrive in sorted path order,
/// so which duplicate wins is deterministic.
Linked link(ParsedFile[] files)
{
    // The same FQN can occur in several modules (or as a test fixture); keep the first.
    TypeDecl[string] byFqn;
    string[] order;
    foreach (f; files)
        foreach (t; f.types)
            if (t.fqn !in byFqn)
            {
                byFqn[t.fqn] = t;
                order ~= t.fqn;
            }
    Linked linked;
    foreach (fqn; order)
    {
        auto t = byFqn[fqn];
        bool[string] out_;
        foreach (r, _; t.references)
        {
            auto target = resolve(r, t, byFqn);
            if (target !is null && target != t.fqn) out_[target] = true;
        }
        linked.dependencies[fqn] = out_;
        linked.types ~= t;
    }
    return linked;
}

/// Java's own lookup order: enclosing types, single-type imports, same package, on-demand imports.
///
/// A qualified reference `A.B` is resolved by its first segment `A`; each candidate for `A` is tried as `cand.B`
/// first and then as `cand` alone, so a nested type the analysis does not know still yields a dependency on its
/// outer type. Simplifications compared with the language: inherited member types and `java.lang` are not
/// considered, and a name shadowed by a type parameter or local class is still resolved. Only when no candidate is a
/// project type is the reference taken as already fully qualified (`com.acme.Order`); returns null when that fails
/// too. Types in the default package are found only from the default package itself (as its "same package"
/// candidate): as in Java, they are invisible from named packages.
///
/// Example, from `com.acme.Order.Line` referring to `Item`:
///   com.acme.Order.Line.Item, com.acme.Order.Item   (member types, innermost first)
///   <explicit import ending in .Item>
///   com.acme.Item                                   (same package)
///   <wildcard>.Item for every `import x.*`
private string resolve(string reference, ref const TypeDecl from, const TypeDecl[string] known)
{
    const dot = reference.indexOf('.');
    const first = dot < 0 ? reference : reference[0 .. dot];
    const rest = dot < 0 ? "" : reference[dot .. $];
    const prefix = from.pkg.length ? from.pkg ~ "." : "";
    string[] candidates;
    string enclosing = from.name;
    while (true)
    {
        candidates ~= prefix ~ enclosing ~ "." ~ first;
        const i = enclosing.lastIndexOf('.');
        if (i < 0) break;
        enclosing = enclosing[0 .. i];
    }
    foreach (imp; from.explicitImports)
        if (imp.endsWith("." ~ first)) candidates ~= imp;
    candidates ~= prefix ~ first;
    foreach (w; from.wildcardImports) candidates ~= w ~ "." ~ first;
    foreach (c; candidates)
    {
        if (rest.length)
        {
            const full = c ~ rest;
            if (full in known) return full;
        }
        if (c in known) return c;
    }
    // Already fully qualified, which a default-package type never is from outside that package.
    if (auto target = reference in known)
        if (target.pkg.length) return reference;
    return null;
}
