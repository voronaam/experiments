//! Walks a source tree, parses every supported file with its language's analyser (in parallel, cached by
//! modification time so watch-mode rebuilds only re-parse what changed), then links each language's files.
//!
//! This is the first half of the pipeline (walk → parse/analyse → link dependencies → git history → city model →
//! layout → JSON → viewers). [`SourceAnalyzer::analyze`] produces an [`Analysis`]; `city.rs` fills in its git history
//! and turns it into the city model.
//!
//! # Two phases per language
//! 1. **Parse** (per file, in parallel, cacheable): each analyser (`java`, `typescript`, `rust_lang`) turns one file
//!    into [`TypeInfo`]s with metrics and the *raw* names they reference, plus a language-specific [`Context`]
//!    (imports, declared names, `impl` blocks) that the linker needs. A file's result depends only on that file and
//!    on a few manifest files (`package.json`, `Cargo.toml`), which is what makes per-file caching possible.
//! 2. **Link** (per language, sequential): all parsed files of one language are combined, raw references are
//!    resolved to fully qualified names of project types, and (Rust only) `impl` blocks are attached to their types.
//!    Linking is cheap compared with parsing and needs to see every file, so it runs again on every analysis.
//!
//! Languages are linked independently: there are no cross-language dependencies.

pub mod java;
pub mod java_parse;
#[cfg(test)]
mod tests;
pub mod rust_lang;
pub mod rust_parse;
pub mod text;
pub mod typescript;
pub mod typescript_parse;

use crate::model::{Analysis, Language, TypeInfo};
use rayon::prelude::*;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

/// Directories never descended into: VCS metadata, IDE/build-tool state, build output and installed dependencies.
/// Analysing them would add generated or third-party code to the city (and `node_modules` alone can be huge).
pub const IGNORED_DIRS: [&str; 13] = [
    ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", "target", "build", "out", "node_modules", "dist",
    "coverage", "bower_components",
];

/// Language-specific data the linker needs (imports, impl blocks, …).
pub enum Context {
    /// Java needs no extra context: its imports are stored on each [`TypeInfo`]. Also used for failed files.
    None,
    TypeScript(typescript::Context),
    Rust(rust_lang::Context),
}

/// The result of parsing one file. Shared through `Arc` because the cache and the current analysis both hold it.
pub struct ParsedFile {
    pub types: Vec<TypeInfo>,
    pub context: Context,
    /// True when the file had syntax errors and was only partially analysed.
    pub failed: bool,
}

/// Every parsed file of one language combined into final types and project-internal dependencies.
pub struct Linked {
    pub types: Vec<TypeInfo>,
    /// fqn → fqns it depends on. Only project types appear as targets; external references are dropped.
    pub dependencies: HashMap<String, HashSet<String>>,
}

/// The language a file is analysed as, chosen by file name alone. `None` means the file is skipped.
pub fn language_of(file: &Path) -> Option<Language> {
    let name = file.file_name()?.to_str()?;
    if name.ends_with(".java") {
        Some(Language::Java)
    } else if typescript::accepts(name) {
        Some(Language::TypeScript)
    } else if name.ends_with(".rs") {
        Some(Language::Rust)
    } else {
        None
    }
}

/// True for directories the walk skips: the [`IGNORED_DIRS`] and any hidden directory (`.name`). The length check
/// keeps `.` itself (the root when given as a relative path) from counting as hidden.
pub fn is_ignored_dir(name: &str) -> bool {
    IGNORED_DIRS.contains(&name) || (name.starts_with('.') && name.len() > 1)
}

/// Larger source files are not parsed and count as failed (16 MiB, far beyond any hand-written file). Reading and
/// parsing take memory and time in proportion to the size, so a crafted multi-gigabyte file would otherwise exhaust
/// memory or stall the analysis.
pub const MAX_FILE_BYTES: u64 = 16 * 1024 * 1024;

/// False for a file or directory name that is not valid UTF-8, or that contains U+FFFD: the Java edition only sees
/// such names with U+FFFD in place of the bad bytes, so all three editions skip both and see the same files. Skipping
/// them keeps undecodable bytes out of paths, module names and the JSON.
pub fn is_valid_name(name: &std::ffi::OsStr) -> bool {
    name.to_str().is_some_and(|n| !n.contains('\u{FFFD}'))
}

