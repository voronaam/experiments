/// Rust support (tree-sitter). Crates and modules are districts; structs, enums, unions and traits are buildings
/// whose methods are collected from every `impl` block in the crate; each module's free functions and constants
/// form a "module" building. `use` paths are resolved so dependencies connect across files.
module codecity.analysis.rustlang;

import codecity.analysis : Context, Linked, ParsedFile;
import codecity.analysis.java : isUpperStart, relativeTo;
import codecity.analysis.syntax;
import codecity.analysis.text;
import codecity.c.treesitter;
import codecity.model;
import core.sync.mutex : Mutex;
import std.algorithm : canFind, endsWith, startsWith;
import std.array : join, replace, split;
import std.file : exists, isFile, readText;
import std.path : baseName, buildPath, dirName, pathSplitter;
import std.regex : ctRegex, matchFirst;
import std.uni : isLower, isWhite;

enum functionsId = "{fn}";

/// Name-resolution scope of one module.
final class Scope
{
    /// Absolute module path, starting with the crate name.
    string[] path;
    /// alias → path as written in the `use` declaration
    string[][string] uses;
    /// paths imported with `::*`
    string[][] globs;

    this(string[] path) { this.path = path; }

    string crate() const { return path[0]; }
}

/// An `impl` block, attached to its type during linking.
struct Impl
{
    Scope scope_;
    string type;
    string trait_;
    MethodInfo[] methods;
    bool[string] refs;
    uint loc;
    string file;
    uint line;
    bool test;
}

final class RustContext : Context
{
    /// type / module-building fqn → its scope
    Scope[string] scopeOf;
    Scope[] scopes;
    Impl[] impls;
}

private struct Crate
{
    string name;
    string dir;
}

ParsedFile parse(string root, string file, string code)
{
    auto tree = codecity.analysis.syntax.parse("rust", &tree_sitter_rust, code);
    if (tree is null) return ParsedFile(null, null, true);
    scope (exit) ts_tree_delete(tree);
    auto crate = crateOf(root, file.dirName);
    const inCrate = relativeTo(file, crate.dir);
    const sourceRoot = sourceRootOf(crate.dir, file.dirName);
    auto modulePath = modulePathOf(relativeTo(file, sourceRoot));
    const first = inCrate.pathSplitter.front;
    const test = first == "tests" || first == "benches";

    auto st = new FileState(Src(code), lines(code), file, crate.name);
    auto scope_ = st.addScope(new Scope([crate.name] ~ modulePath));
    auto rootNode = ts_tree_root_node(tree);
    st.visitItems(rootNode, scope_, scope_, file.baseName, test);
    return ParsedFile(st.types, st.ctx, ts_node_has_error(rootNode));
}

/// The directory module paths are relative to: the nearest one (within the crate) holding `lib.rs` or `main.rs`.
private string sourceRootOf(string crateDir, string dir)
{
    string d = dir;
    while (d.startsWith(crateDir))
    {
        foreach (f; ["lib.rs", "main.rs"])
        {
            const p = buildPath(d, f);
            if (p.exists && p.isFile) return d;
        }
        if (d == crateDir) break;
        d = d.dirName;
    }
    return crateDir;
}

/// `src/lib.rs` → [], `src/net/mod.rs` → [net], `src/net/http.rs` → [net, http].
string[] modulePathOf(string inCrate)
{
    string[] parts;
    foreach (p; inCrate.pathSplitter) parts ~= p;
    if (parts.length && parts[0] == "src") parts = parts[1 .. $];
    if (parts.length == 0) return parts;
    string last = parts[$ - 1];
    parts = parts[0 .. $ - 1];
    if (last.endsWith(".rs")) last = last[0 .. $ - 3];
    const crateRoot = parts.length == 0 && (last == "lib" || last == "main");
    if (!crateRoot && last != "mod") parts ~= last;
    return parts;
}

