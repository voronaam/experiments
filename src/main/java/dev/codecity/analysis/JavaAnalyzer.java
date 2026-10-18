package dev.codecity.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Java support: parses sources with JavaParser and extracts the classic CodeCity metrics per type.
 *
 * <p>Every class, interface, enum, record and annotation type is a building, nested and member types included (each
 * gets its own building, e.g. {@code Order.Line}); local and anonymous classes are not. Packages are the districts,
 * and the path up to the first {@code src} directory is the build module.
 *
 * <p>Parsing is purely syntactic: no classpath and no symbol solver. References are collected as the names written in
 * the source and resolved in {@link #link} against the set of project types, following Java's scoping rules closely
 * enough for project-internal edges. Java needs no extra linker context: everything is stored on {@link TypeInfo}
 * (references and imports), so {@link ParsedFile#context()} is null.
 */
public final class JavaAnalyzer implements LanguageSupport {

    /**
     * One parser per thread, because a {@link JavaParser} instance is not thread-safe and files are parsed in parallel.
     * Comments are not attached to AST nodes, as nothing here reads them.
     */
    private final ThreadLocal<JavaParser> parsers = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                    .setAttributeComments(false)));

    /** A {@code \r} that is not part of a {@code \r\n}. */
    private static final Pattern LONE_CR = Pattern.compile("\r(?!\n)");

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
     * Extracts every type of one file. JavaParser recovers from many syntax errors and still returns a partial tree;
     * such files are analysed as far as possible and only flagged as failed. Without any tree the file contributes
     * nothing.
     */
    @Override
    public ParsedFile parse(Path root, Path file, String code) {
        // JavaParser counts a lone '\r' as a line break, tree-sitter (used by the other editions) does not. Parsing
        // it as a space keeps every line number, and what the rest of the line means, as tree-sitter sees it.
        ParseResult<CompilationUnit> parsed = parsers.get().parse(LONE_CR.matcher(code).replaceAll(" "));
        Optional<CompilationUnit> unit = parsed.getResult();
        if (unit.isEmpty()) {
            return new ParsedFile(file, List.of(), null, true);
        }
        CompilationUnit cu = unit.get();
        String[] lines = SourceText.lines(code);
        Path relative = root.relativize(file);
        String module = moduleOf(relative);
        boolean test = isTest(relative);
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

        // Imports are recorded by what they make visible under a simple name:
        //   import a.b.C;          → explicit "a.b.C"
        //   import static a.b.C.m; → explicit "a.b.C" (the member's declaring type is what this file depends on)
        //   import static a.b.C.*; → explicit "a.b.C" (getNameAsString() omits the ".*")
        //   import a.b.*;          → wildcard "a.b"
        // A static import thus makes C a resolution candidate for a reference to "C". Calls to a statically imported
        // member name no type, so they do not by themselves create a dependency on C.
        List<String> explicitImports = new ArrayList<>();
        List<String> wildcardImports = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isStatic() && !imp.isAsterisk()) {
                int dot = name.lastIndexOf('.');
                explicitImports.add(dot > 0 ? name.substring(0, dot) : name);
            } else if (imp.isAsterisk()) {
                (imp.isStatic() ? explicitImports : wildcardImports).add(name);
            } else {
                explicitImports.add(name);
            }
        }

        List<TypeInfo> types = new ArrayList<>();
        // findAll walks the whole tree, so member types are found as well as top-level ones.
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            Optional<String> fqn = td.getFullyQualifiedName();
            if (fqn.isEmpty()) {
                continue; // local class
            }
            // Strip the package: "com.acme.Order.Line" → "Order.Line".
            String name = pkg.isEmpty() ? fqn.get() : fqn.get().substring(pkg.length() + 1);
            int begin = td.getBegin().map(p -> p.line).orElse(1);
            int end = td.getEnd().map(p -> p.line).orElse(begin);
            // LOC and references of an outer type include those of its member types, because both are measured over
            // the outer type's whole extent.
            types.add(new TypeInfo(
                    Language.JAVA, fqn.get(), name, pkg, module, kindOf(td), file, begin, end,
                    SourceText.countLoc(lines, begin, end), countAttributes(td), test,
                    methodsOf(td, lines), referencesOf(td),
                    List.copyOf(explicitImports), List.copyOf(wildcardImports)));
        }
        return new ParsedFile(file, types, null, !parsed.isSuccessful());
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


    /** Abstract classes get their own kind so the viewers can tell them apart from concrete ones. */
    private static String kindOf(TypeDeclaration<?> td) {
        if (td instanceof ClassOrInterfaceDeclaration c) {
            if (c.isInterface()) {
                return "interface";
            }
            return c.isAbstract() ? "abstract" : "class";
        }
        if (td instanceof EnumDeclaration) {
            return "enum";
        }
        if (td instanceof RecordDeclaration) {
            return "record";
        }
        if (td instanceof AnnotationDeclaration) {
            return "annotation";
        }
        return "class";
    }

    /**
     * Number of attributes (NOA): every declared variable counts, so {@code int x, y;} is two. Static fields count too.
     * Record components and enum constants are attributes as well, although they are not field declarations.
     */
    private static int countAttributes(TypeDeclaration<?> td) {
        int n = td.getFields().stream().mapToInt(f -> f.getVariables().size()).sum();
        if (td instanceof RecordDeclaration r) {
            n += r.getParameters().size();
        }
        if (td instanceof EnumDeclaration e) {
            n += e.getEntries().size();
        }
        return n;
    }

    /**
     * Methods and constructors declared directly in the type (not in member types, which are buildings of their own).
     * Initialiser blocks and compact record constructors are not {@link CallableDeclaration}s and are not counted.
     */
    private static List<MethodInfo> methodsOf(TypeDeclaration<?> td, String[] lines) {
        List<MethodInfo> methods = new ArrayList<>();
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof CallableDeclaration<?> c) {
                int begin = c.getBegin().map(p -> p.line).orElse(0);
                int end = c.getEnd().map(p -> p.line).orElse(begin);
                methods.add(new MethodInfo(c.getNameAsString(), begin, SourceText.countLoc(lines, begin, end),
                        complexity(c), c.getParameters().size()));
            }
        }
        return methods;
    }

    /**
     * McCabe cyclomatic complexity: 1 + number of decision points.
     *
     * <p>Decision points are {@code if}, the four loops, {@code catch}, {@code ?:}, every {@code case} entry (a
     * {@code default} has no labels and is not counted; {@code case 1, 2 ->} counts once) and every {@code &&} or
     * {@code ||}. The walk covers the whole subtree, so lambdas and anonymous classes inside a method add to that
     * method's complexity. The TypeScript and Rust analysers count the equivalent constructs of their languages.
     */
    static int complexity(Node node) {
        int[] cx = {1};
        node.walk(n -> {
            if (n instanceof IfStmt || n instanceof ForStmt || n instanceof ForEachStmt || n instanceof WhileStmt
                    || n instanceof DoStmt || n instanceof CatchClause || n instanceof ConditionalExpr) {
                cx[0]++;
            } else if (n instanceof SwitchEntry e && !e.getLabels().isEmpty()) {
                cx[0]++;
            } else if (n instanceof BinaryExpr b
                    && (b.getOperator() == BinaryExpr.Operator.AND || b.getOperator() == BinaryExpr.Operator.OR)) {
                cx[0]++;
            }
        });
        return cx[0];
    }

    /**
     * Raw names this type uses, as written: type usages ({@code a.b.C}, {@code Map.Entry}, without type arguments),
     * annotations, and type-like scopes of method calls and field accesses ({@code Foo.bar()}). Names of JDK or
     * library types are collected too; {@link #resolve} simply finds no project type for them.
     */
    private static Set<String> referencesOf(TypeDeclaration<?> td) {
        Set<String> refs = new HashSet<>();
        td.walk(n -> {
            if (n instanceof ClassOrInterfaceType t) {
                refs.add(t.getNameWithScope());
            } else if (n instanceof AnnotationExpr a) {
                refs.add(a.getNameAsString());
            } else if (n instanceof MethodCallExpr m) {
                m.getScope().ifPresent(s -> addTypeLikeScope(s, refs));
            } else if (n instanceof FieldAccessExpr f) {
                addTypeLikeScope(f.getScope(), refs);
            }
        });
        return refs;
    }

    /**
     * {@code Foo.bar()} / {@code Foo.BAR}: an upper-case scope is very likely a type name. Without a symbol solver a
     * static call parses like a call on a variable named {@code Foo}, so Java's naming convention decides.
     */
    private static void addTypeLikeScope(Expression scope, Set<String> refs) {
        if (scope instanceof NameExpr name) {
            String s = name.getNameAsString();
            if (!s.isEmpty() && Character.isUpperCase(s.charAt(0))) {
                refs.add(s);
            }
        }
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
