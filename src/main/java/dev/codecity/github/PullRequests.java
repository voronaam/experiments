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
 * Fetches open pull requests and the files they touch.
 * <ul>
 *   <li>With a token (GH_TOKEN, GITHUB_TOKEN or {@code gh auth token}): GraphQL, up to 300 PRs in a few requests.</li>
 *   <li>Without one: the REST API, limited to the most recently updated PRs that fit the anonymous rate limit.</li>
 * </ul>
 * Never throws: failures are reported and result in an empty list.
 */
public final class PullRequests {

    private static final int MAX_PAGES = 6;
    private static final int PAGE_SIZE = 50;
    private static final int ANONYMOUS_FILE_LOOKUPS = 30;

    public record Result(List<PullRequest> pulls, String source) {
    }

    private static final String QUERY = """
            query($owner: String!, $name: String!, $cursor: String) {
              repository(owner: $owner, name: $name) {
                pullRequests(states: OPEN, first: %d, after: $cursor, orderBy: {field: UPDATED_AT, direction: DESC}) {
                  pageInfo { hasNextPage endCursor }
                  nodes {
                    number title url updatedAt isDraft
                    author { login }
                    files(first: 100) { nodes { path } }
                  }
                }
              }
            }""".formatted(PAGE_SIZE);

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

    private Result graphql() throws IOException, InterruptedException {
        List<PullRequest> pulls = new ArrayList<>();
        String cursor = null;
        boolean more = true;
        for (int page = 0; page < MAX_PAGES && more; page++) {
            JsonWriter vars = new JsonWriter().beginObject()
                    .field("owner", repo.owner()).field("name", repo.name()).name("cursor").value(cursor)
                    .endObject();
            String body = new JsonWriter().beginObject()
                    .field("query", QUERY).name("variables").raw(vars.toString())
                    .endObject().toString();
            Object json = send(HttpRequest.newBuilder(URI.create(repo.graphqlApi()))
                    .POST(HttpRequest.BodyPublishers.ofString(body)));
            if (JsonParser.path(json, "errors") instanceof List<?> errors && !errors.isEmpty()) {
                throw new IOException(String.valueOf(JsonParser.path(errors.getFirst(), "message")));
            }
            Object prs = JsonParser.path(json, "data", "repository", "pullRequests");
            if (prs == null) {
                throw new IOException("repository not found or not accessible");
            }
            for (Object node : list(JsonParser.path(prs, "nodes"))) {
                List<String> files = new ArrayList<>();
                for (Object f : list(JsonParser.path(node, "files", "nodes"))) {
                    files.add(str(JsonParser.path(f, "path")));
                }
                pulls.add(new PullRequest(num(JsonParser.path(node, "number")), str(JsonParser.path(node, "title")),
                        str(JsonParser.path(node, "url")), str(JsonParser.path(node, "author", "login")),
                        str(JsonParser.path(node, "updatedAt")), Boolean.TRUE.equals(JsonParser.path(node, "isDraft")),
                        files));
            }
            more = Boolean.TRUE.equals(JsonParser.path(prs, "pageInfo", "hasNextPage"));
            cursor = str(JsonParser.path(prs, "pageInfo", "endCursor"));
        }
        return new Result(pulls, more ? "latest " + pulls.size() + " open PRs" : "all open PRs");
    }

    private Result rest() throws IOException, InterruptedException {
        String base = repo.restApi() + "/repos/" + repo.owner() + "/" + repo.name();
        HttpResponse<String> listing = request(HttpRequest.newBuilder(
                URI.create(base + "/pulls?state=open&sort=updated&direction=desc&per_page=100")).GET());
        int budget = Math.min(ANONYMOUS_FILE_LOOKUPS, remaining(listing) - 1);
        List<Object> nodes = list(JsonParser.parse(listing.body()));
        List<PullRequest> pulls = new ArrayList<>();
        for (Object node : nodes) {
            if (pulls.size() >= budget) {
                break;
            }
            int number = num(JsonParser.path(node, "number"));
            HttpResponse<String> filesResponse = request(HttpRequest.newBuilder(
                    URI.create(base + "/pulls/" + number + "/files?per_page=100")).GET());
            List<String> files = new ArrayList<>();
            for (Object f : list(JsonParser.parse(filesResponse.body()))) {
                files.add(str(JsonParser.path(f, "filename")));
            }
            pulls.add(new PullRequest(number, str(JsonParser.path(node, "title")),
                    str(JsonParser.path(node, "html_url")), str(JsonParser.path(node, "user", "login")),
                    str(JsonParser.path(node, "updated_at")), Boolean.TRUE.equals(JsonParser.path(node, "draft")),
                    files));
        }
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

    private HttpResponse<String> request(HttpRequest.Builder builder) throws IOException, InterruptedException {
        builder.timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "codecity");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 403 || response.statusCode() == 429) {
            throw new IOException("rate limited (HTTP " + response.statusCode() + ")");
        }
        if (response.statusCode() == 401) {
            throw new IOException("token rejected (HTTP 401)");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response;
    }

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

    private static int num(Object o) {
        return o instanceof Double d ? d.intValue() : 0;
    }
}
