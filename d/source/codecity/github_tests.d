/// End-to-end tests of the GitHub integration and its git plumbing, the mirror of the Java edition's
/// `PullRequestsFetchTest`, `RepoClonerTest`, `PrefetchTest`, `GitHubInfoTest`, `GitTest`, `GitHubRepoTest` and
/// `CityGitHubTest` and of the Rust edition's `github_tests.rs`: the same scenarios with the same expectations.
///
/// - Pull requests are fetched from `FakeGitHub`, an in-house stand-in for the GitHub API on the loopback interface
///   (std.socket, one thread), through the `Env` seam of `fetchWith`.
/// - The clone cache clones local bare repositories through `file://` URLs into a cache in a temporary directory
///   (`cloneInto`, the seam of `cloneOrUpdate`).
/// - Fake programs (`gh`, a hanging `git`) are shell scripts found with `lookup` on a `PATH` of their own.
module codecity.github_tests;

version (unittest):

import codecity.analysis : SourceAnalyzer;
import codecity.city : CityModel;
import codecity.git : lookup, output, realPath;
import codecity.github;
import codecity.history : collect, History, prefetchMissing;
import core.thread : Thread;
import core.time : msecs, seconds, MonoTime;
import std.algorithm : all, canFind, count, filter, map, startsWith;
import std.array : array, join, replicate, split;
import std.conv : octal, text, to;
import std.file : exists, isDir, isFile, mkdirRecurse, readText, remove, rmdirRecurse, setAttributes, symlink, tempDir,
    write;
import std.format : format;
import std.json;
import std.path : buildNormalizedPath, buildPath, dirName;
import std.process : Config, execute;
import std.range : iota;
import std.socket;
import std.string : indexOf, lastIndexOf, lineSplitter, strip, toLower;
import std.uuid : randomUUID;

// ------------------------------------------------------------------ the fake GitHub API

/// One request as the fake saw it: `target` is the path with the query string.
private struct Request
{
    string method, target, authorization, body_;
}

/// An answer: status, extra headers and body.
private struct Reply
{
    int status = 200;
    string[2][] headers;
    string body_;

    static Reply json(string body_, string[2][] headers = null) { return Reply(200, headers, body_); }
    static Reply status_(int status, string[2][] headers = null) { return Reply(status, headers, `{"message":"no"}`); }
}

/// The fakes started and not closed yet; `closeAll` closes them. The unittests run one after another on one thread.
private FakeGitHub[] openFakes;

/// Closes every fake still open: each test that starts any begins with `scope (exit) closeAll();`, so a failing
/// assertion cannot leave a server behind.
private void closeAll()
{
    foreach (f; openFakes.dup) f.close();
}

/// The fake API: records every request and answers it with the test's delegate, on 127.0.0.1 at a free port, one
/// connection at a time (`Connection: close`), until `close`. Its thread is a daemon thread, so even a fake that was
/// never closed cannot keep the test program from exiting, and a connection that stalls for 5 seconds is dropped.
private final class FakeGitHub
{
    private Socket listener;
    private Thread thread;
    private Request[] received;
    private shared bool stopping;
    private Reply delegate(Request) answer;

    this(Reply delegate(Request) answer)
    {
        this.answer = answer;
        listener = new TcpSocket(AddressFamily.INET);
        listener.setOption(SocketOptionLevel.SOCKET, SocketOption.REUSEADDR, true);
        listener.bind(new InternetAddress("127.0.0.1", 0));
        listener.listen(16);
        thread = new Thread(&serve);
        thread.isDaemon = true;
        thread.start();
        openFakes ~= this;
    }

    /// `http://127.0.0.1:<port>`.
    string base() { return "http://" ~ listener.localAddress.toString; }

    Request[] requests() { synchronized (this) return received.dup; }

    /// The distinct Authorization headers received ("null" for none), in order.
    string[] authorizations()
    {
        string[] out_;
        foreach (r; requests)
        {
            const a = r.authorization is null ? "null" : r.authorization;
            if (!out_.canFind(a)) out_ ~= a;
        }
        return out_;
    }

    /// Stops the server and waits for its thread; closing twice does nothing.
    void close()
    {
        import core.atomic : atomicStore;
        import std.algorithm : remove, countUntil;
        const i = openFakes.countUntil!(f => f is this);
        if (i < 0) return;
        openFakes = openFakes.remove(i);
        atomicStore(stopping, true);
        // Wake the accept with a connection of our own.
        try { auto s = new TcpSocket(listener.localAddress); s.close(); } catch (Exception) {}
        thread.join();
        listener.close();
    }

    private void serve()
    {
        import core.atomic : atomicLoad;
        while (true)
        {
            Socket c;
            try c = listener.accept();
            catch (Exception) return;
            if (atomicLoad(stopping)) { c.close(); return; }
            c.setOption(SocketOptionLevel.SOCKET, SocketOption.RCVTIMEO, 5.seconds);
            c.setOption(SocketOptionLevel.SOCKET, SocketOption.SNDTIMEO, 5.seconds);
            try handle(c);
            catch (Exception) {}
            c.close();
        }
    }

    /// Reads one request (headers, then a Content-Length body), records it and sends the answer.
    private void handle(Socket c)
    {
        char[] data;
        char[65536] buf;
        ptrdiff_t end;
        while ((end = data.indexOf("\r\n\r\n")) < 0)
        {
            const n = c.receive(buf[]);
            if (n <= 0) return;
            data ~= buf[0 .. n];
        }
        auto lines = data[0 .. end].idup.split("\r\n");
        auto first = lines[0].split(" ");
        Request r;
        r.method = first[0];
        r.target = first[1];
        size_t length;
        bool expectContinue;
        foreach (l; lines[1 .. $])
        {
            const colon = l.indexOf(':');
            if (colon < 0) continue;
            const key = l[0 .. colon].strip.toLower, value = l[colon + 1 .. $].strip;
            if (key == "content-length") length = value.to!size_t;
            if (key == "authorization") r.authorization = value;
            if (key == "expect" && value.toLower == "100-continue") expectContinue = true;
        }
        if (expectContinue) c.send("HTTP/1.1 100 Continue\r\n\r\n");
        auto body_ = data[end + 4 .. $];
        while (body_.length < length)
        {
            const n = c.receive(buf[]);
            if (n <= 0) break;
            body_ ~= buf[0 .. n];
        }
        r.body_ = body_.idup;
        synchronized (this) received ~= r;
        Reply reply;
        try reply = answer(r);
        catch (Exception e)
        {
            import std.stdio : stderr;
            stderr.writefln("fake GitHub: %s %s failed: %s", r.method, r.target, e.msg);
            reply = Reply(500, null, e.msg);
        }
        auto head = format("HTTP/1.1 %d X\r\nContent-Length: %d\r\nContent-Type: application/json\r\nConnection: close\r\n",
            reply.status, reply.body_.length);
        foreach (h; reply.headers) head ~= h[0] ~ ": " ~ h[1] ~ "\r\n";
        const(char)[] out_ = head ~ "\r\n" ~ reply.body_;
        // The client may stop reading (the response size cap).
        while (out_.length)
        {
            const n = c.send(out_);
            if (n <= 0) break;
            out_ = out_[n .. $];
        }
    }

