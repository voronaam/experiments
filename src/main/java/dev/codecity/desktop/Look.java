package dev.codecity.desktop;

import dev.codecity.CityModel;
import dev.codecity.CityModel.Building;
import dev.codecity.history.TypeHistory;
import javafx.scene.paint.Color;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Palettes, themes and metric definitions shared by the desktop viewer (mirrors the web viewer).
 *
 * <p>The ramp, kind colours, metric list, percentile clipping and log scaling match the {@code RAMP}, {@code KINDS},
 * {@code HEIGHT_METRICS} and {@code COLOR_METRICS} tables in {@code web/app.js}, and the background, ground, district
 * and dim colours match its {@code THEMES}, so both viewers colour a project the same way; a change to one should be
 * made to the other too. The lighting values differ, because JavaFX offers ambient and directional lights where the
 * web viewer uses a hemisphere light, a sun with shadows and an environment map.
 */
final class Look {

    private Look() {
    }

    /** The sequential colour ramp for numeric metrics, from cool/low (blue) to hot/high (crimson). */
    static final Color[] RAMP = Arrays.stream(new String[]{
            "#2b6cb0", "#2a9fc0", "#34c3a5", "#8fd67a", "#e8e15b", "#f6b343", "#ef7d34", "#dc3f2e", "#a3123a"})
            .map(Color::web).toArray(Color[]::new);

