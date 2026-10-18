//! An analysed and laid-out city, consumed by both the web viewer (as JSON) and the desktop viewer.
//!
//! [`CityModel::build`] runs the whole pipeline for one source tree: analysis and git history (in parallel), then
//! [`crate::layout`], then [`resolve`], which joins every type with its lot, history, dependency edges and pull
//! requests into a [`Building`]. A model is immutable once built; watch mode builds a new one and swaps it in.
//!
//! Indices: `buildings[i]` is `analysis.types[i]` (`Building::index == i`), and every index in `deps`, `incoming`
//! and the JSON `deps` arrays is such a building index.
//!
//! # History
//! With `--history`, [`crate::history::compute`] adds snapshots of the past, and the model becomes the *union* city:
//! every type that exists today or in any snapshot. Types that exist only in the past ("gone") keep the version of
//! their latest snapshot and are flagged [`Building::gone`]; they are hidden in the present-day view. Every lot is
//! sized for the building's largest attribute count over time, so it fits the building at every snapshot; the
//! building's own `side` is its present footprint (a gone type: its last-seen one), centred in that lot. The header
//! statistics and the districts' type counts count present types only.

use crate::analysis::SourceAnalyzer;
use crate::github::GitHubInfo;
use crate::history::{Snapshot, Track};
use crate::json::JsonWriter;
use crate::layout::{self, District, Lot};
use crate::model::{slashes, Analysis, FileHistory, Language, MethodInfo, TypeInfo};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::OnceLock;
use std::time::Instant;

/// At most this many methods per building go into the model (and the JSON), the most complex first, so a very large
/// type cannot blow up the document.
const MAX_METHODS: usize = 300;

/// One building, fully resolved: the type, where it stands, its history and its edges.
pub struct Building {
    /// Index into [`Analysis::types`] (equal to the building's own index).
    pub index: usize,
    pub lot: Lot,
    /// Path relative to the analysed root, with forward slashes.
    pub file: String,
    /// None when git history is off or the file has no commits (e.g. not committed yet).
    pub history: Option<FileHistory>,
    /// Buildings this one depends on, in the order of [`Analysis::dependencies`] (sorted by fqn).
    pub deps: Vec<usize>,
    /// Buildings that depend on this one (the reverse edges), in ascending index order.
    pub incoming: Vec<usize>,
    /// Indices into the GitHub info's pull requests.
    pub prs: Vec<usize>,
    /// Methods sorted by complexity, then size (capped).
    pub methods: Vec<MethodInfo>,
    /// With history: the type no longer exists today, only in snapshots (its data is from the latest one).
    pub gone: bool,
    /// With history: the type's metrics in every snapshot (all absent for a type that exists only today).
    pub hist: Option<Track>,
}

/// Totals for the JSON `stats` object and the console summary.
pub struct Stats {
    pub files: usize,
    pub failed_files: usize,
    pub types: usize,
    /// Distinct (language, module, package) triples, so equal package names in different modules count separately.
    pub packages: usize,
    pub methods: u32,
    pub loc: u32,
    /// Type count per language, most common first.
    pub languages: Vec<(Language, usize)>,
}

/// Everything a viewer needs about one analysed source tree.
pub struct CityModel {
    /// The root directory's name, used as the city's (root district's) label.
    pub name: String,
    pub analysis: Analysis,
    /// From [`layout::Layout`], in pre-order; [`Lot::district`] indexes into it.
    pub districts: Vec<District>,
    pub width: f64,
    pub depth: f64,
    pub github: Option<GitHubInfo>,
    pub buildings: Vec<Building>,
    pub stats: Stats,
    /// Wall-clock time of [`CityModel::build`], for the console.
    pub build_millis: u128,
    /// The sampled commits, oldest first, when the history was computed (`--history`).
    pub history: Option<Vec<Snapshot>>,
    /// With history, per district (indexed like `districts`) and snapshot: whether the district or any of its
    /// sub-districts has a building that exists in that snapshot. Empty without history.
    pub district_hist: Vec<Vec<bool>>,
    /// The JSON document, built at most once and only if asked for (the native window never needs it).
    json: OnceLock<String>,
}

