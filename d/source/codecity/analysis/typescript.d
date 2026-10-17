/// TypeScript support (tree-sitter). Directories are districts; classes, interfaces, enums and type aliases are
/// buildings; each file's top-level functions and constants form one "module" building named after the file.
module codecity.analysis.typescript;

import codecity.analysis : Context, Linked, ParsedFile;
import codecity.analysis.java : relativeTo;
import codecity.analysis.syntax;
import codecity.analysis.text;
import codecity.c.treesitter;
import codecity.model;
import core.sync.mutex : Mutex;
import std.algorithm : canFind, endsWith, startsWith;
import std.array : join, replace, split;
import std.file : exists, isFile;
import std.path : baseName, buildPath, dirName;
import std.string : lastIndexOf;
import std.utf : count;

private immutable string[] functionValues = ["arrow_function", "function_expression", "function", "generator_function"];
private immutable string[] decisions = [
    "if_statement", "for_statement", "for_in_statement", "while_statement", "do_statement", "switch_case",
    "catch_clause", "ternary_expression",
];
private immutable string[] sourceExts = [".ts", ".tsx", ".mts", ".cts"];

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
    /// Top-level declared name → fqn of the building that holds it.
    string[string] names;
    string defaultExport;
}

ParsedFile parse(string root, string file, string code)
{
    const tsx = file.endsWith(".tsx");
    auto tree = tsx ? codecity.analysis.syntax.parse("tsx", &tree_sitter_tsx, code)
        : codecity.analysis.syntax.parse("typescript", &tree_sitter_typescript, code);
    if (tree is null) return ParsedFile(null, null, true);
    scope (exit) ts_tree_delete(tree);
    auto relative = relativeTo(file, root);
    const project = projectOf(root, file.dirName);
    auto dir = relative.dirName;
    if (dir == ".") dir = "";
    string pkg = project.length == 0 ? dir : dir == project ? "" : dir[project.length + 1 .. $];
    const fileName = file.baseName;
    auto st = new FileState(Src(code), lines(code), file, relative, pkg, project,
        inTestDirectory(relative) || isTestFile(fileName));
    auto rootNode = ts_tree_root_node(tree);
    st.visitStatements(rootNode, "");
    st.finishModule(fileName);
    auto ctx = new TsContext;
    ctx.path = st.path;
    ctx.imports = st.imports;
    ctx.names = st.names;
    ctx.defaultExport = st.defaultExport;
    return ParsedFile(st.types, ctx, ts_node_has_error(rootNode));
}

private final class FileState
{
    Src src;
    string[] lines;
    string file, path, pkg, module_;
    bool test;
    TypeDecl[] types;
    Import[] imports;
    string[string] names;
    bool[string] usedIds;
    string defaultExport;
    // the file's "module" building: free functions and top-level constants
    MethodInfo[] functions;
    bool[string] moduleRefs;
    string[] moduleNames;
    uint moduleAttributes, moduleLoc;

    this(Src src, string[] lines, string file, string path, string pkg, string module_, bool test)
    {
        this.src = src;
        this.lines = lines;
        this.file = file;
        this.path = path;
        this.pkg = pkg;
        this.module_ = module_;
        this.test = test;
    }

    uint loc(TSNode n) { return countLoc(lines, firstLine(n), lastLine(n)); }

    void visitStatements(TSNode container, string prefix)
    {
        foreach (c; namedChildren(container))
        {
            TSNode decl = c;
            bool isDefault;
            switch (kind(c))
            {
            case "import_statement":
                addImport(c);
                continue;
            case "export_statement":
                foreach (x; children(c)) if (kind(x) == "default") isDefault = true;
                auto d = child(c, "declaration");
                if (isNull(d))
                {
                    auto value = child(c, "value");
                    if (!isNull(value) && isDefault) exportDefaultExpression(value);
                    continue;
                }
                decl = d;
                break;
            case "ambient_declaration", "expression_statement":
                if (ts_node_named_child_count(c) == 0) continue;
                decl = ts_node_named_child(c, 0);
                if (kind(c) == "expression_statement" && kind(decl) != "internal_module")
                {
                    addStatement(c, decl);
                    continue;
                }
                break;
            default:
                break;
            }
            auto declared = declaration(decl, prefix);
            if (isDefault && declared !is null) defaultExport = declared;
        }
    }

