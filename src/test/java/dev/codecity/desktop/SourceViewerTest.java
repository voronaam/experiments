package dev.codecity.desktop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceViewerTest {

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
