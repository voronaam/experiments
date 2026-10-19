package dev.codecity.analysis;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Text helpers shared by the language analysers (all supported languages use C-style comments). */
public final class SourceText {

    /**
     * Directory names that mark test code: Maven/Gradle ({@code test}, {@code it} for integration tests,
     * {@code testFixtures}), Android ({@code androidTest}), Cargo and many others ({@code tests}) and Jest
     * ({@code __tests__}).
     */
    private static final Set<String> TEST_DIRS = Set.of("test", "tests", "it", "androidTest", "testFixtures", "__tests__");

    private SourceText() {
    }

    /**
     * Reads a source file as UTF-8, falling back to ISO-8859-1 for legacy files that are not valid UTF-8. The fallback
     * cannot fail (every byte is a character), so a file with a stray Latin-1 byte is still analysed rather than lost.
     */
    public static String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Splits on {@code \n} only, so index {@code i} is line {@code i + 1} as the parsers number lines: a lone
     * {@code \r} (classic Mac line ending) does not break the line, and the {@code \r} of a {@code \r\n} stays at the
     * end of its line, where {@link #countLoc}'s {@code strip()} drops it. The limit of -1 keeps trailing empty
     * lines, so a file that ends with a newline yields one final empty element.
     */
    public static String[] lines(String code) {
        return code.split("\n", -1);
    }

    /**
     * Non-blank lines in [begin, end] (1-based, inclusive) that are not purely comments.
     *
     * <p>A line-based heuristic, not a lexer: a line counts unless, after stripping block comments that start at the
     * beginning of the line, it is empty or starts with {@code //}. Code followed by a comment counts; a comment
     * followed by code on the same line counts too. A block comment opened after code ({@code x = 1; /* starts here})
     * is tracked by {@link #opensBlock}, so its following lines do not count. Comment markers inside string literals
     * are taken at face value, except by {@code opensBlock}, which skips literals; this is rare enough not to matter
     * for a size metric. Out-of-range bounds are clamped to the file.
     */
    public static int countLoc(String[] lines, int begin, int end) {
        int loc = 0;
        // True while inside a /* … */ comment that started on an earlier line.
        boolean inBlock = false;
        for (int i = Math.max(begin, 1) - 1; i < Math.min(end, lines.length); i++) {
            String s = lines[i].strip();
            if (inBlock) {
                // Skip the line unless the comment ends on it; then judge what follows the "*/".
                int close = s.indexOf("*/");
                if (close < 0) {
                    continue;
                }
                inBlock = false;
                s = s.substring(close + 2).strip();
            }
            if (s.startsWith("/*")) {
                // Search from index 2 so that "/*/" is not mistaken for an opening and closing marker.
                int close = s.indexOf("*/", 2);
                if (close < 0) {
                    inBlock = true;
                    continue;
                }
                s = s.substring(close + 2).strip();
            }
            if (!s.isEmpty() && !s.startsWith("//")) {
                loc++;
                inBlock = opensBlock(s);
            }
        }
        return loc;
    }

    /**
     * True when a line of code leaves a {@code /*} comment open ({@code x = 1; /* starts here}), so the next lines are
     * inside it.
     *
     * <p>Scans left to right: comment pairs on the line are skipped, {@code //} ends the scan, and double-quoted
     * strings (with backslash escapes) are skipped, so {@code "**}{@code /*.java"} does not open a comment.
     * One-character literals ({@code '"'}, {@code '\''}) are skipped so their quote does not start a string; a lone
     * {@code '} (a Rust lifetime) is ignored.
     */
    private static boolean opensBlock(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' && i + 2 < s.length() && s.charAt(i + 2) == '\'') {
                i += 2; // 'x'
            } else if (c == '\'' && i + 3 < s.length() && s.charAt(i + 1) == '\\' && s.charAt(i + 3) == '\'') {
                i += 3; // '\x'
            } else if (c == '"') {
                // Skip to the closing quote; an unterminated string runs to the end of the line.
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    i += s.charAt(i) == '\\' ? 2 : 1;
                }
            } else if (s.startsWith("//", i)) {
                return false;
            } else if (s.startsWith("/*", i)) {
                int close = s.indexOf("*/", i + 2);
                if (close < 0) {
                    return true;
                }
                i = close + 1; // the '/' of "*/"; the i++ below steps past it
            }
            i++;
        }
        return false;
    }

    /**
     * True when any directory on the path is a conventional test directory. Only directories count, not the file
     * name, and the path should be relative to the analysed root so that the root's own location does not matter.
     */
    public static boolean inTestDirectory(Path relative) {
        Path parent = relative.getParent();
        if (parent == null) {
            return false;
        }
        for (Path p : parent) {
            if (TEST_DIRS.contains(p.toString())) {
                return true;
            }
        }
        return false;
    }

    /** {@code a/b/c} with forward slashes regardless of platform. */
    public static String slashes(Path p) {
        return p == null ? "" : p.toString().replace('\\', '/');
    }
}
