//! CodeCity — explore a Java, TypeScript or Rust code base as an interactive 3D city (native Rust edition).
//!
//! The pipeline, and the module for each step:
//!
//! ```text
//!   walk + parse/analyse ─ analysis   (tree-sitter, in parallel, cached per file)
//!   link dependencies    ─ analysis::{java,typescript,rust_lang}::link
//!   git history          ─ git        (runs on its own thread while the sources are parsed)
//!   city model           ─ city       (CityModel::build ties everything together)
//!   layout               ─ layout     (districts and building lots)
//!   JSON                 ─ json, city (the data the web viewer loads)
//!   viewers              ─ server (browser, live updates over SSE), export (one HTML file), gui (native window)
//! ```
//!
//! [`github`] adds clone-by-URL and the pull-request overlay; [`watch`] re-runs the pipeline when files change. The
//! Java and D editions implement the same pipeline and serve the identical web viewer (see [`assets`]).

mod analysis;
mod assets;
mod city;
mod export;
mod git;
mod github;
#[cfg(feature = "gui")]
mod gui;
mod json;
mod layout;
mod model;
mod server;
mod watch;

use analysis::SourceAnalyzer;
use city::CityModel;
use github::{GitHubInfo, GitHubRepo};
use std::path::PathBuf;
use std::sync::Arc;

const USAGE: &str = "\
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
  --no-tests        leave test code out of the city
  --no-git          skip mining git history (churn, authors, recency)
  --no-github       no GitHub links or pull-request overlay
  --no-open         don't open a browser
  --export FILE     write a self-contained HTML file and exit
  -h, --help        show this help

