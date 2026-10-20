/// Java support. The source is parsed by the in-house parser in `codecity.analysis.java_parse`.
///
/// `parse` turns one `.java` file into a `TypeDecl` per class, interface, enum, record and annotation type, including
/// member types (`Outer.Inner`), with these metrics: lines of code, number of attributes (fields, record components,
/// enum constants), methods (and constructors) with their LOC, cyclomatic complexity and parameter count. It also
/// records the raw type names each type mentions (`List`, `Map.Entry`, `Util` from `Util.run()`) and the file's
/// imports.
///
/// `link` then resolves those names with (a simplified form of) Java's own scoping rules to the fqns of project
/// types, which gives the dependency edges. Names that resolve to nothing in the project (JDK, libraries) are
/// dropped. No `Context` is needed: the imports travel on every `TypeDecl`.
module codecity.analysis.java;

import codecity.analysis : Linked, ParsedFile;
import codecity.analysis.java_parse : NodeKind, Tree;
import jp = codecity.analysis.java_parse;
import codecity.analysis.text;
import codecity.model;
import std.algorithm : endsWith;
import std.array : join;
import std.path : baseName;
import std.string : indexOf, lastIndexOf;
import std.uni : isWhite;

/// A reference is tried against the file's on-demand imports (`import a.b.*;`) and against its single-type imports of
/// the same simple name. Beyond this many of either kind, later ones are ignored, so a crafted file with very many
/// cannot make the analysis quadratic; real files have a handful of on-demand imports and one single-type import per
/// name. Imports longer than `maxFqnBytes` are ignored too: they cannot name a building. The Java and Rust editions
/// use the same limits.
enum size_t maxImportCandidates = 64;

/// A qualified name (`a.b.C`) contributes its prefixes (`a`, `a.b`) as references too, but at most this many of them,
/// the shortest; a crafted name of very many segments would otherwise cost time quadratic in its length. The Java
/// and Rust editions use the same limit.
enum size_t maxQualifiedPrefixes = 16;

/// Member types nested deeper than this (a top-level type is at depth 1) are not buildings; their code still counts
/// towards the enclosing types. Every type collects and resolves the references of its member types as well, so with
/// deep nesting the work grew with the square of the depth; real code nests a few levels. The Java and Rust editions
/// use the same limit.
enum size_t maxTypeNesting = 16;

/// Analyses one Java file. `root` is the analysed directory, used for the build module (`moduleOf`) and the
/// test-directory check. The file counts as failed when it has syntax errors; whatever was recognised is still
/// returned.
ParsedFile parse(string root, string file, string code)
{
    auto tree = jp.parse(code);
    auto lines = lines(code);
    const relative = relativeTo(file, root);
    const module_ = moduleOf(relative);
    const test = isTest(relative);

    // First pass over the top level: the package and the imports, which apply to every type in the file.
    // `explicit`: single-type imports, resolved first. `wildcard`: packages (or types) imported with `.*`.
    string pkg;
    string[] explicit, wildcard;
    const top = tree.children(0);
    foreach (n; top)
    {
        const node = tree.nodes[n];
        if (node.kind == NodeKind.package_)
        {
            // Package annotations (allowed in package-info.java) are separate nodes; the name child is the name.
            const name = nameChild(tree, n);
            if (name != jp.NONE) pkg = stripWs(nodeText(tree, code, name));
        }
        else if (node.kind == NodeKind.import_)
        {
            const name = nameChild(tree, n);
            if (name == jp.NONE) continue;
            const isStatic = (node.flags & jp.importStatic) != 0;
            const asterisk = (node.flags & jp.importAsterisk) != 0;
            // Names may be written with whitespace or line breaks around the dots; normalise to `a.b.C`.
            //   import a.B;              → explicit "a.B"
            //   import a.*;              → wildcard "a"
            //   import static a.B.c;     → explicit "a.B" (the member's type)
            //   import static a.B.*;     → explicit "a.B" (all members of that type)
            // Static imports thus make the type they import from a dependency.
            auto text = stripWs(nodeText(tree, code, name));
            if (text.length > maxFqnBytes) continue;
            if (isStatic && !asterisk)
            {
                const dot = text.lastIndexOf('.');
                explicit ~= dot >= 0 ? text[0 .. dot] : text;
            }
            else if (asterisk)
            {
                if (isStatic) explicit ~= text;
                else if (wildcard.length < maxImportCandidates) wildcard ~= text;
            }
            else explicit ~= text;
        }
    }

    // Second pass: only top-level type declarations; collectType recurses into member types.
    auto ctx = FileCtx(&tree, code, lines, file, pkg, module_, test, explicit, wildcard);
    TypeDecl[] types;
    foreach (n; top)
        if (tree.nodes[n].kind == NodeKind.typeDecl) collectType(ctx, n, "", 1, types);
    return ParsedFile(types, null, tree.error || ctx.skipped);
}

