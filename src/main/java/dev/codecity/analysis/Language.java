package dev.codecity.analysis;

/** Supported source languages and how each one names its "packages" (the districts of the city). */
public enum Language {
    JAVA("java", "Java", "."),
    TYPESCRIPT("typescript", "TypeScript", "/"),
    RUST("rust", "Rust", "::");

    /** Identifier used in JSON and for syntax highlighting. */
    public final String id;
    public final String displayName;
    /** Separator between package segments: {@code com.acme}, {@code src/ui}, {@code net::http}. */
    public final String separator;

    Language(String id, String displayName, String separator) {
        this.id = id;
        this.displayName = displayName;
        this.separator = separator;
    }
}
