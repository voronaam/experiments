package dev.codecity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command-line parsing (every option, its default and its errors) and terminal-safe printing. The Rust
 * ({@code main.rs}) and D ({@code app.d}) editions have the same tests; {@link CodeCityCliTest} runs the whole
 * program.
 */
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

    /** The message of the usage error {@code args} cause. */
    private static String error(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> CodeCity.parse(args)).getMessage();
    }

    /** Without options: port 7070, tests, git, GitHub and the browser on; no watch, export, window or history. */
    @Test
    void defaultsWithoutOptions(@TempDir Path dir) throws Exception {
        CodeCity.Options o = CodeCity.parse(new String[]{dir.toString()});
        assertEquals(dir.toRealPath(), o.root());
        assertNull(o.remote());
        assertEquals(7070, o.port());
        assertTrue(o.includeTests() && o.git() && o.github() && o.open());
        assertFalse(o.watch() || o.gui());
        assertNull(o.export());
        assertEquals(0, o.history());
    }

    /** Every flag, in its short and long form, turns on or off exactly its own setting. */
    @Test
    void everyFlagSetsItsOption(@TempDir Path dir) {
        String d = dir.toString();
        CodeCity.Options o = CodeCity.parse(new String[]{"--no-tests", "--no-git", "--no-github", "--no-open", d});
        assertFalse(o.includeTests() || o.git() || o.github() || o.open());
        assertFalse(o.watch() || o.gui());
        assertTrue(CodeCity.parse(new String[]{"-w", d}).watch());
        assertTrue(CodeCity.parse(new String[]{"--watch", d}).watch());
        assertTrue(CodeCity.parse(new String[]{"-g", d}).gui());
        assertTrue(CodeCity.parse(new String[]{"--gui", d}).gui());
        assertFalse(CodeCity.parse(new String[]{"--no-tests", d}).includeTests());
        assertTrue(CodeCity.parse(new String[]{"--no-tests", d}).git());
        assertEquals(Path.of("out.html"), CodeCity.parse(new String[]{"--export", "out.html", d}).export());
        // Options may come after the path, too.
        assertEquals(Path.of("x.html"), CodeCity.parse(new String[]{d, "--export", "x.html"}).export());
    }

    /** {@code -p/--port} takes 0 (any free port) to 65535; anything else is "invalid port". */
    @Test
    void portRules(@TempDir Path dir) {
        String d = dir.toString();
        assertEquals(8080, CodeCity.parse(new String[]{"-p", "8080", d}).port());
        assertEquals(0, CodeCity.parse(new String[]{"--port", "0", d}).port());
        assertEquals(65535, CodeCity.parse(new String[]{"--port", "65535", d}).port());
        for (String bad : new String[]{"65536", "-1", "abc", "", "80.5", "99999999999"}) {
            assertEquals("invalid port", error("-p", bad, d), bad);
        }
        assertEquals("-p needs a value", error(d, "-p"));
        assertEquals("--port needs a value", error(d, "--port"));
        assertEquals("--export needs a value", error(d, "--export"));
    }

    /** Unknown options, a second path and a path that is not a directory are refused with a message. */
    @Test
    void refusesUnknownOptionsSecondPathsAndNonDirectories(@TempDir Path dir) throws Exception {
        String d = dir.toString();
        assertEquals("unknown option --bogus", error("--bogus", d));
        assertEquals("unknown option -x", error(d, "-x"));
        assertEquals("unknown option --history-x", error("--history-x", d));
        assertEquals("only one path may be given", error(d, d));
        Path file = Files.writeString(dir.resolve("file.txt"), "x");
        assertEquals("not a directory: " + file, error(file.toString()));
        assertEquals("not a directory: " + dir.resolve("missing"), error(dir.resolve("missing").toString()));
        // Both mistakes at once: the option conflict is reported first, as in the other editions.
        assertEquals("--history cannot be combined with --watch", error("--history", "-w", file.toString()));
    }

    /** {@code -h}/{@code --help} anywhere before an error asks for the usage text (null), whatever else is given. */
    @Test
    void helpWins(@TempDir Path dir) {
        assertNull(CodeCity.parse(new String[]{"-h"}));
        assertNull(CodeCity.parse(new String[]{"--port", "1", dir.toString(), "--help", "--bogus"}));
    }

    /** A GitHub spec that is not an existing directory is a repository to clone, not a path. */
    @Test
    void githubSpecsAreRemotes() {
        CodeCity.Options o = CodeCity.parse(new String[]{"github:owner/repo@dev"});
        assertNull(o.root());
        assertEquals("owner", o.remote().owner());
        assertEquals("repo", o.remote().name());
        assertEquals("dev", o.remote().branch());
    }

    /** Escape sequences from a hostile repository (file names, git remotes) must not reach the terminal. */
    @Test
    void printableReplacesControlCharacters() {
        assertEquals("a?[31mred?]0;title??b\n\tc?d", CodeCity.printable("a\u001b[31mred\u001b]0;title\u0007\rb\n\tc\u009bd"));
        assertEquals("ünïcödé ✓ %s %n", CodeCity.printable("ünïcödé ✓ %s %n"));
        assertEquals("null", CodeCity.printable(null));
    }
}
