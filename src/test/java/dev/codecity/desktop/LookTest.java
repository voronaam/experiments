package dev.codecity.desktop;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link Look}: the colour ramp, kind colours, the day and night palettes, the colour scale and its legend,
 * number formatting and relative ages. Rust's {@code look_tests.rs} and D's {@code look.d} check the same values.
 */
class LookTest {

    /** 2026-09-30T12:00:00Z, the "now" of the age tests. */
    private static final long NOW = 1_790_769_600L;
    private static final long DAY = 86_400;

    private static String hex(Color c) {
        return Look.hex(c);
    }

    @Test
    void rampHitsItsStopsAndBlendsBetweenThem() {
        assertEquals("#2b6cb0", hex(Look.ramp(0)));
        assertEquals("#e8e15b", hex(Look.ramp(0.5)));
        assertEquals("#a3123a", hex(Look.ramp(1)));
        assertEquals("#2b6cb0", hex(Look.ramp(-1)));
        assertEquals("#a3123a", hex(Look.ramp(2)));
        assertEquals("#2b79b4", hex(Look.ramp(1.0 / 32)));
        assertEquals("#58cb94", hex(Look.ramp(0.3)));
        assertEquals("#f2933a", hex(Look.ramp(0.7)));
        assertEquals("#d13630", hex(Look.ramp(0.9)));
    }

    @Test
    void kindsHaveFixedColoursAndUnknownOnesLookLikeClasses() {
        assertEquals(List.of("class", "abstract", "interface", "enum", "record", "annotation", "struct", "trait", "union",
                "type", "module"), List.copyOf(Look.KINDS.keySet()));
        assertEquals("#6b8fc7", hex(Look.kind("class")));
        assertEquals("#26a69a", hex(Look.kind("trait")));
        assertEquals("#7c8ba1", hex(Look.kind("module")));
        assertEquals("#6b8fc7", hex(Look.kind("widget")));
        assertEquals("#f97316", hex(Look.OUT));
        assertEquals("#0ea5e9", hex(Look.IN));
        assertEquals("#c084fc", hex(Look.PR));
    }

    @Test
    void dayAndNightPalettes() {
        Look.Theme day = Look.Theme.DAY;
        assertEquals("#e8ecf1", hex(day.background));
        assertEquals("#dfe5ec", hex(day.ground));
        assertEquals("#d5dbe3", hex(day.dim));
        assertEquals(List.of("#c3ccd7", "#cfd7e0", "#d9dfe7", "#e2e7ed", "#eaeef2", "#f0f3f6"),
                java.util.Arrays.stream(day.districts).map(Look::hex).toList());
        Look.Theme night = Look.Theme.NIGHT;
        assertEquals("#04070d", hex(night.background));
        assertEquals("#060b14", hex(night.ground));
        assertEquals("#0c1220", hex(night.dim));
        assertEquals(List.of("#0e1626", "#121c30", "#16223a", "#1a2844", "#1e2e4e", "#223458"),
                java.util.Arrays.stream(night.districts).map(Look::hex).toList());
        assertEquals(0, day.glow);
        assertTrue(night.glow > 0);
        assertEquals(night, day.other());
        assertEquals(day, night.other());
        assertEquals("day", day.css);
        assertEquals("night", night.css);
    }

    @Test
    void metricMenusAndLegendWords() {
        assertEquals(List.of("Methods (NOM)", "Lines of code", "Complexity (WMC)", "Attributes (NOA)"),
                java.util.Arrays.stream(Look.HeightMetric.values()).map(m -> m.label).toList());
        assertEquals(List.of("Churn (commits)", "Recency", "Authors", "Open pull requests", "Complexity (WMC)",
                        "Complexity / method", "Lines of code", "Type kind"),
                java.util.Arrays.stream(Look.ColorMetric.values()).map(m -> m.label).toList());
        assertEquals("dormant just changed", Look.ColorMetric.AGE.lo + " " + Look.ColorMetric.AGE.hi);
        assertEquals("flat branchy", Look.ColorMetric.DENSITY.lo + " " + Look.ColorMetric.DENSITY.hi);
        assertEquals("", Look.ColorMetric.KIND.lo + Look.ColorMetric.KIND.hi);
    }

    @Test
    void scaleClipsAtThe98thPercentileOnALogRamp() {
        Look.Scale s = Look.Scale.over(Look.ColorMetric.CX, IntStream.rangeClosed(0, 100).asDoubleStream().toArray());
        assertEquals(0, s.lo());
        assertEquals(98, s.hi());
        assertEquals("#2b6cb0", hex(s.color(0, "class")));
        assertEquals("#5acb93", hex(s.color(3, "class")));
        assertEquals("#a3123a", hex(s.color(98, "class")));
        assertEquals("#a3123a", hex(s.color(500, "class")));
        assertArrayEquals(new String[]{"0", "98+"}, s.ends());
        Look.Scale empty = Look.Scale.over(Look.ColorMetric.LOC, new double[0]);
        assertEquals(0, empty.lo());
        assertEquals(1, empty.hi());
        Look.Scale zeros = Look.Scale.over(Look.ColorMetric.COMMITS, new double[]{0, 0, Double.POSITIVE_INFINITY});
        assertEquals(0, zeros.lo());
        assertEquals(1, zeros.hi());
        assertEquals("#a3123a", hex(zeros.color(Double.POSITIVE_INFINITY, "class")));
        assertArrayEquals(new String[]{"50", "12.3k+"}, Look.Scale.over(Look.ColorMetric.LOC, new double[]{50, 12_345, 12_345}).ends());
    }

