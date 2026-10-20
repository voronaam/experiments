/// A small, dependency-free Rust lexer and error-tolerant structural parser, tailored to what the Rust analyser
/// (`codecity.analysis.rustlang`) needs. (It replaced tree-sitter-rust 0.24; its classification rules reproduce that
/// grammar's tree where the analyser looks, so analysis results did not change.) It is a port of the Rust edition's `rust_parse.rs`, token kind for token kind and rule for rule,
/// so both editions produce the same results by construction.
///
/// What the analyser needs:
/// - the items of a file and of inline modules (`use`, `struct`, `union`, `enum`, `trait`, `impl`, `fn`, `const`,
///   `static`, `mod`) with names, line spans and outer attributes (for `#[cfg(test)]`);
/// - per struct/union/enum the number of fields/variants, per function the number of (non-`self`) parameters;
/// - the header of an `impl` (the implemented trait and type);
/// - per item the names it refers to, classified by syntactic position (see "Reference leaves");
/// - per function body its decision points (see "Complexity leaves");
/// - whether the file had syntax errors.
///
/// Everything else (expressions, patterns, types) is parsed only to classify names correctly and to find the decision
/// points, and leaves no nodes behind.
///
/// Lexer: `lex` turns the source into a flat token array. Token kinds: `ident` (identifiers *and* keywords, including
/// raw identifiers `r#x`; keywords are recognised by text in the parser), `lifetime` (`'a`, also used for labels),
/// `literal` (numbers, strings, byte/C/raw strings, chars) and `punct` (exactly one ASCII punctuation character).
/// Multi-character operators (`::`, `&&`, `->`, `..=`, …) are not tokens: each `punct` records whether the next token
/// is a `punct` that follows without any gap (`joint`), and the parser combines characters where an operator is
/// valid. This is how `>>` can close two generic argument lists and `&&x` can be two borrows, while `a && b` is still
/// a logical and.
///
/// Comments and whitespace produce no tokens, but a token remembers whether a comment preceded it (`commentBefore`):
/// a comment between an attribute and its item detaches the attribute from the item, which matters for
/// `#[cfg(test)]` detection. Delimiters `()[]{}` are matched by the lexer (`pair`), so the parser can always find the
/// end of a group even in broken code.
///
/// Lines are 1-based and count `\n` only (as `text.lines` does); `line`/`endLine` are the lines of a
/// token's first and last character.
///
/// Syntax tree: the parser produces nodes in a flat array in pre-order: a node's descendants are the nodes at indices
/// `i + 1 .. nodes[i].end`, its children are found by jumping from one child's `end` to the next. Each node covers the
/// tokens `first .. last` (inclusive). Node kinds are listed at `NodeKind`; `name` and `value` carry kind-specific
/// data.
///
/// Reference leaves: the analyser collects referenced names from these leaves, so the parser emits one for every name
/// of these kinds:
/// - `NodeKind.typeIdent`: a single-segment name in type position (`Foo`, `T`, `_`,
///   but not primitive types such as `u32`), the name of a struct/enum/union/trait/type alias, a generic type
///   parameter, a struct literal or struct pattern name (`Foo { .. }`). Always a reference.
/// - `NodeKind.ident`: a single-segment name in expressions and patterns, function, const,
///   static, module and enum-variant names, lifetimes and labels (token text includes the `'`), and every identifier
///   inside a macro's token tree. A reference only if capitalised.
/// - `NodeKind.path`: a path with `::`. The whole text is
///   the reference, and nothing inside it is looked at, so a path with generic arguments in the middle
///   (`Vec::<T>::new`) or a long qualified path (`<T as Tr>::A::B`) becomes one `path` node whose children are
///   ignored. Generic arguments at the end (`a::B<C>`, `f::<T>()`) are not part of the path; they follow as siblings.
///   A qualified path with one segment after the brackets (`<T as Tr>::Item`) emits no `path` at all: only the
///   types inside the brackets are references, and those are emitted as usual.
/// - `use` trees (`NodeKind.usePath` etc.) are kept structured for the analyser and act as references too.
///
/// Complexity leaves: `NodeKind.if_` (one per `if` keyword of an `if` expression, so `else if` counts; match guards do
/// not), `while_`, `loop`, `for_`, `match`, `arm` and `andOr` (a binary `&&` or `||`). An
/// `&&` that joins a `let` condition (`if let A = b && c`) is a "let chain", not a binary expression, and does not
/// count; nothing inside a macro's token tree counts (macro arguments are not parsed).
///
/// Grammar subset: items: everything stable Rust has (with visibility, `unsafe`/`const`/`async`/`extern "abi"`/
/// `default`/`safe` qualifiers, generics, where clauses), `macro_rules!` definitions and item-position macro calls.
/// Expressions: the full precedence ladder, closures, blocks (`unsafe`/`async`/`const`/`try`/`gen`, labelled),
/// `if`/`if let` with let chains, `match` with guards, loops, struct literals (not allowed in `if`/`while`/`match`/
/// `for` headers, as in Rust), ranges, casts, `?`, `.await`, turbofish. Patterns and types: all stable forms.
/// Statements: `let` (with `else`), items, expression statements (a block-like expression such as `if … {}` ends a
/// statement unless followed by `.` or `?`).
///
/// Error recovery: the parser never throws and always terminates:
/// - Every delimited group is parsed with a hard limit at its closing delimiter. Tokens left over inside a group are
///   skipped (setting the error flag), and parsing resumes after the group. So an error never spreads beyond the
///   innermost group it occurs in.
/// - Item and statement lists skip one token (or one whole group) when nothing could be parsed at a position.
/// - Recursion depth is capped (`maxDepth`); beyond it the rest of the current group is skipped as an error. The cap
///   keeps the native stack use small (a few hundred frames), well within any thread's default stack.
/// - Unmatched delimiters, unterminated literals/comments and unknown characters set the error flag.
/// The error flag is the analyser's "file had parse errors"; on valid code it is never set.
module codecity.analysis.rust_parse;

import std.ascii : isAlpha, isAlphaNum, isDigit, isPunctuation;

/// Marks "no token" in `Node.name` and "no matching delimiter" in `Token.pair`.
enum uint none = uint.max;

/// Marks "no such node" in the results of `Tree.child`.
enum size_t noNode = size_t.max;

/// Maximum nesting of parse functions (expressions, types, patterns, blocks, item lists, use groups). Real code nests
/// a few dozen levels at most; the cap keeps hostile input from overflowing the stack.
enum uint maxDepth = 200;

enum TokKind : ubyte
{
    /// An identifier or keyword (`foo`, `fn`, `Self`, `_`, `r#type`).
    ident,
    /// `'a`, `'static`, or a label such as `'outer`.
    lifetime,
    /// Number, string, byte string, raw string, C string or char literal.
    literal,
    /// One ASCII punctuation character, including the delimiters.
    punct,
}

struct Token
{
    TokKind kind;
    /// Byte range in the source.
    uint start;
    uint end;
    /// 1-based lines of the first and the last character.
    uint line;
    uint endLine;
    /// Punct only: the next token is a punct that starts exactly where this one ends.
    bool joint;
    /// A comment lies between the previous token and this one.
    bool commentBefore;
    /// For `(`, `[`, `{`: index of the matching closer, and vice versa. `none` if unmatched or not a delimiter.
    uint pair = none;
}

/// The kinds of syntax tree nodes. Only what the analyser needs is represented; see the module documentation.
enum NodeKind : ubyte
{
    /// The whole file; its children are the top-level items.
    file,
    /// Outer attribute `#[…]`. Inner attribute `#![…]`.
    attr,
    innerAttr,
    /// A comment between two items of an item list (a leaf on the token after the comment).
    comment,
    /// `use` declaration; its use tree is the last child.
    use,
    /// A path inside a use tree (`a::b::C`), a leaf. `usePrefix`: the `a::b` of `a::b::{…}` / `a::b::*`.
    usePath,
    usePrefix,
    /// `path as alias`: child `usePath`, `name` = alias token.
    useAs,
    /// `prefix::{…}` or `{…}`: optional `usePrefix` child, then the nested use trees.
    useGroup,
    /// `prefix::*` or `*`: optional `usePrefix` child.
    useGlob,
    /// Items. `name` = name token (or none). `struct_`/`union_`: `value` = number of fields; `enum_`: number of
    /// variants; `fn`: number of counted parameters (`self` is not counted).
    struct_,
    union_,
    enum_,
    trait,
    impl,
    fn,
    const_,
    static_,
    mod,
    typeAlias,
    macroRules,
    externCrate,
    foreignMod,
    /// `impl` header parts: the trait and the implemented type. `name`/`value` = first/last token of the type's
    /// "core": `&'a mut T` → `T`, `S<T>` → `S`, otherwise the whole type.
    implTrait,
    implType,
    /// A function's body block.
    body_,
    /// `{ items }` of a `mod`, `trait`, `impl` or `extern` block.
    declBody,
    /// Any other statement in an item list (macro call, `;`, stray expression).
    other,
    /// Reference leaves (see module docs).
    typeIdent,
    ident,
    path,
    /// Complexity leaves (see module docs).
    if_,
    while_,
    loop,
    for_,
    match,
    arm,
    andOr,
}

struct Node
{
    NodeKind kind;
    /// First and last token (inclusive).
    uint first;
    uint last;
    /// One past the last descendant: descendants are `i + 1 .. end`.
    uint end;
    /// Kind-specific token index (see `NodeKind`), `none` if unused.
    uint name = none;
    /// Kind-specific number (see `NodeKind`).
    uint value;
}

/// Lexed and parsed file.
struct Tree
{
    Token[] tokens;
    Node[] nodes;
    /// True if the lexer or the parser met anything that is not valid Rust.
    bool errors;
    /// Index of the token at which the parser first found an error (0 for lexer errors, `none` without errors).
    /// Only read by diagnostics and tests.
    uint firstError = none;

    /// Source text of one token ("" for `none`).
    string tokText(string code, uint tok) const @safe pure nothrow @nogc
    {
        if (tok >= tokens.length) return "";
        const t = tokens[tok];
        return t.end <= code.length ? code[t.start .. t.end] : "";
    }

    /// Source text from the start of token `first` to the end of token `last`, comments and whitespace included.
    string rangeText(string code, uint first, uint last) const @safe pure nothrow @nogc
    {
        if (first >= tokens.length || last >= tokens.length) return "";
        const a = tokens[first], b = tokens[last];
        return a.start <= b.end && b.end <= code.length ? code[a.start .. b.end] : "";
    }

    string nodeText(string code, size_t n) const @safe pure nothrow @nogc
    {
        return rangeText(code, nodes[n].first, nodes[n].last);
    }

    /// 1-based line of the node's first token.
    uint firstLine(size_t n) const @safe pure nothrow @nogc
    {
        return nodes[n].first < tokens.length ? tokens[nodes[n].first].line : 1;
    }

    /// 1-based line of the node's last character.
    uint lastLine(size_t n) const @safe pure nothrow @nogc
    {
        return nodes[n].last < tokens.length ? tokens[nodes[n].last].endLine : 1;
    }

    /// Indices of the direct children of node `n`, in order.
    size_t[] children(size_t n) const @safe pure nothrow
    {
        size_t[] output;
        size_t c = n + 1;
        const end = nodes[n].end;
        while (c < end)
        {
            output ~= c;
            c = nodes[c].end;
        }
        return output;
    }

    /// The first direct child of the given kind, or `noNode`.
    size_t child(size_t n, NodeKind kind) const @safe pure nothrow @nogc
    {
        size_t c = n + 1;
        const end = nodes[n].end;
        while (c < end)
        {
            if (nodes[c].kind == kind) return c;
            c = nodes[c].end;
        }
        return noNode;
    }
}

