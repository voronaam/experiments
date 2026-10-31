package dev.codecity.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.codecity.github.GitFixture.commit;
import static dev.codecity.github.GitFixture.fileUrl;
import static dev.codecity.github.GitFixture.git;
import static dev.codecity.github.GitFixture.missing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clone cache ({@link RepoCloner}) with local bare repositories as the "GitHub" remote, cloned through
 * {@code file://} URLs into a cache in a temporary directory (the test seam of {@code cloneOrUpdate}); everything else
 * is the production code path. The Rust and D editions test the same cases.
 */
class RepoClonerTest {

    private static final GitHubRepo REPO = new GitHubRepo("github.com", "o", "r", null);
    private static final GitHubRepo DEV = new GitHubRepo("github.com", "o", "r", "dev");

    @Test
    void clonesBloblesslyIntoTheCacheThenUpdates(@TempDir Path tmp) throws IOException {
        Path bare = GitFixture.remote(tmp);
        Path cache = tmp.resolve("cache");
        Path dir = RepoCloner.cloneOrUpdate(REPO, cache, fileUrl(bare));
        assertEquals(cache.resolve("github.com").resolve("o").resolve("r"), dir);
        assertEquals(git(bare, "rev-parse", "main"), git(dir, "rev-parse", "HEAD"));
        // Blobless: full history, but the old version of A.java was not downloaded.
        assertEquals("true", git(dir, "config", "remote.origin.promisor"));
        assertEquals("blob:none", git(dir, "config", "remote.origin.partialclonefilter"));
        assertEquals(1, missing(dir));
        assertEquals("class A { /* v2 */ }\n", Files.readString(dir.resolve("A.java")));

        // A new commit upstream, and a local change in the mirror: the update fetches and resets hard.
        String head = commit(tmp.resolve("work"), "A.java", "class A { /* v3 */ }\n");
        git(tmp.resolve("work"), "push", "-q", "origin", "main");
        Files.writeString(dir.resolve("A.java"), "local edit\n");
        assertEquals(dir, RepoCloner.cloneOrUpdate(REPO, cache, fileUrl(bare)));
        assertEquals(head, git(dir, "rev-parse", "HEAD"));
        assertEquals("class A { /* v3 */ }\n", Files.readString(dir.resolve("A.java")));
    }

    @Test
    void checksOutTheRequestedBranch(@TempDir Path tmp) throws IOException {
        Path bare = GitFixture.remote(tmp);
        Path cache = tmp.resolve("cache");
        Path dir = RepoCloner.cloneOrUpdate(DEV, cache, fileUrl(bare));
        assertEquals(git(bare, "rev-parse", "dev"), git(dir, "rev-parse", "HEAD"));
        assertTrue(Files.isRegularFile(dir.resolve("C.java")));
        // Updating a branch clone follows that branch (FETCH_HEAD), not the default branch.
        Path work = tmp.resolve("work");
        git(work, "checkout", "-q", "dev");
        String head = commit(work, "D.java", "class D {}\n");
        git(work, "push", "-q", "origin", "dev");
        RepoCloner.cloneOrUpdate(DEV, cache, fileUrl(bare));
        assertEquals(head, git(dir, "rev-parse", "HEAD"));
        assertTrue(Files.isRegularFile(dir.resolve("D.java")));
    }

    @Test
    void reportsGitFailures(@TempDir Path tmp) throws IOException {
        Path cache = tmp.resolve("cache");
        // Nothing to clone: an error, and no repository left behind.
        assertThrows(IOException.class, () -> RepoCloner.cloneOrUpdate(REPO, cache, fileUrl(tmp.resolve("nowhere.git"))));
        assertFalse(Files.exists(cache.resolve("github.com/o/r/.git")));
        // A branch the remote does not have.
        Path bare = GitFixture.remote(tmp);
        assertThrows(IOException.class, () -> RepoCloner.cloneOrUpdate(
                new GitHubRepo("github.com", "o", "r", "missing"), cache, fileUrl(bare)));
        // The remote is gone when the mirror is updated.
        Path dir = RepoCloner.cloneOrUpdate(REPO, cache, fileUrl(bare));
        git(dir, "remote", "set-url", "origin", fileUrl(tmp.resolve("gone.git")));
        assertThrows(IOException.class, () -> RepoCloner.cloneOrUpdate(REPO, cache, fileUrl(bare)));
    }

    @Test
    void refusesToCloneOutsideTheCache(@TempDir Path tmp) throws IOException {
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache);
        IOException e = assertThrows(IOException.class, () -> RepoCloner.cloneOrUpdate(
                new GitHubRepo("..", "o", "r", null), cache, fileUrl(tmp.resolve("nowhere.git"))));
        assertTrue(e.getMessage().startsWith("refusing to clone outside the cache"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("o")));
    }

    @Test
    void tellsCacheRepositoriesFromOthers(@TempDir Path tmp) throws IOException {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path inside = Files.createDirectories(cache.resolve("github.com/o/r"));
        Path sibling = Files.createDirectories(tmp.resolve("cache-other/github.com/o/r"));
        assertTrue(RepoCloner.isCached(inside, cache));
        assertTrue(RepoCloner.isCached(cache, cache));
        assertFalse(RepoCloner.isCached(sibling, cache));
        assertFalse(RepoCloner.isCached(tmp, cache));
        assertFalse(RepoCloner.isCached(cache.resolve("missing"), cache));
        assertFalse(RepoCloner.isCached(inside, tmp.resolve("no-cache-yet")));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "symbolic links need extra rights on Windows")
    void comparesRealPathsForTheCache(@TempDir Path tmp) throws IOException {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path inside = Files.createDirectories(cache.resolve("github.com/o/r"));
        Path outside = Files.createDirectories(tmp.resolve("elsewhere"));
        // A link into the cache counts as inside, a link in the cache pointing out of it does not.
        assertTrue(RepoCloner.isCached(Files.createSymbolicLink(tmp.resolve("link-in"), inside), cache));
        assertFalse(RepoCloner.isCached(Files.createSymbolicLink(cache.resolve("link-out"), outside), cache));
    }
}
