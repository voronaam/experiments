package dev.codecity.analysis;

import dev.codecity.github.Git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Mines {@code git log} once for per-file churn, author count and recency.
 *
 * <p>This is the "git history" stage of the pipeline. It runs after the analysis, from
 * {@link dev.codecity.CityModel#build}, and is independent of it: the result is keyed by file, and every type declared
 * in a file shares that file's history. It shells out to the {@code git} executable rather than using a library, so
 * it needs git on the {@code PATH}; without it (or outside a work tree) the city simply has no history metrics.
 *
 * <p>A single {@code git log --name-only} pass is enough because each commit lists every file it touched, so all
 * files are counted in one stream, however many there are.
 */
public final class GitHistory {

    private GitHistory() {
    }

    /** Running totals for one file while the log is streamed. */
    private static final class Acc {
        int commits;
        final Set<String> authors = new HashSet<>();
        // Sentinels for max/min; an Acc is only created for a file that has at least one commit, so they never leak.
        long last = Long.MIN_VALUE;
        long first = Long.MAX_VALUE;
    }

    /**
     * Returns an empty map when {@code root} is not inside a git work tree or git is unavailable.
     *
     * <p>Keys are absolute, normalised paths built from the work tree's real path, so they match the paths the
     * analysers report as long as the analysed root itself is a real path (the command line resolves it with
     * {@code toRealPath()}). {@code root} may be a sub-directory of the repository; only its files are logged.
     *
     * <p>Every failure (git missing, a timeout, an unexpected line) degrades to "no history" instead of failing the
     * build. The exit status of {@code git log} is not checked, so whatever was read before it ended is used.
     */
    public static Map<Path, FileHistory> load(Path root) {
        try {
            // git log prints paths relative to the top of the work tree, not to root, so find the top first.
            String topLevel = Git.run(root, "rev-parse", "--show-toplevel").orElse("");
            if (topLevel.isEmpty()) {
                return Map.of();
            }
            Path topPath = Path.of(topLevel).toRealPath();

            // --no-merges: only ordinary commits count, merge commits are skipped.
            // --no-renames: a renamed file starts a fresh history under its new name (no rename detection cost).
            // The format puts a record separator (0x1e), then the author time in epoch seconds, a unit separator
            // (0x1f) and the author e-mail (%aE applies .mailmap) on one line, followed by the commit's file names.
            // These control characters do not appear in e-mail addresses, and git quotes file names containing them.
            // core.quotepath=off prints non-ASCII file names as they are instead of as quoted octal escapes. The
            // reader replaces bytes that are not valid UTF-8 (in a file name or an e-mail) with U+FFFD and goes on.
            // The pathspecs ("*" also matches "/" in git) restrict the log to files the analysers can read, and,
            // because they are relative to root, to the analysed sub-tree.
            Process log = new ProcessBuilder("git", "-C", root.toString(), "-c", "core.quotepath=off",
                    "log", "--no-merges", "--no-renames", "--format=%x1e%at%x1f%aE", "--name-only", "--",
                    "*.java", "*.ts", "*.tsx", "*.mts", "*.cts", "*.rs")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Map<String, Acc> files = new HashMap<>();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(log.getInputStream(), StandardCharsets.UTF_8))) {
                long time = 0;
                String author = "";
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("\u001e")) {
                        // Commit header: remember who and when for the file names that follow.
                        int sep = line.indexOf('\u001f');
                        time = Long.parseLong(line.substring(1, sep));
                        author = line.substring(sep + 1);
                    } else if (!line.isBlank()) {
                        // A file changed by the current commit (git separates the list from the header by a blank).
                        Acc acc = files.computeIfAbsent(line, k -> new Acc());
                        acc.commits++;
                        acc.authors.add(author);
                        acc.last = Math.max(acc.last, time);
                        acc.first = Math.min(acc.first, time);
                    }
                }
            }
            // The stream is exhausted, so git is finishing; its exit status is not checked.
            log.waitFor(10, TimeUnit.SECONDS);

            Map<Path, FileHistory> result = new HashMap<>();
            files.forEach((rel, acc) -> result.put(topPath.resolve(rel).normalize(),
                    new FileHistory(acc.commits, acc.authors.size(), acc.last, acc.first)));
            return result;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }
}
