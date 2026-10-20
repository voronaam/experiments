/// A small, dependency-free Java lexer and error-tolerant structural parser, built for the Java analyser
/// (`codecity.analysis.java`). A port of the Rust edition's `java_parse.rs`, which is the reference: token kinds,
/// node kinds, speculation points, error recovery and the depth cap are the same, so the analyser's output is the
/// same by construction.
///
/// It is not a full Java front end. It reads the whole language (Java 21, including records, sealed types, switch
/// expressions and patterns) but keeps only what the analyser needs, in a tiny syntax tree:
/// - the declarations: package, imports, types (class, interface, enum, record, annotation type) and their members
///   (fields, enum constants, methods and constructors) with names, token spans and counts;
/// - the *references*: every type name (`List`, `Map.Entry`), annotation name and identifier used as the object of a
///   member access or call (`Util` in `Util.run()`);
/// - the *decision points* for McCabe complexity (`if`, loops, `catch`, `case`, `?:`, `&&`, `||`);
/// - whether the file had syntax errors.
///
/// The rules for what counts as which follow tree-sitter-java 0.23, which this parser replaced: the analyser's output
/// is meant to be identical. Where the syntax is ambiguous (is `(a) - b` a cast?), the comments below say
/// what it does.
///
/// Pipeline:
/// 1. `lex` turns the source into a flat array of `Token`s (comments and whitespace dropped), each with its kind,
///    byte range and 1-based line. It also pairs up brackets (`Lexed.pair`), so the parser can jump over a bracketed
///    group in one step.
/// 2. `parse` runs a recursive-descent parser over the tokens and produces a `Tree`: a flat array of `Node`s in
///    pre-order, where each node knows the index just past its subtree (`end`). Children of node `i` are found by
///    starting at `i + 1` and hopping from each child to its `end`.
///
/// Grammar subset. The parser follows the Java grammar closely enough to tell types from expressions, which is what
/// decides whether a name is a type reference. Outline (`[]` optional, `{}` repeated):
///
/// ---
/// file        = { package | import | module | typeDecl | ";" | statement | method }
/// typeDecl    = modifiers ("class" | "interface" | "enum" | "record" | "@interface") Ident header body
/// member      = ";" | block | "static" block | modifiers (typeDecl | constructor | method | field | compactCtor)
/// statement   = block | if | for | while | do | try | switch | return | throw | break | continue | yield | assert
///             | synchronized | label ":" statement | localTypeDecl | localVariable ";" | expression ";"
/// expression  = operand { binaryOp operand | "instanceof" pattern | "?" expression ":" } ;  precedence is ignored
/// operand     = { prefixOp | cast } (lambda | primary { postfix })
/// type        = annotations (primitive | Ident [typeArgs] { "." annotations Ident [typeArgs] }) { annotations "[]" }
/// ---
///
/// Precedence is deliberately ignored: none of the analyser's metrics depend on how operators group, only on which
/// tokens are types, member-access objects and decision points.
///
/// Ambiguities are resolved by bounded look-ahead or by *speculation*: the parser saves its state (token position,
/// node count, error flag), tries one reading (say, "a type followed by a name" for a local variable), and restores
/// the state if that fails. Nodes pushed during a failed attempt are dropped by truncating the node array.
///
/// Error recovery. The parser never stops and never throws. When a token does not fit, it sets the error flag and
/// carries on:
/// - `expect` of a missing token flags the error without consuming anything;
/// - every loop over list items (members, statements, arguments, …) checks that an iteration consumed at least one
///   token; if not, it flags the error and skips one token, or a whole bracketed group if the token opens one (using
///   the bracket pairs from the lexer), so it always makes progress;
/// - recursion is capped at `maxDepth` nested constructs; deeper input is skipped group by group and flagged, and
///   from then on the parser no longer speculates (see `Parser.depthCapped`).
///
/// So a file with errors still yields every declaration the parser could recognise, which is what the analyser
/// reports (with the file counted as failed).
///
/// Everything works on byte offsets into the UTF-8 source; line numbers count `\n` only, as the analyser's
/// `codecity.analysis.text.lines` does. Invalid UTF-8 (which the analysis never passes in, see
/// `SourceAnalyzer.parseFile`) is tolerated: undecodable bytes are error tokens.
module codecity.analysis.java_parse;

import std.typecons : Yes;
import std.uni : isAlpha, isAlphaNum;
import std.utf : decode;

/// Maximum nesting of statements, expressions, types and bodies before the parser gives up on the nested part (and
/// flags an error). Real code stays far below; the cap keeps adversarial input from overflowing the stack (the
/// parser recurses once per nesting level, with a few stack frames each).
enum uint maxDepth = 300;

/// "No token" / "no pair" marker in index fields.
enum uint NONE = uint.max;

/// `NONE` as a `size_t` index, for parser fields and locals that hold token or node indices.
private enum size_t noIndex = NONE;

// ------------------------------------------------------------------ tokens

/// What a `Token` is. Punctuation and operators are all `Tok.op`; the parser compares their text.
enum Tok : ubyte
{
    /// A name. Includes the contextual keywords (`record`, `var`, `yield`, `sealed`, `permits`, `when`, `module`,
    /// …), which are names everywhere except where the parser looks for them, and `goto`/`const`, which
    /// are not reserved either.
    ident,
    /// A reserved word (see `isKeyword`), including the literals `true`, `false` and `null`.
    keyword,
    /// A numeric literal (`12`, `0x1F`, `1.5e-3f`, `1_000L`).
    number,
    /// A character literal (`'a'`, `'\n'`).
    char_,
    /// A string literal (`"…"`) or text block (`"""…"""`).
    str,
    /// An operator or punctuation (see `operators`).
    op,
    /// Anything the lexer could not read: a stray character (`#`, `\`), an unterminated literal or comment. Its
    /// presence makes the file count as having errors.
    error,
    /// End of input. The token array always ends with exactly one, so look-ahead never runs off the end.
    eof,
}

/// One token: kind, byte range `[start, end)` in the source, and the 1-based line it starts on.
struct Token
{
    Tok kind;
    uint start;
    uint end;
    uint line;
}

/// True for the reserved words of Java that are lexed as keywords. `goto` and `const` are missing on
/// purpose (they are ordinary identifiers), as are all contextual keywords.
private bool isKeyword(const(char)[] s) pure nothrow @safe
{
    switch (s)
    {
    case "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "continue", "default",
            "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null":
        return true;
    default:
        return false;
    }
}

/// Operators and punctuation, longest first so the lexer can take the first match. `>` is always a token of its own
/// (except in `>=` and `->`), so that the `>>` closing two type-argument lists is two tokens; in expressions the
/// parser glues adjacent `>`s back into shift operators.
private immutable string[] operators = [
    "<<=", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=",
    "|=", "^=", "<<", "(", ")", "{", "}", "[", "]", ";", ",", ".", "@", "=", ">", "<", "!", "~", "?", ":", "+", "-",
    "*", "/", "&", "|", "^", "%",
];

/// The result of `lex`.
struct Lexed
{
    Token[] tokens;
    /// For each token that opens a bracket (`(`, `[`, `{`), the index of the token that closes it; `NONE` for all
    /// other tokens and for unclosed brackets.
    uint[] pair;
    /// True if the lexer met something it could not read (a `Tok.error` token, or brackets that do not match).
    bool error;
}

/// True if byte `i` of `b` starts a UTF-8 character (is not a continuation byte); also true at the end.
private bool isBoundary(const(ubyte)[] b, size_t i) pure nothrow @nogc @safe
{
    return i >= b.length || (b[i] & 0xC0) != 0x80;
}

private bool isAsciiDigit(ubyte c) pure nothrow @nogc @safe { return c >= '0' && c <= '9'; }

private bool isAsciiAlpha(ubyte c) pure nothrow @nogc @safe
{
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}

