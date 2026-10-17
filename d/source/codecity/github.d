/// GitHub integration: repository URLs, the clone cache, remote detection and open pull requests.
module codecity.github;

import codecity.git : realPath, run;
import std.algorithm : canFind, endsWith, startsWith;
import std.array : replace, split;
import std.conv : to;
import std.file : exists, isDir, mkdirRecurse;
import std.format : format;
import std.json;
import std.path : buildPath, dirName, relativePath;
import std.process : environment, execute, spawnProcess, wait;
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
    /// Only hosts containing "github" match. Throws on `.`/`..` path segments.
    static bool parse(string text, out GitHubRepo repo)
    {
        enum segment = `[A-Za-z0-9_.-]+`;
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
            foreach (i, re; [
                ctRegex!(`^https?://(?:[^@/]+@)?([^/:]+)(?::\d+)?/(` ~ segment ~ `)/(` ~ segment ~ `)(?:/tree/(.+?))?/?$`),
                ctRegex!(`^[^@\s]+@([^:/]+):(` ~ segment ~ `)/(` ~ segment ~ `)/?$`),
                ctRegex!(`^ssh://(?:[^@/]+@)?([^/:]+)(?::\d+)?/(` ~ segment ~ `)/(` ~ segment ~ `)/?$`),
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
        if (ok && ([".", ".."].canFind(repo.owner) || [".", ".."].canFind(repo.name)))
            throw new Exception(format("invalid repository %s/%s", repo.owner, repo.name));
        return ok;
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
    /// Repository-relative paths of the files it changes.
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
    static bool detect(string root, out GitHubInfo info)
    {
        auto top = run(root, "rev-parse", "--show-toplevel");
        if (top is null) return false;
        GitHubRepo repo;
        bool found;
        if (auto url = run(root, "remote", "get-url", "origin"))
            found = GitHubRepo.parse(url, repo);
        if (!found)
            if (auto remotes = run(root, "remote", "-v"))
                foreach (line; remotes.split("\n"))
                {
                    auto parts = line.split;
                    if (parts.length >= 2 && GitHubRepo.parse(parts[1], repo))
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
        auto branch = run(root, "rev-parse", "--abbrev-ref", "HEAD");
        if (branch is null || branch == "HEAD") branch = sha;
        string prefix = relativePath(root, realPath(top)).replace("\\", "/");
        prefix = prefix == "." ? "" : prefix ~ "/";
        info = GitHubInfo(repo, pushed ? sha : branch, prefix);
        return true;
    }
}

// ------------------------------------------------------------------ clone cache

string cacheRoot()
{
    auto xdg = environment.get("XDG_CACHE_HOME", "");
    const base = xdg.length ? xdg : buildPath(environment.get("HOME", "."), ".cache");
    return buildPath(base, "codecity", "repos");
}

/// Clones blobless (full history, current files only) or fetches the latest state into an existing mirror.
string cloneOrUpdate(GitHubRepo repo)
{
    const dir = buildPath(cacheRoot(), repo.host, repo.owner, repo.name);
    void interactive(string[] args)
    {
        auto status = wait(spawnProcess(["git"] ~ args));
        if (status != 0) throw new Exception(format("git %-(%s %) failed (exit %d)", args, status));
    }
    if (buildPath(dir, ".git").exists)
    {
        writefln("Updating %s in %s …", repo, dir);
        auto fetch = ["-C", dir, "fetch", "--prune", "origin"];
        if (repo.branch.length) fetch ~= repo.branch;
        interactive(fetch);
        // the mirror is ours alone, so a hard reset to what we just fetched is safe
        interactive(["-C", dir, "reset", "--quiet", "--hard", repo.branch.length ? "FETCH_HEAD" : "origin/HEAD"]);
    }
    else
    {
        writefln("Cloning %s into %s …", repo.webUrl, dir);
        mkdirRecurse(dir.dirName);
        auto args = ["clone", "--filter=blob:none"];
        if (repo.branch.length) args ~= ["--branch", repo.branch];
        interactive(args ~ ["--", repo.webUrl ~ ".git", dir]);
    }
    return dir;
}

// ------------------------------------------------------------------ pull requests

private enum pageSize = 50, maxPages = 6, anonymousFileLookups = 30;

private string token(ref const GitHubRepo repo)
{
    foreach (v; repo.isPublic ? ["GH_TOKEN", "GITHUB_TOKEN"] : ["GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_TOKEN"])
    {
        auto t = environment.get(v, "").strip;
        if (t.length) return t;
    }
    try
    {
        auto r = execute(["gh", "auth", "token", "--hostname", repo.host]);
        if (r.status == 0 && r.output.strip.length) return r.output.strip;
    }
    catch (Exception) {}
    return null;
}

private struct Response
{
    JSONValue json;
    long remaining = -1;
}

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
    http.onReceiveHeader = (in char[] key, in char[] value) {
        if (key.toLower == "x-ratelimit-remaining") r.remaining = value.to!long;
    };
    http.onReceive = (ubyte[] chunk) { data.put(chunk); return chunk.length; };
    http.perform();
    const status = http.statusLine.code;
    if (status == 401) throw new Exception("token rejected (HTTP 401)");
    if (status == 403 || status == 429) throw new Exception(format("rate limited (HTTP %d)", status));
    if (status < 200 || status > 299) throw new Exception(format("HTTP %d", status));
    r.json = parseJSON(cast(string) data.data);
    return r;
}

import core.time : seconds;

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

/// Open PRs and how complete the list is. Never throws: problems are reported and yield false.
bool fetchPulls(ref const GitHubRepo repo, out PullRequest[] pulls, out string source)
{
    try
    {
        auto tok = token(repo);
        if (tok.length) graphql(repo, tok, pulls, source);
        else rest(repo, pulls, source);
        return true;
    }
    catch (Exception e)
    {
        stderr.writefln("  GitHub: could not fetch pull requests (%s)", e.msg);
        return false;
    }
}

private void graphql(ref const GitHubRepo repo, string tok, out PullRequest[] pulls, out string source)
{
    const query = format("query($owner: String!, $name: String!, $cursor: String) { repository(owner: $owner, name: $name) { "
        ~ "pullRequests(states: OPEN, first: %d, after: $cursor, orderBy: {field: UPDATED_AT, direction: DESC}) { "
        ~ "pageInfo { hasNextPage endCursor } nodes { number title url updatedAt isDraft author { login } files(first: 100) { nodes { path } } } } } }",
        pageSize);
    JSONValue cursor = null;
    bool more = true;
    foreach (_; 0 .. maxPages)
    {
        if (!more) break;
        JSONValue variables = ["owner": JSONValue(repo.owner), "name": JSONValue(repo.name), "cursor": cursor];
        JSONValue body_ = ["query": JSONValue(query), "variables": variables];
        auto json = request("POST", repo.graphqlApi, tok, body_.toString).json;
        if (auto errors = "errors" in json)
            if (errors.type == JSONType.array && errors.array.length)
                throw new Exception(str(errors.array[0], "message"));
        auto data = "data" in json;
        auto repository = data ? "repository" in *data : null;
        if (repository is null || repository.type != JSONType.object) throw new Exception("repository not found or not accessible");
        auto prs = (*repository)["pullRequests"];
        foreach (n; prs["nodes"].array)
        {
            PullRequest pr;
            pr.number = n["number"].integer;
            pr.title = str(n, "title");
            pr.url = str(n, "url");
            auto author = "author" in n;
            pr.author = author && author.type == JSONType.object ? str(*author, "login") : null;
            pr.updatedAt = str(n, "updatedAt");
            pr.draft = flag(n, "isDraft");
            foreach (f; n["files"]["nodes"].array) pr.files ~= str(f, "path");
            pulls ~= pr;
        }
        more = flag(prs["pageInfo"], "hasNextPage");
        cursor = prs["pageInfo"]["endCursor"];
    }
    source = more ? format("latest %d open PRs", pulls.length) : "all open PRs";
}

private void rest(ref const GitHubRepo repo, out PullRequest[] pulls, out string source)
{
    const base = format("%s/repos/%s/%s", repo.restApi, repo.owner, repo.name);
    auto listing = request("GET", base ~ "/pulls?state=open&sort=updated&direction=desc&per_page=100", null);
    const remaining = listing.remaining < 0 ? anonymousFileLookups + 1 : listing.remaining;
    const budget = remaining - 1 < anonymousFileLookups ? (remaining > 0 ? remaining - 1 : 0) : anonymousFileLookups;
    auto nodes = listing.json.array;
    foreach (n; nodes)
    {
        if (pulls.length >= budget) break;
        PullRequest pr;
        pr.number = n["number"].integer;
        auto files = request("GET", format("%s/pulls/%d/files?per_page=100", base, pr.number), null).json;
        pr.title = str(n, "title");
        pr.url = str(n, "html_url");
        auto user = "user" in n;
        pr.author = user && user.type == JSONType.object ? str(*user, "login") : null;
        pr.updatedAt = str(n, "updated_at");
        pr.draft = flag(n, "draft");
        foreach (f; files.array) pr.files ~= str(f, "filename");
        pulls ~= pr;
    }
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
