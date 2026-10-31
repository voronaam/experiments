//! Tests of `look.rs`: the colour ramp, kind colours, the day and night palettes, the colour scale and its legend,
//! number formatting and relative ages. The Java (`LookTest`) and D (`look.d`) editions check the same values.

use super::look::*;
use three_d::Srgba;

/// 2026-09-30T12:00:00Z, the "now" of the age tests.
const NOW: i64 = 1_790_769_600;
const DAY: i64 = 86_400;

/// `#rrggbb` of a colour.
fn hex(c: Srgba) -> String {
    format!("#{:02x}{:02x}{:02x}", c.r, c.g, c.b)
}

#[test]
fn ramp_hits_its_stops_and_blends_between_them() {
    assert_eq!(hex(ramp(0.0)), "#2b6cb0");
    assert_eq!(hex(ramp(0.5)), "#e8e15b");
    assert_eq!(hex(ramp(1.0)), "#a3123a");
    // Clamped outside 0..1.
    assert_eq!(hex(ramp(-1.0)), "#2b6cb0");
    assert_eq!(hex(ramp(2.0)), "#a3123a");
    assert_eq!(hex(ramp(1.0 / 32.0)), "#2b79b4");
    assert_eq!(hex(ramp(0.3)), "#58cb94");
    assert_eq!(hex(ramp(0.7)), "#f2933a");
    assert_eq!(hex(ramp(0.9)), "#d13630");
}

#[test]
fn kinds_have_fixed_colours_and_unknown_ones_look_like_classes() {
    let order: Vec<&str> = KINDS.iter().map(|(k, _)| *k).collect();
    assert_eq!(order, ["class", "abstract", "interface", "enum", "record", "annotation", "struct", "trait", "union", "type", "module"]);
    assert_eq!(hex(kind_color("class")), "#6b8fc7");
    assert_eq!(hex(kind_color("trait")), "#26a69a");
    assert_eq!(hex(kind_color("module")), "#7c8ba1");
    assert_eq!(hex(kind_color("widget")), "#6b8fc7");
    assert_eq!((hex(rgb(OUT)), hex(rgb(IN)), hex(rgb(PR))), ("#f97316".into(), "#0ea5e9".into(), "#c084fc".into()));
}

#[test]
fn day_and_night_palettes() {
    let day = Theme::Day.palette();
    assert_eq!((hex(day.background), hex(day.ground), hex(day.dim)), ("#e8ecf1".into(), "#dfe5ec".into(), "#d5dbe3".into()));
    assert_eq!(day.districts, [0xc3ccd7, 0xcfd7e0, 0xd9dfe7, 0xe2e7ed, 0xeaeef2, 0xf0f3f6]);
    let night = Theme::Night.palette();
    assert_eq!((hex(night.background), hex(night.ground), hex(night.dim)), ("#04070d".into(), "#060b14".into(), "#0c1220".into()));
    assert_eq!(night.districts, [0x0e1626, 0x121c30, 0x16223a, 0x1a2844, 0x1e2e4e, 0x223458]);
    // Only the night glows.
    assert_eq!(day.glow, 0.0);
    assert!(night.glow > 0.0);
    assert_eq!(Theme::Day.other(), Theme::Night);
    assert_eq!(Theme::Night.other(), Theme::Day);
    assert_eq!((Theme::Day.name(), Theme::Night.name()), ("day", "night"));
}

#[test]
fn metric_menus_and_legend_words() {
    let heights: Vec<&str> = HeightMetric::ALL.iter().map(|m| m.label()).collect();
    assert_eq!(heights, ["Methods (NOM)", "Lines of code", "Complexity (WMC)", "Attributes (NOA)"]);
    let colours: Vec<&str> = ColorMetric::ALL.iter().map(|m| m.label()).collect();
    assert_eq!(colours, ["Churn (commits)", "Recency", "Authors", "Open pull requests", "Complexity (WMC)", "Complexity / method", "Lines of code", "Type kind"]);
    assert_eq!(ColorMetric::Age.words(), ("dormant", "just changed"));
    assert_eq!(ColorMetric::Density.words(), ("flat", "branchy"));
    assert_eq!(ColorMetric::Kind.words(), ("", ""));
}

#[test]
fn scale_clips_at_the_98th_percentile_on_a_log_ramp() {
    let s = Scale::over(ColorMetric::Cx, (0..=100).map(f64::from).collect());
    assert_eq!((s.lo, s.hi), (0.0, 98.0));
    assert_eq!(hex(s.color_of(0.0, "class")), "#2b6cb0");
    assert_eq!(hex(s.color_of(3.0, "class")), "#5acb93");
    assert_eq!(hex(s.color_of(98.0, "class")), "#a3123a");
    assert_eq!(hex(s.color_of(500.0, "class")), "#a3123a");
    assert_eq!(s.ends(), ("0".into(), "98+".into()));
    // No values, or only zeros: the top is at least 1. Infinite values are left out.
    let empty = Scale::over(ColorMetric::Loc, vec![]);
    assert_eq!((empty.lo, empty.hi), (0.0, 1.0));
    let zeros = Scale::over(ColorMetric::Commits, vec![0.0, 0.0, f64::INFINITY]);
    assert_eq!((zeros.lo, zeros.hi), (0.0, 1.0));
    // An infinite value is the hot end of a normal metric.
    assert_eq!(hex(zeros.color_of(f64::INFINITY, "class")), "#a3123a");
    let big = Scale::over(ColorMetric::Loc, vec![50.0, 12_345.0, 12_345.0]);
    assert_eq!(big.ends(), ("50".into(), "12.3k+".into()));
}