/// Splits Java source into tokens, dropping whitespace and comments.
///
/// Whitespace is ASCII space, tab, form feed, vertical tab, `\r` and `\n` (other Unicode spaces are errors).
/// A byte-order mark at the very start is skipped. Identifiers are ASCII letters, digits, `_` and
/// `$`, plus any non-ASCII letter or digit (`std.uni.isAlphaNum`). Unicode escapes (`\u0041`) are not decoded.
Lexed lex(string code)
{
    auto b = cast(const(ubyte)[]) code;
    const n = b.length;
    Token[] tokens;
    tokens.reserve(n / 4 + 1);
    bool error;
    uint line = 1;
    size_t i = 0;
    // A byte-order mark (U+FEFF, EF BB BF in UTF-8) at the start of the file is not part of the code.
    if (n >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF) i = 3;
    while (i < n)
    {
        const c = b[i];
        // whitespace
        if (c == '\n')
        {
            line++;
            i++;
            continue;
        }
        if (c == ' ' || c == '\t' || c == '\r' || c == 0x0B || c == 0x0C)
        {
            i++;
            continue;
        }
        const start = i;
        const startLine = line;
        // comments
        if (c == '/' && i + 1 < n && b[i + 1] == '/')
        {
            while (i < n && b[i] != '\n') i++;
            continue;
        }
        if (c == '/' && i + 1 < n && b[i + 1] == '*')
        {
            i += 2;
            while (true)
            {
                if (i >= n)
                {
                    error = true; // unterminated block comment
                    break;
                }
                if (b[i] == '*' && i + 1 < n && b[i + 1] == '/')
                {
                    i += 2;
                    break;
                }
                if (b[i] == '\n') line++;
                i++;
            }
            continue;
        }
        Tok kind;
        if (isIdentStart(code, i))
        {
            i = skipIdent(code, i);
            kind = isKeyword(code[start .. i]) ? Tok.keyword : Tok.ident;
        }
        else if (isAsciiDigit(c) || (c == '.' && i + 1 < n && isAsciiDigit(b[i + 1])))
        {
            i = skipNumber(b, i);
            kind = Tok.number;
        }
        else if (c == '"' && i + 2 < n && b[i + 1] == '"' && b[i + 2] == '"')
        {
            // Text block: runs to the next unescaped `"""`, across lines.
            i += 3;
            while (true)
            {
                if (i >= n)
                {
                    kind = Tok.error;
                    break;
                }
                if (b[i] == '\\')
                {
                    if (i + 1 < n && b[i + 1] == '\n') line++;
                    i += 2;
                    continue;
                }
                if (b[i] == '"' && i + 2 < n && b[i + 1] == '"' && b[i + 2] == '"')
                {
                    i += 3;
                    kind = Tok.str;
                    break;
                }
                if (b[i] == '\n') line++;
                i++;
            }
            if (i > n) i = n;
        }
        else if (c == '"' || c == '\'')
        {
            // String or character literal: ends at the matching quote; a line break or the end of the file first
            // makes it an unterminated (error) token that stops before the line break.
            i++;
            while (true)
            {
                if (i >= n || b[i] == '\n')
                {
                    kind = Tok.error;
                    break;
                }
                if (b[i] == '\\')
                {
                    // `\` + line break is accepted inside character literals; keep it simple
                    // and let the escape swallow any following byte except a line break.
                    if (i + 1 < n && b[i + 1] != '\n') i += 2;
                    else i += 1;
                    continue;
                }
                if (b[i] == c)
                {
                    i++;
                    kind = c == '"' ? Tok.str : Tok.char_;
                    break;
                }
                i++;
            }
            // Move on to a character boundary: an escape may have stepped into the middle of a multi-byte char.
            while (i < n && !isBoundary(b, i)) i++;
        }
        else if (const len = operatorAt(b, i))
        {
            i += len;
            kind = Tok.op;
        }
        else
        {
            // A stray character: one whole UTF-8 character becomes an error token.
            i++;
            while (i < n && !isBoundary(b, i)) i++;
            kind = Tok.error;
        }
        if (kind == Tok.error) error = true;
        tokens ~= Token(kind, cast(uint) start, cast(uint) i, startLine);
    }
    tokens ~= Token(Tok.eof, cast(uint) n, cast(uint) n, line);

    // Pair brackets with a stack. A closer that does not match the innermost opener is left unpaired (and is an
    // error); so is an opener that is never closed.
    auto pair = new uint[tokens.length];
    pair[] = NONE;
    // The open brackets are stack[0 .. depth], managed by hand: appending to a slice that was shortened would copy
    // the whole stack each time, which made alternating openers and closers quadratic.
    size_t[] stack;
    size_t depth;
    foreach (k, t; tokens)
    {
        if (t.kind != Tok.op || t.end - t.start != 1) continue;
        const ch = b[t.start];
        switch (ch)
        {
        case '(', '[', '{':
            if (depth == stack.length) stack.length = depth * 2 + 16;
            stack[depth++] = k;
            break;
        case ')', ']', '}':
        {
            const open = ch == ')' ? '(' : ch == ']' ? '[' : '{';
            if (depth && b[tokens[stack[depth - 1]].start] == open)
            {
                pair[stack[depth - 1]] = cast(uint) k;
                depth--;
            }
            else error = true;
            break;
        }
        default:
            break;
        }
    }
    if (depth) error = true;
    return Lexed(tokens, pair, error);
}

/// Length of the operator (from `operators`) starting at byte `i`, or 0 if none does.
private size_t operatorAt(const(ubyte)[] b, size_t i) pure nothrow @nogc @safe
{
    foreach (op; operators)
        if (b.length - i >= op.length && b[i .. i + op.length] == cast(const(ubyte)[]) op) return op.length;
    return 0;
}

/// True if an identifier starts at byte `i`: an ASCII letter, `_`, `$`, or a non-ASCII letter.
private bool isIdentStart(string code, size_t i)
{
    const c = cast(ubyte) code[i];
    if (c < 0x80) return isAsciiAlpha(c) || c == '_' || c == '$';
    size_t j = i;
    return isAlpha(decode!(Yes.useReplacementDchar)(code, j));
}

/// The end of the identifier starting at `i`: letters, digits, `_`, `$` and non-ASCII letters or digits.
private size_t skipIdent(string code, size_t i)
{
    while (i < code.length)
    {
        const c = cast(ubyte) code[i];
        if (c < 0x80)
        {
            if (isAsciiAlpha(c) || isAsciiDigit(c) || c == '_' || c == '$')
            {
                i++;
                continue;
            }
            break;
        }
        size_t j = i;
        if (!isAlphaNum(decode!(Yes.useReplacementDchar)(code, j))) break;
        i = j;
    }
    return i;
}

/// The end of the numeric literal starting at `i`. Loose on purpose (validity does not matter here): letters,
/// digits and `_` continue it, as does one `.` (not followed by another `.`) and a sign right after an exponent
/// marker (`e`/`E` in decimal literals, `p`/`P` in hexadecimal ones).
private size_t skipNumber(const(ubyte)[] b, size_t i) pure nothrow @nogc @safe
{
    const hex = b[i] == '0' && i + 1 < b.length && (b[i + 1] == 'x' || b[i + 1] == 'X');
    bool dot;
    while (i < b.length)
    {
        const c = b[i];
        if (isAsciiAlpha(c) || isAsciiDigit(c) || c == '_') i++;
        else if (c == '.' && !dot && !(i + 1 < b.length && b[i + 1] == '.'))
        {
            dot = true;
            i++;
        }
        else if ((c == '+' || c == '-') && i > 0)
        {
            const p = b[i - 1];
            const exponent = hex ? (p == 'p' || p == 'P') : (p == 'e' || p == 'E');
            if (!exponent) break;
            i++;
        }
        else break;
    }
    return i;
}

// ------------------------------------------------------------------ tree

/// What a `Node` stands for.
enum NodeKind : ubyte
{
    /// The root (always node 0). Children: the top-level declarations and statements.
    compilationUnit,
    /// `package a.b;` (with any package annotations). Child: the `NodeKind.name`.
    package_,
    /// `import [static] a.b.C [.*];`. `flags`: `importStatic`, `importAsterisk`. Child: the `NodeKind.name`
    /// (without the `.*`).
    import_,
    /// A dotted name of a package or import; tokens `first .. last` (inclusive).
    name,
    /// `module m { … }` (module-info.java). Its directives are skipped.
    module_,
    /// A class, interface, enum, record or annotation type declared at the top level or as a member of another
    /// type. `sub`: one of the `type*` constants; `flags`: `typeAbstract` if it has the `abstract` modifier;
    /// `name`: the name token; `count`: for records, the number of record components (`int... rest` excluded).
    /// Children: references from the header, and the members of its body. Types declared inside code (local and
    /// anonymous classes) get no node; their contents belong to the enclosing node.
    typeDecl,
    /// A field (or interface constant) declaration. `count`: number of declared variables (`int a, b;` is 2).
    field,
    /// An enum constant, including its arguments and class body.
    enumConstant,
    /// A method or constructor of a type. `sub`: `methodConstructor` for constructors; `name`: the name token;
    /// `count`: the number of parameters (a varargs parameter counts, a receiver parameter `Foo this` does not).
    method,
    /// Any other member or top-level item: initializer blocks, compact record constructors, annotation type
    /// elements, top-level statements and methods, and unparsable member debris. Holds the references and
    /// decision points inside it.
    other,
    /// A simple type name (`String`, `T`, `var`): token `first`.
    typeName,
    /// A qualified type name (`Map.Entry`, `java.util.List`, `Outer<String>.Inner`): tokens `first .. last`, which
    /// may include type arguments and annotations of the leading parts. Type names inside those leading parts are
    /// *not* reported separately (the analyser does not descend into a qualified name).
    scopedTypeName,
    /// The name of an annotation (`Override`, `javax.annotation.Nullable`): tokens `first .. last`.
    annotationName,
    /// An identifier used as the object of a field access or method call (`Util` in `Util.run()` or
    /// `Util.VALUE`, also `Outer.super.m()`): token `first`. Not reported for `Outer.this` or `outer.new Inner()`.
    memberObject,
    /// A decision point for McCabe complexity at token `first`: `if`, `for`, `while`, `do` (its trailing `while` is
    /// not another one), `catch`, a `case` label, a ternary `?`, `&&` or `||`.
    decision,
}

/// `NodeKind.typeDecl` `sub` values.
enum ubyte typeClass = 0, typeInterface = 1, typeEnum = 2, typeRecord = 3, typeAnnotation = 4;
/// `NodeKind.typeDecl` flag: declared `abstract`.
enum ubyte typeAbstract = 1;
/// `NodeKind.method` `sub` value of constructors (0 = method).
enum ubyte methodConstructor = 1;
/// `NodeKind.import_` flags.
enum ubyte importStatic = 1, importAsterisk = 2;

/// One node of the syntax tree. Tokens `first .. last` (inclusive) are its source span.
struct Node
{
    NodeKind kind;
    /// Kind-specific sub-kind (see `NodeKind`).
    ubyte sub;
    /// Kind-specific flag bits (see `NodeKind`).
    ubyte flags;
    uint first;
    uint last;
    /// Index of the first node after this node's subtree, so children of `i` are `i + 1`, then each child's `end`,
    /// while below `nodes[i].end`.
    uint end;
    /// A name token, or `NONE`.
    uint name;
    /// Kind-specific count (see `NodeKind`).
    uint count;
}

/// A parsed file: tokens, nodes and the error flag.
struct Tree
{
    Token[] tokens;
    Node[] nodes;
    /// True if the lexer or the parser met anything that is not valid Java.
    bool error;

    /// Indices of the direct children of node `i`.
    size_t[] children(size_t i) const
    {
        size_t[] output;
        size_t j = i + 1;
        while (j < nodes[i].end)
        {
            output ~= j;
            j = nodes[j].end;
        }
        return output;
    }

    /// The source text of tokens `first .. last` inclusive (including any comments and whitespace between them).
    string text(string code, uint first, uint last) const
    {
        const s = tokens[first].start, e = tokens[last].end;
        return e < s ? "" : code[s .. e];
    }

    /// 1-based line the node starts on.
    uint firstLine(size_t i) const
    {
        return tokens[nodes[i].first].line;
    }

    /// 1-based line the node ends on: the line where its last token ends (a text block can span lines).
    uint lastLine(string code, size_t i) const
    {
        const t = tokens[nodes[i].last];
        uint line = t.line;
        foreach (c; code[t.start .. t.end]) if (c == '\n') line++;
        return line;
    }
}

// ------------------------------------------------------------------ parser

/// Parses a Java file. Never fails: problems set `Tree.error`.
Tree parse(string code)
{
    auto lexed = lex(code);
    Parser p;
    p.code = code;
    p.toks = lexed.tokens;
    p.pair = lexed.pair;
    p.error = lexed.error;
    p.labelArrow = noIndex;
    p.compilationUnit();
    return Tree(p.toks, p.nodes[0 .. p.nodeCount], p.error);
}

