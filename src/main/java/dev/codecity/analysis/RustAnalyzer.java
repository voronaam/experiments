package dev.codecity.analysis;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterRust;

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
 * Rust support (tree-sitter). Crates and modules are districts; structs, enums, unions and traits are buildings whose
 * methods are collected from every {@code impl} block in the crate; each module's free functions and constants form a
 * "module" building. {@code use} paths ({@code crate::}, {@code super::}, {@code self::}, groups, globs, aliases) are
 * resolved so dependencies connect across files.
 */
public final class RustAnalyzer implements LanguageSupport {

    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "(?ms)^\\[package\\][^\\[]*?^\\s*name\\s*=\\s*\"([^\"]+)\"");
    private static final Set<String> DECISIONS = Set.of(
            "if_expression", "while_expression", "loop_expression", "for_expression", "match_arm");
    static final String FUNCTIONS = "{fn}";

    private final ThreadLocal<TSParser> parser = ThreadLocal.withInitial(() -> {
        TSParser p = new TSParser();
        p.setLanguage(new TreeSitterRust());
        return p;
    });
    private final Map<Path, Optional<Crate>> crates = new ConcurrentHashMap<>();

    private record Crate(String name, Path dir) {
    }

    /**
     * Name-resolution scope of one module.
     *
     * @param path    absolute module path, starting with the crate name
     * @param uses    alias → path as written in the {@code use} declaration
     * @param globs   paths imported with {@code ::*}
     */
    record Scope(List<String> path, Map<String, List<String>> uses, List<List<String>> globs) {
        String crate() {
            return path.getFirst();
        }
    }

    /** An {@code impl} block, attached to its type during linking. */
    record Impl(Scope scope, String type, String trait, List<MethodInfo> methods, Set<String> refs, int loc, Path file,
                int line, boolean test) {
    }

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

    @Override
    public ParsedFile parse(Path root, Path file, String code) {
        Syntax syntax = Syntax.parse(parser.get(), code);
        Crate crate = crateOf(root, file.getParent());
        Path inCrate = crate.dir().relativize(file);
        List<String> modulePath = modulePath(sourceRoot(crate.dir(), file.getParent()).relativize(file));
        boolean test = inCrate.startsWith("tests") || inCrate.startsWith("benches");
        List<String> absolute = new ArrayList<>(List.of(crate.name()));
        absolute.addAll(modulePath);
        String module = crate.name(); // the layout only shows module districts when there are several

        FileState st = new FileState(syntax, SourceText.lines(code), file, module);
        Scope scope = newScope(absolute);
        st.visitItems(syntax.root, scope, scope, file.getFileName().toString(), test);
        return new ParsedFile(file, st.types, new Context(st.scopes, st.impls), syntax.root.hasError());
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

    /** {@code src/lib.rs} → [], {@code src/net/mod.rs} → [net], {@code src/net/http.rs} → [net, http]. */
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

    private final class FileState {
        final Syntax syntax;
        final String[] lines;
        final Path file;
        final String module;
        final List<TypeInfo> types = new ArrayList<>();
        final List<Impl> impls = new ArrayList<>();
        final Map<String, Scope> scopes = new HashMap<>();

        FileState(Syntax syntax, String[] lines, Path file, String module) {
            this.syntax = syntax;
            this.lines = lines;
            this.file = file;
            this.module = module;
        }

        String packageOf(Scope scope) {
            return String.join("::", scope.path().subList(1, scope.path().size()));
        }

        String fqn(Scope scope, String name) {
            return String.join("::", scope.path()) + "::" + name;
        }

        /**
         * @param district scope whose module path decides the district; differs from {@code scope} inside
         *                 {@code #[cfg(test)]} modules, which stay in their parent's district instead of adding one
         */
        void visitItems(TSNode container, Scope scope, Scope district, String label, boolean test) {
            List<MethodInfo> functions = new ArrayList<>();
            Set<String> functionRefs = new HashSet<>();
            int constants = 0;
            int moduleLoc = 0;
            int first = Integer.MAX_VALUE;
            int last = 0;
            boolean cfgTest = false;
            for (TSNode item : Syntax.namedChildren(container)) {
                String type = item.getType();
                if (type.equals("attribute_item")) {
                    String attr = syntax.text(item).replace(" ", "");
                    cfgTest |= attr.contains("cfg(test)");
                    continue;
                }
                switch (type) {
                    case "use_declaration" -> expandUse(Syntax.field(item, "argument"), List.of(), scope);
                    case "struct_item", "union_item" -> addType(item, scope, district,
                            type.equals("union_item") ? "union" : "struct",
                            fieldCount(Syntax.field(item, "body")), List.of(), test || cfgTest);
                    case "enum_item" -> {
                        TSNode body = Syntax.field(item, "body");
                        int variants = 0;
                        if (body != null) {
                            for (TSNode v : Syntax.namedChildren(body)) {
                                variants += v.getType().equals("enum_variant") ? 1 : 0;
                            }
                        }
                        addType(item, scope, district, "enum", variants, List.of(), test || cfgTest);
                    }
                    case "trait_item" -> addType(item, scope, district, "trait", 0,
                            functionsIn(Syntax.field(item, "body")), test || cfgTest);
                    case "impl_item" -> addImpl(item, scope, test || cfgTest);
                    case "function_item" -> {
                        functions.add(function(item));
                        collectRefs(item, functionRefs);
                        moduleLoc += SourceText.countLoc(lines, Syntax.firstLine(item), Syntax.lastLine(item));
                        first = Math.min(first, Syntax.firstLine(item));
                        last = Math.max(last, Syntax.lastLine(item));
                    }
                    case "const_item", "static_item" -> {
                        constants++;
                        collectRefs(item, functionRefs);
                        moduleLoc += SourceText.countLoc(lines, Syntax.firstLine(item), Syntax.lastLine(item));
                        first = Math.min(first, Syntax.firstLine(item));
                        last = Math.max(last, Syntax.lastLine(item));
                    }
                    case "mod_item" -> {
                        TSNode body = Syntax.field(item, "body");
                        if (body != null) {
                            List<String> child = new ArrayList<>(scope.path());
                            child.add(syntax.text(Syntax.field(item, "name")));
                            Scope childScope = newScope(child);
                            visitItems(body, childScope, cfgTest ? district : childScope, "mod " + child.getLast(),
                                    test || cfgTest);
                        }
                    }
                    default -> {
                    }
                }
                cfgTest = false;
            }
            scopes.put(String.join("::", scope.path()), scope);
            if (!functions.isEmpty() || constants > 0) {
                String id = fqn(scope, FUNCTIONS);
                scopes.put(id, scope);
                types.add(new TypeInfo(Language.RUST, id, label, packageOf(district), module, "module", file,
                        first, last, moduleLoc, constants, test, List.copyOf(functions), Set.copyOf(functionRefs),
                        List.of(), List.of()));
            }
        }

        void addType(TSNode item, Scope scope, Scope district, String kind, int noa, List<MethodInfo> methods,
                     boolean test) {
            String name = syntax.text(Syntax.field(item, "name"));
            Set<String> refs = new HashSet<>();
            collectRefs(item, refs);
            int begin = Syntax.firstLine(item);
            int end = Syntax.lastLine(item);
            String id = fqn(scope, name);
            scopes.put(id, scope);
            types.add(new TypeInfo(Language.RUST, id, name, packageOf(district), module, kind, file, begin, end,
                    SourceText.countLoc(lines, begin, end), noa, test, List.copyOf(methods), Set.copyOf(refs),
                    List.of(), List.of()));
        }

        void addImpl(TSNode item, Scope scope, boolean test) {
            TSNode typeNode = Syntax.field(item, "type");
            TSNode traitNode = Syntax.field(item, "trait");
            Set<String> refs = new HashSet<>();
            collectRefs(Syntax.field(item, "body") == null ? item : Syntax.field(item, "body"), refs);
            int begin = Syntax.firstLine(item);
            int end = Syntax.lastLine(item);
            impls.add(new Impl(scope, typeName(typeNode), traitNode == null ? null : typeName(traitNode),
                    functionsIn(Syntax.field(item, "body")), refs, SourceText.countLoc(lines, begin, end), file, begin, test));
        }

        /** {@code S<T>} → {@code S}; {@code crate::m::Other} stays a path; references are unwrapped. */
        String typeName(TSNode n) {
            if (n == null) {
                return "";
            }
            return switch (n.getType()) {
                case "generic_type" -> typeName(Syntax.field(n, "type"));
                case "reference_type", "pointer_type" -> typeName(Syntax.field(n, "type"));
                default -> syntax.text(n);
            };
        }

        int fieldCount(TSNode body) {
            if (body == null) {
                return 0;
            }
            if (body.getType().equals("field_declaration_list")) {
                int n = 0;
                for (TSNode f : Syntax.namedChildren(body)) {
                    n += f.getType().equals("field_declaration") ? 1 : 0;
                }
                return n;
            }
            int n = 0; // tuple struct: count the "type" fields
            for (int i = 0; i < body.getChildCount(); i++) {
                n += "type".equals(body.getFieldNameForChild(i)) ? 1 : 0;
            }
            return n;
        }

        List<MethodInfo> functionsIn(TSNode body) {
            List<MethodInfo> list = new ArrayList<>();
            if (body != null) {
                for (TSNode m : Syntax.namedChildren(body)) {
                    if (m.getType().equals("function_item") || m.getType().equals("function_signature_item")) {
                        list.add(function(m));
                    }
                }
            }
            return list;
        }

        MethodInfo function(TSNode fn) {
            TSNode params = Syntax.field(fn, "parameters");
            int count = 0;
            if (params != null) {
                for (TSNode p : Syntax.namedChildren(params)) {
                    count += p.getType().equals("parameter") ? 1 : 0;
                }
            }
            TSNode body = Syntax.field(fn, "body");
            int begin = Syntax.firstLine(fn);
            int end = Syntax.lastLine(fn);
            return new MethodInfo(syntax.text(Syntax.field(fn, "name")), begin, SourceText.countLoc(lines, begin, end),
                    body == null ? 1 : complexity(body), count);
        }

        void collectRefs(TSNode node, Set<String> refs) {
            Syntax.walk(node, n -> {
                switch (n.getType()) {
                    case "type_identifier" -> refs.add(syntax.text(n));
                    case "scoped_type_identifier", "scoped_identifier" -> refs.add(syntax.text(n).replaceAll("\\s+", ""));
                    case "identifier" -> {
                        String s = syntax.text(n);
                        if (!s.isEmpty() && Character.isUpperCase(s.charAt(0))) {
                            refs.add(s);
                        }
                    }
                    default -> {
                    }
                }
            }, n -> !n.getType().equals("scoped_type_identifier") && !n.getType().equals("scoped_identifier"));
        }

        /** Flattens a use tree into alias → path entries and glob imports. */
        void expandUse(TSNode n, List<String> prefix, Scope scope) {
            if (n == null) {
                return;
            }
            switch (n.getType()) {
                case "identifier", "crate", "self", "super", "metavariable" -> {
                    String name = syntax.text(n);
                    List<String> path = concat(prefix, List.of(name));
                    if (name.equals("self") && !prefix.isEmpty()) {
                        scope.uses().put(prefix.getLast(), prefix); // use a::b::{self}
                    } else {
                        scope.uses().put(name, path);
                    }
                }
                case "scoped_identifier" -> {
                    List<String> path = concat(prefix, segments(n));
                    String alias = path.getLast();
                    if (alias.equals("self")) {
                        path = path.subList(0, path.size() - 1);
                        alias = path.isEmpty() ? "self" : path.getLast();
                    }
                    scope.uses().put(alias, path);
                }
                case "use_as_clause" -> {
                    String alias = syntax.text(Syntax.field(n, "alias"));
                    if (!alias.equals("_")) {
                        scope.uses().put(alias, concat(prefix, segments(Syntax.field(n, "path"))));
                    }
                }
                case "scoped_use_list" -> {
                    TSNode path = Syntax.field(n, "path");
                    expandUse(Syntax.field(n, "list"), path == null ? prefix : concat(prefix, segments(path)), scope);
                }
                case "use_list" -> Syntax.namedChildren(n).forEach(c -> expandUse(c, prefix, scope));
                case "use_wildcard" -> {
                    List<String> path = n.getNamedChildCount() == 0 ? prefix : concat(prefix, segments(n.getNamedChild(0)));
                    scope.globs().add(path);
                }
                default -> {
                }
            }
        }

        List<String> segments(TSNode n) {
            if (n == null) {
                return List.of();
            }
            if (n.getType().equals("scoped_identifier") || n.getType().equals("scoped_type_identifier")) {
                return concat(segments(Syntax.field(n, "path")), List.of(syntax.text(Syntax.field(n, "name"))));
            }
            return List.of(syntax.text(n));
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    static int complexity(TSNode body) {
        int[] cx = {1};
        Syntax.walk(body, n -> {
            String type = n.getType();
            if (DECISIONS.contains(type)) {
                cx[0]++;
            } else if (type.equals("match_expression")) {
                cx[0]--; // a match with k arms adds k - 1 paths
            } else if (type.equals("binary_expression")) {
                String op = Syntax.operator(n);
                if (op.equals("&&") || op.equals("||")) {
                    cx[0]++;
                }
            }
        });
        return Math.max(1, cx[0]);
    }

    // ------------------------------------------------------------------ crates

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

    @Override
    public Linked link(Path root, List<ParsedFile> files) {
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
        Resolver resolver = new Resolver(byFqn.keySet(), crateNames);

        // Attach impl blocks to their types; impls of foreign types go to the module's function building.
        Map<String, List<Impl>> attached = new HashMap<>();
        Map<String, Set<String>> extraDeps = new HashMap<>();
        for (Impl impl : impls) {
            String target = resolver.resolveType(impl.type(), impl.scope());
            if (target == null || byFqn.get(target).kind().equals("module")) {
                target = String.join("::", impl.scope().path()) + "::" + FUNCTIONS;
                if (!byFqn.containsKey(target)) {
                    Scope s = impl.scope();
                    String file = impl.file().getFileName().toString();
                    byFqn.put(target, new TypeInfo(Language.RUST, target, file, String.join("::",
                            s.path().subList(1, s.path().size())), moduleOf(byFqn, s), "module", impl.file(),
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

    private static String moduleOf(Map<String, TypeInfo> byFqn, Scope scope) {
        String prefix = scope.crate() + "::";
        return byFqn.values().stream().filter(t -> t.fqn().startsWith(prefix)).map(TypeInfo::module)
                .findFirst().orElse("");
    }

    /** Resolves names as written in a scope to fqns of project types (or of a module's function building). */
    static final class Resolver {
        private final Set<String> known;
        private final Set<String> crateNames;

        Resolver(Set<String> known, Set<String> crateNames) {
            this.known = known;
            this.crateNames = crateNames;
        }

        String resolveType(String written, Scope scope) {
            String r = resolve(written, scope);
            return r != null && !r.endsWith("::" + FUNCTIONS) ? r : null;
        }

        String resolve(String written, Scope scope) {
            if (written == null || written.isEmpty() || written.equals("Self")) {
                return null;
            }
            List<String> segs = List.of(written.replaceAll("<.*", "").split("::"));
            String first = segs.getFirst();
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
                if (c.size() > 1) {
                    String functions = String.join("::", c.subList(0, c.size() - 1)) + "::" + FUNCTIONS;
                    if (known.contains(functions) && Character.isLowerCase(c.getLast().charAt(0))) {
                        return functions;
                    }
                }
            }
            return null;
        }

        /** Turns a use path into an absolute path that starts with a crate name. */
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
