/// Walks a source tree, parses every supported file with its language's analyser (in parallel, cached by
/// modification time so watch-mode rebuilds only re-parse what changed), then links each language's files.
///
/// This is the first stage of the pipeline: walk → parse/analyse → link dependencies. Its output, an `Analysis`, then
/// gets its git history attached by `codecity.city.CityModel.build` (which runs `git log` on a second thread while
/// this module parses) and is turned into the city model, laid out, serialised to JSON and drawn by the viewers.
///
/// Work is split in two phases per language:
///
/// 1. `parse(root, file, code)` looks at one file in isolation and returns a `ParsedFile`: the types declared in
///    it, with metrics and the raw, unresolved names they reference, plus an optional language-specific `Context`
///    (imports, impl blocks, …). Parsing is independent per file, so it runs in parallel and its result can be
///    cached.
/// 2. `link(files)` sees every parsed file of the language at once and resolves those raw names to the fully
///    qualified names of project types, producing the dependency edges. It always reruns, because a change in one
///    file can change what names in other files resolve to.
///
/// The Java and Rust editions have the same structure. All analysers here use tree-sitter (see
/// `codecity.analysis.syntax`), like the Rust edition; the Java edition parses Java with JavaParser instead, and the
/// D Java analyser reproduces its metrics.
module codecity.analysis;

import codecity.model;
import java = codecity.analysis.java;
import typescript = codecity.analysis.typescript;
import rustlang = codecity.analysis.rustlang;
import core.atomic : cas;
import std.algorithm : canFind, endsWith, sort, startsWith;
import std.array : array;
import std.conv : to;
import std.datetime.systime : SysTime;
import std.file : DirEntry, dirEntries, exists, getSize, isDir, isFile, read, SpanMode, timeLastModified;
import std.parallelism : taskPool;
import std.path : baseName;
import std.stdio : stderr;
import std.utf : validate;

/// Directory names never descended into: VCS metadata, IDE settings, build output and installed dependencies. Any
/// other hidden directory is skipped too (see `isIgnoredDir`).
immutable string[] ignoredDirs = [
    ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", "target", "build", "out", "node_modules", "dist", "coverage",
    "bower_components",
];

/// Language-specific data the linker needs (imports, impl blocks, …).
abstract class Context
{
}

/// What one language analyser extracted from one file, before linking. Cached per file by `SourceAnalyzer`.
struct ParsedFile
{
    TypeDecl[] types;
    Context context;
    /// True when the file had syntax errors and was only partially analysed.
    bool failed;
}

/// Every parsed file of one language combined into final types and project-internal dependencies.
struct Linked
{
    TypeDecl[] types;
    /// fqn → set of fqns it depends on. May contain fqns that `analyze` later drops (tests); it filters them out.
    bool[string][string] dependencies;
}

/// True for directories the walk skips: the fixed `ignoredDirs` and every hidden directory (`.cache`, `.vscode`, …).
/// The length check keeps a bare "." (the root itself when given as ".") from counting as hidden.
bool isIgnoredDir(string name) pure @safe
{
    return ignoredDirs.canFind(name) || (name.startsWith(".") && name.length > 1);
}

/// Sets `language` to the language of a source file, judged by its file name alone, and returns true; returns false
/// (leaving `language` at its default) when the file is not a supported source file.
bool languageOf(string file, out Language language) @safe
{
    const name = file.baseName;
    if (name.endsWith(".java")) language = Language.java;
    else if (typescript.accepts(name)) language = Language.typescript;
    else if (name.endsWith(".rs")) language = Language.rust;
    else return false;
    return true;
}

/// Manifest file names whose content feeds into the parse of other files: a `.rs` file's crate name comes from the
/// nearest `Cargo.toml`, a `.ts` file's project from the nearest `package.json`.
immutable string[] manifestNames = ["Cargo.toml", "package.json"];

/// Analyses a source tree, keeping a per-file cache of parse results between runs.
///
/// One instance lives for the whole process: the CLI creates it once and watch mode reuses it for every rebuild, so
/// only files whose modification time or size changed are parsed again. `analyze` must not be called concurrently on
/// the same instance: the cache is a plain associative array that only the calling thread reads and writes; the
/// worker threads in `analyze` touch nothing but their own slots of a pre-sized result array.
final class SourceAnalyzer
{
    /// A cached parse and the file stamp it belongs to. A file whose stamp differs is parsed again.
    private struct Entry
    {
        SysTime modified;
        ulong size;
        ParsedFile parsed;
    }

