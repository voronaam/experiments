package dev.codecity.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.codecity.CityModel;
import dev.codecity.analysis.TypeInfo;
import dev.codecity.json.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serves the WebGL viewer and the city model on localhost, and pushes rebuild notifications over server-sent events.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code /}, {@code /index.html}, {@code /app.js}, {@code /walk.js}, {@code /replay.js}, {@code /style.css}, {@code /favicon.svg}: the viewer, read
 *       from the {@code web/} resources on the classpath. Any other path is a 404, so no file on disk is reachable
 *       by its path.</li>
 *   <li>{@code GET /api/city}: the whole city as JSON ({@link CityModel#json()}).</li>
 *   <li>{@code GET /api/source?id=N}: source text of the file declaring type {@code N} (an index into
 *       {@code model.types()}), with the type's line range.</li>
 *   <li>{@code GET /api/events}: a server-sent-event stream. It carries an {@code update} event after each watch-mode
 *       rebuild (the viewer then refetches {@code /api/city}) and a comment line every 20 s as a heartbeat.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * Requests run on virtual threads, one per exchange, so an SSE connection may simply block its thread for as long as
 * the browser keeps it open. The current model is a volatile field that the watcher's rebuild thread replaces as a
 * whole; each handler reads it once, so a request never mixes two models. Writes to an SSE stream come from the
 * request thread (the initial {@code retry} line), the heartbeat thread and the rebuild thread, so they are serialised
 * per client by locking on its {@link EventClient}.
 *
 * <p>The server binds to the loopback address only, so the city and the source code are not exposed to the network.
 */
public final class CityServer implements AutoCloseable {

    private static final Map<String, String> STATIC = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/app.js", "app.js",
            "/walk.js", "walk.js",
            "/replay.js", "replay.js",
            "/style.css", "style.css",
            "/favicon.svg", "favicon.svg");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml");

    /** Larger files are not sent to the source viewer; the same 4 MiB limit applies in the desktop viewer. */
    private static final long MAX_SOURCE_BYTES = 4L * 1024 * 1024;

    /**
     * One open SSE connection. {@code closed} is counted down when a write to it fails or the server closes, which
     * releases the request thread parked in {@link #events}.
     */
    private record EventClient(OutputStream out, CountDownLatch closed) {
    }

    private final HttpServer server;
    private final List<EventClient> clients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private volatile CityModel model;

    public CityServer(CityModel model, int preferredPort) throws IOException {
        this.model = model;
        this.server = bind(preferredPort);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/city", this::city);
        server.createContext("/api/source", this::source);
        server.createContext("/api/events", this::events);
        server.createContext("/", this::staticFile);
    }

    /**
     * Binds to the loopback address on {@code port} or, if that is taken, one of the next 19 ports; failing those, to
     * any free port the OS picks. Ports above 65535 are not tried, and port 0 lets the OS pick straight away.
     * {@link #url()} reports the port actually used.
     */
    private static HttpServer bind(int port) throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        for (int p = port; p < port + 20 && p <= 65535; p++) {
            try {
                return HttpServer.create(new InetSocketAddress(loopback, p), 0);
            } catch (BindException e) {
                // try the next one
            }
        }
        return HttpServer.create(new InetSocketAddress(loopback, 0), 0);
    }

    public void start() {
        server.start();
        // A line starting with ':' is an SSE comment, ignored by EventSource. Sending one regularly keeps idle
        // connections alive, and it is how a vanished browser is noticed: once a write fails the client is dropped.
        heartbeat.scheduleAtFixedRate(() -> broadcast(": ping\n\n"), 20, 20, TimeUnit.SECONDS);
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    /**
     * Swaps in a freshly built model and tells connected viewers to reload it. Called from the watcher's thread. The
     * event's data only carries the new type count; viewers fetch the model itself from {@code /api/city}.
     */
    public void update(CityModel next) {
        this.model = next;
        broadcast("event: update\ndata: {\"types\":" + next.types().size() + "}\n\n");
    }

    private void city(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", model.json().getBytes(StandardCharsets.UTF_8));
    }

    /** Only files that are part of the analysed model can be read — never arbitrary paths. */
    private void source(HttpExchange ex) throws IOException {
        CityModel current = model;
        int id;
        try {
            String query = ex.getRequestURI().getQuery();
            // Pull the digits of "id=N" out of the query string. Without a match the whole query is left as it is and
            // fails to parse, which ends up as a 400.
            id = Integer.parseInt(query == null ? "" : query.replaceFirst("^.*\\bid=(\\d+).*$", "$1"));
        } catch (NumberFormatException e) {
            send(ex, 400, "text/plain", "bad id".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (id < 0 || id >= current.types().size()) {
            send(ex, 404, "text/plain", "unknown type".getBytes(StandardCharsets.UTF_8));
            return;
        }
        TypeInfo t = current.types().get(id);
        String code;
        try {
            code = Files.size(t.file()) > MAX_SOURCE_BYTES
                    ? "// file too large to display"
                    : new String(Files.readAllBytes(t.file()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            code = "// unable to read " + t.file() + ": " + e.getMessage();
        }
        String json = new JsonWriter().beginObject()
                .field("file", current.analysis().root().relativize(t.file()).toString().replace('\\', '/'))
                .field("line", t.line())
                .field("endLine", t.endLine())
                .field("code", code)
                .endObject().toString();
        send(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Opens an SSE stream and keeps it open until a write to it fails or the server closes. Response length 0 tells
     * the JDK server to use chunked encoding, so the stream has no fixed end. {@code retry: 2000} asks the browser to
     * reconnect two seconds after the connection drops (for example when the server restarts).
     */
    private void events(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        EventClient client = new EventClient(ex.getResponseBody(), new CountDownLatch(1));
        clients.add(client);
        write(client, "retry: 2000\n\n");
        try {
            client.closed().await(); // hold the (virtual) thread until the browser goes away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(client);
            ex.close();
        }
    }

    private void broadcast(String message) {
        for (EventClient c : clients) {
            write(c, message);
        }
    }

    /** Writes and flushes one message; a failure means the browser has gone, so the client is dropped and released. */
    private void write(EventClient c, String message) {
        try {
            synchronized (c) {
                c.out().write(message.getBytes(StandardCharsets.UTF_8));
                c.out().flush();
            }
        } catch (IOException e) {
            clients.remove(c);
            c.closed().countDown();
        }
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String name = STATIC.get(ex.getRequestURI().getPath());
        if (name == null) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = resource(name);
        String type = CONTENT_TYPES.get(name.substring(name.lastIndexOf('.') + 1));
        send(ex, 200, type, body);
    }

    /** Reads one of the viewer's files from {@code /web/} on the classpath; {@code HtmlExporter} uses it too. */
    public static byte[] resource(String name) throws IOException {
        try (InputStream in = CityServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                throw new IOException("missing resource web/" + name);
            }
            return in.readAllBytes();
        }
    }

    /**
     * Sends a complete response. {@code no-store} stops the browser from caching anything, so a reload always gets
     * the current city and viewer files.
     */
    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** Stops the heartbeat, releases every parked SSE thread and stops the server without waiting for exchanges. */
    @Override
    public void close() {
        heartbeat.shutdownNow();
        clients.forEach(c -> c.closed().countDown());
        server.stop(0);
    }
}
