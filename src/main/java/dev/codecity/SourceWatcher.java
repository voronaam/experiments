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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Watches the source tree and invokes a callback (debounced) whenever source files change.
 *
 * <p>Drives watch mode: the callback rebuilds the {@link CityModel} from scratch, so the watcher only has to notice
 * that <em>something</em> relevant changed, not what. It uses the JDK {@link WatchService}, which watches single
 * directories, not trees, so every directory is registered on its own (skipping the same directories as the
 * analyser) and new directories are registered as they appear.
 *
 * <h2>Threads</h2>
 * <ul>
 *   <li>{@code source-watcher}: blocks on the watch service, filters events and calls {@link #schedule}.</li>
 *   <li>{@code rebuild}: a single-threaded scheduler that runs the callback. Rebuilds therefore never overlap; a
 *       change during a rebuild schedules another one after it.</li>
 * </ul>
 * Both are daemon threads, so they do not keep the JVM alive.
 */
public final class SourceWatcher implements AutoCloseable {

    /**
     * Quiet period before a rebuild. A save, a branch switch or a formatter run produces bursts of events; each one
     * restarts the timer, so a burst causes a single rebuild once it has been quiet for this long.
     */
    private static final long DEBOUNCE_MS = 400;

    private final Path root;
    private final Runnable onChange;
    private final java.util.function.Predicate<Path> isSource;
    private final WatchService watcher;
    /** Registered directory per watch key; event contexts are names relative to that directory. */
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    /**
     * Every directory ever registered. A deletion event does not say whether a file or a directory went away, and the
     * path can no longer be inspected, so this tells the two apart. It is kept apart from {@link #keys} because a
     * deleted directory's own key may be dropped before its parent reports the deletion.
     */
    private final Set<Path> dirs = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rebuild");
        t.setDaemon(true);
        return t;
    });
    /** The rebuild waiting for its quiet period, if any. Guarded by {@code this} (see {@link #schedule}). */
    private ScheduledFuture<?> pending;

    public SourceWatcher(Path root, java.util.function.Predicate<Path> isSource, Runnable onChange) throws IOException {
        this.root = root;
        this.isSource = isSource;
        this.onChange = onChange;
        this.watcher = FileSystems.getDefault().newWatchService();
    }

    /** Registers the tree (on the calling thread, so it is complete when this returns) and starts watching. */
    public void start() throws IOException {
        registerTree(root);
        Thread.ofPlatform().daemon().name("source-watcher").start(this::loop);
    }

    /**
     * Registers {@code dir} and every sub-directory that is not ignored. Only directories are registered; their
     * file events arrive through the directory's key. Symbolic links to directories are not followed.
     */
    private void registerTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (!d.equals(root) && SourceAnalyzer.isIgnoredDir(d)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                keys.put(d.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), d);
                dirs.add(d);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * The watcher thread: takes one key's batch of events at a time and schedules a rebuild if any event is relevant:
     * an overflow (events were lost, so assume a change), a new directory, a change to a source file or to a
     * {@code Cargo.toml}/{@code package.json} (they decide crate and module names), or the deletion
     * (or move out) of a source file or of a watched directory (which could have held sources). Changes to other files
     * are ignored. Ends when the watch service is closed.
     */
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
                // A new directory (created, moved in, or unpacked) must be registered before its own events are seen.
                // Files created in it before registration are not missed: the rebuild walks the whole tree anyway.
                if (event.kind() == ENTRY_CREATE && Files.isDirectory(child) && !SourceAnalyzer.isIgnoredDir(child)) {
                    try {
                        registerTree(child);
                    } catch (IOException ignored) {
                        // directory vanished again
                    }
                    relevant = true;
                } else if (isSource.test(child) || isManifest(child)
                        || (event.kind() == ENTRY_DELETE && dirs.remove(child))) {
                    relevant = true;
                }
            }
            // reset() re-arms the key for further events; it fails when the directory is gone, so forget the key.
            if (!key.reset()) {
                keys.remove(key);
            }
            if (relevant) {
                schedule();
            }
        }
    }

    /** {@code Cargo.toml} or {@code package.json}: the analysis reads them for crate and module names. */
    private static boolean isManifest(Path file) {
        String name = file.getFileName().toString();
        return name.equals("Cargo.toml") || name.equals("package.json");
    }

    /**
     * Restarts the debounce timer. {@code cancel(false)} only cancels a rebuild that has not started; one already
     * running is allowed to finish, and the new one runs after it on the same thread.
     */
    private synchronized void schedule() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = debouncer.schedule(onChange, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops watching: interrupts a running rebuild and drops pending ones, then closes the watch service, which makes
     * the watcher thread's {@code take()} throw and the loop end.
     */
    @Override
    public void close() throws IOException {
        debouncer.shutdownNow();
        watcher.close();
    }
}