    /// Fetches `repo`'s pull requests from this server with the environment variables `vars` (and programs found on
    /// their `PATH` only); false when the host was not contacted. Failures throw.
    bool fetch(GitHubRepo repo, string[string] vars, out PullRequest[] pulls, out string source)
    {
        string get(string k) { return vars.get(k, null); }
        auto env = Env(&get, (string n) => lookup(n, &get), base ~ "/api/v3", base ~ "/api/graphql");
        return fetchWith(repo, env, pulls, source);
    }

    /// A fetch that must succeed and contact the server; returns the source.
    string pulls(GitHubRepo repo, string[string] vars, out PullRequest[] pulls)
    {
        string source;
        assert(fetch(repo, vars, pulls, source));
        return source;
    }

    /// The message of a fetch that must fail.
    string failure(GitHubRepo repo, string[string] vars)
    {
        PullRequest[] pulls;
        string source;
        try fetch(repo, vars, pulls, source);
        catch (Exception e) return e.msg;
        assert(false, "the fetch did not fail");
    }
}

private GitHubRepo parsed(string url)
{
    GitHubRepo r;
    assert(GitHubRepo.parse(url, r));
    return r;
}

private GitHubRepo publicRepo() { return parsed("https://github.com/o/r"); }
private GitHubRepo enterpriseRepo() { return parsed("https://github.acme.example/o/r"); }

/// The largest response body accepted.
private enum maxBody = 10 * 1024 * 1024;

// ------------------------------------------------------------------ response builders

/// A JSON string literal, or null.
private string q(string s) { return s is null ? "null" : `"` ~ s ~ `"`; }

/// A GraphQL files connection: `paths`, and whether (and after which cursor) more follow.
private string files(string[] paths, bool more, string cursor)
{
    return format(`{"pageInfo":{"hasNextPage":%s,"endCursor":%s},"nodes":[%-(%s,%)]}`, more, q(cursor),
        paths.map!(p => `{"path":"` ~ p ~ `"}`));
}

/// A GraphQL pull request node.
private string node(long number, string url, string author, bool draft, string files)
{
    return format(`{"number":%d,"title":"PR %d","url":"%s","updatedAt":"2026-10-0%dT10:00:00Z","isDraft":%s,"author":%s,"files":%s}`,
        number, number, url, number % 9 + 1, draft, author is null ? "null" : `{"login":"` ~ author ~ `"}`, files);
}

/// A GraphQL page of pull requests.
private string page(string[] nodes, bool more, string cursor)
{
    return format(`{"data":{"repository":{"pullRequests":{"pageInfo":{"hasNextPage":%s,"endCursor":%s},"nodes":[%-(%s,%)]}}}}`,
        more, q(cursor), nodes);
}

/// The answer to a files query: the next page of one PR's files.
private string filesPage(string connection) { return `{"data":{"repository":{"pullRequest":{"files":` ~ connection ~ `}}}}`; }

/// `src/f000.java` … for the numbers `from` (inclusive) to `to` (exclusive).
private string[] paths(int from, int to) { return iota(from, to).map!(i => format("src/f%03d.java", i)).array; }

/// A REST listing of the pull requests `numbers`.
private string listing(long[] numbers)
{
    return format("[%-(%s,%)]", numbers.map!(n => format(`{"number":%d,"title":"PR %d","html_url":"https://github.com/o/r/pull/%d","user":{"login":"u%d"},"updated_at":"2026-10-01T10:00:00Z","draft":false}`, n, n, n, n)));
}

/// A REST page of files.
private string restFiles(string[] paths) { return format("[%-(%s,%)]", paths.map!(p => `{"filename":"` ~ p ~ `"}`)); }

/// The GraphQL variables of a request.
private JSONValue variables(Request r) { return parseJSON(r.body_)["variables"]; }

/// Answers the REST listing with `listing` and `headers`, and `/pulls/<n>/files?per_page=100&page=<p>` with
/// `files(n, p)`.
private Reply restAnswer(Request r, string listing, string[2][] headers, string[] delegate(long, long) files)
{
    enum prefix = "/api/v3/repos/o/r/pulls";
    if (r.target == prefix ~ "?state=open&sort=updated&direction=desc&per_page=100") return Reply.json(listing, headers);
    const rest = r.target[prefix.length + 1 .. $];
    const number = rest[0 .. rest.indexOf('/')].to!long;
    const pageNo = rest[rest.lastIndexOf("&page=") + 6 .. $].to!long;
    return Reply.json(restFiles(files(number, pageNo)));
}

private long[] numbers(PullRequest[] pulls) { return pulls.map!(p => p.number).array; }

/// A JSON value that is null or missing as null, else its string.
private string jstr(JSONValue v) { return v.type == JSONType.string ? v.str : null; }

// ------------------------------------------------------------------ GraphQL

