/// A dependency-free lexer and error-tolerant parser for TypeScript and TSX, producing the small syntax tree the
/// TypeScript analyser (`codecity.analysis.typescript`) works on. A port of the Rust edition's `typescript_parse.rs`;
/// both produce the same tokens, nodes and flags for every input, so the editions report identical metrics.
///
/// It extracts everything the analyser looks at: which declarations a file has, their names and line spans, class and
/// interface members, parameters, decision points and the identifiers that may refer to other declarations. It is not a
/// validator: it accepts a superset of TypeScript and never fails, it only notes whether it had to recover from a
/// syntax error (`Tree.hasError`). It replaced tree-sitter-typescript 0.23 and reproduces that grammar's results for
/// all of the above, so the analysis did not change; the snake_case construct names in the comments
/// (`method_definition`, …) are that grammar's.
///
/// Overview:
/// $(UL
/// $(LI The $(B lexer) (`Lexer`) turns source bytes into `Token`s on demand. JavaScript's lexical grammar depends on
///   the parser's state (is `/` a division or a regular expression? is `}` the end of a block or of a template
///   substitution? is `>>` one operator or two closing type-argument brackets? is `it's` JSX text or a string?), so
///   the lexer only produces the $(I default) reading of a token, and the parser asks for a $(I rescan) in another
///   mode (`Mode`) where it knows better, exactly like the TypeScript compiler's scanner does.)
/// $(LI The $(B parser) (`Parser`) is a hand-written recursive-descent parser over that token stream, following the
///   TypeScript grammar (statements, declarations, expressions with precedence climbing, types, JSX). Its output is
///   twofold:
///   1. A flat tree of `Node`s: only the constructs the analyser needs get a node kind of their own (`NodeKind`);
///      everything else is a generic `expr`, `stmt` or `type` node, or no node at all (most of a type, punctuation).
///      Every node covers a contiguous range of tokens, so its text and its line span follow from the tokens.
///   2. $(B Token flags) (`REF`, `DECISION`): the parser marks the identifier tokens that are names in expression,
///      pattern or type position (the names the analyser collects as references) and the tokens that are decision
///      points for cyclomatic complexity (`if`, `for`, `while`, `do`, `case`, `catch`, the `?` of a conditional
///      expression, and the `&&`, `||` and `??` operators). The analyser then only has to scan the token range of a
///      node for flags; it never needs expression nodes for that.)
/// )
///
/// Tree layout: nodes live in one array (`Tree.nodes`); the children of a node are a contiguous slice of a second array
/// (`Tree.kids`) given by `kidsStart`/`kidsLen`, in source order. The parser builds this bottom-up with a stack: a
/// `Marker` remembers the current token and stack height when a construct starts; nodes finished while parsing it are
/// pushed on the stack; `Parser.finish` moves the stack entries above the marker into `kids` as the new node's children
/// and pushes the new node. `precede` starts a marker $(I before) an already finished node (for left-recursive
/// constructs such as `a.b`, `a + b`, `f(x)`). Some children carry a `Field` tag so the analyser can tell, for example,
/// a variable's name from its value.
///
/// Grammar subset: the full statement, expression and type grammar of TypeScript 5 is parsed, including decorators,
/// `abstract` and `declare`, namespaces and `declare module`/`global`, enums, overloads, accessors, parameter
/// properties, generics (also generic calls `f<T>(x)` and arrow functions `<T,>(x: T) => x`), type predicates and
/// assertions, conditional, mapped and template literal types, `satisfies`, optional chaining, JSX (TSX only) and the
/// type assertion `<T>x` (plain TypeScript only). Not modelled: Flow syntax, legacy octal escapes, HTML comments
/// (`<!--`), and the distinction between strict and sloppy mode (contextual keywords such as `yield`, `await` and `let`
/// are treated as keywords wherever they can be one, regardless of mode).
///
/// Ambiguities: where TypeScript itself needs unbounded lookahead (arrow functions `(a, b) => …` versus
/// parenthesised expressions, generic calls `f<T>(x)` versus comparisons, function types `(a: T) => U` versus
/// parenthesised types), the parser $(I speculates): it takes a `Snapshot`, tries the more specific reading and
/// rolls back to the snapshot if that reading hits an error (see `Parser.bail`). A rolled-back attempt drops its
/// nodes and its tokens (they are lexed again, possibly in a different mode). Failed arrow-function attempts are
/// remembered by source offset, so nested parentheses cannot make parsing exponential.
///
/// Error recovery: a syntax error never stops the parse; it increments `Parser.errors` (the file is then reported as
/// having parse errors) and the parser carries on:
/// $(UL
/// $(LI an expected token that is missing is treated as present (nothing is consumed);)
/// $(LI a token that cannot start the expected construct is wrapped in an `error` node and skipped, so every loop
///   makes progress; statement lists additionally skip any token a statement could not consume;)
/// $(LI recursion is capped at `maxDepth` nested constructs: deeper input is skipped token by token, so adversarial
///   input (thousands of `(`) cannot overflow the stack.)
/// )
///
/// The parser never throws on any input: all indexing into the source and the token list stays within bounds by
/// construction, and growable arrays are managed by `Buf`, which never reallocates on truncation.
module codecity.analysis.typescript_parse;

/// Token flag: the token is a name in expression, pattern or type position that may refer to a declaration. Property
/// names (`a.b`, `{ b: 1 }`, class and interface members), shorthand properties (`{ b }`), labels, JSX attribute names
/// and `undefined` are not marked.
enum ubyte REF = 1;
/// Token flag: the token adds one to cyclomatic complexity (see the module documentation).
enum ubyte DECISION = 2;
/// Token flag (internal): the lexer found the token malformed (unterminated string, comment, template or regex).
private enum ubyte BAD = 4;

/// Maximum nesting of statements, expressions, types and patterns. Real code stays far below this; deeper input is
/// treated as a syntax error. Chosen so that the deepest recursion fits comfortably into a 2 MiB thread stack.
enum uint maxDepth = 400;

/// "No node": what `Tree.field` and the parser's optional results return when there is none.
enum uint none = uint.max;

/// What a token is. Keywords and contextual keywords are `ident` tokens: the parser compares their text.
enum TokKind : ubyte
{
    /// Identifier or keyword, e.g. `foo`, `if`, `type`. JSX names in `jsxName` mode may contain `-`.
    ident,
    /// `#name` (private class member).
    privateName,
    number,
    /// `'…'` or `"…"` (in `jsxString` mode a JSX attribute string, which has no escapes).
    str,
    /// `/…/flags`, only produced by a rescan in `regex` mode.
    regex,
    /// A template literal without substitutions: `` `…` ``.
    template_,
    /// The start of a template literal up to its first substitution: `` `…${ ``.
    templateHead,
    /// The part between two substitutions: `}…${`, only produced by a rescan in `templateContinue` mode.
    templateMiddle,
    /// The part after the last substitution: `` }…` ``, only produced by a rescan in `templateContinue` mode.
    templateTail,
    /// Operator or punctuation, e.g. `{`, `=>`, `?.`. The default mode never joins `>` with what follows (see
    /// `greater` mode), so `Array<Array<T>>` closes with two `>` tokens.
    punct,
    /// Text between JSX tags, only produced in `jsxChild` mode.
    jsxText,
    /// A character that cannot start any token (e.g. a stray control character).
    invalid,
    /// End of input. The token list always ends with exactly one `eof`.
    eof,
}

/// One token: a kind, a byte range into the source and the 1-based lines it starts and ends on.
struct Token
{
    TokKind kind;
    /// Byte offsets into the source, `start .. end`.
    uint start;
    uint end;
    /// 1-based line of `start`, and of `end` (the number of `\n` before the offset, plus one).
    uint line;
    uint endLine;
    /// True when a line break (possibly inside a comment) separates this token from the previous one. Drives
    /// automatic semicolon insertion and the "no line break here" rules.
    bool nlBefore;
    /// `REF`, `DECISION` (set by the parser) and the internal `BAD` flag (set by the lexer).
    ubyte flags;
}

/// Lexing modes. `normal` is used unless the parser rescans the current token in another mode.
enum Mode : ubyte
{
    normal,
    /// The current `/` or `/=` starts a regular expression literal.
    regex,
    /// The current `}` ends a template substitution: lex the template text that follows.
    templateContinue,
    /// The current `>` may be the first character of `>=`, `>>`, `>>=`, `>>>` or `>>>=`.
    greater,
    /// Inside a JSX element's children: text up to the next `{` or `<` (no whitespace or comment skipping).
    jsxChild,
    /// A JSX tag or attribute name, which may contain `-`.
    jsxName,
    /// A JSX attribute string: quoted, no escape sequences.
    jsxString,
}

/// Punctuators of the default mode, longest first so the first match is the longest one. `>` is deliberately only
/// a single character here (see `Mode.greater`); `?.` is handled separately (it must not be followed by a digit).
/// The last six (`?`, `:`, `=`, `@`, `.`, `#`) are the Rust edition's `PUNCTUATORS_2`, tried after the others.
private immutable string[] punctuators = [
    "...", "===", "!==", "**=", "<<=", "&&=", "||=", "??=", "=>", "==", "!=", "<=", "+=", "-=", "*=", "/=", "%=",
    "&=", "|=", "^=", "&&", "||", "??", "++", "--", "**", "<<", "{", "}", "(", ")", "[", "]", ";", ",", "<", ">",
    "+", "-", "*", "/", "%", "&", "|", "^", "!", "~",
    "?", ":", "=", "@", ".", "#",
];

/// What a `>` can be in `Mode.greater`, longest first.
private immutable string[] greaterPunctuators = [">>>=", ">>>", ">>=", ">>", ">="];

private bool isIdStart(ubyte b) pure nothrow @nogc @safe
{
    return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || b == '_' || b == '$' || b >= 0x80;
}

private bool isDigit(ubyte b) pure nothrow @nogc @safe
{
    return b >= '0' && b <= '9';
}

private bool isIdPart(ubyte b) pure nothrow @nogc @safe
{
    return isIdStart(b) || isDigit(b);
}

private bool isAsciiAlnum(ubyte b) pure nothrow @nogc @safe
{
    return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || isDigit(b);
}

private bool isHexDigit(ubyte b) pure nothrow @nogc @safe
{
    return isDigit(b) || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
}

/// The token producer. Stateless apart from the source: every call lexes one token starting at a given offset, which
/// makes rescanning a token in another mode trivial.
struct Lexer
{
    private immutable(ubyte)[] src;
    /// Byte offset of the start of every line (`0`, then one past every `\n`), for line numbers.
    private uint[] lineStarts;

    this(string code) pure nothrow @safe
    {
        src = cast(immutable(ubyte)[]) code;
        size_t count = 1;
        foreach (b; src) if (b == '\n') count++;
        lineStarts = new uint[count];
        size_t k = 1;
        foreach (i, b; src)
            if (b == '\n') lineStarts[k++] = cast(uint)(i + 1);
    }

    /// 1-based line of a byte offset: one plus the number of `\n` before it (the number of line starts at or before
    /// the offset, found by binary search).
    uint lineOf(uint offset) const pure nothrow @nogc @safe
    {
        size_t lo = 0, hi = lineStarts.length;
        while (lo < hi)
        {
            const mid = (lo + hi) / 2;
            if (lineStarts[mid] <= offset) lo = mid + 1;
            else hi = mid;
        }
        return cast(uint) lo;
    }

    /// The byte at `i`, or 0 past the end.
    private ubyte at(size_t i) const pure nothrow @nogc @safe
    {
        return i < src.length ? src[i] : 0;
    }

    /// Does the source contain `p` at offset `i`?
    private bool has(size_t i, string p) const pure nothrow @nogc @safe
    {
        return i + p.length <= src.length && src[i .. i + p.length] == cast(immutable(ubyte)[]) p;
    }

    /// Length of a non-ASCII whitespace character starting at `i` (U+00A0, U+1680, U+2000–U+200B, U+2028, U+2029,
    /// U+202F, U+205F, U+2060, U+3000, U+FEFF), or 0. Such characters separate tokens; any other non-ASCII character is
    /// part of an identifier.
    private size_t unicodeSpace(size_t i) const pure nothrow @nogc @safe
    {
        const a = at(i), b = at(i + 1), c = at(i + 2);
        switch (a)
        {
        case 0xC2: return b == 0xA0 ? 2 : 0;
        case 0xE1: return b == 0x9A && c == 0x80 ? 3 : 0;
        case 0xE2:
            if (b == 0x80 && c >= 0x80 && c <= 0x8B) return 3;
            if (b == 0x80 && (c == 0xA8 || c == 0xA9 || c == 0xAF)) return 3;
            if (b == 0x81 && (c == 0x9F || c == 0xA0)) return 3;
            return 0;
        case 0xE3: return b == 0x80 && c == 0x80 ? 3 : 0;
        case 0xEF: return b == 0xBB && c == 0xBF ? 3 : 0;
        default: return 0;
        }
    }

    /// Skips whitespace and comments from `i`. Returns the new offset; `nl` tells whether a line break was crossed,
    /// `badComment` whether a block comment was left unterminated.
    private size_t skipTrivia(size_t i, out bool nl, out bool badComment) const pure nothrow @nogc @safe
    {
        const n = src.length;
        // A hashbang line (`#!/usr/bin/env node`) is only allowed at the very start of the file.
        if (i == 0 && at(0) == '#' && at(1) == '!')
            while (i < n && src[i] != '\n') i++;
        while (i < n)
        {
            const b = src[i];
            if (b == ' ' || b == '\t' || b == '\r' || b == 0x0B || b == 0x0C) i++;
            else if (b == '\n')
            {
                nl = true;
                i++;
            }
            else if (b == '/' && at(i + 1) == '/')
            {
                while (i < n && src[i] != '\n') i++;
            }
            else if (b == '/' && at(i + 1) == '*')
            {
                i += 2;
                while (true)
                {
                    if (i >= n)
                    {
                        badComment = true;
                        return n;
                    }
                    if (src[i] == '*' && at(i + 1) == '/')
                    {
                        i += 2;
                        break;
                    }
                    if (src[i] == '\n') nl = true;
                    i++;
                }
            }
            else if (b >= 0x80)
            {
                const len = unicodeSpace(i);
                if (len == 0) break;
                // U+2028 and U+2029 are line terminators.
                if (len == 3 && b == 0xE2 && at(i + 1) == 0x80 && (at(i + 2) == 0xA8 || at(i + 2) == 0xA9)) nl = true;
                i += len;
            }
            else break;
        }
        return i;
    }

