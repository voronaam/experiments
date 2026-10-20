package dev.codecity.github;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs short git commands and captures their output. Every failure (git missing, non-zero exit, not a repository)
 * becomes an empty result, so callers can treat "no git" and "no information" the same way. Output is decoded as
 * UTF-8, with invalid bytes replaced by U+FFFD, as in the other editions.
 *
 * <p>The repository being analysed is untrusted: its {@code .git/config} can name programs for git to run. Every git
 * command on it is therefore built by {@link #command}, which overrides the keys that matter for the commands
 * CodeCity runs (see {@link #HARDENING} and {@link #NO_TRANSPORT}).
 */
public final class Git {

    /** Time limit of {@link #runCommand} (and so of {@link #run}), after which the command is killed. */
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * Configuration that every git command on an analysed repository gets on its command line. {@code -c} beats every
     * configuration file (also files pulled in with include/includeIf), and git hands it on to the git processes it
     * starts itself. Of the keys that make git run a program, these are the ones the read-only commands CodeCity runs
     * could reach: the fsmonitor hook (any command that refreshes the index), hooks, and {@code log.showSignature},
     * with which {@code git log} runs {@code gpg.program} for every signed commit. The others (pager: stdout is never a
     * terminal; external diff and textconv: no patch output is ever asked for; filters: nothing is checked out) are
     * not reached. An empty core.fsmonitor means "off" in every git version.
     */
    static final List<String> HARDENING = List.of(
            "-c", "core.fsmonitor=", "-c", "core.hooksPath=" + nullDevice(), "-c", "log.showSignature=false");

    /**
     * No transport at all. A repository can claim to be a partial clone, and reading one of its "missing" objects
     * would then make git fetch it from the remote: that runs the remote's upload-pack, ssh, proxy, askpass and
     * credential-helper commands from the configuration. Each built-in transport is named, since a
     * {@code protocol.<name>.allow} in the repository's configuration would beat {@code protocol.allow}. Only the
     * clone cache, whose configuration CodeCity wrote itself, is allowed to fetch ({@link #command}).
     */
    static final List<String> NO_TRANSPORT = List.of(
            "-c", "protocol.allow=never", "-c", "protocol.file.allow=never", "-c", "protocol.git.allow=never",
            "-c", "protocol.ssh.allow=never", "-c", "protocol.http.allow=never", "-c", "protocol.https.allow=never",
            "-c", "protocol.ext.allow=never");

    /** Absolute paths found by {@link #executable}, by program name (only successful lookups are kept). */
    private static final Map<String, Path> EXECUTABLES = new ConcurrentHashMap<>();

    private Git() {
    }

    /**
     * The absolute path of the program {@code name} ("git", "gh") on the {@code PATH}, looked up once
     * ({@link #find}); empty when it is not installed. Programs are always started by this path, never by bare name:
     * Windows would otherwise also look in the current directory, which may be the hostile checkout being analysed
     * (and on any system a relative or empty {@code PATH} entry means the current directory too).
     */
    public static Optional<Path> executable(String name) {
        Path known = EXECUTABLES.get(name);
        if (known != null) {
            return Optional.of(known);
        }
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        String pathext = System.getenv("PATHEXT");
        List<String> extensions = !windows ? List.of("")
                : List.of((pathext == null || pathext.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathext).split(";"));
        Optional<Path> found = find(name, System.getenv("PATH"), File.pathSeparator, extensions, windows);
        found.ifPresent(p -> EXECUTABLES.put(name, p));
        return found;
    }

    /**
     * The first {@code <dir>/<name><extension>} that is a regular file (and, unless {@code windows}, executable), for
     * the entries {@code dir} of {@code path} (split at {@code separator}) in order and each of {@code extensions}.
     * Empty and relative entries (".", "bin", "") are skipped, so the current directory is never searched. The other
     * editions implement the same rules.
     */
    static Optional<Path> find(String name, String path, String separator, List<String> extensions, boolean windows) {
        if (path == null) {
            return Optional.empty();
        }
        for (String entry : path.split(java.util.regex.Pattern.quote(separator))) {
            try {
                if (entry.isEmpty() || !Path.of(entry).isAbsolute()) {
                    continue;
                }
                for (String ext : extensions) {
                    Path candidate = Path.of(entry, name + ext);
                    if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                        return Optional.of(candidate);
                    }
                }
            } catch (java.nio.file.InvalidPathException e) {
                // not a usable directory name: skip it
            }
        }
        return Optional.empty();
    }

    /** {@link #executable}, or an {@link IOException} saying that {@code name} is not installed. */
    static String require(String name) throws IOException {
        return executable(name).orElseThrow(() -> new IOException(name + " not found on the PATH")).toString();
    }

    /**
     * Output of {@code git -C dir args...}, stripped of surrounding whitespace, or empty if git fails or is
     * unavailable. {@code -C} makes git behave as if started in {@code dir}, whatever the JVM's working directory.
     */
    public static Optional<String> run(Path dir, String... args) {
        try {
            return runCommand(command(dir, false, args));
        } catch (IOException e) {
            return Optional.empty(); // git is not installed
        }
    }

    /**
     * A hardened {@code git -C dir args...} ({@link #HARDENING}, plus {@link #NO_TRANSPORT} unless {@code fetch}),
     * with stdin on the null device and stderr discarded; the caller may still redirect either. The environment
     * keeps git from prompting for credentials and, from git 2.44 on, from fetching missing objects lazily.
     * {@code fetch} is only for repositories of the clone cache ({@link RepoCloner#isCached}). git is started by its
     * absolute path ({@link #executable}).
     *
     * @throws IOException if git is not installed
     */
    public static ProcessBuilder command(Path dir, boolean fetch, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(require("git")));
        cmd.addAll(HARDENING);
        if (!fetch) {
            cmd.addAll(NO_TRANSPORT);
        }
        cmd.addAll(List.of("-C", dir.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectInput(ProcessBuilder.Redirect.from(new File(nullDevice())))
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (!fetch) {
            pb.environment().put("GIT_NO_LAZY_FETCH", "1");
        }
        return pb;
    }

    /** The null device: {@code NUL} on Windows, {@code /dev/null} elsewhere. */
    static String nullDevice() {
        return System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null";
    }

    /**
     * Output of a command, or empty if it fails, is not installed or takes longer than 30 seconds.
     *
     * <p>stderr should be discarded (as {@link #command} does) rather than piped, so a chatty command cannot fill an
     * unread pipe and stall. stdout is read on a separate thread, so the limit also covers a command that hangs while
     * keeping stdout open: when it runs out the process is killed, which closes the pipe and ends the read.
     */
    static Optional<String> runCommand(ProcessBuilder command) {
        Process p = null;
        try {
            p = command.start();
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
        List<String> cmd = new ArrayList<>(List.of(require("git")));
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
