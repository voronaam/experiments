package dev.codecity.github;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Runs short git commands and captures their output. */
final class Git {

    private Git() {
    }

    /** Output of {@code git -C dir args...}, or empty if git fails or is unavailable. */
    static Optional<String> run(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        return runCommand(dir, cmd);
    }

    /** Output of an arbitrary command, or empty if it fails or is not installed. */
    static Optional<String> runCommand(Path dir, List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            return p.exitValue() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Runs git with the terminal attached so the user sees clone/fetch progress. */
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
