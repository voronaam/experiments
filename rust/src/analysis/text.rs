//! Text helpers shared by the language analysers (all supported languages use C-style comments).
//!
//! The lines-of-code metric is computed from the raw text rather than from the syntax tree. The Java edition
//! (`SourceText`) and the D edition (`analysis/text.d`) have the same helpers.

use std::path::Path;

/// Longest fully qualified name, in UTF-8 bytes, a building may have. A declaration whose fqn would be longer is
/// skipped together with everything nested in it, and its file counts as failed. Real names stay far below; without
/// the cap, a crafted long package, namespace, module or outer type name would be copied into the fqn of every
/// building inside it, which is quadratic in the file's size. The Java and D editions use the same limit.
pub const MAX_FQN_BYTES: usize = 1024;

/// Directory names that mark test code: Maven/Gradle (`src/test`, `src/it`, `androidTest`, `testFixtures`), Rust
/// (`tests`) and Jest (`__tests__`). Compared case-sensitively against whole path components.
const TEST_DIRS: [&str; 6] = ["test", "tests", "it", "androidTest", "testFixtures", "__tests__"];

/// The lines of a file, split on `\n` only, reduced to what [`count_loc`] needs: for each line, whether it counts as
/// code and whether a `/*` comment is open after it, once for each of the two states the line can start in (inside
/// such a comment or not). Computing this once per file keeps `count_loc` at constant cost per line: it is called
/// for every type and method, and their line ranges overlap (all of them, in a minified single-line file), so
/// judging the text itself on every call would be quadratic in the file's size.
pub struct Lines {
    /// Per line: [`COUNTS`] and [`OPENS`] for a line starting outside a comment, shifted by 2 for one inside.
    facts: Vec<u8>,
}

/// Line fact: the line counts as code.
const COUNTS: u8 = 1;
/// Line fact: a `/*` comment is open after the line.
const OPENS: u8 = 2;

impl Lines {
    /// The number of lines; a file that ends with a newline has a final empty line.
    pub fn len(&self) -> usize {
        self.facts.len()
    }
}

/// Splits on `\n` only, keeping trailing empty lines.
///
/// Line `i` is line number `i + 1`, so this has to agree with the parsers' line numbering, and they start a new line
/// only at `\n`: a lone `\r` (classic Mac line ending) does not break the line, and the `\r` of a `\r\n` stays at the
/// end of its line, where [`count_loc`]'s trimming drops it. A file ending in a newline has a final empty line.
pub fn lines(code: &str) -> Lines {
    Lines { facts: code.split('\n').map(|l| judge(l, false) | judge(l, true) << 2).collect() }
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
/// `begin`/`end` are clamped to the file, so a range from a node's line span is always safe. The range starts outside
/// a comment.
pub fn count_loc(lines: &Lines, begin: u32, end: u32) -> u32 {
    let mut loc = 0;
    // True while inside a block comment that started on an earlier line.
    let mut in_block = false;
    let from = begin.max(1) as usize - 1;
    let to = (end as usize).min(lines.len());
    for &f in lines.facts.iter().take(to).skip(from) {
        let facts = if in_block { f >> 2 } else { f };
        if facts & COUNTS != 0 {
            loc += 1;
        }
        in_block = facts & OPENS != 0;
    }
    loc
}

/// How one line is judged by [`count_loc`]: [`COUNTS`] if it counts as code, plus [`OPENS`] if a `/*` comment is
/// open after it. `in_block`: the line starts inside one.
fn judge(line: &str, in_block: bool) -> u8 {
    let mut s = line.trim();
    if in_block {
        match s.find("*/") {
            None => return OPENS,
            // The comment ends on this line; only what follows `*/` can be code.
            Some(close) => s = s[close + 2..].trim(),
        }
    }
    if s.starts_with("/*") {
        // Search after the opening `/*` so that `/*/` is not taken as open-and-close. `close` is relative to s[2..],
        // so the text after `*/` starts at close + 2 (the skipped `/*`) + 2 (the `*/` itself).
        match s[2..].find("*/") {
            None => return OPENS,
            Some(close) => s = s[close + 4..].trim(),
        }
    }
    if !s.is_empty() && !s.starts_with("//") {
        COUNTS | if opens_block(s) { OPENS } else { 0 }
    } else {
        0
    }
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
        let l = lines("a\r\nb\rc\n");
        assert_eq!((l.len(), count_loc(&l, 1, 3)), (3, 2));
    }
}
