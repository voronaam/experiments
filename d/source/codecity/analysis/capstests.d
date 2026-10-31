/// The documented work caps of the analysis, each tested exactly at its limit (still accepted) and one step past it
/// (cut off as documented), so that an off-by-one or a changed limit is noticed. The adversarial tests in `tests.d`
/// cover the hostile inputs these caps exist for. The Java (`AnalysisCapsTest`) and Rust (`analysis/caps_tests.rs`)
/// editions run the same scenarios with the same expectations.
///
/// Two caps are already tested at their exact boundary in `tests.d` and are not repeated here: on-demand imports
/// (`maxImportCandidates`) and Rust glob imports (`maxGlobImports`).
module codecity.analysis.capstests;

version (unittest):

import codecity.analysis;
import codecity.git : realPath;
import codecity.model;
import std.algorithm : any, canFind, count, sort, startsWith;
import std.array : array, replicate;
import std.conv : to;
import std.file : mkdirRecurse, rmdirRecurse, tempDir, write;
import std.format : format;
import std.path : buildPath, dirName;
import std.range : iota;
import std.uuid : randomUUID;

/// A temporary source tree, removed by `cleanup`.
private struct Fixture
{
    string root;

    static Fixture create()
    {
        auto dir = buildPath(tempDir, "codecity-test-" ~ randomUUID.toString);
        mkdirRecurse(dir);
        return Fixture(realPath(dir));
    }

    void put(string rel, const(void)[] code)
    {
        const f = buildPath(root, rel);
        mkdirRecurse(f.dirName);
        write(f, code);
    }

    Analysis analyze() { return new SourceAnalyzer().analyze(root, true); }

    void cleanup() { rmdirRecurse(root); }
}

/// The fqns of all buildings, sorted.
private string[] fqns(ref Analysis a)
{
    string[] r;
    foreach (ref t; a.types) r ~= t.fqn;
    sort(r);
    return r;
}

private string[] sorted(string[] items)
{
    auto d = items.dup;
    sort(d);
    return d;
}

private string[] deps(ref Analysis a, string fqn)
{
    auto found = fqn in a.dependencies;
    auto d = found ? (*found).dup : null;
    sort(d);
    return d;
}

/// Pads `head` with a trailing line comment to exactly `size` bytes (ASCII only).
private ubyte[] padded(string head, ulong size)
{
    auto b = new ubyte[cast(size_t) size];
    b[] = 'x';
    const h = head ~ "//";
    b[0 .. h.length] = cast(const(ubyte)[]) h;
    return b;
}

/// `s1.s2…sN`.
private string segments(size_t n)
{
    import std.algorithm : map;
    import std.array : join;
    return iota(1, n + 1).map!(i => "s" ~ i.to!string).join(".");
}

unittest // limitsHaveTheirDocumentedValues: the tests below use the constants, so a change shows up here
{
    import codecity.analysis.java : maxImportCandidates, maxQualifiedPrefixes, maxTypeNesting;
    import codecity.analysis.rustlang : maxGlobImports, maxManifestBytes;
    import codecity.analysis.text : maxFqnBytes;
    static import codecity.analysis.java_parse;
    static import codecity.analysis.typescript_parse;
    static import codecity.analysis.rust_parse;
    assert(maxFileBytes == 16UL * 1024 * 1024);
    assert(maxFqnBytes == 1024);
    assert(maxQualifiedPrefixes == 16);
    assert(maxImportCandidates == 64);
    assert(maxTypeNesting == 16);
    assert(maxGlobImports == 64);
    assert(maxManifestBytes == 1024 * 1024);
    assert(codecity.analysis.java_parse.maxDepth == 300);
    assert(codecity.analysis.typescript_parse.maxDepth == 400);
    assert(codecity.analysis.rust_parse.maxDepth == 200);
}

unittest // fileSizeCapIsInclusive: exactly maxFileBytes is analysed; one byte more is not read and counts as failed
{
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("At.java", padded("class At {}\n", maxFileBytes));
    f.put("Past.java", padded("class Past {}\n", maxFileBytes + 1));
    auto a = f.analyze();
    assert(fqns(a) == ["At"]);
    assert(a.failedFiles == 1);
}

