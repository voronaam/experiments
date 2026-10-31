package dev.codecity.github;

import dev.codecity.json.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubRepoTest {

    private static GitHubRepo parse(String s) {
        return GitHubRepo.parse(s).orElseThrow();
    }

    @Test
    void parsesCommonRemoteFormats() {
        for (String url : List.of(
                "https://github.com/google/gson",
                "https://github.com/google/gson.git",
                "https://github.com/google/gson/",
                "https://user@github.com/google/gson.git",
                "git@github.com:google/gson.git",
                "ssh://git@github.com/google/gson.git",
                "github:google/gson")) {
            GitHubRepo r = parse(url);
            assertEquals("github.com", r.host(), url);
            assertEquals("google", r.owner(), url);
            assertEquals("gson", r.name(), url);
            assertNull(r.branch(), url);
        }
    }

    @Test
    void keepsBranchFromTreeUrlsAndShorthand() {
        assertEquals("release/2.x", parse("https://github.com/o/r/tree/release/2.x").branch());
        assertEquals("dev", parse("github:o/r@dev").branch());
    }

    @Test
    void supportsEnterpriseHosts() {
        GitHubRepo r = parse("git@github.acme.corp:team/app.git");
        assertEquals("github.acme.corp", r.host());
        assertEquals("https://github.acme.corp/api/graphql", r.graphqlApi());
        assertEquals("https://api.github.com/graphql", parse("github:a/b").graphqlApi());
    }

    @Test
    void ignoresNonGitHubAndUnsafeInput() {
        assertEquals(Optional.empty(), GitHubRepo.parse("https://gitlab.com/o/r"));
        assertEquals(Optional.empty(), GitHubRepo.parse("src/main/java"));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepo.parse("github:../.."));
    }

    @Test
    void rejectsHostsAndBranchesThatCouldInjectSyntax() {
        // Path or URL syntax in the host does not match at all.
        assertEquals(Optional.empty(), GitHubRepo.parse("https://github.x\\..\\../o/r"));
        assertEquals(Optional.empty(), GitHubRepo.parse("https://github.com%2f@evil/o/r"));
        assertEquals(Optional.empty(), GitHubRepo.parse("git@github.x?y:o/r"));
        // A branch that git could read as an option, or that is no valid ref name, is an error.
        for (String b : List.of("--upload-pack=touch%20x", "-x", "a..b", "a b", "a~1", "a:b", "a\\b")) {
            assertThrows(IllegalArgumentException.class, () -> GitHubRepo.parse("https://github.com/o/r/tree/" + b), b);
        }
        assertThrows(IllegalArgumentException.class, () -> GitHubRepo.parse("github:o/r@--upload-pack=x"));
        assertEquals("feature/x-1", parse("github:o/r@feature/x-1").branch());
    }

    @Test
    void jsonParserRefusesDeepNesting() {
        assertEquals(1, ((List<?>) JsonParser.parse("[".repeat(127) + "1" + "]".repeat(127))).size());
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("[".repeat(128) + "]".repeat(128)));
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse(deep));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{\"a\":".repeat(200) + "1" + "}".repeat(200)));
    }

    @Test
    void jsonParserHandlesApiShapes() {
        Object json = JsonParser.parse("""
                {"data": {"repository": {"pullRequests": {"nodes": [
                  {"number": 12, "title": "Fix \\"quotes\\" \\u00e9", "isDraft": false, "author": null}
                ]}}}}""");
        Object nodes = JsonParser.path(json, "data", "repository", "pullRequests", "nodes");
        assertTrue(nodes instanceof List<?>);
        Map<?, ?> pr = (Map<?, ?>) ((List<?>) nodes).getFirst();
        assertEquals(12.0, pr.get("number"));
        assertEquals("Fix \"quotes\" é", pr.get("title"));
        assertEquals(Boolean.FALSE, pr.get("isDraft"));
        assertNull(JsonParser.path(pr, "author", "login"));
    }

    /**
     * Every accepted form with what it yields, the inputs that are not GitHub repositories, and those that are
     * errors. The same table is in the Rust and D editions.
     */
    @Test
    void parsesEveryAcceptedFormAndRejectsTheRest() {
        String[][] accepted = {
                // input, host, owner, name, branch ("" = none)
                {"https://github.com/o/r", "github.com", "o", "r", ""},
                {"http://github.com/o/r", "github.com", "o", "r", ""},
                {"https://github.com/o/r.git", "github.com", "o", "r", ""},
                {"https://github.com/o/r/", "github.com", "o", "r", ""},
                {"https://github.com/o/r.git.git", "github.com", "o", "r.git", ""},
                {"https://user:pw@github.com:443/o/r", "github.com", "o", "r", ""},
                {"https://GitHub.COM/Some-Org/My_Repo.js", "github.com", "Some-Org", "My_Repo.js", ""},
                {"  https://github.com/o/r \n", "github.com", "o", "r", ""},
                {"https://github.com/o/r/tree/main", "github.com", "o", "r", "main"},
                {"https://github.com/o/r/tree/release/2.x/", "github.com", "o", "r", "release/2.x"},
                {"https://github.com/o/r.git/tree/x", "github.com", "o", "r", "x"},
                {"git@github.com:o/r.git", "github.com", "o", "r", ""},
                {"git@github.com:o/r", "github.com", "o", "r", ""},
                {"deploy@github.acme.corp:team/app.git", "github.acme.corp", "team", "app", ""},
                {"ssh://git@github.com/o/r.git", "github.com", "o", "r", ""},
                {"ssh://git@github.com:22/o/r", "github.com", "o", "r", ""},
                {"ssh://github.com/o/r/", "github.com", "o", "r", ""},
                {"github:o/r", "github.com", "o", "r", ""},
                {"github:o/r.git", "github.com", "o", "r", ""},
                {"github:o/r@dev", "github.com", "o", "r", "dev"},
                {"github:o/r@feature/x-1", "github.com", "o", "r", "feature/x-1"},
        };
        for (String[] c : accepted) {
            GitHubRepo r = parse(c[0]);
            assertEquals(new GitHubRepo(c[1], c[2], c[3], c[4].isEmpty() ? null : c[4]), r, c[0]);
        }
        for (String s : List.of("https://gitlab.com/o/r", "src/main/java", "github.com/o/r", "https://github.com/o",
                "https://github.com/o/r/issues", "https://github.com/o/r/tree/", "https://github.com/o/r?tab=1",
                "https://github.com/o/r#readme", "ftp://github.com/o/r", "file:///github.com/o/r", "github:o",
                "github:o/r@", "https://github.com/o/r/x", "")) {
            assertEquals(Optional.empty(), GitHubRepo.parse(s), s);
        }
        for (String s : List.of("github:../..", "https://github.com/../r", "git@github.com:o/..", "https://github.com/o/./",
                "ssh://git@github.com/./r", "https://github.com/o/r/tree/a^b", "https://github.com/o/r/tree/a?b",
                "https://github.com/o/r/tree/a*b", "https://github.com/o/r/tree/a[b", "github:o/r@a\u0001b",
                "github:o/r@a\u007fb", "github:o/r@-")) {
            assertThrows(IllegalArgumentException.class, () -> GitHubRepo.parse(s), s);
        }
        // The URLs derived from a repository.
        GitHubRepo pub = parse("github:o/r@dev");
        assertEquals("https://github.com/o/r", pub.webUrl());
        assertEquals("https://github.com/o/r.git", pub.cloneUrl());
        assertEquals("https://api.github.com", pub.restApi());
        assertEquals("https://api.github.com/graphql", pub.graphqlApi());
        assertEquals("o/r@dev", pub.toString());
        GitHubRepo ent = parse("git@github.acme.corp:team/app.git");
        assertEquals("https://github.acme.corp/team/app.git", ent.cloneUrl());
        assertEquals("https://github.acme.corp/api/v3", ent.restApi());
        assertEquals("https://github.acme.corp/api/graphql", ent.graphqlApi());
        assertEquals("team/app", ent.toString());
    }
}
