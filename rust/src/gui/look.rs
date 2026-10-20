//! Palettes, themes and metric definitions shared with the other viewers.
//!
//! The colour ramp, kind colours, metric definitions and the [`Scale`] rules are the same as in the web viewer
//! (`src/main/resources/web/app.js`) and in the Java and D desktop viewers (`Look.java`, `look.d`), so a city looks
//! the same in every viewer. Everything here is plain data and pure functions; `gui/mod.rs` decides when to apply
//! them and `scene.rs` turns the resulting colours into instance colours.
//!
//! Colours are written as `0xRRGGBB` sRGB integers and converted with [`rgb`]. All blending ([`mix`], [`ramp`])
//! interpolates the sRGB byte values directly, not linear light.

use crate::city::{Building, CityModel};
use crate::model::TypeInfo;
use three_d::Srgba;

/// The metric colour ramp, low to high: blue → teal → green → yellow → orange → red → crimson. [`ramp`]
/// interpolates between neighbouring stops.
pub const RAMP: [u32; 9] = [0x2b6cb0, 0x2a9fc0, 0x34c3a5, 0x8fd67a, 0xe8e15b, 0xf6b343, 0xef7d34, 0xdc3f2e, 0xa3123a];
/// Building colour per type kind (the "Type kind" colour metric and the kind badges). An unknown kind falls back
/// to the first entry ([`kind_color`]).
pub const KINDS: [(&str, u32); 11] = [
    ("class", 0x6b8fc7),
    ("abstract", 0x9a86d8),
    ("interface", 0x2fb7a5),
    ("enum", 0xe3a63b),
    ("record", 0xd46fa8),
    ("annotation", 0x8a9aaa),
    ("struct", 0x4f86c6),
    ("trait", 0x26a69a),
    ("union", 0xc98a3a),
    ("type", 0xb7a3e0),
    ("module", 0x7c8ba1),
];
/// Orange: dependencies of the selected type (what it uses).
pub const OUT: u32 = 0xf97316;
/// Blue: dependants of the selected type (what uses it).
pub const IN: u32 = 0x0ea5e9;
/// Purple: open pull requests (beacons and PR lists).
pub const PR: u32 = 0xc084fc;

pub fn rgb(hex: u32) -> Srgba {
    Srgba::new_opaque((hex >> 16) as u8, (hex >> 8) as u8, hex as u8)
}

pub fn egui_rgb(c: Srgba) -> three_d::egui::Color32 {
    three_d::egui::Color32::from_rgb(c.r, c.g, c.b)
}

/// Linear interpolation from `a` (`t = 0`) to `b` (`t = 1`), per sRGB channel, rounded. The result is always fully
/// opaque, whatever the inputs' alpha.
pub fn mix(a: Srgba, b: Srgba, t: f32) -> Srgba {
    let l = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round().clamp(0.0, 255.0) as u8;
    Srgba::new(l(a.r, b.r), l(a.g, b.g), l(a.b, b.b), 255)
}

/// Colour at position `t` on [`RAMP`]; `t` is clamped to `[0, 1]`.
///
/// The 9 stops are spread evenly, so `t` is scaled to `[0, 8]`: the integer part picks the segment and the fraction
/// the blend within it. The segment index is capped at 7 so that `t = 1` lands at the end of the last segment
/// instead of reading past the array.
pub fn ramp(t: f64) -> Srgba {
    let x = t.clamp(0.0, 1.0) * (RAMP.len() - 1) as f64;
    let i = (x.floor() as usize).min(RAMP.len() - 2);
    mix(rgb(RAMP[i]), rgb(RAMP[i + 1]), (x - i as f64) as f32)
}

