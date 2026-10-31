package dev.codecity.server;

import dev.codecity.CityModel;
import dev.codecity.analysis.SourceAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's endpoints and request checks; the Rust and D editions have the same tests. */
class CityServerTest {

    @Test
    void onlyLoopbackHostNamesAreAllowed() {
        for (String ok : new String[]{"localhost", "localhost:8080", "LOCALHOST:8080", "127.0.0.1:8080", "[::1]:8080", "[::1]"}) {
            assertTrue(CityServer.isAllowedHost(ok, 8080), ok);
        }
        for (String bad : new String[]{null, "", "evil.example", "evil.example:8080", "localhost:8081", "localhost.:8080",
                "127.0.0.2:8080", "localhost.evil.example:8080", "localhost:8080:8080", "0.0.0.0:8080"}) {
            assertFalse(CityServer.isAllowedHost(bad, 8080), String.valueOf(bad));
        }
    }

    /** A DNS-rebinding request is refused; the source endpoint does not follow a link swapped in after analysis. */
    @Test
    void refusesForeignHostsAndDoesNotFollowLinks(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.writeString(root.resolve("A.java"), "class A {}");
        Files.writeString(tmp.resolve("secret.txt"), "TOP SECRET");
        CityModel model = CityModel.build(new SourceAnalyzer(), root.toRealPath(), true, false, null);
        try (CityServer server = new CityServer(model, 0)) {
            server.start();
            int port = URI.create(server.url()).getPort();
            assertTrue(get(port, "/api/city", "evil.example:" + port).startsWith("HTTP/1.1 403"));
            assertTrue(get(port, "/", "evil.example:" + port).startsWith("HTTP/1.1 403"));
            assertTrue(get(port, "/api/source?id=0", null).startsWith("HTTP/1.1 403"));
            assertTrue(get(port, "/api/city", "localhost:" + port).startsWith("HTTP/1.1 200"));
            assertTrue(get(port, "/api/source?id=0", "127.0.0.1:" + port).contains("class A {}"));

            Files.delete(root.resolve("A.java"));
            Files.createSymbolicLink(root.resolve("A.java"), tmp.resolve("secret.txt"));
            String r = get(port, "/api/source?id=0", "localhost:" + port);
            assertFalse(r.contains("TOP SECRET"), r);
            assertTrue(r.contains("unable to read A.java"), r);
            assertFalse(r.contains(tmp.toString()), r);
        }
    }

    /** Analyses {@code root} without git or GitHub, as the server tests need no history. */
    private static CityModel model(Path root) throws Exception {
        return CityModel.build(new SourceAnalyzer(), root.toRealPath(), true, false, null);
    }

    /** The port of a started server, from its URL. */
    private static int port(CityServer server) {
        return URI.create(server.url()).getPort();
    }

