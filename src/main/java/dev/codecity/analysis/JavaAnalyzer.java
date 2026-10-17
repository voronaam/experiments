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

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses Java sources with JavaParser and extracts the classic CodeCity metrics per type.
 * Parsed files are cached by modification time so watch-mode re-analysis only re-parses what changed.
 */
public final class JavaAnalyzer {

    public static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", "target", "build", "out", "node_modules");

    private static final Set<String> TEST_DIRS = Set.of("test", "tests", "it", "androidTest", "testFixtures");

    private record FileResult(List<TypeInfo> types, boolean failed) {
    }

    private record CacheEntry(long modified, long size, FileResult result) {
    }

    private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();

    private final ThreadLocal<JavaParser> parsers = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                    .setAttributeComments(false)));

    public Analysis analyze(Path root, boolean includeTests) throws IOException {
        List<Path> files = findSources(root);
        cache.keySet().retainAll(new HashSet<>(files));

        List<FileResult> results = files.parallelStream().map(f -> parseCached(root, f)).toList();

        List<TypeInfo> types = new ArrayList<>();
        int failed = 0;
        for (FileResult r : results) {
            if (r.failed()) {
                failed++;
            }
            for (TypeInfo t : r.types()) {
                if (includeTests || !t.test()) {
                    types.add(t);
                }
            }
        }
        // Same FQN can occur in several modules (or as a test fixture); keep the first so the index stays unique.
        Map<String, TypeInfo> byFqn = new TreeMap<>();
        for (TypeInfo t : types) {
            byFqn.putIfAbsent(t.fqn(), t);
        }
        List<TypeInfo> unique = new ArrayList<>(byFqn.values());
        return new Analysis(root, unique, resolveDependencies(unique, byFqn), Map.of(), files.size(), failed);
    }

    private static List<Path> findSources(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && isIgnoredDir(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }

    public static boolean isIgnoredDir(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return IGNORED_DIRS.contains(name) || (name.startsWith(".") && name.length() > 1);
    }

    private FileResult parseCached(Path root, Path file) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            CacheEntry cached = cache.get(file);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached.result();
            }
            FileResult result = parse(root, file);
            cache.put(file, new CacheEntry(modified, size, result));
            return result;
        } catch (IOException | RuntimeException e) {
            return new FileResult(List.of(), true);
        }
    }

    private FileResult parse(Path root, Path file) throws IOException {
        String code = read(file);
        ParseResult<CompilationUnit> parsed = parsers.get().parse(code);
        Optional<CompilationUnit> unit = parsed.getResult();
        if (unit.isEmpty()) {
            return new FileResult(List.of(), true);
        }
        CompilationUnit cu = unit.get();
        String[] lines = code.split("\\R", -1);
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
                    fqn.get(), name, pkg, module, kindOf(td), file, begin, end,
                    countLoc(lines, begin, end), countAttributes(td), test,
                    methodsOf(td, lines), referencesOf(td),
                    List.copyOf(explicitImports), List.copyOf(wildcardImports)));
        }
        return new FileResult(types, !parsed.isSuccessful());
    }

    private static String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        }
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
        for (Path p : relative.getParent() == null ? List.<Path>of() : relative.getParent()) {
            if (TEST_DIRS.contains(p.toString())) {
                return true;
            }
        }
        String name = relative.getFileName().toString();
        return name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
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
                methods.add(new MethodInfo(c.getNameAsString(), begin, countLoc(lines, begin, end),
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

    /** Non-blank lines in [begin, end] that are not purely comments. */
    static int countLoc(String[] lines, int begin, int end) {
        int loc = 0;
        boolean inBlock = false;
        for (int i = Math.max(begin, 1) - 1; i < Math.min(end, lines.length); i++) {
            String s = lines[i].strip();
            if (inBlock) {
                int close = s.indexOf("*/");
                if (close < 0) {
                    continue;
                }
                inBlock = false;
                s = s.substring(close + 2).strip();
            }
            if (s.startsWith("/*")) {
                int close = s.indexOf("*/", 2);
                if (close < 0) {
                    inBlock = true;
                    continue;
                }
                s = s.substring(close + 2).strip();
            }
            if (!s.isEmpty() && !s.startsWith("//")) {
                loc++;
            }
        }
        return loc;
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
