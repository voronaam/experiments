/// Core data model shared by the analysers, the layout, the JSON output and the viewers.
module codecity.model;

import std.array : split;

/// Supported source languages and how each one names its "packages" (the districts of the city).
enum Language
{
    java,
    typescript,
    rust,
}

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
    string kind;
    string file;
    uint line;
    uint endLine;
    /// Non-blank, non-comment lines inside the declaration.
    uint loc;
    /// Number of attributes (fields, record components, enum constants, …).
    uint noa;
    bool test;
    MethodInfo[] methods;
    /// Raw names referenced in the body, resolved later against the project.
    bool[string] references;
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

    string[] packageSegments() const pure @safe
    {
        return pkg.length ? pkg.split(language.separator) : [];
    }
}

/// Git activity for one file.
struct FileHistory
{
    uint commits;
    uint authors;
    long lastCommit;
    long firstCommit;
}

/// Result of analysing a source tree.
struct Analysis
{
    string root;
    /// All types, sorted by fqn.
    TypeDecl[] types;
    /// fqn → sorted fqns of other project types it uses.
    string[][string] dependencies;
    FileHistory[string] history;
    size_t files;
    size_t failedFiles;
}
