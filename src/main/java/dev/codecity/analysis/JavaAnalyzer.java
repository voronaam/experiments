package dev.codecity.analysis;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterJava;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Java support (tree-sitter-java): extracts the classic CodeCity metrics per type.
 *
 * <p>Every class, interface, enum, record and annotation type is a building, nested and member types included (each
 * gets its own building, e.g. {@code Order.Line}); local and anonymous classes are not. Packages are the districts,
 * and the path up to the first {@code src} directory is the build module.
 *
 * <p>Parsing is purely syntactic: no classpath and no symbol solver. References are collected as the names written in
 * the source and resolved in {@link #link} against the set of project types, following Java's scoping rules closely
 * enough for project-internal edges. Java needs no extra linker context: everything is stored on {@link TypeInfo}
 * (references and imports), so {@link ParsedFile#context()} is null.
 *
 * <p>The Rust and D editions mirror this analyser node kind for node kind, with the same grammar version, so all
 * three produce the same city.
 */
public final class JavaAnalyzer implements LanguageSupport {

    /** Node kinds that declare a type, at the top level or as members of another type's body. */
    private static final Set<String> TYPE_DECLARATIONS = Set.of(
            "class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
            "annotation_type_declaration");
    /**
     * Decision points for {@link #complexity}. {@code enhanced_for_statement} is the for-each loop;
     * {@code switch_label} and {@code &&}/{@code ||} are counted separately.
     */
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "enhanced_for_statement", "while_statement", "do_statement",
            "catch_clause", "ternary_expression");

    /** One parser per thread: a TSParser must not be shared between the threads parsing in parallel. */
    private final ThreadLocal<TSParser> parser = ThreadLocal.withInitial(() -> Syntax.parser(TreeSitterJava::new));

    @Override
    public Language language() {
        return Language.JAVA;
    }

    @Override
    public boolean accepts(Path file) {
        return file.getFileName().toString().endsWith(".java");
    }

    @Override
    public Linked link(Path root, List<ParsedFile> files) {
        // The same FQN can occur in several modules (or as a test fixture); keep the first so the index stays unique.
        Map<String, TypeInfo> byFqn = new TreeMap<>();
        for (ParsedFile f : files) {
            for (TypeInfo t : f.types()) {
                byFqn.putIfAbsent(t.fqn(), t);
            }
        }
        List<TypeInfo> unique = new ArrayList<>(byFqn.values());
        return new Linked(unique, resolveDependencies(unique, byFqn));
    }

    /**
     * Extracts every type of one file: top-level and member types, depth-first in source order. Tree-sitter always
     * yields a tree; a file with syntax errors is analysed as far as it goes and only flagged as failed.
     */
    @Override
    public ParsedFile parse(Path root, Path file, String code) {
        Syntax syntax = Syntax.parse(parser.get(), code);
        Path relative = root.relativize(file);

        // Imports are recorded by what they make visible under a simple name:
        //   import a.b.C;          → explicit "a.b.C"
        //   import static a.b.C.m; → explicit "a.b.C" (the member's declaring type is what this file depends on)
        //   import static a.b.C.*; → explicit "a.b.C"
        //   import a.b.*;          → wildcard "a.b"
        // A static import thus makes C a resolution candidate for a reference to "C". Calls to a statically imported
        // member name no type, so they do not by themselves create a dependency on C.
        String pkg = "";
        List<String> explicitImports = new ArrayList<>();
        List<String> wildcardImports = new ArrayList<>();
        List<TSNode> top = Syntax.namedChildren(syntax.root);
        for (TSNode n : top) {
            switch (n.getType()) {
                case "package_declaration" -> {
                    // Skip package annotations (allowed in package-info.java); the next named child is the name.
                    for (TSNode c : Syntax.namedChildren(n)) {
                        String kind = c.getType();
                        if (!kind.equals("annotation") && !kind.equals("marker_annotation")) {
                            pkg = stripWhitespace(syntax.text(c));
                            break;
                        }
                    }
                }
                case "import_declaration" -> {
                    // `static` is an anonymous token of the import_declaration, `*` a named `asterisk` node.
                    boolean isStatic = Syntax.children(n).stream().anyMatch(c -> c.getType().equals("static"));
                    List<TSNode> named = Syntax.namedChildren(n);
                    boolean asterisk = named.stream().anyMatch(c -> c.getType().equals("asterisk"));
                    TSNode nameNode = named.stream()
                            .filter(c -> c.getType().equals("scoped_identifier") || c.getType().equals("identifier"))
                            .findFirst().orElse(null);
                    if (nameNode == null) {
                        continue;
                    }
                    // Names may be written with whitespace or line breaks around the dots; normalise to `a.b.C`.
                    String name = stripWhitespace(syntax.text(nameNode));
                    if (isStatic && !asterisk) {
                        int dot = name.lastIndexOf('.');
                        explicitImports.add(dot >= 0 ? name.substring(0, dot) : name);
                    } else if (asterisk) {
                        (isStatic ? explicitImports : wildcardImports).add(name);
                    } else {
                        explicitImports.add(name);
                    }
                }
                default -> {
                }
            }
        }

        FileState st = new FileState(syntax, SourceText.lines(code), file, pkg, moduleOf(relative),
                isTest(relative), List.copyOf(explicitImports), List.copyOf(wildcardImports));
        for (TSNode n : top) {
            if (TYPE_DECLARATIONS.contains(n.getType())) {
                st.collectType(n, "");
            }
        }
        return new ParsedFile(file, st.types, null, syntax.root.hasError());
    }

    /** Per-file data shared by every type declared in the file, and the types found so far (in pre-order). */
    private static final class FileState {
        final Syntax syntax;
        final String[] lines;
        final Path file;
        final String pkg;
        final String module;
        final boolean test;
        final List<String> explicitImports;
        final List<String> wildcardImports;
        final List<TypeInfo> types = new ArrayList<>();

        FileState(Syntax syntax, String[] lines, Path file, String pkg, String module, boolean test,
                  List<String> explicitImports, List<String> wildcardImports) {
            this.syntax = syntax;
            this.lines = lines;
            this.file = file;
            this.pkg = pkg;
            this.module = module;
            this.test = test;
            this.explicitImports = explicitImports;
            this.wildcardImports = wildcardImports;
        }

        /**
         * Adds a type and, recursively, the member types declared in its body (local classes are not types).
         *
         * <p>{@code outer} is the enclosing type's name relative to the package ("" at the top level), so a member
         * type is named {@code Outer.Inner}. Metrics:
         * <ul>
         *   <li>NOA (attributes): one per declared variable in fields and interface constants ({@code int a, b;} is
         *       two), one per enum constant and one per record component;</li>
         *   <li>methods: methods and constructors (initialiser blocks and compact record constructors are not
         *       counted);</li>
         *   <li>LOC: the whole declaration, including member types, so an outer type's LOC contains its inner
         *       types';</li>
         *   <li>references: collected from the whole declaration, member types included.</li>
         * </ul>
         */
        void collectType(TSNode decl, String outer) {
            String kind = decl.getType();
            String simple = syntax.text(Syntax.field(decl, "name"));
            String name = outer.isEmpty() ? simple : outer + "." + simple;
            String fqn = pkg.isEmpty() ? name : pkg + "." + name;

            List<MethodInfo> methods = new ArrayList<>();
            List<TSNode> nested = new ArrayList<>();
            int noa = 0;
            // Record components (`record P(int x, int y)`) are the record's fields.
            if (kind.equals("record_declaration")) {
                TSNode params = Syntax.field(decl, "parameters");
                if (params != null) {
                    noa += countOfKind(Syntax.namedChildren(params), "formal_parameter");
                }
            }
            TSNode body = Syntax.field(decl, "body");
            if (body != null) {
                List<TSNode> members = Syntax.namedChildren(body);
                // Enum bodies keep their non-constant members in a nested enum_body_declarations node.
                for (int i = 0; i < members.size(); i++) {
                    if (members.get(i).getType().equals("enum_body_declarations")) {
                        TSNode extra = members.remove(i);
                        members.addAll(Syntax.namedChildren(extra));
                        break;
                    }
                }
                for (TSNode m : members) {
                    String k = m.getType();
                    switch (k) {
                        // constant_declaration is a field in an interface body.
                        case "field_declaration", "constant_declaration" ->
                                noa += countOfKind(Syntax.namedChildren(m), "variable_declarator");
                        case "enum_constant" -> noa++;
                        case "method_declaration", "constructor_declaration" -> methods.add(method(m));
                        default -> {
                            if (TYPE_DECLARATIONS.contains(k)) {
                                nested.add(m);
                            }
                        }
                    }
                }
            }
            int begin = Syntax.firstLine(decl);
            int end = Syntax.lastLine(decl);
            types.add(new TypeInfo(
                    Language.JAVA, fqn, name, pkg, module, kindOf(decl, kind), file, begin, end,
                    SourceText.countLoc(lines, begin, end), noa, test, methods, references(decl),
                    explicitImports, wildcardImports));
            for (TSNode n : nested) {
                collectType(n, name);
            }
        }

        /**
         * The building kind shown in the viewers. Classes are split into "abstract" and "class" by their
         * {@code abstract} modifier, which is an anonymous token inside the {@code modifiers} node.
         */
        private String kindOf(TSNode decl, String kind) {
            return switch (kind) {
                case "interface_declaration" -> "interface";
                case "enum_declaration" -> "enum";
                case "record_declaration" -> "record";
                case "annotation_type_declaration" -> "annotation";
                default -> {
                    TSNode modifiers = Syntax.namedChildren(decl).stream()
                            .filter(c -> c.getType().equals("modifiers")).findFirst().orElse(null);
                    boolean isAbstract = modifiers != null && Syntax.children(modifiers).stream()
                            .anyMatch(c -> syntax.text(c).equals("abstract"));
                    yield isAbstract ? "abstract" : "class";
                }
            };
        }

        /**
         * Metrics of a method or constructor. Parameters include a trailing varargs parameter
         * ({@code spread_parameter}); the receiver parameter ({@code Foo this}) is not counted.
         */
        private MethodInfo method(TSNode m) {
            int begin = Syntax.firstLine(m);
            int end = Syntax.lastLine(m);
            TSNode params = Syntax.field(m, "parameters");
            int parameters = 0;
            if (params != null) {
                for (TSNode p : Syntax.namedChildren(params)) {
                    String k = p.getType();
                    if (k.equals("formal_parameter") || k.equals("spread_parameter")) {
                        parameters++;
                    }
                }
            }
            return new MethodInfo(syntax.text(Syntax.field(m, "name")), begin, SourceText.countLoc(lines, begin, end),
                    complexity(m), parameters);
        }

        /**
         * Type names used in a declaration: types, annotations, and upper-case call/field scopes
         * ({@code Util.run()}). Names are recorded as written; {@link #resolve} decides later which ones are project
         * types (names of JDK or library types simply resolve to nothing). The upper-case test is the usual Java
         * naming convention: without a symbol solver, {@code Util.run()} (a static call on a type) and
         * {@code list.add()} (a call on a variable) look alike.
         */
        private Set<String> references(TSNode decl) {
            Set<String> refs = new HashSet<>();
            Syntax.walkWhile(decl, n -> {
                switch (n.getType()) {
                    case "type_identifier" -> refs.add(syntax.text(n));
                    case "scoped_type_identifier" -> {
                        // `Map.Entry` contributes "Map.Entry" and its scope "Map", but not "Entry" alone.
                        String text = stripWhitespace(syntax.text(n));
                        for (int dot = text.lastIndexOf('.'); dot >= 0; dot = text.lastIndexOf('.', dot - 1)) {
                            refs.add(text.substring(0, dot));
                        }
                        refs.add(text);
                        // Do not descend: the inner type_identifiers would add "Entry" on its own.
                        return false;
                    }
                    case "marker_annotation", "annotation" ->
                            refs.add(stripWhitespace(syntax.text(Syntax.field(n, "name"))));
                    case "method_invocation", "field_access" -> {
                        // `Outer.this` is a qualified this, not a static member access.
                        TSNode field = n.getType().equals("field_access") ? Syntax.field(n, "field") : null;
                        boolean qualifiedThis = field != null && field.getType().equals("this");
                        TSNode object = qualifiedThis ? null : Syntax.field(n, "object");
                        if (object != null && object.getType().equals("identifier")) {
                            String s = syntax.text(object);
                            if (!s.isEmpty() && Character.isUpperCase(s.codePointAt(0))) {
                                refs.add(s);
                            }
                        }
                    }
                    default -> {
                    }
                }
                return true;
            });
            return refs;
        }
    }

    private static int countOfKind(List<TSNode> nodes, String kind) {
        int n = 0;
        for (TSNode c : nodes) {
            if (c.getType().equals(kind)) {
                n++;
            }
        }
        return n;
    }

    /**
     * McCabe cyclomatic complexity: 1 + number of decision points.
     *
     * <p>Decision points are {@code if}, the four loops, {@code catch}, {@code ?:}, every non-default {@code case}
     * label ({@code default} has no expression; {@code case 1, 2 ->} is one label and counts once) and every
     * {@code &&} or {@code ||}. The walk covers the whole subtree, so lambdas and anonymous or local classes inside a
     * method add to that method's complexity. The TypeScript and Rust analysers count the equivalent constructs of
     * their languages.
     */
    static int complexity(TSNode node) {
        int[] cx = {1};
        Syntax.walk(node, n -> {
            String kind = n.getType();
            if (DECISIONS.contains(kind)) {
                cx[0]++;
            } else if (kind.equals("switch_label")) {
                if (n.getNamedChildCount() > 0) {
                    cx[0]++;
                }
            } else if (kind.equals("binary_expression")) {
                String op = Syntax.operator(n);
                if (op.equals("&&") || op.equals("||")) {
                    cx[0]++;
                }
            }
        });
        return cx[0];
    }

    /**
     * Removes all whitespace: tree-sitter keeps the source text, and {@code java.util . List} is a legal way to write
     * a name. Whitespace is Unicode's White_Space property, as in the Rust and D editions
     * ({@link Character#isWhitespace} differs from it in a few code points).
     */
    static String stripWhitespace(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().filter(c -> !isUnicodeWhiteSpace(c)).forEach(b::appendCodePoint);
        return b.toString();
    }

    private static boolean isUnicodeWhiteSpace(int c) {
        return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000;
    }

    /**
     * "core/api" for {@code core/api/src/main/java/...}; "" when sources sit directly under the root.
     *
     * <p>The module is everything before the first {@code src} segment, which matches the Maven and Gradle layouts
     * without reading any build file. A file outside any {@code src} directory belongs to the root module "".
     */
    static String moduleOf(Path relative) {
        List<String> parts = new ArrayList<>();
        for (Path p : relative) {
            String s = p.toString();
            if (s.equals("src")) {
                return String.join("/", parts);
            }
            parts.add(s);
        }
        return "";
    }

    /** In a test directory, or named like a JUnit ({@code *Test}, {@code *Tests}) or Failsafe ({@code *IT}) test. */
    static boolean isTest(Path relative) {
        String name = relative.getFileName().toString();
        return SourceText.inTestDirectory(relative)
                || name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
    }

    /**
     * Resolves raw type names against the project using Java's own lookup order (enclosing types, single-type imports,
     * same package, on-demand imports). No classpath is needed because only project-internal edges matter.
     *
     * @return fqn → resolved fqns, in reference order; names that match no project type are dropped, as are self
     *         references
     */
    static Map<String, Set<String>> resolveDependencies(List<TypeInfo> types, Map<String, TypeInfo> byFqn) {
        Map<String, Set<String>> deps = new HashMap<>();
        for (TypeInfo t : types) {
            Set<String> out = new LinkedHashSet<>();
            for (String ref : t.references()) {
                String target = resolve(ref, t, byFqn);
                if (target != null && !target.equals(t.fqn())) {
                    out.add(target);
                }
            }
            deps.put(t.fqn(), out);
        }
        return deps;
    }

    /**
     * Resolves one name written in {@code from}, or returns null if it names no project type.
     *
     * <p>Only the first segment of a dotted name is looked up; the rest is appended to each candidate. For
     * {@code Order.Line} written in {@code com.acme.shop.Cart.Item} the candidates are, in order:
     * <pre>
     *   com.acme.shop.Cart.Item.Order   member of the type itself
     *   com.acme.shop.Cart.Order        member of an enclosing type, innermost first
     *   a.b.Order                       for each single-type import "a.b.Order"
     *   com.acme.shop.Order             same package
     *   x.y.Order                       for each on-demand import "x.y.*"
     * </pre>
     * The first candidate that exists wins, with {@code .Line} appended if that exists, else the candidate itself
     * (so an unknown nested name still yields an edge to the outer type). Member types inherited from supertypes are
     * not considered. Only when no candidate exists is the name taken as already fully qualified
     * ({@code com.acme.Order}). Types in the default package are found only from the default package itself (as its
     * "same package" candidate): as in Java, they are invisible from named packages.
     */
    private static String resolve(String ref, TypeInfo from, Map<String, TypeInfo> byFqn) {
        int dot = ref.indexOf('.');
        String first = dot < 0 ? ref : ref.substring(0, dot);
        String rest = dot < 0 ? "" : ref.substring(dot);

        List<String> candidates = new ArrayList<>();
        String prefix = from.packageName().isEmpty() ? "" : from.packageName() + ".";
        String enclosing = from.name();
        while (true) {
            candidates.add(prefix + enclosing + "." + first);
            int i = enclosing.lastIndexOf('.');
            if (i < 0) {
                break;
            }
            enclosing = enclosing.substring(0, i);
        }
        for (String imp : from.explicitImports()) {
            if (imp.endsWith("." + first)) {
                candidates.add(imp);
            }
        }
        candidates.add(prefix + first);
        for (String w : from.wildcardImports()) {
            candidates.add(w + "." + first);
        }
        for (String c : candidates) {
            if (!rest.isEmpty() && byFqn.containsKey(c + rest)) {
                return c + rest;
            }
            if (byFqn.containsKey(c)) {
                return c;
            }
        }
        // Already fully qualified, which a default-package type never is from outside that package.
        TypeInfo target = byFqn.get(ref);
        return target != null && !target.packageName().isEmpty() ? ref : null;
    }
}