/// Which kind of body a member is parsed in; decides how `Type name()` is read.
private enum Body
{
    /// A class, interface, enum (after the constants) or record body, or an anonymous class body.
    class_,
    /// An annotation type body: `int value() default 1;` is an element, not a method.
    annotation,
}

/// Saved parser state for speculation (see the module documentation).
private struct Mark
{
    size_t pos;
    size_t nodes;
    bool error;
}

/// The recursive-descent parser. One instance parses one file; see the module documentation for the approach.
private struct Parser
{
    string code;
    Token[] toks;
    uint[] pair;
    /// Index of the current token.
    size_t pos;
    /// The nodes built so far: `nodes[0 .. nodeCount]`. Managed by hand (rather than with `~=`) so that truncating
    /// after a failed speculation keeps the buffer for the next appends.
    Node[] nodes;
    size_t nodeCount;
    bool error;
    /// Current nesting of guarded constructs (see `maxDepth`).
    uint depth;
    /// Set once the depth cap has been reached anywhere in the file. From then on the parser no longer tries the
    /// speculative readings (a local variable, a cast, a type pattern, a record pattern, a type before `::` or
    /// `.class`, a method outside a class) and takes the plain one. Input nested past the cap at each of many such
    /// points (`a < a < a < …`) would otherwise cost up to the cap's worth of steps per token; real code never gets
    /// near the cap. The TypeScript parser does the same.
    bool depthCapped;
    /// Index of the `->` that ends the `case` label being parsed (or `noIndex`): `case A -> x` must not read
    /// `A -> x` as a lambda.
    size_t labelArrow;

    // ---------------------------------------------------------------- token helpers

    /// Index `i` clamped to the token array (the trailing `Tok.eof` stands for everything past the end).
    size_t clamp(size_t i) const
    {
        return i < toks.length ? i : toks.length - 1;
    }

    /// Token text at index `i`.
    const(char)[] textAt(size_t i) const
    {
        const t = toks[clamp(i)];
        return code[t.start .. t.end];
    }

    /// True if token `i` is exactly `s` (an operator, keyword or identifier).
    bool isAt(size_t i, string s) const
    {
        const t = toks[clamp(i)];
        return t.end - t.start == s.length && code[t.start .. t.end] == s;
    }

    /// True if the current token is `s`.
    bool is_(string s) const
    {
        return isAt(pos, s);
    }

    /// True if the token `k` places ahead is `s`.
    bool peekIs(size_t k, string s) const
    {
        return isAt(pos + k, s);
    }

    Tok kindAt(size_t i) const
    {
        return toks[clamp(i)].kind;
    }

    Tok kind() const
    {
        return kindAt(pos);
    }

    bool atEof() const
    {
        return kind() == Tok.eof;
    }

    /// Flags a syntax error at the current token.
    void fail()
    {
        error = true;
    }

    /// Moves to the next token (never past `Tok.eof`).
    void bump()
    {
        if (pos + 1 < toks.length) pos++;
    }

    /// Consumes the current token if it is `s`.
    bool eat(string s)
    {
        if (!is_(s)) return false;
        bump();
        return true;
    }

    /// Consumes `s` or flags an error (consuming nothing).
    void expect(string s)
    {
        if (!eat(s)) fail();
    }

    /// Consumes an identifier or flags an error. Returns the token index, or `NONE` if there was none.
    uint expectIdent()
    {
        if (kind() == Tok.ident)
        {
            const i = cast(uint) pos;
            bump();
            return i;
        }
        fail();
        return NONE;
    }

    /// True if the tokens at `i` and `i + 1` touch (no whitespace or comment between): `non-sealed`, `>>`.
    bool adjacent(size_t i) const
    {
        return i + 1 < toks.length && toks[i].end == toks[i + 1].start;
    }

    /// Error recovery: skips the current token, or the whole bracketed group if it opens one. Flags an error.
    void skip()
    {
        fail();
        const p = pair[pos];
        if (p != NONE) pos = p;
        bump();
    }

    /// If the current token is `(`, the index of its `)` (else `NONE`), for `resync`.
    uint closingParen() const
    {
        return is_("(") ? pair[pos] : NONE;
    }

    /// Error recovery inside a parenthesized list that hit an unexpected token: flags the error and jumps to the
    /// list's closing parenthesis `close` if it is known and ahead, or else skips one token if the current item
    /// (which started at `before`) consumed nothing.
    void resync(uint close, size_t before)
    {
        fail();
        if (close != NONE && close > pos) pos = close;
        else if (pos == before) skip();
    }

    Mark mark() const
    {
        return Mark(pos, nodeCount, error);
    }

    /// Undoes everything since `m`: position, nodes and error flag.
    void reset(Mark m)
    {
        pos = m.pos;
        nodeCount = m.nodes;
        error = m.error;
    }

    /// Enters a nested construct. Past `maxDepth` it flags an error, skips one token or group (so callers always
    /// make progress) and returns false; the caller then skips the construct. Pair with `leave`.
    bool enter()
    {
        if (depth >= maxDepth)
        {
            depthCapped = true;
            skip();
            return false;
        }
        depth++;
        return true;
    }

    void leave()
    {
        depth--;
    }

    // ---------------------------------------------------------------- node helpers

    /// Appends a node, growing the buffer geometrically.
    void push(Node n)
    {
        if (nodeCount == nodes.length) nodes.length = nodes.length < 64 ? 64 : nodes.length * 2;
        nodes[nodeCount++] = n;
    }

    /// Starts a node at token `first`; its span and subtree end are set by `close`.
    size_t open(NodeKind kind, size_t first)
    {
        push(Node(kind, 0, 0, cast(uint) first, cast(uint) first, 0, NONE, 0));
        return nodeCount - 1;
    }

    /// Ends node `i`: its last token is the last one consumed, and its subtree is everything pushed since.
    void close(size_t i)
    {
        const last = pos == 0 ? 0 : cast(uint)(pos - 1);
        auto n = &nodes[i];
        n.last = last > n.first ? last : n.first;
        n.end = cast(uint) nodeCount;
    }

    /// Adds a leaf node spanning tokens `first .. last` (inclusive).
    void leaf(NodeKind kind, size_t first, size_t last)
    {
        push(Node(kind, 0, 0, cast(uint) first, cast(uint) last, cast(uint)(nodeCount + 1), NONE, 0));
    }

    /// Adds a decision point at the current token and consumes it.
    void decision()
    {
        leaf(NodeKind.decision, pos, pos);
        bump();
    }

    // ---------------------------------------------------------------- compilation unit

    /// `file = { package | import | module | typeDecl | ";" | statement | method }`.
    ///
    /// Statements and methods are accepted at the top level (they become `NodeKind.other`);
    /// declarations may come in any order.
    void compilationUnit()
    {
        const root = open(NodeKind.compilationUnit, 0);
        while (!atEof())
        {
            const before = pos;
            topLevelItem();
            if (pos == before) skip();
        }
        close(root);
        nodes[root].last = cast(uint) pos; // the Eof token
    }

    void topLevelItem()
    {
        const start = pos;
        if (eat(";")) return;
        if (is_("import"))
        {
            import_();
            return;
        }
        // Annotations may start a package declaration (package-info.java), a module or a type declaration, so open
        // a node first and decide its kind after the modifiers.
        const node = open(NodeKind.other, start);
        const flags = modifiers();
        if (is_("package"))
        {
            nodes[node].kind = NodeKind.package_;
            bump();
            name();
            expect(";");
        }
        else if (isModuleStart())
        {
            nodes[node].kind = NodeKind.module_;
            module_();
        }
        else if (isTypeDeclStart()) typeDecl(node, flags, true);
        else if (pos > start || looksLikeMethod())
        {
            // A method outside any class, or modifiers followed by a variable declaration: parsed like a member, but
            // the node stays `other` (it belongs to no type).
            memberRest(node, false, Body.class_, false);
        }
        else statement();
        close(node);
    }

    /// `import [static] a.b.C [.*] ;`
    void import_()
    {
        const node = open(NodeKind.import_, pos);
        bump();
        if (eat("static")) nodes[node].flags |= importStatic;
        name();
        if (is_(".") && peekIs(1, "*"))
        {
            bump();
            bump();
            nodes[node].flags |= importAsterisk;
        }
        expect(";");
        close(node);
    }

    /// A dotted name `a.b.c` as a `NodeKind.name` node. Stops before a `.` not followed by an identifier.
    void name()
    {
        if (kind() != Tok.ident)
        {
            fail();
            return;
        }
        const node = open(NodeKind.name, pos);
        bump();
        while (is_(".") && kindAt(pos + 1) == Tok.ident)
        {
            bump();
            bump();
        }
        close(node);
    }

    /// `[open] module` followed by a name.
    bool isModuleStart() const
    {
        const k = is_("open") ? 1 : 0;
        return peekIs(k, "module") && kindAt(pos + k + 1) == Tok.ident;
    }

    /// `[open] module a.b { directives }`: the directives (`requires`, `exports`, …) only name modules and packages,
    /// so the body is skipped as a bracketed group.
    void module_()
    {
        eat("open");
        bump(); // module
        name();
        if (is_("{"))
        {
            const p = pair[pos];
            if (p == NONE) skip();
            else
            {
                pos = p;
                bump();
            }
        }
        else fail();
    }

    /// At the top level: `Type name (` (a method outside any class, which is accepted).
    bool looksLikeMethod()
    {
        if (depthCapped) return false;
        const m = mark();
        const ok = type_() && kind() == Tok.ident && peekIs(1, "(");
        reset(m);
        return ok;
    }

    // ---------------------------------------------------------------- declarations

    /// True if `s` is one of the modifier keywords `modifiers` accepts.
    static bool isModifierKeyword(const(char)[] s)
    {
        switch (s)
        {
        case "public", "protected", "private", "abstract", "static", "final", "strictfp", "default", "synchronized",
                "native", "transient", "volatile":
            return true;
        default:
            return false;
        }
    }

    /// Modifiers: annotations and modifier keywords, including the contextual `sealed` and `non-sealed`. Returns
    /// `typeAbstract` if `abstract` was among them.
    ubyte modifiers()
    {
        ubyte flags;
        while (true)
        {
            if (is_("@") && !peekIs(1, "interface")) annotation();
            else if (kind() == Tok.keyword && isModifierKeyword(textAt(pos)))
            {
                if (is_("abstract")) flags |= typeAbstract;
                bump();
            }
            else if (is_("sealed") && continuesDeclaration(pos + 1)) bump();
            else if (is_("non") && peekIs(1, "-") && peekIs(2, "sealed") && adjacent(pos) && adjacent(pos + 1))
            {
                bump();
                bump();
                bump();
            }
            else return flags;
        }
    }

