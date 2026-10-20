package dev.codecity.github;

import dev.codecity.json.JsonParser;
import dev.codecity.json.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
 * <p>The repository may come from the analysed tree's own (untrusted) git configuration, so the token is guarded: an
 * environment token for github.com only ever goes to api.github.com, and a GitHub Enterprise host gets a token (or a
 * request at all) only when the user opted in to that very host (see {@link #token} and {@link #fetch}). Redirects
 * are followed only within the same host, and responses are capped in size ({@link #MAX_RESPONSE_BYTES}) and in
 * nesting depth (by {@link JsonParser}).
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
    /** Largest response body read; a larger one fails the fetch. The same limit as the Rust edition's HTTP client. */
    static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    /** Redirect statuses that are followed (within the same host), and how many of them in a row. */
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 307, 308);
    private static final int MAX_REDIRECTS = 5;
    /** Environment variables that may hold a token; {@code gh} runs without them (see {@link #ghToken}). */
    private static final List<String> TOKEN_VARIABLES =
            List.of("GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN");

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
            // Redirects are followed by hand in request(), and only within the same host: the JDK client would resend
            // the Authorization header to wherever a redirect points.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final GitHubRepo repo;
    private final String token;

    private PullRequests(GitHubRepo repo, String token) {
        this.repo = repo;
        this.token = token;
    }

    /**
     * Fetches the open PRs of {@code repo}, via GraphQL when a token is available and via REST otherwise. A GitHub
     * Enterprise host is not contacted at all unless there is a token for it or {@code GH_HOST} names it: the host
     * may come from a hostile repository's remote URL, and CodeCity should not call out to it on its own.
     */
    public static Result fetch(GitHubRepo repo) {
        String token = token(repo).orElse(null);
        if (token == null && !repo.isPublicGitHub() && !optedIn(repo, System::getenv)) {
            System.err.println("  GitHub: not contacting " + repo.host() + " (for a GitHub Enterprise host, set GH_HOST="
                    + repo.host() + " or log in with `gh auth login --hostname " + repo.host() + "`)");
            return new Result(List.of(), "unavailable");
        }
        PullRequests client = new PullRequests(repo, token);
        try {
            return token != null ? client.graphql() : client.rest();
        } catch (IOException | RuntimeException e) {
            System.err.println("  GitHub: could not fetch pull requests (" + dev.codecity.CodeCity.printable(e.getMessage()) + ")");
            return new Result(List.of(), "unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(List.of(), "unavailable");
        }
    }

    /**
     * Looks for an API token: the environment first ({@link #envToken}), then the GitHub CLI's stored login for the
     * repository's host ({@link #ghToken}). Empty when there is none, in which case the REST API is used anonymously.
     */
    static Optional<String> token(GitHubRepo repo) {
        return envToken(repo, System::getenv).or(() -> ghToken(repo.host()));
    }

    /**
     * The token from the environment variables the {@code gh} CLI reads: {@code GH_TOKEN} or {@code GITHUB_TOKEN}
     * for github.com, {@code GH_ENTERPRISE_TOKEN} or {@code GITHUB_ENTERPRISE_TOKEN} for any other host, but only when
     * {@code GH_HOST} names that host ({@link #optedIn}). Without that opt-in, a crafted remote URL such as
     * {@code https://github.attacker.example/o/r} in an analysed repository would receive the user's token.
     */
    static Optional<String> envToken(GitHubRepo repo, Function<String, String> env) {
        List<String> vars = repo.isPublicGitHub() ? List.of("GH_TOKEN", "GITHUB_TOKEN")
                : optedIn(repo, env) ? List.of("GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN") : List.of();
        for (String var : vars) {
            String v = env.apply(var);
            if (v != null && !v.isBlank()) {
                return Optional.of(v.strip());
            }
        }
        return Optional.empty();
    }

    /** Whether {@code GH_HOST} names the repository's host (case-insensitively): the opt-in for an Enterprise host. */
    static boolean optedIn(GitHubRepo repo, Function<String, String> env) {
        String host = env.apply("GH_HOST");
        return host != null && host.strip().equalsIgnoreCase(repo.host());
    }

    /**
     * {@code gh auth token} for {@code host}: the token of a login the user made to exactly that host. {@code gh}
     * runs without the token variables, since it would otherwise answer with {@code GH_ENTERPRISE_TOKEN} for any
     * host; the host is passed as {@code --hostname=<host>}, so it can never be read as another option.
     */
    private static Optional<String> ghToken(String host) {
        Optional<java.nio.file.Path> exe = Git.executable("gh");
        if (exe.isEmpty()) {
            return Optional.empty();
        }
        ProcessBuilder gh = new ProcessBuilder(exe.get().toString(), "auth", "token", "--hostname=" + host)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(Git.nullDevice())))
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        TOKEN_VARIABLES.forEach(gh.environment()::remove);
        return Git.runCommand(gh).filter(s -> !s.isBlank());
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
                        pullUrl(repo, str(JsonParser.path(node, "url")), number),
                        str(JsonParser.path(node, "author", "login")),
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
        Reply listing = request(HttpRequest.newBuilder(
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
                    pullUrl(repo, str(JsonParser.path(node, "html_url")), number),
                    str(JsonParser.path(node, "user", "login")),
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

    /** A successful response: its body (UTF-8) and headers. */
    private record Reply(String body, HttpHeaders headers) {
    }

    /**
     * Sends a request with the headers GitHub expects (a User-Agent is mandatory for its API) and turns every non-2xx
     * status into an {@link IOException} whose message is shown to the user (see {@link #failure}).
     *
     * <p>A redirect (GitHub uses them for renamed repositories) is followed only to an https URL on the same host and
     * port, with the same method and headers; a redirect elsewhere fails the request, so the token cannot be sent on.
     * The body is read up to {@link #MAX_RESPONSE_BYTES}.
     */
    private Reply request(HttpRequest.Builder builder) throws IOException, InterruptedException {
        builder.timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "codecity");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        URI uri = builder.build().uri();
        for (int hops = 0; ; hops++) {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                Optional<String> location = response.headers().firstValue("location");
                if (REDIRECTS.contains(response.statusCode()) && location.isPresent() && hops < MAX_REDIRECTS) {
                    URI next = uri.resolve(location.get());
                    if (!"https".equalsIgnoreCase(next.getScheme()) || !uri.getHost().equalsIgnoreCase(next.getHost())
                            || uri.getPort() != next.getPort()) {
                        throw new IOException("redirect to another host refused");
                    }
                    builder.uri(next);
                    uri = next;
                    continue;
                }
                String failure = failure(response.statusCode(),
                        response.headers().firstValue("x-ratelimit-remaining").orElse(null),
                        response.headers().firstValue("retry-after").isPresent());
                if (failure != null) {
                    throw new IOException(failure);
                }
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("response larger than " + MAX_RESPONSE_BYTES + " bytes");
                }
                return new Reply(new String(bytes, StandardCharsets.UTF_8), response.headers());
            }
        }
    }

    /**
     * The link of PR {@code number}: {@code url} from the response when it is an https URL on the repository's host,
     * otherwise the PR's page built from the repository. The URL ends up in the viewers' links and in the desktop
     * viewers' browser launcher, which must only ever be handed a web page (not, say, a {@code file:} URL).
     */
    static String pullUrl(GitHubRepo repo, String url, int number) {
        return url != null && url.startsWith("https://" + repo.host() + "/") && url.chars().noneMatch(c -> c <= ' ')
                ? url : repo.webUrl() + "/pull/" + number;
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
    private static int remaining(Reply response) {
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
