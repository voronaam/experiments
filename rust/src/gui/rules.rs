//! The desktop viewer's rules as pure functions: what the search finds, how tall buildings are, which districts get
//! a label, which dependency arcs are drawn, what the inspector and the tooltip say, and where walk mode starts.
//!
//! `gui/mod.rs` and `scene.rs` call these with the current state and draw the result; nothing here touches the
//! window, egui or the GPU, so every rule can be tested without a display. The Java (`ViewRules.java`) and D
//! (`rules.d`) desktop viewers implement the same rules with the same numbers, and their tests check the same
//! scenarios, so all three show a city identically. The browser viewer (`app.js`) follows the same rules too.

use super::look::{self, ColorMetric};
use super::walk;
use crate::city::Building;
use crate::github::{GitHubInfo, PullRequest};
use crate::history::Track;
use crate::layout::{District, Lot, PLATFORM};
use crate::model::{Language, TypeInfo};
use three_d::{vec3, MetricSpace, Vec3};

// ------------------------------------------------------------------ search

/// Queries shorter than this (in UTF-16 code units, as in the browser viewer) clear the search.
pub const MIN_QUERY: usize = 2;
/// How many of the best matches the drop-down under the search field lists.
pub const MAX_RESULTS: usize = 12;

/// Length of `s` in UTF-16 code units, the unit JavaScript's and Java's `length` count in; the search ranks ties by
/// it so that every viewer orders equally long names the same way.
pub fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// Searches the types that pass `visible` for `query` (trimmed, case-insensitive) and returns every match, best
/// first; None when the query is too short, which means "no search active".
///
/// Ranking: exact simple name (0), name prefix (1), name substring (2), match anywhere in the fully qualified name
/// (3); within a rank, shorter names (in UTF-16 units) first. The sort is stable, so equal entries keep model order.
/// The viewer highlights every match in the city and lists the first [`MAX_RESULTS`].
pub fn search(query: &str, types: &[TypeInfo], visible: impl Fn(usize) -> bool) -> Option<Vec<usize>> {
    let q = query.trim().to_lowercase();
    if utf16_len(&q) < MIN_QUERY {
        return None;
    }
    let mut scored: Vec<(usize, u8, usize)> = vec![];
    for (i, t) in types.iter().enumerate() {
        if !visible(i) {
            continue;
        }
        let name = t.name.to_lowercase();
        let score = if name == q {
            0
        } else if name.starts_with(&q) {
            1
        } else if name.contains(&q) {
            2
        } else if t.fqn.to_lowercase().contains(&q) {
            3
        } else {
            continue;
        };
        scored.push((i, score, utf16_len(&name)));
    }
    scored.sort_by_key(|&(_, s, l)| (s, l));
    Some(scored.into_iter().map(|s| s.0).collect())
}

// ------------------------------------------------------------------ heights

/// The scale of camera distances, heights and shadows: half the city's diagonal, at least 20.
pub fn radius(width: f64, depth: f64) -> f64 {
    (width.hypot(depth) / 2.0).max(20.0)
}

/// Height per unit of the height metric for a city of `radius` whose visible buildings have the metric `values`:
/// the 95th-percentile value (nearest rank, rounding the rank down; at least 1, so an all-zero metric does not divide
/// by zero) becomes 20% of the radius, kept between 8 and 16 units.
pub fn height_scale(mut values: Vec<f64>, radius: f64) -> f64 {
    values.sort_by(f64::total_cmp);
    let p95 = if values.is_empty() { 1.0 } else { values[((0.95 * (values.len() - 1) as f64) as usize).min(values.len() - 1)] }.max(1.0);
    (radius * 0.2).clamp(8.0, 16.0) / p95
}

/// The height of a visible building whose metric is `value`: a 0.35 base, so a value of 0 still shows, plus the value
/// on `scale`, capped at 75% of the radius so outliers do not tower over everything.
pub fn height(value: f64, scale: f64, radius: f64) -> f64 {
    (0.35 + value * scale).min(radius * 0.75)
}

