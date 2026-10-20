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
            assets::base64(assets::WALK_JS.as_bytes()),
            assets::base64(assets::REPLAY_JS.as_bytes())
        ),
    );
    // The JSON writer escapes '<', so the data cannot terminate the script element early. The script stays a module,
    // as in index.html.
    replace_once(
        &mut html,
        r#"<script type="module" src="app.js"></script>"#,
        &format!("<script id=\"city-data\" type=\"application/json\">{}</script>\n<script type=\"module\">\n{}\n</script>", model.json(), assets::APP_JS),
    );
    // Last, once every inline script is in place: their hashes go into the page's Content-Security-Policy.
    let html = assets::with_script_hashes(&html);
    if let Some(parent) = target.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(target, html)
}

/// Replaces the first occurrence of `marker`. Panics if it is missing: that means index.html changed and this
/// exporter no longer matches it, which is a bug in the build, not a user error.
fn replace_once(text: &mut String, marker: &str, replacement: &str) {
    let i = text.find(marker).unwrap_or_else(|| panic!("export marker not found: {marker}"));
    text.replace_range(i..i + marker.len(), replacement);
}

/// The viewer's Content-Security-Policy allows inline scripts only by hash. Each emitted page (the served one, as
/// [`assets::get`] hands it to the server, and an exported one) must list exactly the hashes of its own inline
/// scripts, so editing index.html, app.js, walk.js or replay.js cannot break it. The Java and D editions have the same
/// test.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::analysis::SourceAnalyzer;

    /// `'sha256-…'` of every inline script (import map or module without `src`) of `page`, in page order, found
    /// independently of [`assets::with_script_hashes`].
    fn script_hashes(page: &str) -> Vec<String> {
        let re = regex::Regex::new(r#"(?s)<script type="(?:importmap|module)">(.*?)</script>"#).unwrap();
        re.captures_iter(page)
            .map(|c| format!("'sha256-{}'", assets::base64(&crate::sha256::digest(c[1].as_bytes()))))
            .collect()
    }

    fn csp(page: &str) -> String {
        let re = regex::Regex::new(r#"<meta http-equiv="Content-Security-Policy" content="([^"]*)">"#).unwrap();
        re.captures(page).expect("no Content-Security-Policy meta tag")[1].to_string()
    }

    #[test]
    fn csp_lists_every_inline_script_of_the_served_and_the_exported_page() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::fs::write(root.join("A.java"), "class A { void f() {} }\n").unwrap();
        let model = CityModel::build(&SourceAnalyzer::new(), &root, true, false, None, None);
        let out = root.join("out/city.html");
        export(&model, &out).unwrap();
        let exported = std::fs::read_to_string(&out).unwrap();
        let served = assets::get("/").unwrap().1;

        assert_eq!(script_hashes(served).len(), 1);
        assert_eq!(script_hashes(&exported).len(), 2);
        let hash = regex::Regex::new(r" 'sha256-[A-Za-z0-9+/=]+'").unwrap();
        for page in [served, exported.as_str()] {
            assert!(!page.contains("{{inline-script-hashes}}"));
            let policy = csp(page);
            let listed: Vec<String> = hash.find_iter(&policy).map(|m| m.as_str().trim().to_string()).collect();
            assert_eq!(listed, script_hashes(page), "the policy lists exactly the page's inline scripts");
        }
        assert_eq!(hash.replace_all(&csp(served), ""), hash.replace_all(&csp(&exported), ""));
    }

    /// Cross-check of the in-house SHA-256 as the policy uses it: the expected value was computed with Python's
    /// hashlib (`base64(sha256(script))`), the same rule browsers apply.
    #[test]
    fn script_hash_matches_an_independent_implementation() {
        let html = "<meta content=\"{{inline-script-hashes}}\"><script type=\"importmap\">\n    {\"imports\": {\"three\": \"x\"}}\n  </script>";
        assert!(assets::with_script_hashes(html).starts_with("<meta content=\"'sha256-tWZD2WKW2jTO7eRqEuxxZFb5iSTDW1CRqMCgTsbKMJE='\">"));
    }
}
