/// Rust support (tree-sitter). Crates and modules are districts; structs, enums, unions and traits are buildings
/// whose methods are collected from every `impl` block in the crate; each module's free functions and constants
/// form a "module" building. `use` paths are resolved so dependencies connect across files.
///
/// Naming: fqns are absolute Rust paths starting with the crate name (`shop::cart::Cart`); a module's function
/// building is `shop::cart::{fn}` (see `functionsId`). The district is the module path without the crate
/// (`cart`), and the crate is the build module.
///
/// Unlike Java and TypeScript, a Rust type's methods are not inside its declaration: they live in `impl` blocks,
/// possibly in other files. So `parse` records each impl block in the file's `RustContext` and `link` attaches the
/// impls to their types once every file is known, adding the methods, lines and references to the type.
///
/// Every module (file or inline `mod`) gets a `Scope` with its `use` imports; `link` resolves the names a type
/// mentions in its module's scope with `Resolver`, following Rust's path rules closely enough for the city: `use`
/// aliases, `crate::`/`self::`/`super::`, other crates of the workspace, the current module and glob imports.
/// Standard library and external crate names resolve to nothing.
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

/// Last fqn segment of a module's function building. Braces cannot occur in a Rust identifier, so it never clashes
/// with a real item.
enum functionsId = "{fn}";

/// Name-resolution scope of one module.
final class Scope
{
    /// Absolute module path, starting with the crate name.
    string[] path;
    /// alias → path as written in the `use` declaration (it may start with `crate`, `self`, `super` or a crate
    /// name; `Resolver.absolute` makes it absolute when it is used)
    string[][string] uses;
    /// paths imported with `::*`
    string[][] globs;

    this(string[] path) { this.path = path; }

    string crate() const { return path[0]; }
}

/// An `impl` block, attached to its type during linking.
struct Impl
{
    /// The module the impl is written in: its names resolve there, not in the type's module.
    Scope scope_;
    /// District scope and name of a module building created for the impl, as `visitItems` would give it.
    Scope district;
    string label;
    /// Implemented type and trait as written (`Cart`, `crate::m::Cart`, `fmt::Display`); trait_ is null for an
    /// inherent impl.
    string type;
    string trait_;
    MethodInfo[] methods;
    /// Names referenced in the impl body.
    bool[string] refs;
    /// LOC of the whole impl block, added to the type's LOC.
    uint loc;
    string file;
    uint line;
    bool test;
}

/// What `link` needs from one file: the scope each building's names resolve in, and the file's impl blocks.
final class RustContext : Context
{
    /// type / module-building fqn → its scope
    Scope[string] scopeOf;
    /// Every scope of the file (its own and those of inline modules), used to collect the crate names.
    Scope[] scopes;
    Impl[] impls;
}

/// A Cargo package: its name as Rust code writes it (hyphens become underscores) and the directory of its
/// Cargo.toml.
private struct Crate
{
    string name;
    string dir;
}

/// Analyses one Rust file. The module path comes from the file's location (`src/net/http.rs` is `crate::net::http`);
/// `#[path]` attributes are not followed. Files under the crate's `tests/` or `benches/` are test code.
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

    // The crate is the build module of every type in it.
    auto st = new FileState(Src(code), lines(code), file, crate.name);
    auto scope_ = st.addScope(new Scope([crate.name] ~ modulePath));
    auto rootNode = ts_tree_root_node(tree);
    st.visitItems(rootNode, scope_, scope_, file.baseName, test);
    return ParsedFile(st.types, st.ctx, ts_node_has_error(rootNode));
}

/// The directory module paths are relative to: the nearest one (within the crate) holding `lib.rs` or `main.rs`.
/// Falls back to the crate directory, e.g. for `tests/*.rs`; `modulePathOf` then drops a leading `src`. A binary in
/// `src/bin/tool/main.rs` thus has its own source root and its items get the crate-root module path.
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
///
/// `lib.rs`/`main.rs` only denote the crate root at the top of the path; elsewhere they are ordinary modules. A
/// `mod.rs` stands for its directory.
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

