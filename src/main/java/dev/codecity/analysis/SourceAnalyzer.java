package dev.codecity.analysis;

import java.io.IOException;
import java.nio.file.FileVisitResult;
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
     * Path, modification time and size of every manifest seen by the previous analysis, sorted by path. Any difference
     * (edited, added, removed) empties {@link #cache}.
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
        // so start over. Manifests rarely change, so dropping every language's parses is good enough.
        List<String> stamps = new ArrayList<>();
        for (Path m : manifests) {
            try {
                stamps.add(m + "\0" + Files.getLastModifiedTime(m).toMillis() + "\0" + Files.size(m));
            } catch (IOException e) {
                stamps.add(m.toString());
            }
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

    /** True for files some language analyser understands; used by the watcher. */
    public boolean isSource(Path file) {
        return supportFor(file) != null;
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
     * {@code manifests}. Symbolic links to directories are not followed (the default of
     * {@link Files#walkFileTree}), which also rules out cycles; unreadable entries are skipped silently. The root
     * itself is never treated as ignored, so pointing the tool at e.g. {@code build/} still works.
     */
    private List<Path> findSources(Path root, List<Path> manifests) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && isIgnoredDir(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
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
     * <p>Never throws: an unreadable file and an analyser crash both yield an empty, failed result, so one bad file
     * cannot abort the whole city. Those two outcomes are not cached and are retried on the next analysis, whereas
     * a file that merely has syntax errors is cached like any other.
     */
    private LanguageSupport.ParsedFile parseCached(LanguageSupport lang, Path root, Path file) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            CacheEntry cached = cache.get(file);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached.result();
            }
            LanguageSupport.ParsedFile result = lang.parse(root, file, SourceText.read(file));
            cache.put(file, new CacheEntry(modified, size, result));
            return result;
        } catch (IOException e) {
            return new LanguageSupport.ParsedFile(file, List.of(), null, true);
        } catch (RuntimeException e) {
            // A bug in an analyser, not a syntax error in the file: report it (once) rather than hide it.
            if (reportedFailure.compareAndSet(false, true)) {
                System.err.println("warning: could not analyse " + file + ": " + e);
            }
            return new LanguageSupport.ParsedFile(file, List.of(), null, true);
        }
    }
}
