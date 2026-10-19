/// TypeScript support, parsed with the in-house `codecity.analysis.typescript_parse` (a port of the Rust edition's
/// parser, so both report the same metrics). Directories are districts; classes, interfaces, enums and type aliases
/// are buildings; each file's top-level functions and constants form one "module" building named after the file.
///
/// Naming: a type's fqn is `<path relative to root>:<name>` (`src/ui/App.tsx:App`), a module building's fqn is the
/// bare path (`src/ui/App.tsx`). The district (`pkg`) is the file's directory relative to the nearest `package.json`,
/// and that `package.json` directory is the build module, so a monorepo gets one module per npm project.
///
/// Linking follows `import` statements rather than a global name lookup, because TypeScript names are file-scoped:
/// a reference resolves to a declaration in the same file, or through the file's imports to a declaration exported
/// from another project file (see `link` and `resolveSource`). Bare package imports (`react`) are external and
/// produce no edge.
module codecity.analysis.typescript;

import codecity.analysis : Context, Linked, ParsedFile;
import codecity.analysis.java : relativeTo;
import codecity.analysis.text;
import codecity.analysis.typescript_parse : DECISION, Field, none, NodeKind, REF, Tree;
import tsparse = codecity.analysis.typescript_parse;
import codecity.model;
import core.sync.mutex : Mutex;
import std.algorithm : canFind, endsWith, startsWith;
import std.array : join, replace, split;
import std.file : exists, isFile;
import std.path : baseName, buildPath, dirName;
import std.string : lastIndexOf;
import std.utf : count;

/// Node kinds of function values: a `const f = () => …` declarator or a class field holding one is treated as a
/// function / method rather than an attribute.
private immutable NodeKind[] functionValues = [NodeKind.arrow, NodeKind.functionExpr, NodeKind.generatorExpr];
private immutable string[] sourceExts = [".ts", ".tsx", ".mts", ".cts"];

/// Whether a file name is a TypeScript source file. Declaration files (`.d.ts` etc.) only describe code that lives
/// elsewhere, so they are skipped.
bool accepts(string name) pure @safe
{
    bool source;
    foreach (e; sourceExts) if (name.endsWith(e)) source = true;
    const declaration = name.endsWith(".d.ts") || name.endsWith(".d.mts") || name.endsWith(".d.cts");
    return source && !declaration;
}

private string stripExt(string s, const string[] exts) pure @safe
{
    foreach (e; exts)
        if (s.endsWith(e)) return s[0 .. $ - e.length];
    return s;
}

/// The Jest/Vitest naming convention: `cart.test.ts`, `cart.spec.tsx`.
private bool isTestFile(string name) pure @safe
{
    const stem = stripExt(name, sourceExts);
    return stem != name && (stem.endsWith(".test") || stem.endsWith(".spec"));
}

/// An import binding: `local` names `imported` ("default", "*" or an export name) of `source`.
struct Import
{
    string local;
    string source;
    string imported;
}

/// What the linker needs to know about a file.
final class TsContext : Context
{
    /// Path relative to the root, e.g. `src/ui/App.tsx`.
    string path;
    Import[] imports;
    /// Top-level declared name → fqn of the building that holds it. Types map to their own fqn, functions and
    /// constants to the module building (the file path). The first declaration of a name wins.
    string[string] names;
    /// Name (a key of `names`) of the default export, or null. A default-exported anonymous function or class is
    /// declared under the name "default".
    string defaultExport;
}

/// Analyses one TypeScript file. `.tsx` enables JSX: in plain TypeScript `<T>x` is a type assertion, in TSX it is
/// JSX.
ParsedFile parse(string root, string file, string code)
{
    auto tree = tsparse.parse(code, file.endsWith(".tsx"));
    auto relative = relativeTo(file, root);
    const project = projectOf(root, file.dirName);
    const pkg = packageOf(project, relative);
    const fileName = file.baseName;
    auto st = new FileState(tree, lines(code), file, relative, pkg, project,
        inTestDirectory(relative) || isTestFile(fileName));
    st.visitStatements(tree.root, "");
    st.finishModule(fileName);
    auto ctx = new TsContext;
    ctx.path = st.path;
    ctx.imports = st.imports;
    ctx.names = st.names;
    ctx.defaultExport = st.defaultExport;
    return ParsedFile(st.types, ctx, tree.hasError);
}

