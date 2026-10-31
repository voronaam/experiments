//! History replay: the city as it was at up to N commits of its first-parent history, so the viewers can show it
//! growing from the first commit to today (`--history[=N]`).
//!
//! This step runs after the normal analysis and git pass ([`crate::city::CityModel::build`] calls [`compute`]). It
//! samples N commits, materialises each one into a temporary directory and analyses that directory like any other
//! source tree. The result, a [`History`], records every type's metrics per snapshot ([`Track`]) and the last version
//! seen of every type, from which `city.rs` builds the "union" city: every type that exists today or in any
//! snapshot.
//!
//! # Materialising a snapshot
//! One temporary directory mirrors the analysed root at the current snapshot. The first snapshot writes every blob
//! under the root's path prefix (`git ls-tree`); each later one applies the difference to the previous snapshot
//! (`git diff-tree`): added and modified files are written, deleted ones removed. Only regular files the analysis
//! would read are written (sources and manifests, outside ignored directories), and their contents come from one
//! long-running `git cat-file --batch` ([`crate::git::CatFile`]). Every analysis uses the same [`SourceAnalyzer`],
//! whose parse cache then skips the files a snapshot did not touch. Each written file gets a new, strictly larger
//! modification time, so the cache never mistakes a rewritten file of equal size for an unchanged one.
//!
//! The repository is untrusted. Its git commands are hardened ([`git::command`]); only a clone of the GitHub clone
//! cache may fetch missing objects. Tree entries are only written below the temporary directory when their path is a
//! plain relative one ([`safe_path`]): a crafted tree can hold entries such as `..` or `/abs/dir` that git itself
//! would never check out. A blob larger than [`crate::analysis::MAX_FILE_BYTES`], which the analysis would not read
//! anyway, is treated like a missing one: its file is absent from that snapshot.
//!
//! The Java and D editions implement the same rules, and the JSON they write from it is identical.

use crate::analysis::{is_ignored_dir, language_of, SourceAnalyzer, MANIFESTS};
use crate::git::{self, CatFile, FileEvents};
use crate::model::TypeInfo;
use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

/// Snapshots when `--history` is given without a count, and the allowed range for `--history=N`.
pub const DEFAULT_SNAPSHOTS: usize = 60;
pub const MIN_SNAPSHOTS: usize = 2;
pub const MAX_SNAPSHOTS: usize = 500;

/// One sampled commit.
#[derive(Clone, Debug)]
pub struct Snapshot {
    /// Full 40-hex commit id.
    pub commit: String,
    /// Author time, seconds since the epoch.
    pub time: i64,
    pub author: String,
    /// First line of the commit message.
    pub subject: String,
    /// Number of types in this snapshot's analysis.
    pub types: usize,
}

/// One type through the snapshots: one entry per snapshot in every vector.
///
/// The metrics are -1 where the type does not exist. `commits`/`authors`/`last` are the git statistics of the type's
/// file in that snapshot, counted over the commits of the normal git pass whose author time is at most the snapshot's
/// (see [`FileEvents::until`]); 0, 0 and -1 where the type does not exist or there is no such commit.
#[derive(Clone, Debug)]
pub struct Track {
    pub nom: Vec<i32>,
    pub noa: Vec<i32>,
    pub loc: Vec<i32>,
    pub cx: Vec<i32>,
    pub commits: Vec<i32>,
    pub authors: Vec<i32>,
    pub last: Vec<i64>,
}

impl Track {
    /// A type that exists in none of the `n` snapshots (yet).
    pub fn absent(n: usize) -> Track {
        Track { nom: vec![-1; n], noa: vec![-1; n], loc: vec![-1; n], cx: vec![-1; n], commits: vec![0; n], authors: vec![0; n], last: vec![-1; n] }
    }

    /// Whether the type exists in snapshot `k`.
    pub fn exists(&self, k: usize) -> bool {
        self.nom[k] >= 0
    }

    /// The largest attribute count over the snapshots (0 if it never exists).
    pub fn max_noa(&self) -> u32 {
        self.noa.iter().copied().max().unwrap_or(0).max(0) as u32
    }
}

/// Everything [`compute`] learns from the snapshots.
pub struct History {
    /// Oldest first; the last one is HEAD.
    pub snapshots: Vec<Snapshot>,
    /// fqn → the type's metrics per snapshot, for every type that exists in at least one snapshot.
    pub tracks: HashMap<String, Track>,
    /// fqn → the type as analysed in the latest snapshot that has it, with its dependencies (fqns) there. Its
    /// [`TypeInfo::file`] is rewritten from the temporary directory to the same path below the real root, so it
    /// reads like a type of the normal analysis.
    pub last_seen: HashMap<String, (TypeInfo, Vec<String>)>,
}

