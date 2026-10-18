//! Mines `git log` once for per-file churn, author count and recency.
//!
//! This is the git-history step of the pipeline: [`crate::city::CityModel::build`] calls [`load`] on a separate
//! thread while the sources are parsed, and the result ends up in [`crate::model::Analysis::history`], keyed by
//! absolute path so that it matches [`crate::model::TypeInfo::file`].
//!
//! With `--history`, [`load_with_events`] also keeps every file's commits ([`FileEvents`]), so that
//! [`crate::history`] can count them up to each snapshot. The helpers at the end ([`capture`], [`CatFile`],
//! [`prefetch_missing`]) are the plumbing the history replay needs to materialise old commits.

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
    mine(root, false).0
}

/// Every commit of the [`load`] pass that touched one file, for the history replay: author times sorted ascending,
/// and for each prefix of that order the number of distinct authors in it.
#[derive(Clone, Debug, Default)]
pub struct FileEvents {
    /// Author times (seconds since the epoch) of the commits, oldest first.
    pub times: Vec<i64>,
    /// `distinct[k]` = distinct author emails among the commits `times[0..=k]`.
    pub distinct: Vec<u32>,
}

impl FileEvents {
    /// (commits, authors, last author time) over the commits whose author time is at most `t`; `(0, 0, -1)` when
    /// there is none. `times` is sorted, so the commits up to `t` are a prefix found by binary search.
    pub fn until(&self, t: i64) -> (u32, u32, i64) {
        let k = self.times.partition_point(|&x| x <= t);
        if k == 0 { (0, 0, -1) } else { (k as u32, self.distinct[k - 1], self.times[k - 1]) }
    }
}

/// [`load`], plus the commits of every file ([`FileEvents`]), keyed the same way (absolute path below the work tree).
pub fn load_with_events(root: &Path) -> (HashMap<PathBuf, FileHistory>, HashMap<PathBuf, FileEvents>) {
    mine(root, true)
}

/// The single `git log` pass behind [`load`] and [`load_with_events`]; `events` says whether to keep every file's
/// commits as well as the totals (only the history replay needs them).
fn mine(root: &Path, events: bool) -> (HashMap<PathBuf, FileHistory>, HashMap<PathBuf, FileEvents>) {
    let Some(top) = run(root, &["rev-parse", "--show-toplevel"]).filter(|t| !t.is_empty()) else {
        return Default::default();
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
        return Default::default();
    };
    /// Running totals for one path. `last`/`first` start at the extremes so that min/max work from the first commit.
    /// `events` collects (author time, author id) per commit, only when asked for.
    struct Acc {
        commits: u32,
        authors: HashSet<String>,
        last: i64,
        first: i64,
        events: Vec<(i64, u32)>,
    }
    // Author email → small id, so the events store a number per commit instead of a string.
    let mut author_ids: HashMap<String, u32> = HashMap::new();
    let mut author_id = 0u32;
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
            if events {
                let next = author_ids.len() as u32;
                author_id = *author_ids.entry(author.clone()).or_insert(next);
            }
        } else if !line.trim().is_empty() {
            // Any other non-blank line is a path touched by the current commit.
            let acc = files.entry(line).or_insert_with(|| Acc { commits: 0, authors: HashSet::new(), last: i64::MIN, first: i64::MAX, events: vec![] });
            acc.commits += 1;
            if events {
                acc.events.push((time, author_id));
            }
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
    let mut totals = HashMap::with_capacity(files.len());
    let mut per_file = HashMap::new();
    for (rel, mut a) in files {
        let path = top.join(rel);
        if events {
            // Oldest first (stable, so equal times keep log order; it does not matter for the counts), then count
            // the distinct authors of every prefix in one sweep.
            a.events.sort_by_key(|e| e.0);
            let mut seen = HashSet::new();
            let distinct = a.events.iter().map(|e| { seen.insert(e.1); seen.len() as u32 }).collect();
            per_file.insert(path.clone(), FileEvents { times: a.events.iter().map(|e| e.0).collect(), distinct });
        }
        totals.insert(path, FileHistory { commits: a.commits, authors: a.authors.len() as u32, last_commit: a.last, first_commit: a.first });
    }
    (totals, per_file)
}

/// Raw stdout of `git -C dir args…` when it exits successfully, else None. Unlike [`run`] there is no time limit and
/// nothing is trimmed or decoded: for the long-running, possibly large commands of the history replay.
pub fn capture(dir: &Path, args: &[&str]) -> Option<Vec<u8>> {
    let out = Command::new("git").arg("-C").arg(dir).args(args).stdin(Stdio::null()).stderr(Stdio::null()).output().ok()?;
    out.status.success().then_some(out.stdout)
}

