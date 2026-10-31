package dev.codecity.desktop;

import java.util.prefs.Preferences;

/**
 * The desktop viewer's settings as read at start-up from its {@link Preferences} node (the user preferences of this
 * package; {@link DesktopApp} writes each key when the user changes it).
 *
 * <p>Keys and values: {@code height}, {@code color} and {@code theme} hold the enum constant's name ({@code NOM},
 * {@code COMMITS}, {@code NIGHT}, …), {@code tests}, {@code labels} and {@code beacons} booleans. A missing key, or an
 * unknown or malformed value (for instance from an older version), gives that setting's default, so a damaged store
 * never stops the viewer from starting. The Rust and D viewers keep the same settings (and shadows, which the JavaFX
 * viewer does not have) in a JSON file instead; their tests check the same fallback rules.
 *
 * @param height  what building heights show
 * @param color   the colour metric the user last picked, or null when never chosen ({@code DesktopApp.setModel} then
 *                picks churn or complexity, depending on whether the model has git history)
 * @param theme   day or night
 * @param tests   whether test code is shown
 * @param labels  whether district labels are shown
 * @param beacons whether open-PR beacons are shown
 */
record DesktopPrefs(Look.HeightMetric height, Look.ColorMetric color, Look.Theme theme, boolean tests, boolean labels,
                    boolean beacons) {

    /** What a first start gives: method-count heights, no colour chosen yet, day, everything shown. */
    static final DesktopPrefs DEFAULTS = new DesktopPrefs(Look.HeightMetric.NOM, null, Look.Theme.DAY, true, true, true);

    /** Reads the settings from {@code node}, by the fallback rules above. */
    static DesktopPrefs read(Preferences node) {
        return new DesktopPrefs(
                named(node, "height", Look.HeightMetric.class, DEFAULTS.height),
                named(node, "color", Look.ColorMetric.class, null),
                named(node, "theme", Look.Theme.class, DEFAULTS.theme),
                // getBoolean gives the default for anything but "true" or "false" (in any case).
                node.getBoolean("tests", DEFAULTS.tests),
                node.getBoolean("labels", DEFAULTS.labels),
                node.getBoolean("beacons", DEFAULTS.beacons));
    }

    /** Writes every setting to {@code node}; a null colour removes the key ("never chosen"). */
    void write(Preferences node) {
        node.put("height", height.name());
        if (color == null) {
            node.remove("color");
        } else {
            node.put("color", color.name());
        }
        node.put("theme", theme.name());
        node.putBoolean("tests", tests);
        node.putBoolean("labels", labels);
        node.putBoolean("beacons", beacons);
    }

    /** The constant of {@code type} named by {@code key}, or {@code fallback} when it is missing or unknown. */
    private static <E extends Enum<E>> E named(Preferences node, String key, Class<E> type, E fallback) {
        String v = node.get(key, null);
        if (v == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
