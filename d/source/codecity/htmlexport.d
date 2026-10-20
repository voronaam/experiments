/// Writes the viewer and the city data into one self-contained HTML file.
///
/// The exported page is the served `index.html` with its local references inlined: the stylesheet, the favicon (as
/// a data URI) and the viewer script. `app.js` imports `./walk.js` and `./replay.js`, which an inline module cannot
/// load from disk, so the page's import map gets an entry for each, mapping it to a base64 `data:` URL of that module. The city JSON goes
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
    // module, so the import is served from the data: URL (likewise "./replay.js"). Base64 needs no escaping inside the
    // JSON string.
    replaceOnce(html, `"imports": {`, "\"imports\": {\n        \"./walk.js\": \"data:text/javascript;base64,"
        ~ Base64.encode(cast(const(ubyte)[]) walkJs).idup ~ "\","
        ~ "\n        \"./replay.js\": \"data:text/javascript;base64,"
        ~ Base64.encode(cast(const(ubyte)[]) replayJs).idup ~ "\",");
    // the JSON writer escapes '<', so the data cannot terminate the script element early
    replaceOnce(html, `<script type="module" src="app.js"></script>`,
        `<script id="city-data" type="application/json">` ~ model.json ~ "</script>\n<script type=\"module\">\n" ~ appJs ~ "\n</script>");
    // Last, once every inline script is in place: their hashes go into the page's Content-Security-Policy.
    html = withScriptHashes(html);
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

/// The viewer's Content-Security-Policy allows inline scripts only by hash. Each emitted page (the served one, as
/// `asset` hands it to the server, and an exported one) must list exactly the hashes of its own inline scripts, so
/// editing index.html, app.js, walk.js or replay.js cannot break it. The Java and Rust editions have the same test.
unittest
{
    import codecity.analysis : SourceAnalyzer;
    import codecity.github : GitHubInfo;
    import std.base64 : Base64;
    import std.digest.sha : sha256Of;
    import std.file : mkdirRecurse, readText, rmdirRecurse, tempDir, write;
    import std.path : buildPath;
    import std.regex : ctRegex, matchAll, matchFirst, replaceAll;
    import std.string : strip;
    import std.uuid : randomUUID;

    // `'sha256-…'` of every inline script of `page`, in page order, found independently of `withScriptHashes`.
    string[] scriptHashes(string page)
    {
        string[] r;
        foreach (c; page.matchAll(ctRegex!(`<script type="(?:importmap|module)">([\s\S]*?)</script>`)))
            r ~= "'sha256-" ~ Base64.encode(sha256Of(c[1])).idup ~ "'";
        return r;
    }
    string csp(string page)
    {
        auto m = page.matchFirst(ctRegex!(`<meta http-equiv="Content-Security-Policy" content="([^"]*)">`));
        assert(!m.empty, "no Content-Security-Policy meta tag");
        return m[1];
    }

    const dir = buildPath(tempDir, "codecity-export-test-" ~ randomUUID.toString);
    mkdirRecurse(dir);
    scope (exit) rmdirRecurse(dir);
    write(buildPath(dir, "A.java"), "class A { void f() {} }\n");
    auto model = CityModel.build(new SourceAnalyzer, dir, true, false, false, GitHubInfo.init);
    const out_ = buildPath(dir, "out", "city.html");
    exportHtml(model, out_);
    const exported = readText(out_);
    string ct, served;
    assert(asset("/", ct, served));

    assert(scriptHashes(served).length == 1);
    assert(scriptHashes(exported).length == 2);
    enum hash = ctRegex!(` 'sha256-[A-Za-z0-9+/=]+'`);
    foreach (page; [served, exported])
    {
        assert(page.indexOf("{{inline-script-hashes}}") < 0);
        string[] listed;
        foreach (m; csp(page).matchAll(hash)) listed ~= m.hit.strip;
        assert(listed == scriptHashes(page), "the policy lists exactly the page's inline scripts");
    }
    assert(csp(served).replaceAll(hash, "") == csp(exported).replaceAll(hash, ""));
}