    private Token make(TokKind kind, size_t start, size_t end, bool nlBefore, bool bad) const pure nothrow @nogc @safe
    {
        const s = cast(uint) start, e = cast(uint) end;
        return Token(kind, s, e, lineOf(s), lineOf(e), nlBefore, bad ? BAD : 0);
    }

    /// Lexes one token starting at byte offset `from` (the end of the previous token) in the given mode.
    Token lex(size_t from, Mode mode) const pure nothrow @nogc @safe
    {
        const n = src.length;
        if (from > n) from = n;
        if (mode == Mode.jsxChild)
        {
            // JSX text keeps its whitespace and has no comments, so no trivia is skipped.
            const b = at(from);
            if (from >= n) return make(TokKind.eof, n, n, false, false);
            if (b == '{' || b == '<') return make(TokKind.punct, from, from + 1, false, false);
            size_t i = from;
            while (i < n && src[i] != '{' && src[i] != '<') i++;
            return make(TokKind.jsxText, from, i, false, false);
        }
        bool nl, badComment;
        const i = skipTrivia(from, nl, badComment);
        if (i >= n) return make(TokKind.eof, n, n, nl, badComment);
        const b = src[i];
        switch (mode)
        {
        case Mode.regex:
            if (b == '/') return lexRegex(i, nl);
            break;
        case Mode.templateContinue:
            if (b == '}')
            {
                bool head, bad;
                const end = scanTemplate(i + 1, head, bad);
                return make(head ? TokKind.templateMiddle : TokKind.templateTail, i, end, nl, bad);
            }
            break;
        case Mode.greater:
            if (b == '>')
            {
                size_t len = 1;
                foreach (p; greaterPunctuators)
                    if (has(i, p))
                    {
                        len = p.length;
                        break;
                    }
                return make(TokKind.punct, i, i + len, nl, false);
            }
            break;
        case Mode.jsxName:
            if (isIdStart(b))
            {
                size_t j = i;
                while (j < n && (isIdPart(src[j]) || src[j] == '-') && unicodeSpace(j) == 0) j++;
                return make(TokKind.ident, i, j, nl, false);
            }
            break;
        case Mode.jsxString:
            if (b == '"' || b == '\'')
            {
                size_t j = i + 1;
                while (j < n && src[j] != b) j++;
                const bad = j >= n;
                return make(TokKind.str, i, j + 1 < n ? j + 1 : n, nl, bad);
            }
            break;
        default:
            break;
        }
        return lexNormal(i, nl, badComment);
    }

    /// The default reading of the token at `i` (trivia already skipped).
    private Token lexNormal(size_t i, bool nl, bool badComment) const pure nothrow @nogc @safe
    {
        const n = src.length;
        const b = src[i];
        // identifiers and keywords (including `\uXXXX` escapes)
        if ((isIdStart(b) && unicodeSpace(i) == 0) || (b == '\\' && at(i + 1) == 'u'))
            return make(TokKind.ident, i, scanIdent(i), nl, badComment);
        if (isDigit(b) || (b == '.' && isDigit(at(i + 1))))
            return make(TokKind.number, i, scanNumber(i), nl, badComment);
        if (b == '"' || b == '\'')
        {
            size_t j = i + 1;
            bool bad = true;
            while (j < n)
            {
                const c = src[j];
                if (c == b)
                {
                    j++;
                    bad = false;
                    break;
                }
                if (c == '\n') break;
                if (c == '\\')
                {
                    // `\` + CR LF is a line continuation of two bytes after the backslash
                    j += at(j + 1) == '\r' && at(j + 2) == '\n' ? 3 : 2;
                    continue;
                }
                j++;
            }
            return make(TokKind.str, i, j < n ? j : n, nl, bad || badComment);
        }
        if (b == '`')
        {
            bool head, bad;
            const end = scanTemplate(i + 1, head, bad);
            return make(head ? TokKind.templateHead : TokKind.template_, i, end, nl, bad || badComment);
        }
        if (b == '#' && (isIdStart(at(i + 1)) || at(i + 1) == '\\'))
            return make(TokKind.privateName, i, scanIdent(i + 1), nl, badComment);
        if (b == '?' && at(i + 1) == '.' && !isDigit(at(i + 2)))
            return make(TokKind.punct, i, i + 2, nl, badComment);
        foreach (p; punctuators)
            if (has(i, p)) return make(TokKind.punct, i, i + p.length, nl, badComment);
        // Anything else (a control character, a lone backslash): one byte, or a whole UTF-8 sequence.
        size_t j = i + 1;
        while (j < n && (src[j] & 0xC0) == 0x80) j++;
        return make(TokKind.invalid, i, j, nl, true);
    }

    /// End of an identifier starting at `i`: identifier characters and `\u` escapes.
    private size_t scanIdent(size_t i) const pure nothrow @nogc @safe
    {
        const n = src.length;
        while (i < n)
        {
            const c = src[i];
            if (c == '\\' && at(i + 1) == 'u')
            {
                i += 2;
                if (at(i) == '{')
                {
                    while (i < n && src[i] != '}') i++;
                    i = i + 1 < n ? i + 1 : n;
                }
                else
                {
                    size_t k = 0;
                    while (k < 4 && i < n && isHexDigit(src[i]))
                    {
                        i++;
                        k++;
                    }
                }
            }
            else if (isIdPart(c) && unicodeSpace(i) == 0) i++;
            else break;
        }
        return i;
    }

    /// End of a numeric literal starting at `i`: digits, letters (hex digits, prefixes, `e`, `n`), `_` and `.`, and
    /// a sign directly after an exponent `e` of a decimal number.
    private size_t scanNumber(size_t i) const pure nothrow @nogc @safe
    {
        const n = src.length;
        const hex = at(i) == '0' && (at(i + 1) == 'x' || at(i + 1) == 'X');
        bool dot;
        while (i < n)
        {
            const c = src[i];
            if (c == '.')
            {
                // `1..toString()`: only one dot belongs to the number
                if (dot || hex) break;
                dot = true;
                i++;
            }
            else if ((c == 'e' || c == 'E') && !hex && (at(i + 1) == '+' || at(i + 1) == '-')) i += 2;
            else if (isAsciiAlnum(c) || c == '_') i++;
            else break;
        }
        return i;
    }

    /// Scans template text starting at `i` (just after `` ` `` or `}`) up to the closing `` ` `` or the next `${`.
    /// Returns the end offset; `head` tells whether it stopped at `${` (a head or middle part), `bad` whether input
    /// ended first.
    private size_t scanTemplate(size_t i, out bool head, out bool bad) const pure nothrow @nogc @safe
    {
        const n = src.length;
        while (i < n)
        {
            const c = src[i];
            if (c == '`') return i + 1;
            if (c == '\\') i += 2;
            else if (c == '$' && at(i + 1) == '{')
            {
                head = true;
                return i + 2;
            }
            else i++;
        }
        bad = true;
        return n;
    }

    /// A regular expression literal at `i`: up to the closing `/` outside a character class, then the flags.
    private Token lexRegex(size_t i, bool nl) const pure nothrow @nogc @safe
    {
        const n = src.length;
        size_t j = i + 1;
        bool class_, bad = true;
        while (j < n)
        {
            const c = src[j];
            if (c == '\\')
            {
                if (at(j + 1) == '\n') break;
                j += 2;
                continue;
            }
            if (c == '\n') break;
            if (c == '[') class_ = true;
            else if (c == ']') class_ = false;
            else if (c == '/' && !class_)
            {
                j++;
                bad = false;
                break;
            }
            j++;
        }
        if (j > n) j = n;
        size_t k = j;
        if (!bad)
            while (k < n && isIdPart(src[k]) && src[k] < 0x80) k++;
        return make(TokKind.regex, i, k, nl, bad);
    }
}

// ------------------------------------------------------------------------------------------------------------ tree

/// Node kinds. Most correspond to one grammar construct (whose grammar name the comments give); constructs the analyser
/// never distinguishes share `stmt`, `expr` or `type`.
enum NodeKind : ubyte
{
    /// The whole file; children are statements (`program`).
    program,
    /// `{ statements }`: a block statement, function body or namespace body (`statement_block`).
    block,
    /// `expr;` (`expression_statement`); its first child is the expression.
    exprStmt,
    /// Any other statement (`if`, `for`, `return`, …), labelled or empty statement.
    stmt,
    /// `import … from "m";` (`import_statement`). Children: `importDefault`, `importNamespace`, `importSpecifier`,
    /// and the module specifier string with field `source` (absent for `import x = require("m")`).
    import_,
    /// `D` in `import D from "m"`.
    importDefault,
    /// `* as N` in `import * as N from "m"`; its child `name` is `N`.
    importNamespace,
    /// `a` or `a as b` inside `import { … }`; children `name` (field `name`) and optionally `name` (field `alias_`).
    importSpecifier,
    /// `import A = B.C;` (`import_alias`).
    importAlias,
    /// `export …` without `default` (`export_statement`): the exported declaration has field `decl`; re-exports
    /// (`export { a } from "b"`, `export * from "c"`) and `export = x` have no `decl` child.
    export_,
    /// `export default …`: a declaration (field `decl`) or an expression (field `value`).
    exportDefault,
    /// `declare …` (`ambient_declaration`); its first child is the declaration (or a `block` for `declare global`).
    ambient,
    /// `var`/`let`/`const` declaration (`variable_declaration`/`lexical_declaration`); children are `declarator`s.
    varDecl,
    /// `name: T = value` (`variable_declarator`): child `pattern` (field `name`), optional value (field `value`).
    declarator,
    /// A binding pattern: an identifier or a destructuring pattern, as a declarator's name.
    pattern,
    /// `function f() {}` (`function_declaration`), also `async`.
    functionDecl,
    /// `function* f() {}` (`generator_function_declaration`).
    generatorDecl,
    /// A function overload or ambient function without body (`function_signature`).
    functionSig,
    /// `class C {}` (`class_declaration`). Children: decorators, `name` (field `name`), heritage, `classBody`.
    classDecl,
    /// `abstract class C {}` (`abstract_class_declaration`).
    abstractClassDecl,
    /// A class expression (`class`), e.g. `const C = class {}` or `export default class {}`.
    classExpr,
    /// `{ members }` of a class (`class_body`).
    classBody,
    /// `@expr` (`decorator`).
    decorator,
    /// A method, constructor or accessor with a body (`method_definition`). Starts at its modifiers; decorators before
    /// it are separate siblings in the class body.
    methodDef,
    /// A method without body: an overload in a class, or a method in an interface or object type
    /// (`method_signature`; `abstract_method_signature` for abstract ones).
    methodSig,
    /// A class field (`public_field_definition`), including its decorators. Optional value with field `value`.
    fieldDef,
    /// `[key: K]: V` or a mapped type clause (`index_signature`).
    indexSig,
    /// `static { … }` (`class_static_block`).
    staticBlock,
    /// `interface I {}` (`interface_declaration`); body is an `objectType` (field `body_`).
    interface_,
    /// `{ members }` as a type: object literal type, mapped type or interface body (`object_type`).
    objectType,
    /// `name?: T` in an object type (`property_signature`).
    propertySig,
    /// `(x): T` in an object type (`call_signature`).
    callSig,
    /// `new (x): T` in an object type (`construct_signature`).
    constructSig,
    /// `enum E {}` (`enum_declaration`); its `enumBody` has one `enumMember` per member.
    enum_,
    enumBody,
    enumMember,
    /// `type T = …;` (`type_alias_declaration`); the aliased type has field `value`.
    typeAlias,
    /// `namespace N {}`, `module M {}`, `declare module "m" {}` (`internal_module`/`module`): `name` (field `name`)
    /// and an optional `block` (field `body_`).
    module_,
    /// `( parameters )` (`formal_parameters`), also the single unparenthesised parameter of `x => …`.
    params,
    /// One parameter (`required_parameter`/`optional_parameter`).
    param,
    /// A constructor parameter with an accessibility modifier (`private x: T`), which also declares a field.
    paramProperty,
    /// `=>` function (`arrow_function`).
    arrow,
    /// `function () {}` as an expression (`function_expression`).
    functionExpr,
    /// `function* () {}` as an expression (`generator_function`).
    generatorExpr,
    /// `f(args)`, ``tag`…` `` (`call_expression`): the callee is the first child, the arguments have field `args`.
    call,
    /// `( args )` of a call (`arguments`); children are the argument expressions.
    args,
    /// An identifier used as an expression (`identifier`).
    ident,
    /// A string literal (`string`).
    str,
    /// A template literal (`template_string`).
    template_,
    /// Any other expression.
    expr,
    /// A type that is not a plain object type (union, reference, function type, …).
    type,
    /// A name: of a declaration, member, module or import binding. Covers one token, or several for `A.B.C` and
    /// computed member names.
    name,
    /// Tokens skipped during error recovery.
    error,
}

/// Tags on children: their role in the parent (a declaration's name, a variable's value, a function's body, …).
enum Field : ubyte
{
    none,
    name,
    alias_,
    value,
    body_,
    params,
    args,
    source,
    decl,
}

/// One node: a kind, a field tag (set by the parent), its token range and its children.
struct Node
{
    NodeKind kind;
    Field field;
    /// Token range `first .. end` (end exclusive). Empty only for nodes created at an error.
    uint first;
    uint end;
    /// The children are `kids[kidsStart .. kidsStart + kidsLen]`.
    uint kidsStart;
    uint kidsLen;
}

/// The parse result: tokens (with flags), nodes and the root node.
struct Tree
{
    string code;
    Token[] tokens;
    Node[] nodes;
    uint[] kids;
    uint root;
    /// True when the parser had to recover from at least one syntax error.
    bool hasError;
    /// Byte offset of the token where the first syntax error was noticed (for diagnostics; 0 without errors).
    uint firstError;

    NodeKind kind(uint n) const pure nothrow @nogc @safe
    {
        return nodes[n].kind;
    }

    /// The children of `n` in source order.
    const(uint)[] children(uint n) const pure nothrow @nogc @safe
    {
        const node = nodes[n];
        return kids[node.kidsStart .. node.kidsStart + node.kidsLen];
    }

    /// The first child of `n` tagged with `f`, or `none`.
    uint field(uint n, Field f) const pure nothrow @nogc @safe
    {
        foreach (c; children(n))
            if (nodes[c].field == f) return c;
        return none;
    }

