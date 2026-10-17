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
 */
public final class SourceAnalyzer {

    public static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", "target", "build", "out", "node_modules",
            "dist", "coverage", "bower_components");

    private record CacheEntry(long modified, long size, LanguageSupport.ParsedFile result) {
    }

    private final List<LanguageSupport> languages;
    private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean reportedFailure = new java.util.concurrent.atomic.AtomicBoolean();

    public SourceAnalyzer() {
        this(List.of(new JavaAnalyzer(), new TypeScriptAnalyzer(), new RustAnalyzer()));
    }

    public SourceAnalyzer(List<LanguageSupport> languages) {
        this.languages = List.copyOf(languages);
    }

    public Analysis analyze(Path root, boolean includeTests) throws IOException {
        List<Path> files = findSources(root);
        cache.keySet().retainAll(new HashSet<>(files));

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

        Map<String, TypeInfo> byFqn = new TreeMap<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (var e : byLanguage.entrySet()) {
            LanguageSupport.Linked linked = supports.get(e.getKey()).link(root, e.getValue());
            for (TypeInfo t : linked.types()) {
                byFqn.putIfAbsent(t.fqn(), t);
            }
            dependencies.putAll(linked.dependencies());
        }
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

    private LanguageSupport supportFor(Path file) {
        for (LanguageSupport l : languages) {
            if (l.accepts(file)) {
                return l;
            }
        }
        return null;
    }

    private List<Path> findSources(Path root) throws IOException {
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
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }

    public static boolean isIgnoredDir(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return IGNORED_DIRS.contains(name) || (name.startsWith(".") && name.length() > 1);
    }

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
