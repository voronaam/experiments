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
}