/// Lexes and parses one file.
Tree parse(string code)
{
    bool lexErrors;
    auto tokens = lex(code, lexErrors);
    Parser p;
    p.src = code;
    p.toks = tokens;
    p.limit = tokens.length;
    p.nodes.reserve(tokens.length / 3 + 4);
    p.errors = lexErrors;
    p.firstError = lexErrors ? 0 : none;
    const f = p.open(NodeKind.file);
    p.parseItems(true);
    p.close(f);
    return Tree(tokens, p.nodes, p.errors, p.firstError);
}

// ------------------------------------------------------------------ lexer

private bool isIdentStart(ubyte c) @safe pure nothrow @nogc
{
    return isAlpha(c) || c == '_' || c >= 0x80;
}

private bool isIdentContinue(ubyte c) @safe pure nothrow @nogc
{
    return isAlphaNum(c) || c == '_' || c >= 0x80;
}

/// Length in bytes of the UTF-8 character starting with byte `c`.
private size_t utf8Len(ubyte c) @safe pure nothrow @nogc
{
    return c < 0xC0 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
}

/// End of a `"…"` string whose opening quote is at `i` (backslash escapes skipped); `ok`: whether it was terminated.
private size_t quotedEnd(const(ubyte)[] b, size_t i, out bool ok) @safe pure nothrow @nogc
{
    size_t j = i + 1;
    while (j < b.length)
    {
        if (b[j] == '\\') j += 2;
        else if (b[j] == '"')
        {
            ok = true;
            return j + 1;
        }
        else j++;
    }
    return b.length;
}

/// If a raw string (`r"…"`, `r#"…"#`, …) starts with the `r` at `i`: true, with its end and whether it was
/// terminated.
private bool rawStringEnd(const(ubyte)[] b, size_t i, out size_t end, out bool ok) @safe pure nothrow @nogc
{
    size_t j = i + 1;
    size_t hashes;
    while (j < b.length && b[j] == '#')
    {
        hashes++;
        j++;
    }
    if (j >= b.length || b[j] != '"') return false;
    j++;
    while (j < b.length)
    {
        if (b[j] == '"' && b.length - (j + 1) >= hashes)
        {
            bool all = true;
            foreach (c; b[j + 1 .. j + 1 + hashes]) if (c != '#') all = false;
            if (all)
            {
                end = j + 1 + hashes;
                ok = true;
                return true;
            }
        }
        j++;
    }
    end = b.length;
    return true;
}

/// End of a number literal starting at `i`: digits, letters (suffixes, hex digits), `_`, one decimal point followed
/// by a digit (or not followed by `.` or an identifier: `1.`), and an exponent sign after `e`/`E`.
private size_t numberEnd(const(ubyte)[] b, size_t i) @safe pure nothrow @nogc
{
    const n = b.length;
    const hex = b[i] == '0' && i + 1 < n && (b[i + 1] == 'x' || b[i + 1] == 'o' || b[i + 1] == 'b');
    size_t j = i;
    bool dot = hex;
    while (j < n)
    {
        const c = b[j];
        if (isAlphaNum(c) || c == '_')
        {
            if (!hex && (c == 'e' || c == 'E') && j + 1 < n && (b[j + 1] == '+' || b[j + 1] == '-'))
            {
                if (j + 2 < n && isDigit(b[j + 2]))
                {
                    j += 3;
                    continue;
                }
            }
            j++;
        }
        else if (c == '.' && !dot)
        {
            const ubyte next = j + 1 < n ? b[j + 1] : 0;
            if (isDigit(next))
            {
                dot = true;
                j++;
            }
            else if (next != '.' && !isIdentStart(next))
                return j + 1; // `1.`
            else
                break;
        }
        else
            break;
    }
    return j;
}

/// If a char literal starts with the quote at `i` (`'a'`, `'\n'`, `'\u{1F600}'`): true, with its end and whether it
/// was terminated. False means it is not a char literal (a lifetime, or a lone quote).
private bool charEnd(const(ubyte)[] b, size_t i, out size_t end, out bool ok) @safe pure nothrow @nogc
{
    const n = b.length;
    if (i + 1 >= n) return false;
    if (b[i + 1] == '\\')
    {
        // An escape: scan to the closing quote on the same line.
        size_t j = i + 3;
        while (j < n && b[j] != '\'' && b[j] != '\n') j++;
        if (j < n && b[j] == '\'')
        {
            end = j + 1;
            ok = true;
        }
        else
            end = j < n ? j : n;
        return true;
    }
    const len = utf8Len(b[i + 1]);
    if (i + 1 + len < n && b[i + 1 + len] == '\'' && b[i + 1] != '\n')
    {
        end = i + 2 + len;
        ok = true;
        return true;
    }
    return false;
}

/// Splits the source into tokens; `errors` tells whether anything was malformed (unterminated literal or comment,
/// unknown character, unbalanced delimiter).
Token[] lex(string code, out bool errors) @safe pure nothrow
{
    auto b = cast(const(ubyte)[]) code;
    const n = b.length;
    Token[] toks;
    toks.reserve(n / 5 + 4);
    size_t i;
    uint line = 1;
    bool comment;
    // A shebang line (`#!/usr/bin/env …`, but not an inner attribute `#![…]`) is skipped.
    if (n >= 2 && b[0] == '#' && b[1] == '!')
    {
        size_t j = 2;
        while (j < n && (b[j] == ' ' || b[j] == '\t' || b[j] == '\r' || b[j] == 0x0b || b[j] == 0x0c)) j++;
        if (j >= n || b[j] != '[')
            while (i < n && b[i] != '\n') i++;
    }
    while (i < n)
    {
        const c = b[i];
        if (c == '\n')
        {
            line++;
            i++;
            continue;
        }
        if (c == ' ' || c == '\t' || c == '\r' || c == 0x0b || c == 0x0c)
        {
            i++;
            continue;
        }
        const ubyte next = i + 1 < n ? b[i + 1] : 0;
        if (c == '/' && next == '/')
        {
            while (i < n && b[i] != '\n') i++;
            comment = true;
            continue;
        }
        if (c == '/' && next == '*')
        {
            // Block comments nest.
            size_t depth = 1;
            i += 2;
            while (i < n && depth > 0)
            {
                if (b[i] == '/' && i + 1 < n && b[i + 1] == '*')
                {
                    depth++;
                    i += 2;
                }
                else if (b[i] == '*' && i + 1 < n && b[i + 1] == '/')
                {
                    depth--;
                    i += 2;
                }
                else
                {
                    if (b[i] == '\n') line++;
                    i++;
                }
            }
            if (depth > 0) errors = true;
            comment = true;
            continue;
        }
        const start = i;
        TokKind kind;
        size_t end;
        bool ok;
        if ((c == 'r' || ((c == 'b' || c == 'c') && next == 'r')) && rawStringEnd(b, c == 'r' ? i : i + 1, end, ok))
        {
            errors |= !ok;
            i = end;
            kind = TokKind.literal;
        }
        else if ((c == 'b' || c == 'c') && next == '"')
        {
            i = quotedEnd(b, i + 1, ok);
            errors |= !ok;
            kind = TokKind.literal;
        }
        else if (c == 'b' && next == '\'')
        {
            if (!charEnd(b, i + 1, end, ok))
            {
                end = i + 2;
                ok = false;
            }
            errors |= !ok;
            i = end;
            kind = TokKind.literal;
        }
        else if (c == 'r' && next == '#' && i + 2 < n && isIdentStart(b[i + 2]))
        {
            i += 2;
            while (i < n && isIdentContinue(b[i])) i++;
            kind = TokKind.ident;
        }
        else if (isIdentStart(c))
        {
            while (i < n && isIdentContinue(b[i])) i++;
            kind = TokKind.ident;
        }
        else if (isDigit(c))
        {
            i = numberEnd(b, i);
            kind = TokKind.literal;
        }
        else if (c == '"')
        {
            i = quotedEnd(b, i, ok);
            errors |= !ok;
            kind = TokKind.literal;
        }
        else if (c == '\'')
        {
            if (charEnd(b, i, end, ok))
            {
                errors |= !ok;
                i = end;
                kind = TokKind.literal;
            }
            else if (i + 1 < n && isIdentStart(b[i + 1]))
            {
                i++;
                if (b[i] == 'r' && i + 2 < n && b[i + 1] == '#' && isIdentStart(b[i + 2])) i += 2;
                while (i < n && isIdentContinue(b[i])) i++;
                kind = TokKind.lifetime;
            }
            else
            {
                errors = true;
                i++;
                kind = TokKind.punct;
            }
        }
        else
        {
            // Any other ASCII character: punctuation. Characters Rust does not use (`` ` ``, control characters) are
            // errors but still become tokens so that nothing is lost.
            if (!isPunctuation(c) || c == '`' || c == '\\') errors = true;
            i++;
            kind = TokKind.punct;
        }
        uint endLine = line;
        foreach (x; b[start .. i]) if (x == '\n') endLine++;
        if (toks.length && toks[$ - 1].kind == TokKind.punct && kind == TokKind.punct && toks[$ - 1].end == start)
            toks[$ - 1].joint = true;
        toks ~= Token(kind, cast(uint) start, cast(uint) i, line, endLine, false, comment, none);
        line = endLine;
        comment = false;
    }
    errors |= matchDelimiters(b, toks);
    return toks;
}

/// Pairs `(`/`)`, `[`/`]`, `{`/`}`. A closer that does not match the innermost open delimiter is left unpaired if no
/// open delimiter matches it, otherwise the delimiters opened after that match stay unpaired. Returns whether any
/// delimiter stayed unpaired.
private bool matchDelimiters(const(ubyte)[] b, Token[] toks) @safe pure nothrow
{
    // The open delimiters (token indices), innermost last, each with the stack index of the previous one of the same
    // kind (-1 for none); and the innermost of each kind. A closer thus finds its opener without scanning the stack,
    // which made a long run of unmatched closers quadratic.
    size_t[] stack;
    ptrdiff_t[] prev;
    size_t size;
    ptrdiff_t[3] top = -1;
    bool errors;
    foreach (k; 0 .. toks.length)
    {
        if (toks[k].kind != TokKind.punct) continue;
        const c = b[toks[k].start];
        const kind = delimiterKind(c);
        if (kind < 0) continue;
        if (c == '(' || c == '[' || c == '{')
        {
            if (size == stack.length)
            {
                stack.length = size * 2 + 16;
                prev.length = size * 2 + 16;
            }
            prev[size] = top[kind];
            top[kind] = size;
            stack[size++] = k;
            continue;
        }
        const at = top[kind];
        if (at < 0)
        {
            errors = true;
            continue;
        }
        if (at + 1 != size) errors = true;
        const o = stack[at];
        // Close it and everything opened after it.
        while (size > at)
        {
            size--;
            top[delimiterKind(b[toks[stack[size]].start])] = prev[size];
        }
        toks[o].pair = cast(uint) k;
        toks[k].pair = cast(uint) o;
    }
    return errors || size > 0;
}

/// 0 for `(` and `)`, 1 for `[` and `]`, 2 for braces, -1 for any other byte.
private ptrdiff_t delimiterKind(ubyte c) @safe pure nothrow @nogc
{
    switch (c)
    {
    case '(', ')': return 0;
    case '[', ']': return 1;
    case '{', '}': return 2;
    default: return -1;
    }
}

// ------------------------------------------------------------------ parser

/// Primitive types: in type position they produce no `NodeKind.typeIdent` leaf (they are no references).
private immutable string[] primitives = [
    "u8", "i8", "u16", "i16", "u32", "i32", "u64", "i64", "u128", "i128", "isize", "usize", "f32", "f64", "bool",
    "str", "char",
];

/// The item a statement starts with (see `Parser.itemStart`).
private enum ItemKw
{
    fn,
    struct_,
    union_,
    enum_,
    trait,
    impl,
    mod,
    use,
    type,
    const_,
    static_,
    externCrate,
    foreignMod,
    macroRules,
}

