//! Source viewer: a light tokenizer (comments, strings, keywords, types, constants, numbers, annotations,
//! Rust lifetimes) with a GitHub-dark palette, rendered as virtualised rows.
//!
//! When a building is opened, `gui/mod.rs` reads the type's source file, runs [`highlight`] once over the whole file
//! and keeps the result in a [`SourceView`]. The source window then only calls [`layout`] for the rows that are
//! actually on screen, so even a very long file costs little per frame.
//!
//! The tokenizer is deliberately approximate: it works character by character with a few lookaheads, knows no
//! grammar, and only has to look right, not be right. The one piece of state carried from one line to the next is
//! an open block comment, text block or template literal. No token ever contains a line break, so the output always
//! has exactly one [`Line`] per line of input.

use crate::model::Language;
use three_d::egui::{self, Color32, FontId, TextFormat};

/// Token class; decides only the colour.
#[derive(Clone, Copy, PartialEq)]
pub enum Style {
    Plain,
    Keyword,
    Type,
    Constant,
    Str,
    Comment,
    Number,
    /// Java/TypeScript annotations and decorators, Rust attributes and lifetimes, TypeScript `#private` names.
    Annotation,
}

impl Style {
    /// Text colour on the viewer's dark background (colours from GitHub's dark theme).
    fn color(self) -> Color32 {
        match self {
            Style::Plain => Color32::from_rgb(0xc9, 0xd1, 0xd9),
            Style::Keyword => Color32::from_rgb(0xff, 0x7b, 0x72),
            Style::Type => Color32::from_rgb(0xff, 0xa6, 0x57),
            Style::Constant | Style::Number => Color32::from_rgb(0x79, 0xc0, 0xff),
            Style::Str => Color32::from_rgb(0xa5, 0xd6, 0xff),
            Style::Comment => Color32::from_rgb(0x8b, 0x94, 0x9e),
            Style::Annotation => Color32::from_rgb(0xd2, 0xa8, 0xff),
        }
    }
}

/// One source line as a run of styled pieces; neighbouring plain text is merged into one piece.
pub type Line = Vec<(String, Style)>;

/// The open source window: the highlighted file and which part of it belongs to the selected type.
pub struct SourceView {
    pub title: String,
    /// Every line of the file, highlighted; index `n` is line `n + 1`.
    pub lines: Vec<Line>,
    /// 1-based line range of the selected type
    pub first: usize,
    pub last: usize,
    /// Set when the view is created; the first frame scrolls to a few lines above `first` and clears it.
    pub scroll_pending: bool,
    /// Whether the window is shown; cleared by its close button.
    pub open: bool,
}

// Keywords per language. Literals (`true`, `false`, `null`, `undefined`) and, for TypeScript and Rust, primitive
// type names are included so they get the keyword colour rather than plain or type colouring.
const JAVA: &[&str] = &[
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default",
    "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
    "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
    "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
    "void", "volatile", "while", "var", "record", "yield", "sealed", "permits", "true", "false", "null",
];
const TYPESCRIPT: &[&str] = &[
    "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch", "class", "const", "constructor",
    "continue", "declare", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
    "from", "function", "get", "if", "implements", "import", "in", "instanceof", "interface", "keyof", "let",
    "namespace", "never", "new", "null", "number", "of", "private", "protected", "public", "readonly", "return", "set",
    "static", "string", "super", "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "unknown",
    "var", "void", "while", "yield", "satisfies",
];
const RUST: &[&str] = &[
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for",
    "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static",
    "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "i8", "i16", "i32", "i64", "i128",
    "isize", "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64", "bool", "char", "str",
];

