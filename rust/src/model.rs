//! Core data model shared by the analysers, the layout, the JSON output and the viewers.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

/// Supported source languages and how each one names its "packages" (the districts of the city).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Language {
    Java,
    TypeScript,
    Rust,
}

impl Language {
    pub const ALL: [Language; 3] = [Language::Java, Language::TypeScript, Language::Rust];

    /// Identifier used in JSON and for syntax highlighting.
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
    pub line: u32,
    pub loc: u32,
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
    pub kind: &'static str,
    pub file: PathBuf,
    pub line: u32,
    pub end_line: u32,
    /// Non-blank, non-comment lines inside the declaration.
    pub loc: u32,
    /// Number of attributes (fields, record components, enum constants, …).
    pub noa: u32,
    pub test: bool,
    pub methods: Vec<MethodInfo>,
    /// Raw names referenced in the body, resolved later against the project.
    pub references: HashSet<String>,
    pub explicit_imports: Vec<String>,
    pub wildcard_imports: Vec<String>,
}

impl TypeInfo {
    pub fn nom(&self) -> u32 {
        self.methods.len() as u32
    }

    /// Weighted method count: the sum of the cyclomatic complexity of all methods.
    pub fn complexity(&self) -> u32 {
        self.methods.iter().map(|m| m.complexity).sum()
    }

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
    pub commits: u32,
    pub authors: u32,
    pub last_commit: i64,
    pub first_commit: i64,
}

/// Result of analysing a source tree.
#[derive(Debug)]
pub struct Analysis {
    pub root: PathBuf,
    /// All types, sorted by fqn.
    pub types: Vec<TypeInfo>,
    /// fqn → fqns of other project types it uses.
    pub dependencies: HashMap<String, Vec<String>>,
    pub history: HashMap<PathBuf, FileHistory>,
    pub files: usize,
    pub failed_files: usize,
}

/// `a/b/c` with forward slashes regardless of platform.
pub fn slashes(p: &Path) -> String {
    p.to_string_lossy().replace('\\', "/")
}