unittest // github: pages through pull requests and their files with GraphQL
{
    scope (exit) closeAll();
    auto fake = new FakeGitHub((Request r) {
        auto v = variables(r);
        if ("number" in v)
        {
            // The files of PR 2 after the first 100: two more pages.
            switch (jstr(v["cursor"]))
            {
                case "f1": return Reply.json(filesPage(files(paths(100, 200), true, "f2")));
                case "f2": return Reply.json(filesPage(files(paths(200, 205), false, "f3")));
                default: return Reply.status_(404);
            }
        }
        if (v["cursor"].type == JSONType.null_)
            return Reply.json(page([
                node(1, "https://github.com/o/r/pull/1", "ann", false, files(["src/A.java", "src/B.java"], false, "x")),
                node(2, "https://github.com/o/r/pull/2", "bob", false, files(paths(0, 100), true, "f1"))], true, "p1"));
        // The second page: a draft by a deleted user, with a link that is not a web page on the host.
        return Reply.json(page([node(3, "javascript:alert(1)", null, true, files([], false, null))], false, "p2"));
    });
    scope (exit) fake.close();
    PullRequest[] pulls;
    assert(fake.pulls(publicRepo, ["GH_TOKEN": "t0k"], pulls) == "all open PRs");
    assert(numbers(pulls) == [1, 2, 3]);
    assert(pulls[0] == PullRequest(1, "PR 1", "https://github.com/o/r/pull/1", "ann", "2026-10-02T10:00:00Z", false,
        ["src/A.java", "src/B.java"]));
    assert(pulls[1].files == paths(0, 205));
    assert(pulls[2] == PullRequest(3, "PR 3", "https://github.com/o/r/pull/3", null, "2026-10-04T10:00:00Z", true, []));
    // Four POSTs, each with the token: PR page 1, the two further file pages of PR 2, PR page 2.
    auto requests = fake.requests;
    assert(requests.length == 4);
    string[] sent;
    foreach (r; requests)
    {
        assert(r.method == "POST" && r.target == "/api/graphql" && r.authorization == "Bearer t0k", r.text);
        auto v = variables(r);
        assert(v["owner"].str == "o" && v["name"].str == "r");
        const cursor = v["cursor"].type == JSONType.null_ ? "null" : v["cursor"].str;
        sent ~= "number" in v ? format("%d@%s", v["number"].integer, cursor) : cursor;
    }
    assert(sent == ["null", "2@f1", "2@f2", "p1"], sent.text);
}

unittest // github: stops after six GraphQL pages
{
    scope (exit) closeAll();
    int served;
    auto fake = new FakeGitHub((Request r) {
        const n = ++served;
        return Reply.json(page([node(n, format("https://github.com/o/r/pull/%d", n), "ann", false,
            files(["a.java"], false, null))], true, format("p%d", n)));
    });
    scope (exit) fake.close();
    PullRequest[] pulls;
    assert(fake.pulls(publicRepo, ["GH_TOKEN": "t0k"], pulls) == "latest 6 open PRs");
    assert(numbers(pulls) == [1, 2, 3, 4, 5, 6]);
    assert(fake.requests.length == 6);
}

unittest // github: reports GraphQL errors and missing repositories
{
    scope (exit) closeAll();
    auto fake = new FakeGitHub((Request r) => Reply.json(`{"errors":[{"message":"Something went wrong"}]}`));
    assert(fake.failure(publicRepo, ["GH_TOKEN": "t0k"]) == "Something went wrong");
    fake.close();
    fake = new FakeGitHub((Request r) => Reply.json(`{"data":{"repository":null}}`));
    assert(fake.failure(publicRepo, ["GH_TOKEN": "t0k"]) == "repository not found or not accessible");
    fake.close();
}

// ------------------------------------------------------------------ REST

unittest // github: falls back to REST without a token
{
    scope (exit) closeAll();
    // PR 5 changes two files; PR 6 changes 103, so its files take two pages.
    auto fake = new FakeGitHub((Request r) => restAnswer(r, listing([5, 6]), [["x-ratelimit-remaining", "59"]],
        (long n, long p) => n == 5 ? ["a.java", "b.java"] : p == 1 ? paths(0, 100) : paths(100, 103)));
    scope (exit) fake.close();
    PullRequest[] pulls;
    assert(fake.pulls(publicRepo, null, pulls) == "all open PRs");
    assert(pulls.length == 2);
    assert(pulls[0] == PullRequest(5, "PR 5", "https://github.com/o/r/pull/5", "u5", "2026-10-01T10:00:00Z", false,
        ["a.java", "b.java"]));
    assert(pulls[1].files == paths(0, 103));
    assert(fake.requests.map!(r => r.target).array == [
        "/api/v3/repos/o/r/pulls?state=open&sort=updated&direction=desc&per_page=100",
        "/api/v3/repos/o/r/pulls/5/files?per_page=100&page=1",
        "/api/v3/repos/o/r/pulls/6/files?per_page=100&page=1",
        "/api/v3/repos/o/r/pulls/6/files?per_page=100&page=2"]);
    assert(fake.requests.all!(r => r.method == "GET" && r.authorization is null));
}

unittest // github: spends only the anonymous request budget
{
    scope (exit) closeAll();
    PullRequest[] pulls;
    // Three requests left: one is kept in reserve, so the files of two PRs are fetched and the third PR is left out.
    auto fake = new FakeGitHub((Request r) => restAnswer(r, listing([1, 2, 3]), [["x-ratelimit-remaining", "3"]],
        (long n, long p) => ["a.java"]));
    assert(fake.pulls(publicRepo, null, pulls) == "latest 2 open PRs (no token)");
    assert(numbers(pulls) == [1, 2] && fake.requests.length == 3);
    fake.close();
    // A PR whose files do not fit into the budget is left out rather than shown with some of its files.
    fake = new FakeGitHub((Request r) => restAnswer(r, listing([1, 2]), [["x-ratelimit-remaining", "3"]],
        (long n, long p) => n == 1 && p < 3 ? paths(0, 100) : ["a.java"]));
    assert(fake.pulls(publicRepo, null, pulls) == "latest 0 open PRs (no token)");
    assert(pulls.length == 0 && fake.requests.length == 3);
    fake.close();
    // Without a rate-limit header, at most 30 file requests are made.
    fake = new FakeGitHub((Request r) => restAnswer(r, listing(iota(1L, 41L).array), null, (long n, long p) => ["a.java"]));
    assert(fake.pulls(publicRepo, null, pulls) == "latest 30 open PRs (no token)");
    assert(pulls.length == 30 && fake.requests.length == 31);
    fake.close();
    // No requests left at all: the listing alone.
    fake = new FakeGitHub((Request r) => restAnswer(r, listing([1]), [["x-ratelimit-remaining", "0"]],
        (long n, long p) => ["a.java"]));
    assert(fake.pulls(publicRepo, null, pulls) == "latest 0 open PRs (no token)");
    assert(fake.requests.length == 1);
    fake.close();
}

// ------------------------------------------------------------------ failures and limits

