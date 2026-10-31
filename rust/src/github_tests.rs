//! End-to-end tests of the GitHub integration and its git plumbing, the mirror of the Java edition's
//! `PullRequestsFetchTest`, `RepoClonerTest`, `PrefetchTest`, `GitHubInfoTest`, `GitTest`, `GitHubRepoTest` and
//! `CityGitHubTest` and of the D edition's `github_tests.d`: the same scenarios with the same expectations.
//!
//! - Pull requests are fetched from [`FakeGitHub`], an in-house stand-in for the GitHub API on the loopback interface
//!   (built on `tiny_http`, already a dependency), through the [`Env`] seam of [`fetch_with`].
//! - The clone cache clones local bare repositories through `file://` URLs into a cache in a temporary directory
//!   ([`clone_into`], the seam of [`clone_or_update`]).
//! - Fake programs (`gh`, a hanging `git`) are shell scripts found with [`git::lookup`] on a `PATH` of their own, so
//!   these tests are Unix-only where they need one.

use super::*;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

// ------------------------------------------------------------------ the fake GitHub API

/// One request as the fake saw it: `target` is the path with the query string.
#[derive(Clone, Debug)]
struct Request {
    method: String,
    target: String,
    authorization: Option<String>,
    body: String,
}

/// An answer: status, extra headers and body.
struct Reply {
    status: u16,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl Reply {
    fn json(body: impl Into<String>) -> Reply {
        Reply { status: 200, headers: vec![], body: body.into().into_bytes() }
    }

    fn json_with(body: impl Into<String>, headers: &[(&str, &str)]) -> Reply {
        Reply { headers: headers.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(), ..Reply::json(body) }
    }

    fn status(status: u16, headers: &[(&str, &str)]) -> Reply {
        Reply { status, ..Reply::json_with(r#"{"message":"no"}"#, headers) }
    }
}

/// The fake API: records every request and answers it with the test's function, on 127.0.0.1 at a free port, until
/// it is dropped.
struct FakeGitHub {
    server: Arc<tiny_http::Server>,
    requests: Arc<Mutex<Vec<Request>>>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl FakeGitHub {
    fn new(answer: impl Fn(&Request) -> Reply + Send + 'static) -> FakeGitHub {
        let server = Arc::new(tiny_http::Server::http("127.0.0.1:0").unwrap());
        let requests = Arc::new(Mutex::new(vec![]));
        let (s, r) = (server.clone(), requests.clone());
        let thread = std::thread::spawn(move || {
            for mut req in s.incoming_requests() {
                let mut body = String::new();
                let _ = req.as_reader().read_to_string(&mut body);
                let request = Request {
                    method: req.method().as_str().to_string(),
                    target: req.url().to_string(),
                    authorization: req.headers().iter().find(|h| h.field.equiv("Authorization")).map(|h| h.value.to_string()),
                    body,
                };
                r.lock().unwrap().push(request.clone());
                let reply = answer(&request);
                let mut response = tiny_http::Response::from_data(reply.body).with_status_code(reply.status);
                for (k, v) in &reply.headers {
                    response.add_header(tiny_http::Header::from_bytes(k.as_bytes(), v.as_bytes()).unwrap());
                }
                // The client may stop reading (the response size cap).
                let _ = req.respond(response);
            }
        });
        FakeGitHub { server, requests, thread: Some(thread) }
    }

    /// `http://127.0.0.1:<port>`.
    fn base(&self) -> String {
        format!("http://127.0.0.1:{}", self.server.server_addr().to_ip().unwrap().port())
    }

    fn requests(&self) -> Vec<Request> {
        self.requests.lock().unwrap().clone()
    }

    /// The distinct Authorization headers received ("null" for none), in order.
    fn authorizations(&self) -> Vec<String> {
        let mut out: Vec<String> = vec![];
        for r in self.requests() {
            let a = r.authorization.unwrap_or_else(|| "null".into());
            if !out.contains(&a) {
                out.push(a);
            }
        }
        out
    }

    /// Fetches `repo`'s pull requests from this server with the environment variables `vars` (and programs found on
    /// their `PATH` only).
    fn fetch(&self, repo: &GitHubRepo, vars: &[(&str, &str)]) -> Result<Option<(Vec<PullRequest>, String)>, String> {
        let vars: HashMap<String, String> = vars.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        let get = |k: &str| vars.get(k).cloned();
        let env = Env {
            vars: &get,
            programs: &|name| git::lookup(name, &get),
            rest_api: format!("{}/api/v3", self.base()),
            graphql_api: format!("{}/api/graphql", self.base()),
        };
        fetch_with(repo, &env)
    }

    /// A fetch that must succeed and contact the server.
    fn pulls(&self, repo: &GitHubRepo, vars: &[(&str, &str)]) -> (Vec<PullRequest>, String) {
        self.fetch(repo, vars).unwrap().unwrap()
    }

    /// The message of a fetch that must fail.
    fn failure(&self, repo: &GitHubRepo, vars: &[(&str, &str)]) -> String {
        self.fetch(repo, vars).unwrap_err()
    }
}

impl Drop for FakeGitHub {
    fn drop(&mut self) {
        self.server.unblock();
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

fn public() -> GitHubRepo {
    GitHubRepo::parse("https://github.com/o/r").unwrap().unwrap()
}

fn enterprise() -> GitHubRepo {
    GitHubRepo::parse("https://github.acme.example/o/r").unwrap().unwrap()
}

/// The largest response body accepted.
const MAX_BODY: usize = 10 * 1024 * 1024;

// ------------------------------------------------------------------ response builders

/// A JSON string literal, or null.
fn q(s: Option<&str>) -> String {
    s.map_or("null".into(), |s| format!("\"{s}\""))
}

/// A GraphQL files connection: `paths`, and whether (and after which cursor) more follow.
fn files(paths: &[String], more: bool, cursor: Option<&str>) -> String {
    let nodes: Vec<String> = paths.iter().map(|p| format!("{{\"path\":\"{p}\"}}")).collect();
    format!("{{\"pageInfo\":{{\"hasNextPage\":{more},\"endCursor\":{}}},\"nodes\":[{}]}}", q(cursor), nodes.join(","))
}

/// A GraphQL pull request node.
fn node(number: i64, url: &str, author: Option<&str>, draft: bool, files: &str) -> String {
    let author = author.map_or("null".into(), |a| format!("{{\"login\":\"{a}\"}}"));
    format!(
        "{{\"number\":{number},\"title\":\"PR {number}\",\"url\":\"{url}\",\"updatedAt\":\"2026-10-0{}T10:00:00Z\",\"isDraft\":{draft},\"author\":{author},\"files\":{files}}}",
        number % 9 + 1
    )
}

/// A GraphQL page of pull requests.
fn page(nodes: &[String], more: bool, cursor: Option<&str>) -> String {
    format!(
        "{{\"data\":{{\"repository\":{{\"pullRequests\":{{\"pageInfo\":{{\"hasNextPage\":{more},\"endCursor\":{}}},\"nodes\":[{}]}}}}}}}}",
        q(cursor),
        nodes.join(",")
    )
}

/// The answer to a files query: the next page of one PR's files.
fn files_page(connection: &str) -> String {
    format!("{{\"data\":{{\"repository\":{{\"pullRequest\":{{\"files\":{connection}}}}}}}}}")
}

/// `src/f000.java` … for the numbers `from` (inclusive) to `to` (exclusive).
fn paths(from: usize, to: usize) -> Vec<String> {
    (from..to).map(|i| format!("src/f{i:03}.java")).collect()
}

/// A REST listing of the pull requests `numbers`.
fn listing(numbers: impl IntoIterator<Item = i64>) -> String {
    let pulls: Vec<String> = numbers
        .into_iter()
        .map(|n| format!("{{\"number\":{n},\"title\":\"PR {n}\",\"html_url\":\"https://github.com/o/r/pull/{n}\",\"user\":{{\"login\":\"u{n}\"}},\"updated_at\":\"2026-10-01T10:00:00Z\",\"draft\":false}}"))
        .collect();
    format!("[{}]", pulls.join(","))
}

/// A REST page of files.
fn rest_files(paths: &[String]) -> String {
    let files: Vec<String> = paths.iter().map(|p| format!("{{\"filename\":\"{p}\"}}")).collect();
    format!("[{}]", files.join(","))
}

/// The GraphQL variables of a request.
fn variables(r: &Request) -> Value {
    serde_json::from_str::<Value>(&r.body).unwrap()["variables"].clone()
}

/// Answers the REST listing with `listing` and `headers`, and `/pulls/<n>/files?per_page=100&page=<p>` with
/// `files(n, p)`.
fn rest_answer(r: &Request, listing: &str, headers: &[(&str, &str)], files: impl Fn(i64, i64) -> Vec<String>) -> Reply {
    let prefix = "/api/v3/repos/o/r/pulls";
    if r.target == format!("{prefix}?state=open&sort=updated&direction=desc&per_page=100") {
        return Reply::json_with(listing, headers);
    }
    let rest = &r.target[prefix.len() + 1..];
    let number = rest[..rest.find('/').unwrap()].parse().unwrap();
    let page = rest[rest.rfind("&page=").unwrap() + 6..].parse().unwrap();
    Reply::json(rest_files(&files(number, page)))
}

fn numbers(pulls: &[PullRequest]) -> Vec<i64> {
    pulls.iter().map(|p| p.number).collect()
}

/// The fields of a pull request, for comparison.
fn fields(p: &PullRequest) -> (i64, &str, &str, Option<&str>, &str, bool, &[String]) {
    (p.number, &p.title, &p.url, p.author.as_deref(), &p.updated_at, p.draft, &p.files)
}

// ------------------------------------------------------------------ GraphQL

#[test]
fn pages_through_pull_requests_and_their_files_with_graphql() {
    let fake = FakeGitHub::new(|r| {
        let v = variables(r);
        if !v["number"].is_null() {
            // The files of PR 2 after the first 100: two more pages.
            return match v["cursor"].as_str() {
                Some("f1") => Reply::json(files_page(&files(&paths(100, 200), true, Some("f2")))),
                Some("f2") => Reply::json(files_page(&files(&paths(200, 205), false, Some("f3")))),
                _ => Reply::status(404, &[]),
            };
        }
        if v["cursor"].is_null() {
            return Reply::json(page(
                &[
                    node(1, "https://github.com/o/r/pull/1", Some("ann"), false, &files(&["src/A.java".into(), "src/B.java".into()], false, Some("x"))),
                    node(2, "https://github.com/o/r/pull/2", Some("bob"), false, &files(&paths(0, 100), true, Some("f1"))),
                ],
                true,
                Some("p1"),
            ));
        }
        // The second page: a draft by a deleted user, with a link that is not a web page on the host.
        Reply::json(page(&[node(3, "javascript:alert(1)", None, true, &files(&[], false, None))], false, Some("p2")))
    });
    let (pulls, source) = fake.pulls(&public(), &[("GH_TOKEN", "t0k")]);
    assert_eq!(source, "all open PRs");
    assert_eq!(numbers(&pulls), [1, 2, 3]);
    let ab = ["src/A.java".to_string(), "src/B.java".to_string()];
    assert_eq!(fields(&pulls[0]), (1, "PR 1", "https://github.com/o/r/pull/1", Some("ann"), "2026-10-02T10:00:00Z", false, &ab[..]));
    assert_eq!(pulls[1].files, paths(0, 205));
    assert_eq!(fields(&pulls[2]), (3, "PR 3", "https://github.com/o/r/pull/3", None, "2026-10-04T10:00:00Z", true, &[][..]));
    // Four POSTs, each with the token: PR page 1, the two further file pages of PR 2, PR page 2.
    let requests = fake.requests();
    assert_eq!(requests.len(), 4);
    let mut sent = vec![];
    for r in &requests {
        assert_eq!((r.method.as_str(), r.target.as_str(), r.authorization.as_deref()), ("POST", "/api/graphql", Some("Bearer t0k")));
        let v = variables(r);
        assert_eq!((v["owner"].as_str(), v["name"].as_str()), (Some("o"), Some("r")));
        let cursor = v["cursor"].as_str().unwrap_or("null").to_string();
        sent.push(match v["number"].as_i64() {
            Some(n) => format!("{n}@{cursor}"),
            None => cursor,
        });
    }
    assert_eq!(sent, ["null", "2@f1", "2@f2", "p1"]);
}

#[test]
fn stops_after_six_graphql_pages() {
    let served = Mutex::new(0);
    let fake = FakeGitHub::new(move |_| {
        let mut n = served.lock().unwrap();
        *n += 1;
        let n = *n;
        Reply::json(page(&[node(n, &format!("https://github.com/o/r/pull/{n}"), Some("ann"), false, &files(&["a.java".into()], false, None))], true, Some(&format!("p{n}"))))
    });
    let (pulls, source) = fake.pulls(&public(), &[("GH_TOKEN", "t0k")]);
    assert_eq!(source, "latest 6 open PRs");
    assert_eq!(numbers(&pulls), [1, 2, 3, 4, 5, 6]);
    assert_eq!(fake.requests().len(), 6);
}

#[test]
fn reports_graphql_errors_and_missing_repositories() {
    let fake = FakeGitHub::new(|_| Reply::json(r#"{"errors":[{"message":"Something went wrong"}]}"#));
    assert_eq!(fake.failure(&public(), &[("GH_TOKEN", "t0k")]), "Something went wrong");
    let fake = FakeGitHub::new(|_| Reply::json(r#"{"data":{"repository":null}}"#));
    assert_eq!(fake.failure(&public(), &[("GH_TOKEN", "t0k")]), "repository not found or not accessible");
}

// ------------------------------------------------------------------ REST

#[test]
fn falls_back_to_rest_without_a_token() {
    // PR 5 changes two files; PR 6 changes 103, so its files take two pages.
    let fake = FakeGitHub::new(|r| {
        rest_answer(r, &listing([5, 6]), &[("x-ratelimit-remaining", "59")], |n, p| match (n, p) {
            (5, _) => vec!["a.java".into(), "b.java".into()],
            (_, 1) => paths(0, 100),
            _ => paths(100, 103),
        })
    });
    let (pulls, source) = fake.pulls(&public(), &[]);
    assert_eq!(source, "all open PRs");
    assert_eq!(pulls.len(), 2);
    let ab = ["a.java".to_string(), "b.java".to_string()];
    assert_eq!(fields(&pulls[0]), (5, "PR 5", "https://github.com/o/r/pull/5", Some("u5"), "2026-10-01T10:00:00Z", false, &ab[..]));
    assert_eq!(pulls[1].files, paths(0, 103));
    let targets: Vec<String> = fake.requests().iter().map(|r| r.target.clone()).collect();
    assert_eq!(
        targets,
        [
            "/api/v3/repos/o/r/pulls?state=open&sort=updated&direction=desc&per_page=100",
            "/api/v3/repos/o/r/pulls/5/files?per_page=100&page=1",
            "/api/v3/repos/o/r/pulls/6/files?per_page=100&page=1",
            "/api/v3/repos/o/r/pulls/6/files?per_page=100&page=2",
        ]
    );
    assert!(fake.requests().iter().all(|r| r.method == "GET" && r.authorization.is_none()));
}

#[test]
fn spends_only_the_anonymous_request_budget() {
    // Three requests left: one is kept in reserve, so the files of two PRs are fetched and the third PR is left out.
    let fake = FakeGitHub::new(|r| rest_answer(r, &listing([1, 2, 3]), &[("x-ratelimit-remaining", "3")], |_, _| vec!["a.java".into()]));
    let (pulls, source) = fake.pulls(&public(), &[]);
    assert_eq!(source, "latest 2 open PRs (no token)");
    assert_eq!(numbers(&pulls), [1, 2]);
    assert_eq!(fake.requests().len(), 3);
    // A PR whose files do not fit into the budget is left out rather than shown with some of its files.
    let fake = FakeGitHub::new(|r| {
        rest_answer(r, &listing([1, 2]), &[("x-ratelimit-remaining", "3")], |n, p| if n == 1 && p < 3 { paths(0, 100) } else { vec!["a.java".into()] })
    });
    let (pulls, source) = fake.pulls(&public(), &[]);
    assert_eq!(source, "latest 0 open PRs (no token)");
    assert!(pulls.is_empty());
    assert_eq!(fake.requests().len(), 3);
    // Without a rate-limit header, at most 30 file requests are made.
    let fake = FakeGitHub::new(|r| rest_answer(r, &listing(1..=40), &[], |_, _| vec!["a.java".into()]));
    let (pulls, source) = fake.pulls(&public(), &[]);
    assert_eq!(source, "latest 30 open PRs (no token)");
    assert_eq!(pulls.len(), 30);
    assert_eq!(fake.requests().len(), 31);
    // No requests left at all: the listing alone.
    let fake = FakeGitHub::new(|r| rest_answer(r, &listing([1]), &[("x-ratelimit-remaining", "0")], |_, _| vec!["a.java".into()]));
    assert_eq!(fake.pulls(&public(), &[]).1, "latest 0 open PRs (no token)");
    assert_eq!(fake.requests().len(), 1);
}

// ------------------------------------------------------------------ failures and limits

#[test]
fn classifies_failed_requests() {
    let cases: [(u16, &[(&str, &str)], &str); 6] = [
        (401, &[], "token rejected (HTTP 401)"),
        (403, &[("x-ratelimit-remaining", "0")], "rate limited (HTTP 403)"),
        (403, &[("retry-after", "60")], "rate limited (HTTP 403)"),
        (429, &[], "rate limited (HTTP 429)"),
        (403, &[("x-ratelimit-remaining", "4999")], "access denied (HTTP 403)"),
        (502, &[], "HTTP 502"),
    ];
    for (status, headers, message) in cases {
        let fake = FakeGitHub::new(move |_| Reply::status(status, headers));
        assert_eq!(fake.failure(&public(), &[("GH_TOKEN", "t0k")]), message, "{status} {headers:?}");
        assert_eq!(fake.failure(&public(), &[]), message, "{status} {headers:?}");
    }
}

/// A GraphQL answer with no PRs, padded with spaces to exactly `size` bytes.
fn padded(size: usize) -> String {
    let json = page(&[], false, None);
    format!("{json}{}", " ".repeat(size - json.len()))
}

#[test]
fn accepts_responses_up_to_exactly_ten_megabytes() {
    for size in [MAX_BODY - 1, MAX_BODY] {
        let fake = FakeGitHub::new(move |_| Reply::json(padded(size)));
        assert_eq!(fake.pulls(&public(), &[("GH_TOKEN", "t0k")]).1, "all open PRs", "size {size}");
    }
    let fake = FakeGitHub::new(|_| Reply::json(padded(MAX_BODY + 1)));
    assert_eq!(fake.failure(&public(), &[("GH_TOKEN", "t0k")]), "response larger than 10485760 bytes");
}

/// A GraphQL answer with no PRs whose JSON nests `depth` arrays and objects deep: the answer's object and `depth - 1`
/// arrays, the innermost holding `0` or, with `empty`, nothing.
fn nested(depth: usize, empty: bool) -> String {
    format!(
        "{{\"data\":{{\"repository\":{{\"pullRequests\":{{\"pageInfo\":{{\"hasNextPage\":false}},\"nodes\":[]}}}}}},\"pad\":{}{}{}}}",
        "[".repeat(depth - 1),
        if empty { "" } else { "0" },
        "]".repeat(depth - 1)
    )
}

#[test]
fn accepts_json_nested_up_to_127_levels() {
    // Only arrays and objects count, whatever the innermost value; brackets in strings do not count at all.
    for depth in [126, 127] {
        for empty in [false, true] {
            let fake = FakeGitHub::new(move |_| Reply::json(nested(depth, empty)));
            assert_eq!(fake.pulls(&public(), &[("GH_TOKEN", "t0k")]).1, "all open PRs", "{depth} {empty}");
        }
    }
    let fake = FakeGitHub::new(|_| Reply::json(page(&[], false, Some(&"[".repeat(300)))));
    assert_eq!(fake.pulls(&public(), &[("GH_TOKEN", "t0k")]).1, "all open PRs");
    for depth in [128, 100_000] {
        for empty in [false, true] {
            let fake = FakeGitHub::new(move |_| Reply::json(nested(depth, empty)));
            fake.failure(&public(), &[("GH_TOKEN", "t0k")]);
        }
    }
}

#[test]
fn refuses_redirects_to_another_host_or_to_plain_http() {
    // The other "host" is another port: an origin of its own. It is never contacted, so the token stays here.
    let other = empty_api();
    let location = format!("{}/api/graphql", other.base());
    let fake = FakeGitHub::new(move |_| Reply::status(307, &[("Location", &location)]));
    assert_eq!(fake.failure(&public(), &[("GH_TOKEN", "t0k")]), "redirect to another host refused");
    assert!(other.requests().is_empty());
    // The same host and port, but not https (the fake speaks plain HTTP): refused as well, after one request.
    for status in [301, 302, 307, 308] {
        let fake = FakeGitHub::new(move |_| Reply::status(status, &[("Location", "/api/graphql-moved")]));
        assert_eq!(fake.failure(&public(), &[("GH_TOKEN", "t0k")]), "redirect to another host refused");
        assert_eq!(fake.requests().len(), 1);
    }
}

/// The redirect rule of every edition: only to https on the same host (any case) and port, where an explicit port
/// differs from none. The same table is in the Java and D editions.
#[test]
fn follows_redirects_only_to_https_on_the_same_host_and_port() {
    let from = "https://api.github.com/repos/o/r/pulls";
    assert!(may_follow(from, "https://api.github.com/repositories/1/pulls"));
    assert!(may_follow(from, "https://API.GitHub.com/x"));
    assert!(may_follow("https://github.acme.example/api/v3/x", "https://github.acme.example/api/v3/y?page=2"));
    assert!(may_follow("https://h.example:8443/a", "https://h.example:8443/b"));
    assert!(!may_follow(from, "http://api.github.com/x"));
    assert!(!may_follow(from, "https://evil.example/x"));
    assert!(!may_follow(from, "https://api.github.com:8443/x"));
    assert!(!may_follow(from, "https://api.github.com:443/x"));
    assert!(!may_follow(from, "file:///etc/passwd"));
    assert!(!may_follow(from, "https://api.github.com.evil.example/x"));
    // Locations are resolved against the request's URL first.
    assert_eq!(resolve(from, "https://x.example/y"), "https://x.example/y");
    assert_eq!(resolve(from, "/repositories/1"), "https://api.github.com/repositories/1");
    assert_eq!(resolve(from, "//api.github.com/z"), "https://api.github.com/z");
    assert_eq!(resolve("https://h.example/a/b?q=/x", "c"), "https://h.example/a/c");
}

// ------------------------------------------------------------------ which token goes where

/// Answers GraphQL with an empty page and REST with an empty listing.
fn empty_api() -> FakeGitHub {
    FakeGitHub::new(|r| Reply::json(if r.method == "POST" { page(&[], false, None) } else { "[]".into() }))
}

#[test]
fn chooses_the_token_by_host() {
    // github.com: GH_TOKEN, else GITHUB_TOKEN (trimmed); a blank one does not count.
    let fake = empty_api();
    fake.pulls(&public(), &[("GH_TOKEN", "  public  "), ("GITHUB_TOKEN", "other"), ("GH_ENTERPRISE_TOKEN", "ent")]);
    assert_eq!(fake.authorizations(), ["Bearer public"]);
    let fake = empty_api();
    fake.pulls(&public(), &[("GH_TOKEN", " "), ("GITHUB_TOKEN", "second")]);
    assert_eq!(fake.authorizations(), ["Bearer second"]);
    // An Enterprise host without the GH_HOST opt-in (and no gh login) is not contacted at all.
    let fake = empty_api();
    assert_eq!(fake.fetch(&enterprise(), &[("GH_TOKEN", "public"), ("GH_ENTERPRISE_TOKEN", "ent")]).unwrap().map(|r| r.1), None);
    assert!(fake.requests().is_empty());
    // Opted in without a token: anonymous REST. With GH_ENTERPRISE_TOKEN (or GITHUB_ENTERPRISE_TOKEN): GraphQL.
    let fake = empty_api();
    assert_eq!(fake.pulls(&enterprise(), &[("GH_HOST", "github.acme.example"), ("GH_TOKEN", "public")]).1, "all open PRs");
    assert_eq!(fake.authorizations(), ["null"]);
    assert_eq!(fake.requests()[0].method, "GET");
    let fake = empty_api();
    fake.pulls(&enterprise(), &[("GH_HOST", "GitHub.Acme.Example"), ("GH_TOKEN", "public"), ("GITHUB_ENTERPRISE_TOKEN", "ent2")]);
    assert_eq!(fake.authorizations(), ["Bearer ent2"]);
}

/// Writes an executable shell script `name` into `dir`.
#[cfg(unix)]
fn script(dir: &Path, name: &str, body: &str) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    let path = dir.join(name);
    std::fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
    path
}

/// Writes a fake `gh` into `dir` that records its arguments in `dir/args`, prints `out` and exits with `exit`.
#[cfg(unix)]
fn fake_gh(dir: &Path, out: &str, exit: i32) {
    script(dir, "gh", &format!("echo \"$@\" > '{}'\nprintf '%s\\n' '{out}'\nexit {exit}", dir.join("args").display()));
}

#[cfg(unix)]
#[test]
fn falls_back_to_the_github_cli_login_for_the_host() {
    let dir = tempfile::tempdir().unwrap();
    let (path, args) = (dir.path().to_str().unwrap(), dir.path().join("args"));
    fake_gh(dir.path(), "gh-secret", 0);
    let read_args = || std::fs::read_to_string(&args).unwrap().trim().to_string();
    let fake = empty_api();
    fake.pulls(&public(), &[("PATH", path)]);
    assert_eq!(fake.authorizations(), ["Bearer gh-secret"]);
    assert_eq!(read_args(), "auth token --hostname=github.com");
    // A gh login to exactly the Enterprise host counts as the opt-in.
    let fake = empty_api();
    fake.pulls(&enterprise(), &[("PATH", path)]);
    assert_eq!(fake.authorizations(), ["Bearer gh-secret"]);
    assert_eq!(read_args(), "auth token --hostname=github.acme.example");
    // An environment token wins, and gh is not even asked.
    std::fs::remove_file(&args).unwrap();
    let fake = empty_api();
    fake.pulls(&public(), &[("PATH", path), ("GH_TOKEN", "env")]);
    assert_eq!(fake.authorizations(), ["Bearer env"]);
    assert!(!args.exists());
    // No login (gh fails): anonymous REST for github.com.
    fake_gh(dir.path(), "", 1);
    let fake = empty_api();
    fake.pulls(&public(), &[("PATH", path)]);
    assert_eq!(fake.authorizations(), ["null"]);
}

// ------------------------------------------------------------------ git repositories

/// Runs `git [-C dir] args…` with a fixed author and no signing, panics unless it succeeds, and returns its stdout
/// trimmed. `no_lazy_fetch`: with lazy fetching of missing objects off (git 2.44+).
fn git_in(dir: Option<&Path>, no_lazy_fetch: bool, args: &[&str]) -> String {
    let mut cmd = std::process::Command::new("git");
    cmd.args(["-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main", "-c", "protocol.file.allow=always"]);
    if let Some(d) = dir {
        cmd.arg("-C").arg(d);
    }
    if no_lazy_fetch {
        cmd.env("GIT_NO_LAZY_FETCH", "1");
    }
    let out = cmd.args(args).stderr(std::process::Stdio::null()).output().unwrap();
    assert!(out.status.success(), "git {args:?}");
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

fn git_(dir: &Path, args: &[&str]) -> String {
    git_in(Some(dir), false, args)
}

/// Writes `file` in the work tree `work` with `content`, commits everything and returns the commit.
fn commit(work: &Path, file: &str, content: &str) -> String {
    let f = work.join(file);
    std::fs::create_dir_all(f.parent().unwrap()).unwrap();
    std::fs::write(f, content).unwrap();
    git_(work, &["add", "-A"]);
    git_(work, &["commit", "-q", "-m", &format!("change {file}")]);
    git_(work, &["rev-parse", "HEAD"])
}

/// The "remote": a work tree `dir/work` with three commits on `main` (A.java v1, then A.java v2 and B.java) and one
/// more on `dev` (C.java), pushed to the bare repository `dir/remote.git`, which allows blobless clones and fetching
/// any object. Returns the bare repository.
fn remote(dir: &Path) -> PathBuf {
    let work = dir.join("work");
    std::fs::create_dir_all(&work).unwrap();
    git_(&work, &["init", "-q"]);
    commit(&work, "A.java", "class A { /* v1 */ }\n");
    std::fs::write(work.join("B.java"), "class B {}\n").unwrap();
    commit(&work, "A.java", "class A { /* v2 */ }\n");
    git_(&work, &["checkout", "-q", "-b", "dev"]);
    commit(&work, "C.java", "class C {}\n");
    git_(&work, &["checkout", "-q", "main"]);
    let bare = dir.join("remote.git");
    git_in(None, false, &["clone", "-q", "--bare", work.to_str().unwrap(), bare.to_str().unwrap()]);
    git_(&bare, &["config", "uploadpack.allowFilter", "true"]);
    git_(&bare, &["config", "uploadpack.allowAnySHA1InWant", "true"]);
    git_(&work, &["remote", "add", "origin", bare.to_str().unwrap()]);
    bare
}

/// A `file://` URL of `dir`, so that git uses its transport (a plain path ignores --filter).
fn file_url(dir: &Path) -> String {
    let p = dir.to_string_lossy().replace('\\', "/");
    format!("file://{}{p}", if p.starts_with('/') { "" } else { "/" })
}

/// How many objects reachable from HEAD are missing from the (partial) repository `repo`.
fn missing(repo: &Path) -> usize {
    git_in(Some(repo), true, &["rev-list", "--objects", "--missing=print", "HEAD"]).lines().filter(|l| l.starts_with('?')).count()
}

fn repo(host: &str, branch: Option<&str>) -> GitHubRepo {
    GitHubRepo { host: host.into(), owner: "o".into(), name: "r".into(), branch: branch.map(String::from) }
}

fn read(p: &Path) -> String {
    std::fs::read_to_string(p).unwrap()
}

// ------------------------------------------------------------------ clone cache

#[test]
fn clones_blobless_into_the_cache_then_updates() {
    let tmp = tempfile::tempdir().unwrap();
    let bare = remote(tmp.path());
    let cache = tmp.path().join("cache");
    let dir = clone_into(&repo("github.com", None), &cache, &file_url(&bare)).unwrap();
    assert_eq!(dir, cache.join("github.com").join("o").join("r"));
    assert_eq!(git_(&dir, &["rev-parse", "HEAD"]), git_(&bare, &["rev-parse", "main"]));
    // Blobless: full history, but the old version of A.java was not downloaded.
    assert_eq!(git_(&dir, &["config", "remote.origin.promisor"]), "true");
    assert_eq!(git_(&dir, &["config", "remote.origin.partialclonefilter"]), "blob:none");
    assert_eq!(missing(&dir), 1);
    assert_eq!(read(&dir.join("A.java")), "class A { /* v2 */ }\n");
    // A new commit upstream, and a local change in the mirror: the update fetches and resets hard.
    let work = tmp.path().join("work");
    let head = commit(&work, "A.java", "class A { /* v3 */ }\n");
    git_(&work, &["push", "-q", "origin", "main"]);
    std::fs::write(dir.join("A.java"), "local edit\n").unwrap();
    assert_eq!(clone_into(&repo("github.com", None), &cache, &file_url(&bare)).unwrap(), dir);
    assert_eq!(git_(&dir, &["rev-parse", "HEAD"]), head);
    assert_eq!(read(&dir.join("A.java")), "class A { /* v3 */ }\n");
}

#[test]
fn checks_out_the_requested_branch() {
    let tmp = tempfile::tempdir().unwrap();
    let bare = remote(tmp.path());
    let cache = tmp.path().join("cache");
    let dir = clone_into(&repo("github.com", Some("dev")), &cache, &file_url(&bare)).unwrap();
    assert_eq!(git_(&dir, &["rev-parse", "HEAD"]), git_(&bare, &["rev-parse", "dev"]));
    assert!(dir.join("C.java").is_file());
    // Updating a branch clone follows that branch (FETCH_HEAD), not the default branch.
    let work = tmp.path().join("work");
    git_(&work, &["checkout", "-q", "dev"]);
    let head = commit(&work, "D.java", "class D {}\n");
    git_(&work, &["push", "-q", "origin", "dev"]);
    clone_into(&repo("github.com", Some("dev")), &cache, &file_url(&bare)).unwrap();
    assert_eq!(git_(&dir, &["rev-parse", "HEAD"]), head);
    assert!(dir.join("D.java").is_file());
}

#[test]
fn reports_git_failures() {
    let tmp = tempfile::tempdir().unwrap();
    let cache = tmp.path().join("cache");
    // Nothing to clone: an error, and no repository left behind.
    assert!(clone_into(&repo("github.com", None), &cache, &file_url(&tmp.path().join("nowhere.git"))).is_err());
    assert!(!cache.join("github.com/o/r/.git").exists());
    // A branch the remote does not have.
    let bare = remote(tmp.path());
    assert!(clone_into(&repo("github.com", Some("missing")), &cache, &file_url(&bare)).is_err());
    // The remote is gone when the mirror is updated.
    let dir = clone_into(&repo("github.com", None), &cache, &file_url(&bare)).unwrap();
    git_(&dir, &["remote", "set-url", "origin", &file_url(&tmp.path().join("gone.git"))]);
    assert!(clone_into(&repo("github.com", None), &cache, &file_url(&bare)).is_err());
}

#[test]
fn refuses_to_clone_outside_the_cache() {
    let tmp = tempfile::tempdir().unwrap();
    let cache = tmp.path().join("cache");
    std::fs::create_dir_all(&cache).unwrap();
    let e = clone_into(&repo("..", None), &cache, &file_url(&tmp.path().join("nowhere.git"))).unwrap_err();
    assert!(e.starts_with("refusing to clone outside the cache"), "{e}");
    assert!(!tmp.path().join("o").exists());
}

#[test]
fn tells_cache_repositories_from_others() {
    let tmp = tempfile::tempdir().unwrap();
    let cache = tmp.path().join("cache");
    let inside = cache.join("github.com/o/r");
    let sibling = tmp.path().join("cache-other/github.com/o/r");
    std::fs::create_dir_all(&inside).unwrap();
    std::fs::create_dir_all(&sibling).unwrap();
    assert!(is_cached_in(&inside, &cache));
    assert!(is_cached_in(&cache, &cache));
    assert!(!is_cached_in(&sibling, &cache));
    assert!(!is_cached_in(tmp.path(), &cache));
    assert!(!is_cached_in(&cache.join("missing"), &cache));
    assert!(!is_cached_in(&inside, &tmp.path().join("no-cache-yet")));
}

#[cfg(unix)]
#[test]
fn compares_real_paths_for_the_cache() {
    let tmp = tempfile::tempdir().unwrap();
    let cache = tmp.path().join("cache");
    let inside = cache.join("github.com/o/r");
    let outside = tmp.path().join("elsewhere");
    std::fs::create_dir_all(&inside).unwrap();
    std::fs::create_dir_all(&outside).unwrap();
    // A link into the cache counts as inside, a link in the cache pointing out of it does not.
    std::os::unix::fs::symlink(&inside, tmp.path().join("link-in")).unwrap();
    std::os::unix::fs::symlink(&outside, cache.join("link-out")).unwrap();
    assert!(is_cached_in(&tmp.path().join("link-in"), &cache));
    assert!(!is_cached_in(&cache.join("link-out"), &cache));
}

// ------------------------------------------------------------------ blobless-clone completion

#[test]
fn fetches_every_missing_object_of_a_partial_clone() {
    let tmp = tempfile::tempdir().unwrap();
    let bare = remote(tmp.path());
    let clone = tmp.path().join("clone");
    git_in(None, false, &["clone", "-q", "--filter=blob:none", &file_url(&bare), clone.to_str().unwrap()]);
    assert_eq!(missing(&clone), 1);
    git::prefetch_missing(&std::fs::canonicalize(&clone).unwrap());
    assert_eq!(missing(&clone), 0);
    // Nothing missing: nothing to do.
    git::prefetch_missing(&std::fs::canonicalize(&clone).unwrap());
    assert_eq!(missing(&clone), 0);
}

#[test]
fn never_fetches_for_a_partial_clone_outside_the_cache() {
    let tmp = tempfile::tempdir().unwrap();
    let bare = remote(tmp.path());
    let clone = tmp.path().join("clone");
    git_in(None, false, &["clone", "-q", "--filter=blob:none", &file_url(&bare), clone.to_str().unwrap()]);
    assert_eq!(missing(&clone), 1);
    let history = crate::history::compute(&std::fs::canonicalize(&clone).unwrap(), true, 2, &HashMap::new());
    assert!(history.is_some());
    // The old A.java could not be read, but it was not fetched either.
    assert_eq!(missing(&clone), 1);
}

// ------------------------------------------------------------------ remote detection

/// A repository in `dir/repo` with one commit (sub/inner/A.java) and the remote `origin` = `url`; its real path.
fn work_repo(dir: &Path, url: Option<&str>) -> PathBuf {
    let repo = dir.join("repo");
    std::fs::create_dir_all(&repo).unwrap();
    git_(&repo, &["init", "-q"]);
    commit(&repo, "sub/inner/A.java", "class A {}\n");
    if let Some(u) = url {
        git_(&repo, &["remote", "add", "origin", u]);
    }
    std::fs::canonicalize(repo).unwrap()
}

fn detect(root: &Path) -> GitHubInfo {
    GitHubInfo::detect(root).unwrap()
}

#[test]
fn links_to_the_pushed_commit_else_to_the_branch() {
    let tmp = tempfile::tempdir().unwrap();
    let repo = work_repo(tmp.path(), Some("git@github.com:o/r.git"));
    // Not on any remote-tracking branch yet: the branch name.
    let info = detect(&repo);
    assert_eq!(info.repo, GitHubRepo { host: "github.com".into(), owner: "o".into(), name: "r".into(), branch: None });
    assert_eq!((info.git_ref.as_str(), info.prefix.as_str()), ("main", ""));
    // Pushed (some remote-tracking branch contains HEAD): the commit.
    let sha = git_(&repo, &["rev-parse", "HEAD"]);
    git_(&repo, &["update-ref", "refs/remotes/origin/main", "HEAD"]);
    assert_eq!(detect(&repo).git_ref, sha);
    // A new local commit is not pushed: the branch again; on a detached HEAD, the commit anyway.
    let local = commit(&repo, "B.java", "class B {}\n");
    assert_eq!(detect(&repo).git_ref, "main");
    git_(&repo, &["checkout", "-q", "--detach"]);
    assert_eq!(detect(&repo).git_ref, local);
    // Pull requests are not part of detection.
    assert!(detect(&repo).pulls.is_none());
}

#[test]
fn prefixes_the_analysed_subdirectory() {
    let tmp = tempfile::tempdir().unwrap();
    let repo = work_repo(tmp.path(), Some("https://github.com/o/r"));
    assert_eq!(detect(&repo.join("sub")).prefix, "sub/");
    assert_eq!(detect(&repo.join("sub/inner")).prefix, "sub/inner/");
}

#[test]
fn finds_the_github_remote() {
    let tmp = tempfile::tempdir().unwrap();
    let r = |host: &str, owner: &str, name: &str| GitHubRepo { host: host.into(), owner: owner.into(), name: name.into(), branch: None };
    // An Enterprise origin.
    let repo = work_repo(&tmp.path().join("a"), Some("https://github.acme.corp/team/app.git"));
    assert_eq!(detect(&repo).repo, r("github.acme.corp", "team", "app"));
    // origin elsewhere: the first remote that is on GitHub.
    let repo = work_repo(&tmp.path().join("b"), Some("https://gitlab.com/o/r.git"));
    git_(&repo, &["remote", "add", "upstream", "ssh://git@github.com/u/p.git"]);
    assert_eq!(detect(&repo).repo, r("github.com", "u", "p"));
    // A GitHub URL that is rejected (".." as owner) counts as none; no remote at all, or no repository: none.
    assert!(GitHubInfo::detect(&work_repo(&tmp.path().join("c"), Some("https://github.com/../x"))).is_none());
    assert!(GitHubInfo::detect(&work_repo(&tmp.path().join("d"), None)).is_none());
    let plain = tmp.path().join("plain");
    std::fs::create_dir_all(&plain).unwrap();
    assert!(GitHubInfo::detect(&std::fs::canonicalize(plain).unwrap()).is_none());
}

// ------------------------------------------------------------------ command time limits

#[cfg(unix)]
#[test]
fn kills_a_command_that_outlives_its_time_limit() {
    let dir = tempfile::tempdir().unwrap();
    let pid_file = dir.path().join("pid");
    // A fake git that writes its process id and then runs `body`, found with dir as the whole PATH.
    let fake = |body: &str| {
        let git = script(dir.path(), "git", &format!("echo $$ > '{}'\n{body}", pid_file.display()));
        let path = dir.path().to_str().unwrap().to_string();
        assert_eq!(git::lookup("git", &|k| (k == "PATH").then(|| path.clone())), Some(git.clone()));
        git
    };
    // In time (a limit far beyond what the command needs, however busy the machine): the output, trimmed; a
    // failure: nothing.
    let plenty = Duration::from_secs(60);
    assert_eq!(git::output_within(&mut Command::new(fake("echo ' ok '")), plenty).as_deref(), Some("ok"));
    assert_eq!(git::output_within(&mut Command::new(fake("echo no; exit 3")), plenty), None);
    // Hanging with stdout open (after some output) for ten minutes: nothing is returned once the limit runs out, and
    // the process is gone. Only the outcome is checked, not how long it took.
    let git = fake("echo partial; exec sleep 600");
    let _ = std::fs::remove_file(&pid_file);
    let start = std::time::Instant::now();
    assert_eq!(git::output_within(&mut Command::new(git), Duration::from_secs(3)), None);
    assert!(start.elapsed() < Duration::from_secs(300), "not killed");
    // The script writes its id first thing; under extreme load it may not have got that far before the kill.
    if let Ok(pid) = std::fs::read_to_string(&pid_file) {
        let pid = pid.trim().to_string();
        let alive = || Command::new("kill").args(["-0", &pid]).stderr(std::process::Stdio::null()).status().unwrap().success();
        let deadline = std::time::Instant::now() + Duration::from_secs(60);
        while alive() && std::time::Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(50));
        }
        assert!(!alive(), "the command still runs");
    }
}

// ------------------------------------------------------------------ URL parsing

/// Every accepted form with what it yields, the inputs that are not GitHub repositories, and those that are errors.
/// The same table is in the Java and D editions.
#[test]
fn parses_every_accepted_form_and_rejects_the_rest() {
    let accepted = [
        // input, host, owner, name, branch ("" = none)
        ("https://github.com/o/r", "github.com", "o", "r", ""),
        ("http://github.com/o/r", "github.com", "o", "r", ""),
        ("https://github.com/o/r.git", "github.com", "o", "r", ""),
        ("https://github.com/o/r/", "github.com", "o", "r", ""),
        ("https://github.com/o/r.git.git", "github.com", "o", "r.git", ""),
        ("https://user:pw@github.com:443/o/r", "github.com", "o", "r", ""),
        ("https://GitHub.COM/Some-Org/My_Repo.js", "github.com", "Some-Org", "My_Repo.js", ""),
        ("  https://github.com/o/r \n", "github.com", "o", "r", ""),
        ("https://github.com/o/r/tree/main", "github.com", "o", "r", "main"),
        ("https://github.com/o/r/tree/release/2.x/", "github.com", "o", "r", "release/2.x"),
        ("https://github.com/o/r.git/tree/x", "github.com", "o", "r", "x"),
        ("git@github.com:o/r.git", "github.com", "o", "r", ""),
        ("git@github.com:o/r", "github.com", "o", "r", ""),
        ("deploy@github.acme.corp:team/app.git", "github.acme.corp", "team", "app", ""),
        ("ssh://git@github.com/o/r.git", "github.com", "o", "r", ""),
        ("ssh://git@github.com:22/o/r", "github.com", "o", "r", ""),
        ("ssh://github.com/o/r/", "github.com", "o", "r", ""),
        ("github:o/r", "github.com", "o", "r", ""),
        ("github:o/r.git", "github.com", "o", "r", ""),
        ("github:o/r@dev", "github.com", "o", "r", "dev"),
        ("github:o/r@feature/x-1", "github.com", "o", "r", "feature/x-1"),
    ];
    for (input, host, owner, name, branch) in accepted {
        let expected = GitHubRepo { host: host.into(), owner: owner.into(), name: name.into(), branch: (!branch.is_empty()).then(|| branch.into()) };
        assert_eq!(GitHubRepo::parse(input), Ok(Some(expected)), "{input}");
    }
    for s in [
        "https://gitlab.com/o/r", "src/main/java", "github.com/o/r", "https://github.com/o", "https://github.com/o/r/issues",
        "https://github.com/o/r/tree/", "https://github.com/o/r?tab=1", "https://github.com/o/r#readme", "ftp://github.com/o/r",
        "file:///github.com/o/r", "github:o", "github:o/r@", "https://github.com/o/r/x", "",
    ] {
        assert_eq!(GitHubRepo::parse(s), Ok(None), "{s}");
    }
    for s in [
        "github:../..", "https://github.com/../r", "git@github.com:o/..", "https://github.com/o/./", "ssh://git@github.com/./r",
        "https://github.com/o/r/tree/a^b", "https://github.com/o/r/tree/a?b", "https://github.com/o/r/tree/a*b",
        "https://github.com/o/r/tree/a[b", "github:o/r@a\u{1}b", "github:o/r@a\u{7f}b", "github:o/r@-",
    ] {
        assert!(GitHubRepo::parse(s).is_err(), "{s}");
    }
    // The URLs derived from a repository.
    let public = GitHubRepo::parse("github:o/r@dev").unwrap().unwrap();
    assert_eq!(public.web_url(), "https://github.com/o/r");
    assert_eq!(public.rest_api(), "https://api.github.com");
    assert_eq!(public.graphql_api(), "https://api.github.com/graphql");
    assert_eq!(public.to_string(), "o/r@dev");
    let enterprise = GitHubRepo::parse("git@github.acme.corp:team/app.git").unwrap().unwrap();
    assert_eq!(enterprise.rest_api(), "https://github.acme.corp/api/v3");
    assert_eq!(enterprise.graphql_api(), "https://github.acme.corp/api/graphql");
    assert_eq!(enterprise.to_string(), "team/app");
}

// ------------------------------------------------------------------ the GitHub part of the city JSON

const PREFIX: &str = "testdata/java-project/";

fn city(github: Option<GitHubInfo>) -> Value {
    let root = std::fs::canonicalize(Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata/java-project")).unwrap();
    let model = crate::city::CityModel::build(&crate::analysis::SourceAnalyzer::new(), &root, true, false, github, None);
    serde_json::from_str(model.json()).unwrap()
}

/// Building id → its `prs` (only the buildings that have any).
fn prs_by_id(city: &Value) -> std::collections::BTreeMap<String, Value> {
    city["buildings"].as_array().unwrap().iter().filter(|b| b.get("prs").is_some()).map(|b| (b["id"].as_str().unwrap().to_string(), b["prs"].clone())).collect()
}

fn pr(number: i64, author: Option<&str>, draft: bool, files: &[&str]) -> PullRequest {
    PullRequest {
        number,
        title: format!("PR {number}"),
        url: format!("https://github.com/acme/shop/pull/{number}"),
        author: author.map(String::from),
        updated_at: "2026-10-01T10:00:00Z".into(),
        draft,
        files: files.iter().map(|f| f.to_string()).collect(),
    }
}

fn pull_json(number: i64, author: Option<&str>, draft: bool, files: usize) -> Value {
    serde_json::json!({ "number": number, "title": format!("PR {number}"), "url": format!("https://github.com/acme/shop/pull/{number}"),
        "author": author, "updatedAt": "2026-10-01T10:00:00Z", "draft": draft, "files": files })
}

fn shop(git_ref: &str, prefix: &str, pulls: Option<Vec<PullRequest>>, source: Option<&str>) -> GitHubInfo {
    GitHubInfo {
        repo: GitHubRepo { host: "github.com".into(), owner: "acme".into(), name: "shop".into(), branch: None },
        git_ref: git_ref.into(),
        prefix: prefix.into(),
        pulls,
        pulls_source: source.map(String::from),
    }
}

#[test]
fn matches_pull_requests_to_buildings_by_repository_path() {
    let registry = format!("{PREFIX}core/src/main/java/com/acme/core/Registry.java");
    let scratch = format!("{PREFIX}Scratch.java");
    let circle = format!("{PREFIX}core/src/main/java/com/acme/core/circle.java");
    let pulls = vec![
        pr(11, Some("ann"), false, &[&registry, "README.md"]),
        pr(12, None, true, &[&registry, &scratch]),
        // Root-relative (no prefix) and differently cased paths match nothing.
        pr(13, Some("bob"), false, &["core/src/main/java/com/acme/core/Circle.java", &circle]),
    ];
    let city = city(Some(shop("0123456789abcdef0123456789abcdef01234567", PREFIX, Some(pulls), Some("all open PRs"))));
    assert_eq!(
        city["github"],
        serde_json::json!({ "host": "github.com", "owner": "acme", "repo": "shop", "url": "https://github.com/acme/shop",
            "ref": "0123456789abcdef0123456789abcdef01234567", "prefix": PREFIX, "pullsSource": "all open PRs",
            "pulls": [pull_json(11, Some("ann"), false, 2), pull_json(12, None, true, 2), pull_json(13, Some("bob"), false, 2)] })
    );
    // Every type of Registry.java has both PRs touching it; Scratch has the second only.
    let mut expected = std::collections::BTreeMap::new();
    for id in ["com.acme.core.Registry", "com.acme.core.Registry.Cursor", "com.acme.core.Registry.Cursor.Step", "com.acme.core.Registry.Entry",
        "com.acme.core.Registry.Listener", "com.acme.core.Registry.Mode", "com.acme.core.Registry.Stats"] {
        expected.insert(id.to_string(), serde_json::json!([0, 1]));
    }
    expected.insert("Scratch".into(), serde_json::json!([1]));
    assert_eq!(prs_by_id(&city), expected);
}

#[test]
fn leaves_pull_requests_out_when_they_were_not_fetched() {
    let city = city(Some(shop("main", "", None, None)));
    assert_eq!(
        city["github"],
        serde_json::json!({ "host": "github.com", "owner": "acme", "repo": "shop", "url": "https://github.com/acme/shop", "ref": "main", "prefix": "" })
    );
    assert!(prs_by_id(&city).is_empty());
    // No GitHub context at all: no section.
    assert!(self::city(None).get("github").is_none());
}

#[test]
fn matches_at_the_repository_top_without_prefix() {
    let city = city(Some(shop("main", "", Some(vec![pr(1, Some("ann"), false, &["Scratch.java"])]), Some("latest 1 open PRs (no token)"))));
    assert_eq!(prs_by_id(&city), [("Scratch".to_string(), serde_json::json!([0]))].into_iter().collect());
    assert_eq!(city["github"]["pullsSource"], "latest 1 open PRs (no token)");
}
