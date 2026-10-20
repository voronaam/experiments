/// Rust support, on the in-house parser `codecity.analysis.rust_parse`. Crates and modules are
/// districts; structs, enums, unions and traits are buildings whose methods are collected from every `impl` block in
/// the crate; each module's free functions and constants form a "module" building. `use` paths are resolved so
/// dependencies connect across files.
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
import codecity.analysis.rust_parse;
import codecity.analysis.text;
import codecity.model;
import core.sync.mutex : Mutex;
import std.algorithm : canFind, endsWith, startsWith;
import std.array : join, replace, split;
import std.file : attrIsFile, exists, getLinkAttributes, getSize, isFile, readText;
import std.path : baseName, buildPath, dirName, pathSplitter;
import std.string : indexOf;
import std.uni : isLower, isWhite;

/// Glob imports (`use a::b::*;`) beyond this many in one module are ignored. A name is tried in each of them, so the
/// cap keeps a crafted file with very many from making the analysis quadratic; real modules have a handful. The Java
/// and Rust editions use the same limit.
enum size_t maxGlobImports = 64;

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
    auto tree = codecity.analysis.rust_parse.parse(code);
    auto crate = crateOf(root, file.dirName);
    const inCrate = relativeTo(file, crate.dir);
    const sourceRoot = sourceRootOf(crate.dir, file.dirName);
    auto modulePath = modulePathOf(relativeTo(file, sourceRoot));
    const first = inCrate.pathSplitter.front;
    const test = first == "tests" || first == "benches";

    // The crate is the build module of every type in it.
    auto st = new FileState(code, tree, file, crate.name);
    auto path = [crate.name] ~ modulePath;
    if (st.fits(path))
    {
        auto scope_ = st.addScope(new Scope(path));
        st.visitItems(0, scope_, scope_, file.baseName, test);
    }
    return ParsedFile(st.types, st.ctx, tree.errors || st.skipped);
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

/// Largest `Cargo.toml` that is read; a bigger one counts as having no package name. Real manifests are a few KiB;
/// the cap keeps a crafted one from exhausting memory. The Java and Rust editions use the same limit.
enum ulong maxManifestBytes = 1024 * 1024;

/// The `name` in the `[package]` table of `dir`'s Cargo.toml, with hyphens turned into underscores as the crate is
/// written in Rust paths, or "" when the manifest is missing, unreadable, not valid UTF-8, larger than
/// `maxManifestBytes` or has no package name. Only a regular file is read: a symbolic link (which could point outside
/// the analysed tree, or at a device such as `/dev/zero`) counts as missing.
package string readCrateName(string dir)
{
    const manifest = buildPath(dir, "Cargo.toml");
    try
    {
        // getLinkAttributes does not follow a symbolic link, so a link is not a regular file here.
        if (!attrIsFile(getLinkAttributes(manifest)) || getSize(manifest) > maxManifestBytes) return "";
        return packageName(readText(manifest)).replace("-", "_");
    }
    catch (Exception)
        return "";
}

/// The `name = "…"` of the `[package]` table of a Cargo.toml, or "" when there is none. Not a TOML parser, and
/// hand-written rather than a regular expression so that all three editions run the same scan in time linear in the
/// manifest's size (std.regex backtracks).
///
/// Starting at each line that begins with `[package]`, the following lines are scanned: the first one of the form
/// `[ \t]* name \s* = \s* "value"` gives the name (the value may not be empty and ends at the next `"`, even on a later
/// line). Lines skipped on the way must end with a newline and must not start with `[` after indentation
/// (`[ \t\r]`), which keeps the search inside the table, as the next table header starts with `[`; a `[` elsewhere on
/// a line (`authors = ["…"]`) does not stop it. When a `[package]` line yields nothing, the search goes on at the next
/// one. `\s` is ASCII whitespace and lines end at `\n` only.
package string packageName(string toml) pure @safe
{
    const n = toml.length;
    size_t nextLine(size_t i)
    {
        const p = toml[i .. $].indexOf('\n');
        return p < 0 ? n : i + p + 1;
    }
    for (size_t start = 0; start < n; start = nextLine(start))
    {
        if (!toml[start .. $].startsWith("[package]")) continue;
        size_t line = nextLine(start);
        while (line < n)
        {
            const name = nameValue(toml, line);
            if (name.length) return name;
            // Skip the line, unless it would leave the table.
            size_t i = line;
            while (i < n && (toml[i] == ' ' || toml[i] == '\t' || toml[i] == '\r')) i++;
            if (i == n || toml[i] == '[' || (toml[i] != '\n' && isTomlSpace(toml[i]))) break;
            const p = toml[i .. $].indexOf('\n');
            if (p < 0) break;
            line = i + p + 1;
        }
    }
    return "";
}

