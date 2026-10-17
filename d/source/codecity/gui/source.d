/// Source viewer: a light tokenizer (comments, strings, keywords, types, constants, numbers, annotations, Rust
/// lifetimes) with a GitHub-dark palette.
module codecity.gui.source;

import codecity.model : Language;
import std.algorithm : canFind;
import std.uni : isAlpha, isAlphaNum, isNumber, isUpper, toUpper;
import std.conv : to;

enum Style { plain, keyword, type, constant, str, comment, number, annotation }

uint styleColor(Style s) pure
{
    final switch (s)
    {
    case Style.plain: return 0xc9d1d9;
    case Style.keyword: return 0xff7b72;
    case Style.type: return 0xffa657;
    case Style.constant, Style.number: return 0x79c0ff;
    case Style.str: return 0xa5d6ff;
    case Style.comment: return 0x8b949e;
    case Style.annotation: return 0xd2a8ff;
    }
}

struct Token
{
    string text;
    Style style;
}

alias Line = Token[];

private immutable string[] javaKeywords = [
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default",
    "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
    "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
    "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
    "void", "volatile", "while", "var", "record", "yield", "sealed", "permits", "true", "false", "null",
];
private immutable string[] tsKeywords = [
    "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch", "class", "const", "constructor",
    "continue", "declare", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
    "from", "function", "get", "if", "implements", "import", "in", "instanceof", "interface", "keyof", "let",
    "namespace", "never", "new", "null", "number", "of", "private", "protected", "public", "readonly", "return", "set",
    "static", "string", "super", "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "unknown",
    "var", "void", "while", "yield", "satisfies",
];
private immutable string[] rustKeywords = [
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for",
    "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static",
    "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "i8", "i16", "i32", "i64", "i128",
    "isize", "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64", "bool", "char", "str",
];

Line[] highlight(string code, Language language)
{
    const keywords = language == Language.java ? javaKeywords : language == Language.typescript ? tsKeywords : rustKeywords;
    dchar[] chars = code.to!(dchar[]);
    const n = chars.length;
    Line[] lines;
    Line line;
    dchar[] plain;
    bool inMode;
    Style mode;
    size_t i;
    void flush()
    {
        if (plain.length)
        {
            line ~= Token(plain.to!string, Style.plain);
            plain = null;
        }
    }
    bool starts(size_t at, string s)
    {
        size_t k;
        foreach (dchar c; s)
        {
            if (at + k >= n || chars[at + k] != c) return false;
            k++;
        }
        return true;
    }
    bool ident(size_t at) { return at < n && (isAlphaNum(chars[at]) || chars[at] == '_' || chars[at] == '$'); }
    while (i < n)
    {
        const c = chars[i];
        if (c == '\r')
        {
            i++;
            continue;
        }
        if (c == '\n')
        {
            flush();
            lines ~= line;
            line = null;
            i++;
            continue;
        }
        if (inMode)
        {
            const close = mode == Style.comment ? "*/" : `"""`;
            size_t j = i;
            while (j < n && chars[j] != '\n' && !starts(j, close)) j++;
            if (j < n && starts(j, close))
            {
                j += close.length;
                inMode = false;
            }
            line ~= Token(chars[i .. j].to!string, mode);
            i = j;
            continue;
        }
        if (starts(i, "//"))
        {
            flush();
            size_t j = i;
            while (j < n && chars[j] != '\n') j++;
            line ~= Token(chars[i .. j].to!string, Style.comment);
            i = j;
        }
        else if (starts(i, "/*"))
        {
            flush();
            inMode = true;
            mode = Style.comment;
            line ~= Token("/*", Style.comment);
            i += 2;
        }
        else if (starts(i, `"""`))
        {
            flush();
            inMode = true;
            mode = Style.str;
            line ~= Token(`"""`, Style.str);
            i += 3;
        }
        else if (c == '\'' && i + 2 < n && (isAlpha(chars[i + 1]) || chars[i + 1] == '_') && chars[i + 2] != '\'')
        {
            // a Rust lifetime such as 'a or 'static, not a character literal
            flush();
            size_t j = i + 1;
            while (ident(j)) j++;
            line ~= Token(chars[i .. j].to!string, Style.annotation);
            i = j;
        }
        else if (c == '"' || c == '\'' || c == '`')
        {
            flush();
            size_t j = i + 1;
            while (j < n && chars[j] != c && chars[j] != '\n') j += chars[j] == '\\' ? 2 : 1;
            j = j + 1 < n ? j + 1 : n;
            line ~= Token(chars[i .. j].to!string, Style.str);
            i = j;
        }
        else if ((c == '@' || c == '#') && i + 1 < n && (isAlpha(chars[i + 1]) || chars[i + 1] == '['))
        {
            flush();
            size_t j = i + 1;
            if (chars[j] == '[')
            {
                while (j < n && chars[j] != ']' && chars[j] != '\n') j++;
                j = j + 1 < n ? j + 1 : n;
            }
            else
                while (ident(j) || (j < n && chars[j] == '.')) j++;
            line ~= Token(chars[i .. j].to!string, Style.annotation);
            i = j;
        }
        else if (isAlpha(c) || c == '_' || c == '$')
        {
            size_t j = i;
            while (ident(j)) j++;
            const word = chars[i .. j].to!string;
            Style s;
            bool styled = true;
            if (keywords.canFind(word)) s = Style.keyword;
            else if (j - i > 1 && isAlpha(chars[i]) && word == word.toUpper) s = Style.constant;
            else if (isUpper(chars[i])) s = Style.type;
            else styled = false;
            if (styled)
            {
                flush();
                line ~= Token(word, s);
            }
            else plain ~= chars[i .. j];
            i = j;
        }
        else if (c >= '0' && c <= '9')
        {
            flush();
            size_t j = i;
            while (j < n && (isAlphaNum(chars[j]) || chars[j] == '.' || chars[j] == '_')) j++;
            line ~= Token(chars[i .. j].to!string, Style.number);
            i = j;
        }
        else
        {
            plain ~= c;
            i++;
        }
    }
    flush();
    lines ~= line;
    return lines;
}

unittest
{
    auto lines = highlight("/* a\n b */ fn x<'a>(s: &'a str) -> u8 { // c\n  let C_MAX = \"q\"; 42 }", Language.rust);
    assert(lines.length == 3);
    Style find(Line l, string t) { foreach (tok; l) if (tok.text == t) return tok.style; assert(0, t); }
    assert(find(lines[1], " b */") == Style.comment);
    assert(find(lines[1], "fn") == Style.keyword);
    assert(find(lines[1], "'a") == Style.annotation);
    assert(find(lines[1], "// c") == Style.comment);
    assert(find(lines[2], "C_MAX") == Style.constant);
    assert(find(lines[2], `"q"`) == Style.str);
    assert(find(lines[2], "42") == Style.number);
}