/// The `NodeKind.name` child of a package or import node, or `jp.NONE`.
private size_t nameChild(ref const Tree tree, size_t n)
{
    foreach (c; tree.children(n))
        if (tree.nodes[c].kind == NodeKind.name) return c;
    return jp.NONE;
}

/// Source text of a node's whole token span.
private string nodeText(ref const Tree tree, string code, size_t n)
{
    return tree.text(code, tree.nodes[n].first, tree.nodes[n].last);
}

/// Source text of a single token, "" for `jp.NONE`, as a copy: names outlive the source text in the parse cache.
private string tokenText(ref const Tree tree, string code, uint t)
{
    return t == jp.NONE ? "" : tree.text(code, t, t).idup;
}

/// Per-file facts shared by every type in the file.
private struct FileCtx
{
    const(Tree)* tree;
    string code;
    Lines lines;
    string file;
    string pkg;
    string module_;
    bool test;
    string[] explicit;
    string[] wildcard;
    /// Set when a type was skipped for its nesting or the length of its fqn (see `collectType`).
    bool skipped;
}

/// Adds a type and, recursively, the member types declared in its body (local classes are not types).
///
/// `outer` is the enclosing type's package-relative name ("" at top level), so a member type is named `Outer.Inner`
/// and its fqn is `pkg.Outer.Inner`. Metrics:
/// - `noa` (attributes): one per declared variable in fields and interface constants (`int a, b;` is two), one per
///   enum constant and one per record component (a varargs component `int... xs` is not counted);
/// - methods: methods and constructors (initialiser blocks and compact record constructors are not counted);
/// - `loc`: the whole declaration, including member types, so an outer type's LOC contains its inner types';
/// - references: collected from the whole declaration, member types included.
///
/// The outer type is emitted before its member types, so `output` is in pre-order. A type nested deeper than
/// `maxTypeNesting` or whose fqn would exceed `maxFqnBytes` is skipped with its member types (whose fqns are longer
/// still), and the file counts as failed; the length is checked before the fqn is built.
private void collectType(ref FileCtx ctx, size_t decl, string outer, size_t depth, ref TypeDecl[] output)
{
    const tree = ctx.tree;
    const node = tree.nodes[decl];
    const simple = tokenText(*tree, ctx.code, node.name);
    const fqnBytes = ctx.pkg.length + (ctx.pkg.length > 0) + outer.length + (outer.length > 0) + simple.length;
    if (depth > maxTypeNesting || fqnBytes > maxFqnBytes)
    {
        ctx.skipped = true;
        return;
    }
    const name = outer.length ? outer ~ "." ~ simple : simple;
    const fqn = ctx.pkg.length ? ctx.pkg ~ "." ~ name : name;

    MethodInfo[] methods;
    // Record components (`record P(int x, int y)`) are the record's fields; `count` is 0 for other kinds.
    uint noa = node.count;
    size_t[] nested;
    foreach (m; tree.children(decl))
    {
        const member = tree.nodes[m];
        switch (member.kind)
        {
        case NodeKind.field: noa += member.count; break;
        case NodeKind.enumConstant: noa++; break;
        case NodeKind.method: methods ~= method(ctx, m); break;
        case NodeKind.typeDecl: nested ~= m; break;
        default: break;
        }
    }
    const begin = tree.firstLine(decl), end = tree.lastLine(ctx.code, decl);
    TypeDecl t;
    t.language = Language.java;
    t.fqn = fqn;
    t.name = name;
    t.pkg = ctx.pkg;
    t.module_ = ctx.module_;
    t.kind = kindOf(node.sub, node.flags);
    t.file = ctx.file;
    t.line = begin;
    t.endLine = end;
    t.loc = countLoc(ctx.lines, begin, end);
    t.noa = noa;
    t.test = ctx.test;
    t.methods = methods;
    t.references = references(*tree, ctx.code, decl);
    t.explicitImports = ctx.explicit;
    t.wildcardImports = ctx.wildcard;
    output ~= t;
    foreach (n; nested) collectType(ctx, n, name, depth + 1, output);
}

/// The building kind shown in the viewer. Classes are split into "abstract" and "class" by their `abstract`
/// modifier.
private string kindOf(ubyte sub, ubyte flags)
{
    switch (sub)
    {
    case jp.typeInterface: return "interface";
    case jp.typeEnum: return "enum";
    case jp.typeRecord: return "record";
    case jp.typeAnnotation: return "annotation";
    default: return (flags & jp.typeAbstract) ? "abstract" : "class";
    }
}

/// Metrics of one method or constructor. Parameters include a trailing varargs parameter; the receiver parameter
/// (`Foo this`) is not counted (the parser does both).
private MethodInfo method(ref FileCtx ctx, size_t m)
{
    const tree = ctx.tree;
    const begin = tree.firstLine(m), end = tree.lastLine(ctx.code, m);
    return MethodInfo(tokenText(*tree, ctx.code, tree.nodes[m].name), begin, countLoc(ctx.lines, begin, end),
        complexity(*tree, m), tree.nodes[m].count);
}

