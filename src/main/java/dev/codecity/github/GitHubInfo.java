package dev.codecity.github;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * GitHub context of an analysed source tree.
 *
 * @param ref         commit SHA (if it exists on the remote) or branch name used for permalinks
 * @param prefix      path of the analysed root inside the repository, "" or ending in "/"
 * @param pulls       open pull requests, or null when they were not fetched
 * @param pullsSource how complete {@code pulls} is, shown in the viewer
 */
public record GitHubInfo(GitHubRepo repo, String ref, String prefix, List<PullRequest> pulls, String pullsSource) {

    /** Finds a GitHub remote for the repository containing {@code root}; empty if there is none. */
    public static Optional<GitHubInfo> detect(Path root) {
        Optional<String> top = Git.run(root, "rev-parse", "--show-toplevel");
        if (top.isEmpty()) {
            return Optional.empty();
        }
        Optional<GitHubRepo> repo = Git.run(root, "remote", "get-url", "origin").flatMap(GitHubRepo::parse);
        if (repo.isEmpty()) {
            repo = Git.run(root, "remote", "-v").stream()
                    .flatMap(String::lines)
                    .map(l -> l.split("\\s+"))
                    .filter(parts -> parts.length >= 2)
                    .flatMap(parts -> GitHubRepo.parse(parts[1]).stream())
                    .findFirst();
        }
        if (repo.isEmpty()) {
            return Optional.empty();
        }
        String sha = Git.run(root, "rev-parse", "HEAD").orElse("HEAD");
        // A local-only commit would 404 on GitHub; fall back to the branch name in that case.
        boolean pushed = Git.run(root, "branch", "-r", "--contains", "HEAD").map(s -> !s.isBlank()).orElse(false);
        String branch = Git.run(root, "rev-parse", "--abbrev-ref", "HEAD").filter(b -> !b.equals("HEAD")).orElse(sha);
        String prefix;
        try {
            prefix = Path.of(top.get()).toRealPath().relativize(root.toRealPath()).toString().replace('\\', '/');
        } catch (java.io.IOException e) {
            prefix = "";
        }
        if (!prefix.isEmpty()) {
            prefix += "/";
        }
        return Optional.of(new GitHubInfo(repo.get(), pushed ? sha : branch, prefix, null, null));
    }

    public GitHubInfo withPulls(List<PullRequest> pulls, String source) {
        return new GitHubInfo(repo, ref, prefix, pulls, source);
    }
}
