package dev.codecity.analysis;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterTsx;
import org.treesitter.TreeSitterTypescript;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * TypeScript support (tree-sitter). Directories are districts; classes, interfaces, enums and type aliases are
 * buildings; each file's top-level functions and constants form one "module" building named after the file — which is
 * where React components and other function-style code live.
 */
public final class TypeScriptAnalyzer implements LanguageSupport {

    private static final Pattern SOURCE = Pattern.compile(".*\\.(ts|tsx|mts|cts)$");
    private static final Pattern DECLARATION = Pattern.compile(".*\\.d\\.(ts|mts|cts)$");
    private static final Pattern TEST_FILE = Pattern.compile(".*\\.(test|spec)\\.(ts|tsx|mts|cts)$");
    private static final Set<String> FUNCTION_VALUES = Set.of(
            "arrow_function", "function_expression", "function", "generator_function");
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "for_in_statement", "while_statement", "do_statement",
            "switch_case", "catch_clause", "ternary_expression");

    private final ThreadLocal<TSParser> tsParser = ThreadLocal.withInitial(() -> parser(new TreeSitterTypescript()));
    private final ThreadLocal<TSParser> tsxParser = ThreadLocal.withInitial(() -> parser(new TreeSitterTsx()));
    private final Map<Path, String> projectDirs = new ConcurrentHashMap<>();

    private static TSParser parser(org.treesitter.TSLanguage language) {
        TSParser p = new TSParser();
        p.setLanguage(language);
        return p;
    }

    /** An import binding: {@code local} names {@code imported} ("default", "*" or an export name) of {@code source}. */
    record Import(String local, String source, String imported) {
    }

    /**
     * What the linker needs to know about a file.
     *
     * @param path          path relative to the root, e.g. {@code src/ui/App.tsx}
     * @param names         top-level declared name → fqn of the building that holds it
     * @param defaultExport local name of the default export, or null
     */
    record Context(String path, List<Import> imports, Map<String, String> names, String defaultExport) {
    }

    @Override
    public Language language() {
        return Language.TYPESCRIPT;
    }

    @Override
    public boolean accepts(Path file) {
        String name = file.getFileName().toString();
        return SOURCE.matcher(name).matches() && !DECLARATION.matcher(name).matches();
    }

    @Override
    public ParsedFile parse(Path root, Path file, String code) {
        TSParser parser = file.getFileName().toString().endsWith(".tsx") ? tsxParser.get() : tsParser.get();
        Syntax syntax = Syntax.parse(parser, code);
        FileState st = new FileState(root, file, syntax, SourceText.lines(code));
        st.visitStatements(syntax.root, "");
        st.finishModule();
        Context ctx = new Context(st.path, st.imports, st.names, st.defaultExport);
        return new ParsedFile(file, st.types, ctx, syntax.root.hasError());
    }

    /** Per-file extraction state. */
    private final class FileState {
        final Syntax syntax;
        final String[] lines;
        final Path file;
        final String path;
        final String packageName;
        final String module;
        final boolean test;
        final List<TypeInfo> types = new ArrayList<>();
        final List<Import> imports = new ArrayList<>();
        final Map<String, String> names = new LinkedHashMap<>();
        final Set<String> usedIds = new HashSet<>();
        String defaultExport;
        // the file's "module" building: free functions and top-level constants
        final List<MethodInfo> functions = new ArrayList<>();
        final Set<String> moduleRefs = new HashSet<>();
        final List<String> moduleNames = new ArrayList<>();
        int moduleAttributes;
        int moduleLoc;

        FileState(Path root, Path file, Syntax syntax, String[] lines) {
            this.syntax = syntax;
            this.lines = lines;
            this.file = file;
            Path rel = root.relativize(file);
            this.path = SourceText.slashes(rel);
            String project = projectOf(root, file.getParent());
            this.module = project;
            String dir = SourceText.slashes(rel.getParent());
            String pkg = project.isEmpty() ? dir : dir.equals(project) ? "" : dir.substring(project.length() + 1);
            this.packageName = pkg;
            this.test = SourceText.inTestDirectory(rel) || TEST_FILE.matcher(file.getFileName().toString()).matches();
        }

        String moduleId() {
            return path;
        }

        void visitStatements(TSNode container, String prefix) {
            for (TSNode child : Syntax.namedChildren(container)) {
                TSNode decl = child;
                boolean isDefault = false;
                switch (child.getType()) {
                    case "import_statement" -> {
                        addImport(child);
                        continue;
                    }
                    case "export_statement" -> {
                        isDefault = Syntax.children(child).stream().anyMatch(c -> c.getType().equals("default"));
                        decl = Syntax.field(child, "declaration");
                        if (decl == null) {
                            TSNode value = Syntax.field(child, "value");
                            if (value != null && isDefault) {
                                exportDefaultExpression(value);
                            }
                            continue;
                        }
                    }
                    case "ambient_declaration", "expression_statement" -> {
                        if (child.getNamedChildCount() == 0) {
                            continue;
                        }
                        decl = child.getNamedChild(0);
                        if (child.getType().equals("expression_statement")
                                && !decl.getType().equals("internal_module")) {
                            addStatement(child, decl);
                            continue;
                        }
                    }
                    default -> {
                    }
                }
                String declared = declaration(decl, prefix);
                if (isDefault && declared != null) {
                    defaultExport = declared;
                }
            }
        }

        /** Handles one declaration; returns the declared name (for default exports) or null. */
        String declaration(TSNode decl, String prefix) {
            switch (decl.getType()) {
                case "class_declaration", "abstract_class_declaration", "class" -> {
                    return addClass(decl, prefix);
                }
                case "interface_declaration" -> {
                    return addInterface(decl, prefix);
                }
                case "enum_declaration" -> {
                    return addEnum(decl, prefix);
                }
                case "type_alias_declaration" -> {
                    return addAlias(decl, prefix);
                }
                case "function_declaration", "generator_function_declaration" -> {
                    String name = prefix + syntax.text(Syntax.field(decl, "name"));
                    addFunction(name, decl, decl);
                    return name;
                }
                case "lexical_declaration", "variable_declaration" -> {
                    String last = null;
                    for (TSNode d : Syntax.namedChildren(decl)) {
                        if (!d.getType().equals("variable_declarator")) {
                            continue;
                        }
                        String name = prefix + syntax.text(Syntax.field(d, "name"));
                        TSNode value = Syntax.field(d, "value");
                        if (value != null && FUNCTION_VALUES.contains(value.getType())) {
                            addFunction(name, value, decl);
                        } else if (value != null && value.getType().equals("class")) {
                            addClass(value, prefix, syntax.text(Syntax.field(d, "name")));
                        } else {
                            moduleAttributes++;
                            moduleLoc += SourceText.countLoc(lines, Syntax.firstLine(decl), Syntax.lastLine(decl));
                            moduleNames.add(name);
                            collectRefs(decl, moduleRefs);
                        }
                        last = name;
                    }
                    return last;
                }
                case "internal_module", "module" -> {
                    TSNode body = Syntax.field(decl, "body");
                    String name = syntax.text(Syntax.field(decl, "name")).replace("'", "").replace("\"", "");
                    if (body != null) {
                        visitStatements(body, prefix + name + ".");
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }

        void exportDefaultExpression(TSNode value) {
            switch (value.getType()) {
                case "identifier" -> defaultExport = syntax.text(value);
                case "arrow_function", "function_expression", "function" -> {
                    addFunction("default", value, value);
                    defaultExport = "default";
                }
                case "class" -> defaultExport = addClass(value, "", "default");
                default -> {
                }
            }
        }

        void addImport(TSNode stmt) {
            TSNode source = Syntax.field(stmt, "source");
            if (source == null) {
                return;
            }
            String from = syntax.text(source).replaceAll("^['\"`]|['\"`]$", "");
            for (TSNode clause : Syntax.namedChildren(stmt)) {
                if (!clause.getType().equals("import_clause")) {
                    continue;
                }
                for (TSNode part : Syntax.namedChildren(clause)) {
                    switch (part.getType()) {
                        case "identifier" -> imports.add(new Import(syntax.text(part), from, "default"));
                        case "namespace_import" -> {
                            for (TSNode id : Syntax.namedChildren(part)) {
                                imports.add(new Import(syntax.text(id), from, "*"));
                            }
                        }
                        case "named_imports" -> {
                            for (TSNode spec : Syntax.namedChildren(part)) {
                                if (!spec.getType().equals("import_specifier")) {
                                    continue;
                                }
                                String name = syntax.text(Syntax.field(spec, "name"));
                                TSNode alias = Syntax.field(spec, "alias");
                                imports.add(new Import(alias == null ? name : syntax.text(alias), from, name));
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }

        String id(String name, String kind) {
            String id = path + ":" + name;
            if (!usedIds.add(id)) {
                id = id + "#" + kind; // e.g. declaration merging: an interface and a class with the same name
                usedIds.add(id);
            }
            names.putIfAbsent(name, id);
            return id;
        }

        TypeInfo type(String name, String kind, TSNode decl, int noa, List<MethodInfo> methods) {
            Set<String> refs = new HashSet<>();
            collectRefs(decl, refs);
            int begin = Syntax.firstLine(decl);
            int end = Syntax.lastLine(decl);
            TypeInfo t = new TypeInfo(Language.TYPESCRIPT, id(name, kind), name, packageName, module, kind, file,
                    begin, end, SourceText.countLoc(lines, begin, end), noa, test, List.copyOf(methods),
                    Set.copyOf(refs), List.of(), List.of());
            types.add(t);
            return t;
        }

        String addClass(TSNode decl, String prefix) {
            return addClass(decl, prefix, null);
        }

        String addClass(TSNode decl, String prefix, String fallbackName) {
            TSNode nameNode = Syntax.field(decl, "name");
            String name = prefix + (nameNode != null ? syntax.text(nameNode) : fallbackName);
            if (name.equals(prefix)) {
                return null;
            }
            List<MethodInfo> methods = new ArrayList<>();
            int attributes = 0;
            TSNode body = Syntax.field(decl, "body");
            if (body != null) {
                for (TSNode member : Syntax.namedChildren(body)) {
                    switch (member.getType()) {
                        case "method_definition" -> {
                            methods.add(method(member, Syntax.field(member, "name"), member));
                            if (syntax.text(Syntax.field(member, "name")).equals("constructor")) {
                                attributes += parameterProperties(member);
                            }
                        }
                        case "abstract_method_signature", "method_signature" ->
                                methods.add(method(member, Syntax.field(member, "name"), null));
                        case "public_field_definition" -> {
                            TSNode value = Syntax.field(member, "value");
                            if (value != null && FUNCTION_VALUES.contains(value.getType())) {
                                methods.add(method(value, Syntax.field(member, "name"), member));
                            } else {
                                attributes++;
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
            String kind = decl.getType().equals("abstract_class_declaration") ? "abstract" : "class";
            type(name, kind, decl, attributes, methods);
            return name;
        }

        /** Constructor parameters with an accessibility modifier ({@code private x: T}) declare fields. */
        int parameterProperties(TSNode constructor) {
            TSNode params = Syntax.field(constructor, "parameters");
            int n = 0;
            if (params != null) {
                for (TSNode p : Syntax.namedChildren(params)) {
                    for (TSNode c : Syntax.namedChildren(p)) {
                        if (c.getType().equals("accessibility_modifier") || c.getType().equals("readonly")) {
                            n++;
                            break;
                        }
                    }
                }
            }
            return n;
        }

        String addInterface(TSNode decl, String prefix) {
            String name = prefix + syntax.text(Syntax.field(decl, "name"));
            List<MethodInfo> methods = new ArrayList<>();
            int attributes = countMembers(Syntax.field(decl, "body"), methods);
            type(name, "interface", decl, attributes, methods);
            return name;
        }

        String addAlias(TSNode decl, String prefix) {
            String name = prefix + syntax.text(Syntax.field(decl, "name"));
            List<MethodInfo> methods = new ArrayList<>();
            TSNode value = Syntax.field(decl, "value");
            int attributes = value != null && value.getType().equals("object_type") ? countMembers(value, methods) : 0;
            type(name, "type", decl, attributes, methods);
            return name;
        }

        int countMembers(TSNode body, List<MethodInfo> methods) {
            int attributes = 0;
            if (body == null) {
                return 0;
            }
            for (TSNode m : Syntax.namedChildren(body)) {
                switch (m.getType()) {
                    case "property_signature" -> attributes++;
                    case "method_signature", "call_signature", "construct_signature" ->
                            methods.add(method(m, Syntax.field(m, "name"), null));
                    default -> {
                    }
                }
            }
            return attributes;
        }

        String addEnum(TSNode decl, String prefix) {
            String name = prefix + syntax.text(Syntax.field(decl, "name"));
            TSNode body = Syntax.field(decl, "body");
            type(name, "enum", decl, body == null ? 0 : body.getNamedChildCount(), List.of());
            return name;
        }

        /** @param body node whose complexity is measured (null for signatures without a body) */
        MethodInfo method(TSNode node, TSNode nameNode, TSNode body) {
            String name = nameNode == null ? "(anonymous)" : syntax.text(nameNode);
            TSNode params = Syntax.field(node, "parameters");
            int count = params == null ? (Syntax.field(node, "parameter") != null ? 1 : 0) : params.getNamedChildCount();
            int begin = Syntax.firstLine(node);
            int end = Syntax.lastLine(node);
            return new MethodInfo(name, begin, SourceText.countLoc(lines, begin, end),
                    body == null ? 1 : complexity(body), count);
        }

        void addFunction(String name, TSNode fn, TSNode statement) {
            MethodInfo m = method(fn, null, fn);
            functions.add(new MethodInfo(name, m.line(), m.loc(), m.complexity(), m.parameters()));
            moduleLoc += SourceText.countLoc(lines, Syntax.firstLine(statement), Syntax.lastLine(statement));
            moduleNames.add(name);
            collectRefs(statement, moduleRefs);
        }

        /**
         * Top-level code: {@code describe("cart", () => …)} or {@code app.get("/", handler)} becomes a method named
         * after the call, so test suites and route tables show up; anything else just adds lines and references.
         */
        void addStatement(TSNode statement, TSNode expression) {
            moduleLoc += SourceText.countLoc(lines, Syntax.firstLine(statement), Syntax.lastLine(statement));
            collectRefs(statement, moduleRefs);
            if (!expression.getType().equals("call_expression")) {
                return;
            }
            TSNode args = Syntax.field(expression, "arguments");
            if (args == null) {
                return;
            }
            boolean hasCallback = false;
            String label = null;
            for (TSNode arg : Syntax.namedChildren(args)) {
                hasCallback |= FUNCTION_VALUES.contains(arg.getType());
                if (label == null && (arg.getType().equals("string") || arg.getType().equals("template_string"))) {
                    label = syntax.text(arg);
                }
            }
            if (!hasCallback) {
                return;
            }
            String callee = syntax.text(Syntax.field(expression, "function"));
            String name = callee + "(" + (label == null ? "" : label.length() > 48 ? label.substring(0, 47) + "…" : label) + ")";
            int begin = Syntax.firstLine(statement);
            int end = Syntax.lastLine(statement);
            functions.add(new MethodInfo(name, begin, SourceText.countLoc(lines, begin, end), complexity(args), 0));
        }

        void finishModule() {
            if (functions.isEmpty() && moduleAttributes == 0 && moduleLoc == 0) {
                return;
            }
            String name = file.getFileName().toString();
            usedIds.add(moduleId());
            for (String n : moduleNames) {
                names.putIfAbsent(n, moduleId());
            }
            types.add(new TypeInfo(Language.TYPESCRIPT, moduleId(), name, packageName, module, "module", file,
                    1, lines.length, moduleLoc, moduleAttributes, test, List.copyOf(functions),
                    Set.copyOf(moduleRefs), List.of(), List.of()));
        }

        void collectRefs(TSNode node, Set<String> refs) {
            Syntax.walk(node, n -> {
                String type = n.getType();
                if (type.equals("type_identifier") || type.equals("identifier")) {
                    refs.add(syntax.text(n));
                }
            });
        }
    }

    static int complexity(TSNode body) {
        int[] cx = {1};
        Syntax.walk(body, n -> {
            String type = n.getType();
            if (DECISIONS.contains(type)) {
                cx[0]++;
            } else if (type.equals("binary_expression")) {
                String op = Syntax.operator(n);
                if (op.equals("&&") || op.equals("||") || op.equals("??")) {
                    cx[0]++;
                }
            }
        });
        return cx[0];
    }

    /** Directory of the nearest package.json at or above {@code dir} (relative to root, "" for the root itself). */
    private String projectOf(Path root, Path dir) {
        List<Path> visited = new ArrayList<>();
        String project = "";
        for (Path d = dir; d != null && d.startsWith(root); d = d.getParent()) {
            String cached = projectDirs.get(d);
            if (cached != null) {
                project = cached;
                break;
            }
            visited.add(d);
            if (Files.isRegularFile(d.resolve("package.json"))) {
                project = d.equals(root) ? "" : SourceText.slashes(root.relativize(d));
                break;
            }
            if (d.equals(root)) {
                break;
            }
        }
        for (Path d : visited) {
            projectDirs.put(d, project);
        }
        return project;
    }

    // ------------------------------------------------------------------ linking

    @Override
    public Linked link(Path root, List<ParsedFile> files) {
        List<TypeInfo> types = new ArrayList<>();
        Map<String, Context> byPath = new HashMap<>();
        Map<String, List<String>> byKey = new HashMap<>(); // module key (path without extension) → file paths
        Map<String, List<String>> keysByLastSegment = new HashMap<>();
        for (ParsedFile f : files) {
            types.addAll(f.types());
            if (f.context() instanceof Context c) {
                byPath.put(c.path(), c);
                for (String key : keys(c.path())) {
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(c.path());
                    keysByLastSegment.computeIfAbsent(key.substring(key.lastIndexOf('/') + 1), k -> new ArrayList<>())
                            .add(key);
                }
            }
        }
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, String> resolvedSources = new HashMap<>();
        for (TypeInfo t : types) {
            Context ctx = byPath.get(SourceText.slashes(root.relativize(t.file())));
            if (ctx == null) {
                continue;
            }
            Map<String, Import> importsByLocal = new HashMap<>();
            ctx.imports().forEach(i -> importsByLocal.put(i.local(), i));
            Set<String> out = new HashSet<>();
            for (String ref : t.references()) {
                String local = ctx.names().get(ref);
                if (local != null) {
                    out.add(local);
                    continue;
                }
                Import imp = importsByLocal.get(ref);
                if (imp == null) {
                    continue;
                }
                String target = resolvedSources.computeIfAbsent(ctx.path() + "|" + imp.source(),
                        k -> resolveSource(ctx.path(), imp.source(), byKey, keysByLastSegment));
                if (target.isEmpty()) {
                    continue;
                }
                Context other = byPath.get(target);
                String symbol = imp.imported().equals("default") ? other.defaultExport() : imp.imported();
                String fqn = symbol == null || imp.imported().equals("*") ? null : other.names().get(symbol);
                out.add(fqn != null ? fqn : target); // whole-module imports point at the module building
            }
            out.remove(t.fqn());
            deps.put(t.fqn(), out);
        }
        return new Linked(types, deps);
    }

    /** Module keys a file can be imported by: {@code src/a/b} for {@code src/a/b.ts}, and {@code src/a} for an index. */
    private static List<String> keys(String path) {
        String noExt = path.replaceFirst("\\.(ts|tsx|mts|cts)$", "");
        List<String> keys = new ArrayList<>(List.of(noExt));
        if (noExt.equals("index") || noExt.endsWith("/index")) {
            keys.add(noExt.equals("index") ? "" : noExt.substring(0, noExt.length() - "/index".length()));
        }
        return keys;
    }

    /** Resolves an import specifier to a project file path, or "" for external packages. */
    static String resolveSource(String fromPath, String spec, Map<String, List<String>> byKey,
                                Map<String, List<String>> keysByLastSegment) {
        String s = spec.replaceFirst("\\.(js|jsx|mjs|cjs|ts|tsx|mts|cts)$", "");
        if (s.startsWith("./") || s.startsWith("../") || s.equals(".") || s.equals("..")) {
            int slash = fromPath.lastIndexOf('/');
            String dir = slash < 0 ? "" : fromPath.substring(0, slash);
            String key = normalize(dir.isEmpty() ? s : dir + "/" + s);
            List<String> hits = key == null ? null : byKey.get(key);
            return hits == null ? "" : hits.getFirst();
        }
        // Path aliases such as "@/components/Button" or "~/lib/db": match the project file whose path ends that way.
        String tail = s.replaceFirst("^(@|~|#)/", "");
        if (tail.equals(s) && !s.contains("/")) {
            return ""; // a bare package name like "react"
        }
        String last = tail.substring(tail.lastIndexOf('/') + 1);
        String found = "";
        for (String key : keysByLastSegment.getOrDefault(last, List.of())) {
            if (key.equals(tail) || key.endsWith("/" + tail)) {
                if (!found.isEmpty()) {
                    return ""; // ambiguous
                }
                found = byKey.get(key).getFirst();
            }
        }
        return found;
    }

    private static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (out.isEmpty()) {
                    return null;
                }
                out.removeLast();
            } else {
                out.add(part);
            }
        }
        return String.join("/", out);
    }
}
