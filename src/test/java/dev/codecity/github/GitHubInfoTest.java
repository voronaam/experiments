package dev.codecity.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static dev.codecity.github.GitFixture.commit;
import static dev.codecity.github.GitFixture.git;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Remote detection and permalinks: which repository, which ref (a pushed commit, else the branch) and which path prefix
 * {@link GitHubInfo#detect} finds, and the file, blame and history links built from them. The Rust and D editions
 * test the same cases.
 */
class GitHubInfoTest {

    /** A repository in {@code dir/repo} with one commit (sub/inner/A.java) and the remote {@code origin} = {@code url}. */
    private static Path repo(Path dir, String url) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        git(repo, "init", "-q");
        commit(repo, "sub/inner/A.java", "class A {}\n");
        if (url != null) {
            git(repo, "remote", "add", "origin", url);
        }
        return repo.toRealPath();
    }

    private static GitHubInfo detect(Path root) {
        return GitHubInfo.detect(root).orElseThrow();
    }

    @Test
    void linksToThePushedCommitElseToTheBranch(@TempDir Path dir) throws Exception {
        Path repo = repo(dir, "git@github.com:o/r.git");
        // Not on any remote-tracking branch yet: the branch name.
        GitHubInfo info = detect(repo);
        assertEquals(new GitHubRepo("github.com", "o", "r", null), info.repo());
        assertEquals("main", info.ref());
        assertEquals("", info.prefix());
        // Pushed (some remote-tracking branch contains HEAD): the commit.
        String sha = git(repo, "rev-parse", "HEAD");
        git(repo, "update-ref", "refs/remotes/origin/main", "HEAD");
        assertEquals(sha, detect(repo).ref());
        // A new local commit is not pushed: the branch again; on a detached HEAD, the commit anyway.
        String local = commit(repo, "B.java", "class B {}\n");
        assertEquals("main", detect(repo).ref());
        git(repo, "checkout", "-q", "--detach");
        assertEquals(local, detect(repo).ref());
        // Pull requests are not part of detection.
        assertEquals(null, detect(repo).pulls());
    }

    @Test
    void prefixesTheAnalysedSubdirectory(@TempDir Path dir) throws Exception {
        Path repo = repo(dir, "https://github.com/o/r");
        assertEquals("sub/", detect(repo.resolve("sub")).prefix());
        assertEquals("sub/inner/", detect(repo.resolve("sub/inner")).prefix());
    }

    @Test
    void findsTheGitHubRemote(@TempDir Path dir) throws Exception {
        // An Enterprise origin.
        Path repo = repo(dir.resolve("a"), "https://github.acme.corp/team/app.git");
        assertEquals(new GitHubRepo("github.acme.corp", "team", "app", null), detect(repo).repo());
        // origin elsewhere: the first remote that is on GitHub.
        repo = repo(dir.resolve("b"), "https://gitlab.com/o/r.git");
        git(repo, "remote", "add", "upstream", "ssh://git@github.com/u/p.git");
        assertEquals(new GitHubRepo("github.com", "u", "p", null), detect(repo).repo());
        // A GitHub URL that is rejected (".." as owner) counts as none; no remote at all, or no repository: none.
        repo = repo(dir.resolve("c"), "https://github.com/../x");
        assertEquals(Optional.empty(), GitHubInfo.detect(repo));
        assertEquals(Optional.empty(), GitHubInfo.detect(repo(dir.resolve("d"), null)));
        assertEquals(Optional.empty(), GitHubInfo.detect(Files.createDirectories(dir.resolve("plain")).toRealPath()));
    }

    @Test
    void buildsFileBlameAndHistoryLinks() {
        GitHubInfo info = new GitHubInfo(new GitHubRepo("github.com", "o", "r", null), "0123abc", "sub/", null, null);
        assertEquals("https://github.com/o/r/blob/0123abc/sub/pkg/A.java#L3-L9", info.link("blob", "pkg/A.java", 3, 9));
        assertEquals("https://github.com/o/r/blame/0123abc/sub/pkg/A.java#L3-L9", info.link("blame", "pkg/A.java", 3, 9));
        assertEquals("https://github.com/o/r/commits/0123abc/sub/pkg/A.java", info.link("commits", "pkg/A.java", 3, 9));
        // Each path segment of the ref and the path is percent-encoded; the slashes stay.
        GitHubInfo odd = new GitHubInfo(new GitHubRepo("github.acme.corp", "team", "app", null), "feature/x y",
                "my dir/", null, null);
        assertEquals("https://github.acme.corp/team/app/blob/feature/x%20y/my%20dir/%C3%9Cn%C3%AF/a%23b%3Fc%25d%2Be.java#L1-L2",
                odd.link("blob", "Ünï/a#b?c%d+e.java", 1, 2));
        assertEquals("https://github.acme.corp/team/app/commits/feature/x%20y/my%20dir/A.java",
                odd.link("commits", "A.java", 1, 2));
        // Only the unreserved characters of RFC 3986 stay as they are, as in every edition's viewers.
        assertEquals("https://github.acme.corp/team/app/commits/feature/x%20y/my%20dir/a%21b%27c%28d%29e%2Af~g-h_i.j%2Ck",
                odd.link("commits", "a!b'c(d)e*f~g-h_i.j,k", 1, 2));
    }
}
