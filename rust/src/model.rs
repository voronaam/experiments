//! Core data model shared by the analysers, the layout, the JSON output and the viewers.
//!
//! The analysers produce [`TypeInfo`]s (with raw `references` and imports); linking turns those into the project-
//! internal dependencies of [`Analysis`]; git mining adds [`FileHistory`]. Everything else (layout, city model,
//! JSON, viewers) reads these structures and never changes them.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// Supported source languages and how each one names its "packages" (the districts of the city).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Language {
    Java,
    TypeScript,
    Rust,
}

impl Language {
    pub const ALL: [Language; 3] = [Language::Java, Language::TypeScript, Language::Rust];

    /// Identifier used in JSON and for syntax highlighting. Also the key of the language counts in the JSON stats.
    pub fn id(self) -> &'static str {
        match self {
            Language::Java => "java",
            Language::TypeScript => "typescript",
            Language::Rust => "rust",
        }
    }

    pub fn display_name(self) -> &'static str {
        match self {
            Language::Java => "Java",
            Language::TypeScript => "TypeScript",
            Language::Rust => "Rust",
        }
    }

    /// Separator between package segments: `com.acme`, `src/ui`, `net::http`.
    pub fn separator(self) -> &'static str {
        match self {
            Language::Java => ".",
            Language::TypeScript => "/",
            Language::Rust => "::",
        }
    }
}

/// A method, constructor or free function and its size/complexity.
#[derive(Clone, Debug, PartialEq)]
pub struct MethodInfo {
    pub name: String,
    /// First line of the declaration, 1-based.
    pub line: u32,
    /// Non-blank, non-comment lines, as for [`TypeInfo::loc`].
    pub loc: u32,
    /// Cyclomatic complexity: 1 plus one per decision point.
    pub complexity: u32,
    pub parameters: u32,
}

/// One type found in the code base — a building in the city. Depending on the language this is a class,
/// interface, enum, record, struct, trait, type alias, or a "module": the free functions of one file.
#[derive(Clone, Debug)]
pub struct TypeInfo {
    pub language: Language,
    /// Unique id, e.g. `com.acme.Order.Line`, `src/ui/App.tsx:App`, `shop::cart::Cart`.
    pub fqn: String,
    /// Name relative to its package, e.g. `Order.Line`.
    pub name: String,
    /// District path in the language's own notation; split with [`Language::separator`].
    pub package: String,
    /// Build module / package.json project / crate ("" when there is only one).
    pub module: String,
    /// The declaration keyword as shown in the viewer, e.g. `class`, `interface`, `struct`, `trait`, `module`.
    pub kind: &'static str,
    /// The source file, as found by the walker below the (canonical) analysed root; also the key into
    /// [`Analysis::history`].
    pub file: PathBuf,
    /// First and last line of the declaration, 1-based and inclusive.
    pub line: u32,
    pub end_line: u32,
    /// Non-blank, non-comment lines inside the declaration.
    pub loc: u32,
    /// Number of attributes (fields, record components, enum constants, …).
    pub noa: u32,
    /// Test code as each analyser recognises it (test directories, test file names, `#[cfg(test)]` in Rust); left
    /// out with `--no-tests`.
    pub test: bool,
    pub methods: Vec<MethodInfo>,
    /// Raw names referenced in the body, resolved later against the project.
    pub references: HashSet<String>,
    /// Java only (empty for the other languages): single-type imports (`import a.b.C;`) and on-demand imports
    /// (`import a.b.*;`), used by the Java linker to resolve `references`. Shared by the types of a file, so a file
    /// with many types and many imports does not hold a copy of its imports per type.
    pub explicit_imports: Arc<[String]>,
    pub wildcard_imports: Arc<[String]>,
}

impl TypeInfo {
    /// Number of methods (the default building height).
    pub fn nom(&self) -> u32 {
        self.methods.len() as u32
    }

    /// Weighted method count: the sum of the cyclomatic complexity of all methods.
    pub fn complexity(&self) -> u32 {
        self.methods.iter().map(|m| m.complexity).sum()
    }

    /// The package split into its segments, e.g. `["com", "acme"]`; empty for a type outside any package (rather
    /// than one empty segment, which `split` would give).
    pub fn package_segments(&self) -> Vec<&str> {
        if self.package.is_empty() {
            Vec::new()
        } else {
            self.package.split(self.language.separator()).collect()
        }
    }
}

/// Git activity for one file.
#[derive(Clone, Copy, Debug)]
pub struct FileHistory {
    /// Non-merge commits that touched the file.
    pub commits: u32,
    /// Distinct author emails among those commits.
    pub authors: u32,
    /// Author times of the newest and the oldest of those commits, in seconds since the Unix epoch.
    pub last_commit: i64,
    pub first_commit: i64,
}

/// Result of analysing a source tree.
#[derive(Debug)]
pub struct Analysis {
    pub root: PathBuf,
    /// All types, sorted by fqn.
    pub types: Vec<TypeInfo>,
    /// fqn → fqns of other project types it uses: sorted, without itself, and only types that are in `types`.
    pub dependencies: HashMap<String, Vec<String>>,
    /// Keyed by absolute file path (see [`TypeInfo::file`]); empty without git or with `--no-git`.
    pub history: HashMap<PathBuf, FileHistory>,
    /// Supported source files found, and how many of them had syntax errors (or could not be read or analysed).
    pub files: usize,
    pub failed_files: usize,
}

/// `a/b/c` with forward slashes regardless of platform.
pub fn slashes(p: &Path) -> String {
    p.to_string_lossy().replace('\\', "/")
}
