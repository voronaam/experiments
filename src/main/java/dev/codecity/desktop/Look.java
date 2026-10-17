package dev.codecity.desktop;

import dev.codecity.CityModel;
import dev.codecity.CityModel.Building;
import javafx.scene.paint.Color;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Palettes, themes and metric definitions shared by the desktop viewer (mirrors the web viewer). */
final class Look {

    private Look() {
    }

    static final Color[] RAMP = Arrays.stream(new String[]{
            "#2b6cb0", "#2a9fc0", "#34c3a5", "#8fd67a", "#e8e15b", "#f6b343", "#ef7d34", "#dc3f2e", "#a3123a"})
            .map(Color::web).toArray(Color[]::new);

    static final Map<String, Color> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("class", Color.web("#6b8fc7"));
        KINDS.put("abstract", Color.web("#9a86d8"));
        KINDS.put("interface", Color.web("#2fb7a5"));
        KINDS.put("enum", Color.web("#e3a63b"));
        KINDS.put("record", Color.web("#d46fa8"));
        KINDS.put("annotation", Color.web("#8a9aaa"));
    }

    static final Color OUT = Color.web("#f97316");
    static final Color IN = Color.web("#0ea5e9");
    static final Color PR = Color.web("#c084fc");

    static Color ramp(double t) {
        double x = Math.max(0, Math.min(1, t)) * (RAMP.length - 1);
        int i = Math.min(RAMP.length - 2, (int) Math.floor(x));
        return RAMP[i].interpolate(RAMP[i + 1], x - i);
    }

    static Color kind(String kind) {
        return KINDS.getOrDefault(kind, KINDS.get("class"));
    }

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

    enum HeightMetric {
        NOM("Methods (NOM)", b -> b.type().nom()),
        LOC("Lines of code", b -> b.type().loc()),
        CX("Complexity (WMC)", b -> b.type().complexity()),
        NOA("Attributes (NOA)", b -> b.type().noa());

        final String label;
        final ToDoubleFunction<Building> value;

        HeightMetric(String label, ToDoubleFunction<Building> value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Requires { NOTHING, GIT, PULLS }

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

        String unavailableReason() {
            return requires == Requires.GIT ? " (no git)" : requires == Requires.PULLS ? " (no GitHub)" : "";
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Colour scale for a metric: log-scaled and clipped at the 98th percentile, like the web viewer. */
    record Scale(ColorMetric metric, double lo, double hi) {

        static Scale of(ColorMetric metric, List<Building> buildings) {
            double[] finite = buildings.stream().mapToDouble(metric.value).filter(Double::isFinite).sorted().toArray();
            double p98 = finite.length == 0 ? 0 : finite[Math.min(finite.length - 1, (int) (0.98 * (finite.length - 1)))];
            double hi = Math.max(metric.linear ? 1e-6 : 1, p98);
            double lo = finite.length == 0 ? 0 : Math.min(finite[0], hi);
            return new Scale(metric, lo, hi);
        }

        Color color(Building b) {
            if (metric == ColorMetric.KIND) {
                return kind(b.type().kind());
            }
            double v = metric.value.applyAsDouble(b);
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

    static String fmt(double n) {
        if (n >= 100_000) {
            return Math.round(n / 1000) + "k";
        }
        if (n >= 10_000) {
            return String.format("%.1fk", n / 1000);
        }
        return String.format("%,d", Math.round(n));
    }

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

    static String hex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }
}