/// A missing Cargo.toml also maps to "", as does a workspace root without [package]. Shared by all parsing threads
/// and guarded by `crateLock`; cleared by `link`, so each analysis reads the manifests afresh and a Cargo.toml added,
/// removed or edited while watching applies to the files parsed after it.
private __gshared string[string] crateNames; // dir → crate name ("" = no [package])
private __gshared Mutex crateLock;

/// Created on first use (no module constructor: those would form an import cycle between the analysers).
private Mutex crateLockInstance()
{
    import std.concurrency : initOnce;
    return initOnce!crateLock(new Mutex);
}

/// The crate of a file in `dir`: the nearest Cargo.toml with a `[package]` name at or above `dir`, not looking above
/// `root`. Without one, the whole tree is a single crate named after the root directory. The lock is held while a
/// manifest is read, so each directory is looked up once.
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

/// The `name` in the `[package]` table of `dir`'s Cargo.toml, with hyphens turned into underscores as the crate is
/// written in Rust paths, or "" when missing or unreadable.
///
/// A regex rather than a TOML parser: `(?m)` lets `^` match at every line start; the lines lazily skipped before
/// `name` must not start with `[` (after indentation), so the match cannot run into the next `[table]`. A `[`
/// elsewhere on a line (`authors = ["a"]`) does not stop it.
package string readCrateName(string dir)
{
    const manifest = buildPath(dir, "Cargo.toml");
    if (!manifest.exists || !manifest.isFile) return "";
    try
    {
        auto m = readText(manifest).matchFirst(ctRegex!(`(?m)^\[package\][^\n]*\n(?:[ \t\r]*(?:[^\[\s][^\n]*)?\n)*?[ \t]*name\s*=\s*"([^"]+)"`));
        return m.empty ? "" : m[1].replace("-", "_");
    }
    catch (Exception)
        return "";
}

/// Everything collected while walking one file.
private final class FileState
{
    Src src;
    string[] lines;
    /// module_ is the crate name.
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

    /// The district of a scope: its module path without the crate name ("" for the crate root).
    static string packageOf(Scope s) { return s.path[1 .. $].join("::"); }

    static string fqn(Scope s, string name) { return s.path.join("::") ~ "::" ~ name; }

    uint loc(TSNode n) { return countLoc(lines, firstLine(n), lastLine(n)); }

    /// `district`: scope whose module path decides the district; differs from `scope_` inside `#[cfg(test)]`
    /// modules, which stay in their parent's district instead of adding one.
    ///
    /// Visits the items of a file or of an inline `mod` body. Types become buildings; impl blocks are recorded for
    /// linking; free functions, `const` and `static` items are collected into the module's function building
    /// (named `label`: the file name, or `mod name` for an inline module), which is only emitted when there is at
    /// least one of them. `mod x;` without a body is skipped here: its file is parsed on its own.
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
            // In tree-sitter-rust an outer attribute is a sibling node *before* the item it applies to, so remember
            // `#[cfg(test)]` until the next item (cfgTest is reset after every item). Only spaces are removed before
            // the check, so `#[cfg( test )]` matches; `#[cfg(all(test, …))]` does not.
            if (k == "attribute_item")
            {
                string attr;
                foreach (dchar c; src.text(item)) if (c != ' ') attr ~= c;
                if (attr.canFind("cfg(test)")) cfgTest = true;
                continue;
            }
            // Applies to types, impls and inline modules. Free functions and constants always share the module
            // building, which is test code only when the whole module is.
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
            // A trait's attributes are 0; its methods are its declared functions, with or without default bodies.
            case "trait_item":
                addType(item, scope_, district, "trait", 0, functionsIn(child(item, "body")), isTest);
                break;
            case "impl_item":
                addImpl(item, scope_, district, label, isTest);
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

