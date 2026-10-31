package dev.codecity;

import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.github.GitHubInfo;
import dev.codecity.github.GitHubRepo;
import dev.codecity.github.PullRequest;
import dev.codecity.json.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The GitHub part of the city JSON: the {@code github} section (repository, ref, prefix, and the pull requests when
 * they were fetched) and the {@code prs} of every building, matched by repository path (the prefix plus the
 * building's file). Built on the {@code testdata/java-project} fixture, analysed as the subdirectory
 * {@code testdata/java-project/} of a repository. The Rust and D editions check the same JSON.
 */
class CityGitHubTest {

    private static final Path FIXTURE = Path.of("testdata", "java-project").toAbsolutePath();
    private static final String PREFIX = "testdata/java-project/";
    private static final String REGISTRY = PREFIX + "core/src/main/java/com/acme/core/Registry.java";
    private static final GitHubRepo REPO = new GitHubRepo("github.com", "acme", "shop", null);

    private static Map<?, ?> city(GitHubInfo github) throws Exception {
        return (Map<?, ?>) JsonParser.parse(CityModel.build(new SourceAnalyzer(), FIXTURE.toRealPath(), true, false,
                github, 0).json());
    }

    /** Building id → its {@code prs} (only the buildings that have any), in id order. */
    private static Map<String, List<?>> prsById(Map<?, ?> city) {
        Map<String, List<?>> prs = new TreeMap<>();
        for (Object b : (List<?>) city.get("buildings")) {
            Map<?, ?> m = (Map<?, ?>) b;
            if (m.containsKey("prs")) {
                prs.put((String) m.get("id"), (List<?>) m.get("prs"));
            }
        }
        return prs;
    }

    private static Map<String, Object> pull(int number, String author, boolean draft, int files) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", (double) number);
        m.put("title", "PR " + number);
        m.put("url", "https://github.com/acme/shop/pull/" + number);
        m.put("author", author);
        m.put("updatedAt", "2026-10-01T10:00:00Z");
        m.put("draft", draft);
        m.put("files", (double) files);
        return m;
    }

    private static PullRequest pr(int number, String author, boolean draft, List<String> files) {
        return new PullRequest(number, "PR " + number, "https://github.com/acme/shop/pull/" + number, author,
                "2026-10-01T10:00:00Z", draft, files);
    }

    @Test
    void matchesPullRequestsToBuildingsByRepositoryPath() throws Exception {
        GitHubInfo github = new GitHubInfo(REPO, "0123456789abcdef0123456789abcdef01234567", PREFIX, List.of(
                pr(11, "ann", false, List.of(REGISTRY, "README.md")),
                pr(12, null, true, List.of(REGISTRY, PREFIX + "Scratch.java")),
                // Root-relative (no prefix) and differently cased paths match nothing.
                pr(13, "bob", false, List.of("core/src/main/java/com/acme/core/Circle.java",
                        PREFIX + "core/src/main/java/com/acme/core/circle.java"))),
                "all open PRs");
        Map<?, ?> city = city(github);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("host", "github.com");
        expected.put("owner", "acme");
        expected.put("repo", "shop");
        expected.put("url", "https://github.com/acme/shop");
        expected.put("ref", "0123456789abcdef0123456789abcdef01234567");
        expected.put("prefix", PREFIX);
        expected.put("pullsSource", "all open PRs");
        expected.put("pulls", List.of(pull(11, "ann", false, 2), pull(12, null, true, 2), pull(13, "bob", false, 2)));
        assertEquals(expected, city.get("github"));
        // Every type of Registry.java has both PRs touching it; Scratch has the second only.
        Map<String, List<?>> prs = new TreeMap<>();
        for (String id : List.of("com.acme.core.Registry", "com.acme.core.Registry.Cursor",
                "com.acme.core.Registry.Cursor.Step", "com.acme.core.Registry.Entry", "com.acme.core.Registry.Listener",
                "com.acme.core.Registry.Mode", "com.acme.core.Registry.Stats")) {
            prs.put(id, List.of(0.0, 1.0));
        }
        prs.put("Scratch", List.of(1.0));
        assertEquals(prs, prsById(city));
    }

    @Test
    void leavesPullRequestsOutWhenTheyWereNotFetched() throws Exception {
        Map<?, ?> city = city(new GitHubInfo(REPO, "main", "", null, null));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("host", "github.com");
        expected.put("owner", "acme");
        expected.put("repo", "shop");
        expected.put("url", "https://github.com/acme/shop");
        expected.put("ref", "main");
        expected.put("prefix", "");
        assertEquals(expected, city.get("github"));
        assertEquals(Map.of(), prsById(city));
        // No GitHub context at all: no section.
        assertFalse(city(null).containsKey("github"));
    }

    @Test
    void matchesAtTheRepositoryTopWithoutPrefix() throws Exception {
        Map<?, ?> city = city(new GitHubInfo(REPO, "main", "", List.of(pr(1, "ann", false, List.of("Scratch.java"))),
                "latest 1 open PRs (no token)"));
        assertEquals(Map.of("Scratch", List.of(0.0)), prsById(city));
        assertEquals("latest 1 open PRs (no token)", ((Map<?, ?>) city.get("github")).get("pullsSource"));
    }
}
