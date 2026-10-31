/// Serves the web viewer and the city model on localhost, and pushes rebuild notifications as server-sent events.
/// A deliberately small HTTP/1.1 server: one thread per connection, GET only, loopback only.
///
/// Routes: `/api/city` (the model as JSON), `/api/source?id=N` (source of building N), `/api/events` (server-sent
/// events) and the static viewer files from `codecity.assets`. Every response is sent with `Connection: close`, so
/// each connection carries exactly one request. The method is not checked: any request line is answered as a GET.
///
/// Threads and locking: the main thread accepts connections; each connection is handled on its own daemon thread;
/// a daemon heartbeat thread pings the event streams; `update` is called from the watcher's thread. `lock` guards
/// `model_`, `clients` and every `EventClient`'s fields. A request takes the current model once at its start and
/// uses that snapshot throughout, so a concurrent swap never mixes two models in one response.
///
/// Who may connect: binding to 127.0.0.1 keeps other machines out, but not web pages open in the user's browser. A
/// page on `evil.example` can point its DNS name at 127.0.0.1 ("DNS rebinding") and then read this server's responses
/// as same-origin. Such requests carry the attacker's name in their `Host` header, so every request whose `Host` is
/// not a loopback name (`isAllowedHost`) is refused with 403. Ordinary cross-origin requests need no check: no
/// response carries CORS headers, so the browser does not let the requesting page read it, and no endpoint changes
/// any state.
module codecity.server;

import codecity.assets;
import codecity.city : CityModel;
import codecity.json : JsonWriter;
import core.sync.condition : Condition;
import core.sync.mutex : Mutex;
import core.thread : Thread;
import core.time : seconds;
import std.algorithm : min, remove, startsWith;
import std.array : array;
import std.range : iota;
import std.conv : to;
import std.file : attrIsFile, getLinkAttributes, getSize, read;
import std.format : format;
import std.socket;
import std.string : indexOf, split, strip, toLower;
import std.utf : validate;

/// Larger source files are not sent to the viewer (a placeholder comment is sent instead).
private enum maxSourceBytes = 4UL * 1024 * 1024;

/// One open event stream: a queue the broadcaster appends to and the connection thread drains; `closed` is set by
/// `CityServer.close` to end the stream. Both fields are guarded by `CityServer.lock`.
private final class EventClient
{
    string[] queue;
    bool closed;
}

/// The local viewer server. Construct it (which binds the port), then call `run`, which serves until `close`; the
/// program itself never calls `close` (it ends with Ctrl+C), but tests do, so no server outlives its test.
final class CityServer
{
    private Socket listener;
    /// Set by `close`; `run`, the heartbeat and the event streams end when they see it.
    private shared bool stopped;
    private CityModel model_;
    private Mutex lock;
    /// Signalled (with `lock` held) whenever a message is queued for the event streams.
    private Condition wake;
    private EventClient[] clients;
    ushort port;

    /// Binds to 127.0.0.1 on the first free port from `preferred` to `preferred + 19` (not beyond 65535); failing
    /// those, on any free port the OS picks, as does a `preferred` of 0. Throws if even that fails. Binding to the
    /// loopback address keeps the server unreachable from other machines.
    this(CityModel model, ushort preferred)
    {
        model_ = model;
        lock = new Mutex;
        wake = new Condition(lock);
        Exception last;
        // Port 0 last: the OS picks a free one.
        foreach (p; iota(preferred, min(preferred + 20, 65_536)).array ~ 0)
        {
            try
            {
                auto s = new TcpSocket(AddressFamily.INET);
                // SO_REUSEADDR lets a restarted server bind again while old connections are still in TIME_WAIT.
                s.setOption(SocketOptionLevel.SOCKET, SocketOption.REUSEADDR, true);
                s.bind(new InternetAddress("127.0.0.1", cast(ushort) p));
                s.listen(64);
                listener = s;
                // The port actually bound, which differs from `preferred` after the fallback or for port 0.
                port = (cast(InternetAddress) s.localAddress).port;
                return;
            }
            catch (Exception e)
                last = e;
        }
        throw new Exception("cannot bind a port: " ~ last.msg);
    }

    string url() const { return format("http://localhost:%d/", port); }

    private CityModel current()
    {
        lock.lock();
        scope (exit) lock.unlock();
        return model_;
    }