    /// Handles one declaration; returns the declared name (for default exports).
    string declaration(TSNode decl, string prefix)
    {
        switch (kind(decl))
        {
        case "class_declaration", "abstract_class_declaration", "class":
            return addClass(decl, prefix, null);
        case "interface_declaration":
            const name = prefix ~ src.fieldText(decl, "name");
            MethodInfo[] methods;
            const attributes = countMembers(child(decl, "body"), methods);
            addType(name, "interface", decl, attributes, methods);
            return name;
        case "enum_declaration":
            const name = prefix ~ src.fieldText(decl, "name");
            auto b = child(decl, "body");
            addType(name, "enum", decl, isNull(b) ? 0 : ts_node_named_child_count(b), null);
            return name;
        case "type_alias_declaration":
            const name = prefix ~ src.fieldText(decl, "name");
            MethodInfo[] methods;
            auto v = child(decl, "value");
            const attributes = !isNull(v) && kind(v) == "object_type" ? countMembers(v, methods) : 0;
            addType(name, "type", decl, attributes, methods);
            return name;
        case "function_declaration", "generator_function_declaration":
            const name = prefix ~ src.fieldText(decl, "name");
            addFunction(name, decl, decl);
            return name;
        case "lexical_declaration", "variable_declaration":
            string last;
            foreach (d; namedChildren(decl))
            {
                if (kind(d) != "variable_declarator") continue;
                const simple = src.fieldText(d, "name").idup;
                const name = prefix ~ simple;
                auto v = child(d, "value");
                if (!isNull(v) && functionValues.canFind(kind(v))) addFunction(name, v, decl);
                else if (!isNull(v) && kind(v) == "class") addClass(v, prefix, simple);
                else
                {
                    moduleAttributes++;
                    moduleLoc += loc(decl);
                    moduleNames ~= name;
                    collectRefs(src, decl, moduleRefs);
                }
                last = name;
            }
            return last;
        case "internal_module", "module":
            const name = src.fieldText(decl, "name").replace("'", "").replace("\"", "");
            auto body_ = child(decl, "body");
            if (!isNull(body_)) visitStatements(body_, prefix ~ name ~ ".");
            return null;
        default:
            return null;
        }
    }

    void exportDefaultExpression(TSNode value)
    {
        switch (kind(value))
        {
        case "identifier":
            defaultExport = src.text(value).idup;
            break;
        case "arrow_function", "function_expression", "function":
            addFunction("default", value, value);
            defaultExport = "default";
            break;
        case "class":
            defaultExport = addClass(value, "", "default");
            break;
        default:
            break;
        }
    }

    void addImport(TSNode stmt)
    {
        auto source = child(stmt, "source");
        if (isNull(source)) return;
        string from = src.text(source).idup;
        while (from.length && (from[0] == '\'' || from[0] == '"' || from[0] == '`')) from = from[1 .. $];
        while (from.length && (from[$ - 1] == '\'' || from[$ - 1] == '"' || from[$ - 1] == '`')) from = from[0 .. $ - 1];
        foreach (clause; namedChildren(stmt))
        {
            if (kind(clause) != "import_clause") continue;
            foreach (part; namedChildren(clause))
            {
                switch (kind(part))
                {
                case "identifier":
                    imports ~= Import(src.text(part).idup, from, "default");
                    break;
                case "namespace_import":
                    foreach (id; namedChildren(part)) imports ~= Import(src.text(id).idup, from, "*");
                    break;
                case "named_imports":
                    foreach (spec; namedChildren(part))
                    {
                        if (kind(spec) != "import_specifier") continue;
                        const name = src.fieldText(spec, "name").idup;
                        auto alias_ = child(spec, "alias");
                        imports ~= Import(isNull(alias_) ? name : src.text(alias_).idup, from, name);
                    }
                    break;
                default:
                    break;
                }
            }
        }
    }

    string id(string name, string kind_)
    {
        string id = path ~ ":" ~ name;
        if (id in usedIds) id = id ~ "#" ~ kind_; // declaration merging: an interface and a class with the same name
        usedIds[id] = true;
        if (name !in names) names[name] = id;
        return id;
    }

