package dev.codecity.analysis;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Text helpers shared by the language analysers (all supported languages use C-style comments). */
public final class SourceText {

    private static final Set<String> TEST_DIRS = Set.of("test", "tests", "it", "androidTest", "testFixtures", "__tests__");

    private SourceText() {
    }

    public static String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        }
    }

    public static String[] lines(String code) {
        return code.split("\\R", -1);
    }

    /** Non-blank lines in [begin, end] (1-based, inclusive) that are not purely comments. */
    public static int countLoc(String[] lines, int begin, int end) {
        int loc = 0;
        boolean inBlock = false;
        for (int i = Math.max(begin, 1) - 1; i < Math.min(end, lines.length); i++) {
            String s = lines[i].strip();
            if (inBlock) {
                int close = s.indexOf("*/");
                if (close < 0) {
                    continue;
                }
                inBlock = false;
                s = s.substring(close + 2).strip();
            }
            if (s.startsWith("/*")) {
                int close = s.indexOf("*/", 2);
                if (close < 0) {
                    inBlock = true;
                    continue;
                }
                s = s.substring(close + 2).strip();
            }
            if (!s.isEmpty() && !s.startsWith("//")) {
                loc++;
            }
        }
        return loc;
    }

    /** True when any directory on the path is a conventional test directory. */
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
