package dev.codecity.json;

import dev.codecity.server.CityServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The JSON writer's rules, one per test. The Rust ({@code json.rs}) and D ({@code json.d}) editions run the same cases
 * with the same expected text.
 */
class JsonWriterTest {

    private static String write(double... values) {
        JsonWriter w = new JsonWriter().beginArray();
        for (double v : values) {
            w.value(v);
        }
        return w.endArray().toString();
    }

    @Test
    void roundsHalvesUpLikeTheOtherEditions() {
        // 2.0625 * 1000 = 2062.5 exactly, so these are true ties at the third decimal.
        assertEquals("[2.063,-2.062,-1,1.235,2]", write(2.0625, -2.0625, -1.0, 1.23456, 2.0));
    }

    @Test
    void writesNonFiniteNumbersAsZero() {
        assertEquals("[0,0,0]", write(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
    }

    /**
     * A hostile string (from a file name, commit message or pull request title) cannot end or confuse the
     * {@code <script>} element an export embeds the JSON in, nor break a JavaScript string. The Rust and D editions
     * have the same test.
     */
    @Test
    void escapesEverythingThatCouldEscapeAScriptElement() {
        String hostile = "</script><!--\u2028\u2029\u0001&>\"\\";
        assertEquals("[\"\\u003c/script\\u003e\\u003c!--\\u2028\\u2029\\u0001\\u0026\\u003e\\\"\\\\\"]",
                new JsonWriter().beginArray().value(hostile).endArray().toString());
    }

    /** The viewer files an export inlines as they are must not contain what would end their element early. */
    @Test
    void inlinedViewerFilesCannotEndTheirElements() throws Exception {
        String js = new String(CityServer.resource("app.js"), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        String css = new String(CityServer.resource("style.css"), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        assertFalse(js.contains("</script") || js.contains("<!--"));
        assertFalse(css.contains("</style"));
    }

    /** Whole numbers are written without a fraction, whether given as a double or as an integer. */
    @Test
    void writesWholeNumbersWithoutAFraction() {
        assertEquals("[0,3,-7,100000000000000]", write(0.0, 3.0, -7.0, 1e14));
        assertEquals("[0,9223372036854775807,-9223372036854775808]", new JsonWriter().beginArray().value(0L)
                .value(Long.MAX_VALUE).value(Long.MIN_VALUE).endArray().toString());
    }

    /**
     * Numbers keep at most three decimals, without trailing zeros; what rounds to zero (including minus zero and
     * tiny negative values) is written as a plain {@code 0}.
     */
    @Test
    void roundsToThreeDecimalsWithoutTrailingZeros() {
        assertEquals("[0.4,0.3,2.5,1.235,0.001,0,0,0,-0.4]", write(0.4, 0.1 + 0.2, 2.50, 1.23456, 0.0005, 0.0004,
                -0.0004, -0.0, -0.4));
    }

    /**
     * Large numbers are written in full with their shortest round-tripping digits (2^60 as 1152921504606847000),
     * never with an exponent, the same in every edition.
     */
    @Test
    void writesLargeNumbersWithoutAnExponent() {
        assertEquals("[12345678.5,1000000000000000,1152921504606847000,-98765432.125]",
                write(12345678.5, 1e15, 0x1p60, -98765432.125));
    }

    /**
     * Control characters are escaped: newline, carriage return and tab by their short forms, all others (including
     * backspace and form feed) as a six-character unicode escape. DEL and non-ASCII text pass through as they are.
     */
    @Test
    void escapesControlCharactersAndKeepsOtherText() {
        assertEquals("[\"\\n\\r\\t\\u0008\\u000c\\u0000\\u001f\u007f\u00e9\u6f22\ud83d\ude00'/\"]",
                new JsonWriter().beginArray().value("\n\r\t\b\f\u0000\u001f\u007f\u00e9\u6f22\ud83d\ude00'/")
                        .endArray().toString());
    }

    /**
     * Keys are escaped exactly like values: they can hold repository-derived text, and the exported page relies on
     * {@code <}, {@code >}, {@code &} and U+2028/2029 never appearing raw anywhere in its JSON.
     */
    @Test
    void escapesKeysLikeValues() {
        assertEquals("{\"\\u003ck\\u003e\\\"\\u0026\\u2028\":1}", new JsonWriter().beginObject().field("<k>\"&\u2028", 1).endObject()
                .toString());
    }

    /**
     * Keys stay in the order they were written, and commas go exactly between the members of nested objects and
     * arrays, empty ones included.
     */
    @Test
    void keepsKeysInWritingOrderAndCommasBetweenMembers() {
        String json = new JsonWriter().beginObject()
                .field("b", 1)
                .name("a").beginArray().value(1).beginObject().field("c", true).field("d", false).endObject()
                .beginArray().endArray().endArray()
                .name("e").beginObject().endObject()
                .field("f", "x")
                .name("g").value((String) null)
                .endObject().toString();
        assertEquals("{\"b\":1,\"a\":[1,{\"c\":true,\"d\":false},[]],\"e\":{},\"f\":\"x\",\"g\":null}", json);
    }
}