/// Everything collected while walking one file. A class rather than a struct so that the nested methods can all
/// add to the same lists.
private final class FileState
{
    Tree tree;
    string[] lines;
    string file, path, pkg, module_;
    bool test;
    TypeDecl[] types;
    Import[] imports;
    string[string] names;
    /// Fqns handed out so far in this file, to disambiguate declaration merging (see `id`).
    bool[string] usedIds;
    string defaultExport;
    // the file's "module" building: free functions and top-level constants
    MethodInfo[] functions;
    bool[string] moduleRefs;
    string[] moduleNames;
    uint moduleAttributes, moduleLoc;

    this(Tree tree, string[] lines, string file, string path, string pkg, string module_, bool test)
    {
        this.tree = tree;
        this.lines = lines;
        this.file = file;
        this.path = path;
        this.pkg = pkg;
        this.module_ = module_;
        this.test = test;
    }

    uint loc(uint n) { return countLoc(lines, tree.firstLine(n), tree.lastLine(n)); }

    /// Text of the child of `n` with the given field, or "". Like every text kept in the results, it is a copy,
    /// so the parse cache does not keep whole source files alive.
    string fieldText(uint n, Field f)
    {
        const c = tree.field(n, f);
        return c == none ? "" : tree.text(c).idup;
    }

    /// Handles the statements of the program or of a namespace body. `prefix` is the enclosing namespace path
    /// (`NS.Inner.`), prepended to every declared name.
    ///
    /// Statements are unwrapped to the declaration they contain:
    ///   import …                  → recorded for linking
    ///   export [default] <decl>   → the declaration (field `decl`; remembered as default export for `default`)
    ///   export default <expr>     → see exportDefaultExpression (field `value`); `export { a }` and re-exports
    ///                               have neither and are ignored, as is `export declare …` (an ambient declaration
    ///                               inside an export, which is not unwrapped)
    ///   declare <decl>            → `ambient`; its first child is the declaration
    ///   any expression statement  → top-level code of the module building (addStatement)
    void visitStatements(uint container, string prefix)
    {
        foreach (c; tree.children(container))
        {
            uint decl = c;
            bool isDefault;
            switch (tree.kind(c))
            {
            case NodeKind.import_:
                addImport(c);
                continue;
            case NodeKind.export_, NodeKind.exportDefault:
                isDefault = tree.kind(c) == NodeKind.exportDefault;
                const d = tree.field(c, Field.decl);
                if (d == none)
                {
                    const value = tree.field(c, Field.value);
                    if (value != none && isDefault) exportDefaultExpression(value);
                    continue;
                }
                decl = d;
                break;
            case NodeKind.ambient:
                if (tree.children(c).length == 0) continue;
                decl = tree.children(c)[0];
                break;
            case NodeKind.exprStmt:
                if (tree.children(c).length == 0) continue;
                addStatement(c, tree.children(c)[0]);
                continue;
            default:
                break;
            }
            auto declared = declaration(decl, prefix);
            if (isDefault && declared !is null) defaultExport = declared;
        }
    }

