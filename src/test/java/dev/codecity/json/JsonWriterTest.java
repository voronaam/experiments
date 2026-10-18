package dev.codecity.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