/// Manifest file names whose content feeds into the parse of other files: a `.rs` file's crate name comes from the
/// nearest `Cargo.toml`, a `.ts` file's project from the nearest `package.json`.
pub const MANIFESTS: [&str; 2] = ["Cargo.toml", "package.json"];

/// What every manifest contributes to the parse of other files (see [`manifest_stamp`]), sorted by path: when this
/// changes, cached parses may be stale.
type ManifestStamps = Vec<(PathBuf, Option<String>)>;

/// A manifest's path plus what the analysers read from it, so the stamp changes exactly when a parse could: for a
/// `Cargo.toml` the crate name it declares (the Rust analyser uses nothing else from it); a `package.json` matters
/// only by existing (the TypeScript analyser only looks for one). An edit that only bumps a version or a dependency
/// therefore keeps the parse cache, which matters for the history replay ([`crate::history`]), where such edits come
/// with most snapshots.
fn manifest_stamp(manifest: PathBuf) -> (PathBuf, Option<String>) {
    let name = if manifest.file_name().is_some_and(|n| n == "Cargo.toml") { manifest.parent().and_then(rust_lang::read_crate_name) } else { None };
    (manifest, name)
}

/// A cached parse result. The file counts as unchanged while both its modification time and its size match; the size
/// catches rewrites within the timestamp resolution of the file system.
struct CacheEntry {
    modified: SystemTime,
    size: u64,
    parsed: Arc<ParsedFile>,
}

/// Parses source trees, keeping per-file results between runs. One instance lives for the whole process and is
/// shared (via `Arc`) with the watch-mode rebuild, so each rebuild only re-parses files that changed.
#[derive(Default)]
pub struct SourceAnalyzer {
    /// Absolute file path → last parse. Locked briefly by the rayon workers in `parse_cached`; never held while
    /// parsing, so workers do not serialise on it.
    cache: Mutex<HashMap<PathBuf, CacheEntry>>,
    /// The manifest stamps of the previous analysis. Any difference (a crate renamed, a manifest added or removed)
    /// empties `cache`.
    manifests: Mutex<ManifestStamps>,
    /// Set once the first analyser panic has been reported, so a buggy analyser does not flood stderr.
    reported_failure: AtomicBool,
}

impl SourceAnalyzer {
    pub fn new() -> Self {
        Self::default()
    }

    #[cfg(test)]
    pub fn cache_len(&self) -> usize {
        self.cache.lock().unwrap().len()
    }

    /// Analyses every supported file under `root`. Test types are dropped unless `include_tests` is set. The returned
    /// [`Analysis`] has an empty `history`; the caller adds git history.
    pub fn analyze(&self, root: &Path, include_tests: bool) -> Analysis {
        let (files, manifests) = find_sources(root);
        {
            let mut cache = self.cache.lock().unwrap();
            // A manifest change that matters (a crate renamed, a manifest added or removed) can change the parse of
            // files that did not change themselves (their crate or project), so start over. Such changes are rare, so
            // dropping every language's parses is good enough.
            let stamps: ManifestStamps = manifests.into_iter().map(manifest_stamp).collect();
            let mut last = self.manifests.lock().unwrap();
            if *last != stamps {
                cache.clear();
                *last = stamps;
            }
            // Forget deleted (or now ignored) files so the cache does not grow without bound in watch mode.
            let keep: HashSet<&PathBuf> = files.iter().collect();
            cache.retain(|p, _| keep.contains(p));
        }
        // Parse in parallel. Rayon's `collect` keeps the input order, and `files` is sorted, so the order below is
        // deterministic.
        let parsed: Vec<(Language, Arc<ParsedFile>)> = files
            .par_iter()
            .filter_map(|f| language_of(f).map(|l| (l, self.parse_cached(l, root, f))))
            .collect();

        let mut failed = 0;
        let mut by_language: BTreeMap<Language, Vec<Arc<ParsedFile>>> = BTreeMap::new();
        for (lang, p) in parsed {
            if p.failed {
                failed += 1;
            }
            by_language.entry(lang).or_default().push(p);
        }

        // Sorted by fqn, so the type list (and everything built from it) does not depend on parse order.
        let mut by_fqn: BTreeMap<String, TypeInfo> = BTreeMap::new();
        let mut dependencies: HashMap<String, HashSet<String>> = HashMap::new();
        for (lang, files) in &by_language {
            let linked = match lang {
                Language::Java => java::link(files),
                Language::TypeScript => typescript::link(root, files),
                Language::Rust => rust_lang::link(files),
            };
            // fqn formats differ per language (`a.b.C`, `src/x.ts:C`, `krate::m::C`), so collisions across
            // languages are not expected; should one happen, the first language (in `Language` order) wins and the
            // later duplicate is dropped together with its dependencies.
            let mut linked_deps = linked.dependencies;
            for t in linked.types {
                if !by_fqn.contains_key(&t.fqn) {
                    if let Some(d) = linked_deps.remove(&t.fqn) {
                        dependencies.insert(t.fqn.clone(), d);
                    }
                    by_fqn.insert(t.fqn.clone(), t);
                }
            }
        }
        let types: Vec<TypeInfo> = by_fqn.into_values().filter(|t| include_tests || !t.test).collect();
        // Final dependency lists: only edges to types that survived the test filter, no self-edges, and sorted so
        // the JSON output is stable.
        let kept: HashSet<&str> = types.iter().map(|t| t.fqn.as_str()).collect();
        let mut deps = HashMap::with_capacity(types.len());
        for t in &types {
            let mut out: Vec<String> = dependencies
                .get(&t.fqn)
                .map(|d| d.iter().filter(|x| kept.contains(x.as_str()) && **x != t.fqn).cloned().collect())
                .unwrap_or_default();
            out.sort();
            deps.insert(t.fqn.clone(), out);
        }
        Analysis {
            root: root.to_path_buf(),
            types,
            dependencies: deps,
            history: HashMap::new(),
            files: files.len(),
            failed_files: failed,
        }
    }

