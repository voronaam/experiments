package dev.codecity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Command-line rules of {@code --history}, and terminal-safe printing. */
class CodeCityTest {

    @Test
    void historyDefaultsToSixtySnapshots(@TempDir Path dir) {
        assertEquals(0, CodeCity.parse(new String[]{dir.toString()}).history());
        assertEquals(60, CodeCity.parse(new String[]{"--history", dir.toString()}).history());
        assertEquals(2, CodeCity.parse(new String[]{"--history=2", dir.toString()}).history());
        assertEquals(500, CodeCity.parse(new String[]{"--history=500", dir.toString()}).history());
    }

    /** A separate value is not a count: the argument after a bare --history is the path. */
    @Test
    void bareHistoryNeverSwallowsThePath(@TempDir Path dir) throws Exception {
        CodeCity.Options o = CodeCity.parse(new String[]{"--history", dir.toString()});
        assertEquals(dir.toRealPath(), o.root());
    }

    @Test
    void rejectsBadCountsAndWatch(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> CodeCity.parse(new String[]{"--history=1", dir.toString()}));
        assertThrows(IllegalArgumentException.class, () -> CodeCity.parse(new String[]{"--history=501", dir.toString()}));
        assertThrows(IllegalArgumentException.class, () -> CodeCity.parse(new String[]{"--history=x", dir.toString()}));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CodeCity.parse(new String[]{"--history", "--watch", dir.toString()}));
        assertEquals("--history cannot be combined with --watch", e.getMessage());
    }

    /** Escape sequences from a hostile repository (file names, git remotes) must not reach the terminal. */
    @Test
    void printableReplacesControlCharacters() {
        assertEquals("a?[31mred?]0;title??b\n\tc?d", CodeCity.printable("a\u001b[31mred\u001b]0;title\u0007\rb\n\tc\u009bd"));
        assertEquals("ünïcödé ✓ %s %n", CodeCity.printable("ünïcödé ✓ %s %n"));
        assertEquals("null", CodeCity.printable(null));
    }
}
