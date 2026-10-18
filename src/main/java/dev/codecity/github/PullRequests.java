package dev.codecity.github;

import dev.codecity.json.JsonParser;
import dev.codecity.json.JsonWriter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches open pull requests and the files they touch. The viewers use the file lists to put a beacon on every
 * building whose file an open PR changes ({@link PullRequest#files()} holds repository-relative paths; matching them
 * to buildings happens in {@code CityModel}).
 * <ul>
 *   <li>With a token (GH_TOKEN, GITHUB_TOKEN or {@code gh auth token}): GraphQL, up to 300 PRs in a few requests
 *       (plus one per further 100 files of a PR that changes more than 100).</li>
 *   <li>Without one: the REST API, limited to the most recently updated PRs that fit the anonymous rate limit.</li>
 * </ul>
 * Never throws: failures are reported and result in an empty list with the source {@code "unavailable"}.
 *
 * <p>The {@code source} string of a {@link Result} says how complete the list is. It starts with {@code "all"} only
 * when every open PR was loaded; the viewers rely on that prefix to decide whether "no PRs" means none at all or
 * just none among the ones loaded.
 */
public final class PullRequests {

    /** GraphQL pages to fetch at most: {@code MAX_PAGES * PAGE_SIZE} = 300 PRs. */
    private static final int MAX_PAGES = 6;
    /** PRs per GraphQL page; each PR node also asks for its first 100 files. */
    private static final int PAGE_SIZE = 50;
    /**
     * REST mode needs extra requests per PR for its file list (one per 100 files), and anonymous clients only get a
     * small hourly request quota (60 on github.com). At most this many file requests are made, and never more than
     * the quota left.
     */
    private static final int ANONYMOUS_FILE_LOOKUPS = 30;

    public record Result(List<PullRequest> pulls, String source) {
    }

    /**
     * Newest-updated open PRs first, so that a truncated list holds the most recently updated ones. The first 100
     * files of each PR come with it; a PR with more is completed with {@link #FILES_QUERY}. {@code $cursor} is null
     * for the first page.
     */
    private static final String QUERY = """
            query($owner: String!, $name: String!, $cursor: String) {
              repository(owner: $owner, name: $name) {
                pullRequests(states: OPEN, first: %d, after: $cursor, orderBy: {field: UPDATED_AT, direction: DESC}) {
                  pageInfo { hasNextPage endCursor }
                  nodes {
                    number title url updatedAt isDraft
                    author { login }
                    files(first: 100) { pageInfo { hasNextPage endCursor } nodes { path } }
                  }
                }
              }
            }""".formatted(PAGE_SIZE);

    /** The next 100 files of one PR, after {@code $cursor} (the {@code endCursor} of the files seen so far). */
    private static final String FILES_QUERY = """
            query($owner: String!, $name: String!, $number: Int!, $cursor: String) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  files(first: 100, after: $cursor) { pageInfo { hasNextPage endCursor } nodes { path } }
                }
              }
            }""";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final GitHubRepo repo;
    private final String token;

    private PullRequests(GitHubRepo repo, String token) {
        this.repo = repo;
        this.token = token;
    }

    /** Fetches the open PRs of {@code repo}, via GraphQL when a token is available and via REST otherwise. */
    public static Result fetch(GitHubRepo repo) {
        String token = token(repo).orElse(null);
        PullRequests client = new PullRequests(repo, token);
        try {
            return token != null ? client.graphql() : client.rest();
        } catch (IOException | RuntimeException e) {
            System.err.println("  GitHub: could not fetch pull requests (" + e.getMessage() + ")");
            return new Result(List.of(), "unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(List.of(), "unavailable");
        }
    }

    /**
     * Looks for an API token: the usual environment variables first (the Enterprise ones only for non-github.com
     * hosts), then the GitHub CLI's stored login for the repository's host ({@code gh auth token}). Empty when there
     * is none, in which case the REST API is used anonymously.
     */
    static Optional<String> token(GitHubRepo repo) {
        for (String var : repo.isPublicGitHub()
                ? List.of("GH_TOKEN", "GITHUB_TOKEN")
                : List.of("GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_TOKEN")) {
            String v = System.getenv(var);
            if (v != null && !v.isBlank()) {
                return Optional.of(v.strip());
            }
        }
        return Git.runCommand(Path.of("."), List.of("gh", "auth", "token", "--hostname", repo.host()))
                .filter(s -> !s.isBlank());
    }

    /**
     * Pages through the open PRs with GraphQL (cursor-based: each page's {@code endCursor} is sent as the next
     * {@code $cursor}). Stops after {@link #MAX_PAGES}; if more pages remain, the source says "latest N". The files of
     * a PR are paged the same way, one extra request per further 100 files.
     */
    private Result graphql() throws IOException, InterruptedException {
        List<PullRequest> pulls = new ArrayList<>();
        String cursor = null;
        boolean more = true;
        for (int page = 0; page < MAX_PAGES && more; page++) {
            Object prs = JsonParser.path(query(QUERY, new JsonWriter().beginObject()
                    .field("owner", repo.owner()).field("name", repo.name()).name("cursor").value(cursor)
                    .endObject()), "repository", "pullRequests");
            if (prs == null) {
                throw new IOException("repository not found or not accessible");
            }
            for (Object node : list(JsonParser.path(prs, "nodes"))) {
                int number = num(JsonParser.path(node, "number"));
                List<String> files = new ArrayList<>();
                Object connection = JsonParser.path(node, "files");
                while (true) {
                    for (Object f : list(JsonParser.path(connection, "nodes"))) {
                        files.add(str(JsonParser.path(f, "path")));
                    }
                    if (!Boolean.TRUE.equals(JsonParser.path(connection, "pageInfo", "hasNextPage"))) {
                        break;
                    }
                    connection = JsonParser.path(query(FILES_QUERY, new JsonWriter().beginObject()
                            .field("owner", repo.owner()).field("name", repo.name()).field("number", number)
                            .name("cursor").value(str(JsonParser.path(connection, "pageInfo", "endCursor")))
                            .endObject()), "repository", "pullRequest", "files");
                }
                pulls.add(new PullRequest(number, str(JsonParser.path(node, "title")),
                        str(JsonParser.path(node, "url")), str(JsonParser.path(node, "author", "login")),
                        str(JsonParser.path(node, "updatedAt")), Boolean.TRUE.equals(JsonParser.path(node, "isDraft")),
                        files));
            }
            more = Boolean.TRUE.equals(JsonParser.path(prs, "pageInfo", "hasNextPage"));
            cursor = str(JsonParser.path(prs, "pageInfo", "endCursor"));
        }
        return new Result(pulls, more ? "latest " + pulls.size() + " open PRs" : "all open PRs");
    }

    /**
     * Sends one GraphQL query and returns its {@code data}. GraphQL reports most errors with HTTP 200 and an
     * {@code errors} array, so that is checked explicitly.
     */
    private Object query(String query, JsonWriter variables) throws IOException, InterruptedException {
        // The request body is {"query": "...", "variables": {...}}; the variables are spliced in as raw JSON.
        String body = new JsonWriter().beginObject()
                .field("query", query).name("variables").raw(variables.toString())
                .endObject().toString();
        Object json = send(HttpRequest.newBuilder(URI.create(repo.graphqlApi()))
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (JsonParser.path(json, "errors") instanceof List<?> errors && !errors.isEmpty()) {
            throw new IOException(String.valueOf(JsonParser.path(errors.getFirst(), "message")));
        }
        return JsonParser.path(json, "data");
    }

    /**
     * Anonymous fallback: one request lists up to 100 open PRs (newest-updated first), then each PR's files are fetched
     * 100 per request until a page comes back short. The number of file requests is capped by
     * {@link #ANONYMOUS_FILE_LOOKUPS} and by the rate limit the listing response reports; a PR whose files do not fit
     * in what is left is left out, like the PRs after it, rather than shown with only some of its files.
     */
    private Result rest() throws IOException, InterruptedException {
        String base = repo.restApi() + "/repos/" + repo.owner() + "/" + repo.name();
        HttpResponse<String> listing = request(HttpRequest.newBuilder(
                URI.create(base + "/pulls?state=open&sort=updated&direction=desc&per_page=100")).GET());
        // The "- 1" keeps one request of the remaining quota unused.
        int budget = Math.min(ANONYMOUS_FILE_LOOKUPS, remaining(listing) - 1);
        int used = 0;
        List<Object> nodes = list(JsonParser.parse(listing.body()));
        List<PullRequest> pulls = new ArrayList<>();
        for (Object node : nodes) {
            int number = num(JsonParser.path(node, "number"));
            List<String> files = new ArrayList<>();
            boolean done = false;
            for (int page = 1; !done && used < budget; page++) {
                List<Object> batch = list(JsonParser.parse(request(HttpRequest.newBuilder(
                        URI.create(base + "/pulls/" + number + "/files?per_page=100&page=" + page)).GET()).body()));
                used++;
                for (Object f : batch) {
                    files.add(str(JsonParser.path(f, "filename")));
                }
                done = batch.size() < 100;
            }
            if (!done) {
                break;
            }
            pulls.add(new PullRequest(number, str(JsonParser.path(node, "title")),
                    str(JsonParser.path(node, "html_url")), str(JsonParser.path(node, "user", "login")),
                    str(JsonParser.path(node, "updated_at")), Boolean.TRUE.equals(JsonParser.path(node, "draft")),
                    files));
        }
        // A full page of 100 means there may be more open PRs than this single listing request returned.
        boolean complete = pulls.size() == nodes.size() && nodes.size() < 100;
        if (!complete) {
            System.out.println("  GitHub: no token found — showing the " + pulls.size()
                    + " most recently updated open PRs. Set GH_TOKEN (or log in with `gh`) to see all of them.");
        }
        return new Result(pulls, complete ? "all open PRs" : "latest " + pulls.size() + " open PRs (no token)");
    }

    private Object send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return JsonParser.parse(request(builder).body());
    }

    /**
     * Sends a request with the headers GitHub expects (a User-Agent is mandatory for its API) and turns every non-2xx
     * status into an {@link IOException} whose message is shown to the user (see {@link #failure}).
     */
    private HttpResponse<String> request(HttpRequest.Builder builder) throws IOException, InterruptedException {
        builder.timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "codecity");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String failure = failure(response.statusCode(),
                response.headers().firstValue("x-ratelimit-remaining").orElse(null),
                response.headers().firstValue("retry-after").isPresent());
        if (failure != null) {
            throw new IOException(failure);
        }
        return response;
    }

    /**
     * The message for a failed request, or null for a 2xx status. GitHub answers an exhausted rate limit with 429, or
     * with 403 and either {@code x-ratelimit-remaining: 0} (primary limit) or a {@code retry-after} header (secondary
     * limit); any other 403 means the token or anonymous client may not access the repository.
     */
    static String failure(int status, String rateLimitRemaining, boolean retryAfter) {
        if (status / 100 == 2) {
            return null;
        }
        if (status == 429 || status == 403 && ("0".equals(rateLimitRemaining) || retryAfter)) {
            return "rate limited (HTTP " + status + ")";
        }
        return switch (status) {
            case 401 -> "token rejected (HTTP 401)";
            case 403 -> "access denied (HTTP 403)";
            default -> "HTTP " + status;
        };
    }

    /** Requests left in the current rate-limit window; without the header, assume enough for the full lookup budget. */
    private static int remaining(HttpResponse<?> response) {
        return response.headers().firstValue("x-ratelimit-remaining").map(Integer::parseInt).orElse(ANONYMOUS_FILE_LOOKUPS + 1);
    }

    private static List<Object> list(Object o) {
        if (o instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return List.of();
    }

    private static String str(Object o) {
        return o instanceof String s ? s : o == null ? null : String.valueOf(o);
    }

    /** {@link JsonParser} returns every number as a Double. */
    private static int num(Object o) {
        return o instanceof Double d ? d.intValue() : 0;
    }
}