    /// Handles one declaration; returns the declared name (for default exports).
    ///
    /// Classes, interfaces, enums and type aliases become buildings. Functions and `const`/`let`/`var` declarations
    /// go into the file's module building: a variable holding a function counts as a function (method), one
    /// holding a class expression as a class, any other variable as an attribute of the module. Namespaces
    /// (`namespace X {}`, `module X {}`, `declare module "x" {}`) are not buildings: their contents are visited
    /// with the namespace name as prefix. Returns null when nothing nameable was declared.
    string declaration(uint decl, string prefix)
    {
        switch (tree.kind(decl))
        {
        // A class expression without a name is skipped by addClass (it returns null).
        case NodeKind.classDecl, NodeKind.abstractClassDecl, NodeKind.classExpr:
            return addClass(decl, prefix, null);
        case NodeKind.interface_:
            const name = prefix ~ fieldText(decl, Field.name);
            MethodInfo[] methods;
            const attributes = countMembers(tree.field(decl, Field.body_), methods);
            addType(name, "interface", decl, attributes, methods);
            return name;
        case NodeKind.enum_:
            const name = prefix ~ fieldText(decl, Field.name);
            const b = tree.field(decl, Field.body_);
            // Every enum member is an attribute.
            addType(name, "enum", decl, b == none ? 0 : cast(uint) tree.children(b).length, null);
            return name;
        case NodeKind.typeAlias:
            const name = prefix ~ fieldText(decl, Field.name);
            MethodInfo[] methods;
            const v = tree.field(decl, Field.value);
            // Only an object type literal (`type P = { x: number }`) has members; unions, mapped types etc. have none.
            const attributes = v != none && tree.kind(v) == NodeKind.objectType ? countMembers(v, methods) : 0;
            addType(name, "type", decl, attributes, methods);
            return name;
        case NodeKind.functionDecl, NodeKind.generatorDecl:
            const name = prefix ~ fieldText(decl, Field.name);
            addFunction(name, decl, decl);
            moduleLoc += loc(decl);
            return name;
        case NodeKind.varDecl:
            // The statement's lines count once for the module building, however many declarators it has.
            string last;
            bool free;
            foreach (d; tree.children(decl))
            {
                if (tree.kind(d) != NodeKind.declarator) continue;
                const simple = fieldText(d, Field.name);
                const name = prefix ~ simple;
                const v = tree.field(d, Field.value);
                if (v != none && functionValues.canFind(tree.kind(v)))
                {
                    addFunction(name, v, decl);
                    free = true;
                }
                // `const Foo = class {}`: an anonymous class takes the variable's name.
                else if (v != none && tree.kind(v) == NodeKind.classExpr) addClass(v, prefix, simple);
                else
                {
                    moduleAttributes++;
                    moduleNames ~= name;
                    collectRefs(tree, decl, moduleRefs);
                    free = true;
                }
                last = name;
            }
            if (free) moduleLoc += loc(decl);
            return last;
        case NodeKind.module_:
            // `declare module "lib"` has a string literal as its name; drop the quotes.
            const name = fieldText(decl, Field.name).replace("'", "").replace("\"", "");
            const body_ = tree.field(decl, Field.body_);
            if (body_ != none) visitStatements(body_, prefix ~ name ~ ".");
            return null;
        default:
            return null;
        }
    }

    /// `export default <expression>`: an identifier names an existing declaration; an anonymous function or class
    /// is recorded under the name "default" so that `import X from "./file"` can still find it. Other expressions
    /// are not tracked.
    void exportDefaultExpression(uint value)
    {
        switch (tree.kind(value))
        {
        case NodeKind.ident:
            defaultExport = tree.text(value).idup;
            break;
        case NodeKind.arrow, NodeKind.functionExpr:
            addFunction("default", value, value);
            moduleLoc += loc(value);
            defaultExport = "default";
            break;
        case NodeKind.classExpr:
            defaultExport = addClass(value, "", "default");
            break;
        default:
            break;
        }
    }