    /// The source text of a node: from the start of its first token to the end of its last ("" if empty).
    string text(uint n) const pure nothrow @nogc @safe
    {
        const node = nodes[n];
        if (node.end <= node.first) return "";
        const a = tokens[node.first].start, b = tokens[node.end - 1].end;
        return a <= b && b <= code.length ? code[a .. b] : "";
    }

    /// The source text of token `t`.
    string tokenText(size_t t) const pure nothrow @nogc @safe
    {
        const tok = tokens[t];
        return tok.start <= tok.end && tok.end <= code.length ? code[tok.start .. tok.end] : "";
    }

    /// 1-based line the node starts on.
    uint firstLine(uint n) const pure nothrow @nogc @safe
    {
        const first = nodes[n].first;
        return first < tokens.length ? tokens[first].line : 1;
    }

    /// 1-based line the node ends on (inclusive).
    uint lastLine(uint n) const pure nothrow @nogc @safe
    {
        const node = nodes[n];
        if (node.end <= node.first) return firstLine(n);
        return tokens[node.end - 1].endLine;
    }

    /// The first token index a node covers (see `tokenEnd`).
    uint tokenStart(uint n) const pure nothrow @nogc @safe
    {
        return nodes[n].first;
    }

    /// One past the last token index a node covers (never before `tokenStart`).
    uint tokenEnd(uint n) const pure nothrow @nogc @safe
    {
        const node = nodes[n];
        return node.end > node.first ? node.end : node.first;
    }
}

/// Parses `code` as TypeScript (`tsx == false`) or TSX.
Tree parse(string code, bool tsx)
{
    Parser p;
    p.lx = Lexer(code);
    p.code = code;
    p.toks.reserve(code.length / 4 + 16);
    p.nodes.reserve(code.length / 8 + 16);
    p.tsx = tsx;
    const root = p.parseProgram();
    // Make sure the token list is complete (it ends with the Eof token), so ranges are always valid.
    p.ensure(0);
    Tree t;
    t.code = code;
    t.hasError = p.errors > 0;
    t.firstError = p.hasFirstError ? p.firstError : 0;
    t.tokens = p.toks[];
    t.nodes = p.nodes[];
    t.kids = p.kids[];
    t.root = root;
    return t;
}

// ---------------------------------------------------------------------------------------------------------- parser

/// A growable array with an explicit length: truncating and pushing again reuses the storage (a plain D slice would
/// reallocate after shrinking, unless `assumeSafeAppend` is used everywhere).
private struct Buf(T)
{
    private T[] data;
    private size_t len;

    size_t length() const pure nothrow @nogc @safe
    {
        return len;
    }

    void reserve(size_t n) pure nothrow @safe
    {
        if (data.length < n) data.length = n;
    }

    void push(T v) pure nothrow @safe
    {
        if (len == data.length) data.length = data.length < 16 ? 16 : data.length * 2;
        data[len++] = v;
    }

    void truncate(size_t n) pure nothrow @nogc @safe
    {
        if (n < len) len = n;
    }

    ref T opIndex(size_t i) pure nothrow @nogc @safe
    {
        assert(i < len);
        return data[i];
    }

    /// The elements in use (a view of the storage, valid until the next push).
    T[] opSlice() pure nothrow @nogc @safe
    {
        return data[0 .. len];
    }

    T[] opSlice(size_t a, size_t b) pure nothrow @nogc @safe
    {
        assert(a <= b && b <= len);
        return data[a .. b];
    }

    ref T last() pure nothrow @nogc @safe
    {
        return data[len - 1];
    }
}

/// The start of a node under construction: its first token and the stack height (see the module documentation).
private struct Marker
{
    uint tok;
    uint stack;
}

/// Everything needed to roll back a failed speculative parse.
private struct Snapshot
{
    size_t pos;
    size_t nodes;
    size_t kids;
    size_t stack;
    uint errors;
}

/// Words that can never be an identifier in an expression (reserved words). Contextual keywords (`type`, `async`,
/// `of`, `get`, …) are not listed: they are identifiers wherever they are not used as keywords.
private bool isReserved(string s) pure nothrow @nogc @safe
{
    switch (s)
    {
    case "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum",
        "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null",
        "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with":
        return true;
    default:
        return false;
    }
}

/// Predefined type names such as `string` and `any` (plus the literal types `null`, `undefined`, `true`, `false` and
/// `this`): never references.
private bool isPredefinedType(string s) pure nothrow @nogc @safe
{
    switch (s)
    {
    case "any", "number", "boolean", "string", "symbol", "void", "unknown", "never", "object", "null", "undefined",
        "true", "false", "this", "unique":
        return true;
    default:
        return false;
    }
}

/// Class member modifiers.
private immutable string[] modifiers = [
    "public", "private", "protected", "static", "override", "readonly", "async", "abstract", "declare", "accessor",
];

/// Word lists for `Parser.atAnyKw` (module-level, so that no call allocates an array literal).
private immutable string[] varWords = ["var", "let", "const"];
private immutable string[] staticGetSet = ["static", "get", "set"];
private immutable string[] getSet = ["get", "set"];
private immutable string[] accessibility = ["public", "private", "protected"];
private immutable string[] readonlyOverride = ["readonly", "override"];
private immutable string[] asyncGetSet = ["async", "get", "set"];
private immutable string[] typeMemberModifiers = [
    "readonly", "public", "private", "protected", "static", "override", "get", "set", "async",
];
private immutable string[] typeParameterModifiers = ["const", "in", "out"];
/// Punctuators after a type operator keyword that start its operand (see `Parser.typeOperandFollows`).
private immutable string[] typeOperandStarts = ["(", "[", "{", "<", "-", "*"];

/// Assignment operators (`>>=` and `>>>=` only appear after a `Mode.greater` rescan).
private bool isAssignOp(string s) pure nothrow @nogc @safe
{
    switch (s)
    {
    case "=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=":
        return true;
    default:
        return false;
    }
}

/// The recursive-descent parser (see the module documentation). Used once per file through `parse`.
private struct Parser
{
    Lexer lx;
    string code;
    /// Tokens lexed so far. Tokens at and after `pos` are lookahead and may be re-lexed (see `rescan`).
    Buf!Token toks;
    /// Index of the current token.
    size_t pos;
    Buf!Node nodes;
    Buf!uint kids;
    /// Finished nodes that have no parent yet (see the module documentation).
    Buf!uint stack;
    /// Number of syntax errors so far (rolled back with failed speculation).
    uint errors;
    /// Nesting of speculative parses, and the error count when the innermost one started.
    uint spec;
    uint specBase;
    /// Current recursion depth (see `maxDepth`).
    uint depth;
    bool tsx;
    /// Source offsets of `(`/`<` tokens where an arrow function was tried and failed.
    bool[uint] arrowFailed;
    /// Set once `maxDepth` has been reached anywhere in the file. From then on the parser no longer speculates and
    /// takes the plain reading of every ambiguity (a comparison rather than type arguments, a parenthesised expression
    /// or type rather than an arrow function or function type). Input that nests past the cap at each of many
    /// speculation points (`a < a < a < …`) would otherwise cost up to `maxDepth` steps per token, each re-lexed
    /// after the rollback; real code never gets near the cap.
    bool depthCapped;
    /// Offset of the first error outside speculation (see `Tree.firstError`), valid when `hasFirstError`.
    uint firstError;
    bool hasFirstError;

    // ------------------------------------------------------------------ tokens

    /// Makes sure the token `k` positions ahead is lexed (or the list ends with Eof).
    void ensure(size_t k)
    {
        while (toks.length <= pos + k)
        {
            if (toks.length && toks.last.kind == TokKind.eof) return;
            const from = toks.length ? toks.last.end : 0;
            toks.push(lx.lex(from, Mode.normal));
        }
    }

    /// The token `k` positions ahead of the current one (Eof beyond the end).
    Token peek(size_t k)
    {
        ensure(k);
        const i = pos + k < toks.length - 1 ? pos + k : toks.length - 1;
        return toks[i];
    }

    Token cur()
    {
        return peek(0);
    }

    string textOf(const Token t) const pure nothrow @nogc @safe
    {
        return t.start <= t.end && t.end <= code.length ? code[t.start .. t.end] : "";
    }

    /// Is the token `k` ahead the punctuator `p`?
    bool nthP(size_t k, string p)
    {
        const t = peek(k);
        return t.kind == TokKind.punct && textOf(t) == p;
    }

    /// Is the token `k` ahead the word (keyword or identifier) `w`?
    bool nthKw(size_t k, string w)
    {
        const t = peek(k);
        return t.kind == TokKind.ident && textOf(t) == w;
    }

    bool atP(string p)
    {
        return nthP(0, p);
    }

    bool atKw(string w)
    {
        return nthKw(0, w);
    }

    TokKind nthKind(size_t k)
    {
        return peek(k).kind;
    }

    bool atEof()
    {
        return cur().kind == TokKind.eof;
    }

    /// Is the current token one of the given words?
    bool atAnyKw(const string[] words)
    {
        const t = cur();
        if (t.kind != TokKind.ident) return false;
        const s = textOf(t);
        foreach (w; words)
            if (s == w) return true;
        return false;
    }

    /// Consumes the current token (never the Eof) and returns its index.
    size_t bump()
    {
        const t = cur();
        if (t.kind == TokKind.eof) return pos;
        if (t.flags & BAD) error();
        pos++;
        return pos - 1;
    }

    /// Consumes the current token and sets `flag` on it.
    size_t bumpFlag(ubyte flag)
    {
        const i = bump();
        if (i < toks.length && toks[i].kind != TokKind.eof) toks[i].flags |= flag;
        return i;
    }

    bool eatP(string p)
    {
        if (!atP(p)) return false;
        bump();
        return true;
    }

    bool eatKw(string w)
    {
        if (!atKw(w)) return false;
        bump();
        return true;
    }

    /// Consumes `p` or records an error (the missing token is treated as present).
    void expectP(string p)
    {
        if (!eatP(p)) error();
    }

    /// Re-lexes the current token in another mode, discarding all lookahead.
    void rescan(Mode mode)
    {
        toks.truncate(pos);
        const from = toks.length ? toks.last.end : 0;
        toks.push(lx.lex(from, mode));
    }

    /// Statement terminator: `;`, or automatic semicolon insertion before `}`, at the end of input or after a line
    /// break.
    void semicolon()
    {
        if (eatP(";")) return;
        const t = cur();
        if (t.kind == TokKind.eof || t.nlBefore || atP("}")) return;
        error();
    }

    /// Can automatic semicolon insertion end a statement here (no `;` needed)?
    bool canEndStatement()
    {
        const t = cur();
        return t.kind == TokKind.eof || t.nlBefore || atP("}") || atP(";");
    }

    // ------------------------------------------------------------------ nodes, errors, speculation

    Marker mark()
    {
        return Marker(cast(uint) pos, cast(uint) stack.length);
    }

    /// A marker that starts at the already finished node `n`, which must be on top of the stack.
    Marker precede(uint n)
    {
        return Marker(nodes[n].first, cast(uint)(stack.length ? stack.length - 1 : 0));
    }

    /// Finishes a node that started at `m` and ends at the last consumed token; nodes finished since `m` become its
    /// children.
    uint finish(Marker m, NodeKind kind)
    {
        const from = m.stack < stack.length ? m.stack : stack.length;
        const kidsStart = cast(uint) kids.length;
        foreach (c; stack[from .. stack.length]) kids.push(c);
        const kidsLen = cast(uint)(stack.length - from);
        stack.truncate(from);
        const id = cast(uint) nodes.length;
        const end = cast(uint) pos > m.tok ? cast(uint) pos : m.tok;
        nodes.push(Node(kind, Field.none, m.tok, end, kidsStart, kidsLen));
        stack.push(id);
        return id;
    }

    void setField(uint n, Field f)
    {
        nodes[n].field = f;
    }

    void error()
    {
        errors++;
        if (spec == 0 && !hasFirstError)
        {
            hasFirstError = true;
            firstError = cur().start;
        }
    }

    /// True while speculating and the current attempt has already hit an error: loops stop early so that a failed
    /// attempt is abandoned quickly.
    bool bail() const pure nothrow @nogc @safe
    {
        return spec > 0 && errors > specBase;
    }

    /// Skips one token as an `error` node (used where nothing else applies, to guarantee progress).
    uint errorNode()
    {
        error();
        const m = mark();
        if (!bail()) bump();
        return finish(m, NodeKind.error);
    }

    Snapshot snapshot()
    {
        return Snapshot(pos, nodes.length, kids.length, stack.length, errors);
    }

    /// Rolls back to a snapshot. Tokens from the snapshot position on are dropped and lexed again later, as the
    /// failed attempt may have rescanned some of them in another mode or flagged them.
    void restore(Snapshot s)
    {
        pos = s.pos;
        toks.truncate(s.pos);
        nodes.truncate(s.nodes);
        kids.truncate(s.kids);
        stack.truncate(s.stack);
        errors = s.errors;
    }

    /// Starts a speculative parse; `base` receives what `endSpec` needs besides the snapshot.
    Snapshot beginSpec(out uint base)
    {
        const s = snapshot();
        base = specBase;
        spec++;
        specBase = errors;
        return s;
    }

    /// Ends a speculative parse: on failure (any error since it began) rolls back and returns false.
    bool endSpec(Snapshot s, uint base, bool ok)
    {
        ok = ok && errors == specBase;
        spec--;
        specBase = base;
        if (!ok) restore(s);
        return ok;
    }

    /// Enters a nested construct; false (after recording an error) when `maxDepth` is reached.
    bool enter()
    {
        if (depth >= maxDepth)
        {
            depthCapped = true;
            error();
            return false;
        }
        depth++;
        return true;
    }

    void leave()
    {
        depth--;
    }

    // ------------------------------------------------------------------ names

    /// Is the current token a word usable as a binding or reference name (any identifier except reserved words)?
    bool atIdentifier()
    {
        return nthIdentifier(0);
    }

    bool nthIdentifier(size_t k)
    {
        const t = peek(k);
        return t.kind == TokKind.ident && !isReserved(textOf(t));
    }

    /// Consumes an identifier that is a reference (flags it `REF` unless it is `undefined`).
    size_t bumpRef()
    {
        return atKw("undefined") ? bump() : bumpFlag(REF);
    }

    /// A declaration's name: a `name` node over one identifier, flagged as reference (a declared name counts like any
    /// other name). Records an error and creates nothing if there is no identifier.
    uint declName()
    {
        if (cur().kind != TokKind.ident)
        {
            error();
            return none;
        }
        const m = mark();
        bumpRef();
        const n = finish(m, NodeKind.name);
        setField(n, Field.name);
        return n;
    }