unittest // javaFqnCapIsInclusive: an fqn of exactly maxFqnBytes is kept, one byte longer skipped (package or outer type)
{
    import codecity.analysis.text : maxFqnBytes;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    const atPkg = "a".replicate(maxFqnBytes - 2);
    const pastPkg = "b".replicate(maxFqnBytes - 1);
    const atOuter = "O".replicate(maxFqnBytes - 2);
    const pastOuter = "Q".replicate(maxFqnBytes - 1);
    f.put("At.java", "package " ~ atPkg ~ ";\nclass C {}\n");
    f.put("Past.java", "package " ~ pastPkg ~ ";\nclass C {}\n");
    f.put("AtOuter.java", "class " ~ atOuter ~ " { class I {} }\n");
    f.put("PastOuter.java", "class " ~ pastOuter ~ " { class I {} }\n");
    auto a = f.analyze();
    assert(fqns(a) == sorted([atPkg ~ ".C", atOuter, atOuter ~ ".I", pastOuter]));
    assert(a.failedFiles == 2);
}

unittest // typeScriptFqnCapIsInclusive: path:name of exactly maxFqnBytes is kept, one byte longer skipped
{
    import codecity.analysis.text : maxFqnBytes;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    const at = "N".replicate(maxFqnBytes - "a.ts:".length);
    const past = "P".replicate(maxFqnBytes + 1 - "b.ts:".length);
    f.put("a.ts", "export class " ~ at ~ " {}\n");
    f.put("b.ts", "export class " ~ past ~ " {}\n");
    auto a = f.analyze();
    assert(fqns(a) == ["a.ts:" ~ at]);
    assert(a.failedFiles == 1);
}

unittest // rustFqnCapIsInclusive: item fqns and module building fqns (path::{fn}) of exactly maxFqnBytes are kept
{
    import codecity.analysis.text : maxFqnBytes;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    const atType = "S" ~ "s".replicate(maxFqnBytes - "k::".length - 1);
    const atMod = "m".replicate(maxFqnBytes - "k::".length - "::{fn}".length);
    const pastType = "P" ~ "p".replicate(maxFqnBytes + 1 - "k::past::".length - 1);
    const pastMod = "q".replicate(maxFqnBytes + 1 - "k::past::".length - "::{fn}".length);
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub mod past;\npub struct " ~ atType ~ ";\nmod " ~ atMod ~ " { fn f() {} }\n");
    f.put("src/past.rs", "pub struct " ~ pastType ~ ";\nmod " ~ pastMod ~ " { fn f() {} }\n");
    auto a = f.analyze();
    assert(fqns(a) == sorted(["k::" ~ atType, "k::" ~ atMod ~ "::{fn}"]));
    assert(a.failedFiles == 1);
}

unittest // qualifiedNamePrefixesAreCapped: only the shortest maxQualifiedPrefixes prefixes of a name are references
{
    import codecity.analysis.java : maxQualifiedPrefixes;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    enum cap = maxQualifiedPrefixes;
    const longest = segments(cap);
    const next = segments(cap + 1);
    f.put("Longest.java", format("package %s;\npublic class s%s {}\n", segments(cap - 1), cap));
    f.put("Next.java", format("package %s;\npublic class s%s {}\n", segments(cap), cap + 1));
    f.put("At.java", "class At { " ~ segments(cap + 1) ~ " x; }\n");
    f.put("Past.java", "class Past { " ~ segments(cap + 2) ~ " x; }\n");
    auto a = f.analyze();
    assert(deps(a, "At") == sorted([longest, next]));
    assert(deps(a, "Past") == [longest]);
}

