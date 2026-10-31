package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git history mining ({@link GitHistory}). The rule tests run {@code src/test/resources/git-log-repo.sh}, which the
 * Rust ({@code git.rs}) and D ({@code git.d}) editions run as well, with the same expectations.
 */
class GitHistoryTest {

    /** Author time of commit {@code n} (1-based) of git-log-repo.sh: midnight UTC, one day apart from 2020-01-01. */
    private static long day(int n) {
        return 1_577_836_800L + (n - 1) * 86_400L;
    }

    /** Builds the shared git-log-repo.sh history in {@code dir} and returns its real path. */
    private static Path mined(Path dir) throws Exception {
        Path script = Path.of(GitHistoryTest.class.getResource("/git-log-repo.sh").toURI());
        Process p = new ProcessBuilder("sh", script.toString()).directory(dir.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor());
        return dir.toRealPath();
    }

    /**
     * Per file: non-merge commits, distinct authors, and the newest and oldest author time. A.java is changed by Ann,
     * Bob, Ann under an old address (which .mailmap maps back to her) and, on a side branch, Cid: four commits by three
     * authors, the last one the side-branch commit (the merge that brought it in does not count).
     */
    @Test
    void countsCommitsAuthorsAndFirstAndLastAuthorTime(@TempDir Path dir) throws Exception {
        Path top = mined(dir);
        Map<Path, FileHistory> h = GitHistory.load(top);
        assertEquals(new FileHistory(4, 3, day(4), day(1)), h.get(top.resolve("A.java")));
        assertEquals(new FileHistory(3, 2, day(5), day(1)), h.get(top.resolve("r/lib.rs")));
    }

    /** {@code %aE} applies .mailmap: Ann's old address counts as Ann, so A.java has three authors, not four. */
    @Test
    void mailmapMergesAnAuthorsAddresses(@TempDir Path dir) throws Exception {
        Path top = mined(dir);
        assertEquals(3, GitHistory.load(top).get(top.resolve("A.java")).authors());
        // The raw log, which the replay counts, shows the mapped address for commit 3.
        List<GitHistory.Touch> touches = GitHistory.touches(top).get(top.resolve("A.java"));
        assertEquals(List.of(day(1), day(2), day(3), day(4)),
                touches.stream().map(GitHistory.Touch::time).sorted().toList());
        assertEquals(List.of("ann@example.com", "ann@example.com", "bob@example.com", "cid@example.com"),
                touches.stream().map(GitHistory.Touch::author).sorted().toList());
    }

    /** M.java is changed only by the merge commit (an "evil merge"), which does not count: one commit, from day 1. */
    @Test
    void mergeCommitsAreExcluded(@TempDir Path dir) throws Exception {
        Path top = mined(dir);
        assertEquals(new FileHistory(1, 1, day(1), day(1)), GitHistory.load(top).get(top.resolve("M.java")));
    }

    /**
     * Renames are not followed: the old path keeps its commits (the add and the deletion by the rename) and the new
     * path starts afresh with the rename.
     */
    @Test
    void renamesStartAFreshHistory(@TempDir Path dir) throws Exception {
        Path top = mined(dir);
        Map<Path, FileHistory> h = GitHistory.load(top);
        assertEquals(new FileHistory(2, 2, day(7), day(1)), h.get(top.resolve("web/c.ts")));
        assertEquals(new FileHistory(1, 1, day(7), day(7)), h.get(top.resolve("web/d.ts")));
    }

    /** Only files of the analysed languages are listed: notes.txt and .mailmap are not. */
    @Test
    void onlySourceFilesAreListed(@TempDir Path dir) throws Exception {
        Path top = mined(dir);
        assertEquals(Set.of(top.resolve("A.java"), top.resolve("M.java"), top.resolve("r/lib.rs"),
                top.resolve("web/c.ts"), top.resolve("web/d.ts")), GitHistory.load(top).keySet());
    }

    /**
     * Analysing a sub-directory logs only its files, keyed by their absolute path below the top of the work tree.
     * The times are author times (the committer's are an hour later).
     */
    @Test
    void aSubDirectoryLogsOnlyItsOwnFiles(@TempDir Path dir) throws Exception {
        Path top = mined(dir);
        Map<Path, FileHistory> h = GitHistory.load(top.resolve("web"));
        assertEquals(Set.of(top.resolve("web/c.ts"), top.resolve("web/d.ts")), h.keySet());
        assertEquals(day(7), h.get(top.resolve("web/d.ts")).lastCommit());
    }

    /** Outside a work tree, and in a repository without commits, there is no history (and no error). */
    @Test
    void noHistoryOutsideGitOrWithoutCommits(@TempDir Path dir) throws Exception {
        Path plain = Files.createDirectories(dir.resolve("plain")).toRealPath();
        Files.writeString(plain.resolve("A.java"), "class A {}");
        assertTrue(GitHistory.load(plain).isEmpty());
        Path empty = Files.createDirectories(dir.resolve("empty")).toRealPath();
        Process p = new ProcessBuilder("git", "init", "-q", "--template=", ".").directory(empty.toFile())
                .inheritIO().start();
        assertEquals(0, p.waitFor());
        Files.writeString(empty.resolve("A.java"), "class A {}");
        assertTrue(GitHistory.load(empty).isEmpty());
    }

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
