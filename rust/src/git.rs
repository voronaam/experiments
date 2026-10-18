//! Mines `git log` once for per-file churn, author count and recency.
//!
//! This is the git-history step of the pipeline: [`crate::city::CityModel::build`] calls [`load`] on a separate
//! thread while the sources are parsed, and the result ends up in [`crate::model::Analysis::history`], keyed by
//! absolute path so that it matches [`crate::model::TypeInfo::file`].

use crate::model::FileHistory;
use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// How long a short command ([`run`], `gh auth token`) may take before [`output`] kills it.
const TIMEOUT: Duration = Duration::from_secs(30);

/// Output of `git -C dir args…`, or None if git fails, is unavailable or times out (see [`output`]).
pub fn run(dir: &Path, args: &[&str]) -> Option<String> {
    output(Command::new("git").arg("-C").arg(dir).args(args))
}

/// Output of a short command, trimmed (it usually ends with a newline) and decoded lossily (invalid UTF-8 becomes
/// U+FFFD); None if it cannot be started, exits with an error or takes longer than [`TIMEOUT`]. stderr is discarded,
/// so a chatty command cannot fill an unread pipe. stdout is read on a separate thread, so the limit also covers a
/// command that hangs while keeping stdout open: it is then killed.
pub fn output(cmd: &mut Command) -> Option<String> {
    let mut child = cmd.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().ok()?;
    let mut stdout = child.stdout.take()?;
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let mut buf = vec![];
        let _ = stdout.read_to_end(&mut buf);
        let _ = tx.send(buf);
    });
    let deadline = Instant::now() + TIMEOUT;
    let kill = |child: &mut std::process::Child| {
        let _ = child.kill();
        let _ = child.wait();
    };
    let Ok(out) = rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) else {
        kill(&mut child);
        return None;
    };
    // stdout is closed, so the command is about to exit; poll for that until the deadline.
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(10)),
            _ => {
                kill(&mut child);
                return None;
            }
        }
    };
    status.success().then(|| String::from_utf8_lossy(&out).trim().to_string())
}

/// Empty when `root` is not inside a git work tree or git is unavailable.
///
/// One `git log` over the whole history, streamed rather than collected, prints for every commit a header line and
/// then the files it touched:
///
/// ```text
/// \x1e1712345678\x1fann@example.com      <- %x1e (record separator), author time, %x1f (unit separator), email
///                                       <- blank line after the header
/// src/main/java/com/acme/Shop.java
/// src/main/java/com/acme/Cart.java
/// ```
///
/// With a pathspec, commits that touch none of the listed files are left out.
///
/// The control characters cannot occur in a path or an email, so a header can never be mistaken for a file name.
/// Merge commits are skipped (they would count every change twice), and with `--no-renames` a rename shows up as the
/// new path only, so a renamed file's history starts at the rename. Only the languages CodeCity analyses are
/// listed, which keeps the output small on mixed repositories.
pub fn load(root: &Path) -> HashMap<PathBuf, FileHistory> {
    let Some(top) = run(root, &["rev-parse", "--show-toplevel"]).filter(|t| !t.is_empty()) else {
        return HashMap::new();
    };
    // `--name-only` prints paths relative to the top of the work tree, not to root, so they are joined onto the
    // canonical top below; canonical because the analysed root is canonical too (symlinks resolved).
    let top = std::fs::canonicalize(&top).unwrap_or_else(|_| PathBuf::from(&top));
    let Ok(mut child) = Command::new("git")
        .arg("-C")
        .arg(root)
        // core.quotepath=off: print non-ASCII paths as they are instead of quoted octal escapes. %aE is the author
        // email after .mailmap, so one person with several addresses can count once. The pathspecs are globs in
        // which `*` also matches `/`, so `*.java` matches at any depth below root.
        .args(["-c", "core.quotepath=off", "log", "--no-merges", "--no-renames", "--format=%x1e%at%x1f%aE", "--name-only", "--"])
        .args(["*.java", "*.ts", "*.tsx", "*.mts", "*.cts", "*.rs"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    else {
        return HashMap::new();
    };
    /// Running totals for one path. `last`/`first` start at the extremes so that min/max work from the first commit.
    struct Acc {
        commits: u32,
        authors: HashSet<String>,
        last: i64,
        first: i64,
    }
    let mut files: HashMap<String, Acc> = HashMap::new();
    // The header of the commit whose file lines are being read.
    let mut time = 0i64;
    let mut author = String::new();
    // Lines are split on raw bytes and decoded lossily: a path or email that is not valid UTF-8 gets U+FFFD in place
    // of the bad bytes (as in the other editions) instead of ending the read. The line ending ("\n" or "\r\n") is
    // dropped, as `lines()` would.
    let mut reader = BufReader::new(child.stdout.take().unwrap());
    let mut bytes = vec![];
    while reader.read_until(b'\n', &mut bytes).unwrap_or(0) > 0 {
        let raw = bytes.strip_suffix(b"\n").unwrap_or(&bytes);
        let line = String::from_utf8_lossy(raw.strip_suffix(b"\r").unwrap_or(raw)).into_owned();
        bytes.clear();
        if let Some(header) = line.strip_prefix('\u{1e}') {
            let (t, a) = header.split_once('\u{1f}').unwrap_or((header, ""));
            time = t.parse().unwrap_or(0);
            author = a.to_string();
        } else if !line.trim().is_empty() {
            // Any other non-blank line is a path touched by the current commit.
            let acc = files.entry(line).or_insert_with(|| Acc { commits: 0, authors: HashSet::new(), last: i64::MIN, first: i64::MAX });
            acc.commits += 1;
            // Check first so the author string is only cloned for a new author.
            if !acc.authors.contains(&author) {
                acc.authors.insert(author.clone());
            }
            acc.last = acc.last.max(time);
            acc.first = acc.first.min(time);
        }
    }
    // Reap the child. Its exit status does not matter: whatever was read is used.
    let _ = child.wait();
    files
        .into_iter()
        .map(|(rel, a)| {
            (top.join(rel), FileHistory { commits: a.commits, authors: a.authors.len() as u32, last_commit: a.last, first_commit: a.first })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The newest commit adds a file whose name, and whose author's email, are not valid UTF-8 (byte 0xFF). They are
    /// read with U+FFFD in place of the bad byte, and the older history after them is still read.
    #[test]
    fn reads_history_past_invalid_utf8() {
        let dir = tempfile::tempdir().unwrap();
        let script = r#"
set -e
git init -q .
git config user.name t
git config user.email t@example.com
echo a > A.java
git add A.java
git -c commit.gpgsign=false commit -q -m a
echo b > "$(printf 'B\377.java')"
git add -A
git -c commit.gpgsign=false commit -q -m b --author="$(printf 'x <x\377@example.com>')"
"#;
        assert!(Command::new("sh").arg("-c").arg(script).current_dir(dir.path()).status().unwrap().success());
        let top = std::fs::canonicalize(dir.path()).unwrap();
        let history = load(&top);
        assert_eq!(history.len(), 2);
        assert_eq!(history[&top.join("A.java")].commits, 1);
        assert_eq!(history[&top.join("B\u{FFFD}.java")].authors, 1);
    }
}
