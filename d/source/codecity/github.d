/// GitHub integration: repository URLs, the clone cache, remote detection and open pull requests.
///
/// Runs before the city is built (see `run` in app.d): a GitHub URL on the command line is cloned into a local cache
/// and analysed like any directory; for a local tree with a GitHub remote, `GitHubInfo.detect` works out where the
/// tree lives on GitHub (so the viewer can link to files, blame and history) and `fetchPulls` loads the open pull
/// requests once, for the PR overlay. Rebuilds in watch mode reuse that information and do not contact GitHub again.
///
/// GitHub Enterprise hosts are supported; their APIs live under `/api/v3` and `/api/graphql` on the host itself.
///
/// The repository may come from the analysed tree's own (untrusted) git configuration, so the token is guarded: an
/// environment token for github.com only ever goes to api.github.com, and a GitHub Enterprise host gets a token (or a
/// request at all) only when the user opted in to that very host (see `token` and `fetchPulls`). libcurl does not send
/// the Authorization header on to another host when it follows a redirect; responses are capped at
/// `maxResponseBytes` and JSON at `maxJsonDepth` levels of nesting.
module codecity.github;

import codecity.git : executable, output, realPath, run;
import std.process : Config;
import std.algorithm : canFind, endsWith, startsWith;
import std.array : replace, split;
import std.conv : to;
import std.file : exists, isDir, mkdirRecurse;
import std.format : format;
import std.json;
import std.path : buildPath, dirName, relativePath;
import std.process : environment, spawnProcess, wait;
import std.regex : ctRegex, matchFirst;
import std.stdio : stderr, writefln, writeln;
import std.string : strip, toLower;

/// A repository on github.com or a GitHub Enterprise host.
struct GitHubRepo
{
    string host, owner, name;
    /// Branch named in a `/tree/<branch>` URL or `@branch` shorthand (null for the default branch).
    string branch;

    /// Parses `https://github.com/o/r[/tree/b]`, `git@github.com:o/r.git`, `ssh://…` or `github:owner/repo[@branch]`.
    /// Only hosts containing "github" match. Throws on `.`/`..` path segments and on an invalid branch name
    /// (`badBranch`).
    static bool parse(string text, out GitHubRepo repo)
    {
        // One owner or repository name: letters, digits, `_`, `.` and `-` (no slashes).
        enum segment = `[A-Za-z0-9_.-]+`;
        // A host name: letters, digits, `.` and `-`. The host becomes a directory of the clone cache and part of API
        // and web URLs, so path and URL syntax (`\`, `?`, `#`, `%`, …) must not get in.
        enum host = `([A-Za-z0-9.-]+)`;
        const s = text.strip;
        string stripGit(string n) { return n.endsWith(".git") ? n[0 .. $ - 4] : n; }
        bool ok;
        auto m = s.matchFirst(ctRegex!(`^github:(` ~ segment ~ `)/(` ~ segment ~ `)(?:@(.+))?$`));
        if (!m.empty)
        {
            repo = GitHubRepo("github.com", m[1], stripGit(m[2]), m[3].length ? m[3] : null);
            ok = true;
        }
        else
        {
            // In each pattern: group 1 = host, 2 = owner, 3 = repository; for HTTPS, group 4 = branch. Optional
            // `user@` parts and ports are accepted and dropped. The branch group is lazy so a trailing slash is not
            // part of it, but it may contain slashes itself (`/tree/release/2.x`).
            foreach (i, re; [
                // https://[user@]host[:port]/owner/repo[/tree/branch][/]
                ctRegex!(`^https?://(?:[^@/]+@)?` ~ host ~ `(?::\d+)?/(` ~ segment ~ `)/(` ~ segment ~ `)(?:/tree/(.+?))?/?$`),
                // scp-like SSH: user@host:owner/repo[.git]
                ctRegex!(`^[^@\s]+@` ~ host ~ `:(` ~ segment ~ `)/(` ~ segment ~ `)/?$`),
                // ssh://[user@]host[:port]/owner/repo[.git]
                ctRegex!(`^ssh://(?:[^@/]+@)?` ~ host ~ `(?::\d+)?/(` ~ segment ~ `)/(` ~ segment ~ `)/?$`),
            ])
            {
                auto c = s.matchFirst(re);
                if (!c.empty && c[1].toLower.canFind("github"))
                {
                    repo = GitHubRepo(c[1].toLower, c[2], stripGit(c[3]), i == 0 && c[4].length ? c[4] : null);
                    ok = true;
                    break;
                }
            }
        }
        // The owner and name become directories of the clone cache (`cloneOrUpdate`), so `.` and `..` would escape it.
        if (ok && ([".", ".."].canFind(repo.owner) || [".", ".."].canFind(repo.name)))
            throw new Exception(format("invalid repository %s/%s", repo.owner, repo.name));
        if (ok && repo.branch !is null && badBranch(repo.branch))
        {
            import std.regex : regex, replaceAll;
            throw new Exception("invalid branch name " ~ repo.branch.replaceAll(regex(`\p{Cc}`), "?"));
        }
        return ok;
    }

