//! Tests of the viewer rules in `rules.rs`: search ranking, heights, shading, district labels, dependency arcs, the
//! inspector's and tooltip's texts, GitHub links and walk-mode start and exit.
//!
//! Most scenarios run on the city of `testdata/java-project` (built without git, like its golden file, so its
//! indices and numbers are fixed). The Java (`ViewRulesTest`) and D (`rules.d`) editions test the same scenarios with
//! the same expected values.

use super::look::{self, ColorMetric};
use super::rules::*;
use super::walk;
use crate::analysis::SourceAnalyzer;
use crate::city::{Building, CityModel};
use crate::github::{GitHubInfo, GitHubRepo, PullRequest};
use crate::layout::{District, Lot};
use crate::model::FileHistory;
use std::path::Path;
use three_d::{vec3, MetricSpace, Srgba};

/// 2026-09-30T12:00:00Z, the "now" of every age in these tests.
const NOW: i64 = 1_790_769_600;
const DAY: i64 = 86_400;

/// Building indices in the java-project city (see `testdata/golden/java-project.json`).
const MAIN: usize = 4;
const MAIN_TEST: usize = 5;
const REGISTRY: usize = 11;

/// The city of `testdata/<name>`, with test code, without git and GitHub.
fn fixture(name: &str) -> CityModel {
    let root = std::fs::canonicalize(Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata").join(name)).unwrap();
    CityModel::build(&SourceAnalyzer::new(), &root, true, false, None, None)
}

/// `#rrggbb` of a colour.
fn hex(c: Srgba) -> String {
    format!("#{:02x}{:02x}{:02x}", c.r, c.g, c.b)
}

fn close(a: f64, b: f64) -> bool {
    (a - b).abs() < 1e-3
}

// ------------------------------------------------------------------ search

#[test]
fn search_ranks_exact_then_prefix_then_substring_then_qualified_name() {
    let m = fixture("java-project");
    let all = |_| true;
    // Registry is exact; the nested Registry.* types are prefixes, shortest first, ties in model order.
    assert_eq!(search("registry", m.types(), all), Some(vec![11, 16, 14, 17, 12, 15, 13]));
    // Trimmed and case-insensitive.
    assert_eq!(search("  REG ", m.types(), all), Some(vec![11, 16, 14, 17, 12, 15, 13]));
    // Strings is a prefix match; the rest contain "st" in their simple names, shorter first.
    assert_eq!(search("st", m.types(), all), Some(vec![19, 5, 11, 9, 16, 14, 17, 12, 15, 13]));
    // Only the fully qualified name matches.
    assert_eq!(search("acme.core.util", m.types(), all), Some(vec![19]));
    assert_eq!(search("zzz", m.types(), all), Some(vec![]));
}

#[test]
fn search_needs_two_characters_and_skips_hidden_types() {
    let m = fixture("java-project");
    assert_eq!(search("x", m.types(), |_| true), None);
    assert_eq!(search("  x  ", m.types(), |_| true), None);
    assert_eq!(search("", m.types(), |_| true), None);
    // One character outside the BMP is two UTF-16 units, as in the browser: a search, with no hits.
    assert_eq!(search("😀", m.types(), |_| true), Some(vec![]));
    // "é" is one unit (two UTF-8 bytes): still too short.
    assert_eq!(search("é", m.types(), |_| true), None);
    assert_eq!(search("test", m.types(), |_| true), Some(vec![MAIN_TEST, 9]));
    assert_eq!(search("test", m.types(), |i| !m.types()[i].test), Some(vec![]));
}

#[test]
fn search_ties_are_ordered_by_utf16_length() {
    // "Äb" sorts before "Abc" (2 against 3 UTF-16 units, although both are 3 bytes in UTF-8).
    let mut m = fixture("java-project");
    m.analysis.types[0].name = "Abc".into();
    m.analysis.types[1].name = "Äb".into();
    assert_eq!(search("b", m.types(), |i| i < 2), None);
    let names = |i: usize| i < 2;
    assert_eq!(utf16_len("Äb"), 2);
    assert_eq!(utf16_len("😀"), 2);
    assert_eq!(search("bc", m.types(), names), Some(vec![0]));
    m.analysis.types[1].name = "Äbc".into();
    m.analysis.types[0].name = "Abcd".into();
    assert_eq!(search("bc", m.types(), names), Some(vec![1, 0]));
}

// ------------------------------------------------------------------ heights

#[test]
fn heights_scale_the_95th_percentile_to_a_fifth_of_the_radius() {
    assert_eq!(radius(17.131, 27.531), 20.0);
    assert_eq!(radius(60.0, 80.0), 50.0);
    // No values or only zeros: the percentile counts as 1.
    assert_eq!(height_scale(vec![], 20.0), 8.0);
    assert_eq!(height_scale(vec![0.0, 0.0, 0.0], 20.0), 8.0);
    // 0..=100: the nearest-rank 95th percentile is 95; a fifth of radius 50 is 10.
    let hundred: Vec<f64> = (0..=100).map(f64::from).collect();
    assert!(close(height_scale(hundred.clone(), 50.0), 10.0 / 95.0));
    // The fifth of the radius is kept within 8..16.
    assert!(close(height_scale(hundred.clone(), 10.0), 8.0 / 95.0));
    assert!(close(height_scale(hundred, 200.0), 16.0 / 95.0));
    // Unsorted input is fine.
    assert_eq!(height_scale(vec![4.0, 1.0, 2.0], 20.0), 4.0);
    // A 0.35 base and a cap at 75% of the radius.
    assert_eq!(height(0.0, 2.0, 20.0), 0.35);
    assert_eq!(height(3.0, 2.0, 20.0), 6.35);
    assert_eq!(height(1e9, 1.0, 20.0), 15.0);
}

#[test]
fn fixture_heights_follow_the_method_count() {
    let m = fixture("java-project");
    let r = radius(m.width, m.depth);
    let s = height_scale(m.types().iter().map(|t| t.nom() as f64).collect(), r);
    assert_eq!(s, 2.0);
    assert_eq!(height(m.types()[0].nom() as f64, s, r), 2.35);
    // Registry (8 methods) would be 16.35: capped at 15.
    assert_eq!(height(m.types()[REGISTRY].nom() as f64, s, r), 15.0);
}

// ------------------------------------------------------------------ colours

#[test]
fn colour_metric_falls_back_when_the_model_lacks_its_data() {
    assert_eq!(effective_color(None, true, false), ColorMetric::Commits);
    assert_eq!(effective_color(None, false, false), ColorMetric::Cx);
    assert_eq!(effective_color(Some(ColorMetric::Loc), false, false), ColorMetric::Loc);
    assert_eq!(effective_color(Some(ColorMetric::Age), true, false), ColorMetric::Age);
    assert_eq!(effective_color(Some(ColorMetric::Age), false, true), ColorMetric::Cx);
    assert_eq!(effective_color(Some(ColorMetric::Prs), true, false), ColorMetric::Commits);
    assert_eq!(effective_color(Some(ColorMetric::Prs), false, true), ColorMetric::Prs);
    assert_eq!(ColorMetric::Authors.missing(false, true), Some(" (no git)"));
    assert_eq!(ColorMetric::Prs.missing(true, false), Some(" (no GitHub)"));
    assert_eq!(ColorMetric::Kind.missing(false, false), None);
}

#[test]
fn selection_dims_everything_but_its_neighbours() {
    let m = fixture("java-project");
    let sel = &m.buildings[REGISTRY];
    // Registry uses 14, 15, 16, 18 and is used by 4, 5, 15.
    let related_set: Vec<usize> = (0..m.buildings.len()).filter(|&i| related(Some(sel), i)).collect();
    assert_eq!(related_set, vec![4, 5, 11, 14, 15, 16, 18]);
    assert!((0..m.buildings.len()).all(|i| related(None, i)));
}

#[test]
fn shading_dims_then_lightens_the_hovered_building() {
    let base = look::rgb(0x2b6cb0);
    let dim = look::rgb(0xd5dbe3);
    assert_eq!(hex(shade(base, dim, false, false, false)), "#2b6cb0");
    assert_eq!(hex(shade(base, dim, true, false, false)), "#b3c5d9");
    assert_eq!(hex(shade(base, dim, false, true, false)), "#b3c5d9");
    assert_eq!(hex(shade(base, dim, true, true, false)), "#b3c5d9");
    assert_eq!(hex(shade(base, dim, false, false, true)), "#6b98c8");
    assert_eq!(hex(shade(base, dim, true, false, true)), "#cad6e4");
}

// ------------------------------------------------------------------ districts and labels

/// A district for the label scenarios.
fn district(label: &str, depth: u32, x: f64, w: f64, types: u32) -> District {
    District { id: label.into(), label: label.into(), depth, x, z: 0.0, w, d: 2.0, types }
}

#[test]
fn labels_go_to_the_largest_uncrowded_shallow_districts() {
    // Not candidates, although the largest: the city itself, a depth-3 district and an empty one.
    let mut ds = vec![district("city", 0, 0.0, 100.0, 50), district("deep", 3, 0.0, 50.0, 1), district("empty", 1, 0.0, 40.0, 0)];
    // 30 candidates, alternately at depth 1 and 2, 10 apart and smaller and smaller; the second is crowded (5 from the
    // first, at the same depth position).
    for k in 0..30 {
        let x = if k == 1 { 5.0 } else { k as f64 * 10.0 };
        ds.push(district(&format!("p{k}"), 1 + k % 2, x, 30.0 - k as f64 * 0.5, 1));
    }
    let labels = labels(&ds);
    let kept: Vec<usize> = labels.iter().map(|l| l.district).collect();
    let expected: Vec<usize> = std::iter::once(3).chain(5..=19).collect();
    assert_eq!(kept, expected);
    assert_eq!(labels.len(), MAX_LABELS);
    // Anchored at the front edge (z + d/2), 0.2 above the platform top ((depth + 1) * 0.4).
    assert_eq!(labels[0].text, "p0");
    assert!(close(labels[0].at[0], 0.0) && close(labels[0].at[1], 0.8 + 0.2) && close(labels[0].at[2], 1.0));
    assert!(close(labels[2].at[0], 30.0) && close(labels[2].at[1], 1.2 + 0.2));
}

#[test]
fn labels_consider_only_the_24_largest_candidates() {
    // 30 uncrowded candidates whose 16 largest come last: only the 24 largest are considered, so the 16 labels are
    // the 24 largest's first 16 (by area).
    let ds: Vec<District> = (0..30).map(|k| district(&format!("p{k}"), 1, k as f64 * 10.0, 1.0 + k as f64, 1)).collect();
    let kept: Vec<usize> = labels(&ds).iter().map(|l| l.district).collect();
    assert_eq!(kept, (14..30).rev().collect::<Vec<_>>());
    // Crowding is checked against the labels kept, not the candidates skipped.
    let crowded = vec![district("a", 1, 0.0, 10.0, 1), district("b", 1, 7.0, 9.0, 1), district("c", 1, 14.0, 8.0, 1)];
    let kept: Vec<usize> = labels(&crowded).iter().map(|l| l.district).collect();
    assert_eq!(kept, vec![0, 2]);
}

#[test]
fn fixture_labels_skip_districts_that_would_overlap() {
    let m = fixture("java-project");
    let labels = labels(&m.districts);
    // (root) is within 8 of app sideways and 0.4 in depth; com.acme.core sits right behind core.
    let kept: Vec<(&str, usize)> = labels.iter().map(|l| (l.text.as_str(), l.district)).collect();
    assert_eq!(kept, vec![("core", 1), ("app", 4)]);
    assert!(close(labels[0].at[0], -0.366) && close(labels[0].at[1], 1.0) && close(labels[0].at[2], 2.7655));
    assert!(close(labels[1].at[0], -3.4) && close(labels[1].at[2], 12.766));
}

#[test]
fn present_day_districts_hide_only_emptied_ones_of_a_history_city() {
    let ds = vec![district("a", 1, 0.0, 1.0, 2), district("b", 1, 0.0, 1.0, 0)];
    assert_eq!(present_districts(&ds, false), vec![true, true]);
    assert_eq!(present_districts(&ds, true), vec![true, false]);
}

// ------------------------------------------------------------------ dependency arcs

#[test]
fn arcs_join_the_selection_with_its_visible_neighbours() {
    let m = fixture("java-project");
    let sel = &m.buildings[REGISTRY];
    // The model's dependency order differs between editions (the golden files sort it): compare sorted, outgoing first.
    let ends = |a: &[ArcEnds]| {
        let mut v: Vec<_> = a.iter().map(|a| (a.from, a.to, a.outgoing)).collect();
        v.sort_by_key(|&(f, t, o)| (!o, f, t));
        v
    };
    assert_eq!(
        ends(&arcs(Some(sel), |_| true)),
        vec![(11, 14, true), (11, 15, true), (11, 16, true), (11, 18, true), (4, 11, false), (5, 11, false), (15, 11, false)]
    );
    // Hidden test code (MainTest) loses its arc.
    let no_tests = |i: usize| !m.types()[i].test;
    assert_eq!(ends(&arcs(Some(sel), no_tests)).len(), 6);
    assert!(!arcs(Some(sel), no_tests).iter().any(|a| a.from == MAIN_TEST));
    // A hidden selection, or none, has no arcs.
    assert!(arcs(Some(&m.buildings[MAIN_TEST]), no_tests).is_empty());
    assert!(arcs(None, |_| true).is_empty());
    // Main uses 7, 8, 11, 16, 19 and is used by the two tests.
    assert_eq!(arcs(Some(&m.buildings[MAIN]), |_| true).len(), 7);
}

#[test]
fn arcs_are_capped_at_200_per_direction() {
    let lot = Lot { x: 0.0, z: 0.0, side: 1.0, y: 0.0, district: 0 };
    let b = Building {
        index: 0,
        lot,
        file: String::new(),
        history: None,
        deps: (1..=250).collect(),
        incoming: (251..=260).collect(),
        prs: vec![],
        methods: vec![],
        gone: false,
        hist: None,
    };
    let all = arcs(Some(&b), |_| true);
    assert_eq!(all.iter().filter(|a| a.outgoing).count(), MAX_ARCS);
    assert_eq!(all.iter().filter(|a| !a.outgoing).count(), 10);
    // The cap applies after filtering: with every odd dependency hidden, 125 remain, all drawn.
    let even = arcs(Some(&b), |i| i % 2 == 0 || i > 250);
    assert_eq!(even.iter().filter(|a| a.outgoing).count(), 125);
    assert_eq!(all[199].to, 200);
}

#[test]
fn arc_shape_rises_with_length_and_thins_for_busy_selections() {
    let p1 = arc_control(vec3(0.0, 2.0, 0.0), vec3(6.0, 4.0, 8.0));
    // Above the midpoint (3, 4) on the ground, 2 units plus 35% of the distance above the taller roof.
    let d = vec3(0.0, 2.0, 0.0).distance(vec3(6.0, 4.0, 8.0)) as f64;
    assert!(close(p1.x as f64, 3.0) && close(p1.z as f64, 4.0));
    assert!(close(p1.y as f64, 4.0 + d * 0.35 + 2.0));
    assert!(close(arc_radius(40, 10.0) as f64, 0.06));
    assert!(close(arc_radius(41, 10.0) as f64, 0.035));
    assert!(close(particle_speed(1.0) as f64, 2.8));
    assert!(close(particle_speed(10.0) as f64, 14.0 / 12.0));
}

// ------------------------------------------------------------------ inspector and tooltip

/// Pull request number `n` touching `files` files.
fn pr(n: i64, author: Option<&str>, updated: &str, draft: bool, files: usize) -> PullRequest {
    PullRequest {
        number: n,
        title: format!("PR {n}"),
        url: format!("https://github.com/o/r/pull/{n}"),
        author: author.map(String::from),
        updated_at: updated.into(),
        draft,
        files: (0..files).map(|k| format!("f{k}")).collect(),
    }
}

/// `owner/repo` on github.com at `git_ref`, analysed from `prefix`.
fn github(git_ref: &str, prefix: &str) -> GitHubInfo {
    GitHubInfo {
        repo: GitHubRepo { host: "github.com".into(), owner: "o".into(), name: "r".into(), branch: None },
        git_ref: git_ref.into(),
        prefix: prefix.into(),
        pulls: None,
        pulls_source: None,
    }
}

#[test]
fn inspector_shows_metrics_and_git_history() {
    let mut m = fixture("java-project");
    let cells = |m: &CityModel, git: bool| -> Vec<(String, &'static str)> { metric_cells(&m.types()[REGISTRY], &m.buildings[REGISTRY], git, 4, 3, NOW) };
    let plain = vec![
        ("8".to_string(), "methods"),
        ("4".to_string(), "attributes"),
        ("111".to_string(), "lines of code"),
        ("25".to_string(), "complexity"),
        ("3.1".to_string(), "cx / method"),
        ("4 / 3".to_string(), "uses / used by"),
    ];
    assert_eq!(cells(&m, false), plain);
    // A file without history in a git city.
    let mut with_git = plain.clone();
    with_git.extend([("0".to_string(), "commits"), ("0".to_string(), "authors"), ("—".to_string(), "last change")]);
    assert_eq!(cells(&m, true), with_git);
    m.buildings[REGISTRY].history = Some(FileHistory { commits: 12, authors: 3, last_commit: NOW - 5 * DAY, first_commit: NOW - 400 * DAY });
    let h = cells(&m, true);
    assert_eq!(&h[6..], &[("12".to_string(), "commits"), ("3".to_string(), "authors"), ("5d ago".to_string(), "last change")]);
    // No methods: the ratio is 0, not NaN; thousands are separated.
    let t = &mut m.analysis.types[1];
    t.loc = 12_345;
    let c = metric_cells(&m.types()[1], &m.buildings[1], false, 0, 0, NOW);
    assert_eq!(c[0].0, "0");
    assert_eq!(c[2].0, "12.3k");
    assert_eq!(c[4].0, "0");
}

#[test]
fn inspector_package_line_and_method_names() {
    let m = fixture("java-project");
    assert_eq!(package_line(&m.types()[REGISTRY]), "com.acme.core · core");
    // Helper is in the default package, outside any module.
    assert_eq!(package_line(&m.types()[0]), "(default package)");
    // A Rust type at the root of its crate: "(root)" and the crate's module.
    let mixed = fixture("mixed-project");
    assert_eq!(mixed.types()[4].name, "Counter");
    assert_eq!(package_line(&mixed.types()[4]), "(root) · mix_native");
    let methods: Vec<String> = m.buildings[REGISTRY].methods.iter().take(3).map(|x| method_name(&x.name, x.parameters)).collect();
    assert_eq!(methods, vec!["retries(…)", "add(…)", "snapshot()"]);
}

#[test]
fn inspector_lists_15_methods_and_counts_the_rest() {
    assert_eq!(more_methods(0), None);
    assert_eq!(more_methods(15), None);
    assert_eq!(more_methods(16).as_deref(), Some("+ 1 more"));
    assert_eq!(more_methods(321).as_deref(), Some("+ 306 more"));
    // A class with 321 methods: the model keeps the 300 most complex, the most complex first, and the inspector
    // counts all of them.
    let dir = tempfile::tempdir().unwrap();
    let mut src = String::from("class Big {\n");
    for k in 0..320 {
        src.push_str(&format!("  void m{k}() {{}}\n"));
    }
    src.push_str("  int hot(int a) { if (a > 1) { return 1; } if (a > 2) { return 2; } return 0; }\n}\n");
    std::fs::write(dir.path().join("Big.java"), src).unwrap();
    let root = std::fs::canonicalize(dir.path()).unwrap();
    let m = CityModel::build(&SourceAnalyzer::new(), &root, true, false, None, None);
    let (t, b) = (&m.types()[0], &m.buildings[0]);
    assert_eq!(t.methods.len(), 321);
    assert_eq!(b.methods.len(), 300);
    assert_eq!(b.methods[0].name, "hot");
    assert_eq!(more_methods(t.methods.len()).as_deref(), Some("+ 306 more"));
}

#[test]
fn inspector_describes_pull_requests() {
    assert_eq!(pr_meta(&pr(7, Some("ada"), "2026-09-25T12:00:00Z", true, 2), NOW), "ada · 5d ago · 2 files · draft");
    assert_eq!(pr_meta(&pr(8, None, "2026-09-30T11:00:00Z", false, 1), NOW), "ghost · today · 1 files");
    // An unparsable time shows as unknown.
    assert_eq!(pr_meta(&pr(9, Some("bo"), "yesterday", false, 0), NOW), "bo · — · 0 files");
    assert_eq!(no_pulls(Some("all open PRs")), "none");
    assert_eq!(no_pulls(Some("latest 30 open PRs (no token)")), "none among the latest 30 open PRs (no token)");
    assert_eq!(no_pulls(None), "none among the ");
}

#[test]
fn github_links_encode_each_path_segment() {
    let gh = github("main", "sub dir/");
    let file = "core/src/A B#1Ä.java";
    assert_eq!(github_link(&gh, "blob", file, 3, 9), "https://github.com/o/r/blob/main/sub%20dir/core/src/A%20B%231%C3%84.java#L3-L9");
    assert_eq!(github_link(&gh, "blame", file, 3, 9), "https://github.com/o/r/blame/main/sub%20dir/core/src/A%20B%231%C3%84.java#L3-L9");
    // The history page has no line anchor; a branch name keeps its slashes.
    assert_eq!(github_link(&github("feature/x y", ""), "commits", "a/b.rs", 1, 2), "https://github.com/o/r/commits/feature/x%20y/a/b.rs");
    assert!(look::is_web_link(&github_link(&gh, "blob", file, 1, 1)));
}

/// Only the unreserved characters of RFC 3986 stay as they are, as in every edition's viewers.
#[test]
fn github_links_keep_only_unreserved_characters() {
    assert_eq!(
        github_link(&github("feature/x y", "sub dir/"), "commits", "a!b'c(d)e*f~g-h_i.j,k é", 1, 2),
        "https://github.com/o/r/commits/feature/x%20y/sub%20dir/a%21b%27c%28d%29e%2Af~g-h_i.j%2Ck%20%C3%A9"
    );
}

#[test]
fn parse_iso_offsets() {
    let utc = parse_iso("2026-09-30T12:00:00Z");
    assert_eq!(utc, Some(NOW));
    assert_eq!(parse_iso("2026-09-30T14:00:00+02:00"), utc);
    assert_eq!(parse_iso("2026-09-30T06:30:00.5-0530"), utc);
    assert_eq!(parse_iso("2026-09-30T12:00"), utc);
    assert_eq!(parse_iso("1970-01-01T00:00:00Z"), Some(0));
    assert_eq!(parse_iso("2024-02-29T00:00:00Z"), Some(1_709_164_800));
    assert_eq!(parse_iso("yesterday"), None);
    assert_eq!(parse_iso(""), None);
}

#[test]
fn tooltips_summarise_buildings_and_districts() {
    let mut m = fixture("java-project");
    assert_eq!(tooltip_row(&m.types()[REGISTRY], &m.buildings[REGISTRY]), "8 methods · 4 attrs · 111 loc · cx 25");
    m.buildings[REGISTRY].history = Some(FileHistory { commits: 12, authors: 3, last_commit: NOW, first_commit: NOW });
    m.buildings[REGISTRY].prs = vec![0];
    assert_eq!(tooltip_row(&m.types()[REGISTRY], &m.buildings[REGISTRY]), "8 methods · 4 attrs · 111 loc · cx 25 · 12 commits · 1 open PR");
    m.buildings[REGISTRY].prs = vec![0, 1];
    assert!(tooltip_row(&m.types()[REGISTRY], &m.buildings[REGISTRY]).ends_with(" · 2 open PRs"));
    assert_eq!(district_tooltip(&m.districts[0], false), None);
    assert_eq!(district_tooltip(&m.districts[1], false), Some(("core".into(), "core".into(), "13 types · double-click to zoom".into())));
    assert_eq!(district_tooltip(&m.districts[2], true), Some(("com.acme.core".into(), "core/com.acme.core".into(), "13 types".into())));
}

#[test]
fn replay_texts_match_the_browser() {
    let mut m = fixture("java-project");
    let mut t = crate::history::Track::absent(2);
    t.nom = vec![-1, 3];
    t.noa = vec![-1, 1];
    t.loc = vec![-1, 12_345];
    t.cx = vec![-1, 5];
    t.commits = vec![0, 4];
    // Snapshot 1: the snapshot's numbers and date; commits only for a file with git statistics today.
    let b = &m.buildings[REGISTRY];
    assert_eq!(replay_tooltip_row(b, &t, 1, "2026-09-30"), "3 methods · 1 attrs · 12.3k loc · cx 5 · at 2026-09-30");
    m.buildings[REGISTRY].history = Some(FileHistory { commits: 12, authors: 3, last_commit: NOW, first_commit: NOW });
    m.buildings[REGISTRY].prs = vec![0];
    let b = &m.buildings[REGISTRY];
    assert_eq!(replay_tooltip_row(b, &t, 1, "2026-09-30"), "3 methods · 1 attrs · 12.3k loc · cx 5 · 4 commits · at 2026-09-30");
    // Snapshot 0, where the type does not exist: zeros.
    assert_eq!(replay_tooltip_row(b, &t, 0, "2026-01-01"), "0 methods · 0 attrs · 0 loc · cx 0 · 0 commits · at 2026-01-01");
    assert_eq!(replay_heading("2026-09-30"), "Replaying — values at 2026-09-30");
    assert_eq!(
        replay_cells(&t, 1),
        Some(vec![("3".into(), "methods"), ("1".into(), "attributes"), ("12.3k".into(), "lines of code"), ("5".into(), "complexity")])
    );
    assert_eq!(replay_cells(&t, 0), None);
    assert_eq!((NOT_IN_SNAPSHOT, REMOVED), ("Not in this snapshot", "Removed before today"));
}

// ------------------------------------------------------------------ walk mode

/// The java-project city as walk mode sees it: method-count heights, lot footprints, every district shown.
fn walk_world(m: &CityModel) -> walk::World {
    let r = radius(m.width, m.depth);
    let s = height_scale(m.types().iter().map(|t| t.nom() as f64).collect(), r);
    let heights: Vec<f32> = m.types().iter().map(|t| height(t.nom() as f64, s, r) as f32).collect();
    let sides: Vec<f32> = m.buildings.iter().map(|b| b.lot.side as f32).collect();
    walk::World::from_model(m, &heights, &sides, &vec![true; m.districts.len()])
}

#[test]
fn walking_starts_in_front_of_the_selection_facing_it() {
    let m = fixture("java-project");
    let orbit = Orbit { target: vec3(2.0, 0.0, 3.0), yaw: 0.6, pitch: 0.62, distance: 52.0 };
    // Registry's lot is at (3.434, -9.366) with side 2.8: 1.4 + 0.2 + 0.2 in front of its centre.
    let (x, z, yaw) = walk_start(Some(&m.buildings[REGISTRY].lot), &orbit);
    assert!(close(x as f64, 3.434) && close(z as f64, -7.566) && yaw == 0.0);
    // Without a selection, at the orbit target, looking the orbit camera's way.
    assert_eq!(walk_start(None, &orbit), (2.0, 3.0, 0.6));
    let w = Walking::begin(&walk_world(&m), Some(&m.buildings[REGISTRY].lot), orbit, 7.5);
    // The spot is free street, so the walker stands exactly there, on the district platform (core, depth 2: 1.2).
    assert!(close(w.walker.x as f64, 3.434) && close(w.walker.z as f64, -7.566), "{} {}", w.walker.x, w.walker.z);
    assert!(close(w.walker.feet as f64, 1.2), "{}", w.walker.feet);
    assert_eq!(w.since, 7.5);
    // Facing the building: the view direction points towards -z.
    let [dx, _, dz] = w.walker.direction();
    assert!(close(dx as f64, 0.0) && dz < -0.99);
}

#[test]
fn leaving_walk_mode_restores_the_orbit_camera_exactly() {
    let m = fixture("java-project");
    let world = walk_world(&m);
    let orbit = Orbit::framing(20.0);
    assert_eq!(orbit.distance, 52.0);
    let eye = orbit.position();
    assert!(close(eye.x as f64, 23.896) && close(eye.y as f64, 30.214) && close(eye.z as f64, 34.930), "{eye:?}");
    let mut w = Walking::begin(&world, None, orbit, 0.0);
    // Walk and look around for a second; the orbit camera is untouched by it.
    for _ in 0..20 {
        w.walker.look(13.0, -4.0);
        w.walker.step(0.05, walk::Input { forward: true, ..Default::default() }, &world);
    }
    assert_ne!((w.walker.x, w.walker.z), (0.0, 0.0));
    assert_eq!(w.end(), orbit);
}
