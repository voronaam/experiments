package dev.codecity.github;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs short git commands and captures their output. Every failure (git missing, non-zero exit, not a repository)
 * becomes an empty result, so callers can treat "no git" and "no information" the same way. Output is decoded as
 * UTF-8, with invalid bytes replaced by U+FFFD, as in the other editions.
 */
public final class Git {

    /** Time limit of {@link #runCommand} (and so of {@link #run}), after which the command is killed. */
    private static final long TIMEOUT_SECONDS = 30;

    private Git() {
    }

    /**
     * Output of {@code git -C dir args...}, stripped of surrounding whitespace, or empty if git fails or is
     * unavailable. {@code -C} makes git behave as if started in {@code dir}, whatever the JVM's working directory.
     */
    public static Optional<String> run(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        return runCommand(dir, cmd);
    }

    /**
     * Output of an arbitrary command, or empty if it fails, is not installed or takes longer than 30 seconds.
     *
     * <p>stderr is discarded rather than piped, so a chatty command cannot fill an unread pipe and stall. stdout is
     * read on a separate thread, so the limit also covers a command that hangs while keeping stdout open: when it
     * runs out the process is killed, which closes the pipe and ends the read.
     */
    static Optional<String> runCommand(Path dir, List<String> cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            InputStream stdout = p.getInputStream();
            FutureTask<byte[]> read = new FutureTask<>(stdout::readAllBytes);
            Thread.ofVirtual().start(read);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            String out = new String(read.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            return p.exitValue() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException | ExecutionException e) {
            return Optional.empty();
        } catch (TimeoutException e) {
            p.destroyForcibly();
            return Optional.empty();
        } catch (InterruptedException e) {
            if (p != null) {
                p.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Runs git with the terminal attached (stdin, stdout and stderr inherited) so the user sees clone/fetch progress
     * and can answer credential prompts. Unlike {@link #runCommand} it waits without a time limit.
     *
     * @throws IOException if git cannot be started or exits with a non-zero code
     */
    static void interactive(List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(args);
        try {
            int exit = new ProcessBuilder(cmd).inheritIO().start().waitFor();
            if (exit != 0) {
                throw new IOException("git " + String.join(" ", args) + " failed with exit code " + exit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