    /// Whether `branch` is a name git would not accept, or could read as an option: empty, starting with `-`,
    /// containing a control character, a space, one of `~^:?*[\` or `..`. It is passed to `git fetch` and `git clone`.
    static bool badBranch(string branch)
    {
        import std.algorithm : any;
        return branch.length == 0 || branch.startsWith("-") || branch.canFind("..")
            || branch.any!(c => c <= ' ' || c == '\x7f' || "~^:?*[\\".canFind(c));
    }

    string webUrl() const { return format("https://%s/%s/%s", host, owner, name); }
    bool isPublic() const { return host == "github.com"; }
    string restApi() const { return isPublic ? "https://api.github.com" : format("https://%s/api/v3", host); }
    string graphqlApi() const { return isPublic ? "https://api.github.com/graphql" : format("https://%s/api/graphql", host); }

    string toString() const { return owner ~ "/" ~ name ~ (branch.length ? "@" ~ branch : ""); }
}

struct PullRequest
{
    long number;
    string title, url;
    /// null for deleted ("ghost") users
    string author;
    string updatedAt;
    bool draft;
    /// Repository-relative paths of the files it changes (all of them, fetched 100 at a time).
    string[] files;
}

/// GitHub context of an analysed source tree.
struct GitHubInfo
{
    GitHubRepo repo;
    /// Commit SHA (if it exists on the remote) or branch name, for permalinks.
    string gitRef;
    /// Path of the analysed root inside the repository: "" or ending in "/".
    string prefix;
    bool hasPulls;
    PullRequest[] pulls;
    string pullsSource;

    /// Finds a GitHub remote for the repository containing `root`.
    ///
    /// `origin` is preferred; otherwise the first remote (in `git remote -v` order) with a GitHub URL is used. False
    /// when `root` is not in a git work tree or no remote points at GitHub. Pull requests are not fetched here.
    static bool detect(string root, out GitHubInfo info)
    {
        auto top = run(root, "rev-parse", "--show-toplevel");
        if (top is null) return false;
        GitHubRepo repo;
        bool found;
        // The URLs come from the analysed repository's configuration, so one that `parse` rejects must not end the run.
        bool parseRemote(string url)
        {
            try return GitHubRepo.parse(url, repo);
            catch (Exception) return false;
        }
        if (auto url = run(root, "remote", "get-url", "origin"))
            found = parseRemote(url);
        if (!found)
            if (auto remotes = run(root, "remote", "-v"))
                foreach (line; remotes.split("\n"))
                {
                    auto parts = line.split;
                    if (parts.length >= 2 && parseRemote(parts[1]))
                    {
                        found = true;
                        break;
                    }
                }
        if (!found) return false;
        auto sha = run(root, "rev-parse", "HEAD");
        if (sha is null) sha = "HEAD";
        // a local-only commit would 404 on GitHub; fall back to the branch name then
        auto containing = run(root, "branch", "-r", "--contains", "HEAD");
        const pushed = containing !is null && containing.strip.length > 0;
        // On a detached HEAD, --abbrev-ref prints "HEAD"; there is no branch name to fall back to then, so use the SHA.
        auto branch = run(root, "rev-parse", "--abbrev-ref", "HEAD");
        if (branch is null || branch == "HEAD") branch = sha;
        string prefix = relativePath(root, realPath(top)).replace("\\", "/");
        prefix = prefix == "." ? "" : prefix ~ "/";
        info = GitHubInfo(repo, pushed ? sha : branch, prefix);
        return true;
    }
}