/// Where a path occurs; decides whether `<` starts generic arguments and how a single-segment path is classified.
private enum PathCtx
{
    expr,
    type,
    pattern,
}

/// What `Parser.parsePath` found. Nodes for the path itself are emitted by `Parser.emitPath`.
private struct PathInfo
{
    /// First token, and one past the last token of the "name part" (the path without trailing generic arguments).
    size_t start;
    size_t nameEnd;
    /// Node count when the path started (nodes for a qualified `<…>` prefix or middle generics follow it).
    size_t mark;
    /// Node count where trailing generic arguments start (equal to the node count after the path if none).
    size_t tailMark;
    /// Number of `::`-separated segments, not counting a `<…>` prefix.
    uint segs;
    /// Starts with `<T as Tr>` / `<T>`.
    bool bracket;
    /// Starts with `::`.
    bool leading;
    /// Generic arguments before the last segment (`Vec::<T>::new`).
    bool mid;
    /// Generic arguments after the last segment (`Vec<T>`, `f::<T>`).
    bool tailArgs;

    bool single() const @safe pure nothrow @nogc
    {
        return segs == 1 && !bracket && !leading && !mid;
    }
}

/// Facts about a parsed expression that its context needs.
private struct ExprInfo
{
    /// Ends with a block and needs no `;` as a statement (`if`, `match`, loops, blocks, brace macros).
    bool blockLike;
    /// The whole expression is one macro call (needs no `;` as a statement either).
    bool macroCall;
    /// A `let` condition or a let chain (its `&&`s are not binary expressions).
    bool isLet;
}

/// First and last token of a type's "core" (see `Parser.parseType`).
private struct Core
{
    size_t first;
    size_t last;
}

/// A binary operator (see `Parser.binop`).
private struct BinOp
{
    /// Number of tokens.
    size_t len;
    /// Left and right binding power.
    ubyte lbp;
    ubyte rbp;
    /// `&&` or `||`.
    bool logic;
    /// A range operator.
    bool range;
}

/// Saved parser state for backtracking (see `parseParams`).
private struct Checkpoint
{
    size_t pos;
    size_t nodes;
    bool errors;
    uint firstError;
}

private struct Parser
{
    string src;
    const(Token)[] toks;
    size_t pos;
    /// End of the current group: the index of its closing delimiter (or the token count at the top level). Nothing
    /// at or after it is ever looked at.
    size_t limit;
    Node[] nodes;
    bool errors;
    /// Token index of the first error found by the parser (for diagnostics and tests).
    uint firstError;
    uint depth;

    // ---------------------------------------------------------- token helpers

    bool atEnd() const { return pos >= limit; }

    string text(size_t i) const
    {
        const t = toks[i];
        return t.end <= src.length ? src[t.start .. t.end] : "";
    }

    /// The first byte of token `i` (its character, for a punct).
    char ch(size_t i) const { return src[toks[i].start]; }

    bool isKind(size_t i, TokKind k) const { return i < limit && toks[i].kind == k; }

    bool isIdent(size_t i) const { return isKind(i, TokKind.ident); }

    /// Token `i` is the punctuation character `c`.
    bool punctAt(size_t i, char c) const
    {
        return i < limit && toks[i].kind == TokKind.punct && ch(i) == c;
    }

    bool punct(char c) const { return punctAt(pos, c); }

    /// Tokens from `i` spell the operator `s` without gaps (`::`, `&&`, `..=`).
    bool opAt(size_t i, string s) const
    {
        foreach (k, c; s)
        {
            if (!punctAt(i + k, c)) return false;
            if (k + 1 < s.length && !toks[i + k].joint) return false;
        }
        return true;
    }

    bool op(string s) const { return opAt(pos, s); }

    bool kwAt(size_t i, string s) const { return isIdent(i) && text(i) == s; }

    bool kw(string s) const { return kwAt(pos, s); }

    /// Token `i` is one of the keywords `ks`.
    bool kwAnyAt(size_t i, scope string[] ks...) const
    {
        foreach (k; ks) if (kwAt(i, k)) return true;
        return false;
    }

    bool isOpener(size_t i) const { return punctAt(i, '(') || punctAt(i, '[') || punctAt(i, '{'); }

    /// A single `:` (not `::`).
    bool colonAt(size_t i) const { return punctAt(i, ':') && !opAt(i, "::"); }

    void bump()
    {
        if (pos < limit) pos++;
    }

    void bumpN(size_t n)
    {
        foreach (_; 0 .. n) bump();
    }

    bool eatPunct(char c)
    {
        if (!punct(c)) return false;
        bump();
        return true;
    }

    void expectPunct(char c)
    {
        if (!eatPunct(c)) err();
    }

    bool eatKw(string s)
    {
        if (!kw(s)) return false;
        bump();
        return true;
    }

    /// One past the last consumed token, minus one, but not before `start` (the last token of a construct that
    /// started at `start`).
    size_t lastFrom(size_t start) const
    {
        const l = pos > 0 ? pos - 1 : 0;
        return l > start ? l : start;
    }

    /// Skips one token, or a whole group if at an opening delimiter.
    void skipTree()
    {
        const p = pos < toks.length ? toks[pos].pair : none;
        if (isOpener(pos) && p != none && p < limit) pos = p + 1;
        else bump();
    }

    /// Enters the group opening at the current token: the limit becomes its closing delimiter. Returns false (error,
    /// nothing consumed) if not at a matched opener; otherwise `saved` is the outer limit, to be handed to
    /// `closeGroup`.
    bool openGroup(out size_t saved)
    {
        if (!isOpener(pos))
        {
            err();
            return false;
        }
        const p = toks[pos].pair;
        if (p == none || p >= limit)
        {
            err();
            return false;
        }
        saved = limit;
        limit = p;
        pos++;
        return true;
    }

    /// Leaves the current group: anything not parsed inside it is an error; continues after the closer.
    void closeGroup(size_t saved)
    {
        if (pos < limit) err();
        pos = limit + 1;
        limit = saved;
    }

    /// Counts one level of recursion. Beyond `maxDepth`, gives up on the rest of the current group.
    bool enter()
    {
        if (depth >= maxDepth)
        {
            err();
            pos = limit;
            return false;
        }
        depth++;
        return true;
    }

    void leave() { depth--; }

    Checkpoint checkpoint() const { return Checkpoint(pos, nodes.length, errors, firstError); }

    void restore(Checkpoint c)
    {
        pos = c.pos;
        nodes.length = c.nodes;
        nodes.assumeSafeAppend();
        errors = c.errors;
        firstError = c.firstError;
    }

    /// Records a syntax error at the current token.
    void err()
    {
        if (!errors) firstError = cast(uint)(pos < toks.length ? pos : toks.length);
        errors = true;
    }

    // ---------------------------------------------------------- node helpers

    /// Starts a node at the current token; finish it with `close`.
    size_t open(NodeKind kind) { return openAt(kind, pos); }

    size_t openAt(NodeKind kind, size_t first)
    {
        nodes ~= Node(kind, cast(uint) first, cast(uint) first, 0, none, 0);
        return nodes.length - 1;
    }

    /// Ends node `n` at the last consumed token; everything emitted since it was opened are its descendants.
    void close(size_t n)
    {
        const first = nodes[n].first;
        nodes[n].last = cast(uint)(pos > first ? pos - 1 : first);
        nodes[n].end = cast(uint) nodes.length;
    }

    /// Stores `value` in node `n`. A function rather than `nodes[n].value = parseX()`: D may take the address of
    /// `nodes[n]` before the call, which can reallocate `nodes` while it appends.
    void setValue(size_t n, uint value) { nodes[n].value = value; }

    void leaf(NodeKind kind, size_t tok) { leafRange(kind, tok, tok); }

    void leafRange(NodeKind kind, size_t first, size_t last)
    {
        nodes ~= Node(kind, cast(uint) first, cast(uint) last, cast(uint) nodes.length + 1, none, 0);
    }

    /// Inserts a node at index `at` whose descendants are the nodes `at .. coverEnd` emitted so far. Used where the
    /// kind of a node is only known after its contents were parsed (qualified paths, `impl` headers).
    size_t wrap(size_t at, size_t coverEnd, NodeKind kind, size_t first, size_t last)
    {
        foreach (ref n; nodes[at .. $]) n.end++;
        nodes ~= Node.init;
        foreach_reverse (k; at + 1 .. nodes.length) nodes[k] = nodes[k - 1];
        nodes[at] = Node(kind, cast(uint) first, cast(uint) last, cast(uint) coverEnd + 1, none, 0);
        return at;
    }

    // ---------------------------------------------------------- items

    /// Parses a list of items until the end of the current group. `top`: the file itself, where statements are
    /// accepted too; inside `{ … }` of a module, trait, impl or extern block only items and macro calls are
    /// valid.
    void parseItems(bool top)
    {
        if (!enter()) return;
        while (!atEnd())
        {
            const before = pos;
            if (toks[pos].commentBefore) leaf(NodeKind.comment, pos);
            ItemKw kw;
            size_t at;
            if (atAttr())
                parseAttr();
            else if (itemStart(pos, kw, at))
                parseItem(kw, at);
            else
            {
                const n = open(NodeKind.other);
                if (!top && !punct(';') && !isMacroCall(pos)) err();
                parseStmtRest();
                close(n);
            }
            if (pos == before)
            {
                err();
                skipTree();
            }
        }
        leave();
    }

    /// At `#[` or `#![`.
    bool atAttr() const
    {
        return punct('#') && (punctAt(pos + 1, '[') || (punctAt(pos + 1, '!') && punctAt(pos + 2, '[')));
    }

    /// Parses an outer (`#[…]`) or inner (`#![…]`) attribute: a path, then `= expr` or a token tree.
    void parseAttr()
    {
        const inner = punctAt(pos + 1, '!');
        const n = open(inner ? NodeKind.innerAttr : NodeKind.attr);
        bumpN(inner ? 2 : 1);
        size_t saved;
        if (openGroup(saved))
        {
            if (isIdent(pos) || op("::"))
            {
                const p = parsePath(PathCtx.expr);
                emitPath(p, NodeKind.ident);
            }
            else
                err();
            if (punct('='))
            {
                bump();
                parseExpr(false);
            }
            else if (isOpener(pos))
                tokenTree(false);
            closeGroup(saved);
        }
        else
            bump();
        close(n);
    }

    /// Skips outer attributes (still emitting their references), e.g. before fields, parameters and match arms.
    void parseOuterAttrs()
    {
        while (atAttr()) parseAttr();
    }

