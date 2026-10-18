package dev.codecity.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser for API responses. Produces {@link Map}, {@link List}, {@link String}, {@link Double},
 * {@link Boolean} or {@code null}.
 *
 * <p>Used for the GitHub API responses in {@link dev.codecity.github.PullRequests}. It is a recursive-descent parser
 * with a single cursor {@code i} into the input, and it accepts slightly more than strict JSON: a number is any run of
 * {@code +-0123456789.eE} handed to {@link Double#parseDouble}, unknown escapes yield the escaped character itself, and
 * raw control characters inside strings are kept. Objects keep their key order ({@link LinkedHashMap}). Malformed
 * input raises an {@link IllegalArgumentException}, usually naming the offset (input that ends inside a string escape
 * raises an {@link IndexOutOfBoundsException} instead); {@code PullRequests} treats both as a failed fetch.
 */
public final class JsonParser {

    private final String s;
    private int i;

    private JsonParser(String s) {
        this.s = s;
    }

    /** Parses one complete JSON document; anything but whitespace after the value is an error. */
    public static Object parse(String json) {
        JsonParser p = new JsonParser(json);
        Object value = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.error("trailing content");
        }
        return value;
    }

    /** Navigates nested maps, e.g. {@code path(root, "data", "repository")}; null if any step is missing. */
    public static Object path(Object node, String... keys) {
        for (String k : keys) {
            if (!(node instanceof Map<?, ?> m)) {
                return null;
            }
            node = m.get(k);
        }
        return node;
    }

    /** Parses the value at the cursor; the first character decides which kind it is. */
    private Object value() {
        ws();
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    /** Parses {@code {...}} with the cursor on the opening brace. A duplicate key keeps the last value. */
    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            map.put(key, value());
            ws();
            if (peek() == ',') {
                i++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            list.add(value());
            ws();
            if (peek() == ',') {
                i++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    /**
     * Parses a string literal. {@code \}{@code uXXXX} becomes one UTF-16 unit, so a surrogate pair written as two
     * escapes comes out as the right two chars without special handling.
     */
    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw error("unexpected character '" + s.charAt(i) + "'");
        }
        return Double.parseDouble(s.substring(start, i));
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, i)) {
            throw error("expected " + word);
        }
        i += word.length();
        return value;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        i++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON " + msg + " at offset " + i);
    }
}
