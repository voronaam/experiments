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
 *
 * <h2>Identity</h2>
 * TypeScript has no package declaration, so everything is derived from the file's path relative to the root:
 * <ul>
 *   <li>module: the directory of the nearest {@code package.json} ("" for the root project), so each project of a
 *       monorepo is a module district;</li>
 *   <li>package: the file's directory relative to that project, e.g. {@code src/ui};</li>
 *   <li>fqn: {@code <path>:<name>} for declarations ({@code src/ui/App.tsx:Button}), and the bare path for the
 *       file's module building ({@code src/ui/App.tsx}).</li>
 * </ul>
 * Declarations inside {@code namespace N { … }} are named {@code N.Name}.
 *
 * <h2>Linking</h2>
 * Each file keeps a {@link Context}: its imports, the top-level names it declares and its default export. The linker
 * resolves a referenced identifier first against the file's own declarations, then through its imports to the file
 * they come from (relative specifiers and path aliases; bare package names are external) and to the declaration
 * exported under that name. Re-exports ({@code export … from}) are not followed: an import through a barrel file
 * points at the barrel's module building, if it has one.
 *
 * <p>References are collected per identifier, without scope analysis, so a local variable that happens to share a
 * name with a top-level declaration or an import also creates an edge. The Rust and D editions mirror this analyser.
 */
public final class TypeScriptAnalyzer implements LanguageSupport {

