//! Tests of the source highlighter (`source.rs`).
//!
//! The samples in `testdata/highlight/` (one per language) must give exactly the tokens in their `.tokens.json`
//! files, which the Java (`SourceViewerTest`) and D (`source.d`) editions check too, so the three source windows colour
//! code identically. The format is a JSON array with one entry per line, each an array of `[style, text]` pairs.
//! The hostile inputs check the highlighter's promises on anything: it terminates quickly, gives exactly one line per
//! line of input, and loses no character but `\r`.

use super::source::{highlight, Line, Style};
use crate::model::Language;
use serde_json::{json, Value};
use std::path::Path;

/// The shared name of a token class.
fn name(s: Style) -> &'static str {
    match s {
        Style::Plain => "plain",
        Style::Keyword => "keyword",
        Style::Type => "type",
        Style::Constant => "constant",
        Style::Str => "string",
        Style::Comment => "comment",
        Style::Number => "number",
        Style::Annotation => "annotation",
    }
}

/// The highlighted lines in the shared JSON form.
fn tokens(lines: &[Line]) -> Value {
    Value::Array(lines.iter().map(|l| Value::Array(l.iter().map(|(t, s)| json!([name(*s), t])).collect())).collect())
}

/// Highlights `testdata/highlight/<file>` and compares with `<file>.tokens.json`; the actual tokens are kept in
/// `target/highlight-actual/` for inspection.
fn check(file: &str, language: Language) {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata/highlight");
    let code = std::fs::read_to_string(dir.join(file)).unwrap();
    let actual = tokens(&highlight(&code, language));
    let out = Path::new(env!("CARGO_MANIFEST_DIR")).join("target/highlight-actual");
    std::fs::create_dir_all(&out).unwrap();
    let pretty: Vec<String> = actual.as_array().unwrap().iter().map(|l| l.to_string()).collect();
    std::fs::write(out.join(format!("{file}.tokens.json")), format!("[\n{}\n]\n", pretty.join(",\n"))).unwrap();
    let expected: Value = serde_json::from_str(&std::fs::read_to_string(dir.join(format!("{file}.tokens.json"))).unwrap()).unwrap();
    let (e, a) = (expected.as_array().unwrap(), actual.as_array().unwrap());
    assert_eq!(e.len(), a.len(), "{file}: line count");
    for (n, (x, y)) in e.iter().zip(a).enumerate() {
        assert_eq!(x, y, "{file}: line {}", n + 1);
    }
}

#[test]
fn java_sample_matches_the_shared_tokens() {
    check("Sample.java", Language::Java);
}

#[test]
fn typescript_sample_matches_the_shared_tokens() {
    check("sample.ts", Language::TypeScript);
}

#[test]
fn rust_sample_matches_the_shared_tokens() {
    check("sample.rs", Language::Rust);
}

/// The highlighter's promises for `code`: one line per input line, and every character but `\r` kept in order.
fn assert_lossless(code: &str, language: Language) {
    let lines = highlight(code, language);
    let expected: Vec<String> = code.replace('\r', "").split('\n').map(String::from).collect();
    assert_eq!(lines.len(), expected.len(), "line count of {:?}", &code[..code.len().min(40)]);
    for (line, text) in lines.iter().zip(&expected) {
        assert_eq!(&line.iter().map(|(s, _)| s.as_str()).collect::<String>(), text);
        assert!(line.iter().all(|(s, _)| !s.is_empty()), "empty token");
    }
}

#[test]
fn hostile_input_keeps_every_character_and_line() {
    let cases = [
        "",
        "\n",
        "a\n",
        "\r\n\r\n",
        "x = 1;\r\ny = 2;\r\n",
        "/*",
        "/* a\r\n b */ c",
        "\"",
        "\"\\",
        "'",
        "`",
        "\"\"\"",
        "#[",
        "#[derive(\n)]",
        "@",
        "@\n",
        "#",
        "0x",
        "1.2.3.4e+-5",
        "'a",
        "'ab'",
        "a'b'c'",
        "\u{0}\u{1}\u{7}\u{1b}[31m\u{7f}",
        "\u{feff}class Ä { String ü = \"ö\"; }",
        "let 𝑥 = '😀'; // 😀\u{202e}evil",
        "\u{fffd}\u{fffd} broken \u{fffd}",
        "\\\\\\",
        "/* /* */ */",
        "// comment \\\nnext",
        "`${`${`nested`}`}`",
    ];
    for code in cases {
        for language in [Language::Java, Language::TypeScript, Language::Rust] {
            assert_lossless(code, language);
        }
    }
}

#[test]
fn huge_and_pathological_input_finishes_quickly() {
    let start = std::time::Instant::now();
    let long_line = "/*".repeat(50_000) + &"\"".repeat(50_000) + &"a".repeat(100_000);
    let many_lines = "\n".repeat(100_000);
    let comment_soup = "/* x */ \"y\" 'z' `w` @a #[b] 12 ".repeat(20_000);
    for code in [long_line.as_str(), many_lines.as_str(), comment_soup.as_str()] {
        for language in [Language::Java, Language::TypeScript, Language::Rust] {
            assert_lossless(code, language);
        }
    }
    assert_eq!(highlight(&many_lines, Language::Java).len(), 100_001);
    assert!(start.elapsed().as_secs() < 30, "took {:?}", start.elapsed());
}

#[test]
fn literals_and_comments_by_language() {
    let style = |code: &str, language: Language, text: &str| -> Option<Style> {
        highlight(code, language).iter().flatten().find(|(t, _)| t == text).map(|(_, s)| *s)
    };
    // Keywords are per language.
    assert!(style("fn x", Language::Rust, "fn") == Some(Style::Keyword));
    assert!(style("fn x", Language::Java, "fn").is_none());
    assert!(style("let x", Language::TypeScript, "let") == Some(Style::Keyword));
    assert!(style("record R", Language::Java, "record") == Some(Style::Keyword));
    // Lifetimes only in Rust; elsewhere a quote starts a string.
    assert!(style("&'a str", Language::Rust, "'a") == Some(Style::Annotation));
    assert!(style("x('a str')", Language::TypeScript, "'a str'") == Some(Style::Str));
    // A char literal is not a lifetime.
    assert!(style("let c = 'x';", Language::Rust, "'x'") == Some(Style::Str));
    // Text blocks and template literals span lines.
    let lines = highlight("s = \"\"\"\n  a\n  \"\"\";", Language::Java);
    assert!(lines[1].iter().all(|(_, s)| *s == Style::Str));
    // Constants need two characters and a letter first; capitalised words are types.
    assert!(style("A b", Language::Java, "A") == Some(Style::Type));
    assert!(style("AB b", Language::Java, "AB") == Some(Style::Constant));
    assert!(style("Ab b", Language::Java, "Ab") == Some(Style::Type));
    assert!(style("@Override x", Language::Java, "@Override") == Some(Style::Annotation));
    assert!(style("#priv = 1", Language::TypeScript, "#priv") == Some(Style::Annotation));
}
