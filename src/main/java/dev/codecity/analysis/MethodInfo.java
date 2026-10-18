package dev.codecity.analysis;

/**
 * A method or constructor and its size/complexity. TypeScript and Rust also use it for free functions (collected in
 * a "module" building) and TypeScript for top-level calls such as {@code describe("…", () => …)}.
 *
 * @param line       first line of the declaration (1-based)
 * @param loc        non-blank, non-comment lines, see {@link SourceText#countLoc}
 * @param complexity cyclomatic complexity, at least 1; 1 for signatures without a body
 * @param parameters declared parameters; a Rust {@code self} receiver is not counted
 */
public record MethodInfo(String name, int line, int loc, int complexity, int parameters) {
}
