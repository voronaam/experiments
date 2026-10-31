package dev.codecity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole command-line program, run as a separate JVM: exit codes, what goes to stdout and stderr, {@code --export}
 * end to end, the flags that switch git, GitHub, tests and the browser off, and server mode. The Rust
 * ({@code rust/tests/cli.rs}) and D ({@code app.d}) editions run the same scenarios with the same expectations.
 *
 * <p>Every run passes {@code --no-github} (the fixtures live inside this repository, whose GitHub remote would
 * otherwise be asked for pull requests over the network) and, unless git is the point, {@code --no-git}.
 */
class CodeCityCliTest {

    private static final Path TESTDATA = Path.of("testdata").toAbsolutePath();

    /** The usage text, the same for all three editions. */
    private static String usage() throws Exception {
        return Files.readString(TESTDATA.resolve("cli/usage.txt"));
    }

    /** What a finished run left: its exit code and everything it printed. */
    private record Result(int code, String out, String err) {
    }

    /**
     * The command line that starts the program in a new JVM, headless, with the test's own class path (which holds the
     * main classes and their dependencies).
     */
    private static List<String> command(String... args) {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), CodeCity.class.getName()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    /** Runs the program to its end with {@code env} edited by {@code changeEnv}; output goes through temp files. */
    private static Result run(Path tmp, Consumer<Map<String, String>> changeEnv, String... args) throws Exception {
        Path out = Files.createTempFile(tmp, "out", ".txt");
        Path err = Files.createTempFile(tmp, "err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(command(args)).redirectOutput(out.toFile()).redirectError(err.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(System.getProperty("os.name")
                        .startsWith("Windows") ? "NUL" : "/dev/null")));
        changeEnv.accept(pb.environment());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the program did not finish");
        return new Result(p.exitValue(), Files.readString(out), Files.readString(err));
    }

    private static Result run(Path tmp, String... args) throws Exception {
        return run(tmp, env -> { }, args);
    }

    /** {@code -h} and {@code --help} print the usage text to stdout and exit with 0. */
    @Test
    void helpPrintsTheUsageAndSucceeds(@TempDir Path tmp) throws Exception {
        for (String h : new String[]{"-h", "--help"}) {
            Result r = run(tmp, h);
            assertEquals(new Result(0, usage(), ""), r);
        }
    }

    /**
     * A bad command line exits with 2 and prints one {@code error:} line, a blank line and the usage text to stderr,
     * nothing to stdout.
     */
    @Test
    void usageErrorsExitWithTwo(@TempDir Path tmp) throws Exception {
        String d = tmp.toString();
        Path file = Files.writeString(tmp.resolve("file.txt"), "x");
        String[][] cases = {
                {"unknown option --bogus", "--bogus", d},
                {"only one path may be given", d, d},
                {"not a directory: " + file, file.toString()},
                {"-p needs a value", d, "-p"},
                {"invalid port", "--port", "x", d},
                {"--history needs a number of snapshots from 2 to 500", "--history=1", d},
                {"--history cannot be combined with --watch", "--history", "--watch", d},
        };
        for (String[] c : cases) {
            Result r = run(tmp, java.util.Arrays.copyOfRange(c, 1, c.length));
            assertEquals(new Result(2, "", "error: " + c[0] + "\n\n" + usage()), r, c[0]);
        }
    }

    /** Control characters from a path given by the user (or a hostile repository) are printed as '?'. */
    @Test
    void errorMessagesAreSanitised(@TempDir Path tmp) throws Exception {
        String evil = tmp.resolve("x\u001b]0;pwned\u0007\u001b[31m").toString();
        Result r = run(tmp, evil);
        assertEquals(2, r.code());
        assertTrue(r.err().startsWith("error: not a directory: " + tmp.resolve("x?]0;pwned??[31m") + "\n"), r.err());
        assertFalse(r.err().contains("\u001b") || r.err().contains("\u0007"), r.err());
    }

    /**
     * {@code --export} analyses, prints the summary line and the absolute path written, exits with 0 and leaves a
     * self-contained page: the Content-Security-Policy filled in and the city JSON inlined. {@code --no-tests} leaves
     * the test classes out of that city.
     */
    @Test
    void exportWritesThePageAndSucceeds(@TempDir Path tmp) throws Exception {
        Path project = TESTDATA.resolve("java-project");
        Path out = tmp.resolve("sub/city.html");
        Result r = run(tmp, "--export", out.toString(), "--no-git", "--no-github", "--no-open", project.toString());
        assertEquals(0, r.code(), r.err());
        assertEquals("", r.err());
        assertTrue(Pattern.compile("\\AAnalysing " + Pattern.quote(project.toRealPath().toString()) + " …\n"
                        + "  13 files, 20 types \\(Java 20\\), 4 packages, 1 with parse errors — \\d+ ms\n"
                        + "Wrote " + Pattern.quote(out.toString()) + "\n\\z").matcher(r.out()).find(), r.out());
        String html = Files.readString(out);
        assertTrue(html.contains("<meta http-equiv=\"Content-Security-Policy\""));
        assertTrue(html.contains("'sha256-") && !html.contains("{{inline-script-hashes}}"));
        assertTrue(html.contains("<script id=\"city-data\" type=\"application/json\">{"));
        assertTrue(html.contains("\"com.acme.core.CircleTest\""));

        Result noTests = run(tmp, "--export", out.toString(), "--no-tests", "--no-git", "--no-github",
                project.toString());
        assertEquals(0, noTests.code(), noTests.err());
        assertTrue(noTests.out().contains("  13 files, 17 types (Java 17), 4 packages, 1 with parse errors — "),
                noTests.out());
        String withoutTests = Files.readString(out);
        assertFalse(withoutTests.contains("\"com.acme.core.CircleTest\""));
        assertTrue(withoutTests.contains("\"com.acme.core.Circle\""));
    }

    /** A file that cannot be written is a run failure: exit 1, one sanitised {@code error:} line, no usage text. */
    @Test
    void exportFailureExitsWithOne(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("out\u001b[31m.html"));
        Result r = run(tmp, "--export", dir.toString(), "--no-git", "--no-github",
                TESTDATA.resolve("java-project").toString());
        assertEquals(1, r.code());
        String expected = "error: cannot write " + tmp.resolve("out?[31m.html") + ": Is a directory";
        assertTrue(r.err().startsWith(expected), r.err());
        assertEquals(1, r.err().lines().count(), r.err());
    }

    /**
     * {@code --no-git} skips the history (no "git history" in the summary), and {@code --no-github} skips the GitHub
     * remote (no "GitHub:" line and no network) even though the repository has one.
     */
    @Test
    void noGitAndNoGithubSwitchThemOff(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Process make = new ProcessBuilder("sh", TESTDATA.resolve("make-history-repo.sh").toString(), repo.toString())
                .redirectErrorStream(true).start();
        String log = new String(make.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, make.waitFor(), log);
        Process remote = new ProcessBuilder("git", "-C", repo.toString(), "remote", "add", "origin",
                "https://github.com/owner/repo.git").redirectErrorStream(true).start();
        assertEquals(0, remote.waitFor());

        Path out = tmp.resolve("city.html");
        Result withGit = run(tmp, "--export", out.toString(), "--no-github", repo.toString());
        assertEquals(0, withGit.code(), withGit.err());
        assertTrue(withGit.out().contains(", git history for 6 files — "), withGit.out());
        assertFalse(withGit.out().contains("GitHub"), withGit.out());

        Result noGit = run(tmp, "--export", out.toString(), "--no-git", "--no-github", repo.toString());
        assertEquals(0, noGit.code(), noGit.err());
        assertFalse(noGit.out().contains("git history"), noGit.out());
        assertFalse(noGit.out().contains("GitHub"), noGit.out());
    }

    /**
     * Server mode: prints the URL (with the port it got, here one chosen by the system for {@code -p 0}), serves the
     * city there and opens the browser, unless {@code --no-open}. The browser launcher is replaced by a script that
     * records its argument.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void serverModeServesAndOpensTheBrowserUnlessToldNotTo(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path opened = tmp.resolve("opened.txt");
        Path launcher = Files.writeString(bin.resolve("xdg-open"), "#!/bin/sh\necho \"$1\" >> '" + opened + "'\n");
        assertTrue(launcher.toFile().setExecutable(true));
        String project = TESTDATA.resolve("java-project").toString();

        Process p = start(bin, "-p", "0", "--no-git", "--no-github", project);
        try {
            String url = awaitLive(p);
            long end = System.currentTimeMillis() + 20_000;
            while (!(Files.exists(opened) && Files.readString(opened).equals(url + "\n"))
                    && System.currentTimeMillis() < end) {
                Thread.sleep(50);
            }
            assertEquals(url + "\n", Files.readString(opened));
        } finally {
            p.destroyForcibly().waitFor();
        }

        Files.delete(opened);
        p = start(bin, "-p", "0", "--no-open", "--no-git", "--no-github", project);
        try {
            awaitLive(p);
            // The browser would be launched right after the "live" line; give it ample time not to happen.
            Thread.sleep(1000);
            assertFalse(Files.exists(opened), "--no-open opened a browser");
        } finally {
            p.destroyForcibly().waitFor();
        }
    }

    /** Starts the program in server mode with {@code bin} first on the PATH and no display; stderr is discarded. */
    private static Process start(Path bin, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command(args)).redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("PATH", bin + java.io.File.pathSeparator + System.getenv("PATH"));
        pb.environment().remove("DISPLAY");
        pb.environment().remove("WAYLAND_DISPLAY");
        pb.environment().remove("BROWSER");
        return pb.start();
    }

    /**
     * Reads the program's stdout up to its "live" line, checks that the printed URL serves the city and returns the
     * URL.
     */
    private static String awaitLive(Process p) throws Exception {
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        Pattern live = Pattern.compile("^CodeCity is live at (http://localhost:(\\d+)/)  \\(Ctrl\\+C to stop\\)$");
        for (String line; (line = out.readLine()) != null; ) {
            Matcher m = live.matcher(line);
            if (m.matches()) {
                assertTrue(Integer.parseInt(m.group(2)) > 0);
                HttpResponse<String> city = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(m.group(1) + "api/city")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, city.statusCode());
                assertTrue(city.body().contains("\"com.acme.core.Circle\""));
                return m.group(1);
            }
        }
        throw new AssertionError("the server never said it was live");
    }

    /** {@code --gui} without a display is a run failure with a hint, not a crash. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void guiWithoutADisplayExitsWithOne(@TempDir Path tmp) throws Exception {
        Result r = run(tmp, env -> {
            env.remove("DISPLAY");
            env.remove("WAYLAND_DISPLAY");
        }, "--gui", "--no-git", "--no-github", TESTDATA.resolve("java-project").toString());
        assertEquals(1, r.code());
        assertTrue(r.err().startsWith("error: no graphical display found"), r.err());
    }
}
