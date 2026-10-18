package dev.codecity.github;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repository on github.com or a GitHub Enterprise host.
 *
 * <p>Used both for the command-line argument (which repository to clone) and for the {@code origin} remote of a local
 * checkout (where links and pull requests come from). {@code host} is lower-case and one trailing {@code .git}
 * is removed from {@code name}.
 *
 * @param branch branch named in a {@code /tree/<branch>} URL, or null for the default branch
 */
public record GitHubRepo(String host, String owner, String name, String branch) {

    /**
     * Characters accepted in owner and repository names (letters, digits, '_', '.', '-'). Restricting them also keeps
     * {@code /} and other path syntax out, which matters because owner and name become directories in the clone cache
     * ({@link RepoCloner}).
     */
    private static final String SEGMENT = "[A-Za-z0-9_.-]+";
    // HTTPS, SCP and SSH capture (1) host, (2) owner, (3) name and, for HTTPS only, (4) the branch of a /tree/ URL.
    // Credentials ("user@") and a port are accepted and dropped. Group 4 is lazy, so a trailing '/' is not part of
    // the branch name, but it may itself contain slashes (feature/x).
    private static final Pattern HTTPS = Pattern.compile(
            "https?://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/(" + SEGMENT + ")/(" + SEGMENT + ")(?:/tree/(.+?))?/?");
    /** scp-like syntax as used by git: {@code git@github.com:owner/repo.git}. */
    private static final Pattern SCP = Pattern.compile(
            "[^@\\s]+@([^:/]+):(" + SEGMENT + ")/(" + SEGMENT + ")/?");
    private static final Pattern SSH = Pattern.compile(
            "ssh://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/(" + SEGMENT + ")/(" + SEGMENT + ")/?");
    /** {@code github:owner/repo[@branch]}: (1) owner, (2) name, (3) optional branch; the host is always github.com. */
    private static final Pattern SHORTHAND = Pattern.compile(
            "github:(" + SEGMENT + ")/(" + SEGMENT + ")(?:@(.+))?");

    /**
     * Rejects {@code .} and {@code ..}: SEGMENT allows dots, and these two would let a crafted URL resolve the clone
     * directory outside the cache.
     */
    public GitHubRepo {
        if (owner.equals(".") || owner.equals("..") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("invalid repository " + owner + "/" + name);
        }
    }

    /**
     * Parses a GitHub URL ({@code https://github.com/o/r}, {@code .../tree/branch}, {@code git@github.com:o/r.git},
     * {@code ssh://...}) or the shorthand {@code github:owner/repo[@branch]}. Only hosts containing "github" match.
     */
    public static Optional<GitHubRepo> parse(String text) {
        String s = text.strip();
        Matcher m = SHORTHAND.matcher(s);
        if (m.matches()) {
            return Optional.of(new GitHubRepo("github.com", m.group(1), stripGit(m.group(2)), m.group(3)));
        }
        for (Pattern p : new Pattern[]{HTTPS, SCP, SSH}) {
            m = p.matcher(s);
            if (m.matches() && m.group(1).toLowerCase().contains("github")) {
                String branch = p == HTTPS ? m.group(4) : null;
                return Optional.of(new GitHubRepo(m.group(1).toLowerCase(), m.group(2), stripGit(m.group(3)), branch));
            }
        }
        return Optional.empty();
    }

    private static String stripGit(String name) {
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    public String webUrl() {
        return "https://" + host + "/" + owner + "/" + name;
    }

    public String cloneUrl() {
        return webUrl() + ".git";
    }

    public boolean isPublicGitHub() {
        return host.equals("github.com");
    }

    /** REST base URL: api.github.com for github.com, {@code /api/v3} on the host for GitHub Enterprise Server. */
    public String restApi() {
        return isPublicGitHub() ? "https://api.github.com" : "https://" + host + "/api/v3";
    }

    public String graphqlApi() {
        return isPublicGitHub() ? "https://api.github.com/graphql" : "https://" + host + "/api/graphql";
    }

    @Override
    public String toString() {
        return owner + "/" + name + (branch == null ? "" : "@" + branch);
    }
}
