//! Writes the viewer and the city data into one self-contained HTML file.

use crate::assets;
use crate::city::CityModel;
use std::path::Path;

pub fn export(model: &CityModel, target: &Path) -> std::io::Result<()> {
    let mut html = assets::INDEX_HTML.to_string();
    replace_once(&mut html, r#"<link rel="stylesheet" href="style.css">"#, &format!("<style>\n{}\n</style>", assets::STYLE_CSS));
    let favicon = assets::FAVICON_SVG.replace('"', "'").replace('#', "%23").replace('\n', " ");
    replace_once(&mut html, r#"<link rel="icon" href="favicon.svg" type="image/svg+xml">"#, &format!(r#"<link rel="icon" href="data:image/svg+xml,{favicon}">"#));
    // the JSON writer escapes '<', so the data cannot terminate the script element early
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

fn replace_once(text: &mut String, marker: &str, replacement: &str) {
    let i = text.find(marker).unwrap_or_else(|| panic!("export marker not found: {marker}"));
    text.replace_range(i..i + marker.len(), replacement);
}