unittest // github: classifies failed requests
{
    scope (exit) closeAll();
    struct Case { int status; string[2][] headers; string message; }
    foreach (c; [Case(401, null, "token rejected (HTTP 401)"),
            Case(403, [["x-ratelimit-remaining", "0"]], "rate limited (HTTP 403)"),
            Case(403, [["retry-after", "60"]], "rate limited (HTTP 403)"),
            Case(429, null, "rate limited (HTTP 429)"),
            Case(403, [["x-ratelimit-remaining", "4999"]], "access denied (HTTP 403)"),
            Case(502, null, "HTTP 502")])
    {
        auto fake = new FakeGitHub((Request r) => Reply.status_(c.status, c.headers));
        scope (exit) fake.close();
        assert(fake.failure(publicRepo, ["GH_TOKEN": "t0k"]) == c.message, c.message);
        assert(fake.failure(publicRepo, null) == c.message, c.message);
    }
}

/// A GraphQL answer with no PRs, padded with spaces to exactly `size` bytes.
private string padded(size_t size)
{
    const json = page([], false, null);
    return json ~ " ".replicate(size - json.length);
}

unittest // github: accepts responses up to exactly ten megabytes
{
    scope (exit) closeAll();
    PullRequest[] pulls;
    foreach (size; [maxBody - 1, maxBody])
    {
        auto fake = new FakeGitHub((Request r) => Reply.json(padded(size)));
        scope (exit) fake.close();
        assert(fake.pulls(publicRepo, ["GH_TOKEN": "t0k"], pulls) == "all open PRs", size.text);
    }
    auto fake = new FakeGitHub((Request r) => Reply.json(padded(maxBody + 1)));
    scope (exit) fake.close();
    assert(fake.failure(publicRepo, ["GH_TOKEN": "t0k"]) == "response larger than 10485760 bytes");
}

/// A GraphQL answer with no PRs whose JSON nests `depth` arrays and objects deep: the answer's object and `depth - 1`
/// arrays, the innermost holding `0` or, with `empty`, nothing.
private string nested(size_t depth, bool empty)
{
    return `{"data":{"repository":{"pullRequests":{"pageInfo":{"hasNextPage":false},"nodes":[]}}},"pad":`
        ~ "[".replicate(depth - 1) ~ (empty ? "" : "0") ~ "]".replicate(depth - 1) ~ "}";
}

unittest // github: accepts JSON nested up to 127 levels
{
    scope (exit) closeAll();
    PullRequest[] pulls;
    // Only arrays and objects count, whatever the innermost value; brackets in strings do not count at all.
    foreach (depth; [126, 127])
        foreach (empty; [false, true])
        {
            auto fake = new FakeGitHub((Request r) => Reply.json(nested(depth, empty)));
            scope (exit) fake.close();
            assert(fake.pulls(publicRepo, ["GH_TOKEN": "t0k"], pulls) == "all open PRs", depth.text);
        }
    {
        auto fake = new FakeGitHub((Request r) => Reply.json(page([], false, "[".replicate(300))));
        scope (exit) fake.close();
        assert(fake.pulls(publicRepo, ["GH_TOKEN": "t0k"], pulls) == "all open PRs");
    }
    foreach (depth; [128, 100_000])
        foreach (empty; [false, true])
        {
            auto fake = new FakeGitHub((Request r) => Reply.json(nested(depth, empty)));
            scope (exit) fake.close();
            fake.failure(publicRepo, ["GH_TOKEN": "t0k"]);
        }
}

unittest // github: refuses redirects to another host or to plain HTTP
{
    scope (exit) closeAll();
    // The other "host" is another port: an origin of its own. It is never contacted, so the token stays here.
    {
        auto other = emptyApi();
        scope (exit) other.close();
        const location = other.base ~ "/api/graphql";
        auto fake = new FakeGitHub((Request r) => Reply.status_(307, [["Location", location]]));
        scope (exit) fake.close();
        { const m = fake.failure(publicRepo, ["GH_TOKEN": "t0k"]); assert(m == "redirect to another host refused", m); }
        assert(other.requests.length == 0);
    }
    // The same host and port, but not https (the fake speaks plain HTTP): refused as well, after one request.
    foreach (status; [301, 302, 307, 308])
    {
        auto fake = new FakeGitHub((Request r) => Reply.status_(status, [["Location", "/api/graphql-moved"]]));
        scope (exit) fake.close();
        assert(fake.failure(publicRepo, ["GH_TOKEN": "t0k"]) == "redirect to another host refused");
        assert(fake.requests.length == 1);
    }
}

unittest // github: follows redirects only to https on the same host and port (the same table as in Java and Rust)
{
    enum from = "https://api.github.com/repos/o/r/pulls";
    assert(mayFollow(from, "https://api.github.com/repositories/1/pulls"));
    assert(mayFollow(from, "https://API.GitHub.com/x"));
    assert(mayFollow("https://github.acme.example/api/v3/x", "https://github.acme.example/api/v3/y?page=2"));
    assert(mayFollow("https://h.example:8443/a", "https://h.example:8443/b"));
    assert(!mayFollow(from, "http://api.github.com/x"));
    assert(!mayFollow(from, "https://evil.example/x"));
    assert(!mayFollow(from, "https://api.github.com:8443/x"));
    assert(!mayFollow(from, "https://api.github.com:443/x"));
    assert(!mayFollow(from, "file:///etc/passwd"));
    assert(!mayFollow(from, "https://api.github.com.evil.example/x"));
    // Locations are resolved against the request's URL first.
    assert(resolve(from, "https://x.example/y") == "https://x.example/y");
    assert(resolve(from, "/repositories/1") == "https://api.github.com/repositories/1");
    assert(resolve(from, "//api.github.com/z") == "https://api.github.com/z");
    assert(resolve("https://h.example/a/b?q=/x", "c") == "https://h.example/a/c");
}

// ------------------------------------------------------------------ which token goes where

/// Answers GraphQL with an empty page and REST with an empty listing.
private FakeGitHub emptyApi()
{
    return new FakeGitHub((Request r) => Reply.json(r.method == "POST" ? page([], false, null) : "[]"));
}