    /// Is the current token the start of a property name (identifier or keyword, string, number, `[`, `#name`)?
    bool atPropertyName()
    {
        return nthPropertyName(0);
    }

    bool nthPropertyName(size_t k)
    {
        const kind = nthKind(k);
        return kind == TokKind.ident || kind == TokKind.str || kind == TokKind.number || kind == TokKind.privateName
            || nthP(k, "[");
    }

    /// A property name of a class member, object literal property, interface member or enum member: a `name` node
    /// (field `name`). Plain names are not references; a computed name `[expr]` is parsed as an expression.
    uint propertyName()
    {
        const m = mark();
        if (atP("["))
        {
            bump();
            parseAssignment(false);
            expectP("]");
        }
        else if (atPropertyName()) bump();
        else error();
        const n = finish(m, NodeKind.name);
        setField(n, Field.name);
        return n;
    }

    // ------------------------------------------------------------------ statements

    uint parseProgram()
    {
        const m = mark();
        statementList(false);
        // a lexing error at the very end (unterminated block comment) is attached to the Eof token
        if (cur().flags & BAD) error();
        return finish(m, NodeKind.program);
    }

    /// Statements until the end of input, or until `}` when `inBlock`. Tokens that cannot start a statement are
    /// skipped (one at a time, as errors).
    void statementList(bool inBlock)
    {
        while (true)
        {
            if (atEof() || (inBlock && atP("}"))) return;
            const before = pos;
            parseStatement();
            if (pos == before) errorNode();
            if (bail()) return;
        }
    }

    /// `{ statements }` as a `block` node.
    uint parseBlock()
    {
        const m = mark();
        if (!enter()) return errorNode();
        expectP("{");
        statementList(true);
        expectP("}");
        leave();
        return finish(m, NodeKind.block);
    }

    uint parseStatement()
    {
        if (!enter()) return errorNode();
        const n = statement();
        leave();
        return n;
    }

    uint statement()
    {
        const t = cur();
        if (t.kind == TokKind.punct)
        {
            if (atP("{")) return parseBlock();
            if (atP(";"))
            {
                const m = mark();
                bump();
                return finish(m, NodeKind.stmt);
            }
            if (atP("@")) return decoratedStatement();
            return expressionStatement();
        }
        if (t.kind != TokKind.ident) return expressionStatement();
        const m = mark();
        const word = textOf(t);
        if (word == "import" && !nthP(1, "(") && !nthP(1, ".")) return importStatement();
        if (word == "export") return exportStatement(m);
        if (word == "var") return varDeclaration(m, true);
        if (word == "let" && (nthIdentifier(1) || nthP(1, "[") || nthP(1, "{"))) return varDeclaration(m, true);
        if (word == "const") return nthKw(1, "enum") ? enumDeclaration(m) : varDeclaration(m, true);
        switch (word)
        {
        case "if", "for", "while", "do", "switch", "try", "return", "throw", "break", "continue", "debugger", "with":
            return controlStatement(m);
        default:
            break;
        }
        const n = tryDeclaration(m);
        if (n != none) return n;
        // `label: statement`
        if (atIdentifier() && nthP(1, ":"))
        {
            bump(); // statement_identifier: not a reference
            bump();
            parseStatement();
            return finish(m, NodeKind.stmt);
        }
        return expressionStatement();
    }

    /// Declarations that may also follow `export` or `declare`: functions, classes, interfaces, type aliases,
    /// enums, namespaces, modules, `declare …` and `global { }`. `none` (nothing consumed) if the current tokens do
    /// not start one; `var`/`let`/`const` are handled by the caller.
    uint tryDeclaration(Marker m)
    {
        const t = cur();
        if (t.kind != TokKind.ident) return none;
        const nextSameLine = !peek(1).nlBefore;
        const word = textOf(t);
        if (word == "function") return functionDeclaration(m);
        if (word == "async" && nthKw(1, "function") && nextSameLine) return functionDeclaration(m);
        if (word == "class") return class_(m, NodeKind.classDecl);
        if (word == "abstract" && nthKw(1, "class") && nextSameLine)
        {
            bump();
            return class_(m, NodeKind.abstractClassDecl);
        }
        if (word == "interface" && nthKind(1) == TokKind.ident && nextSameLine) return interfaceDeclaration(m);
        if (word == "type" && nthKind(1) == TokKind.ident && nextSameLine) return typeAlias(m);
        if (word == "enum" && nthKind(1) == TokKind.ident) return enumDeclaration(m);
        if ((word == "namespace" || word == "module") && nextSameLine
            && (nthKind(1) == TokKind.ident || nthKind(1) == TokKind.str))
            return moduleDeclaration(m);
        if (word == "declare" && nextSameLine && nthIsDeclarationStart(1))
        {
            bump();
            const inner = mark();
            if (atKw("global"))
            {
                bump();
                if (atP("{")) parseBlock();
                else semicolon();
                finish(inner, NodeKind.stmt);
            }
            else if (atKw("module") && nthP(1, "."))
            {
                // `declare module.exports: T;`
                bump();
                bump();
                bump();
                if (eatP(":")) parseType();
                semicolon();
                finish(inner, NodeKind.stmt);
            }
            else if (atAnyKw(varWords) && !nthKw(1, "enum")) varDeclaration(inner, true);
            else if (tryDeclaration(inner) == none) error();
            return finish(m, NodeKind.ambient);
        }
        if (word == "global" && nthP(1, "{"))
        {
            bump();
            parseBlock();
            return finish(m, NodeKind.stmt);
        }
        return none;
    }

    /// Can the token `k` ahead start a declaration after `declare`?
    bool nthIsDeclarationStart(size_t k)
    {
        const t = peek(k);
        if (t.kind != TokKind.ident) return false;
        switch (textOf(t))
        {
        case "var", "let", "const", "function", "async", "class", "abstract", "enum", "interface", "type",
            "namespace", "module", "global":
            return true;
        default:
            return false;
        }
    }

    /// Decorators before a class declaration, `abstract class` or `export` statement.
    uint decoratedStatement()
    {
        const m = mark();
        while (atP("@")) decorator();
        if (atKw("export")) return exportStatement(m);
        if (atKw("abstract") && nthKw(1, "class"))
        {
            bump();
            return class_(m, NodeKind.abstractClassDecl);
        }
        if (atKw("class")) return class_(m, NodeKind.classDecl);
        error();
        return finish(m, NodeKind.stmt);
    }

    uint expressionStatement()
    {
        const m = mark();
        parseExpression(false);
        semicolon();
        return finish(m, NodeKind.exprStmt);
    }

    /// `var`/`let`/`const` declarations. `semi` is false inside a `for (…)` header, where `noIn` also applies.
    uint varDeclaration(Marker m, bool semi)
    {
        bump(); // var, let or const
        while (true)
        {
            declarator(!semi);
            if (bail() || !eatP(",")) break;
        }
        if (semi) semicolon();
        return finish(m, NodeKind.varDecl);
    }

    /// `pattern [!] [: type] [= value]`.
    uint declarator(bool noIn)
    {
        const m = mark();
        const pm = mark();
        bindingPattern();
        const name = finish(pm, NodeKind.pattern);
        setField(name, Field.name);
        eatP("!");
        if (eatP(":")) parseType();
        if (eatP("="))
        {
            const v = parseAssignment(noIn);
            setField(v, Field.value);
        }
        return finish(m, NodeKind.declarator);
    }

    /// `[async] function [*] name<T>(params): R { body }`, or a signature without body (overload, ambient).
    uint functionDeclaration(Marker m)
    {
        eatKw("async");
        bump(); // function
        const generator = eatP("*");
        if (cur().kind == TokKind.ident && !atP("(")) declName();
        else error();
        callSignature();
        if (atP("{"))
        {
            parseBlock();
            return finish(m, generator ? NodeKind.generatorDecl : NodeKind.functionDecl);
        }
        semicolon();
        return finish(m, NodeKind.functionSig);
    }

    /// `<T>(params): R`, the part of every function, method and signature between name and body. The parameter list
    /// gets field `params`.
    void callSignature()
    {
        if (atP("<")) typeParameters();
        parameters();
        if (eatP(":")) returnType();
    }

    /// A class declaration or expression; `m` may include decorators and `abstract`. The current token is `class`.
    uint class_(Marker m, NodeKind kind)
    {
        bump(); // class
        if (atIdentifier() && !atKw("implements")) declName();
        else if (kind != NodeKind.classExpr) error();
        if (atP("<")) typeParameters();
        if (eatKw("extends"))
        {
            while (true)
            {
                parseLhsExpression();
                if (atP("<")) typeArguments();
                if (bail() || !eatP(",")) break;
            }
        }
        if (eatKw("implements"))
        {
            while (true)
            {
                parseType();
                if (bail() || !eatP(",")) break;
            }
        }
        classBody();
        return finish(m, kind);
    }

    uint classBody()
    {
        const m = mark();
        expectP("{");
        while (!atP("}") && !atEof() && !bail())
        {
            const before = pos;
            classMember();
            if (pos == before) errorNode();
        }
        expectP("}");
        const n = finish(m, NodeKind.classBody);
        setField(n, Field.body_);
        return n;
    }

    /// Can a modifier at the current position really be a modifier, given the token after it? (TypeScript's
    /// `nextTokenCanFollowModifier`: a property name, `[`, `{`, `*`, `...` or `#name`, on the same line except after
    /// `static`, `get` and `set`.)
    bool modifierFollows()
    {
        const next = peek(1);
        const sameLineNeeded = !atAnyKw(staticGetSet);
        if (sameLineNeeded && next.nlBefore) return false;
        return nthPropertyName(1) || nthP(1, "{") || nthP(1, "*") || nthP(1, "...");
    }

    /// One class member. Decorators belong to a field (they start its span) but are separate siblings before a method.
    void classMember()
    {
        if (eatP(";")) return;
        if (!enter())
        {
            errorNode();
            return;
        }
        const m = mark();
        while (atP("@")) decorator();
        if (atKw("static") && nthP(1, "{"))
        {
            bump();
            parseBlock();
            finish(m, NodeKind.staticBlock);
            leave();
            return;
        }
        const mm = mark();
        while ((atAnyKw(modifiers) || atAnyKw(getSet)) && modifierFollows()) bump();
        eatP("*");
        if (atP("[") && nthIdentifier(1) && nthP(2, ":"))
        {
            indexSignatureRest(mm);
            memberSeparator();
            leave();
            return;
        }
        propertyName();
        if (!eatP("?")) eatP("!");
        if (atP("(") || atP("<"))
        {
            callSignature();
            if (atP("{"))
            {
                parseBlock();
                finish(mm, NodeKind.methodDef);
                eatP(";");
            }
            else
            {
                finish(mm, NodeKind.methodSig);
                memberSeparator();
            }
        }
        else
        {
            if (eatP(":")) parseType();
            if (eatP("="))
            {
                const v = parseAssignment(false);
                setField(v, Field.value);
            }
            finish(m, NodeKind.fieldDef);
            memberSeparator();
        }
        leave();
    }

    /// `;` or `,` after a class member, or automatic semicolon insertion.
    void memberSeparator()
    {
        if (eatP(";") || eatP(",")) return;
        if (!canEndStatement()) error();
    }

    /// The rest of an index signature `[key: K]: V` from the `[` (modifiers already consumed with `m`).
    uint indexSignatureRest(Marker m)
    {
        bump(); // [
        bumpRef(); // the key name counts as a reference
        bump(); // :
        parseType();
        expectP("]");
        if (eatP(":")) parseType();
        return finish(m, NodeKind.indexSig);
    }

    uint decorator()
    {
        const m = mark();
        bump(); // @
        if (atP("(")) parsePrimary();
        else if (cur().kind == TokKind.ident)
        {
            bumpRef();
            while (atP("."))
            {
                bump();
                const k = cur().kind;
                if (k == TokKind.ident || k == TokKind.privateName) bump();
                else
                {
                    error();
                    break;
                }
            }
            if (atP("<")) typeArguments();
            if (atP("(")) arguments();
        }
        else error();
        return finish(m, NodeKind.decorator);
    }

    uint interfaceDeclaration(Marker m)
    {
        bump(); // interface
        declName();
        if (atP("<")) typeParameters();
        if (eatKw("extends"))
        {
            while (true)
            {
                parseType();
                if (bail() || !eatP(",")) break;
            }
        }
        const body_ = objectType();
        setField(body_, Field.body_);
        return finish(m, NodeKind.interface_);
    }

    uint typeAlias(Marker m)
    {
        bump(); // type
        declName();
        if (atP("<")) typeParameters();
        expectP("=");
        const v = parseType();
        setField(v, Field.value);
        semicolon();
        return finish(m, NodeKind.typeAlias);
    }

    /// `[const] enum E { A, B = 1 }`.
    uint enumDeclaration(Marker m)
    {
        eatKw("const");
        bump(); // enum
        declName();
        const bm = mark();
        expectP("{");
        while (!atP("}") && !atEof() && !bail())
        {
            const before = pos;
            const em = mark();
            propertyName();
            if (eatP("=")) parseAssignment(false);
            finish(em, NodeKind.enumMember);
            if (!eatP(",")) break;
            if (pos == before) errorNode();
        }
        expectP("}");
        const body_ = finish(bm, NodeKind.enumBody);
        setField(body_, Field.body_);
        return finish(m, NodeKind.enum_);
    }

    /// `namespace A.B { }`, `module M { }`, `module "m" { }` or a body-less `declare module "m";`.
    uint moduleDeclaration(Marker m)
    {
        bump(); // namespace or module
        const nm = mark();
        if (cur().kind == TokKind.str) bump();
        else
        {
            bumpRef();
            while (atP(".") && nthKind(1) == TokKind.ident)
            {
                bump();
                bump(); // property_identifier
            }
        }
        const name = finish(nm, NodeKind.name);
        setField(name, Field.name);
        if (atP("{"))
        {
            const b = parseBlock();
            setField(b, Field.body_);
        }
        else semicolon();
        return finish(m, NodeKind.module_);
    }

    /// Is the token `k` ahead an identifier or a string?
    bool nthIdentOrString(size_t k)
    {
        const kind = nthKind(k);
        return kind == TokKind.ident || kind == TokKind.str;
    }

