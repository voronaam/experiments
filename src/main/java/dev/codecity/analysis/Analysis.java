package dev.codecity.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of analysing a source tree: the hand-over point between the analysis stages (walk → parse → link → git
 * history) and the {@link dev.codecity.CityModel city model}, which lays the types out and serialises them.
 *
 * <p>{@link SourceAnalyzer#analyze} fills everything except {@code history}, which it leaves empty;
 * {@link dev.codecity.CityModel#build} then creates a copy with the result of {@link GitHistory#load}.
 *
 * <p>Invariants: every key and every target in {@code dependencies} is the fqn of an entry in {@code types} (edges to
 * filtered-out tests and to external code have already been dropped), and no type depends on itself.
 *
 * @param root         the analysed directory (a real path, as resolved by the command line)
 * @param types        all types, sorted by fully qualified name
 * @param dependencies fqn → fqns of other project types it references
 * @param history      per-file git history (empty when not a git repository), keyed by absolute, normalised path
 * @param files        number of source files found, whatever their outcome
 * @param failedFiles  files that could not be read, or that parsed with syntax errors (and so may be incomplete)
 */
public record Analysis(
        Path root,
        List<TypeInfo> types,
        Map<String, Set<String>> dependencies,
        Map<Path, FileHistory> history,
        int files,
        int failedFiles) {
}