    /// If an item starts at token `at`: true, with its kind and the index of its keyword, after visibility and
    /// qualifiers (`pub(crate) const unsafe fn` → `fn` at `fn`). Contextual keywords (`union`, `auto`, `default`,
    /// `safe`, `macro_rules`) and words that also start expressions (`const {`, `unsafe {`, `async move`,
    /// `static ||`) are told apart by the following tokens.
    bool itemStart(size_t at, out ItemKw kw, out size_t kwPos) const
    {
        size_t j = at;
        if (kwAt(j, "pub"))
        {
            j++;
            if (punctAt(j, '(') && kwAnyAt(j + 1, "crate", "self", "super", "in"))
            {
                const p = toks[j].pair;
                if (p != none && p < limit) j = p + 1;
            }
        }
        bool found(ItemKw k, size_t where)
        {
            kw = k;
            kwPos = where;
            return true;
        }
        for (;;)
        {
            if (!isIdent(j)) return false;
            switch (text(j))
            {
            case "fn": return found(ItemKw.fn, j);
            case "struct": return found(ItemKw.struct_, j);
            case "enum": return found(ItemKw.enum_, j);
            case "trait": return found(ItemKw.trait, j);
            case "impl": return found(ItemKw.impl, j);
            case "mod": return found(ItemKw.mod, j);
            case "use": return found(ItemKw.use, j);
            case "type": return found(ItemKw.type, j);
            case "union":
                if (isIdent(j + 1) && (punctAt(j + 2, '{') || punctAt(j + 2, '<') || kwAt(j + 2, "where")))
                    return found(ItemKw.union_, j);
                return false;
            case "static":
                if (kwAnyAt(j + 1, "mut", "ref") || (isIdent(j + 1) && !kwAt(j + 1, "move") && colonAt(j + 2)))
                    return found(ItemKw.static_, j);
                return false;
            case "macro_rules":
                if (punctAt(j + 1, '!') && !opAt(j + 1, "!=")) return found(ItemKw.macroRules, j);
                return false;
            case "extern":
            {
                if (kwAt(j + 1, "crate")) return found(ItemKw.externCrate, j);
                const k = isKind(j + 1, TokKind.literal) ? j + 2 : j + 1;
                if (punctAt(k, '{')) return found(ItemKw.foreignMod, k);
                j = k;
                break;
            }
            case "const":
                if (kwAnyAt(j + 1, "fn", "unsafe", "async", "extern")) j++;
                else if (isIdent(j + 1)) return found(ItemKw.const_, j);
                else return false;
                break;
            case "async":
                if (!kwAnyAt(j + 1, "fn", "unsafe", "extern")) return false;
                j++;
                break;
            case "unsafe":
                if (!kwAnyAt(j + 1, "fn", "impl", "trait", "extern", "auto", "mod")) return false;
                j++;
                break;
            case "safe":
                if (!kwAnyAt(j + 1, "fn", "static")) return false;
                j++;
                break;
            case "default":
                if (!kwAnyAt(j + 1, "fn", "impl", "unsafe", "const", "async", "type", "extern")) return false;
                j++;
                break;
            case "auto":
                if (kwAt(j + 1, "trait")) return found(ItemKw.trait, j + 1);
                return false;
            default:
                return false;
            }
        }
    }

    /// `path!` followed by a delimiter at token `i`.
    bool isMacroCall(size_t i) const
    {
        size_t j = i;
        if (opAt(j, "::")) j += 2;
        for (;;)
        {
            if (!isIdent(j)) return false;
            j++;
            if (opAt(j, "::"))
            {
                j += 2;
                continue;
            }
            break;
        }
        return punctAt(j, '!') && !opAt(j, "!=") && isOpener(j + 1);
    }

    /// `pub`, `pub(crate)`, `pub(in a::b)`: only the `in` path has references.
    void parseVisibility()
    {
        if (!eatKw("pub")) return;
        if (punct('(') && kwAnyAt(pos + 1, "crate", "self", "super", "in"))
        {
            size_t saved;
            if (openGroup(saved))
            {
                if (eatKw("in"))
                {
                    const p = parsePath(PathCtx.expr);
                    emitPath(p, NodeKind.ident);
                }
                else
                    bump();
                closeGroup(saved);
            }
        }
    }

    /// Emits the name token of an item as a reference leaf of the given kind and stores it as the item's name.
    void itemName(size_t n, NodeKind kind)
    {
        if (isIdent(pos))
        {
            nodes[n].name = cast(uint) pos;
            leaf(kind, pos);
            bump();
        }
        else
            err();
    }

    /// Parses an item whose keyword (as found by `itemStart`) is at token `kwPos`.
    void parseItem(ItemKw kw, size_t kwPos)
    {
        NodeKind kind;
        final switch (kw)
        {
        case ItemKw.fn: kind = NodeKind.fn; break;
        case ItemKw.struct_: kind = NodeKind.struct_; break;
        case ItemKw.union_: kind = NodeKind.union_; break;
        case ItemKw.enum_: kind = NodeKind.enum_; break;
        case ItemKw.trait: kind = NodeKind.trait; break;
        case ItemKw.impl: kind = NodeKind.impl; break;
        case ItemKw.mod: kind = NodeKind.mod; break;
        case ItemKw.use: kind = NodeKind.use; break;
        case ItemKw.type: kind = NodeKind.typeAlias; break;
        case ItemKw.const_: kind = NodeKind.const_; break;
        case ItemKw.static_: kind = NodeKind.static_; break;
        case ItemKw.externCrate: kind = NodeKind.externCrate; break;
        case ItemKw.foreignMod: kind = NodeKind.foreignMod; break;
        case ItemKw.macroRules: kind = NodeKind.macroRules; break;
        }
        const n = open(kind);
        parseVisibility();
        // Qualifiers (`const`, `async`, `unsafe`, `extern "C"`, …) carry no references.
        if (kwPos >= pos && kwPos < limit) pos = kwPos;
        final switch (kw)
        {
        case ItemKw.fn:
            parseFn(n);
            break;
        case ItemKw.struct_, ItemKw.union_:
            bump();
            itemName(n, NodeKind.typeIdent);
            parseGenericParamsOpt();
            parseWhereOpt();
            if (punct('{'))
                setValue(n, parseFields());
            else if (kw == ItemKw.struct_ && punct('('))
            {
                setValue(n, parseTupleFields());
                parseWhereOpt();
                expectPunct(';');
            }
            else
                expectPunct(';');
            break;
        case ItemKw.enum_:
            bump();
            itemName(n, NodeKind.typeIdent);
            parseGenericParamsOpt();
            parseWhereOpt();
            setValue(n, parseVariants());
            break;
        case ItemKw.trait:
            bump();
            itemName(n, NodeKind.typeIdent);
            parseGenericParamsOpt();
            if (colonAt(pos))
            {
                bump();
                parseBoundsOpt();
            }
            parseWhereOpt();
            if (punct('='))
            {
                // trait alias
                bump();
                parseBoundsOpt();
                parseWhereOpt();
                expectPunct(';');
            }
            else
                parseDeclBody();
            break;
        case ItemKw.impl:
            parseImpl();
            break;
        case ItemKw.mod:
            bump();
            itemName(n, NodeKind.ident);
            if (!eatPunct(';')) parseDeclBody();
            break;
        case ItemKw.use:
            bump();
            parseUseTree();
            expectPunct(';');
            break;
        case ItemKw.type:
            bump();
            itemName(n, NodeKind.typeIdent);
            parseGenericParamsOpt();
            if (colonAt(pos))
            {
                bump();
                parseBoundsOpt();
            }
            parseWhereOpt();
            if (eatPunct('=')) parseType(true);
            parseWhereOpt();
            expectPunct(';');
            break;
        case ItemKw.const_, ItemKw.static_:
            bump();
            if (kw == ItemKw.static_)
            {
                eatKw("ref");
                eatKw("mut");
            }
            itemName(n, NodeKind.ident);
            parseGenericParamsOpt();
            if (colonAt(pos))
            {
                bump();
                parseType(true);
            }
            else
                err();
            if (eatPunct('=')) parseExpr(false);
            parseWhereOpt();
            expectPunct(';');
            break;
        case ItemKw.externCrate:
            bumpN(2);
            itemName(n, NodeKind.ident);
            if (eatKw("as"))
            {
                if (isIdent(pos))
                {
                    leaf(NodeKind.ident, pos);
                    bump();
                }
                else
                    err();
            }
            expectPunct(';');
            break;
        case ItemKw.foreignMod:
            parseDeclBody();
            break;
        case ItemKw.macroRules:
            bumpN(2);
            itemName(n, NodeKind.ident);
            const paren = !punct('{');
            if (isOpener(pos))
            {
                tokenTree(true);
                if (paren) expectPunct(';');
            }
            else
                err();
            break;
        }
        close(n);
    }

    /// `fn name<…>(params) -> T where … { body }` or `… ;` (a signature in a trait or extern block).
    void parseFn(size_t n)
    {
        bump();
        itemName(n, NodeKind.ident);
        parseGenericParamsOpt();
        if (punct('(')) setValue(n, parseParams());
        else err();
        if (op("->"))
        {
            bumpN(2);
            parseType(true);
        }
        parseWhereOpt();
        if (punct('{'))
        {
            const b = open(NodeKind.body_);
            parseBlock();
            close(b);
        }
        else
            expectPunct(';');
    }

    /// `impl<…> !? Trait for Type where … { items }`. The trait and type are wrapped into `implTrait`/`implType`
    /// nodes once it is known which is which (the first type is the trait iff `for` follows it).
    void parseImpl()
    {
        bump();
        if (punct('<')) parseGenericParams();
        eatKw("const");
        // `impl !Trait for T`; but `impl ! {}` implements for the never type
        if (punct('!') && canStartType(pos + 1)) bump();
        auto mark = nodes.length;
        auto start = pos;
        auto core = parseType(true);
        if (kw("for") && !punctAt(pos + 1, '<'))
        {
            const t = wrap(mark, nodes.length, NodeKind.implTrait, start, lastFrom(start));
            nodes[t].name = cast(uint) core.first;
            nodes[t].value = cast(uint) core.last;
            bump();
            mark = nodes.length;
            start = pos;
            core = parseType(true);
        }
        const t = wrap(mark, nodes.length, NodeKind.implType, start, lastFrom(start));
        nodes[t].name = cast(uint) core.first;
        nodes[t].value = cast(uint) core.last;
        parseWhereOpt();
        if (!eatPunct(';')) parseDeclBody();
    }

    /// `{ items }` of a module, trait, impl or extern block.
    void parseDeclBody()
    {
        if (!punct('{'))
        {
            err();
            return;
        }
        const b = open(NodeKind.declBody);
        size_t saved;
        if (openGroup(saved))
        {
            parseItems(false);
            closeGroup(saved);
        }
        close(b);
    }

    /// `{ attrs* vis? name: Type, … }`; returns the number of fields.
    uint parseFields()
    {
        uint count;
        size_t saved;
        if (!openGroup(saved)) return 0;
        while (!atEnd())
        {
            parseOuterAttrs();
            if (atEnd()) break;
            parseVisibility();
            if ((isIdent(pos) || isKind(pos, TokKind.literal)) && colonAt(pos + 1))
            {
                bumpN(2); // field names are not references
                parseType(true);
                count++;
            }
            else
            {
                err();
                break;
            }
            if (!eatPunct(',')) break;
        }
        closeGroup(saved);
        return count;
    }

    /// `( attrs* vis? Type, … )`; returns the number of fields.
    uint parseTupleFields()
    {
        uint count;
        size_t saved;
        if (!openGroup(saved)) return 0;
        while (!atEnd())
        {
            parseOuterAttrs();
            if (atEnd()) break;
            parseVisibility();
            const before = pos;
            parseType(true);
            if (pos == before) break;
            count++;
            if (!eatPunct(',')) break;
        }
        closeGroup(saved);
        return count;
    }

    /// `{ attrs* vis? Name ({…} | (…))? (= expr)?, … }`; returns the number of variants.
    uint parseVariants()
    {
        uint count;
        size_t saved;
        if (!openGroup(saved)) return 0;
        while (!atEnd())
        {
            parseOuterAttrs();
            if (atEnd()) break;
            parseVisibility();
            if (!isIdent(pos))
            {
                err();
                break;
            }
            // Variant names are `ident` leaves, so they are references when capitalised.
            leaf(NodeKind.ident, pos);
            bump();
            count++;
            if (punct('{')) parseFields();
            else if (punct('(')) parseTupleFields();
            if (eatPunct('=')) parseExpr(false);
            if (!eatPunct(',')) break;
        }
        closeGroup(saved);
        return count;
    }

