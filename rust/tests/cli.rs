//! The whole command-line program, run as a child process: exit codes, what goes to stdout and stderr, `--export` end
//! to end, the flags that switch git, GitHub, tests and the browser off, and server mode. The Java
//! (`CodeCityCliTest`) and D (`app.d`) editions run the same scenarios with the same expectations.
//!
//! Every run passes `--no-github` (the fixtures live inside this repository, whose GitHub remote would otherwise be
//! asked for pull requests over the network) and, unless git is the point, `--no-git`.

use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::time::{Duration, Instant};

/// The repository's `testdata` directory.
fn testdata() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata").canonicalize().unwrap()
}

/// The usage text, the same for all three editions.
fn usage() -> String {
    std::fs::read_to_string(testdata().join("cli/usage.txt")).unwrap()
}

/// The program with `args`, stdin closed.
fn program(args: &[&str]) -> Command {
    let mut c = Command::new(env!("CARGO_BIN_EXE_codecity"));
    c.args(args).stdin(Stdio::null());
    c
}

/// Runs the program to its end; returns its exit code, stdout and stderr.
fn run(args: &[&str]) -> (i32, String, String) {
    let Output { status, stdout, stderr } = program(args).output().unwrap();
    (status.code().unwrap(), String::from_utf8(stdout).unwrap(), String::from_utf8(stderr).unwrap())
}

/// `-h` and `--help` print the usage text to stdout and exit with 0.
#[test]
fn help_prints_the_usage_and_succeeds() {
    for h in ["-h", "--help"] {
        assert_eq!(run(&[h]), (0, usage(), String::new()));
    }
}

/// A bad command line exits with 2 and prints one `error:` line, a blank line and the usage text to stderr, nothing
/// to stdout.
#[test]
fn usage_errors_exit_with_two() {
    let tmp = tempfile::tempdir().unwrap();
    let d = tmp.path().to_str().unwrap();
    let file = tmp.path().join("file.txt");
    std::fs::write(&file, "x").unwrap();
    let file = file.to_str().unwrap();
    let not_a_dir = format!("not a directory: {file}");
    let cases: [(&str, &[&str]); 7] = [
        ("unknown option --bogus", &["--bogus", d]),
        ("only one path may be given", &[d, d]),
        (&not_a_dir, &[file]),
        ("-p needs a value", &[d, "-p"]),
        ("invalid port", &["--port", "x", d]),
        ("--history needs a number of snapshots from 2 to 500", &["--history=1", d]),
        ("--history cannot be combined with --watch", &["--history", "--watch", d]),
    ];
    for (message, args) in cases {
        assert_eq!(run(args), (2, String::new(), format!("error: {message}\n\n{}", usage())), "{message}");
    }
}

/// Control characters from a path given by the user (or a hostile repository) are printed as '?'.
#[test]
fn error_messages_are_sanitised() {
    let tmp = tempfile::tempdir().unwrap();
    let evil = tmp.path().join("x\x1b]0;pwned\x07\x1b[31m");
    let (code, _, err) = run(&[evil.to_str().unwrap()]);
    assert_eq!(code, 2);
    let shown = tmp.path().join("x?]0;pwned??[31m");
    assert!(err.starts_with(&format!("error: not a directory: {}\n", shown.display())), "{err}");
    assert!(!err.contains('\x1b') && !err.contains('\x07'), "{err}");
}

/// The summary line without its timing, e.g. `  13 files, 20 types (Java 20), 4 packages, … — `.
fn summary(out: &str) -> &str {
    let line = out.lines().nth(1).unwrap_or("");
    &line[..line.rfind("— ").map_or(line.len(), |i| i + "— ".len())]
}