/// The indices of the `n` commits sampled from `m` (oldest first): all of them when `m ≤ n`, otherwise `n` indices
/// spread evenly from the first to the last, `i_k = round(k·(m−1)/(n−1))` with halves rounded up, in integer
/// arithmetic: `(2·k·(m−1) + (n−1)) div (2·(n−1))`. The first and the last commit are always included.
pub fn sample(m: usize, n: usize) -> Vec<usize> {
    if m <= n {
        return (0..m).collect();
    }
    (0..n).map(|k| (2 * k * (m - 1) + (n - 1)) / (2 * (n - 1))).collect()
}

/// Printed (to stderr) when the history cannot be computed; the city is then built without it.
pub const NO_HISTORY: &str = "warning: --history needs git history; replay disabled";

/// Computes up to `n` snapshots of `root`'s history. `events` are the per-file commits of the normal git pass
/// ([`git::load_with_events`]); `include_tests` is applied to every snapshot as to the normal analysis.
///
/// Returns None (after printing [`NO_HISTORY`]) when root is not in a git work tree or HEAD has no commits. Progress
/// goes to stdout: a line per 10% of the snapshots and a summary at the end.
pub fn compute(root: &Path, include_tests: bool, n: usize, events: &HashMap<PathBuf, FileEvents>) -> Option<History> {
    let start = Instant::now();
    // The root's path inside the work tree ("" at the top, else "sub/dir/"), which selects and strips the paths of
    // the root's files in the tree listings below.
    let (Some(top), Some(prefix)) = (git::run(root, &["rev-parse", "--show-toplevel"]), git::run(root, &["rev-parse", "--show-prefix"])) else {
        eprintln!("{NO_HISTORY}");
        return None;
    };
    let top = PathBuf::from(top);
    let commits: Vec<String> = git::capture(root, &["rev-list", "--first-parent", "--reverse", "HEAD"])
        .map(|out| String::from_utf8_lossy(&out).split_whitespace().map(str::to_string).collect())
        .unwrap_or_default();
    if commits.is_empty() {
        eprintln!("{NO_HISTORY}");
        return None;
    }
    let picked: Vec<&str> = sample(commits.len(), n).into_iter().map(|i| commits[i].as_str()).collect();
    let mut snapshots = describe(root, &picked)?;
    // Only the clone cache may fetch: the configuration of any other repository could run programs on fetch.
    let cached = crate::github::is_cached(&top);
    if cached {
        git::prefetch_missing(root);
    }

    let tmp = TempDir::new(root).ok()?;
    let mut cat = CatFile::new(&top, cached)?;
    let analyzer = SourceAnalyzer::new();
    let count = snapshots.len();
    let mut tracks: HashMap<String, Track> = HashMap::new();
    let mut last_seen = HashMap::new();
    let mut mirror = Mirror { root: tmp.root.clone(), clock: SystemTime::UNIX_EPOCH + Duration::from_secs(1_000_000_000) };
    for k in 0..count {
        let changes = if k == 0 { list_tree(&top, &picked[0], &prefix) } else { diff_trees(&top, &picked[k - 1], &picked[k], &prefix) };
        mirror.apply(&mut cat, changes?);
        let analysis = analyzer.analyze(&tmp.root, include_tests);
        let snapshot = &mut snapshots[k];
        snapshot.types = analysis.types.len();
        let mut deps = analysis.dependencies;
        for mut t in analysis.types {
            // The same path below the real root: this is what the git statistics are keyed by, and what a type of
            // the normal analysis would carry.
            let rel = t.file.strip_prefix(&tmp.root).map(Path::to_path_buf).unwrap_or_else(|_| t.file.clone());
            t.file = root.join(rel);
            let track = tracks.entry(t.fqn.clone()).or_insert_with(|| Track::absent(count));
            track.nom[k] = t.nom() as i32;
            track.noa[k] = t.noa as i32;
            track.loc[k] = t.loc as i32;
            track.cx[k] = t.complexity() as i32;
            let (c, a, last) = events.get(&t.file).map(|e| e.until(snapshot.time)).unwrap_or((0, 0, -1));
            track.commits[k] = c as i32;
            track.authors[k] = a as i32;
            track.last[k] = last;
            let d = deps.remove(&t.fqn).unwrap_or_default();
            last_seen.insert(t.fqn.clone(), (t, d));
        }
        // One line each time another tenth of the snapshots is done; the last snapshot gets the summary instead.
        if k + 1 < count && (k + 1) * 10 / count > k * 10 / count {
            println!("  history: {}/{count} snapshots …", k + 1);
        }
    }
    println!(
        "  history: {count} snapshots from {} to {} — {} ms",
        crate::city::iso_date(snapshots[0].time),
        crate::city::iso_date(snapshots[count - 1].time),
        start.elapsed().as_millis()
    );
    Some(History { snapshots, tracks, last_seen })
}