    /// File path, as `findSources` returns it → last parse result.
    private Entry[string] cache;
    /// Path and parse-relevant content (see `manifestStamp`) of every manifest seen by the previous analysis, sorted
    /// by path. Any difference (a crate renamed, a manifest added or removed) empties `cache`.
    private string[] manifestStamps;
    /// Set once the first analyser crash has been reported, so a systematic bug does not print one line per file.
    /// Shared and updated with `cas` because the parsing workers race on it.
    private shared bool reportedFailure;

    size_t cacheLength() const { return cache.length; }

    /**
     * Analyses every supported file under `root` and returns its types and project-internal dependencies.
     *
     * Steps: find the files; drop cache entries of files that no longer exist; stat every file and reuse cached
     * results whose modification time and size are unchanged; parse the rest in parallel; link each language
     * separately; finally merge the languages, drop test types unless `includeTests`, and turn the dependency sets
     * into sorted lists restricted to the types that were kept.
     *
     * The result is deterministic: types are sorted by fqn and every dependency list is sorted, whatever order the
     * files were parsed in. Self-dependencies are removed. `Analysis.history` is left empty for the caller to fill.
     */
    Analysis analyze(string root, bool includeTests)
    {
        string[] manifests;
        auto files = findSources(root, manifests);
        // A changed manifest can change the parse of files that did not change themselves (their crate or project),
        // so start over. Manifests rarely change in a way that matters, so dropping every language's parses is good
        // enough.
        string[] manifestNow;
        foreach (m; manifests) manifestNow ~= manifestStamp(m);
        if (manifestNow != manifestStamps)
        {
            cache = null;
            manifestStamps = manifestNow;
        }
        // drop cache entries for deleted files
        bool[string] present;
        foreach (f; files) present[f] = true;
        foreach (k; cache.keys) if (k !in present) cache.remove(k);

        // stat sequentially (cheap), parse misses in parallel
        auto results = new ParsedFile[files.length];
        auto langs = new Language[files.length];
        auto stamps = new Entry[files.length];
        size_t[] todo;
        foreach (i, f; files)
        {
            // Cannot fail: findSources only returns files whose language is known.
            languageOf(f, langs[i]);
            // A file that vanished or cannot be stat'ed keeps the zero stamp; it is still parsed (and fails there).
            try
            {
                stamps[i].modified = f.timeLastModified;
                stamps[i].size = f.getSize;
            }
            catch (Exception) {}
            auto hit = f in cache;
            if (hit && hit.modified == stamps[i].modified && hit.size == stamps[i].size)
                results[i] = hit.parsed;
            else
                todo ~= i;
        }
        // Work units of 4 files. Each worker writes only results[i] for its own i, so no locking is needed; tree-sitter
        // parsers are per thread (see codecity.analysis.syntax.parse). The cache is updated afterwards, on this thread.
        foreach (i; taskPool.parallel(todo, 4))
            results[i] = parseFile(langs[i], root, files[i]);
        foreach (i; todo)
            cache[files[i]] = Entry(stamps[i].modified, stamps[i].size, results[i]);

        size_t failed;
        ParsedFile[][Language] byLanguage;
        foreach (i, r; results)
        {
            if (r.failed) failed++;
            byLanguage[langs[i]] ~= r;
        }

        // Link each language on its own (names only resolve within a language) and merge. The fixed language order
        // decides which type wins if two languages produced the same fqn: the first one; the later duplicate is
        // dropped together with its dependencies. The fqn notations differ per language (`a.B`, `src/a.ts:B`,
        // `a::B`), so a clash is unlikely in practice.
        TypeDecl[string] byFqn;
        bool[string][string] dependencies;
        foreach (lang; [Language.java, Language.typescript, Language.rust])
        {
            auto group = lang in byLanguage;
            if (group is null) continue;
            Linked linked;
            final switch (lang)
            {
            case Language.java: linked = java.link(*group); break;
            case Language.typescript: linked = typescript.link(root, *group); break;
            case Language.rust: linked = rustlang.link(*group); break;
            }
            foreach (t; linked.types)
                if (t.fqn !in byFqn)
                {
                    byFqn[t.fqn] = t;
                    if (auto d = t.fqn in linked.dependencies) dependencies[t.fqn] = *d;
                }
        }

        // Associative arrays have no order; sort by fqn so the output (and the city layout) is reproducible.
        auto keys = byFqn.keys;
        keys.sort();
        TypeDecl[] types;
        foreach (k; keys)
            if (includeTests || !byFqn[k].test) types ~= byFqn[k];
        // Edges may only point at types that are in the output: with tests excluded, edges into test types go too.
        bool[string] kept;
        foreach (t; types) kept[t.fqn] = true;
        string[][string] deps;
        foreach (t; types)
        {
            string[] list;
            if (auto d = t.fqn in dependencies)
                foreach (target, _; *d)
                    if (target in kept && target != t.fqn) list ~= target;
            list.sort();
            deps[t.fqn] = list;
        }
        return Analysis(root, types, deps, null, files.length, failed);
    }

