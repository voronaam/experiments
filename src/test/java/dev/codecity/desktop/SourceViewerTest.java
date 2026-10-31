package dev.codecity.desktop;

import dev.codecity.analysis.Language;
import dev.codecity.json.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the source highlighter ({@link SourceViewer#highlight}).
 *
 * <p>The samples in {@code testdata/highlight/} (one per language) must give exactly the tokens in their
 * {@code .tokens.json} files, which Rust's {@code source_tests.rs} and D's {@code source.d} check too, so the three
 * source windows colour code identically: a JSON array with one entry per line, each an array of {@code [style, text]}
 * pairs. The hostile inputs check the promises on anything: it terminates quickly, gives one line per line of input
 * and loses no character but {@code \r}.
 */
class SourceViewerTest {

    private static final Path SAMPLES = Path.of("testdata", "highlight");

    /** The highlighted lines in the shared JSON form, parsed: lists of [style, text] lists. */
    private static List<List<List<String>>> tokens(List<List<SourceViewer.Token>> lines) {
        return lines.stream().map(l -> l.stream().map(t -> List.of(t.style(), t.text())).toList()).toList();
    }

    private static void check(String file, Language language) throws IOException {
        String code = Files.readString(SAMPLES.resolve(file));
        List<List<List<String>>> actual = tokens(SourceViewer.highlight(code, language));
        Object expected = JsonParser.parse(Files.readString(SAMPLES.resolve(file + ".tokens.json")));
        List<?> lines = (List<?>) expected;
        assertEquals(lines.size(), actual.size(), file + ": line count");
        for (int n = 0; n < lines.size(); n++) {
            assertEquals(lines.get(n), actual.get(n), file + ": line " + (n + 1));
        }
    }

    @Test
    void javaSampleMatchesTheSharedTokens() throws IOException {
        check("Sample.java", Language.JAVA);
    }

    @Test
    void typescriptSampleMatchesTheSharedTokens() throws IOException {
        check("sample.ts", Language.TYPESCRIPT);
    }

    @Test
    void rustSampleMatchesTheSharedTokens() throws IOException {
        check("sample.rs", Language.RUST);
    }

    /** One line per input line, and every character but '\r' kept in order, in no empty token. */
    private static void assertLossless(String code, Language language) {
        List<List<SourceViewer.Token>> lines = SourceViewer.highlight(code, language);
        String[] expected = code.replace("\r", "").split("\n", -1);
        assertEquals(expected.length, lines.size(), "line count");
        for (int n = 0; n < expected.length; n++) {
            assertEquals(expected[n], lines.get(n).stream().map(SourceViewer.Token::text).collect(Collectors.joining()));
            assertFalse(lines.get(n).stream().anyMatch(t -> t.text().isEmpty()), "empty token");
        }
    }

    @Test
    void hostileInputKeepsEveryCharacterAndLine() {
        String[] cases = {"", "\n", "a\n", "\r\n\r\n", "x = 1;\r\ny = 2;\r\n", "/*", "/* a\r\n b */ c", "\"", "\"\\",
                "'", "`", "\"\"\"", "#[", "#[derive(\n)]", "@", "@\n", "#", "0x", "1.2.3.4e+-5", "'a", "'ab'", "a'b'c'",
                "\u0000\u0001\u0007\u001b[31m\u007f", "\ufeffclass Ä { String ü = \"ö\"; }",
                "let 𝑥 = '😀'; // 😀\u202eevil", "\ufffd\ufffd broken \ufffd", "\\\\\\", "/* /* */ */",
                "// comment \\\nnext", "`${`${`nested`}`}`"};
        for (String code : cases) {
            for (Language language : Language.values()) {
                assertLossless(code, language);
            }
        }
    }

    @Test
    void hugeAndPathologicalInputFinishesQuickly() {
        long start = System.nanoTime();
        String longLine = "/*".repeat(50_000) + "\"".repeat(50_000) + "a".repeat(100_000);
        String manyLines = "\n".repeat(100_000);
        String soup = "/* x */ \"y\" 'z' `w` @a #[b] 12 ".repeat(20_000);
        for (String code : List.of(longLine, manyLines, soup)) {
            for (Language language : Language.values()) {
                assertLossless(code, language);
            }
        }
        assertEquals(100_001, SourceViewer.highlight(manyLines, Language.JAVA).size());
        assertTrue(System.nanoTime() - start < 30_000_000_000L);
    }

    @Test
    void literalsAndCommentsByLanguage() {
        assertEquals("keyword", style("fn x", Language.RUST, "fn"));
        assertEquals(null, style("fn x", Language.JAVA, "fn"));
        assertEquals("keyword", style("let x", Language.TYPESCRIPT, "let"));
        assertEquals("keyword", style("record R", Language.JAVA, "record"));
        assertEquals("annotation", style("&'a str", Language.RUST, "'a"));
        assertEquals("string", style("x('a str')", Language.TYPESCRIPT, "'a str'"));
        assertEquals("string", style("let c = 'x';", Language.RUST, "'x'"));
        List<List<SourceViewer.Token>> lines = SourceViewer.highlight("s = \"\"\"\n  a\n  \"\"\";", Language.JAVA);
        assertTrue(lines.get(1).stream().allMatch(t -> t.style().equals("string")));
        assertEquals("type", style("A b", Language.JAVA, "A"));
        assertEquals("constant", style("AB b", Language.JAVA, "AB"));
        assertEquals("type", style("Ab b", Language.JAVA, "Ab"));
        assertEquals("annotation", style("@Override x", Language.JAVA, "@Override"));
        assertEquals("annotation", style("#priv = 1", Language.TYPESCRIPT, "#priv"));
    }

    /** The style of the first token {@code text} in {@code code}, or null. */
    private static String style(String code, Language language, String text) {
        return SourceViewer.highlight(code, language).stream().flatMap(List::stream).filter(t -> t.text().equals(text))
                .map(SourceViewer.Token::style).findFirst().orElse(null);
    }

    private static String styleOf(List<?> line, String text) {
        return line.stream().map(Object::toString).filter(t -> t.contains("text=" + text + ","))
                .findFirst().orElseThrow(() -> new AssertionError(text + " not found in " + line));
    }

    @Test
    void highlightsTokensAndKeepsLinesAligned() {
        var lines = SourceViewer.highlight("""
                /* multi
                   line */ public class Foo { // note
                  static final int MAX = 42; String s = "a\\"b";
                }""");
        assertEquals(4, lines.size());
        assertTrue(styleOf(lines.get(0), "/*").contains("comment"));
        assertTrue(styleOf(lines.get(1), "   line */").contains("comment"));
        assertTrue(styleOf(lines.get(1), "public").contains("keyword"));
        assertTrue(styleOf(lines.get(1), "Foo").contains("type"));
        assertTrue(styleOf(lines.get(1), "// note").contains("comment"));
        assertTrue(styleOf(lines.get(2), "MAX").contains("constant"));
        assertTrue(styleOf(lines.get(2), "42").contains("number"));
        assertTrue(styleOf(lines.get(2), "\"a\\\"b\"").contains("string"));
    }

    @Test
    void unterminatedLiteralsDoNotMergeLines() {
        var lines = SourceViewer.highlight("String a = \"open\nString b = \"x\\\nint C = 1;\n");
        assertEquals(4, lines.size());
        assertTrue(styleOf(lines.get(1), "String").contains("type"));
        assertTrue(styleOf(lines.get(2), "int").contains("keyword"));
    }

    @Test
    void lifetimesOnlyInRust() {
        var ts = SourceViewer.highlight("import 'react';", Set.of("import"));
        assertTrue(styleOf(ts.get(0), "'react'").contains("string"));
    }
}