    @Test
    void densityIsLinearAndRecencyInverted() {
        Look.Scale d = Look.Scale.over(Look.ColorMetric.DENSITY, new double[]{0.5, 2.5, 2.5});
        assertEquals(0.5, d.lo());
        assertEquals(2.5, d.hi());
        assertEquals("#f3bc48", hex(d.color(1.5, "class")));
        assertArrayEquals(new String[]{"0.5", "2.5+"}, d.ends());
        assertEquals(1e-6, Look.Scale.over(Look.ColorMetric.DENSITY, new double[0]).hi());
        Look.Scale a = Look.Scale.over(Look.ColorMetric.AGE, new double[]{0, 3, 30, 365, Double.POSITIVE_INFINITY});
        assertEquals(0, a.lo());
        assertEquals(30, a.hi());
        assertEquals("#a3123a", hex(a.color(0, "class")));
        assertEquals("#f3be49", hex(a.color(3, "class")));
        assertEquals("#2b6cb0", hex(a.color(30, "class")));
        assertEquals("#2b6cb0", hex(a.color(365, "class")));
        assertEquals("#2b6cb0", hex(a.color(Double.POSITIVE_INFINITY, "class")));
        assertArrayEquals(new String[]{"30d ago+", "0d ago"}, a.ends());
        assertEquals("#26a69a", hex(Look.Scale.over(Look.ColorMetric.KIND, new double[0]).color(0, "trait")));
    }

    @Test
    void fmtSignsAndSeparators() {
        assertEquals("-123", Look.fmt(-123));
        assertEquals("-1,234", Look.fmt(-1234));
        assertEquals("0", Look.fmt(0));
        assertEquals("999", Look.fmt(999.4));
        assertEquals("1,000", Look.fmt(999.5));
        assertEquals("9,876", Look.fmt(9876));
        assertEquals("10.0k", Look.fmt(10_000));
        assertEquals("12.3k", Look.fmt(12_345));
        assertEquals("100.0k", Look.fmt(99_999));
        assertEquals("100k", Look.fmt(100_000));
        assertEquals("1235k", Look.fmt(1_234_567));
        // Negative halves round up too (towards +∞).
        assertEquals("-2", Look.fmt(-2.5));
    }

    @Test
    void oneDecimalTiesRoundHalfUp() {
        // Ties round up, also where n / 1000 is not exact in binary (10.35 is stored as 10.3499…).
        assertEquals("12.3k", Look.fmt(12_250));
        assertEquals("10.4k", Look.fmt(10_350));
        assertEquals("12.2k", Look.fmt(12_249));
        assertEquals("151k", Look.fmt(150_500));
        assertEquals("1.3", Look.ratio(5, 4));
        assertEquals("1.2", Look.ratio(23, 20));
        assertEquals("0.3", Look.ratio(1, 3));
        assertEquals("3.1", Look.ratio(25, 8));
        assertEquals("1.3", Look.oneDecimal(1.25));
        assertEquals("2.5", Look.oneDecimal(2.5));
        assertEquals("-0.2", Look.oneDecimal(-0.25));
        assertEquals("0.0", Look.tenths(0));
        assertEquals("-0.5", Look.tenths(-5));
        assertEquals("12.3", Look.tenths(123));
        // 1.25 years exactly.
        assertEquals("1.3y ago", Look.relTime(NOW - 456 * DAY - 6 * 3600, NOW));
        assertArrayEquals(new String[]{"0.3", "1.3+"}, Look.Scale.over(Look.ColorMetric.DENSITY, new double[]{0.25, 1.25, 1.25}).ends());
    }

    @Test
    void numbersDoNotFollowTheSystemLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1,234", Look.fmt(1234));
            assertEquals("12.3k", Look.fmt(12_345));
            assertEquals("1.1y ago", Look.relTime(NOW - 400 * DAY, NOW));
            assertArrayEquals(new String[]{"0.5", "2.5+"}, Look.Scale.over(Look.ColorMetric.DENSITY, new double[]{0.5, 2.5, 2.5}).ends());
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void agesRelativeToNow() {
        assertEquals("—", Look.relTime(0, NOW));
        assertEquals("—", Look.relTime(-5, NOW));
        assertEquals("today", Look.relTime(NOW - 3600, NOW));
        assertEquals("today", Look.relTime(NOW + DAY, NOW));
        assertEquals("5d ago", Look.relTime(NOW - 5 * DAY, NOW));
        assertEquals("30d ago", Look.relTime(NOW - 29 * DAY - 14 * 3600, NOW));
        assertEquals("2mo ago", Look.relTime(NOW - 45 * DAY, NOW));
        assertEquals("12mo ago", Look.relTime(NOW - 364 * DAY, NOW));
        assertEquals("1.1y ago", Look.relTime(NOW - 400 * DAY, NOW));
    }

    @Test
    void webLinksAreHttpsOnly() {
        assertTrue(Look.isWebLink("https://github.com/o/r/pull/1"));
        assertTrue(Look.isWebLink("HTTPS://github.com/o/r"));
        for (String bad : new String[]{"javascript:alert(1)", "file:///etc/passwd", "http://x", "--help", "https://x y",
                "https://x\n", "ssh://h", "", "https:/", "https://x\u007f"}) {
            assertFalse(Look.isWebLink(bad), bad);
        }
        assertFalse(Look.isWebLink(null));
    }
}