    /// `import …;` in all its forms (the current token is `import`).
    uint importStatement()
    {
        const m = mark();
        bump(); // import
        // `type`/`typeof` modifier, unless it is itself the default import (`import type from "m"`)
        if ((atKw("type") || atKw("typeof"))
            && (nthP(1, "{") || nthP(1, "*") || (nthKind(1) == TokKind.ident && !nthKw(1, "from"))))
            bump();
        if (cur().kind == TokKind.str)
        {
            const s = mark();
            bump();
            const src = finish(s, NodeKind.str);
            setField(src, Field.source);
            semicolon();
            return finish(m, NodeKind.import_);
        }
        if (cur().kind == TokKind.ident && nthP(1, "="))
        {
            // `import x = require("m")` (no module source for the analyser) or `import A = B.C` (an alias)
            bump();
            bump();
            const kind = atKw("require") && nthP(1, "(") ? NodeKind.import_ : NodeKind.importAlias;
            parseAssignment(false);
            semicolon();
            return finish(m, kind);
        }
        if ((cur().kind == TokKind.ident && !atKw("from")) || (atKw("from") && nthKw(1, "from")))
        {
            const d = mark();
            bump();
            finish(d, NodeKind.importDefault);
            eatP(",");
        }
        if (atP("*"))
        {
            const ns = mark();
            bump();
            if (!eatKw("as")) error();
            const nm = mark();
            if (cur().kind == TokKind.ident) bump();
            else error();
            finish(nm, NodeKind.name);
            finish(ns, NodeKind.importNamespace);
        }
        else if (atP("{"))
        {
            bump();
            while (!atP("}") && !atEof() && !bail())
            {
                const sm = mark();
                // `type`/`typeof` modifier of one specifier (but `{ type }` and `{ type as x }` import "type")
                if ((atKw("type") || atKw("typeof")) && nthIdentOrString(1)
                    && !(nthKw(1, "as") && !nthIdentOrString(2)))
                    bump();
                if (!nthIdentOrString(0))
                {
                    error();
                    break;
                }
                const nm = mark();
                bump();
                const name = finish(nm, NodeKind.name);
                setField(name, Field.name);
                if (eatKw("as"))
                {
                    const am = mark();
                    if (nthIdentOrString(0)) bump();
                    else error();
                    const alias_ = finish(am, NodeKind.name);
                    setField(alias_, Field.alias_);
                }
                finish(sm, NodeKind.importSpecifier);
                if (!eatP(",")) break;
            }
            expectP("}");
        }
        if (eatKw("from"))
        {
            if (cur().kind == TokKind.str)
            {
                const s = mark();
                bump();
                const src = finish(s, NodeKind.str);
                setField(src, Field.source);
            }
            else error();
        }
        else error();
        importAttributes();
        semicolon();
        return finish(m, NodeKind.import_);
    }

    /// `with { type: "json" }` / `assert { … }` after an import or re-export.
    void importAttributes()
    {
        if ((atKw("with") || atKw("assert")) && nthP(1, "{") && !peek(0).nlBefore)
        {
            bump();
            parsePrimary();
        }
    }

    /// `export …` in all its forms; `m` may include decorators.
    uint exportStatement(Marker m)
    {
        bump(); // export
        if (atP("*"))
        {
            bump();
            if (eatKw("as")) bump();
            expectKwFromSource();
            semicolon();
            return finish(m, NodeKind.export_);
        }
        if (atP("{") || (atKw("type") && nthP(1, "{")))
        {
            eatKw("type");
            exportClause();
            if (atKw("from")) expectKwFromSource();
            semicolon();
            return finish(m, NodeKind.export_);
        }
        if (eatP("="))
        {
            parseExpression(false);
            semicolon();
            return finish(m, NodeKind.export_);
        }
        if (atKw("as") && nthKw(1, "namespace"))
        {
            bump();
            bump();
            bump();
            semicolon();
            return finish(m, NodeKind.export_);
        }
        if (atKw("import"))
        {
            const d = importStatement();
            setField(d, Field.decl);
            return finish(m, NodeKind.export_);
        }
        if (eatKw("default"))
        {
            const dm = mark();
            // A named function or class is a declaration; an anonymous one is an expression.
            const namedFunction = (atKw("function")
                    && (nthKind(1) == TokKind.ident || (nthP(1, "*") && nthKind(2) == TokKind.ident)))
                || (atKw("async") && nthKw(1, "function") && !peek(1).nlBefore
                    && (nthKind(2) == TokKind.ident || (nthP(2, "*") && nthKind(3) == TokKind.ident)));
            const namedClass = atKw("class") && nthIdentifier(1) && !nthKw(1, "implements");
            uint decl = none;
            if (namedFunction) decl = functionDeclaration(dm);
            else if (namedClass) decl = class_(dm, NodeKind.classDecl);
            else if (atKw("abstract") && nthKw(1, "class"))
            {
                bump();
                decl = class_(dm, NodeKind.abstractClassDecl);
            }
            else if (atKw("interface") && nthKind(1) == TokKind.ident) decl = interfaceDeclaration(dm);
            if (decl != none) setField(decl, Field.decl);
            else
            {
                const v = parseAssignment(false);
                setField(v, Field.value);
                semicolon();
            }
            return finish(m, NodeKind.exportDefault);
        }
        const dm = mark();
        uint decl = none;
        if (atAnyKw(varWords) && !nthKw(1, "enum")) decl = varDeclaration(dm, true);
        else if (atKw("const")) decl = enumDeclaration(dm);
        else if (atP("@"))
        {
            while (atP("@")) decorator();
            if (atKw("abstract"))
            {
                bump();
                decl = class_(dm, NodeKind.abstractClassDecl);
            }
            else if (atKw("class")) decl = class_(dm, NodeKind.classDecl);
        }
        else decl = tryDeclaration(dm);
        if (decl != none) setField(decl, Field.decl);
        else error();
        return finish(m, NodeKind.export_);
    }

    /// `from "m"` of a re-export.
    void expectKwFromSource()
    {
        if (!eatKw("from"))
        {
            error();
            return;
        }
        if (cur().kind == TokKind.str) bump();
        else error();
        importAttributes();
    }

    /// `{ a, b as c, type d }` of an export.
    void exportClause()
    {
        expectP("{");
        while (!atP("}") && !atEof() && !bail())
        {
            if ((atKw("type") || atKw("typeof")) && nthIdentOrString(1) && !nthKw(1, "as")) bump();
            if (nthIdentOrString(0)) bump();
            else
            {
                error();
                break;
            }
            if (eatKw("as"))
            {
                if (nthIdentOrString(0)) bump();
                else error();
            }
            if (!eatP(",")) break;
        }
        expectP("}");
    }

    /// `if`, loops, `switch`, `try`, jumps, `debugger`, `with`. Decision points are flagged on their keyword.
    uint controlStatement(Marker m)
    {
        const word = textOf(cur());
        switch (word)
        {
        case "if":
            // `else if` chains are parsed iteratively (they are not nested deeper), so long chains cannot reach
            // maxDepth; the analyser only needs the flags, not the nesting.
            while (true)
            {
                bumpFlag(DECISION);
                parenExpression();
                parseStatement();
                if (!eatKw("else")) break;
                if (!atKw("if"))
                {
                    parseStatement();
                    break;
                }
            }
            break;
        case "for":
            bumpFlag(DECISION);
            eatKw("await");
            forHeader();
            parseStatement();
            break;
        case "while":
            bumpFlag(DECISION);
            parenExpression();
            parseStatement();
            break;
        case "do":
            bumpFlag(DECISION);
            parseStatement();
            if (!eatKw("while")) error();
            parenExpression();
            eatP(";");
            break;
        case "switch":
            bump();
            parenExpression();
            expectP("{");
            while (!atP("}") && !atEof() && !bail())
            {
                if (atKw("case"))
                {
                    bumpFlag(DECISION);
                    parseExpression(false);
                    expectP(":");
                }
                else if (atKw("default"))
                {
                    bump();
                    expectP(":");
                }
                else
                {
                    errorNode();
                    continue;
                }
                while (!atP("}") && !atKw("case") && !atKw("default") && !atEof() && !bail())
                {
                    const before = pos;
                    parseStatement();
                    if (pos == before) errorNode();
                }
            }
            expectP("}");
            break;
        case "try":
            bump();
            parseBlock();
            if (atKw("catch"))
            {
                bumpFlag(DECISION);
                if (eatP("("))
                {
                    bindingPattern();
                    if (eatP(":")) parseType();
                    expectP(")");
                }
                parseBlock();
            }
            if (eatKw("finally")) parseBlock();
            break;
        case "return", "throw":
            bump();
            if (!canEndStatement()) parseExpression(false);
            semicolon();
            break;
        case "break", "continue":
            bump();
            if (cur().kind == TokKind.ident && !cur().nlBefore) bump(); // label: statement_identifier, not a reference
            semicolon();
            break;
        case "debugger":
            bump();
            semicolon();
            break;
        default:
            // with
            bump();
            parenExpression();
            parseStatement();
            break;
        }
        return finish(m, NodeKind.stmt);
    }

    void parenExpression()
    {
        expectP("(");
        parseExpression(false);
        expectP(")");
    }

    /// `( init; test; update )`, `( x of xs )` or `( k in obj )`.
    void forHeader()
    {
        expectP("(");
        if (!atP(";"))
        {
            const isDecl = atKw("var") || atKw("const")
                || (atKw("let") && (nthIdentifier(1) || nthP(1, "[") || nthP(1, "{")));
            if (isDecl) varDeclaration(mark(), false);
            else parseExpression(true);
            if (atKw("of") || atKw("in"))
            {
                bump();
                parseExpression(false);
                expectP(")");
                return;
            }
        }
        expectP(";");
        if (!atP(";")) parseExpression(false);
        expectP(";");
        if (!atP(")")) parseExpression(false);
        expectP(")");
    }

    // ------------------------------------------------------------------ patterns and parameters

    /// A binding target: identifier (a reference), `{ … }` or `[ … ]` destructuring, or `...rest`.
    void bindingPattern()
    {
        if (!enter())
        {
            errorNode();
            return;
        }
        if (eatP("...")) bindingPattern();
        else if (atP("{"))
        {
            bump();
            while (!atP("}") && !atEof() && !bail())
            {
                if (eatP("...")) bindingPattern();
                else if (atPropertyName())
                {
                    const computed = atP("[");
                    const plain = atIdentifier();
                    propertyNameUnmarked();
                    if (eatP(":")) bindingElement();
                    else
                    {
                        // shorthand `{ a }` / `{ a = 1 }`: shorthand_property_identifier_pattern, not a reference
                        if (!plain || computed) error();
                        if (eatP("=")) parseAssignment(false);
                    }
                }
                else
                {
                    error();
                    break;
                }
                if (!eatP(",")) break;
            }
            expectP("}");
        }
        else if (atP("["))
        {
            bump();
            while (!atP("]") && !atEof() && !bail())
            {
                if (eatP(",")) continue; // hole
                bindingElement();
                if (!eatP(",")) break;
            }
            expectP("]");
        }
        else if (atIdentifier()) bumpRef();
        else error();
        leave();
    }

    /// A property name without creating a node (inside patterns).
    void propertyNameUnmarked()
    {
        if (atP("["))
        {
            bump();
            parseAssignment(false);
            expectP("]");
        }
        else bump();
    }

    /// `pattern [= default]`.
    void bindingElement()
    {
        bindingPattern();
        if (eatP("=")) parseAssignment(false);
    }

    /// `( parameters )` as a `params` node with field `params`.
    uint parameters()
    {
        const m = mark();
        expectP("(");
        while (!atP(")") && !atEof() && !bail())
        {
            parameter();
            if (!eatP(",")) break;
        }
        expectP(")");
        const n = finish(m, NodeKind.params);
        setField(n, Field.params);
        return n;
    }

    /// `@dec private readonly name?: T = default`, `...rest: T[]` or `this: T`.
    uint parameter()
    {
        const m = mark();
        while (atP("@")) decorator();
        bool property;
        while (true)
        {
            const nextOk = peek(1).kind == TokKind.ident || nthP(1, "{") || nthP(1, "[") || nthP(1, "...");
            if (atAnyKw(accessibility) && nextOk)
            {
                property = true;
                bump();
            }
            else if (atAnyKw(readonlyOverride) && nextOk) bump();
            else break;
        }
        if (atKw("this")) bump();
        else bindingPattern();
        eatP("?");
        if (eatP(":")) parseType();
        if (eatP("=")) parseAssignment(false);
        return finish(m, property ? NodeKind.paramProperty : NodeKind.param);
    }

    // ------------------------------------------------------------------ expressions

    /// Expression with the comma operator.
    uint parseExpression(bool noIn)
    {
        const first = parseAssignment(noIn);
        if (atP(","))
        {
            const m = precede(first);
            while (eatP(","))
            {
                parseAssignment(noIn);
                if (bail()) break;
            }
            return finish(m, NodeKind.expr);
        }
        return first;
    }

    /// Assignment-level expression: arrow function, `yield`, conditional, assignment. `noIn` disallows the `in`
    /// operator (in a `for (…)` initializer).
    uint parseAssignment(bool noIn)
    {
        if (!enter()) return errorNode();
        const n = assignment(noIn);
        leave();
        return n;
    }

    uint assignment(bool noIn)
    {
        const arrow = tryArrow(noIn);
        if (arrow != none) return arrow;
        if (atKw("yield") && !nthP(1, "=>"))
        {
            const m = mark();
            bump();
            const star = eatP("*");
            if (star || (!canEndStatement() && atExpressionStart())) parseAssignment(noIn);
            return finish(m, NodeKind.expr);
        }
        const left = conditional(noIn);
        if (atP(">")) rescan(Mode.greater);
        const t = cur();
        if (t.kind == TokKind.punct && isAssignOp(textOf(t)))
        {
            const m = precede(left);
            bump();
            parseAssignment(noIn);
            return finish(m, NodeKind.expr);
        }
        return left;
    }

    /// Can the current token start an expression (for `yield` and type-argument disambiguation)?
    bool atExpressionStart()
    {
        const t = cur();
        switch (t.kind)
        {
        case TokKind.eof, TokKind.invalid:
            return false;
        case TokKind.punct:
            switch (textOf(t))
            {
            case "(", "[", "{", "+", "-", "!", "~", "++", "--", "<", "/", "/=", "@", "#", "...":
                return true;
            default:
                return false;
            }
        case TokKind.ident:
            switch (textOf(t))
            {
            case "in", "of", "as", "satisfies", "instanceof":
                return false;
            default:
                return true;
            }
        default:
            return true;
        }
    }

