package dev.codecity;

import dev.codecity.server.CityServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Writes the viewer and the city data into one self-contained HTML file that can be shared or hosted statically.
 *
 * <p>The exporter takes the same {@code web/} resources the {@link CityServer} serves and splices them together by
 * replacing four exact markers in {@code index.html}: the stylesheet link, the favicon link, the module script tag and
 * the start of the import map. {@code app.js} imports {@code ./walk.js} and {@code ./replay.js}; an inline module
 * cannot load a sibling file, so the import map gets an entry for each that maps it to a base64 {@code data:} URL of
 * that module. The
 * city JSON goes into a {@code <script id="city-data" type="application/json">} element; the viewer looks for that
 * element first and only falls back to fetching {@code api/city} (and listening for live updates) when it is
 * missing. If {@code index.html} is ever edited so that a marker no longer matches character for character, the export
 * fails loudly instead of producing a page that tries to load files that are not there.
 */
public final class HtmlExporter {

    private HtmlExporter() {
    }

    /** Writes the page to {@code target}, creating missing parent directories and overwriting an existing file. */
    public static void export(CityModel model, Path target) throws IOException {
        String html = text("index.html");
        String css = text("style.css");
        String js = text("app.js");
        String walkJs = text("walk.js");
        String replayJs = text("replay.js");
        String favicon = text("favicon.svg");

        html = replaceOnce(html, "<link rel=\"stylesheet\" href=\"style.css\">", "<style>\n" + css + "\n</style>");
        // The SVG becomes a data: URI. Double quotes would end the href attribute, '#' would start a URL fragment and
        // newlines are not allowed in a URL, so those three are rewritten; the rest of the SVG is left unescaped.
        html = replaceOnce(html, "<link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">",
                "<link rel=\"icon\" href=\"data:image/svg+xml," + favicon.replace("\"", "'")
                        .replace("#", "%23").replace("\n", " ") + "\">");
        // "./walk.js" in the import map resolves against the page URL, exactly like app.js's import from the inline
        // module, so the import is served from the data: URL (likewise "./replay.js"). Base64 needs no escaping inside
        // the JSON string.
        html = replaceOnce(html, "\"imports\": {", "\"imports\": {\n        \"./walk.js\": \"data:text/javascript;base64,"
                + Base64.getEncoder().encodeToString(walkJs.getBytes(StandardCharsets.UTF_8)) + "\","
                + "\n        \"./replay.js\": \"data:text/javascript;base64,"
                + Base64.getEncoder().encodeToString(replayJs.getBytes(StandardCharsets.UTF_8)) + "\",");
        // JsonWriter escapes '<', so the data cannot terminate the script element early.
        html = replaceOnce(html, "<script type=\"module\" src=\"app.js\"></script>",
                "<script id=\"city-data\" type=\"application/json\">" + model.json() + "</script>\n"
                        + "<script type=\"module\">\n" + js + "\n</script>");

        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, html, StandardCharsets.UTF_8);
    }

    private static String text(String name) throws IOException {
        return new String(CityServer.resource(name), StandardCharsets.UTF_8);
    }

    /** Replaces the first occurrence of {@code marker}; throws if there is none (see the class comment). */
    private static String replaceOnce(String text, String marker, String replacement) {
        int i = text.indexOf(marker);
        if (i < 0) {
            throw new IllegalStateException("export marker not found: " + marker);
        }
        return text.substring(0, i) + replacement + text.substring(i + marker.length());
    }
}