unittest // github: chooses the token by host
{
    scope (exit) closeAll();
    PullRequest[] pulls;
    string source;
    // github.com: GH_TOKEN, else GITHUB_TOKEN (trimmed); a blank one does not count.
    auto fake = emptyApi();
    fake.pulls(publicRepo, ["GH_TOKEN": "  public  ", "GITHUB_TOKEN": "other", "GH_ENTERPRISE_TOKEN": "ent"], pulls);
    assert(fake.authorizations == ["Bearer public"]);
    fake.close();
    fake = emptyApi();
    fake.pulls(publicRepo, ["GH_TOKEN": " ", "GITHUB_TOKEN": "second"], pulls);
    assert(fake.authorizations == ["Bearer second"]);
    fake.close();
    // An Enterprise host without the GH_HOST opt-in (and no gh login) is not contacted at all.
    fake = emptyApi();
    assert(!fake.fetch(enterpriseRepo, ["GH_TOKEN": "public", "GH_ENTERPRISE_TOKEN": "ent"], pulls, source));
    assert(fake.requests.length == 0);
    fake.close();
    // Opted in without a token: anonymous REST. With GH_ENTERPRISE_TOKEN (or GITHUB_ENTERPRISE_TOKEN): GraphQL.
    fake = emptyApi();
    assert(fake.pulls(enterpriseRepo, ["GH_HOST": "github.acme.example", "GH_TOKEN": "public"], pulls) == "all open PRs");
    assert(fake.authorizations == ["null"] && fake.requests[0].method == "GET");
    fake.close();
    fake = emptyApi();
    fake.pulls(enterpriseRepo, ["GH_HOST": "GitHub.Acme.Example", "GH_TOKEN": "public", "GITHUB_ENTERPRISE_TOKEN": "ent2"], pulls);
    assert(fake.authorizations == ["Bearer ent2"]);
    fake.close();
}

/// A new empty temporary directory (its real path).
private string tempDirectory()
{
    const dir = buildPath(tempDir, "codecity-gh-" ~ randomUUID.toString);
    mkdirRecurse(dir);
    return realPath(dir);
}

/// Writes an executable shell script `name` into `dir`.
private string script(string dir, string name, string body_)
{
    const path = buildPath(dir, name);
    write(path, "#!/bin/sh\n" ~ body_ ~ "\n");
    setAttributes(path, octal!755);
    return path;
}

/// Writes a fake `gh` into `dir` that records its arguments in `dir/args`, prints `out_` and exits with `exit`.
private void fakeGh(string dir, string out_, int exit)
{
    script(dir, "gh", format("echo \"$@\" > '%s'\nprintf '%%s\\n' '%s'\nexit %d", buildPath(dir, "args"), out_, exit));
}

unittest // github: falls back to the GitHub CLI login for the host
{
    scope (exit) closeAll();
    const dir = tempDirectory();
    scope (exit) rmdirRecurse(dir);
    const args = buildPath(dir, "args");
    fakeGh(dir, "gh-secret", 0);
    PullRequest[] pulls;
    auto fake = emptyApi();
    fake.pulls(publicRepo, ["PATH": dir], pulls);
    assert(fake.authorizations == ["Bearer gh-secret"]);
    assert(readText(args).strip == "auth token --hostname=github.com");
    fake.close();
    // A gh login to exactly the Enterprise host counts as the opt-in.
    fake = emptyApi();
    fake.pulls(enterpriseRepo, ["PATH": dir], pulls);
    assert(fake.authorizations == ["Bearer gh-secret"]);
    assert(readText(args).strip == "auth token --hostname=github.acme.example");
    fake.close();
    // An environment token wins, and gh is not even asked.
    remove(args);
    fake = emptyApi();
    fake.pulls(publicRepo, ["PATH": dir, "GH_TOKEN": "env"], pulls);
    assert(fake.authorizations == ["Bearer env"] && !args.exists);
    fake.close();
    // No login (gh fails): anonymous REST for github.com.
    fakeGh(dir, "", 1);
    fake = emptyApi();
    fake.pulls(publicRepo, ["PATH": dir], pulls);
    assert(fake.authorizations == ["null"]);
    fake.close();
}

// ------------------------------------------------------------------ git repositories

/// Runs `git [-C dir] args…` with a fixed author and no signing, fails unless it succeeds, and returns its stdout
/// stripped. `noLazyFetch`: with lazy fetching of missing objects off (git 2.44+).
private string gitIn(string dir, bool noLazyFetch, string[] args...)
{
    auto cmd = ["git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "-c",
        "init.defaultBranch=main", "-c", "protocol.file.allow=always"];
    if (dir !is null) cmd ~= ["-C", dir];
    string[string] env;
    if (noLazyFetch) env["GIT_NO_LAZY_FETCH"] = "1";
    auto r = execute(cmd ~ args, env, Config.stderrPassThrough);
    assert(r.status == 0, format("git %-(%s %)", args));
    return r.output.strip;
}

private string git_(string dir, string[] args...) { return gitIn(dir, false, args); }

/// Writes `file` in the work tree `work` with `content`, commits everything and returns the commit.
private string commit(string work, string file, string content)
{
    const f = buildPath(work, file);
    mkdirRecurse(f.dirName);
    write(f, content);
    git_(work, "add", "-A");
    git_(work, "commit", "-q", "-m", "change " ~ file);
    return git_(work, "rev-parse", "HEAD");
}

/// The "remote": a work tree `dir/work` with three commits on `main` (A.java v1, then A.java v2 and B.java) and one
/// more on `dev` (C.java), pushed to the bare repository `dir/remote.git`, which allows blobless clones and fetching
/// any object. Returns the bare repository.
private string remoteRepo(string dir)
{
    const work = buildPath(dir, "work");
    mkdirRecurse(work);
    git_(work, "init", "-q");
    commit(work, "A.java", "class A { /* v1 */ }\n");
    write(buildPath(work, "B.java"), "class B {}\n");
    commit(work, "A.java", "class A { /* v2 */ }\n");
    git_(work, "checkout", "-q", "-b", "dev");
    commit(work, "C.java", "class C {}\n");
    git_(work, "checkout", "-q", "main");
    const bare = buildPath(dir, "remote.git");
    gitIn(null, false, "clone", "-q", "--bare", work, bare);
    git_(bare, "config", "uploadpack.allowFilter", "true");
    git_(bare, "config", "uploadpack.allowAnySHA1InWant", "true");
    git_(work, "remote", "add", "origin", bare);
    return bare;
}

/// A `file://` URL of `dir`, so that git uses its transport (a plain path ignores --filter).
private string fileUrl(string dir)
{
    const p = dir.replace('\\', '/');
    return "file://" ~ (p.startsWith("/") ? "" : "/") ~ p;
}

private string replace(string s, char a, char b) { import std.array : r = replace; return s.r([a], [b]); }

