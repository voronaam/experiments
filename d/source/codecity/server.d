/// Serves the web viewer and the city model on localhost, and pushes rebuild notifications as server-sent events.
/// A deliberately small HTTP/1.1 server: one thread per connection, GET only, loopback only.
module codecity.server;

import codecity.assets;
import codecity.city : CityModel;
import codecity.json : JsonWriter;
import core.sync.condition : Condition;
import core.sync.mutex : Mutex;
import core.thread : Thread;
import core.time : seconds;
import std.algorithm : remove, startsWith;
import std.conv : to;
import std.file : getSize, read;
import std.format : format;
import std.socket;
import std.string : indexOf, split;
import std.utf : validate;

private enum maxSourceBytes = 4UL * 1024 * 1024;

/// One open event stream: a queue the broadcaster appends to and the connection thread drains.
private final class EventClient
{
    string[] queue;
    bool closed;
}

final class CityServer
{
    private Socket listener;
    private CityModel model_;
    private Mutex lock;
    private Condition wake;
    private EventClient[] clients;
    ushort port;

    this(CityModel model, ushort preferred)
    {
        model_ = model;
        lock = new Mutex;
        wake = new Condition(lock);
        Exception last;
        foreach (p; preferred .. cast(ushort)(preferred + 20))
        {
            try
            {
                auto s = new TcpSocket(AddressFamily.INET);
                s.setOption(SocketOptionLevel.SOCKET, SocketOption.REUSEADDR, true);
                s.bind(new InternetAddress("127.0.0.1", p));
                s.listen(64);
                listener = s;
                port = p;
                return;
            }
            catch (Exception e)
                last = e;
        }
        throw new Exception("cannot bind a port: " ~ (last ? last.msg : ""));
    }

    string url() const { return format("http://localhost:%d/", port); }

    private CityModel current()
    {
        lock.lock();
        scope (exit) lock.unlock();
        return model_;
    }

    /// Swaps in a rebuilt model and tells connected viewers to reload it.
    void update(CityModel next)
    {
        lock.lock();
        model_ = next;
        lock.unlock();
        broadcast(format("event: update\ndata: {\"types\":%d}\n\n", next.buildings.length));
    }

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

    private void handle(Socket conn)
    {
        scope (exit) conn.close();
        conn.setOption(SocketOptionLevel.SOCKET, SocketOption.RCVTIMEO, 10.seconds);
        char[8192] buf;
        string request;
        while (request.indexOf("\r\n\r\n") < 0 && request.length < 65536)
        {
            const n = conn.receive(buf[]);
            if (n <= 0) return;
            request ~= buf[0 .. n].idup;
        }
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

    private static void send(Socket conn, int status, string contentType, const(char)[] body_)
    {
        const reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Bad Request";
        auto head = format("HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
            status, reason, contentType, body_.length);
        sendAll(conn, head);
        sendAll(conn, body_);
    }

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
    private static void source(Socket conn, CityModel model, string query)
    {
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
            string[] batch;
            lock.lock();
            while (client.queue.length == 0) wake.wait();
            batch = client.queue;
            client.queue = null;
            lock.unlock();
            foreach (m; batch)
                if (!sendAll(conn, m)) return; // browser went away
        }
    }
}