// ------------------------------------------------------------------ colours

/// The colour metric in effect: the user's choice when this model can provide it, else churn when there is git
/// history, else complexity.
pub fn effective_color(chosen: Option<ColorMetric>, has_git: bool, has_pulls: bool) -> ColorMetric {
    match chosen {
        Some(c) if c.available(has_git, has_pulls) => c,
        _ if has_git => ColorMetric::Commits,
        _ => ColorMetric::Cx,
    }
}

/// Whether building `i` stays undimmed while `selected` is selected: the selection itself and its direct neighbours
/// in both directions (what it uses, what uses it). Everything is related when nothing is selected.
pub fn related(selected: Option<&Building>, i: usize) -> bool {
    selected.is_none_or(|s| s.index == i || s.deps.contains(&i) || s.incoming.contains(&i))
}

/// A building's final colour from its metric colour `base`: mixed 80% towards the theme's `dim` colour when it is
/// outside the search matches or unrelated to the selection, then lightened 30% towards white when it is hovered
/// (and not the selected one).
pub fn shade(base: three_d::Srgba, dim: three_d::Srgba, out_of_search: bool, out_of_focus: bool, hovered: bool) -> three_d::Srgba {
    let mut c = base;
    if out_of_search || out_of_focus {
        c = look::mix(c, dim, 0.8);
    }
    if hovered {
        c = look::mix(c, three_d::Srgba::WHITE, 0.3);
    }
    c
}

// ------------------------------------------------------------------ districts and labels

/// The districts of the present-day view: all of them, except in a history city those whose `types` is 0, which hold
/// only gone types (and so would be empty platforms today).
pub fn present_districts(districts: &[District], history: bool) -> Vec<bool> {
    districts.iter().map(|d| !history || d.types > 0).collect()
}

/// At most this many districts are considered for a label, largest first.
pub const LABEL_CANDIDATES: usize = 24;
/// At most this many labels are placed.
pub const MAX_LABELS: usize = 16;

/// A district name label: the text, its anchor in world space and the district's index.
#[derive(Clone, Debug, PartialEq)]
pub struct Label {
    pub text: String,
    pub at: [f64; 3],
    pub district: usize,
}

/// Chooses which districts get a floating name label.
///
/// Labelling every district would bury the city in text, so only districts at nesting depth 1 and 2 (modules and
/// top-level packages; depth 0 is the city itself) that contain at least one type are candidates. The
/// [`LABEL_CANDIDATES`] largest by area are considered, biggest first (equal areas in model order), and a label is
/// skipped if its anchor is within 8 units sideways and 3 units in depth of one already placed, since the two would
/// overlap on screen. At most [`MAX_LABELS`] labels are kept.
///
/// Each label is anchored at the middle of the district's front edge (`z + d/2`, as `z` runs back to front), 0.2
/// above its platform: a district at depth `n` has its top at `(n + 1) * PLATFORM`, as in the layout.
pub fn labels(districts: &[District]) -> Vec<Label> {
    let mut candidates: Vec<_> = districts.iter().enumerate().filter(|(_, d)| (1..=2).contains(&d.depth) && d.types > 0).collect();
    candidates.sort_by(|(_, a), (_, b)| (b.w * b.d).total_cmp(&(a.w * a.d)));
    let mut out: Vec<Label> = vec![];
    for (index, d) in candidates.into_iter().take(LABEL_CANDIDATES) {
        let front = d.z + d.d / 2.0;
        if out.len() >= MAX_LABELS || out.iter().any(|l| (l.at[0] - d.x).abs() < 8.0 && (l.at[2] - front).abs() < 3.0) {
            continue;
        }
        out.push(Label { text: d.label.clone(), at: [d.x, (d.depth + 1) as f64 * PLATFORM + 0.2, front], district: index });
    }
    out
}