    /// Swaps in a rebuilt model and tells connected viewers to reload it.
    ///
    /// The SSE message is an `update` event whose data is the new type count; the viewer then fetches `/api/city`
    /// again. Requests already in progress finish with the model they started with.
    void update(CityModel next)
    {
        lock.lock();
        model_ = next;
        lock.unlock();
        broadcast(format("event: update\ndata: {\"types\":%d}\n\n", next.buildings.length));
    }

    /// Queues `message` (an already framed SSE message) for every open event stream and wakes their threads.
    private void broadcast(string message)
    {
        lock.lock();
        scope (exit) lock.unlock();
        foreach (c; clients) c.queue ~= message;
        wake.notifyAll();
    }

    /// Serves until `close` is called (in the program: forever).
    void run()
    {
        import core.atomic : atomicLoad;
        import core.time : msecs;
        // Every 20 s an SSE comment line (": ping", ignored by EventSource) goes to every stream. This is also how a
        // stream thread notices a closed browser tab: the thread blocks until there is something to send, and only
        // a failed send ends it. The wait is taken in short steps so that the thread ends soon after `close`.
        auto heartbeat = new Thread({
            while (!atomicLoad(stopped))
            {
                foreach (_; 0 .. 200)
                {
                    if (atomicLoad(stopped)) return;
                    Thread.sleep(100.msecs);
                }
                broadcast(": ping\n\n");
            }
        });
        heartbeat.isDaemon = true;
        heartbeat.start();
        while (!atomicLoad(stopped))
        {
            Socket conn;
            // A failed accept (e.g. a connection reset before it was accepted) must not stop the server; one that
            // fails because `close` shut the listener down does.
            try conn = listener.accept();
            catch (Exception) continue;
            serveInThread(conn);
        }
    }

    /// Stops the server: `run` returns, the heartbeat ends and every open event stream is closed. Shutting the
    /// listening socket down (not just closing it) is what wakes a thread blocked in `accept` on Linux. Safe to call
    /// more than once, and before `run`.
    void close()
    {
        import core.atomic : atomicStore;
        atomicStore(stopped, true);
        lock.lock();
        foreach (c; clients) c.closed = true;
        wake.notifyAll();
        lock.unlock();
        try listener.shutdown(SocketShutdown.BOTH);
        catch (Exception) {}
        listener.close();
    }

    /// A separate function so each connection's thread gets its own closure frame (a closure created directly in the
    /// accept loop would share the loop variable, which the next iteration resets).
    private void serveInThread(Socket conn)
    {
        auto t = new Thread(() {
            try handle(conn);
            catch (Throwable e)
            {
                import std.stdio : stderr;
                { import app : printable; stderr.writeln("request failed: ", printable(e.msg)); }
            }
        });
        t.isDaemon = true;
        t.start();
    }

    /// Reads one request and dispatches it by path. Of the headers only `Host` is looked at (a foreign one is refused,
    /// see `isAllowedHost`); any body is ignored. The query string is not percent-decoded (only `/api/source` uses it,
    /// with a plain number).
    private void handle(Socket conn)
    {
        scope (exit) conn.close();
        // A client that connects but sends nothing would otherwise hold its thread forever.
        conn.setOption(SocketOptionLevel.SOCKET, SocketOption.RCVTIMEO, 10.seconds);
        char[8192] buf;
        string request;
        // Read until the blank line that ends the headers. After 64 KiB without one, give up reading and try to
        // parse what arrived.
        while (request.indexOf("\r\n\r\n") < 0 && request.length < 65536)
        {
            const n = conn.receive(buf[]);
            // The garbage collector's stop-the-world signals interrupt a blocked receive, and with a receive timeout
            // set Linux does not restart it: that is no reason to drop the request.
            if (n < 0 && interrupted) continue;
            if (n <= 0) return;
            request ~= buf[0 .. n].idup;
        }
        // Request line: "<method> <target> HTTP/1.1".
        const lineEnd = request.indexOf("\r\n");
        auto parts = request[0 .. lineEnd < 0 ? $ : lineEnd].split(" ");
        if (parts.length < 2) return;
        const target = parts[1];
        const q = target.indexOf('?');
        const path = q < 0 ? target : target[0 .. q];
        const query = q < 0 ? "" : target[q + 1 .. $];
        if (!isAllowedHost(headerValue(request, "host"), port))
        {
            send(conn, 403, "text/plain", "forbidden host");
            return;
        }
        auto model = current();
        switch (path)
        {
        case "/api/city":
            send(conn, 200, "application/json; charset=utf-8", model.json);
            break;
        case "/api/source":
            source(conn, model, query);
            break;
        case "/api/events":
            events(conn);
            break;
        default:
            string ct, body_;
            if (asset(path, ct, body_)) send(conn, 200, ct, body_);
            else send(conn, 404, "text/plain", "not found");
        }
    }

