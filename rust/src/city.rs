//! An analysed and laid-out city, consumed by both the web viewer (as JSON) and the desktop viewer.

use crate::analysis::SourceAnalyzer;
use crate::github::GitHubInfo;
use crate::json::JsonWriter;
use crate::layout::{self, District, Lot};
use crate::model::{slashes, Analysis, FileHistory, Language, MethodInfo, TypeInfo};
use std::collections::HashMap;
use std::path::Path;
use std::sync::OnceLock;
use std::time::Instant;

const MAX_METHODS: usize = 300;

/// One building, fully resolved: the type, where it stands, its history and its edges.
pub struct Building {
    pub index: usize,
    pub lot: Lot,
    /// Path relative to the analysed root, with forward slashes.
    pub file: String,
    pub history: Option<FileHistory>,
    pub deps: Vec<usize>,
    pub incoming: Vec<usize>,
    /// Indices into the GitHub info's pull requests.
    pub prs: Vec<usize>,
    /// Methods sorted by complexity, then size (capped).
    pub methods: Vec<MethodInfo>,
}

pub struct Stats {
    pub files: usize,
    pub failed_files: usize,
    pub types: usize,
    pub packages: usize,
    pub methods: u32,
    pub loc: u32,
    /// Type count per language, most common first.
    pub languages: Vec<(Language, usize)>,
}

pub struct CityModel {
    pub name: String,
    pub analysis: Analysis,
    pub districts: Vec<District>,
    pub width: f64,
    pub depth: f64,
    pub github: Option<GitHubInfo>,
    pub buildings: Vec<Building>,
    pub stats: Stats,
    pub build_millis: u128,
    json: OnceLock<String>,
}

impl CityModel {
    pub fn build(analyzer: &SourceAnalyzer, root: &Path, include_tests: bool, use_git: bool, github: Option<GitHubInfo>) -> CityModel {
        let start = Instant::now();
        let (mut analysis, history) = std::thread::scope(|s| {
            // git log runs while the sources are parsed
            let git = s.spawn(|| if use_git { crate::git::load(root) } else { HashMap::new() });
            let analysis = analyzer.analyze(root, include_tests);
            (analysis, git.join().unwrap_or_default())
        });
        analysis.history = history;
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

fn resolve(a: &Analysis, lots: &HashMap<String, Lot>, github: Option<&GitHubInfo>) -> Vec<Building> {
    let index: HashMap<&str, usize> = a.types.iter().enumerate().map(|(i, t)| (t.fqn.as_str(), i)).collect();
    let mut pulls_by_path: HashMap<&str, Vec<usize>> = HashMap::new();
    if let Some(pulls) = github.and_then(|g| g.pulls.as_ref()) {
        for (i, pr) in pulls.iter().enumerate() {
            for f in &pr.files {
                pulls_by_path.entry(f.as_str()).or_default().push(i);
            }
        }
    }
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
            let repo_path = github.map(|g| format!("{}{}", g.prefix, file)).unwrap_or_else(|| file.clone());
            let mut methods = t.methods.clone();
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

fn stats(a: &Analysis) -> Stats {
    let mut packages: Vec<(Language, &str, &str)> = a.types.iter().map(|t| (t.language, t.module.as_str(), t.package.as_str())).collect();
    packages.sort_unstable();
    packages.dedup();
    let mut counts: HashMap<Language, usize> = HashMap::new();
    for t in &a.types {
        *counts.entry(t.language).or_default() += 1;
    }
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
    // civil-from-days (Howard Hinnant)
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