// ------------------------------------------------------------------ dependency arcs

/// At most this many arcs are drawn per direction (uses / used by).
pub const MAX_ARCS: usize = 200;
/// Above this many dependency edges in total (before filtering and capping), arcs are drawn thinner.
pub const THIN_ARCS_ABOVE: usize = 40;

/// One dependency arc: from the depending building to its dependency, and whether it starts at the selection (an
/// outgoing "uses" arc, orange) or ends there (an incoming "used by" arc, blue).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ArcEnds {
    pub from: usize,
    pub to: usize,
    pub outgoing: bool,
}

/// The arcs to draw for the selection: none when nothing is selected or the selection is hidden; otherwise one to
/// every visible type it uses, then one from every visible type that uses it, each direction capped at
/// [`MAX_ARCS`] in model order.
pub fn arcs(selected: Option<&Building>, visible: impl Fn(usize) -> bool) -> Vec<ArcEnds> {
    let Some(b) = selected.filter(|b| visible(b.index)) else { return vec![] };
    let sel = b.index;
    let out = b.deps.iter().copied().filter(|&j| visible(j)).take(MAX_ARCS).map(|j| ArcEnds { from: sel, to: j, outgoing: true });
    let inc = b.incoming.iter().copied().filter(|&j| visible(j)).take(MAX_ARCS).map(|j| ArcEnds { from: j, to: sel, outgoing: false });
    out.chain(inc).collect()
}

/// The control point of the quadratic Bézier between the roofs `p0` and `p2`: above their midpoint, 2 units plus 35%
/// of their distance higher than the taller roof, so longer arcs rise higher and neighbours still get a visible hump.
pub fn arc_control(p0: Vec3, p2: Vec3) -> Vec3 {
    let mid = (p0 + p2) * 0.5;
    vec3(mid.x, p0.y.max(p2.y) + p0.distance(p2) * 0.35 + 2.0, mid.z)
}

/// The tube "radius" of an arc of length `dist` when the selection has `total` edges: thinner for busy selections,
/// growing slightly with length.
pub fn arc_radius(total: usize, dist: f32) -> f32 {
    (if total > THIN_ARCS_ABOVE { 0.03 } else { 0.055 }) + dist * 0.0005
}

/// The particle speed on an arc of length `dist`, in curve parameter per second: about 14 / 1.2 ≈ 12 world units per
/// second along the straight line, at most 2.8 trips per second on short arcs.
pub fn particle_speed(dist: f32) -> f32 {
    14.0 / (dist * 1.2).max(5.0)
}

// ------------------------------------------------------------------ inspector and tooltip

/// How many of the most complex methods the inspector lists.
pub const INSPECTOR_METHODS: usize = 15;

/// The inspector's line under the type name: the package (or `(default package)` for Java, `(root)` otherwise) and
/// the module after a dot, if any.
pub fn package_line(t: &TypeInfo) -> String {
    let pkg = if t.package.is_empty() { if t.language == Language::Java { "(default package)" } else { "(root)" } } else { &t.package };
    if t.module.is_empty() { pkg.to_string() } else { format!("{pkg} · {}", t.module) }
}

/// The inspector's metric tiles, as (value, label) in display order: methods, attributes, lines, complexity,
/// complexity per method (one decimal) and the visible dependency counts; with git history also commits, authors and
/// the age of the last change relative to `now` (a file without history shows 0, 0 and "—").
pub fn metric_cells(t: &TypeInfo, b: &Building, has_git: bool, uses: usize, used_by: usize, now: i64) -> Vec<(String, &'static str)> {
    let mut cells: Vec<(String, &'static str)> = vec![
        (t.nom().to_string(), "methods"),
        (t.noa.to_string(), "attributes"),
        (look::fmt(t.loc as f64), "lines of code"),
        (t.complexity().to_string(), "complexity"),
        (if t.nom() == 0 { "0".into() } else { look::ratio(t.complexity() as i64, t.nom() as i64) }, "cx / method"),
        (format!("{uses} / {used_by}"), "uses / used by"),
    ];
    if has_git {
        let h = b.history;
        cells.push((h.map(|h| h.commits.to_string()).unwrap_or("0".into()), "commits"));
        cells.push((h.map(|h| h.authors.to_string()).unwrap_or("0".into()), "authors"));
        cells.push((h.map(|h| look::rel_time_at(h.last_commit, now)).unwrap_or("—".into()), "last change"));
    }
    cells
}

