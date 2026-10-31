//! End-to-end regression suite, shared by all three editions: runs the whole pipeline ([`CityModel::build`], as
//! `--export` does) on every fixture under `testdata/` and compares the city JSON with its golden file in
//! `testdata/golden/`. The Java (`GoldenCityTest`) and D (`golden.d`) editions compare with the same files, so the
//! three are held to identical output.
//!
//! The cases: each of `java-project`, `ts-project`, `rust-project` and `mixed-project` without git history (the
//! fixtures live inside CodeCity's own repository, whose history keeps changing), with and without test code
//! (`<name>.no-tests.json`); and the repository built by `testdata/make-history-repo.sh` (fixed authors and dates, so
//! fixed commit ids) with git history (`history-repo.json`) and with the history replay (`history-repo.history.json`).
//! That repository is built under a fixed directory name, as the city is named after it.
//!
//! Both documents are normalised first, by the rules of `scripts/city_json.py` (which the other editions implement
//! too): `generatedAt` and `buildMillis` are removed, `root` becomes `"<root>"` and every building's `deps` are sorted.
//! A mismatch fails with the first differences, and the actual JSON is kept in `rust/target/golden-actual/`. To
//! regenerate the golden files after an intended change, build all three editions and run
//! `scripts/parity.sh --update-golden`, which refuses to write them unless the editions agree.

use crate::analysis::SourceAnalyzer;
use crate::city::CityModel;
use serde_json::Value;
use std::path::{Path, PathBuf};

/// At most this many differences are reported, as in `city_json.py`.
const MAX_DIFFS: usize = 10;

fn testdata() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata")
}

/// Builds the city of `tree` like the command line does (the real path, no GitHub) and compares its normalised JSON
/// with `testdata/golden/<golden>.json`; the error describes the differences.
fn check(golden: &str, tree: &Path, include_tests: bool, use_git: bool, history: Option<usize>) -> Result<(), String> {
    let root = std::fs::canonicalize(tree).unwrap();
    let model = CityModel::build(&SourceAnalyzer::new(), &root, include_tests, use_git, None, history);
    let json = model.json();
    let actual = Path::new(env!("CARGO_MANIFEST_DIR")).join("target/golden-actual").join(format!("{golden}.json"));
    std::fs::create_dir_all(actual.parent().unwrap()).unwrap();
    std::fs::write(&actual, json).unwrap();
    let file = testdata().join("golden").join(format!("{golden}.json"));
    let expected: Value = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
    let mut out = vec![];
    diff(&normalise(expected), &normalise(serde_json::from_str(json).unwrap()), "$", &mut out);
    if out.is_empty() {
        return Ok(());
    }
    Err(format!(
        "{golden}.json: the city differs from the golden file (actual JSON in {}):\n    {}",
        actual.display(),
        out.join("\n    ")
    ))
}

/// Fails with every error of `results`.
fn assert_all(results: impl IntoIterator<Item = Result<(), String>>) {
    let errors: Vec<String> = results.into_iter().filter_map(Result::err).collect();
    assert!(
        errors.is_empty(),
        "{}\nIf the change is intended, regenerate the golden files with scripts/parity.sh --update-golden.",
        errors.join("\n")
    );
}

/// Builds the history fixture in `dir/history-repo`.
fn history_repo(dir: &Path) -> PathBuf {
    let repo = dir.join("history-repo");
    let out = std::process::Command::new("sh").arg(testdata().join("make-history-repo.sh")).arg(&repo).output().unwrap();
    assert!(out.status.success(), "make-history-repo.sh failed:\n{}", String::from_utf8_lossy(&out.stderr));
    repo
}

/// The normalisation of `scripts/city_json.py`.
fn normalise(mut city: Value) -> Value {
    if let Some(c) = city.as_object_mut() {
        c.remove("generatedAt");
        c.remove("buildMillis");
        if let Some(stats) = c.get_mut("stats").and_then(Value::as_object_mut) {
            stats.remove("buildMillis");
        }
        if c.contains_key("root") {
            c.insert("root".into(), Value::from("<root>"));
        }
        for b in c.get_mut("buildings").and_then(Value::as_array_mut).into_iter().flatten() {
            if let Some(deps) = b.get_mut("deps").and_then(Value::as_array_mut) {
                deps.sort_by(|x, y| x.as_f64().partial_cmp(&y.as_f64()).unwrap());
            }
        }
    }
    city
}

