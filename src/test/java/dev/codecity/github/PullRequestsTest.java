package dev.codecity.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
