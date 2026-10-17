/// Writes the viewer and the city data into one self-contained HTML file.
module codecity.htmlexport;

import codecity.assets;
import codecity.city : CityModel;
import std.array : replace;
import std.file : mkdirRecurse, write;
import std.path : dirName;
import std.string : indexOf;

void exportHtml(CityModel model, string target)
{
    string html = indexHtml;
    replaceOnce(html, `<link rel="stylesheet" href="style.css">`, "<style>\n" ~ styleCss ~ "\n</style>");
    const favicon = faviconSvg.replace(`"`, "'").replace("#", "%23").replace("\n", " ");
    replaceOnce(html, `<link rel="icon" href="favicon.svg" type="image/svg+xml">`, `<link rel="icon" href="data:image/svg+xml,` ~ favicon ~ `">`);
    // the JSON writer escapes '<', so the data cannot terminate the script element early
    replaceOnce(html, `<script type="module" src="app.js"></script>`,
        `<script id="city-data" type="application/json">` ~ model.json ~ "</script>\n<script type=\"module\">\n" ~ appJs ~ "\n</script>");
    const dir = target.dirName;
    if (dir.length && dir != ".") mkdirRecurse(dir);
    write(target, html);
}

private void replaceOnce(ref string text, string marker, string replacement)
{
    const i = text.indexOf(marker);
    if (i < 0) throw new Exception("export marker not found: " ~ marker);
    text = text[0 .. i] ~ replacement ~ text[i + marker.length .. $];
}
