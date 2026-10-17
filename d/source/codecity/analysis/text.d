/// Text helpers shared by the language analysers (all supported languages use C-style comments).
module codecity.analysis.text;

import std.algorithm : canFind, startsWith;
import std.path : dirName, pathSplitter;
import std.string : indexOf, strip;

immutable string[] testDirs = ["test", "tests", "it", "androidTest", "testFixtures", "__tests__"];

/// Splits like Java's `split("\\R", -1)`: on \r\n, \n or \r, keeping trailing empty lines.
string[] lines(string code) pure nothrow @safe
{
    string[] output;
    size_t start = 0, i = 0;
    while (i < code.length)
    {
        if (code[i] == '\n')
        {
            output ~= code[start .. i];
            start = i + 1;
        }
        else if (code[i] == '\r')
        {
            output ~= code[start .. i];
            if (i + 1 < code.length && code[i + 1] == '\n') i++;
            start = i + 1;
        }
        i++;
    }
    output ~= code[start .. $];
    return output;
}

/// Non-blank lines in [begin, end] (1-based, inclusive) that are not purely comments.
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
            const close = s[2 .. $].indexOf("*/");
            if (close < 0)
            {
                inBlock = true;
                continue;
            }
            s = s[close + 4 .. $].strip;
        }
        if (s.length && !s.startsWith("//")) loc++;
    }
    return loc;
}

/// True when any directory on the (relative, forward-slash) path is a conventional test directory.
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
    assert(lines("a\r\nb\rc\n") == ["a", "b", "c", ""]);
    assert(inTestDirectory("src/test/java/A.java") && !inTestDirectory("A.java"));
}
