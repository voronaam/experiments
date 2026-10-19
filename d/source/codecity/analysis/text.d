/// Text helpers shared by the language analysers (all supported languages use C-style comments).
module codecity.analysis.text;

import std.algorithm : canFind, startsWith;
import std.path : dirName, pathSplitter;
import std.string : indexOf, strip;

/// Directory names that mark test code: Maven/Gradle (`test`, `it`, `androidTest`, `testFixtures`), Rust/Cargo
/// (`tests`) and Jest (`__tests__`). Compared against whole path segments, case-sensitively.
immutable string[] testDirs = ["test", "tests", "it", "androidTest", "testFixtures", "__tests__"];

/// Splits on `\n` only, keeping trailing empty lines.
///
/// Element `i` of the result is source line `i + 1`, so `countLoc` can index it with the 1-based line numbers from
/// `firstLine`/`lastLine`. The parsers start a new line only at `\n`, so a lone `\r` (classic Mac line ending) does
/// not break the line, and the `\r` of a `\r\n` stays at the end of its line, where `countLoc`'s `strip` drops it.
/// The lines are slices of `code`, not copies.
string[] lines(string code) pure nothrow @safe
{
    // Not std.array.split: it returns no lines at all for an empty file, where the other editions return one.
    string[] output;
    size_t start = 0;
    foreach (i, c; code)
        if (c == '\n')
        {
            output ~= code[start .. i];
            start = i + 1;
        }
    output ~= code[start .. $];
    return output;
}

/// Non-blank lines in [begin, end] (1-based, inclusive) that are not purely comments.
///
/// A line-based scan, not a tokeniser: `//` comments count only when they start the (trimmed) line, and a `/*`
/// block is skipped only at the start of a line (or right after a block that closed on that line). Consequences:
/// a line with code before a comment counts as code, and a block comment opened after code (`x(); /* ...`) is
/// tracked by `opensBlock`, so its following lines do not count; comment markers inside string literals are taken at
/// face value, except by `opensBlock`, which skips literals. Out-of-range bounds are clamped to the available lines.
/// The Rust edition's `count_loc` is the same algorithm.
uint countLoc(const string[] lines, uint begin, uint end) pure @safe
{
    uint loc;
    bool inBlock;
    const from = (begin < 1 ? 1 : begin) - 1;
    const to = end < lines.length ? end : lines.length;
    foreach (idx; from .. to)
    {
        string s = lines[idx].strip;
        if (inBlock)
        {
            const close = s.indexOf("*/");
            if (close < 0) continue;
            inBlock = false;
            s = s[close + 2 .. $].strip;
        }
        if (s.startsWith("/*"))
        {
            // Search after the opening "/*" so that "/*/" is not taken as a complete comment.
            const close = s[2 .. $].indexOf("*/");
            if (close < 0)
            {
                inBlock = true;
                continue;
            }
            // close is relative to s[2 .. $]: skip "/*", the comment body and "*/".
            s = s[close + 4 .. $].strip;
        }
        if (s.length && !s.startsWith("//"))
        {
            loc++;
            inBlock = opensBlock(s);
        }
    }
    return loc;
}

/// True when a line of code leaves a `/*` comment open (`x = 1; /* starts here`), so the next lines are inside it.
///
/// Scans left to right: `/* ... */` pairs on the line are skipped, `//` ends the scan, and double-quoted strings
/// (with backslash escapes) are skipped, so `"**/*.d"` does not open a comment. One-character literals (`'"'`,
/// `'\''`) are skipped so their quote does not start a string; a lone `'` (a Rust lifetime) is ignored.
private bool opensBlock(string s) pure @safe
{
    size_t i = 0;
    while (i < s.length)
    {
        if (s[i] == '\'' && i + 2 < s.length && s[i + 2] == '\'')
            i += 2; // 'x'
        else if (s[i] == '\'' && i + 3 < s.length && s[i + 1] == '\\' && s[i + 3] == '\'')
            i += 3; // '\x'
        else if (s[i] == '"')
        {
            // Skip to the closing quote; an unterminated string runs to the end of the line.
            i++;
            while (i < s.length && s[i] != '"') i += s[i] == '\\' ? 2 : 1;
        }
        else if (s[i .. $].startsWith("//"))
            return false;
        else if (s[i .. $].startsWith("/*"))
        {
            const close = s[i + 2 .. $].indexOf("*/");
            if (close < 0) return true;
            i += close + 3; // to the '/' of "*/"; the i++ below steps past it
        }
        i++;
    }
    return false;
}

/// True when any directory on the (relative, forward-slash) path is a conventional test directory. The file name
/// itself is not checked (a file named `test` is not in a test directory); per-language name rules such as
/// `*Test.java` live in the analysers.
bool inTestDirectory(string relative) @safe
{
    const dir = relative.dirName;
    if (dir == ".") return false;
    foreach (part; dir.pathSplitter)
        if (testDirs.canFind(part)) return true;
    return false;
}

unittest
{
    assert(countLoc(lines("a\n\n// c\n/* x\n y */ b\n/* z */\nc /* d */"), 1, 7) == 3);
    // A block comment opened after code hides the following lines; "/*" inside literals opens nothing.
    assert(countLoc(lines("int x; /* start\n still comment\nend */\ny = \"/*\" + '\"'; /* a */ z;\nw;"), 1, 5) == 3);
    // Only \n starts a new line, as in the parsers.
    assert(lines("a\r\nb\rc\n") == ["a\r", "b\rc", ""]);
    assert(lines("") == [""]);
    assert(inTestDirectory("src/test/java/A.java") && !inTestDirectory("A.java"));
}