/// McCabe cyclomatic complexity: 1 + number of decision points.
///
/// Decision points are branches and loops, each `catch`, each `?:`, each non-default `case` label, and each `&&` /
/// `||` (short-circuit operators add a path each); the parser marks them as `NodeKind.decision` nodes. `else` and
/// `finally` do not branch and do not count. Everything inside the node counts, so lambdas and anonymous or local
/// classes in a method add to that method's complexity.
uint complexity(ref const Tree tree, size_t node)
{
    uint cx = 1;
    foreach (ref n; tree.nodes[node + 1 .. tree.nodes[node].end])
        if (n.kind == NodeKind.decision) cx++;
    return cx;
}

/// Adds a reference unless it is longer than `maxFqnBytes`: it could not name a building, only resolve through its
/// first segment, which a qualified name contributes on its own (see `references`), and resolving it would copy it into
/// every candidate tried.
private void addReference(ref bool[string] refs, string name)
{
    if (name.length <= maxFqnBytes) refs[name] = true;
}

/// Type names used in a declaration: types, annotations, and upper-case call/field scopes (`Util.run()`).
///
/// The names are raw source text (`List`, `Map.Entry`, `com.acme.Order`); `resolve` turns them into fqns later.
/// For `x.run()` and `Util.run()` the parser cannot tell a variable from a type, so the Java convention decides: an
/// identifier starting with an upper-case letter is taken to be a type. Unresolvable guesses (constants such as
/// `MAX.foo`) are harmless, since `link` drops names that are not project types.
private bool[string] references(ref const Tree tree, string code, size_t decl)
{
    bool[string] refs;
    foreach (ref n; tree.nodes[decl + 1 .. tree.nodes[decl].end])
    {
        switch (n.kind)
        {
        case NodeKind.typeName:
            addReference(refs, tree.text(code, n.first, n.last).idup);
            break;
        case NodeKind.scopedTypeName:
        {
            // a qualified name: `Map.Entry` contributes "Map.Entry" and its scope "Map", but not "Entry" alone
            // (the parser does not report the parts of a qualified name separately)
            // At most maxQualifiedPrefixes of them, the shortest, so a crafted name of many segments does not yield
            // a quadratic amount of work.
            auto text = stripWs(tree.text(code, n.first, n.last));
            ptrdiff_t dot = text.indexOf('.');
            // A prefix longer than maxFqnBytes would be dropped by addReference anyway.
            for (size_t count = 0; dot >= 0 && count < maxQualifiedPrefixes && dot <= maxFqnBytes; count++)
            {
                addReference(refs, text[0 .. dot]);
                const next = text[dot + 1 .. $].indexOf('.');
                dot = next < 0 ? -1 : dot + 1 + next;
            }
            addReference(refs, text);
            break;
        }
        case NodeKind.annotationName:
            addReference(refs, stripWs(tree.text(code, n.first, n.last)));
            break;
        case NodeKind.memberObject:
        {
            const s = tree.text(code, n.first, n.last);
            if (isUpperStart(s)) addReference(refs, s.idup);
            break;
        }
        default:
            break;
        }
    }
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
    // The single-type imports of each import list by simple name; the types of a file share one list, so each file's
    // imports are indexed once.
    string[][string][const(string)*] importIndex;
    foreach (fqn; order)
    {
        auto t = byFqn[fqn];
        auto imports = t.explicitImports.ptr in importIndex;
        if (imports is null)
        {
            importIndex[t.explicitImports.ptr] = bySimpleName(t.explicitImports);
            imports = t.explicitImports.ptr in importIndex;
        }
        bool[string] out_;
        foreach (r, _; t.references)
        {
            auto target = resolve(r, t, *imports, byFqn);
            if (target !is null && target != t.fqn) out_[target] = true;
        }
        linked.dependencies[fqn] = out_;
        linked.types ~= t;
    }
    return linked;
}

/// Single-type imports by simple name (the last segment: `a.b.C` under "C"), each list in import order and at most
/// `maxImportCandidates` long. An import without a dot names no package member and is left out. Lets `resolve` find the imports that match a name without
/// scanning them all for every reference.
private string[][string] bySimpleName(const string[] imports)
{
    string[][string] index;
    foreach (imp; imports)
    {
        const dot = imp.lastIndexOf('.');
        if (dot >= 0)
        {
            auto same = imp[dot + 1 .. $] in index;
            if (same is null) index[imp[dot + 1 .. $]] = [imp];
            else if (same.length < maxImportCandidates) *same ~= imp;
        }
    }
    return index;
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
private string resolve(string reference, ref const TypeDecl from, const string[][string] imports,
    const TypeDecl[string] known)
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
    // The single-type imports whose last segment is `first`.
    if (auto matching = first in imports) candidates ~= *matching;
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