/// Splits `code` into lines and styles each piece.
///
/// Returns one [`Line`] per line of input: a file ending in a newline gets an empty last line. `\r` is dropped, so
/// CRLF files work. Tokens are recognised in this order at each position:
///
/// 1. continuation of a block comment, `"""` text block or template literal opened on an earlier line;
/// 2. `//` line comments (to the end of the line);
/// 3. `/*` block comments and `"""` text blocks (Java), which may span lines;
/// 4. Rust lifetimes (`'a`, `'static`), in Rust code only: a quote followed by a letter or `_` and then not a closing
///    quote;
/// 5. string and character literals in `"` or `'`, ending at the matching quote or the end of the line (an
///    unterminated literal stops before the line break); template literals in `` ` `` may span lines like rule 3;
/// 6. annotations: `@Name`, `@a.b.Name`, `#name` and attributes `#[...]` / `@[...]` up to the first `]` on the line
///    (or the end of the line);
/// 7. words, classified as keyword (from the language's list), constant (at least two characters, starts with a
///    letter, no lower-case letters: `MAX_SIZE`), type (starts with an upper-case letter) or plain;
/// 8. numbers: a digit followed by any letters, digits, `.` and `_`, which covers `0x1F`, `1_000`, `2.5f`, `10u8`;
/// 9. anything else is plain.
pub fn highlight(code: &str, language: Language) -> Vec<Line> {
    let keywords = match language {
        Language::Java => JAVA,
        Language::TypeScript => TYPESCRIPT,
        Language::Rust => RUST,
    };
    let chars: Vec<char> = code.chars().collect();
    let n = chars.len();
    let mut lines = vec![];
    let mut line: Line = vec![];
    let mut plain = String::new();
    // inside a block comment, text block or template literal that may continue onto the next line: its style and
    // closing delimiter
    let mut mode: Option<(Style, &str)> = None;
    let mut i = 0;
    // Plain characters are collected in `plain` and only pushed as one piece when a styled token or the end of the
    // line follows.
    let flush = |plain: &mut String, line: &mut Line| {
        if !plain.is_empty() {
            line.push((std::mem::take(plain), Style::Plain));
        }
    };
    // Whether the text at position `i` starts with `s`; false near the end instead of panicking.
    let starts = |i: usize, s: &str| s.chars().enumerate().all(|(k, c)| chars.get(i + k) == Some(&c));
    while i < n {
        let c = chars[i];
        if c == '\r' {
            i += 1;
            continue;
        }
        if c == '\n' {
            flush(&mut plain, &mut line);
            lines.push(std::mem::take(&mut line));
            i += 1;
            continue;
        }
        // Inside a multi-line comment, text block or template literal: everything up to the closing delimiter
        // (inclusive) or the end of this line is one piece; if the line ends first, `mode` stays set for the next line.
        // In literals a backslash skips the next character (but never the line break).
        if let Some((style, close)) = mode {
            let mut j = i;
            while j < n && chars[j] != '\n' && !starts(j, close) {
                j += if style == Style::Str && chars[j] == '\\' && chars.get(j + 1).is_some_and(|&c| c != '\n') { 2 } else { 1 };
            }
            if j < n && starts(j, close) {
                j += close.len();
                mode = None;
            }
            line.push((chars[i..j].iter().collect(), style));
            i = j;
            continue;
        }
        // Identifier characters; `$` is legal in Java and TypeScript identifiers.
        let ident = |i: usize| chars.get(i).is_some_and(|c| c.is_alphanumeric() || *c == '_' || *c == '$');
        if starts(i, "//") {
            flush(&mut plain, &mut line);
            let mut j = i;
            while j < n && chars[j] != '\n' {
                j += 1;
            }
            line.push((chars[i..j].iter().collect(), Style::Comment));
            i = j;
        } else if starts(i, "/*") {
            flush(&mut plain, &mut line);
            mode = Some((Style::Comment, "*/"));
            line.push(("/*".into(), Style::Comment));
            i += 2;
        } else if starts(i, "\"\"\"") {
            flush(&mut plain, &mut line);
            mode = Some((Style::Str, "\"\"\""));
            line.push(("\"\"\"".into(), Style::Str));
            i += 3;
        } else if c == '`' {
            flush(&mut plain, &mut line);
            mode = Some((Style::Str, "`"));
            line.push(("`".into(), Style::Str));
            i += 1;
        } else if language == Language::Rust && c == '\'' && i + 2 < n && (chars[i + 1].is_alphabetic() || chars[i + 1] == '_') && chars[i + 2] != '\'' {
            // a Rust lifetime such as 'a or 'static, not a character literal
            flush(&mut plain, &mut line);
            let mut j = i + 1;
            while ident(j) {
                j += 1;
            }
            line.push((chars[i..j].iter().collect(), Style::Annotation));
            i = j;
        } else if c == '"' || c == '\'' {
            flush(&mut plain, &mut line);
            let mut j = i + 1;
            // A backslash skips the next character, so escaped quotes do not end the literal; a backslash right before
            // the line break does not skip it.
            while j < n && chars[j] != c && chars[j] != '\n' {
                j += if chars[j] == '\\' && chars.get(j + 1).is_some_and(|&c| c != '\n') { 2 } else { 1 };
            }
            // Include the closing quote; an unterminated literal ends before the line break.
            if j < n && chars[j] == c {
                j += 1;
            }
            line.push((chars[i..j].iter().collect(), Style::Str));
            i = j;
        } else if (c == '@' || c == '#') && i + 1 < n && (chars[i + 1].is_alphabetic() || chars[i + 1] == '[') {
            flush(&mut plain, &mut line);
            let mut j = i + 1;
            // `#[derive(...)]`: up to the first `]` (a nested `]` ends it early) or the end of the line. Otherwise a
            // dotted name.
            if chars[j] == '[' {
                while j < n && chars[j] != ']' && chars[j] != '\n' {
                    j += 1;
                }
                if j < n && chars[j] == ']' {
                    j += 1;
                }
            } else {
                while ident(j) || chars.get(j) == Some(&'.') {
                    j += 1;
                }
            }
            line.push((chars[i..j].iter().collect(), Style::Annotation));
            i = j;
        } else if c.is_alphabetic() || c == '_' || c == '$' {
            let mut j = i;
            while ident(j) {
                j += 1;
            }
            let word: String = chars[i..j].iter().collect();
            let style = if keywords.contains(&word.as_str()) {
                Some(Style::Keyword)
            // `word == word.to_uppercase()` holds when there are no lower-case letters; digits and `_` are allowed.
            } else if word.chars().count() > 1 && word.chars().next().is_some_and(char::is_alphabetic) && word == word.to_uppercase() {
                Some(Style::Constant)
            } else if word.chars().next().is_some_and(char::is_uppercase) {
                Some(Style::Type)
            } else {
                None
            };
            match style {
                Some(s) => {
                    flush(&mut plain, &mut line);
                    line.push((word, s));
                }
                // Ordinary identifiers stay part of the surrounding plain run.
                None => plain.push_str(&word),
            }
            i = j;
        } else if c.is_ascii_digit() {
            flush(&mut plain, &mut line);
            let mut j = i;
            while j < n && (chars[j].is_alphanumeric() || chars[j] == '.' || chars[j] == '_') {
                j += 1;
            }
            line.push((chars[i..j].iter().collect(), Style::Number));
            i = j;
        } else {
            plain.push(c);
            i += 1;
        }
    }
    flush(&mut plain, &mut line);
    lines.push(line);
    lines
}