private __gshared string[string] crateNames; // dir → crate name ("" = no [package])
private __gshared Mutex crateLock;

/// Created on first use (no module constructor: those would form an import cycle between the analysers).
private Mutex crateLockInstance()
{
    import std.concurrency : initOnce;
    return initOnce!crateLock(new Mutex);
}

private Crate crateOf(string root, string dir)
{
    string d = dir;
    while (d.startsWith(root))
    {
        string name;
        {
            crateLockInstance.lock();
            scope (exit) crateLockInstance.unlock();
            if (auto hit = d in crateNames) name = *hit;
            else crateNames[d] = name = readCrateName(d);
        }
        if (name.length) return Crate(name, d);
        if (d == root) break;
        d = d.dirName;
    }
    return Crate(root.baseName.replace("-", "_"), root);
}

private string readCrateName(string dir)
{
    const manifest = buildPath(dir, "Cargo.toml");
    if (!manifest.exists || !manifest.isFile) return "";
    try
    {
        auto m = readText(manifest).matchFirst(ctRegex!(`(?ms)^\[package\][^\[]*?^\s*name\s*=\s*"([^"]+)"`));
        return m.empty ? "" : m[1].replace("-", "_");
    }
    catch (Exception)
        return "";
}

private final class FileState
{
    Src src;
    string[] lines;
    string file, module_;
    TypeDecl[] types;
    RustContext ctx;

    this(Src src, string[] lines, string file, string module_)
    {
        this.src = src;
        this.lines = lines;
        this.file = file;
        this.module_ = module_;
        ctx = new RustContext;
    }

    Scope addScope(Scope s)
    {
        ctx.scopes ~= s;
        return s;
    }

    static string packageOf(Scope s) { return s.path[1 .. $].join("::"); }

    static string fqn(Scope s, string name) { return s.path.join("::") ~ "::" ~ name; }

    uint loc(TSNode n) { return countLoc(lines, firstLine(n), lastLine(n)); }

    /// `district`: scope whose module path decides the district; differs from `scope_` inside `#[cfg(test)]`
    /// modules, which stay in their parent's district instead of adding one.
    void visitItems(TSNode container, Scope scope_, Scope district, string label, bool test)
    {
        MethodInfo[] functions;
        bool[string] functionRefs;
        uint constants, moduleLoc;
        uint first = uint.max, last = 0;
        bool cfgTest;
        foreach (item; namedChildren(container))
        {
            const k = kind(item);
            if (k == "attribute_item")
            {
                string attr;
                foreach (dchar c; src.text(item)) if (c != ' ') attr ~= c;
                if (attr.canFind("cfg(test)")) cfgTest = true;
                continue;
            }
            const isTest = test || cfgTest;
            switch (k)
            {
            case "use_declaration":
                auto arg = child(item, "argument");
                if (!isNull(arg)) expandUse(arg, null, scope_);
                break;
            case "struct_item", "union_item":
                addType(item, scope_, district, k == "union_item" ? "union" : "struct", fieldCount(child(item, "body")), null, isTest);
                break;
            case "enum_item":
                uint variants;
                auto b = child(item, "body");
                if (!isNull(b)) foreach (v; namedChildren(b)) if (kind(v) == "enum_variant") variants++;
                addType(item, scope_, district, "enum", variants, null, isTest);
                break;
            case "trait_item":
                addType(item, scope_, district, "trait", 0, functionsIn(child(item, "body")), isTest);
                break;
            case "impl_item":
                addImpl(item, scope_, isTest);
                break;
            case "function_item", "const_item", "static_item":
                if (k == "function_item") functions ~= function_(item);
                else constants++;
                collectRefs(src, item, functionRefs);
                moduleLoc += loc(item);
                if (firstLine(item) < first) first = firstLine(item);
                if (lastLine(item) > last) last = lastLine(item);
                break;
            case "mod_item":
                auto body_ = child(item, "body");
                if (!isNull(body_))
                {
                    const name = src.fieldText(item, "name").idup;
                    auto childScope = addScope(new Scope(scope_.path ~ name));
                    visitItems(body_, childScope, cfgTest ? district : childScope, "mod " ~ name, isTest);
                }
                break;
            default:
                break;
            }
            cfgTest = false;
        }
        if (functions.length || constants > 0)
        {
            const id = fqn(scope_, functionsId);
            ctx.scopeOf[id] = scope_;
            TypeDecl t;
            t.language = Language.rust;
            t.fqn = id;
            t.name = label;
            t.pkg = packageOf(district);
            t.module_ = module_;
            t.kind = "module";
            t.file = file;
            t.line = first;
            t.endLine = last;
            t.loc = moduleLoc;
            t.noa = constants;
            t.test = test;
            t.methods = functions;
            t.references = functionRefs;
            types ~= t;
        }
    }

