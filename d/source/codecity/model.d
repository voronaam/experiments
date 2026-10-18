/// Core data model shared by the analysers, the layout, the JSON output and the viewers.
///
/// The analysers (`codecity.analysis`) produce one `TypeDecl` per type and collect them, with the dependency graph
/// and git history, in an `Analysis`. Everything downstream (`codecity.layout`, `codecity.city`) only reads it.
/// The Java and Rust editions have the same model.
module codecity.model;

import std.array : split;

/// Supported source languages and how each one names its "packages" (the districts of the city).
enum Language
{
    java,
    typescript,
    rust,
}

/// Lower-case identifier used in the JSON (`"lang"` and the per-language stats).
string id(Language l) pure nothrow @safe
{
    final switch (l)
    {
    case Language.java: return "java";
    case Language.typescript: return "typescript";
    case Language.rust: return "rust";
    }
}

string displayName(Language l) pure nothrow @safe
{
    final switch (l)
    {
    case Language.java: return "Java";
    case Language.typescript: return "TypeScript";
    case Language.rust: return "Rust";
    }
}

/// Separator between package segments: `com.acme`, `src/ui`, `net::http`.
string separator(Language l) pure nothrow @safe
{
    final switch (l)
    {
    case Language.java: return ".";
    case Language.typescript: return "/";
    case Language.rust: return "::";
    }
}

/// A method, constructor or free function and its size/complexity.
struct MethodInfo
{
    string name;
    uint line;
    uint loc;
    uint complexity;
    uint parameters;
}

/// One type found in the code base — a building in the city: a class, interface, enum, record, struct, trait,
/// type alias, or a "module" (the free functions of one file).
struct TypeDecl
{
    Language language;
    /// Unique id, e.g. `com.acme.Order.Line`, `src/ui/App.tsx:App`, `shop::cart::Cart`.
    string fqn;
    /// Name relative to its package, e.g. `Order.Line`.
    string name;
    /// District path in the language's own notation.
    string pkg;
    /// Build module / package.json project / crate ("" when there is only one).
    string module_;
    /// Declaration kind as the viewer shows it, e.g. `class`, `record`, `enum`, `struct`, `type` or `module`.
    string kind;
    /// Absolute path of the source file, under the analysed root (as `findSources` returns it).
    string file;
    /// First and last line of the declaration, 1-based.
    uint line;
    uint endLine;
    /// Non-blank, non-comment lines inside the declaration.
    uint loc;
    /// Number of attributes (fields, record components, enum constants, …).
    uint noa;
    /// Declared in test code; such types are dropped when tests are excluded.
    bool test;
    MethodInfo[] methods;
    /// Raw names referenced in the body, resolved later against the project.
    bool[string] references;
    /// Java only: single-type imports (`import a.b.C;`) and on-demand import packages (`import a.b.*;` gives `a.b`),
    /// used to resolve `references` to fqns.
    string[] explicitImports;
    string[] wildcardImports;

    uint nom() const pure nothrow @safe { return cast(uint) methods.length; }

    /// Weighted method count: the sum of the cyclomatic complexity of all methods.
    uint complexity() const pure nothrow @safe
    {
        uint s;
        foreach (m; methods) s += m.complexity;
        return s;
    }

    /// `pkg` split at the language's separator; empty for a type outside any package.
    string[] packageSegments() const pure @safe
    {
        return pkg.length ? pkg.split(language.separator) : [];
    }
}

/// Git activity for one file.
struct FileHistory
{
    /// Non-merge commits touching the file, and distinct author e-mail addresses among them.
    uint commits;
    uint authors;
    /// Author timestamps of the newest and oldest of those commits, in Unix seconds.
    long lastCommit;
    long firstCommit;
}

/// Result of analysing a source tree.
struct Analysis
{
    /// The analysed directory (absolute).
    string root;
    /// All types, sorted by fqn.
    TypeDecl[] types;
    /// fqn → sorted fqns of other project types it uses.
    string[][string] dependencies;
    /// Absolute file path → git history; empty without git.
    FileHistory[string] history;
    /// Source files found, and how many of them had syntax errors (analysed only partially) or could not be read.
    size_t files;
    size_t failedFiles;
}