    /// Returns the cached result if the file's modification time and size are unchanged, otherwise parses it and
    /// caches the result. Files whose modification time cannot be read are parsed every time and never cached.
    ///
    /// The metadata is read without following links. A path that is no longer a regular file (replaced by a link or
    /// a FIFO since the walk) and a file over [`MAX_FILE_BYTES`] are not read at all: they fail, uncached.
    ///
    /// Called concurrently from rayon workers: the lock is taken for the lookup and again for the insert, never
    /// across the parse. Two workers never parse the same path because each path occurs once in the file list.
    fn parse_cached(&self, lang: Language, root: &Path, file: &Path) -> Arc<ParsedFile> {
        let meta = std::fs::symlink_metadata(file).ok();
        if meta.as_ref().is_some_and(|m| !m.is_file() || m.len() > MAX_FILE_BYTES) {
            return Arc::new(failed());
        }
        let modified = meta.as_ref().and_then(|m| m.modified().ok());
        let size = meta.as_ref().map(|m| m.len()).unwrap_or(0);
        if let Some(modified) = modified {
            if let Some(e) = self.cache.lock().unwrap().get(file) {
                if e.modified == modified && e.size == size {
                    return e.parsed.clone();
                }
            }
        }
        let parsed = Arc::new(self.parse(lang, root, file));
        if let Some(modified) = modified {
            self.cache
                .lock()
                .unwrap()
                .insert(file.to_path_buf(), CacheEntry { modified, size, parsed: parsed.clone() });
        }
        parsed
    }

    /// Reads and parses one file. Never fails: unreadable files and analyser panics yield an empty, failed result.
    fn parse(&self, lang: Language, root: &Path, file: &Path) -> ParsedFile {
        let code = match std::fs::read(file) {
            Ok(bytes) => match String::from_utf8(bytes) {
                Ok(s) => s,
                // like Java's ISO-8859-1 fallback: every byte becomes one char
                Err(e) => e.into_bytes().iter().map(|&b| b as char).collect(),
            },
            Err(_) => return failed(),
        };
        // Catch panics so that one file the analyser cannot handle does not abort the whole (parallel) analysis.
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| match lang {
            Language::Java => java::parse(root, file, &code),
            Language::TypeScript => typescript::parse(root, file, &code),
            Language::Rust => rust_lang::parse(root, file, &code),
        }));
        match result {
            Ok(p) => p,
            Err(_) => {
                // A bug in an analyser, not a syntax error in the file: report it (once) rather than hide it.
                if !self.reported_failure.swap(true, Ordering::Relaxed) {
                    eprintln!("warning: could not analyse {}", crate::printable(&file.display().to_string()));
                }
                failed()
            }
        }
    }
}