/// The value of a `[ \t]* name \s* = \s* "value"` at byte `i`, or "" when there is none.
private string nameValue(string s, size_t i) pure @safe
{
    const n = s.length;
    while (i < n && (s[i] == ' ' || s[i] == '\t')) i++;
    if (!s[i .. $].startsWith("name")) return "";
    i += 4;
    while (i < n && isTomlSpace(s[i])) i++;
    if (i == n || s[i] != '=') return "";
    i++;
    while (i < n && isTomlSpace(s[i])) i++;
    if (i == n || s[i] != '"') return "";
    const close = s[i + 1 .. $].indexOf('"');
    return close > 0 ? s[i + 1 .. i + 1 + close] : "";
}

/// ASCII whitespace, as `\s` in a Java regular expression: space, `\t \n \x0B \x0C \r`.
private bool isTomlSpace(char c) pure nothrow @safe @nogc
{
    return c == ' ' || (c >= '\t' && c <= '\r');
}

/// Everything collected while walking one file.
private final class FileState
{
    string code;
    Tree tree;
    Lines lines;
    /// module_ is the crate name.
    string file, module_;
    TypeDecl[] types;
    RustContext ctx;
    /// Set when a module or building was skipped for the length of its fqn (see `fits`).
    bool skipped;

    this(string code, Tree tree, string file, string module_)
    {
        this.code = code;
        this.tree = tree;
        this.lines = codecity.analysis.text.lines(code);
        this.file = file;
        this.module_ = module_;
        ctx = new RustContext;
    }

    /// Whether the module with this path gets a module building fqn (`path::{fn}`) within `maxFqnBytes`. If not, the
    /// module and everything in it are skipped, and the file counts as failed; so no fqn of a building inside it can
    /// be too long either, apart from a single over-long name, which `addType` checks.
    bool fits(const string[] path)
    {
        const fits = pathBytes(path) + 2 + functionsId.length <= maxFqnBytes;
        if (!fits) skipped = true;
        return fits;
    }

    Scope addScope(Scope s)
    {
        ctx.scopes ~= s;
        return s;
    }

    /// The district of a scope: its module path without the crate name ("" for the crate root).
    static string packageOf(Scope s) { return s.path[1 .. $].join("::"); }

    static string fqn(Scope s, string name) { return s.path.join("::") ~ "::" ~ name; }

    uint loc(size_t n) { return countLoc(lines, tree.firstLine(n), tree.lastLine(n)); }

    /// Text of a token ("" for `none`), copied so that the result does not keep the source alive.
    string tok(uint t) { return tree.tokText(code, t).idup; }

    /// The item's name (its `name` token), "" if it has none.
    string name(size_t n) { return tok(tree.nodes[n].name); }

