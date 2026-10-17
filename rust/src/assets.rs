//! The web viewer, embedded from the Java project's resources so both versions serve the identical viewer.

pub const INDEX_HTML: &str = include_str!("../../src/main/resources/web/index.html");
pub const APP_JS: &str = include_str!("../../src/main/resources/web/app.js");
pub const STYLE_CSS: &str = include_str!("../../src/main/resources/web/style.css");
pub const FAVICON_SVG: &str = include_str!("../../src/main/resources/web/favicon.svg");

/// (content type, body) for a request path.
pub fn get(path: &str) -> Option<(&'static str, &'static str)> {
    Some(match path {
        "/" | "/index.html" => ("text/html; charset=utf-8", INDEX_HTML),
        "/app.js" => ("text/javascript; charset=utf-8", APP_JS),
        "/style.css" => ("text/css; charset=utf-8", STYLE_CSS),
        "/favicon.svg" => ("image/svg+xml", FAVICON_SVG),
        _ => return None,
    })
}