// ------------------------------------------------------------------ clone cache

/// Where cloned repositories are kept: `$XDG_CACHE_HOME/codecity/repos`, or `~/.cache/codecity/repos`.
string cacheRoot()
{
    auto xdg = environment.get("XDG_CACHE_HOME", "");
    const base = xdg.length ? xdg : buildPath(environment.get("HOME", "."), ".cache");
    return buildPath(base, "codecity", "repos");
}

/// Whether `dir` lies in the clone cache. Such a repository was cloned by CodeCity, so its configuration is git's own
/// and the user's, not the analysed project's: only there may the history replay fetch missing objects
/// (`codecity.git.gitArgs`). Both sides are compared as real paths.
bool isCached(string dir)
{
    const cache = realPath(cacheRoot());
    if (!cache.exists) return false;
    const d = realPath(dir);
    return d == cache || d.startsWith(cache ~ "/");
}

/// Clones blobless (full history, current files only) or fetches the latest state into an existing mirror.
///
/// The clone lives at `<cacheRoot>/<host>/<owner>/<name>` and is reused on later runs. `--filter=blob:none`
/// downloads every commit and tree, which is all `git log --name-only` needs for the history metrics, but file
/// contents only for the checked-out revision. Returns the clone's directory; throws if git fails.
string cloneOrUpdate(GitHubRepo repo)
{
    const dir = buildPath(cacheRoot(), repo.host, repo.owner, repo.name);
    // Runs git with the terminal's stdin/stdout/stderr, so its progress output and any credential prompt are
    // visible to the user.
    void interactive(string[] args)
    {
        auto git = executable("git");
        if (git is null) throw new Exception("git not found on the PATH");
        auto status = wait(spawnProcess([git] ~ args));
        if (status != 0) throw new Exception(format("git %-(%s %) failed (exit %d)", args, status));
    }
    if (buildPath(dir, ".git").exists)
    {
        { import app : printable; writefln("Updating %s in %s …", printable(repo.to!string), printable(dir)); }
        // "--": a branch can never be taken for an option (and `parse` rejects one starting with '-' anyway).
        auto fetch = ["-C", dir, "fetch", "--prune", "--", "origin"];
        if (repo.branch.length) fetch ~= repo.branch;
        interactive(fetch);
        // the mirror is ours alone, so a hard reset to what we just fetched is safe
        interactive(["-C", dir, "reset", "--quiet", "--hard", repo.branch.length ? "FETCH_HEAD" : "origin/HEAD"]);
    }
    else
    {
        { import app : printable; writefln("Cloning %s into %s …", printable(repo.webUrl), printable(dir)); }
        mkdirRecurse(dir.dirName);
        auto args = ["clone", "--filter=blob:none"];
        if (repo.branch.length) args ~= ["--branch", repo.branch];
        // "--" ends the options, so nothing in the URL or path can be taken for one.
        interactive(args ~ ["--", repo.webUrl ~ ".git", dir]);
    }
    return dir;
}

// ------------------------------------------------------------------ pull requests

/// GraphQL: PRs per page and the page limit (at most 300 PRs). REST without a token: how many file-list requests
/// (one per PR and 100 files) are made at most, out of the 60 requests per hour GitHub allows unauthenticated
/// clients.
private enum pageSize = 50, maxPages = 6, anonymousFileLookups = 30;
/// Largest response body read (a larger one fails the fetch), and deepest JSON nesting accepted: the limits of the
/// Rust edition's HTTP client and JSON parser.
private enum maxResponseBytes = 10 * 1024 * 1024, maxJsonDepth = 128;
/// Environment variables that may hold a token; `gh` runs without them (see `token`).
private immutable tokenVariables = ["GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN"];

