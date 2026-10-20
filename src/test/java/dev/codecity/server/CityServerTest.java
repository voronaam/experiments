package dev.codecity.server;

import dev.codecity.CityModel;
import dev.codecity.analysis.SourceAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's request checks; the Rust and D editions have the same tests. */
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

    /** Sends a raw GET (so the Host header is fully controlled; null leaves it out) and returns the whole response. */
    static String get(int port, String path, String host) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n" + (host == null ? "" : "Host: " + host + "\r\n")
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
