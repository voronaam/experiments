package dev.codecity.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookTest {

    @Test
    void webLinksAreHttpsOnly() {
        assertTrue(Look.isWebLink("https://github.com/o/r/pull/1"));
        assertTrue(Look.isWebLink("HTTPS://github.com/o/r"));
        for (String bad : new String[]{"javascript:alert(1)", "file:///etc/passwd", "http://x", "--help", "https://x y",
                "https://x\n", "ssh://h", "", "https:/"}) {
            assertFalse(Look.isWebLink(bad), bad);
        }
        assertFalse(Look.isWebLink(null));
    }
}
