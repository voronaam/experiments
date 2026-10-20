//! GitHub integration: repository URLs, the clone cache, remote detection and open pull requests.
//!
//! Three independent uses:
//! - `codecity <github-url>`: [`GitHubRepo::parse`] recognises the argument and [`clone_or_update`] keeps a clone of
//!   it under [`cache_root`], which is then analysed like any local tree;
//! - links: [`GitHubInfo::detect`] finds the GitHub remote of the analysed tree, the commit to link to and the path
//!   of the root inside the repository, so the viewer can link to files, blame and history;
//! - the pull-request overlay: [`fetch_pulls`] loads the open PRs and the files each one changes, once at startup.
//!   With a token it uses the GraphQL API (one request per 50 PRs, plus one per further 100 files of a PR that
//!   changes more than 100); without one it falls back to the REST API, which needs one request per PR and 100 files
//!   and so is held back by the anonymous rate limit (60 requests an hour on github.com).
//!
//! github.com and GitHub Enterprise hosts are both supported; they differ only in the API base URLs.
//!
//! The repository may come from the analysed tree's own (untrusted) git configuration, so the token is guarded: an
//! environment token for github.com only ever goes to api.github.com, and a GitHub Enterprise host gets a token (or a
//! request at all) only when the user opted in to that very host (see [`token`] and [`fetch_pulls`]). ureq does not
//! send the Authorization header on to another host when it follows a redirect, reads at most 10 MB of a response,
//! and serde_json refuses JSON nested more than 128 deep.

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

/// An owner or repository name: letters, digits, `_`, `.` and `-`. Since `/` is excluded, the names are also safe as
/// single directory names in the clone cache (with `.`/`..` rejected separately in `parse`).
const SEGMENT: &str = "[A-Za-z0-9_.-]+";
/// A host name: letters, digits, `.` and `-`. The host becomes a directory of the clone cache and part of API and web
/// URLs, so path and URL syntax (`\\`, `?`, `#`, `%`, …) must not get in.
const HOST: &str = "[A-Za-z0-9.-]+";

/// Whether `branch` is a name git would not accept, or could read as an option: empty, starting with `-`, containing
/// a control character, a space, one of `~^:?*[\\` or `..`. It is passed to `git fetch` and `git clone`.
fn bad_branch(branch: &str) -> bool {
    branch.is_empty()
        || branch.starts_with('-')
        || branch.contains("..")
        || branch.chars().any(|c| c <= ' ' || c == '\u{7f}' || "~^:?*[\\".contains(c))
}

