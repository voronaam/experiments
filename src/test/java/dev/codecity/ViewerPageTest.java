package dev.codecity;

import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.server.CityServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The browser viewer's Content-Security-Policy (a meta tag in {@code index.html}) allows inline scripts only by hash.
 * {@code index.html} has a placeholder for them, filled in by the server for the page it serves (one inline script: the
 * import map) and by the exporter for the page it writes (two: the import map with walk.js and replay.js spliced in,
 * and app.js inline). This checks that each emitted policy lists exactly the hashes of that page's inline scripts, so
 * editing any of those files cannot break the page. The JSON data block is not a script and needs no hash. The Rust
 * and D editions have the same test.
 */
class ViewerPageTest {

    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?: type=\"(?:importmap|module)\")>(.*?)</script>",
            Pattern.DOTALL);
    private static final Pattern HASH = Pattern.compile(" 'sha256-[A-Za-z0-9+/=]+'");

    @Test
    void cspListsEveryInlineScriptOfTheServedAndTheExportedPage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("A.java"), "class A { void f() {} }\n");
        CityModel model = CityModel.build(new SourceAnalyzer(), dir, true, false, null);
        String served;
        try (CityServer server = new CityServer(model, 0)) {
            server.start();
            int port = URI.create(server.url()).getPort();
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(), HttpResponse.BodyHandlers.ofString());
            served = r.body();
        }
        Path out = dir.resolve("out/city.html");
        HtmlExporter.export(model, out);
        String exported = Files.readString(out);

        assertEquals(1, scriptHashes(served).size());
        assertEquals(2, scriptHashes(exported).size());
        for (String page : List.of(served, exported)) {
            assertFalse(page.contains("{{inline-script-hashes}}"));
            List<String> listed = new ArrayList<>();
            Matcher m = HASH.matcher(csp(page));
            while (m.find()) {
                listed.add(m.group().strip());
            }
            assertEquals(scriptHashes(page), listed, "the policy lists exactly the page's inline scripts");
        }
        assertEquals(HASH.matcher(csp(served)).replaceAll(""), HASH.matcher(csp(exported)).replaceAll(""),
                "apart from the hashes, the exported page keeps the served page's policy");
    }

    /** {@code 'sha256-…'} of every inline script in {@code page}, in page order. */
    private static List<String> scriptHashes(String page) throws Exception {
        List<String> hashes = new ArrayList<>();
        Matcher m = INLINE_SCRIPT.matcher(page);
        while (m.find()) {
            hashes.add("'sha256-" + Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(m.group(1).getBytes(StandardCharsets.UTF_8))) + "'");
        }
        return hashes;
    }

    @Test
    void policyForbidsInlineHandlersAndEval() throws Exception {
        String policy = csp(new String(CityServer.resource("index.html"), StandardCharsets.UTF_8));
        assertTrue(policy.startsWith("default-src 'none'; "), policy);
        assertFalse(policy.contains("unsafe-eval"), policy);
        String scripts = policy.substring(policy.indexOf("script-src"), policy.indexOf(';', policy.indexOf("script-src")));
        assertFalse(scripts.contains("unsafe-inline"), scripts);
    }

    @Test
    void cdnFilesCarryIntegrityHashes() throws Exception {
        String html = new String(CityServer.resource("index.html"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<(?:script|link)[^>]*(?:src|href)=\"https://cdnjs[^>]*>").matcher(html);
        int n = 0;
        while (m.find()) {
            assertTrue(m.group().contains("integrity=\"sha384-") && m.group().contains("crossorigin=\"anonymous\""),
                    m.group());
            n++;
        }
        assertEquals(2, n);
        assertTrue(html.contains("\"integrity\": {"), "the import map pins the three.js modules");
    }

    private static String csp(String html) {
        Matcher m = Pattern.compile("<meta http-equiv=\"Content-Security-Policy\" content=\"([^\"]*)\">").matcher(html);
        assertTrue(m.find(), "no Content-Security-Policy meta tag");
        return m.group(1);
    }
}
