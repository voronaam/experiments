package dev.codecity;

import dev.codecity.analysis.SourceAnalyzer;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/** Watches the source tree and invokes a callback (debounced) whenever source files change. */
public final class SourceWatcher implements AutoCloseable {

    private static final long DEBOUNCE_MS = 400;

    private final Path root;
    private final Runnable onChange;
    private final java.util.function.Predicate<Path> isSource;
    private final WatchService watcher;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rebuild");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending;

    public SourceWatcher(Path root, java.util.function.Predicate<Path> isSource, Runnable onChange) throws IOException {
        this.root = root;
        this.isSource = isSource;
        this.onChange = onChange;
        this.watcher = FileSystems.getDefault().newWatchService();
    }

    public void start() throws IOException {
        registerTree(root);
        Thread.ofPlatform().daemon().name("source-watcher").start(this::loop);
    }

    private void registerTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (!d.equals(root) && SourceAnalyzer.isIgnoredDir(d)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                keys.put(d.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), d);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void loop() {
        while (true) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            Path dir = keys.get(key);
            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == OVERFLOW) {
                    relevant = true;
                    continue;
                }
                Path child = dir == null ? null : dir.resolve((Path) event.context());
                if (child == null) {
                    continue;
                }
                if (event.kind() == ENTRY_CREATE && Files.isDirectory(child) && !SourceAnalyzer.isIgnoredDir(child)) {
                    try {
                        registerTree(child);
                    } catch (IOException ignored) {
                        // directory vanished again
                    }
                    relevant = true;
                } else if (isSource.test(child) || event.kind() == ENTRY_DELETE) {
                    relevant = true;
                }
            }
            if (!key.reset()) {
                keys.remove(key);
            }
            if (relevant) {
                schedule();
            }
        }
    }

    private synchronized void schedule() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = debouncer.schedule(onChange, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException {
        debouncer.shutdownNow();
        watcher.close();
    }
}
