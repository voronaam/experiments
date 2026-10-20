package dev.codecity.github;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestsTest {

    @Test
    void tellsRateLimitsFromMissingAccess() {
        assertNull(PullRequests.failure(200, "59", false));
        assertEquals("rate limited (HTTP 403)", PullRequests.failure(403, "0", false));
        assertEquals("rate limited (HTTP 403)", PullRequests.failure(403, "4000", true));
        assertEquals("rate limited (HTTP 429)", PullRequests.failure(429, null, false));
        assertEquals("access denied (HTTP 403)", PullRequests.failure(403, "4999", false));
        assertEquals("access denied (HTTP 403)", PullRequests.failure(403, null, false));
        assertEquals("token rejected (HTTP 401)", PullRequests.failure(401, null, false));
        assertEquals("HTTP 404", PullRequests.failure(404, null, false));
    }

    private static final GitHubRepo PUBLIC = GitHubRepo.parse("https://github.com/o/r").orElseThrow();
    private static final GitHubRepo ENTERPRISE = GitHubRepo.parse("https://github.acme.example/o/r").orElseThrow();

    @Test
    void sendsEnvironmentTokensOnlyWhereTheyBelong() {
        Map<String, String> env = Map.of("GH_TOKEN", "public", "GH_ENTERPRISE_TOKEN", "enterprise");
        assertEquals(Optional.of("public"), PullRequests.envToken(PUBLIC, env::get));
        // A remote URL naming some other "github" host never gets GH_TOKEN, nor the Enterprise token without opt-in.
        assertEquals(Optional.empty(), PullRequests.envToken(ENTERPRISE, env::get));
        assertFalse(PullRequests.optedIn(ENTERPRISE, env::get));
        Map<String, String> optIn = Map.of("GH_TOKEN", "public", "GH_ENTERPRISE_TOKEN", "enterprise",
                "GH_HOST", "GitHub.Acme.Example");
        assertTrue(PullRequests.optedIn(ENTERPRISE, optIn::get));
        assertEquals(Optional.of("enterprise"), PullRequests.envToken(ENTERPRISE, optIn::get));
        Map<String, String> otherHost = Map.of("GH_ENTERPRISE_TOKEN", "enterprise", "GH_HOST", "github.other.example");
        assertEquals(Optional.empty(), PullRequests.envToken(ENTERPRISE, otherHost::get));
    }

    @Test
    void keepsOnlyWebLinksOnTheRepositoryHost() {
        assertEquals("https://github.com/o/r/pull/7", PullRequests.pullUrl(PUBLIC, "https://github.com/o/r/pull/7", 7));
        for (String bad : new String[]{null, "file:///etc/passwd", "javascript:alert(1)", "http://github.com/o/r/pull/7",
                "https://github.com.evil.example/x", "https://github.com/o/r/pull/7 x", "https://github.com"}) {
            assertEquals("https://github.com/o/r/pull/7", PullRequests.pullUrl(PUBLIC, bad, 7), bad);
        }
    }
}