    /// `test ? a : b`; the `?` is a decision point.
    uint conditional(bool noIn)
    {
        const test = binary(0, noIn);
        if (atP("?"))
        {
            const m = precede(test);
            bumpFlag(DECISION);
            parseAssignment(false);
            expectP(":");
            parseAssignment(noIn);
            return finish(m, NodeKind.expr);
        }
        return test;
    }

    /// Precedence of the binary operator at the current token (0 if it is none). `>` must already be rescanned.
    uint binaryPrecedence(bool noIn)
    {
        const t = cur();
        const s = textOf(t);
        if (t.kind == TokKind.ident)
        {
            if (s == "instanceof") return 8;
            if (s == "in") return noIn ? 0 : 8;
            if (s == "as" || s == "satisfies") return t.nlBefore ? 0 : 8;
            return 0;
        }
        if (t.kind != TokKind.punct) return 0;
        switch (s)
        {
        case "??": return 1;
        case "||": return 2;
        case "&&": return 3;
        case "|": return 4;
        case "^": return 5;
        case "&": return 6;
        case "==", "!=", "===", "!==": return 7;
        case "<", ">", "<=", ">=": return 8;
        case "<<", ">>", ">>>": return 9;
        case "+", "-": return 10;
        case "*", "/", "%": return 11;
        case "**": return 12;
        default: return 0;
        }
    }

    /// Binary operators by precedence climbing. `&&`, `||` and `??` are decision points.
    uint binary(uint min, bool noIn)
    {
        uint left = parseUnary();
        while (true)
        {
            if (bail()) return left;
            if (atP(">")) rescan(Mode.greater);
            const prec = binaryPrecedence(noIn);
            if (prec == 0 || (prec <= min && !(prec == 12 && min == 12))) return left;
            const m = precede(left);
            if (atKw("as") || atKw("satisfies"))
            {
                bump();
                if (!eatKw("const")) parseType();
                left = finish(m, NodeKind.expr);
                continue;
            }
            const decision = atP("&&") || atP("||") || atP("??");
            bumpFlag(decision ? DECISION : 0);
            // `**` is right-associative: parse its right side at one level lower (and count the recursion, as a
            // chain of `**` nests without bound)
            if (!enter()) return errorNode();
            binary(prec == 12 ? 11 : prec, noIn);
            leave();
            left = finish(m, NodeKind.expr);
        }
    }

    /// Prefix operators, `await`, and (plain TypeScript only) the type assertion `<T>expr`.
    uint parseUnary()
    {
        if (!enter()) return errorNode();
        const n = unary();
        leave();
        return n;
    }

    uint unary()
    {
        const t = cur();
        const s = textOf(t);
        bool prefix;
        if (t.kind == TokKind.punct)
            prefix = s == "!" || s == "~" || s == "+" || s == "-" || s == "++" || s == "--";
        else if (t.kind == TokKind.ident)
            prefix = s == "typeof" || s == "void" || s == "delete"
                || (s == "await" && !nthP(1, "=>") && !nthP(1, ")") && !nthP(1, ",") && !nthP(1, ";"));
        if (prefix)
        {
            const m = mark();
            bump();
            parseUnary();
            return finish(m, NodeKind.expr);
        }
        if (!tsx && atP("<"))
        {
            const m = mark();
            typeArguments();
            parseUnary();
            return finish(m, NodeKind.expr);
        }
        const e = parseLhsExpression();
        const t2 = cur();
        if ((atP("++") || atP("--")) && !t2.nlBefore)
        {
            const m = precede(e);
            bump();
            return finish(m, NodeKind.expr);
        }
        return e;
    }

    /// Member access, calls (also generic `f<T>()` and tagged templates), non-null `x!`, `new`.
    uint parseLhsExpression()
    {
        const e = atKw("new") ? newExpression() : parsePrimary();
        return callTail(e, true);
    }

    /// `new X<T>(args)` and `new.target`. The callee is a member expression without calls.
    uint newExpression()
    {
        const m = mark();
        if (!enter()) return errorNode();
        bump(); // new
        if (atP("."))
        {
            bump();
            bump(); // target
            leave();
            return finish(m, NodeKind.expr);
        }
        const callee = atKw("new") ? newExpression() : parsePrimary();
        callTail(callee, false);
        if (atP("<")) tryTypeArgumentsInExpression();
        if (atP("("))
        {
            const a = arguments();
            setField(a, Field.args);
        }
        leave();
        return finish(m, NodeKind.expr);
    }

    /// The postfix part of a left-hand-side expression. Without `calls` (the callee of `new`), stops before `(`.
    uint callTail(uint e, bool calls)
    {
        while (true)
        {
            if (bail()) return e;
            const t = cur();
            if (t.kind == TokKind.template_ || t.kind == TokKind.templateHead)
            {
                if (!calls) return e;
                // tagged template: a call with the template as its argument
                const m = precede(e);
                const a = template_();
                setField(a, Field.args);
                e = finish(m, NodeKind.call);
                continue;
            }
            if (t.kind != TokKind.punct) return e;
            const s = textOf(t);
            if (s == ".")
            {
                const m = precede(e);
                bump();
                const k = cur().kind;
                if (k == TokKind.ident || k == TokKind.privateName) bump(); // property_identifier
                else error();
                e = finish(m, NodeKind.expr);
            }
            else if (s == "?.")
            {
                const m = precede(e);
                bump();
                if (atP("(") || atP("<"))
                {
                    if (atP("<")) typeArguments();
                    const a = arguments();
                    setField(a, Field.args);
                    e = finish(m, NodeKind.call);
                    continue;
                }
                if (atP("["))
                {
                    bump();
                    parseExpression(false);
                    expectP("]");
                }
                else if (cur().kind == TokKind.ident || cur().kind == TokKind.privateName) bump();
                else error();
                e = finish(m, NodeKind.expr);
            }
            else if (s == "[")
            {
                const m = precede(e);
                bump();
                parseExpression(false);
                expectP("]");
                e = finish(m, NodeKind.expr);
            }
            else if (s == "!" && !t.nlBefore)
            {
                const m = precede(e);
                bump();
                e = finish(m, NodeKind.expr);
            }
            else if (s == "(" && calls)
            {
                const m = precede(e);
                const a = arguments();
                setField(a, Field.args);
                e = finish(m, NodeKind.call);
            }
            else if (s == "<" && calls)
            {
                // `f<T>(x)`, ``f<T>`…` `` or an instantiation expression `f<T>`; otherwise a comparison
                const m = precede(e);
                if (!tryTypeArgumentsInExpression()) return e;
                if (atP("("))
                {
                    const a = arguments();
                    setField(a, Field.args);
                    e = finish(m, NodeKind.call);
                }
                else if (cur().kind == TokKind.template_ || cur().kind == TokKind.templateHead)
                {
                    const a = template_();
                    setField(a, Field.args);
                    e = finish(m, NodeKind.call);
                }
                else e = finish(m, NodeKind.expr);
            }
            else return e;
        }
    }

    /// Speculatively parses `<T, U>` after an expression; keeps it only if what follows makes it type arguments
    /// (TypeScript's `canFollowTypeArgumentsInExpression`).
    bool tryTypeArgumentsInExpression()
    {
        if (depthCapped) return false;
        uint base;
        const s = beginSpec(base);
        typeArguments();
        const ok = !bail() && typeArgumentsCanFollow();
        return endSpec(s, base, ok);
    }

    bool typeArgumentsCanFollow()
    {
        const t = cur();
        if (atP("(") || t.kind == TokKind.template_ || t.kind == TokKind.templateHead) return true;
        if (atP("<") || atP(">") || atP("+") || atP("-")) return false;
        if (t.nlBefore || t.kind == TokKind.eof) return true;
        const binary = binaryPrecedence(false) > 0 || (t.kind == TokKind.punct && isAssignOp(textOf(t))) || atP("?")
            || atP(",");
        return binary || !atExpressionStart();
    }

    /// `( args )` as an `args` node.
    uint arguments()
    {
        const m = mark();
        expectP("(");
        while (!atP(")") && !atEof() && !bail())
        {
            if (atP("..."))
            {
                const sm = mark();
                bump();
                parseAssignment(false);
                finish(sm, NodeKind.expr);
            }
            else parseAssignment(false);
            if (!eatP(",")) break;
        }
        expectP(")");
        return finish(m, NodeKind.args);
    }

    /// Primary expressions: names, literals, `( )`, arrays, objects, functions, classes, templates, regexes, JSX.
    uint parsePrimary()
    {
        const t = cur();
        const m = mark();
        switch (t.kind)
        {
        case TokKind.ident:
        {
            const s = textOf(t);
            if (s == "function") return functionExpression(m);
            if (s == "async" && nthKw(1, "function") && !peek(1).nlBefore) return functionExpression(m);
            if (s == "class") return class_(m, NodeKind.classExpr);
            if (s == "this" || s == "super" || s == "null" || s == "true" || s == "false" || s == "import")
            {
                bump();
                return finish(m, NodeKind.expr);
            }
            if (isReserved(s)) return errorNode();
            bumpRef();
            return finish(m, NodeKind.ident);
        }
        case TokKind.str:
            bump();
            return finish(m, NodeKind.str);
        case TokKind.number, TokKind.regex, TokKind.privateName:
            bump();
            return finish(m, NodeKind.expr);
        case TokKind.template_, TokKind.templateHead:
            return template_();
        case TokKind.punct:
        {
            const s = textOf(t);
            if (s == "(")
            {
                bump();
                parseExpression(false);
                if (eatP(":")) parseType();
                expectP(")");
                return finish(m, NodeKind.expr);
            }
            if (s == "[")
            {
                bump();
                while (!atP("]") && !atEof() && !bail())
                {
                    if (eatP(",")) continue;
                    eatP("...");
                    parseAssignment(false);
                    if (!eatP(",")) break;
                }
                expectP("]");
                return finish(m, NodeKind.expr);
            }
            if (s == "{") return objectLiteral();
            if (s == "/" || s == "/=")
            {
                rescan(Mode.regex);
                bump();
                return finish(m, NodeKind.expr);
            }
            if (s == "<" && tsx) return jsxElement();
            if (s == "@")
            {
                while (atP("@")) decorator();
                if (atKw("class")) return class_(m, NodeKind.classExpr);
                error();
                return finish(m, NodeKind.error);
            }
            return errorNode();
        }
        default:
            return errorNode();
        }
    }

    /// `[async] function [*] [name](params) { }` as an expression.
    uint functionExpression(Marker m)
    {
        eatKw("async");
        bump(); // function
        const generator = eatP("*");
        if (cur().kind == TokKind.ident) declName();
        callSignature();
        parseBlock();
        return finish(m, generator ? NodeKind.generatorExpr : NodeKind.functionExpr);
    }

    /// `{ a: 1, b, ...c, m() {}, get x() {}, [k]: v }`. Property names and shorthand properties are not references.
    uint objectLiteral()
    {
        const m = mark();
        if (!enter()) return errorNode();
        bump(); // {
        while (!atP("}") && !atEof() && !bail())
        {
            if (eatP(",")) continue;
            const pm = mark();
            if (eatP("..."))
            {
                parseAssignment(false);
                finish(pm, NodeKind.expr);
            }
            else
            {
                // `async`, `get`, `set` and `*` are modifiers only when a property name follows
                while (atAnyKw(asyncGetSet) && (nthPropertyName(1) || nthP(1, "*"))
                    && !(atKw("async") && peek(1).nlBefore))
                    bump();
                eatP("*");
                if (!atPropertyName())
                {
                    errorNode();
                    continue;
                }
                propertyName();
                eatP("?");
                if (atP("(") || atP("<"))
                {
                    callSignature();
                    parseBlock();
                }
                else if (eatP(":")) parseAssignment(false);
                else if (eatP("=")) parseAssignment(false);
                finish(pm, NodeKind.expr);
            }
            if (!atP("}") && !eatP(","))
            {
                error();
                break;
            }
        }
        expectP("}");
        leave();
        return finish(m, NodeKind.expr);
    }

    /// A template literal; substitutions are expressions.
    uint template_()
    {
        return templateWith(false);
    }

    /// A template literal whose substitutions are types (`types`, for template literal types) or expressions.
    uint templateWith(bool types)
    {
        const m = mark();
        if (cur().kind == TokKind.template_)
        {
            bump();
            return finish(m, NodeKind.template_);
        }
        if (!enter()) return errorNode();
        bump(); // head
        while (true)
        {
            if (types) parseType();
            else parseExpression(false);
            if (!atP("}"))
            {
                error();
                break;
            }
            rescan(Mode.templateContinue);
            const k = cur().kind;
            bump();
            if (k != TokKind.templateMiddle || bail()) break;
        }
        leave();
        return finish(m, NodeKind.template_);
    }

    /// The `x` of `x => …` as a `params` node holding one `param`, and the `=>` and body after it.
    private uint simpleArrow(Marker m, bool noIn)
    {
        const pm = mark();
        const qm = mark();
        bumpRef();
        finish(qm, NodeKind.param);
        const p = finish(pm, NodeKind.params);
        setField(p, Field.params);
        bump(); // =>
        arrowBody(noIn);
        return finish(m, NodeKind.arrow);
    }

    /// Tries to parse an arrow function at the current position. Plain `x => …` and `async x => …` are recognised
    /// directly; parenthesised parameters (and generic arrows `<T>(…) =>`) speculatively, up to `=>`. `none` if
    /// there is no arrow function here (nothing consumed).
    uint tryArrow(bool noIn)
    {
        const t = cur();
        if (t.kind == TokKind.ident)
        {
            const isAsync = atKw("async") && !peek(1).nlBefore;
            // x => …
            if (atIdentifier() && nthP(1, "=>")) return simpleArrow(mark(), noIn);
            // async x => …
            if (isAsync && nthIdentifier(1) && nthP(2, "=>"))
            {
                const m = mark();
                bump();
                return simpleArrow(m, noIn);
            }
            if (!(isAsync && (nthP(1, "(") || nthP(1, "<")))) return none;
        }
        else if (!(atP("(") || atP("<"))) return none;
        if (atP("<") && tsx)
        {
            // In TSX `<T>(x) => x` would be JSX; generic arrows are written `<T,>(…)` or `<T extends U>(…)`.
            const generic = (nthKind(1) == TokKind.ident && (nthP(2, ",") || nthKw(2, "extends")))
                || (nthKw(1, "const") && nthKind(2) == TokKind.ident);
            if (!generic) return none;
        }
        const start = t.start;
        if (depthCapped || start in arrowFailed) return none;
        uint base;
        const s = beginSpec(base);
        const m = mark();
        eatKw("async");
        if (atP("<")) typeParameters();
        if (!bail()) parameters();
        if (!bail() && eatP(":")) returnType();
        const ok = !bail() && atP("=>");
        if (!endSpec(s, base, ok))
        {
            arrowFailed[start] = true;
            return none;
        }
        bump(); // =>
        arrowBody(noIn);
        return finish(m, NodeKind.arrow);
    }