    /// Records the bindings of one `import` statement:
    ///   import A from "m"              → Import("A", "m", "default")
    ///   import * as NS from "m"        → Import("NS", "m", "*")
    ///   import { a, b as c } from "m"  → Import("a", "m", "a"), Import("c", "m", "b")
    /// Side-effect imports (`import "m"`) bind nothing, and `import x = require("m")` has no source. Type-only
    /// imports are treated alike.
    void addImport(uint stmt)
    {
        const source = tree.field(stmt, Field.source);
        if (source == none) return;
        // The source is a string literal; strip its quotes.
        string from = tree.text(source).idup;
        while (from.length && (from[0] == '\'' || from[0] == '"' || from[0] == '`')) from = from[1 .. $];
        while (from.length && (from[$ - 1] == '\'' || from[$ - 1] == '"' || from[$ - 1] == '`')) from = from[0 .. $ - 1];
        foreach (part; tree.children(stmt))
        {
            switch (tree.kind(part))
            {
            case NodeKind.importDefault:
                imports ~= Import(tree.text(part).idup, from, "default");
                break;
            case NodeKind.importNamespace:
                foreach (id; tree.children(part)) imports ~= Import(tree.text(id).idup, from, "*");
                break;
            case NodeKind.importSpecifier:
                const name = fieldText(part, Field.name);
                const alias_ = tree.field(part, Field.alias_);
                imports ~= Import(alias_ == none ? name : tree.text(alias_).idup, from, name);
                break;
            default:
                break;
            }
        }
    }

    /// The fqn for a type declared in this file, also registering `name` in `names` (first declaration wins).
    ///
    /// TypeScript allows several declarations with the same name (declaration merging, e.g. a class plus an
    /// interface), but fqns must be unique, so a repeated name gets its kind appended: `a.ts:X#interface`; further
    /// declarations of the same name and kind are numbered in source order: `a.ts:X#interface2`, `#interface3`, …
    string id(string name, string kind_)
    {
        import std.conv : to;
        const base = path ~ ":" ~ name;
        string id = base;
        // declaration merging: an interface and a class with the same name
        for (uint n = 1; id in usedIds; n++) id = base ~ "#" ~ kind_ ~ (n == 1 ? "" : n.to!string);
        usedIds[id] = true;
        if (name !in names) names[name] = id;
        return id;
    }

    void addType(string name, string kind_, uint decl, uint noa, MethodInfo[] methods)
    {
        bool[string] refs;
        collectRefs(tree, decl, refs);
        const begin = tree.firstLine(decl), end = tree.lastLine(decl);
        TypeDecl t;
        t.language = Language.typescript;
        t.fqn = id(name, kind_);
        t.name = name;
        t.pkg = pkg;
        t.module_ = module_;
        t.kind = kind_;
        t.file = file;
        t.line = begin;
        t.endLine = end;
        t.loc = countLoc(lines, begin, end);
        t.noa = noa;
        t.test = test;
        t.methods = methods;
        t.references = refs;
        types ~= t;
    }

    /// Adds a class building. `fallback` names an anonymous class expression (the variable it is assigned to, or
    /// "default"); without a name and fallback the class is skipped and null returned.
    ///
    /// Methods: method definitions, abstract/overload signatures (complexity 1, no body) and fields holding
    /// functions (`onClick = () => …`). Attributes: all other fields plus constructor parameter properties.
    string addClass(uint decl, string prefix, string fallback)
    {
        const nameNode = tree.field(decl, Field.name);
        string simple;
        if (nameNode != none) simple = tree.text(nameNode).idup;
        else if (fallback !is null) simple = fallback;
        else return null;
        const name = prefix ~ simple;
        MethodInfo[] methods;
        uint attributes;
        const body_ = tree.field(decl, Field.body_);
        if (body_ != none)
        {
            foreach (member; tree.children(body_))
            {
                switch (tree.kind(member))
                {
                case NodeKind.methodDef:
                    methods ~= method(member, tree.field(member, Field.name), member);
                    if (fieldText(member, Field.name) == "constructor") attributes += parameterProperties(tree, member);
                    break;
                case NodeKind.methodSig:
                    methods ~= method(member, tree.field(member, Field.name), none);
                    break;
                case NodeKind.fieldDef:
                    const v = tree.field(member, Field.value);
                    if (v != none && functionValues.canFind(tree.kind(v)))
                        methods ~= method(v, tree.field(member, Field.name), member);
                    else
                        attributes++;
                    break;
                default:
                    break;
                }
            }
        }
        addType(name, tree.kind(decl) == NodeKind.abstractClassDecl ? "abstract" : "class", decl, attributes, methods);
        return name;
    }

