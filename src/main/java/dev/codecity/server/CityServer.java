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
 */
public final class CityServer implements AutoCloseable {

    private static final Map<String, String> STATIC = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/app.js", "app.js",
            "/style.css", "style.css",
            "/favicon.svg", "favicon.svg");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml");

    private static final long MAX_SOURCE_BYTES = 4L * 1024 * 1024;

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

    private static HttpServer bind(int port) throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        for (int p = port; p < port + 20; p++) {
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
        heartbeat.scheduleAtFixedRate(() -> broadcast(": ping\n\n"), 20, 20, TimeUnit.SECONDS);
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    /** Swaps in a freshly built model and tells connected viewers to reload it. */
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

    public static byte[] resource(String name) throws IOException {
        try (InputStream in = CityServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                throw new IOException("missing resource web/" + name);
            }
            return in.readAllBytes();
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
        clients.forEach(c -> c.closed().countDown());
        server.stop(0);
    }
}
