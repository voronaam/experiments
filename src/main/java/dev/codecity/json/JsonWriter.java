package dev.codecity.json;

/** Minimal streaming JSON writer. Output is safe to embed inside an HTML {@code <script>} element. */
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

    public JsonWriter value(double v) {
        comma();
        double r = Math.round(v * 1000) / 1000.0;
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