    /// Sends a complete response. `Cache-Control: no-store` makes the browser fetch the model and viewer anew after
    /// every rebuild. Send errors are ignored: the connection is closed afterwards anyway.
    private static void send(Socket conn, int status, string contentType, const(char)[] body_)
    {
        const reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Bad Request";
        auto head = format("HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
            status, reason, contentType, body_.length);
        sendAll(conn, head);
        sendAll(conn, body_);
    }

    /// `send` may write only part of the buffer, so loop until everything is out. False when the peer has gone.
    private static bool sendAll(Socket conn, const(char)[] data)
    {
        while (data.length)
        {
            const n = conn.send(data);
            if (n < 0 && interrupted) continue; // a signal, not the peer going away (see `handle`)
            if (n <= 0 || n == Socket.ERROR) return false;
            data = data[n .. $];
        }
        return true;
    }

    /// Only files that are part of the analysed model can be read — never arbitrary paths.
    ///
    /// `id` is a building index (`CityModel.buildings`), so the client cannot name a path at all. Read errors and
    /// oversized files are reported inside the `code` field rather than as an HTTP error, so the viewer shows them
    /// in place of the source. The attributes are read without following links, and only a regular file is read:
    /// should the file have been replaced by a symbolic link (to a file outside the tree) or a FIFO since the analysis,
    /// it is reported as unreadable rather than followed or blocked on. An error names the file by its path relative
    /// to the root, never the absolute path. Invalid UTF-8 is replaced (`sanitize`) because the JSON writer needs valid UTF-8.
    private static void source(Socket conn, CityModel model, string query)
    {
        // The first `id=` parameter decides. A missing or malformed id (anything but decimal digits, e.g. `-1`, `+1`
        // or `abc`) is a bad request; a well-formed one that names no building is not found. The Java and Rust
        // editions answer the same.
        import std.ascii : isDigit;
        import std.algorithm : all;
        bool found;
        size_t id;
        foreach (kv; query.split("&"))
            if (kv.startsWith("id="))
            {
                const v = kv[3 .. $];
                if (v.length && v.all!isDigit)
                    try id = v.to!size_t, found = true;
                    catch (Exception) {} // too large for size_t
                break;
            }
        if (!found)
        {
            send(conn, 400, "text/plain", "bad id");
            return;
        }
        if (id >= model.buildings.length)
        {
            send(conn, 404, "text/plain", "unknown type");
            return;
        }
        auto b = &model.buildings[id];
        auto t = &model.analysis.types[b.index];
        string code;
        try
        {
            if (!attrIsFile(getLinkAttributes(t.file))) code = "// unable to read " ~ b.file;
            else if (getSize(t.file) > maxSourceBytes) code = "// file too large to display";
            else
            {
                code = cast(string) read(t.file);
                try validate(code);
                catch (Exception)
                {
                    import std.encoding : sanitize;
                    code = sanitize(code);
                }
            }
        }
        catch (Exception)
            code = "// unable to read " ~ b.file;
        auto w = JsonWriter.create();
        w.beginObject().field("file", b.file).field("line", t.line).field("endLine", t.endLine).field("code", code).endObject();
        send(conn, 200, "application/json; charset=utf-8", w.finish());
    }

