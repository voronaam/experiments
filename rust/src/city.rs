//! An analysed and laid-out city, consumed by both the web viewer (as JSON) and the desktop viewer.
//!
//! [`CityModel::build`] runs the whole pipeline for one source tree: analysis and git history (in parallel), then
//! [`crate::layout`], then [`resolve`], which joins every type with its lot, history, dependency edges and pull
//! requests into a [`Building`]. A model is immutable once built; watch mode builds a new one and swaps it in.
//!
//! Indices: `buildings[i]` is `analysis.types[i]` (`Building::index == i`), and every index in `deps`, `incoming`
//! and the JSON `deps` arrays is such a building index.

use crate::analysis::SourceAnalyzer;
use crate::github::GitHubInfo;
use crate::json::JsonWriter;
use crate::layout::{self, District, Lot};
use crate::model::{slashes, Analysis, FileHistory, Language, MethodInfo, TypeInfo};
use std::collections::HashMap;
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
    /// The JSON document, built at most once and only if asked for (the native window never needs it).
    json: OnceLock<String>,
}

impl CityModel {
    /// Analyses `root` and lays it out. Never fails: unreadable files and missing git just yield less data.
    pub fn build(analyzer: &SourceAnalyzer, root: &Path, include_tests: bool, use_git: bool, github: Option<GitHubInfo>) -> CityModel {
        let start = Instant::now();
        let (mut analysis, history) = std::thread::scope(|s| {
            // git log runs while the sources are parsed. A scoped thread may borrow root; if it panics, the city is
            // built without history (unwrap_or_default).
            let git = s.spawn(|| if use_git { crate::git::load(root) } else { HashMap::new() });
            let analysis = analyzer.analyze(root, include_tests);
            (analysis, git.join().unwrap_or_default())
        });
        analysis.history = history;
        // A root without a file name (e.g. "/") is named after its full path.
        let name = root.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| root.display().to_string());
        let layout = layout::layout(&name, &analysis.types);
        let buildings = resolve(&analysis, &layout.lots, github.as_ref());
        let stats = stats(&analysis);
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
    ///   districts: [ { id, name, depth, x, z, w, d, types } ],
    ///   buildings: [ { id, lang, name, …, x, z, y, side, district, nom, noa, loc, cx,
    ///                  commits?, authors?, last?, first?, prs?, methods: [ [name, line, loc, cx, params] ], deps } ] }
    /// ```
    ///
    /// Methods are arrays rather than objects to keep the file small. `?` marks keys that are left out when there is
    /// no data; `pullsSource` and `pulls` are only present when the pull requests were fetched.
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
        for d in &self.districts {
            w.begin_object()
                .field_str("id", &d.id)
                .field_str("name", &d.label)
                .field_int("depth", d.depth as i64)
                .field_num("x", d.x).field_num("z", d.z)
                .field_num("w", d.w).field_num("d", d.d)
                .field_int("types", d.types as i64)
                .end_object();
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
            w.end_object();
        }
        w.end_array();
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
            }
        })
        .collect()
}

/// Totals over the types that made it into the model (after `--no-tests` filtering).
fn stats(a: &Analysis) -> Stats {
    let mut packages: Vec<(Language, &str, &str)> = a.types.iter().map(|t| (t.language, t.module.as_str(), t.package.as_str())).collect();
    packages.sort_unstable();
    packages.dedup();
    let mut counts: HashMap<Language, usize> = HashMap::new();
    for t in &a.types {
        *counts.entry(t.language).or_default() += 1;
    }
    // Start from Language::ALL so that languages with equal counts keep a fixed order (the sort below is stable).
    let mut languages: Vec<(Language, usize)> = Language::ALL.iter().filter_map(|l| counts.get(l).map(|n| (*l, *n))).collect();
    languages.sort_by(|x, y| y.1.cmp(&x.1));
    Stats {
        files: a.files,
        failed_files: a.failed_files,
        types: a.types.len(),
        packages: packages.len(),
        methods: a.types.iter().map(TypeInfo::nom).sum(),
        loc: a.types.iter().map(|t| t.loc).sum(),
        languages,
    }
}

/// Current UTC time as ISO-8601, without pulling in a date library.
fn now_iso() -> String {
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) as i64;
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
