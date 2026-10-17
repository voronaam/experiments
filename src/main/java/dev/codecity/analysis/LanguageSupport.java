package dev.codecity.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds one language to the analyser: turns single files into types, then links all of a language's files together
 * (merging split declarations and resolving references between project types).
 */
public interface LanguageSupport {

    Language language();

    boolean accepts(Path file);

    /** Parses one file. Implementations must be thread-safe: files are parsed in parallel. */
    ParsedFile parse(Path root, Path file, String code) throws IOException;

    /** Combines every parsed file of this language into the final types and their project-internal dependencies. */
    Linked link(Path root, List<ParsedFile> files);

    /**
     * @param context language-specific data the linker needs (imports, impl blocks, …); may be null
     * @param failed  true when the file had syntax errors and was only partially analysed
     */
    record ParsedFile(Path file, List<TypeInfo> types, Object context, boolean failed) {
    }

    /** @param dependencies fqn → fqns of other project types it uses */
    record Linked(List<TypeInfo> types, Map<String, Set<String>> dependencies) {
    }
}
