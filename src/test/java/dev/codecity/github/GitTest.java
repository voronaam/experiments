package dev.codecity.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PATH search that finds git and gh, and the time limit of short commands (the Rust and D editions test the same cases). */
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

    /**
     * A fake {@code git} in {@code dir} that writes its process id to {@code dir/pid} and then runs {@code body}; found
     * through {@link Git#lookup} with {@code dir} as the whole PATH.
     */
    private static Path fakeGit(Path dir, String body) throws Exception {
        Path git = Files.writeString(dir.resolve("git"), "#!/bin/sh\necho $$ > '" + dir.resolve("pid") + "'\n" + body + "\n");
        assertTrue(git.toFile().setExecutable(true));
        Path found = Git.lookup("git", Map.of("PATH", dir.toString())::get).orElseThrow();
        assertEquals(git, found);
        return found;
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake git is a shell script")
    void killsACommandThatOutlivesItsTimeLimit(@TempDir Path dir) throws Exception {
        // In time (a limit far beyond what the command needs, however busy the machine): the output, stripped; a
        // failure: nothing.
        Duration plenty = Duration.ofSeconds(60);
        assertEquals(Optional.of("ok"), Git.runCommand(new ProcessBuilder(fakeGit(dir, "echo ' ok '").toString()), plenty));
        assertEquals(Optional.empty(), Git.runCommand(new ProcessBuilder(fakeGit(dir, "echo no; exit 3").toString()), plenty));
        // Hanging with stdout open (after some output) for ten minutes: nothing is returned once the limit runs out,
        // and the process is gone. Only the outcome is checked, not how long it took.
        Path git = fakeGit(dir, "echo partial; exec sleep 600");
        Files.deleteIfExists(dir.resolve("pid"));
        long start = System.nanoTime();
        assertEquals(Optional.empty(), Git.runCommand(new ProcessBuilder(git.toString()), Duration.ofSeconds(3)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(300).toNanos(), "not killed");
        // The script writes its id first thing; under extreme load it may not have got that far before the kill.
        if (Files.exists(dir.resolve("pid"))) {
            long pid = Long.parseLong(Files.readString(dir.resolve("pid")).strip());
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the command still runs");
        }
    }
}