/// An API token for the repository's host, or null: the environment first (`envToken`), else `gh auth token` for the
/// host: the token of a login the user made to exactly that host. `gh` runs without the token variables, since it
/// would otherwise answer with `GH_ENTERPRISE_TOKEN` for any host; the host is passed as `--hostname=<host>`, so it
/// can never be read as another option.
private string token(ref const GitHubRepo repo)
{
    if (auto t = envToken(repo, (string v) => environment.get(v))) return t;
    auto env = environment.toAA;
    foreach (v; tokenVariables) env.remove(v);
    auto gh = executable("gh");
    if (gh is null) return null;
    auto t = output([gh, "auth", "token", "--hostname=" ~ repo.host], env, Config.newEnv);
    return t.length ? t : null;
}

/// The token from the environment variables the `gh` CLI reads: `GH_TOKEN` or `GITHUB_TOKEN` for github.com,
/// `GH_ENTERPRISE_TOKEN` or `GITHUB_ENTERPRISE_TOKEN` for any other host, but only when `GH_HOST` names that host
/// (`optedIn`); null when there is none. Without that opt-in, a crafted remote URL such as
/// `https://github.attacker.example/o/r` in an analysed repository would receive the user's token.
private string envToken(ref const GitHubRepo repo, scope string delegate(string) env)
{
    const string[] vars = repo.isPublic ? ["GH_TOKEN", "GITHUB_TOKEN"]
        : optedIn(repo, env) ? ["GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN"] : [];
    foreach (v; vars)
    {
        auto t = env(v).strip;
        if (t.length) return t;
    }
    return null;
}

/// Whether `GH_HOST` names the repository's host (case-insensitively): the opt-in for an Enterprise host.
private bool optedIn(ref const GitHubRepo repo, scope string delegate(string) env)
{
    import std.uni : sicmp;
    auto host = env("GH_HOST");
    return host !is null && sicmp(host.strip, repo.host) == 0;
}

/// The link of PR `number`: `url` from the response when it is an https URL on the repository's host, otherwise the
/// PR's page built from the repository. The URL ends up in the viewers' links and in the desktop viewers' browser
/// launcher, which must only ever be handed a web page (not, say, a `file:` URL).
private string pullUrl(ref const GitHubRepo repo, string url, long number)
{
    import std.algorithm : any;
    if (url.startsWith("https://" ~ repo.host ~ "/") && !url.any!(c => c <= ' ')) return url;
    return format("%s/pull/%d", repo.webUrl, number);
}

private struct Response
{
    JSONValue json;
    /// The `X-RateLimit-Remaining` header: API requests left in the current window, or -1 when it was missing.
    long remaining = -1;
}

/// Performs one API call with libcurl and parses the JSON response. `body_` is sent only for POST (GraphQL). Throws
/// on any non-2xx status, with a readable message for the common failures (see `failure`).
private Response request(string method, string url, string tok, string body_ = null)
{
    import std.net.curl : HTTP;
    import std.array : appender;
    auto http = HTTP(url);
    http.operationTimeout = 30.seconds;
    http.addRequestHeader("Accept", "application/vnd.github+json");
    http.addRequestHeader("X-GitHub-Api-Version", "2022-11-28");
    http.setUserAgent("codecity");
    if (tok.length) http.addRequestHeader("Authorization", "Bearer " ~ tok);
    if (method == "POST") http.postData = body_;
    auto data = appender!(ubyte[]);
    Response r;
    string remainingHeader;
    bool retryAfter;
    http.onReceiveHeader = (in char[] key, in char[] value) {
        if (key.toLower == "x-ratelimit-remaining")
        {
            remainingHeader = value.idup;
            // A malformed value counts as missing (an exception must not escape into libcurl's callback).
            try r.remaining = value.to!long;
            catch (Exception) r.remaining = -1;
        }
        if (key.toLower == "retry-after") retryAfter = true;
    };
    bool tooLarge;
    http.onReceive = (ubyte[] chunk) {
        if (data.data.length + chunk.length > maxResponseBytes)
        {
            tooLarge = true;
            return 0; // anything but chunk.length aborts the transfer
        }
        data.put(chunk);
        return chunk.length;
    };
    try http.perform();
    catch (Exception e)
        throw new Exception(tooLarge ? format("response larger than %d bytes", maxResponseBytes) : e.msg);
    if (auto msg = failure(http.statusLine.code, remainingHeader, retryAfter)) throw new Exception(msg);
    r.json = parseJSON(cast(string) data.data, maxJsonDepth);
    return r;
}