    void addType(TSNode item, Scope scope_, Scope district, string kind_, uint noa, MethodInfo[] methods, bool test)
    {
        const name = src.fieldText(item, "name").idup;
        bool[string] refs;
        collectRefs(src, item, refs);
        const id = fqn(scope_, name);
        ctx.scopeOf[id] = scope_;
        TypeDecl t;
        t.language = Language.rust;
        t.fqn = id;
        t.name = name;
        t.pkg = packageOf(district);
        t.module_ = module_;
        t.kind = kind_;
        t.file = file;
        t.line = firstLine(item);
        t.endLine = lastLine(item);
        t.loc = loc(item);
        t.noa = noa;
        t.test = test;
        t.methods = methods;
        t.references = refs;
        types ~= t;
    }

    void addImpl(TSNode item, Scope scope_, bool test)
    {
        auto body_ = child(item, "body");
        bool[string] refs;
        collectRefs(src, isNull(body_) ? item : body_, refs);
        auto traitNode = child(item, "trait");
        ctx.impls ~= Impl(scope_, typeName(child(item, "type")), isNull(traitNode) ? null : typeName(traitNode),
            functionsIn(body_), refs, loc(item), file, firstLine(item), test);
    }

    /// `S<T>` → `S`; `crate::m::Other` stays a path; references are unwrapped.
    string typeName(TSNode n)
    {
        if (isNull(n)) return "";
        switch (kind(n))
        {
        case "generic_type", "reference_type", "pointer_type":
            return typeName(child(n, "type"));
        default:
            return src.text(n).idup;
        }
    }

    MethodInfo[] functionsIn(TSNode body_)
    {
        MethodInfo[] list;
        if (isNull(body_)) return list;
        foreach (m; namedChildren(body_))
            if (kind(m) == "function_item" || kind(m) == "function_signature_item") list ~= function_(m);
        return list;
    }

    MethodInfo function_(TSNode f)
    {
        uint parameters;
        auto p = child(f, "parameters");
        if (!isNull(p)) foreach (c; namedChildren(p)) if (kind(c) == "parameter") parameters++;
        auto body_ = child(f, "body");
        return MethodInfo(src.fieldText(f, "name").idup, firstLine(f), loc(f), isNull(body_) ? 1 : complexity(body_), parameters);
    }