impl CityModel {
    /// Analyses `root` and lays it out. Never fails: unreadable files and missing git just yield less data.
    ///
    /// `history` is the number of snapshots to replay (`--history=N`), or None for the present-day city only. It
    /// needs git: with `use_git` off (or no git history, see [`crate::history::compute`]) a warning is printed and the
    /// city is built without it.
    pub fn build(analyzer: &SourceAnalyzer, root: &Path, include_tests: bool, use_git: bool, github: Option<GitHubInfo>, history: Option<usize>) -> CityModel {
        let start = Instant::now();
        if history.is_some() && !use_git {
            eprintln!("{}", crate::history::NO_HISTORY);
        }
        // The replay needs every file's commits, not just the totals.
        let events = use_git && history.is_some();
        let (mut analysis, (totals, file_events)) = std::thread::scope(|s| {
            // git log runs while the sources are parsed. A scoped thread may borrow root; if it panics, the city is
            // built without history (unwrap_or_default).
            let git = s.spawn(|| match (use_git, events) {
                (false, _) => Default::default(),
                (true, false) => (crate::git::load(root), HashMap::new()),
                (true, true) => crate::git::load_with_events(root),
            });
            let analysis = analyzer.analyze(root, include_tests);
            (analysis, git.join().unwrap_or_default())
        });
        analysis.history = totals;
        let mut past = history.filter(|_| events).and_then(|n| crate::history::compute(root, include_tests, n, &file_events));
        // Union city: add the gone types (with their last-seen dependencies) and remember which they are.
        let gone: HashSet<String> = past.as_mut().map(|h| union(&mut analysis, h)).unwrap_or_default();
        // A root without a file name (e.g. "/") is named after its full path.
        let name = root.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| root.display().to_string());
        // With history every lot is sized for the type's largest attribute count, today or in any snapshot.
        let mut layout = match &past {
            None => layout::layout(&name, &analysis.types),
            Some(h) => layout::layout_with_noa(&name, &analysis.types, |t| h.tracks.get(&t.fqn).map_or(t.noa, |tr| tr.max_noa().max(t.noa))),
        };
        let mut buildings = resolve(&analysis, &layout.lots, github.as_ref());
        let mut district_hist = vec![];
        if let Some(h) = &mut past {
            let n = h.snapshots.len();
            for b in &mut buildings {
                let fqn = &analysis.types[b.index].fqn;
                b.gone = gone.contains(fqn);
                b.hist = Some(h.tracks.remove(fqn).unwrap_or_else(|| Track::absent(n)));
            }
            district_hist = districts_alive(&layout.districts, &buildings, n);
            // The lots are sized for the largest version; the building itself (and the JSON `side`) has its present
            // footprint (a gone type: its last-seen one), centred in its lot. District type counts are of present types.
            for b in &mut buildings {
                b.lot.side = layout::side_of_noa(analysis.types[b.index].noa);
            }
            present_type_counts(&mut layout.districts, &buildings);
        }
        let stats = stats(&analysis, &buildings);
        CityModel {
            name,
            analysis,
            districts: layout.districts,
            width: layout.width,
            depth: layout.depth,
            github,
            buildings,
            stats,
            build_millis: start.elapsed().as_millis(),
            history: past.map(|h| h.snapshots),
            district_hist,
            json: OnceLock::new(),
        }
    }

    pub fn types(&self) -> &[TypeInfo] {
        &self.analysis.types
    }

    pub fn has_git(&self) -> bool {
        !self.analysis.history.is_empty()
    }

    pub fn has_pulls(&self) -> bool {
        self.github.as_ref().is_some_and(|g| g.pulls.is_some())
    }

    /// The model as served to the web viewer; computed on first use.
    pub fn json(&self) -> &str {
        self.json.get_or_init(|| self.to_json())
    }

    /// Writes the document the web viewer loads. The format is shared with the Java and D editions:
    ///
    /// ```text
    /// { name, root, generatedAt, hasGit, platform, width, depth,
    ///   stats: { files, failedFiles, types, packages, methods, loc, languages: { <lang id>: count } },
    ///   github?: { host, owner, repo, url, ref, prefix, pullsSource?, pulls?: [ { number, title, … } ] },
    ///   districts: [ { id, name, depth, x, z, w, d, types, hist? } ],
    ///   buildings: [ { id, lang, name, …, x, z, y, side, district, nom, noa, loc, cx,
    ///                  commits?, authors?, last?, first?, prs?, methods: [ [name, line, loc, cx, params] ], deps,
    ///                  hist?: { nom, noa, loc, cx, commits, authors, last }, gone? } ],
    ///   history?: { snapshots: [ { commit, time, author, subject, types } ] } }
    /// ```
    ///
    /// Methods are arrays rather than objects to keep the file small. `?` marks keys that are left out when there is
    /// no data; `pullsSource` and `pulls` are only present when the pull requests were fetched. `history` and every
    /// `hist` are present with `--history`: a district's `hist` holds 0 or 1 per snapshot, a building's one value per
    /// snapshot in each array (see [`Track`]); `gone` is written only as `true`.
    fn to_json(&self) -> String {
        let a = &self.analysis;
        let mut w = JsonWriter::new();
        w.begin_object()
            .field_str("name", &self.name)
            .field_str("root", &a.root.to_string_lossy())
            .field_str("generatedAt", &now_iso())
            .field_bool("hasGit", self.has_git())
            .field_num("platform", layout::PLATFORM)
            .field_num("width", self.width)
            .field_num("depth", self.depth);
        let s = &self.stats;
        w.name("stats").begin_object()
            .field_int("files", s.files as i64)
            .field_int("failedFiles", s.failed_files as i64)
            .field_int("types", s.types as i64)
            .field_int("packages", s.packages as i64)
            .field_int("methods", s.methods as i64)
            .field_int("loc", s.loc as i64);
        w.name("languages").begin_object();
        for (lang, n) in &s.languages {
            w.field_int(lang.id(), *n as i64);
        }
        w.end_object().end_object();

        if let Some(gh) = &self.github {
            w.name("github").begin_object()
                .field_str("host", &gh.repo.host)
                .field_str("owner", &gh.repo.owner)
                .field_str("repo", &gh.repo.name)
                .field_str("url", &gh.repo.web_url())
                .field_str("ref", &gh.git_ref)
                .field_str("prefix", &gh.prefix);
            if let Some(pulls) = &gh.pulls {
                w.name("pullsSource").opt_str(gh.pulls_source.as_deref());
                w.name("pulls").begin_array();
                for pr in pulls {
                    w.begin_object()
                        .field_int("number", pr.number)
                        .field_str("title", &pr.title)
                        .field_str("url", &pr.url)
                        .name("author").opt_str(pr.author.as_deref())
                        .field_str("updatedAt", &pr.updated_at)
                        .field_bool("draft", pr.draft)
                        .field_int("files", pr.files.len() as i64)
                        .end_object();
                }
                w.end_array();
            }
            w.end_object();
        }

        w.name("districts").begin_array();
        for (i, d) in self.districts.iter().enumerate() {
            w.begin_object()
                .field_str("id", &d.id)
                .field_str("name", &d.label)
                .field_int("depth", d.depth as i64)
                .field_num("x", d.x).field_num("z", d.z)
                .field_num("w", d.w).field_num("d", d.d)
                .field_int("types", d.types as i64);
            if let Some(alive) = self.district_hist.get(i) {
                w.name("hist").begin_array();
                for &a in alive {
                    w.int(a as i64);
                }
                w.end_array();
            }
            w.end_object();
        }
        w.end_array();

        w.name("buildings").begin_array();
        for b in &self.buildings {
            let t = &a.types[b.index];
            let lot = b.lot;
            w.begin_object()
                .field_str("id", &t.fqn)
                .field_str("lang", t.language.id())
                .field_str("name", &t.name)
                .field_str("pkg", &t.package)
                .field_str("module", &t.module)
                .field_str("kind", t.kind)
                .field_bool("test", t.test)
                .field_str("file", &b.file)
                .field_int("line", t.line as i64)
                .field_int("endLine", t.end_line as i64)
                .field_num("x", lot.x).field_num("z", lot.z).field_num("y", lot.y)
                .field_num("side", lot.side)
                .field_int("district", lot.district as i64)
                .field_int("nom", t.nom() as i64)
                .field_int("noa", t.noa as i64)
                .field_int("loc", t.loc as i64)
                .field_int("cx", t.complexity() as i64);
            if let Some(h) = b.history {
                w.field_int("commits", h.commits as i64)
                    .field_int("authors", h.authors as i64)
                    .field_int("last", h.last_commit)
                    .field_int("first", h.first_commit);
            }
            if !b.prs.is_empty() {
                w.name("prs").begin_array();
                for p in &b.prs {
                    w.int(*p as i64);
                }
                w.end_array();
            }
            w.name("methods").begin_array();
            for m in &b.methods {
                w.begin_array().str(&m.name).int(m.line as i64).int(m.loc as i64).int(m.complexity as i64).int(m.parameters as i64).end_array();
            }
            w.end_array();
            w.name("deps").begin_array();
            for d in &b.deps {
                w.int(*d as i64);
            }
            w.end_array();
            if let Some(h) = &b.hist {
                w.name("hist").begin_object();
                for (key, values) in [("nom", &h.nom), ("noa", &h.noa), ("loc", &h.loc), ("cx", &h.cx), ("commits", &h.commits), ("authors", &h.authors)] {
                    w.name(key).begin_array();
                    for &v in values {
                        w.int(v as i64);
                    }
                    w.end_array();
                }
                w.name("last").begin_array();
                for &v in &h.last {
                    w.int(v);
                }
                w.end_array().end_object();
            }
            if b.gone {
                w.field_bool("gone", true);
            }
            w.end_object();
        }
        w.end_array();
        if let Some(snapshots) = &self.history {
            w.name("history").begin_object().name("snapshots").begin_array();
            for s in snapshots {
                w.begin_object()
                    .field_str("commit", &s.commit)
                    .field_int("time", s.time)
                    .field_str("author", &s.author)
                    .field_str("subject", &s.subject)
                    .field_int("types", s.types as i64)
                    .end_object();
            }
            w.end_array().end_object();
        }
        w.end_object();
        w.finish()
    }
}