/// One long-running `git cat-file --batch`, which prints any number of objects for one process start.
///
/// Request: the object name and a newline. Response: `<sha> <type> <size>\n`, then `size` bytes of content and a
/// newline; or `<name> missing\n` for an object that is not in the repository.
pub struct CatFile {
    child: std::process::Child,
    stdin: Option<std::process::ChildStdin>,
    stdout: BufReader<std::process::ChildStdout>,
}

impl CatFile {
    /// Starts the process in `dir` (any directory of the repository); None if git cannot be started.
    pub fn new(dir: &Path) -> Option<CatFile> {
        let mut child = Command::new("git")
            .arg("-C")
            .arg(dir)
            .args(["cat-file", "--batch"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .ok()?;
        let stdin = child.stdin.take();
        let stdout = BufReader::new(child.stdout.take()?);
        Some(CatFile { child, stdin, stdout })
    }

    /// The contents of the objects `shas`, in order; None for one that is missing (or when git died). The requests
    /// are written on a second thread while the responses are read here, so neither pipe can fill up and block.
    pub fn read_all(&mut self, shas: &[&str]) -> Vec<Option<Vec<u8>>> {
        let Some(stdin) = self.stdin.as_mut() else { return vec![None; shas.len()] };
        let stdout = &mut self.stdout;
        std::thread::scope(|s| {
            s.spawn(move || {
                use std::io::Write;
                let mut w = std::io::BufWriter::new(stdin);
                for sha in shas {
                    if writeln!(w, "{sha}").is_err() {
                        return;
                    }
                }
                let _ = w.flush();
            });
            let mut out = Vec::with_capacity(shas.len());
            let mut header = String::new();
            for _ in shas {
                header.clear();
                if stdout.read_line(&mut header).unwrap_or(0) == 0 {
                    // git is gone: everything still outstanding is missing.
                    out.push(None);
                    continue;
                }
                // "<sha> <type> <size>"; anything else ("<name> missing") has no content to read.
                let size = header.split_whitespace().nth(2).and_then(|n| n.parse::<usize>().ok());
                match size {
                    Some(size) => {
                        // The content plus the newline after it.
                        let mut buf = vec![0; size + 1];
                        if stdout.read_exact(&mut buf).is_err() {
                            out.push(None);
                            continue;
                        }
                        buf.truncate(size);
                        out.push(Some(buf));
                    }
                    None => out.push(None),
                }
            }
            out
        })
    }
}

impl Drop for CatFile {
    /// Closing stdin ends the batch; then the process is reaped.
    fn drop(&mut self) {
        drop(self.stdin.take());
        let _ = self.child.wait();
    }
}

/// In a partial (blobless) clone, fetches every object missing from history in one batch, so that materialising old
/// commits does not make git fetch blobs lazily one at a time. Does nothing in a complete repository.
///
/// `rev-list --missing=print` lists the missing objects (as `?<sha>`) without fetching them; they are piped to one
/// `fetch --stdin`. The `noop` negotiation skips sending what the clone already has, which is pointless here, and
/// `--filter=blob:none` keeps the fetch from turning the clone into a full one.
pub fn prefetch_missing(dir: &Path) {
    if run(dir, &["config", "--get", "remote.origin.promisor"]).as_deref() != Some("true") {
        return;
    }
    let Some(listing) = capture(dir, &["rev-list", "--objects", "--missing=print", "HEAD"]) else { return };
    let missing: Vec<&[u8]> = listing.split(|&b| b == b'\n').filter_map(|l| l.strip_prefix(b"?")).collect();
    if missing.is_empty() {
        return;
    }
    println!("  history: fetching {} missing objects …", missing.len());
    let Ok(mut child) = Command::new("git")
        .arg("-C")
        .arg(dir)
        .args(["-c", "fetch.negotiationAlgorithm=noop", "fetch", "-q", "origin", "--no-tags", "--no-write-fetch-head"])
        .args(["--recurse-submodules=no", "--filter=blob:none", "--stdin"])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return;
    };
    if let Some(mut stdin) = child.stdin.take() {
        use std::io::Write;
        let mut w = std::io::BufWriter::new(&mut stdin);
        for sha in missing {
            let _ = w.write_all(sha).and_then(|_| w.write_all(b"\n"));
        }
        let _ = w.flush();
    }
    // stdin is closed when it goes out of scope above; a failed fetch only means git fetches lazily later.
    let _ = child.wait();
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
