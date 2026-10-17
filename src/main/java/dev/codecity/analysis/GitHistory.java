package dev.codecity.analysis;

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

/** Mines {@code git log} once for per-file churn, author count and recency. */
public final class GitHistory {

    private GitHistory() {
    }

    private static final class Acc {
        int commits;
        final Set<String> authors = new HashSet<>();
        long last = Long.MIN_VALUE;
        long first = Long.MAX_VALUE;
    }

    /** Returns an empty map when {@code root} is not inside a git work tree or git is unavailable. */
    public static Map<Path, FileHistory> load(Path root) {
        try {
            Process top = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--show-toplevel")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String topLevel = new String(top.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!top.waitFor(10, TimeUnit.SECONDS) || top.exitValue() != 0 || topLevel.isEmpty()) {
                return Map.of();
            }
            Path topPath = Path.of(topLevel).toRealPath();

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
                        int sep = line.indexOf('\u001f');
                        time = Long.parseLong(line.substring(1, sep));
                        author = line.substring(sep + 1);
                    } else if (!line.isBlank()) {
                        Acc acc = files.computeIfAbsent(line, k -> new Acc());
                        acc.commits++;
                        acc.authors.add(author);
                        acc.last = Math.max(acc.last, time);
                        acc.first = Math.min(acc.first, time);
                    }
                }
            }
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