    /// Flattens a use tree into alias → path entries and glob imports.
    void expandUse(TSNode n, string[] prefix, Scope scope_)
    {
        switch (kind(n))
        {
        case "identifier", "crate", "self", "super", "metavariable":
            const name = src.text(n).idup;
            if (name == "self" && prefix.length) scope_.uses[prefix[$ - 1]] = prefix.dup; // a::{self}
            else scope_.uses[name] = prefix ~ name;
            break;
        case "scoped_identifier":
            auto path = prefix ~ segments(n);
            string alias_ = path.length ? path[$ - 1] : "";
            if (alias_ == "self")
            {
                path = path[0 .. $ - 1];
                alias_ = path.length ? path[$ - 1] : "self";
            }
            scope_.uses[alias_] = path;
            break;
        case "use_as_clause":
            const alias_ = src.fieldText(n, "alias").idup;
            if (alias_ != "_") scope_.uses[alias_] = prefix ~ segments(child(n, "path"));
            break;
        case "scoped_use_list":
            auto list = child(n, "list");
            if (!isNull(list)) expandUse(list, prefix ~ segments(child(n, "path")), scope_);
            break;
        case "use_list":
            foreach (c; namedChildren(n)) expandUse(c, prefix, scope_);
            break;
        case "use_wildcard":
            scope_.globs ~= prefix ~ (ts_node_named_child_count(n) ? segments(ts_node_named_child(n, 0)) : null);
            break;
        default:
            break;
        }
    }

    string[] segments(TSNode n)
    {
        if (isNull(n)) return null;
        if (kind(n) == "scoped_identifier" || kind(n) == "scoped_type_identifier")
            return segments(child(n, "path")) ~ src.fieldText(n, "name").idup;
        return [src.text(n).idup];
    }
}

private uint fieldCount(TSNode body_)
{
    if (isNull(body_)) return 0;
    uint n;
    if (kind(body_) == "field_declaration_list")
    {
        foreach (f; namedChildren(body_)) if (kind(f) == "field_declaration") n++;
        return n;
    }
    // tuple struct: count the "type" fields
    foreach (i; 0 .. ts_node_child_count(body_)) if (fieldNameForChild(body_, i) == "type") n++;
    return n;
}

private void collectRefs(ref Src src, TSNode node, ref bool[string] refs)
{
    walk(node, (TSNode n) {
        switch (kind(n))
        {
        case "type_identifier":
            refs[src.text(n).idup] = true;
            return true;
        case "scoped_type_identifier", "scoped_identifier":
            string s;
            foreach (dchar c; src.text(n)) if (!isWhite(c)) s ~= c;
            refs[s] = true;
            return false;
        case "identifier":
            const s = src.text(n);
            if (isUpperStart(s)) refs[s.idup] = true;
            return true;
        default:
            return true;
        }
    });
}

