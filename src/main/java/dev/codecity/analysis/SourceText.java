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

    /**
     * Longest fully qualified name, in UTF-8 bytes, a building may have. A declaration whose fqn would be longer is
     * skipped together with everything nested in it, and its file counts as failed. Real names stay far below; without
     * the cap, a crafted long package, namespace, module or outer type name would be copied into the fqn of every
     * building inside it, which is quadratic in the file's size. The Rust and D editions use the same limit.
     */
    public static final int MAX_FQN_BYTES = 1024;

    private SourceText() {
    }

    /** The length of {@code s} in UTF-8 bytes, which is how the Rust and D editions measure strings. */
    public static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // a surrogate pair is 4 bytes, 2 per half
            n += c < 0x80 ? 1 : c < 0x800 || Character.isSurrogate(c) ? 2 : 3;
        }
        return n;
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
     * The lines of a file, split on {@code \n} only, reduced to what {@link #countLoc} needs: for each line, whether it
     * counts as code and whether a {@code /*} comment is open after it, once for each of the two states the line can
     * start in (inside such a comment or not). Computing this once per file keeps {@code countLoc} at constant cost
     * per line: it is called for every type and method, and their line ranges overlap (all of them, in a minified
     * single-line file), so judging the text itself on every call would be quadratic in the file's size.
     */
    public static final class Lines {
        /** Per line: {@link #COUNTS} and {@link #OPENS} for a line starting outside a comment, shifted by 2 inside. */
        private final byte[] facts;

        private static final int COUNTS = 1;
        private static final int OPENS = 2;

        private Lines(byte[] facts) {
            this.facts = facts;
        }

        /** The number of lines; a file that ends with a newline has a final empty line. */
        public int count() {
            return facts.length;
        }
    }

    /**
     * Splits on {@code \n} only, so index {@code i} is line {@code i + 1} as the parsers number lines: a lone
     * {@code \r} (classic Mac line ending) does not break the line, and the {@code \r} of a {@code \r\n} stays at the
     * end of its line, where {@link #countLoc}'s {@code strip()} drops it. Trailing empty lines are kept, so a file
     * that ends with a newline yields one final empty line.
     */
    public static Lines lines(String code) {
        String[] text = code.split("\n", -1);
        byte[] facts = new byte[text.length];
        for (int i = 0; i < text.length; i++) {
            facts[i] = (byte) (judge(text[i], false) | judge(text[i], true) << 2);
        }
        return new Lines(facts);
    }

    /**
     * Non-blank lines in [begin, end] (1-based, inclusive) that are not purely comments.
     *
     * <p>A line-based heuristic, not a lexer: a line counts unless, after stripping block comments that start at the
     * beginning of the line, it is empty or starts with {@code //}. Code followed by a comment counts; a comment
     * followed by code on the same line counts too. A block comment opened after code ({@code x = 1; /* starts here})
     * is tracked by {@link #opensBlock}, so its following lines do not count. Comment markers inside string literals
     * are taken at face value, except by {@code opensBlock}, which skips literals; this is rare enough not to matter
     * for a size metric. Out-of-range bounds are clamped to the file. The range starts outside a comment.
     */
    public static int countLoc(Lines lines, int begin, int end) {
        int loc = 0;
        // True while inside a /* … */ comment that started on an earlier line.
        boolean inBlock = false;
        for (int i = Math.max(begin, 1) - 1; i < Math.min(end, lines.count()); i++) {
            int facts = inBlock ? lines.facts[i] >> 2 : lines.facts[i];
            if ((facts & Lines.COUNTS) != 0) {
                loc++;
            }
            inBlock = (facts & Lines.OPENS) != 0;
        }
        return loc;
    }

    /**
     * How one line is judged by {@link #countLoc}: {@link Lines#COUNTS} if it counts as code, plus
     * {@link Lines#OPENS} if a {@code /*} comment is open after it. {@code inBlock}: the line starts inside one.
     */
    private static int judge(String line, boolean inBlock) {
        String s = line.strip();
        if (inBlock) {
            // Skip the line unless the comment ends on it; then judge what follows the "*/".
            int close = s.indexOf("*/");
            if (close < 0) {
                return Lines.OPENS;
            }
            s = s.substring(close + 2).strip();
        }
        if (s.startsWith("/*")) {
            // Search from index 2 so that "/*/" is not mistaken for an opening and closing marker.
            int close = s.indexOf("*/", 2);
            if (close < 0) {
                return Lines.OPENS;
            }
            s = s.substring(close + 2).strip();
        }
        if (!s.isEmpty() && !s.startsWith("//")) {
            return Lines.COUNTS | (opensBlock(s) ? Lines.OPENS : 0);
        }
        return 0;
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
