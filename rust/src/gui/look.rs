//! Palettes, themes and metric definitions shared with the other viewers.

use crate::city::{Building, CityModel};
use crate::model::TypeInfo;
use three_d::Srgba;

pub const RAMP: [u32; 9] = [0x2b6cb0, 0x2a9fc0, 0x34c3a5, 0x8fd67a, 0xe8e15b, 0xf6b343, 0xef7d34, 0xdc3f2e, 0xa3123a];
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
pub const OUT: u32 = 0xf97316;
pub const IN: u32 = 0x0ea5e9;
pub const PR: u32 = 0xc084fc;

pub fn rgb(hex: u32) -> Srgba {
    Srgba::new_opaque((hex >> 16) as u8, (hex >> 8) as u8, hex as u8)
}

pub fn egui_rgb(c: Srgba) -> three_d::egui::Color32 {
    three_d::egui::Color32::from_rgb(c.r, c.g, c.b)
}

pub fn mix(a: Srgba, b: Srgba, t: f32) -> Srgba {
    let l = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round().clamp(0.0, 255.0) as u8;
    Srgba::new(l(a.r, b.r), l(a.g, b.g), l(a.b, b.b), 255)
}

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

pub struct Palette {
    pub background: Srgba,
    pub ground: Srgba,
    pub districts: [u32; 6],
    pub ambient: (Srgba, f32),
    pub sun: (Srgba, f32),
    /// how strongly buildings emit their own colour / lit windows
    pub glow: f32,
    pub windows: f32,
    pub dim: Srgba,
}

impl Theme {
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

    fn linear(self) -> bool {
        self == ColorMetric::Density
    }

    fn invert(self) -> bool {
        self == ColorMetric::Age
    }

    pub fn value(self, t: &TypeInfo, b: &Building) -> f64 {
        let h = b.history;
        match self {
            ColorMetric::Commits => h.map(|h| h.commits as f64).unwrap_or(0.0),
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
pub struct Scale {
    pub metric: ColorMetric,
    pub lo: f64,
    pub hi: f64,
}

impl Scale {
    pub fn of(metric: ColorMetric, model: &CityModel) -> Scale {
        let mut finite: Vec<f64> = model
            .buildings
            .iter()
            .map(|b| metric.value(&model.types()[b.index], b))
            .filter(|v| v.is_finite())
            .collect();
        finite.sort_by(f64::total_cmp);
        let p98 = if finite.is_empty() { 0.0 } else { finite[((0.98 * (finite.len() - 1) as f64) as usize).min(finite.len() - 1)] };
        let hi = p98.max(if metric.linear() { 1e-6 } else { 1.0 });
        let lo = finite.first().copied().unwrap_or(0.0).min(hi);
        Scale { metric, lo, hi }
    }

    pub fn color(&self, t: &TypeInfo, b: &Building) -> Srgba {
        if self.metric == ColorMetric::Kind {
            return kind_color(t.kind);
        }
        let v = self.metric.value(t, b);
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

pub fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

pub fn fmt(n: f64) -> String {
    if n >= 100_000.0 {
        format!("{}k", (n / 1000.0).round())
    } else if n >= 10_000.0 {
        format!("{:.1}k", n / 1000.0)
    } else {
        let s = format!("{}", n.round() as i64);
        // thousands separators
        let bytes = s.as_bytes();
        let mut out = String::new();
        for (i, c) in bytes.iter().enumerate() {
            if i > 0 && (bytes.len() - i) % 3 == 0 && *c != b'-' {
                out.push(',');
            }
            out.push(*c as char);
        }
        out
    }
}

pub fn rel_time(epoch: i64) -> String {
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
