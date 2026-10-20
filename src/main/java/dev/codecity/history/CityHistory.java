package dev.codecity.history;

import dev.codecity.analysis.Analysis;
import dev.codecity.analysis.GitHistory;
import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.analysis.TypeInfo;
import dev.codecity.github.Git;
import dev.codecity.github.RepoCloner;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The history replay: the city's types and metrics at up to N commits sampled from the first-parent history, from the
 * first commit to HEAD. The viewers play these snapshots back to show the city growing.
 *
 * <p>This is an optional pipeline stage, run by {@link dev.codecity.CityModel#build} after the normal analysis and
 * git history when {@code --history} is given:
 * <pre>
 *   rev-list (first parent) → sample N commits → [prefetch missing blobs] →
 *       per snapshot: update a temp copy of the tree (ls-tree, then diffs) → analyse it → record each type's metrics
 * </pre>
 * The temporary tree mirrors the analysed root, so fqns and paths come out as in the normal analysis. Only files the
 * analysis reads (sources and manifests) are written, their contents streamed from one long-running
 * {@code git cat-file --batch}; after the first snapshot only the files a commit diff names are touched. One
 * {@link SourceAnalyzer} analyses every snapshot, so its parse cache re-parses only what changed. The Rust and D
 * editions follow the same rules and produce the same numbers.
 *
 * <p>A type's git activity in snapshot k (commits, authors, last) is counted over the commits of the normal
 * {@link GitHistory} pass that touched the type's file (as it was named in that snapshot) with an author time not
 * after snapshot k's.
 *
 * <p>The repository is untrusted. Its git commands are hardened ({@link Git#command}); only a clone of the GitHub
 * clone cache may fetch missing objects. Tree entries are only written below the temporary tree when their path is
 * a plain relative one ({@link #safePath}): a crafted tree can hold entries such as ".." or "/abs/dir" that git itself
 * would never check out. A blob larger than {@link SourceAnalyzer#MAX_FILE_BYTES}, which the analysis would not read
 * anyway, is treated like a missing one: its file is absent from that snapshot.
 */
public final class CityHistory {

    /** Snapshots used when {@code --history} is given without a count, and the allowed range for a count. */
    public static final int DEFAULT_SNAPSHOTS = 60;
    public static final int MIN_SNAPSHOTS = 2;
    public static final int MAX_SNAPSHOTS = 500;

    private final List<Snapshot> snapshots;
    private final Map<String, TypeHistory> types;

    private CityHistory(List<Snapshot> snapshots, Map<String, TypeHistory> types) {
        this.snapshots = List.copyOf(snapshots);
        this.types = types;
    }

    /** The snapshots, oldest first; the last one is HEAD. */
    public List<Snapshot> snapshots() {
        return snapshots;
    }

    /** Every type that exists in at least one snapshot, by fqn, sorted. */
    public Map<String, TypeHistory> types() {
        return types;
    }

    /**
     * Indices of the {@code n} commits to sample out of {@code m} (oldest first): all of them when {@code m <= n},
     * otherwise {@code i_k = (2k(m-1) + (n-1)) div (2(n-1))} for {@code k = 0..n-1}, i.e. {@code k(m-1)/(n-1)} rounded
     * half up, in integer arithmetic so every edition picks the same commits. The first (0) and the last ({@code m-1})
     * are always included, and the indices strictly increase because the step {@code (m-1)/(n-1)} is at least 1.
     */
    public static int[] sample(int m, int n) {
        if (m <= n) {
            int[] all = new int[m];
            Arrays.setAll(all, i -> i);
            return all;
        }
        int[] idx = new int[n];
        for (int k = 0; k < n; k++) {
            idx[k] = (int) ((2L * k * (m - 1) + (n - 1)) / (2L * (n - 1)));
        }
        return idx;
    }

    /**
     * Computes the replay for {@code root} with at most {@code n} snapshots. Returns null when there is no git history
     * to replay: git is missing, {@code root} is not inside a work tree, or HEAD has no commits. Progress goes to
     * {@code out}, one line per 10% plus a summary.
     *
     * @param includeTests as in the normal analysis: false leaves test types out of every snapshot
     * @param touches      the raw log of the normal git history pass ({@link GitHistory#touches})
     * @throws IOException when the temporary tree cannot be written or git fails half-way
     */
    public static CityHistory compute(Path root, int n, boolean includeTests, Map<Path, List<GitHistory.Touch>> touches,
                                      PrintStream out) throws IOException {
        long start = System.nanoTime();
        Path top;
        String prefix;
        List<String> commits;
        try {
            top = Path.of(git(root, "rev-parse", "--show-toplevel").strip()).toRealPath();
            // The analysed root's path inside the work tree, with a trailing slash ("" at the top).
            prefix = git(root, "rev-parse", "--show-prefix").strip();
            commits = lines(git(root, "rev-list", "--first-parent", "--reverse", "HEAD"));
        } catch (IOException e) {
            return null;
        }
        if (commits.isEmpty()) {
            return null;
        }
        // Only the clone cache may fetch: the configuration of any other repository could run programs on fetch.
        boolean cached = RepoCloner.isCached(top);
        if (cached) {
            prefetchMissing(top, out);
        }

        int[] picked = sample(commits.size(), n);
        List<String> shas = new ArrayList<>();
        for (int i : picked) {
            shas.add(commits.get(i));
        }
        List<Snapshot> meta = describe(top, shas);
        int count = meta.size();

        SourceAnalyzer analyzer = new SourceAnalyzer();
        Map<String, TypeHistory> types = new TreeMap<>();
        Map<Path, Activity> activity = new HashMap<>();
        List<Snapshot> snapshots = new ArrayList<>();
        // The temporary tree is named after the root, because a crate without Cargo.toml is named after its root
        // directory: the same directory name gives the same fqns as the normal analysis.
        Path tmp = Files.createTempDirectory("codecity-history");
        Path tree = tmp.resolve(root.getFileName() == null ? "root" : root.getFileName().toString());
        Files.createDirectories(tree);
        try (Blobs blobs = new Blobs(top, cached)) {
            Materialiser files = new Materialiser(analyzer, tree, prefix, blobs);
            int reported = 0;
            for (int k = 0; k < count; k++) {
                Snapshot s = meta.get(k);
                if (k == 0) {
                    files.writeAll(top, s.commit());
                } else {
                    files.applyDiff(top, meta.get(k - 1).commit(), s.commit());
                }
                Analysis a = analyzer.analyze(tree, includeTests);
                for (TypeInfo t : a.types()) {
                    TypeHistory h = types.computeIfAbsent(t.fqn(), f -> new TypeHistory(count));
                    // Back in the analysed root: the path git history uses, and what a gone building will show.
                    Path file = root.resolve(tree.relativize(t.file())).normalize();
                    h.nom[k] = t.nom();
                    h.noa[k] = t.noa();
                    h.loc[k] = t.loc();
                    h.cx[k] = t.complexity();
                    Activity act = activity.computeIfAbsent(file, f -> Activity.of(touches.get(f)));
                    int c = act.upTo(s.time());
                    if (c > 0) {
                        h.commits[k] = c;
                        h.authors[k] = act.authors[c - 1];
                        h.last[k] = act.times[c - 1];
                    }
                    h.latest = relocated(t, file);
                    h.latestDeps = a.dependencies().getOrDefault(t.fqn(), Set.of());
                }
                snapshots.add(new Snapshot(s.commit(), s.time(), s.author(), s.subject(), a.types().size()));
                // One line per tenth of the way (not for the last snapshot, which the summary below reports).
                int tenth = (k + 1) * 10 / count;
                if (tenth > reported && k + 1 < count) {
                    reported = tenth;
                    out.printf("  history: %d/%d snapshots …%n", k + 1, count);
                }
            }
        } finally {
            deleteTree(tmp);
        }
        out.printf("  history: %d snapshots from %s to %s — %d ms%n", count, date(snapshots.getFirst().time()),
                date(snapshots.getLast().time()), (System.nanoTime() - start) / 1_000_000);
        return new CityHistory(snapshots, types);
    }

    /** {@code t} with its file moved from the temporary tree to {@code file}. */
    private static TypeInfo relocated(TypeInfo t, Path file) {
        return new TypeInfo(t.language(), t.fqn(), t.name(), t.packageName(), t.module(), t.kind(), file, t.line(),
                t.endLine(), t.loc(), t.noa(), t.test(), t.methods(), t.references(), t.explicitImports(),
                t.wildcardImports());
    }

    private static String date(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    /**
     * One file's commits sorted by author time, for counting "commits up to time T" by binary search.
     * {@code authors[i]} is the number of distinct authors among the first {@code i + 1} commits.
     */
    private record Activity(long[] times, int[] authors) {

        static Activity of(List<GitHistory.Touch> touches) {
            if (touches == null) {
                return new Activity(new long[0], new int[0]);
            }
            List<GitHistory.Touch> sorted = touches.stream()
                    .sorted(Comparator.comparingLong(GitHistory.Touch::time)).toList();
            long[] times = new long[sorted.size()];
            int[] authors = new int[sorted.size()];
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < sorted.size(); i++) {
                times[i] = sorted.get(i).time();
                seen.add(sorted.get(i).author());
                authors[i] = seen.size();
            }
            return new Activity(times, authors);
        }

        /** Number of commits with an author time at or before {@code time}. */
        int upTo(long time) {
            int lo = 0;
            int hi = times.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (times[mid] <= time) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    /**
     * Fetches, in one go, the objects a blobless clone of the GitHub clone cache lacks. Without this, reading old
     * blobs would make git fetch them lazily one request at a time. Works with git 2.34, which has no
     * {@code fetch --refetch}: the missing object ids are fed to a plain fetch on stdin, and the noop negotiation
     * stops git from walking the history to find common commits. Failures are ignored; git then still fetches lazily.
     * Only called for a repository of the cache ({@link RepoCloner#isCached}).
     */
    private static void prefetchMissing(Path top, PrintStream out) {
        try {
            boolean partial = git(top, "config", "--get", "remote.origin.promisor").strip().equals("true");
            if (!partial) {
                return;
            }
        } catch (IOException e) {
            return; // not set: not a partial clone
        }
        try {
            StringBuilder missing = new StringBuilder();
            int n = 0;
            for (String line : lines(git(top, "rev-list", "--objects", "--missing=print", "HEAD"))) {
                if (line.startsWith("?")) {
                    missing.append(line, 1, line.length()).append('\n');
                    n++;
                }
            }
            if (n == 0) {
                return;
            }
            out.printf("  history: fetching %d missing objects …%n", n);
            run(top, true, missing.toString().getBytes(StandardCharsets.UTF_8), "-c", "fetch.negotiationAlgorithm=noop",
                    "fetch", "-q", "origin", "--no-tags", "--no-write-fetch-head", "--recurse-submodules=no",
                    "--filter=blob:none", "--stdin");
        } catch (IOException e) {
            // carry on: missing blobs are then fetched lazily by cat-file
        }
    }

    /**
     * Commit, author time, author name and subject of each sha, in the given order, with one {@code git log} call.
     * {@code types} is left 0 until the snapshot has been analysed.
     */
    private static List<Snapshot> describe(Path top, List<String> shas) throws IOException {
        List<String> args = new ArrayList<>(List.of("log", "--no-walk=unsorted", "--format=%H%x00%at%x00%an%x00%s"));
        args.addAll(shas);
        Map<String, Snapshot> bySha = new HashMap<>();
        // Split on newlines only: String.lines() would also split a subject at a stray carriage return.
        for (String line : git(top, args.toArray(String[]::new)).split("\n")) {
            String[] f = line.split("\0", -1);
            if (f.length == 4) {
                bySha.put(f[0], new Snapshot(f[0], Long.parseLong(f[1]), f[2], f[3], 0));
            }
        }
        List<Snapshot> result = new ArrayList<>();
        for (String sha : shas) {
            Snapshot s = bySha.get(sha);
            if (s == null) {
                throw new IOException("git log did not describe " + sha);
            }
            result.add(s);
        }
        return result;
    }

    /**
     * Whether {@code rel}, a path from a tree listing relative to the analysed root, may be written below the
     * temporary tree: every '/'-separated component is non-empty and neither ".", "..", nor ".git" in any case, and
     * none holds a backslash or a colon (path syntax on Windows). git never checks out anything else, but a crafted
     * tree object can hold such entries, and writing "../../x" or "/home/u/x" would put a file outside the tree.
     */
    static boolean safePath(String rel) {
        for (String part : rel.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.toLowerCase(Locale.ROOT).equals(".git")
                    || part.indexOf('\\') >= 0 || part.indexOf(':') >= 0 || part.indexOf('\0') >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The pathspec ({@code -- :(literal)<dir>}) that limits a tree listing to the analysed root's directory, or
     * nothing at the top of the work tree. Literal, so that glob characters in a directory name are not interpreted.
     */
    private static List<String> pathspec(String prefix) {
        return prefix.isEmpty() ? List.of() : List.of("--", ":(literal)" + prefix.substring(0, prefix.length() - 1));
    }

    /**
     * Keeps the temporary tree in step with the snapshot being analysed. A file is written when the analysis would
     * read it (a source file or a manifest), its path is safe ({@link #safePath}) and its tree entry is a regular file;
     * symbolic links and submodules are skipped. Every written file gets a modification time one millisecond after
     * the previous one, so the analyser's cache (keyed by time and size) re-parses it even when its size did not
     * change.
     */
    private static final class Materialiser {
        private final SourceAnalyzer analyzer;
        private final Path tree;
        private final String prefix;
        private final Blobs blobs;
        /** Modification time (ms) given to the last written file; any fixed start works, it only has to grow. */
        private long clock = 1_000_000_000_000L;

        Materialiser(SourceAnalyzer analyzer, Path tree, String prefix, Blobs blobs) {
            this.analyzer = analyzer;
            this.tree = tree;
            this.prefix = prefix;
            this.blobs = blobs;
        }

        /**
         * Writes every wanted file of {@code commit} under the prefix. {@code ls-tree -z} prints one
         * {@code <mode> <type> <sha>\t<path>} entry per file, NUL-terminated and unquoted.
         */
        void writeAll(Path top, String commit) throws IOException {
            List<String> args = new ArrayList<>(List.of("ls-tree", "-r", "-z", commit));
            args.addAll(pathspec(prefix));
            for (String entry : git(top, args.toArray(String[]::new)).split("\0")) {
                int tab = entry.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                String[] head = entry.substring(0, tab).split(" ");
                apply(head[0], head[2], entry.substring(tab + 1));
            }
        }

        /**
         * Applies the changes between two commits under the prefix. {@code diff-tree -r -z} names the same changes as
         * {@code diff --name-status}, plus the new mode and blob sha:
         * {@code :<old mode> <new mode> <old sha> <new sha> <status>\0<path>\0}. Added, modified and type-changed
         * entries are (re)written by their new mode, deletions removed.
         */
        void applyDiff(Path top, String from, String to) throws IOException {
            List<String> args = new ArrayList<>(List.of("diff-tree", "-r", "-z", "--no-renames", from, to));
            args.addAll(pathspec(prefix));
            String[] parts = git(top, args.toArray(String[]::new)).split("\0");
            for (int i = 0; i + 1 < parts.length; i += 2) {
                String[] head = parts[i].substring(1).split(" ");
                String path = parts[i + 1];
                if (head[4].startsWith("D")) {
                    apply("000000", null, path);
                } else {
                    apply(head[1], head[3], path);
                }
            }
        }

        /**
         * Writes blob {@code sha} at {@code path} (relative to the work tree), or deletes it if it is not wanted.
         * Paths that are not safe ({@link #safePath}) are ignored altogether.
         */
        private void apply(String mode, String sha, String path) throws IOException {
            if (!path.startsWith(prefix) || !safePath(path.substring(prefix.length()))) {
                return;
            }
            Path file = tree.resolve(path.substring(prefix.length()));
            if (!analyzer.isSource(file) && !SourceAnalyzer.isManifest(file)) {
                return;
            }
            byte[] content = mode.equals("100644") || mode.equals("100755") ? blobs.read(sha) : null;
            if (content != null) {
                Files.createDirectories(file.getParent());
                Files.write(file, content);
                Files.setLastModifiedTime(file, FileTime.fromMillis(++clock));
            } else {
                // Deleted, now a symlink or submodule, or its blob is missing or too large.
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * Blob contents from a single {@code git cat-file --batch} process: each request is a sha on stdin, each answer a
     * {@code <sha> blob <size>} header line, the content and a newline, or {@code <sha> missing}.
     */
    private static final class Blobs implements AutoCloseable {
        private final Process process;
        private final OutputStream in;
        private final InputStream out;

        /** {@code fetch}: whether git may fetch missing objects lazily (only in the clone cache). */
        Blobs(Path top, boolean fetch) throws IOException {
            process = Git.command(top, fetch, "cat-file", "--batch").redirectInput(ProcessBuilder.Redirect.PIPE).start();
            in = process.getOutputStream();
            out = new BufferedInputStream(process.getInputStream(), 1 << 16);
        }

        /**
         * The content of blob {@code sha}, or null when it is missing or larger than
         * {@link SourceAnalyzer#MAX_FILE_BYTES} (its content is then skipped unread).
         */
        byte[] read(String sha) throws IOException {
            in.write((sha + "\n").getBytes(StandardCharsets.US_ASCII));
            in.flush();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int c;
            while ((c = out.read()) != '\n') {
                if (c < 0) {
                    throw new IOException("git cat-file ended early");
                }
                header.write(c);
            }
            String[] h = header.toString(StandardCharsets.UTF_8).split(" ");
            if (h.length != 3) {
                return null; // "<sha> missing"
            }
            long size = Long.parseLong(h[2]);
            if (size > SourceAnalyzer.MAX_FILE_BYTES) {
                out.skipNBytes(size + 1); // the content and the newline after it
                return null;
            }
            byte[] content = out.readNBytes((int) size);
            out.read(); // the newline after the content
            return content;
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException ignored) {
                // the process is going away anyway
            }
            process.destroy();
        }
    }

    /** Deletes a directory tree, ignoring failures (the system cleans its temp directory eventually). */
    private static void deleteTree(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ git

    /**
     * Stdout of the hardened {@code git -C dir args...} ({@link Git#command}), decoded as UTF-8; throws when git
     * cannot run or exits non-zero.
     */
    private static String git(Path dir, String... args) throws IOException {
        return run(dir, false, null, args);
    }

    /**
     * Runs the hardened git ({@link Git#command}; {@code fetch} only for the clone cache) with {@code stdin} (or
     * nothing) as input and returns its stdout. Stdin is written on its own thread so that a command printing while
     * it reads cannot deadlock against us; stderr is discarded.
     */
    private static String run(Path dir, boolean fetch, byte[] stdin, String... args) throws IOException {
        ProcessBuilder command = Git.command(dir, fetch, args);
        if (stdin != null) {
            command.redirectInput(ProcessBuilder.Redirect.PIPE);
        }
        Process p = command.start();
        Thread feeder = Thread.ofVirtual().start(() -> {
            try (OutputStream os = p.getOutputStream()) {
                if (stdin != null) {
                    os.write(stdin);
                }
            } catch (IOException ignored) {
                // git exited early; its exit code tells
            }
        });
        byte[] output = p.getInputStream().readAllBytes();
        try {
            feeder.join();
            if (p.waitFor() != 0) {
                throw new IOException("git " + args[0] + " failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted", e);
        }
        return new String(output, StandardCharsets.UTF_8);
    }

    private static List<String> lines(String text) {
        return text.lines().filter(l -> !l.isEmpty()).toList();
    }
}