    /// Members of an interface body or object type literal: property signatures are attributes; method, call
    /// (`(x: T): U`) and construct (`new (x: T): U`) signatures are methods without a body. Returns the number of
    /// attributes and appends the methods. `body_` may be `none`.
    uint countMembers(uint body_, ref MethodInfo[] methods)
    {
        if (body_ == none) return 0;
        uint attributes;
        foreach (m; tree.children(body_))
        {
            switch (tree.kind(m))
            {
            case NodeKind.propertySig:
                attributes++;
                break;
            case NodeKind.methodSig, NodeKind.callSig, NodeKind.constructSig:
                methods ~= method(m, tree.field(m, Field.name), none);
                break;
            default:
                break;
            }
        }
        return attributes;
    }

    /// `node` is the function-like node (its LOC and parameters are measured); `nameNode` may be `none`, giving
    /// "(anonymous)"; `body_` is the node whose complexity is measured (`none` for signatures without a body:
    /// complexity 1). Parameters are the entries of the node's parameter list (an arrow function's single
    /// unparenthesised parameter counts as one).
    MethodInfo method(uint node, uint nameNode, uint body_)
    {
        const name = nameNode == none ? "(anonymous)" : tree.text(nameNode).idup;
        const params = tree.field(node, Field.params);
        const parameters = params == none ? 0 : cast(uint) tree.children(params).length;
        return MethodInfo(name, tree.firstLine(node), loc(node), body_ == none ? 1 : complexity(tree, body_),
            parameters);
    }

    /// Adds a top-level function to the module building. The method's LOC is the function's; the module's
    /// references are collected from the whole declaring statement. The caller adds the statement's lines to the
    /// module's LOC, once per statement.
    void addFunction(string name, uint fn, uint statement)
    {
        auto m = method(fn, none, fn);
        m.name = name;
        functions ~= m;
        moduleNames ~= name;
        collectRefs(tree, statement, moduleRefs);
    }

    /// Top-level code: `describe("cart", () => …)` or `app.get("/", handler)` becomes a method named after the
    /// call, so test suites and route tables show up; anything else just adds lines and references.
    void addStatement(uint statement, uint expression)
    {
        moduleLoc += loc(statement);
        collectRefs(tree, statement, moduleRefs);
        if (tree.kind(expression) != NodeKind.call) return;
        const args = tree.field(expression, Field.args);
        if (args == none || tree.kind(args) != NodeKind.args) return; // no arguments, or a tagged template
        bool hasCallback;
        string label;
        bool hasLabel;
        foreach (arg; tree.children(args))
        {
            if (functionValues.canFind(tree.kind(arg))) hasCallback = true;
            if (!hasLabel && (tree.kind(arg) == NodeKind.str || tree.kind(arg) == NodeKind.template_))
            {
                label = tree.text(arg);
                hasLabel = true;
            }
        }
        if (!hasCallback) return;
        // The method name is e.g. `describe("cart")`, using the first string argument verbatim (with its quotes),
        // shortened to 47 code points plus an ellipsis when longer than 48 so names stay readable in the viewer.
        const kids = tree.children(expression);
        const callee = kids.length ? tree.text(kids[0]) : "";
        if (label.count > 48)
        {
            import std.range : take;
            import std.conv : to;
            label = label.take(47).to!string ~ "…";
        }
        // Complexity covers all arguments, so it includes the callback bodies.
        functions ~= MethodInfo(callee ~ "(" ~ label ~ ")", tree.firstLine(statement), loc(statement),
            complexity(tree, args), 0);
    }