/// The result for a file that could not be read or analysed: no types, counted as failed.
fn failed() -> ParsedFile {
    ParsedFile { types: Vec::new(), context: Context::None, failed: true }
}

/// Supported files under `root`, and separately the [`MANIFESTS`], skipping build output and VCS directories; walked
/// in parallel.
///
/// `standard_filters(false)` turns off the `ignore` crate's own rules (.gitignore, hidden files), so the result does
/// not depend on ignore files; only [`is_ignored_dir`] prunes directories. Symlinks are not followed, neither to
/// directories (which also rules out cycles) nor to files: a link's own file type is not a file, so a link to a file
/// outside the tree is never read. FIFOs, devices and sockets are not files either, so nothing is opened that could
/// block. Entries whose name fails [`is_valid_name`] are skipped. The root itself is never pruned, even if its name looks ignored (e.g. a project checked out as
/// `build/`). The walker threads push into shared vectors, so the lists are sorted afterwards to be deterministic.
fn find_sources(root: &Path) -> (Vec<PathBuf>, Vec<PathBuf>) {
    let files = Mutex::new(Vec::new());
    let manifests = Mutex::new(Vec::new());
    let root_buf = root.to_path_buf();
    ignore::WalkBuilder::new(root)
        .standard_filters(false)
        .follow_links(false)
        .filter_entry(move |e| {
            if e.path() == root_buf {
                return true;
            }
            if !is_valid_name(e.file_name()) {
                return false;
            }
            !e.file_type().map(|t| t.is_dir()).unwrap_or(false) || !is_ignored_dir(&e.file_name().to_string_lossy())
        })
        .build_parallel()
        .run(|| {
            // Called once per walker thread to create that thread's visitor.
            let (files, manifests) = (&files, &manifests);
            Box::new(move |entry| {
                if let Ok(e) = entry {
                    if e.file_type().map(|t| t.is_file()).unwrap_or(false) {
                        if language_of(e.path()).is_some() {
                            files.lock().unwrap().push(e.into_path());
                        } else if MANIFESTS.contains(&e.file_name().to_string_lossy().as_ref()) {
                            manifests.lock().unwrap().push(e.into_path());
                        }
                    }
                }
                ignore::WalkState::Continue
            })
        });
    let mut files = files.into_inner().unwrap();
    files.sort();
    let mut manifests = manifests.into_inner().unwrap();
    manifests.sort();
    (files, manifests)
}

/// The walk over a hostile tree (the Java and D editions have the same test): links to files and directories outside
/// the tree, a link cycle, a FIFO, a name that is not UTF-8 and an oversized file. Only the two ordinary files count;
/// the oversized one is found but fails, and nothing blocks or escapes the tree.
#[cfg(all(test, unix))]
mod walk_tests {
    use super::*;

    #[test]
    fn walk_stays_inside_the_tree_and_skips_special_files() {
        use std::os::unix::ffi::OsStrExt;
        use std::os::unix::fs::symlink;
        let tmp = tempfile::tempdir().unwrap();
        let tmp = std::fs::canonicalize(tmp.path()).unwrap();
        let outside = tmp.join("outside");
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(outside.join("Secret.java"), "class Secret {}").unwrap();
        let src = tmp.join("root/src");
        std::fs::create_dir_all(&src).unwrap();
        std::fs::write(src.join("A.java"), "class A {}").unwrap();
        std::fs::write(src.join("B.ts"), "export class B {}").unwrap();
        symlink(outside.join("Secret.java"), src.join("Link.java")).unwrap();
        symlink(&outside, src.join("outlink")).unwrap();
        symlink(&src, src.join("loop")).unwrap();
        std::fs::File::create(src.join("Big.java")).unwrap().set_len(MAX_FILE_BYTES + 1).unwrap(); // sparse
        let status = std::process::Command::new("mkfifo").arg(src.join("Fifo.java")).status().unwrap();
        assert!(status.success());
        std::fs::write(src.join(std::ffi::OsStr::from_bytes(b"B\xffd.java")), "class Bad {}").unwrap();

        let a = SourceAnalyzer::new().analyze(&tmp.join("root"), true);
        let fqns: Vec<&str> = a.types.iter().map(|t| t.fqn.as_str()).collect();
        assert_eq!(fqns, ["A", "src/B.ts:B"]);
        assert_eq!(a.files, 3); // A.java, B.ts and Big.java
        assert_eq!(a.failed_files, 1); // Big.java is too large to parse
    }
}
