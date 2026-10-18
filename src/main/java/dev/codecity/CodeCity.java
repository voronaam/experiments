package dev.codecity;

import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.desktop.DesktopApp;
import dev.codecity.github.GitHubInfo;
import dev.codecity.github.GitHubRepo;
import dev.codecity.github.PullRequests;
import dev.codecity.github.RepoCloner;
import dev.codecity.history.CityHistory;
import dev.codecity.server.CityServer;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Command-line entry point. It parses the options, then runs the whole pipeline once:
 *
 * <pre>
 *   [clone GitHub repo] → walk + parse/analyse → link dependencies → git history → layout → CityModel
 *                                                                                                │
 *                  ┌──────────────────────────────┬──────────────────────────────────────────────┤
 *            --export FILE                  (default)                                        --gui
 *        HtmlExporter (JSON inlined)   CityServer (JSON over HTTP) + browser    DesktopApp (reads the model directly)
 * </pre>
 *
 * Everything up to the {@link CityModel} is done by {@link CityModel#build}; this class only decides which viewer
 * gets the result. With {@code --watch}, a {@link SourceWatcher} calls {@code CityModel.build} again on its own
 * background thread whenever a source file changes and hands the new model to the running viewer, which swaps it in.
 * The GitHub context (remote, permalink ref, open pull requests) is fetched only once at start-up and reused for every
 * rebuild. With {@code --history}, {@code CityModel.build} also computes the history replay between the git history
 * and the layout; it is computed once, which is why it cannot be combined with {@code --watch}.
 */
public final class CodeCity {

    private static final String USAGE = """
            CodeCity — explore a Java, TypeScript or Rust code base as an interactive 3D city

            Usage: codecity [options] [path | github-url]

              path              source tree to analyse (default: current directory)
              github-url        clone and analyse a GitHub repository, e.g.
                                  https://github.com/google/gson
                                  https://github.com/owner/repo/tree/branch
                                  github:owner/repo[@branch]
              -g, --gui         open a native desktop window instead of the browser viewer
              -p, --port N      port for the local viewer (default: 7070)
              -w, --watch       re-analyse and live-update the viewer when files change
              --no-tests        leave test classes out of the city
              --no-git          skip mining git history (churn, authors, recency)
              --no-github       no GitHub links or pull-request overlay
              --no-open         don't open a browser
              --export FILE     write a self-contained HTML file and exit
              --history[=N]     replay the city's growth over N sampled commits (default 60)
              -h, --help        show this help

            Metaphor: packages / directories / modules are districts; classes, interfaces, structs,
            traits and enums are buildings (plus one "module" building per file of free functions).
              height    = number of methods     (switchable: LOC, complexity, attributes)
              footprint = number of attributes
              colour    = git churn / complexity / recency / authors / open PRs / type kind

            GitHub: repositories with a GitHub remote get links to files, blame and history, and an
            overlay of the open pull requests touching each class. Set GH_TOKEN (or log in with the
            gh CLI) to fetch every open PR; without a token only the most recent ~30 are loaded.
            """;

    /**
     * The parsed command line; {@code history} is the number of replay snapshots, 0 without {@code --history}.
     * Exactly one of {@code root} (a local directory, already resolved to its real path) and
     * {@code remote} (a GitHub repository still to be cloned) is set; {@link #withRoot} turns the latter into the
     * former once the clone exists.
     */
    record Options(Path root, GitHubRepo remote, int port, boolean watch, boolean includeTests, boolean git,
                   boolean github, boolean open, Path export, boolean gui, int history) {
        Options withRoot(Path dir) {
            return new Options(dir, null, port, watch, includeTests, git, github, open, export, gui, history);
        }
    }

    /**
     * Runs the tool. Exit codes: 0 on success (or after {@code --help}), 1 when a clone or the desktop window cannot be
     * set up, 2 for a bad command line. In server mode this method never returns: the main thread parks forever and
     * the process ends with Ctrl+C, which runs the shutdown hook that closes the server.
     */
    public static void main(String[] args) throws Exception {
        Options opts;
        try {
            opts = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage() + "\n");
            System.err.print(USAGE);
            System.exit(2);
            return;
        }
        if (opts == null) {
            System.out.print(USAGE);
            return;
        }

        if (opts.remote() != null) {
            try {
                opts = opts.withRoot(RepoCloner.cloneOrUpdate(opts.remote()).toRealPath());
            } catch (IOException e) {
                System.err.println("error: " + e.getMessage());
                System.exit(1);
                return;
            }
        }
        GitHubInfo github = opts.github() ? loadGitHub(opts.root()) : null;

        // One analyser for the first build and every watch-mode rebuild; its isSource() also tells the watcher which
        // file changes matter.
        SourceAnalyzer analyzer = new SourceAnalyzer();
        System.out.println("Analysing " + opts.root() + " …");
        // An effectively final copy, so the watcher lambdas below can capture it.
        Options o = opts;
        CityModel model = CityModel.build(analyzer, o.root(), o.includeTests(), o.git(), github, o.history());
        printSummary(model);
        if (model.types().isEmpty()) {
            System.err.println("No Java, TypeScript or Rust types found under " + opts.root());
        }

        if (opts.export() != null) {
            HtmlExporter.export(model, opts.export());
            System.out.println("Wrote " + opts.export().toAbsolutePath());
            return;
        }

        if (opts.gui()) {
            runDesktop(o, analyzer, model, github);
            return;
        }

        CityServer server = new CityServer(model, opts.port());
        server.start();
        System.out.println("CodeCity is live at " + server.url() + "  (Ctrl+C to stop)");

        if (opts.watch()) {
            // The callback runs on the watcher's own (debounced) thread. A failed rebuild only logs: the viewer keeps
            // showing the last good city.
            SourceWatcher watcher = new SourceWatcher(o.root(), analyzer::isSource, () -> {
                try {
                    CityModel next = CityModel.build(analyzer, o.root(), o.includeTests(), o.git(), github);
                    server.update(next);
                    System.out.printf("↻ rebuilt city: %d types in %d ms%n", next.types().size(), next.buildMillis());
                } catch (IOException | RuntimeException e) {
                    System.err.println("rebuild failed: " + e.getMessage());
                }
            });
            watcher.start();
            System.out.println("Watching for changes …");
        }
        if (opts.open()) {
            openBrowser(server.url());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        // Joining the current thread never returns; the HTTP server and watcher do their work on other threads.
        Thread.currentThread().join();
    }

    /**
     * Parses the command line. Returns null for {@code --help}; throws {@link IllegalArgumentException} with a message
     * for the user on any error.
     *
     * <p>A positional argument is treated as a GitHub repository only when it is not an existing directory, so a
     * local folder that happens to be called e.g. {@code github:x} still wins. A local path defaults to the current
     * directory and is resolved to its real path (symlinks followed).
     */
    static Options parse(String[] args) {
        Path root = null;
        GitHubRepo remote = null;
        boolean github = true;
        boolean gui = false;
        int port = 7070;
        boolean watch = false;
        boolean tests = true;
        boolean git = true;
        boolean open = true;
        Path export = null;
        int history = 0;
        List<String> rest = new ArrayList<>(List.of(args));
        while (!rest.isEmpty()) {
            String a = rest.removeFirst();
            switch (a) {
                case "-h", "--help" -> {
                    return null;
                }
                case "-p", "--port" -> {
                    port = Integer.parseInt(require(rest, a));
                    if (port < 0 || port > 65535) {
                        throw new IllegalArgumentException("invalid port");
                    }
                }
                case "-w", "--watch" -> watch = true;
                case "-g", "--gui" -> gui = true;
                case "--no-tests" -> tests = false;
                case "--no-git" -> git = false;
                case "--no-github" -> github = false;
                case "--no-open" -> open = false;
                case "--export" -> export = Path.of(require(rest, a));
                // Only the attached form takes a count, so "--history path" never mistakes the path for one.
                case "--history" -> history = CityHistory.DEFAULT_SNAPSHOTS;
                default -> {
                    if (a.startsWith("--history=")) {
                        history = snapshotCount(a.substring("--history=".length()));
                        continue;
                    }
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option " + a);
                    }
                    if (root != null || remote != null) {
                        throw new IllegalArgumentException("only one path may be given");
                    }
                    Optional<GitHubRepo> repo = Files.isDirectory(Path.of(a)) ? Optional.empty() : GitHubRepo.parse(a);
                    if (repo.isPresent()) {
                        remote = repo.get();
                    } else {
                        root = Path.of(a);
                    }
                }
            }
        }
        // A replay is computed once; a live rebuild would have to redo it on every change.
        if (history > 0 && watch) {
            throw new IllegalArgumentException("--history cannot be combined with --watch");
        }
        if (remote != null) {
            return new Options(null, remote, port, watch, tests, git, github, open, export, gui, history);
        }
        Path dir = root == null ? Path.of(".") : root;
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        try {
            dir = dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot resolve " + dir + ": " + e.getMessage());
        }
        return new Options(dir, null, port, watch, tests, git, github, open, export, gui, history);
    }

    /** The N of {@code --history=N}: a whole number from 2 to 500. */
    private static int snapshotCount(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n >= CityHistory.MIN_SNAPSHOTS && n <= CityHistory.MAX_SNAPSHOTS) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalArgumentException("--history needs a number of snapshots from "
                + CityHistory.MIN_SNAPSHOTS + " to " + CityHistory.MAX_SNAPSHOTS);
    }

    /**
     * Native window mode: no HTTP server and no browser; blocks until the window is closed. The watcher is started
     * before the window, because {@link DesktopApp#launch} does not return until the window closes;
     * {@link DesktopApp#update} hands each rebuilt model over to the JavaFX thread. {@code System.exit} at the end
     * also stops the watcher's thread.
     */
    private static void runDesktop(Options o, SourceAnalyzer analyzer, CityModel model, GitHubInfo github)
            throws IOException {
        if (!DesktopApp.displayAvailable()) {
            System.err.println("error: no graphical display found (DISPLAY / WAYLAND_DISPLAY unset). "
                    + "Run without --gui to use the browser viewer.");
            System.exit(1);
        }
        if (o.watch()) {
            SourceWatcher watcher = new SourceWatcher(o.root(), analyzer::isSource, () -> {
                try {
                    CityModel next = CityModel.build(analyzer, o.root(), o.includeTests(), o.git(), github);
                    DesktopApp.update(next);
                    System.out.printf("↻ rebuilt city: %d types in %d ms%n", next.types().size(), next.buildMillis());
                } catch (IOException | RuntimeException e) {
                    System.err.println("rebuild failed: " + e.getMessage());
                }
            });
            watcher.start();
            System.out.println("Watching for changes …");
        }
        System.out.println("Opening the CodeCity window …");
        DesktopApp.launch(model);
        System.exit(0);
    }

    /**
     * Detects the GitHub remote of {@code root} and fetches its open pull requests. Returns null when there is no
     * GitHub remote (the viewers then show no GitHub links at all), and a {@link GitHubInfo} without pulls when the
     * fetch failed (links, but no PR overlay).
     */
    private static GitHubInfo loadGitHub(Path root) {
        Optional<GitHubInfo> detected = GitHubInfo.detect(root);
        if (detected.isEmpty()) {
            return null;
        }
        GitHubInfo info = detected.get();
        System.out.println("GitHub: " + info.repo().webUrl() + " — fetching open pull requests …");
        PullRequests.Result pulls = PullRequests.fetch(info.repo());
        System.out.println("  loaded " + pulls.source());
        // A failed fetch should not look like "no open PRs": leave the PR features off instead.
        return pulls.source().equals("unavailable") ? info : info.withPulls(pulls.pulls(), pulls.source());
    }

    private static String require(List<String> rest, String option) {
        if (rest.isEmpty()) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return rest.removeFirst();
    }

    private static void printSummary(CityModel model) {
        var a = model.analysis();
        var stats = model.stats();
        String languages = stats.languages().entrySet().stream()
                .map(e -> e.getKey().displayName + " " + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        System.out.printf("  %d files, %d types%s, %d packages%s%s — %d ms%n",
                a.files(), a.types().size(), languages.isEmpty() ? "" : " (" + languages + ")", stats.packages(),
                a.failedFiles() > 0 ? ", " + a.failedFiles() + " with parse errors" : "",
                a.history().isEmpty() ? "" : ", git history for " + a.history().size() + " files",
                model.buildMillis());
    }

    /**
     * Opens {@code url} in the default browser: through AWT's {@link Desktop} where it is supported, otherwise with
     * the platform's own launcher ({@code open}, {@code rundll32} or {@code xdg-open}). Failures are ignored, since
     * the URL has already been printed.
     */
    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // fall through to the platform launcher
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd = os.contains("mac") ? List.of("open", url)
                : os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : List.of("xdg-open", url);
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ignored) {
            // no browser available; the URL is printed above
        }
    }
}