    /// Emits the module building, unless the file has no top-level functions, variables or expression statements.
    /// It spans the whole file, but its LOC counts only those (other statements such as a top-level `if` are not
    /// visited at all). Module names not already claimed by a type are registered in `names`, pointing at the
    /// module building, only now, so a type and a function with the same name resolve to the type.
    void finishModule(string fileName)
    {
        if (functions.length == 0 && moduleAttributes == 0 && moduleLoc == 0) return;
        usedIds[path] = true;
        foreach (n; moduleNames) if (n !in names) names[n] = path;
        TypeDecl t;
        t.language = Language.typescript;
        t.fqn = path;
        t.name = fileName;
        t.pkg = pkg;
        t.module_ = module_;
        t.kind = "module";
        t.file = file;
        t.line = 1;
        t.endLine = cast(uint) lines.length;
        t.loc = moduleLoc;
        t.noa = moduleAttributes;
        t.test = test;
        t.methods = functions;
        t.references = moduleRefs;
        types ~= t;
    }
}

/// Constructor parameters with an accessibility modifier (`private x: T`) declare fields. (`readonly` alone does
/// too in TypeScript, but is not counted, to keep the metric stable across CodeCity versions.)
private uint parameterProperties(ref const Tree tree, uint constructor)
{
    const params = tree.field(constructor, Field.params);
    if (params == none) return 0;
    uint n;
    foreach (p; tree.children(params))
        if (tree.kind(p) == NodeKind.paramProperty) n++;
    return n;
}

/// Every identifier the parser flagged as a possible reference (`REF`) under `node`, values and types alike:
/// `new Cart()` and `items: Cart[]` both name `Cart`. Most are locals or members; `link` keeps only names declared
/// at the top level of the file or imported. Property names (`obj.x`) are not flagged.
private void collectRefs(ref const Tree tree, uint node, ref bool[string] refs)
{
    foreach (t; tree.tokenStart(node) .. tree.tokenEnd(node))
        if (tree.tokens[t].flags & REF)
        {
            const text = tree.tokenText(t);
            if (text !in refs) refs[text.idup] = true;
        }
}

/// McCabe cyclomatic complexity: 1 + decision points (`if`, loops, `case`, `catch`, `?:`, `&&`, `||`, `??`, as
/// flagged `DECISION` by the parser). Nested functions inside `node` count towards it.
uint complexity(ref const Tree tree, uint node)
{
    uint cx = 1;
    foreach (t; tree.tokenStart(node) .. tree.tokenEnd(node))
        if (tree.tokens[t].flags & DECISION) cx++;
    return cx;
}

/// Directory → its project (as `projectOf` returns it), shared by all parsing threads (hence `__gshared`) and
/// guarded by `projectLock`. Cleared at the start of every `link`, which re-derives the projects, so a
/// `package.json` added or removed while watching is noticed by the next analysis.
private __gshared string[string] projectCache;
private __gshared Mutex projectLock;

/// Created on first use (no module constructor: those would form an import cycle between the analysers).
private Mutex projectLockInstance()
{
    import std.concurrency : initOnce;
    return initOnce!projectLock(new Mutex);
}

/// Directory of the nearest package.json at or above `dir` (relative to root, "" for the root itself).
///
/// Walks up from `dir`, stopping at `root`; "" also when there is no package.json at all. Every directory passed on
/// the way gets the result cached, so sibling files cost one lookup. The lock is held only around cache accesses,
/// not the file-system checks: two threads may compute the same directory concurrently, which is harmless since
/// they reach the same answer.
private string projectOf(string root, string dir)
{
    string[] visited;
    string project;
    string d = dir;
    while (d.startsWith(root))
    {
        {
            projectLockInstance.lock();
            scope (exit) projectLockInstance.unlock();
            if (auto hit = d in projectCache)
            {
                project = *hit;
                break;
            }
        }
        visited ~= d;
        const manifest = buildPath(d, "package.json");
        if (manifest.exists && manifest.isFile)
        {
            project = d == root ? "" : relativeTo(d, root);
            break;
        }
        if (d == root) break;
        d = d.dirName;
    }
    projectLockInstance.lock();
    scope (exit) projectLockInstance.unlock();
    foreach (v; visited) projectCache[v] = project;
    return project;
}

