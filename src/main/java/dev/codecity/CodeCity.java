package dev.codecity;

import dev.codecity.analysis.JavaAnalyzer;
import dev.codecity.desktop.DesktopApp;
import dev.codecity.github.GitHubInfo;
import dev.codecity.github.GitHubRepo;
import dev.codecity.github.PullRequests;
import dev.codecity.github.RepoCloner;
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

/** Command-line entry point. */
public final class CodeCity {

    private static final String USAGE = """
            CodeCity — explore a Java code base as an interactive 3D city

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
              -h, --help        show this help

            Metaphor: packages are districts, classes are buildings.
              height    = number of methods     (switchable: LOC, complexity, attributes)
              footprint = number of attributes
              colour    = git churn / complexity / recency / authors / open PRs / type kind

            GitHub: repositories with a GitHub remote get links to files, blame and history, and an
            overlay of the open pull requests touching each class. Set GH_TOKEN (or log in with the
            gh CLI) to fetch every open PR; without a token only the most recent ~30 are loaded.
            """;

    record Options(Path root, GitHubRepo remote, int port, boolean watch, boolean includeTests, boolean git,
                   boolean github, boolean open, Path export, boolean gui) {
        Options withRoot(Path dir) {
            return new Options(dir, null, port, watch, includeTests, git, github, open, export, gui);
        }
    }

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

        JavaAnalyzer analyzer = new JavaAnalyzer();
        System.out.println("Analysing " + opts.root() + " …");
        Options o = opts;
        CityModel model = CityModel.build(analyzer, o.root(), o.includeTests(), o.git(), github);
        printSummary(model);
        if (model.types().isEmpty()) {
            System.err.println("No Java types found under " + opts.root());
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
            SourceWatcher watcher = new SourceWatcher(o.root(), () -> {
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
        Thread.currentThread().join();
    }

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
        List<String> rest = new ArrayList<>(List.of(args));
        while (!rest.isEmpty()) {
            String a = rest.removeFirst();
            switch (a) {
                case "-h", "--help" -> {
                    return null;
                }
                case "-p", "--port" -> port = Integer.parseInt(require(rest, a));
                case "-w", "--watch" -> watch = true;
                case "-g", "--gui" -> gui = true;
                case "--no-tests" -> tests = false;
                case "--no-git" -> git = false;
                case "--no-github" -> github = false;
                case "--no-open" -> open = false;
                case "--export" -> export = Path.of(require(rest, a));
                default -> {
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
        if (remote != null) {
            return new Options(null, remote, port, watch, tests, git, github, open, export, gui);
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
        return new Options(dir, null, port, watch, tests, git, github, open, export, gui);
    }

    /** Native window mode: no HTTP server and no browser; blocks until the window is closed. */
    private static void runDesktop(Options o, JavaAnalyzer analyzer, CityModel model, GitHubInfo github)
            throws IOException {
        if (!DesktopApp.displayAvailable()) {
            System.err.println("error: no graphical display found (DISPLAY / WAYLAND_DISPLAY unset). "
                    + "Run without --gui to use the browser viewer.");
            System.exit(1);
        }
        if (o.watch()) {
            SourceWatcher watcher = new SourceWatcher(o.root(), () -> {
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
        long packages = a.types().stream().map(t -> t.module() + ":" + t.packageName()).distinct().count();
        System.out.printf("  %d files, %d types, %d packages%s%s — %d ms%n",
                a.files(), a.types().size(), packages,
                a.failedFiles() > 0 ? ", " + a.failedFiles() + " with parse errors" : "",
                a.history().isEmpty() ? "" : ", git history for " + a.history().size() + " files",
                model.buildMillis());
    }

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
