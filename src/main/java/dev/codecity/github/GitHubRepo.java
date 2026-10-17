package dev.codecity.github;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repository on github.com or a GitHub Enterprise host.
 *
 * @param branch branch named in a {@code /tree/<branch>} URL, or null for the default branch
 */
public record GitHubRepo(String host, String owner, String name, String branch) {

    private static final String SEGMENT = "[A-Za-z0-9_.-]+";
    private static final Pattern HTTPS = Pattern.compile(
            "https?://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/(" + SEGMENT + ")/(" + SEGMENT + ")(?:/tree/(.+?))?/?");
    private static final Pattern SCP = Pattern.compile(
            "[^@\\s]+@([^:/]+):(" + SEGMENT + ")/(" + SEGMENT + ")/?");
    private static final Pattern SSH = Pattern.compile(
            "ssh://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/(" + SEGMENT + ")/(" + SEGMENT + ")/?");
    private static final Pattern SHORTHAND = Pattern.compile(
            "github:(" + SEGMENT + ")/(" + SEGMENT + ")(?:@(.+))?");

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
