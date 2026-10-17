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

/** Java support: parses sources with JavaParser and extracts the classic CodeCity metrics per type. */
public final class JavaAnalyzer implements LanguageSupport {

    private final ThreadLocal<JavaParser> parsers = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                    .setAttributeComments(false)));

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

    @Override
    public ParsedFile parse(Path root, Path file, String code) {
        ParseResult<CompilationUnit> parsed = parsers.get().parse(code);
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
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            Optional<String> fqn = td.getFullyQualifiedName();
            if (fqn.isEmpty()) {
                continue; // local class
            }
            String name = pkg.isEmpty() ? fqn.get() : fqn.get().substring(pkg.length() + 1);
            int begin = td.getBegin().map(p -> p.line).orElse(1);
            int end = td.getEnd().map(p -> p.line).orElse(begin);
            types.add(new TypeInfo(
                    Language.JAVA, fqn.get(), name, pkg, module, kindOf(td), file, begin, end,
                    SourceText.countLoc(lines, begin, end), countAttributes(td), test,
                    methodsOf(td, lines), referencesOf(td),
                    List.copyOf(explicitImports), List.copyOf(wildcardImports)));
        }
        return new ParsedFile(file, types, null, !parsed.isSuccessful());
    }


    /** "core/api" for {@code core/api/src/main/java/...}; "" when sources sit directly under the root. */
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

    static boolean isTest(Path relative) {
        String name = relative.getFileName().toString();
        return SourceText.inTestDirectory(relative)
                || name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
    }


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

    /** McCabe cyclomatic complexity: 1 + number of decision points. */
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

    /** {@code Foo.bar()} / {@code Foo.BAR}: an upper-case scope is very likely a type name. */
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

    private static String resolve(String ref, TypeInfo from, Map<String, TypeInfo> byFqn) {
        if (byFqn.containsKey(ref)) {
            return ref;
        }
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
            if (imp.equals(first) || imp.endsWith("." + first)) {
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
        return null;
    }
}
