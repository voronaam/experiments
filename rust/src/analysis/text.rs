//! Text helpers shared by the language analysers (all supported languages use C-style comments).

use std::path::Path;

const TEST_DIRS: [&str; 6] = ["test", "tests", "it", "androidTest", "testFixtures", "__tests__"];

/// Splits like Java's `split("\\R", -1)`: on \r\n, \n or \r, keeping trailing empty lines.
pub fn lines(code: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let bytes = code.as_bytes();
    let mut start = 0;
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'\n' => {
                out.push(&code[start..i]);
                start = i + 1;
            }
            b'\r' => {
                out.push(&code[start..i]);
                if i + 1 < bytes.len() && bytes[i + 1] == b'\n' {
                    i += 1;
                }
                start = i + 1;
            }
            _ => {}
        }
        i += 1;
    }
    out.push(&code[start..]);
    out
}

/// Non-blank lines in [begin, end] (1-based, inclusive) that are not purely comments.
pub fn count_loc(lines: &[&str], begin: u32, end: u32) -> u32 {
    let mut loc = 0;
    let mut in_block = false;
    let from = begin.max(1) as usize - 1;
    let to = (end as usize).min(lines.len());
    for line in lines.iter().take(to).skip(from) {
        let mut s = line.trim();
        if in_block {
            match s.find("*/") {
                None => continue,
                Some(close) => {
                    in_block = false;
                    s = s[close + 2..].trim();
                }
            }
        }
        if s.starts_with("/*") {
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
        }
    }
    loc
}

/// True when any directory on the (relative) path is a conventional test directory.
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
    fn splits_like_java() {
        assert_eq!(lines("a\r\nb\rc\n"), vec!["a", "b", "c", ""]);
    }
}