Metaphor: packages / directories / modules are districts; classes, interfaces, structs,
traits and enums are buildings (plus one \"module\" building per file of free functions).
  height    = number of methods     (switchable: LOC, complexity, attributes)
  footprint = number of attributes
  colour    = git churn / complexity / recency / authors / open PRs / type kind

GitHub: repositories with a GitHub remote get links to files, blame and history, and an
overlay of the open pull requests touching each class. Set GH_TOKEN (or log in with the
gh CLI) to fetch every open PR; without a token only the most recent ~30 are loaded.
";

/// The parsed command line; see [`USAGE`]. Cloned into the watch-mode rebuild closure.
#[derive(Clone)]
struct Options {
    /// Canonical source tree to analyse. After [`parse_args`] it is set unless `remote` is; [`run`] fills it in for a
    /// remote after cloning.
    root: Option<PathBuf>,
    /// A GitHub repository to clone (or update) into the cache and analyse instead of a local path.
    remote: Option<GitHubRepo>,
    port: u16,
    watch: bool,
    include_tests: bool,
    git: bool,
    github: bool,
    open: bool,
    export: Option<PathBuf>,
    gui: bool,
}

/// Parses the arguments (without the program name). `Ok(None)` means help was asked for; `Err` is a usage error.
///
/// The single positional argument is a path if it names an existing directory, otherwise it is tried as a GitHub
/// URL or `github:` shorthand; anything that is neither ends up as a path and fails the directory check below. A
/// local root is canonicalised so that paths from the walker, git and the GitHub prefix all agree.
fn parse_args(args: Vec<String>) -> Result<Option<Options>, String> {
    let mut o = Options {
        root: None,
        remote: None,
        port: 7070,
        watch: false,
        include_tests: true,
        git: true,
        github: true,
        open: true,
        export: None,
        gui: false,
    };
    let mut it = args.into_iter();
    while let Some(a) = it.next() {
        // Takes the option's value from the same iterator, so `-p 8080` consumes two arguments.
        let mut value = |name: &str| it.next().ok_or_else(|| format!("{name} needs a value"));
        match a.as_str() {
            "-h" | "--help" => return Ok(None),
            "-p" | "--port" => o.port = value(&a)?.parse().map_err(|_| "invalid port".to_string())?,
            "-w" | "--watch" => o.watch = true,
            "-g" | "--gui" => o.gui = true,
            "--no-tests" => o.include_tests = false,
            "--no-git" => o.git = false,
            "--no-github" => o.github = false,
            "--no-open" => o.open = false,
            "--export" => o.export = Some(PathBuf::from(value(&a)?)),
            s if s.starts_with('-') => return Err(format!("unknown option {s}")),
            s => {
                if o.root.is_some() || o.remote.is_some() {
                    return Err("only one path may be given".into());
                }
                let as_path = PathBuf::from(s);
                // An existing directory wins, even if its name would also parse as a GitHub spec. A GitHub spec with
                // `.`/`..` as owner or name is an error rather than a path.
                match if as_path.is_dir() { None } else { GitHubRepo::parse(s)? } {
                    Some(repo) => o.remote = Some(repo),
                    None => o.root = Some(as_path),
                }
            }
        }
    }
    if o.remote.is_none() {
        let dir = o.root.clone().unwrap_or_else(|| PathBuf::from("."));
        if !dir.is_dir() {
            return Err(format!("not a directory: {}", dir.display()));
        }
        o.root = Some(std::fs::canonicalize(&dir).map_err(|e| format!("cannot resolve {}: {e}", dir.display()))?);
    }
    Ok(Some(o))
}

/// Exit codes: 0 on success or `--help`, 2 for a usage error, 1 when the run itself fails.
fn main() {
    let opts = match parse_args(std::env::args().skip(1).collect()) {
        Ok(Some(o)) => o,
        Ok(None) => {
            print!("{USAGE}");
            return;
        }
        Err(e) => {
            eprintln!("error: {e}\n");
            eprint!("{USAGE}");
            std::process::exit(2);
        }
    };
    if let Err(e) = run(opts) {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

/// Builds the city once, then either exports it, opens the native window, or serves it to the browser until the
/// process is killed. With `--watch`, file changes rebuild the model and push it to the viewer.
fn run(mut opts: Options) -> Result<(), String> {
    if let Some(repo) = &opts.remote {
        let dir = github::clone_or_update(repo)?;
        opts.root = Some(std::fs::canonicalize(&dir).map_err(|e| e.to_string())?);
    }
    // Set by parse_args for a local path, or just above for a remote.
    let root = opts.root.clone().unwrap();
    let github = if opts.github { load_github(&root) } else { None };

    // Shared with the rebuild closure: the analyser's parse cache is what makes watch-mode rebuilds re-parse only the
    // files that changed.
    let analyzer = Arc::new(SourceAnalyzer::new());
    println!("Analysing {} …", root.display());
    let model = Arc::new(CityModel::build(&analyzer, &root, opts.include_tests, opts.git, github.clone()));
    print_summary(&model);
    if model.buildings.is_empty() {
        eprintln!("No Java, TypeScript or Rust types found under {}", root.display());
    }

    if let Some(target) = &opts.export {
        export::export(&model, target).map_err(|e| format!("cannot write {}: {e}", target.display()))?;
        println!("Wrote {}", std::path::absolute(target).unwrap_or(target.clone()).display());
        return Ok(());
    }

    // Produces a fresh model in watch mode. The GitHub info (including the pull requests) is fetched only once at
    // startup and reused for every rebuild.
    let rebuild = {
        let analyzer = analyzer.clone();
        let root = root.clone();
        let o = opts.clone();
        move || {
            let next = CityModel::build(&analyzer, &root, o.include_tests, o.git, github.clone());
            println!("↻ rebuilt city: {} types in {} ms", next.buildings.len(), next.build_millis);
            Arc::new(next)
        }
    };

    if opts.gui {
        #[cfg(feature = "gui")]
        {
            println!("Opening the CodeCity window …");
            // The window does its own watching, so it gets the root to watch and the rebuild closure.
            return gui::run(model, opts.watch.then_some((root, Box::new(rebuild) as Box<dyn Fn() -> Arc<CityModel> + Send>)));
        }
        #[cfg(not(feature = "gui"))]
        return Err("this build has no desktop window (built without the `gui` feature)".into());
    }

    let server = server::CityServer::bind(model, opts.port).map_err(|e| format!("cannot start server: {e}"))?;
    println!("CodeCity is live at {}  (Ctrl+C to stop)", server.url());
    // The watcher stops when dropped, so it is kept in a named binding (`_` alone would drop it at once) for as long
    // as server.run() serves. The callback runs on the watcher's debounce thread, rebuilds there and hands the model
    // to the server.
    let _watcher = if opts.watch {
        let update = server.updater();
        let w = watch::watch(&root, move || update(rebuild())).map_err(|e| format!("cannot watch: {e}"))?;
        println!("Watching for changes …");
        Some(w)
    } else {
        None
    };
    if opts.open {
        // Failing to open a browser is not fatal: the URL has been printed.
        let _ = open_browser(&server.url());
    }
    server.run();
    Ok(())
}

/// GitHub context for `root`, or None when it has no GitHub remote. Pull requests are fetched here, once.
fn load_github(root: &std::path::Path) -> Option<GitHubInfo> {
    let mut info = GitHubInfo::detect(root)?;
    println!("GitHub: {} — fetching open pull requests …", info.repo.web_url());
    // A failed fetch should not look like "no open PRs": leave the PR features off instead.
    if let Some((pulls, source)) = github::fetch_pulls(&info.repo) {
        println!("  loaded {source}");
        info.pulls = Some(pulls);
        info.pulls_source = Some(source);
    }
    Some(info)
}

/// One line of analysis statistics, e.g. `120 files, 95 types (Java 95), 12 packages, git history for 118 files — 840
/// ms`.
fn print_summary(model: &CityModel) {
    let a = &model.analysis;
    let s = &model.stats;
    let languages: Vec<String> = s.languages.iter().map(|(l, n)| format!("{} {n}", l.display_name())).collect();
    println!(
        "  {} files, {} types{}, {} packages{}{} — {} ms",
        a.files,
        a.types.len(),
        if languages.is_empty() { String::new() } else { format!(" ({})", languages.join(", ")) },
        s.packages,
        if a.failed_files > 0 { format!(", {} with parse errors", a.failed_files) } else { String::new() },
        if a.history.is_empty() { String::new() } else { format!(", git history for {} files", a.history.len()) },
        model.build_millis
    );
}

/// Opens `url` in the default browser with the platform's opener. The child is spawned and not waited for; its
/// output is discarded.
fn open_browser(url: &str) -> std::io::Result<()> {
    let cmd = if cfg!(target_os = "macos") {
        vec!["open", url]
    } else if cfg!(windows) {
        vec!["rundll32", "url.dll,FileProtocolHandler", url]
    } else {
        vec!["xdg-open", url]
    };
    std::process::Command::new(cmd[0])
        .args(&cmd[1..])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(|_| ())
}
