package dev.codecity.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser for API responses. Produces {@link Map}, {@link List}, {@link String}, {@link Double},
 * {@link Boolean} or {@code null}.
 */
public final class JsonParser {

    private final String s;
    private int i;

    private JsonParser(String s) {
        this.s = s;
    }

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