/// How many objects reachable from HEAD are missing from the (partial) repository `repo`.
private size_t missing(string repo)
{
    return gitIn(repo, true, "rev-list", "--objects", "--missing=print", "HEAD").lineSplitter.count!(l => l.startsWith("?"));
}

private GitHubRepo ghRepo(string host, string branch = null) { return GitHubRepo(host, "o", "r", branch); }

// ------------------------------------------------------------------ clone cache

unittest // github: clones blobless into the cache, then updates
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const bare = remoteRepo(tmp), cache = buildPath(tmp, "cache");
    const dir = cloneInto(ghRepo("github.com"), cache, fileUrl(bare));
    assert(dir == buildPath(cache, "github.com", "o", "r"));
    assert(git_(dir, "rev-parse", "HEAD") == git_(bare, "rev-parse", "main"));
    // Blobless: full history, but the old version of A.java was not downloaded.
    assert(git_(dir, "config", "remote.origin.promisor") == "true");
    assert(git_(dir, "config", "remote.origin.partialclonefilter") == "blob:none");
    assert(missing(dir) == 1);
    assert(readText(buildPath(dir, "A.java")) == "class A { /* v2 */ }\n");
    // A new commit upstream, and a local change in the mirror: the update fetches and resets hard.
    const work = buildPath(tmp, "work");
    const head = commit(work, "A.java", "class A { /* v3 */ }\n");
    git_(work, "push", "-q", "origin", "main");
    write(buildPath(dir, "A.java"), "local edit\n");
    assert(cloneInto(ghRepo("github.com"), cache, fileUrl(bare)) == dir);
    assert(git_(dir, "rev-parse", "HEAD") == head);
    assert(readText(buildPath(dir, "A.java")) == "class A { /* v3 */ }\n");
}

unittest // github: checks out the requested branch
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const bare = remoteRepo(tmp), cache = buildPath(tmp, "cache");
    const dir = cloneInto(ghRepo("github.com", "dev"), cache, fileUrl(bare));
    assert(git_(dir, "rev-parse", "HEAD") == git_(bare, "rev-parse", "dev"));
    assert(buildPath(dir, "C.java").isFile);
    // Updating a branch clone follows that branch (FETCH_HEAD), not the default branch.
    const work = buildPath(tmp, "work");
    git_(work, "checkout", "-q", "dev");
    const head = commit(work, "D.java", "class D {}\n");
    git_(work, "push", "-q", "origin", "dev");
    cloneInto(ghRepo("github.com", "dev"), cache, fileUrl(bare));
    assert(git_(dir, "rev-parse", "HEAD") == head);
    assert(buildPath(dir, "D.java").isFile);
}

/// Whether `f` throws.
private bool throws(lazy void f)
{
    try f();
    catch (Exception) return true;
    return false;
}

unittest // github: reports git failures
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const cache = buildPath(tmp, "cache");
    // Nothing to clone: an error, and no repository left behind.
    assert(throws(cloneInto(ghRepo("github.com"), cache, fileUrl(buildPath(tmp, "nowhere.git")))));
    assert(!buildPath(cache, "github.com/o/r/.git").exists);
    // A branch the remote does not have.
    const bare = remoteRepo(tmp);
    assert(throws(cloneInto(ghRepo("github.com", "missing"), cache, fileUrl(bare))));
    // The remote is gone when the mirror is updated.
    const dir = cloneInto(ghRepo("github.com"), cache, fileUrl(bare));
    git_(dir, "remote", "set-url", "origin", fileUrl(buildPath(tmp, "gone.git")));
    assert(throws(cloneInto(ghRepo("github.com"), cache, fileUrl(bare))));
}

unittest // github: refuses to clone outside the cache
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const cache = buildPath(tmp, "cache");
    mkdirRecurse(cache);
    string message;
    try cloneInto(ghRepo(".."), cache, fileUrl(buildPath(tmp, "nowhere.git")));
    catch (Exception e) message = e.msg;
    assert(message.startsWith("refusing to clone outside the cache"), message);
    assert(!buildPath(tmp, "o").exists);
}

unittest // github: tells cache repositories from others
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const cache = buildPath(tmp, "cache"), inside = buildPath(cache, "github.com/o/r");
    const sibling = buildPath(tmp, "cache-other/github.com/o/r");
    mkdirRecurse(inside);
    mkdirRecurse(sibling);
    assert(isCachedIn(inside, cache));
    assert(isCachedIn(cache, cache));
    assert(!isCachedIn(sibling, cache));
    assert(!isCachedIn(tmp, cache));
    assert(!isCachedIn(buildPath(cache, "missing"), cache));
    assert(!isCachedIn(inside, buildPath(tmp, "no-cache-yet")));
}

unittest // github: compares real paths for the cache
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const cache = buildPath(tmp, "cache"), inside = buildPath(cache, "github.com/o/r"), outside = buildPath(tmp, "elsewhere");
    mkdirRecurse(inside);
    mkdirRecurse(outside);
    // A link into the cache counts as inside, a link in the cache pointing out of it does not.
    symlink(inside, buildPath(tmp, "link-in"));
    symlink(outside, buildPath(cache, "link-out"));
    assert(isCachedIn(buildPath(tmp, "link-in"), cache));
    assert(!isCachedIn(buildPath(cache, "link-out"), cache));
}

// ------------------------------------------------------------------ blobless-clone completion

unittest // github: fetches every missing object of a partial clone
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const bare = remoteRepo(tmp), clone = buildPath(tmp, "clone");
    gitIn(null, false, "clone", "-q", "--filter=blob:none", fileUrl(bare), clone);
    assert(missing(clone) == 1);
    prefetchMissing(realPath(clone));
    assert(missing(clone) == 0);
    // Nothing missing: nothing to do.
    prefetchMissing(realPath(clone));
    assert(missing(clone) == 0);
}

unittest // github: never fetches for a partial clone outside the cache
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const bare = remoteRepo(tmp), clone = buildPath(tmp, "clone");
    gitIn(null, false, "clone", "-q", "--filter=blob:none", fileUrl(bare), clone);
    assert(missing(clone) == 1);
    History history;
    assert(collect(new SourceAnalyzer, realPath(clone), true, 2, history));
    // The old A.java could not be read, but it was not fetched either.
    assert(missing(clone) == 1);
}

// ------------------------------------------------------------------ remote detection