/// Joins every type with its lot, file history, dependency edges (both directions) and the open pull requests that
/// touch its file. fqns are unique (the analyser keeps one type per fqn), so every type has exactly one lot.
fn resolve(a: &Analysis, lots: &HashMap<String, Lot>, github: Option<&GitHubInfo>) -> Vec<Building> {
    let index: HashMap<&str, usize> = a.types.iter().enumerate().map(|(i, t)| (t.fqn.as_str(), i)).collect();
    // PR files are repository-relative paths, so index the PRs by path once rather than scanning them per type.
    let mut pulls_by_path: HashMap<&str, Vec<usize>> = HashMap::new();
    if let Some(pulls) = github.and_then(|g| g.pulls.as_ref()) {
        for (i, pr) in pulls.iter().enumerate() {
            for f in &pr.files {
                pulls_by_path.entry(f.as_str()).or_default().push(i);
            }
        }
    }
    // Dependencies by fqn → by building index. Analysis has already dropped targets that are not in `types`, so the
    // filter_map is only a safeguard.
    let deps: Vec<Vec<usize>> = a
        .types
        .iter()
        .map(|t| a.dependencies.get(&t.fqn).map(|d| d.iter().filter_map(|x| index.get(x.as_str()).copied()).collect()).unwrap_or_default())
        .collect();
    let mut incoming = vec![vec![]; a.types.len()];
    for (i, out) in deps.iter().enumerate() {
        for &j in out {
            incoming[j].push(i);
        }
    }
    a.types
        .iter()
        .enumerate()
        .zip(deps)
        .zip(incoming)
        .map(|(((i, t), deps), incoming)| {
            let file = slashes(t.file.strip_prefix(&a.root).unwrap_or(&t.file));
            // The analysed root may be a subdirectory of the repository; the GitHub prefix turns the root-relative
            // path into the repository-relative one that PR file lists use.
            let repo_path = github.map(|g| format!("{}{}", g.prefix, file)).unwrap_or_else(|| file.clone());
            let mut methods = t.methods.clone();
            // Most complex first, then largest (descending on both); the sort is stable, so ties keep source order.
            methods.sort_by(|x, y| y.complexity.cmp(&x.complexity).then(y.loc.cmp(&x.loc)));
            methods.truncate(MAX_METHODS);
            Building {
                index: i,
                lot: lots[&t.fqn],
                history: a.history.get(&t.file).copied(),
                file,
                deps,
                incoming,
                prs: pulls_by_path.get(repo_path.as_str()).cloned().unwrap_or_default(),
                methods,
                gone: false,
                hist: None,
            }
        })
        .collect()
}

