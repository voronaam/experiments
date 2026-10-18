package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHistoryTest {

    /**
     * The newest commit adds a file whose name, and whose author's e-mail, are not valid UTF-8 (byte 0xFF). They are
     * read with U+FFFD in place of the bad byte, and the older history after them is still read.
     */
    @Test
    void readsHistoryPastInvalidUtf8(@TempDir Path dir) throws Exception {
        String script = """
                set -e
                git init -q .
                git config user.name t
                git config user.email t@example.com
                echo a > A.java
                git add A.java
                git -c commit.gpgsign=false commit -q -m a
                echo b > "$(printf 'B\\377.java')"
                git add -A
                git -c commit.gpgsign=false commit -q -m b --author="$(printf 'x <x\\377@example.com>')"
                """;
        Process p = new ProcessBuilder("sh", "-c", script).directory(dir.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor());
        Path top = dir.toRealPath();
        Map<Path, FileHistory> history = GitHistory.load(top);
        assertEquals(2, history.size());
        assertEquals(1, history.get(top.resolve("A.java")).commits());
        assertEquals(1, history.get(top.resolve("B\uFFFD.java")).authors());
    }
}