    /// Serves one server-sent-events stream until the browser goes away (the thread lives as long as the stream).
    ///
    /// The headers are sent without a Content-Length, and the body is an open-ended sequence of SSE messages.
    /// `retry: 2000` tells EventSource to reconnect after 2 s if the stream breaks, e.g. when the server restarts.
    private void events(Socket conn)
    {
        import core.atomic : atomicLoad;
        auto client = new EventClient;
        lock.lock();
        clients ~= client;
        client.closed = atomicLoad(stopped); // a stream that arrives while the server closes ends at once
        lock.unlock();
        scope (exit)
        {
            lock.lock();
            clients = clients.remove!(c => c is client);
            lock.unlock();
        }
        if (!sendAll(conn, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\nretry: 2000\n\n"))
            return;
        while (true)
        {
            // Take the whole queue under the lock, then send without holding it, so a slow client never blocks the
            // broadcaster or other streams.
            string[] batch;
            lock.lock();
            // Loop because wake is shared by all streams (notifyAll) and waits can wake spuriously.
            while (client.queue.length == 0 && !client.closed) wake.wait();
            if (client.closed)
            {
                lock.unlock();
                return; // the server is closing
            }
            batch = client.queue;
            client.queue = null;
            lock.unlock();
            foreach (m; batch)
                if (!sendAll(conn, m)) return; // browser went away
        }
    }
}

/// True when the last socket call failed only because a signal interrupted it (EINTR); it can simply be repeated.
private bool interrupted()
{
    import core.stdc.errno : EINTR, errno;
    return errno == EINTR;
}

/// True for a `Host` header naming this server by a loopback name: `localhost`, `127.0.0.1` or `[::1]`, either alone or
/// followed by `:port` (case-insensitive). Anything else, including a missing header (null), is what a DNS-rebinding
/// page would send (see the module comment). The Java and Rust editions apply the same rule.
bool isAllowedHost(string host, ushort port)
{
    if (host is null) return false;
    auto h = host.toLower;
    const suffix = format(":%d", port);
    if (h.length >= suffix.length && h[$ - suffix.length .. $] == suffix) h = h[0 .. $ - suffix.length];
    return h == "localhost" || h == "127.0.0.1" || h == "[::1]";
}

/// The value of the first header called `name` (lower case; header names are matched case-insensitively), with
/// surrounding blanks removed, or null if the request has no such header. `request` is the raw request head.
private string headerValue(string request, string name)
{
    foreach (line; request.split("\r\n")[1 .. $])
    {
        if (line.length == 0) break; // end of the headers
        const colon = line.indexOf(':');
        if (colon > 0 && line[0 .. colon].toLower == name) return line[colon + 1 .. $].strip;
    }
    return null;
}

unittest
{
    foreach (ok; ["localhost", "localhost:8080", "LOCALHOST:8080", "127.0.0.1:8080", "[::1]:8080", "[::1]"])
        assert(isAllowedHost(ok, 8080), ok);
    foreach (bad; ["", "evil.example", "evil.example:8080", "localhost:8081", "localhost.:8080", "127.0.0.2:8080",
            "localhost.evil.example:8080", "localhost:8080:8080", "0.0.0.0:8080"])
        assert(!isAllowedHost(bad, 8080), bad);
    assert(!isAllowedHost(null, 8080));
    assert(headerValue("GET / HTTP/1.1\r\nhOsT:  localhost:1 \r\nHost: x\r\n\r\n", "host") == "localhost:1");
    assert(headerValue("GET / HTTP/1.1\r\nX: y\r\n\r\nHost: x", "host") is null);
}

/// A DNS-rebinding request is refused; the source endpoint does not follow a link swapped in after analysis. The Java
/// and Rust editions have the same test.
version (Posix) unittest
{
    import codecity.analysis : SourceAnalyzer;
    import codecity.github : GitHubInfo;
    import std.algorithm : canFind;
    import std.file : mkdirRecurse, remove, rmdirRecurse, symlink, tempDir, write;
    import std.path : buildPath;
    import std.uuid : randomUUID;

    const tmp = buildPath(tempDir, "codecity-server-test-" ~ randomUUID.toString);
    scope (exit) rmdirRecurse(tmp);
    const root = buildPath(tmp, "root");
    mkdirRecurse(root);
    write(buildPath(root, "A.java"), "class A {}");
    write(buildPath(tmp, "secret.txt"), "TOP SECRET");
    auto server = new CityServer(CityModel.build(new SourceAnalyzer, root, true, false, false, GitHubInfo.init), 0);
    scope (exit) server.close();
    auto t = new Thread({ server.run(); });
    t.isDaemon = true;
    t.start();

    // Sends a raw GET (so the Host header is fully controlled; null leaves it out) and returns the whole response.
    string get(string path, string host)
    {
        auto s = new TcpSocket(new InternetAddress("127.0.0.1", server.port));
        scope (exit) s.close();
        s.setOption(SocketOptionLevel.SOCKET, SocketOption.RCVTIMEO, 20.seconds); // a stuck server fails the test
        s.send("GET " ~ path ~ " HTTP/1.1\r\n" ~ (host is null ? "" : "Host: " ~ host ~ "\r\n") ~ "Connection: close\r\n\r\n");
        char[4096] buf;
        string r;
        for (ptrdiff_t n; (n = receiveRetrying(s, buf[])) > 0;) r ~= buf[0 .. n].idup;
        return r;
    }
    const p = format(":%d", server.port);
    assert(get("/api/city", "evil.example" ~ p).startsWith("HTTP/1.1 403"));
    assert(get("/", "evil.example" ~ p).startsWith("HTTP/1.1 403"));
    assert(get("/api/source?id=0", null).startsWith("HTTP/1.1 403"));
    assert(get("/api/city", "localhost" ~ p).startsWith("HTTP/1.1 200"));
    assert(get("/api/source?id=0", "127.0.0.1" ~ p).canFind("class A {}"));

    remove(buildPath(root, "A.java"));
    symlink(buildPath(tmp, "secret.txt"), buildPath(root, "A.java"));
    const r = get("/api/source?id=0", "localhost" ~ p);
    assert(!r.canFind("TOP SECRET"), r);
    assert(r.canFind("unable to read A.java"), r);
    assert(!r.canFind(tmp), r);
}

version (unittest)
{
    /// `s.receive(buf)`, retried when a signal interrupts it. With a receive timeout set, Linux does not restart an
    /// interrupted receive, and the garbage collector's stop-the-world signals interrupt blocked threads routinely; a
    /// test would otherwise take that for the end of the stream. Returns the byte count, 0 at the end of the stream
    /// and -1 on an error or timeout.
    ptrdiff_t receiveRetrying(Socket s, void[] buf)
    {
        import core.stdc.errno : EINTR, errno;
        while (true)
        {
            const n = s.receive(buf);
            if (n >= 0 || errno != EINTR) return n;
        }
    }

    /// Analyses `root` without git or GitHub and serves it on a port the system picks, on a daemon thread. The caller
    /// closes the server (`scope (exit) server.close()`).
    private CityServer serveForTest(string root)
    {
        import codecity.analysis : SourceAnalyzer;
        import codecity.git : realPath;
        import codecity.github : GitHubInfo;
        auto server = new CityServer(CityModel.build(new SourceAnalyzer, realPath(root), true, false, false, GitHubInfo.init), 0);
        auto t = new Thread({ server.run(); });
        t.isDaemon = true;
        t.start();
        return server;
    }

    /// Sends a raw GET (so the Host header is fully controlled; null leaves it out) and returns the whole response.
    private string httpGet(ushort port, string path, string host)
    {
        auto s = new TcpSocket(new InternetAddress("127.0.0.1", port));
        scope (exit) s.close();
        // A stuck server makes the receive fail after this long instead of hanging the test run.
        s.setOption(SocketOptionLevel.SOCKET, SocketOption.RCVTIMEO, 20.seconds);
        s.send("GET " ~ path ~ " HTTP/1.1\r\n" ~ (host is null ? "" : "Host: " ~ host ~ "\r\n") ~ "Connection: close\r\n\r\n");
        char[65_536] buf;
        string r;
        for (ptrdiff_t n; (n = receiveRetrying(s, buf[])) > 0;) r ~= buf[0 .. n].idup;
        return r;
    }

    /// The value of header `name` in a raw response (lower case; names compared case-insensitively), or null.
    private string headerOf(string response, string name)
    {
        return headerValue(response, name);
    }

    /// The body of a raw response (everything after the blank line).
    private string bodyOf(string response)
    {
        const at = response.indexOf("\r\n\r\n");
        return at < 0 ? "" : response[at + 4 .. $];
    }

    /// A fresh temporary directory for one test; the caller removes it.
    private string serverTestDir()
    {
        import std.file : mkdirRecurse, tempDir;
        import std.path : buildPath;
        import std.uuid : randomUUID;
        const d = buildPath(tempDir, "codecity-server-test-" ~ randomUUID.toString);
        mkdirRecurse(d);
        return d;
    }
}

/// The viewer's files are served with their content types and `no-store`; the page carries its Content-Security-Policy
/// with the inline scripts' hashes filled in. Paths that are not viewer files or endpoints, including paths below an
/// endpoint and paths that try to leave the asset list, are 404s. The Java and Rust editions have the same test.
unittest
{
    import std.algorithm : canFind;
    import std.file : rmdirRecurse, write;
    import std.path : buildPath;
    const d = serverTestDir();
    scope (exit) rmdirRecurse(d);
    write(buildPath(d, "A.java"), "class A {}");
    auto server = serveForTest(d);
    scope (exit) server.close();
    const port = server.port, host = format("localhost:%d", port);
    foreach (path; ["/", "/index.html"])
    {
        const r = httpGet(port, path, host);
        assert(r.startsWith("HTTP/1.1 200"), r);
        assert(headerOf(r, "content-type") == "text/html; charset=utf-8");
        assert(headerOf(r, "cache-control") == "no-store");
        assert(r.canFind(`<meta http-equiv="Content-Security-Policy"`), path);
        assert(r.canFind("'sha256-"), path);
        assert(!r.canFind("{{inline-script-hashes}}"), path);
    }
    assert(headerOf(httpGet(port, "/app.js", host), "content-type") == "text/javascript; charset=utf-8");
    assert(headerOf(httpGet(port, "/style.css", host), "content-type") == "text/css; charset=utf-8");
    assert(headerOf(httpGet(port, "/favicon.svg", host), "content-type") == "image/svg+xml");
    const city = httpGet(port, "/api/city", host);
    assert(headerOf(city, "content-type") == "application/json; charset=utf-8");
    assert(headerOf(city, "cache-control") == "no-store");
    assert(bodyOf(city).startsWith("{") && bodyOf(city).canFind(`"A"`), city);
    foreach (path; ["/nope", "/web/app.js", "/../pom.xml", "/index.htm", "/api", "/api/", "/api/city/x", "/api/source/x",
            "/api/events/x", "/api/cities"])
    {
        const r = httpGet(port, path, host);
        assert(r.startsWith("HTTP/1.1 404"), path ~ ": " ~ r);
        assert(headerOf(r, "content-type") == "text/plain", path);
        assert(bodyOf(r) == "not found", path);
    }
}

/// `/api/source`: a missing or malformed id is a 400, a well-formed id that names no building a 404; neither answer
/// mentions a path. A valid id answers the file (relative), the type's line range and the code.
unittest
{
    import std.file : mkdirRecurse, rmdirRecurse, write;
    import std.path : buildPath;
    const d = serverTestDir();
    scope (exit) rmdirRecurse(d);
    mkdirRecurse(buildPath(d, "p"));
    write(buildPath(d, "p", "A.java"), "package p;\n\nclass A {\n}\n");
    auto server = serveForTest(d);
    scope (exit) server.close();
    const port = server.port, host = format("localhost:%d", port);
    foreach (query; ["", "?", "?id=", "?id=abc", "?id=-1", "?id=+0", "?id=0x1", "?id=1.0", "?x=1", "?id=99999999999999999999999"])
    {
        const r = httpGet(port, "/api/source" ~ query, host);
        assert(r.startsWith("HTTP/1.1 400"), query ~ ": " ~ r);
        assert(bodyOf(r) == "bad id", query);
    }
    foreach (query; ["?id=1", "?id=999", "?id=4294967296"])
    {
        const r = httpGet(port, "/api/source" ~ query, host);
        assert(r.startsWith("HTTP/1.1 404"), query ~ ": " ~ r);
        assert(bodyOf(r) == "unknown type", query);
    }
    const r = httpGet(port, "/api/source?x=1&id=0", host);
    assert(r.startsWith("HTTP/1.1 200"), r);
    assert(headerOf(r, "content-type") == "application/json; charset=utf-8");
    assert(bodyOf(r) == `{"file":"p/A.java","line":3,"endLine":4,"code":"package p;\n\nclass A {\n}\n"}`, bodyOf(r));
}

/// Files over 4 MiB are not sent (a placeholder comment is), a file of exactly 4 MiB is; a FIFO swapped in after the
/// analysis is reported as unreadable without blocking the request.
version (Posix) unittest
{
    import core.time : MonoTime;
    import std.algorithm : canFind;
    import std.array : replicate;
    import std.file : remove, rmdirRecurse, write;
    import std.path : buildPath;
    import std.process : execute;
    import std.stdio : File;
    const d = serverTestDir();
    scope (exit) rmdirRecurse(d);
    const a = buildPath(d, "A.java");
    write(a, "class A {}");
    auto server = serveForTest(d);
    scope (exit) server.close();
    const port = server.port, host = format("localhost:%d", port);
    {
        auto f = File(a, "r+");
        f.seek(maxSourceBytes);
        f.rawWrite("x"); // sparse, maxSourceBytes + 1 bytes
    }
    assert(httpGet(port, "/api/source?id=0", host).canFind(`"code":"// file too large to display"`));
    write(a, "x".replicate(maxSourceBytes));
    const r = httpGet(port, "/api/source?id=0", host);
    assert(r.canFind(`"code":"xxxx`), r[0 .. min($, 300)]);
    assert(!r.canFind("too large"));

    remove(a);
    assert(execute(["mkfifo", a]).status == 0);
    const start = MonoTime.currTime;
    const fifo = httpGet(port, "/api/source?id=0", host);
    assert(MonoTime.currTime - start < 10.seconds, "the request blocked on the FIFO");
    assert(fifo.canFind(`"code":"// unable to read A.java"`), fifo);
}

/// `/api/events`: a stream opens with the event-stream headers and a `retry` line; swapping in a new model sends one
/// `update` event with the new type count, after which `/api/city` serves the new model. A foreign host gets no stream.
unittest
{
    import codecity.analysis : SourceAnalyzer;
    import codecity.git : realPath;
    import codecity.github : GitHubInfo;
    import std.algorithm : canFind, endsWith;
    import std.file : rmdirRecurse, write;
    import std.path : buildPath;
    const d = serverTestDir();
    scope (exit) rmdirRecurse(d);
    write(buildPath(d, "A.java"), "class A {}");
    auto server = serveForTest(d);
    scope (exit) server.close();
    const port = server.port, host = format("localhost:%d", port);
    assert(httpGet(port, "/api/events", format("evil.example:%d", port)).startsWith("HTTP/1.1 403"));

    auto s = new TcpSocket(new InternetAddress("127.0.0.1", port));
    scope (exit) s.close();
    s.setOption(SocketOptionLevel.SOCKET, SocketOption.RCVTIMEO, 20.seconds);
    s.send("GET /api/events HTTP/1.1\r\nHost: " ~ host ~ "\r\n\r\n");
    // Reads until what was read ends with `marker`; fails at the end of the stream (or after the timeout).
    string readUntil(string marker)
    {
        string got;
        char[1] c;
        while (!got.endsWith(marker))
        {
            assert(receiveRetrying(s, c[]) == 1, "stream ended before " ~ marker ~ ": " ~ got);
            got ~= c[0];
        }
        return got;
    }
    const head = readUntil("retry: 2000\n\n");
    assert(head.startsWith("HTTP/1.1 200"), head);
    assert(headerOf(head, "content-type") == "text/event-stream; charset=utf-8");
    assert(headerOf(head, "cache-control") == "no-cache");

    write(buildPath(d, "Brand.java"), "class Brand {}");
    server.update(CityModel.build(new SourceAnalyzer, realPath(d), true, false, false, GitHubInfo.init));
    readUntil("event: update\ndata: {\"types\":2}\n\n");
    assert(httpGet(port, "/api/city", host).canFind(`"Brand"`));

    // Closing the server ends the open stream (and `run`), so nothing outlives the test.
    server.close();
    char[1] c;
    assert(receiveRetrying(s, c[]) == 0, "the event stream stayed open after close");
}

/// When the preferred port is taken, the server binds one of the next ones (the Java and Rust editions search the same
/// 20 ports) and reports the port it got in its URL; port 0 lets the system choose.
unittest
{
    import codecity.analysis : SourceAnalyzer;
    import codecity.github : GitHubInfo;
    import std.file : rmdirRecurse, write;
    import std.path : buildPath;
    const d = serverTestDir();
    scope (exit) rmdirRecurse(d);
    write(buildPath(d, "A.java"), "class A {}");
    auto taken = new TcpSocket(AddressFamily.INET);
    scope (exit) taken.close();
    taken.bind(new InternetAddress("127.0.0.1", 0));
    taken.listen(1);
    const busy = (cast(InternetAddress) taken.localAddress).port;
    auto server = new CityServer(CityModel.build(new SourceAnalyzer, d, true, false, false, GitHubInfo.init), busy);
    scope (exit) server.close();
    const port = server.port;
    assert(server.url == format("http://localhost:%d/", port));
    assert(port != busy, "bound the busy port");
    if (busy <= 65_535 - 19) assert(port > busy && port <= busy + 19, format("%d -> %d", busy, port));
    auto t = new Thread({ server.run(); });
    t.isDaemon = true;
    t.start();
    assert(httpGet(port, "/api/city", format("localhost:%d", port)).startsWith("HTTP/1.1 200"));
}