/// Builds the egui text for one row: the line number right-aligned in a `digits`-wide grey gutter, three spaces,
/// then the styled pieces, all in `font`.
pub fn layout(line: &Line, number: usize, digits: usize, font: &FontId) -> egui::text::LayoutJob {
    let mut job = egui::text::LayoutJob::default();
    let gutter = TextFormat { font_id: font.clone(), color: Color32::from_rgb(0x6e, 0x76, 0x81), ..Default::default() };
    job.append(&format!("{number:>digits$}   "), 0.0, gutter);
    for (text, style) in line {
        job.append(text, 0.0, TextFormat { font_id: font.clone(), color: style.color(), ..Default::default() });
    }
    job
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_and_lines() {
        let lines = highlight("/* a\n b */ fn x<'a>(s: &'a str) -> u8 { // c\n  let C_MAX = \"q\"; 42 }", Language::Rust);
        assert_eq!(lines.len(), 3);
        let find = |l: &Line, t: &str| l.iter().find(|(s, _)| s == t).map(|(_, st)| *st);
        assert!(find(&lines[1], " b */") == Some(Style::Comment));
        assert!(find(&lines[1], "fn") == Some(Style::Keyword));
        assert!(find(&lines[1], "'a") == Some(Style::Annotation));
        assert!(find(&lines[1], "// c") == Some(Style::Comment));
        assert!(find(&lines[2], "C_MAX") == Some(Style::Constant));
        assert!(find(&lines[2], "\"q\"") == Some(Style::Str));
        assert!(find(&lines[2], "42") == Some(Style::Number));
    }

    #[test]
    fn line_breaks_never_swallowed() {
        let code = "let a = \"open\n#[derive(X\nlet b = \"x\\\nconst T = `a\nb` + 'react';\nC_MAX";
        let lines = highlight(code, Language::TypeScript);
        assert_eq!(lines.len(), code.lines().count());
        for (line, text) in lines.iter().zip(code.lines()) {
            assert_eq!(line.iter().map(|(s, _)| s.as_str()).collect::<String>(), text);
        }
        let find = |l: &Line, t: &str| l.iter().find(|(s, _)| s == t).map(|(_, st)| *st);
        assert!(find(&lines[4], "b`") == Some(Style::Str));
        assert!(find(&lines[4], "'react'") == Some(Style::Str));
        assert!(find(&lines[5], "C_MAX") == Some(Style::Constant));
    }
}