    // Declaration files (.d.ts) only describe the shape of code that lives elsewhere, so they are not analysed.
    private static final Pattern SOURCE = Pattern.compile(".*\\.(ts|tsx|mts|cts)$");
    private static final Pattern DECLARATION = Pattern.compile(".*\\.d\\.(ts|mts|cts)$");
    private static final Pattern TEST_FILE = Pattern.compile(".*\\.(test|spec)\\.(ts|tsx|mts|cts)$");
    /**
     * Node kinds of a function used as a value: {@code const f = () => …}, a method-valued class field, or a callback
     * argument. {@code function} is what older versions of the grammar call {@code function_expression}.
     */
    private static final Set<String> FUNCTION_VALUES = Set.of(
            "arrow_function", "function_expression", "function", "generator_function");
    /**
     * Decision points for {@link #complexity}: {@code for_in_statement} covers both {@code for … in} and
     * {@code for … of}; {@code switch_case} is a {@code case} ({@code default} is a separate node kind and not
     * counted). {@code &&}, {@code ||} and {@code ??} are counted separately.
     */
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "for_in_statement", "while_statement", "do_statement",
            "switch_case", "catch_clause", "ternary_expression");

    // One parser per thread and grammar: a TSParser must not be shared between the threads parsing in parallel. .tsx
    // needs its own grammar because JSX is ambiguous with the <T>expr type assertion of plain TypeScript.
    private final ThreadLocal<TSParser> tsParser = ThreadLocal.withInitial(() -> Syntax.parser(TreeSitterTypescript::new));
    private final ThreadLocal<TSParser> tsxParser = ThreadLocal.withInitial(() -> Syntax.parser(TreeSitterTsx::new));
    /**
     * Absolute directory → its project directory (see {@link #projectOf}), shared by the parsing threads. Cleared at the
     * start of every {@link #link}, which re-derives the projects, so a {@code package.json} added or removed during a
     * watch session is noticed by the next analysis.
     */
    private final Map<Path, String> projectDirs = new ConcurrentHashMap<>();

    /** An import binding: {@code local} names {@code imported} ("default", "*" or an export name) of {@code source}. */
    record Import(String local, String source, String imported) {
    }

    /**
     * What the linker needs to know about a file.
     *
     * @param path          path relative to the root, e.g. {@code src/ui/App.tsx}
     * @param imports       the file's import bindings, in source order
     * @param names         top-level declared name → fqn of the building that holds it: its own building for a class,
     *                      interface, enum or alias, the file's module building for a function or constant
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

    /**
     * Extracts one file in a single pass over its top-level statements, then builds the module building from whatever
     * free code was found. A file with syntax errors still yields whatever tree-sitter could parse around them.
     */
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

    /** Per-file extraction state. Created for one {@link #parse} call and used by one thread only. */
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
        /** fqns already handed out in this file, to keep ids unique, see {@link #id}. */
        final Set<String> usedIds = new HashSet<>();
        String defaultExport;
        // the file's "module" building: free functions and top-level constants
        final List<MethodInfo> functions = new ArrayList<>();
        final Set<String> moduleRefs = new HashSet<>();
        /** Names of the functions and constants, mapped to the module building in {@link #finishModule}. */
        final List<String> moduleNames = new ArrayList<>();
        /** Top-level constants and variables that are not functions or classes: the module building's NOA. */
        int moduleAttributes;
        /** LOC of the free statements only; the module building's LOC is not the whole file's. */
        int moduleLoc;

        FileState(Path root, Path file, Syntax syntax, String[] lines) {
            this.syntax = syntax;
            this.lines = lines;
            this.file = file;
            Path rel = root.relativize(file);
            this.path = SourceText.slashes(rel);
            String project = projectOf(root, file.getParent());
            this.module = project;
            this.packageName = packageOf(project, rel);
            this.test = SourceText.inTestDirectory(rel) || TEST_FILE.matcher(file.getFileName().toString()).matches();
        }

        String moduleId() {
            return path;
        }

        /**
         * Visits the statements of a program or namespace body. {@code prefix} is the namespace path ("" at the top
         * level, {@code "N."} inside {@code namespace N}).
         *
         * <p>Statement kinds handled here before {@link #declaration}:
         * <ul>
         *   <li>{@code export_statement}: {@code export [default] <declaration>} carries it in the
         *       {@code declaration} field; {@code export default <expression>} in {@code value}; {@code export { … }}
         *       and {@code export … from} have neither and are skipped.</li>
         *   <li>{@code ambient_declaration} ({@code declare …}) wraps a declaration as its first named child.</li>
         *   <li>{@code expression_statement}: the grammar parses a statement-level {@code namespace N { … }} as an
         *       expression statement around an {@code internal_module}; that is unwrapped like a declaration. Any
         *       other expression is free top-level code for the module building.</li>
         * </ul>
         */
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
                        // "default" is an anonymous keyword token, hence the search through all children.
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

        /**
         * Handles one declaration; returns the declared name (for default exports) or null.
         *
         * <p>{@code class} is a class expression; {@code internal_module} is
         * {@code namespace N} and {@code module} is {@code module N} or {@code declare module "name"}, whose quotes are
         * stripped. Their bodies are visited recursively with the namespace added to the prefix.
         */
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
                    moduleLoc += SourceText.countLoc(lines, Syntax.firstLine(decl), Syntax.lastLine(decl));
                    return name;
                }
                case "lexical_declaration", "variable_declaration" -> {
                    // const/let/var with one or more declarators. A function value becomes a function of the module
                    // building, a class expression a building named after the variable, anything else an attribute.
                    // Destructuring patterns get their pattern text as name. For "export default", the last
                    // declarator's name is returned. The statement's lines count once for the module building, however
                    // many declarators it has.
                    String last = null;
                    boolean free = false;
                    for (TSNode d : Syntax.namedChildren(decl)) {
                        if (!d.getType().equals("variable_declarator")) {
                            continue;
                        }
                        String name = prefix + syntax.text(Syntax.field(d, "name"));
                        TSNode value = Syntax.field(d, "value");
                        if (value != null && FUNCTION_VALUES.contains(value.getType())) {
                            addFunction(name, value, decl);
                            free = true;
                        } else if (value != null && value.getType().equals("class")) {
                            addClass(value, prefix, syntax.text(Syntax.field(d, "name")));
                        } else {
                            moduleAttributes++;
                            moduleNames.add(name);
                            collectRefs(decl, moduleRefs);
                            free = true;
                        }
                        last = name;
                    }
                    if (free) {
                        moduleLoc += SourceText.countLoc(lines, Syntax.firstLine(decl), Syntax.lastLine(decl));
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

        /**
         * {@code export default <expression>}: an identifier names a declaration elsewhere in the file; an anonymous
         * function or class is recorded under the name {@code default}. Other expressions (objects, calls, …) leave
         * the file without a resolvable default export.
         */
        void exportDefaultExpression(TSNode value) {
            switch (value.getType()) {
                case "identifier" -> defaultExport = syntax.text(value);
                case "arrow_function", "function_expression", "function" -> {
                    addFunction("default", value, value);
                    moduleLoc += SourceText.countLoc(lines, Syntax.firstLine(value), Syntax.lastLine(value));
                    defaultExport = "default";
                }
                case "class" -> defaultExport = addClass(value, "", "default");
                default -> {
                }
            }
        }

        /**
         * Records the bindings of one import statement:
         * <pre>
         *   import D from "./m"            → (D, ./m, default)
         *   import * as NS from "./m"      → (NS, ./m, *)
         *   import { a, b as c } from "./m" → (a, ./m, a), (c, ./m, b)
         * </pre>
         * {@code import "./m"} (side effects only) and {@code import x = require(…)} bind nothing usable here.
         */
        void addImport(TSNode stmt) {
            TSNode source = Syntax.field(stmt, "source");
            if (source == null) {
                return;
            }
            // The source node is the string literal including its quotes.
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

        /**
         * The fqn for a declaration, and registers its name. A second declaration with the same name gets
         * {@code #kind} appended, further ones with the same name and kind {@code #kind2}, {@code #kind3}, … in
         * source order. {@link #names} keeps the first declaration of a name.
         */
        String id(String name, String kind) {
            String base = path + ":" + name;
            String id = base;
            // e.g. declaration merging: an interface and a class with the same name
            for (int n = 1; !usedIds.add(id); n++) {
                id = base + "#" + kind + (n == 1 ? "" : n);
            }
            names.putIfAbsent(name, id);
            return id;
        }

        /** Creates and records the building for a class, interface, enum or type alias spanning {@code decl}. */
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

        /**
         * Adds a class building. Methods are method definitions, abstract and overload signatures, and fields whose
         * value is a function ({@code handle = () => …}); other fields and
         * constructor parameter properties are attributes. Static members count like instance members.
         *
         * @param fallbackName name for an anonymous class expression (the variable it is assigned to, or
         *                     {@code default}); null if there is none, and then nothing is added
         * @return the building's name, or null
         */
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

        /** A type alias; only an object-type alias ({@code type P = { … }}) has members to count. */
        String addAlias(TSNode decl, String prefix) {
            String name = prefix + syntax.text(Syntax.field(decl, "name"));
            List<MethodInfo> methods = new ArrayList<>();
            TSNode value = Syntax.field(decl, "value");
            int attributes = value != null && value.getType().equals("object_type") ? countMembers(value, methods) : 0;
            type(name, "type", decl, attributes, methods);
            return name;
        }

        /**
         * Counts the members of an interface body or object type: properties are attributes, method, call and
         * construct signatures are methods (complexity 1). Index signatures are not counted.
         */
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
            // Every named child of the enum body except comments is a member.
            type(name, "enum", decl, body == null ? 0 : countNamed(body), List.of());
            return name;
        }

        /**
         * Measures one function-like node.
         *
         * @param node     the node with the parameters and the line range
         * @param nameNode node holding the name, or null for "(anonymous)"
         * @param body     node whose complexity is measured (null for signatures without a body)
         */
        MethodInfo method(TSNode node, TSNode nameNode, TSNode body) {
            String name = nameNode == null ? "(anonymous)" : syntax.text(nameNode);
            TSNode params = Syntax.field(node, "parameters");
            // An arrow function with a single unparenthesised parameter (x => …) has a "parameter" field instead of a
            // "parameters" list.
            int count = params == null ? (Syntax.field(node, "parameter") != null ? 1 : 0) : countNamed(params);
            int begin = Syntax.firstLine(node);
            int end = Syntax.lastLine(node);
            return new MethodInfo(name, begin, SourceText.countLoc(lines, begin, end),
                    body == null ? 1 : complexity(body), count);
        }

        /**
         * Adds a free function to the module building. {@code fn} is the function itself (measured for complexity and
         * parameters), {@code statement} the whole declaration (scanned for references), e.g. the {@code const f = …}
         * around an arrow function. The caller adds the statement's lines to the module building's LOC, once per
         * statement.
         */
        void addFunction(String name, TSNode fn, TSNode statement) {
            MethodInfo m = method(fn, null, fn);
            functions.add(new MethodInfo(name, m.line(), m.loc(), m.complexity(), m.parameters()));
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
            // e.g. describe("cart"); the label keeps its quotes and is cut to 48 characters including the ellipsis.
            String name = callee + "(" + (label == null ? "" : label.length() > 48 ? label.substring(0, 47) + "…" : label) + ")";
            int begin = Syntax.firstLine(statement);
            int end = Syntax.lastLine(statement);
            // The complexity covers all arguments, so it includes the callback bodies.
            functions.add(new MethodInfo(name, begin, SourceText.countLoc(lines, begin, end), complexity(args), 0));
        }

        /**
         * Adds the file's module building, unless the file has no free code at all (e.g. a file with only classes and
         * interfaces, or only re-exports). It spans the whole file, but its LOC only counts the free statements, and
         * its name is the file name. Free names that no declaration has claimed yet are mapped to it.
         */
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

        /**
         * Every identifier and type identifier in the subtree, values included, since a component or function is used
         * as a value ({@code <Button/>}, {@code render()}). Property names ({@code a.b}'s {@code b}) are
         * {@code property_identifier} nodes and are not collected. Most names will not resolve; the linker drops them.
         */
        void collectRefs(TSNode node, Set<String> refs) {
            Syntax.walk(node, n -> {
                String type = n.getType();
                if (type.equals("type_identifier") || type.equals("identifier")) {
                    refs.add(syntax.text(n));
                }
            });
        }
    }

    /** Named children except comments, which tree-sitter allows between the members of a list. */
    static int countNamed(TSNode node) {
        return (int) Syntax.namedChildren(node).stream().filter(c -> !c.getType().equals("comment")).count();
    }

    /**
     * Cyclomatic complexity: 1 + {@link #DECISIONS} + short-circuit operators ({@code &&}, {@code ||}, {@code ??}).
     * Nested functions inside {@code body} add to it, as in the Java analyser.
     */
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

    /**
     * Directory of the nearest package.json at or above {@code dir} (relative to root, "" for the root itself).
     *
     * <p>The search stops at the root, so a package.json above the analysed directory is never used. Every directory
     * walked through is cached with the answer, so a directory is normally checked on disk only once. Two threads may
     * compute the same entry concurrently; they store the same value, so the race is harmless.
     */
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

    /**
     * The package of a file: its directory relative to the project, "web/src/ui" in project "web" → "src/ui". The
     * project is an ancestor of the file, so the directory starts with project + "/" unless the two are equal.
     */
    private static String packageOf(String project, Path relativeFile) {
        String dir = SourceText.slashes(relativeFile.getParent());
        return project.isEmpty() ? dir : dir.equals(project) ? "" : dir.substring(project.length() + 1);
    }

    // ------------------------------------------------------------------ linking

    /**
     * Resolves each building's references to fqns (see the class comment). Lookups, in order, for a name {@code X}:
     * <ol>
     *   <li>declared at the top level of the same file → that declaration's building;</li>
     *   <li>imported → the imported file ({@link #resolveSource}); then, by the import's kind, its default export,
     *       the export of that name, or (for {@code * as X}, or when the name is not found) its module building.</li>
     * </ol>
     * An edge may point at a module building that does not exist (a file without free code);
     * {@link SourceAnalyzer} drops such edges along with the other unknown targets.
     *
     * <p>Modules and packages are derived again here, with a fresh project cache: in watch mode an unchanged file keeps
     * its cached parse, which may predate a {@code package.json} added or removed since.
     */
    @Override
    public Linked link(Path root, List<ParsedFile> files) {
        projectDirs.clear();
        List<TypeInfo> types = new ArrayList<>();
        Map<String, Context> byPath = new HashMap<>();
        // module key (path without extension) → file paths, in TypeScript's resolution order (see keyRank)
        Map<String, List<String>> byKey = new HashMap<>();
        // last path segment of a key → keys, an index for the suffix matching of path aliases
        Map<String, List<String>> keysByLastSegment = new HashMap<>();
        for (ParsedFile f : files) {
            String project = projectOf(root, f.file().getParent());
            String pkg = packageOf(project, root.relativize(f.file()));
            for (TypeInfo t : f.types()) {
                types.add(t.module().equals(project) && t.packageName().equals(pkg) ? t
                        : new TypeInfo(t.language(), t.fqn(), t.name(), pkg, project, t.kind(), t.file(), t.line(),
                                t.endLine(), t.loc(), t.noa(), t.test(), t.methods(), t.references(),
                                t.explicitImports(), t.wildcardImports()));
            }
            if (f.context() instanceof Context c) {
                byPath.put(c.path(), c);
                for (String key : keys(c.path())) {
                    List<String> paths = byKey.computeIfAbsent(key, k -> new ArrayList<>());
                    if (paths.isEmpty()) {
                        keysByLastSegment.computeIfAbsent(key.substring(key.lastIndexOf('/') + 1),
                                k -> new ArrayList<>()).add(key);
                    }
                    paths.add(c.path());
                }
            }
        }
        byKey.forEach((key, paths) -> paths.sort(java.util.Comparator.comparingInt(p -> keyRank(key, p))));
        Map<String, Set<String>> deps = new HashMap<>();
        // "<importing file>|<specifier>" → resolved path ("" if external); the buildings of a file share its imports
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

    /**
     * Module keys a file can be imported by: {@code src/a/b} for {@code src/a/b.ts}, and {@code src/a} for an index.
     * A root-level {@code index.ts} also gets the empty key.
     */
    private static List<String> keys(String path) {
        String noExt = path.replaceFirst("\\.(ts|tsx|mts|cts)$", "");
        List<String> keys = new ArrayList<>(List.of(noExt));
        if (noExt.equals("index") || noExt.endsWith("/index")) {
            keys.add(noExt.equals("index") ? "" : noExt.substring(0, noExt.length() - "/index".length()));
        }
        return keys;
    }

    /**
     * TypeScript's preference among the files sharing a module key: the file itself before a directory index, and
     * {@code .ts} before {@code .tsx}, {@code .mts} and {@code .cts}. So {@code ./a} is {@code a.ts}, then
     * {@code a.tsx}, …, then {@code a/index.ts}, {@code a/index.tsx}, …
     */
    private static int keyRank(String key, String path) {
        String noExt = path.replaceFirst("\\.(ts|tsx|mts|cts)$", "");
        String ext = path.substring(noExt.length());
        return (noExt.equals(key) ? 0 : 4) + List.of(".ts", ".tsx", ".mts", ".cts").indexOf(ext);
    }

    /**
     * Resolves an import specifier to a project file path, or "" for external packages.
     *
     * <p>A JavaScript or TypeScript extension is dropped first, since ESM-style TypeScript imports {@code ./a.js} to
     * mean {@code ./a.ts}. Then:
     * <ul>
     *   <li>Relative specifiers are resolved against the importing file's directory; one that climbs above the root is
     *       external. When several files share a key (e.g. {@code a.ts}, {@code a.tsx} and {@code a/index.ts}), the
     *       one TypeScript would pick wins (see {@link #keyRank}).</li>
     *   <li>A specifier starting with {@code @/}, {@code ~/} or {@code #/} is treated as a path alias without reading
     *       {@code tsconfig.json}: after dropping the prefix, it matches the one key that equals it or ends with
     *       {@code /} plus it, and from that key's files the one TypeScript would pick. No match, or more than one
     *       key, means external.</li>
     *   <li>Anything else is a package and always external: a bare name ({@code react}), a scoped package
     *       ({@code @scope/pkg}) or a deep import ({@code lodash/fp}).</li>
     * </ul>
     */
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
        if (tail.equals(s)) {
            return ""; // a package such as "react", "@scope/pkg" or "lodash/fp"
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

    /** Collapses {@code .} and {@code ..} segments; null when {@code ..} would leave the root. */
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