/// A repository in `dir/repo` with one commit (sub/inner/A.java) and the remote `origin` = `url`; its real path.
private string workRepo(string dir, string url)
{
    const repo = buildPath(dir, "repo");
    mkdirRecurse(repo);
    git_(repo, "init", "-q");
    commit(repo, "sub/inner/A.java", "class A {}\n");
    if (url !is null) git_(repo, "remote", "add", "origin", url);
    return realPath(repo);
}

private GitHubInfo detected(string root)
{
    GitHubInfo info;
    assert(GitHubInfo.detect(root, info), root);
    return info;
}

private bool detects(string root)
{
    GitHubInfo info;
    return GitHubInfo.detect(root, info);
}

unittest // github: links to the pushed commit, else to the branch
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const repo = workRepo(tmp, "git@github.com:o/r.git");
    // Not on any remote-tracking branch yet: the branch name.
    auto info = detected(repo);
    assert(info.repo == GitHubRepo("github.com", "o", "r", null));
    assert(info.gitRef == "main" && info.prefix == "");
    // Pushed (some remote-tracking branch contains HEAD): the commit.
    const sha = git_(repo, "rev-parse", "HEAD");
    git_(repo, "update-ref", "refs/remotes/origin/main", "HEAD");
    assert(detected(repo).gitRef == sha);
    // A new local commit is not pushed: the branch again; on a detached HEAD, the commit anyway.
    const local = commit(repo, "B.java", "class B {}\n");
    assert(detected(repo).gitRef == "main");
    git_(repo, "checkout", "-q", "--detach");
    assert(detected(repo).gitRef == local);
    // Pull requests are not part of detection.
    assert(!detected(repo).hasPulls);
}

unittest // github: prefixes the analysed subdirectory
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    const repo = workRepo(tmp, "https://github.com/o/r");
    assert(detected(buildPath(repo, "sub")).prefix == "sub/");
    assert(detected(buildPath(repo, "sub/inner")).prefix == "sub/inner/");
}

unittest // github: finds the GitHub remote
{
    const tmp = tempDirectory();
    scope (exit) rmdirRecurse(tmp);
    // An Enterprise origin.
    auto repo = workRepo(buildPath(tmp, "a"), "https://github.acme.corp/team/app.git");
    assert(detected(repo).repo == GitHubRepo("github.acme.corp", "team", "app", null));
    // origin elsewhere: the first remote that is on GitHub.
    repo = workRepo(buildPath(tmp, "b"), "https://gitlab.com/o/r.git");
    git_(repo, "remote", "add", "upstream", "ssh://git@github.com/u/p.git");
    assert(detected(repo).repo == GitHubRepo("github.com", "u", "p", null));
    // A GitHub URL that is rejected (".." as owner) counts as none; no remote at all, or no repository: none.
    assert(!detects(workRepo(buildPath(tmp, "c"), "https://github.com/../x")));
    assert(!detects(workRepo(buildPath(tmp, "d"), null)));
    mkdirRecurse(buildPath(tmp, "plain"));
    assert(!detects(realPath(buildPath(tmp, "plain"))));
}

// ------------------------------------------------------------------ command time limits

unittest // github: kills a command that outlives its time limit
{
    import core.sys.posix.signal : kill;
    import core.sys.posix.sys.types : pid_t;
    const dir = tempDirectory();
    scope (exit) rmdirRecurse(dir);
    const pidFile = buildPath(dir, "pid");
    // A fake git that writes its process id and then runs `body_`, found with dir as the whole PATH.
    string fake(string body_)
    {
        const git = script(dir, "git", format("echo $$ > '%s'\n%s", pidFile, body_));
        assert(lookup("git", (string k) => k == "PATH" ? dir : null) == git);
        return git;
    }
    // In time (a limit far beyond what the command needs, however busy the machine): the output, stripped; a
    // failure: nothing.
    const plenty = 60.seconds;
    assert(output([fake("echo ' ok '")], null, Config.none, plenty) == "ok");
    assert(output([fake("echo no; exit 3")], null, Config.none, plenty) is null);
    // Hanging with stdout open (after some output) for ten minutes: nothing is returned once the limit runs out, and
    // the process is gone. Only the outcome is checked, not how long it took.
    const git = fake("echo partial; exec sleep 600");
    if (pidFile.exists) remove(pidFile);
    const start = MonoTime.currTime;
    assert(output([git], null, Config.none, 3.seconds) is null);
    assert(MonoTime.currTime - start < 300.seconds, "not killed");
    // The script writes its id first thing; under extreme load it may not have got that far before the kill.
    if (pidFile.exists)
    {
        const pid = readText(pidFile).strip.to!pid_t;
        const deadline = MonoTime.currTime + 60.seconds;
        while (kill(pid, 0) == 0 && MonoTime.currTime < deadline) Thread.sleep(50.msecs);
        assert(kill(pid, 0) != 0, "the command still runs");
    }
}

// ------------------------------------------------------------------ URL parsing

