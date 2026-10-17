package dev.codecity.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One class, interface, enum, record or annotation found in the code base — a building in the city.
 *
 * @param fqn        fully qualified name, e.g. {@code com.acme.Order.Line}
 * @param name       name relative to its package, e.g. {@code Order.Line}
 * @param module     build module the file lives in ("" for single-module projects)
 * @param loc        non-blank, non-comment lines inside the declaration
 * @param noa        number of attributes (fields, record components, enum constants)
 * @param references raw type names referenced in the body, resolved later against the project
 */
public record TypeInfo(
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

    public int nom() {
        return methods.size();
    }

    /** Weighted method count: the sum of the cyclomatic complexity of all methods. */
    public int complexity() {
        return methods.stream().mapToInt(MethodInfo::complexity).sum();
    }
}
