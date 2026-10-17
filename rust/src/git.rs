//! Mines `git log` once for per-file churn, author count and recency.

use crate::model::FileHistory;
use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// Output of `git -C dir args…`, or None if git fails or is unavailable.
pub fn run(dir: &Path, args: &[&str]) -> Option<String> {
    let out = Command::new("git").arg("-C").arg(dir).args(args).stderr(Stdio::null()).output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
}

/// Empty when `root` is not inside a git work tree or git is unavailable.
pub fn load(root: &Path) -> HashMap<PathBuf, FileHistory> {
    let Some(top) = run(root, &["rev-parse", "--show-toplevel"]).filter(|t| !t.is_empty()) else {
        return HashMap::new();
    };
    let top = std::fs::canonicalize(&top).unwrap_or_else(|_| PathBuf::from(&top));
    let Ok(mut child) = Command::new("git")
        .arg("-C")
        .arg(root)
        .args(["-c", "core.quotepath=off", "log", "--no-merges", "--no-renames", "--format=%x1e%at%x1f%aE", "--name-only", "--"])
        .args(["*.java", "*.ts", "*.tsx", "*.mts", "*.cts", "*.rs"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    else {
        return HashMap::new();
    };
    struct Acc {
        commits: u32,
        authors: HashSet<String>,
        last: i64,
        first: i64,
    }
    let mut files: HashMap<String, Acc> = HashMap::new();
    let mut time = 0i64;
    let mut author = String::new();
    for line in BufReader::new(child.stdout.take().unwrap()).lines().map_while(Result::ok) {
        if let Some(header) = line.strip_prefix('\u{1e}') {
            let (t, a) = header.split_once('\u{1f}').unwrap_or((header, ""));
            time = t.parse().unwrap_or(0);
            author = a.to_string();
        } else if !line.trim().is_empty() {
            let acc = files.entry(line).or_insert_with(|| Acc { commits: 0, authors: HashSet::new(), last: i64::MIN, first: i64::MAX });
            acc.commits += 1;
            if !acc.authors.contains(&author) {
                acc.authors.insert(author.clone());
            }
            acc.last = acc.last.max(time);
            acc.first = acc.first.min(time);
        }
    }
    let _ = child.wait();
    files
        .into_iter()
        .map(|(rel, a)| {
            (top.join(rel), FileHistory { commits: a.commits, authors: a.authors.len() as u32, last_commit: a.last, first_commit: a.first })
        })
        .collect()
}
