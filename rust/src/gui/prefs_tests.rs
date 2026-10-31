//! Tests of the settings file (`prefs.rs`): defaults, round trips, damaged files, and the examples in
//! `testdata/desktop-prefs/`, which the D edition reads and writes too, so both read the same file the same way and
//! write it byte for byte the same.

use super::look::{ColorMetric, HeightMetric, Theme};
use super::prefs::Prefs;
use std::path::{Path, PathBuf};

/// `testdata/desktop-prefs/<name>`.
fn example(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata/desktop-prefs").join(name)
}

/// Settings with every field given.
fn prefs(height: HeightMetric, color: Option<ColorMetric>, theme: Theme, [tests, labels, beacons, shadows]: [bool; 4]) -> Prefs {
    Prefs { height, color, theme, show_tests: tests, labels, beacons, shadows }
}

#[test]
fn defaults_on_first_start() {
    let d = Prefs::default();
    assert_eq!(d, prefs(HeightMetric::Nom, None, Theme::Day, [true; 4]));
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(Prefs::read(&dir.path().join("missing.json")), d);
    // The default settings as a file are the shared example, byte for byte.
    assert_eq!(d.json(), std::fs::read_to_string(example("defaults.json")).unwrap());
}

#[test]
fn reads_the_shared_examples() {
    let cases = [
        ("defaults.json", Prefs::default()),
        ("all-set.json", prefs(HeightMetric::Cx, Some(ColorMetric::Age), Theme::Night, [false, false, false, true])),
        // Unparsable, or not an object: all defaults.
        ("corrupt.json", Prefs::default()),
        ("not-an-object.json", Prefs::default()),
        // Each wrongly typed or unknown value falls back to its own default; the valid ones are kept.
        ("wrong-types.json", prefs(HeightMetric::Nom, None, Theme::Day, [true, true, true, false])),
        ("partial.json", prefs(HeightMetric::Noa, None, Theme::Day, [true, true, true, false])),
    ];
    for (name, expected) in cases {
        assert_eq!(Prefs::read(&example(name)), expected, "{name}");
    }
}

#[test]
fn writes_what_it_reads() {
    let text = std::fs::read_to_string(example("all-set.json")).unwrap();
    assert_eq!(Prefs::parse(&text).json(), text);
    // Every metric and theme survives a round trip through a file, which is created with its directory.
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config/codecity/desktop.json");
    for (k, &height) in HeightMetric::ALL.iter().enumerate() {
        for color in ColorMetric::ALL.into_iter().map(Some).chain([None]) {
            let theme = if k % 2 == 0 { Theme::Day } else { Theme::Night };
            let p = prefs(height, color, theme, [k == 0, k == 1, k == 2, k == 3]);
            p.write(&path);
            assert_eq!(Prefs::read(&path), p);
        }
    }
}

#[test]
fn an_unwritable_file_is_ignored() {
    let dir = tempfile::tempdir().unwrap();
    // The path is a directory: writing fails silently and reading gives the defaults.
    Prefs::default().write(dir.path());
    assert!(dir.path().is_dir());
    assert_eq!(Prefs::read(dir.path()), Prefs::default());
}

#[test]
fn file_location_follows_xdg_then_home() {
    let p = |xdg: Option<&str>, home: Option<&str>| Prefs::path_in(xdg.map(Into::into), home.map(Into::into));
    assert_eq!(p(Some("/x"), Some("/h")), Path::new("/x/codecity/desktop.json"));
    assert_eq!(p(Some(""), Some("/h")), Path::new("/h/.config/codecity/desktop.json"));
    assert_eq!(p(None, Some("/h")), Path::new("/h/.config/codecity/desktop.json"));
    assert_eq!(p(None, None), Path::new(".config/codecity/desktop.json"));
}
