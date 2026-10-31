package dev.codecity.github;

import dev.codecity.github.FakeGitHub.Reply;
import dev.codecity.github.FakeGitHub.Request;
import dev.codecity.json.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pull-request fetching end to end, against {@link FakeGitHub} on the loopback interface: GraphQL with a token
 * (pagination of PRs and of the files of a PR), the anonymous REST fallback and its request budget, error
 * classification, the response size and nesting limits, redirects, and which token goes where. The Rust
 * ({@code github_tests.rs}) and D ({@code github_tests.d}) editions run the same scenarios with the same expectations.
 */
class PullRequestsFetchTest {

    private static final GitHubRepo PUBLIC = GitHubRepo.parse("https://github.com/o/r").orElseThrow();
    private static final GitHubRepo ENTERPRISE = GitHubRepo.parse("https://github.acme.example/o/r").orElseThrow();
    /** The largest response body accepted. */
    private static final int MAX_BODY = 10 * 1024 * 1024;

    // ------------------------------------------------------------------ response builders

    /** A JSON string literal (the test data needs no escapes). */
    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    /** A GraphQL files connection: {@code paths}, and whether (and after which cursor) more follow. */
    private static String files(List<String> paths, boolean more, String cursor) {
        return "{\"pageInfo\":{\"hasNextPage\":" + more + ",\"endCursor\":" + q(cursor) + "},\"nodes\":["
                + String.join(",", paths.stream().map(p -> "{\"path\":" + q(p) + "}").toList()) + "]}";
    }

    /** A GraphQL pull request node. */
    private static String node(int number, String url, String author, boolean draft, String files) {
        return "{\"number\":" + number + ",\"title\":\"PR " + number + "\",\"url\":" + q(url)
                + ",\"updatedAt\":\"2026-10-0" + (number % 9 + 1) + "T10:00:00Z\",\"isDraft\":" + draft
                + ",\"author\":" + (author == null ? "null" : "{\"login\":" + q(author) + "}") + ",\"files\":" + files + "}";
    }

    /** A GraphQL page of pull requests. */
    private static String page(List<String> nodes, boolean more, String cursor) {
        return "{\"data\":{\"repository\":{\"pullRequests\":{\"pageInfo\":{\"hasNextPage\":" + more + ",\"endCursor\":"
                + q(cursor) + "},\"nodes\":[" + String.join(",", nodes) + "]}}}}";
    }

    /** The answer to a files query: the next page of one PR's files. */
    private static String filesPage(String connection) {
        return "{\"data\":{\"repository\":{\"pullRequest\":{\"files\":" + connection + "}}}}";
    }

