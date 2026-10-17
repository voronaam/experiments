/// Java support (tree-sitter-java), mirroring the metrics the JavaParser-based Java edition computes.
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

private immutable string[] typeDeclarations = [
    "class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
    "annotation_type_declaration",
];

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

    string pkg;
    string[] explicit, wildcard;
    foreach (n; namedChildren(program))
    {
        switch (kind(n))
        {
        case "package_declaration":
            foreach (c; namedChildren(n))
                if (kind(c) != "annotation" && kind(c) != "marker_annotation")
                {
                    pkg = stripWs(src.text(c));
                    break;
                }
            break;
        case "import_declaration":
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

    auto ctx = FileCtx(&src, lines, file, pkg, module_, test, explicit, wildcard);
    TypeDecl[] types;
    foreach (n; namedChildren(program))
        if (typeDeclarations.canFind(kind(n))) collectType(ctx, n, "", types);
    return ParsedFile(types, null, ts_node_has_error(program));
}

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
private void collectType(ref FileCtx ctx, TSNode decl, string outer, ref TypeDecl[] output)
{
    const simple = ctx.src.fieldText(decl, "name");
    const name = outer.length ? outer ~ "." ~ simple : simple.idup;
    const fqn = ctx.pkg.length ? ctx.pkg ~ "." ~ name : name;
    auto body_ = child(decl, "body");

    MethodInfo[] methods;
    uint noa;
    TSNode[] nested;
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

bool isUpperStart(string s) @safe
{
    import std.uni : isUpper;
    import std.utf : decodeFront;
    auto copy = s;
    return copy.length && isUpper(decodeFront(copy));
}

string stripWs(string s) @safe
{
    string output;
    foreach (dchar c; s) if (!isWhite(c)) output ~= c;
    return output;
}

/// `root`-relative path with forward slashes.
string relativeTo(string file, string root) @safe
{
    import std.path : relativePath;
    import std.array : replace;
    return relativePath(file, root).replace("\\", "/");
}

/// "core/api" for `core/api/src/main/java/...`; "" when sources sit directly under the root.
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

private bool isTest(string relative) @safe
{
    const name = relative.baseName;
    return inTestDirectory(relative) || name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
}

// ------------------------------------------------------------------ linking

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
private string resolve(string reference, ref const TypeDecl from, const TypeDecl[string] known)
{
    if (reference in known) return reference;
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
        if (imp == first || imp.endsWith("." ~ first)) candidates ~= imp;
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
    return null;
}
