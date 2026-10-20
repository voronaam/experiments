package dev.codecity.github;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a local mirror of a GitHub repository under {@code ~/.cache/codecity/repos/<host>/<owner>/<name>}. Clones are
 * blobless ({@code --filter=blob:none}): full history for churn metrics, but only the current tree's file contents.
 *
 * <p>The cache honours {@code XDG_CACHE_HOME}. A second run on the same repository fetches and resets instead of
 * cloning again, so repeated analyses of a large repository are cheap.
 */
public final class RepoCloner {

    private RepoCloner() {
    }

    public static Path cacheRoot() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("codecity").resolve("repos");
    }

    /**
     * Whether {@code dir} lies in the clone cache. Such a repository was cloned by CodeCity, so its configuration is
     * git's own and the user's, not the analysed project's: only there may the history replay fetch missing objects
     * ({@link Git#command}). Both sides are compared as real paths.
     */
    public static boolean isCached(Path dir) {
        try {
            return dir.toRealPath().startsWith(cacheRoot().toRealPath());
        } catch (IOException e) {
            return false; // no cache yet, or dir does not exist
        }
    }

    /**
     * Clones the repository, or brings an existing mirror to the latest remote state. Returns its path.
     *
     * <p>Update path: {@code fetch --prune origin [branch]}, then a hard reset either to {@code FETCH_HEAD} (the branch
     * just fetched) or to {@code origin/HEAD} (the remote's default branch, as recorded when the mirror was cloned).
     * Clone path: a blobless clone of the requested branch, or of the default branch when none was given. The
     * {@code --} stops a URL, path or branch starting with '-' from being read as an option (and {@link GitHubRepo}
     * rejects such branches anyway).
     *
     * @throws IOException if git fails, or the computed directory would lie outside the cache
     */
    public static Path cloneOrUpdate(GitHubRepo repo) throws IOException {
        Path dir = cacheRoot().resolve(repo.host()).resolve(repo.owner()).resolve(repo.name()).normalize();
        // Defence in depth: GitHubRepo already rejects "." and "..", but a path escaping the cache must never be
        // cloned into or hard-reset.
        if (!dir.startsWith(cacheRoot())) {
            throw new IOException("refusing to clone outside the cache: " + dir);
        }
        if (Files.isDirectory(dir.resolve(".git"))) {
            System.out.println(dev.codecity.CodeCity.printable("Updating " + repo + " in " + dir + " …"));
            List<String> fetch = new ArrayList<>(List.of("-C", dir.toString(), "fetch", "--prune", "--", "origin"));
            if (repo.branch() != null) {
                fetch.add(repo.branch());
            }
            Git.interactive(fetch);
            // The mirror is ours alone, so a hard reset to what we just fetched is safe.
            String target = repo.branch() != null ? "FETCH_HEAD" : "origin/HEAD";
            Git.interactive(List.of("-C", dir.toString(), "reset", "--quiet", "--hard", target));
        } else {
            System.out.println(dev.codecity.CodeCity.printable("Cloning " + repo.webUrl() + " into " + dir + " …"));
            Files.createDirectories(dir.getParent());
            List<String> clone = new ArrayList<>(List.of("clone", "--filter=blob:none"));
            if (repo.branch() != null) {
                clone.addAll(List.of("--branch", repo.branch()));
            }
            clone.addAll(List.of("--", repo.cloneUrl(), dir.toString()));
            Git.interactive(clone);
        }
        return dir;
    }
}