/// The message for a failed request, or null for a 2xx status. GitHub answers an exhausted rate limit with 429, or
/// with 403 and either `x-ratelimit-remaining: 0` (primary limit) or a `retry-after` header (secondary limit); any
/// other 403 means the token or anonymous client may not access the repository.
private string failure(int status, string rateLimitRemaining, bool retryAfter)
{
    if (status >= 200 && status <= 299) return null;
    if (status == 429 || (status == 403 && (rateLimitRemaining == "0" || retryAfter)))
        return format("rate limited (HTTP %d)", status);
    if (status == 401) return "token rejected (HTTP 401)";
    if (status == 403) return "access denied (HTTP 403)";
    return format("HTTP %d", status);
}

/// Sends one GraphQL query and returns its `data`. GraphQL reports errors (bad token scope, unknown repository, …)
/// with HTTP 200 and an "errors" array.
private JSONValue query(ref const GitHubRepo repo, string tok, string text, JSONValue variables)
{
    JSONValue body_ = ["query": JSONValue(text), "variables": variables];
    auto json = request("POST", repo.graphqlApi, tok, body_.toString).json;
    if (auto errors = "errors" in json)
        if (errors.type == JSONType.array && errors.array.length)
            throw new Exception(str(errors.array[0], "message"));
    auto data = "data" in json;
    return data ? *data : JSONValue(null);
}

import core.time : seconds;

/// The string member `key` of object `v`, or null when it is missing or not a string (e.g. JSON null).
private string str(JSONValue v, string key)
{
    auto p = key in v;
    return p && p.type == JSONType.string ? p.str : null;
}

private bool flag(JSONValue v, string key)
{
    auto p = key in v;
    return p && p.type == JSONType.true_;
}

/// Open PRs and how complete the list is. Never throws: problems are reported and yield false. A GitHub Enterprise
/// host is not contacted at all unless there is a token for it or `GH_HOST` names it: the host may come from a
/// hostile repository's remote URL, and CodeCity should not call out to it on its own.
bool fetchPulls(ref const GitHubRepo repo, out PullRequest[] pulls, out string source)
{
    try
    {
        auto tok = token(repo);
        if (!tok.length && !repo.isPublic && !optedIn(repo, (string v) => environment.get(v)))
        {
            stderr.writefln("  GitHub: not contacting %1$s (for a GitHub Enterprise host, set GH_HOST=%1$s or log in with `gh auth login --hostname %1$s`)",
                repo.host);
            return false;
        }
        if (tok.length) graphql(repo, tok, pulls, source);
        else rest(repo, pulls, source);
        return true;
    }
    catch (Exception e)
    {
        { import app : printable; stderr.writefln("  GitHub: could not fetch pull requests (%s)", printable(e.msg)); }
        return false;
    }
}

