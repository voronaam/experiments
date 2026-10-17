package dev.codecity.analysis;

/** A method or constructor and its size/complexity. */
public record MethodInfo(String name, int line, int loc, int complexity, int parameters) {
}
