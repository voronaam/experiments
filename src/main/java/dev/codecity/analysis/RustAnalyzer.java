package dev.codecity.analysis;

import dev.codecity.analysis.RustParse.Node;
import dev.codecity.analysis.RustParse.NodeKind;
import dev.codecity.analysis.RustParse.TokKind;
import dev.codecity.analysis.RustParse.Tree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rust support, on the in-house parser {@link RustParse}. Crates and modules are districts; structs, enums, unions and traits are buildings whose
 * methods are collected from every {@code impl} block in the crate; each module's free functions and constants form a
 * "module" building. {@code use} paths ({@code crate::}, {@code super::}, {@code self::}, groups, globs, aliases) are
 * resolved so dependencies connect across files.
 *
 * <h2>Identity</h2>
 * The crate is found through the nearest {@code Cargo.toml} with a {@code [package]} name (dashes become
 * underscores, as in Rust paths). The module path follows from the file's location under the crate's source root
 * ({@code src/net/http.rs} → {@code net::http}) plus any inline {@code mod} blocks. fqns are absolute paths starting
 * with the crate name: {@code shop::cart::Cart} for a type, and {@code shop::cart::{fn}} for the module building
 * that holds the free functions and constants of {@code shop::cart}. The package (district) is the module path
 * without the crate; the crate is the build module.
 *
 * <h2>Two phases</h2>
 * Parsing records, per file, the {@link Scope} of every module (its {@code use} declarations) and every
 * {@link Impl} block without resolving anything, since an impl or a {@code use} target may live in a file not parsed
 * yet. {@link #link} then resolves impl targets, merges impl blocks into their types and resolves references in
 * the scope where they were written. The Rust and D editions mirror this analyser.
 */
public final class RustAnalyzer implements LanguageSupport {

    /**
     * The {@code name = "…"} line of the {@code [package]} table. {@code (?m)} lets {@code ^} match at line starts;
     * the lines skipped before the name must not start with {@code [} (after indentation), which keeps the search
     * inside the table, as the next table header starts with {@code [}. A {@code [} elsewhere on a line (e.g.
     * {@code authors = ["…"]}) does not stop it.
     */
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "(?m)^\\[package\\][^\\n]*\\n(?:[ \\t\\r]*(?:[^\\[\\s][^\\n]*)?\\n)*?[ \\t]*name\\s*=\\s*\"([^\"]+)\"");
    /**
     * Last segment of a module building's fqn ({@code shop::cart::{fn}}). Braces cannot occur in a Rust identifier, so
     * the id never collides with a real item.
     */
    static final String FUNCTIONS = "{fn}";

    /**
     * Directory → the crate whose {@code Cargo.toml} sits in it, or empty if none does. Shared by the parsing threads
     * and cleared by {@link #link}, so each analysis reads the manifests afresh and a {@code Cargo.toml} added,
     * removed or edited in watch mode applies to the files parsed after it.
     */
    private final Map<Path, Optional<Crate>> crates = new ConcurrentHashMap<>();

    /** @param name the crate name as used in paths (underscores); {@code dir} holds its {@code Cargo.toml} */
    private record Crate(String name, Path dir) {
    }

    /**
     * Name-resolution scope of one module.
     *
     * @param path    absolute module path, starting with the crate name
     * @param uses    alias → path as written in the {@code use} declaration
     * @param globs   paths imported with {@code ::*}
     *
     * <p>{@code uses} and {@code globs} are filled while the module is parsed (they are mutable on purpose) and only
     * read during linking.
     */
    record Scope(List<String> path, Map<String, List<String>> uses, List<List<String>> globs) {
        String crate() {
            return path.getFirst();
        }
    }

    /**
     * An {@code impl} block, attached to its type during linking.
     *
     * @param scope   the module the impl is written in; its names are resolved there, not in the type's module
     * @param district scope deciding the district of a module building created for the impl, see
     *                 {@link FileState#visitItems}
     * @param label    name of such a module building, as in {@link FileState#visitItems}
     * @param type    the implementing type as written, generics and references stripped ({@code Vec}, {@code a::S})
     * @param trait   the implemented trait as written, or null for an inherent impl
     * @param refs    names used in the impl body
     * @param loc     LOC of the whole block, added to the type's LOC
     */
    record Impl(Scope scope, Scope district, String label, String type, String trait, List<MethodInfo> methods,
                Set<String> refs, int loc, Path file, int line, boolean test) {
    }

    /**
     * Linker data of one file.
     *
     * @param scopes fqn of every building in the file, and path of every module, → the scope its names resolve in
     */
    record Context(Map<String, Scope> scopes, List<Impl> impls) {
    }

    @Override
    public Language language() {
        return Language.RUST;
    }

    @Override
    public boolean accepts(Path file) {
        return file.getFileName().toString().endsWith(".rs");
    }

    /**
     * Extracts one file. Files in {@code tests/} or {@code benches/} of a crate (integration tests and benchmarks)
     * are test code; so are items under {@code #[cfg(test)]}, see {@link FileState#visitItems}.
     */
    @Override
    public ParsedFile parse(Path root, Path file, String code) {
        Tree tree = RustParse.parse(code);
        Crate crate = crateOf(root, file.getParent());
        Path inCrate = crate.dir().relativize(file);
        List<String> modulePath = modulePath(sourceRoot(crate.dir(), file.getParent()).relativize(file));
        boolean test = inCrate.startsWith("tests") || inCrate.startsWith("benches");
        List<String> absolute = new ArrayList<>(List.of(crate.name()));
        absolute.addAll(modulePath);
        String module = crate.name(); // the layout only shows module districts when there are several

        FileState st = new FileState(tree, SourceText.lines(code), file, module);
        Scope scope = newScope(absolute);
        st.visitItems(0, scope, scope, file.getFileName().toString(), test);
        return new ParsedFile(file, st.types, new Context(st.scopes, st.impls), tree.errors);
    }

    private static Scope newScope(List<String> path) {
        return new Scope(List.copyOf(path), new LinkedHashMap<>(), new ArrayList<>());
    }

    /**
     * The directory module paths are relative to: the nearest one (within the crate) holding the crate root file
     * {@code lib.rs} or {@code main.rs}. Usually {@code src/}, but crates such as ripgrep keep {@code main.rs}
     * elsewhere; files outside any such directory (tests/, benches/) fall back to the crate directory.
     */
    private static Path sourceRoot(Path crateDir, Path dir) {
        for (Path d = dir; d != null && d.startsWith(crateDir); d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("lib.rs")) || Files.isRegularFile(d.resolve("main.rs"))) {
                return d;
            }
            if (d.equals(crateDir)) {
                break;
            }
        }
        return crateDir;
    }

    /**
     * {@code src/lib.rs} → [], {@code src/net/mod.rs} → [net], {@code src/net/http.rs} → [net, http].
     *
     * <p>The argument is relative to the source root, so the leading {@code src} is normally absent already; it is
     * dropped here as well in case it is not. Only a top-level {@code lib.rs}/{@code main.rs} is the crate root;
     * {@code mod.rs} stands for its directory. A {@code lib.rs}/{@code main.rs} in a sub-directory is an ordinary
     * module.
     */
    static List<String> modulePath(Path inCrate) {
        List<String> parts = new ArrayList<>();
        for (Path p : inCrate) {
            parts.add(p.toString());
        }
        if (!parts.isEmpty() && parts.getFirst().equals("src")) {
            parts.removeFirst();
        }
        if (parts.isEmpty()) {
            return parts;
        }
        String last = parts.removeLast().replaceFirst("\\.rs$", "");
        boolean crateRoot = parts.isEmpty() && (last.equals("lib") || last.equals("main"));
        if (!crateRoot && !last.equals("mod")) {
            parts.add(last);
        }
        return parts;
    }

    // ------------------------------------------------------------------ per-file extraction

    /** Per-file extraction state. Created for one {@link #parse} call and used by one thread only. */
    private final class FileState {
        final Tree tree;
        final String[] lines;
        final Path file;
        final String module;
        final List<TypeInfo> types = new ArrayList<>();
        final List<Impl> impls = new ArrayList<>();
        /** See {@link Context#scopes}. */
        final Map<String, Scope> scopes = new HashMap<>();

        FileState(Tree tree, String[] lines, Path file, String module) {
            this.tree = tree;
            this.lines = lines;
            this.file = file;
            this.module = module;
        }

        /** The module path without the crate name, e.g. {@code net::http}; "" for the crate root. */
        String packageOf(Scope scope) {
            return String.join("::", scope.path().subList(1, scope.path().size()));
        }

        String fqn(Scope scope, String name) {
            return String.join("::", scope.path()) + "::" + name;
        }

        /** Lines of code of node {@code n}. */
        int loc(int n) {
            return SourceText.countLoc(lines, tree.firstLine(n), tree.lastLine(n));
        }

        /** The item's name (its {@code name} token), "" if it has none. */
        String name(int n) {
            return tree.tokText(tree.node(n).name);
        }

        /**
         * Visits the items of a file or an inline {@code mod} body (the children of a {@code FILE} or
         * {@code DECL_BODY} node), adding buildings, impls and {@code use} entries, and finally the module building if
         * the module has free functions, constants or statics.
         *
         * <p>Attributes are separate sibling nodes that precede their item. {@code cfgTest} remembers whether one of
         * them is {@code #[cfg(test)]} and applies to the next item only. Only that exact form is recognised (with
         * any spacing), not e.g. {@code #[cfg(all(test, …))]}; inner attributes ({@code #![cfg(test)]}) are not
         * looked at. Any other node in between, including a comment (the parser records one as a
         * {@code COMMENT} node), ends the attribute's effect.
         *
         * <p>{@code mod name;} (without a body) declares a module in another file, which is parsed on its own;
         * {@code mod name { … }} is visited recursively with a child scope. Macros, type aliases, {@code extern}
         * blocks and the like are ignored, and so is a function without a body (a signature, which valid code only
         * has in traits and {@code extern} blocks).
         *
         * @param container the {@code FILE} or {@code DECL_BODY} node
         * @param scope     module whose names are in scope, which also decides the fqns
         * @param district  scope whose module path decides the district; differs from {@code scope} inside
         *                  {@code #[cfg(test)]} modules, which stay in their parent's district instead of adding one
         * @param label     name of the module building: the file name, or {@code mod name} for an inline module
         * @param test      everything in here is test code
         */
        void visitItems(int container, Scope scope, Scope district, String label, boolean test) {
            List<MethodInfo> functions = new ArrayList<>();
            Set<String> functionRefs = new HashSet<>();
            int constants = 0;
            int moduleLoc = 0;
            // Line range of the module building: from the first to the last free function or constant.
            int first = Integer.MAX_VALUE;
            int last = 0;
            boolean cfgTest = false;
            for (int item : tree.children(container)) {
                NodeKind kind = tree.node(item).kind;
                if (kind == NodeKind.ATTR) {
                    // Spaces removed so that "#[cfg( test )]" matches too.
                    String attr = tree.nodeText(item).replace(" ", "");
                    cfgTest |= attr.contains("cfg(test)");
                    continue;
                }
                boolean isTest = test || cfgTest;
                switch (kind) {
                    case USE -> {
                        List<Integer> children = tree.children(item);
                        if (!children.isEmpty()) {
                            expandUse(children.getLast(), List.of(), scope);
                        }
                    }
                    case STRUCT, UNION -> addType(item, scope, district, kind == NodeKind.UNION ? "union" : "struct",
                            tree.node(item).value, List.of(), isTest);
                    case ENUM -> addType(item, scope, district, "enum", tree.node(item).value, List.of(), isTest);
                    case TRAIT -> addType(item, scope, district, "trait", 0,
                            functionsIn(tree.child(item, NodeKind.DECL_BODY)), isTest);
                    case IMPL -> addImpl(item, scope, district, label, isTest);
                    case FN, CONST, STATIC -> {
                        if (kind == NodeKind.FN) {
                            if (tree.child(item, NodeKind.BODY) < 0) {
                                break; // a signature: valid code has none at module level
                            }
                            functions.add(function(item));
                        } else {
                            constants++;
                        }
                        collectRefs(item, functionRefs);
                        moduleLoc += loc(item);
                        first = Math.min(first, tree.firstLine(item));
                        last = Math.max(last, tree.lastLine(item));
                    }
                    case MOD -> {
                        int body = tree.child(item, NodeKind.DECL_BODY);
                        if (body >= 0) {
                            List<String> child = new ArrayList<>(scope.path());
                            child.add(name(item));
                            Scope childScope = newScope(child);
                            visitItems(body, childScope, cfgTest ? district : childScope, "mod " + child.getLast(),
                                    isTest);
                        }
                    }
                    default -> {
                    }
                }
                // The attribute applied to this item only.
                cfgTest = false;
            }
            // Registered by module path too, so a module building created later for impls (see link) finds its scope.
            scopes.put(String.join("::", scope.path()), scope);
            if (!functions.isEmpty() || constants > 0) {
                String id = fqn(scope, FUNCTIONS);
                scopes.put(id, scope);
                types.add(new TypeInfo(Language.RUST, id, label, packageOf(district), module, "module", file,
                        first, last, moduleLoc, constants, test, List.copyOf(functions), Set.copyOf(functionRefs),
                        List.of(), List.of()));
            }
        }

        /**
         * Adds a struct, union, enum or trait building. Its own methods are a trait's declared and default methods;
         * methods from {@code impl} blocks are merged in by {@link #link}. {@code noa} is the field or variant count
         * the parser stored on the item.
         */
        void addType(int item, Scope scope, Scope district, String kind, int noa, List<MethodInfo> methods,
                     boolean test) {
            String name = name(item);
            Set<String> refs = new HashSet<>();
            collectRefs(item, refs);
            String id = fqn(scope, name);
            scopes.put(id, scope);
            types.add(new TypeInfo(Language.RUST, id, name, packageOf(district), module, kind, file,
                    tree.firstLine(item), tree.lastLine(item), loc(item), noa, test, List.copyOf(methods),
                    Set.copyOf(refs), List.of(), List.of()));
        }

        /**
         * Records an impl block for the linker. References come from the body only: the implemented type is the
         * target, not a dependency, and the trait is resolved separately in {@link #link}.
         */
        void addImpl(int item, Scope scope, Scope district, String label, boolean test) {
            int body = tree.child(item, NodeKind.DECL_BODY);
            int trait = tree.child(item, NodeKind.IMPL_TRAIT);
            Set<String> refs = new HashSet<>();
            collectRefs(body < 0 ? item : body, refs);
            impls.add(new Impl(scope, district, label, typeName(tree.child(item, NodeKind.IMPL_TYPE)),
                    trait < 0 ? null : typeName(trait), functionsIn(body), refs, loc(item), file,
                    tree.firstLine(item), test));
        }

        /**
         * {@code S<T>} → {@code S}; {@code crate::m::Other} stays a path; references and pointers are unwrapped
         * ({@code &S}, {@code *const S} → {@code S}). The parser has already found this "core" of the
         * {@code IMPL_TYPE}/{@code IMPL_TRAIT} node's type (its {@code name} to {@code value} tokens). Other type
         * forms ({@code dyn Trait}, tuples, …) are their source text, which then resolves to nothing.
         */
        String typeName(int n) {
            if (n < 0) {
                return "";
            }
            Node node = tree.node(n);
            return tree.rangeText(node.name, node.value);
        }

        /** Functions in an impl or trait body, with or without a body (a trait method without a default). */
        List<MethodInfo> functionsIn(int body) {
            List<MethodInfo> list = new ArrayList<>();
            if (body >= 0) {
                for (int m : tree.children(body)) {
                    if (tree.node(m).kind == NodeKind.FN) {
                        list.add(function(m));
                    }
                }
            }
            return list;
        }

        /** Metrics of one function. The parser counted its parameters ({@code self} is not counted). */
        MethodInfo function(int fn) {
            int body = tree.child(fn, NodeKind.BODY);
            return new MethodInfo(name(fn), tree.firstLine(fn), loc(fn), body < 0 ? 1 : complexity(tree, body),
                    tree.node(fn).value);
        }

        /**
         * Collects names under {@code node} that may refer to project items: type names, whole paths
         * ({@code net::http::get}, {@code Shape::Circle}, without whitespace) and capitalised plain identifiers (unit
         * structs, tuple-struct constructors and enum variants used as values). The walk does not descend into a
         * path, so its segments are not collected again individually. For a qualified path
         * ({@code <T as Trait>::Item}) the text names nothing resolvable, so the parser emitted the types inside the
         * brackets instead (see {@link RustParse}, "Reference leaves"). Lower-case plain identifiers (variables,
         * local function calls) are left out: only a path can lead to another module's function building. Paths of
         * {@code use} declarations inside items count like paths, a single name or an {@code as} alias like an
         * identifier.
         */
        void collectRefs(int node, Set<String> refs) {
            int end = tree.node(node).end;
            int i = node + 1;
            while (i < end) {
                Node n = tree.node(i);
                switch (n.kind) {
                    case TYPE_IDENT -> refs.add(tree.tokText(n.first));
                    case IDENT -> addIdentifier(tree.tokText(n.first), refs);
                    case USE_AS -> {
                        if (n.name != RustParse.NONE) {
                            addIdentifier(tree.tokText(n.name), refs);
                        }
                    }
                    case PATH, USE_PATH, USE_PREFIX -> {
                        if (n.kind != NodeKind.PATH && n.first == n.last) {
                            addIdentifier(tree.tokText(n.first), refs);
                        } else {
                            refs.add(withoutWhitespace(tree.rangeText(n.first, n.last)));
                            i = n.end; // nothing inside a path is a separate reference
                            continue;
                        }
                    }
                    default -> {
                    }
                }
                i++;
            }
        }

        /**
         * Flattens a use tree into alias → path entries and glob imports. Recursive over the tree; {@code prefix} is
         * the path accumulated from enclosing {@code a::b::{…}} groups:
         * <pre>
         *   use a::b::C;                → C ↦ [a, b, C]
         *   use a::{b::C, D as E, self}; → C ↦ [a, b, C], E ↦ [a, D], a ↦ [a]
         *   use a::b::*;                → glob [a, b]
         *   use x as _;                 → nothing (only brings trait methods into scope)
         * </pre>
         * Paths are stored as written; {@link Resolver#absolute} makes them absolute when they are used.
         */
        void expandUse(int n, List<String> prefix, Scope scope) {
            Node node = tree.node(n);
            switch (node.kind) {
                case USE_PATH -> {
                    if (node.first == node.last) {
                        // A single name, at the top level or inside a `{…}` list.
                        String name = tree.tokText(node.first);
                        if (name.equals("self") && !prefix.isEmpty()) {
                            scope.uses().put(prefix.getLast(), prefix); // use a::b::{self}
                        } else {
                            scope.uses().put(name, concat(prefix, List.of(name)));
                        }
                    } else {
                        List<String> path = concat(prefix, segments(n));
                        String alias = path.isEmpty() ? "" : path.getLast();
                        if (alias.equals("self")) {
                            path = path.subList(0, path.size() - 1);
                            alias = path.isEmpty() ? "self" : path.getLast();
                        }
                        scope.uses().put(alias, path);
                    }
                }
                case USE_AS -> {
                    String alias = tree.tokText(node.name);
                    if (!alias.equals("_")) {
                        scope.uses().put(alias, concat(prefix, segments(tree.child(n, NodeKind.USE_PATH))));
                    }
                }
                case USE_GROUP -> {
                    List<String> p = concat(prefix, segments(tree.child(n, NodeKind.USE_PREFIX)));
                    for (int c : tree.children(n)) {
                        if (tree.node(c).kind != NodeKind.USE_PREFIX) {
                            expandUse(c, p, scope);
                        }
                    }
                }
                case USE_GLOB -> scope.globs().add(concat(prefix, segments(tree.child(n, NodeKind.USE_PREFIX))));
                default -> {
                }
            }
        }

        /** The segments of a use path node: {@code a::b::c} → [a, b, c] (a leading {@code ::} adds none). */
        List<String> segments(int n) {
            List<String> out = new ArrayList<>();
            if (n >= 0) {
                Node node = tree.node(n);
                for (int t = node.first; t <= node.last; t++) {
                    if (tree.tokens[t].kind == TokKind.IDENT) {
                        out.add(tree.tokText(t));
                    }
                }
            }
            return out;
        }
    }

    /** An identifier is a reference if capitalised. Lifetimes and labels ({@code 'a}) count without their quote. */
    private static void addIdentifier(String s, Set<String> refs) {
        String name = s.startsWith("'") ? s.substring(1) : s;
        if (!name.isEmpty() && Character.isUpperCase(name.codePointAt(0))) {
            refs.add(name);
        }
    }

    /** The text without whitespace (Unicode White_Space, as Rust's {@code char::is_whitespace}). */
    private static String withoutWhitespace(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().filter(c -> !isRustWhitespace(c)).forEach(b::appendCodePoint);
        return b.toString();
    }

    /** The Unicode White_Space property, which Rust's {@code char::is_whitespace} implements. */
    private static boolean isRustWhitespace(int c) {
        return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * McCabe cyclomatic complexity of a function body: 1 + {@code if}, {@code while}, {@code loop}, {@code for}, each
     * {@code &&}/{@code ||}, and k - 1 per {@code match} with k arms (each arm adds one, the match itself takes one
     * off). {@code else if} counts as another {@code if}; {@code ?} does not. Closures and nested functions count
     * towards the enclosing function. Clamped to at least 1 because an empty {@code match} would make the sum go down.
     */
    static int complexity(Tree tree, int body) {
        int cx = 1;
        int end = tree.node(body).end;
        for (int i = body + 1; i < end; i++) {
            switch (tree.node(i).kind) {
                case IF, WHILE, LOOP, FOR, ARM, AND_OR -> cx++;
                case MATCH -> cx--; // a match with k arms adds k - 1 paths
                default -> {
                }
            }
        }
        return Math.max(1, cx);
    }

    // ------------------------------------------------------------------ crates

    /**
     * The crate of the nearest {@code Cargo.toml} with a package name at or above {@code dir}, not looking above
     * {@code root}. Without one (a workspace-only manifest, or no Cargo at all) the whole root is one crate named
     * after the root directory.
     */
    private Crate crateOf(Path root, Path dir) {
        Path d = dir;
        while (d != null && d.startsWith(root)) {
            Optional<Crate> c = crates.computeIfAbsent(d, RustAnalyzer::readCrate);
            if (c.isPresent()) {
                return c.get();
            }
            if (d.equals(root)) {
                break;
            }
            d = d.getParent();
        }
        String name = root.getFileName() == null ? "crate" : root.getFileName().toString();
        return new Crate(name.replace('-', '_'), root);
    }

    /**
     * The crate name (underscores) declared by {@code dir/Cargo.toml}, or "" when there is none. This is all the
     * analyser takes from a manifest, so {@link SourceAnalyzer} keys its cache invalidation on it.
     */
    static String crateName(Path dir) {
        return readCrate(dir).map(Crate::name).orElse("");
    }

    /** Reads {@code dir/Cargo.toml}; empty when it is missing, unreadable or has no {@code [package]} name. */
    private static Optional<Crate> readCrate(Path dir) {
        Path manifest = dir.resolve("Cargo.toml");
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            Matcher m = PACKAGE_NAME.matcher(Files.readString(manifest));
            return m.find() ? Optional.of(new Crate(m.group(1).replace('-', '_'), dir)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ linking

    /**
     * Links all Rust files:
     * <ol>
     *   <li>indexes every building (the first one wins on a duplicate fqn), every scope and every impl;</li>
     *   <li>resolves each impl's type in the impl's scope and attaches the impl there. An impl of a type outside the
     *       project (e.g. {@code impl Display for Vec<…>}, or a trait for a foreign type) goes to the module building
     *       of the module it is written in, which is created if the module has no free functions;</li>
     *   <li>merges the attached impls' methods and LOC into their buildings and computes each building's
     *       dependencies: its own references resolved in its own scope, plus the implemented traits and the impl
     *       bodies' references, already resolved in the impl's scope.</li>
     * </ol>
     */
    @Override
    public Linked link(Path root, List<ParsedFile> files) {
        // Every file of this analysis is parsed by now: the next analysis reads the manifests again.
        crates.clear();
        // Insertion order follows the (sorted) file order, so the output does not depend on hash order.
        Map<String, TypeInfo> byFqn = new LinkedHashMap<>();
        Map<String, Scope> scopes = new HashMap<>();
        List<Impl> impls = new ArrayList<>();
        Set<String> crateNames = new HashSet<>();
        for (ParsedFile f : files) {
            for (TypeInfo t : f.types()) {
                byFqn.putIfAbsent(t.fqn(), t);
            }
            if (f.context() instanceof Context c) {
                scopes.putAll(c.scopes());
                impls.addAll(c.impls());
                c.scopes().values().forEach(s -> crateNames.add(s.crate()));
            }
        }
        // keySet() is a live view: module buildings added below for impls become resolvable immediately.
        Resolver resolver = new Resolver(byFqn.keySet(), crateNames);

        // Attach impl blocks to their types; impls of foreign types go to the module's function building.
        Map<String, List<Impl>> attached = new HashMap<>();
        Map<String, Set<String>> extraDeps = new HashMap<>();
        for (Impl impl : impls) {
            String target = resolver.resolveType(impl.type(), impl.scope());
            if (target == null || byFqn.get(target).kind().equals("module")) {
                target = String.join("::", impl.scope().path()) + "::" + FUNCTIONS;
                if (!byFqn.containsKey(target)) {
                    // A module with impls but no free functions: create an empty module building at the impl's line;
                    // its LOC and methods come from the impls merged in below. District and name are those
                    // visitItems would have given it (a #[cfg(test)] module stays in its parent's district).
                    Scope s = impl.scope();
                    List<String> d = impl.district().path();
                    byFqn.put(target, new TypeInfo(Language.RUST, target, impl.label(), String.join("::",
                            d.subList(1, d.size())), moduleOf(byFqn, s), "module", impl.file(),
                            impl.line(), impl.line(), 0, 0, impl.test(), List.of(), Set.of(), List.of(), List.of()));
                    scopes.put(target, s);
                }
            }
            attached.computeIfAbsent(target, k -> new ArrayList<>()).add(impl);
            Set<String> deps = extraDeps.computeIfAbsent(target, k -> new HashSet<>());
            if (impl.trait() != null) {
                Optional.ofNullable(resolver.resolveType(impl.trait(), impl.scope())).ifPresent(deps::add);
            }
            for (String ref : impl.refs()) {
                Optional.ofNullable(resolver.resolve(ref, impl.scope())).ifPresent(deps::add);
            }
        }
        List<TypeInfo> types = new ArrayList<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (TypeInfo t : byFqn.values()) {
            TypeInfo merged = t;
            for (Impl impl : attached.getOrDefault(t.fqn(), List.of())) {
                // References are not merged: they were resolved in the impl's own scope into extraDeps, and
                // resolving them again in the type's scope could find the wrong items.
                merged = merged.withMembers(impl.methods(), Set.of(), impl.loc());
            }
            types.add(merged);
            Set<String> deps = new HashSet<>(extraDeps.getOrDefault(t.fqn(), Set.of()));
            Scope scope = scopes.get(t.fqn());
            if (scope != null) {
                for (String ref : t.references()) {
                    Optional.ofNullable(resolver.resolve(ref, scope)).ifPresent(deps::add);
                }
            }
            deps.remove(t.fqn());
            dependencies.put(t.fqn(), deps);
        }
        return new Linked(types, dependencies);
    }

    /** The build module of a scope's crate, taken from any building of that crate ("" if it has none yet). */
    private static String moduleOf(Map<String, TypeInfo> byFqn, Scope scope) {
        String prefix = scope.crate() + "::";
        return byFqn.values().stream().filter(t -> t.fqn().startsWith(prefix)).map(TypeInfo::module)
                .findFirst().orElse("");
    }

    /**
     * Resolves names as written in a scope to fqns of project types (or of a module's function building).
     *
     * <p>A simplified version of Rust's path resolution, enough for project-internal edges: a name's first segment is
     * looked up in the scope's {@code use} aliases; else a path starting with {@code crate}, {@code self},
     * {@code super} or a project crate's name is absolute; else the name is tried in the current module and in each
     * glob-imported module. Not modelled: the prelude and external crates (they never resolve, which is fine because
     * they are not project types), items declared inside function bodies, and names visible through
     * {@code pub use} re-exports.
     */
    static final class Resolver {
        /** fqns of all buildings; a live view that grows while impls are attached. */
        private final Set<String> known;
        private final Set<String> crateNames;

        Resolver(Set<String> known, Set<String> crateNames) {
            this.known = known;
            this.crateNames = crateNames;
        }

        /** Like {@link #resolve}, but only accepts a type building, never a module building. */
        String resolveType(String written, Scope scope) {
            String r = resolve(written, scope);
            return r != null && !r.endsWith("::" + FUNCTIONS) ? r : null;
        }

        String resolve(String written, Scope scope) {
            if (written == null || written.isEmpty() || written.equals("Self")) {
                return null;
            }
            // Cut generic arguments: "Vec::<T>::new" → "Vec::" → [Vec]. A qualified path such as
            // "<T as Trait>::Item" leaves nothing and resolves to nothing (collectRefs collects the types inside).
            List<String> segs = List.of(written.replaceAll("<.*", "").split("::"));
            String first = segs.getFirst();
            if (first.isEmpty()) {
                return null;
            }
            List<List<String>> candidates = new ArrayList<>();
            List<String> used = scope.uses().get(first);
            if (used != null) {
                candidates.add(concat(absolute(used, scope), segs.subList(1, segs.size())));
            } else if (first.equals("crate") || first.equals("self") || first.equals("super") || crateNames.contains(first)) {
                candidates.add(absolute(segs, scope));
            } else {
                candidates.add(concat(scope.path(), segs));
                for (List<String> glob : scope.globs()) {
                    candidates.add(concat(absolute(glob, scope), segs));
                }
            }
            for (List<String> c : candidates) {
                String fqn = String.join("::", c);
                if (known.contains(fqn)) {
                    return fqn;
                }
                // a function path like net::http::get → the function building of net::http
                // The lower-case check keeps e.g. an unknown type or variant (net::http::Status) from matching.
                if (c.size() > 1) {
                    String parent = String.join("::", c.subList(0, c.size() - 1));
                    if (known.contains(parent + "::" + FUNCTIONS) && Character.isLowerCase(c.getLast().charAt(0))) {
                        return parent + "::" + FUNCTIONS;
                    }
                    // an associated item or variant of a type (Cart::new, Shape::Circle) → the type
                    if (known.contains(parent)) {
                        return parent;
                    }
                }
            }
            return null;
        }

        /**
         * Turns a use path into an absolute path that starts with a crate name. {@code super} is applied once per
         * occurrence and stops at the crate root. A path that starts with neither a keyword nor a project crate name
         * is taken as relative to the current module (an external crate's path then simply resolves to nothing).
         */
        List<String> absolute(List<String> path, Scope scope) {
            if (path.isEmpty()) {
                return path;
            }
            String first = path.getFirst();
            if (first.equals("crate")) {
                return concat(List.of(scope.crate()), path.subList(1, path.size()));
            }
            if (first.equals("self")) {
                return concat(scope.path(), path.subList(1, path.size()));
            }
            if (first.equals("super")) {
                List<String> base = new ArrayList<>(scope.path());
                int i = 0;
                while (i < path.size() && path.get(i).equals("super")) {
                    if (base.size() > 1) {
                        base.removeLast();
                    }
                    i++;
                }
                return concat(base, path.subList(i, path.size()));
            }
            if (crateNames.contains(first)) {
                return path;
            }
            return concat(scope.path(), path); // 2018-edition relative path to a child module
        }
    }
}