    /** {@code src/f000.java} … for the numbers {@code from} (inclusive) to {@code to} (exclusive). */
    private static List<String> paths(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "src/f%03d.java".formatted(i)).toList();
    }

    /** A REST pull request of the listing. */
    private static String restPull(int number) {
        return "{\"number\":" + number + ",\"title\":\"PR " + number + "\",\"html_url\":\"https://github.com/o/r/pull/"
                + number + "\",\"user\":{\"login\":\"u" + number + "\"},\"updated_at\":\"2026-10-01T10:00:00Z\",\"draft\":false}";
    }

    /** A REST listing of the pull requests {@code numbers}. */
    private static String listing(List<Integer> numbers) {
        return "[" + String.join(",", numbers.stream().map(PullRequestsFetchTest::restPull).toList()) + "]";
    }

    /** A REST page of files. */
    private static String restFiles(List<String> paths) {
        return "[" + String.join(",", paths.stream().map(p -> "{\"filename\":" + q(p) + "}").toList()) + "]";
    }

    /** The GraphQL variables of a request. */
    private static Object variables(Request r) {
        return JsonParser.path(JsonParser.parse(r.body()), "variables");
    }

    /** The answer to a REST files request {@code /pulls/<n>/files?per_page=100&page=<p>}, by {@code (n, p)}. */
    private static Reply restAnswer(Request r, String listing, Map<String, String> listingHeaders,
                                    Function<int[], List<String>> files) {
        String prefix = "/api/v3/repos/o/r/pulls";
        if (r.target().equals(prefix + "?state=open&sort=updated&direction=desc&per_page=100")) {
            return Reply.json(listing, listingHeaders);
        }
        String rest = r.target().substring(prefix.length() + 1);
        int number = Integer.parseInt(rest.substring(0, rest.indexOf('/')));
        int pageNo = Integer.parseInt(rest.substring(rest.lastIndexOf("&page=") + 6));
        return Reply.json(restFiles(files.apply(new int[]{number, pageNo})));
    }

    private static Map<String, String> env(String... pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    /** Fetches {@code repo}'s pull requests from {@code fake} with the environment variables {@code vars}. */
    private static PullRequests.Result fetch(FakeGitHub fake, GitHubRepo repo, Map<String, String> vars)
            throws Exception {
        return PullRequests.fetch(repo, fake.environment(vars));
    }

    /**
     * The message of the failure of a fetch: an IOException, or a RuntimeException from the JSON parser (both are
     * reported the same way by {@link PullRequests#fetch(GitHubRepo)}).
     */
    private static String failure(FakeGitHub fake, GitHubRepo repo, Map<String, String> vars) {
        return assertThrows(Exception.class, () -> fetch(fake, repo, vars)).getMessage();
    }

    // ------------------------------------------------------------------ GraphQL

    @Test
    void pagesThroughPullRequestsAndTheirFilesWithGraphQL() throws Exception {
        try (FakeGitHub fake = new FakeGitHub(r -> {
            Object v = variables(r);
            if (JsonParser.path(v, "number") != null) {
                // The files of PR 2 after the first 100: two more pages.
                return switch (String.valueOf(JsonParser.path(v, "cursor"))) {
                    case "f1" -> Reply.json(filesPage(files(paths(100, 200), true, "f2")));
                    case "f2" -> Reply.json(filesPage(files(paths(200, 205), false, "f3")));
                    default -> Reply.status(404, Map.of());
                };
            }
            if (JsonParser.path(v, "cursor") == null) {
                return Reply.json(page(List.of(
                        node(1, "https://github.com/o/r/pull/1", "ann", false,
                                files(List.of("src/A.java", "src/B.java"), false, "x")),
                        node(2, "https://github.com/o/r/pull/2", "bob", false, files(paths(0, 100), true, "f1"))),
                        true, "p1"));
            }
            // The second page: a draft by a deleted user, with a link that is not a web page on the host.
            return Reply.json(page(List.of(node(3, "javascript:alert(1)", null, true, files(List.of(), false, null))),
                    false, "p2"));
        })) {
            PullRequests.Result result = fetch(fake, PUBLIC, env("GH_TOKEN", "t0k"));
            assertEquals("all open PRs", result.source());
            List<PullRequest> pulls = result.pulls();
            assertEquals(List.of(1, 2, 3), pulls.stream().map(PullRequest::number).toList());
            assertEquals(new PullRequest(1, "PR 1", "https://github.com/o/r/pull/1", "ann", "2026-10-02T10:00:00Z",
                    false, List.of("src/A.java", "src/B.java")), pulls.get(0));
            assertEquals(paths(0, 205), pulls.get(1).files());
            assertEquals(new PullRequest(3, "PR 3", "https://github.com/o/r/pull/3", null, "2026-10-04T10:00:00Z",
                    true, List.of()), pulls.get(2));
            // Four POSTs, each with the token: PR page 1, the two further file pages of PR 2, PR page 2.
            List<Request> requests = fake.requests();
            assertEquals(4, requests.size());
            List<String> sent = new ArrayList<>();
            for (Request r : requests) {
                assertEquals("POST", r.method());
                assertEquals("/api/graphql", r.target());
                assertEquals("Bearer t0k", r.authorization());
                Object v = variables(r);
                assertEquals("o", JsonParser.path(v, "owner"));
                assertEquals("r", JsonParser.path(v, "name"));
                Object number = JsonParser.path(v, "number");
                sent.add((number == null ? "" : ((Double) number).intValue() + "@") + JsonParser.path(v, "cursor"));
            }
            assertEquals(List.of("null", "2@f1", "2@f2", "p1"), sent);
        }
    }

    @Test
    void stopsAfterSixGraphQLPages() throws Exception {
        int[] served = {0};
        try (FakeGitHub fake = new FakeGitHub(r -> {
            int n = ++served[0];
            return Reply.json(page(List.of(node(n, "https://github.com/o/r/pull/" + n, "ann", false,
                    files(List.of("a.java"), false, null))), true, "p" + n));
        })) {
            PullRequests.Result result = fetch(fake, PUBLIC, env("GH_TOKEN", "t0k"));
            assertEquals("latest 6 open PRs", result.source());
            assertEquals(List.of(1, 2, 3, 4, 5, 6), result.pulls().stream().map(PullRequest::number).toList());
            assertEquals(6, fake.requests().size());
        }
    }

    @Test
    void reportsGraphQLErrorsAndMissingRepositories() throws Exception {
        try (FakeGitHub fake = new FakeGitHub(r -> Reply.json("{\"errors\":[{\"message\":\"Something went wrong\"}]}"))) {
            assertEquals("Something went wrong", failure(fake, PUBLIC, env("GH_TOKEN", "t0k")));
        }
        try (FakeGitHub fake = new FakeGitHub(r -> Reply.json("{\"data\":{\"repository\":null}}"))) {
            assertEquals("repository not found or not accessible", failure(fake, PUBLIC, env("GH_TOKEN", "t0k")));
        }
    }

    // ------------------------------------------------------------------ REST

    @Test
    void fallsBackToRestWithoutAToken() throws Exception {
        try (FakeGitHub fake = new FakeGitHub(r -> restAnswer(r, listing(List.of(5, 6)),
                Map.of("x-ratelimit-remaining", "59"),
                // PR 5 changes two files; PR 6 changes 103, so its files take two pages.
                np -> np[0] == 5 ? List.of("a.java", "b.java") : np[1] == 1 ? paths(0, 100) : paths(100, 103)))) {
            PullRequests.Result result = fetch(fake, PUBLIC, env());
            assertEquals("all open PRs", result.source());
            assertEquals(2, result.pulls().size());
            assertEquals(new PullRequest(5, "PR 5", "https://github.com/o/r/pull/5", "u5", "2026-10-01T10:00:00Z",
                    false, List.of("a.java", "b.java")), result.pulls().get(0));
            assertEquals(paths(0, 103), result.pulls().get(1).files());
            List<String> targets = fake.requests().stream().map(Request::target).toList();
            assertEquals(List.of("/api/v3/repos/o/r/pulls?state=open&sort=updated&direction=desc&per_page=100",
                    "/api/v3/repos/o/r/pulls/5/files?per_page=100&page=1",
                    "/api/v3/repos/o/r/pulls/6/files?per_page=100&page=1",
                    "/api/v3/repos/o/r/pulls/6/files?per_page=100&page=2"), targets);
            assertTrue(fake.requests().stream().allMatch(r -> r.method().equals("GET") && r.authorization() == null));
        }
    }

    @Test
    void spendsOnlyTheAnonymousRequestBudget() throws Exception {
        // Three requests left: one is kept in reserve, so the files of two PRs are fetched and the third PR is left out.
        try (FakeGitHub fake = new FakeGitHub(r -> restAnswer(r, listing(List.of(1, 2, 3)),
                Map.of("x-ratelimit-remaining", "3"), np -> List.of("a.java")))) {
            PullRequests.Result result = fetch(fake, PUBLIC, env());
            assertEquals("latest 2 open PRs (no token)", result.source());
            assertEquals(List.of(1, 2), result.pulls().stream().map(PullRequest::number).toList());
            assertEquals(3, fake.requests().size());
        }
        // A PR whose files do not fit into the budget is left out rather than shown with some of its files.
        try (FakeGitHub fake = new FakeGitHub(r -> restAnswer(r, listing(List.of(1, 2)),
                Map.of("x-ratelimit-remaining", "3"), np -> np[0] == 1 && np[1] < 3 ? paths(0, 100) : List.of("a.java")))) {
            PullRequests.Result result = fetch(fake, PUBLIC, env());
            assertEquals("latest 0 open PRs (no token)", result.source());
            assertEquals(List.of(), result.pulls());
            assertEquals(3, fake.requests().size());
        }
        // Without a rate-limit header, at most 30 file requests are made.
        try (FakeGitHub fake = new FakeGitHub(r -> restAnswer(r, listing(IntStream.rangeClosed(1, 40).boxed().toList()),
                Map.of(), np -> List.of("a.java")))) {
            PullRequests.Result result = fetch(fake, PUBLIC, env());
            assertEquals("latest 30 open PRs (no token)", result.source());
            assertEquals(30, result.pulls().size());
            assertEquals(31, fake.requests().size());
        }
        // No requests left at all: the listing alone.
        try (FakeGitHub fake = new FakeGitHub(r -> restAnswer(r, listing(List.of(1)),
                Map.of("x-ratelimit-remaining", "0"), np -> List.of("a.java")))) {
            assertEquals("latest 0 open PRs (no token)", fetch(fake, PUBLIC, env()).source());
            assertEquals(1, fake.requests().size());
        }
    }

    // ------------------------------------------------------------------ failures and limits

    @Test
    void classifiesFailedRequests() throws Exception {
        record Case(int status, Map<String, String> headers, String message) {
        }
        for (Case c : List.of(
                new Case(401, Map.of(), "token rejected (HTTP 401)"),
                new Case(403, Map.of("x-ratelimit-remaining", "0"), "rate limited (HTTP 403)"),
                new Case(403, Map.of("retry-after", "60"), "rate limited (HTTP 403)"),
                new Case(429, Map.of(), "rate limited (HTTP 429)"),
                new Case(403, Map.of("x-ratelimit-remaining", "4999"), "access denied (HTTP 403)"),
                new Case(502, Map.of(), "HTTP 502"))) {
            try (FakeGitHub fake = new FakeGitHub(r -> Reply.status(c.status(), c.headers()))) {
                assertEquals(c.message(), failure(fake, PUBLIC, env("GH_TOKEN", "t0k")), c.toString());
                assertEquals(c.message(), failure(fake, PUBLIC, env()), c.toString());
            }
        }
    }

    /** A GraphQL answer with no PRs, padded with spaces to exactly {@code size} bytes. */
    private static String padded(int size) {
        String json = page(List.of(), false, null);
        return json + " ".repeat(size - json.length());
    }

    @Test
    void acceptsResponsesUpToExactlyTenMegabytes() throws Exception {
        for (int size : new int[]{MAX_BODY - 1, MAX_BODY}) {
            try (FakeGitHub fake = new FakeGitHub(r -> Reply.json(padded(size)))) {
                assertEquals("all open PRs", fetch(fake, PUBLIC, env("GH_TOKEN", "t0k")).source(), "size " + size);
            }
        }
        try (FakeGitHub fake = new FakeGitHub(r -> Reply.json(padded(MAX_BODY + 1)))) {
            assertEquals("response larger than 10485760 bytes", failure(fake, PUBLIC, env("GH_TOKEN", "t0k")));
        }
    }

    /**
     * A GraphQL answer with no PRs whose JSON nests {@code depth} arrays and objects deep: the answer's object and
     * {@code depth - 1} arrays, the innermost holding {@code 0} or, with {@code empty}, nothing.
     */
    private static String nested(int depth, boolean empty) {
        return "{\"data\":{\"repository\":{\"pullRequests\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":[]}}},\"pad\":"
                + "[".repeat(depth - 1) + (empty ? "" : "0") + "]".repeat(depth - 1) + "}";
    }

    @Test
    void acceptsJsonNestedUpTo127Levels() throws Exception {
        // Only arrays and objects count, whatever the innermost value; brackets in strings do not count at all.
        for (int depth : new int[]{126, 127}) {
            for (boolean empty : new boolean[]{false, true}) {
                try (FakeGitHub fake = new FakeGitHub(r -> Reply.json(nested(depth, empty)))) {
                    assertEquals("all open PRs", fetch(fake, PUBLIC, env("GH_TOKEN", "t0k")).source(), depth + " " + empty);
                }
            }
        }
        try (FakeGitHub fake = new FakeGitHub(r -> Reply.json(page(List.of(), false, "[".repeat(300))))) {
            assertEquals("all open PRs", fetch(fake, PUBLIC, env("GH_TOKEN", "t0k")).source());
        }
        for (int depth : new int[]{128, 100_000}) {
            for (boolean empty : new boolean[]{false, true}) {
                try (FakeGitHub fake = new FakeGitHub(r -> Reply.json(nested(depth, empty)))) {
                    failure(fake, PUBLIC, env("GH_TOKEN", "t0k"));
                }
            }
        }
    }

    @Test
    void refusesRedirectsToAnotherHostOrToPlainHttp() throws Exception {
        // The other "host" is another port: an origin of its own. It is never contacted, so the token stays here.
        try (FakeGitHub other = emptyApi();
             FakeGitHub fake = new FakeGitHub(r -> Reply.status(307, Map.of("Location", other.base() + "/api/graphql")))) {
            assertEquals("redirect to another host refused", failure(fake, PUBLIC, env("GH_TOKEN", "t0k")));
            assertEquals(List.of(), other.requests());
        }
        // The same host and port, but not https (the fake speaks plain HTTP): refused as well, after one request.
        for (int status : new int[]{301, 302, 307, 308}) {
            try (FakeGitHub fake = new FakeGitHub(r -> Reply.status(status, Map.of("Location", "/api/graphql-moved")))) {
                assertEquals("redirect to another host refused", failure(fake, PUBLIC, env("GH_TOKEN", "t0k")));
                assertEquals(1, fake.requests().size());
            }
        }
    }

    /**
     * The redirect rule of every edition: only to https on the same host (any case) and port, where an explicit port
     * differs from none. The same table is in the Rust and D editions.
     */
    @Test
    void followsRedirectsOnlyToHttpsOnTheSameHostAndPort() {
        URI from = URI.create("https://api.github.com/repos/o/r/pulls");
        assertTrue(PullRequests.mayFollow(from, URI.create("https://api.github.com/repositories/1/pulls")));
        assertTrue(PullRequests.mayFollow(from, URI.create("https://API.GitHub.com/x")));
        assertTrue(PullRequests.mayFollow(URI.create("https://github.acme.example/api/v3/x"),
                URI.create("https://github.acme.example/api/v3/y?page=2")));
        assertTrue(PullRequests.mayFollow(URI.create("https://h.example:8443/a"), URI.create("https://h.example:8443/b")));
        assertFalse(PullRequests.mayFollow(from, URI.create("http://api.github.com/x")));
        assertFalse(PullRequests.mayFollow(from, URI.create("https://evil.example/x")));
        assertFalse(PullRequests.mayFollow(from, URI.create("https://api.github.com:8443/x")));
        assertFalse(PullRequests.mayFollow(from, URI.create("https://api.github.com:443/x")));
        assertFalse(PullRequests.mayFollow(from, URI.create("file:///etc/passwd")));
        assertFalse(PullRequests.mayFollow(from, URI.create("https://api.github.com.evil.example/x")));
    }

    // ------------------------------------------------------------------ which token goes where

    /** The Authorization headers the fake received. */
    private static List<String> authorizations(FakeGitHub fake) {
        return fake.requests().stream().map(r -> String.valueOf(r.authorization())).distinct().toList();
    }

    /** Answers GraphQL with an empty page and REST with an empty listing. */
    private static FakeGitHub emptyApi() throws IOException {
        return new FakeGitHub(r -> Reply.json(r.method().equals("POST") ? page(List.of(), false, null) : "[]"));
    }

    @Test
    void choosesTheTokenByHost() throws Exception {
        // github.com: GH_TOKEN, else GITHUB_TOKEN (trimmed); a blank one does not count.
        try (FakeGitHub fake = emptyApi()) {
            fetch(fake, PUBLIC, env("GH_TOKEN", "  public  ", "GITHUB_TOKEN", "other", "GH_ENTERPRISE_TOKEN", "ent"));
            assertEquals(List.of("Bearer public"), authorizations(fake));
        }
        try (FakeGitHub fake = emptyApi()) {
            fetch(fake, PUBLIC, env("GH_TOKEN", " ", "GITHUB_TOKEN", "second"));
            assertEquals(List.of("Bearer second"), authorizations(fake));
        }
        // An Enterprise host without the GH_HOST opt-in (and no gh login) is not contacted at all.
        try (FakeGitHub fake = emptyApi()) {
            PullRequests.Result result = fetch(fake, ENTERPRISE, env("GH_TOKEN", "public", "GH_ENTERPRISE_TOKEN", "ent"));
            assertEquals("unavailable", result.source());
            assertEquals(List.of(), fake.requests());
        }
        // Opted in without a token: anonymous REST. With GH_ENTERPRISE_TOKEN (or GITHUB_ENTERPRISE_TOKEN): GraphQL.
        try (FakeGitHub fake = emptyApi()) {
            assertEquals("all open PRs", fetch(fake, ENTERPRISE, env("GH_HOST", "github.acme.example",
                    "GH_TOKEN", "public")).source());
            assertEquals(List.of("null"), authorizations(fake));
            assertEquals("GET", fake.requests().getFirst().method());
        }
        try (FakeGitHub fake = emptyApi()) {
            fetch(fake, ENTERPRISE, env("GH_HOST", "GitHub.Acme.Example", "GH_TOKEN", "public",
                    "GITHUB_ENTERPRISE_TOKEN", "ent2"));
            assertEquals(List.of("Bearer ent2"), authorizations(fake));
        }
    }

    /** Writes a fake {@code gh} into {@code dir} that records its arguments in {@code dir/args} and prints {@code out}. */
    private static void fakeGh(Path dir, String out, int exit) throws IOException {
        Path gh = dir.resolve("gh");
        Files.writeString(gh, "#!/bin/sh\necho \"$@\" > '" + dir.resolve("args") + "'\nprintf '%s\\n' '" + out
                + "'\nexit " + exit + "\n");
        assertTrue(gh.toFile().setExecutable(true));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake gh is a shell script")
    void fallsBackToTheGitHubCliLoginForTheHost(@TempDir Path dir) throws Exception {
        fakeGh(dir, "gh-secret", 0);
        Path args = dir.resolve("args");
        String path = dir.toString();
        try (FakeGitHub fake = emptyApi()) {
            fetch(fake, PUBLIC, env("PATH", path));
            assertEquals(List.of("Bearer gh-secret"), authorizations(fake));
            assertEquals("auth token --hostname=github.com", Files.readString(args).strip());
        }
        // A gh login to exactly the Enterprise host counts as the opt-in.
        try (FakeGitHub fake = emptyApi()) {
            fetch(fake, ENTERPRISE, env("PATH", path));
            assertEquals(List.of("Bearer gh-secret"), authorizations(fake));
            assertEquals("auth token --hostname=github.acme.example", Files.readString(args).strip());
        }
        // An environment token wins, and gh is not even asked.
        Files.delete(args);
        try (FakeGitHub fake = emptyApi()) {
            fetch(fake, PUBLIC, env("PATH", path, "GH_TOKEN", "env"));
            assertEquals(List.of("Bearer env"), authorizations(fake));
            assertFalse(Files.exists(args));
        }
        // No login (gh fails): anonymous REST for github.com.
        fakeGh(dir, "", 1);
        try (FakeGitHub fake = emptyApi()) {
            fetch(fake, PUBLIC, env("PATH", path));
            assertEquals(List.of("null"), authorizations(fake));
        }
    }
}
