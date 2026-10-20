package dev.codecity.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.codecity.analysis.JavaParse.NodeKind;
import dev.codecity.analysis.JavaParse.Tree;

/**
 * Java support: extracts the classic CodeCity metrics per type. The source is parsed by the in-house parser in
 * {@link JavaParse} (no third-party parser).
 *
 * <p>Every class, interface, enum, record and annotation type is a building, nested and member types included (each
 * gets its own building, e.g. {@code Order.Line}); local and anonymous classes are not. Packages are the districts,
 * and the path up to the first {@code src} directory is the build module.
 *
 * <p>Parsing is purely syntactic: no classpath and no symbol solver. Parsing records, per type, the <em>raw</em> names
 * it mentions ({@code List}, {@code Map.Entry}, {@code Util} from {@code Util.run()}) and the file's imports;
 * {@link #link} then resolves those names against the set of project types, following Java's scoping rules closely
 * enough for project-internal edges. Java needs no extra linker context: everything is stored on {@link TypeInfo}
 * (references and imports), so {@link ParsedFile#context()} is null.
 *
 * <p>The Rust and D editions mirror this analyser (and {@link JavaParse}) node kind for node kind, so all three
 * produce the same city.
 */
public final class JavaAnalyzer implements LanguageSupport {

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
     * A reference is tried against the file's on-demand imports ({@code import a.b.*;}) and against its single-type
     * imports of the same simple name. Beyond this many of either kind, later ones are ignored, so a crafted file with
     * very many cannot make the analysis quadratic; real files have a handful of on-demand imports and one single-type
     * import per name. Imports longer than {@link SourceText#MAX_FQN_BYTES} are ignored too: they cannot name a
     * building. The Rust and D editions use the same limits.
     */
    static final int MAX_IMPORT_CANDIDATES = 64;

    /**
     * A qualified name ({@code a.b.C}) contributes its prefixes ({@code a}, {@code a.b}) as references too, but at
     * most this many of them, the shortest; a crafted name of very many segments would otherwise yield an amount of
     * text quadratic in its length. The Rust and D editions use the same limit.
     */
    static final int MAX_QUALIFIED_PREFIXES = 16;

    /**
     * Member types nested deeper than this (a top-level type is at depth 1) are not buildings; their code still counts
     * towards the enclosing types. Every type collects and resolves the references of its member types as well, so
     * with deep nesting the work grew with the square of the depth; real code nests a few levels. The Rust and D
     * editions use the same limit.
     */
    static final int MAX_TYPE_NESTING = 16;

    /**
     * Extracts every type of one file: top-level and member types, depth-first in source order. The parser always
     * yields a tree; a file with syntax errors is analysed as far as it goes and only flagged as failed.
     */
    @Override
    public ParsedFile parse(Path root, Path file, String code) {
        Tree tree = JavaParse.parse(code);
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
        List<Integer> top = tree.children(0);
        for (int n : top) {
            switch (tree.kind(n)) {
                case PACKAGE -> {
                    // Package annotations (allowed in package-info.java) are separate nodes; the NAME child is the name.
                    int name = nameChild(tree, n);
                    if (name != JavaParse.NONE) {
                        pkg = stripWhitespace(tree.text(name));
                    }
                }
                case IMPORT -> {
                    int nameNode = nameChild(tree, n);
                    if (nameNode == JavaParse.NONE) {
                        continue;
                    }
                    boolean isStatic = (tree.flags(n) & JavaParse.IMPORT_STATIC) != 0;
                    boolean asterisk = (tree.flags(n) & JavaParse.IMPORT_ASTERISK) != 0;
                    // Names may be written with whitespace or line breaks around the dots; normalise to `a.b.C`.
                    String name = stripWhitespace(tree.text(nameNode));
                    if (SourceText.utf8Length(name) > SourceText.MAX_FQN_BYTES) {
                        continue;
                    }
                    if (isStatic && !asterisk) {
                        int dot = name.lastIndexOf('.');
                        explicitImports.add(dot >= 0 ? name.substring(0, dot) : name);
                    } else if (asterisk) {
                        if (isStatic) {
                            explicitImports.add(name);
                        } else if (wildcardImports.size() < MAX_IMPORT_CANDIDATES) {
                            wildcardImports.add(name);
                        }
                    } else {
                        explicitImports.add(name);
                    }
                }
                default -> {
                }
            }
        }

        FileState st = new FileState(tree, SourceText.lines(code), file, pkg, moduleOf(relative),
                isTest(relative), List.copyOf(explicitImports), List.copyOf(wildcardImports));
        for (int n : top) {
            if (tree.kind(n) == NodeKind.TYPE_DECL) {
                st.collectType(n, "", 1);
            }
        }
        return new ParsedFile(file, st.types, null, tree.error() || st.skipped);
    }

    /** The {@link NodeKind#NAME} child of a package or import node, or {@link JavaParse#NONE}. */
    private static int nameChild(Tree tree, int n) {
        for (int c = n + 1; c < tree.end(n); c = tree.end(c)) {
            if (tree.kind(c) == NodeKind.NAME) {
                return c;
            }
        }
        return JavaParse.NONE;
    }

    /** Per-file data shared by every type declared in the file, and the types found so far (in pre-order). */
    private static final class FileState {
        final Tree tree;
        final SourceText.Lines lines;
        final Path file;
        final String pkg;
        final String module;
        final boolean test;
        final List<String> explicitImports;
        final List<String> wildcardImports;
        final List<TypeInfo> types = new ArrayList<>();
        /** Length of {@link #pkg} in UTF-8 bytes. */
        final int pkgBytes;
        /** Set when a type was skipped for its nesting or the length of its fqn (see {@link #collectType}). */
        boolean skipped;

        FileState(Tree tree, SourceText.Lines lines, Path file, String pkg, String module, boolean test,
                  List<String> explicitImports, List<String> wildcardImports) {
            this.tree = tree;
            this.lines = lines;
            this.file = file;
            this.pkg = pkg;
            this.module = module;
            this.test = test;
            this.explicitImports = explicitImports;
            this.wildcardImports = wildcardImports;
            this.pkgBytes = SourceText.utf8Length(pkg);
        }

        /**
         * Adds a type and, recursively, the member types declared in its body (local classes are not types).
         *
         * <p>{@code outer} is the enclosing type's name relative to the package ("" at the top level), so a member
         * type is named {@code Outer.Inner}. Metrics:
         * <ul>
         *   <li>NOA (attributes): one per declared variable in fields and interface constants ({@code int a, b;} is
         *       two), one per enum constant and one per record component (a varargs component {@code int... xs} is
         *       not counted);</li>
         *   <li>methods: methods and constructors (initialiser blocks and compact record constructors are not
         *       counted);</li>
         *   <li>LOC: the whole declaration, including member types, so an outer type's LOC contains its inner
         *       types';</li>
         *   <li>references: collected from the whole declaration, member types included.</li>
         * </ul>
         * The type is added before its member types, so {@link #types} is in pre-order. A type nested deeper than
         * {@link #MAX_TYPE_NESTING} or whose fqn would exceed {@link SourceText#MAX_FQN_BYTES} is skipped with its
         * member types (whose fqns are longer still), and the file counts as failed; the length is checked before the
         * fqn is built.
         */
        void collectType(int decl, String outer, int depth) {
            String simple = tree.tokenText(tree.name(decl));
            int fqnBytes = pkgBytes + (pkg.isEmpty() ? 0 : 1) + SourceText.utf8Length(outer) + (outer.isEmpty() ? 0 : 1)
                    + SourceText.utf8Length(simple);
            if (depth > MAX_TYPE_NESTING || fqnBytes > SourceText.MAX_FQN_BYTES) {
                skipped = true;
                return;
            }
            String name = outer.isEmpty() ? simple : outer + "." + simple;
            String fqn = pkg.isEmpty() ? name : pkg + "." + name;

            List<MethodInfo> methods = new ArrayList<>();
            List<Integer> nested = new ArrayList<>();
            // Record components (`record P(int x, int y)`) are the record's fields; `count` is 0 for other kinds.
            int noa = tree.count(decl);
            for (int m = decl + 1; m < tree.end(decl); m = tree.end(m)) {
                switch (tree.kind(m)) {
                    case FIELD -> noa += tree.count(m);
                    case ENUM_CONSTANT -> noa++;
                    case METHOD -> methods.add(method(m));
                    case TYPE_DECL -> nested.add(m);
                    default -> {
                    }
                }
            }
            int begin = tree.firstLine(decl);
            int end = tree.lastLine(decl);
            types.add(new TypeInfo(
                    Language.JAVA, fqn, name, pkg, module, kindOf(tree.sub(decl), tree.flags(decl)), file, begin, end,
                    SourceText.countLoc(lines, begin, end), noa, test, methods, references(decl),
                    explicitImports, wildcardImports));
            for (int n : nested) {
                collectType(n, name, depth + 1);
            }
        }

        /**
         * Metrics of a method or constructor. Parameters include a trailing varargs parameter; the receiver parameter
         * ({@code Foo this}) is not counted (the parser does both).
         */
        private MethodInfo method(int m) {
            int begin = tree.firstLine(m);
            int end = tree.lastLine(m);
            return new MethodInfo(tree.tokenText(tree.name(m)), begin, SourceText.countLoc(lines, begin, end),
                    complexity(tree, m), tree.count(m));
        }

        /**
         * Type names used in a declaration: types, annotations, and upper-case call/field scopes
         * ({@code Util.run()}). Names are recorded as written; {@link #resolve} decides later which ones are project
         * types (names of JDK or library types simply resolve to nothing). The upper-case test is the usual Java
         * naming convention: without a symbol solver, {@code Util.run()} (a static call on a type) and
         * {@code list.add()} (a call on a variable) look alike.
         */
        private Set<String> references(int decl) {
            Set<String> refs = new HashSet<>();
            for (int n = decl + 1; n < tree.end(decl); n++) {
                switch (tree.kind(n)) {
                    case TYPE_NAME -> addReference(refs, tree.text(n));
                    case SCOPED_TYPE_NAME -> {
                        // `Map.Entry` contributes "Map.Entry" and its scope "Map", but not "Entry" alone (the parser
                        // does not report the parts of a qualified name separately).
                        // At most MAX_QUALIFIED_PREFIXES of them, the shortest, so a crafted name of many segments
                        // does not yield a quadratic amount of text.
                        String text = stripWhitespace(tree.text(n));
                        int count = 0;
                        // A prefix longer than MAX_FQN_BYTES would be dropped by addReference anyway.
                        for (int dot = text.indexOf('.'); dot >= 0 && count < MAX_QUALIFIED_PREFIXES
                                && dot <= SourceText.MAX_FQN_BYTES; dot = text.indexOf('.', dot + 1), count++) {
                            addReference(refs, text.substring(0, dot));
                        }
                        addReference(refs, text);
                    }
                    case ANNOTATION_NAME -> addReference(refs, stripWhitespace(tree.text(n)));
                    case MEMBER_OBJECT -> {
                        String s = tree.text(n);
                        if (!s.isEmpty() && Character.isUpperCase(s.codePointAt(0))) {
                            addReference(refs, s);
                        }
                    }
                    default -> {
                    }
                }
            }
            return refs;
        }
    }

    /**
     * Adds a reference unless it is longer than {@link SourceText#MAX_FQN_BYTES}: it could not name a building, only
     * resolve through its first segment, which a qualified name contributes on its own (see {@link FileState#references}),
     * and resolving it would copy it into every candidate tried.
     */
    private static void addReference(Set<String> refs, String name) {
        if (SourceText.utf8Length(name) <= SourceText.MAX_FQN_BYTES) {
            refs.add(name);
        }
    }

    /**
     * The building kind shown in the viewers. Classes are split into "abstract" and "class" by their {@code abstract}
     * modifier.
     */
    private static String kindOf(int sub, int flags) {
        return switch (sub) {
            case JavaParse.TYPE_INTERFACE -> "interface";
            case JavaParse.TYPE_ENUM -> "enum";
            case JavaParse.TYPE_RECORD -> "record";
            case JavaParse.TYPE_ANNOTATION -> "annotation";
            default -> (flags & JavaParse.TYPE_ABSTRACT) != 0 ? "abstract" : "class";
        };
    }

    /**
     * McCabe cyclomatic complexity: 1 + number of decision points.
     *
     * <p>Decision points are {@code if}, the four loops, {@code catch}, {@code ?:}, every non-default {@code case}
     * label ({@code default} has no expression; {@code case 1, 2 ->} is one label and counts once) and every
     * {@code &&} or {@code ||}; the parser marks them as {@link NodeKind#DECISION} nodes. Everything inside the node
     * counts, so lambdas and anonymous or local classes inside a method add to that method's complexity. The
     * TypeScript and Rust analysers count the equivalent constructs of their languages.
     */
    static int complexity(Tree tree, int node) {
        int cx = 1;
        for (int n = node + 1; n < tree.end(node); n++) {
            if (tree.kind(n) == NodeKind.DECISION) {
                cx++;
            }
        }
        return cx;
    }

    /**
     * Removes all whitespace: names are taken from the source text, and {@code java.util . List} is a legal way to
     * write one. Whitespace is Unicode's White_Space property, as in the Rust and D editions
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
        // The single-type imports of each import list by simple name; the types of a file share one list, so each
        // file's imports are indexed once.
        Map<List<String>, Map<String, List<String>>> importIndex = new IdentityHashMap<>();
        for (TypeInfo t : types) {
            Map<String, List<String>> imports = importIndex.computeIfAbsent(t.explicitImports(),
                    JavaAnalyzer::bySimpleName);
            Set<String> out = new LinkedHashSet<>();
            for (String ref : t.references()) {
                String target = resolve(ref, t, imports, byFqn);
                if (target != null && !target.equals(t.fqn())) {
                    out.add(target);
                }
            }
            deps.put(t.fqn(), out);
        }
        return deps;
    }

    /**
     * Single-type imports by simple name (the last segment: {@code a.b.C} under "C"), each list in import order and
     * at most {@link #MAX_IMPORT_CANDIDATES} long. An import without a dot names no package member and is left out. Lets {@link #resolve} find the imports that match
     * a name without scanning them all for every reference.
     */
    private static Map<String, List<String>> bySimpleName(List<String> imports) {
        Map<String, List<String>> index = new HashMap<>();
        for (String imp : imports) {
            int dot = imp.lastIndexOf('.');
            if (dot >= 0) {
                List<String> same = index.computeIfAbsent(imp.substring(dot + 1), k -> new ArrayList<>());
                if (same.size() < MAX_IMPORT_CANDIDATES) {
                    same.add(imp);
                }
            }
        }
        return index;
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
    private static String resolve(String ref, TypeInfo from, Map<String, List<String>> imports,
                                  Map<String, TypeInfo> byFqn) {
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
        candidates.addAll(imports.getOrDefault(first, List.of()));
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