impl GitHubRepo {
    /// Parses `https://github.com/o/r[/tree/b]`, `git@github.com:o/r.git`, `ssh://…` or `github:owner/repo[@branch]`.
    /// Only hosts containing "github" match. Errors on `.`/`..` path segments and on an invalid branch name
    /// ([`bad_branch`]).
    pub fn parse(text: &str) -> Result<Option<GitHubRepo>, String> {
        // Compiled once per process. In order: `github:o/r[@b]`; `http(s)://[user@]host[:port]/o/r[/tree/b]`, where
        // the branch may contain slashes (`release/2.x`); scp-like `user@host:o/r`; `ssh://[user@]host[:port]/o/r`.
        static PATTERNS: OnceLock<[Regex; 4]> = OnceLock::new();
        let [shorthand, https, scp, ssh] = PATTERNS.get_or_init(|| {
            [
                Regex::new(&format!(r"^github:({SEGMENT})/({SEGMENT})(?:@(.+))?$")).unwrap(),
                Regex::new(&format!(r"^https?://(?:[^@/]+@)?({HOST})(?::\d+)?/({SEGMENT})/({SEGMENT})(?:/tree/(.+?))?/?$")).unwrap(),
                Regex::new(&format!(r"^[^@\s]+@({HOST}):({SEGMENT})/({SEGMENT})/?$")).unwrap(),
                Regex::new(&format!(r"^ssh://(?:[^@/]+@)?({HOST})(?::\d+)?/({SEGMENT})/({SEGMENT})/?$")).unwrap(),
            ]
        });
        let s = text.trim();
        let strip = |n: &str| n.strip_suffix(".git").unwrap_or(n).to_string();
        let repo = if let Some(c) = shorthand.captures(s) {
            Some(GitHubRepo { host: "github.com".into(), owner: c[1].into(), name: strip(&c[2]), branch: c.get(3).map(|m| m.as_str().into()) })
        } else {
            // Hosts are compared in lower case; "contains github" admits Enterprise hosts like github.acme.com.
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
        // SEGMENT allows dots, so "." and ".." get through the patterns; as path components of the clone cache they
        // would escape it.
        if let Some(r) = &repo {
            if [".", ".."].contains(&r.owner.as_str()) || [".", ".."].contains(&r.name.as_str()) {
                return Err(format!("invalid repository {}/{}", r.owner, r.name));
            }
            if r.branch.as_deref().is_some_and(bad_branch) {
                return Err(format!("invalid branch name {}", r.branch.as_deref().unwrap_or("").replace(|c: char| c.is_control(), "?")));
            }
        }
        Ok(repo)
    }

    pub fn web_url(&self) -> String {
        format!("https://{}/{}/{}", self.host, self.owner, self.name)
    }

    /// github.com, as opposed to a GitHub Enterprise Server host (whose APIs live under the host itself).
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

/// An open pull request, as much as the overlay needs.
#[derive(Clone, Debug)]
pub struct PullRequest {
    pub number: i64,
    pub title: String,
    pub url: String,
    /// Login of the author; None when the response has none.
    pub author: Option<String>,
    /// ISO-8601 timestamp, as GitHub sends it.
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
    /// Open pull requests; None when they were not fetched or fetching failed (which is not the same as none open).
    pub pulls: Option<Vec<PullRequest>>,
    /// How complete `pulls` is, for display, e.g. "all open PRs" or "latest 30 open PRs (no token)".
    pub pulls_source: Option<String>,
}

impl GitHubInfo {
    /// Finds a GitHub remote for the repository containing `root`: `origin` if it is a GitHub URL, otherwise the first
    /// GitHub URL among all remotes. None outside a git work tree or without a GitHub remote.
    pub fn detect(root: &Path) -> Option<GitHubInfo> {
        let top = git::run(root, &["rev-parse", "--show-toplevel"])?;
        let repo = git::run(root, &["remote", "get-url", "origin"])
            .and_then(|u| GitHubRepo::parse(&u).ok().flatten())
            .or_else(|| {
                git::run(root, &["remote", "-v"])?
                    .lines()
                    // Lines look like "<name>\t<url> (fetch)"; the URL is the second field.
                    .filter_map(|l| l.split_whitespace().nth(1))
                    .find_map(|u| GitHubRepo::parse(u).ok().flatten())
            })?;
        let sha = git::run(root, &["rev-parse", "HEAD"]).unwrap_or_else(|| "HEAD".into());
        // A local-only commit would 404 on GitHub; fall back to the branch name then. "Pushed" means that some
        // remote-tracking branch (of any remote, as of the last fetch) contains HEAD.
        let pushed = git::run(root, &["branch", "-r", "--contains", "HEAD"]).is_some_and(|s| !s.trim().is_empty());
        // On a detached HEAD, --abbrev-ref prints "HEAD", which is no use in a URL; use the SHA then.
        let branch = git::run(root, &["rev-parse", "--abbrev-ref", "HEAD"]).filter(|b| b != "HEAD").unwrap_or_else(|| sha.clone());
        let top = std::fs::canonicalize(&top).unwrap_or_else(|_| PathBuf::from(top));
        // Canonical on both sides (root is canonicalised in main), so strip_prefix works through symlinks.
        let mut prefix = root.strip_prefix(&top).map(crate::model::slashes).unwrap_or_default();
        if !prefix.is_empty() {
            prefix.push('/');
        }
        Some(GitHubInfo { repo, git_ref: if pushed { sha } else { branch }, prefix, pulls: None, pulls_source: None })
    }
}

// ------------------------------------------------------------------ clone cache

/// `$XDG_CACHE_HOME/codecity/repos`, or `~/.cache/codecity/repos`. Clones live below it at `<host>/<owner>/<name>`.
pub fn cache_root() -> PathBuf {
    let base = std::env::var_os("XDG_CACHE_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join(".cache"));
    base.join("codecity").join("repos")
}

/// Whether `dir` lies in the clone cache. Such a repository was cloned by CodeCity, so its configuration is git's own
/// and the user's, not the analysed project's: only there may the history replay fetch missing objects
/// ([`crate::git::command`]). Both sides are compared canonicalised.
pub fn is_cached(dir: &Path) -> bool {
    match (std::fs::canonicalize(dir), std::fs::canonicalize(cache_root())) {
        (Ok(dir), Ok(cache)) => dir.starts_with(cache),
        _ => false,
    }
}

/// `HOME`, else `USERPROFILE` (Windows), else the current directory.
fn home() -> PathBuf {
    std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")).map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."))
}

/// Clones blobless (full history, current files only) or fetches the latest state into an existing mirror.
///
/// A blobless clone (`--filter=blob:none`) has every commit and tree but downloads file contents only as they are
/// needed: here, for the checked-out files. `git log --name-only --no-renames`, as [`crate::git::load`] runs it, then
/// works without fetching more, since it only compares trees. git runs with the terminal attached, so its progress
/// output and any credential prompt reach the user.
pub fn clone_or_update(repo: &GitHubRepo) -> Result<PathBuf, String> {
    let dir = cache_root().join(&repo.host).join(&repo.owner).join(&repo.name);
    let interactive = |args: &[&str]| -> Result<(), String> {
        let git = git::executable("git").ok_or("git: not found on the PATH")?;
        let status = Command::new(git).args(args).status().map_err(|e| format!("git: {e}"))?;
        if status.success() { Ok(()) } else { Err(format!("git {} failed ({status})", args.join(" "))) }
    };
    let d = dir.to_string_lossy().to_string();
    if dir.join(".git").is_dir() {
        println!("{}", crate::printable(&format!("Updating {repo} in {d} …")));
        // "--": a branch can never be taken for an option (and `parse` rejects one starting with '-' anyway).
        let mut fetch = vec!["-C", &d, "fetch", "--prune", "--", "origin"];
        if let Some(b) = &repo.branch {
            fetch.push(b);
        }
        interactive(&fetch)?;
        // The mirror is ours alone, so a hard reset to what we just fetched is safe. A fetch of a named branch leaves
        // it in FETCH_HEAD; otherwise origin/HEAD, the remote's default branch as recorded at clone time.
        let target = if repo.branch.is_some() { "FETCH_HEAD" } else { "origin/HEAD" };
        interactive(&["-C", &d, "reset", "--quiet", "--hard", target])?;
    } else {
        println!("{}", crate::printable(&format!("Cloning {} into {d} …", repo.web_url())));
        std::fs::create_dir_all(dir.parent().unwrap()).map_err(|e| e.to_string())?;
        // Always over HTTPS, whichever URL form was given.
        let url = format!("{}.git", repo.web_url());
        let mut args = vec!["clone", "--filter=blob:none"];
        if let Some(b) = &repo.branch {
            args.extend(["--branch", b]);
        }
        // "--" so that a URL or path can never be read as an option.
        args.extend(["--", &url, &d]);
        interactive(&args)?;
    }
    Ok(dir)
}

// ------------------------------------------------------------------ pull requests

/// GraphQL: PRs per request, and at most this many requests, so at most 300 PRs. Each PR brings its first 100 file
/// paths; the rest are fetched 100 per request.
const PAGE_SIZE: usize = 50;
const MAX_PAGES: usize = 6;
/// REST without a token: at most this many file-list requests (one per PR and 100 files).
const ANONYMOUS_FILE_LOOKUPS: usize = 30;

/// Environment variables that may hold a token; `gh` runs without them (see [`token`]).
const TOKEN_VARIABLES: [&str; 4] = ["GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN"];

/// An API token: the environment first ([`env_token`]), else `gh auth token` for the host: the token of a login the
/// user made to exactly that host. `gh` runs without the token variables, since it would otherwise answer with
/// `GH_ENTERPRISE_TOKEN` for any host; the host is passed as `--hostname=<host>`, so it can never be read as another
/// option. `gh` is started by its absolute path ([`git::executable`]). None when there is none or `gh` is not
/// installed.
fn token(repo: &GitHubRepo) -> Option<String> {
    env_token(repo, |v| std::env::var(v).ok()).or_else(|| {
        let mut gh = Command::new(git::executable("gh")?);
        gh.args(["auth", "token", &format!("--hostname={}", repo.host)]);
        TOKEN_VARIABLES.iter().for_each(|v| {
            gh.env_remove(v);
        });
        git::output(&mut gh).filter(|t| !t.is_empty())
    })
}

/// The token from the environment variables the `gh` CLI reads: `GH_TOKEN` or `GITHUB_TOKEN` for github.com,
/// `GH_ENTERPRISE_TOKEN` or `GITHUB_ENTERPRISE_TOKEN` for any other host, but only when `GH_HOST` names that host
/// ([`opted_in`]). Without that opt-in, a crafted remote URL such as `https://github.attacker.example/o/r` in an
/// analysed repository would receive the user's token.
fn env_token(repo: &GitHubRepo, env: impl Fn(&str) -> Option<String>) -> Option<String> {
    let vars: &[&str] = if repo.is_public() {
        &["GH_TOKEN", "GITHUB_TOKEN"]
    } else if opted_in(repo, &env) {
        &["GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN"]
    } else {
        &[]
    };
    vars.iter().filter_map(|v| env(v)).map(|t| t.trim().to_string()).find(|t| !t.is_empty())
}

/// Whether `GH_HOST` names the repository's host (case-insensitively): the opt-in for an Enterprise host.
fn opted_in(repo: &GitHubRepo, env: impl Fn(&str) -> Option<String>) -> bool {
    env("GH_HOST").is_some_and(|h| h.trim().eq_ignore_ascii_case(&repo.host))
}

/// The link of PR `number`: `url` from the response when it is an https URL on the repository's host, otherwise the
/// PR's page built from the repository. The URL ends up in the viewers' links and in the desktop viewers' browser
/// launcher, which must only ever be handed a web page (not, say, a `file:` URL).
fn pull_url(repo: &GitHubRepo, url: Option<&str>, number: i64) -> String {
    match url {
        Some(u) if u.starts_with(&format!("https://{}/", repo.host)) && !u.chars().any(|c| c <= ' ') => u.to_string(),
        _ => format!("{}/pull/{number}", repo.web_url()),
    }
}

/// Open PRs and how complete the list is. Never fails: problems are reported and yield None. A GitHub Enterprise
/// host is not contacted at all unless there is a token for it or `GH_HOST` names it: the host may come from a
/// hostile repository's remote URL, and CodeCity should not call out to it on its own.
pub fn fetch_pulls(repo: &GitHubRepo) -> Option<(Vec<PullRequest>, String)> {
    let token = token(repo);
    if token.is_none() && !repo.is_public() && !opted_in(repo, |v| std::env::var(v).ok()) {
        eprintln!(
            "  GitHub: not contacting {0} (for a GitHub Enterprise host, set GH_HOST={0} or log in with `gh auth login --hostname {0}`)",
            repo.host
        );
        return None;
    }
    // http_status_as_error(false): error statuses come back as responses, so `request` can turn 401/403/429 into
    // readable messages. 30 s bounds each whole request.
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
            eprintln!("  GitHub: could not fetch pull requests ({})", crate::printable(&e.to_string()));
            None
        }
    }
}

