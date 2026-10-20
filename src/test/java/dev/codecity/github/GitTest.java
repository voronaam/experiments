package dev.codecity.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PATH search that finds git and gh (the Rust and D editions test the same cases). */
class GitTest {

    private static Path program(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path p = Files.writeString(dir.resolve("git"), "#!/bin/sh\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    @Test
    void searchesOnlyAbsolutePathEntriesInOrder(@TempDir Path dir) throws Exception {
        Path a = program(dir.resolve("a"));
        Path b = program(dir.resolve("b"));
        Files.createDirectories(dir.resolve("empty"));
        // Relative and empty entries are skipped; the first absolute entry holding the program wins.
        String path = String.join(":", "", ".", "a", dir.resolve("empty").toString(), dir.resolve("a").toString(),
                dir.resolve("b").toString());
        assertEquals(Optional.of(a), Git.find("git", path, ":", List.of(""), false));
        assertEquals(Optional.of(b), Git.find("git", dir.resolve("b").toString(), ":", List.of(""), false));
        // A non-executable file does not count (Unix), and nothing is found without a PATH.
        Path c = Files.createDirectories(dir.resolve("c"));
        Files.writeString(c.resolve("git"), "");
        assertEquals(Optional.of(b), Git.find("git", c + ":" + dir.resolve("b"), ":", List.of(""), false));
        assertEquals(Optional.empty(), Git.find("git", null, ":", List.of(""), false));
        // Windows-style extensions are tried in order.
        Files.writeString(c.resolve("gh.EXE"), "");
        assertEquals(Optional.of(c.resolve("gh.EXE")), Git.find("gh", c.toString(), ":", List.of(".COM", ".EXE"), true));
    }

    @Test
    void neverPicksTheCurrentDirectory() throws Exception {
        // The JVM's working directory: a "git" there must not be found through "." or an empty entry.
        Path cwd = Path.of("").toAbsolutePath();
        Path planted = cwd.resolve("git");
        assertTrue(Files.notExists(planted));
        try {
            program(cwd);
            assertEquals(Optional.empty(), Git.find("git", ".::git", ":", List.of(""), false));
        } finally {
            Files.deleteIfExists(planted);
        }
    }
}
