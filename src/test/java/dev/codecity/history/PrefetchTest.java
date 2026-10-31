package dev.codecity.history;

import dev.codecity.github.GitFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static dev.codecity.github.GitFixture.fileUrl;
import static dev.codecity.github.GitFixture.git;
import static dev.codecity.github.GitFixture.missing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completion of blobless clones before the history replay: {@link CityHistory#prefetchMissing} fetches every
 * missing object in one go, and the replay of a partial clone outside the clone cache fetches nothing at all (its
 * configuration is not to be trusted). The Rust and D editions test the same cases.
 */
class PrefetchTest {

    @Test
    void fetchesEveryMissingObjectOfAPartialClone(@TempDir Path tmp) throws Exception {
        Path bare = GitFixture.remote(tmp);
        Path clone = tmp.resolve("clone");
        git(null, "clone", "-q", "--filter=blob:none", fileUrl(bare), clone.toString());
        assertEquals(1, missing(clone));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CityHistory.prefetchMissing(clone.toRealPath(), new PrintStream(out, true, StandardCharsets.UTF_8));
        assertEquals(0, missing(clone));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("fetching 1 missing objects"), out.toString());
        // Nothing missing: nothing to do (and nothing printed).
        out.reset();
        CityHistory.prefetchMissing(clone.toRealPath(), new PrintStream(out, true, StandardCharsets.UTF_8));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void neverFetchesForAPartialCloneOutsideTheCache(@TempDir Path tmp) throws Exception {
        Path bare = GitFixture.remote(tmp);
        Path clone = tmp.resolve("clone");
        git(null, "clone", "-q", "--filter=blob:none", fileUrl(bare), clone.toString());
        assertEquals(1, missing(clone));
        CityHistory history = CityHistory.compute(clone.toRealPath(), 2, true, Map.of(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertNotNull(history);
        // The old A.java could not be read, but it was not fetched either.
        assertEquals(1, missing(clone));
    }
}