/// `--export` analyses, prints the summary line and the absolute path written, exits with 0 and leaves a
/// self-contained page: the Content-Security-Policy filled in and the city JSON inlined. `--no-tests` leaves the test
/// classes out of that city.
#[test]
fn export_writes_the_page_and_succeeds() {
    let tmp = tempfile::tempdir().unwrap();
    let project = testdata().join("java-project");
    let out = tmp.path().join("sub/city.html");
    let (o, p) = (out.to_str().unwrap(), project.to_str().unwrap());
    let (code, stdout, err) = run(&["--export", o, "--no-git", "--no-github", "--no-open", p]);
    assert_eq!((code, err.as_str()), (0, ""));
    let lines: Vec<&str> = stdout.lines().collect();
    assert_eq!(lines.len(), 3, "{stdout}");
    assert_eq!(lines[0], format!("Analysing {p} …"));
    assert_eq!(summary(&stdout), "  13 files, 20 types (Java 20), 4 packages, 1 with parse errors — ");
    assert!(lines[1].ends_with(" ms"), "{stdout}");
    assert_eq!(lines[2], format!("Wrote {o}"));
    let html = std::fs::read_to_string(&out).unwrap();
    assert!(html.contains(r#"<meta http-equiv="Content-Security-Policy""#));
    assert!(html.contains("'sha256-") && !html.contains("{{inline-script-hashes}}"));
    assert!(html.contains(r#"<script id="city-data" type="application/json">{"#));
    assert!(html.contains(r#""com.acme.core.CircleTest""#));

    let (code, stdout, err) = run(&["--export", o, "--no-tests", "--no-git", "--no-github", p]);
    assert_eq!(code, 0, "{err}");
    assert_eq!(summary(&stdout), "  13 files, 17 types (Java 17), 4 packages, 1 with parse errors — ");
    let html = std::fs::read_to_string(&out).unwrap();
    assert!(!html.contains(r#""com.acme.core.CircleTest""#));
    assert!(html.contains(r#""com.acme.core.Circle""#));
}

/// A file that cannot be written is a run failure: exit 1, one sanitised `error:` line, no usage text.
#[test]
fn export_failure_exits_with_one() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path().join("out\x1b[31m.html");
    std::fs::create_dir_all(&dir).unwrap();
    let project = testdata().join("java-project");
    let (code, _, err) = run(&["--export", dir.to_str().unwrap(), "--no-git", "--no-github", project.to_str().unwrap()]);
    assert_eq!(code, 1);
    let expected = format!("error: cannot write {}: Is a directory", tmp.path().join("out?[31m.html").display());
    assert!(err.starts_with(&expected), "{err}");
    assert_eq!(err.lines().count(), 1, "{err}");
}

/// `--no-git` skips the history (no "git history" in the summary), and `--no-github` skips the GitHub remote (no
/// "GitHub:" line and no network) even though the repository has one.
#[test]
fn no_git_and_no_github_switch_them_off() {
    let tmp = tempfile::tempdir().unwrap();
    let repo = tmp.path().join("repo");
    let made = Command::new("sh").arg(testdata().join("make-history-repo.sh")).arg(&repo).output().unwrap();
    assert!(made.status.success(), "{}", String::from_utf8_lossy(&made.stderr));
    let mut remote = Command::new("git");
    remote.arg("-C").arg(&repo).args(["remote", "add", "origin", "https://github.com/owner/repo.git"]);
    assert!(remote.status().unwrap().success());

    let out = tmp.path().join("city.html");
    let (o, r) = (out.to_str().unwrap(), repo.to_str().unwrap());
    let (code, stdout, err) = run(&["--export", o, "--no-github", r]);
    assert_eq!(code, 0, "{err}");
    assert!(stdout.contains(", git history for 6 files — "), "{stdout}");
    assert!(!stdout.contains("GitHub"), "{stdout}");

    let (code, stdout, err) = run(&["--export", o, "--no-git", "--no-github", r]);
    assert_eq!(code, 0, "{err}");
    assert!(!stdout.contains("git history"), "{stdout}");
    assert!(!stdout.contains("GitHub"), "{stdout}");
}

/// Kills the child when dropped, so a failed assertion leaves no server running.
struct Killed(std::process::Child);

impl Drop for Killed {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Starts the program in server mode with `bin` first on the PATH and no display; stderr is discarded.
#[cfg(target_os = "linux")]
fn start(bin: &Path, args: &[&str]) -> Killed {
    let path = format!("{}:{}", bin.display(), std::env::var("PATH").unwrap_or_default());
    let child = program(args)
        .env("PATH", path)
        .env_remove("DISPLAY")
        .env_remove("WAYLAND_DISPLAY")
        .env_remove("BROWSER")
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    Killed(child)
}

/// Reads the program's stdout up to its "live" line, checks that the printed URL serves the city and returns the URL.
#[cfg(target_os = "linux")]
fn await_live(p: &mut Killed) -> String {
    let out = BufReader::new(p.0.stdout.take().unwrap());
    for line in out.lines() {
        let line = line.unwrap();
        let Some(rest) = line.strip_prefix("CodeCity is live at ") else { continue };
        let url = rest.strip_suffix("  (Ctrl+C to stop)").expect(&line).to_string();
        let port: u16 = url.strip_prefix("http://localhost:").and_then(|r| r.strip_suffix('/')).unwrap().parse().unwrap();
        assert!(port > 0);
        let mut s = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        s.write_all(format!("GET /api/city HTTP/1.1\r\nHost: localhost:{port}\r\nConnection: close\r\n\r\n").as_bytes()).unwrap();
        let mut city = String::new();
        s.read_to_string(&mut city).unwrap();
        assert!(city.starts_with("HTTP/1.1 200"), "{city}");
        assert!(city.contains(r#""com.acme.core.Circle""#));
        return url;
    }
    panic!("the server never said it was live");
}

/// Server mode: prints the URL (with the port it got, here one chosen by the system for `-p 0`), serves the city
/// there and opens the browser, unless `--no-open`. The browser launcher is replaced by a script that records its
/// argument.
#[cfg(target_os = "linux")]
#[test]
fn server_mode_serves_and_opens_the_browser_unless_told_not_to() {
    use std::os::unix::fs::PermissionsExt;
    let tmp = tempfile::tempdir().unwrap();
    let bin = tmp.path().join("bin");
    std::fs::create_dir_all(&bin).unwrap();
    let opened = tmp.path().join("opened.txt");
    let launcher = bin.join("xdg-open");
    std::fs::write(&launcher, format!("#!/bin/sh\necho \"$1\" >> '{}'\n", opened.display())).unwrap();
    std::fs::set_permissions(&launcher, std::fs::Permissions::from_mode(0o755)).unwrap();
    let project = testdata().join("java-project");
    let project = project.to_str().unwrap();

    let mut p = start(&bin, &["-p", "0", "--no-git", "--no-github", project]);
    let url = await_live(&mut p);
    let end = Instant::now() + Duration::from_secs(20);
    while std::fs::read_to_string(&opened).ok() != Some(format!("{url}\n")) && Instant::now() < end {
        std::thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(std::fs::read_to_string(&opened).unwrap(), format!("{url}\n"));
    drop(p);

    std::fs::remove_file(&opened).unwrap();
    let mut p = start(&bin, &["-p", "0", "--no-open", "--no-git", "--no-github", project]);
    await_live(&mut p);
    // The browser would be launched right after the "live" line; give it ample time not to happen.
    std::thread::sleep(Duration::from_secs(1));
    assert!(!opened.exists(), "--no-open opened a browser");
}