    /// Function parameters `( … )`; returns how many count as parameters: `pattern: Type`, including
    /// `self: Box<Self>`, but not `self`/`&mut self`, variadic `...`, `_` or anonymous types (`fn(u8)`).
    uint parseParams()
    {
        uint count;
        size_t saved;
        if (!openGroup(saved)) return 0;
        while (!atEnd())
        {
            parseOuterAttrs();
            if (atEnd()) break;
            const before = pos;
            // self parameter: `&'a mut self`, `mut self`, `self`
            size_t j = pos;
            bool amp;
            if (punctAt(j, '&'))
            {
                amp = true;
                j++;
                if (isKind(j, TokKind.lifetime)) j++;
            }
            if (kwAt(j, "mut")) j++;
            if (kwAt(j, "self") && !opAt(j + 1, "::"))
            {
                if (amp && isKind(pos + 1, TokKind.lifetime)) leaf(NodeKind.ident, pos + 1);
                pos = j + 1;
                if (!amp && colonAt(pos))
                {
                    bump();
                    parseType(true);
                    count++;
                }
            }
            else if (op("..."))
                bumpN(3);
            else if (kw("_") && (punctAt(pos + 1, ',') || pos + 1 >= limit))
                bump();
            else
            {
                const cp = checkpoint();
                eatKw("mut");
                parsePatternNoAlt();
                if (colonAt(pos))
                {
                    bump();
                    if (op("..."))
                        bumpN(3);
                    else
                    {
                        parseType(true);
                        count++;
                    }
                }
                else
                {
                    restore(cp);
                    parseType(true);
                }
            }
            if (pos == before)
            {
                err();
                break;
            }
            if (!eatPunct(',')) break;
        }
        closeGroup(saved);
        return count;
    }

    // ---------------------------------------------------------- use trees

    /// A use tree: `a::b`, `a::b as c`, `a::{…}`, `a::*`, `{…}`, `*`.
    void parseUseTree()
    {
        const start = pos;
        if (punct('{'))
        {
            const n = open(NodeKind.useGroup);
            parseUseList();
            close(n);
            return;
        }
        if (punct('*'))
        {
            leaf(NodeKind.useGlob, pos);
            bump();
            return;
        }
        size_t pathEnd = pos;
        if (op("::")) bumpN(2);
        while (isIdent(pos))
        {
            bump();
            pathEnd = pos;
            if (op("::") && isIdent(pos + 2)) bumpN(2);
            else break;
        }
        const hasPath = pathEnd > start;
        if ((op("::") && punctAt(pos + 2, '{')) || (!hasPath && punct('{')))
        {
            const n = openAt(NodeKind.useGroup, start);
            if (hasPath) leafRange(NodeKind.usePrefix, start, pathEnd - 1);
            if (op("::")) bumpN(2);
            parseUseList();
            close(n);
        }
        else if ((op("::") && punctAt(pos + 2, '*')) || (!hasPath && punct('*')))
        {
            const n = openAt(NodeKind.useGlob, start);
            if (hasPath) leafRange(NodeKind.usePrefix, start, pathEnd - 1);
            if (op("::")) bumpN(2);
            bump();
            close(n);
        }
        else if (!hasPath)
            err();
        else if (kw("as"))
        {
            const n = openAt(NodeKind.useAs, start);
            leafRange(NodeKind.usePath, start, pathEnd - 1);
            bump();
            if (isIdent(pos))
            {
                nodes[n].name = cast(uint) pos;
                bump();
            }
            else
                err();
            close(n);
        }
        else
            leafRange(NodeKind.usePath, start, pathEnd - 1);
    }

    /// `{ tree, … }` of a use group. Nested groups count towards `maxDepth` like any other recursion.
    void parseUseList()
    {
        size_t saved;
        if (!openGroup(saved)) return;
        if (enter())
        {
            while (!atEnd())
            {
                const before = pos;
                parseUseTree();
                if (pos == before || !eatPunct(',')) break;
            }
            leave();
        }
        closeGroup(saved);
    }

    // ---------------------------------------------------------- generics

    void parseGenericParamsOpt()
    {
        if (punct('<')) parseGenericParams();
    }

    /// `<'a: 'b, T: Bound = Default, const N: usize = 3>`. Type parameter names are `type_identifier`s.
    void parseGenericParams()
    {
        bump();
        while (!atEnd() && !punct('>'))
        {
            const before = pos;
            parseOuterAttrs();
            if (isKind(pos, TokKind.lifetime))
            {
                leaf(NodeKind.ident, pos);
                bump();
                if (colonAt(pos))
                {
                    bump();
                    parseBoundsOpt();
                }
            }
            else if (kw("const") && isIdent(pos + 1))
            {
                bump();
                leaf(NodeKind.ident, pos);
                bump();
                if (colonAt(pos))
                {
                    bump();
                    parseType(true);
                }
                if (eatPunct('='))
                {
                    if (punct('{'))
                        parseBlock();
                    else if (isIdent(pos))
                    {
                        leaf(NodeKind.ident, pos);
                        bump();
                    }
                    else
                    {
                        eatPunct('-');
                        if (isKind(pos, TokKind.literal)) bump();
                        else err();
                    }
                }
            }
            else if (isIdent(pos))
            {
                leaf(NodeKind.typeIdent, pos);
                bump();
                if (colonAt(pos))
                {
                    bump();
                    parseBoundsOpt();
                }
                if (eatPunct('=')) parseType(true);
            }
            else
            {
                err();
                break;
            }
            if (pos == before || !eatPunct(',')) break;
        }
        expectPunct('>');
    }

    /// Bounds after `:` (`'a + Clone + ?Sized + for<'b> Fn(&'b T)`), possibly empty.
    void parseBoundsOpt()
    {
        if (canStartType(pos)) parseType(true);
    }

    /// `where T: A + B, 'a: 'b, for<'c> &'c T: C` (ends before `{`, `;` or `=`).
    void parseWhereOpt()
    {
        if (!eatKw("where")) return;
        while (!atEnd())
        {
            const before = pos;
            if (isKind(pos, TokKind.lifetime))
            {
                leaf(NodeKind.ident, pos);
                bump();
            }
            else if (canStartType(pos))
            {
                if (kw("for") && punctAt(pos + 1, '<'))
                {
                    bump();
                    parseGenericParams();
                }
                parseType(false);
            }
            else
                break;
            if (colonAt(pos))
            {
                bump();
                parseBoundsOpt();
            }
            else
                err();
            if (pos == before || !eatPunct(',')) break;
        }
    }

    /// Generic arguments `<T, 'a, 3, {N}, Item = U, Item: Bound>` (at the `<`).
    void parseGenericArgs()
    {
        bump();
        while (!atEnd() && !punct('>'))
        {
            const before = pos;
            if (isKind(pos, TokKind.lifetime))
            {
                leaf(NodeKind.ident, pos);
                bump();
            }
            else if (isKind(pos, TokKind.literal) || kw("true") || kw("false"))
                bump();
            else if (punct('-') && isKind(pos + 1, TokKind.literal))
                bumpN(2);
            else if (punct('{'))
                parseBlock();
            else
            {
                parseType(true);
                if (punct('=') && !op("=="))
                {
                    bump();
                    parseType(true);
                }
            }
            if (colonAt(pos))
            {
                bump();
                parseBoundsOpt();
            }
            if (pos == before || !eatPunct(',')) break;
        }
        expectPunct('>');
    }

    // ---------------------------------------------------------- paths

    /// Parses a path (`a::b::C`, `::a`, `<T as Tr>::X`, `Vec::<T>::new`, `Vec<T>` in types) up to and including
    /// trailing generic arguments. Nodes for generic arguments and the `<…>` prefix are emitted; the path's own node
    /// is emitted by `emitPath` once the caller knows what follows.
    PathInfo parsePath(PathCtx ctx)
    {
        PathInfo p;
        p.start = pos;
        p.nameEnd = pos;
        p.mark = nodes.length;
        if (punct('<'))
        {
            p.bracket = true;
            bump();
            parseType(true);
            if (eatKw("as")) parseType(true);
            expectPunct('>');
            if (op("::")) bumpN(2);
            else err();
        }
        else if (op("::"))
        {
            p.leading = true;
            bumpN(2);
        }
        for (;;)
        {
            if (!isIdent(pos))
            {
                err();
                break;
            }
            bump();
            p.segs++;
            p.nameEnd = pos;
            const argsMark = nodes.length;
            bool args;
            if (ctx == PathCtx.type && punct('<') && !op("<="))
            {
                parseGenericArgs();
                args = true;
            }
            else if (op("::") && punctAt(pos + 2, '<'))
            {
                bumpN(2);
                parseGenericArgs();
                args = true;
            }
            if (args)
            {
                if (op("::") && isIdent(pos + 2))
                {
                    p.mid = true;
                    bumpN(2);
                    continue;
                }
                p.tailArgs = true;
                p.tailMark = argsMark;
                break;
            }
            if (op("::") && isIdent(pos + 2))
            {
                bumpN(2);
                continue;
            }
            break;
        }
        if (!p.tailArgs) p.tailMark = nodes.length;
        return p;
    }

    /// Emits the node for a parsed path (see "Reference leaves" in the module docs). `single`: the leaf kind for a
    /// one-segment path; without it, a one-segment path gets no node. `self`, `super` and `crate` alone are never
    /// identifiers.
    void emitPath(const PathInfo p, NodeKind single) { emitPathImpl(p, true, single); }

    /// ditto
    void emitPath(const PathInfo p) { emitPathImpl(p, false, NodeKind.ident); }

    void emitPathImpl(const PathInfo p, bool hasSingle, NodeKind single)
    {
        if (p.segs == 0) return;
        if (p.bracket && p.segs == 1 && !p.mid) return; // the types in the brackets were emitted already
        if (p.bracket || p.mid)
            wrap(p.mark, p.tailMark, NodeKind.path, p.start, p.nameEnd - 1);
        else if (p.single())
        {
            const t = text(p.start);
            if (hasSingle && t != "self" && t != "super" && t != "crate") leaf(single, p.start);
        }
        else
            leafRange(NodeKind.path, p.start, p.nameEnd - 1);
    }

    // ---------------------------------------------------------- types

    bool canStartType(size_t i) const
    {
        if (i >= limit) return false;
        final switch (toks[i].kind)
        {
        case TokKind.ident:
        {
            const t = text(i);
            return t != "where" && t != "as" && t != "in" && t != "else";
        }
        case TokKind.lifetime:
            return true;
        case TokKind.literal:
            return false;
        case TokKind.punct:
            const c = ch(i);
            return c == '(' || c == '[' || c == '&' || c == '*' || c == '!' || c == '?' || c == '<' || opAt(i, "::");
        }
    }