/// Commit id, author time, author name and subject of each commit, in the given order, from one `git log`. Each
/// commit is one line, its fields separated by NUL: git cuts a subject at a NUL and joins its lines, and a name holds
/// neither, so any other character (also a control character) is kept as it is. `types` is filled in later.
fn describe(root: &Path, shas: &[&str]) -> Option<Vec<Snapshot>> {
    let mut args = vec!["log", "--no-walk=unsorted", "--format=%H%x00%at%x00%an%x00%s"];
    args.extend_from_slice(shas);
    let out = git::capture(root, &args)?;
    let snapshots: Vec<Snapshot> = out
        .split(|&b| b == b'\n')
        .filter_map(|line| {
            let f: Vec<String> = line.split(|&b| b == 0).map(|f| String::from_utf8_lossy(f).into_owned()).collect();
            let [commit, time, author, subject] = <[String; 4]>::try_from(f).ok()?;
            Some(Snapshot { commit, time: time.parse().unwrap_or(0), author, subject, types: 0 })
        })
        .collect();
    (snapshots.len() == shas.len()).then_some(snapshots)
}

/// What one snapshot changes in the mirror: paths relative to the root, files to write as (path, blob id) and files
/// to delete.
#[derive(Default)]
struct Changes {
    write: Vec<(PathBuf, String)>,
    delete: Vec<PathBuf>,
}

impl Changes {
    /// Records the new state of the work-tree path `path` (`mode`/`sha` of the new blob, or None when it is gone).
    /// Paths outside the prefix are ignored (the pathspec should already have excluded them), and so are paths that
    /// are not safe ([`safe_path`]). Anything that is not a regular file the analysis reads is deleted rather than
    /// written: a file that became a symlink or a submodule (a type change) must not keep its old contents.
    fn add(&mut self, prefix: &str, path: &[u8], new: Option<(&str, &str)>) {
        let Some(rel) = path.strip_prefix(prefix.as_bytes()) else { return };
        if !safe_path(rel) {
            return;
        }
        let rel = path_of(rel);
        match new {
            Some((mode, sha)) if (mode == "100644" || mode == "100755") && wanted(&rel) => self.write.push((rel, sha.to_string())),
            _ => self.delete.push(rel),
        }
    }
}

/// Whether `rel`, a path from a tree listing relative to the root, may be written below the temporary directory:
/// every `/`-separated component is non-empty and neither `.`, `..`, nor `.git` in any case, and none holds a
/// backslash or a colon (path syntax on Windows). git never checks out anything else, but a crafted tree object can
/// hold such entries, and writing `../../x` or `/home/u/x` would put a file outside the directory.
fn safe_path(rel: &[u8]) -> bool {
    rel.split(|&b| b == b'/').all(|c| !c.is_empty() && c != b"." && c != b".." && !c.eq_ignore_ascii_case(b".git") && !c.iter().any(|&b| b == b'\\' || b == b':' || b == 0))
}

/// The pathspec (`-- :(literal)<dir>`) that limits a tree listing to the root's directory, or nothing at the top of
/// the work tree. Literal, so that glob characters in a directory name are not interpreted.
fn pathspec(prefix: &str) -> Vec<String> {
    match prefix.strip_suffix('/') {
        Some(dir) => vec!["--".into(), format!(":(literal){dir}")],
        None => vec![],
    }
}

/// Every file of commit `sha` under the prefix, as an initial set of writes (`git ls-tree -r -z`, records
/// `<mode> <type> <sha>\t<path>\0`).
fn list_tree(top: &Path, sha: &str, prefix: &str) -> Option<Changes> {
    let spec = pathspec(prefix);
    let mut args = vec!["ls-tree", "-r", "-z", sha];
    args.extend(spec.iter().map(String::as_str));
    let out = git::capture(top, &args)?;
    let mut changes = Changes::default();
    for record in out.split(|&b| b == 0).filter(|r| !r.is_empty()) {
        let Some(tab) = record.iter().position(|&b| b == b'\t') else { continue };
        let meta = String::from_utf8_lossy(&record[..tab]);
        let fields: Vec<&str> = meta.split(' ').collect();
        if fields.len() == 3 {
            changes.add(prefix, &record[tab + 1..], Some((fields[0], fields[2])));
        }
    }
    // Nothing to delete in an empty mirror; ls-tree only lists what exists.
    changes.delete.clear();
    Some(changes)
}