    /// Records an impl block. Its references come from the body only: the implemented type is the impl's target and
    /// the trait becomes a dependency through `trait_` in `link`.
    void addImpl(TSNode item, Scope scope_, Scope district, string label, bool test)
    {
        auto body_ = child(item, "body");
        bool[string] refs;
        collectRefs(src, isNull(body_) ? item : body_, refs);
        auto traitNode = child(item, "trait");
        ctx.impls ~= Impl(scope_, district, label, typeName(child(item, "type")), isNull(traitNode) ? null : typeName(traitNode),
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

    /// The functions of an impl or trait body. function_signature_item is a trait method without a default body.
    MethodInfo[] functionsIn(TSNode body_)
    {
        MethodInfo[] list;
        if (isNull(body_)) return list;
        foreach (m; namedChildren(body_))
            if (kind(m) == "function_item" || kind(m) == "function_signature_item") list ~= function_(m);
        return list;
    }

    /// Metrics of one function. Only `parameter` nodes count, so `self`/`&mut self` (a self_parameter) is not a
    /// parameter. Without a body the complexity is 1.
    MethodInfo function_(TSNode f)
    {
        uint parameters;
        auto p = child(f, "parameters");
        if (!isNull(p)) foreach (c; namedChildren(p)) if (kind(c) == "parameter") parameters++;
        auto body_ = child(f, "body");
        return MethodInfo(src.fieldText(f, "name").idup, firstLine(f), loc(f), isNull(body_) ? 1 : complexity(body_), parameters);
    }

    /// Flattens a use tree into alias → path entries and glob imports.
    ///
    /// `prefix` is the path of the enclosing `a::b::{…}` group. For example
    /// `use a::b::{self, C as D, e::F, g::*};` gives uses b → a::b, D → a::b::C, F → a::b::e::F and the glob a::b::g.
    /// `metavariable` covers `$crate` in macro bodies.
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
            // `use a::b::C;` binds the last segment; `use a::b::self` binds `b`.
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
            // `use Trait as _;` only brings trait methods into scope and binds no name.
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
            // `use a::*;` has the path as its only named child; `*` inside a group (`a::{*}`) has none.
            scope_.globs ~= prefix ~ (ts_node_named_child_count(n) ? segments(ts_node_named_child(n, 0)) : null);
            break;
        default:
            break;
        }
    }

    /// The segments of a (possibly scoped) path node: `a::b::C` → [a, b, C].
    string[] segments(TSNode n)
    {
        if (isNull(n)) return null;
        if (kind(n) == "scoped_identifier" || kind(n) == "scoped_type_identifier")
            return segments(child(n, "path")) ~ src.fieldText(n, "name").idup;
        return [src.text(n).idup];
    }
}

/// Attributes of a struct or union: named fields (`field_declaration_list`) or tuple fields
/// (`ordered_field_declaration_list`); a unit struct has no body and no fields.
private uint fieldCount(TSNode body_)
{
    if (isNull(body_)) return 0;
    uint n;
    if (kind(body_) == "field_declaration_list")
    {
        foreach (f; namedChildren(body_)) if (kind(f) == "field_declaration") n++;
        return n;
    }
    // tuple struct: count the "type" fields. Each tuple element's type is a child in the grammar field "type";
    // visibility modifiers and attributes are children too, but in no field.
    foreach (i; 0 .. ts_node_child_count(body_)) if (fieldNameForChild(body_, i) == "type") n++;
    return n;
}

