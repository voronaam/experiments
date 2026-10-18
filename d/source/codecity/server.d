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
import std.file : getSize, read;
import std.format : format;
import std.socket;
import std.string : indexOf, split;
import std.utf : validate;

/// Larger source files are not sent to the viewer (a placeholder comment is sent instead).
private enum maxSourceBytes = 4UL * 1024 * 1024;

/// One open event stream: a queue the broadcaster appends to and the connection thread drains.
/// Both fields are guarded by `CityServer.lock`.
private final class EventClient
{
    string[] queue;
    bool closed;
}

/// The local viewer server. Construct it (which binds the port), then call `run`, which never returns.
final class CityServer
{
    private Socket listener;
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

    /// Serves forever.
    void run()
    {
        // Every 20 s an SSE comment line (": ping", ignored by EventSource) goes to every stream. This is also how a
        // stream thread notices a closed browser tab: the thread blocks until there is something to send, and only
        // a failed send ends it.
        auto heartbeat = new Thread({
            while (true)
            {
                Thread.sleep(20.seconds);
                broadcast(": ping\n\n");
            }
        });
        heartbeat.isDaemon = true;
        heartbeat.start();
        while (true)
        {
            Socket conn;
            // A failed accept (e.g. a connection reset before it was accepted) must not stop the server.
            try conn = listener.accept();
            catch (Exception) continue;
            serveInThread(conn);
        }
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
                stderr.writeln("request failed: ", e.msg);
            }
        });
        t.isDaemon = true;
        t.start();
    }

    /// Reads one request and dispatches it by path. Only the request line is looked at; headers and any body are
    /// ignored. The query string is not percent-decoded (only `/api/source` uses it, with a plain number).
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
        const reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Bad Request";
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
            if (n <= 0 || n == Socket.ERROR) return false;
            data = data[n .. $];
        }
        return true;
    }

    /// Only files that are part of the analysed model can be read — never arbitrary paths.
    ///
    /// `id` is a building index (`CityModel.buildings`), so the client cannot name a path at all. Read errors and
    /// oversized files are reported inside the `code` field rather than as an HTTP error, so the viewer shows them
    /// in place of the source. Invalid UTF-8 is replaced (`sanitize`) because the JSON writer needs valid UTF-8.
    private static void source(Socket conn, CityModel model, string query)
    {
        // size_t.max means "missing or not a number" and fails the range check below.
        size_t id = size_t.max;
        foreach (kv; query.split("&"))
            if (kv.startsWith("id="))
                try id = kv[3 .. $].to!size_t;
                catch (Exception) {}
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
            if (getSize(t.file) > maxSourceBytes) code = "// file too large to display";
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
        catch (Exception e)
            code = "// unable to read " ~ t.file ~ ": " ~ e.msg;
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
        auto client = new EventClient;
        lock.lock();
        clients ~= client;
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
            while (client.queue.length == 0) wake.wait();
            batch = client.queue;
            client.queue = null;
            lock.unlock();
            foreach (m; batch)
                if (!sendAll(conn, m)) return; // browser went away
        }
    }
}

