/// Walks a source tree, parses every supported file with its language's analyser (in parallel, cached by
/// modification time so watch-mode rebuilds only re-parse what changed), then links each language's files.
module codecity.analysis;

import codecity.model;
import java = codecity.analysis.java;
import typescript = codecity.analysis.typescript;
import rustlang = codecity.analysis.rustlang;
import core.atomic : cas;
import std.algorithm : canFind, endsWith, sort, startsWith;
import std.array : array;
import std.datetime.systime : SysTime;
import std.file : DirEntry, dirEntries, exists, getSize, isDir, isFile, read, SpanMode, timeLastModified;
import std.parallelism : taskPool;
import std.path : baseName;
import std.stdio : stderr;
import std.utf : validate;

immutable string[] ignoredDirs = [
    ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", "target", "build", "out", "node_modules", "dist", "coverage",
    "bower_components",
];

/// Language-specific data the linker needs (imports, impl blocks, …).
abstract class Context
{
}

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
    bool[string][string] dependencies;
}

bool isIgnoredDir(string name) pure @safe
{
    return ignoredDirs.canFind(name) || (name.startsWith(".") && name.length > 1);
}

/// The language of a source file, or null-ish (false) when unsupported.
bool languageOf(string file, out Language language) @safe
{
    const name = file.baseName;
    if (name.endsWith(".java")) language = Language.java;
    else if (typescript.accepts(name)) language = Language.typescript;
    else if (name.endsWith(".rs")) language = Language.rust;
    else return false;
    return true;
}

final class SourceAnalyzer
{
    private struct Entry
    {
        SysTime modified;
        ulong size;
        ParsedFile parsed;
    }

    private Entry[string] cache;
    private shared bool reportedFailure;

    size_t cacheLength() const { return cache.length; }

    Analysis analyze(string root, bool includeTests)
    {
        auto files = findSources(root);
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
            languageOf(f, langs[i]);
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
                if (t.fqn !in byFqn) byFqn[t.fqn] = t;
            foreach (k, v; linked.dependencies) dependencies[k] = v;
        }

        auto keys = byFqn.keys;
        keys.sort();
        TypeDecl[] types;
        foreach (k; keys)
            if (includeTests || !byFqn[k].test) types ~= byFqn[k];
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
                import std.conv : to;
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

/// Supported files under `root`, skipping build output and VCS directories, sorted.
string[] findSources(string root)
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
                }
            }
            catch (Exception) {}
        }
    }
    visit(root);
    files.sort();
    return files;
}