/// With a token: the GraphQL API returns each PR together with its changed files, 50 PRs per request, so all open
/// PRs (up to `maxPages` pages) cost only a few requests. PRs are ordered by last update, newest first, so when the
/// page limit cuts the list off, the most recently active PRs are the ones kept. Each PR comes with its first 100
/// files; a PR with more is completed 100 files per extra request, paged by cursor like the PRs.
private void graphql(ref const GitHubRepo repo, string tok, out PullRequest[] pulls, out string source)
{
    const pullsQuery = format("query($owner: String!, $name: String!, $cursor: String) { repository(owner: $owner, name: $name) { "
        ~ "pullRequests(states: OPEN, first: %d, after: $cursor, orderBy: {field: UPDATED_AT, direction: DESC}) { "
        ~ "pageInfo { hasNextPage endCursor } nodes { number title url updatedAt isDraft author { login } files(first: 100) { pageInfo { hasNextPage endCursor } nodes { path } } } } } }",
        pageSize);
    // The next 100 files of one PR, after the files seen so far.
    enum filesQuery = "query($owner: String!, $name: String!, $number: Int!, $cursor: String) { repository(owner: $owner, name: $name) { "
        ~ "pullRequest(number: $number) { files(first: 100, after: $cursor) { pageInfo { hasNextPage endCursor } nodes { path } } } } }";
    // Cursor pagination: null asks for the first page, then each page's endCursor asks for the next.
    JSONValue cursor = null;
    bool more = true;
    foreach (_; 0 .. maxPages)
    {
        if (!more) break;
        JSONValue variables = ["owner": JSONValue(repo.owner), "name": JSONValue(repo.name), "cursor": cursor];
        auto data = query(repo, tok, pullsQuery, variables);
        auto repository = data.type == JSONType.object ? "repository" in data : null;
        if (repository is null || repository.type != JSONType.object) throw new Exception("repository not found or not accessible");
        auto prs = (*repository)["pullRequests"];
        foreach (n; prs["nodes"].array)
        {
            PullRequest pr;
            pr.number = n["number"].integer;
            pr.title = str(n, "title");
            pr.url = pullUrl(repo, str(n, "url"), pr.number);
            auto author = "author" in n;
            // author is null for deleted ("ghost") accounts.
            pr.author = author && author.type == JSONType.object ? str(*author, "login") : null;
            pr.updatedAt = str(n, "updatedAt");
            pr.draft = flag(n, "isDraft");
            auto connection = n["files"];
            while (true)
            {
                foreach (f; connection["nodes"].array) pr.files ~= str(f, "path");
                if (!flag(connection["pageInfo"], "hasNextPage")) break;
                JSONValue next = ["owner": JSONValue(repo.owner), "name": JSONValue(repo.name),
                    "number": JSONValue(pr.number), "cursor": connection["pageInfo"]["endCursor"]];
                connection = query(repo, tok, filesQuery, next)["repository"]["pullRequest"]["files"];
            }
            pulls ~= pr;
        }
        more = flag(prs["pageInfo"], "hasNextPage");
        cursor = prs["pageInfo"]["endCursor"];
    }
    source = more ? format("latest %d open PRs", pulls.length) : "all open PRs";
}

/// Without a token: the REST API lists PRs without their files, so each PR costs one more request. One listing
/// request (up to 100 PRs, most recently updated first) is followed by file requests (100 files each, until a page
/// comes back short) PR by PR until the budget is used up: `anonymousFileLookups`, or fewer if the rate limit left
/// after the listing is lower, keeping one request in reserve. A PR whose files do not fit in what is left is left
/// out, like the PRs after it, rather than shown with only some of its files.
private void rest(ref const GitHubRepo repo, out PullRequest[] pulls, out string source)
{
    const base = format("%s/repos/%s/%s", repo.restApi, repo.owner, repo.name);
    auto listing = request("GET", base ~ "/pulls?state=open&sort=updated&direction=desc&per_page=100", null);
    // Without the header, assume the full budget is available.
    const remaining = listing.remaining < 0 ? anonymousFileLookups + 1 : listing.remaining;
    const budget = remaining - 1 < anonymousFileLookups ? (remaining > 0 ? remaining - 1 : 0) : anonymousFileLookups;
    long used = 0;
    auto nodes = listing.json.array;
    foreach (n; nodes)
    {
        PullRequest pr;
        pr.number = n["number"].integer;
        bool done;
        for (int page = 1; !done && used < budget; page++)
        {
            auto batch = request("GET", format("%s/pulls/%d/files?per_page=100&page=%d", base, pr.number, page), null).json.array;
            used++;
            foreach (f; batch) pr.files ~= str(f, "filename");
            done = batch.length < 100;
        }
        if (!done) break;
        pr.title = str(n, "title");
        pr.url = pullUrl(repo, str(n, "html_url"), pr.number);
        auto user = "user" in n;
        pr.author = user && user.type == JSONType.object ? str(*user, "login") : null;
        pr.updatedAt = str(n, "updated_at");
        pr.draft = flag(n, "draft");
        pulls ~= pr;
    }
    // A full page of 100 may mean there are more open PRs than the listing returned.
    const complete = pulls.length == nodes.length && nodes.length < 100;
    if (!complete)
        writefln("  GitHub: no token found — showing the %d most recently updated open PRs. Set GH_TOKEN (or log in with `gh`) to see all of them.",
            pulls.length);
    source = complete ? "all open PRs" : format("latest %d open PRs (no token)", pulls.length);
}