/// The package of a file: its directory below its project. For src/ui/App.tsx in project "" it is "src/ui"; in
/// project "web" a file web/src/App.tsx gets "src". The project directory is always an ancestor of (or equal to) dir.
private string packageOf(string project, string relative)
{
    auto dir = relative.dirName;
    if (dir == ".") dir = "";
    return project.length == 0 ? dir : dir == project ? "" : dir[project.length + 1 .. $];
}

// ------------------------------------------------------------------ linking

/// Resolves each type's references to fqns:
///
/// 1. A name declared at the top level of the same file links to that declaration's building.
/// 2. An imported name: the import's specifier is resolved to a project file (`resolveSource`, cached per importing
///    file and specifier); then the imported symbol (or, for a default import, the target's default export) is
///    looked up in that file's top-level names. Namespace imports, and symbols not found there (re-exports,
///    `export { x } from …`), link to the target file's module building instead.
/// 3. Anything else (globals, locals, external packages) is ignored.
///
/// Self-references are removed. A target fqn whose building does not exist (a module building for a file with
/// only types) is filtered out later by `SourceAnalyzer.analyze`.
///
/// Modules and packages are derived again here, with a fresh project cache: in watch mode an unchanged file keeps its
/// cached parse, which may predate a `package.json` added or removed since.
Linked link(string root, ParsedFile[] files)
{
    import std.algorithm : sort;
    {
        projectLockInstance.lock();
        scope (exit) projectLockInstance.unlock();
        projectCache.clear();
    }
    // byPath: file path → its context. byKey: import key (see `keys`) → files with that key, in TypeScript's
    // resolution order (see `keyRank`). keysByLast: last segment of a key → keys, so path aliases can be matched by
    // suffix without a full scan.
    TsContext[string] byPath;
    string[][string] byKey;
    string[][string] keysByLast;
    Linked linked;
    foreach (f; files)
    {
        // The types of a file share its project; it is looked up from the first one's file.
        string project, pkg;
        foreach (i, t; f.types)
        {
            if (i == 0)
            {
                project = projectOf(root, t.file.dirName);
                pkg = packageOf(project, relativeTo(t.file, root));
            }
            t.module_ = project;
            t.pkg = pkg;
            linked.types ~= t;
        }
        if (auto c = cast(TsContext) f.context)
        {
            byPath[c.path] = c;
            foreach (key; keys(c.path))
            {
                if (key !in byKey)
                {
                    const slash = key.lastIndexOf('/');
                    keysByLast[slash < 0 ? key : key[slash + 1 .. $]] ~= key;
                }
                byKey[key] ~= c.path;
            }
        }
    }
    foreach (key, ref paths; byKey) paths.sort!((a, b) => keyRank(key, a) < keyRank(key, b));
    string[string] resolved;
    foreach (ref t; linked.types)
    {
        auto ctx = byPath.get(relativeTo(t.file, root), null);
        if (ctx is null) continue;
        Import[string] imports;
        foreach (i; ctx.imports) imports[i.local] = i;
        bool[string] out_;
        foreach (r, _; t.references)
        {
            if (auto local = r in ctx.names)
            {
                out_[*local] = true;
                continue;
            }
            auto imp = r in imports;
            if (imp is null) continue;
            const cacheKey = ctx.path ~ "|" ~ imp.source;
            string target;
            if (auto hit = cacheKey in resolved) target = *hit;
            else resolved[cacheKey] = target = resolveSource(ctx.path, imp.source, byKey, keysByLast);
            if (target.length == 0) continue;
            auto other = byPath[target];
            string symbol = imp.imported == "default" ? other.defaultExport : imp.imported;
            string fqn;
            if (symbol !is null && imp.imported != "*")
                if (auto hit = symbol in other.names) fqn = *hit;
            out_[fqn !is null ? fqn : target] = true; // whole-module imports point at the module building
        }
        out_.remove(t.fqn);
        linked.dependencies[t.fqn] = out_;
    }
    return linked;
}