/// The files that changed under the prefix between commits `from` and `to`, from `git diff-tree -r -z
/// --no-renames`, whose records are `:<old mode> <new mode> <old sha> <new sha> <status>\0<path>\0`. This is the
/// raw form of `git diff --name-status`, with the new blob's mode and id included. Status D deletes; A, M and T
/// (type change) take the new mode and blob.
fn diff_trees(top: &Path, from: &str, to: &str, prefix: &str) -> Option<Changes> {
    let spec = pathspec(prefix);
    let mut args = vec!["diff-tree", "-r", "-z", "--no-renames", from, to];
    args.extend(spec.iter().map(String::as_str));
    let out = git::capture(top, &args)?;
    let mut changes = Changes::default();
    let mut fields = out.split(|&b| b == 0);
    while let Some(meta) = fields.next() {
        let Some(meta) = meta.strip_prefix(b":") else { continue };
        let Some(path) = fields.next() else { break };
        let meta = String::from_utf8_lossy(meta);
        let m: Vec<&str> = meta.split(' ').collect();
        if m.len() < 5 {
            continue;
        }
        let new = if m[4].starts_with('D') { None } else { Some((m[1], m[3])) };
        changes.add(prefix, path, new);
    }
    Some(changes)
}

/// Whether the analysis would read the file at `rel` (relative to the root): a source file or a manifest, and not
/// inside a directory the walk skips. Writing anything else would be wasted work: the result is the same.
fn wanted(rel: &Path) -> bool {
    let Some(name) = rel.file_name().map(|n| n.to_string_lossy()) else { return false };
    let dirs_ok = rel.parent().is_none_or(|p| p.components().all(|c| !is_ignored_dir(&c.as_os_str().to_string_lossy())));
    dirs_ok && (language_of(rel).is_some() || MANIFESTS.contains(&name.as_ref()))
}

/// A git path (raw bytes) as a path; on Unix byte for byte, elsewhere decoded lossily.
fn path_of(bytes: &[u8]) -> PathBuf {
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        PathBuf::from(std::ffi::OsStr::from_bytes(bytes))
    }
    #[cfg(not(unix))]
    {
        PathBuf::from(String::from_utf8_lossy(bytes).into_owned())
    }
}

/// The temporary directory that mirrors the root at the current snapshot.
struct Mirror {
    root: PathBuf,
    /// The modification time the next written file gets; one second later each time.
    clock: SystemTime,
}

impl Mirror {
    /// Deletes, then writes (so a path that changes from file to directory or back works), reading every blob to
    /// write through `cat` in one batch. A file that cannot be written, or whose blob is missing or too large, is
    /// removed: the snapshot just lacks it.
    fn apply(&mut self, cat: &mut CatFile, changes: Changes) {
        for rel in &changes.delete {
            let _ = std::fs::remove_file(self.root.join(rel));
        }
        let shas: Vec<&str> = changes.write.iter().map(|(_, sha)| sha.as_str()).collect();
        cat.for_each(&shas, |i, data| {
            let path = self.root.join(&changes.write[i].0);
            let Some(data) = data else {
                let _ = std::fs::remove_file(&path);
                return;
            };
            if let Some(parent) = path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            // A strictly increasing modification time: the parse cache compares it (with the size), so the new
            // contents are always re-parsed.
            let written = std::fs::File::create(&path).and_then(|mut f| {
                f.write_all(&data)?;
                f.set_modified(self.clock)
            });
            if written.is_err() {
                let _ = std::fs::remove_file(&path);
            }
            self.clock += Duration::from_secs(1);
        });
    }
}

/// A fresh directory in the system's temporary directory, removed with everything in it when dropped, so also on an
/// early return or a panic. The mirror itself is a sub-directory named like the analysed root: an analyser that falls
/// back to the root directory's name (a Rust crate without a `Cargo.toml`) then sees the same name as normally.
///
/// The directory is created new (never an existing one, which another user of a shared temporary directory could
/// have planted, say as a symbolic link) and, on Unix, readable by the user only.
struct TempDir {
    base: PathBuf,
    root: PathBuf,
}

impl TempDir {
    fn new(like: &Path) -> std::io::Result<TempDir> {
        let mut attempt = 0;
        let base = loop {
            let nanos = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
            let base = std::env::temp_dir().join(format!("codecity-history-{}-{nanos}-{attempt}", std::process::id()));
            let mut builder = std::fs::DirBuilder::new();
            #[cfg(unix)]
            std::os::unix::fs::DirBuilderExt::mode(&mut builder, 0o700);
            match builder.create(&base) {
                Ok(()) => break base,
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists && attempt < 100 => attempt += 1,
                Err(e) => return Err(e),
            }
        };
        // Created first, so that a failure below still cleans up.
        let dir = TempDir { root: base.join(like.file_name().unwrap_or("root".as_ref())), base };
        std::fs::create_dir_all(&dir.root)?;
        Ok(dir)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.base);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::city::CityModel;
    use std::process::Command;

