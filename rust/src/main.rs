//! CodeCity — explore a Java, TypeScript or Rust code base as an interactive 3D city (native Rust edition).

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

#[derive(Clone)]
struct Options {
    root: Option<PathBuf>,
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

fn run(mut opts: Options) -> Result<(), String> {
    if let Some(repo) = &opts.remote {
        let dir = github::clone_or_update(repo)?;
        opts.root = Some(std::fs::canonicalize(&dir).map_err(|e| e.to_string())?);
    }
    let root = opts.root.clone().unwrap();
    let github = if opts.github { load_github(&root) } else { None };

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
            return gui::run(model, opts.watch.then_some((root, Box::new(rebuild) as Box<dyn Fn() -> Arc<CityModel> + Send>)));
        }
        #[cfg(not(feature = "gui"))]
        return Err("this build has no desktop window (built without the `gui` feature)".into());
    }

    let server = server::CityServer::bind(model, opts.port).map_err(|e| format!("cannot start server: {e}"))?;
    println!("CodeCity is live at {}  (Ctrl+C to stop)", server.url());
    let _watcher = if opts.watch {
        let update = server.updater();
        let w = watch::watch(&root, move || update(rebuild())).map_err(|e| format!("cannot watch: {e}"))?;
        println!("Watching for changes …");
        Some(w)
    } else {
        None
    };
    if opts.open {
        let _ = open_browser(&server.url());
    }
    server.run();
    Ok(())
}

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