    /**
     * Colour per type kind for the "Type kind" metric. Keys are the kind strings the analysers produce (across Java,
     * TypeScript and Rust); insertion order is the legend order.
     */
    static final Map<String, Color> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("class", Color.web("#6b8fc7"));
        KINDS.put("abstract", Color.web("#9a86d8"));
        KINDS.put("interface", Color.web("#2fb7a5"));
        KINDS.put("enum", Color.web("#e3a63b"));
        KINDS.put("record", Color.web("#d46fa8"));
        KINDS.put("annotation", Color.web("#8a9aaa"));
        KINDS.put("struct", Color.web("#4f86c6"));
        KINDS.put("trait", Color.web("#26a69a"));
        KINDS.put("union", Color.web("#c98a3a"));
        KINDS.put("type", Color.web("#b7a3e0"));
        KINDS.put("module", Color.web("#7c8ba1"));
    }

    /** Dependency arcs to the types the selection uses (orange). */
    static final Color OUT = Color.web("#f97316");
    /** Dependency arcs from the types that use the selection (blue). */
    static final Color IN = Color.web("#0ea5e9");
    /** Open-pull-request beacons (violet). */
    static final Color PR = Color.web("#c084fc");

    /**
     * Colour at position {@code t} on {@link #RAMP}, clamped to [0, 1]. The stops are evenly spaced and linearly
     * interpolated in RGB; {@code i} is capped at the second-to-last stop so that {@code t = 1} lands exactly on the
     * last colour instead of indexing past it.
     */
    static Color ramp(double t) {
        double x = Math.max(0, Math.min(1, t)) * (RAMP.length - 1);
        int i = Math.min(RAMP.length - 2, (int) Math.floor(x));
        return RAMP[i].interpolate(RAMP[i + 1], x - i);
    }

    static Color kind(String kind) {
        return KINDS.getOrDefault(kind, KINDS.get("class"));
    }

    /**
     * Day and night look of the 3D scene. {@code css} is the style class put on the window root (and the search
     * popup) so the HUD stylesheet can follow; {@code districts} holds one platform colour per nesting depth, deeper
     * levels reusing the last one; {@code dim} is what buildings fade towards when they fall outside the search or the
     * selection's neighbourhood.
     */
    enum Theme {
        DAY("day", "#e8ecf1", "#dfe5ec",
                new String[]{"#c3ccd7", "#cfd7e0", "#d9dfe7", "#e2e7ed", "#eaeef2", "#f0f3f6"},
                "#7d8794", "#fff8ee", 0.78, "#c6d0dc", 0.32, 0.0, "#d5dbe3"),
        NIGHT("night", "#04070d", "#060b14",
                new String[]{"#0e1626", "#121c30", "#16223a", "#1a2844", "#1e2e4e", "#223458"},
                "#1c2236", "#8fa6ff", 0.55, "#24304f", 0.35, 0.42, "#0c1220");

        final String css;
        final Color background;
        final Color ground;
        final Color[] districts;
        final Color ambient;
        final Color keyLight;
        final double keyIntensity;
        final Color fillLight;
        final double fillIntensity;
        /** How strongly buildings emit their own colour (0 = not at all). */
        final double glow;
        final Color dim;

        Theme(String css, String background, String ground, String[] districts, String ambient, String keyLight,
              double keyIntensity, String fillLight, double fillIntensity, double glow, String dim) {
            this.css = css;
            this.background = Color.web(background);
            this.ground = Color.web(ground);
            this.districts = Arrays.stream(districts).map(Color::web).toArray(Color[]::new);
            this.ambient = Color.web(ambient);
            this.keyLight = Color.web(keyLight);
            this.keyIntensity = keyIntensity;
            this.fillLight = Color.web(fillLight);
            this.fillIntensity = fillIntensity;
            this.glow = glow;
            this.dim = Color.web(dim);
        }

        Theme other() {
            return this == DAY ? NIGHT : DAY;
        }
    }

    /**
     * What a building's height stands for. Heights are scaled in {@code DesktopApp.reheight}; {@code hist} picks the
     * same metric's per-snapshot values for the history replay.
     */
    enum HeightMetric {
        NOM("Methods (NOM)", b -> b.type().nom(), h -> h.nom),
        LOC("Lines of code", b -> b.type().loc(), h -> h.loc),
        CX("Complexity (WMC)", b -> b.type().complexity(), h -> h.cx),
        NOA("Attributes (NOA)", b -> b.type().noa(), h -> h.noa);

        final String label;
        final ToDoubleFunction<Building> value;
        final Function<TypeHistory, int[]> hist;

        HeightMetric(String label, ToDoubleFunction<Building> value, Function<TypeHistory, int[]> hist) {
            this.label = label;
            this.value = value;
            this.hist = hist;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What a colour metric needs: git history, or the GitHub pull-request list. */
    enum Requires { NOTHING, GIT, PULLS }

    /**
     * What a building's colour stands for.
     *
     * <ul>
     *   <li>{@code linear}: map values linearly instead of with {@code log1p}. Only the complexity/method ratio uses
     *       it.</li>
     *   <li>{@code invert}: high values are the cool end of the ramp. Used for recency, whose value is the age in
     *       days, so a just-changed file is hot.</li>
     *   <li>{@code lo}/{@code hi}: words for the two ends of the legend.</li>
     *   <li>{@code value}: the raw metric. Recency is {@code +Infinity} for a file without history, which
     *       {@link Scale#color} paints as the "dormant" end.</li>
     * </ul>
     */
    enum ColorMetric {
        COMMITS("Churn (commits)", Requires.GIT, false, false, "stable", "hot spot",
                b -> b.history() == null ? 0 : b.history().commits()),
        AGE("Recency", Requires.GIT, false, true, "dormant", "just changed",
                b -> b.history() == null ? Double.POSITIVE_INFINITY
                        : Math.max(0, (System.currentTimeMillis() / 1000.0 - b.history().lastCommit()) / 86400)),
        AUTHORS("Authors", Requires.GIT, false, false, "one owner", "many hands",
                b -> b.history() == null ? 0 : b.history().authors()),
        PRS("Open pull requests", Requires.PULLS, false, false, "none", "contested", b -> b.prs().length),
        CX("Complexity (WMC)", Requires.NOTHING, false, false, "simple", "complex", b -> b.type().complexity()),
        DENSITY("Complexity / method", Requires.NOTHING, true, false, "flat", "branchy",
                b -> b.type().nom() == 0 ? 0 : (double) b.type().complexity() / b.type().nom()),
        LOC("Lines of code", Requires.NOTHING, false, false, "small", "large", b -> b.type().loc()),
        KIND("Type kind", Requires.NOTHING, false, false, "", "", b -> 0);

        final String label;
        final Requires requires;
        final boolean linear;
        final boolean invert;
        final String lo;
        final String hi;
        final ToDoubleFunction<Building> value;

        ColorMetric(String label, Requires requires, boolean linear, boolean invert, String lo, String hi,
                    ToDoubleFunction<Building> value) {
            this.label = label;
            this.requires = requires;
            this.linear = linear;
            this.invert = invert;
            this.lo = lo;
            this.hi = hi;
            this.value = value;
        }

        boolean available(CityModel model) {
            return switch (requires) {
                case NOTHING -> true;
                case GIT -> model.hasGit();
                case PULLS -> model.github() != null && model.github().pulls() != null;
            };
        }

        /**
         * The metric for building {@code b} as it was in replay snapshot {@code k} (author time {@code time}): the
         * snapshot's metrics and its git counts, recency measured from the snapshot's time rather than from now. A
         * type absent from the snapshot counts as 0 (and as dormant for recency). The open-PR metric has no history;
         * the caller replaces it with churn during replay.
         */
        double replayValue(Building b, int k, long time) {
            TypeHistory h = b.hist();
            return switch (this) {
                case COMMITS, PRS -> h.commits[k];
                case AGE -> h.last[k] < 0 ? Double.POSITIVE_INFINITY : Math.max(0, (time - h.last[k]) / 86400.0);
                case AUTHORS -> h.authors[k];
                case CX -> Math.max(0, h.cx[k]);
                case DENSITY -> h.nom[k] <= 0 ? 0 : (double) h.cx[k] / h.nom[k];
                case LOC -> Math.max(0, h.loc[k]);
                case KIND -> 0;
            };
        }

        String unavailableReason() {
            return requires == Requires.GIT ? " (no git)" : requires == Requires.PULLS ? " (no GitHub)" : "";
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Colour scale for a metric: log-scaled and clipped at the 98th percentile, like the web viewer.
     *
     * <p>Clipping at the 98th percentile stops a handful of outliers from pushing every other building into the
     * bottom of the ramp; everything above {@code hi} gets the hottest colour. {@code lo} is only shown in the legend:
     * the mapping itself always starts from 0.
     */
    record Scale(ColorMetric metric, double lo, double hi) {

        /**
         * Computes the scale over all present-day buildings (hidden test code included, types that exist only in the
         * history replay left out). Infinite values are left out of the percentile. {@code hi} is at least 1 on a log scale (so {@code log1p(hi)} is never 0) and a tiny positive
         * number on a linear one, which avoids dividing by zero.
         */
        static Scale of(ColorMetric metric, List<Building> buildings) {
            double[] finite = buildings.stream().filter(b -> !b.gone()).mapToDouble(metric.value)
                    .filter(Double::isFinite).sorted().toArray();
            double p98 = finite.length == 0 ? 0 : finite[Math.min(finite.length - 1, (int) (0.98 * (finite.length - 1)))];
            double hi = Math.max(metric.linear ? 1e-6 : 1, p98);
            double lo = finite.length == 0 ? 0 : Math.min(finite[0], hi);
            return new Scale(metric, lo, hi);
        }

        /** Ramp colour of {@code b}: {@code log1p(v) / log1p(hi)} or {@code v / hi}, flipped for inverted metrics. */
        Color color(Building b) {
            return color(b, metric.value.applyAsDouble(b));
        }

        /** Like {@link #color(Building)}, but for the value {@code v} instead of the building's present one. */
        Color color(Building b, double v) {
            if (metric == ColorMetric.KIND) {
                return kind(b.type().kind());
            }
            double t;
            if (!Double.isFinite(v)) {
                t = metric.invert ? 0 : 1;
            } else {
                t = metric.linear ? v / hi : Math.log1p(v) / Math.log1p(hi);
                if (metric.invert) {
                    t = 1 - t;
                }
            }
            return ramp(t);
        }
    }

    /** Compact number for the HUD: {@code 1,234}, {@code 12.3k}, {@code 123k}. */
    static String fmt(double n) {
        if (n >= 100_000) {
            return Math.round(n / 1000) + "k";
        }
        if (n >= 10_000) {
            return String.format("%.1fk", n / 1000);
        }
        return String.format("%,d", Math.round(n));
    }

    /** "today", "5d ago", "3mo ago" or "1.2y ago" (months of 30 days, years of 365); "—" when unknown (≤ 0). */
    static String relTime(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "—";
        }
        double days = (System.currentTimeMillis() / 1000.0 - epochSeconds) / 86400;
        if (days < 1) {
            return "today";
        }
        if (days < 30) {
            return Math.round(days) + "d ago";
        }
        if (days < 365) {
            return Math.round(days / 30) + "mo ago";
        }
        return String.format("%.1fy ago", days / 365);
    }

    /**
     * Whether {@code url} may be handed to the system browser: an {@code https://} URL without spaces or control
     * characters. Link targets come from the city data (pull request URLs from the GitHub API, the repository URL
     * from the analysed tree's git remote), so anything else ({@code file:}, {@code javascript:}, custom URL schemes
     * that start other applications, option-like strings) is refused.
     */
    static boolean isWebLink(String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8) && url.chars().noneMatch(c -> c <= ' ' || c == 0x7f);
    }

    /** {@code #rrggbb} for use in inline JavaFX CSS; opacity is dropped. */
    static String hex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }
}