    /**
     * The viewer's files are served with their content types and {@code no-store}; the page carries its
     * Content-Security-Policy with the inline scripts' hashes filled in. Paths that are not viewer files or endpoints,
     * including paths below an endpoint and paths that try to leave the resource directory, are 404s.
     */
    @Test
    void servesTheViewerFilesWithTheirTypesAndNothingElse(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("A.java"), "class A {}");
        try (CityServer server = new CityServer(model(tmp), 0)) {
            server.start();
            int port = port(server);
            String host = "localhost:" + port;
            for (String path : new String[]{"/", "/index.html"}) {
                String r = get(port, path, host);
                assertTrue(r.startsWith("HTTP/1.1 200"), r);
                assertEquals("text/html; charset=utf-8", header(r, "Content-Type"));
                assertEquals("no-store", header(r, "Cache-Control"));
                assertTrue(r.contains("<meta http-equiv=\"Content-Security-Policy\""), path);
                assertTrue(r.contains("'sha256-"), path);
                assertFalse(r.contains("{{inline-script-hashes}}"), path);
            }
            assertEquals("text/javascript; charset=utf-8", header(get(port, "/app.js", host), "Content-Type"));
            assertEquals("text/css; charset=utf-8", header(get(port, "/style.css", host), "Content-Type"));
            assertEquals("image/svg+xml", header(get(port, "/favicon.svg", host), "Content-Type"));
            String city = get(port, "/api/city", host);
            assertEquals("application/json; charset=utf-8", header(city, "Content-Type"));
            assertEquals("no-store", header(city, "Cache-Control"));
            assertTrue(body(city).startsWith("{") && body(city).contains("\"A\""), city);
            for (String path : new String[]{"/nope", "/web/app.js", "/../pom.xml", "/index.htm", "/api", "/api/",
                    "/api/city/x", "/api/source/x", "/api/events/x", "/api/cities"}) {
                String r = get(port, path, host);
                assertTrue(r.startsWith("HTTP/1.1 404"), path + ": " + r);
                assertEquals("text/plain", header(r, "Content-Type"), path);
                assertEquals("not found", body(r), path);
            }
        }
    }

    /**
     * {@code /api/source}: a missing or malformed id is a 400, a well-formed id that names no building a 404; neither
     * answer mentions a path. A valid id answers the file (relative), the type's line range and the code.
     */
    @Test
    void sourceAnswersBadAndUnknownIds(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("p"));
        Files.writeString(tmp.resolve("p/A.java"), "package p;\n\nclass A {\n}\n");
        try (CityServer server = new CityServer(model(tmp), 0)) {
            server.start();
            int port = port(server);
            String host = "localhost:" + port;
            for (String query : new String[]{"", "?", "?id=", "?id=abc", "?id=-1", "?id=+0", "?id=0x1", "?id=1.0",
                    "?x=1", "?id=99999999999999999999999"}) {
                String r = get(port, "/api/source" + query, host);
                assertTrue(r.startsWith("HTTP/1.1 400"), query + ": " + r);
                assertEquals("bad id", body(r), query);
            }
            for (String query : new String[]{"?id=1", "?id=999", "?id=4294967296"}) {
                String r = get(port, "/api/source" + query, host);
                assertTrue(r.startsWith("HTTP/1.1 404"), query + ": " + r);
                assertEquals("unknown type", body(r), query);
            }
            String r = get(port, "/api/source?x=1&id=0", host);
            assertTrue(r.startsWith("HTTP/1.1 200"), r);
            assertEquals("application/json; charset=utf-8", header(r, "Content-Type"));
            assertEquals("{\"file\":\"p/A.java\",\"line\":3,\"endLine\":4,\"code\":\"package p;\\n\\nclass A {\\n}\\n\"}",
                    body(r));
        }
    }

    /**
     * Files over 4 MiB are not sent (a placeholder comment is), a file of exactly 4 MiB is; a FIFO swapped in after the
     * analysis is reported as unreadable without blocking the request.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "needs mkfifo")
    void sourceCapsLargeFilesAndNeverBlocksOnAFifo(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("A.java");
        Files.writeString(a, "class A {}");
        try (CityServer server = new CityServer(model(tmp), 0)) {
            server.start();
            int port = port(server);
            String host = "localhost:" + port;
            try (RandomAccessFile f = new RandomAccessFile(a.toFile(), "rw")) {
                f.setLength(4L * 1024 * 1024 + 1);
            }
            assertTrue(get(port, "/api/source?id=0", host).contains("\"code\":\"// file too large to display\""));
            Files.write(a, "x".repeat(4 * 1024 * 1024).getBytes(StandardCharsets.US_ASCII));
            String r = get(port, "/api/source?id=0", host);
            assertTrue(r.contains("\"code\":\"xxxx"), () -> r.substring(0, Math.min(r.length(), 300)));
            assertFalse(r.contains("too large"));

            Files.delete(a);
            assertEquals(0, new ProcessBuilder("mkfifo", a.toString()).start().waitFor());
            String fifo = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> get(port, "/api/source?id=0", host));
            assertTrue(fifo.contains("\"code\":\"// unable to read A.java\""), fifo);
        }
    }

    /**
     * {@code /api/events}: a stream opens with the event-stream headers and a {@code retry} line; swapping in a new
     * model sends one {@code update} event with the new type count, after which {@code /api/city} serves the new
     * model. A foreign host gets no stream.
     */
    @Test
    void eventsAnnounceAModelSwap(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("A.java"), "class A {}");
        try (CityServer server = new CityServer(model(tmp), 0)) {
            server.start();
            int port = port(server);
            String host = "localhost:" + port;
            assertTrue(get(port, "/api/events", "evil.example:" + port).startsWith("HTTP/1.1 403"));
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                try (Socket s = new Socket("127.0.0.1", port)) {
                    s.getOutputStream().write(("GET /api/events HTTP/1.1\r\nHost: " + host + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    InputStream in = s.getInputStream();
                    String head = readUntil(in, "retry: 2000\n\n");
                    assertTrue(head.startsWith("HTTP/1.1 200"), head);
                    assertEquals("text/event-stream; charset=utf-8", header(head, "Content-Type"));
                    assertEquals("no-cache", header(head, "Cache-Control"));

                    Files.writeString(tmp.resolve("Brand.java"), "class Brand {}");
                    server.update(model(tmp));
                    readUntil(in, "event: update\ndata: {\"types\":2}\n\n");
                }
                return null;
            });
            assertTrue(get(port, "/api/city", host).contains("\"Brand\""));
        }
    }

    /**
     * When the preferred port is taken, the server binds one of the next ones (the Rust and D editions search the same
     * 20 ports) and reports the port it got in its URL; port 0 lets the system choose.
     */
    @Test
    void fallsBackToTheNextFreePort(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("A.java"), "class A {}");
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int busy = taken.getLocalPort();
            try (CityServer server = new CityServer(model(tmp), busy)) {
                server.start();
                int port = port(server);
                assertTrue(server.url().startsWith("http://localhost:"), server.url());
                assertTrue(port != busy, "bound the busy port");
                if (busy <= 65535 - 19) {
                    assertTrue(port > busy && port <= busy + 19, busy + " -> " + port);
                }
                assertTrue(get(port, "/api/city", "localhost:" + port).startsWith("HTTP/1.1 200"));
            }
        }
    }

    /** Reads from {@code in} until what was read ends with {@code marker}; fails at the end of the stream. */
    private static String readUntil(InputStream in, String marker) throws Exception {
        StringBuilder b = new StringBuilder();
        while (!b.toString().endsWith(marker)) {
            int c = in.read();
            assertTrue(c >= 0, "stream ended before " + marker + ": " + b);
            b.append((char) c);
        }
        return b.toString();
    }

    /** The value of header {@code name} in a raw response (names compared case-insensitively), or null. */
    static String header(String response, String name) {
        for (String line : response.split("\r\n")) {
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** The body of a raw response with a Content-Length (everything after the blank line). */
    static String body(String response) {
        int at = response.indexOf("\r\n\r\n");
        return at < 0 ? "" : response.substring(at + 4);
    }

    /** Sends a raw GET (so the Host header is fully controlled; null leaves it out) and returns the whole response. */
    static String get(int port, String path, String host) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(20_000); // a stuck server fails the test instead of hanging the run
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n" + (host == null ? "" : "Host: " + host + "\r\n")
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