unittest // github: parses every accepted form and rejects the rest (the same table as in Java and Rust)
{
    static immutable string[5][] accepted = [
        // input, host, owner, name, branch ("" = none)
        ["https://github.com/o/r", "github.com", "o", "r", ""],
        ["http://github.com/o/r", "github.com", "o", "r", ""],
        ["https://github.com/o/r.git", "github.com", "o", "r", ""],
        ["https://github.com/o/r/", "github.com", "o", "r", ""],
        ["https://github.com/o/r.git.git", "github.com", "o", "r.git", ""],
        ["https://user:pw@github.com:443/o/r", "github.com", "o", "r", ""],
        ["https://GitHub.COM/Some-Org/My_Repo.js", "github.com", "Some-Org", "My_Repo.js", ""],
        ["  https://github.com/o/r \n", "github.com", "o", "r", ""],
        ["https://github.com/o/r/tree/main", "github.com", "o", "r", "main"],
        ["https://github.com/o/r/tree/release/2.x/", "github.com", "o", "r", "release/2.x"],
        ["https://github.com/o/r.git/tree/x", "github.com", "o", "r", "x"],
        ["git@github.com:o/r.git", "github.com", "o", "r", ""],
        ["git@github.com:o/r", "github.com", "o", "r", ""],
        ["deploy@github.acme.corp:team/app.git", "github.acme.corp", "team", "app", ""],
        ["ssh://git@github.com/o/r.git", "github.com", "o", "r", ""],
        ["ssh://git@github.com:22/o/r", "github.com", "o", "r", ""],
        ["ssh://github.com/o/r/", "github.com", "o", "r", ""],
        ["github:o/r", "github.com", "o", "r", ""],
        ["github:o/r.git", "github.com", "o", "r", ""],
        ["github:o/r@dev", "github.com", "o", "r", "dev"],
        ["github:o/r@feature/x-1", "github.com", "o", "r", "feature/x-1"],
    ];
    foreach (c; accepted)
    {
        GitHubRepo r;
        assert(GitHubRepo.parse(c[0], r), c[0]);
        assert(r == GitHubRepo(c[1], c[2], c[3], c[4].length ? c[4] : null), c[0]);
    }
    foreach (s; ["https://gitlab.com/o/r", "src/main/java", "github.com/o/r", "https://github.com/o",
            "https://github.com/o/r/issues", "https://github.com/o/r/tree/", "https://github.com/o/r?tab=1",
            "https://github.com/o/r#readme", "ftp://github.com/o/r", "file:///github.com/o/r", "github:o", "github:o/r@",
            "https://github.com/o/r/x", ""])
    {
        GitHubRepo r;
        assert(!GitHubRepo.parse(s, r), s);
    }
    foreach (s; ["github:../..", "https://github.com/../r", "git@github.com:o/..", "https://github.com/o/./",
            "ssh://git@github.com/./r", "https://github.com/o/r/tree/a^b", "https://github.com/o/r/tree/a?b",
            "https://github.com/o/r/tree/a*b", "https://github.com/o/r/tree/a[b", "github:o/r@a\x01b",
            "github:o/r@a\x7fb", "github:o/r@-"])
    {
        GitHubRepo r;
        assert(throws(GitHubRepo.parse(s, r)), s);
    }
    // The URLs derived from a repository.
    const pub = parsed("github:o/r@dev");
    assert(pub.webUrl == "https://github.com/o/r");
    assert(pub.restApi == "https://api.github.com" && pub.graphqlApi == "https://api.github.com/graphql");
    assert(pub.toString == "o/r@dev");
    const ent = parsed("git@github.acme.corp:team/app.git");
    assert(ent.restApi == "https://github.acme.corp/api/v3" && ent.graphqlApi == "https://github.acme.corp/api/graphql");
    assert(ent.toString == "team/app");
}

// ------------------------------------------------------------------ the GitHub part of the city JSON

private enum prefix = "testdata/java-project/";

private JSONValue city(bool hasGithub, GitHubInfo github)
{
    const root = realPath(buildNormalizedPath(__FILE_FULL_PATH__.dirName, "..", "..", "..", "testdata", "java-project"));
    return parseJSON(CityModel.build(new SourceAnalyzer, root, true, false, hasGithub, github, 0).json);
}

/// Building id → its `prs` (only the buildings that have any), as JSON text.
private string[string] prsById(JSONValue city)
{
    string[string] out_;
    foreach (b; city["buildings"].array)
        if (auto p = "prs" in b) out_[b["id"].str] = p.toString;
    return out_;
}

private PullRequest pr(long number, string author, bool draft, string[] files)
{
    return PullRequest(number, format("PR %d", number), format("https://github.com/acme/shop/pull/%d", number), author,
        "2026-10-01T10:00:00Z", draft, files);
}

private JSONValue pullJson(long number, string author, bool draft, long files)
{
    JSONValue v = ["number": JSONValue(number), "title": JSONValue(format("PR %d", number)),
        "url": JSONValue(format("https://github.com/acme/shop/pull/%d", number)),
        "author": author is null ? JSONValue(null) : JSONValue(author),
        "updatedAt": JSONValue("2026-10-01T10:00:00Z"), "draft": JSONValue(draft), "files": JSONValue(files)];
    return v;
}

private GitHubInfo shop(string gitRef, string prefix_, bool hasPulls, PullRequest[] pulls, string source)
{
    return GitHubInfo(GitHubRepo("github.com", "acme", "shop", null), gitRef, prefix_, hasPulls, pulls, source);
}

/// The expected `github` section; `pulls` null: without pull requests.
private JSONValue section(string gitRef, string prefix_, string source, JSONValue[] pulls)
{
    JSONValue v = ["host": "github.com", "owner": "acme", "repo": "shop", "url": "https://github.com/acme/shop",
        "ref": gitRef, "prefix": prefix_];
    if (source !is null)
    {
        v["pullsSource"] = source;
        v["pulls"] = JSONValue(pulls);
    }
    return v;
}

unittest // github: matches pull requests to buildings by repository path
{
    const registry = prefix ~ "core/src/main/java/com/acme/core/Registry.java";
    auto pulls = [pr(11, "ann", false, [registry, "README.md"]), pr(12, null, true, [registry, prefix ~ "Scratch.java"]),
        // Root-relative (no prefix) and differently cased paths match nothing.
        pr(13, "bob", false, ["core/src/main/java/com/acme/core/Circle.java", prefix ~ "core/src/main/java/com/acme/core/circle.java"])];
    auto c = city(true, shop("0123456789abcdef0123456789abcdef01234567", prefix, true, pulls, "all open PRs"));
    assert(c["github"] == section("0123456789abcdef0123456789abcdef01234567", prefix, "all open PRs",
        [pullJson(11, "ann", false, 2), pullJson(12, null, true, 2), pullJson(13, "bob", false, 2)]), c["github"].toString);
    // Every type of Registry.java has both PRs touching it; Scratch has the second only.
    string[string] expected = ["Scratch": "[1]"];
    foreach (id; ["com.acme.core.Registry", "com.acme.core.Registry.Cursor", "com.acme.core.Registry.Cursor.Step",
            "com.acme.core.Registry.Entry", "com.acme.core.Registry.Listener", "com.acme.core.Registry.Mode",
            "com.acme.core.Registry.Stats"])
        expected[id] = "[0,1]";
    assert(prsById(c) == expected, prsById(c).text);
}

unittest // github: leaves pull requests out when they were not fetched
{
    auto c = city(true, shop("main", "", false, null, null));
    assert(c["github"] == section("main", "", null, null), c["github"].toString);
    assert(prsById(c).length == 0);
    // No GitHub context at all: no section.
    assert(("github" in city(false, GitHubInfo.init)) is null);
}

unittest // github: matches at the repository top without prefix
{
    auto c = city(true, shop("main", "", true, [pr(1, "ann", false, ["Scratch.java"])], "latest 1 open PRs (no token)"));
    assert(prsById(c) == ["Scratch": "[0]"]);
    assert(c["github"]["pullsSource"].str == "latest 1 open PRs (no token)");
}
