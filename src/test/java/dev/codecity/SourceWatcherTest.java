package dev.codecity;

import dev.codecity.analysis.SourceAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Watch mode's file watcher: which changes cause a rebuild, and that a burst of changes causes exactly one. The Rust
 * ({@code watch.rs}) and D ({@code watch.d}) editions have the same tests.
 */
class SourceWatcherTest {

    /**
     * How long a test waits for an expected rebuild. Generous, because the JDK's watch service polls on platforms
     * without a native implementation (macOS: every few seconds).
     */
    private static final long WAIT_MS = 30_000;

    /**
     * How long the tree is left alone after a rebuild before the count is checked again: longer than the 400 ms
     * debounce, so a second rebuild from the same burst would have happened by then.
     */
    private static final long QUIET_MS = 900;

    /** Rebuilds so far. */
    private final AtomicInteger calls = new AtomicInteger();

    /** Waits until {@code n} rebuilds have happened, then checks that no further one follows. */
    private void expectCalls(int n) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (calls.get() < n && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        Thread.sleep(QUIET_MS);
        assertEquals(n, calls.get());
    }

    /**
     * Creating, modifying (in a burst) and deleting a source file, a new directory with a source file (and a later
     * file inside it, so the new directory is watched), the two manifests and deleting a directory each cause exactly
     * one rebuild. Other files, ignored directories (existing and new) and files changed in them cause none.
     */
    @Test
    void rebuildsOnceForEachRelevantChangeAndNeverForOthers(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("package.json"), "{}");
        try (SourceWatcher w = new SourceWatcher(root, new SourceAnalyzer()::isSource, calls::incrementAndGet)) {
            w.start();

            Files.writeString(root.resolve("notes.txt"), "x");
            Files.writeString(root.resolve("notes.txt"), "y");
            Files.writeString(root.resolve("target/X.java"), "class X {}");
            Files.createDirectories(root.resolve("node_modules/dep"));
            Files.writeString(root.resolve("node_modules/dep/Y.java"), "class Y {}");
            Files.createDirectories(root.resolve(".hidden"));
            Files.writeString(root.resolve(".hidden/Z.java"), "class Z {}");
            Files.delete(root.resolve("notes.txt"));
            expectCalls(0);

            Files.writeString(root.resolve("Main.java"), "class Main {}");
            expectCalls(1);
            for (int i = 0; i < 5; i++) {
                Files.writeString(root.resolve("Main.java"), "class Main { int f" + i + "; }");
            }
            expectCalls(2);
            Files.createDirectories(root.resolve("sub"));
            Files.writeString(root.resolve("sub/Sub.java"), "class Sub {}");
            expectCalls(3);
            Files.writeString(root.resolve("sub/Later.ts"), "export class Later {}");
            expectCalls(4);
            Files.writeString(root.resolve("sub/Cargo.toml"), "[package]\nname = \"x\"\n");
            expectCalls(5);
            Files.writeString(root.resolve("package.json"), "{\"name\": \"y\"}");
            expectCalls(6);
            Files.delete(root.resolve("Main.java"));
            expectCalls(7);
            deleteTree(root.resolve("sub"));
            expectCalls(8);
        }
    }

    /**
     * A change behind a symbolic link to a directory outside the tree is not watched (the walker does not follow links
     * either), while a change inside the tree is.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "symbolic links need extra privileges")
    void doesNotFollowLinksOutOfTheTree(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.toRealPath().resolve("root"));
        Path outside = Files.createDirectories(tmp.toRealPath().resolve("outside"));
        Files.createSymbolicLink(root.resolve("link"), outside);
        try (SourceWatcher w = new SourceWatcher(root, new SourceAnalyzer()::isSource, calls::incrementAndGet)) {
            w.start();
            Files.writeString(outside.resolve("X.java"), "class X {}");
            expectCalls(0);
            Files.writeString(root.resolve("Y.java"), "class Y {}");
            expectCalls(1);
        }
    }

    /** Deletes {@code dir} and everything in it, deepest first. */
    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
