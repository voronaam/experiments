package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hostile input for the analysers: inputs that used to take quadratic time or memory, overflow the stack, or read
 * outside the analysed tree. Each analysis must finish well within its time limit (the old behaviour took minutes or
 * crashed). The Rust ({@code analysis/tests.rs}) and D ({@code analysis/tests.d}) editions run the same cases.
 */
class AdversarialInputTest {

    /** Generous for these inputs in linear time, far too short for the quadratic behaviour they guard against. */
    private static final Duration LIMIT = Duration.ofSeconds(20);

    @TempDir
    Path root;

    private void write(String rel, String code) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, code);
    }

    private Analysis analyze() {
        return assertTimeoutPreemptively(LIMIT, () -> new SourceAnalyzer().analyze(root, true));
    }

    private static Map<String, TypeInfo> byFqn(Analysis a) {
        return a.types().stream().collect(Collectors.toMap(TypeInfo::fqn, Function.identity()));
    }

    /** The hand-written scan finds what the regular expression it replaced found. */
    @Test
    void cargoPackageNameScan() {
        String[][] cases = {
                {"[package]\nname = \"a-b\"\n", "a-b"},
                {"[package]\nauthors = [\"x\"]\nname = \"n\"\n", "n"},
                {"[package] # c\n  name\t=  \"x\"", "x"},
                {"[package]\nversion = \"1\"\n\n  \nname = \"x\"\n", "x"},
                {"[package]\r\nname = \"x\"\r\n", "x"},
                {"[package]\nname =\n \"x\"\n", "x"},
                {"[package]\nname = \"\"\nname = \"y\"\n", "y"},
                {"[package]\n[dependencies]\n[package]\nname = \"second\"\n", "second"},
                {"[workspace]\nname = \"w\"\n", null},
                {"[package]\n[lib]\nname = \"l\"\n", null},
                {"  [package]\nname = \"x\"\n", null},
                {"[package]", null},
                {"[package]\nname = \"x", null},
                {"[package]\n\fname = \"x\"\n", null},
                {"[package]\nversion = 1", null},
        };
        for (String[] c : cases) {
            assertEquals(c[1], RustAnalyzer.packageName(c[0]), c[0]);
        }
    }

    /** The regular expression recursed once per line it skipped: a long [package] table overflowed the stack. */
    @Test
    void longCargoPackageTable() throws IOException {
        write("Cargo.toml", "[package]\n" + "a = 1\n".repeat(100_000) + "name = \"deep\"\n");
        write("src/lib.rs", "pub struct A;\n");
        assertTrue(byFqn(analyze()).containsKey("deep::A"));
    }

    /** Manifests are read only as regular files within the size cap; a symbolic link could point anywhere. */
    @Test
    void symlinkedAndOversizedManifestsAreIgnored() throws IOException {
        Path outside = Files.createTempFile("codecity-outside", ".toml");
        try {
            Files.writeString(outside, "[package]\nname = \"leak\"\n");
            Files.createDirectories(root.resolve("a/src"));
            Files.createSymbolicLink(root.resolve("a/Cargo.toml"), outside);
            write("a/src/lib.rs", "pub struct A;\n");
            write("b/Cargo.toml", "[package]\nname = \"big\"\n" + "#".repeat((int) RustAnalyzer.MAX_MANIFEST_BYTES));
            write("b/src/lib.rs", "pub struct B;\n");
            Files.createDirectories(root.resolve("web/src"));
            Files.createSymbolicLink(root.resolve("web/package.json"), outside);
            write("web/src/c.ts", "export class C {}\n");
            Analysis a = analyze();
            assertTrue(a.types().stream().noneMatch(t -> t.fqn().startsWith("leak::") || t.fqn().startsWith("big::")));
            assertEquals("", byFqn(a).get("web/src/c.ts:C").module(), "a linked package.json is no project");
        } finally {
            Files.delete(outside);
        }
    }

    /** All methods on one line: lines of code used to be judged once per method, over the whole line each time. */
    @Test
    void minifiedSingleLine() throws IOException {
        StringBuilder b = new StringBuilder("class C {");
        for (int i = 0; i < 50_000; i++) {
            b.append(" void m").append(i).append("() { int x = 1; }");
        }
        write("C.java", b.append(" }").toString());
        TypeInfo c = byFqn(analyze()).get("C");
        assertEquals(50_000, c.methods().size());
        assertEquals(1, c.loc());
    }

    /** a < a < a …: every {@code <} was tried as type arguments down to the depth cap, re-lexing each time. */
    @Test
    void typeScriptComparisonChain() throws IOException {
        write("a.ts", "export const x = " + "a < ".repeat(100_000) + "b;\nexport class After {}\n");
        assertTrue(byFqn(analyze()).containsKey("a.ts:After"));
    }

    /** A.A.A…: every prefix of a qualified name was a reference, quadratic in its length. */
    @Test
    void longQualifiedName() throws IOException {
        write("C.java", "class C { A" + ".A".repeat(200_000) + " x; }");
        assertTrue(byFqn(analyze()).get("C").references().size() <= JavaAnalyzer.MAX_QUALIFIED_PREFIXES + 1);
    }

    /** Every reference was checked against every import. */
    @Test
    void manyImports() throws IOException {
        StringBuilder b = new StringBuilder("package p;\n");
        for (int i = 0; i < 20_000; i++) {
            b.append("import a").append(i).append(".B;\nimport w").append(i).append(".*;\n");
        }
        b.append("class C {");
        for (int i = 0; i < 20_000; i++) {
            b.append(" B").append(i).append(" x").append(i).append(';');
        }
        write("p/C.java", b.append(" }").toString());
        StringBuilder ts = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            ts.append("import { B").append(i).append(" } from \"./b").append(i).append("\";\n");
        }
        for (int i = 0; i < 20_000; i++) {
            ts.append("export class C").append(i).append(" { x: B").append(i).append("; }\n");
        }
        write("a.ts", ts.toString());
        assertTrue(analyze().types().size() >= 20_001);
    }

    /** Only the first {@link JavaAnalyzer.MAX_IMPORT_CANDIDATES} on-demand imports of a file are used. */
    @Test
    void wildcardImportsAreCapped() throws IOException {
        write("q/D.java", "package q; public class D {}");
        String first = "import w.*;\n".repeat(JavaAnalyzer.MAX_IMPORT_CANDIDATES - 1);
        write("p/In.java", "package p;\n" + first + "import q.*;\nclass In { D d; }");
        write("p/Out.java", "package p;\n" + first + "import w.*;\nimport q.*;\nclass Out { D d; }");
        Analysis a = analyze();
        assertTrue(a.dependencies().get("p.In").contains("q.D"));
        assertFalse(a.dependencies().get("p.Out").contains("q.D"));
    }

    /** The same for Rust glob imports, with {@link RustAnalyzer#MAX_GLOB_IMPORTS} per module. */
    @Test
    void globImportsAreCapped() throws IOException {
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        String first = "use w::*;\n".repeat(RustAnalyzer.MAX_GLOB_IMPORTS - 1);
        write("src/lib.rs", "pub mod q { pub struct D; }\n"
                + "pub mod a {\n" + first + "use crate::q::*;\npub struct In { d: D }\n}\n"
                + "pub mod b {\n" + first + "use w::*;\nuse crate::q::*;\npub struct Out { d: D }\n}\n");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            many.append("use g").append(i).append("::*;\n");
        }
        many.append("pub struct S {");
        for (int i = 0; i < 20_000; i++) {
            many.append(" x").append(i).append(": B").append(i).append(',');
        }
        write("src/many.rs", many.append(" }\n").toString());
        Analysis a = analyze();
        assertTrue(a.dependencies().get("k::a::In").contains("k::q::D"));
        assertFalse(a.dependencies().get("k::b::Out").contains("k::q::D"));
    }

    /**
     * A long package, outer type, namespace or module name used to be copied into the fqn of every building inside it.
     * Such buildings are skipped now ({@link SourceText#MAX_FQN_BYTES}), and their files count as failed.
     */
    @Test
    void overLongNamesAreSkipped() throws IOException {
        String longName = "N".repeat(SourceText.MAX_FQN_BYTES + 1);
        StringBuilder classes = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            classes.append("class C").append(i).append(" {}\n");
        }
        write("p/A.java", "package " + longName + ";\n" + classes);
        write("p/B.java", "class " + longName + " {\n" + classes + "}\nclass Kept {}\n");
        write("a.ts", "namespace " + longName + " {\n" + classes.toString().replace("class", "export class")
                + "}\nexport class Kept {}\n");
        write("Cargo.toml", "[package]\nname = \"k\"\n");
        write("src/lib.rs", "mod " + longName.toLowerCase() + " {\n" + classes.toString().replace("class", "pub struct")
                .replace(" {}", ";") + "}\npub struct Kept;\n");
        Analysis a = analyze();
        assertEquals(java.util.Set.of("Kept", "a.ts:Kept", "k::Kept"),
                a.types().stream().map(TypeInfo::fqn).collect(Collectors.toSet()));
        assertEquals(4, a.failedFiles());
    }

    /** Member types nested deeper than {@link JavaAnalyzer#MAX_TYPE_NESTING} are not buildings. */
    @Test
    void deepMemberTypesAreSkipped() throws IOException {
        write("C.java", "class C0 {".repeat(JavaAnalyzer.MAX_TYPE_NESTING + 4) + "}".repeat(JavaAnalyzer.MAX_TYPE_NESTING + 4));
        Analysis a = analyze();
        assertEquals(JavaAnalyzer.MAX_TYPE_NESTING, a.types().size());
        assertEquals(1, a.failedFiles());
    }

    /** Repeated declarations of one name get numbered ids; finding the next number used to rescan from the first. */
    @Test
    void repeatedTypeScriptDeclarations() throws IOException {
        write("a.ts", "interface A {}\n".repeat(30_000));
        assertTrue(byFqn(analyze()).containsKey("a.ts:A#interface29999"));
    }

    /**
     * Safety net: an analyser that overflows the stack (none is expected, the parsers cap their recursion) fails that
     * file only, like any other analyser crash, and the analysis goes on.
     */
    @Test
    void stackOverflowInAnAnalyserFailsOnlyThatFile() throws IOException {
        JavaAnalyzer real = new JavaAnalyzer();
        LanguageSupport crashing = new LanguageSupport() {
            @Override
            public Language language() {
                return Language.JAVA;
            }

            @Override
            public boolean accepts(Path file) {
                return real.accepts(file);
            }

            @Override
            public ParsedFile parse(Path root, Path file, String code) throws IOException {
                if (file.getFileName().toString().equals("Bad.java")) {
                    throw new StackOverflowError();
                }
                return real.parse(root, file, code);
            }

            @Override
            public Linked link(Path root, java.util.List<ParsedFile> files) {
                return real.link(root, files);
            }
        };
        write("Bad.java", "class Bad {}");
        write("Good.java", "class Good {}");
        Analysis a = assertTimeoutPreemptively(LIMIT,
                () -> new SourceAnalyzer(java.util.List.of(crashing)).analyze(root, true));
        assertEquals(java.util.Set.of("Good"), byFqn(a).keySet());
        assertEquals(1, a.failedFiles());
    }
}