/// Adds the types of `h` that do not exist today to `a` (keeping `a.types` sorted by fqn), with the dependencies of
/// their last-seen version restricted to the union. Returns the fqns of those gone types.
fn union(a: &mut Analysis, h: &mut crate::history::History) -> HashSet<String> {
    let present: HashSet<String> = a.types.iter().map(|t| t.fqn.clone()).collect();
    let mut gone_deps = vec![];
    for (fqn, (t, deps)) in h.last_seen.drain() {
        if !present.contains(&fqn) {
            a.types.push(t);
            gone_deps.push((fqn, deps));
        }
    }
    a.types.sort_by(|x, y| x.fqn.cmp(&y.fqn));
    let all: HashSet<&str> = a.types.iter().map(|t| t.fqn.as_str()).collect();
    let gone: HashSet<String> = gone_deps.iter().map(|(f, _)| f.clone()).collect();
    for (fqn, deps) in gone_deps {
        let kept = deps.into_iter().filter(|d| all.contains(d.as_str())).collect();
        a.dependencies.insert(fqn, kept);
    }
    gone
}

/// For every district and snapshot, whether a building that exists in that snapshot stands on the district or on
/// one of its sub-districts. `districts` is in pre-order, so a district's parent is the nearest earlier district
/// one level up; walking backwards then hands each district's flags up to its parent after all of its sub-districts
/// have handed theirs up.
fn districts_alive(districts: &[District], buildings: &[Building], n: usize) -> Vec<Vec<bool>> {
    let mut alive = vec![vec![false; n]; districts.len()];
    for b in buildings {
        if let Some(h) = &b.hist {
            for (k, flag) in alive[b.lot.district].iter_mut().enumerate() {
                *flag |= h.exists(k);
            }
        }
    }
    let parent = parents(districts);
    for i in (0..districts.len()).rev() {
        if let Some(p) = parent[i] {
            let child = alive[i].clone();
            for (flag, c) in alive[p].iter_mut().zip(child) {
                *flag |= c;
            }
        }
    }
    alive
}