    /// True if token `i` can follow a modifier: another modifier, an annotation or a type keyword. Used to tell
    /// the modifier `sealed` from a type or variable named `sealed`.
    bool continuesDeclaration(size_t i) const
    {
        switch (kindAt(i))
        {
        case Tok.keyword:
            switch (textAt(i))
            {
            case "public", "protected", "private", "abstract", "static", "final", "strictfp", "class", "interface":
                return true;
            default:
                return false;
            }
        case Tok.op:
            return isAt(i, "@");
        case Tok.ident:
            return isAt(i, "sealed") || isAt(i, "non") || isAt(i, "record");
        default:
            return false;
        }
    }

    /// After modifiers: `class`, `interface`, `enum`, `@interface`, or `record Name (` / `record Name <`.
    bool isTypeDeclStart() const
    {
        return is_("class") || is_("interface") || is_("enum") || (is_("@") && peekIs(1, "interface"))
            || (is_("record") && kindAt(pos + 1) == Tok.ident && (peekIs(2, "(") || peekIs(2, "<")));
    }

    /// A type declaration from its keyword on; the modifiers are already parsed into `node`, which becomes the
    /// `NodeKind.typeDecl` (if `emit`; otherwise, for local types, `node` is whatever encloses it).
    ///
    /// Headers: `class N [<T>] [extends T] [implements Ts] [permits Ts]`, `interface N [<T>] [extends Ts]
    /// [permits Ts]`, `enum N [implements Ts]`, `record N [<T>] (components) [implements Ts]`, `@interface N`. The
    /// clauses are accepted in any order.
    void typeDecl(size_t node, ubyte flags, bool emit)
    {
        ubyte sub;
        if (is_("interface")) sub = typeInterface;
        else if (is_("enum")) sub = typeEnum;
        else if (is_("record")) sub = typeRecord;
        else if (is_("@"))
        {
            bump(); // the `interface` follows
            sub = typeAnnotation;
        }
        else sub = typeClass;
        bump();
        const nameToken = expectIdent();
        if (emit)
        {
            auto n = &nodes[node];
            n.kind = NodeKind.typeDecl;
            n.sub = sub;
            n.flags = flags;
            n.name = nameToken;
        }
        while (true)
        {
            if (is_("<")) typeParameters();
            else if (sub == typeRecord && is_("("))
            {
                uint spread;
                const formal = formalParameters(spread);
                if (emit) nodes[node].count = formal;
            }
            else if (eat("extends") || eat("implements") || eat("permits")) typeList();
            else break;
        }
        if (!is_("{"))
        {
            fail();
            return;
        }
        switch (sub)
        {
        case typeEnum: enumBody(emit); break;
        case typeAnnotation: classBody(emit, Body.annotation); break;
        default: classBody(emit, Body.class_); break;
        }
    }

    /// `Type {, Type}` (after `extends`, `implements`, `permits`, `throws`).
    void typeList()
    {
        while (true)
        {
            if (!type_())
            {
                fail();
                return;
            }
            if (!eat(",")) return;
        }
    }

    /// `< T [extends A & B], … >`. Each parameter name is reported as a `NodeKind.typeName`.
    void typeParameters()
    {
        bump(); // <
        while (true)
        {
            annotations();
            if (kind() == Tok.ident)
            {
                leaf(NodeKind.typeName, pos, pos);
                bump();
            }
            else
            {
                fail();
                break;
            }
            if (eat("extends"))
            {
                while (true)
                {
                    if (!type_())
                    {
                        fail();
                        break;
                    }
                    if (!eat("&")) break;
                }
            }
            if (!eat(",")) break;
        }
        expect(">");
    }

    /// `{ members }`. With `emit`, members become nodes (fields, methods, nested types); without (anonymous and
    /// local classes, enum constant bodies), only their references and decision points are recorded.
    void classBody(bool emit, Body body_)
    {
        if (!enter()) return;
        expect("{");
        while (!is_("}") && !atEof())
        {
            const before = pos;
            member(emit, body_);
            if (pos == before) skip();
        }
        expect("}");
        leave();
    }

    /// `{ [constant {, constant}] [,] [; members] }`; a constant is `annotations Name [(args)] [classBody]`.
    void enumBody(bool emit)
    {
        if (!enter()) return;
        expect("{");
        while (kind() == Tok.ident || (is_("@") && !peekIs(1, "interface")))
        {
            const node = emit ? open(NodeKind.enumConstant, pos) : noIndex;
            modifiers();
            expectIdent();
            if (is_("(")) arguments();
            if (is_("{")) classBody(false, Body.class_);
            if (node != noIndex) close(node);
            if (!eat(",")) break;
        }
        if (!eat(";") && !is_("}")) fail();
        while (!is_("}") && !atEof())
        {
            const before = pos;
            member(emit, Body.class_);
            if (pos == before) skip();
        }
        expect("}");
        leave();
    }

    /// One member of a class body (see `classBody`).
    void member(bool emit, Body body_)
    {
        const start = pos;
        if (eat(";")) return;
        const node = emit ? open(NodeKind.other, start) : noIndex;
        if (is_("{")) block(); // instance initializer
        else if (is_("static") && peekIs(1, "{"))
        {
            bump();
            block(); // static initializer
        }
        else
        {
            const flags = modifiers();
            if (isTypeDeclStart()) typeDecl(node, flags, emit);
            else memberRest(node, emit, body_, true);
        }
        if (emit) close(node);
    }

    /// The rest of a member after its modifiers: a constructor, compact constructor, method, annotation element or
    /// field. Fills in `node` (when `emit`) as a `NodeKind.method` or `NodeKind.field`; anything else stays
    /// `NodeKind.other`. `inClass` is false at the top level (methods there are not members of any type).
    void memberRest(size_t node, bool emit, Body body_, bool inClass)
    {
        if (is_("<"))
        {
            // Generic method or constructor; annotations may follow the type parameters.
            typeParameters();
            annotations();
        }
        if (kind() == Tok.ident && peekIs(1, "("))
        {
            // Constructor: `Name(params) [throws Ts] { body }`.
            const nameToken = cast(uint) pos;
            bump();
            uint spread;
            const formal = formalParameters(spread);
            if (eat("throws")) typeList();
            methodBody();
            if (emit && inClass)
            {
                auto n = &nodes[node];
                n.kind = NodeKind.method;
                n.sub = methodConstructor;
                n.name = nameToken;
                n.count = formal + spread;
            }
            return;
        }
        if (kind() == Tok.ident && peekIs(1, "{"))
        {
            // Compact canonical constructor of a record: `Name { body }`.
            bump();
            block();
            return;
        }
        if (!type_())
        {
            fail();
            return;
        }
        if (kind() != Tok.ident)
        {
            fail();
            return;
        }
        const nameToken = cast(uint) pos;
        if (peekIs(1, "("))
        {
            bump();
            if (body_ == Body.annotation && peekIs(1, ")"))
            {
                // Annotation type element: `Type name() [dims] [default value];`.
                bump();
                bump();
                dims();
                if (eat("default")) elementValue();
                expect(";");
                return;
            }
            uint spread;
            const formal = formalParameters(spread);
            dims();
            if (eat("throws")) typeList();
            if (eat("default"))
            {
                elementValue(); // only valid in annotation types, but harmless
                expect(";");
            }
            else methodBody();
            if (emit && inClass)
            {
                auto n = &nodes[node];
                n.kind = NodeKind.method;
                n.name = nameToken;
                n.count = formal + spread;
            }
            return;
        }
        // Field: `Type a [= x], b[] ;`.
        const count = variableDeclarators();
        expect(";");
        if (emit && inClass)
        {
            auto n = &nodes[node];
            n.kind = NodeKind.field;
            n.count = count;
        }
    }

    /// A method body: a block, or `;` for abstract and interface methods.
    void methodBody()
    {
        if (eat(";")) return;
        if (is_("{")) block();
        else fail();
    }

    /// `name [dims] [= init] {, …}`. Returns the number of declarators.
    uint variableDeclarators()
    {
        uint count;
        while (true)
        {
            if (expectIdent() == NONE) return count;
            count++;
            dims();
            if (eat("=")) variableInitializer();
            if (!eat(",")) return count;
        }
    }

    /// An expression or an array initializer `{ a, b, }`.
    void variableInitializer()
    {
        if (!is_("{"))
        {
            expression();
            return;
        }
        if (!enter()) return;
        bump();
        while (!is_("}") && !atEof())
        {
            const before = pos;
            variableInitializer();
            if (!eat(",") && !is_("}"))
            {
                fail();
                if (pos == before) skip();
            }
        }
        expect("}");
        leave();
    }

    /// `( [receiver ,] param {, param} )` where a param is `modifiers Type [...] name [dims]` and a receiver
    /// parameter is `Type [Outer.]this`. Returns the number of ordinary parameters and sets `spread` to the number
    /// of varargs parameters; the receiver is not counted.
    uint formalParameters(out uint spread)
    {
        uint formal;
        if (!enter()) return 0;
        const close = closingParen();
        expect("(");
        while (!is_(")") && !atEof())
        {
            const before = pos;
            modifiers();
            const modified = pos > before;
            if (!type_()) fail();
            else if (eat("..."))
            {
                annotations();
                expectIdent();
                dims();
                spread++;
            }
            else if (eat("this"))
            {
                // Receiver parameter `Foo this`, not counted. One with modifiers
                // (`@A Foo this`, `final Foo this`) is read as an ordinary parameter named `this`, so that one counts.
                if (modified) formal++;
            }
            else if (kind() == Tok.ident && peekIs(1, "."))
            {
                // receiver parameter of an inner class constructor: `Outer Outer.this`
                while (kind() == Tok.ident && peekIs(1, "."))
                {
                    bump();
                    bump();
                }
                expect("this");
            }
            else
            {
                expectIdent();
                dims();
                formal++;
            }
            if (!eat(","))
            {
                if (!is_(")")) resync(close, before);
                break;
            }
        }
        expect(")");
        leave();
        return formal;
    }

