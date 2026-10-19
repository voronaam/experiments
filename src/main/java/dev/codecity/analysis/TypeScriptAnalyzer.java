package dev.codecity.analysis;

import dev.codecity.analysis.TypeScriptParse.Field;
import dev.codecity.analysis.TypeScriptParse.NodeKind;
import dev.codecity.analysis.TypeScriptParse.Tree;

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
 * TypeScript support (parsed with the in-house {@link TypeScriptParse}). Directories are districts; classes,
 * interfaces, enums and type aliases are buildings; each file's top-level functions and constants form one "module"
 * building named after the file — which is where React components and other function-style code live.
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
 * <p>References are the identifier tokens the parser flagged as possible references ({@link TypeScriptParse#REF}),
 * collected without scope analysis, so a local variable that happens to share a name with a top-level declaration or
 * an import also creates an edge. The Rust and D editions mirror this analyser.
 */
public final class TypeScriptAnalyzer implements LanguageSupport {

    // Declaration files (.d.ts) only describe the shape of code that lives elsewhere, so they are not analysed.
    private static final Pattern SOURCE = Pattern.compile(".*\\.(ts|tsx|mts|cts)$");
    private static final Pattern DECLARATION = Pattern.compile(".*\\.d\\.(ts|mts|cts)$");
    private static final Pattern TEST_FILE = Pattern.compile(".*\\.(test|spec)\\.(ts|tsx|mts|cts)$");
    /**
     * Node kinds of a function used as a value: {@code const f = () => …}, a method-valued class field, or a callback
     * argument.
     */
    private static final Set<NodeKind> FUNCTION_VALUES = Set.of(
            NodeKind.ARROW, NodeKind.FUNCTION_EXPR, NodeKind.GENERATOR_EXPR);

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
     * free code was found. A file with syntax errors still yields whatever the parser recovered around them.
     */
    @Override
    public ParsedFile parse(Path root, Path file, String code) {
        // .tsx enables JSX: in plain TypeScript <T>x is a type assertion, in TSX it is JSX.
        Tree tree = TypeScriptParse.parse(code, file.getFileName().toString().endsWith(".tsx"));
        FileState st = new FileState(root, file, tree, SourceText.lines(code));
        st.visitStatements(tree.root, "");
        st.finishModule();
        Context ctx = new Context(st.path, st.imports, st.names, st.defaultExport);
        return new ParsedFile(file, st.types, ctx, tree.hasError);
    }

    /** Per-file extraction state. Created for one {@link #parse} call and used by one thread only. */
    private final class FileState {
        final Tree tree;
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

        FileState(Path root, Path file, Tree tree, String[] lines) {
            this.tree = tree;
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

        /** Lines of code of a node. */
        int loc(int n) {
            return SourceText.countLoc(lines, tree.firstLine(n), tree.lastLine(n));
        }

        /** Text of the child with the given field, or "". */
        String fieldText(int n, Field f) {
            int c = tree.field(n, f);
            return c < 0 ? "" : tree.text(c);
        }

        /**
         * Visits the statements of a program or namespace body. {@code prefix} is the namespace path ("" at the top
         * level, {@code "N."} inside {@code namespace N}).
         *
         * <p>Unwraps the statement forms that wrap a declaration: {@code export …} (a DECL child, or for
         * {@code export default <expr>} a VALUE child) and {@code declare …} (AMBIENT). Expression statements are
         * top-level code for the module building. Re-exports ({@code export { a } from "./b"}) have neither child and
         * are ignored, as is {@code export declare …} (an ambient declaration inside an export, which is not unwrapped).
         */
        void visitStatements(int container, String prefix) {
            for (int child : tree.children(container)) {
                int decl = child;
                boolean isDefault = false;
                switch (tree.kind(child)) {
                    case IMPORT -> {
                        addImport(child);
                        continue;
                    }
                    case EXPORT, EXPORT_DEFAULT -> {
                        isDefault = tree.kind(child) == NodeKind.EXPORT_DEFAULT;
                        decl = tree.field(child, Field.DECL);
                        if (decl < 0) {
                            int value = tree.field(child, Field.VALUE);
                            if (value >= 0 && isDefault) {
                                exportDefaultExpression(value);
                            }
                            continue;
                        }
                    }
                    case AMBIENT -> {
                        int[] kids = tree.children(child);
                        if (kids.length == 0) {
                            continue;
                        }
                        decl = kids[0];
                    }
                    case EXPR_STMT -> {
                        int[] kids = tree.children(child);
                        if (kids.length > 0) {
                            addStatement(child, kids[0]);
                        }
                        continue;
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
         * <p>Classes, interfaces, enums and type aliases become buildings; functions and variables go into the module
         * building; namespaces ({@code namespace N}, {@code module N}, {@code declare module "name"}, whose quotes are
         * stripped) are walked recursively with the namespace added to the prefix and do not become buildings.
         */
        String declaration(int decl, String prefix) {
            switch (tree.kind(decl)) {
                case CLASS_DECL, ABSTRACT_CLASS_DECL, CLASS_EXPR -> {
                    return addClass(decl, prefix, null);
                }
                case INTERFACE -> {
                    String name = prefix + fieldText(decl, Field.NAME);
                    List<MethodInfo> methods = new ArrayList<>();
                    int attributes = countMembers(tree.field(decl, Field.BODY), methods);
                    type(name, "interface", decl, attributes, methods);
                    return name;
                }
                case ENUM -> {
                    String name = prefix + fieldText(decl, Field.NAME);
                    int body = tree.field(decl, Field.BODY);
                    type(name, "enum", decl, body < 0 ? 0 : tree.children(body).length, List.of());
                    return name;
                }
                case TYPE_ALIAS -> {
                    String name = prefix + fieldText(decl, Field.NAME);
                    List<MethodInfo> methods = new ArrayList<>();
                    // Only an object-literal type (type P = { x: number }) has members to count; unions etc. have none.
                    int value = tree.field(decl, Field.VALUE);
                    int attributes = value >= 0 && tree.kind(value) == NodeKind.OBJECT_TYPE
                            ? countMembers(value, methods) : 0;
                    type(name, "type", decl, attributes, methods);
                    return name;
                }
                case FUNCTION_DECL, GENERATOR_DECL -> {
                    String name = prefix + fieldText(decl, Field.NAME);
                    addFunction(name, decl, decl);
                    moduleLoc += loc(decl);
                    return name;
                }
                case VAR_DECL -> {
                    // const/let/var with one or more declarators. A function value becomes a function of the module
                    // building, a class expression a building named after the variable, anything else an attribute.
                    // Destructuring patterns get their pattern text as name. For "export default", the last
                    // declarator's name is returned. The statement's lines count once for the module building, however
                    // many declarators it has.
                    String last = null;
                    boolean free = false;
                    for (int d : tree.children(decl)) {
                        if (tree.kind(d) != NodeKind.DECLARATOR) {
                            continue;
                        }
                        String simple = fieldText(d, Field.NAME);
                        String name = prefix + simple;
                        int value = tree.field(d, Field.VALUE);
                        if (value >= 0 && FUNCTION_VALUES.contains(tree.kind(value))) {
                            addFunction(name, value, decl);
                            free = true;
                        } else if (value >= 0 && tree.kind(value) == NodeKind.CLASS_EXPR) {
                            addClass(value, prefix, simple);
                        } else {
                            moduleAttributes++;
                            moduleNames.add(name);
                            collectRefs(decl, moduleRefs);
                            free = true;
                        }
                        last = name;
                    }
                    if (free) {
                        moduleLoc += loc(decl);
                    }
                    return last;
                }
                case MODULE -> {
                    String name = fieldText(decl, Field.NAME).replace("'", "").replace("\"", "");
                    int body = tree.field(decl, Field.BODY);
                    if (body >= 0) {
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
         * function becomes a module-building function named {@code default}; a class expression becomes a building
         * named after its own name or {@code default}. Other expressions (objects, calls, …) leave the file without a
         * resolvable default export.
         */
        void exportDefaultExpression(int value) {
            switch (tree.kind(value)) {
                case IDENT -> defaultExport = tree.text(value);
                case ARROW, FUNCTION_EXPR -> {
                    addFunction("default", value, value);
                    moduleLoc += loc(value);
                    defaultExport = "default";
                }
                case CLASS_EXPR -> defaultExport = addClass(value, "", "default");
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
        void addImport(int stmt) {
            int source = tree.field(stmt, Field.SOURCE);
            if (source < 0) {
                return;
            }
            // The source node is the string literal including its quotes.
            String from = trimQuotes(tree.text(source));
            for (int part : tree.children(stmt)) {
                switch (tree.kind(part)) {
                    case IMPORT_DEFAULT -> imports.add(new Import(tree.text(part), from, "default"));
                    case IMPORT_NAMESPACE -> {
                        for (int id : tree.children(part)) {
                            imports.add(new Import(tree.text(id), from, "*"));
                        }
                    }
                    case IMPORT_SPECIFIER -> {
                        String name = fieldText(part, Field.NAME);
                        int alias = tree.field(part, Field.ALIAS);
                        imports.add(new Import(alias < 0 ? name : tree.text(alias), from, name));
                    }
                    default -> {
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
        TypeInfo type(String name, String kind, int decl, int noa, List<MethodInfo> methods) {
            Set<String> refs = new HashSet<>();
            collectRefs(decl, refs);
            int begin = tree.firstLine(decl);
            int end = tree.lastLine(decl);
            TypeInfo t = new TypeInfo(Language.TYPESCRIPT, id(name, kind), name, packageName, module, kind, file,
                    begin, end, SourceText.countLoc(lines, begin, end), noa, test, List.copyOf(methods),
                    Set.copyOf(refs), List.of(), List.of());
            types.add(t);
            return t;
        }

        /**
         * Adds a class building. Methods are method definitions, abstract and overload signatures, and fields whose
         * value is a function ({@code handle = () => …}); other fields and constructor parameter properties are
         * attributes. Static members count like instance members.
         *
         * @param fallbackName name for an anonymous class expression (the variable it is assigned to, or
         *                     {@code default}); null if there is none, and then nothing is added
         * @return the building's name, or null
         */
        String addClass(int decl, String prefix, String fallbackName) {
            int nameNode = tree.field(decl, Field.NAME);
            if (nameNode < 0 && fallbackName == null) {
                return null;
            }
            String name = prefix + (nameNode >= 0 ? tree.text(nameNode) : fallbackName);
            List<MethodInfo> methods = new ArrayList<>();
            int attributes = 0;
            int body = tree.field(decl, Field.BODY);
            if (body >= 0) {
                for (int member : tree.children(body)) {
                    switch (tree.kind(member)) {
                        case METHOD_DEF -> {
                            methods.add(method(member, tree.field(member, Field.NAME), member));
                            if (fieldText(member, Field.NAME).equals("constructor")) {
                                attributes += parameterProperties(member);
                            }
                        }
                        case METHOD_SIG -> methods.add(method(member, tree.field(member, Field.NAME), -1));
                        case FIELD_DEF -> {
                            int value = tree.field(member, Field.VALUE);
                            if (value >= 0 && FUNCTION_VALUES.contains(tree.kind(value))) {
                                methods.add(method(value, tree.field(member, Field.NAME), member));
                            } else {
                                attributes++;
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
            String kind = tree.kind(decl) == NodeKind.ABSTRACT_CLASS_DECL ? "abstract" : "class";
            type(name, kind, decl, attributes, methods);
            return name;
        }

        /**
         * Constructor parameters with an accessibility modifier ({@code private x: T}) declare fields. ({@code readonly}
         * alone does too in TypeScript, but is not counted, to keep the metric stable across CodeCity versions.)
         */
        int parameterProperties(int constructor) {
            int params = tree.field(constructor, Field.PARAMS);
            int n = 0;
            if (params >= 0) {
                for (int p : tree.children(params)) {
                    if (tree.kind(p) == NodeKind.PARAM_PROPERTY) {
                        n++;
                    }
                }
            }
            return n;
        }

        /**
         * Counts the members of an interface body or object type: properties are attributes, method, call
         * ({@code (x): T}) and construct ({@code new (x): T}) signatures are methods (complexity 1). Index signatures
         * are not counted.
         */
        int countMembers(int body, List<MethodInfo> methods) {
            if (body < 0) {
                return 0;
            }
            int attributes = 0;
            for (int m : tree.children(body)) {
                switch (tree.kind(m)) {
                    case PROPERTY_SIG -> attributes++;
                    case METHOD_SIG, CALL_SIG, CONSTRUCT_SIG -> methods.add(method(m, tree.field(m, Field.NAME), -1));
                    default -> {
                    }
                }
            }
            return attributes;
        }

        /**
         * Measures one function-like node. Parameters are the entries of the node's parameter list (an arrow
         * function's single unparenthesised parameter counts as one).
         *
         * @param node     the node with the parameters and the line range
         * @param nameNode node holding the name, or -1 for "(anonymous)"
         * @param body     node whose complexity is measured (-1 for signatures without a body)
         */
        MethodInfo method(int node, int nameNode, int body) {
            String name = nameNode < 0 ? "(anonymous)" : tree.text(nameNode);
            int params = tree.field(node, Field.PARAMS);
            int count = params < 0 ? 0 : tree.children(params).length;
            return new MethodInfo(name, tree.firstLine(node), loc(node), body < 0 ? 1 : complexity(tree, body), count);
        }

        /**
         * Adds a free function to the module building. {@code fn} is the function itself (measured for complexity and
         * parameters), {@code statement} the whole declaration (scanned for references), e.g. the {@code const f = …}
         * around an arrow function. The caller adds the statement's lines to the module building's LOC, once per
         * statement.
         */
        void addFunction(String name, int fn, int statement) {
            MethodInfo m = method(fn, -1, fn);
            functions.add(new MethodInfo(name, m.line(), m.loc(), m.complexity(), m.parameters()));
            moduleNames.add(name);
            collectRefs(statement, moduleRefs);
        }

        /**
         * Top-level code: {@code describe("cart", () => …)} or {@code app.get("/", handler)} becomes a method named
         * after the call, so test suites and route tables show up; anything else just adds lines and references.
         */
        void addStatement(int statement, int expression) {
            moduleLoc += loc(statement);
            collectRefs(statement, moduleRefs);
            if (tree.kind(expression) != NodeKind.CALL) {
                return;
            }
            int args = tree.field(expression, Field.ARGS);
            if (args < 0 || tree.kind(args) != NodeKind.ARGS) {
                return; // no arguments, or a tagged template
            }
            boolean hasCallback = false;
            String label = null;
            for (int arg : tree.children(args)) {
                hasCallback |= FUNCTION_VALUES.contains(tree.kind(arg));
                if (label == null && (tree.kind(arg) == NodeKind.STR || tree.kind(arg) == NodeKind.TEMPLATE)) {
                    label = tree.text(arg);
                }
            }
            if (!hasCallback) {
                return;
            }
            int[] parts = tree.children(expression);
            String callee = parts.length == 0 ? "" : tree.text(parts[0]);
            // e.g. describe("cart"); the label keeps its quotes and is cut to 48 characters (code points) including the
            // ellipsis.
            if (label == null) {
                label = "";
            } else if (label.codePointCount(0, label.length()) > 48) {
                label = label.substring(0, label.offsetByCodePoints(0, 47)) + "…";
            }
            // The complexity covers all arguments, so it includes the callback bodies.
            functions.add(new MethodInfo(callee + "(" + label + ")", tree.firstLine(statement), loc(statement),
                    complexity(tree, args), 0));
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
         * Every identifier the parser flagged as a possible reference ({@link TypeScriptParse#REF}) under {@code node},
         * values included, since a component or function is used as a value ({@code <Button/>}, {@code render()}).
         * Property names ({@code a.b}'s {@code b}) are not flagged. Most names will not resolve; the linker drops them.
         */
        void collectRefs(int node, Set<String> refs) {
            for (int t = tree.tokenFirst(node), e = tree.tokenEnd(node); t < e; t++) {
                if ((tree.flags(t) & TypeScriptParse.REF) != 0) {
                    refs.add(tree.tokenText(t));
                }
            }
        }
    }

    /**
     * Strips quotes and backticks from both ends of a module specifier (all of them, like Rust's {@code trim_matches}).
     */
    private static String trimQuotes(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isQuote(s.charAt(a))) {
            a++;
        }
        while (b > a && isQuote(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean isQuote(char c) {
        return c == '\'' || c == '"' || c == '`';
    }

    /**
     * McCabe cyclomatic complexity: 1 + decision points ({@code if}, loops, {@code case}, {@code catch}, {@code ?:},
     * {@code &&}, {@code ||}, {@code ??}, as flagged {@link TypeScriptParse#DECISION} by the parser). Nested functions
     * inside {@code node} count towards it, as in the Java analyser.
     */
    static int complexity(Tree tree, int node) {
        int cx = 1;
        for (int t = tree.tokenFirst(node), e = tree.tokenEnd(node); t < e; t++) {
            if ((tree.flags(t) & TypeScriptParse.DECISION) != 0) {
                cx++;
            }
        }
        return cx;
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
