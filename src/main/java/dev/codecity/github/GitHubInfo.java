package dev.codecity.github;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GitHub context of an analysed source tree.
 *
 * <p>The viewers use it for two things: permalinks ({@code blob}/{@code blame}/{@code commits} URLs built from
 * {@code repo}, {@code ref} and {@code prefix} plus a building's root-relative file path) and the pull-request overlay
 * ({@code pulls}). It is detected and fetched once at start-up and shared by every watch-mode rebuild.
 *
 * @param ref         commit SHA (if it exists on the remote) or branch name used for permalinks
 * @param prefix      path of the analysed root inside the repository, "" or ending in "/"
 * @param pulls       open pull requests, or null when they were not fetched
 * @param pullsSource how complete {@code pulls} is, shown in the viewer
 */
public record GitHubInfo(GitHubRepo repo, String ref, String prefix, List<PullRequest> pulls, String pullsSource) {

    /**
     * Finds a GitHub remote for the repository containing {@code root}; empty if there is none (or {@code root} is not
     * inside a git work tree, or git is not installed).
     *
     * <p>The remote called {@code origin} is preferred; otherwise the first remote in {@code git remote -v} output
     * whose URL parses as a GitHub repository is used. {@code ref} is the HEAD commit SHA when some remote-tracking
     * branch contains it, otherwise the current branch name (or, on a detached HEAD, the SHA anyway). {@code prefix} is
     * where {@code root} lies below the repository top level, so that links work when only a sub-directory was
     * analysed.
     */
    public static Optional<GitHubInfo> detect(Path root) {
        Optional<String> top = Git.run(root, "rev-parse", "--show-toplevel");
        if (top.isEmpty()) {
            return Optional.empty();
        }
        Optional<GitHubRepo> repo = Git.run(root, "remote", "get-url", "origin").flatMap(GitHubInfo::parseRemote);
        if (repo.isEmpty()) {
            // "remote -v" prints "<name>\t<url> (fetch|push)" per line; the URL is the second field.
            repo = Git.run(root, "remote", "-v").stream()
                    .flatMap(String::lines)
                    .map(l -> l.split("\\s+"))
                    .filter(parts -> parts.length >= 2)
                    .flatMap(parts -> parseRemote(parts[1]).stream())
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
        // Both sides are resolved to real paths before relativising, so a symlink on the way to either one cannot
        // turn the prefix into a "../" path. Windows separators become '/' because the prefix goes into URLs.
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

    /**
     * A remote URL as a repository; empty for one that is not a GitHub URL or that {@link GitHubRepo} rejects. The
     * URL comes from the analysed repository's configuration, so a rejected one must not end the run.
     */
    private static Optional<GitHubRepo> parseRemote(String url) {
        try {
            return GitHubRepo.parse(url);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public GitHubInfo withPulls(List<PullRequest> pulls, String source) {
        return new GitHubInfo(repo, ref, prefix, pulls, source);
    }

    /**
     * The GitHub page of lines {@code line}..{@code endLine} of {@code file} (relative to the analysed root): {@code
     * kind} is {@code blob} (file view), {@code blame} or {@code commits} (history), giving
     * {@code https://<host>/<owner>/<repo>/<kind>/<ref>/<prefix><file>#L<line>-L<endLine>}. The history page takes no
     * line anchor. The desktop viewer's inspector links use it; the other editions build the same URLs.
     */
    public String link(String kind, String file, int line, int endLine) {
        String url = repo.webUrl() + "/" + kind + "/" + encodePath(ref) + "/" + encodePath(prefix + file);
        return kind.equals("commits") ? url : url + "#L" + line + "-L" + endLine;
    }

    /**
     * Percent-encodes each path segment but keeps the slashes: every byte of the UTF-8 form except the unreserved
     * characters of RFC 3986 ({@code A-Z a-z 0-9 - . _ ~}) becomes {@code %XX} (upper-case hex). Every viewer of every
     * edition encodes GitHub links with exactly this set, so the links are identical; GitHub resolves them all.
     */
    static String encodePath(String path) {
        return Stream.of(path.split("/")).map(GitHubInfo::encodeSegment).collect(Collectors.joining("/"));
    }

    /** One path segment, encoded as described at {@link #encodePath}. */
    private static String encodeSegment(String segment) {
        StringBuilder out = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_'
                    || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return out.toString();
    }
}
