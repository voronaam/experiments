package dev.codecity.json;

/**
 * Minimal streaming JSON writer. Output is safe to embed inside an HTML {@code <script>} element.
 *
 * <p>Used for the city model served to the web viewer (and inlined by {@code HtmlExporter}), for the
 * {@code /api/source} response and for GitHub GraphQL request bodies. Calls are chained in document order, e.g.
 * {@code beginObject().field("a", 1).name("b").beginArray().value(2).endArray().endObject()}. The writer does not
 * check nesting; a mismatched begin/end simply produces invalid JSON.
 *
 * <p>Comma handling: {@code needComma} is true right after a complete value. Every method that starts a new value or
 * key ({@code beginObject}, {@code beginArray}, {@code name}, {@code value}, {@code raw}) first emits a comma if it is
 * set. {@code name} and the {@code begin} methods then clear it, because what follows a key or an opening bracket must
 * not be preceded by a comma.
 */
public final class JsonWriter {

    private final StringBuilder out = new StringBuilder(1 << 16);
    private boolean needComma;

    public JsonWriter beginObject() {
        comma();
        out.append('{');
        needComma = false;
        return this;
    }

    public JsonWriter endObject() {
        out.append('}');
        needComma = true;
        return this;
    }

    public JsonWriter beginArray() {
        comma();
        out.append('[');
        needComma = false;
        return this;
    }

    public JsonWriter endArray() {
        out.append(']');
        needComma = true;
        return this;
    }

    public JsonWriter name(String name) {
        comma();
        string(name);
        out.append(':');
        needComma = false;
        return this;
    }

    public JsonWriter value(String s) {
        comma();
        if (s == null) {
            out.append("null");
        } else {
            string(s);
        }
        needComma = true;
        return this;
    }

    public JsonWriter value(long v) {
        comma();
        out.append(v);
        needComma = true;
        return this;
    }

    /**
     * Writes a number rounded to three decimals. Whole numbers below 10^15 are written without a fraction
     * ({@code 3}, not {@code 3.0}); larger ones use {@link Double#toString} (e.g. {@code 1.0E15}), which is still valid
     * JSON. {@link Math#round(double)} rounds halves up (towards positive infinity), which the other editions copy
     * exactly. JSON has no NaN or infinity, so those are written as {@code 0}.
     */
    public JsonWriter value(double v) {
        comma();
        double r = Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : 0;
        if (r == Math.rint(r) && Math.abs(r) < 1e15) {
            out.append((long) r);
        } else {
            out.append(r);
        }
        needComma = true;
        return this;
    }

    public JsonWriter value(boolean v) {
        comma();
        out.append(v);
        needComma = true;
        return this;
    }

    /** Writes an already-serialised JSON value verbatim. */
    public JsonWriter raw(String json) {
        comma();
        out.append(json);
        needComma = true;
        return this;
    }

    public JsonWriter field(String name, String v) {
        return name(name).value(v);
    }

    public JsonWriter field(String name, long v) {
        return name(name).value(v);
    }

    public JsonWriter field(String name, double v) {
        return name(name).value(v);
    }

    public JsonWriter field(String name, boolean v) {
        return name(name).value(v);
    }

    private void comma() {
        if (needComma) {
            out.append(',');
        }
    }

    /**
     * Writes a quoted, escaped string. Beyond the escapes JSON requires, {@code <}, {@code >} and {@code &} are written
     * as unicode escapes so that {@code </script>} or {@code <!--} inside a value cannot end or confuse an enclosing
     * HTML script element, and U+2028/U+2029 (line and paragraph separator) are escaped because older JavaScript
     * engines reject them inside string literals.
     */
    private void string(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