uint complexity(TSNode body_)
{
    int cx = 1;
    walk(body_, (TSNode n) {
        switch (kind(n))
        {
        case "if_expression", "while_expression", "loop_expression", "for_expression", "match_arm":
            cx++;
            break;
        case "match_expression":
            cx--; // a match with k arms adds k - 1 paths
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
    return cx < 1 ? 1 : cast(uint) cx;
}

// ------------------------------------------------------------------ linking

Linked link(ParsedFile[] files)
{
    string[] order;
    TypeDecl[string] byFqn;
    Scope[string] scopes;
    Impl[] impls;
    bool[string] crates;
    foreach (f; files)
    {
        foreach (t; f.types)
            if (t.fqn !in byFqn)
            {
                order ~= t.fqn;
                byFqn[t.fqn] = t;
            }
        if (auto c = cast(RustContext) f.context)
        {
            foreach (fqn, s; c.scopeOf) if (fqn !in scopes) scopes[fqn] = s;
            impls ~= c.impls;
            foreach (s; c.scopes) crates[s.crate] = true;
        }
    }
    bool[string] known;
    foreach (k; byFqn.keys) known[k] = true;
    auto resolver = Resolver(known, crates);

    // Attach impl blocks to their types; impls of foreign types go to the module's function building.
    Impl[][string] attached;
    bool[string][string] extraDeps;
    foreach (ref imp; impls)
    {
        auto target = resolver.resolveType(imp.type, imp.scope_);
        if (target !is null && byFqn[target].kind == "module") target = null;
        if (target is null)
        {
            target = imp.scope_.path.join("::") ~ "::" ~ functionsId;
            if (target !in byFqn)
            {
                string module_;
                foreach (fqn; order)
                    if (fqn.startsWith(imp.scope_.crate ~ "::"))
                    {
                        module_ = byFqn[fqn].module_;
                        break;
                    }
                TypeDecl t;
                t.language = Language.rust;
                t.fqn = target;
                t.name = imp.file.baseName;
                t.pkg = imp.scope_.path[1 .. $].join("::");
                t.module_ = module_;
                t.kind = "module";
                t.file = imp.file;
                t.line = imp.line;
                t.endLine = imp.line;
                t.test = imp.test;
                byFqn[target] = t;
                order ~= target;
                scopes[target] = imp.scope_;
            }
        }
        attached[target] ~= imp;
        if (target !in extraDeps) extraDeps[target] = null;
        if (imp.trait_ !is null)
            if (auto t = resolver.resolveType(imp.trait_, imp.scope_)) extraDeps[target][t] = true;
        foreach (r, _; imp.refs)
            if (auto t = resolver.resolve(r, imp.scope_)) extraDeps[target][t] = true;
    }

    Linked linked;
    foreach (fqn; order)
    {
        auto t = byFqn[fqn];
        if (auto list = fqn in attached)
            foreach (imp; *list)
            {
                t.methods ~= imp.methods;
                t.loc += imp.loc;
            }
        bool[string] deps;
        if (auto extra = fqn in extraDeps) foreach (d, _; *extra) deps[d] = true;
        if (auto s = fqn in scopes)
            foreach (r, _; t.references)
                if (auto target = resolver.resolve(r, *s)) deps[target] = true;
        deps.remove(fqn);
        linked.dependencies[fqn] = deps;
        linked.types ~= t;
    }
    return linked;
}

/// Resolves names as written in a scope to fqns of project types (or of a module's function building).
private struct Resolver
{
    bool[string] known;
    bool[string] crates;

    string resolveType(string written, Scope s)
    {
        auto r = resolve(written, s);
        return r !is null && !r.endsWith("::" ~ functionsId) ? r : null;
    }

    string resolve(string written, Scope s)
    {
        if (written.length == 0 || written == "Self") return null;
        import std.string : indexOf;
        const lt = written.indexOf('<');
        const cleaned = lt < 0 ? written : written[0 .. lt];
        auto segs = cleaned.split("::");
        const first = segs[0];
        string[][] candidates;
        if (auto used = first in s.uses)
            candidates ~= absolute(*used, s) ~ segs[1 .. $];
        else if (first == "crate" || first == "self" || first == "super" || first in crates)
            candidates ~= absolute(segs, s);
        else
        {
            candidates ~= s.path ~ segs;
            foreach (glob; s.globs) candidates ~= absolute(glob, s) ~ segs;
        }
        foreach (c; candidates)
        {
            const fqn = c.join("::");
            if (fqn in known) return fqn;
            // a function path like net::http::get → the function building of net::http
            if (c.length > 1)
            {
                const functions = c[0 .. $ - 1].join("::") ~ "::" ~ functionsId;
                import std.utf : decodeFront;
                auto lastSeg = c[$ - 1];
                if (functions in known && lastSeg.length && isLower(decodeFront(lastSeg))) return functions;
            }
        }
        return null;
    }

    /// Turns a use path into an absolute path that starts with a crate name.
    string[] absolute(string[] path, Scope s)
    {
        if (path.length == 0) return path;
        const first = path[0];
        if (first == "crate") return [s.crate] ~ path[1 .. $];
        if (first == "self") return s.path ~ path[1 .. $];
        if (first == "super")
        {
            auto base = s.path.dup;
            size_t i;
            while (i < path.length && path[i] == "super")
            {
                if (base.length > 1) base = base[0 .. $ - 1];
                i++;
            }
            return base ~ path[i .. $];
        }
        if (first in crates) return path;
        return s.path ~ path; // 2018-edition relative path
    }
}