    /// Parses a type. `plus`: `A + B` bound lists are allowed (not after `as` or inside `&`/`*`). Returns the first
    /// and last token of the type's "core" as the analyser's impl-type name: `&'a mut T` → core of `T`, `a::B<T>` →
    /// `a::B`, anything else → the whole type.
    Core parseType(bool plus)
    {
        const start = pos;
        if (!enter()) return Core(start, start);
        auto core = Core(start, start);
        bool whole = true;
        if (atEnd())
            err();
        else if (punct('(') || punct('['))
        {
            const array = punct('[');
            size_t saved;
            if (openGroup(saved))
            {
                while (!atEnd())
                {
                    const before = pos;
                    parseType(true);
                    if (array && eatPunct(';')) parseExpr(false);
                    if (pos == before || !eatPunct(',')) break;
                }
                closeGroup(saved);
            }
            else
                bump();
        }
        else if (punct('&') || punct('*'))
        {
            const star = punct('*');
            bump();
            if (!star && isKind(pos, TokKind.lifetime))
            {
                leaf(NodeKind.ident, pos);
                bump();
            }
            if (!eatKw("mut") && star) eatKw("const");
            core = parseType(false);
            whole = false;
        }
        else if (punct('!'))
            bump();
        else if (punct('?'))
        {
            bump();
            parseType(false);
        }
        else if (isKind(pos, TokKind.lifetime))
        {
            leaf(NodeKind.ident, pos);
            bump();
        }
        else if (kw("impl") || kw("dyn"))
        {
            bump();
            parseType(plus);
        }
        else if (kw("for") && punctAt(pos + 1, '<'))
        {
            bump();
            parseGenericParams();
            parseType(false);
        }
        else if (kw("fn") || kw("unsafe") || kw("extern"))
        {
            eatKw("unsafe");
            if (eatKw("extern") && isKind(pos, TokKind.literal)) bump();
            if (eatKw("fn") && punct('('))
            {
                parseParams();
                if (op("->"))
                {
                    bumpN(2);
                    parseType(false);
                }
            }
            else
                err();
        }
        else if (kw("use") && punctAt(pos + 1, '<'))
        {
            // precise capturing `use<'a, T>`
            bump();
            parseGenericArgs();
        }
        else if (isIdent(pos) || op("::") || punct('<'))
        {
            const p = parsePath(PathCtx.type);
            bool primitive;
            if (p.single() && !p.tailArgs)
                foreach (prim; primitives) if (text(p.start) == prim) primitive = true;
            void emit()
            {
                if (primitive) emitPath(p);
                else emitPath(p, NodeKind.typeIdent);
            }
            if (!p.tailArgs && punct('!') && !op("!=") && isOpener(pos + 1))
            {
                emitPath(p, NodeKind.ident);
                bump();
                tokenTree(false);
            }
            else if (!p.tailArgs && punct('('))
            {
                // `Fn(A) -> B` sugar: a function type with the path as its trait
                emit();
                parseParams();
                if (op("->"))
                {
                    bumpN(2);
                    parseType(false);
                }
            }
            else
            {
                emit();
                const last = p.nameEnd > 0 ? p.nameEnd - 1 : 0;
                core = Core(p.start, last > p.start ? last : p.start);
                whole = false;
            }
        }
        else
            err();
        if (plus && punct('+') && !op("+="))
        {
            while (punct('+') && !op("+="))
            {
                bump();
                if (canStartType(pos)) parseType(false);
            }
            whole = true;
        }
        if (whole) core = Core(start, lastFrom(start));
        leave();
        return core;
    }

    // ---------------------------------------------------------- patterns

    /// A pattern with alternatives (`A | B`, also with a leading `|`).
    void parsePattern()
    {
        if (punct('|') && !op("||")) bump();
        parsePatternNoAlt();
        while (punct('|') && !op("||") && !op("|="))
        {
            bump();
            parsePatternNoAlt();
        }
    }

    /// One pattern without top-level alternatives (closure parameters cannot have them).
    void parsePatternNoAlt()
    {
        if (!enter()) return;
        if (atEnd())
            err();
        else if (isKind(pos, TokKind.literal))
        {
            bump();
            parseRangePatternTail();
        }
        else if (punct('-') && isKind(pos + 1, TokKind.literal))
        {
            bumpN(2);
            parseRangePatternTail();
        }
        else if (punct('&'))
        {
            bump();
            eatKw("mut");
            parsePatternNoAlt();
        }
        else if (punct('(') || punct('['))
            parsePatternList();
        else if (op(".."))
        {
            if (op("..=") || op("..."))
            {
                bumpN(3);
                parseRangeBound();
            }
            else
            {
                bumpN(2);
                if (canStartRangeBound()) parseRangeBound();
            }
        }
        else if (kw("_"))
            bump();
        else if (kw("ref") || kw("mut"))
        {
            bump();
            eatKw("mut");
            parsePatternNoAlt();
        }
        else if (kw("const") && punctAt(pos + 1, '{'))
        {
            bump();
            parseBlock();
        }
        else if (kw("true") || kw("false"))
            bump();
        else if (isIdent(pos) || op("::") || punct('<'))
        {
            const p = parsePath(PathCtx.pattern);
            if (!p.tailArgs && punct('!') && !op("!=") && isOpener(pos + 1))
            {
                emitPath(p, NodeKind.ident);
                bump();
                tokenTree(false);
            }
            else if (punct('('))
            {
                emitPath(p, p.tailArgs ? NodeKind.typeIdent : NodeKind.ident);
                parsePatternList();
            }
            else if (punct('{'))
            {
                emitPath(p, NodeKind.typeIdent);
                parseStructPatternFields();
            }
            else if (punct('@') && p.single())
            {
                emitPath(p, NodeKind.ident);
                bump();
                parsePatternNoAlt();
            }
            else
            {
                emitPath(p, NodeKind.ident);
                parseRangePatternTail();
            }
        }
        else
            err();
        leave();
    }

    /// `( p, … )` or `[ p, … ]`.
    void parsePatternList()
    {
        size_t saved;
        if (!openGroup(saved))
        {
            bump();
            return;
        }
        while (!atEnd())
        {
            const before = pos;
            parsePattern();
            if (pos == before || !eatPunct(',')) break;
        }
        closeGroup(saved);
    }

    /// `{ a: p, ref mut b, 0: c, .. }`; field names are not references.
    void parseStructPatternFields()
    {
        size_t saved;
        if (!openGroup(saved))
        {
            bump();
            return;
        }
        while (!atEnd())
        {
            const before = pos;
            parseOuterAttrs();
            if (op(".."))
                bumpN(2);
            else
            {
                eatKw("ref");
                eatKw("mut");
                if ((isIdent(pos) || isKind(pos, TokKind.literal)) && colonAt(pos + 1))
                {
                    bumpN(2);
                    parsePattern();
                }
                else if (isIdent(pos))
                    bump();
                else
                    err();
            }
            if (pos == before || !eatPunct(',')) break;
        }
        closeGroup(saved);
    }

    /// After a literal or path in a pattern: an optional range `..= end`, `.. end` or `..`.
    void parseRangePatternTail()
    {
        if (op("..=") || op("..."))
        {
            bumpN(3);
            parseRangeBound();
        }
        else if (op(".."))
        {
            bumpN(2);
            if (canStartRangeBound()) parseRangeBound();
        }
    }

    bool canStartRangeBound() const
    {
        return isKind(pos, TokKind.literal)
            || (punct('-') && isKind(pos + 1, TokKind.literal))
            || (isIdent(pos) && !kw("if"))
            || op("::")
            || punct('<');
    }

    void parseRangeBound()
    {
        if (isKind(pos, TokKind.literal))
            bump();
        else if (punct('-') && isKind(pos + 1, TokKind.literal))
            bumpN(2);
        else if (isIdent(pos) || op("::") || punct('<'))
        {
            const p = parsePath(PathCtx.pattern);
            emitPath(p, NodeKind.ident);
        }
        else
            err();
    }

    // ---------------------------------------------------------- token trees

    /// A macro's token tree (at its opening delimiter): every identifier and lifetime inside is an `ident` leaf
    /// (macro arguments are not parsed). In a `macro_rules!` definition (`rules`), `$name` is a
    /// metavariable, not an identifier.
    void tokenTree(bool rules)
    {
        const p = pos < toks.length ? toks[pos].pair : none;
        if (p == none || p >= limit)
        {
            err();
            bump();
            return;
        }
        foreach (i; pos + 1 .. p)
        {
            final switch (toks[i].kind)
            {
            case TokKind.ident:
            {
                const meta = rules && punctAt(i - 1, '$') && toks[i - 1].end == toks[i].start;
                if (!meta) leaf(NodeKind.ident, i);
                break;
            }
            case TokKind.lifetime:
                leaf(NodeKind.ident, i);
                break;
            case TokKind.literal, TokKind.punct:
                break;
            }
        }
        pos = p + 1;
    }

    // ---------------------------------------------------------- statements and blocks

    /// `{ statements }` at the current `{`.
    void parseBlock()
    {
        if (!punct('{'))
        {
            err();
            return;
        }
        size_t saved;
        if (!openGroup(saved))
        {
            bump();
            return;
        }
        if (enter())
        {
            while (!atEnd())
            {
                const before = pos;
                parseStmt();
                if (pos == before)
                {
                    err();
                    skipTree();
                }
            }
            leave();
        }
        closeGroup(saved);
    }

    void parseStmt()
    {
        if (eatPunct(';')) return;
        if (atAttr())
        {
            parseAttr();
            return;
        }
        ItemKw kw;
        size_t at;
        if (itemStart(pos, kw, at))
        {
            parseItem(kw, at);
            return;
        }
        parseStmtRest();
    }

    /// A `let` or expression statement (or `;`).
    void parseStmtRest()
    {
        if (eatPunct(';')) return;
        if (kw("let"))
        {
            bump();
            parsePattern();
            if (colonAt(pos))
            {
                bump();
                parseType(true);
            }
            if (eatPunct('='))
            {
                parseExpr(false);
                if (eatKw("else")) parseBlock();
            }
            expectPunct(';');
            return;
        }
        const info = parseExprStmt(false);
        if (!eatPunct(';') && !atEnd() && !info.blockLike && !info.macroCall) err();
    }

    /// An expression in statement position (also used for match arm bodies): a block-like expression (`if`, `match`,
    /// a loop, a block, a brace macro) ends there unless followed by `.` or `?`.
    ExprInfo parseExprStmt(bool ns)
    {
        const info = parsePrefix(ns, false);
        if (info.blockLike && !(punct('.') && !op("..")) && !punct('?')) return info;
        return parseInfix(info, 0, ns, false);
    }

    // ---------------------------------------------------------- expressions

    /// A full expression. `ns`: no struct literals (in `if`/`while`/`match`/`for` headers).
    ExprInfo parseExpr(bool ns) { return parseExprBp(0, ns, false); }

    /// Pratt parser entry: an expression whose operators all bind at least as tightly as `minBp`. `cond`: in an
    /// `if`/`while` condition or match guard, where `let` is allowed.
    ExprInfo parseExprBp(ubyte minBp, bool ns, bool cond)
    {
        const lhs = parsePrefix(ns, cond);
        return parseInfix(lhs, minBp, ns, cond);
    }

    /// Can token `i` start an expression? Decides whether `return`, `break`, `..` have an operand.
    bool canStartExpr(size_t i, bool ns) const
    {
        if (i >= limit) return false;
        final switch (toks[i].kind)
        {
        case TokKind.literal, TokKind.lifetime:
            return true;
        case TokKind.ident:
        {
            const t = text(i);
            return t != "as" && t != "else" && t != "in" && t != "where";
        }
        case TokKind.punct:
            switch (ch(i))
            {
            case '(', '[', '!', '-', '*', '&', '|', '<', '#': return true;
            case '{': return !ns;
            case ':': return opAt(i, "::");
            case '.': return opAt(i, "..");
            default: return false;
            }
        }
    }

    /// The binary operator at the current position, if any. Binding powers: assignment 1 (right-associative),
    /// range 2, `||` 3, `&&` 4, comparison 5, `|` 6, `^` 7, `&` 8, shift 9, additive 10, multiplicative 11 (`as` 12,
    /// unary 13, postfix 14).
    bool binop(out BinOp o) const
    {
        if (!isKind(pos, TokKind.punct)) return false;
        const c = ch(pos);
        bool set(size_t len, ubyte lbp, ubyte rbp, bool logic = false, bool range = false)
        {
            o = BinOp(len, lbp, rbp, logic, range);
            return true;
        }
        switch (c)
        {
        case '=':
            if (op("==")) return set(2, 5, 6);
            if (op("=>")) return false;
            return set(1, 1, 1);
        case '!':
            return op("!=") ? set(2, 5, 6) : false;
        case '<', '>':
        {
            const s = c == '<' ? "<<=" : ">>=";
            if (op(s)) return set(3, 1, 1);
            if (op(s[0 .. 2])) return set(2, 9, 10);
            if (op(c == '<' ? "<=" : ">=")) return set(2, 5, 6);
            return set(1, 5, 6);
        }
        case '&':
            if (op("&&")) return set(2, 4, 5, true);
            if (op("&=")) return set(2, 1, 1);
            return set(1, 8, 9);
        case '|':
            if (op("||")) return set(2, 3, 4, true);
            if (op("|=")) return set(2, 1, 1);
            return set(1, 6, 7);
        case '^', '+', '-', '*', '/', '%':
            if (punctAt(pos + 1, '=') && toks[pos].joint) return set(2, 1, 1);
            if (c == '-' && op("->")) return false;
            if (c == '^') return set(1, 7, 8);
            if (c == '+' || c == '-') return set(1, 10, 11);
            return set(1, 11, 12);
        case '.':
            if (op("..=") || op("...")) return set(3, 2, 3, false, true);
            if (op("..")) return set(2, 2, 3, false, true);
            return false;
        default:
            return false;
        }
    }

