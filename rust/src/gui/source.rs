//! Source viewer: a light tokenizer (comments, strings, keywords, types, constants, numbers, annotations,
//! Rust lifetimes) with a GitHub-dark palette, rendered as virtualised rows.

use crate::model::Language;
use three_d::egui::{self, Color32, FontId, TextFormat};

#[derive(Clone, Copy, PartialEq)]
pub enum Style {
    Plain,
    Keyword,
    Type,
    Constant,
    Str,
    Comment,
    Number,
    Annotation,
}

impl Style {
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

pub type Line = Vec<(String, Style)>;

pub struct SourceView {
    pub title: String,
    pub lines: Vec<Line>,
    /// 1-based line range of the selected type
    pub first: usize,
    pub last: usize,
    pub scroll_pending: bool,
    pub open: bool,
}

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
    let mut mode: Option<Style> = None; // inside a block comment or text block that continues onto the next line
    let mut i = 0;
    let flush = |plain: &mut String, line: &mut Line| {
        if !plain.is_empty() {
            line.push((std::mem::take(plain), Style::Plain));
        }
    };
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
        if let Some(style) = mode {
            let close = if style == Style::Comment { "*/" } else { "\"\"\"" };
            let mut j = i;
            while j < n && chars[j] != '\n' && !starts(j, close) {
                j += 1;
            }
            if j < n && starts(j, close) {
                j += close.len();
                mode = None;
            }
            line.push((chars[i..j].iter().collect(), style));
            i = j;
            continue;
        }
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
            mode = Some(Style::Comment);
            line.push(("/*".into(), Style::Comment));
            i += 2;
        } else if starts(i, "\"\"\"") {
            flush(&mut plain, &mut line);
            mode = Some(Style::Str);
            line.push(("\"\"\"".into(), Style::Str));
            i += 3;
        } else if c == '\'' && i + 2 < n && (chars[i + 1].is_alphabetic() || chars[i + 1] == '_') && chars[i + 2] != '\'' {
            // a Rust lifetime such as 'a or 'static, not a character literal
            flush(&mut plain, &mut line);
            let mut j = i + 1;
            while ident(j) {
                j += 1;
            }
            line.push((chars[i..j].iter().collect(), Style::Annotation));
            i = j;
        } else if c == '"' || c == '\'' || c == '`' {
            flush(&mut plain, &mut line);
            let mut j = i + 1;
            while j < n && chars[j] != c && chars[j] != '\n' {
                j += if chars[j] == '\\' { 2 } else { 1 };
            }
            j = (j + 1).min(n);
            line.push((chars[i..j].iter().collect(), Style::Str));
            i = j;
        } else if (c == '@' || c == '#') && i + 1 < n && (chars[i + 1].is_alphabetic() || chars[i + 1] == '[') {
            flush(&mut plain, &mut line);
            let mut j = i + 1;
            if chars[j] == '[' {
                while j < n && chars[j] != ']' && chars[j] != '\n' {
                    j += 1;
                }
                j = (j + 1).min(n);
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
}