/// One API call; returns the parsed JSON body and the `x-ratelimit-remaining` header (requests left in the current
/// rate-limit window), if present. Any non-2xx status is an error.
fn request(agent: &ureq::Agent, method: &str, url: &str, token: Option<&str>, body: Option<String>) -> Result<(Value, Option<usize>), String> {
    let builder = ureq::http::Request::builder()
        .method(method)
        .uri(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        // GitHub rejects API requests without a User-Agent.
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
    let header = |name: &str| response.headers().get(name).and_then(|v| v.to_str().ok());
    if let Some(e) = failure(response.status().as_u16(), header("x-ratelimit-remaining"), header("retry-after").is_some()) {
        return Err(e);
    }
    let remaining = header("x-ratelimit-remaining").and_then(|v| v.parse().ok());
    let text = response.into_body().read_to_string().map_err(|e| e.to_string())?;
    let json = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    Ok((json, remaining))
}

/// The message for a failed request, or None for a 2xx status. GitHub answers an exhausted rate limit with 429, or
/// with 403 and either `x-ratelimit-remaining: 0` (primary limit) or a `retry-after` header (secondary limit); any
/// other 403 means the token or anonymous client may not access the repository.
fn failure(status: u16, rate_limit_remaining: Option<&str>, retry_after: bool) -> Option<String> {
    Some(match status {
        200..=299 => return None,
        429 => format!("rate limited (HTTP {status})"),
        403 if rate_limit_remaining == Some("0") || retry_after => format!("rate limited (HTTP {status})"),
        401 => "token rejected (HTTP 401)".into(),
        403 => "access denied (HTTP 403)".into(),
        _ => format!("HTTP {status}"),
    })
}

/// One GraphQL query; returns its `data`. GraphQL reports errors with HTTP 200 and an "errors" array.
fn query(agent: &ureq::Agent, repo: &GitHubRepo, token: &str, query: &str, variables: Value) -> Result<Value, String> {
    let body = serde_json::json!({ "query": query, "variables": variables });
    let (mut json, _) = request(agent, "POST", &repo.graphql_api(), Some(token), Some(body.to_string()))?;
    if let Some(err) = json["errors"].as_array().and_then(|e| e.first()) {
        return Err(err["message"].as_str().unwrap_or("GraphQL error").to_string());
    }
    Ok(json["data"].take())
}

/// All open PRs (up to PAGE_SIZE × MAX_PAGES), most recently updated first, with their files, paged with the
/// connection cursor (`after: endCursor` until `hasNextPage` is false). A PR's files are paged the same way, one
/// extra request per further 100 files.
fn graphql(agent: &ureq::Agent, repo: &GitHubRepo, token: &str) -> Result<(Vec<PullRequest>, String), String> {
    let pulls_query = format!(
        "query($owner: String!, $name: String!, $cursor: String) {{ repository(owner: $owner, name: $name) {{ \
         pullRequests(states: OPEN, first: {PAGE_SIZE}, after: $cursor, orderBy: {{field: UPDATED_AT, direction: DESC}}) {{ \
         pageInfo {{ hasNextPage endCursor }} nodes {{ number title url updatedAt isDraft author {{ login }} files(first: 100) {{ pageInfo {{ hasNextPage endCursor }} nodes {{ path }} }} }} }} }} }}"
    );
    // The next 100 files of one PR, after the files seen so far.
    let files_query = "query($owner: String!, $name: String!, $number: Int!, $cursor: String) { repository(owner: $owner, name: $name) { \
         pullRequest(number: $number) { files(first: 100, after: $cursor) { pageInfo { hasNextPage endCursor } nodes { path } } } } }";
    let mut pulls = vec![];
    let mut cursor = Value::Null;
    let mut more = true;
    for _ in 0..MAX_PAGES {
        if !more {
            break;
        }
        let data = query(agent, repo, token, &pulls_query, serde_json::json!({ "owner": repo.owner, "name": repo.name, "cursor": cursor }))?;
        let prs = &data["repository"]["pullRequests"];
        if prs.is_null() {
            return Err("repository not found or not accessible".into());
        }
        for n in prs["nodes"].as_array().into_iter().flatten() {
            let number = n["number"].as_i64().unwrap_or(0);
            let mut files = vec![];
            let mut connection = n["files"].clone();
            loop {
                files.extend(connection["nodes"].as_array().into_iter().flatten().filter_map(|f| f["path"].as_str().map(String::from)));
                if !connection["pageInfo"]["hasNextPage"].as_bool().unwrap_or(false) {
                    break;
                }
                let variables = serde_json::json!({ "owner": repo.owner, "name": repo.name, "number": number, "cursor": connection["pageInfo"]["endCursor"] });
                connection = query(agent, repo, token, files_query, variables)?["repository"]["pullRequest"]["files"].take();
            }
            pulls.push(PullRequest {
                number,
                title: n["title"].as_str().unwrap_or("").into(),
                url: pull_url(repo, n["url"].as_str(), number),
                author: n["author"]["login"].as_str().map(String::from),
                updated_at: n["updatedAt"].as_str().unwrap_or("").into(),
                draft: n["isDraft"].as_bool().unwrap_or(false),
                files,
            });
        }
        more = prs["pageInfo"]["hasNextPage"].as_bool().unwrap_or(false);
        cursor = prs["pageInfo"]["endCursor"].clone();
    }
    let source = if more { format!("latest {} open PRs", pulls.len()) } else { "all open PRs".into() };
    Ok((pulls, source))
}

/// Without a token: one listing request (the 100 most recently updated open PRs), then each PR's files, 100 per
/// request until a page comes back short, for as many PRs as the budget of file requests allows. A PR whose files do
/// not fit in what is left is left out, like the PRs after it, rather than shown with only some of its files.
fn rest(agent: &ureq::Agent, repo: &GitHubRepo) -> Result<(Vec<PullRequest>, String), String> {
    let base = format!("{}/repos/{}/{}", repo.rest_api(), repo.owner, repo.name);
    let (listing, remaining) = request(agent, "GET", &format!("{base}/pulls?state=open&sort=updated&direction=desc&per_page=100"), None, None)?;
    // Keep one request in reserve; without the header, assume the full ANONYMOUS_FILE_LOOKUPS are available.
    let budget = ANONYMOUS_FILE_LOOKUPS.min(remaining.unwrap_or(ANONYMOUS_FILE_LOOKUPS + 1).saturating_sub(1));
    let nodes = listing.as_array().cloned().unwrap_or_default();
    let mut used = 0;
    let mut pulls = vec![];
    for n in &nodes {
        let number = n["number"].as_i64().unwrap_or(0);
        let mut files = vec![];
        let mut done = false;
        let mut page = 1;
        while !done && used < budget {
            let (batch, _) = request(agent, "GET", &format!("{base}/pulls/{number}/files?per_page=100&page={page}"), None, None)?;
            used += 1;
            page += 1;
            let batch = batch.as_array().cloned().unwrap_or_default();
            files.extend(batch.iter().filter_map(|f| f["filename"].as_str().map(String::from)));
            done = batch.len() < 100;
        }
        if !done {
            break;
        }
        pulls.push(PullRequest {
            number,
            title: n["title"].as_str().unwrap_or("").into(),
            url: pull_url(repo, n["html_url"].as_str(), number),
            author: n["user"]["login"].as_str().map(String::from),
            updated_at: n["updated_at"].as_str().unwrap_or("").into(),
            draft: n["draft"].as_bool().unwrap_or(false),
            files,
        });
    }
    // A full page of 100 may mean there are more open PRs than the listing returned.
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

    #[test]
    fn rejects_hosts_and_branches_that_could_inject_syntax() {
        // Path or URL syntax in the host does not match at all.
        assert_eq!(GitHubRepo::parse(r"https://github.x\..\../o/r").unwrap(), None);
        assert_eq!(GitHubRepo::parse("https://github.com%2f@evil/o/r").unwrap(), None);
        assert_eq!(GitHubRepo::parse("git@github.x?y:o/r").unwrap(), None);
        // A branch that git could read as an option, or that is no valid ref name, is an error.
        for b in ["--upload-pack=touch%20x", "-x", "a..b", "a b", "a~1", "a:b", r"a\b"] {
            assert!(GitHubRepo::parse(&format!("https://github.com/o/r/tree/{b}")).is_err(), "{b}");
        }
        assert!(GitHubRepo::parse("github:o/r@--upload-pack=x").is_err());
        assert_eq!(GitHubRepo::parse("github:o/r@feature/x-1").unwrap().unwrap().branch.as_deref(), Some("feature/x-1"));
    }

    #[test]
    fn sends_environment_tokens_only_where_they_belong() {
        let public = GitHubRepo::parse("https://github.com/o/r").unwrap().unwrap();
        let enterprise = GitHubRepo::parse("https://github.acme.example/o/r").unwrap().unwrap();
        let env = |vars: &'static [(&'static str, &'static str)]| move |k: &str| vars.iter().find(|v| v.0 == k).map(|v| v.1.to_string());
        let plain = env(&[("GH_TOKEN", "public"), ("GH_ENTERPRISE_TOKEN", "enterprise")]);
        assert_eq!(env_token(&public, &plain).as_deref(), Some("public"));
        // A remote URL naming some other "github" host never gets GH_TOKEN, nor the Enterprise token without opt-in.
        assert_eq!(env_token(&enterprise, &plain), None);
        assert!(!opted_in(&enterprise, &plain));
        let opt_in = env(&[("GH_TOKEN", "public"), ("GH_ENTERPRISE_TOKEN", "enterprise"), ("GH_HOST", "GitHub.Acme.Example")]);
        assert!(opted_in(&enterprise, &opt_in));
        assert_eq!(env_token(&enterprise, &opt_in).as_deref(), Some("enterprise"));
        let other = env(&[("GH_ENTERPRISE_TOKEN", "enterprise"), ("GH_HOST", "github.other.example")]);
        assert_eq!(env_token(&enterprise, &other), None);
    }

    #[test]
    fn keeps_only_web_links_on_the_repository_host() {
        let repo = GitHubRepo::parse("https://github.com/o/r").unwrap().unwrap();
        assert_eq!(pull_url(&repo, Some("https://github.com/o/r/pull/7"), 7), "https://github.com/o/r/pull/7");
        for bad in [None, Some("file:///etc/passwd"), Some("javascript:alert(1)"), Some("http://github.com/o/r/pull/7"),
            Some("https://github.com.evil.example/x"), Some("https://github.com/o/r/pull/7 x"), Some("https://github.com")] {
            assert_eq!(pull_url(&repo, bad, 7), "https://github.com/o/r/pull/7", "{bad:?}");
        }
    }

    #[test]
    fn tells_rate_limits_from_missing_access() {
        assert_eq!(failure(200, Some("59"), false), None);
        assert_eq!(failure(403, Some("0"), false).as_deref(), Some("rate limited (HTTP 403)"));
        assert_eq!(failure(403, Some("4000"), true).as_deref(), Some("rate limited (HTTP 403)"));
        assert_eq!(failure(429, None, false).as_deref(), Some("rate limited (HTTP 429)"));
        assert_eq!(failure(403, Some("4999"), false).as_deref(), Some("access denied (HTTP 403)"));
        assert_eq!(failure(403, None, false).as_deref(), Some("access denied (HTTP 403)"));
        assert_eq!(failure(401, None, false).as_deref(), Some("token rejected (HTTP 401)"));
        assert_eq!(failure(404, None, false).as_deref(), Some("HTTP 404"));
    }
}
