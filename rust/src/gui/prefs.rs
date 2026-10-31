//! View settings that survive restarts, stored as JSON in `$XDG_CONFIG_HOME/codecity/desktop.json` (or
//! `~/.config/codecity/desktop.json`). The viewer saves them whenever the user changes one.
//!
//! The D desktop viewer (`d/source/codecity/gui/prefs.d`) reads and writes the same file with the same keys and value
//! names, so a choice made in one carries over to the other; both editions' tests read the shared examples in
//! `testdata/desktop-prefs/` and check that they write byte-identical files.
//!
//! The file is one JSON object: `height` and `color` hold the metric's enum variant name (`"Nom"`, `"Commits"`, …;
//! `color` is `null` until the user picks one), `theme` is `"day"` or `"night"`, and `tests`, `labels`, `beacons` and
//! `shadows` are booleans. Keys are written in sorted order without spaces.

use super::look::{ColorMetric, HeightMetric, Theme};
use std::ffi::OsString;
use std::path::{Path, PathBuf};

/// The desktop viewer's settings.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Prefs {
    pub height: HeightMetric,
    /// The colour metric the user last picked. `None` means "never chosen": the app then picks a default that suits
    /// the model (churn with git history, complexity without; see `rules::effective_color`).
    pub color: Option<ColorMetric>,
    pub theme: Theme,
    pub show_tests: bool,
    pub labels: bool,
    pub beacons: bool,
    pub shadows: bool,
}

impl Default for Prefs {
    /// What a first start (or an unreadable file) gives: method-count heights, no colour chosen yet, day, and test
    /// code, labels, PR beacons and shadows all shown.
    fn default() -> Prefs {
        Prefs { height: HeightMetric::Nom, color: None, theme: Theme::Day, show_tests: true, labels: true, beacons: true, shadows: true }
    }
}

impl Prefs {
    /// Where the settings live, from the environment (see [`Prefs::path_in`]).
    pub fn path() -> PathBuf {
        Self::path_in(std::env::var_os("XDG_CONFIG_HOME"), std::env::var_os("HOME"))
    }

    /// `<xdg>/codecity/desktop.json` when `xdg` (`XDG_CONFIG_HOME`) is set and not empty, else
    /// `<home>/.config/codecity/desktop.json` (a relative `.config/…` without a home either).
    pub fn path_in(xdg: Option<OsString>, home: Option<OsString>) -> PathBuf {
        let base = xdg.filter(|v| !v.is_empty()).map(PathBuf::from).unwrap_or_else(|| PathBuf::from(home.unwrap_or_default()).join(".config"));
        base.join("codecity").join("desktop.json")
    }

    /// Reads the settings file.
    pub fn load() -> Prefs {
        Self::read(&Self::path())
    }

    /// Reads the settings from `path`. A missing or malformed file, or a missing, unknown or wrongly typed value,
    /// silently falls back to the default for that setting, so old or hand-edited files never stop the viewer from
    /// starting.
    pub fn read(path: &Path) -> Prefs {
        Self::parse(&std::fs::read_to_string(path).unwrap_or_default())
    }

    /// The settings in the JSON text `text`, by the rules of [`Prefs::read`].
    pub fn parse(text: &str) -> Prefs {
        // Unparsable text gives Value::Null, and indexing Null (or anything but an object) yields Null, so every
        // lookup below falls through to its default.
        let v: serde_json::Value = serde_json::from_str(text).unwrap_or_default();
        let s = |k: &str| v[k].as_str().unwrap_or("").to_string();
        let b = |k: &str, d: bool| v[k].as_bool().unwrap_or(d);
        let d = Prefs::default();
        Prefs {
            // Metrics are stored by their Debug name ("Nom", "Commits", …), matching what save() writes.
            height: HeightMetric::ALL.into_iter().find(|m| format!("{m:?}") == s("height")).unwrap_or(d.height),
            color: ColorMetric::ALL.into_iter().find(|m| format!("{m:?}") == s("color")),
            theme: if s("theme") == "night" { Theme::Night } else { Theme::Day },
            show_tests: b("tests", d.show_tests),
            labels: b("labels", d.labels),
            beacons: b("beacons", d.beacons),
            shadows: b("shadows", d.shadows),
        }
    }

    /// The settings as the file's JSON text.
    pub fn json(&self) -> String {
        serde_json::json!({
            "height": format!("{:?}", self.height),
            "color": self.color.map(|c| format!("{c:?}")),
            "theme": self.theme.name(),
            "tests": self.show_tests,
            "labels": self.labels,
            "beacons": self.beacons,
            "shadows": self.shadows,
        })
        .to_string()
    }

    /// Writes the settings file.
    pub fn save(&self) {
        self.write(&Self::path());
    }

    /// Writes the settings to `path`, creating its directory if needed. Errors are ignored: losing a view preference
    /// is not worth interrupting the user for.
    pub fn write(&self, path: &Path) {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(path, self.json());
    }
}