/// The parent of every district (None for the city): in pre-order, the nearest earlier district one level up, which
/// is the last district seen at that depth while going forwards.
fn parents(districts: &[District]) -> Vec<Option<usize>> {
    let mut parent = vec![None; districts.len()];
    let mut last_at_depth: Vec<usize> = vec![];
    for (i, d) in districts.iter().enumerate() {
        let depth = d.depth as usize;
        last_at_depth.truncate(depth);
        parent[i] = depth.checked_sub(1).and_then(|p| last_at_depth.get(p).copied());
        last_at_depth.push(i);
    }
    parent
}

/// Recounts every district's `types` over the present-day buildings only (the layout counted the gone ones too):
/// each present building counts for its district and all the districts above it.
fn present_type_counts(districts: &mut [District], buildings: &[Building]) {
    let parent = parents(districts);
    for d in districts.iter_mut() {
        d.types = 0;
    }
    for b in buildings.iter().filter(|b| !b.gone) {
        let mut d = Some(b.lot.district);
        while let Some(i) = d {
            districts[i].types += 1;
            d = parent[i];
        }
    }
}

/// Totals over the present-day types that made it into the model (after `--no-tests` filtering); gone types (see
/// [`Building::gone`]) do not count.
fn stats(a: &Analysis, buildings: &[Building]) -> Stats {
    let present: Vec<&TypeInfo> = a.types.iter().zip(buildings).filter(|(_, b)| !b.gone).map(|(t, _)| t).collect();
    let mut packages: Vec<(Language, &str, &str)> = present.iter().map(|t| (t.language, t.module.as_str(), t.package.as_str())).collect();
    packages.sort_unstable();
    packages.dedup();
    let mut counts: HashMap<Language, usize> = HashMap::new();
    for t in &present {
        *counts.entry(t.language).or_default() += 1;
    }
    // Start from Language::ALL so that languages with equal counts keep a fixed order (the sort below is stable).
    let mut languages: Vec<(Language, usize)> = Language::ALL.iter().filter_map(|l| counts.get(l).map(|n| (*l, *n))).collect();
    languages.sort_by(|x, y| y.1.cmp(&x.1));
    Stats {
        files: a.files,
        failed_files: a.failed_files,
        types: present.len(),
        packages: packages.len(),
        methods: present.iter().map(|t| t.nom()).sum(),
        loc: present.iter().map(|t| t.loc).sum(),
        languages,
    }
}

/// Current UTC time as ISO-8601, without pulling in a date library.
fn now_iso() -> String {
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) as i64;
    iso(secs)
}

/// The UTC date `YYYY-MM-DD` of a Unix time (for the history summary on the console).
pub fn iso_date(secs: i64) -> String {
    iso(secs)[..10].to_string()
}

/// A Unix time as ISO-8601 in UTC, `YYYY-MM-DDThh:mm:ssZ`.
fn iso(secs: i64) -> String {
    let (days, rem) = (secs.div_euclid(86400), secs.rem_euclid(86400));
    // civil-from-days (Howard Hinnant, "chrono-Compatible Low-Level Date Algorithms"). It shifts the epoch to
    // 0000-03-01 so that the leap day is the last day of the year, and counts in 400-year eras of 146097 days:
    //   719468  days from 0000-03-01 to 1970-01-01
    //   doe     day of the era [0, 146096], yoe year of the era [0, 399], doy day of the March-based year [0, 365]
    //   mp      month counted from March [0, 11]; (153 * mp + 2) / 5 is the first day of that month in the year
    // Months 11 and 12 of that March-based year (January, February) belong to the next calendar year.
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}