    /// `{ [annotations] [] }`, as after a type or a declarator name.
    void dims()
    {
        while (true)
        {
            const m = mark();
            annotations();
            if (is_("[") && peekIs(1, "]"))
            {
                bump();
                bump();
            }
            else
            {
                reset(m);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- annotations

    /// Zero or more annotations (not `@interface`).
    void annotations()
    {
        while (is_("@") && !peekIs(1, "interface")) annotation();
    }

    /// `@Name [( [value | key = value {, key = value}] )]`. Reports the name as a `NodeKind.annotationName`.
    void annotation()
    {
        bump(); // @
        const first = pos;
        if (expectIdent() == NONE) return;
        while (is_(".") && kindAt(pos + 1) == Tok.ident)
        {
            bump();
            bump();
        }
        leaf(NodeKind.annotationName, first, pos - 1);
        if (is_("("))
        {
            if (!enter()) return;
            const close = closingParen();
            bump();
            while (!is_(")") && !atEof())
            {
                const before = pos;
                if (kind() == Tok.ident && peekIs(1, "="))
                {
                    bump();
                    bump();
                }
                elementValue();
                if (!eat(","))
                {
                    if (!is_(")")) resync(close, before);
                    break;
                }
            }
            expect(")");
            leave();
        }
    }

    /// An annotation element value: an annotation, `{ values }` or an expression.
    void elementValue()
    {
        if (is_("@")) annotation();
        else if (is_("{"))
        {
            if (!enter()) return;
            bump();
            while (!is_("}") && !atEof())
            {
                const before = pos;
                elementValue();
                if (!eat(",") && !is_("}"))
                {
                    fail();
                    if (pos == before) skip();
                }
            }
            expect("}");
            leave();
        }
        else expression();
    }

    // ---------------------------------------------------------------- types

    /// True if token `i` is a primitive type keyword or `void` (`void` is accepted wherever a type is).
    bool isPrimitiveAt(size_t i) const
    {
        if (kindAt(i) != Tok.keyword) return false;
        switch (textAt(i))
        {
        case "byte", "short", "int", "long", "char", "float", "double", "boolean", "void":
            return true;
        default:
            return false;
        }
    }

    /// Parses a type if one starts here: `annotations (primitive | classType) {annotations []}`. All or nothing:
    /// on failure nothing is consumed or reported and it returns false.
    ///
    /// A class type `A<X>.B<Y>` is reported as follows: a single name `A` as `NodeKind.typeName`, a
    /// qualified one as one `NodeKind.scopedTypeName` covering everything up to the last name (type arguments and
    /// annotations of the leading parts included, and not reported on their own), plus whatever the last part's
    /// type arguments contain.
    bool type_()
    {
        if (!enter()) return false;
        const ok = typeInner();
        leave();
        return ok;
    }

    bool typeInner()
    {
        const m = mark();
        annotations();
        if (isPrimitiveAt(pos)) bump();
        else if (kind() == Tok.ident)
        {
            const first = pos;
            size_t last = pos;
            const before = nodeCount;
            bump();
            if (is_("<") && !typeArguments())
            {
                reset(m);
                return false;
            }
            bool scoped;
            while (is_("."))
            {
                const dot = mark();
                bump();
                annotations();
                if (kind() != Tok.ident)
                {
                    reset(dot);
                    break;
                }
                last = pos;
                bump();
                // The parts before this name are now inside the scoped name: drop what they reported.
                nodeCount = before;
                scoped = true;
                if (is_("<") && !typeArguments())
                {
                    reset(m);
                    return false;
                }
            }
            if (scoped) leaf(NodeKind.scopedTypeName, first, last);
            else leaf(NodeKind.typeName, first, first);
        }
        else
        {
            reset(m);
            return false;
        }
        dims();
        return true;
    }

    /// `< [typeOrWildcard {, typeOrWildcard}] >` (empty for the diamond `<>`). All or nothing, like `type_`.
    bool typeArguments()
    {
        const m = mark();
        bump(); // <
        if (eat(">")) return true;
        while (true)
        {
            const w = mark();
            annotations();
            if (eat("?"))
            {
                if (eat("extends") || eat("super"))
                {
                    if (!type_())
                    {
                        reset(m);
                        return false;
                    }
                }
            }
            else
            {
                reset(w);
                if (!type_())
                {
                    reset(m);
                    return false;
                }
            }
            if (!eat(",")) break;
        }
        if (!eat(">"))
        {
            reset(m);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- statements

    /// `{ statements }`
    void block()
    {
        if (!enter()) return;
        expect("{");
        statementsUntilBrace();
        expect("}");
        leave();
    }

    /// Statements up to (not including) the closing `}`.
    void statementsUntilBrace()
    {
        while (!is_("}") && !atEof())
        {
            const before = pos;
            statement();
            if (pos == before) skip();
        }
    }

    void statement()
    {
        if (!enter()) return;
        statementInner();
        leave();
    }

    void statementInner()
    {
        switch (kind())
        {
        case Tok.op:
            if (is_("{")) block();
            else if (eat(";")) {}
            else if (is_("@")) localDeclaration();
            else if (is_("<"))
            {
                // `<T>this(…);` explicit constructor invocation with type arguments
                if (!typeArguments()) fail();
                expressionStatement();
            }
            else expressionStatement();
            break;
        case Tok.keyword:
            keywordStatement();
            break;
        case Tok.ident:
            if (peekIs(1, ":"))
            {
                // labeled statement
                bump();
                bump();
                statement();
            }
            else if (is_("yield") && isYield())
            {
                bump();
                expression();
                expect(";");
            }
            else if (isTypeDeclStart() || ((is_("sealed") || is_("non")) && continuesDeclaration(pos + 1)))
                localDeclaration();
            else if (isLocalVariable()) localVariable();
            else expressionStatement();
            break;
        default:
            expressionStatement();
            break;
        }
    }

    /// A statement that starts with a keyword (part of `statementInner`).
    void keywordStatement()
    {
        switch (textAt(pos))
        {
        case "if":
            ifStatement();
            return;
        case "while":
            decision();
            parenthesized();
            statement();
            return;
        case "do":
            decision();
            statement();
            expect("while");
            parenthesized();
            expect(";");
            return;
        case "for":
            forStatement();
            return;
        case "try":
            tryStatement();
            return;
        case "switch":
            switch_();
            return;
        case "return":
            bump();
            if (!is_(";")) expression();
            expect(";");
            return;
        case "throw":
            bump();
            expression();
            expect(";");
            return;
        case "break", "continue":
            bump();
            if (kind() == Tok.ident) bump();
            expect(";");
            return;
        case "assert":
            bump();
            expression();
            if (eat(":")) expression();
            expect(";");
            return;
        default:
            break;
        }
        if (is_("synchronized") && peekIs(1, "("))
        {
            bump();
            parenthesized();
            block();
            return;
        }
        switch (textAt(pos))
        {
        case "class", "interface", "enum", "abstract", "final", "static", "strictfp", "public", "protected",
                "private", "synchronized", "native", "transient", "volatile", "default":
            localDeclaration();
            return;
        default:
            break;
        }
        if (isPrimitiveAt(pos) && !peekIs(1, ".") && !peekIs(1, "::")) localVariable();
        else expressionStatement();
    }

    /// `yield` starts a yield statement unless it is used as a name (`yield = 1;`, `yield.x()`; `yield(…)` is
    /// still a statement).
    bool isYield() const
    {
        const i = pos + 1;
        switch (kindAt(i))
        {
        case Tok.ident, Tok.number, Tok.char_, Tok.str:
            return true;
        case Tok.keyword:
            return !isAt(i, "instanceof");
        case Tok.op:
            return isAt(i, "(") || isAt(i, "-") || isAt(i, "+") || isAt(i, "!") || isAt(i, "~") || isAt(i, "++")
                || isAt(i, "--");
        default:
            return false;
        }
    }

    /// At an identifier: `Type name` follows, so this is a local variable declaration.
    bool isLocalVariable()
    {
        if (depthCapped) return false;
        const m = mark();
        const ok = type_() && kind() == Tok.ident;
        reset(m);
        return ok;
    }

    /// A statement that starts with modifiers: a local class/interface/enum/record or a local variable.
    void localDeclaration()
    {
        const flags = modifiers();
        if (isTypeDeclStart()) typeDecl(noIndex, flags, false);
        else localVariable();
    }

    /// `Type declarators ;` (modifiers already parsed).
    void localVariable()
    {
        if (!type_())
        {
            fail();
            return;
        }
        variableDeclarators();
        expect(";");
    }

    /// `expression ;`
    void expressionStatement()
    {
        expression();
        expect(";");
    }

    /// `( expression )`
    void parenthesized()
    {
        expect("(");
        expression();
        expect(")");
    }

    /// `if (c) s [else if (c) s]… [else s]`. The `else if` chain is a loop, so long chains do not nest.
    void ifStatement()
    {
        while (true)
        {
            decision(); // if
            parenthesized();
            statement();
            if (!eat("else")) return;
            if (!is_("if"))
            {
                statement();
                return;
            }
        }
    }

    /// `for (init; cond; update) s` or `for (modifiers Type name : expr) s`.
    void forStatement()
    {
        decision(); // for
        expect("(");
        // A declaration first (`modifiers Type name`), which an enhanced for continues with `:`.
        const m = mark();
        modifiers();
        if (type_() && kind() == Tok.ident)
        {
            bump();
            dims();
            if (eat(":"))
            {
                expression();
                expect(")");
                statement();
                return;
            }
            // Classic for with a declaration: finish the first declarator, then any others.
            if (eat("=")) variableInitializer();
            if (eat(",")) variableDeclarators();
        }
        else
        {
            reset(m);
            expressionList(";");
        }
        expect(";");
        if (!is_(";")) expression();
        expect(";");
        expressionList(")");
        expect(")");
        statement();
    }

    /// `[expression {, expression}]` up to (not including) `end`.
    void expressionList(string end)
    {
        if (is_(end)) return;
        while (true)
        {
            expression();
            if (!eat(",")) return;
        }
    }

    /// `try [(resources)] block {catch (T | U e) block} [finally block]`.
    void tryStatement()
    {
        bump(); // try
        if (is_("("))
        {
            bump();
            while (!is_(")") && !atEof())
            {
                const before = pos;
                // `modifiers Type name = expr` or an expression naming an existing resource.
                const m = mark();
                modifiers();
                if (type_() && kind() == Tok.ident)
                {
                    bump();
                    expect("=");
                    expression();
                }
                else
                {
                    reset(m);
                    expression();
                }
                if (!eat(";") && !is_(")"))
                {
                    fail();
                    if (pos == before) skip();
                    break;
                }
            }
            expect(")");
        }
        block();
        while (is_("catch"))
        {
            decision();
            expect("(");
            modifiers();
            while (true)
            {
                if (!type_())
                {
                    fail();
                    break;
                }
                if (!eat("|")) break;
            }
            expectIdent();
            expect(")");
            block();
        }
        if (eat("finally")) block();
    }

    /// `switch (x) { … }`, as a statement or an expression. The body is either statement groups
    /// (`case A: case B: statements`) or rules (`case A, B -> expression; | block | throw`).
    void switch_()
    {
        bump(); // switch
        parenthesized();
        if (!is_("{"))
        {
            fail();
            return;
        }
        if (!enter()) return;
        bump();
        while (!is_("}") && !atEof())
        {
            const before = pos;
            if (is_("case") || is_("default"))
            {
                const arrow = switchLabel();
                if (arrow)
                {
                    if (is_("{")) block();
                    else if (is_("throw")) statement();
                    else expressionStatement();
                }
                else
                {
                    while (!is_("case") && !is_("default") && !is_("}") && !atEof())
                    {
                        const b = pos;
                        statement();
                        if (pos == b) skip();
                    }
                }
            }
            if (pos == before) skip();
        }
        expect("}");
        leave();
    }

    /// `case p {, p} [when guard]` or `default`, followed by `:` or `->`. Returns true for `->`. Each `case` is
    /// a decision point; `default` is not.
    bool switchLabel()
    {
        if (eat("default"))
        {
            if (eat("->")) return true;
            expect(":");
            return false;
        }
        decision(); // case
        // Find the `->` that ends this label (the first `->` or `:` at bracket depth 0), so the label's own
        // expressions do not take `A -> x` for a lambda.
        const saved = labelArrow;
        labelArrow = labelEnd();
        while (true)
        {
            const before = pos;
            caseItem();
            if (pos == before || !eat(",")) break;
        }
        if (is_("when"))
        {
            bump();
            expression();
        }
        labelArrow = saved;
        if (eat("->")) return true;
        expect(":");
        return false;
    }

    /// Index of the `->` ending the current case label, or `noIndex` if it ends with `:` (or is malformed).
    size_t labelEnd() const
    {
        size_t i = pos;
        uint ternary;
        while (i < toks.length)
        {
            const t = toks[i];
            if (t.kind == Tok.eof) break;
            if (t.kind == Tok.op)
            {
                const p = pair[i];
                if (p != NONE)
                {
                    i = cast(size_t) p + 1;
                    continue;
                }
                if (isAt(i, "->")) return i;
                if (isAt(i, "?")) ternary++;
                else if (isAt(i, ":"))
                {
                    if (ternary == 0) break;
                    ternary--;
                }
                else if (isAt(i, ";") || isAt(i, "}") || isAt(i, ")") || isAt(i, "{")) break;
            }
            i++;
        }
        return noIndex;
    }

    /// One item of a case label: a record pattern, a type pattern (`String s`), an expression (a constant or an
    /// enum name), or `default` (in `case null, default`).
    void caseItem()
    {
        // `case null, default ->` (Java 21)
        if (eat("default")) return;
        if (tryRecordPattern(true)) return;
        if (!depthCapped)
        {
            const m = mark();
            if (type_() && kind() == Tok.ident && endsPattern(pos + 1))
            {
                bump();
                return;
            }
            reset(m);
        }
        expression();
    }

    /// True if token `i` can follow a pattern in a case label.
    bool endsPattern(size_t i) const
    {
        return isAt(i, "->") || isAt(i, ":") || isAt(i, ",") || isAt(i, "when");
    }

    /// Tries a record pattern `Name(components)` or `Name<T>(components)`; with `inLabel`, it must also be
    /// followed by what ends a case label item. All or nothing. The plain name is not a type reference
    /// (it is a plain identifier), a generic one is.
    bool tryRecordPattern(bool inLabel)
    {
        if (depthCapped || kind() != Tok.ident || !(peekIs(1, "(") || peekIs(1, "<"))) return false;
        const m = mark();
        const ok = recordPattern() && (!inLabel || endsPattern(pos));
        if (!ok) reset(m);
        return ok;
    }

    /// `Name[<T>] ( [component {, component}] )` where a component is `_`, a record pattern or `Type name`.
    bool recordPattern()
    {
        if (!enter()) return false;
        const ok = recordPatternInner();
        leave();
        return ok;
    }

    bool recordPatternInner()
    {
        if (peekIs(1, "<"))
        {
            if (!type_()) return false;
        }
        else bump();
        if (!eat("(")) return false;
        while (!is_(")"))
        {
            if (is_("_") && (peekIs(1, ",") || peekIs(1, ")"))) bump();
            else if (tryRecordPattern(false)) {}
            else if (!(type_() && kind() == Tok.ident)) return false;
            else bump();
            if (!eat(",")) break;
        }
        return eat(")");
    }

    // ---------------------------------------------------------------- expressions

    /// `operand { op operand | instanceof pattern | ? expression : }`, with operator precedence ignored (see the
    /// module documentation). `&&`, `||` and the `?` of a conditional are decision points.
    void expression()
    {
        if (!enter()) return;
        while (true)
        {
            operand();
            // `instanceof` and postfix-like parts that may follow an operand
            while (is_("instanceof"))
            {
                bump();
                eat("final");
                if (!tryRecordPattern(false))
                {
                    if (!type_()) fail();
                    else if (kind() == Tok.ident) bump(); // pattern variable
                }
            }
            if (is_("?"))
            {
                decision();
                expression();
                expect(":");
                continue;
            }
            if (binaryOperator()) continue;
            break;
        }
        leave();
    }

    /// Consumes a binary or assignment operator if one is here (adjacent `>`s count as one shift operator).
    bool binaryOperator()
    {
        if (kind() != Tok.op) return false;
        const op = textAt(pos);
        if (op == "&&" || op == "||")
        {
            decision();
            return true;
        }
        switch (op)
        {
        case "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">=", ">", "<", "<=", "==", "!=", "+", "-",
                "*", "/", "%", "&", "|", "^", "<<":
            break;
        default:
            return false;
        }
        const shift = op == ">";
        bump();
        if (shift)
        {
            // `>>`, `>>>`, `>>=`, `>>>=` arrive as separate `>` / `>=` tokens
            while ((is_(">") || is_(">=")) && toks[pos - 1].end == toks[pos].start)
            {
                const more = is_(">");
                bump();
                if (!more) break;
            }
        }
        return true;
    }

    /// True if token `i` can start an operand that is not `+`/`-`: what may follow `(Type)` in a cast.
    bool startsOperand(size_t i) const
    {
        switch (kindAt(i))
        {
        case Tok.ident, Tok.number, Tok.char_, Tok.str:
            return true;
        case Tok.keyword:
            switch (textAt(i))
            {
            case "this", "super", "new", "true", "false", "null", "switch", "byte", "short", "int", "long", "char",
                    "float", "double", "boolean", "void":
                return true;
            default:
                return false;
            }
        case Tok.op:
            return isAt(i, "(") || isAt(i, "!") || isAt(i, "~")
                || ((isAt(i, "++") || isAt(i, "--")) && startsOperand(i + 1));
        default:
            return false;
        }
    }

    /// `{ prefixOp | cast } (lambda | primary {postfix})`.
    void operand()
    {
        while (true)
        {
            if (kind() == Tok.op)
            {
                const t = textAt(pos);
                if (t == "+" || t == "-" || t == "!" || t == "~" || t == "++" || t == "--")
                {
                    bump();
                    continue;
                }
            }
            if (is_("(") && !lambdaAhead() && tryCast()) continue;
            break;
        }
        if (lambdaAhead())
        {
            lambda();
            return;
        }
        const bare = primary();
        postfix(bare);
    }

    /// Tries `( Type {& Type} )` followed by an operand. It reads `(Foo) x`, `(Foo) !x`,
    /// `(Foo) ++x` and `(int) -x` as casts but `(Foo) - x` as a parenthesized expression minus `x`, i.e. only a
    /// primitive type may be followed by `+`/`-`. All or nothing.
    bool tryCast()
    {
        if (depthCapped) return false;
        const m = mark();
        bump(); // (
        const primitive = isPrimitiveAt(pos) && peekIs(1, ")");
        if (!type_())
        {
            reset(m);
            return false;
        }
        while (eat("&"))
        {
            if (!type_())
            {
                reset(m);
                return false;
            }
        }
        if (!eat(")"))
        {
            reset(m);
            return false;
        }
        if (startsOperand(pos) || (primitive && (is_("+") || is_("-")))) return true;
        reset(m);
        return false;
    }

    /// True if a lambda starts here: `name ->` or `( … ) ->`. The `->` ending a case label does not count.
    bool lambdaAhead() const
    {
        if (kind() == Tok.ident) return peekIs(1, "->") && pos + 1 != labelArrow;
        if (is_("("))
        {
            const p = pair[pos];
            return p != NONE && isAt(cast(size_t) p + 1, "->") && cast(size_t) p + 1 != labelArrow;
        }
        return false;
    }

    /// `params -> (expression | block)`, where params are a name, `(a, b)` or formal parameters `(Type a, var b)`.
    void lambda()
    {
        if (kind() == Tok.ident) bump();
        else
        {
            // Inferred parameters `(a, b)` have no types; otherwise parse formal parameters (which report types).
            const close = cast(size_t) pair[pos];
            bool inferred = true;
            size_t i = pos + 1;
            while (i < close)
            {
                if (!(kindAt(i) == Tok.ident && (isAt(i + 1, ",") || i + 1 == close)))
                {
                    inferred = false;
                    break;
                }
                i += 2;
            }
            if (inferred)
            {
                pos = close;
                bump();
            }
            else
            {
                uint spread;
                formalParameters(spread);
            }
        }
        expect("->");
        if (is_("{")) block();
        else expression();
    }

    /// A primary expression. Returns the token of a lone identifier (the possible object of a member access), else
    /// `noIndex`.
    size_t primary()
    {
        switch (kind())
        {
        case Tok.number, Tok.char_, Tok.str:
            bump();
            return noIndex;
        case Tok.ident:
        {
            if (typeBeforeMemberRefOrClass()) return noIndex;
            const i = pos;
            bump();
            if (is_("("))
            {
                arguments(); // unqualified method call
                return noIndex;
            }
            return i;
        }
        case Tok.keyword:
            switch (textAt(pos))
            {
            case "true", "false", "null", "this", "super":
                bump();
                return noIndex;
            case "new":
                creation();
                return noIndex;
            case "switch":
                switch_();
                return noIndex;
            default:
                // `int.class`, `int[].class`, `int[]::new`
                if (isPrimitiveAt(pos)) type_();
                else fail();
                return noIndex;
            }
        case Tok.op:
            if (is_("("))
            {
                if (!enter()) return noIndex;
                bump();
                expression();
                expect(")");
                leave();
            }
            else if (is_("@"))
            {
                // annotated type in an expression (e.g. before `::`); report and continue with the type
                if (!type_()) skip();
            }
            else fail();
            return noIndex;
        default:
            fail();
            return noIndex;
        }
    }

    /// At an identifier: if a dotted name is followed by type arguments or `[]` and then `::`, or by `[]`/nothing
    /// and then `.class`, parse it as a type (a generic or array type in a method reference,
    /// or the type of a class literal) and return true. Plain `Foo::bar` and `a.b::c` stay expressions.
    bool typeBeforeMemberRefOrClass()
    {
        // end of `a.b.c`
        size_t k = pos + 1;
        while (isAt(k, ".") && kindAt(k + 1) == Tok.ident) k += 2;
        const classLiteral = isAt(k, ".") && isAt(k + 1, "class");
        const array = isAt(k, "[") && isAt(k + 1, "]");
        const generic = isAt(k, "<");
        if (depthCapped || !(classLiteral || array || generic)) return false;
        const m = mark();
        if (type_() && (is_("::") || (is_(".") && peekIs(1, "class")))) return true;
        reset(m);
        return false;
    }

    /// `new [<T>] annotations Type (args) [classBody]` or `new Type dims [initializer]`.
    void creation()
    {
        bump(); // new
        if (is_("<") && !typeArguments()) fail();
        annotations();
        // The created type. `type_` also takes empty dimensions (`new int[][] {…}`); sized ones (`new int[n]`)
        // are left for the loop below.
        if (!type_())
        {
            fail();
            return;
        }
        if (is_("("))
        {
            arguments();
            if (is_("{")) classBody(false, Body.class_);
            return;
        }
        // Array creation: `[expr]…[]…` or (already consumed by type_) `[]… {init}`.
        bool sized;
        while (is_("["))
        {
            if (peekIs(1, "]"))
            {
                bump();
                bump();
                continue;
            }
            if (!enter()) return;
            bump();
            expression();
            expect("]");
            leave();
            sized = true;
        }
        if (!sized)
        {
            if (is_("{")) variableInitializer();
            else fail();
        }
    }

    /// `( [expression {, expression}] )`
    void arguments()
    {
        if (!enter()) return;
        const close = closingParen();
        expect("(");
        while (!is_(")") && !atEof())
        {
            const before = pos;
            expression();
            if (!eat(","))
            {
                if (!is_(")")) resync(close, before);
                break;
            }
        }
        expect(")");
        leave();
    }

    /// Member accesses, calls, indexing, method references and postfix `++`/`--` after a primary. `bare` is the
    /// token of the primary if it was a lone identifier: then the first `.member` makes it a
    /// `NodeKind.memberObject` (except `Outer.this` and `outer.new Inner()`).
    void postfix(size_t bare)
    {
        while (true)
        {
            if (is_("."))
            {
                const next = pos + 1;
                if (isAt(next, "this") || isAt(next, "class"))
                {
                    bump();
                    bump();
                }
                else if (isAt(next, "new"))
                {
                    bump();
                    creation();
                }
                else if (kindAt(next) == Tok.str)
                {
                    // string template `STR."…"`
                    bump();
                    bump();
                }
                else if (isAt(next, "super") || isAt(next, "<") || kindAt(next) == Tok.ident)
                {
                    if (bare != noIndex) leaf(NodeKind.memberObject, bare, bare);
                    bump();
                    if (eat("super"))
                    {
                        if (is_("::"))
                        {
                            bare = noIndex;
                            continue;
                        }
                        if (!eat("."))
                        {
                            // `Outer.super(…)` explicit constructor invocation
                            bare = noIndex;
                            continue;
                        }
                    }
                    if (is_("<") && !typeArguments()) fail();
                    expectIdent();
                    if (is_("(")) arguments();
                }
                else
                {
                    bump();
                    fail();
                }
                bare = noIndex;
            }
            else if (is_("["))
            {
                if (!enter()) return;
                bump();
                expression();
                expect("]");
                leave();
                bare = noIndex;
            }
            else if (is_("("))
            {
                // call on `this`/`super` (`this(…)`), or on something that is not callable (an error, which is
                // reported; parse the arguments anyway)
                arguments();
                bare = noIndex;
            }
            else if (is_("::"))
            {
                bump();
                if (is_("<") && !typeArguments()) fail();
                if (!eat("new")) expectIdent();
                bare = noIndex;
            }
            else if (is_("++") || is_("--"))
            {
                bump();
                bare = noIndex;
            }
            else return;
        }
    }
}

// ------------------------------------------------------------------ tests
//
// Tricky syntax, with expected results taken from the previous parser (see the module comment), plus
// robustness: mutated input, deep nesting. Ported from the Rust edition's tests.

version (unittest)
{
    import std.algorithm : sort;
    import std.array : replicate;
    import std.format : format;

    /// Texts of all nodes of `kind`, sorted.
    private string[] texts(string code, NodeKind kind)
    {
        auto t = parse(code);
        string[] v;
        foreach (n; t.nodes) if (n.kind == kind) v ~= t.text(code, n.first, n.last);
        v.sort();
        return v;
    }

    /// `texts` of a statement inside a method body.
    private string[] inMethod(string stmt, NodeKind kind)
    {
        return texts("class A { void f() { " ~ stmt ~ " } }", kind);
    }

    private bool ok(string code)
    {
        return !parse(code).error;
    }

    /// Checks the structural invariants of a tree: spans inside the token array, subtrees properly nested.
    private void checkTree(ref const Tree t)
    {
        const n = t.nodes.length;
        foreach (i, node; t.nodes)
        {
            assert(node.end > i && node.end <= n);
            assert(node.first <= node.last && node.last < t.tokens.length);
            size_t j = i + 1;
            while (j < node.end)
            {
                assert(t.nodes[j].end <= node.end);
                j = t.nodes[j].end;
            }
        }
        assert(t.nodes[0].end == n);
    }
}

/// Lexes literals, comments and lines.
unittest
{
    const code = "a /* x\n y */ \"s\\\"t\" 'c' '\\'' 0x1.8p-3 1e+5f .5 1_000L \"\"\"\n tb \"\"\" >>= ->\nb";
    auto l = lex(code);
    Tok[] kinds;
    foreach (t; l.tokens) kinds ~= t.kind;
    with (Tok) assert(kinds == [ident, str, char_, char_, number, number, number, number, str, op, op, op, ident, eof]);
    assert(!l.error);
    assert(l.tokens[1].line == 2);
    assert(l.tokens[12].line == 4); // after the text block's line break
    // `>` stays single so that `List<List<X>>` closes twice
    string[] ops;
    foreach (t; l.tokens[9 .. 12]) ops ~= code[t.start .. t.end];
    assert(ops == [">", ">=", "->"]);
}

/// Lexer errors.
unittest
{
    assert(lex("a # b").error);
    assert(lex("\"open\nx").error);
    assert(lex("/* open").error);
    assert(lex("f(]").error);
    assert(lex("int\u00a0x;").error); // no-break space is not whitespace
    assert(!lex("\ufeffclass A {}").error); // leading byte-order mark
    assert(!lex("class \u00e9t\u00e9 { String \u02c7x = \"\0\"; }").error); // a NUL inside a string is fine
}

/// Simple and scoped type names.
unittest
{
    assert(inMethod("Map.Entry<K, V> e;", NodeKind.scopedTypeName) == ["Map.Entry"]);
    assert(inMethod("Map.Entry<K, V> e;", NodeKind.typeName) == ["K", "V"]);
    // leading parts' type arguments and annotations are part of the scoped name, not reported on their own
    assert(inMethod("Outer<String>.Inner<Integer> x;", NodeKind.scopedTypeName) == ["Outer<String>.Inner"]);
    assert(inMethod("Outer<String>.Inner<Integer> x;", NodeKind.typeName) == ["Integer"]);
    assert(inMethod("java.util.@Nn List<@Nn String> l;", NodeKind.annotationName) == ["Nn"]);
    // `var` and type parameters are type names too
    assert(texts("class A<T extends B & C> { <U> void f() { var x = 1; } }", NodeKind.typeName)
        == ["B", "C", "T", "U", "var"]);
}

/// Casts are recognised by what follows the parenthesis.
unittest
{
    assert(inMethod("x = (Foo) y;", NodeKind.typeName) == ["Foo"]);
    assert(inMethod("x = (Foo) !y + (Bar) ++y + (Baz) (y) + (Q) this;", NodeKind.typeName)
        == ["Bar", "Baz", "Foo", "Q"]);
    // a reference type followed by + or - is a parenthesized expression; a primitive type is still a cast
    assert(inMethod("x = (Foo) - y; x = (foo) + 1; x = (y)++;", NodeKind.typeName).length == 0);
    assert(ok("class A { void f() { x = (int) -y; x = (A & B) () -> 1; } }"));
    assert(inMethod("o = (Comparable<String> & Serializable) () -> 1;", NodeKind.typeName)
        == ["Comparable", "Serializable", "String"]);
}

/// Member objects, class literals and method references.
unittest
{
    assert(inMethod("Util.run(); Foo.BAR.baz(); a.b.C.d(); Outer.this.x = 1; o.new Inner();", NodeKind.memberObject)
        == ["Foo", "Util", "a"]);
    assert(inMethod("Outer.super.m(); Collections.<T>empty();", NodeKind.memberObject) == ["Collections", "Outer"]);
    // class literals are types: qualified ones are scoped names, not member accesses
    assert(inMethod("c = a.b.C.class; d = Foo[].class; e = int.class;", NodeKind.scopedTypeName) == ["a.b.C"]);
    assert(inMethod("c = a.b.C.class; d = Foo[].class;", NodeKind.memberObject).length == 0);
    // `Foo::bar` keeps `Foo` an expression; generic and array forms are types
    assert(inMethod("f = String::valueOf;", NodeKind.typeName).length == 0);
    assert(inMethod("f = List<String>::size; g = Foo[]::new; h = Foo.Bar::m;", NodeKind.typeName)
        == ["Foo", "List", "String"]);
    assert(inMethod("h = Foo.Bar::m;", NodeKind.memberObject) == ["Foo"]);
}

/// Decision points.
unittest
{
    size_t count(string stmt) { return inMethod(stmt, NodeKind.decision).length; }
    assert(count("if (a && b || c) {} else if (d) {} else {}") == 4);
    assert(count("for (;;) {} for (var x : xs) {} while (a) {} do {} while (b);") == 4);
    assert(count("try {} catch (A | B e) {} catch (C e) {} finally {}") == 2);
    assert(count("x = a ? b : c ? d : e; List<?> l;") == 2);
    assert(count("switch (x) { case 1: case 2: break; default: }") == 2);
    assert(count("y = switch (o) { case Integer i when i > 0 && i < 9 -> 1; case null, default -> 2; };") == 3);
    // lambdas and anonymous classes count towards the enclosing method
    assert(count("r = () -> { if (a) {} }; o = new Object() { void g() { while (b) {} } };") == 2);
}

/// Patterns and switch rules.
unittest
{
    const code = "class A { Object f(Object o) { return switch (o) {
        case Point(int x, var y) -> 1;
        case Box<String>(Pair(var a, var b)) -> 2;
        case String s -> { yield s; }
        case Color.RED -> 3;
        case FOO -> 4;
        default -> throw new IllegalStateException();
    }; } }";
    assert(ok(code));
    // a record pattern's plain name is not a type reference, a generic one is
    assert(texts(code, NodeKind.typeName)
        == ["Box", "IllegalStateException", "Object", "Object", "String", "String", "var", "var", "var"]);
    assert(texts(code, NodeKind.memberObject) == ["Color"]);
    assert(ok("class A { void f() { if (o instanceof Point(int a, int b) && o instanceof final List<?> l) {} } }"));
}

/// Declarations and counts.
unittest
{
    const code = "@Ann package p;
        import static a.B.c;
        import a.b.*;
        public abstract sealed class A<T> extends B<T>.C implements I permits D {
          int x, y[] = {1}, z;
          A(@X A this, int a) {}
          A(A this) {}
          <R> R m(String... rest) { return null; }
          record R(int a, String... more) { R {} }
          @interface An { int v() default 1; int K = 2; }
          enum E { P(1) { void g() {} }, Q; E(int i) {} }
          { } static { }
        }";
    auto t = parse(code);
    assert(!t.error);
    NodeKind[] kinds;
    foreach (c; t.children(0)) kinds ~= t.nodes[c].kind;
    with (NodeKind) assert(kinds == [package_, import_, import_, typeDecl]);
    const a = t.children(0)[$ - 1];
    assert(t.nodes[a].flags == typeAbstract);
    struct Member { NodeKind kind; uint count; }
    Member[] members;
    foreach (c; t.children(a))
    {
        const k = t.nodes[c].kind;
        if (k != NodeKind.typeName && k != NodeKind.scopedTypeName && k != NodeKind.annotationName)
            members ~= Member(k, t.nodes[c].count);
    }
    // a receiver parameter with modifiers is counted as a parameter
    with (NodeKind) assert(members == [Member(field, 3), Member(method, 2), Member(method, 0), Member(method, 1),
        Member(typeDecl, 1), Member(typeDecl, 0), Member(typeDecl, 0), Member(other, 0), Member(other, 0)]);
    const imp = t.children(0)[1];
    assert(t.nodes[imp].flags == importStatic);
}

/// Contextual keywords and top-level oddities.
unittest
{
    assert(ok("class A { void f() { yield = 1; int yield = 2; var record = 3; String sealed = \"s\"; f(record, sealed); } }"));
    assert(ok("non-sealed class A {} sealed interface I permits A {} record R() {}"));
    assert(ok("module m.n { requires transitive java.base; exports a.b to c; }"));
    assert(ok("int x = 1; void top() {} class A {}")); // statements and methods are accepted at the top
    assert(ok("class A { void f() { label: for (;;) { break label; } assert x : \"m\"; synchronized (this) {} } }"));
    assert(ok("class A { String s = STR.\"a \\{b}\"; int[] m()[] { return null; } A() { this(1); } A(O o) { o.super(); } }"));
}

/// Flags invalid code.
unittest
{
    foreach (bad; ["class A {", "class A { void f() { x = ; } }", "class A { int }", "class { }", "}",
            "class A { void f() { if x {} } }"])
        assert(parse(bad).error, bad);
}

/// Recovers declarations after errors.
unittest
{
    auto t = parse("class A { void f() { x = ; } int y; void g() {} }");
    assert(t.error);
    const a = t.children(0)[0];
    size_t methods;
    foreach (c; t.children(a)) if (t.nodes[c].kind == NodeKind.method) methods++;
    assert(methods == 2);
}

version (unittest) private immutable string corpus = `@Ann(v = Foo.class, o = {Bar.BAZ, @In(1)}) package p.q;
import static java.util.Map.Entry; import a.*;
public sealed abstract class T<K extends Comparable<? super K> & java.io.Serializable, V> extends Base<K>.In<V> implements I permits S {
  private static final int A = 1, B[] = {1, 2};
  Map<String, List<Map.Entry<K, V>>> m = new HashMap<>();
  public <X extends Number> T(X t, String... rest) throws IOException { super(t); }
  @Override public <R> R apply(final Function<? super K, ? extends R> f) throws Exception {
    label: for (var e : m.entrySet()) { if (e == null) break label; else if (e.getKey() == null) continue; }
    int x = a.length > 2 ? a[0] : a[1] > 3 ? 4 : 5; x >>>= 1;
    Object o = (Comparable<String> & Serializable) () -> 1;
    Runnable r = () -> { try (var in = new FileInputStream("f")) { } catch (IOException | RuntimeException e) { } finally { } };
    Object y = switch (o) { case Integer i when i > 0 -> "s"; case Point(int px, var py) -> "p"; default -> throw new X(); };
    switch (x) { case 1: case 2: x++; break; default: x = 0; }
    Object anon = new Object() { int f; void g() { if (f > 0) {} } };
    String tb = """
        text "block"
        """;
    return (R) Util.<R>cast(T.super.toString(), int[].class, String::valueOf);
  }
  enum E { A(1) { void f() {} }, B; E(int x) {} }
  record R(int x) { R { if (x < 0) throw new IAE(); } }
  @interface An { int value() default 1; }
}
`;

/// Random byte deletions, insertions and truncations of a corpus file must never throw or break the tree. Unlike
/// the Rust test, the mutated bytes are parsed as they are (possibly invalid UTF-8), which the lexer must survive too.
unittest
{
    ulong seed = 0x9E37_79B9_7F4A_7C15;
    size_t next(size_t bound)
    {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        return cast(size_t)(seed % (bound < 1 ? 1 : bound));
    }
    immutable alphabet = cast(immutable(ubyte)[]) "{}()[]<>;,.:?@=&|-\"'/*\\\n abcAB01";
    foreach (_; 0 .. 3000)
    {
        auto b = (cast(immutable(ubyte)[]) corpus).dup;
        foreach (__; 0 .. 1 + next(4))
        {
            if (b.length == 0) break;
            const i = next(b.length);
            switch (next(4))
            {
            case 0:
            {
                const k = 1 + next(8);
                const e = i + k < b.length ? i + k : b.length;
                b = b[0 .. i] ~ b[e .. $];
                break;
            }
            case 1:
                b = b[0 .. i] ~ alphabet[next(alphabet.length)] ~ b[i .. $];
                break;
            case 2:
                b = b[0 .. i] ~ cast(ubyte) next(256) ~ b[i .. $];
                break;
            default:
                b = b[0 .. i];
                break;
            }
        }
        auto t = parse(cast(string) b.idup);
        checkTree(t);
    }
}

/// Pathological nesting must not overflow the stack (run on a thread with a small 2 MiB stack) and must finish.
unittest
{
    import core.thread : Thread;
    static void run()
    {
        string[] inputs = [
            format("class A { int x = %s1%s; }", "(".replicate(100_000), ")".replicate(100_000)),
            format("class A { void f() %s }", "{".replicate(100_000)),
            format("class A { int x = %s1; }", "a + ".replicate(100_000)),
            format("class A { void f() { %s {} } }", "if (a) {} else ".replicate(20_000)),
            format("class A { %s x; }", "List<".replicate(50_000)),
            format("class A { Object x = %s1; }", "y -> ".replicate(50_000)),
            format("class A { int x = %s1; }", "(A) -".replicate(50_000)),
            format("class A { Object x = %s; }", "new A() { int y = ".replicate(20_000)),
            "class A { ".replicate(50_000),
            format("@A(%s) class A {}", "@A(".replicate(50_000)),
            format("class A { int x = %s; }", "a ? b : ".replicate(50_000) ~ "c"),
        ];
        foreach (code; inputs)
        {
            auto t = parse(code);
            checkTree(t);
        }
        // a long but shallow chain parses without error
        assert(!parse(format("class A { void f() { %s {} } }", "if (a) {} else ".replicate(20_000))).error);
    }
    auto thread = new Thread(&run, 2 * 1024 * 1024);
    thread.start();
    thread.join(); // rethrows a failed assertion
}

unittest // brackets that alternate between opening and closing are paired in linear time
{
    import std.array : replicate;
    import std.datetime.stopwatch : AutoStart, StopWatch;
    // Appending to the shortened bracket stack used to copy it every time.
    auto sw = StopWatch(AutoStart.yes);
    auto t = parse("class C { void m() { x = " ~ "((A)".replicate(100_000) ~ "a" ~ ")".replicate(100_000) ~ "; } }");
    assert(t.error); // deeper than maxDepth
    assert(sw.peek.total!"seconds" < 10);
}

unittest // `a < a < a …` past the depth cap: no more speculation, so linear (it took minutes unoptimised before)
{
    import std.array : replicate;
    import std.datetime.stopwatch : AutoStart, StopWatch;
    auto sw = StopWatch(AutoStart.yes);
    auto t = parse("class C { void m() { x = " ~ "a < ".replicate(1_000_000) ~ "b; List" ~ "<List".replicate(400_000)
        ~ " y; } }");
    assert(t.error);
    assert(sw.peek.total!"seconds" < 30);
}
