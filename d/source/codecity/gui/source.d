/// Source viewer: a light tokenizer (comments, strings, keywords, types, constants, numbers, annotations, Rust
/// lifetimes) with a GitHub-dark palette.
///
/// This is deliberately not a parser: it is a single left-to-right scan that only has to look right in the source
/// window, using one keyword list per language and a few lexical rules shared by Java, TypeScript and Rust. It does
/// not use the analysis parsers, so it also copes with files that failed to parse. The output is one `Line` per source line,
/// each a list of styled tokens that together reproduce the line's text (a carriage return is dropped when it would
/// start a token), so the viewer can draw the tokens side by side and number the lines.
module codecity.gui.source;

import codecity.model : Language;
import std.algorithm : canFind;
import std.uni : isAlpha, isAlphaNum, isNumber, isUpper, toUpper;
import std.conv : to;

/// Token classes. `annotation` also covers Rust attributes (`#[...]`), TypeScript decorators and Rust lifetimes.
enum Style { plain, keyword, type, constant, str, comment, number, annotation }

/// `0xRRGGBB` colour of a token class (GitHub's dark theme).
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

/// One source line as consecutive tokens; an empty array is an empty line.
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
// Rust's primitive types are listed as keywords so that they stand out like Java's `int`.
private immutable string[] rustKeywords = [
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for",
    "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static",
    "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "i8", "i16", "i32", "i64", "i128",
    "isize", "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64", "bool", "char", "str",
];

/// Splits `code` into lines of styled tokens.
///
/// The scan works on code points (`dchar`) so that identifiers with non-ASCII letters are classified correctly. At
/// each position the first matching rule wins, in this order: line comment, block comment, text block / triple-quoted
/// string, Rust lifetime, quoted string, annotation / attribute, word (keyword, CONSTANT, Type or plain identifier),
/// number, anything else (plain). Runs of plain text are collected in `plain` and emitted as one token by `flush`
/// whenever a styled token follows, which keeps the token count low.
///
/// Block comments, `"""` strings and TypeScript template literals may span lines: `inMode` / `mode` / `close` carry
/// that state over each newline, and every line inside them is emitted as one token of that style up to the closing
/// delimiter. Ordinary quoted strings and `#[` attributes never span lines: they end at the line break at the latest
/// (an escape is skipped as two characters, but never the line break). No token contains a newline, so there is
/// always exactly one `Line` per source line. Lifetimes are only recognised in Rust code, so TypeScript's `'react'`
/// is a string.
///
/// Delimiters are only looked for between tokens, so `//` inside a string and a quote inside a comment are handled
/// correctly. Known simplification: nested Rust block comments end at the first `*/`.
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
    string close;
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
    // identifier characters; `$` is legal in Java and TypeScript names
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
            // inside a multi-line block comment, text block or template literal: take the rest of this line, or up
            // to and including the closing delimiter if it is on this line (in literals, `\x` is stepped over as a
            // pair, but never a backslash and the line break)
            size_t j = i;
            while (j < n && chars[j] != '\n' && !starts(j, close))
                j += mode == Style.str && chars[j] == '\\' && j + 1 < n && chars[j + 1] != '\n' ? 2 : 1;
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
            close = "*/";
            line ~= Token("/*", Style.comment);
            i += 2;
        }
        else if (starts(i, `"""`))
        {
            flush();
            inMode = true;
            mode = Style.str;
            close = `"""`;
            line ~= Token(`"""`, Style.str);
            i += 3;
        }
        else if (c == '`')
        {
            flush();
            inMode = true;
            mode = Style.str;
            close = "`";
            line ~= Token("`", Style.str);
            i++;
        }
        else if (language == Language.rust && c == '\'' && i + 2 < n && (isAlpha(chars[i + 1]) || chars[i + 1] == '_') && chars[i + 2] != '\'')
        {
            // a Rust lifetime such as 'a or 'static, not a character literal
            flush();
            size_t j = i + 1;
            while (ident(j)) j++;
            line ~= Token(chars[i .. j].to!string, Style.annotation);
            i = j;
        }
        else if (c == '"' || c == '\'')
        {
            // a string or character literal up to the matching quote on the same line, or up to the line break if it
            // is not closed; `\x` escapes are stepped over as a pair so that `\"` does not end the string
            flush();
            size_t j = i + 1;
            while (j < n && chars[j] != c && chars[j] != '\n') j += chars[j] == '\\' && j + 1 < n && chars[j + 1] != '\n' ? 2 : 1;
            if (j < n && chars[j] == c) j++;
            line ~= Token(chars[i .. j].to!string, Style.str);
            i = j;
        }
        // Java annotations / TypeScript decorators (`@Override`, `@foo.bar`) and Rust attributes (`#[derive(..)]`,
        // up to the first `]` on the line, or the end of the line)
        else if ((c == '@' || c == '#') && i + 1 < n && (isAlpha(chars[i + 1]) || chars[i + 1] == '['))
        {
            flush();
            size_t j = i + 1;
            if (chars[j] == '[')
            {
                while (j < n && chars[j] != ']' && chars[j] != '\n') j++;
                if (j < n && chars[j] == ']') j++;
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
            // keyword; else ALL_CAPS (at least two characters, starting with a letter) is a constant; else a
            // Capitalised word is taken to be a type name; anything else stays plain
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
            // greedy: also takes hex digits, suffixes (`10L`, `1u8`), separators (`1_000`) and exponents without sign
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
    // the last line has no newline after it (and is an empty line if the code ends with one)
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

unittest
{
    // unterminated literals and attributes, a backslash before the line break and a multi-line template literal keep
    // one Line per source line, each reproducing its text
    import std.array : split;
    const code = "let a = \"open\n#[derive(X\nlet b = \"x\\\nconst T = `a\nb` + 'react';\nC_MAX";
    auto lines = highlight(code, Language.typescript);
    auto texts = code.split("\n");
    assert(lines.length == texts.length);
    foreach (k, l; lines)
    {
        string joined;
        foreach (tok; l) joined ~= tok.text;
        assert(joined == texts[k], joined);
    }
    Style find(Line l, string t) { foreach (tok; l) if (tok.text == t) return tok.style; assert(0, t); }
    assert(find(lines[4], "b`") == Style.str);
    assert(find(lines[4], "'react'") == Style.str);
    assert(find(lines[5], "C_MAX") == Style.constant);
}