    /// Postfix and binary operators after an operand (see `binop`).
    ExprInfo parseInfix(ExprInfo info, ubyte minBp, bool ns, bool cond)
    {
        for (;;)
        {
            if (atEnd()) break;
            if (punct('?'))
            {
                bump();
                info = ExprInfo.init;
                continue;
            }
            if (punct('.') && !op(".."))
            {
                bump();
                if (kw("await"))
                    bump();
                else if (isIdent(pos))
                {
                    bump(); // field or method name: not a reference
                    if (op("::") && punctAt(pos + 2, '<'))
                    {
                        bumpN(2);
                        parseGenericArgs();
                    }
                }
                else if (isKind(pos, TokKind.literal))
                    bump();
                else
                    err();
                info = ExprInfo.init;
                continue;
            }
            if (punct('(') || punct('['))
            {
                // Calls and indexes nest without passing parsePrefix's depth guard (`f(f(f(…)))`), so they count a
                // level of their own.
                if (enter())
                {
                    parseExprList();
                    leave();
                }
                info = ExprInfo.init;
                continue;
            }
            if (kw("as"))
            {
                if (12 < minBp) break;
                bump();
                parseType(false);
                info = ExprInfo.init;
                continue;
            }
            BinOp o;
            if (!binop(o)) break;
            if (o.lbp < minBp) break;
            const opTok = pos;
            bumpN(o.len);
            // A right operand nests (`a = b = c = …`) without passing parsePrefix's depth guard either.
            if (!enter())
            {
                info = ExprInfo.init;
                continue;
            }
            if (o.range)
            {
                if (canStartExpr(pos, ns)) parseExprBp(o.rbp, ns, false);
                leave();
                info = ExprInfo.init;
                continue;
            }
            const rhs = parseExprBp(o.rbp, ns, cond);
            leave();
            const and = o.logic && ch(opTok) == '&';
            if (and && (info.isLet || rhs.isLet))
            {
                info = ExprInfo.init;
                info.isLet = true;
            }
            else
            {
                if (o.logic) leaf(NodeKind.andOr, opTok);
                info = ExprInfo.init;
            }
        }
        return info;
    }

    /// `( … )` call arguments / tuple / parenthesised expression, or `[ … ]` index / array (`[x; n]`): comma
    /// separated expressions with optional attributes.
    void parseExprList()
    {
        size_t saved;
        if (!openGroup(saved))
        {
            bump();
            return;
        }
        while (!atEnd())
        {
            const before = pos;
            parseOuterAttrs();
            if (atEnd()) break;
            parseExpr(false);
            if (eatPunct(';')) parseExpr(false);
            if (pos == before || !eatPunct(',')) break;
        }
        closeGroup(saved);
    }

    /// An operand: literals, paths, struct literals, macro calls, groups, blocks, control flow, closures, unary and
    /// prefix-range operators. Unary operators bind tighter than everything but postfix operators.
    ExprInfo parsePrefix(bool ns, bool cond)
    {
        if (!enter()) return ExprInfo.init;
        const info = parsePrefixInner(ns, cond);
        leave();
        return info;
    }

    ExprInfo parsePrefixInner(bool ns, bool cond)
    {
        const block = ExprInfo(true, false, false);
        const plain = ExprInfo.init;
        if (atEnd())
        {
            err();
            return plain;
        }
        final switch (toks[pos].kind)
        {
        case TokKind.literal:
            bump();
            return plain;
        case TokKind.lifetime:
            // label: `'a: loop { … }`
            if (colonAt(pos + 1))
            {
                leaf(NodeKind.ident, pos);
                bumpN(2);
                if (kw("loop") || kw("while") || kw("for") || punct('{')) return parsePrefixInner(ns, cond);
            }
            err();
            bump();
            return plain;
        case TokKind.punct:
        {
            const c = ch(pos);
            switch (c)
            {
            case '(', '[':
                parseExprList();
                return plain;
            case '{':
                parseBlock();
                return block;
            case '|':
                return parseClosure(ns);
            case '-', '!', '*':
                bump();
                parseExprBp(13, ns, false);
                return plain;
            case '&':
                bump();
                if (kw("raw") && (kwAt(pos + 1, "const") || kwAt(pos + 1, "mut"))) bumpN(2);
                else eatKw("mut");
                parseExprBp(13, ns, false);
                return plain;
            default:
                break;
            }
            if (c == '.' && op(".."))
            {
                bumpN(op("..=") || op("...") ? 3 : 2);
                if (canStartExpr(pos, ns)) parseExprBp(3, ns, false);
                return plain;
            }
            if (c == '<' || (c == ':' && op("::"))) return parsePathExpr(ns);
            if (c == '#' && atAttr())
            {
                parseAttr();
                return parsePrefixInner(ns, cond);
            }
            err();
            return plain;
        }
        case TokKind.ident:
            break;
        }
        // An identifier or keyword. Keywords that do not start the construct they name (`unsafe` without a block,
        // `async` without a block or closure, …) fall through to a path.
        switch (text(pos))
        {
        case "if":
            parseIf();
            return block;
        case "match":
            parseMatch();
            return block;
        case "while":
            leaf(NodeKind.while_, pos);
            bump();
            parseExprBp(0, true, true);
            parseBlock();
            return block;
        case "loop":
            leaf(NodeKind.loop, pos);
            bump();
            parseBlock();
            return block;
        case "for":
            leaf(NodeKind.for_, pos);
            bump();
            parsePattern();
            if (!eatKw("in")) err();
            parseExprBp(0, true, false);
            parseBlock();
            return block;
        case "return", "yield", "become":
            bump();
            if (canStartExpr(pos, ns)) parseExprBp(1, ns, false);
            return plain;
        case "break":
            bump();
            if (isKind(pos, TokKind.lifetime))
            {
                leaf(NodeKind.ident, pos);
                bump();
            }
            if (canStartExpr(pos, ns)) parseExprBp(1, ns, false);
            return plain;
        case "continue":
            bump();
            if (isKind(pos, TokKind.lifetime))
            {
                leaf(NodeKind.ident, pos);
                bump();
            }
            return plain;
        case "let":
            // `let` condition (only valid in `if`/`while` conditions and match guards)
            if (!cond) err();
            bump();
            parsePattern();
            expectPunct('=');
            parseExprBp(5, true, false);
            return ExprInfo(false, false, true);
        case "true", "false":
            bump();
            return plain;
        default:
            break;
        }
        const t = text(pos);
        if ((t == "unsafe" || t == "const" || t == "try") && punctAt(pos + 1, '{'))
        {
            bump();
            parseBlock();
            return block;
        }
        if ((t == "async" || t == "gen")
            && (punctAt(pos + 1, '{') || (kwAt(pos + 1, "move") && punctAt(pos + 2, '{'))))
        {
            bump();
            eatKw("move");
            parseBlock();
            return block;
        }
        if ((t == "async" || t == "move" || t == "static") && (punctAt(pos + 1, '|') || kwAt(pos + 1, "move")))
            return parseClosure(ns);
        return parsePathExpr(ns);
    }

    /// A path in expression position, possibly a macro call or struct literal.
    ExprInfo parsePathExpr(bool ns)
    {
        const p = parsePath(PathCtx.expr);
        if (!p.tailArgs && punct('!') && !op("!=") && isOpener(pos + 1))
        {
            emitPath(p, NodeKind.ident);
            bump();
            const brace = punct('{');
            tokenTree(false);
            return ExprInfo(brace, true, false);
        }
        if (punct('{') && !ns)
        {
            // struct literal: the name is a type_identifier
            emitPath(p, NodeKind.typeIdent);
            parseStructLiteralFields();
            return ExprInfo.init;
        }
        emitPath(p, NodeKind.ident);
        return ExprInfo.init;
    }

    /// `{ a: x, b, 0: y, ..base }`: field names are not references, shorthand fields are identifiers.
    void parseStructLiteralFields()
    {
        size_t saved;
        if (!openGroup(saved))
        {
            bump();
            return;
        }
        while (!atEnd())
        {
            const before = pos;
            parseOuterAttrs();
            if (op(".."))
            {
                bumpN(2);
                if (!atEnd() && !punct(',')) parseExpr(false);
            }
            else if ((isIdent(pos) || isKind(pos, TokKind.literal)) && colonAt(pos + 1))
            {
                bumpN(2);
                parseExpr(false);
            }
            else if (isIdent(pos))
            {
                leaf(NodeKind.ident, pos);
                bump();
            }
            else
                err();
            if (pos == before || !eatPunct(',')) break;
        }
        closeGroup(saved);
    }

    /// `if cond { … } else if cond { … } else { … }`, iteratively so that long `else if` chains need no recursion.
    void parseIf()
    {
        for (;;)
        {
            leaf(NodeKind.if_, pos);
            bump();
            parseExprBp(0, true, true);
            parseBlock();
            if (eatKw("else"))
            {
                if (kw("if")) continue;
                parseBlock();
            }
            break;
        }
    }

    /// `match x { attrs* pat (if guard)? => body,? … }`.
    void parseMatch()
    {
        leaf(NodeKind.match, pos);
        bump();
        parseExprBp(0, true, false);
        if (!punct('{'))
        {
            err();
            return;
        }
        size_t saved;
        if (!openGroup(saved))
        {
            bump();
            return;
        }
        while (!atEnd())
        {
            const before = pos;
            while (atAttr()) parseAttr();
            if (atEnd()) break;
            leaf(NodeKind.arm, pos);
            parsePattern();
            if (eatKw("if")) parseExprBp(0, false, true);
            if (op("=>")) bumpN(2);
            else err();
            const body = parseExprStmt(false);
            if (!eatPunct(',') && !atEnd() && !body.blockLike && !body.macroCall) err();
            if (pos == before)
            {
                err();
                skipTree();
            }
        }
        closeGroup(saved);
    }

    /// `static? async? move? |params| body` or `… || -> T { … }`.
    ExprInfo parseClosure(bool ns)
    {
        eatKw("static");
        eatKw("async");
        eatKw("move");
        if (!eatPunct('|'))
        {
            err();
            return ExprInfo.init;
        }
        while (!atEnd() && !punct('|'))
        {
            const before = pos;
            parseOuterAttrs();
            parsePatternNoAlt();
            if (colonAt(pos))
            {
                bump();
                parseType(false);
            }
            if (pos == before || !eatPunct(',')) break;
        }
        expectPunct('|');
        if (op("->"))
        {
            bumpN(2);
            parseType(false);
            parseBlock();
        }
        else
            parseExprBp(0, ns, false);
        return ExprInfo.init;
    }
}

