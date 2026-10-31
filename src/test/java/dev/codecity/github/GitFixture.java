package dev.codecity.github;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Small git repositories for the clone-cache, prefetch and permalink tests, made with the git on the PATH. Commits
 * have a fixed author and no signature, whatever the user's configuration says. The Rust and D editions build the
 * same repositories ({@code github_tests.rs}, {@code github_tests.d}).
 */
public final class GitFixture {

    private GitFixture() {
    }

    /**
     * Runs {@code git -C dir args...} (dir may be null), fails the test unless it succeeds, and returns its stdout
     * stripped.
     */
    public static String git(Path dir, String... args) {
        return git(dir, false, args);
    }

    /** {@link #git(Path, String...)}; {@code noLazyFetch}: with lazy fetching of missing objects off (git 2.44+). */
    private static String git(Path dir, boolean noLazyFetch, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com",
                "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main", "-c", "protocol.file.allow=always"));
        if (dir != null) {
            cmd.addAll(List.of("-C", dir.toString()));
        }
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD);
            if (noLazyFetch) {
                pb.environment().put("GIT_NO_LAZY_FETCH", "1");
            }
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
            return out;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes {@code file} in the work tree {@code work} with {@code content} and commits everything. */
    public static String commit(Path work, String file, String content) throws IOException {
        Path f = work.resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        git(work, "add", "-A");
        git(work, "commit", "-q", "-m", "change " + file);
        return git(work, "rev-parse", "HEAD");
    }

    /**
     * The "remote": a work tree {@code dir/work} with three commits on {@code main} (A.java v1, then A.java v2 and
     * B.java) and one more on {@code dev} (C.java), pushed to the bare repository {@code dir/remote.git}, which allows
     * blobless clones and fetching any object. Returns the bare repository.
     */
    public static Path remote(Path dir) throws IOException {
        Path work = dir.resolve("work");
        Files.createDirectories(work);
        git(work, "init", "-q");
        commit(work, "A.java", "class A { /* v1 */ }\n");
        Files.writeString(work.resolve("B.java"), "class B {}\n");
        commit(work, "A.java", "class A { /* v2 */ }\n");
        git(work, "checkout", "-q", "-b", "dev");
        commit(work, "C.java", "class C {}\n");
        git(work, "checkout", "-q", "main");
        Path bare = dir.resolve("remote.git");
        git(null, "clone", "-q", "--bare", work.toString(), bare.toString());
        git(bare, "config", "uploadpack.allowFilter", "true");
        git(bare, "config", "uploadpack.allowAnySHA1InWant", "true");
        git(work, "remote", "add", "origin", bare.toString());
        return bare;
    }

    /** A {@code file://} URL of {@code dir}, so that git uses its transport (a plain path ignores --filter). */
    public static String fileUrl(Path dir) {
        String p = dir.toAbsolutePath().toString().replace('\\', '/');
        return "file://" + (p.startsWith("/") ? "" : "/") + p;
    }

    /** How many objects reachable from HEAD are missing from the (partial) repository {@code repo}. */
    public static long missing(Path repo) {
        return git(repo, true, "rev-list", "--objects", "--missing=print", "HEAD").lines().filter(l -> l.startsWith("?")).count();
    }
}