/// Names a type might depend on, as written: every type identifier, every path (`net::http::get`,
/// `crate::cart::Cart`) as a whole, and plain identifiers starting with an upper-case letter (unit structs, enum
/// variants, tuple-struct constructors or constants used as values). Lower-case identifiers are locals or
/// functions and are skipped. A path is not descended into, so `Cart::new()` contributes only `Cart::new`, which
/// `Resolver.resolve` maps to the type `Cart`. A qualified path (`<T as Trait>::Item`) names nothing resolvable as
/// text, so the types inside its brackets are collected instead.
private void collectRefs(ref Src src, TSNode node, ref bool[string] refs)
{
    walk(node, (TSNode n) {
        switch (kind(n))
        {
        case "type_identifier":
            refs[src.text(n).idup] = true;
            return true;
        case "scoped_type_identifier", "scoped_identifier":
            auto path = child(n, "path");
            if (!isNull(path) && kind(path) == "bracketed_type")
            {
                collectRefs(src, path, refs);
                return false;
            }
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

/// Cyclomatic complexity: 1 + `if`, `while`, `loop`, `for`, `&&`/`||`, plus k - 1 for a `match` with k arms
/// (`if let`/`while let` parse as if/while expressions; `?` does not count). Nested closures count towards the
/// enclosing function.
uint complexity(TSNode body_)
{
    // Signed: a match is visited (and decremented) before its arms, and a match without arms would go below 1.
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

/// Links all Rust files of the project.
///
/// 1. Collect types (first fqn wins), the scope of every building, all impl blocks and the set of crate names.
/// 2. Attach each impl to the type it implements, resolved in the impl's scope. An impl for a type outside the
///    project (`impl From<Cart> for String`) goes to the function building of the module it is written in, which is
///    created if the module has no free functions. The implemented trait and the impl body's references become
///    dependencies of the target.
/// 3. For every building: add the attached impls' methods and LOC, and resolve its own references in its own scope.
Linked link(ParsedFile[] files)
{
    // Every file of this analysis is parsed by now: the next analysis reads the manifests again.
    {
        crateLockInstance.lock();
        scope (exit) crateLockInstance.unlock();
        crateNames = null;
    }
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
        // resolveType never returns a function building; kept as a guard so impls only attach to real types.
        if (target !is null && byFqn[target].kind == "module") target = null;
        if (target is null)
        {
            target = imp.scope_.path.join("::") ~ "::" ~ functionsId;
            if (target !in byFqn)
            {
                // Synthesise the missing function building. Its build module is copied from the first known type of
                // the same crate (all of a crate's types share it); it starts with no LOC of its own, the impl's LOC
                // is added below like for any other target. District and name are those visitItems would have given
                // it (a #[cfg(test)] module stays in its parent's district).
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
                t.name = imp.label;
                t.pkg = imp.district.path[1 .. $].join("::");
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
    /// Every building's fqn.
    bool[string] known;
    /// Crate names of the project, so `other_crate::x` is recognised as an absolute path.
    bool[string] crates;

    /// Like `resolve`, but only types: used for impl targets and traits.
    string resolveType(string written, Scope s)
    {
        auto r = resolve(written, s);
        return r !is null && !r.endsWith("::" ~ functionsId) ? r : null;
    }

    /// Candidate paths, in order:
    ///   - the first segment is a `use` alias: only the imported path plus the remaining segments;
    ///   - it is `crate`, `self`, `super` or a crate name: that absolute path;
    ///   - otherwise: an item of the current module, then of each glob import.
    /// The first candidate that is a known building wins. A candidate whose last segment is lower-case and whose
    /// parent module has a function building resolves to that building (a call such as `http::get`); else one whose
    /// parent is a known type resolves to that type (`Cart::new`, `Shape::Circle`). Generic arguments
    /// (`Vec<Cart>` → `Vec`) are cut off first; `Self` resolves to nothing, as it is the type itself, and so does a
    /// qualified path (`<T as Trait>::Item`), whose types `collectRefs` collects separately.
    string resolve(string written, Scope s)
    {
        if (written.length == 0 || written == "Self") return null;
        import std.string : indexOf;
        const lt = written.indexOf('<');
        const cleaned = lt < 0 ? written : written[0 .. lt];
        auto segs = cleaned.split("::");
        const first = segs[0];
        if (first.length == 0) return null;
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
                const parent = c[0 .. $ - 1].join("::");
                const functions = parent ~ "::" ~ functionsId;
                import std.utf : decodeFront;
                auto lastSeg = c[$ - 1];
                if (functions in known && lastSeg.length && isLower(decodeFront(lastSeg))) return functions;
                // an associated item or variant of a type (Cart::new, Shape::Circle) → the type
                if (parent in known) return parent;
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
                // Never climb above the crate root: the crate name stays.
            if (base.length > 1) base = base[0 .. $ - 1];
                i++;
            }
            return base ~ path[i .. $];
        }
        if (first in crates) return path;
        return s.path ~ path; // 2018-edition relative path
    }
}

version (unittest)
{
    import codecity.analysis : SourceAnalyzer;
    import codecity.model : Analysis;

    /// A temporary project directory; `put` writes a file into it.
    private struct RustFixture
    {
        string root;

        static RustFixture create()
        {
            import codecity.git : realPath;
            import std.file : mkdirRecurse, tempDir;
            import std.uuid : randomUUID;
            auto dir = buildPath(tempDir, "codecity-rust-test-" ~ randomUUID.toString);
            mkdirRecurse(dir);
            return RustFixture(realPath(dir));
        }

        void put(string rel, string code)
        {
            import std.file : mkdirRecurse, write;
            const f = buildPath(root, rel);
            mkdirRecurse(f.dirName);
            write(f, code);
        }

        Analysis analyze() { return new SourceAnalyzer().analyze(root, true); }

        void cleanup()
        {
            import std.file : rmdirRecurse;
            rmdirRecurse(root);
        }
    }

    private const(TypeDecl)* find(ref Analysis a, string fqn)
    {
        foreach (ref t; a.types) if (t.fqn == fqn) return &t;
        return null;
    }
}

unittest // a qualified path (<T as Tr>::Item) resolves the types inside the brackets
{
    auto f = RustFixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"q\"\n");
    f.put("src/lib.rs", "pub fn helper() {}\npub trait Tr { type Item; }\npub struct S;\nimpl S { fn f<T>(x: <T as Tr>::Item) {} }\n");
    auto a = f.analyze();
    assert(a.dependencies["q::S"].canFind("q::Tr"));
}

unittest // the package name is found after an array value
{
    auto f = RustFixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nauthors = [\"a\"]\nname = \"named\"\n[dependencies]\nname = \"no\"\n");
    f.put("src/lib.rs", "pub struct A;\n");
    auto a = f.analyze();
    assert(find(a, "named::A") !is null);
}

unittest // a path into a type (Cart::new(), Shape::Circle) depends on the type
{
    auto f = RustFixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"p\"\n");
    f.put("src/lib.rs", "pub mod cart;\npub enum Shape { Circle }\n");
    f.put("src/cart.rs", "pub struct Cart;\nimpl Cart { pub fn new() -> Self { Cart } }\npub struct Shop;\nimpl Shop { fn open(&self) { Cart::new(); let _s = crate::Shape::Circle; } }\n");
    auto a = f.analyze();
    assert(a.dependencies["p::cart::Shop"].canFind("p::cart::Cart"));
    assert(a.dependencies["p::cart::Shop"].canFind("p::Shape"));
}

unittest // a module building created for impls is named and placed like other module buildings
{
    auto f = RustFixture.create();
    scope (exit) f.cleanup();
    f.put("Cargo.toml", "[package]\nname = \"m\"\n");
    f.put("src/lib.rs", "pub mod cart;\npub trait Probe { fn probe(&self); }\n");
    f.put("src/cart.rs", "pub struct Cart;\n#[cfg(test)]\nmod tests {\n    impl crate::Probe for u32 { fn probe(&self) {} }\n}\n");
    auto a = f.analyze();
    auto tests = find(a, "m::cart::tests::{fn}");
    assert(tests !is null && tests.pkg == "cart" && tests.name == "mod tests" && tests.test);
    assert(a.dependencies["m::cart::tests::{fn}"].canFind("m::Probe"));
}

unittest // Cargo.toml changes are seen on reanalysis
{
    auto f = RustFixture.create();
    scope (exit) f.cleanup();
    auto analyzer = new SourceAnalyzer;
    f.put("Cargo.toml", "[package]\nname = \"before\"\n");
    f.put("src/lib.rs", "pub struct A;\n");
    auto first = analyzer.analyze(f.root, true);
    assert(find(first, "before::A") !is null);
    f.put("Cargo.toml", "[package]\nname = \"after\"\n");
    f.put("src/lib.rs", "pub struct A; // changed, so the file is parsed again\n");
    auto second = analyzer.analyze(f.root, true);
    assert(find(second, "after::A") !is null);
}