#[test]
fn density_is_linear_and_recency_inverted() {
    let d = Scale::over(ColorMetric::Density, vec![0.5, 2.5, 2.5]);
    assert_eq!((d.lo, d.hi), (0.5, 2.5));
    assert_eq!(hex(d.color_of(1.5, "class")), "#f3bc48");
    assert_eq!(d.ends(), ("0.5".into(), "2.5+".into()));
    assert_eq!(Scale::over(ColorMetric::Density, vec![]).hi, 1e-6);
    // Ages in days: a just-changed type is hot, an old one or one without history (infinite) cold.
    let a = Scale::over(ColorMetric::Age, vec![0.0, 3.0, 30.0, 365.0, f64::INFINITY]);
    assert_eq!((a.lo, a.hi), (0.0, 30.0));
    assert_eq!(hex(a.color_of(0.0, "class")), "#a3123a");
    assert_eq!(hex(a.color_of(3.0, "class")), "#f3be49");
    assert_eq!(hex(a.color_of(30.0, "class")), "#2b6cb0");
    assert_eq!(hex(a.color_of(365.0, "class")), "#2b6cb0");
    assert_eq!(hex(a.color_of(f64::INFINITY, "class")), "#2b6cb0");
    // The legend swaps: the oldest age on the left under "dormant".
    assert_eq!(a.ends(), ("30d ago+".into(), "0d ago".into()));
    // Type kind ignores the value.
    let k = Scale::over(ColorMetric::Kind, vec![]);
    assert_eq!(hex(k.color_of(0.0, "trait")), "#26a69a");
}

#[test]
fn fmt_signs_and_separators() {
    assert_eq!(fmt(-123.0), "-123");
    assert_eq!(fmt(-1234.0), "-1,234");
    assert_eq!(fmt(0.0), "0");
    assert_eq!(fmt(999.4), "999");
    assert_eq!(fmt(999.5), "1,000");
    assert_eq!(fmt(9876.0), "9,876");
    assert_eq!(fmt(10_000.0), "10.0k");
    assert_eq!(fmt(12_345.0), "12.3k");
    assert_eq!(fmt(99_999.0), "100.0k");
    assert_eq!(fmt(100_000.0), "100k");
    assert_eq!(fmt(1_234_567.0), "1235k");
    // Negative halves round up too (towards +∞).
    assert_eq!(fmt(-2.5), "-2");
}

#[test]
fn one_decimal_ties_round_half_up() {
    // Ties round up, also where n / 1000 is not exact in binary (10.35 is stored as 10.3499…).
    assert_eq!(fmt(12_250.0), "12.3k");
    assert_eq!(fmt(10_350.0), "10.4k");
    assert_eq!(fmt(12_249.0), "12.2k");
    assert_eq!(fmt(150_500.0), "151k");
    assert_eq!(ratio(5, 4), "1.3");
    assert_eq!(ratio(23, 20), "1.2");
    assert_eq!(ratio(1, 3), "0.3");
    assert_eq!(ratio(25, 8), "3.1");
    assert_eq!(one_decimal(1.25), "1.3");
    assert_eq!(one_decimal(2.5), "2.5");
    assert_eq!(one_decimal(-0.25), "-0.2");
    assert_eq!((tenths(0), tenths(-5), tenths(123)), ("0.0".to_string(), "-0.5".to_string(), "12.3".to_string()));
    // 1.25 years exactly.
    assert_eq!(rel_time_at(NOW - 456 * DAY - 6 * 3600, NOW), "1.3y ago");
    let d = Scale::over(ColorMetric::Density, vec![0.25, 1.25, 1.25]);
    assert_eq!(d.ends(), ("0.3".into(), "1.3+".into()));
}

#[test]
fn ages_relative_to_now() {
    assert_eq!(rel_time_at(0, NOW), "—");
    assert_eq!(rel_time_at(-5, NOW), "—");
    assert_eq!(rel_time_at(NOW - 3600, NOW), "today");
    // A date in the future counts as today.
    assert_eq!(rel_time_at(NOW + DAY, NOW), "today");
    assert_eq!(rel_time_at(NOW - 5 * DAY, NOW), "5d ago");
    assert_eq!(rel_time_at(NOW - 29 * DAY - 14 * 3600, NOW), "30d ago");
    assert_eq!(rel_time_at(NOW - 45 * DAY, NOW), "2mo ago");
    assert_eq!(rel_time_at(NOW - 364 * DAY, NOW), "12mo ago");
    assert_eq!(rel_time_at(NOW - 400 * DAY, NOW), "1.1y ago");
}

#[test]
fn web_links_are_https_only() {
    assert!(is_web_link("https://github.com/o/r/pull/1"));
    assert!(is_web_link("HTTPS://github.com/o/r"));
    for bad in ["javascript:alert(1)", "file:///etc/passwd", "http://x", "--help", "https://x y", "https://x\n", "ssh://h", "", "https:/", "https://x\u{7f}"] {
        assert!(!is_web_link(bad), "{bad:?}");
    }
}