    #[test]
    fn samples_evenly_with_both_ends() {
        assert_eq!(sample(10, 4), vec![0, 3, 6, 9]);
        assert_eq!(sample(3, 60), vec![0, 1, 2]);
        let s = sample(100, 60);
        assert_eq!(s.len(), 60);
        assert_eq!((s[0], s[59]), (0, 99));
        assert!(s.windows(2).all(|w| w[0] < w[1]));
    }

    /// Runs one of the shared fixture scripts of `src/test/resources` (next to the Java edition's tests) in `dir`.
    /// `history-three-commits.sh`: A appears, B appears and A grows a method, B is deleted and A gains a field.
    fn fixture(dir: &Path, script: &str) {
        let path = format!("{}/../src/test/resources/{script}", env!("CARGO_MANIFEST_DIR"));
        assert!(Command::new("sh").arg(path).current_dir(dir).status().unwrap().success(), "{script}");
    }

    /// A new temporary directory holding the result of a fixture script, and its canonical path.
    fn repo(script: &str) -> (tempfile::TempDir, std::path::PathBuf) {
        let dir = tempfile::tempdir().unwrap();
        fixture(dir.path(), script);
        let root = std::fs::canonicalize(dir.path()).unwrap();
        (dir, root)
    }

    /// The shared scenario (the Java and D editions assert the same).
    #[test]
    fn replays_three_commits() {
        let (_dir, root) = repo("history-three-commits.sh");
        let shas: Vec<String> = git::run(&root, &["rev-list", "--reverse", "HEAD"]).unwrap().lines().map(str::to_string).collect();

        let model = CityModel::build(&SourceAnalyzer::new(), &root, true, true, None, Some(60));
        let snaps = model.history.as_ref().expect("history");
        assert_eq!(snaps.iter().map(|s| s.commit.clone()).collect::<Vec<_>>(), shas);
        assert_eq!(snaps.iter().map(|s| s.time).collect::<Vec<_>>(), vec![1_577_836_800, 1_577_923_200, 1_578_009_600]);
        assert_eq!(snaps.iter().map(|s| s.subject.as_str()).collect::<Vec<_>>(), vec!["add A", "add B", "drop B"]);
        assert_eq!(snaps.iter().map(|s| s.author.as_str()).collect::<Vec<_>>(), vec!["Ann Author"; 3]);
        assert_eq!(snaps.iter().map(|s| s.types).collect::<Vec<_>>(), vec![1, 2, 1]);

        let find = |name: &str| model.buildings.iter().find(|b| model.types()[b.index].name == name).unwrap();
        let (a, b) = (find("A"), find("B"));
        let ha = a.hist.as_ref().unwrap();
        assert_eq!((ha.nom.clone(), ha.noa.clone()), (vec![1, 2, 2], vec![0, 0, 1]));
        assert_eq!((ha.commits.clone(), ha.authors.clone()), (vec![1, 2, 3], vec![1, 1, 1]));
        assert_eq!(ha.last, snaps.iter().map(|s| s.time).collect::<Vec<_>>());
        let hb = b.hist.as_ref().unwrap();
        assert_eq!(hb.nom, vec![-1, 2, -1]);
        assert_eq!((hb.commits.clone(), hb.last.clone()), (vec![0, 1, 0], vec![-1, 1_577_923_200, -1]));
        assert!(b.gone && !a.gone);
        // B's file is the path it had, below the real root.
        assert_eq!(b.file, "src/B.java");
        // A's lot fits side(max noa) = side(1), which is also its present side; B is drawn at its last-seen side(0).
        assert_eq!(a.lot.side, crate::layout::side_of_noa(1));
        assert_eq!(b.lot.side, crate::layout::side_of_noa(0));
        // District type counts are of present types: B, gone, is not counted.
        assert_eq!(model.districts[0].types, 1);
        // Only present types count in the stats.
        assert_eq!(model.stats.types, 1);
        // The city and A's (default) district exist in every snapshot.
        assert_eq!(model.district_hist, vec![vec![true; 3]; model.districts.len()]);

        let json = model.json();
        assert!(json.contains(r#""hist":{"nom":[1,2,2],"noa":[0,0,1],"loc":[1,1,1],"cx":[1,2,2],"commits":[1,2,3],"authors":[1,1,1],"last":[1577836800,1577923200,1578009600]}"#));
        assert!(json.contains(r#""hist":{"nom":[-1,2,-1],"noa":[-1,0,-1],"loc":[-1,1,-1],"cx":[-1,2,-1],"commits":[0,1,0],"authors":[0,1,0],"last":[-1,1577923200,-1]},"gone":true}"#));
        assert!(json.contains(&format!(r#""history":{{"snapshots":[{{"commit":"{}","time":1577836800,"author":"Ann Author","subject":"add A","types":1}}"#, shas[0])));
        assert!(json.contains(r#""types":1,"hist":[1,1,1]}"#));
        // "gone" only on B.
        assert_eq!(json.matches(r#""gone":true"#).count(), 1);
    }

    /// The hostile repository shared by all three editions (`src/test/resources/hostile-repo.sh`): its configuration
    /// names programs for git to run, and its trees hold paths that escape the work tree. Neither may have any effect.
    #[test]
    fn hostile_repository_runs_nothing_and_writes_nothing_outside() {
        let dir = tempfile::tempdir().unwrap();
        let (repo, markers) = (dir.path().join("repo"), dir.path().join("m"));
        std::fs::create_dir_all(&repo).unwrap();
        std::fs::create_dir_all(&markers).unwrap();
        let script = concat!(env!("CARGO_MANIFEST_DIR"), "/../src/test/resources/hostile-repo.sh");
        assert!(Command::new("sh").arg(script).arg(&markers).current_dir(&repo).status().unwrap().success());
        let repo = std::fs::canonicalize(&repo).unwrap();
        let esc_a = std::env::temp_dir().join("EscA.java");
        let _ = std::fs::remove_file(&esc_a);

        assert!(crate::github::GitHubInfo::detect(&repo).is_some());
        assert!(!git::load(&repo).is_empty());
        let model = CityModel::build(&SourceAnalyzer::new(), &repo, true, true, None, Some(60));

        let hits: Vec<String> = std::fs::read_dir(&markers)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .filter(|n| n.starts_with("hit-"))
            .collect();
        assert_eq!(hits, Vec::<String>::new());
        assert!(!esc_a.exists() && !markers.join("EscB.java").exists() && !std::env::temp_dir().join("EscC.java").exists());
        // Every snapshot holds just A: the escaping entries are skipped, and Z's blob is missing (not fetched).
        let snaps = model.history.as_ref().expect("history");
        assert_eq!(snaps.iter().map(|s| s.types).collect::<Vec<_>>(), vec![1, 1, 1]);
    }

    #[test]
    fn only_plain_relative_paths_are_safe() {
        assert!(safe_path(b"src/A.java"));
        assert!(safe_path(b"a.b/..c/.gitx/A.java"));
        for bad in ["../A.java", "a/../../A.java", "/tmp/A.java", "a//A.java", "./A.java", "a/", ".git/A.java", "a/.GIT/A.java", r"a\..\A.java", "C:A.java", ""] {
            assert!(!safe_path(bad.as_bytes()), "{bad}");
        }
    }

    /// Without a work tree there is no history, and the city is the normal one.
    #[test]
    fn no_history_outside_git() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::fs::write(root.join("A.java"), "class A {}").unwrap();
        let model = CityModel::build(&SourceAnalyzer::new(), &root, true, true, None, Some(60));
        assert!(model.history.is_none() && !model.json().contains("\"hist\""));
        assert_eq!(model.buildings.len(), 1);
    }

    /// With git turned off (`--no-git`) there is no history, even in a repository.
    #[test]
    fn no_history_without_git() {
        let (_dir, root) = repo("history-three-commits.sh");
        let model = CityModel::build(&SourceAnalyzer::new(), &root, true, false, None, Some(60));
        assert!(model.history.is_none() && !model.json().contains("\"hist\""));
    }

    /// A repository without any commit has nothing to replay either.
    #[test]
    fn no_history_without_commits() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        assert!(Command::new("git").args(["init", "-q", "--template=", "."]).current_dir(&root).status().unwrap().success());
        std::fs::write(root.join("A.java"), "class A {}").unwrap();
        let model = CityModel::build(&SourceAnalyzer::new(), &root, true, true, None, Some(60));
        assert!(model.history.is_none() && !model.json().contains("\"hist\""));
    }

    /// The sampling formula at its edges: one commit; exactly as many commits as snapshots; two snapshots (both ends
    /// only); a true half rounds up (k(m-1)/(n-1) = 1.5 → 2); one commit more than snapshots skips exactly one in the
    /// middle; and a long history keeps both ends and strictly increasing indices.
    #[test]
    fn sampling_formula_edge_cases() {
        assert_eq!(sample(0, 60), Vec::<usize>::new());
        assert_eq!(sample(1, 60), vec![0]);
        assert_eq!(sample(2, 2), vec![0, 1]);
        assert_eq!(sample(3, 2), vec![0, 2]);
        assert_eq!(sample(100_000, 2), vec![0, 99_999]);
        assert_eq!(sample(4, 3), vec![0, 2, 3]);
        assert_eq!(sample(6, 4), vec![0, 2, 3, 5]);
        let one_more = sample(61, 60);
        assert_eq!((one_more.len(), one_more[59]), (60, 60));
        assert!(!one_more.contains(&30), "skips index 30");
        let most = sample(100_000, MAX_SNAPSHOTS);
        assert_eq!((most.len(), most[0], most[499]), (500, 0, 99_999));
        assert!(most.windows(2).all(|w| w[0] < w[1]));
        assert_eq!((DEFAULT_SNAPSHOTS, MIN_SNAPSHOTS, MAX_SNAPSHOTS), (60, 2, 500));
    }

    // ------------------------------------------------------------------ rules, on history-replay-repo.sh

    /// Author time of commit `n` of history-replay-repo.sh: midnight UTC, one day apart from 2020-01-01.
    fn day(n: i64) -> i64 {
        1_577_836_800 + (n - 1) * 86_400
    }

    /// The JSON text of the entry with this id in the model's `"districts"` or `"buildings"` array: from its opening
    /// brace up to the next entry. Every edition slices the JSON the same way, so the assertions read alike.
    fn entry<'a>(json: &'a str, array: &str, id: &str) -> &'a str {
        let from = json.find(&format!("\"{array}\":[")).expect(array);
        let at = from + json[from..].find(&format!("{{\"id\":\"{id}\",")).unwrap_or_else(|| panic!("no entry {id}"));
        let next = json[at + 1..].find("{\"id\":").map_or(json.len(), |i| at + 1 + i);
        &json[at..next]
    }

    fn replay(root: &Path, include_tests: bool, snapshots: usize) -> CityModel {
        let model = CityModel::build(&SourceAnalyzer::new(), root, include_tests, true, None, Some(snapshots));
        assert!(model.history.is_some());
        model
    }

    fn ids(model: &CityModel) -> Vec<String> {
        let mut ids: Vec<String> = model.buildings.iter().map(|b| model.types()[b.index].fqn.clone()).collect();
        ids.sort();
        ids
    }

    fn snapshot_types(model: &CityModel) -> Vec<usize> {
        model.history.as_ref().unwrap().iter().map(|s| s.types).collect()
    }

    /// The city is the union of today's types and every type of any snapshot. A type deleted and added again (C) is
    /// present, with -1 for the snapshot it was missing from; types that no longer exist (D, p.P) are "gone" and keep
    /// their last version's file. The statistics count today's types only.
    #[test]
    fn the_city_is_the_union_of_all_snapshots() {
        let (_dir, root) = repo("history-replay-repo.sh");
        let model = replay(&root, true, 60);
        assert_eq!(snapshot_types(&model), vec![4, 5, 3, 4]);
        assert_eq!(ids(&model), ["A", "ATest", "C", "D", "O", "p.P"]);
        let json = model.json();
        let c = entry(&json, "buildings", "C");
        assert!(c.contains(r#""hist":{"nom":[-1,0,-1,1],"noa":[-1,0,-1,0],"#), "{c}");
        assert!(!c.contains("\"gone\""));
        let d = entry(&json, "buildings", "D");
        assert!(d.contains(r#""nom":[-1,0,-1,-1]"#) && d.contains(r#""gone":true"#), "{d}");
        let p = entry(&json, "buildings", "p.P");
        assert!(p.contains(r#""file":"src/p/P.java""#) && p.contains(r#""nom":[0,-1,-1,-1]"#) && p.contains(r#""gone":true"#), "{p}");
        assert_eq!(model.stats.types, 4);
    }

    /// A type's git activity in snapshot k counts the commits of its file with an author time up to snapshot k's (the
    /// committer times, an hour later, play no part): C's file is added, deleted and added again by three authors.
    /// Where the type does not exist, the activity is 0, 0, -1 even though its file had commits.
    #[test]
    fn git_activity_is_counted_up_to_each_snapshot() {
        let (_dir, root) = repo("history-replay-repo.sh");
        let model = replay(&root, true, 60);
        let snaps = model.history.as_ref().unwrap();
        assert_eq!(snaps.iter().map(|s| s.time).collect::<Vec<_>>(), vec![day(1), day(2), day(3), day(4)]);
        assert_eq!(snaps.iter().map(|s| s.author.as_str()).collect::<Vec<_>>(), ["Ann Author", "Bob Builder", "Ann Author", "Cid Coder"]);
        let json = model.json();
        assert!(entry(&json, "buildings", "A").contains(&format!(r#""commits":[1,2,2,2],"authors":[1,2,2,2],"last":[{},{},{},{}]}}"#, day(1), day(2), day(2), day(2))));
        assert!(entry(&json, "buildings", "C").contains(&format!(r#""commits":[0,1,0,3],"authors":[0,1,0,3],"last":[-1,{},-1,{}]}}"#, day(2), day(4))));
    }

    /// A district's history marks the snapshots in which it, or a sub-district, holds an existing building: "p" only in
    /// the first. Its type count is of today's types, so 0.
    #[test]
    fn district_history_follows_its_buildings() {
        let (_dir, root) = repo("history-replay-repo.sh");
        let model = replay(&root, true, 60);
        let json = model.json();
        assert!(entry(&json, "districts", "p").contains(r#""types":0,"hist":[1,0,0,0]}"#));
        assert!(entry(&json, "districts", "(default)").contains(r#""types":4,"hist":[1,1,1,1]}"#));
        assert!(entry(&json, "districts", "").contains(r#""types":4,"hist":[1,1,1,1]}"#));
    }

    /// A's attribute count shrinks from 3 to 1. Its building has today's side, but its lot is reserved for the widest
    /// version: the footprint of side(3), centred on the building, keeps a street's width from every other building.
    #[test]
    fn lots_fit_the_widest_version() {
        use crate::layout::{side_of_noa, GAP};
        let (_dir, root) = repo("history-replay-repo.sh");
        let model = replay(&root, true, 60);
        let a = model.buildings.iter().find(|b| model.types()[b.index].fqn == "A").unwrap();
        assert_eq!(a.hist.as_ref().unwrap().noa, vec![3, 1, 1, 1]);
        assert!((a.lot.side - side_of_noa(1)).abs() < 1e-9);
        let reserved = side_of_noa(3);
        let mut touches = false;
        for b in model.buildings.iter().filter(|b| b.index != a.index && b.lot.district == a.lot.district) {
            let gap_x = (a.lot.x - b.lot.x).abs() - (reserved + b.lot.side) / 2.0;
            let gap_z = (a.lot.z - b.lot.z).abs() - (reserved + b.lot.side) / 2.0;
            assert!(gap_x.max(gap_z) >= GAP - 1e-6, "{}", model.types()[b.index].fqn);
            // Some neighbour must be closer than a street to today's smaller footprint plus the reserved margin;
            // otherwise the test would not tell the reserved lot from the plain one.
            touches |= gap_x.max(gap_z) < GAP + (reserved - side_of_noa(1)) / 2.0 - 1e-6;
        }
        assert!(touches, "a neighbour stands next to A's reserved lot");
    }

    /// With two snapshots only the first and the last commit are replayed. D, which existed only in between, is not
    /// part of the city at all; the activity is still counted over every commit up to the snapshot.
    #[test]
    fn sampling_keeps_both_ends_and_drops_types_in_between() {
        let (_dir, root) = repo("history-replay-repo.sh");
        let model = replay(&root, true, 2);
        let snaps = model.history.as_ref().unwrap();
        assert_eq!(snaps.iter().map(|s| s.subject.as_str()).collect::<Vec<_>>(), ["one", "four"]);
        let ends = vec![git::run(&root, &["rev-list", "--max-parents=0", "HEAD"]).unwrap(), git::run(&root, &["rev-parse", "HEAD"]).unwrap()];
        assert_eq!(snaps.iter().map(|s| s.commit.clone()).collect::<Vec<_>>(), ends);
        assert_eq!(ids(&model), ["A", "ATest", "C", "O", "p.P"]);
        let json = model.json();
        assert!(entry(&json, "buildings", "C").contains(r#""nom":[-1,1],"#));
        assert!(entry(&json, "buildings", "C").contains(r#""commits":[0,3],"#));
        assert!(entry(&json, "buildings", "p.P").contains(r#""nom":[0,-1],"#));
        assert!(entry(&json, "buildings", "A").contains(r#""commits":[1,2],"#));
    }

    /// Without tests, test types are left out of every snapshot (ATest is in a test directory).
    #[test]
    fn tests_are_left_out_of_every_snapshot() {
        let (_dir, root) = repo("history-replay-repo.sh");
        let model = replay(&root, false, 60);
        assert_eq!(snapshot_types(&model), vec![3, 4, 2, 3]);
        assert_eq!(ids(&model), ["A", "C", "D", "O", "p.P"]);
    }

    /// Analysing a sub-directory replays only its files (other/O.java never appears), with paths relative to it; the
    /// git activity is still found, as the history keys are absolute.
    #[test]
    fn a_sub_directory_replays_only_its_files() {
        let (_dir, top) = repo("history-replay-repo.sh");
        let model = replay(&top.join("src"), true, 60);
        assert_eq!(snapshot_types(&model), vec![3, 4, 2, 3]);
        assert_eq!(ids(&model), ["A", "ATest", "C", "D", "p.P"]);
        let json = model.json();
        assert!(entry(&json, "buildings", "p.P").contains(r#""file":"p/P.java""#));
        assert!(entry(&json, "buildings", "A").contains(r#""commits":[1,2,2,2],"#));
    }
}
