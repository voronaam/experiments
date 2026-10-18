package dev.codecity.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds one language to the analyser: turns single files into types, then links all of a language's files together
 * (merging split declarations and resolving references between project types).
 *
 * <p>The split into two phases is what makes watch mode cheap. {@link #parse} only looks at one file, so
 * {@link SourceAnalyzer} can cache its result per file and re-parse just the files that changed. {@link #link} needs
 * the whole project (a reference can only be resolved once every type is known), so it always runs again, but it works
 * on the small {@link ParsedFile} summaries rather than on syntax trees.
 *
 * <p>Implementations: {@link JavaAnalyzer} (JavaParser), {@link TypeScriptAnalyzer} and {@link RustAnalyzer}
 * (tree-sitter, via {@link Syntax}).
 */
public interface LanguageSupport {

    Language language();

    /** Decides by file name alone; the first language that accepts a file analyses it. */
    boolean accepts(Path file);

    /**
     * Parses one file. Implementations must be thread-safe: files are parsed in parallel.
     *
     * @param root the analysed root; fqns, packages and modules are derived from the path relative to it
     * @param code the file's contents, already decoded (see {@link SourceText#read})
     */
    ParsedFile parse(Path root, Path file, String code) throws IOException;

    /**
     * Combines every parsed file of this language into the final types and their project-internal dependencies.
     * Called once per analysis on a single thread, with the files in path order.
     */
    Linked link(Path root, List<ParsedFile> files);

    /**
     * @param types   the buildings declared in the file, before linking
     * @param context language-specific data the linker needs (imports, impl blocks, …); may be null
     * @param failed  true when the file had syntax errors and was only partially analysed
     */
    record ParsedFile(Path file, List<TypeInfo> types, Object context, boolean failed) {
    }

    /** @param dependencies fqn → fqns of other project types it uses */
    record Linked(List<TypeInfo> types, Map<String, Set<String>> dependencies) {
    }
}