    void addType(string name, string kind_, TSNode decl, uint noa, MethodInfo[] methods)
    {
        bool[string] refs;
        collectRefs(src, decl, refs);
        const begin = firstLine(decl), end = lastLine(decl);
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

    string addClass(TSNode decl, string prefix, string fallback)
    {
        auto nameNode = child(decl, "name");
        string simple;
        if (!isNull(nameNode)) simple = src.text(nameNode).idup;
        else if (fallback !is null) simple = fallback;
        else return null;
        const name = prefix ~ simple;
        MethodInfo[] methods;
        uint attributes;
        auto body_ = child(decl, "body");
        if (!isNull(body_))
        {
            foreach (member; namedChildren(body_))
            {
                switch (kind(member))
                {
                case "method_definition":
                    methods ~= method(member, child(member, "name"), member, true);
                    if (src.fieldText(member, "name") == "constructor") attributes += parameterProperties(member);
                    break;
                case "abstract_method_signature", "method_signature":
                    methods ~= method(member, child(member, "name"), member, false);
                    break;
                case "public_field_definition":
                    auto v = child(member, "value");
                    if (!isNull(v) && functionValues.canFind(kind(v)))
                        methods ~= method(v, child(member, "name"), member, true);
                    else
                        attributes++;
                    break;
                default:
                    break;
                }
            }
        }
        addType(name, kind(decl) == "abstract_class_declaration" ? "abstract" : "class", decl, attributes, methods);
        return name;
    }

    uint countMembers(TSNode body_, ref MethodInfo[] methods)
    {
        if (isNull(body_)) return 0;
        uint attributes;
        foreach (m; namedChildren(body_))
        {
            switch (kind(m))
            {
            case "property_signature":
                attributes++;
                break;
            case "method_signature", "call_signature", "construct_signature":
                methods ~= method(m, child(m, "name"), m, false);
                break;
            default:
                break;
            }
        }
        return attributes;
    }

    /// `measured`: whether `body_` is measured for complexity (signatures have no body: complexity 1).
    MethodInfo method(TSNode node, TSNode nameNode, TSNode body_, bool measured)
    {
        const name = isNull(nameNode) ? "(anonymous)" : src.text(nameNode).idup;
        auto params = child(node, "parameters");
        const parameters = !isNull(params) ? ts_node_named_child_count(params) : (isNull(child(node, "parameter")) ? 0 : 1);
        return MethodInfo(name, firstLine(node), loc(node), measured ? complexity(body_) : 1, parameters);
    }

    void addFunction(string name, TSNode fn, TSNode statement)
    {
        auto m = method(fn, TSNode.init, fn, true);
        m.name = name;
        functions ~= m;
        moduleLoc += loc(statement);
        moduleNames ~= name;
        collectRefs(src, statement, moduleRefs);
    }

    /// Top-level code: `describe("cart", () => …)` or `app.get("/", handler)` becomes a method named after the
    /// call, so test suites and route tables show up; anything else just adds lines and references.
    void addStatement(TSNode statement, TSNode expression)
    {
        moduleLoc += loc(statement);
        collectRefs(src, statement, moduleRefs);
        if (kind(expression) != "call_expression") return;
        auto args = child(expression, "arguments");
        if (isNull(args)) return;
        bool hasCallback;
        string label;
        bool hasLabel;
        foreach (arg; namedChildren(args))
        {
            if (functionValues.canFind(kind(arg))) hasCallback = true;
            if (!hasLabel && (kind(arg) == "string" || kind(arg) == "template_string"))
            {
                label = src.text(arg).idup;
                hasLabel = true;
            }
        }
        if (!hasCallback) return;
        const callee = src.fieldText(expression, "function");
        if (label.count > 48)
        {
            import std.range : take;
            import std.conv : to;
            label = label.take(47).to!string ~ "…";
        }
        functions ~= MethodInfo(callee ~ "(" ~ label ~ ")", firstLine(statement), loc(statement), complexity(args), 0);
    }

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

/// Constructor parameters with an accessibility modifier (`private x: T`) declare fields.
private uint parameterProperties(TSNode constructor)
{
    auto params = child(constructor, "parameters");
    if (isNull(params)) return 0;
    uint n;
    foreach (p; namedChildren(params))
        foreach (c; namedChildren(p))
            if (kind(c) == "accessibility_modifier" || kind(c) == "readonly")
            {
                n++;
                break;
            }
    return n;
}

private void collectRefs(ref Src src, TSNode node, ref bool[string] refs)
{
    walk(node, (TSNode n) {
        const k = kind(n);
        if (k == "type_identifier" || k == "identifier") refs[src.text(n).idup] = true;
        return true;
    });
}

uint complexity(TSNode body_)
{
    uint cx = 1;
    walk(body_, (TSNode n) {
        const k = kind(n);
        if (decisions.canFind(k)) cx++;
        else if (k == "binary_expression")
        {
            const op = operator(n);
            if (op == "&&" || op == "||" || op == "??") cx++;
        }
        return true;
    });
    return cx;
}

private __gshared string[string] projectCache;
private __gshared Mutex projectLock;

/// Created on first use (no module constructor: those would form an import cycle between the analysers).
private Mutex projectLockInstance()
{
    import std.concurrency : initOnce;
    return initOnce!projectLock(new Mutex);
}

/// Directory of the nearest package.json at or above `dir` (relative to root, "" for the root itself).
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

// ------------------------------------------------------------------ linking

Linked link(string root, ParsedFile[] files)
{
    TsContext[string] byPath;
    string[][string] byKey;
    string[][string] keysByLast;
    Linked linked;
    foreach (f; files)
    {
        linked.types ~= f.types;
        if (auto c = cast(TsContext) f.context)
        {
            byPath[c.path] = c;
            foreach (key; keys(c.path))
            {
                byKey[key] ~= c.path;
                const slash = key.lastIndexOf('/');
                keysByLast[slash < 0 ? key : key[slash + 1 .. $]] ~= key;
            }
        }
    }
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
private string[] keys(string path)
{
    const noExt = stripExt(path, sourceExts);
    string[] result = [noExt];
    if (noExt == "index") result ~= "";
    else if (noExt.endsWith("/index")) result ~= noExt[0 .. $ - "/index".length];
    return result;
}

/// Resolves an import specifier to a project file path, or "" for external packages.
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
    if (tail == s && !s.canFind('/')) return ""; // a bare package name like "react"
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