/// One method in the inspector's list: its name with `(…)` when it takes parameters, else `()`.
pub fn method_name(name: &str, parameters: u32) -> String {
    format!("{name}({})", if parameters > 0 { "…" } else { "" })
}

/// The line under the method list when the type has more methods than are listed: counted on all of the type's
/// methods (`total`), like the section header, not on the model's capped list of the most complex 300.
pub fn more_methods(total: usize) -> Option<String> {
    (total > INSPECTOR_METHODS).then(|| format!("+ {} more", total - INSPECTOR_METHODS))
}

/// The second line of an open pull request in the inspector: author (`ghost` when unknown), how long ago it was
/// updated relative to `now` ("—" when the timestamp does not parse), its file count and whether it is a draft.
pub fn pr_meta(pr: &PullRequest, now: i64) -> String {
    format!(
        "{} · {} · {} files{}",
        pr.author.as_deref().unwrap_or("ghost"),
        look::rel_time_at(parse_iso(&pr.updated_at).unwrap_or(0), now),
        pr.files.len(),
        if pr.draft { " · draft" } else { "" }
    )
}

/// What the inspector says when no fetched pull request touches the file: plain "none" when every open PR was
/// fetched, otherwise which ones were searched (e.g. "none among the latest 30 open PRs (no token)").
pub fn no_pulls(source: Option<&str>) -> String {
    let src = source.unwrap_or("");
    if src.starts_with("all") { "none".into() } else { format!("none among the {src}") }
}

/// GitHub web URL for a file: `kind` is the URL segment, `blob` (file view), `blame` or `commits` (history), giving
/// `https://<host>/<owner>/<repo>/<kind>/<ref>/<prefix><file>`.
///
/// `gh.prefix` is the analysed root's path inside the repository with a trailing slash (empty when the root is the
/// repository's top level), since `file` is relative to the analysed root. Each path segment is percent-encoded
/// separately so the slashes survive. File and blame views get a `#Lstart-Lend` anchor that highlights the type's
/// lines; the history page has no line anchors.
pub fn github_link(gh: &GitHubInfo, kind: &str, file: &str, line: u32, end_line: u32) -> String {
    let enc = |p: &str| p.split('/').map(url_encode).collect::<Vec<_>>().join("/");
    let url = format!("{}/{}/{}/{}", gh.repo.web_url(), kind, enc(&gh.git_ref), enc(&format!("{}{}", gh.prefix, file)));
    if kind == "commits" { url } else { format!("{url}#L{line}-L{end_line}") }
}

/// Percent-encodes one URL path segment: everything except RFC 3986 unreserved characters (`A–Z a–z 0–9 - _ . ~`) is
/// written as `%XX` per UTF-8 byte. `/` is encoded too, so callers encode segment by segment.
pub fn url_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// The numbers row of a building's hover card in the present-day city: methods, attributes, lines, complexity, then
/// the commit count when the file has history and the open pull requests touching it, if any.
pub fn tooltip_row(t: &TypeInfo, b: &Building) -> String {
    let mut row = format!("{} methods · {} attrs · {} loc · cx {}", t.nom(), t.noa, look::fmt(t.loc as f64), t.complexity());
    if let Some(h) = b.history {
        row.push_str(&format!(" · {} commits", h.commits));
    }
    if !b.prs.is_empty() {
        row.push_str(&format!(" · {} open PR{}", b.prs.len(), if b.prs.len() > 1 { "s" } else { "" }));
    }
    row
}

