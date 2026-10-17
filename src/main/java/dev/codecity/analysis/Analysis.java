package dev.codecity.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of analysing a source tree.
 *
 * @param types        all types, sorted by fully qualified name
 * @param dependencies fqn → fqns of other project types it references
 * @param history      per-file git history (empty when not a git repository)
 */
public record Analysis(
        Path root,
        List<TypeInfo> types,
        Map<String, Set<String>> dependencies,
        Map<Path, FileHistory> history,
        int files,
        int failedFiles) {
}
