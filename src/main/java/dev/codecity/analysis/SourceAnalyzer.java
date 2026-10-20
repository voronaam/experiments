package dev.codecity.analysis;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walks a source tree, parses every supported file with its language's analyser (in parallel, cached by modification
 * time so watch-mode rebuilds only re-parse what changed), then links each language's files together.
 *
 * <p>This covers the first three stages of the pipeline:
 * <pre>
 *   walk ──► parse (per file, parallel, cached) ──► link (per language) ──► filter tests ──► {@link Analysis}
 * </pre>
 * Git history, layout and serialisation follow in {@link dev.codecity.CityModel#build}.
 *
 * <p>One instance is meant to live for the whole session: in watch mode the same analyser is called again on every
 * change, and its parse cache is what makes those rebuilds fast. {@link #analyze} is not meant to be called
 * concurrently with itself; watch mode runs rebuilds one after another on a single thread.
 */
public final class SourceAnalyzer {

    /**
     * Directories never descended into: version-control and IDE metadata, build output (Maven, Gradle, Cargo, tsc,
     * bundlers) and installed dependencies. Hidden directories are skipped as well, see {@link #isIgnoredDir}.
     */
    public static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", "target", "build", "out", "node_modules",
            "dist", "coverage", "bower_components");

    /**
     * Manifest file names whose content feeds into the parse of other files: a {@code .rs} file's crate name comes from
     * the nearest {@code Cargo.toml}, a {@code .ts} file's project from the nearest {@code package.json}.
     */
    private static final Set<String> MANIFESTS = Set.of("Cargo.toml", "package.json");

    /**
     * Larger source files are not parsed and count as failed (16 MiB, far beyond any hand-written file). Reading and
     * parsing a file takes memory and time in proportion to its size, so a crafted multi-gigabyte file would otherwise
     * exhaust the heap or stall the analysis.
     */
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    /**
     * A parse result plus the file's modification time (ms) and size when it was parsed. Both must still match for
     * the entry to be reused; comparing the size as well also catches edits that leave the timestamp unchanged.
     */
    private record CacheEntry(long modified, long size, LanguageSupport.ParsedFile result) {
    }

    /** In priority order: a file goes to the first language that accepts it. */
    private final List<LanguageSupport> languages;
    /** Absolute file path → last parse. Concurrent because the parallel parse stream fills it. */
    private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();
    /**
     * Path and parse-relevant content ({@link #manifestStamp}) of every manifest seen by the previous analysis, sorted
     * by path. Any difference (a crate renamed, a manifest added or removed) empties {@link #cache}.
     */
    private List<String> manifestStamps = List.of();
    /** Set after the first analyser crash has been printed, so a systematic bug does not flood the console. */
    private final java.util.concurrent.atomic.AtomicBoolean reportedFailure = new java.util.concurrent.atomic.AtomicBoolean();

    public SourceAnalyzer() {
        this(List.of(new JavaAnalyzer(), new TypeScriptAnalyzer(), new RustAnalyzer()));
    }

    public SourceAnalyzer(List<LanguageSupport> languages) {
        this.languages = List.copyOf(languages);
    }

    /**
     * Analyses everything under {@code root}.
     *
     * <p>Linking always starts from scratch, because a change in one file can change how references in any other
     * file resolve; only the per-file parse is cached. The result's {@code history} is empty (see {@link Analysis}).
     *
     * @param includeTests false drops test types, and with them every dependency edge to or from them
     * @throws IOException when the tree cannot be walked at all; unreadable files only count as failed
     */
    public Analysis analyze(Path root, boolean includeTests) throws IOException {
        List<Path> manifests = new ArrayList<>();
        List<Path> files = findSources(root, manifests);
        // A changed manifest can change the parse of files that did not change themselves (their crate or project),
        // so start over. Manifests rarely change in a way that matters, so dropping every language's parses is good
        // enough. The Rust and D editions use the same stamps.
        List<String> stamps = new ArrayList<>();
        for (Path m : manifests) {
            stamps.add(manifestStamp(m));
        }
        if (!stamps.equals(manifestStamps)) {
            cache.clear();
            manifestStamps = stamps;
        }
        // Forget deleted (or now ignored) files so the cache does not grow without bound in watch mode.
        cache.keySet().retainAll(new HashSet<>(files));

        // The EnumMap links languages in Language declaration order. toList() keeps the encounter order of the sorted
        // file list even though the stream is parallel, so each linker sees its files in path order: results do not
        // depend on thread scheduling.
        Map<Language, List<LanguageSupport.ParsedFile>> byLanguage = new EnumMap<>(Language.class);
        List<Map.Entry<LanguageSupport, LanguageSupport.ParsedFile>> parsed = files.parallelStream()
                .map(f -> {
                    LanguageSupport lang = supportFor(f);
                    return Map.entry(lang, parseCached(lang, root, f));
                })
                .toList();
        int failed = 0;
        Map<Language, LanguageSupport> supports = new LinkedHashMap<>();
        for (var e : parsed) {
            supports.put(e.getKey().language(), e.getKey());
            byLanguage.computeIfAbsent(e.getKey().language(), k -> new ArrayList<>()).add(e.getValue());
            if (e.getValue().failed()) {
                failed++;
            }
        }

        // A TreeMap sorts the types by fqn, as Analysis promises. Should two languages produce the same fqn, the first
        // one wins and the later duplicate is dropped together with its dependencies.
        Map<String, TypeInfo> byFqn = new TreeMap<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (var e : byLanguage.entrySet()) {
            LanguageSupport.Linked linked = supports.get(e.getKey()).link(root, e.getValue());
            for (TypeInfo t : linked.types()) {
                if (byFqn.putIfAbsent(t.fqn(), t) == null) {
                    Set<String> d = linked.dependencies().get(t.fqn());
                    if (d != null) {
                        dependencies.put(t.fqn(), d);
                    }
                }
            }
        }
        // Keep only edges between surviving types, and no self-edges, so every edge has a building at both ends.
        List<TypeInfo> types = byFqn.values().stream().filter(t -> includeTests || !t.test()).toList();
        Set<String> kept = new HashSet<>();
        types.forEach(t -> kept.add(t.fqn()));
        Map<String, Set<String>> deps = new HashMap<>();
        for (TypeInfo t : types) {
            Set<String> out = new HashSet<>(dependencies.getOrDefault(t.fqn(), Set.of()));
            out.retainAll(kept);
            out.remove(t.fqn());
            deps.put(t.fqn(), out);
        }
        return new Analysis(root, types, deps, Map.of(), files.size(), failed);
    }

    /**
     * What a manifest contributes to the parse of other files, as a string that changes exactly when that does: the
     * path plus, for a {@code Cargo.toml}, the crate name it declares (the Rust analyser uses nothing else from it); a
     * {@code package.json} matters only by existing (the TypeScript analyser only looks for one). So an edit that only
     * bumps a version or a dependency keeps the parse cache, which matters for the history replay
     * ({@link dev.codecity.history.CityHistory}), where such edits come with most snapshots.
     */
    private static String manifestStamp(Path manifest) {
        if (manifest.getFileName().toString().equals("Cargo.toml")) {
            return manifest + "\0" + RustAnalyzer.crateName(manifest.getParent());
        }
        return manifest.toString();
    }

    /** True for files some language analyser understands; used by the watcher. */
    public boolean isSource(Path file) {
        return supportFor(file) != null;
    }

    /**
     * True for the manifests ({@code Cargo.toml}, {@code package.json}) whose content feeds into the parse of other
     * files. The history replay writes exactly the files for which this or {@link #isSource} holds.
     */
    public static boolean isManifest(Path file) {
        return file.getFileName() != null && MANIFESTS.contains(file.getFileName().toString());
    }

    /** The analyser for a file, or null if no language accepts it. */
    private LanguageSupport supportFor(Path file) {
        for (LanguageSupport l : languages) {
            if (l.accepts(file)) {
                return l;
            }
        }
        return null;
    }

    /**
     * All supported files under {@code root}, sorted; the {@link #MANIFESTS} found on the way are added, sorted, to
     * {@code manifests}. Symbolic links are not followed (the default of {@link Files#walkFileTree}), neither to
     * directories, which also rules out cycles, nor to files: a link's own attributes say it is not a regular file, so
     * a link to a file outside the tree is never read. FIFOs, devices and sockets are not regular files either, so
     * nothing is opened that could block. Unreadable entries are skipped silently, and so are entries whose name is
     * not valid UTF-8 ({@link #isValidName}). The root itself is never treated as ignored, so pointing the tool at
     * e.g. {@code build/} still works.
     */
    private List<Path> findSources(Path root, List<Path> manifests) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && (isIgnoredDir(dir) || !isValidName(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!isValidName(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isRegularFile() && supportFor(file) != null) {
                    files.add(file);
                } else if (attrs.isRegularFile() && MANIFESTS.contains(file.getFileName().toString())) {
                    manifests.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        manifests.sort(Comparator.naturalOrder());
        return files;
    }

    /**
     * False for a file or directory name that is not valid UTF-8. The JDK decodes such a name with U+FFFD in place of
     * the bad bytes, so the replacement character marks it; a name that really contains U+FFFD is skipped as well,
     * which the Rust and D editions do too, so all three see the same files. Skipping these names keeps undecodable
     * bytes out of paths, package names and the JSON.
     */
    static boolean isValidName(Path p) {
        return p.getFileName() == null || p.getFileName().toString().indexOf('\uFFFD') < 0;
    }

    /** True for {@link #IGNORED_DIRS} and hidden directories (but not "." itself); shared with the watcher. */
    public static boolean isIgnoredDir(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return IGNORED_DIRS.contains(name) || (name.startsWith(".") && name.length() > 1);
    }

    /**
     * Parses a file, or returns the cached result when its modification time and size are unchanged. Runs on the
     * common fork-join pool; concurrent calls for different files are safe because each touches only its own cache
     * entry.
     *
     * <p>Never throws: an unreadable file, a file over {@link #MAX_FILE_BYTES}, a path that is no longer a regular
     * file (replaced by a link or a FIFO since the walk; the attributes are read without following links) and an
     * analyser crash all yield an empty, failed result, so one bad file cannot abort the whole city. Those outcomes
     * are not cached and are retried on the next analysis, whereas a file that merely has syntax errors is cached like
     * any other.
     */
    private LanguageSupport.ParsedFile parseCached(LanguageSupport lang, Path root, Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long modified = attrs.lastModifiedTime().toMillis();
            long size = attrs.size();
            if (!attrs.isRegularFile() || size > MAX_FILE_BYTES) {
                return new LanguageSupport.ParsedFile(file, List.of(), null, true);
            }
            CacheEntry cached = cache.get(file);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached.result();
            }
            LanguageSupport.ParsedFile result = lang.parse(root, file, SourceText.read(file));
            cache.put(file, new CacheEntry(modified, size, result));
            return result;
        } catch (IOException e) {
            return new LanguageSupport.ParsedFile(file, List.of(), null, true);
        } catch (RuntimeException | StackOverflowError e) {
            // A bug in an analyser, not a syntax error in the file: report it (once) rather than hide it. A stack
            // overflow is caught too, as a safety net: the parsers cap their recursion, so none is expected, and the
            // Rust and D editions could not recover from one. Like a panic there, it fails this file only.
            if (reportedFailure.compareAndSet(false, true)) {
                System.err.println(dev.codecity.CodeCity.printable("warning: could not analyse " + file + ": " + e));
            }
            return new LanguageSupport.ParsedFile(file, List.of(), null, true);
        }
    }
}
