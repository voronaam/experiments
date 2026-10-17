//! GitHub integration: repository URLs, the clone cache, remote detection and open pull requests.

use crate::git;
use regex::Regex;
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::OnceLock;
use std::time::Duration;

/// A repository on github.com or a GitHub Enterprise host.
#[derive(Clone, Debug, PartialEq)]
pub struct GitHubRepo {
    pub host: String,
    pub owner: String,
    pub name: String,
    /// Branch named in a `/tree/<branch>` URL or `@branch` shorthand.
    pub branch: Option<String>,
}

const SEGMENT: &str = "[A-Za-z0-9_.-]+";

impl GitHubRepo {
    /// Parses `https://github.com/o/r[/tree/b]`, `git@github.com:o/r.git`, `ssh://…` or `github:owner/repo[@branch]`.
    /// Only hosts containing "github" match. Errors on `.`/`..` path segments.
    pub fn parse(text: &str) -> Result<Option<GitHubRepo>, String> {
        static PATTERNS: OnceLock<[Regex; 4]> = OnceLock::new();
        let [shorthand, https, scp, ssh] = PATTERNS.get_or_init(|| {
            [
                Regex::new(&format!(r"^github:({SEGMENT})/({SEGMENT})(?:@(.+))?$")).unwrap(),
                Regex::new(&format!(r"^https?://(?:[^@/]+@)?([^/:]+)(?::\d+)?/({SEGMENT})/({SEGMENT})(?:/tree/(.+?))?/?$")).unwrap(),
                Regex::new(&format!(r"^[^@\s]+@([^:/]+):({SEGMENT})/({SEGMENT})/?$")).unwrap(),
                Regex::new(&format!(r"^ssh://(?:[^@/]+@)?([^/:]+)(?::\d+)?/({SEGMENT})/({SEGMENT})/?$")).unwrap(),
            ]
        });
        let s = text.trim();
        let strip = |n: &str| n.strip_suffix(".git").unwrap_or(n).to_string();
        let repo = if let Some(c) = shorthand.captures(s) {
            Some(GitHubRepo { host: "github.com".into(), owner: c[1].into(), name: strip(&c[2]), branch: c.get(3).map(|m| m.as_str().into()) })
        } else {
            [https, scp, ssh].iter().find_map(|re| {
                let c = re.captures(s)?;
                let host = c[1].to_lowercase();
                host.contains("github").then(|| GitHubRepo {
                    host,
                    owner: c[2].into(),
                    name: strip(&c[3]),
                    branch: c.get(4).map(|m| m.as_str().into()),
                })
            })
        };
        if let Some(r) = &repo {
            if [".", ".."].contains(&r.owner.as_str()) || [".", ".."].contains(&r.name.as_str()) {
                return Err(format!("invalid repository {}/{}", r.owner, r.name));
            }
        }
        Ok(repo)
    }

    pub fn web_url(&self) -> String {
        format!("https://{}/{}/{}", self.host, self.owner, self.name)
    }

    fn is_public(&self) -> bool {
        self.host == "github.com"
    }

    fn rest_api(&self) -> String {
        if self.is_public() { "https://api.github.com".into() } else { format!("https://{}/api/v3", self.host) }
    }

    fn graphql_api(&self) -> String {
        if self.is_public() { "https://api.github.com/graphql".into() } else { format!("https://{}/api/graphql", self.host) }
    }
}

impl std::fmt::Display for GitHubRepo {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "{}/{}", self.owner, self.name)?;
        if let Some(b) = &self.branch {
            write!(f, "@{b}")?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug)]
pub struct PullRequest {
    pub number: i64,
    pub title: String,
    pub url: String,
    pub author: Option<String>,
    pub updated_at: String,
    pub draft: bool,
    /// Repository-relative paths of the files it changes.
    pub files: Vec<String>,
}

/// GitHub context of an analysed source tree.
#[derive(Clone, Debug)]
pub struct GitHubInfo {
    pub repo: GitHubRepo,
    /// Commit SHA (if it exists on the remote) or branch name, for permalinks.
    pub git_ref: String,
    /// Path of the analysed root inside the repository: "" or ending in "/".
    pub prefix: String,
    pub pulls: Option<Vec<PullRequest>>,
    pub pulls_source: Option<String>,
}

impl GitHubInfo {
    /// Finds a GitHub remote for the repository containing `root`.
    pub fn detect(root: &Path) -> Option<GitHubInfo> {
        let top = git::run(root, &["rev-parse", "--show-toplevel"])?;
        let repo = git::run(root, &["remote", "get-url", "origin"])
            .and_then(|u| GitHubRepo::parse(&u).ok().flatten())
            .or_else(|| {
                git::run(root, &["remote", "-v"])?
                    .lines()
                    .filter_map(|l| l.split_whitespace().nth(1))
                    .find_map(|u| GitHubRepo::parse(u).ok().flatten())
            })?;
        let sha = git::run(root, &["rev-parse", "HEAD"]).unwrap_or_else(|| "HEAD".into());
        // a local-only commit would 404 on GitHub; fall back to the branch name then
        let pushed = git::run(root, &["branch", "-r", "--contains", "HEAD"]).is_some_and(|s| !s.trim().is_empty());
        let branch = git::run(root, &["rev-parse", "--abbrev-ref", "HEAD"]).filter(|b| b != "HEAD").unwrap_or_else(|| sha.clone());
        let top = std::fs::canonicalize(&top).unwrap_or_else(|_| PathBuf::from(top));
        let mut prefix = root.strip_prefix(&top).map(crate::model::slashes).unwrap_or_default();
        if !prefix.is_empty() {
            prefix.push('/');
        }
        Some(GitHubInfo { repo, git_ref: if pushed { sha } else { branch }, prefix, pulls: None, pulls_source: None })
    }
}