unittest // singleTypeImportsPerNameAreCapped: only the first maxImportCandidates imports of one simple name are tried
{
    import codecity.analysis.java : maxImportCandidates;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    string others;
    foreach (i; 0 .. maxImportCandidates - 1) others ~= format("import w%s.D;\n", i);
    f.put("q/D.java", "package q; public class D {}\n");
    f.put("p/In.java", "package p;\n" ~ others ~ "import q.D;\nclass In { D d; }\n");
    f.put("p/Out.java", "package p;\n" ~ others ~ "import x.D;\nimport q.D;\nclass Out { D d; }\n");
    auto a = f.analyze();
    assert(deps(a, "p.In") == ["q.D"]);
    assert(deps(a, "p.Out").length == 0);
}

unittest // typeNestingCapIsInclusive: member types exactly maxTypeNesting deep are buildings; one level more is skipped
{
    import codecity.analysis.java : maxTypeNesting;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("A.java", "class A {".replicate(maxTypeNesting) ~ "}".replicate(maxTypeNesting));
    f.put("B.java", "class B {".replicate(maxTypeNesting + 1) ~ "}".replicate(maxTypeNesting + 1));
    auto a = f.analyze();
    auto all = fqns(a);
    assert(all.count!(x => x.startsWith("A")) == maxTypeNesting);
    assert(all.count!(x => x.startsWith("B")) == maxTypeNesting);
    assert(a.failedFiles == 1);
}

unittest // manifestSizeCapIsInclusive: a Cargo.toml of exactly maxManifestBytes is read; one byte more names no crate
{
    import codecity.analysis.rustlang : maxManifestBytes;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    f.put("a/src/lib.rs", "pub struct A;\n");
    f.put("b/src/lib.rs", "pub struct B;\n");
    f.put("a/Cargo.toml", padded("[package]\nname = \"atlimit\"\n", maxManifestBytes));
    f.put("b/Cargo.toml", padded("[package]\nname = \"past\"\n", maxManifestBytes + 1));
    auto a = f.analyze();
    auto all = fqns(a);
    assert(all.canFind("atlimit::A"));
    assert(!all.any!(x => x.startsWith("past::")));
}

unittest // javaParserDepthCap: array initialisers nested maxDepth - 1 deep parse cleanly; one more is flagged
{
    import codecity.analysis.java_parse : maxDepth;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    enum ok = maxDepth - 1;
    f.put("At.java", "class At { int[] x = " ~ "{".replicate(ok) ~ "}".replicate(ok) ~ "; }\n");
    f.put("Past.java", "class Past { int[] x = " ~ "{".replicate(ok + 1) ~ "}".replicate(ok + 1) ~ "; }\n");
    auto a = f.analyze();
    assert(fqns(a) == ["At", "Past"]);
    assert(a.failedFiles == 1);
}

unittest // typeScriptParserDepthCap: `if (x)` nested maxDepth - 3 deep parses cleanly; one more is flagged
{
    import codecity.analysis.typescript_parse : maxDepth;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    enum ok = maxDepth - 3;
    f.put("at.ts", "if (x) ".replicate(ok) ~ "y;\n");
    f.put("past.ts", "if (x) ".replicate(ok + 1) ~ "y;\n");
    auto a = f.analyze();
    assert(a.files == 2);
    assert(a.failedFiles == 1);
}

unittest // rustParserDepthCap: inline modules nested maxDepth - 1 deep parse cleanly; one more is flagged
{
    import codecity.analysis.rust_parse : maxDepth;
    auto f = Fixture.create();
    scope (exit) f.cleanup();
    enum ok = maxDepth - 1;
    f.put("Cargo.toml", "[package]\nname = \"k\"\n");
    f.put("src/lib.rs", "pub mod past;\n" ~ "mod a { ".replicate(ok) ~ "pub struct At;" ~ "}".replicate(ok) ~ "\n");
    f.put("src/past.rs", "mod a { ".replicate(ok + 1) ~ "pub struct Past;" ~ "}".replicate(ok + 1) ~ "\n");
    auto a = f.analyze();
    assert(fqns(a).canFind("k" ~ "::a".replicate(ok) ~ "::At"));
    assert(a.failedFiles == 1);
}
