package dev.codecity.github;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a local mirror of a GitHub repository under {@code ~/.cache/codecity/repos/<host>/<owner>/<name>}. Clones are
 * blobless ({@code --filter=blob:none}): full history for churn metrics, but only the current tree's file contents.
 */
public final class RepoCloner {

    private RepoCloner() {
    }

    public static Path cacheRoot() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("codecity").resolve("repos");
    }

    /** Clones the repository, or fast-forwards an existing mirror to the latest remote state. Returns its path. */
    public static Path cloneOrUpdate(GitHubRepo repo) throws IOException {
        Path dir = cacheRoot().resolve(repo.host()).resolve(repo.owner()).resolve(repo.name()).normalize();
        if (!dir.startsWith(cacheRoot())) {
            throw new IOException("refusing to clone outside the cache: " + dir);
        }
        if (Files.isDirectory(dir.resolve(".git"))) {
            System.out.println("Updating " + repo + " in " + dir + " …");
            List<String> fetch = new ArrayList<>(List.of("-C", dir.toString(), "fetch", "--prune", "origin"));
            if (repo.branch() != null) {
                fetch.add(repo.branch());
            }
            Git.interactive(fetch);
            // The mirror is ours alone, so a hard reset to what we just fetched is safe.
            String target = repo.branch() != null ? "FETCH_HEAD" : "origin/HEAD";
            Git.interactive(List.of("-C", dir.toString(), "reset", "--quiet", "--hard", target));
        } else {
            System.out.println("Cloning " + repo.webUrl() + " into " + dir + " …");
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