/// The numbers row of a building's hover card during a replay, for snapshot `k` taken on `date`: the snapshot's
/// methods, attributes, lines and complexity (0 where the type does not exist), its commit count when the file has git
/// statistics today, and the date; no pull requests, which belong to today. The same text as the browser viewer's.
pub fn replay_tooltip_row(b: &Building, h: &Track, k: usize, date: &str) -> String {
    let z = |v: i32| v.max(0);
    let mut row = format!("{} methods · {} attrs · {} loc · cx {}", z(h.nom[k]), z(h.noa[k]), look::fmt(z(h.loc[k]) as f64), z(h.cx[k]));
    if b.history.is_some() {
        row.push_str(&format!(" · {} commits", h.commits[k]));
    }
    row.push_str(&format!(" · at {date}"));
    row
}

/// The inspector's heading of the replay box for a snapshot taken on `date`.
pub fn replay_heading(date: &str) -> String {
    format!("Replaying — values at {date}")
}

/// What the inspector says about a type that does not exist in the replayed snapshot.
pub const NOT_IN_SNAPSHOT: &str = "Not in this snapshot";
/// What the inspector says about a type that no longer exists today.
pub const REMOVED: &str = "Removed before today";

/// The inspector's replay tiles for snapshot `k` as (value, label): methods, attributes, lines of code and complexity;
/// None when the type does not exist there (the inspector then says [`NOT_IN_SNAPSHOT`]).
pub fn replay_cells(h: &Track, k: usize) -> Option<Vec<(String, &'static str)>> {
    h.exists(k).then(|| {
        vec![
            (h.nom[k].to_string(), "methods"),
            (h.noa[k].to_string(), "attributes"),
            (look::fmt(h.loc[k] as f64), "lines of code"),
            (h.cx[k].to_string(), "complexity"),
        ]
    })
}

/// A district's hover card as (title, id, row), or None for the city platform itself (depth 0). Module ids carry a
/// `module:` prefix in the layout to keep them apart from packages, which is not shown; the double-click hint is
/// left out while walking, where double-clicks do nothing.
pub fn district_tooltip(d: &District, walking: bool) -> Option<(String, String, String)> {
    (d.depth > 0).then(|| {
        let id = d.id.strip_prefix("module:").unwrap_or(&d.id).to_string();
        (d.label.clone(), id, format!("{} types{}", d.types, if walking { "" } else { " · double-click to zoom" }))
    })
}

/// Seconds since the epoch for an ISO-8601 timestamp such as `2026-09-30T12:00:00Z` or `2026-09-30T14:00:00+02:00`.
///
/// Used for pull requests' `updated_at` from the GitHub API, which comes in UTC with a `Z` suffix. Fractional
/// seconds are accepted and truncated, and missing seconds count as 0. A `±hh:mm`, `±hhmm` or `±hh` offset is
/// subtracted to get UTC; a time without `Z` or offset is taken as UTC. Returns `None` if the date, the hours and
/// minutes or the offset do not parse.
pub fn parse_iso(s: &str) -> Option<i64> {
    let (date, time) = s.split_once('T')?;
    let mut d = date.split('-').map(|x| x.parse::<i64>());
    let (y, m, day) = (d.next()?.ok()?, d.next()?.ok()?, d.next()?.ok()?);
    // The time part contains no '+' or '-' of its own, so one of them starts the offset.
    let (time, offset) = match time.find(['+', '-']) {
        Some(p) => {
            let (time, zone) = time.split_at(p);
            let digits = zone[1..].replace(':', "");
            let hours: i64 = digits.get(..2)?.parse().ok()?;
            let minutes: i64 = if digits.len() > 2 { digits[2..].parse().ok()? } else { 0 };
            (time, if zone.starts_with('-') { -1 } else { 1 } * (hours * 3600 + minutes * 60))
        }
        None => (time.trim_end_matches('Z'), 0),
    };
    let mut t = time.split(':').map(|x| x.parse::<f64>());
    let (hh, mm, ss) = (t.next()?.ok()?, t.next()?.ok()?, t.next().and_then(|x| x.ok()).unwrap_or(0.0));
    // days-from-civil (Howard Hinnant): proleptic Gregorian date to days since 1970-01-01. Shifting the year to start
    // in March puts the leap day at the end, so the day of the year (doy) follows from the month by a closed formula:
    // (153 * month + 2) / 5 counts the days of the months before it with March = 0. An era is a 400-year Gregorian
    // cycle of 146097 days, and 719468 is the day number of 1970-01-01 counted from 0000-03-01.
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    Some(days * 86400 + (hh * 3600.0 + mm * 60.0 + ss) as i64 - offset)
}