// ------------------------------------------------------------------ clone cache

pub fn cache_root() -> PathBuf {
    let base = std::env::var_os("XDG_CACHE_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join(".cache"));
    base.join("codecity").join("repos")
}

fn home() -> PathBuf {
    std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")).map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."))
}

/// Clones blobless (full history, current files only) or fetches the latest state into an existing mirror.
pub fn clone_or_update(repo: &GitHubRepo) -> Result<PathBuf, String> {
    let dir = cache_root().join(&repo.host).join(&repo.owner).join(&repo.name);
    let interactive = |args: &[&str]| -> Result<(), String> {
        let status = Command::new("git").args(args).status().map_err(|e| format!("git: {e}"))?;
        if status.success() { Ok(()) } else { Err(format!("git {} failed ({status})", args.join(" "))) }
    };
    let d = dir.to_string_lossy().to_string();
    if dir.join(".git").is_dir() {
        println!("Updating {repo} in {d} …");
        let mut fetch = vec!["-C", &d, "fetch", "--prune", "origin"];
        if let Some(b) = &repo.branch {
            fetch.push(b);
        }
        interactive(&fetch)?;
        // the mirror is ours alone, so a hard reset to what we just fetched is safe
        let target = if repo.branch.is_some() { "FETCH_HEAD" } else { "origin/HEAD" };
        interactive(&["-C", &d, "reset", "--quiet", "--hard", target])?;
    } else {
        println!("Cloning {} into {d} …", repo.web_url());
        std::fs::create_dir_all(dir.parent().unwrap()).map_err(|e| e.to_string())?;
        let url = format!("{}.git", repo.web_url());
        let mut args = vec!["clone", "--filter=blob:none"];
        if let Some(b) = &repo.branch {
            args.extend(["--branch", b]);
        }
        args.extend(["--", &url, &d]);
        interactive(&args)?;
    }
    Ok(dir)
}

// ------------------------------------------------------------------ pull requests

const PAGE_SIZE: usize = 50;
const MAX_PAGES: usize = 6;
const ANONYMOUS_FILE_LOOKUPS: usize = 30;

fn token(repo: &GitHubRepo) -> Option<String> {
    let vars: &[&str] = if repo.is_public() { &["GH_TOKEN", "GITHUB_TOKEN"] } else { &["GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_TOKEN"] };
    for v in vars {
        if let Ok(t) = std::env::var(v) {
            if !t.trim().is_empty() {
                return Some(t.trim().to_string());
            }
        }
    }
    let out = Command::new("gh").args(["auth", "token", "--hostname", &repo.host]).output().ok()?;
    let t = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (out.status.success() && !t.is_empty()).then_some(t)
}

/// Open PRs and how complete the list is. Never fails: problems are reported and yield None.
pub fn fetch_pulls(repo: &GitHubRepo) -> Option<(Vec<PullRequest>, String)> {
    let token = token(repo);
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(30)))
        .http_status_as_error(false)
        .build()
        .into();
    let result = match &token {
        Some(t) => graphql(&agent, repo, t),
        None => rest(&agent, repo),
    };
    match result {
        Ok(r) => Some(r),
        Err(e) => {
            eprintln!("  GitHub: could not fetch pull requests ({e})");
            None
        }
    }
}

fn request(agent: &ureq::Agent, method: &str, url: &str, token: Option<&str>, body: Option<String>) -> Result<(Value, Option<usize>), String> {
    let builder = ureq::http::Request::builder()
        .method(method)
        .uri(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "codecity");
    let builder = match token {
        Some(t) => builder.header("Authorization", format!("Bearer {t}")),
        None => builder,
    };
    let response = match body {
        Some(b) => agent.run(builder.body(b).map_err(|e| e.to_string())?),
        None => agent.run(builder.body(()).map_err(|e| e.to_string())?),
    }
    .map_err(|e| e.to_string())?;
    let status = response.status().as_u16();
    match status {
        200..=299 => {}
        401 => return Err("token rejected (HTTP 401)".into()),
        403 | 429 => return Err(format!("rate limited (HTTP {status})")),
        _ => return Err(format!("HTTP {status}")),
    }
    let remaining = response.headers().get("x-ratelimit-remaining").and_then(|v| v.to_str().ok()).and_then(|v| v.parse().ok());
    let text = response.into_body().read_to_string().map_err(|e| e.to_string())?;
    let json = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    Ok((json, remaining))
}

