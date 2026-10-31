package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documented work caps of the analysis, each tested exactly at its limit (still accepted) and one step past it
 * (cut off as documented), so that an off-by-one or a changed limit is noticed. {@link AdversarialInputTest} covers the
 * hostile inputs these caps exist for. The Rust ({@code analysis/caps_tests.rs}) and D ({@code analysis/capstests.d})
 * editions run the same scenarios with the same expectations.
 *
 * <p>Two caps are already tested at their exact boundary elsewhere and are not repeated here: on-demand imports
 * ({@link JavaAnalyzer#MAX_IMPORT_CANDIDATES}, {@code AdversarialInputTest.wildcardImportsAreCapped}) and Rust glob
 * imports ({@link RustAnalyzer#MAX_GLOB_IMPORTS}, {@code AdversarialInputTest.globImportsAreCapped}).
 */
class AnalysisCapsTest {

    @TempDir
    Path root;

    private void write(String rel, String code) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, code);
    }

    private Analysis analyze() throws IOException {
        return new SourceAnalyzer().analyze(root.toRealPath(), true);
    }

    private static Set<String> fqns(Analysis a) {
        return a.types().stream().map(TypeInfo::fqn).collect(Collectors.toSet());
    }

    /** Pads {@code head} with a trailing line comment to exactly {@code size} bytes (ASCII only). */
    private static byte[] padded(String head, long size) {
        byte[] b = new byte[(int) size];
        Arrays.fill(b, (byte) 'x');
        byte[] h = (head + "//").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(h, 0, b, 0, h.length);
        return b;
    }

    /** The limits have their documented values; the tests below use the constants, so a change shows up here. */
    @Test
    void limitsHaveTheirDocumentedValues() {
        assertEquals(16L * 1024 * 1024, SourceAnalyzer.MAX_FILE_BYTES);
        assertEquals(1024, SourceText.MAX_FQN_BYTES);
        assertEquals(16, JavaAnalyzer.MAX_QUALIFIED_PREFIXES);
        assertEquals(64, JavaAnalyzer.MAX_IMPORT_CANDIDATES);
        assertEquals(16, JavaAnalyzer.MAX_TYPE_NESTING);
        assertEquals(64, RustAnalyzer.MAX_GLOB_IMPORTS);
        assertEquals(1024L * 1024, RustAnalyzer.MAX_MANIFEST_BYTES);
        assertEquals(List.of(300, 400, 200), List.of(JavaParse.MAX_DEPTH, TypeScriptParse.MAX_DEPTH, RustParse.MAX_DEPTH));
    }

    /** A source file of exactly MAX_FILE_BYTES is analysed; one byte more and it is not read, and counts as failed. */
    @Test
    void fileSizeCapIsInclusive() throws IOException {
        Files.write(root.resolve("At.java"), padded("class At {}\n", SourceAnalyzer.MAX_FILE_BYTES));
        Files.write(root.resolve("Past.java"), padded("class Past {}\n", SourceAnalyzer.MAX_FILE_BYTES + 1));
        Analysis a = analyze();
        assertEquals(Set.of("At"), fqns(a));
        assertEquals(1, a.failedFiles());
    }

    /**
     * Java: a type whose fqn is exactly MAX_FQN_BYTES long is a building, one byte longer is skipped (its file
     * failed), whether the length comes from the package or from an outer type; the outer type itself is kept.
     */
    @Test
    void javaFqnCapIsInclusive() throws IOException {
        int max = SourceText.MAX_FQN_BYTES;
        String atPkg = "a".repeat(max - 2);
        String pastPkg = "b".repeat(max - 1);
        String atOuter = "O".repeat(max - 2);
        String pastOuter = "Q".repeat(max - 1);
        write("At.java", "package " + atPkg + ";\nclass C {}\n");
        write("Past.java", "package " + pastPkg + ";\nclass C {}\n");
        write("AtOuter.java", "class " + atOuter + " { class I {} }\n");
        write("PastOuter.java", "class " + pastOuter + " { class I {} }\n");
        Analysis a = analyze();
        assertEquals(Set.of(atPkg + ".C", atOuter, atOuter + ".I", pastOuter), fqns(a));
        assertEquals(2, a.failedFiles());
    }

    /** TypeScript: {@code path:name} of exactly MAX_FQN_BYTES is a building, one byte longer is skipped. */
    @Test
    void typeScriptFqnCapIsInclusive() throws IOException {
        int max = SourceText.MAX_FQN_BYTES;
        String at = "N".repeat(max - "a.ts:".length());
        String past = "P".repeat(max + 1 - "b.ts:".length());
        write("a.ts", "export class " + at + " {}\n");
        write("b.ts", "export class " + past + " {}\n");
        Analysis a = analyze();
        assertEquals(Set.of("a.ts:" + at), fqns(a));
        assertEquals(1, a.failedFiles());
    }

    /**
     * Rust: an item fqn of exactly MAX_FQN_BYTES is a building, one byte longer is skipped; an inline module is kept
     * while its module building's fqn ({@code path::{fn}}) fits exactly, and skipped with everything in it beyond.
     */
    @Test
    void rustFqnCapIsInclusive() throws IOException {
        int max = SourceText.MAX_FQN_BYTES;
        String atType = "S" + "s".repeat(max - "k::".length() - 1);
        String atMod = "m".repeat(max - "k::".length() - "::{fn}".length());
        String pastType = "P" + "p".repeat(max + 1 - "k::past::".length() - 1);
        String pastMod = "q".repeat(max + 1 - "k::past::".length() - "::{fn}".length());
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", "pub mod past;\npub struct " + atType + ";\nmod " + atMod + " { fn f() {} }\n");
        write("src/past.rs", "pub struct " + pastType + ";\nmod " + pastMod + " { fn f() {} }\n");
        Analysis a = analyze();
        assertEquals(Set.of("k::" + atType, "k::" + atMod + "::{fn}"), fqns(a));
        assertEquals(1, a.failedFiles());
    }

    /**
     * Java: a qualified name contributes at most MAX_QUALIFIED_PREFIXES of its prefixes as references, the shortest.
     * With one segment more than that, every prefix still counts; with two more, the longest prefix is dropped.
     */
    @Test
    void qualifiedNamePrefixesAreCapped() throws IOException {
        int cap = JavaAnalyzer.MAX_QUALIFIED_PREFIXES;
        String longest = segments(cap); // the longest prefix still collected
        String next = segments(cap + 1);
        write("Longest.java", "package " + segments(cap - 1) + ";\npublic class s" + cap + " {}\n");
        write("Next.java", "package " + segments(cap) + ";\npublic class s" + (cap + 1) + " {}\n");
        write("At.java", "class At { " + segments(cap + 1) + " x; }\n");
        write("Past.java", "class Past { " + segments(cap + 2) + " x; }\n");
        Analysis a = analyze();
        assertEquals(Set.of(longest, next), a.dependencies().get("At"));
        assertEquals(Set.of(longest), a.dependencies().get("Past"));
    }

    /** {@code s1.s2…sN}. */
    private static String segments(int n) {
        StringBuilder b = new StringBuilder("s1");
        for (int i = 2; i <= n; i++) {
            b.append(".s").append(i);
        }
        return b.toString();
    }

    /** Java: of the single-type imports of one simple name, only the first MAX_IMPORT_CANDIDATES are tried. */
    @Test
    void singleTypeImportsPerNameAreCapped() throws IOException {
        int cap = JavaAnalyzer.MAX_IMPORT_CANDIDATES;
        StringBuilder others = new StringBuilder();
        for (int i = 0; i < cap - 1; i++) {
            others.append("import w").append(i).append(".D;\n");
        }
        write("q/D.java", "package q; public class D {}\n");
        write("p/In.java", "package p;\n" + others + "import q.D;\nclass In { D d; }\n");
        write("p/Out.java", "package p;\n" + others + "import x.D;\nimport q.D;\nclass Out { D d; }\n");
        Analysis a = analyze();
        assertEquals(Set.of("q.D"), a.dependencies().get("p.In"));
        assertEquals(Set.of(), a.dependencies().get("p.Out"));
    }

    /** Java: member types nested exactly MAX_TYPE_NESTING deep are all buildings; one level more is skipped. */
    @Test
    void typeNestingCapIsInclusive() throws IOException {
        int cap = JavaAnalyzer.MAX_TYPE_NESTING;
        write("A.java", "class A {".repeat(cap) + "}".repeat(cap));
        write("B.java", "class B {".repeat(cap + 1) + "}".repeat(cap + 1));
        Analysis a = analyze();
        assertEquals(cap, fqns(a).stream().filter(f -> f.startsWith("A")).count());
        assertEquals(cap, fqns(a).stream().filter(f -> f.startsWith("B")).count());
        assertEquals(1, a.failedFiles());
    }

    /** A Cargo.toml of exactly MAX_MANIFEST_BYTES is read; one byte more and it names no crate. */
    @Test
    void manifestSizeCapIsInclusive() throws IOException {
        Files.createDirectories(root.resolve("a/src"));
        Files.createDirectories(root.resolve("b/src"));
        Files.write(root.resolve("a/Cargo.toml"), padded("[package]\nname = \"atlimit\"\n", RustAnalyzer.MAX_MANIFEST_BYTES));
        Files.write(root.resolve("b/Cargo.toml"), padded("[package]\nname = \"past\"\n", RustAnalyzer.MAX_MANIFEST_BYTES + 1));
        write("a/src/lib.rs", "pub struct A;\n");
        write("b/src/lib.rs", "pub struct B;\n");
        Set<String> fqns = fqns(analyze());
        assertTrue(fqns.contains("atlimit::A"));
        assertFalse(fqns.stream().anyMatch(f -> f.startsWith("past::")));
    }

    /**
     * Java parser: array initialisers nested MAX_DEPTH - 1 deep (the field takes one level) parse cleanly; one more
     * level reaches the cap and the file is flagged.
     */
    @Test
    void javaParserDepthCap() throws IOException {
        int ok = JavaParse.MAX_DEPTH - 1;
        write("At.java", "class At { int[] x = " + "{".repeat(ok) + "}".repeat(ok) + "; }\n");
        write("Past.java", "class Past { int[] x = " + "{".repeat(ok + 1) + "}".repeat(ok + 1) + "; }\n");
        Analysis a = analyze();
        assertEquals(Set.of("At", "Past"), fqns(a));
        assertEquals(1, a.failedFiles());
    }

    /**
     * TypeScript parser: {@code if (x)} statements nested MAX_DEPTH - 3 deep (the file and the innermost statement take
     * the other levels) parse cleanly; one more reaches the cap and the file is flagged.
     */
    @Test
    void typeScriptParserDepthCap() throws IOException {
        int ok = TypeScriptParse.MAX_DEPTH - 3;
        write("at.ts", "if (x) ".repeat(ok) + "y;\n");
        write("past.ts", "if (x) ".repeat(ok + 1) + "y;\n");
        Analysis a = analyze();
        assertEquals(2, a.files());
        assertEquals(1, a.failedFiles());
    }

    /**
     * Rust parser: inline modules nested MAX_DEPTH - 1 deep (the file takes one level) parse cleanly; one more reaches
     * the cap and the file is flagged.
     */
    @Test
    void rustParserDepthCap() throws IOException {
        int ok = RustParse.MAX_DEPTH - 1;
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", "pub mod past;\n" + "mod a { ".repeat(ok) + "pub struct At;" + "}".repeat(ok) + "\n");
        write("src/past.rs", "mod a { ".repeat(ok + 1) + "pub struct Past;" + "}".repeat(ok + 1) + "\n");
        Analysis a = analyze();
        assertTrue(fqns(a).contains("k" + "::a".repeat(ok) + "::At"));
        assertEquals(1, a.failedFiles());
    }
}