// ------------------------------------------------------------------ camera and walk mode

/// An orbit camera: the eye sits on a sphere of radius `distance` around `target` and always looks at it.
///
/// `yaw` is the angle around the vertical axis (radians; 0 puts the eye on the `+z` side, i.e. in front of the
/// city), `pitch` the elevation above the ground plane (0 is level, π/2 straight down from above). Mouse handling
/// keeps pitch in `0.07..=1.5`: never quite level with the ground and never exactly overhead, where the view
/// direction would be parallel to the up vector and the view matrix degenerate.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Orbit {
    pub target: Vec3,
    pub yaw: f32,
    pub pitch: f32,
    pub distance: f32,
}

impl Orbit {
    /// The default three-quarter view of the whole city (yaw 0.6, pitch 0.62 rad, ≈ 36° above the horizon) for a
    /// city of `radius`: at 2.6 radii the whole city fits in the 42° field of view.
    pub fn framing(radius: f32) -> Orbit {
        Orbit { target: Vec3::new(0.0, 0.0, 0.0), yaw: 0.6, pitch: 0.62, distance: radius * 2.6 }
    }

    /// Eye position: spherical to Cartesian coordinates around the target.
    pub fn position(&self) -> Vec3 {
        self.target + vec3(self.pitch.cos() * self.yaw.sin(), self.pitch.sin(), self.pitch.cos() * self.yaw.cos()) * self.distance
    }
}

/// Where walk mode starts, as `(x, z, yaw)` for [`walk::Walker::spawn`]: with a building selected, on the street in
/// front of it (its `+z` side, just clear of the wall: half its lot side, the walker's radius and 0.2 out from its
/// centre), facing it (yaw 0 looks towards `-z`); otherwise at the orbit target, facing the way the orbit camera looks
/// (its yaw uses the walker's convention).
pub fn walk_start(selected: Option<&Lot>, orbit: &Orbit) -> (f32, f32, f32) {
    match selected {
        Some(lot) => (lot.x as f32, lot.z as f32 + lot.side as f32 / 2.0 + walk::RADIUS + 0.2, 0.0),
        None => (orbit.target.x, orbit.target.z, orbit.yaw),
    }
}

/// First-person walk mode in progress: the walker, and the orbit camera from before, restored exactly on leaving.
pub struct Walking {
    pub walker: walk::Walker,
    /// The orbit camera when walking started.
    pub saved: Orbit,
    /// When walking started, in the app's seconds, for fading out the key hint.
    pub since: f64,
}

impl Walking {
    /// Starts walking in `world` from where [`walk_start`] says, remembering `orbit`.
    pub fn begin(world: &walk::World, selected: Option<&Lot>, orbit: Orbit, now: f64) -> Walking {
        let (x, z, yaw) = walk_start(selected, &orbit);
        Walking { walker: walk::Walker::spawn(world, x, z, yaw), saved: orbit, since: now }
    }

    /// Stops walking: the orbit camera to go back to, exactly as it was when walking started.
    pub fn end(self) -> Orbit {
        self.saved
    }
}