/// Module keys a file can be imported by: `src/a/b` for `src/a/b.ts`, and `src/a` for an index file.
/// An `index.ts` in the root gets the empty key.
private string[] keys(string path)
{
    const noExt = stripExt(path, sourceExts);
    string[] result = [noExt];
    if (noExt == "index") result ~= "";
    else if (noExt.endsWith("/index")) result ~= noExt[0 .. $ - "/index".length];
    return result;
}

/// TypeScript's preference among the files sharing a module key: the file itself before a directory index, and `.ts`
/// before `.tsx`, `.mts` and `.cts`. So `./a` is `a.ts`, then `a.tsx`, …, then `a/index.ts`, `a/index.tsx`, …
private size_t keyRank(string key, string path)
{
    import std.algorithm : countUntil;
    const noExt = stripExt(path, sourceExts);
    const ext = sourceExts.countUntil(path[noExt.length .. $]);
    return (noExt == key ? 0 : sourceExts.length) + (ext < 0 ? 0 : ext);
}

/// Resolves an import specifier to a project file path, or "" for external packages.
///
/// `from` is the importing file's path. The extension is stripped first, including `.js` and friends: ESM code
/// imports `./cart.js` for the file `cart.ts`. Relative specifiers are joined with the importing directory and
/// normalised; when several files share the key (`a.ts`, `a.tsx` and `a/index.ts`), the one TypeScript would pick
/// wins (see `keyRank`). Specifiers escaping the root resolve to nothing.
///
/// Specifiers starting with `@/`, `~/` or `#/` are path aliases, matched by suffix without reading tsconfig: the
/// prefix is dropped and the remaining path must equal the end of exactly one file key; from that key's files the
/// one TypeScript would pick wins. If several keys match, the import is ambiguous and yields no edge. Anything else
/// is an npm package: a bare name (`react`), a scoped package (`@scope/pkg`) or a deep import (`lodash/fp`).
string resolveSource(string from, string spec, string[][string] byKey, string[][string] keysByLast)
{
    const s = stripExt(spec, [".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx", ".mts", ".cts"]);
    if (s.startsWith("./") || s.startsWith("../") || s == "." || s == "..")
    {
        const slash = from.lastIndexOf('/');
        const dir = slash < 0 ? "" : from[0 .. slash];
        const joined = dir.length ? dir ~ "/" ~ s : s;
        bool ok;
        const key = normalize(joined, ok);
        if (!ok) return "";
        auto hits = key in byKey;
        return hits ? (*hits)[0] : "";
    }
    // Path aliases such as "@/components/Button" or "~/lib/db": match the file whose path ends that way.
    string tail = s;
    foreach (p; ["@/", "~/", "#/"])
        if (s.startsWith(p))
        {
            tail = s[p.length .. $];
            break;
        }
    if (tail == s) return ""; // a package such as "react", "@scope/pkg" or "lodash/fp"
    const slash = tail.lastIndexOf('/');
    const last = slash < 0 ? tail : tail[slash + 1 .. $];
    string found;
    if (auto candidates = last in keysByLast)
        foreach (key; *candidates)
            if (key == tail || key.endsWith("/" ~ tail))
            {
                if (found.length) return ""; // ambiguous
                found = byKey[key][0];
            }
    return found;
}

/// Resolves `.` and `..` segments and collapses empty ones in a `/`-separated path. `ok` is false when `..` would
/// climb above the start of the path (i.e. above the analysed root).
private string normalize(string path, out bool ok)
{
    string[] output;
    foreach (part; path.split("/"))
    {
        if (part == "" || part == ".") continue;
        if (part == "..")
        {
            if (output.length == 0)
            {
                ok = false;
                return null;
            }
            output = output[0 .. $ - 1];
        }
        else output ~= part;
    }
    ok = true;
    return output.join("/");
}