    void arrowBody(bool noIn)
    {
        if (atP("{")) parseBlock();
        else parseAssignment(noIn);
    }

    // ------------------------------------------------------------------ JSX

    /// A JSX element or fragment starting at `<` (TSX only). Tag names are references (also for intrinsic elements like
    /// `div`); attribute names and member parts (`<A.B>`'s `B`) are not.
    uint jsxElement()
    {
        const m = mark();
        if (!enter()) return errorNode();
        bump(); // <
        if (eatP(">"))
        {
            jsxChildren();
            leave();
            return finish(m, NodeKind.expr);
        }
        jsxName();
        if (atP("<")) typeArguments();
        while (!bail())
        {
            if (atP("{"))
            {
                bump();
                eatP("...");
                parseAssignment(false);
                expectP("}");
                continue;
            }
            if (cur().kind == TokKind.ident)
            {
                rescan(Mode.jsxName);
                bump(); // attribute name: property_identifier
                if (atP(":"))
                {
                    // namespaced attribute `a:b`: both parts count as references
                    if (pos > 0 && pos - 1 < toks.length) toks[pos - 1].flags |= REF;
                    bump();
                    rescan(Mode.jsxName);
                    bumpFlag(REF);
                }
                if (eatP("="))
                {
                    if (cur().kind == TokKind.str)
                    {
                        rescan(Mode.jsxString);
                        bump();
                    }
                    else if (atP("{"))
                    {
                        bump();
                        parseExpression(false);
                        expectP("}");
                    }
                    else if (atP("<")) jsxElement();
                    else error();
                }
                continue;
            }
            break;
        }
        if (atP("/") && nthP(1, ">"))
        {
            bump();
            bump();
            leave();
            return finish(m, NodeKind.expr);
        }
        if (!atP(">"))
        {
            error();
            leave();
            return finish(m, NodeKind.expr);
        }
        bump();
        jsxChildren();
        leave();
        return finish(m, NodeKind.expr);
    }

    /// `Name`, `my-element`, `A.B.C` or `ns:name` after `<` or `</`.
    void jsxName()
    {
        if (cur().kind != TokKind.ident)
        {
            error();
            return;
        }
        rescan(Mode.jsxName);
        bumpFlag(REF);
        if (atP(":"))
        {
            bump();
            rescan(Mode.jsxName);
            bumpFlag(REF);
            return;
        }
        while (atP("."))
        {
            bump();
            if (cur().kind == TokKind.ident) bump(); // property_identifier
            else
            {
                error();
                return;
            }
        }
    }

    /// Children after an opening tag, up to and including the closing tag.
    void jsxChildren()
    {
        while (true)
        {
            if (bail()) return;
            rescan(Mode.jsxChild);
            const t = cur();
            if (t.kind == TokKind.jsxText) bump();
            else if (t.kind == TokKind.eof)
            {
                error();
                return;
            }
            else if (atP("{"))
            {
                bump();
                if (!atP("}"))
                {
                    eatP("...");
                    parseExpression(false);
                }
                expectP("}");
            }
            else if (nthP(1, "/"))
            {
                // closing tag `</Name>` or `</>`
                bump();
                bump();
                if (!atP(">")) jsxName();
                expectP(">");
                return;
            }
            else jsxElement();
        }
    }

    // ------------------------------------------------------------------ types

    /// A type, as a node: an `objectType` when the whole type is an object literal type, otherwise a `type` node.
    uint parseType()
    {
        const m = mark();
        if (!enter()) return errorNode();
        typeInner(false);
        leave();
        // Do not wrap a lone object type, so the analyser can see `type P = { … }`.
        if (stack.length == m.stack + 1)
        {
            const top = stack.last;
            const node = nodes[top];
            if (node.kind == NodeKind.objectType && node.first == m.tok && node.end == pos) return top;
        }
        return finish(m, NodeKind.type);
    }

    /// `noConditional`: in the `extends` clause of a conditional type, which cannot itself be conditional.
    void typeInner(bool noConditional)
    {
        if (atKw("new") || (atKw("abstract") && nthKw(1, "new")))
        {
            eatKw("abstract");
            bump();
            if (atP("<")) typeParameters();
            parameters();
            expectP("=>");
            returnType();
            return;
        }
        if (atP("<") || (atP("(") && tryFunctionType()))
        {
            if (atP("<"))
            {
                typeParameters();
                parameters();
                expectP("=>");
                returnType();
            }
            return;
        }
        unionType(noConditional);
        if (!noConditional && atKw("extends") && !cur().nlBefore)
        {
            bump();
            if (!enter()) return;
            typeInner(true);
            leave();
            expectP("?");
            parseTypeNoNode();
            expectP(":");
            parseTypeNoNode();
        }
    }

    /// A nested type that needs no node of its own.
    void parseTypeNoNode()
    {
        if (!enter())
        {
            errorNode();
            return;
        }
        typeInner(false);
        leave();
    }

    /// Speculatively parses `(params) => R` at `(`. True if it was a function type (already consumed).
    bool tryFunctionType()
    {
        const start = cur().start;
        if (depthCapped || start in arrowFailed) return false;
        uint base;
        const s = beginSpec(base);
        parameters();
        const ok = !bail() && atP("=>");
        if (!endSpec(s, base, ok))
        {
            arrowFailed[start] = true;
            return false;
        }
        bump();
        returnType();
        return true;
    }

    /// After `:` of a signature: `x is T`, `asserts x [is T]`, `this is T` or a type.
    void returnType()
    {
        if (atKw("asserts") && nthKind(1) == TokKind.ident && !peek(1).nlBefore)
        {
            bump();
            if (atKw("this")) bump();
            else bumpRef();
            if (eatKw("is")) parseType();
            return;
        }
        if (cur().kind == TokKind.ident && nthKw(1, "is") && !peek(1).nlBefore)
        {
            if (atKw("this")) bump();
            else bumpRef();
            bump();
            parseType();
            return;
        }
        parseType();
    }

    void unionType(bool noConditional)
    {
        eatP("|");
        intersectionType(noConditional);
        while (!bail() && eatP("|")) intersectionType(noConditional);
    }

    void intersectionType(bool noConditional)
    {
        eatP("&");
        typeOperator(noConditional);
        while (!bail() && eatP("&")) typeOperator(noConditional);
    }

    /// `keyof T`, `readonly T[]`, `unique symbol`, `infer U [extends C]`, or a postfix type.
    void typeOperator(bool noConditional)
    {
        if (!enter())
        {
            errorNode();
            return;
        }
        if ((atKw("keyof") || atKw("readonly") || atKw("unique")) && typeOperandFollows())
        {
            bump();
            typeOperator(noConditional);
        }
        else if (atKw("infer") && nthKind(1) == TokKind.ident)
        {
            bump();
            bumpRef();
            if (atKw("extends") && !cur().nlBefore && !depthCapped)
            {
                // `infer U extends C` – unless the `extends` starts the conditional type around it (TypeScript's
                // rule: inside the `extends` clause of a conditional type, it is always a constraint)
                uint base;
                const s = beginSpec(base);
                bump();
                typeInner(true);
                const ok = !bail() && (noConditional || !atP("?"));
                endSpec(s, base, ok);
            }
        }
        else postfixType();
        leave();
    }

    /// Is the token after a type operator keyword the start of a type (otherwise the keyword is a type name)?
    bool typeOperandFollows()
    {
        const k = nthKind(1);
        if (k == TokKind.ident || k == TokKind.str || k == TokKind.number || k == TokKind.template_
            || k == TokKind.templateHead)
            return true;
        foreach (p; typeOperandStarts)
            if (nthP(1, p)) return true;
        return false;
    }

    /// A primary type followed by `[]` or `[K]` (not across a line break).
    void postfixType()
    {
        primaryType();
        while (!bail() && atP("[") && !cur().nlBefore)
        {
            bump();
            if (!eatP("]"))
            {
                parseType();
                expectP("]");
            }
        }
    }

    void primaryType()
    {
        const t = cur();
        switch (t.kind)
        {
        case TokKind.ident:
        {
            const s = textOf(t);
            if (s == "typeof")
            {
                bump();
                typeQuery();
                return;
            }
            if (s == "import" && nthP(1, "("))
            {
                bump();
                arguments();
                while (atP(".") && nthKind(1) == TokKind.ident)
                {
                    bump();
                    bump(); // property_identifier
                }
                if (atP("<") && !cur().nlBefore) typeArguments();
                return;
            }
            if (isPredefinedType(s) && !nthP(1, "."))
            {
                bump();
                if (s == "unique") eatKw("symbol");
                return;
            }
            // A type reference `A`, `A.B`, `A.B.C<T>`: the first and the last name are references, the ones in between
            // are property names.
            const first = bumpFlag(REF);
            size_t last = first;
            while (atP(".") && nthKind(1) == TokKind.ident)
            {
                bump();
                last = bump();
            }
            if (last < toks.length) toks[last].flags |= REF;
            if (atP("<") && !cur().nlBefore) typeArguments();
            break;
        }
        case TokKind.str, TokKind.number:
            bump();
            break;
        case TokKind.template_, TokKind.templateHead:
            templateWith(true);
            break;
        case TokKind.punct:
        {
            const s = textOf(t);
            if (s == "-" && nthKind(1) == TokKind.number)
            {
                bump();
                bump();
            }
            else if (s == "(")
            {
                bump();
                parseType();
                expectP(")");
            }
            else if (s == "[") tupleType();
            else if (s == "{") objectType();
            else if (s == "*") bump();
            else if (s == "?")
            {
                bump();
                primaryType();
            }
            else errorNode();
            break;
        }
        default:
            errorNode();
            break;
        }
    }

    /// After `typeof`: `x`, `x.y`, `x[0]`, `import("m").x`, optionally with type arguments `typeof f<T>`. Only the
    /// first name is a reference.
    void typeQuery()
    {
        if (atKw("import") && nthP(1, "("))
        {
            bump();
            arguments();
        }
        else if (atKw("this")) bump();
        else if (cur().kind == TokKind.ident) bumpRef();
        else
        {
            error();
            return;
        }
        while (true)
        {
            if ((atP(".") || atP("?.")) && (nthKind(1) == TokKind.ident || nthKind(1) == TokKind.privateName))
            {
                bump();
                bump();
            }
            else if (atP("[") && !cur().nlBefore && !nthP(1, "]"))
            {
                bump();
                parseType();
                expectP("]");
            }
            else break;
        }
        if (atP("<") && !cur().nlBefore) typeArguments();
    }

    /// `[A, b?: B, ...C[]]`. Names of named members are references.
    void tupleType()
    {
        bump(); // [
        while (!atP("]") && !atEof() && !bail())
        {
            eatP("...");
            if (cur().kind == TokKind.ident && (nthP(1, ":") || (nthP(1, "?") && nthP(2, ":"))))
            {
                bumpRef();
                eatP("?");
                bump(); // :
            }
            parseType();
            eatP("?");
            if (!eatP(",")) break;
        }
        expectP("]");
    }

    /// `{ members }` as an `objectType` node: property, method, call, construct and index signatures, and mapped
    /// type clauses.
    uint objectType()
    {
        const m = mark();
        if (!enter()) return errorNode();
        expectP("{");
        while (!atP("}") && !atEof() && !bail())
        {
            const before = pos;
            typeMember();
            if (!(eatP(";") || eatP(",") || canEndStatement())) error();
            if (pos == before) errorNode();
        }
        expectP("}");
        leave();
        return finish(m, NodeKind.objectType);
    }

    /// One member of an object type or interface body.
    void typeMember()
    {
        const m = mark();
        if (atP("(") || atP("<"))
        {
            callSignature();
            finish(m, NodeKind.callSig);
            return;
        }
        if (atKw("new") && (nthP(1, "(") || nthP(1, "<")))
        {
            bump();
            callSignature();
            finish(m, NodeKind.constructSig);
            return;
        }
        // mapped type modifiers `+readonly` / `-readonly`
        if ((atP("+") || atP("-")) && nthKw(1, "readonly")) bump();
        while (atAnyKw(typeMemberModifiers)
            && modifierFollows())
            bump();
        // index signature `[k: K]: V` or mapped type `[K in T as U]?: V`
        if (atP("[") && nthKind(1) == TokKind.ident && (nthP(2, ":") || nthKw(2, "in")))
        {
            bump();
            bumpRef();
            if (eatKw("in"))
            {
                parseType();
                if (eatKw("as")) parseType();
            }
            else
            {
                bump(); // :
                parseType();
            }
            expectP("]");
            if ((atP("+") || atP("-")) && nthP(1, "?")) bump();
            eatP("?");
            if (eatP(":")) parseType();
            finish(m, NodeKind.indexSig);
            return;
        }
        propertyName();
        eatP("?");
        if (atP("(") || atP("<"))
        {
            callSignature();
            finish(m, NodeKind.methodSig);
            return;
        }
        if (eatP(":")) parseType();
        finish(m, NodeKind.propertySig);
    }

    /// `<T, U>` type arguments.
    void typeArguments()
    {
        expectP("<");
        while (!atP(">") && !atEof() && !bail())
        {
            parseType();
            if (!eatP(",")) break;
        }
        expectP(">");
    }

    /// `<const T extends C = D, in out U>` type parameters. Names are references (type_identifier).
    void typeParameters()
    {
        expectP("<");
        while (!atP(">") && !atEof() && !bail())
        {
            // `const`, `in`, `out` modifiers (when another name follows)
            while (atAnyKw(typeParameterModifiers) && nthKind(1) == TokKind.ident) bump();
            if (cur().kind == TokKind.ident) bumpRef();
            else
            {
                error();
                break;
            }
            if (eatKw("extends") || eatP(":")) parseType();
            if (eatP("=")) parseType();
            if (!eatP(",")) break;
        }
        expectP(">");
    }
}

// ----------------------------------------------------------------------------------------------------------- tests

