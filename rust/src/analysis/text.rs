//! Text helpers shared by the language analysers (all supported languages use C-style comments).
//!
//! The lines-of-code metric is computed from the raw text rather than from the syntax tree. The Java edition
//! (`SourceText`) and the D edition (`analysis/text.d`) have the same helpers.

use std::path::Path;

/// Directory names that mark test code: Maven/Gradle (`src/test`, `src/it`, `androidTest`, `testFixtures`), Rust
/// (`tests`) and Jest (`__tests__`). Compared case-sensitively against whole path components.
const TEST_DIRS: [&str; 6] = ["test", "tests", "it", "androidTest", "testFixtures", "__tests__"];

/// Splits on `\n` only, keeping trailing empty lines.
///
/// The result is indexed by line number - 1, so it has to agree with the parsers' line numbering, and they start a
/// new line only at `\n`: a lone `\r` (classic Mac line ending) does not break the line, and the `\r` of a
/// `\r\n` stays at the end of its line, where [`count_loc`]'s trimming drops it. A file ending in a newline yields a
/// final empty string.
pub fn lines(code: &str) -> Vec<&str> {
    code.split('\n').collect()
}

/// Non-blank lines in [begin, end] (1-based, inclusive) that are not purely comments.
///
/// A line-based approximation, not a lexer: a line counts unless, after stripping a `/* … */` block that starts it
/// (after trimming), it is empty or starts with `//`. Consequences:
/// - `code(); // note` and `code(); /* note */` count, as they contain code;
/// - `/* a */ b` counts (code after the comment), `/* a */` alone does not;
/// - a block comment opened after code (`x = 1; /* starts here`) is tracked, so its following lines do not count
///   (see [`opens_block`]);
/// - comment markers inside string literals are taken at face value, except by [`opens_block`], which skips literals.
///
/// `begin`/`end` are clamped to the file, so a range from a node's line span is always safe.
pub fn count_loc(lines: &[&str], begin: u32, end: u32) -> u32 {
    let mut loc = 0;
    // True while inside a block comment that started on an earlier line.
    let mut in_block = false;
    let from = begin.max(1) as usize - 1;
    let to = (end as usize).min(lines.len());
    for line in lines.iter().take(to).skip(from) {
        let mut s = line.trim();
        if in_block {
            match s.find("*/") {
                None => continue,
                Some(close) => {
                    // The comment ends on this line; only what follows `*/` can be code.
                    in_block = false;
                    s = s[close + 2..].trim();
                }
            }
        }
        if s.starts_with("/*") {
            // Search after the opening `/*` so that `/*/` is not taken as open-and-close. `close` is relative to
            // s[2..], so the text after `*/` starts at close + 2 (the skipped `/*`) + 2 (the `*/` itself).
            match s[2..].find("*/") {
                None => {
                    in_block = true;
                    continue;
                }
                Some(close) => s = s[close + 4..].trim(),
            }
        }
        if !s.is_empty() && !s.starts_with("//") {
            loc += 1;
            in_block = opens_block(s);
        }
    }
    loc
}

/// True when a line of code leaves a `/*` comment open (`x = 1; /* starts here`), so the next lines are inside it.
///
/// Scans left to right: `/* … */` pairs on the line are skipped, `//` ends the scan, and double-quoted strings
/// (with backslash escapes) are skipped, so `"**/*.rs"` does not open a comment. One-character literals (`'"'`,
/// `'\''`) are skipped so their quote does not start a string; a lone `'` (a Rust lifetime) is ignored.
fn opens_block(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'\'' && b.get(i + 2) == Some(&b'\'') {
            i += 2; // 'x'
        } else if b[i] == b'\'' && b.get(i + 1) == Some(&b'\\') && b.get(i + 3) == Some(&b'\'') {
            i += 3; // '\x'
        } else if b[i] == b'"' {
            // Skip to the closing quote; an unterminated string runs to the end of the line.
            i += 1;
            while i < b.len() && b[i] != b'"' {
                i += if b[i] == b'\\' { 2 } else { 1 };
            }
        } else if b[i..].starts_with(b"//") {
            return false;
        } else if b[i..].starts_with(b"/*") {
            match s[i + 2..].find("*/") {
                None => return true,
                Some(close) => i += close + 3, // to the '/' of "*/"; the += 1 below steps past it
            }
        }
        i += 1;
    }
    false
}

/// True when any directory on the (relative) path is a conventional test directory. Only the directories count, not
/// the file name itself: `src/test/java/Foo.java` is a test, `src/main/test.ts` is not. Callers pass the path
/// relative to the analysed root so that the root's own location (e.g. a checkout under `/tmp/test`) does not matter.
pub fn in_test_directory(relative: &Path) -> bool {
    match relative.parent() {
        None => false,
        Some(parent) => parent
            .components()
            .any(|c| TEST_DIRS.contains(&c.as_os_str().to_string_lossy().as_ref())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_code_lines_only() {
        let code = "a\n\n// c\n/* x\n y */ b\n/* z */\nc /* d */";
        assert_eq!(count_loc(&lines(code), 1, 7), 3);
    }

    #[test]
    fn tracks_block_comment_opened_after_code() {
        let code = "int x; /* start\n still comment\nend */\ny = \"/*\" + '\"'; /* a */ z;\nw;";
        assert_eq!(count_loc(&lines(code), 1, 5), 3);
    }

    #[test]
    fn splits_on_newlines_only() {
        // Only \n starts a new line, as in the parsers.
        assert_eq!(lines("a\r\nb\rc\n"), vec!["a\r", "b\rc", ""]);
    }
}
