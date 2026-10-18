//! Writes the viewer and the city data into one self-contained HTML file.
//!
//! `--export` takes the viewer's `index.html` and replaces its three external references (stylesheet, favicon,
//! script) with inline copies, plus the city JSON in a `<script id="city-data">` element. The viewer's `app.js` looks
//! for that element and uses the embedded data instead of fetching `api/city`, so the file works from disk with no
//! server. `app.js` imports `./walk.js` and `./replay.js`, which an inline module cannot load from disk, so the page's
//! import map also gets an entry for each, mapping it to a base64 `data:` URL of that module.

use crate::assets;
use crate::city::CityModel;
use std::path::Path;

/// Writes the self-contained page to `target`, creating its parent directories.
pub fn export(model: &CityModel, target: &Path) -> std::io::Result<()> {
    let mut html = assets::INDEX_HTML.to_string();
    replace_once(&mut html, r#"<link rel="stylesheet" href="style.css">"#, &format!("<style>\n{}\n</style>", assets::STYLE_CSS));
    // Inline the SVG as a data: URL. The attribute is double-quoted, so the SVG's own double quotes become single
    // quotes; '#' would start the URL's fragment, so it is percent-encoded; newlines are flattened to spaces.
    let favicon = assets::FAVICON_SVG.replace('"', "'").replace('#', "%23").replace('\n', " ");
    replace_once(&mut html, r#"<link rel="icon" href="favicon.svg" type="image/svg+xml">"#, &format!(r#"<link rel="icon" href="data:image/svg+xml,{favicon}">"#));
    // "./walk.js" in the import map resolves against the page URL, exactly like app.js's import from the inline
    // module, so the import is served from the data: URL (likewise "./replay.js"). Base64 needs no escaping inside the
    // JSON string.
    replace_once(
        &mut html,
        r#""imports": {"#,
        &format!(
            "\"imports\": {{\n        \"./walk.js\": \"data:text/javascript;base64,{}\",\n        \"./replay.js\": \"data:text/javascript;base64,{}\",",
            base64(assets::WALK_JS.as_bytes()),
            base64(assets::REPLAY_JS.as_bytes())
        ),
    );
    // The JSON writer escapes '<', so the data cannot terminate the script element early. The script stays a module,
    // as in index.html.
    replace_once(
        &mut html,
        r#"<script type="module" src="app.js"></script>"#,
        &format!("<script id=\"city-data\" type=\"application/json\">{}</script>\n<script type=\"module\">\n{}\n</script>", model.json(), assets::APP_JS),
    );
    if let Some(parent) = target.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(target, html)
}

/// Standard base64 (RFC 4648, with padding).
fn base64(data: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        // Three bytes make 24 bits, emitted as four 6-bit digits; a short final chunk is padded with '='.
        let n = (chunk[0] as u32) << 16 | (*chunk.get(1).unwrap_or(&0) as u32) << 8 | *chunk.get(2).unwrap_or(&0) as u32;
        for k in 0..4 {
            if k <= chunk.len() {
                out.push(ALPHABET[(n >> (18 - 6 * k) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// Replaces the first occurrence of `marker`. Panics if it is missing: that means index.html changed and this
/// exporter no longer matches it, which is a bug in the build, not a user error.
fn replace_once(text: &mut String, marker: &str, replacement: &str) {
    let i = text.find(marker).unwrap_or_else(|| panic!("export marker not found: {marker}"));
    text.replace_range(i..i + marker.len(), replacement);
}