fn graphql(agent: &ureq::Agent, repo: &GitHubRepo, token: &str) -> Result<(Vec<PullRequest>, String), String> {
    let query = format!(
        "query($owner: String!, $name: String!, $cursor: String) {{ repository(owner: $owner, name: $name) {{ \
         pullRequests(states: OPEN, first: {PAGE_SIZE}, after: $cursor, orderBy: {{field: UPDATED_AT, direction: DESC}}) {{ \
         pageInfo {{ hasNextPage endCursor }} nodes {{ number title url updatedAt isDraft author {{ login }} files(first: 100) {{ nodes {{ path }} }} }} }} }} }}"
    );
    let mut pulls = vec![];
    let mut cursor = Value::Null;
    let mut more = true;
    for _ in 0..MAX_PAGES {
        if !more {
            break;
        }
        let body = serde_json::json!({ "query": query, "variables": { "owner": repo.owner, "name": repo.name, "cursor": cursor } });
        let (json, _) = request(agent, "POST", &repo.graphql_api(), Some(token), Some(body.to_string()))?;
        if let Some(err) = json["errors"].as_array().and_then(|e| e.first()) {
            return Err(err["message"].as_str().unwrap_or("GraphQL error").to_string());
        }
        let prs = &json["data"]["repository"]["pullRequests"];
        if prs.is_null() {
            return Err("repository not found or not accessible".into());
        }
        for n in prs["nodes"].as_array().into_iter().flatten() {
            pulls.push(PullRequest {
                number: n["number"].as_i64().unwrap_or(0),
                title: n["title"].as_str().unwrap_or("").into(),
                url: n["url"].as_str().unwrap_or("").into(),
                author: n["author"]["login"].as_str().map(String::from),
                updated_at: n["updatedAt"].as_str().unwrap_or("").into(),
                draft: n["isDraft"].as_bool().unwrap_or(false),
                files: n["files"]["nodes"].as_array().into_iter().flatten().filter_map(|f| f["path"].as_str().map(String::from)).collect(),
            });
        }
        more = prs["pageInfo"]["hasNextPage"].as_bool().unwrap_or(false);
        cursor = prs["pageInfo"]["endCursor"].clone();
    }
    let source = if more { format!("latest {} open PRs", pulls.len()) } else { "all open PRs".into() };
    Ok((pulls, source))
}

fn rest(agent: &ureq::Agent, repo: &GitHubRepo) -> Result<(Vec<PullRequest>, String), String> {
    let base = format!("{}/repos/{}/{}", repo.rest_api(), repo.owner, repo.name);
    let (listing, remaining) = request(agent, "GET", &format!("{base}/pulls?state=open&sort=updated&direction=desc&per_page=100"), None, None)?;
    let budget = ANONYMOUS_FILE_LOOKUPS.min(remaining.unwrap_or(ANONYMOUS_FILE_LOOKUPS + 1).saturating_sub(1));
    let nodes = listing.as_array().cloned().unwrap_or_default();
    let mut pulls = vec![];
    for n in nodes.iter().take(budget) {
        let number = n["number"].as_i64().unwrap_or(0);
        let (files, _) = request(agent, "GET", &format!("{base}/pulls/{number}/files?per_page=100"), None, None)?;
        pulls.push(PullRequest {
            number,
            title: n["title"].as_str().unwrap_or("").into(),
            url: n["html_url"].as_str().unwrap_or("").into(),
            author: n["user"]["login"].as_str().map(String::from),
            updated_at: n["updated_at"].as_str().unwrap_or("").into(),
            draft: n["draft"].as_bool().unwrap_or(false),
            files: files.as_array().into_iter().flatten().filter_map(|f| f["filename"].as_str().map(String::from)).collect(),
        });
    }
    let complete = pulls.len() == nodes.len() && nodes.len() < 100;
    if !complete {
        println!(
            "  GitHub: no token found — showing the {} most recently updated open PRs. Set GH_TOKEN (or log in with `gh`) to see all of them.",
            pulls.len()
        );
    }
    let source = if complete { "all open PRs".to_string() } else { format!("latest {} open PRs (no token)", pulls.len()) };
    Ok((pulls, source))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_remote_formats() {
        for url in [
            "https://github.com/google/gson",
            "https://github.com/google/gson.git",
            "git@github.com:google/gson.git",
            "ssh://git@github.com/google/gson.git",
            "github:google/gson",
        ] {
            let r = GitHubRepo::parse(url).unwrap().unwrap();
            assert_eq!((r.host.as_str(), r.owner.as_str(), r.name.as_str()), ("github.com", "google", "gson"), "{url}");
        }
        assert_eq!(GitHubRepo::parse("https://github.com/o/r/tree/release/2.x").unwrap().unwrap().branch.as_deref(), Some("release/2.x"));
        assert_eq!(GitHubRepo::parse("https://gitlab.com/o/r").unwrap(), None);
        assert!(GitHubRepo::parse("github:../..").is_err());
    }
}
