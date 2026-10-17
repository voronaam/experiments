//! Walks a source tree, parses every supported file with its language's analyser (in parallel, cached by
//! modification time so watch-mode rebuilds only re-parse what changed), then links each language's files.

pub mod java;
#[cfg(test)]
mod tests;
pub mod rust_lang;
pub mod syntax;
pub mod text;
pub mod typescript;

use crate::model::{Analysis, Language, TypeInfo};
use rayon::prelude::*;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

pub const IGNORED_DIRS: [&str; 13] = [
    ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", "target", "build", "out", "node_modules", "dist",
    "coverage", "bower_components",
];

/// Language-specific data the linker needs (imports, impl blocks, …).
pub enum Context {
    None,
    TypeScript(typescript::Context),
    Rust(rust_lang::Context),
}

pub struct ParsedFile {
    pub types: Vec<TypeInfo>,
    pub context: Context,
    /// True when the file had syntax errors and was only partially analysed.
    pub failed: bool,
}

/// Every parsed file of one language combined into final types and project-internal dependencies.
pub struct Linked {
    pub types: Vec<TypeInfo>,
    pub dependencies: HashMap<String, HashSet<String>>,
}

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

pub fn is_ignored_dir(name: &str) -> bool {
    IGNORED_DIRS.contains(&name) || (name.starts_with('.') && name.len() > 1)
}

struct CacheEntry {
    modified: SystemTime,
    size: u64,
    parsed: Arc<ParsedFile>,
}

#[derive(Default)]
pub struct SourceAnalyzer {
    cache: Mutex<HashMap<PathBuf, CacheEntry>>,
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

    pub fn analyze(&self, root: &Path, include_tests: bool) -> Analysis {
        let files = find_sources(root);
        {
            let keep: HashSet<&PathBuf> = files.iter().collect();
            self.cache.lock().unwrap().retain(|p, _| keep.contains(p));
        }
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

        let mut by_fqn: BTreeMap<String, TypeInfo> = BTreeMap::new();
        let mut dependencies: HashMap<String, HashSet<String>> = HashMap::new();
        for (lang, files) in &by_language {
            let linked = match lang {
                Language::Java => java::link(files),
                Language::TypeScript => typescript::link(root, files),
                Language::Rust => rust_lang::link(files),
            };
            for t in linked.types {
                by_fqn.entry(t.fqn.clone()).or_insert(t);
            }
            dependencies.extend(linked.dependencies);
        }
        let types: Vec<TypeInfo> = by_fqn.into_values().filter(|t| include_tests || !t.test).collect();
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

    fn parse_cached(&self, lang: Language, root: &Path, file: &Path) -> Arc<ParsedFile> {
        let meta = std::fs::metadata(file).ok();
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

    fn parse(&self, lang: Language, root: &Path, file: &Path) -> ParsedFile {
        let code = match std::fs::read(file) {
            Ok(bytes) => match String::from_utf8(bytes) {
                Ok(s) => s,
                // like Java's ISO-8859-1 fallback: every byte becomes one char
                Err(e) => e.into_bytes().iter().map(|&b| b as char).collect(),
            },
            Err(_) => return failed(),
        };
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
                    eprintln!("warning: could not analyse {}", file.display());
                }
                failed()
            }
        }
    }
}

fn failed() -> ParsedFile {
    ParsedFile { types: Vec::new(), context: Context::None, failed: true }
}

/// Supported files under `root`, skipping build output and VCS directories; walked in parallel.
fn find_sources(root: &Path) -> Vec<PathBuf> {
    let files = Mutex::new(Vec::new());
    let root_buf = root.to_path_buf();
    ignore::WalkBuilder::new(root)
        .standard_filters(false)
        .follow_links(false)
        .filter_entry(move |e| {
            if e.path() == root_buf || !e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                return true;
            }
            !is_ignored_dir(&e.file_name().to_string_lossy())
        })
        .build_parallel()
        .run(|| {
            let files = &files;
            Box::new(move |entry| {
                if let Ok(e) = entry {
                    if e.file_type().map(|t| t.is_file()).unwrap_or(false) && language_of(e.path()).is_some() {
                        files.lock().unwrap().push(e.into_path());
                    }
                }
                ignore::WalkState::Continue
            })
        });
    let mut files = files.into_inner().unwrap();
    files.sort();
    files
}

/// Thread-local tree-sitter parser per grammar (parsers are not `Sync`), keyed by grammar name.
pub fn with_parser<R>(
    grammar: &'static str,
    language: impl FnOnce() -> tree_sitter::Language,
    f: impl FnOnce(&mut tree_sitter::Parser) -> R,
) -> R {
    thread_local! {
        static PARSERS: std::cell::RefCell<HashMap<&'static str, tree_sitter::Parser>> =
            std::cell::RefCell::new(HashMap::new());
    }
    PARSERS.with(|cell| {
        let mut map = cell.borrow_mut();
        let parser = map.entry(grammar).or_insert_with(|| {
            let mut p = tree_sitter::Parser::new();
            p.set_language(&language()).expect("grammar version compatible with tree-sitter");
            p
        });
        f(parser)
    })
}