/// The ids of an array of objects that all have one (districts, buildings), else None.
fn ids(list: &[Value]) -> Option<Vec<&Value>> {
    if list.is_empty() {
        return None;
    }
    list.iter().map(|x| x.get("id")).collect()
}

/// A value as compact JSON, cut to 80 characters, for a difference report.
fn label(v: &Value) -> String {
    let s = v.to_string();
    if s.chars().count() <= 80 {
        s
    } else {
        s.chars().take(77).collect::<String>() + "..."
    }
}

/// Appends up to [`MAX_DIFFS`] differences between `a` (expected) and `b` (actual) to `out`, depth first, like
/// `city_json.py`: object keys in sorted order, numbers by value, and an array of objects with ids (districts,
/// buildings) whose ids differ reported by its missing and unexpected ids.
fn diff(a: &Value, b: &Value, path: &str, out: &mut Vec<String>) {
    if out.len() >= MAX_DIFFS {
        return;
    }
    match (a, b) {
        (Value::Object(ma), Value::Object(mb)) => {
            let keys: std::collections::BTreeSet<&String> = ma.keys().chain(mb.keys()).collect();
            for k in keys {
                match (ma.get(k), mb.get(k)) {
                    (Some(x), None) => out.push(format!("{path}.{k}: missing (expected {})", label(x))),
                    (None, Some(y)) => out.push(format!("{path}.{k}: unexpected (got {})", label(y))),
                    (Some(x), Some(y)) => diff(x, y, &format!("{path}.{k}"), out),
                    (None, None) => unreachable!(),
                }
                if out.len() >= MAX_DIFFS {
                    return;
                }
            }
        }
        (Value::Array(la), Value::Array(lb)) => {
            if let (Some(ia), Some(ib)) = (ids(la), ids(lb)) {
                if ia != ib {
                    let missing: Vec<Value> = ia.iter().filter(|x| !ib.contains(x)).map(|x| (*x).clone()).collect();
                    let extra: Vec<Value> = ib.iter().filter(|x| !ia.contains(x)).map(|x| (*x).clone()).collect();
                    out.push(if missing.is_empty() && extra.is_empty() {
                        format!("{path}: same ids in a different order")
                    } else {
                        format!("{path}: ids differ; missing {}, unexpected {}", label(&missing.into()), label(&extra.into()))
                    });
                    return;
                }
            }
            if la.len() != lb.len() {
                out.push(format!("{path}: expected {} elements, got {}", la.len(), lb.len()));
            }
            for (i, (x, y)) in la.iter().zip(lb).enumerate() {
                let id = match x.get("id").and_then(Value::as_str) {
                    Some(id) => format!("{{{id}}}"),
                    None => String::new(),
                };
                diff(x, y, &format!("{path}[{i}]{id}"), out);
                if out.len() >= MAX_DIFFS {
                    return;
                }
            }
        }
        (Value::Number(x), Value::Number(y)) => {
            if x.as_f64() != y.as_f64() {
                out.push(format!("{path}: expected {x}, got {y}"));
            }
        }
        _ => {
            if a != b {
                out.push(format!("{path}: expected {}, got {}", label(a), label(b)));
            }
        }
    }
}

const FIXTURES: [&str; 4] = ["java-project", "ts-project", "rust-project", "mixed-project"];

#[test]
fn fixtures() {
    assert_all(FIXTURES.map(|name| check(name, &testdata().join(name), true, false, None)));
}

#[test]
fn fixtures_without_tests() {
    assert_all(FIXTURES.map(|name| check(&format!("{name}.no-tests"), &testdata().join(name), false, false, None)));
}

#[test]
fn history_repo_with_git() {
    let dir = tempfile::tempdir().unwrap();
    let repo = history_repo(dir.path());
    assert_all([check("history-repo", &repo, true, true, None)]);
}

#[test]
fn history_repo_replay() {
    let dir = tempfile::tempdir().unwrap();
    let repo = history_repo(dir.path());
    assert_all([check("history-repo.history", &repo, true, true, Some(crate::history::DEFAULT_SNAPSHOTS))]);
}
