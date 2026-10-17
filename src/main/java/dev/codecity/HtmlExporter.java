package dev.codecity;

import dev.codecity.server.CityServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the viewer and the city data into one self-contained HTML file that can be shared or hosted statically. */
public final class HtmlExporter {

    private HtmlExporter() {
    }

    public static void export(CityModel model, Path target) throws IOException {
        String html = text("index.html");
        String css = text("style.css");
        String js = text("app.js");
        String favicon = text("favicon.svg");

        html = replaceOnce(html, "<link rel=\"stylesheet\" href=\"style.css\">", "<style>\n" + css + "\n</style>");
        html = replaceOnce(html, "<link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">",
                "<link rel=\"icon\" href=\"data:image/svg+xml," + favicon.replace("\"", "'")
                        .replace("#", "%23").replace("\n", " ") + "\">");
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

    private static String replaceOnce(String text, String marker, String replacement) {
        int i = text.indexOf(marker);
        if (i < 0) {
            throw new IllegalStateException("export marker not found: " + marker);
        }
        return text.substring(0, i) + replacement + text.substring(i + marker.length());
    }
}
