/// Writes the viewer and the city data into one self-contained HTML file.
///
/// The exported page is the served `index.html` with its local references inlined: the stylesheet, the favicon (as
/// a data URI) and the viewer script. `app.js` imports `./walk.js`, which an inline module cannot load from disk, so
/// the page's import map gets an entry mapping `./walk.js` to a base64 `data:` URL of that module. The city JSON goes
/// into a `<script id="city-data" type="application/json">` element, which `app.js` reads instead of fetching `/api/city`. Third-party references in the page (fonts and
/// libraries from CDNs) stay as links, so the file still needs network access for those. Without a server there is
/// no `/api/source` and no live update.
module codecity.htmlexport;

import codecity.assets;
import codecity.city : CityModel;
import std.array : replace;
import std.base64 : Base64;
import std.file : mkdirRecurse, write;
import std.path : dirName;
import std.string : indexOf;

/// Writes the page to `target`, creating its directory if needed. Throws if the page template no longer contains
/// one of the markers being replaced (the template and this code must change together).
void exportHtml(CityModel model, string target)
{
    string html = indexHtml;
    replaceOnce(html, `<link rel="stylesheet" href="style.css">`, "<style>\n" ~ styleCss ~ "\n</style>");
    // In a data URI, `#` would start the fragment and must be escaped as %23; double quotes would end the href
    // attribute, so they become single quotes; newlines are flattened.
    const favicon = faviconSvg.replace(`"`, "'").replace("#", "%23").replace("\n", " ");
    replaceOnce(html, `<link rel="icon" href="favicon.svg" type="image/svg+xml">`, `<link rel="icon" href="data:image/svg+xml,` ~ favicon ~ `">`);
    // "./walk.js" in the import map resolves against the page URL, exactly like app.js's import from the inline
    // module, so the import is served from the data: URL. Base64 needs no escaping inside the JSON string.
    replaceOnce(html, `"imports": {`, "\"imports\": {\n        \"./walk.js\": \"data:text/javascript;base64,"
        ~ Base64.encode(cast(const(ubyte)[]) walkJs).idup ~ "\",");
    // the JSON writer escapes '<', so the data cannot terminate the script element early
    replaceOnce(html, `<script type="module" src="app.js"></script>`,
        `<script id="city-data" type="application/json">` ~ model.json ~ "</script>\n<script type=\"module\">\n" ~ appJs ~ "\n</script>");
    const dir = target.dirName;
    if (dir.length && dir != ".") mkdirRecurse(dir);
    write(target, html);
}

/// Replaces the first occurrence of `marker` in `text`.
private void replaceOnce(ref string text, string marker, string replacement)
{
    const i = text.indexOf(marker);
    if (i < 0) throw new Exception("export marker not found: " ~ marker);
    text = text[0 .. i] ~ replacement ~ text[i + marker.length .. $];
}