unittest
{
    foreach (url; ["https://github.com/google/gson", "https://github.com/google/gson.git", "git@github.com:google/gson.git",
            "ssh://git@github.com/google/gson.git", "github:google/gson"])
    {
        GitHubRepo r;
        assert(GitHubRepo.parse(url, r), url);
        assert(r.host == "github.com" && r.owner == "google" && r.name == "gson", url);
    }
    GitHubRepo r;
    assert(GitHubRepo.parse("https://github.com/o/r/tree/release/2.x", r) && r.branch == "release/2.x");
    assert(!GitHubRepo.parse("https://gitlab.com/o/r", r));
    bool threw;
    try GitHubRepo.parse("github:../..", r);
    catch (Exception) threw = true;
    assert(threw);
}

unittest
{
    // Path or URL syntax in the host does not match at all.
    GitHubRepo r;
    assert(!GitHubRepo.parse(`https://github.x\..\../o/r`, r));
    assert(!GitHubRepo.parse("https://github.com%2f@evil/o/r", r));
    assert(!GitHubRepo.parse("git@github.x?y:o/r", r));
    // A branch that git could read as an option, or that is no valid ref name, is an error.
    bool throws(string s)
    {
        try GitHubRepo.parse(s, r);
        catch (Exception) return true;
        return false;
    }
    foreach (b; ["--upload-pack=touch%20x", "-x", "a..b", "a b", "a~1", "a:b", `a\b`])
        assert(throws("https://github.com/o/r/tree/" ~ b), b);
    assert(throws("github:o/r@--upload-pack=x"));
    assert(GitHubRepo.parse("github:o/r@feature/x-1", r) && r.branch == "feature/x-1");
}

unittest
{
    // Environment tokens only go where they belong.
    GitHubRepo pub, ent;
    GitHubRepo.parse("https://github.com/o/r", pub);
    GitHubRepo.parse("https://github.acme.example/o/r", ent);
    string delegate(string) env(string[string] vars) { return (string k) => vars.get(k, null); }
    auto plain = env(["GH_TOKEN": "public", "GH_ENTERPRISE_TOKEN": "enterprise"]);
    assert(envToken(pub, plain) == "public");
    // A remote URL naming some other "github" host never gets GH_TOKEN, nor the Enterprise token without opt-in.
    assert(envToken(ent, plain) is null && !optedIn(ent, plain));
    auto optIn = env(["GH_TOKEN": "public", "GH_ENTERPRISE_TOKEN": "enterprise", "GH_HOST": "GitHub.Acme.Example"]);
    assert(optedIn(ent, optIn) && envToken(ent, optIn) == "enterprise");
    assert(envToken(ent, env(["GH_ENTERPRISE_TOKEN": "enterprise", "GH_HOST": "github.other.example"])) is null);

    // Only web links on the repository's host are kept.
    assert(pullUrl(pub, "https://github.com/o/r/pull/7", 7) == "https://github.com/o/r/pull/7");
    foreach (bad; [null, "file:///etc/passwd", "javascript:alert(1)", "http://github.com/o/r/pull/7",
            "https://github.com.evil.example/x", "https://github.com/o/r/pull/7 x", "https://github.com"])
        assert(pullUrl(pub, bad, 7) == "https://github.com/o/r/pull/7", bad);
}

unittest
{
    assert(failure(200, "59", false) is null);
    assert(failure(403, "0", false) == "rate limited (HTTP 403)");
    assert(failure(403, "4000", true) == "rate limited (HTTP 403)");
    assert(failure(429, null, false) == "rate limited (HTTP 429)");
    assert(failure(403, "4999", false) == "access denied (HTTP 403)");
    assert(failure(403, null, false) == "access denied (HTTP 403)");
    assert(failure(401, null, false) == "token rejected (HTTP 401)");
    assert(failure(404, null, false) == "HTTP 404");
}
