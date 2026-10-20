package dev.codecity.json;

import dev.codecity.server.CityServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