version (unittest)
{
    /// (kind, text) of every reference leaf, in node order.
    private struct Leaf
    {
        NodeKind kind;
        string text;
    }

    private Leaf[] leaves(string code)
    {
        auto t = parse(code);
        assert(!t.errors, "unexpected parse error in " ~ code);
        Leaf[] output;
        foreach (n; t.nodes)
            if (n.kind == NodeKind.typeIdent || n.kind == NodeKind.ident || n.kind == NodeKind.path)
                output ~= Leaf(n.kind, t.rangeText(code, n.first, n.last));
        return output;
    }

    private size_t count(string code, NodeKind kind)
    {
        size_t c;
        foreach (n; parse(code).nodes) if (n.kind == kind) c++;
        return c;
    }

    private NodeKind[] kindsOf(const Tree t)
    {
        NodeKind[] output;
        foreach (n; t.nodes) output ~= n.kind;
        return output;
    }

    /// Tiny deterministic PRNG (xorshift) so the fuzz test is reproducible.
    private ulong next(ref ulong state)
    {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        return state;
    }
}

unittest // lexes raw strings, lifetimes, chars and nested comments
{
    const code = "r#\"a \" b\"# 'a 'b' '\\'' b'x' /* x /* y */ z */ r#type 1.0 1..2 x.0";
    bool err;
    const toks = lex(code, err);
    assert(!err);
    TokKind[] kinds;
    foreach (t; toks) kinds ~= t.kind;
    with (TokKind)
        assert(kinds == [literal, lifetime, literal, literal, literal, ident, literal, literal, punct, punct, literal,
            ident, punct, literal]);
    assert(toks[5].commentBefore);
}

unittest // classifies names by position
{
    with (NodeKind)
        assert(leaves("fn f(x: a::B<C>) { let y = Foo { z }; Vec::<T>::new(); bar::<U>(); <T as Tr>::f(); x.Method(); }")
            == [Leaf(ident, "f"), Leaf(ident, "x"), Leaf(typeIdent, "C"), Leaf(path, "a::B"), Leaf(ident, "y"),
                Leaf(typeIdent, "Foo"), Leaf(ident, "z"), Leaf(path, "Vec::<T>::new"), Leaf(typeIdent, "T"),
                Leaf(typeIdent, "U"), Leaf(ident, "bar"), Leaf(typeIdent, "T"), Leaf(typeIdent, "Tr"),
                Leaf(ident, "x")]);
}

unittest // macro arguments are identifiers, except metavariables in macro_rules
{
    string[] names;
    foreach (l; leaves("fn f() { m!(Foo::bar, $Baz); macro_rules! r { ($T:ty) => { impl X for $T {} } } }"))
        names ~= l.text;
    assert(names == ["f", "m", "Foo", "bar", "Baz", "r", "ty", "impl", "X", "for"]);
}

unittest // counts decision points, but not let chains, guards or macro arguments
{
    const code = "fn f() { if a && b {} else if let Some(x) = y && z {} match q { A if c => 1, _ => 2 } while x || y {} for i in 0.. {} loop {} assert!(a && b); }";
    assert(count(code, NodeKind.if_) == 2);
    assert(count(code, NodeKind.andOr) == 2);
    assert(count(code, NodeKind.arm) == 2);
    assert(count(code, NodeKind.for_) == 1);
}

unittest // struct literals are not allowed in conditions
{
    assert(!parse("fn f() { if x == foo { bar } }").errors);
    assert(count("fn f() { if x == foo { bar } }", NodeKind.typeIdent) == 0);
}

unittest // a block-like statement ends unless a method call follows
{
    assert(!parse("fn f() { if a {} *b = 1; match x {}.foo(); m!{} m!(x) fn g() {} }").errors);
}

unittest // counts fields, variants and parameters
{
    const code = "struct S(pub A, #[x] B); enum E { A, B(u8), C { x: u8 } } trait T { fn f(&self, self: Box<Self>, u32, _, a: u8); }";
    auto t = parse(code);
    assert(!t.errors);
    uint[] values;
    NodeKind[] kinds;
    foreach (n; t.nodes)
        if (n.kind == NodeKind.struct_ || n.kind == NodeKind.enum_ || n.kind == NodeKind.fn)
        {
            kinds ~= n.kind;
            values ~= n.value;
        }
    assert(kinds == [NodeKind.struct_, NodeKind.enum_, NodeKind.fn]);
    assert(values == [2, 3, 2]);
}

unittest // counts stay right when the node array grows while a list is parsed
{
    auto t = parse("struct T(#[a(b c d e f g h i j k l m n o p q r s t u v w x y z)] u8, u8);");
    assert(!t.errors && t.nodes[1].kind == NodeKind.struct_ && t.nodes[1].value == 2);
}

unittest // generic argument lists close with shift tokens
{
    assert(!parse("fn f() -> Vec<Vec<u8>> { let x: Vec<u8>= v; a >> b }").errors);
}

unittest // impl headers keep the core type
{
    const code = "impl<'a, T> !Send for &'a mut S<T> {}";
    auto t = parse(code);
    string ty, tr;
    foreach (n; t.nodes)
    {
        if (n.kind == NodeKind.implType) ty = t.rangeText(code, n.name, n.value);
        if (n.kind == NodeKind.implTrait) tr = t.rangeText(code, n.name, n.value);
    }
    assert(ty == "S" && tr == "Send");
}

unittest // broken input is flagged and does not crash, however deeply nested
{
    import std.array : replicate;
    foreach (code; ["fn f( {", "struct", "impl<", "}}}", "fn f() { let x = ; }", "'", "r#\"", "/* x", "#!["])
        assert(parse(code).errors, code);
    const deep = "(".replicate(10_000) ~ ")".replicate(10_000);
    assert(parse("fn f() { " ~ deep ~ " }").errors);
    assert(parse("mod a { ".replicate(5_000) ~ "}".replicate(5_000)).errors);
}

unittest // nested use groups are capped like any other recursion
{
    import std.algorithm : count;
    import std.array : replicate;
    auto t = parse("use " ~ "{".replicate(200_000) ~ "}".replicate(200_000) ~ "; fn after() {}");
    assert(t.errors);
    assert(t.nodes.count!(n => n.kind == NodeKind.fn) == 1); // the item after the use is parsed
    enum ok = maxDepth - 1;
    assert(!parse("use " ~ "{".replicate(ok) ~ "a" ~ "}".replicate(ok) ~ ";").errors);
}

unittest // deep nesting stays within a small thread stack
{
    import core.thread : Thread;
    import std.array : replicate;
    // Worker threads (std.parallelism) may have smaller stacks than the main thread: 512 KiB is plenty.
    bool flagged;
    auto t = new Thread({
        flagged = parse("fn f() { " ~ "[-&(".replicate(5_000) ~ ")]".replicate(5_000) ~ " }").errors
            && parse("type T = " ~ "&Vec<(".replicate(5_000) ~ ";").errors;
    }, 512 * 1024);
    t.start();
    t.join();
    assert(flagged);
}

unittest // calls, indexes and right operands are capped too; unmatched closers are paired in linear time
{
    import core.thread : Thread;
    import std.array : replicate;
    import std.datetime.stopwatch : StopWatch, AutoStart;
    // They nest without passing parsePrefix's depth guard, so they count a level of their own.
    bool flagged;
    auto t = new Thread({
        flagged = parse("fn f() { " ~ "f(".replicate(100_000) ~ ")".replicate(100_000) ~ "; }").errors
            && parse("fn f() { " ~ "a[".replicate(100_000) ~ "]".replicate(100_000) ~ "; }").errors
            && parse("fn f() { " ~ "x = ".replicate(100_000) ~ "a; }").errors
            && parse("fn f() { " ~ "([".replicate(100_000) ~ ")".replicate(100_000) ~ "; }").errors;
    }, 256 * 1024);
    t.start();
    t.join();
    assert(flagged);
    auto sw = StopWatch(AutoStart.yes);
    assert(parse("fn f() { " ~ "(]".replicate(200_000) ~ " }").errors);
    assert(sw.peek.total!"seconds" < 10);
}

unittest // use trees keep their structure
{
    auto t = parse("use a::{self, b::C as D, e::*, {f}}; use ::g::h; use i::*;");
    assert(!t.errors);
    with (NodeKind)
        assert(kindsOf(t) == [file, use, useGroup, usePrefix, usePath, useAs, usePath, useGlob, usePrefix, useGroup,
            usePath, use, usePath, use, useGlob, usePrefix]);
}

unittest // comments between items are recorded
{
    auto t = parse("#[cfg(test)]\n// note\nmod tests {}\n");
    with (NodeKind)
        assert(kindsOf(t)[0 .. 5] == [file, attr, ident, ident, comment]);
}

unittest // literals that look like code are skipped
{
    const code = `fn f() { let s = r#"} fn g() { "#; let c = '{'; let b = b"}"; /* } /* { */ } */ let l: &'static str = "\"}"; }`;
    auto t = parse(code);
    assert(!t.errors);
    assert(count(code, NodeKind.fn) == 1);
}

unittest // closures, ranges, casts and other tricky syntax parse without errors
{
    import std.conv : to;
    foreach (code; [
        "fn f() { let a = |x: u8, (y, z)| -> u8 { x }; let b = move || a; v.iter().map(|&x| x as u32 + 1).sum::<u32>(); }",
        "fn f() { for i in 0..n {} let r = ..=5; let s = &x[1..]; if let 1..=9 | 20.. = n {} }",
        "fn f() { 'outer: loop { break 'outer; } let x = 'a: { break 'a 1 }; async move { x.await? }; }",
        "fn f() -> impl Fn(&str) -> Box<dyn Error + Send + 'static> { |_| todo!() }",
        "fn f<T: ?Sized + for<'a> Fn(&'a T)>(x: &T) where T: Iterator<Item = u8> {}",
        "fn f() { let Some(x) = y else { return }; let S { a, b: [c, ..], .. } = s; }",
        "const _: () = { impl X for Y {} }; static mut S: [u8; 4 * N] = [0; 4 * N];",
        "extern \"C\" { fn printf(f: *const c_char, ...) -> c_int; static errno: c_int; }",
    ])
    {
        auto t = parse(code);
        assert(!t.errors, code ~ ": error at token " ~ t.firstError.to!string);
    }
}

unittest // survives mutated sources: no crash, no endless loop, a well-formed tree
{
    import codecity.analysis.rustlang : analyse = parse;
    import std.conv : to;
    import std.file : exists, readText;
    import std.path : buildPath;
    // Real Rust files: the Rust edition's sources next to this project (skipped if absent).
    const dir = buildPath("..", "rust", "src", "analysis");
    immutable pieces = ["{", "}", "(", ")", "[", "]", "<", ">", "'", "\"", "#", "r#", "/*", "*/", "//", "::", "|", "&&",
        "$", "é", "\n", "fn ", "impl ", "match ", "if "];
    ulong state = 0x9E37_79B9_7F4A_7C15UL;
    foreach (name; ["rust_parse.rs", "rust_lang.rs", "text.rs"])
    {
        const file = buildPath(dir, name);
        if (!file.exists) continue;
        const chars = readText(file).to!(dchar[]);
        foreach (_; 0 .. 60)
        {
            auto c = chars.dup;
            foreach (__; 0 .. 1 + next(state) % 8)
            {
                if (c.length == 0) break;
                const at = cast(size_t)(next(state) % c.length);
                switch (next(state) % 3)
                {
                case 0:
                {
                    const len = cast(size_t)(1 + next(state) % 20);
                    const to_ = at + len < c.length ? at + len : c.length;
                    c = c[0 .. at] ~ c[to_ .. $];
                    break;
                }
                case 1:
                {
                    const p = pieces[cast(size_t)(next(state) % pieces.length)];
                    c = c[0 .. at] ~ p.to!(dchar[]) ~ c[at .. $];
                    break;
                }
                default:
                    c = c[0 .. at];
                    break;
                }
            }
            const mutated = c.to!string;
            auto t = parse(mutated);
            foreach (i, n; t.nodes)
            {
                assert(n.end > i && n.end <= t.nodes.length);
                assert(n.first <= n.last || t.tokens.length == 0);
            }
            analyse(dir, file, mutated);
        }
    }
}
