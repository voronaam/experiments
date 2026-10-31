package dev.codecity.desktop;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests of the desktop settings ({@link DesktopPrefs}): defaults, round trips and damaged values, on an in-memory
 * {@link Preferences} node so the user's real preferences are never touched. The Rust and D viewers' settings file
 * tests ({@code prefs_tests.rs}, {@code prefs.d}) check the same fallback rules on their JSON file.
 */
class DesktopPrefsTest {

    /** A preferences node that lives only in memory. */
    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();

        MemoryPreferences() {
            super(null, "");
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }

    @Test
    void defaultsOnFirstStart() {
        assertEquals(new DesktopPrefs(Look.HeightMetric.NOM, null, Look.Theme.DAY, true, true, true), DesktopPrefs.DEFAULTS);
        assertEquals(DesktopPrefs.DEFAULTS, DesktopPrefs.read(new MemoryPreferences()));
    }

    @Test
    void writesWhatItReads() {
        Preferences node = new MemoryPreferences();
        for (Look.HeightMetric height : Look.HeightMetric.values()) {
            for (int c = 0; c <= Look.ColorMetric.values().length; c++) {
                Look.ColorMetric color = c < Look.ColorMetric.values().length ? Look.ColorMetric.values()[c] : null;
                int k = height.ordinal();
                DesktopPrefs p = new DesktopPrefs(height, color, k % 2 == 0 ? Look.Theme.DAY : Look.Theme.NIGHT,
                        k == 0, k == 1, k == 2);
                p.write(node);
                assertEquals(p, DesktopPrefs.read(node));
            }
        }
    }

    @Test
    void damagedValuesFallBackOneByOne() {
        Preferences node = new MemoryPreferences();
        // Unknown or wrongly typed values: each falls back to its own default; the valid one is kept.
        node.put("height", "3");
        node.put("color", "Bogus");
        node.put("theme", "night");
        node.put("tests", "maybe");
        node.put("labels", "");
        node.put("beacons", "false");
        assertEquals(new DesktopPrefs(Look.HeightMetric.NOM, null, Look.Theme.DAY, true, true, false), DesktopPrefs.read(node));
        // Only the height set, as by a first change.
        Preferences partial = new MemoryPreferences();
        partial.put("height", "NOA");
        assertEquals(new DesktopPrefs(Look.HeightMetric.NOA, null, Look.Theme.DAY, true, true, true), DesktopPrefs.read(partial));
        // A colour that was chosen and is written as never chosen again is removed.
        new DesktopPrefs(Look.HeightMetric.CX, Look.ColorMetric.AGE, Look.Theme.NIGHT, false, false, true).write(node);
        DesktopPrefs.DEFAULTS.write(node);
        assertEquals(DesktopPrefs.DEFAULTS, DesktopPrefs.read(node));
        assertFalse(java.util.Arrays.asList(((MemoryPreferences) node).keysSpi()).contains("color"));
    }
}