    /// `district`: scope whose module path decides the district; differs from `scope_` inside `#[cfg(test)]`
    /// modules, which stay in their parent's district instead of adding one.
    ///
    /// Visits the items of a file or of an inline `mod` body (the children of a `file` or `declBody` node). Types
    /// become buildings; impl blocks are recorded for linking; free functions, `const` and `static` items are
    /// collected into the module's function building (named `label`: the file name, or `mod name` for an inline
    /// module), which is only emitted when there is at least one of them. `mod x;` without a body is skipped here:
    /// its file is parsed on its own.
    void visitItems(size_t container, Scope scope_, Scope district, string label, bool test)
    {
        MethodInfo[] functions;
        bool[string] functionRefs;
        uint constants, moduleLoc;
        // Line span of the free items, reported as the module building's position.
        uint first = uint.max, last = 0;
        bool cfgTest;
        foreach (item; tree.children(container))
        {
            const k = tree.nodes[item].kind;
            // Outer attributes precede the item they apply to, so remember `#[cfg(test)]` until the next item. Only
            // spaces are removed before the check, so `#[cfg( test )]` matches; `#[cfg(all(test, …))]` does not. Any
            // other node in between, including a comment (the parser records one as a `comment` node), ends the
            // attribute's effect.
            if (k == NodeKind.attr)
            {
                string attr;
                foreach (dchar c; tree.nodeText(code, item)) if (c != ' ') attr ~= c;
                if (attr.canFind("cfg(test)")) cfgTest = true;
                continue;
            }
            // Applies to types, impls and inline modules. Free functions and constants always share the module
            // building, which is test code only when the whole module is.
            const isTest = test || cfgTest;
            switch (k)
            {
            case NodeKind.use:
            {
                // The use tree is the item's last child.
                auto parts = tree.children(item);
                if (parts.length) expandUse(parts[$ - 1], null, scope_);
                break;
            }
            case NodeKind.struct_, NodeKind.union_:
                addType(item, scope_, district, k == NodeKind.union_ ? "union" : "struct", tree.nodes[item].value, null,
                    isTest);
                break;
            case NodeKind.enum_:
                addType(item, scope_, district, "enum", tree.nodes[item].value, null, isTest);
                break;
            // A trait's attributes are 0; its methods are its declared functions, with or without default bodies.
            case NodeKind.trait:
                addType(item, scope_, district, "trait", 0, functionsIn(tree.child(item, NodeKind.declBody)), isTest);
                break;
            case NodeKind.impl:
                addImpl(item, scope_, district, label, isTest);
                break;
            case NodeKind.fn, NodeKind.const_, NodeKind.static_:
                // A function without a body is a signature, which does not belong here; valid code has none at module
                // level.
                if (k == NodeKind.fn && tree.child(item, NodeKind.body_) == noNode) break;
                if (k == NodeKind.fn) functions ~= function_(item);
                else constants++;
                collectRefs(code, tree, item, functionRefs);
                moduleLoc += loc(item);
                if (tree.firstLine(item) < first) first = tree.firstLine(item);
                if (tree.lastLine(item) > last) last = tree.lastLine(item);
                break;
            case NodeKind.mod:
            {
                const body_ = tree.child(item, NodeKind.declBody);
                if (body_ != noNode)
                {
                    const modName = name(item);
                    auto childPath = scope_.path ~ modName;
                    if (!fits(childPath)) break;
                    auto childScope = addScope(new Scope(childPath));
                    // The module always gets its own scope (its names are `parent::tests::…`), but a cfg(test)
                    // module does not get its own district.
                    visitItems(body_, childScope, cfgTest ? district : childScope, "mod " ~ modName, isTest);
                }
                break;
            }
            default:
                break;
            }
            // The attribute applied to this item only.
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

    /// Adds a struct, union, enum or trait building. Its methods (other than a trait's own) arrive in `link`.
    void addType(size_t item, Scope scope_, Scope district, string kind_, uint noa, MethodInfo[] methods, bool test)
    {
        const typeName_ = name(item);
        if (pathBytes(scope_.path) + 2 + typeName_.length > maxFqnBytes)
        {
            skipped = true;
            return;
        }
        bool[string] refs;
        collectRefs(code, tree, item, refs);
        const id = fqn(scope_, typeName_);
        ctx.scopeOf[id] = scope_;
        TypeDecl t;
        t.language = Language.rust;
        t.fqn = id;
        t.name = typeName_;
        t.pkg = packageOf(district);
        t.module_ = module_;
        t.kind = kind_;
        t.file = file;
        t.line = tree.firstLine(item);
        t.endLine = tree.lastLine(item);
        t.loc = loc(item);
        t.noa = noa;
        t.test = test;
        t.methods = methods;
        t.references = refs;
        types ~= t;
    }

    /// Records an impl block. Its references come from the body only (the header with type, trait and generics is
    /// not scanned): the implemented type is the impl's target and the trait becomes a dependency through `trait_`
    /// in `link`.
    void addImpl(size_t item, Scope scope_, Scope district, string label, bool test)
    {
        const body_ = tree.child(item, NodeKind.declBody);
        bool[string] refs;
        collectRefs(code, tree, body_ == noNode ? item : body_, refs);
        const traitNode = tree.child(item, NodeKind.implTrait);
        ctx.impls ~= Impl(scope_, district, label, typeName(tree.child(item, NodeKind.implType)),
            traitNode == noNode ? null : typeName(traitNode), functionsIn(body_), refs, loc(item), file,
            tree.firstLine(item), test);
    }

    /// `S<T>` → `S`; `crate::m::Other` stays a path; references and pointers are unwrapped. The parser has already
    /// found this "core" of the `implType`/`implTrait` node's type (its `name` to `value` tokens).
    string typeName(size_t n)
    {
        if (n == noNode) return "";
        const node = tree.nodes[n];
        return tree.rangeText(code, node.name, node.value).idup;
    }

    /// Functions of an impl or trait body, with or without a body (a trait method without a default).
    MethodInfo[] functionsIn(size_t body_)
    {
        MethodInfo[] list;
        if (body_ == noNode) return list;
        foreach (m; tree.children(body_)) if (tree.nodes[m].kind == NodeKind.fn) list ~= function_(m);
        return list;
    }

    /// Metrics of one function. The parser counted its parameters (`self` is not counted). Without a body the
    /// complexity is 1.
    MethodInfo function_(size_t f)
    {
        const body_ = tree.child(f, NodeKind.body_);
        return MethodInfo(name(f), tree.firstLine(f), loc(f), body_ == noNode ? 1 : complexity(tree, body_),
            tree.nodes[f].value);
    }

    /// Flattens a use tree into alias → path entries and glob imports.
    ///
    /// `prefix` is the path accumulated from enclosing `a::b::{…}` groups. For example
    /// `use crate::net::{self, http::Client as C, tls::*};` yields `net → [crate, net]`,
    /// `C → [crate, net, http, Client]` and the glob `[crate, net, tls]`.
    void expandUse(size_t n, string[] prefix, Scope scope_)
    {
        const node = tree.nodes[n];
        switch (node.kind)
        {
        case NodeKind.usePath:
        {
            if (node.first == node.last)
            {
                // A single name, at the top level or inside a `{…}` list.
                const single = tok(node.first);
                if (single == "self" && prefix.length) addUse(scope_, prefix[$ - 1], prefix.dup); // a::{self}
                else addUse(scope_, single, prefix ~ single);
                break;
            }
            // `use a::b::C;` binds the last segment; `use a::b::self` binds `b`.
            auto path = prefix ~ segments(n);
            string alias_ = path.length ? path[$ - 1] : "";
            if (alias_ == "self")
            {
                path = path[0 .. $ - 1];
                alias_ = path.length ? path[$ - 1] : "self";
            }
            addUse(scope_, alias_, path);
            break;
        }
        case NodeKind.useAs:
        {
            const alias_ = tok(node.name);
            // `use Trait as _;` only brings trait methods into scope and binds no name.
            if (alias_ != "_") addUse(scope_, alias_, prefix ~ segments(tree.child(n, NodeKind.usePath)));
            break;
        }
        case NodeKind.useGroup:
        {
            auto p = prefix ~ segments(tree.child(n, NodeKind.usePrefix));
            // A group under an over-long prefix only holds over-long paths (see addUse).
            if (pathBytes(p) > maxFqnBytes) break;
            foreach (c; tree.children(n))
                if (tree.nodes[c].kind != NodeKind.usePrefix) expandUse(c, p, scope_);
            break;
        }
        case NodeKind.useGlob:
        {
            auto path = prefix ~ segments(tree.child(n, NodeKind.usePrefix));
            if (scope_.globs.length < maxGlobImports && pathBytes(path) <= maxFqnBytes) scope_.globs ~= path;
            break;
        }
        default:
            break;
        }
    }

    /// Records `use path as alias`, unless the path is longer than `maxFqnBytes`: it is copied into a candidate for
    /// every reference to the alias, and a group of many names under one long prefix would make that quadratic. (Only
    /// a path of mostly `super` could still name a building.)
    void addUse(Scope scope_, string alias_, string[] path)
    {
        if (pathBytes(path) <= maxFqnBytes) scope_.uses[alias_] = path;
    }

    /// The segments of a use path: `a::b::c` → [a, b, c] (a leading `::` adds none).
    string[] segments(size_t n)
    {
        if (n == noNode) return null;
        string[] output;
        const node = tree.nodes[n];
        foreach (t; node.first .. node.last + 1)
            if (tree.tokens[t].kind == TokKind.ident) output ~= tok(t);
        return output;
    }
}

/// Names under node `node` that may refer to project items: type identifiers, whole paths (`net::http::get`,
/// `Order::new`; whitespace removed, not descended into) and capitalised identifiers (e.g. a tuple-struct constructor
/// call `Price(3)`). Lower-case single identifiers are skipped as they are almost always locals. A path is not
/// descended into, so `Cart::new()` contributes only `Cart::new`, which `Resolver.resolve` maps to the type `Cart`.
/// For a qualified path (`<T as Trait>::Item`) the text names nothing resolvable, so the parser emitted the types
/// inside the brackets instead (see `rust_parse`, "Reference leaves"). Paths of `use` declarations inside items count
/// like paths, a single name or an `as` alias like an identifier.
private void collectRefs(string code, ref const Tree tree, size_t node, ref bool[string] refs)
{
    size_t i = node + 1;
    const end = tree.nodes[node].end;
    while (i < end)
    {
        const n = tree.nodes[i];
        switch (n.kind)
        {
        case NodeKind.typeIdent:
            addReference(tree.tokText(code, n.first).idup, refs);
            break;
        case NodeKind.ident:
            addIdentifier(tree.tokText(code, n.first), refs);
            break;
        case NodeKind.useAs:
            if (n.name != none) addIdentifier(tree.tokText(code, n.name), refs);
            break;
        case NodeKind.usePath, NodeKind.usePrefix, NodeKind.path:
            if (n.kind != NodeKind.path && n.first == n.last)
            {
                addIdentifier(tree.tokText(code, n.first), refs);
                break;
            }
            string s;
            foreach (dchar c; tree.rangeText(code, n.first, n.last)) if (!isWhite(c)) s ~= c;
            addReference(s, refs);
            i = n.end; // nothing inside a path is a separate reference
            continue;
        default:
            break;
        }
        i++;
    }
}

/// An identifier is a reference if capitalised. Lifetimes and labels (`'a`) are identifiers without their quote.
private void addIdentifier(string s, ref bool[string] refs)
{
    if (s.length && s[0] == '\'') s = s[1 .. $];
    if (isUpperStart(s)) addReference(s.idup, refs);
}

/// Adds a reference unless it is longer than `maxFqnBytes`: no building's fqn is that long, so it could not resolve,
/// and resolving it would copy it into every candidate tried.
private void addReference(string name, ref bool[string] refs)
{
    if (name.length <= maxFqnBytes) refs[name] = true;
}

/// The length in bytes of a module path joined with `::`.
private size_t pathBytes(const string[] path)
{
    size_t n = path.length ? 2 * (path.length - 1) : 0;
    foreach (segment; path) n += segment.length;
    return n;
}

/// McCabe cyclomatic complexity of a function body: 1 + `if`, `while`, `loop`, `for`, each `&&`/`||`, and k - 1 per
/// `match` with k arms. `else if` counts as another `if`; `?` does not. Closures and nested functions count towards
/// the enclosing function. Clamped to at least 1 because an empty `match` would make the sum go down.
uint complexity(ref const Tree tree, size_t body_)
{
    int cx = 1;
    foreach (ref n; tree.nodes[body_ + 1 .. tree.nodes[body_].end])
    {
        switch (n.kind)
        {
        case NodeKind.if_, NodeKind.while_, NodeKind.loop, NodeKind.for_, NodeKind.arm, NodeKind.andOr:
            cx++;
            break;
        case NodeKind.match:
            cx--; // a match with k arms adds k - 1 paths
            break;
        default:
            break;
        }
    }
    return cx < 1 ? 1 : cast(uint) cx;
}

// ------------------------------------------------------------------ linking

/// Links all Rust files of the project.
///
/// 1. Collect types (first fqn wins), the scope of every building, all impl blocks and the set of crate names.
/// 2. Attach each impl to the type it implements, resolved in the impl's scope. An impl for a type outside the
///    project (`impl From<Cart> for String`) goes to the function building of the module it is written in, which is
///    created if the module has no free functions. The implemented trait and the impl body's references become
///    dependencies of the target; they are resolved after all impls are attached.
/// 3. For every building: add the attached impls' methods and LOC, and resolve its own references in its own scope.
/// References resolve against every building, including the function buildings created in step 2.
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
    // crate → build module of the module buildings created for impls
    string[string] moduleOfCrate;
    string[] targets; // per impl, in impl order
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
                // Looked up once per crate rather than once per new module building.
                string module_;
                if (auto hit = imp.scope_.crate in moduleOfCrate) module_ = *hit;
                else
                {
                    foreach (fqn; order)
                        if (fqn.startsWith(imp.scope_.crate ~ "::"))
                        {
                            module_ = byFqn[fqn].module_;
                            break;
                        }
                    moduleOfCrate[imp.scope_.crate] = module_;
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
        targets ~= target;
    }
    // References resolve only now, against every building including the module buildings just created, so that the
    // result does not depend on the order of the impls (the editions used to differ here).
    bool[string] all;
    foreach (k; byFqn.keys) all[k] = true;
    resolver = Resolver(all, crates);
    foreach (i, ref imp; impls)
    {
        const target = targets[i];
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