    /**
     * What a manifest contributes to the parse of other files, as a string that changes exactly when that does: the
     * path plus, for a `Cargo.toml`, the crate name it declares (the Rust analyser uses nothing else from it); a
     * `package.json` matters only by existing (the TypeScript analyser only looks for one). So an edit that only
     * bumps a version or a dependency keeps the parse cache, which matters for the history replay
     * (`codecity.history`), where such edits come with most snapshots.
     */
    private static string manifestStamp(string manifest)
    {
        import std.path : dirName;
        if (manifest.baseName == "Cargo.toml") return manifest ~ "\0" ~ rustlang.readCrateName(manifest.dirName);
        return manifest;
    }

    /**
     * Reads and parses one file. Runs on a worker thread.
     *
     * A file that cannot be read counts as failed. A file that is not valid UTF-8 is decoded as ISO-8859-1 (each byte
     * becomes the code point of the same value, re-encoded as UTF-8), as the Java edition does, so legacy encodings
     * still parse with only odd-looking identifiers. Any exception or error thrown by an analyser is caught: one bad
     * file must not abort the whole analysis.
     */
    private ParsedFile parseFile(Language lang, string root, string file)
    {
        string code;
        try
        {
            auto bytes = cast(string) read(file);
            try
            {
                validate(bytes);
                code = bytes;
            }
            catch (Exception)
            {
                // like Java's ISO-8859-1 fallback: every byte becomes one char
                dchar[] chars;
                foreach (b; cast(const(ubyte)[]) bytes) chars ~= cast(dchar) b;
                code = chars.to!string;
            }
        }
        catch (Exception)
            return ParsedFile(null, null, true);
        try
        {
            final switch (lang)
            {
            case Language.java: return java.parse(root, file, code);
            case Language.typescript: return typescript.parse(root, file, code);
            case Language.rust: return rustlang.parse(root, file, code);
            }
        }
        catch (Throwable e)
        {
            // A bug in an analyser, not a syntax error in the file: report it (once) rather than hide it.
            if (cas(&reportedFailure, false, true))
                stderr.writefln("warning: could not analyse %s: %s", file, e.msg);
            return ParsedFile(null, null, true);
        }
    }
}

/// Supported files under `root`, skipping build output and VCS directories, sorted. The second overload also
/// collects the `manifestNames` files it passes, sorted, into `manifests`.
///
/// Symbolic links are not followed (neither to files nor to directories), which also rules out cycles. Directories
/// that cannot be listed and entries that cannot be stat'ed are skipped silently. Returned paths are `root` joined
/// with the relative path, as `dirEntries` builds them.
string[] findSources(string root)
{
    string[] manifests;
    return findSources(root, manifests);
}

/// ditto
string[] findSources(string root, out string[] manifests)
{
    string[] files;
    void visit(string dir)
    {
        DirEntry[] entries;
        try
            entries = dirEntries(dir, SpanMode.shallow, false).array;
        catch (Exception)
            return;
        foreach (e; entries)
        {
            try
            {
                if (e.isSymlink) continue;
                if (e.isDir)
                {
                    if (!isIgnoredDir(e.name.baseName)) visit(e.name);
                }
                else if (e.isFile)
                {
                    Language l;
                    if (languageOf(e.name, l)) files ~= e.name;
                    else if (manifestNames.canFind(e.name.baseName)) manifests ~= e.name;
                }
            }
            catch (Exception) {}
        }
    }
    visit(root);
    files.sort();
    manifests.sort();
    return files;
}
