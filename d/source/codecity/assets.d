/// The web viewer, compiled in from the Java project's resources so every edition serves the identical viewer.
module codecity.assets;

enum indexHtml = import("index.html");
enum appJs = import("app.js");
enum walkJs = import("walk.js");
enum replayJs = import("replay.js");
enum styleCss = import("style.css");
enum faviconSvg = import("favicon.svg");

/// Where `index.html`'s Content-Security-Policy lists the hashes of the page's inline scripts.
private enum scriptHashes = "{{inline-script-hashes}}";

/// The served `index.html`, its policy filled in (`withScriptHashes`) once at start-up.
private __gshared string servedIndexHtml;

shared static this()
{
    servedIndexHtml = withScriptHashes(indexHtml);
}

/// Fills in the page's Content-Security-Policy: replaces the placeholder with `'sha256-…'` for every inline script of
/// `html` (an import map or a module without `src`: the import map of the served page; the import map and app.js of
/// an exported page), so the browser runs exactly those. The city data block is not a script. Used for the served
/// page and by `codecity.htmlexport`; the Java and Rust editions do the same. Throws if the placeholder is missing,
/// which would mean index.html and this code no longer match.
string withScriptHashes(string html)
{
    import std.algorithm : sort;
    import std.array : join;
    import std.base64 : Base64;
    import std.digest.sha : sha256Of;
    import std.string : indexOf;
    import std.typecons : Tuple, tuple;

    Tuple!(size_t, string)[] found;
    foreach (open; [`<script type="importmap">`, `<script type="module">`])
    {
        size_t from = 0;
        for (ptrdiff_t i; (i = html.indexOf(open, from)) >= 0;)
        {
            const start = i + open.length;
            auto end = html.indexOf("</script>", start);
            if (end < 0) end = html.length;
            found ~= tuple(cast(size_t) i, "'sha256-" ~ Base64.encode(sha256Of(html[start .. end])).idup ~ "'");
            from = end;
        }
    }
    // In page order, as the other editions list them.
    found.sort();
    string[] list;
    foreach (f; found) list ~= f[1];
    const at = html.indexOf(scriptHashes);
    if (at < 0) throw new Exception("index.html lacks " ~ scriptHashes);
    return html[0 .. at] ~ list.join(" ") ~ html[at + scriptHashes.length .. $];
}

/// Content type and body for a request path; false when there is no such asset.
bool asset(string path, out string contentType, out string body_)
{
    switch (path)
    {
    case "/", "/index.html": contentType = "text/html; charset=utf-8"; body_ = servedIndexHtml; return true;
    case "/app.js": contentType = "text/javascript; charset=utf-8"; body_ = appJs; return true;
    case "/walk.js": contentType = "text/javascript; charset=utf-8"; body_ = walkJs; return true;
    case "/replay.js": contentType = "text/javascript; charset=utf-8"; body_ = replayJs; return true;
    case "/style.css": contentType = "text/css; charset=utf-8"; body_ = styleCss; return true;
    case "/favicon.svg": contentType = "image/svg+xml"; body_ = faviconSvg; return true;
    default: return false;
    }
}