pub fn kind_color(kind: &str) -> Srgba {
    rgb(KINDS.iter().find(|(k, _)| *k == kind).map(|(_, c)| *c).unwrap_or(KINDS[0].1))
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Theme {
    Day,
    Night,
}

/// Every colour and light setting that depends on the theme.
pub struct Palette {
    /// Clear colour behind the city.
    pub background: Srgba,
    pub ground: Srgba,
    /// Platform colour by nesting depth (index 0 is the city itself); deeper districts reuse the last one.
    pub districts: [u32; 6],
    /// Colour and intensity of the ambient light.
    pub ambient: (Srgba, f32),
    /// Colour and intensity of the directional sun.
    pub sun: (Srgba, f32),
    /// how strongly buildings emit their own colour / lit windows
    pub glow: f32,
    pub windows: f32,
    /// What buildings are faded towards when they do not match the search or are unrelated to the selection.
    pub dim: Srgba,
}

impl Theme {
    /// The palette for this theme. In both, platforms get lighter with nesting depth. Only the night theme turns on
    /// the building glow and lit windows (see `material.rs`).
    pub fn palette(self) -> Palette {
        match self {
            Theme::Day => Palette {
                background: rgb(0xe8ecf1),
                ground: rgb(0xdfe5ec),
                districts: [0xc3ccd7, 0xcfd7e0, 0xd9dfe7, 0xe2e7ed, 0xeaeef2, 0xf0f3f6],
                ambient: (rgb(0xffffff), 0.55),
                sun: (rgb(0xfff4e5), 2.2),
                glow: 0.0,
                windows: 0.0,
                dim: rgb(0xd5dbe3),
            },
            Theme::Night => Palette {
                background: rgb(0x04070d),
                ground: rgb(0x060b14),
                districts: [0x0e1626, 0x121c30, 0x16223a, 0x1a2844, 0x1e2e4e, 0x223458],
                ambient: (rgb(0x5a6fa8), 0.25),
                sun: (rgb(0x9db4ff), 0.6),
                glow: 0.28,
                windows: 1.4,
                dim: rgb(0x0c1220),
            },
        }
    }

    pub fn other(self) -> Theme {
        match self {
            Theme::Day => Theme::Night,
            Theme::Night => Theme::Day,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Theme::Day => "day",
            Theme::Night => "night",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
/// What a building's height shows. The app maps the value to a height; this only defines the metric.
pub enum HeightMetric {
    Nom,
    Loc,
    Cx,
    Noa,
}

impl HeightMetric {
    pub const ALL: [HeightMetric; 4] = [HeightMetric::Nom, HeightMetric::Loc, HeightMetric::Cx, HeightMetric::Noa];

    pub fn label(self) -> &'static str {
        match self {
            HeightMetric::Nom => "Methods (NOM)",
            HeightMetric::Loc => "Lines of code",
            HeightMetric::Cx => "Complexity (WMC)",
            HeightMetric::Noa => "Attributes (NOA)",
        }
    }

    pub fn value(self, t: &TypeInfo) -> f64 {
        (match self {
            HeightMetric::Nom => t.nom(),
            HeightMetric::Loc => t.loc,
            HeightMetric::Cx => t.complexity(),
            HeightMetric::Noa => t.noa,
        }) as f64
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
/// What a building's colour shows. Churn, recency and authors need git history, open pull requests need GitHub
/// (see [`ColorMetric::unavailable`]).
pub enum ColorMetric {
    Commits,
    Age,
    Authors,
    Prs,
    Cx,
    Density,
    Loc,
    Kind,
}

impl ColorMetric {
    pub const ALL: [ColorMetric; 8] = [
        ColorMetric::Commits,
        ColorMetric::Age,
        ColorMetric::Authors,
        ColorMetric::Prs,
        ColorMetric::Cx,
        ColorMetric::Density,
        ColorMetric::Loc,
        ColorMetric::Kind,
    ];

    pub fn label(self) -> &'static str {
        match self {
            ColorMetric::Commits => "Churn (commits)",
            ColorMetric::Age => "Recency",
            ColorMetric::Authors => "Authors",
            ColorMetric::Prs => "Open pull requests",
            ColorMetric::Cx => "Complexity (WMC)",
            ColorMetric::Density => "Complexity / method",
            ColorMetric::Loc => "Lines of code",
            ColorMetric::Kind => "Type kind",
        }
    }

    /// (low, high) legend words
    pub fn words(self) -> (&'static str, &'static str) {
        match self {
            ColorMetric::Commits => ("stable", "hot spot"),
            ColorMetric::Age => ("dormant", "just changed"),
            ColorMetric::Authors => ("one owner", "many hands"),
            ColorMetric::Prs => ("none", "contested"),
            ColorMetric::Cx => ("simple", "complex"),
            ColorMetric::Density => ("flat", "branchy"),
            ColorMetric::Loc => ("small", "large"),
            ColorMetric::Kind => ("", ""),
        }
    }

    /// None when available, otherwise why not.
    pub fn unavailable(self, model: &CityModel) -> Option<&'static str> {
        match self {
            ColorMetric::Commits | ColorMetric::Age | ColorMetric::Authors if !model.has_git() => Some(" (no git)"),
            ColorMetric::Prs if !model.has_pulls() => Some(" (no GitHub)"),
            _ => None,
        }
    }

    /// Whether the scale is linear rather than logarithmic. Only complexity per method, a ratio rather than a count.
    fn linear(self) -> bool {
        self == ColorMetric::Density
    }

    /// Whether low values are hot. Only for recency: the value is the age in days, and a recently changed type
    /// should be red, a dormant one blue.
    fn invert(self) -> bool {
        self == ColorMetric::Age
    }

    /// The raw metric value of one building, before scaling.
    ///
    /// Without git history the counts are 0, and the age is infinite: a type with no recorded commit is treated as
    /// the most dormant one. [`Scale`] keeps infinite values out of its range and colours them at the cold end.
    pub fn value(self, t: &TypeInfo, b: &Building) -> f64 {
        let h = b.history;
        match self {
            ColorMetric::Commits => h.map(|h| h.commits as f64).unwrap_or(0.0),
            // Days since the last commit; clamped at 0 in case a commit is dated in the future.
            ColorMetric::Age => h.map(|h| ((now() - h.last_commit) as f64 / 86400.0).max(0.0)).unwrap_or(f64::INFINITY),
            ColorMetric::Authors => h.map(|h| h.authors as f64).unwrap_or(0.0),
            ColorMetric::Prs => b.prs.len() as f64,
            ColorMetric::Cx => t.complexity() as f64,
            ColorMetric::Density => if t.nom() == 0 { 0.0 } else { t.complexity() as f64 / t.nom() as f64 },
            ColorMetric::Loc => t.loc as f64,
            ColorMetric::Kind => 0.0,
        }
    }
}

/// Colour scale for a metric: log-scaled and clipped at the 98th percentile.
///
/// The range is computed over the current model's present-day buildings (not the gone ones of a history replay, which
/// keeps this scale during playback). `hi` is the 98th percentile rather than the maximum,
/// so a few extreme outliers do not push every other building into the bottom of the ramp; everything above `hi`
/// gets the top colour. Colours always start at 0, not at `lo`: `lo` (the smallest finite value) is only shown at
/// the left end of the legend. The Java and D viewers compute the same scale.
pub struct Scale {
    pub metric: ColorMetric,
    pub lo: f64,
    pub hi: f64,
}

impl Scale {
    /// Measures the metric over all buildings of `model`.
    pub fn of(metric: ColorMetric, model: &CityModel) -> Scale {
        let mut finite: Vec<f64> = model
            .buildings
            .iter()
            .filter(|b| !b.gone)
            .map(|b| metric.value(&model.types()[b.index], b))
            .filter(|v| v.is_finite())
            .collect();
        finite.sort_by(f64::total_cmp);
        // Nearest-rank percentile, rounding the rank down.
        let p98 = if finite.is_empty() { 0.0 } else { finite[((0.98 * (finite.len() - 1) as f64) as usize).min(finite.len() - 1)] };
        // `hi` must not be 0, as `color` divides by it (or by ln(1 + hi)). Log metrics get at least 1, so their scale
        // always spans at least 0..1.
        let hi = p98.max(if metric.linear() { 1e-6 } else { 1.0 });
        let lo = finite.first().copied().unwrap_or(0.0).min(hi);
        Scale { metric, lo, hi }
    }

    /// The colour of one building. Log metrics use `ln(1 + v) / ln(1 + hi)`, which handles 0 and keeps small
    /// counts apart; linear metrics use `v / hi`. Results above 1 are clamped by [`ramp`].
    pub fn color(&self, t: &TypeInfo, b: &Building) -> Srgba {
        self.color_of(self.metric.value(t, b), t.kind)
    }

    /// The colour for a raw metric value `v` (see [`ColorMetric::value`]) of a type of kind `kind`; the history
    /// replay passes a snapshot's value here.
    pub fn color_of(&self, v: f64, kind: &str) -> Srgba {
        if self.metric == ColorMetric::Kind {
            return kind_color(kind);
        }
        // An infinite value (only the age of a type without history) goes to the cold end of the ramp after
        // inversion: 0 for recency, 1 for anything else.
        let mut x = if !v.is_finite() {
            if self.metric.invert() { 0.0 } else { 1.0 }
        } else if self.metric.linear() {
            v / self.hi
        } else {
            v.ln_1p() / self.hi.ln_1p()
        };
        if self.metric.invert() && v.is_finite() {
            x = 1.0 - x;
        }
        ramp(x)
    }

    /// A metric value formatted for the legend.
    pub fn show(&self, v: f64) -> String {
        if self.metric == ColorMetric::Age {
            format!("{}d ago", v.round())
        } else if v == v.trunc() {
            fmt(v)
        } else {
            format!("{v:.1}")
        }
    }
}

/// Current time in seconds since the Unix epoch (0 if the clock is before 1970).
pub fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Compact number for labels and chips: rounded with thousands separators (`9,876`) below 10,000, `12.3k` below
/// 100,000, `123k` above.
pub fn fmt(n: f64) -> String {
    if n >= 100_000.0 {
        format!("{}k", (n / 1000.0).round())
    } else if n >= 10_000.0 {
        format!("{:.1}k", n / 1000.0)
    } else {
        let v = n.round() as i64;
        let s = v.unsigned_abs().to_string();
        // thousands separators, counted on the digits only so a minus sign does not shift them (`-1,234`)
        let bytes = s.as_bytes();
        let mut out = String::from(if v < 0 { "-" } else { "" });
        for (i, c) in bytes.iter().enumerate() {
            if i > 0 && (bytes.len() - i) % 3 == 0 {
                out.push(',');
            }
            out.push(*c as char);
        }
        out
    }
}

/// Relative age of a Unix timestamp: "today", "12d ago", "5mo ago" (30-day months) or "1.5y ago".
pub fn rel_time(epoch: i64) -> String {
    // 0 or less means "no date known".
    if epoch <= 0 {
        return "—".into();
    }
    let days = (now() - epoch) as f64 / 86400.0;
    if days < 1.0 {
        "today".into()
    } else if days < 30.0 {
        format!("{}d ago", days.round())
    } else if days < 365.0 {
        format!("{}mo ago", (days / 30.0).round())
    } else {
        format!("{:.1}y ago", days / 365.0)
    }
}

/// Whether `url` may be handed to the system browser: an `https://` URL without spaces or control characters. Link
/// targets come from the city data (pull request URLs from the GitHub API, the repository URL from the analysed tree's
/// git remote), so anything else (`file:`, `javascript:`, custom URL schemes that start other applications,
/// option-like strings) is refused.
pub fn is_web_link(url: &str) -> bool {
    url.get(..8).is_some_and(|s| s.eq_ignore_ascii_case("https://")) && !url.chars().any(|c| c <= ' ' || c == '\x7f')
}

#[cfg(test)]
mod tests {
    #[test]
    fn web_links_are_https_only() {
        assert!(super::is_web_link("https://github.com/o/r/pull/1"));
        assert!(super::is_web_link("HTTPS://github.com/o/r"));
        for bad in ["javascript:alert(1)", "file:///etc/passwd", "http://x", "--help", "https://x y", "https://x\n", "ssh://h", "", "https:/"] {
            assert!(!super::is_web_link(bad), "{bad:?}");
        }
    }

    #[test]
    fn fmt_signs_and_separators() {
        assert_eq!(super::fmt(-123.0), "-123");
        assert_eq!(super::fmt(-1234.0), "-1,234");
        assert_eq!(super::fmt(9876.0), "9,876");
        assert_eq!(super::fmt(12_345.0), "12.3k");
    }
}