version (unittest)
{
    import std.algorithm : map, filter;
    import std.array : array, replicate;
    import std.conv : to;
    import std.format : format;
    import std.range : iota;

    /// The tokens carrying `flag`, in source order; fails on a syntax error.
    private string[] flagged(string code, bool tsx, ubyte flag)
    {
        auto t = parse(code, tsx);
        assert(!t.hasError, format("unexpected syntax error at %s in %s", t.firstError, code));
        string[] output;
        foreach (i; 0 .. t.tokens.length)
            if (t.tokens[i].flags & flag) output ~= t.tokenText(i);
        return output;
    }

    /// The identifiers flagged as references, in source order.
    private string[] refs(string code, bool tsx)
    {
        return flagged(code, tsx, REF);
    }

    /// The tokens flagged as decision points, in source order.
    private string[] decisions(string code, bool tsx)
    {
        return flagged(code, tsx, DECISION);
    }

    /// Kinds of the program's top-level statements.
    private NodeKind[] top(string code, bool tsx)
    {
        auto t = parse(code, tsx);
        assert(!t.hasError, format("unexpected syntax error at %s in %s", t.firstError, code));
        NodeKind[] output;
        foreach (c; t.children(t.root)) output ~= t.kind(c);
        return output;
    }

    /// Runs `f` on a thread with a 2 MiB stack, the size the Rust edition tests with (a rayon worker's), and
    /// rethrows anything it threw (assertion failures included).
    private void onSmallStack(void delegate() f)
    {
        import core.thread : Thread;
        Throwable failure;
        auto th = new Thread({
            try f();
            catch (Throwable e) failure = e;
        }, 2 << 20);
        th.start();
        th.join();
        if (failure !is null) throw failure;
    }

    /// Checks the structural invariants of a tree: children lie within their parent's token range, in order.
    private void checkTree(ref const Tree t)
    {
        assert(t.tokens.length && t.tokens[$ - 1].kind == TokKind.eof);
        foreach (i, n; t.nodes)
        {
            assert(n.end <= t.tokens.length);
            uint prev = n.first;
            foreach (c; t.children(cast(uint) i))
            {
                const k = t.nodes[c];
                assert(k.first >= prev && k.end <= (n.end > n.first ? n.end : n.first), "child outside parent");
                prev = k.end > k.first ? k.end : k.first;
            }
            cast(void) t.text(cast(uint) i);
            cast(void) t.firstLine(cast(uint) i);
            cast(void) t.lastLine(cast(uint) i);
        }
    }

    /// A sample with a bit of every construct, for the fuzz test.
    private enum corpus = `#!/usr/bin/env node
import React, { useState as useS, type FC } from "react";
import * as path from 'path';
export * from "./a"; export { b as default } from './b';
/** doc */
@Component({ selector: "x" })
export abstract class Store<T extends object = {}> extends Base<T> implements IStore, Other {
  private static readonly items: Map<string, T[]> = new Map();
  #secret = 1; declare x?: number; accessor y = 2;
  constructor(private readonly db: Db, public name = ` ~ "`n${1 + 2}`" ~ `) { super(db); }
  @Input() get value(): T | undefined { return this.items?.get("a")?.[0]; }
  set value(v) { if (v && !this.x) this.x ??= 1; }
  abstract total(): number;
  async *gen<U>(...args: U[]): AsyncGenerator<U> { for await (const a of args) yield a; }
  onChange = (e: Event): void => { e.preventDefault(); };
}
namespace NS.Inner { export const enum E { A = 1 << 2, B = A | 1, "c" = 3 } }
declare module "m" { export function f(x: number): string; }
type Fn = <A>(a: A, ...rest: [b?: string, ...c: number[]]) => asserts a is NonNullable<A>;
type M<T> = { -readonly [K in keyof T as ` ~ "`get${Capitalize<K & string>}`" ~ `]+?: () => T[K] } & Record<string, unknown>;
type C<T> = T extends (infer U)[] ? U : T extends Promise<infer V> ? V : never;
interface I extends J, K.L<M> { (x: number): string; new (y: string): I; readonly [k: string]: any; m?<T>(): void; get g(): number }
function overload(a: string): void;
function overload(a: any) { label: switch (typeof a) { case "string": break label; default: return a as unknown as never; } }
const re = /ab+c\/[/]/gi, div = 4 / 2 / 1, tpl = tag` ~ "`x${re}y`" ~ `, n = 0x1F_n, f = 1.5e-3;
let obj = { a, b: 2, [c]: 3, d() {}, get e() { return 1 }, async *f() {}, ...g };
const App: FC<{ title: string }> = ({ title }) => <div className="app" onClick={() => setX(x => x + 1)}>{title} it's &amp; <></></div>;
try { throw new Error("x"); } catch (e: unknown) { console.log(e satisfies unknown); } finally { }
do x++; while (x < 10)
for (let i = 0, j = 1; i < j; i++) for (const k in obj) if (k) continue; else break;
export default function* () { yield* other(); }
`;
}

unittest // regex versus division
{
    assert(refs("let a = b / c / d;", false) == ["a", "b", "c", "d"]);
    assert(refs("let r = /[/'`]+/g.test(s);", false) == ["r", "s"]);
    assert(refs("if (x) /a`b/.exec(y)", false) == ["x", "y"]);
    assert(refs("f(a)/2; g(/=/);", false) == ["f", "a", "g"]);
}

unittest // templates nest and close at the right brace
{
    assert(refs("const s = `a${b ? `c${d}` : { e }.e}f${g}`;", false) == ["s", "b", "d", "g"]);
    assert(decisions("const s = `${a ? 1 : 2}`;", false) == ["?"]);
}

unittest // generics versus comparison
{
    assert(refs("f<T>(x); a < b; c < d > (e);", false) == ["f", "T", "x", "a", "b", "c", "d", "e"]);
    assert(refs("const m = new Map<string, Array<Set<K>>>();", false) == ["m", "Map", "Array", "Set", "K"]);
    assert(refs("x = y >> 2; z >>>= 1; w >= v;", false) == ["x", "y", "z", "w", "v"]);
    assert(refs("const g = <T,>(x: T) => x;", true) == ["g", "T", "x", "T", "x"]);
    assert(refs("const g = <T>(x: T) => <T>x;", false) == ["g", "T", "x", "T", "T", "x"]);
}

unittest // arrow functions versus parentheses
{
    auto t = parse("a = (b, c) => d; e = (f, g); h = async (i) => i; j = async x => x; k = (l): M => l;", false);
    assert(!t.hasError);
    size_t arrows;
    foreach (n; t.nodes) if (n.kind == NodeKind.arrow) arrows++;
    assert(arrows == 4);
    assert(decisions("x = a ? (b) : c;", false) == ["?"]);
    assert(decisions("x = a ? (b): c => d : e;", false) == ["?"]);
}

unittest // property names and shorthands are not references
{
    assert(refs("x = { a: b, c, [d]: e, f() { return g.h; }, ...i };", false) == ["x", "b", "d", "e", "g", "i"]);
    assert(refs("const { a, b: c, d = e, ...f } = g;", false) == ["c", "e", "f", "g"]);
    assert(refs("class A { b: C; d() {} #e = f; }", false) == ["A", "C", "f"]);
    assert(refs("interface I { a: B; c(d: E): F }", false) == ["I", "B", "d", "E", "F"]);
    assert(refs("enum E { A = B.C, D }", false) == ["E", "B"]);
    assert(refs("lbl: for (;;) { break lbl; }", false).length == 0);
    assert(refs("let u = undefined;", false) == ["u"]);
}

unittest // type references
{
    assert(refs("type T = A.B.C<D> | typeof e.f | keyof G | [h: I] | `${J}`;", false)
        == ["T", "A", "C", "D", "e", "G", "h", "I", "J"]);
    assert(refs("type T<U> = U extends infer V extends W ? V : never;", false) == ["T", "U", "U", "V", "W", "V"]);
    assert(refs("function f(x): x is Y { return true; }", false) == ["f", "x", "x", "Y"]);
    assert(refs("let a: readonly typeof b.c[] = d;", false) == ["a", "b", "d"]);
    assert(refs("let a: Array<typeof import('m')>;", false) == ["a", "Array"]);
    assert(refs("type M = { readonly [K in keyof T as `x${K}`]-?: T[K] };", false) == ["M", "K", "T", "K", "T", "K"]);
}

unittest // JSX
{
    const code = "const a = <A.B c=\"d\\\" e:f={g}><div>it's {h} // not a comment</div><I {...j} /></A.B>;";
    assert(refs(code, true) == ["a", "A", "e", "f", "g", "div", "h", "div", "I", "j", "A"]);
    assert(refs("const f = <><my-el x-y=\"1\">a > b</my-el></>;", true) == ["f", "my-el", "my-el"]);
}

unittest // decision points
{
    const code = "if (a && b || c ?? d) {} else if (e) {} for (;;) {} for (x of y) {} while (z) {} do {} while (w);\n"
        ~ "switch (v) { case 1: break; default: } try {} catch {} x &&= y; let t: A extends B ? C : D; o?.p;";
    assert(decisions(code, false) == ["if", "&&", "||", "??", "if", "for", "for", "while", "do", "case", "catch"]);
}

unittest // declarations
{
    with (NodeKind)
    {
        const code = "import a, { b as c, type d } from 'm';\nimport * as n from 'n';\nimport x = require('x');\n"
            ~ "export default class {}\nexport declare const q: number;\n@dec export abstract class P {}\n"
            ~ "declare global { interface Window {} }\nnamespace A.B { export const c = 1; }\ndeclare module 'm' { }\n"
            ~ "export const enum E { A }\ntype T = { a: 1 };\nfunction f(): void;\nfunction f() {}\nlet type = 1;\n";
        assert(top(code, false) == [import_, import_, import_, exportDefault, export_, export_, ambient, module_,
            ambient, export_, typeAlias, functionSig, functionDecl, varDecl]);
        auto t = parse("class C { @d x = 1; @d m() {} static { } [k: string]: any; constructor(private a, readonly b) {} o(): void; }", false);
        const body_ = t.field(t.children(t.root)[0], Field.body_);
        assert(body_ != none);
        NodeKind[] kinds;
        foreach (c; t.children(body_)) kinds ~= t.kind(c);
        assert(kinds == [fieldDef, decorator, methodDef, staticBlock, indexSig, methodDef, methodSig]);
        // a field's span includes its decorators, a method's does not
        assert(t.text(t.children(body_)[0]) == "@d x = 1");
        assert(t.text(t.children(body_)[2]) == "m() {}");
    }
}

unittest // line spans
{
    auto t = parse("\nconst x = 1 // c\n\nclass A {\n  m() {\n  }\n}\n", false);
    const kids = t.children(t.root);
    assert(t.firstLine(kids[0]) == 2 && t.lastLine(kids[0]) == 2);
    assert(t.firstLine(kids[1]) == 4 && t.lastLine(kids[1]) == 7);
}

unittest // deep nesting is an error, not a crash
{
    onSmallStack({
        // (prefix, opening part, closing part, suffix), nested 20 000 times and 100 times
        static immutable string[4][] cases = [
            ["", "(", ")", ""], ["", "[", "]", ""], ["", "{", "}", ""], ["", "a = ", "", ""], ["", "!", "", ""],
            ["", "x ? ", " : y", ""], ["", "2 ** ", "2", ""], ["", "<div>", "</div>", ""], ["type T = ", "A<", ">", ""],
            ["", "`${", "}`", ""], ["", "new ", "", ""], ["", "if (x) ", "", ""], ["", "(a) => ", "a", ""],
            ["", "function f() {", "}", ""], ["let ", "[", "]", " = y"], ["type T = ", "keyof ", "", ""],
        ];
        foreach (c; cases)
        {
            const code = c[0] ~ c[1].replicate(20_000) ~ "x" ~ c[2].replicate(20_000) ~ c[3];
            assert(parse(code, true).hasError, c[1]);
            // modest nesting is fine
            const small = c[0] ~ c[1].replicate(100) ~ "x" ~ c[2].replicate(100) ~ c[3];
            assert(!parse(small, true).hasError, c[1]);
        }
        const chain = "if (a) {}" ~ " else if (b) {}".replicate(20_000);
        assert(!parse(chain, false).hasError);
    });
}

unittest // the corpus parses cleanly
{
    auto t = parse(corpus, true);
    assert(!t.hasError, format("syntax error at %s: %s", t.firstError, corpus[t.firstError .. $][0 .. 40]));
}

unittest // fuzzed input never throws
{
    onSmallStack({
        // A deterministic linear congruential generator, so failures are reproducible.
        ulong seed = 0x2545F4914F6CDD1D;
        size_t next(size_t n)
        {
            seed = seed * 6364136223846793005UL + 1442695040888963407UL;
            return cast(size_t)(seed >> 33) % (n < 1 ? 1 : n);
        }
        auto base = cast(immutable(ubyte)[]) corpus;
        static immutable alphabet = cast(immutable(ubyte)[]) "(){}[]<>/`'\"$#@!?:;,.=+-*&|\\\n x0";
        foreach (round; 0 .. 1500)
        {
            ubyte[] bytes = base.dup;
            foreach (_; 0 .. 1 + next(8))
            {
                const at = next(bytes.length + 1);
                // delete up to 20 bytes, insert one byte from the alphabet, or truncate (also when there is
                // nothing to delete)
                const op = next(3);
                if (op == 0 && bytes.length && at < bytes.length)
                {
                    const avail = bytes.length - at - 1;
                    const r = next(20);
                    const len = 1 + (r < avail ? r : avail);
                    bytes = bytes[0 .. at] ~ bytes[at + len .. $];
                }
                else if (op == 1) bytes = bytes[0 .. at] ~ alphabet[next(alphabet.length)] ~ bytes[at .. $];
                else bytes = bytes[0 .. at];
            }
            // The analyser receives valid UTF-8 (the walker maps invalid bytes to Latin-1 characters); do the same.
            string code;
            import std.utf : validate;
            try
            {
                validate(cast(string) bytes);
                code = cast(string) bytes.idup;
            }
            catch (Exception)
            {
                dchar[] chars;
                foreach (b; bytes) chars ~= cast(dchar) b;
                code = chars.to!string;
            }
            auto t = parse(code, round % 2 == 0);
            checkTree(t);
        }
        // binary garbage
        dchar[] garbage;
        foreach (_; 0 .. 20_000) garbage ~= cast(dchar) next(256);
        auto g = parse(garbage.to!string, true);
        checkTree(g);
    });
}
