//! The web viewer, embedded from the Java project's resources so both versions serve the identical viewer.
//!
//! The files are compiled into the binary with `include_str!`, so the executable needs nothing at run time. They are
//! served by [`crate::server`] and inlined into one file by [`crate::export`].

pub const INDEX_HTML: &str = include_str!("../../src/main/resources/web/index.html");
pub const APP_JS: &str = include_str!("../../src/main/resources/web/app.js");
pub const WALK_JS: &str = include_str!("../../src/main/resources/web/walk.js");
pub const REPLAY_JS: &str = include_str!("../../src/main/resources/web/replay.js");
pub const STYLE_CSS: &str = include_str!("../../src/main/resources/web/style.css");
pub const FAVICON_SVG: &str = include_str!("../../src/main/resources/web/favicon.svg");

/// Where `index.html`'s Content-Security-Policy lists the hashes of the page's inline scripts.
const SCRIPT_HASHES: &str = "{{inline-script-hashes}}";

/// The served `index.html`, its policy filled in ([`with_script_hashes`]) on first use; the server calls this when it
/// starts.
pub fn served_index() -> &'static str {
    static INDEX: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    INDEX.get_or_init(|| with_script_hashes(INDEX_HTML))
}

/// Fills in the page's Content-Security-Policy: replaces the placeholder with `'sha256-…'` for every inline script of
/// `html` (an import map or a module without `src`: the import map of the served page; the import map and app.js of
/// an exported page), so the browser runs exactly those. The city data block is not a script. Used for the served
/// page and by [`crate::export`]; the Java and D editions do the same. Panics if the placeholder is missing, which
/// would mean index.html and this code no longer match.
pub fn with_script_hashes(html: &str) -> String {
    let mut hashes = Vec::new();
    for open in [r#"<script type="importmap">"#, r#"<script type="module">"#] {
        let mut rest = html;
        while let Some(i) = rest.find(open) {
            let body = &rest[i + open.len()..];
            let end = body.find("</script>").unwrap_or(body.len());
            let digest = crate::sha256::digest(body[..end].as_bytes());
            hashes.push((i + (html.len() - rest.len()), format!("'sha256-{}'", base64(&digest))));
            rest = &body[end..];
        }
    }
    // In page order, as the other editions list them.
    hashes.sort();
    let list: Vec<String> = hashes.into_iter().map(|(_, h)| h).collect();
    let at = html.find(SCRIPT_HASHES).unwrap_or_else(|| panic!("index.html lacks {SCRIPT_HASHES}"));
    format!("{}{}{}", &html[..at], list.join(" "), &html[at + SCRIPT_HASHES.len()..])
}

/// Standard base64 (RFC 4648, with padding).
pub fn base64(data: &[u8]) -> String {
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

/// (content type, body) for a request path, or None for anything that is not one of the viewer's files.
pub fn get(path: &str) -> Option<(&'static str, &'static str)> {
    Some(match path {
        "/" | "/index.html" => ("text/html; charset=utf-8", served_index()),
        "/app.js" => ("text/javascript; charset=utf-8", APP_JS),
        "/walk.js" => ("text/javascript; charset=utf-8", WALK_JS),
        "/replay.js" => ("text/javascript; charset=utf-8", REPLAY_JS),
        "/style.css" => ("text/css; charset=utf-8", STYLE_CSS),
        "/favicon.svg" => ("image/svg+xml", FAVICON_SVG),
        _ => return None,
    })
}
