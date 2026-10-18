package dev.codecity.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One type found in the code base — a building in the city. Depending on the language this is a class, interface,
 * enum, record, struct, trait, type alias, or a "module": the free functions of one file.
 *
 * @param language    source language
 * @param packageName the district path in the language's own notation ({@code com.acme}, {@code src/ui},
 *                    {@code net::http}); split with {@link Language#separator}
 * @param fqn        unique id, e.g. {@code com.acme.Order.Line}, {@code src/ui/App.tsx:App}, {@code shop::cart::Cart}
 * @param name       name relative to its package, e.g. {@code Order.Line}
 * @param module     build module / package.json project / crate the file belongs to ("" when there is only one)
 * @param kind       e.g. {@code class}, {@code abstract}, {@code interface}, {@code enum}, {@code record},
 *                   {@code annotation}, {@code type}, {@code struct}, {@code union}, {@code trait} or {@code module};
 *                   the viewers can colour buildings by it
 * @param file       absolute path of the declaring file
 * @param line       first line of the declaration (1-based)
 * @param endLine    last line of the declaration (inclusive)
 * @param loc        non-blank, non-comment lines inside the declaration (for Rust, plus those of its impl blocks)
 * @param noa        number of attributes (fields, record components, enum constants)
 * @param test       declared in test code; such types are dropped when tests are excluded
 * @param methods    methods, constructors or functions in declaration order (for Rust, those of the impl blocks
 *                   follow the type's own)
 * @param references raw type names referenced in the body, resolved later against the project
 * @param explicitImports Java only: single-type imports, plus the types named by static imports
 * @param wildcardImports Java only: packages imported on demand ({@code import a.b.*})
 */
public record TypeInfo(
        Language language,
        String fqn,
        String name,
        String packageName,
        String module,
        String kind,
        Path file,
        int line,
        int endLine,
        int loc,
        int noa,
        boolean test,
        List<MethodInfo> methods,
        Set<String> references,
        List<String> explicitImports,
        List<String> wildcardImports) {

    /** The package split into district segments; empty for the root (default) package. */
    public java.util.List<String> packageSegments() {
        if (packageName.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.of(packageName.split(java.util.regex.Pattern.quote(language.separator)));
    }

    /**
     * Copy with methods, references and lines from elsewhere added — used to merge Rust {@code impl} blocks, which may
     * live in other files, into the type they implement. The declaration's own line range is unchanged.
     */
    public TypeInfo withMembers(List<MethodInfo> moreMethods, Set<String> moreReferences, int moreLoc) {
        List<MethodInfo> m = new java.util.ArrayList<>(methods);
        m.addAll(moreMethods);
        Set<String> r = new java.util.HashSet<>(references);
        r.addAll(moreReferences);
        return new TypeInfo(language, fqn, name, packageName, module, kind, file, line, endLine,
                loc + moreLoc, noa, test, List.copyOf(m), Set.copyOf(r), explicitImports, wildcardImports);
    }

    public int nom() {
        return methods.size();
    }

    /** Weighted method count: the sum of the cyclomatic complexity of all methods. */
    public int complexity() {
        return methods.stream().mapToInt(MethodInfo::complexity).sum();
    }
}
