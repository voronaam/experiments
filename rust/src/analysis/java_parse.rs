//! A small, dependency-free Java lexer and error-tolerant structural parser, built for the Java analyser
//! ([`super::java`]).
//!
//! It is not a full Java front end. It reads the whole language (Java 21, including records, sealed types, switch
//! expressions and patterns) but keeps only what the analyser needs, in a tiny syntax tree:
//! - the declarations: package, imports, types (class, interface, enum, record, annotation type) and their members
//!   (fields, enum constants, methods and constructors) with names, token spans and counts;
//! - the *references*: every type name (`List`, `Map.Entry`), annotation name and identifier used as the object of a
//!   member access or call (`Util` in `Util.run()`);
//! - the *decision points* for McCabe complexity (`if`, loops, `catch`, `case`, `?:`, `&&`, `||`);
//! - whether the file had syntax errors.
//!
//! The rules for what counts as which follow tree-sitter-java 0.23, which this parser replaced: the analyser's output
//! is meant to be identical. Where the syntax is ambiguous (is `(a) - b` a cast?), the comments below say
//! what it does.
//!
//! # Pipeline
//! 1. [`lex`] turns the source into a flat array of [`Token`]s (comments and whitespace dropped), each with its kind,
//!    byte range and 1-based line. It also pairs up brackets (`pair`), so the parser can jump over a bracketed group
//!    in one step.
//! 2. [`parse`] runs a recursive-descent parser over the tokens and produces a [`Tree`]: a flat `Vec` of [`Node`]s in
//!    pre-order, where each node knows the index just past its subtree (`end`). Children of node `i` are found by
//!    starting at `i + 1` and hopping from each child to its `end`.
//!
//! # Grammar subset
//! The parser follows the Java grammar closely enough to tell types from expressions, which is what decides whether
//! a name is a type reference. Outline (`[]` optional, `{}` repeated):
//!
//! ```text
//! file        = { package | import | module | typeDecl | ";" | statement | method }
//! typeDecl    = modifiers ("class" | "interface" | "enum" | "record" | "@interface") Ident header body
//! member      = ";" | block | "static" block | modifiers (typeDecl | constructor | method | field | compactCtor)
//! statement   = block | if | for | while | do | try | switch | return | throw | break | continue | yield | assert
//!             | synchronized | label ":" statement | localTypeDecl | localVariable ";" | expression ";"
//! expression  = operand { binaryOp operand | "instanceof" pattern | "?" expression ":" } ;  precedence is ignored
//! operand     = { prefixOp | cast } (lambda | primary { postfix })
//! type        = annotations (primitive | Ident [typeArgs] { "." annotations Ident [typeArgs] }) { annotations "[]" }
//! ```
//!
//! Precedence is deliberately ignored: none of the analyser's metrics depend on how operators group, only on which
//! tokens are types, member-access objects and decision points.
//!
//! Ambiguities are resolved by bounded look-ahead or by *speculation*: the parser saves its state (token position,
//! node count, error flag), tries one reading (say, "a type followed by a name" for a local variable), and restores
//! the state if that fails. Nodes pushed during a failed attempt are dropped by truncating the node array.
//!
//! # Error recovery
//! The parser never stops and never panics. When a token does not fit, it sets the error flag and carries on:
//! - `expect` of a missing token flags the error without consuming anything;
//! - every loop over list items (members, statements, arguments, …) checks that an iteration consumed at least one
//!   token; if not, it flags the error and skips one token, or a whole bracketed group if the token opens one (using
//!   the bracket pairs from the lexer), so it always makes progress;
//! - recursion is capped at [`MAX_DEPTH`] nested constructs; deeper input is skipped group by group and flagged.
//!
//! So a file with errors still yields every declaration the parser could recognise, which is what the analyser
//! reports (with the file counted as failed).
//!
//! Everything works on byte offsets into the UTF-8 source; line numbers count `\n` only, as the analyser's
//! [`super::text::lines`] does.

/// Maximum nesting of statements, expressions, types and bodies before the parser gives up on the nested part (and
/// flags an error). Real code stays far below; the cap keeps adversarial input from overflowing the stack (the
/// parser recurses once per nesting level, with a few stack frames each).
pub const MAX_DEPTH: u32 = 300;

/// "No token" / "no pair" marker in index fields.
pub const NONE: u32 = u32::MAX;

// ------------------------------------------------------------------ tokens

/// What a [`Token`] is. Punctuation and operators are all [`Tok::Op`]; the parser compares their text.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Tok {
    /// A name. Includes the contextual keywords (`record`, `var`, `yield`, `sealed`, `permits`, `when`, `module`,
    /// …), which are names everywhere except where the parser looks for them, and `goto`/`const`, which
    /// are not reserved either.
    Ident,
    /// A reserved word (see [`KEYWORDS`]), including the literals `true`, `false` and `null`.
    Keyword,
    /// A numeric literal (`12`, `0x1F`, `1.5e-3f`, `1_000L`).
    Number,
    /// A character literal (`'a'`, `'\n'`).
    Char,
    /// A string literal (`"…"`) or text block (`"""…"""`).
    Str,
    /// An operator or punctuation (see [`OPERATORS`]).
    Op,
    /// Anything the lexer could not read: a stray character (`#`, `\`), an unterminated literal or comment. Its
    /// presence makes the file count as having errors.
    Error,
    /// End of input. The token array always ends with exactly one, so look-ahead never runs off the end.
    Eof,
}

/// One token: kind, byte range `[start, end)` in the source, and the 1-based line it starts on.
#[derive(Clone, Copy, Debug)]
pub struct Token {
    pub kind: Tok,
    pub start: u32,
    pub end: u32,
    pub line: u32,
}

/// The reserved words of Java that are lexed as keywords. `goto` and `const` are missing on purpose
/// (they are ordinary identifiers), as are all contextual keywords.
const KEYWORDS: [&str; 51] = [
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "continue", "default", "do",
    "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements", "import",
    "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
    "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
    "void", "volatile", "while", "true", "false", "null",
];

/// Operators and punctuation, longest first so the lexer can take the first match. `>` is always a token of its own
/// (except in `>=` and `->`), so that the `>>` closing two type-argument lists is two tokens; in expressions the
/// parser glues adjacent `>`s back into shift operators.
const OPERATORS: [&str; 46] = [
    "<<=", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=",
    "|=", "^=", "<<", "(", ")", "{", "}", "[", "]", ";", ",", ".", "@", "=", ">", "<", "!", "~", "?", ":", "+", "-",
    "*", "/", "&", "|", "^", "%",
];

/// The result of [`lex`].
pub struct Lexed {
    pub tokens: Vec<Token>,
    /// For each token that opens a bracket (`(`, `[`, `{`), the index of the token that closes it; [`NONE`] for
    /// all other tokens and for unclosed brackets.
    pub pair: Vec<u32>,
    /// True if the lexer met something it could not read (an [`Tok::Error`] token, or brackets that do not match).
    pub error: bool,
}

/// Splits Java source into tokens, dropping whitespace and comments.
///
/// Whitespace is ASCII space, tab, form feed, vertical tab, `\r` and `\n` (other Unicode spaces are errors).
/// A byte-order mark at the very start is skipped. Identifiers are ASCII letters, digits, `_` and
/// `$`, plus any non-ASCII letter or digit (`char::is_alphanumeric`). Unicode escapes (`A`) are not decoded.
pub fn lex(code: &str) -> Lexed {
    let b = code.as_bytes();
    let n = b.len();
    let mut tokens: Vec<Token> = Vec::with_capacity(n / 4 + 1);
    let mut error = false;
    let mut line: u32 = 1;
    let mut i = 0;
    // A byte-order mark (U+FEFF, EF BB BF in UTF-8) at the start of the file is not part of the code.
    if b.starts_with(&[0xEF, 0xBB, 0xBF]) {
        i = 3;
    }
    while i < n {
        let c = b[i];
        // whitespace
        if c == b'\n' {
            line += 1;
            i += 1;
            continue;
        }
        if c == b' ' || c == b'\t' || c == b'\r' || c == 0x0B || c == 0x0C {
            i += 1;
            continue;
        }
        let start = i;
        let start_line = line;
        // comments
        if c == b'/' && i + 1 < n && b[i + 1] == b'/' {
            while i < n && b[i] != b'\n' {
                i += 1;
            }
            continue;
        }
        if c == b'/' && i + 1 < n && b[i + 1] == b'*' {
            i += 2;
            loop {
                if i >= n {
                    error = true; // unterminated block comment
                    break;
                }
                if b[i] == b'*' && i + 1 < n && b[i + 1] == b'/' {
                    i += 2;
                    break;
                }
                if b[i] == b'\n' {
                    line += 1;
                }
                i += 1;
            }
            continue;
        }
        let kind;
        if is_ident_start(code, i) {
            i = skip_ident(code, i);
            kind = if KEYWORDS.contains(&&code[start..i]) { Tok::Keyword } else { Tok::Ident };
        } else if c.is_ascii_digit() || (c == b'.' && i + 1 < n && b[i + 1].is_ascii_digit()) {
            i = skip_number(b, i);
            kind = Tok::Number;
        } else if c == b'"' && i + 2 < n && b[i + 1] == b'"' && b[i + 2] == b'"' {
            // Text block: runs to the next unescaped `"""`, across lines.
            i += 3;
            kind = loop {
                if i >= n {
                    break Tok::Error;
                }
                if b[i] == b'\\' {
                    if i + 1 < n && b[i + 1] == b'\n' {
                        line += 1;
                    }
                    i += 2;
                    continue;
                }
                if b[i] == b'"' && i + 2 < n && b[i + 1] == b'"' && b[i + 2] == b'"' {
                    i += 3;
                    break Tok::Str;
                }
                if b[i] == b'\n' {
                    line += 1;
                }
                i += 1;
            };
            i = i.min(n);
        } else if c == b'"' || c == b'\'' {
            // String or character literal: ends at the matching quote; a line break or the end of the file first
            // makes it an unterminated (error) token that stops before the line break.
            i += 1;
            kind = loop {
                if i >= n || b[i] == b'\n' {
                    break Tok::Error;
                }
                if b[i] == b'\\' {
                    // `\` + line break is accepted inside character literals; keep it simple
                    // and let the escape swallow any following byte except a line break.
                    if i + 1 < n && b[i + 1] != b'\n' {
                        i += 2;
                    } else {
                        i += 1;
                    }
                    continue;
                }
                if b[i] == c {
                    i += 1;
                    break if c == b'"' { Tok::Str } else { Tok::Char };
                }
                i += 1;
            };
            // Back up to a character boundary: an escape may have stepped into the middle of a multi-byte char.
            while i < n && !code.is_char_boundary(i) {
                i += 1;
            }
        } else if let Some(op) = OPERATORS.iter().find(|op| b[i..].starts_with(op.as_bytes())) {
            i += op.len();
            kind = Tok::Op;
        } else {
            // A stray character: one whole UTF-8 character becomes an error token.
            i += 1;
            while i < n && !code.is_char_boundary(i) {
                i += 1;
            }
            kind = Tok::Error;
        }
        if kind == Tok::Error {
            error = true;
        }
        tokens.push(Token { kind, start: start as u32, end: i as u32, line: start_line });
    }
    tokens.push(Token { kind: Tok::Eof, start: n as u32, end: n as u32, line });

    // Pair brackets with a stack. A closer that does not match the innermost opener is left unpaired (and is an
    // error); so is an opener that is never closed.
    let mut pair = vec![NONE; tokens.len()];
    let mut stack: Vec<usize> = Vec::new();
    for (k, t) in tokens.iter().enumerate() {
        if t.kind != Tok::Op || t.end - t.start != 1 {
            continue;
        }
        let ch = b[t.start as usize];
        match ch {
            b'(' | b'[' | b'{' => stack.push(k),
            b')' | b']' | b'}' => {
                let open = match ch {
                    b')' => b'(',
                    b']' => b'[',
                    _ => b'{',
                };
                match stack.last() {
                    Some(&top) if b[tokens[top].start as usize] == open => {
                        pair[top] = k as u32;
                        stack.pop();
                    }
                    _ => error = true,
                }
            }
            _ => {}
        }
    }
    if !stack.is_empty() {
        error = true;
    }
    Lexed { tokens, pair, error }
}

/// True if an identifier starts at byte `i`: an ASCII letter, `_`, `$`, or a non-ASCII letter.
fn is_ident_start(code: &str, i: usize) -> bool {
    let c = code.as_bytes()[i];
    if c < 0x80 {
        return c.is_ascii_alphabetic() || c == b'_' || c == b'$';
    }
    code[i..].chars().next().is_some_and(|ch| ch.is_alphabetic())
}

/// The end of the identifier starting at `i`: letters, digits, `_`, `$` and non-ASCII letters or digits.
fn skip_ident(code: &str, mut i: usize) -> usize {
    let b = code.as_bytes();
    while i < b.len() {
        let c = b[i];
        if c < 0x80 {
            if c.is_ascii_alphanumeric() || c == b'_' || c == b'$' {
                i += 1;
                continue;
            }
            break;
        }
        match code[i..].chars().next() {
            Some(ch) if ch.is_alphanumeric() => i += ch.len_utf8(),
            _ => break,
        }
    }
    i
}

/// The end of the numeric literal starting at `i`. Loose on purpose (validity does not matter here): letters,
/// digits and `_` continue it, as does one `.` (not followed by another `.`) and a sign right after an exponent
/// marker (`e`/`E` in decimal literals, `p`/`P` in hexadecimal ones).
fn skip_number(b: &[u8], mut i: usize) -> usize {
    let hex = b[i] == b'0' && i + 1 < b.len() && (b[i + 1] == b'x' || b[i + 1] == b'X');
    let mut dot = false;
    while i < b.len() {
        let c = b[i];
        if c.is_ascii_alphanumeric() || c == b'_' {
            i += 1;
        } else if c == b'.' && !dot && !(i + 1 < b.len() && b[i + 1] == b'.') {
            dot = true;
            i += 1;
        } else if (c == b'+' || c == b'-') && i > 0 {
            let p = b[i - 1];
            let exponent = if hex { p == b'p' || p == b'P' } else { p == b'e' || p == b'E' };
            if !exponent {
                break;
            }
            i += 1;
        } else {
            break;
        }
    }
    i
}

// ------------------------------------------------------------------ tree

/// What a [`Node`] stands for.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum NodeKind {
    /// The root (always node 0). Children: the top-level declarations and statements.
    CompilationUnit,
    /// `package a.b;` (with any package annotations). Child: the [`NodeKind::Name`].
    Package,
    /// `import [static] a.b.C [.*];`. `flags`: [`IMPORT_STATIC`], [`IMPORT_ASTERISK`]. Child: the
    /// [`NodeKind::Name`] (without the `.*`).
    Import,
    /// A dotted name of a package or import; tokens `first..=last`.
    Name,
    /// `module m { … }` (module-info.java). Its directives are skipped.
    Module,
    /// A class, interface, enum, record or annotation type declared at the top level or as a member of another
    /// type. `sub`: one of the `TYPE_*` constants; `flags`: [`TYPE_ABSTRACT`] if it has the `abstract` modifier;
    /// `name`: the name token; `count`: for records, the number of record components (`int... rest` excluded).
    /// Children: references from the header, and the members of its body. Types declared inside code (local and
    /// anonymous classes) get no node; their contents belong to the enclosing node.
    TypeDecl,
    /// A field (or interface constant) declaration. `count`: number of declared variables (`int a, b;` is 2).
    Field,
    /// An enum constant, including its arguments and class body.
    EnumConstant,
    /// A method or constructor of a type. `sub`: [`METHOD_CONSTRUCTOR`] for constructors; `name`: the name token;
    /// `count`: the number of parameters (a varargs parameter counts, a receiver parameter `Foo this` does not).
    Method,
    /// Any other member or top-level item: initializer blocks, compact record constructors, annotation type
    /// elements, top-level statements and methods, and unparsable member debris. Holds the references and
    /// decision points inside it.
    Other,
    /// A simple type name (`String`, `T`, `var`): token `first`.
    TypeName,
    /// A qualified type name (`Map.Entry`, `java.util.List`, `Outer<String>.Inner`): tokens `first..=last`, which
    /// may include type arguments and annotations of the leading parts. Type names inside those leading parts are
    /// *not* reported separately (the analyser does not descend into a qualified name).
    ScopedTypeName,
    /// The name of an annotation (`Override`, `javax.annotation.Nullable`): tokens `first..=last`.
    AnnotationName,
    /// An identifier used as the object of a field access or method call (`Util` in `Util.run()` or
    /// `Util.VALUE`, also `Outer.super.m()`): token `first`. Not reported for `Outer.this` or `outer.new Inner()`.
    MemberObject,
    /// A decision point for McCabe complexity at token `first`: `if`, `for`, `while`, `do` (its trailing `while` is
    /// not another one), `catch`, a `case` label, a ternary `?`, `&&` or `||`.
    Decision,
}

/// [`NodeKind::TypeDecl`] `sub` values.
pub const TYPE_CLASS: u8 = 0;
pub const TYPE_INTERFACE: u8 = 1;
pub const TYPE_ENUM: u8 = 2;
pub const TYPE_RECORD: u8 = 3;
pub const TYPE_ANNOTATION: u8 = 4;
/// [`NodeKind::TypeDecl`] flag: declared `abstract`.
pub const TYPE_ABSTRACT: u8 = 1;
/// [`NodeKind::Method`] `sub` value of constructors (0 = method).
pub const METHOD_CONSTRUCTOR: u8 = 1;
/// [`NodeKind::Import`] flags.
pub const IMPORT_STATIC: u8 = 1;
pub const IMPORT_ASTERISK: u8 = 2;

/// One node of the syntax tree. Tokens `first..=last` are its source span (`last` may be less than `first` only
/// for an empty node at the end of the file, which the parser avoids).
#[derive(Clone, Copy, Debug)]
pub struct Node {
    pub kind: NodeKind,
    /// Kind-specific sub-kind (see [`NodeKind`]).
    pub sub: u8,
    /// Kind-specific flag bits (see [`NodeKind`]).
    pub flags: u8,
    pub first: u32,
    pub last: u32,
    /// Index of the first node after this node's subtree, so children of `i` are `i + 1`, then each child's `end`,
    /// while below `nodes[i].end`.
    pub end: u32,
    /// A name token, or [`NONE`].
    pub name: u32,
    /// Kind-specific count (see [`NodeKind`]).
    pub count: u32,
}

/// A parsed file: tokens, nodes and the error flag.
pub struct Tree {
    pub tokens: Vec<Token>,
    pub nodes: Vec<Node>,
    /// True if the lexer or the parser met anything that is not valid Java.
    pub error: bool,
}

impl Tree {
    /// Indices of the direct children of node `i`.
    pub fn children(&self, i: usize) -> Vec<usize> {
        let mut out = Vec::new();
        let mut j = i + 1;
        while j < self.nodes[i].end as usize {
            out.push(j);
            j = self.nodes[j].end as usize;
        }
        out
    }

    /// The source text of tokens `first..=last` (including any comments and whitespace between them).
    pub fn text<'a>(&self, code: &'a str, first: u32, last: u32) -> &'a str {
        let s = self.tokens[first as usize].start as usize;
        let e = self.tokens[last as usize].end as usize;
        if e < s { "" } else { &code[s..e] }
    }

    /// 1-based line the node starts on.
    pub fn first_line(&self, i: usize) -> u32 {
        self.tokens[self.nodes[i].first as usize].line
    }

    /// 1-based line the node ends on: the line where its last token ends (a text block can span lines).
    pub fn last_line(&self, code: &str, i: usize) -> u32 {
        let t = self.tokens[self.nodes[i].last as usize];
        let text = &code.as_bytes()[t.start as usize..t.end as usize];
        t.line + text.iter().filter(|&&c| c == b'\n').count() as u32
    }
}

// ------------------------------------------------------------------ parser

/// Parses a Java file. Never fails: problems set [`Tree::error`].
pub fn parse(code: &str) -> Tree {
    let lexed = lex(code);
    let mut p = Parser {
        code: code.as_bytes(),
        toks: lexed.tokens,
        pair: lexed.pair,
        pos: 0,
        nodes: Vec::new(),
        error: lexed.error,
        depth: 0,
        label_arrow: NONE as usize,
    };
    p.compilation_unit();
    Tree { tokens: p.toks, nodes: p.nodes, error: p.error }
}

/// Which kind of body a member is parsed in; decides how `Type name()` is read.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Body {
    /// A class, interface, enum (after the constants) or record body, or an anonymous class body.
    Class,
    /// An annotation type body: `int value() default 1;` is an element, not a method.
    Annotation,
}

/// Saved parser state for speculation (see the module documentation).
#[derive(Clone, Copy)]
struct Mark {
    pos: usize,
    nodes: usize,
    error: bool,
}

struct Parser<'a> {
    code: &'a [u8],
    toks: Vec<Token>,
    pair: Vec<u32>,
    /// Index of the current token.
    pos: usize,
    nodes: Vec<Node>,
    error: bool,
    /// Current nesting of guarded constructs (see [`MAX_DEPTH`]).
    depth: u32,
    /// Index of the `->` that ends the `case` label being parsed (or `NONE`): `case A -> x` must not read `A -> x` as
    /// a lambda.
    label_arrow: usize,
}

impl Parser<'_> {
    // ---------------------------------------------------------------- token helpers

    /// Token text at index `i`.
    fn text_at(&self, i: usize) -> &[u8] {
        let t = &self.toks[i];
        &self.code[t.start as usize..t.end as usize]
    }

    /// True if token `i` is exactly `s` (an operator, keyword or identifier).
    fn is_at(&self, i: usize, s: &str) -> bool {
        let t = &self.toks[i.min(self.toks.len() - 1)];
        (t.end - t.start) as usize == s.len() && &self.code[t.start as usize..t.end as usize] == s.as_bytes()
    }

    /// True if the current token is `s`.
    fn is(&self, s: &str) -> bool {
        self.is_at(self.pos, s)
    }

    /// True if the token `k` places ahead is `s`.
    fn peek_is(&self, k: usize, s: &str) -> bool {
        self.is_at(self.pos + k, s)
    }

    fn kind_at(&self, i: usize) -> Tok {
        self.toks[i.min(self.toks.len() - 1)].kind
    }

    fn kind(&self) -> Tok {
        self.kind_at(self.pos)
    }

    fn at_eof(&self) -> bool {
        self.kind() == Tok::Eof
    }

    /// Flags a syntax error at the current token.
    fn fail(&mut self) {
        self.error = true;
    }

    /// Moves to the next token (never past `Eof`).
    fn bump(&mut self) {
        if self.pos + 1 < self.toks.len() {
            self.pos += 1;
        }
    }

    /// Consumes the current token if it is `s`.
    fn eat(&mut self, s: &str) -> bool {
        if self.is(s) {
            self.bump();
            true
        } else {
            false
        }
    }

    /// Consumes `s` or flags an error (consuming nothing).
    fn expect(&mut self, s: &str) {
        if !self.eat(s) {
            self.fail();
        }
    }

    /// Consumes an identifier or flags an error. Returns the token index, or `NONE` if there was none.
    fn expect_ident(&mut self) -> u32 {
        if self.kind() == Tok::Ident {
            let i = self.pos as u32;
            self.bump();
            i
        } else {
            self.fail();
            NONE
        }
    }

    /// True if the tokens at `i` and `i + 1` touch (no whitespace or comment between): `non-sealed`, `>>`.
    fn adjacent(&self, i: usize) -> bool {
        i + 1 < self.toks.len() && self.toks[i].end == self.toks[i + 1].start
    }

    /// Error recovery: skips the current token, or the whole bracketed group if it opens one. Flags an error.
    fn skip(&mut self) {
        self.fail();
        let p = self.pair[self.pos];
        if p != NONE {
            self.pos = p as usize;
        }
        self.bump();
    }

    /// If the current token is `(`, the index of its `)` (else `NONE`), for [`Parser::resync`].
    fn closing_paren(&self) -> u32 {
        if self.is("(") { self.pair[self.pos] } else { NONE }
    }

    /// Error recovery inside a parenthesized list that hit an unexpected token: flags the error and jumps to the
    /// list's closing parenthesis `close` if it is known and ahead, or else skips one token if the current item
    /// (which started at `before`) consumed nothing.
    fn resync(&mut self, close: u32, before: usize) {
        self.fail();
        if close != NONE && close as usize > self.pos {
            self.pos = close as usize;
        } else if self.pos == before {
            self.skip();
        }
    }

    fn mark(&self) -> Mark {
        Mark { pos: self.pos, nodes: self.nodes.len(), error: self.error }
    }

    /// Undoes everything since `m`: position, nodes and error flag.
    fn reset(&mut self, m: Mark) {
        self.pos = m.pos;
        self.nodes.truncate(m.nodes);
        self.error = m.error;
    }

    /// Enters a nested construct. Past [`MAX_DEPTH`] it flags an error, skips one token or group (so callers always
    /// make progress) and returns false; the caller then skips the construct. Pair with [`Parser::leave`].
    fn enter(&mut self) -> bool {
        if self.depth >= MAX_DEPTH {
            self.skip();
            return false;
        }
        self.depth += 1;
        true
    }

    fn leave(&mut self) {
        self.depth -= 1;
    }

    // ---------------------------------------------------------------- node helpers

    /// Starts a node at token `first`; its span and subtree end are set by [`Parser::close`].
    fn open(&mut self, kind: NodeKind, first: usize) -> usize {
        self.nodes.push(Node { kind, sub: 0, flags: 0, first: first as u32, last: first as u32, end: 0, name: NONE, count: 0 });
        self.nodes.len() - 1
    }

    /// Ends node `i`: its last token is the last one consumed, and its subtree is everything pushed since.
    fn close(&mut self, i: usize) {
        let end = self.nodes.len() as u32;
        let last = (self.pos as u32).saturating_sub(1);
        let n = &mut self.nodes[i];
        n.last = last.max(n.first);
        n.end = end;
    }

    /// Adds a leaf node spanning tokens `first..=last`.
    fn leaf(&mut self, kind: NodeKind, first: usize, last: usize) {
        let end = self.nodes.len() as u32 + 1;
        self.nodes.push(Node { kind, sub: 0, flags: 0, first: first as u32, last: last as u32, end, name: NONE, count: 0 });
    }

    /// Adds a decision point at the current token and consumes it.
    fn decision(&mut self) {
        self.leaf(NodeKind::Decision, self.pos, self.pos);
        self.bump();
    }

    // ---------------------------------------------------------------- compilation unit

    /// `file = { package | import | module | typeDecl | ";" | statement | method }`.
    ///
    /// Statements and methods are accepted at the top level (they become
    /// [`NodeKind::Other`]); declarations may come in any order.
    fn compilation_unit(&mut self) {
        let root = self.open(NodeKind::CompilationUnit, 0);
        while !self.at_eof() {
            let before = self.pos;
            self.top_level_item();
            if self.pos == before {
                self.skip();
            }
        }
        self.close(root);
        self.nodes[root].last = self.pos as u32; // the Eof token
    }

    fn top_level_item(&mut self) {
        let start = self.pos;
        if self.eat(";") {
            return;
        }
        if self.is("import") {
            self.import();
            return;
        }
        // Annotations may start a package declaration (package-info.java), a module or a type declaration, so open
        // a node first and decide its kind after the modifiers.
        let node = self.open(NodeKind::Other, start);
        let flags = self.modifiers();
        if self.is("package") {
            self.nodes[node].kind = NodeKind::Package;
            self.bump();
            self.name();
            self.expect(";");
        } else if self.is_module_start() {
            self.nodes[node].kind = NodeKind::Module;
            self.module();
        } else if self.is_type_decl_start() {
            self.type_decl(node, flags, true);
        } else if self.pos > start || self.looks_like_method() {
            // A method outside any class, or modifiers followed by a variable declaration: parsed like a member, but
            // the node stays `Other` (it belongs to no type).
            self.member_rest(node, false, Body::Class, false);
        } else {
            self.statement();
        }
        self.close(node);
    }

    /// `import [static] a.b.C [.*] ;`
    fn import(&mut self) {
        let node = self.open(NodeKind::Import, self.pos);
        self.bump();
        if self.eat("static") {
            self.nodes[node].flags |= IMPORT_STATIC;
        }
        self.name();
        if self.is(".") && self.peek_is(1, "*") {
            self.bump();
            self.bump();
            self.nodes[node].flags |= IMPORT_ASTERISK;
        }
        self.expect(";");
        self.close(node);
    }

    /// A dotted name `a.b.c` as a [`NodeKind::Name`] node. Stops before a `.` not followed by an identifier.
    fn name(&mut self) {
        if self.kind() != Tok::Ident {
            self.fail();
            return;
        }
        let node = self.open(NodeKind::Name, self.pos);
        self.bump();
        while self.is(".") && self.kind_at(self.pos + 1) == Tok::Ident {
            self.bump();
            self.bump();
        }
        self.close(node);
    }

    /// `[open] module` followed by a name.
    fn is_module_start(&self) -> bool {
        let k = if self.is("open") { 1 } else { 0 };
        self.peek_is(k, "module") && self.kind_at(self.pos + k + 1) == Tok::Ident
    }

    /// `[open] module a.b { directives }`: the directives (`requires`, `exports`, …) only name modules and packages,
    /// so the body is skipped as a bracketed group.
    fn module(&mut self) {
        self.eat("open");
        self.bump(); // module
        self.name();
        if self.is("{") {
            let p = self.pair[self.pos];
            if p == NONE {
                self.skip();
            } else {
                self.pos = p as usize;
                self.bump();
            }
        } else {
            self.fail();
        }
    }

    /// At the top level: `Type name (` (a method outside any class, which is accepted).
    fn looks_like_method(&mut self) -> bool {
        let m = self.mark();
        let ok = self.type_() && self.kind() == Tok::Ident && self.peek_is(1, "(");
        self.reset(m);
        ok
    }

    // ---------------------------------------------------------------- declarations

    /// Modifiers: annotations and modifier keywords, including the contextual `sealed` and `non-sealed`. Returns
    /// [`TYPE_ABSTRACT`] if `abstract` was among them.
    fn modifiers(&mut self) -> u8 {
        let mut flags = 0;
        loop {
            if self.is("@") && !self.peek_is(1, "interface") {
                self.annotation();
            } else if self.kind() == Tok::Keyword
                && matches!(
                    self.text_at(self.pos),
                    b"public" | b"protected" | b"private" | b"abstract" | b"static" | b"final" | b"strictfp"
                        | b"default" | b"synchronized" | b"native" | b"transient" | b"volatile"
                )
            {
                if self.is("abstract") {
                    flags |= TYPE_ABSTRACT;
                }
                self.bump();
            } else if self.is("sealed") && self.continues_declaration(self.pos + 1) {
                self.bump();
            } else if self.is("non")
                && self.peek_is(1, "-")
                && self.peek_is(2, "sealed")
                && self.adjacent(self.pos)
                && self.adjacent(self.pos + 1)
            {
                self.bump();
                self.bump();
                self.bump();
            } else {
                return flags;
            }
        }
    }

    /// True if token `i` can follow a modifier: another modifier, an annotation or a type keyword. Used to tell
    /// the modifier `sealed` from a type or variable named `sealed`.
    fn continues_declaration(&self, i: usize) -> bool {
        match self.kind_at(i) {
            Tok::Keyword => matches!(
                self.text_at(i),
                b"public" | b"protected" | b"private" | b"abstract" | b"static" | b"final" | b"strictfp" | b"class"
                    | b"interface"
            ),
            Tok::Op => self.is_at(i, "@"),
            Tok::Ident => self.is_at(i, "sealed") || self.is_at(i, "non") || self.is_at(i, "record"),
            _ => false,
        }
    }

    /// After modifiers: `class`, `interface`, `enum`, `@interface`, or `record Name (` / `record Name <`.
    fn is_type_decl_start(&self) -> bool {
        self.is("class")
            || self.is("interface")
            || self.is("enum")
            || (self.is("@") && self.peek_is(1, "interface"))
            || (self.is("record")
                && self.kind_at(self.pos + 1) == Tok::Ident
                && (self.peek_is(2, "(") || self.peek_is(2, "<")))
    }

    /// A type declaration from its keyword on; the modifiers are already parsed into `node`, which becomes the
    /// [`NodeKind::TypeDecl`] (if `emit`; otherwise, for local types, `node` is whatever encloses it).
    ///
    /// Headers: `class N [<T>] [extends T] [implements Ts] [permits Ts]`, `interface N [<T>] [extends Ts]
    /// [permits Ts]`, `enum N [implements Ts]`, `record N [<T>] (components) [implements Ts]`, `@interface N`. The
    /// clauses are accepted in any order.
    fn type_decl(&mut self, node: usize, flags: u8, emit: bool) {
        let sub = if self.is("interface") {
            TYPE_INTERFACE
        } else if self.is("enum") {
            TYPE_ENUM
        } else if self.is("record") {
            TYPE_RECORD
        } else if self.is("@") {
            self.bump(); // the `interface` follows
            TYPE_ANNOTATION
        } else {
            TYPE_CLASS
        };
        self.bump();
        let name = self.expect_ident();
        if emit {
            let n = &mut self.nodes[node];
            n.kind = NodeKind::TypeDecl;
            n.sub = sub;
            n.flags = flags;
            n.name = name;
        }
        loop {
            if self.is("<") {
                self.type_parameters();
            } else if sub == TYPE_RECORD && self.is("(") {
                let (formal, _) = self.formal_parameters();
                if emit {
                    self.nodes[node].count = formal;
                }
            } else if self.eat("extends") || self.eat("implements") || self.eat("permits") {
                self.type_list();
            } else {
                break;
            }
        }
        if !self.is("{") {
            self.fail();
            return;
        }
        match sub {
            TYPE_ENUM => self.enum_body(emit),
            TYPE_ANNOTATION => self.class_body(emit, Body::Annotation),
            _ => self.class_body(emit, Body::Class),
        }
    }

    /// `Type {, Type}` (after `extends`, `implements`, `permits`, `throws`).
    fn type_list(&mut self) {
        loop {
            if !self.type_() {
                self.fail();
                return;
            }
            if !self.eat(",") {
                return;
            }
        }
    }

    /// `< T [extends A & B], … >`. Each parameter name is reported as a [`NodeKind::TypeName`].
    fn type_parameters(&mut self) {
        self.bump(); // <
        loop {
            self.annotations();
            if self.kind() == Tok::Ident {
                self.leaf(NodeKind::TypeName, self.pos, self.pos);
                self.bump();
            } else {
                self.fail();
                break;
            }
            if self.eat("extends") {
                loop {
                    if !self.type_() {
                        self.fail();
                        break;
                    }
                    if !self.eat("&") {
                        break;
                    }
                }
            }
            if !self.eat(",") {
                break;
            }
        }
        self.expect(">");
    }

    /// `{ members }`. With `emit`, members become nodes (fields, methods, nested types); without (anonymous and
    /// local classes, enum constant bodies), only their references and decision points are recorded.
    fn class_body(&mut self, emit: bool, body: Body) {
        if !self.enter() {
            return;
        }
        self.expect("{");
        while !self.is("}") && !self.at_eof() {
            let before = self.pos;
            self.member(emit, body);
            if self.pos == before {
                self.skip();
            }
        }
        self.expect("}");
        self.leave();
    }

    /// `{ [constant {, constant}] [,] [; members] }`; a constant is `annotations Name [(args)] [classBody]`.
    fn enum_body(&mut self, emit: bool) {
        if !self.enter() {
            return;
        }
        self.expect("{");
        while self.kind() == Tok::Ident || (self.is("@") && !self.peek_is(1, "interface")) {
            let node = if emit { Some(self.open(NodeKind::EnumConstant, self.pos)) } else { None };
            self.modifiers();
            self.expect_ident();
            if self.is("(") {
                self.arguments();
            }
            if self.is("{") {
                self.class_body(false, Body::Class);
            }
            if let Some(node) = node {
                self.close(node);
            }
            if !self.eat(",") {
                break;
            }
        }
        if !self.eat(";") && !self.is("}") {
            self.fail();
        }
        while !self.is("}") && !self.at_eof() {
            let before = self.pos;
            self.member(emit, Body::Class);
            if self.pos == before {
                self.skip();
            }
        }
        self.expect("}");
        self.leave();
    }

    /// One member of a class body (see [`Parser::class_body`]).
    fn member(&mut self, emit: bool, body: Body) {
        let start = self.pos;
        if self.eat(";") {
            return;
        }
        let node = if emit { self.open(NodeKind::Other, start) } else { NONE as usize };
        if self.is("{") {
            self.block(); // instance initializer
        } else if self.is("static") && self.peek_is(1, "{") {
            self.bump();
            self.block(); // static initializer
        } else {
            let flags = self.modifiers();
            if self.is_type_decl_start() {
                self.type_decl(node, flags, emit);
            } else {
                self.member_rest(node, emit, body, true);
            }
        }
        if emit {
            self.close(node);
        }
    }

    /// The rest of a member after its modifiers: a constructor, compact constructor, method, annotation element or
    /// field. Fills in `node` (when `emit`) as a [`NodeKind::Method`] or [`NodeKind::Field`]; anything else stays
    /// [`NodeKind::Other`]. `in_class` is false at the top level (methods there are not members of any type).
    fn member_rest(&mut self, node: usize, emit: bool, body: Body, in_class: bool) {
        if self.is("<") {
            // Generic method or constructor; annotations may follow the type parameters.
            self.type_parameters();
            self.annotations();
        }
        if self.kind() == Tok::Ident && self.peek_is(1, "(") {
            // Constructor: `Name(params) [throws Ts] { body }`.
            let name = self.pos as u32;
            self.bump();
            let (formal, spread) = self.formal_parameters();
            if self.eat("throws") {
                self.type_list();
            }
            self.method_body();
            if emit && in_class {
                let n = &mut self.nodes[node];
                n.kind = NodeKind::Method;
                n.sub = METHOD_CONSTRUCTOR;
                n.name = name;
                n.count = formal + spread;
            }
            return;
        }
        if self.kind() == Tok::Ident && self.peek_is(1, "{") {
            // Compact canonical constructor of a record: `Name { body }`.
            self.bump();
            self.block();
            return;
        }
        if !self.type_() {
            self.fail();
            return;
        }
        if self.kind() != Tok::Ident {
            self.fail();
            return;
        }
        let name = self.pos as u32;
        if self.peek_is(1, "(") {
            self.bump();
            if body == Body::Annotation && self.peek_is(1, ")") {
                // Annotation type element: `Type name() [dims] [default value];`.
                self.bump();
                self.bump();
                self.dims();
                if self.eat("default") {
                    self.element_value();
                }
                self.expect(";");
                return;
            }
            let (formal, spread) = self.formal_parameters();
            self.dims();
            if self.eat("throws") {
                self.type_list();
            }
            if self.eat("default") {
                self.element_value(); // only valid in annotation types, but harmless
                self.expect(";");
            } else {
                self.method_body();
            }
            if emit && in_class {
                let n = &mut self.nodes[node];
                n.kind = NodeKind::Method;
                n.name = name;
                n.count = formal + spread;
            }
            return;
        }
        // Field: `Type a [= x], b[] ;`.
        let count = self.variable_declarators();
        self.expect(";");
        if emit && in_class {
            let n = &mut self.nodes[node];
            n.kind = NodeKind::Field;
            n.count = count;
        }
    }

    /// A method body: a block, or `;` for abstract and interface methods.
    fn method_body(&mut self) {
        if !self.eat(";") {
            if self.is("{") {
                self.block();
            } else {
                self.fail();
            }
        }
    }

    /// `name [dims] [= init] {, …}`. Returns the number of declarators.
    fn variable_declarators(&mut self) -> u32 {
        let mut count = 0;
        loop {
            if self.expect_ident() == NONE {
                return count;
            }
            count += 1;
            self.dims();
            if self.eat("=") {
                self.variable_initializer();
            }
            if !self.eat(",") {
                return count;
            }
        }
    }

    /// An expression or an array initializer `{ a, b, }`.
    fn variable_initializer(&mut self) {
        if !self.is("{") {
            self.expression();
            return;
        }
        if !self.enter() {
            return;
        }
        self.bump();
        while !self.is("}") && !self.at_eof() {
            let before = self.pos;
            self.variable_initializer();
            if !self.eat(",") && !self.is("}") {
                self.fail();
                if self.pos == before {
                    self.skip();
                }
            }
        }
        self.expect("}");
        self.leave();
    }

    /// `( [receiver ,] param {, param} )` where a param is `modifiers Type [...] name [dims]` and a receiver
    /// parameter is `Type [Outer.]this`. Returns (ordinary parameters, varargs parameters); the receiver is not
    /// counted.
    fn formal_parameters(&mut self) -> (u32, u32) {
        let mut formal = 0;
        let mut spread = 0;
        if !self.enter() {
            return (0, 0);
        }
        let close = self.closing_paren();
        self.expect("(");
        while !self.is(")") && !self.at_eof() {
            let before = self.pos;
            self.modifiers();
            let modified = self.pos > before;
            if !self.type_() {
                self.fail();
            } else if self.eat("...") {
                self.annotations();
                self.expect_ident();
                self.dims();
                spread += 1;
            } else if self.eat("this") {
                // Receiver parameter `Foo this`, not counted. One with modifiers
                // (`@A Foo this`, `final Foo this`) is read as an ordinary parameter named `this`, so that one counts.
                if modified {
                    formal += 1;
                }
            } else if self.kind() == Tok::Ident && self.peek_is(1, ".") {
                // receiver parameter of an inner class constructor: `Outer Outer.this`
                while self.kind() == Tok::Ident && self.peek_is(1, ".") {
                    self.bump();
                    self.bump();
                }
                self.expect("this");
            } else {
                self.expect_ident();
                self.dims();
                formal += 1;
            }
            if !self.eat(",") {
                if !self.is(")") {
                    self.resync(close, before);
                }
                break;
            }
        }
        self.expect(")");
        self.leave();
        (formal, spread)
    }

    /// `{ [annotations] [] }`, as after a type or a declarator name.
    fn dims(&mut self) {
        loop {
            let m = self.mark();
            self.annotations();
            if self.is("[") && self.peek_is(1, "]") {
                self.bump();
                self.bump();
            } else {
                self.reset(m);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- annotations

    /// Zero or more annotations (not `@interface`).
    fn annotations(&mut self) {
        while self.is("@") && !self.peek_is(1, "interface") {
            self.annotation();
        }
    }

    /// `@Name [( [value | key = value {, key = value}] )]`. Reports the name as a [`NodeKind::AnnotationName`].
    fn annotation(&mut self) {
        self.bump(); // @
        let first = self.pos;
        if self.expect_ident() == NONE {
            return;
        }
        while self.is(".") && self.kind_at(self.pos + 1) == Tok::Ident {
            self.bump();
            self.bump();
        }
        self.leaf(NodeKind::AnnotationName, first, self.pos - 1);
        if self.is("(") {
            if !self.enter() {
                return;
            }
            let close = self.closing_paren();
            self.bump();
            while !self.is(")") && !self.at_eof() {
                let before = self.pos;
                if self.kind() == Tok::Ident && self.peek_is(1, "=") {
                    self.bump();
                    self.bump();
                }
                self.element_value();
                if !self.eat(",") {
                    if !self.is(")") {
                        self.resync(close, before);
                    }
                    break;
                }
            }
            self.expect(")");
            self.leave();
        }
    }

    /// An annotation element value: an annotation, `{ values }` or an expression.
    fn element_value(&mut self) {
        if self.is("@") {
            self.annotation();
        } else if self.is("{") {
            if !self.enter() {
                return;
            }
            self.bump();
            while !self.is("}") && !self.at_eof() {
                let before = self.pos;
                self.element_value();
                if !self.eat(",") && !self.is("}") {
                    self.fail();
                    if self.pos == before {
                        self.skip();
                    }
                }
            }
            self.expect("}");
            self.leave();
        } else {
            self.expression();
        }
    }

    // ---------------------------------------------------------------- types

    /// True if token `i` is a primitive type keyword or `void` (`void` is accepted wherever a type is).
    fn is_primitive_at(&self, i: usize) -> bool {
        self.kind_at(i) == Tok::Keyword
            && matches!(
                self.text_at(i),
                b"byte" | b"short" | b"int" | b"long" | b"char" | b"float" | b"double" | b"boolean" | b"void"
            )
    }

    /// Parses a type if one starts here: `annotations (primitive | classType) {annotations []}`. All or nothing:
    /// on failure nothing is consumed or reported and it returns false.
    ///
    /// A class type `A<X>.B<Y>` is reported as follows: a single name `A` as [`NodeKind::TypeName`],
    /// a qualified one as one [`NodeKind::ScopedTypeName`] covering everything up to the last name (type arguments
    /// and annotations of the leading parts included, and not reported on their own), plus whatever the last part's
    /// type arguments contain.
    fn type_(&mut self) -> bool {
        if !self.enter() {
            return false;
        }
        let ok = self.type_inner();
        self.leave();
        ok
    }

    fn type_inner(&mut self) -> bool {
        let m = self.mark();
        self.annotations();
        if self.is_primitive_at(self.pos) {
            self.bump();
        } else if self.kind() == Tok::Ident {
            let first = self.pos;
            let mut last = self.pos;
            let mark = self.nodes.len();
            self.bump();
            if self.is("<") && !self.type_arguments() {
                self.reset(m);
                return false;
            }
            let mut scoped = false;
            while self.is(".") {
                let dot = self.mark();
                self.bump();
                self.annotations();
                if self.kind() != Tok::Ident {
                    self.reset(dot);
                    break;
                }
                last = self.pos;
                self.bump();
                // The parts before this name are now inside the scoped name: drop what they reported.
                self.nodes.truncate(mark);
                scoped = true;
                if self.is("<") && !self.type_arguments() {
                    self.reset(m);
                    return false;
                }
            }
            if scoped {
                self.leaf(NodeKind::ScopedTypeName, first, last);
            } else {
                self.leaf(NodeKind::TypeName, first, first);
            }
        } else {
            self.reset(m);
            return false;
        }
        self.dims();
        true
    }

    /// `< [typeOrWildcard {, typeOrWildcard}] >` (empty for the diamond `<>`). All or nothing, like [`Parser::type_`].
    fn type_arguments(&mut self) -> bool {
        let m = self.mark();
        self.bump(); // <
        if self.eat(">") {
            return true;
        }
        loop {
            let w = self.mark();
            self.annotations();
            if self.eat("?") {
                if self.eat("extends") || self.eat("super") {
                    if !self.type_() {
                        self.reset(m);
                        return false;
                    }
                }
            } else {
                self.reset(w);
                if !self.type_() {
                    self.reset(m);
                    return false;
                }
            }
            if !self.eat(",") {
                break;
            }
        }
        if !self.eat(">") {
            self.reset(m);
            return false;
        }
        true
    }

    // ---------------------------------------------------------------- statements

    /// `{ statements }`
    fn block(&mut self) {
        if !self.enter() {
            return;
        }
        self.expect("{");
        self.statements_until_brace();
        self.expect("}");
        self.leave();
    }

    /// Statements up to (not including) the closing `}`.
    fn statements_until_brace(&mut self) {
        while !self.is("}") && !self.at_eof() {
            let before = self.pos;
            self.statement();
            if self.pos == before {
                self.skip();
            }
        }
    }

    fn statement(&mut self) {
        if !self.enter() {
            return;
        }
        self.statement_inner();
        self.leave();
    }

    fn statement_inner(&mut self) {
        match self.kind() {
            Tok::Op => {
                if self.is("{") {
                    self.block();
                } else if self.eat(";") {
                } else if self.is("@") {
                    self.local_declaration();
                } else if self.is("<") {
                    // `<T>this(…);` explicit constructor invocation with type arguments
                    if !self.type_arguments() {
                        self.fail();
                    }
                    self.expression_statement();
                } else {
                    self.expression_statement();
                }
            }
            Tok::Keyword => match self.text_at(self.pos) {
                b"if" => self.if_statement(),
                b"while" => {
                    self.decision();
                    self.parenthesized();
                    self.statement();
                }
                b"do" => {
                    self.decision();
                    self.statement();
                    self.expect("while");
                    self.parenthesized();
                    self.expect(";");
                }
                b"for" => self.for_statement(),
                b"try" => self.try_statement(),
                b"switch" => self.switch(),
                b"return" => {
                    self.bump();
                    if !self.is(";") {
                        self.expression();
                    }
                    self.expect(";");
                }
                b"throw" => {
                    self.bump();
                    self.expression();
                    self.expect(";");
                }
                b"break" | b"continue" => {
                    self.bump();
                    if self.kind() == Tok::Ident {
                        self.bump();
                    }
                    self.expect(";");
                }
                b"assert" => {
                    self.bump();
                    self.expression();
                    if self.eat(":") {
                        self.expression();
                    }
                    self.expect(";");
                }
                b"synchronized" if self.peek_is(1, "(") => {
                    self.bump();
                    self.parenthesized();
                    self.block();
                }
                b"class" | b"interface" | b"enum" | b"abstract" | b"final" | b"static" | b"strictfp" | b"public"
                | b"protected" | b"private" | b"synchronized" | b"native" | b"transient" | b"volatile"
                | b"default" => self.local_declaration(),
                _ if self.is_primitive_at(self.pos) && !self.peek_is(1, ".") && !self.peek_is(1, "::") => {
                    self.local_variable()
                }
                _ => self.expression_statement(),
            },
            Tok::Ident => {
                if self.peek_is(1, ":") {
                    // labeled statement
                    self.bump();
                    self.bump();
                    self.statement();
                } else if self.is("yield") && self.is_yield() {
                    self.bump();
                    self.expression();
                    self.expect(";");
                } else if self.is_type_decl_start()
                    || ((self.is("sealed") || self.is("non")) && self.continues_declaration(self.pos + 1))
                {
                    self.local_declaration();
                } else if self.is_local_variable() {
                    self.local_variable();
                } else {
                    self.expression_statement();
                }
            }
            _ => self.expression_statement(),
        }
    }

    /// `yield` starts a yield statement unless it is used as a name (`yield = 1;`, `yield.x()`, `yield(…)` is
    /// still a statement).
    fn is_yield(&self) -> bool {
        let i = self.pos + 1;
        match self.kind_at(i) {
            Tok::Ident | Tok::Number | Tok::Char | Tok::Str => true,
            Tok::Keyword => !self.is_at(i, "instanceof"),
            Tok::Op => {
                self.is_at(i, "(") || self.is_at(i, "-") || self.is_at(i, "+") || self.is_at(i, "!") || self.is_at(i, "~")
                    || self.is_at(i, "++") || self.is_at(i, "--")
            }
            _ => false,
        }
    }

    /// At an identifier: `Type name` follows, so this is a local variable declaration.
    fn is_local_variable(&mut self) -> bool {
        let m = self.mark();
        let ok = self.type_() && self.kind() == Tok::Ident;
        self.reset(m);
        ok
    }

    /// A statement that starts with modifiers: a local class/interface/enum/record or a local variable.
    fn local_declaration(&mut self) {
        let flags = self.modifiers();
        if self.is_type_decl_start() {
            self.type_decl(NONE as usize, flags, false);
        } else {
            self.local_variable();
        }
    }

    /// `Type declarators ;` (modifiers already parsed).
    fn local_variable(&mut self) {
        if !self.type_() {
            self.fail();
            return;
        }
        self.variable_declarators();
        self.expect(";");
    }

    /// `expression ;`
    fn expression_statement(&mut self) {
        self.expression();
        self.expect(";");
    }

    /// `( expression )`
    fn parenthesized(&mut self) {
        self.expect("(");
        self.expression();
        self.expect(")");
    }

    /// `if (c) s [else if (c) s]… [else s]`. The `else if` chain is a loop, so long chains do not nest.
    fn if_statement(&mut self) {
        loop {
            self.decision(); // if
            self.parenthesized();
            self.statement();
            if !self.eat("else") {
                return;
            }
            if !self.is("if") {
                self.statement();
                return;
            }
        }
    }

    /// `for (init; cond; update) s` or `for (modifiers Type name : expr) s`.
    fn for_statement(&mut self) {
        self.decision(); // for
        self.expect("(");
        // A declaration first (`modifiers Type name`), which an enhanced for continues with `:`.
        let m = self.mark();
        self.modifiers();
        if self.type_() && self.kind() == Tok::Ident {
            self.bump();
            self.dims();
            if self.eat(":") {
                self.expression();
                self.expect(")");
                self.statement();
                return;
            }
            // Classic for with a declaration: finish the first declarator, then any others.
            if self.eat("=") {
                self.variable_initializer();
            }
            if self.eat(",") {
                self.variable_declarators();
            }
        } else {
            self.reset(m);
            self.expression_list(";");
        }
        self.expect(";");
        if !self.is(";") {
            self.expression();
        }
        self.expect(";");
        self.expression_list(")");
        self.expect(")");
        self.statement();
    }

    /// `[expression {, expression}]` up to (not including) `end`.
    fn expression_list(&mut self, end: &str) {
        if self.is(end) {
            return;
        }
        loop {
            self.expression();
            if !self.eat(",") {
                return;
            }
        }
    }

    /// `try [(resources)] block {catch (T | U e) block} [finally block]`.
    fn try_statement(&mut self) {
        self.bump(); // try
        if self.is("(") {
            self.bump();
            while !self.is(")") && !self.at_eof() {
                let before = self.pos;
                // `modifiers Type name = expr` or an expression naming an existing resource.
                let m = self.mark();
                self.modifiers();
                if self.type_() && self.kind() == Tok::Ident {
                    self.bump();
                    self.expect("=");
                    self.expression();
                } else {
                    self.reset(m);
                    self.expression();
                }
                if !self.eat(";") && !self.is(")") {
                    self.fail();
                    if self.pos == before {
                        self.skip();
                    }
                    break;
                }
            }
            self.expect(")");
        }
        self.block();
        while self.is("catch") {
            self.decision();
            self.expect("(");
            self.modifiers();
            loop {
                if !self.type_() {
                    self.fail();
                    break;
                }
                if !self.eat("|") {
                    break;
                }
            }
            self.expect_ident();
            self.expect(")");
            self.block();
        }
        if self.eat("finally") {
            self.block();
        }
    }

    /// `switch (x) { … }`, as a statement or an expression. The body is either statement groups
    /// (`case A: case B: statements`) or rules (`case A, B -> expression; | block | throw`).
    fn switch(&mut self) {
        self.bump(); // switch
        self.parenthesized();
        if !self.is("{") {
            self.fail();
            return;
        }
        if !self.enter() {
            return;
        }
        self.bump();
        while !self.is("}") && !self.at_eof() {
            let before = self.pos;
            if self.is("case") || self.is("default") {
                let arrow = self.switch_label();
                if arrow {
                    if self.is("{") {
                        self.block();
                    } else if self.is("throw") {
                        self.statement();
                    } else {
                        self.expression_statement();
                    }
                } else {
                    while !self.is("case") && !self.is("default") && !self.is("}") && !self.at_eof() {
                        let b = self.pos;
                        self.statement();
                        if self.pos == b {
                            self.skip();
                        }
                    }
                }
            }
            if self.pos == before {
                self.skip();
            }
        }
        self.expect("}");
        self.leave();
    }

    /// `case p {, p} [when guard]` or `default`, followed by `:` or `->`. Returns true for `->`. Each `case` is
    /// a decision point; `default` is not.
    fn switch_label(&mut self) -> bool {
        if self.eat("default") {
            if self.eat("->") {
                return true;
            }
            self.expect(":");
            return false;
        }
        self.decision(); // case
        // Find the `->` that ends this label (the first `->` or `:` at bracket depth 0), so the label's own
        // expressions do not take `A -> x` for a lambda.
        let saved = self.label_arrow;
        self.label_arrow = self.label_end();
        loop {
            let before = self.pos;
            self.case_item();
            if self.pos == before || !self.eat(",") {
                break;
            }
        }
        if self.is("when") {
            self.bump();
            self.expression();
        }
        self.label_arrow = saved;
        if self.eat("->") {
            return true;
        }
        self.expect(":");
        false
    }

    /// Index of the `->` ending the current case label, or `NONE` if it ends with `:` (or is malformed).
    fn label_end(&self) -> usize {
        let mut i = self.pos;
        let mut ternary = 0;
        while i < self.toks.len() {
            let t = self.toks[i];
            if t.kind == Tok::Eof {
                break;
            }
            if t.kind == Tok::Op {
                let p = self.pair[i];
                if p != NONE {
                    i = p as usize + 1;
                    continue;
                }
                if self.is_at(i, "->") {
                    return i;
                }
                if self.is_at(i, "?") {
                    ternary += 1;
                } else if self.is_at(i, ":") {
                    if ternary == 0 {
                        break;
                    }
                    ternary -= 1;
                } else if self.is_at(i, ";") || self.is_at(i, "}") || self.is_at(i, ")") || self.is_at(i, "{") {
                    break;
                }
            }
            i += 1;
        }
        NONE as usize
    }

    /// One item of a case label: a record pattern, a type pattern (`String s`), an expression (a constant or an
    /// enum name), or `default` (in `case null, default`).
    fn case_item(&mut self) {
        // `case null, default ->` (Java 21)
        if self.eat("default") {
            return;
        }
        if self.try_record_pattern(true) {
            return;
        }
        let m = self.mark();
        if self.type_() && self.kind() == Tok::Ident && self.ends_pattern(self.pos + 1) {
            self.bump();
            return;
        }
        self.reset(m);
        self.expression();
    }

    /// True if token `i` can follow a pattern in a case label.
    fn ends_pattern(&self, i: usize) -> bool {
        self.is_at(i, "->") || self.is_at(i, ":") || self.is_at(i, ",") || self.is_at(i, "when")
    }

    /// Tries a record pattern `Name(components)` or `Name<T>(components)`; with `in_label`, it must also be
    /// followed by what ends a case label item. All or nothing. The plain name is not a type reference
    /// (it is a plain identifier), a generic one is.
    fn try_record_pattern(&mut self, in_label: bool) -> bool {
        if self.kind() != Tok::Ident || !(self.peek_is(1, "(") || self.peek_is(1, "<")) {
            return false;
        }
        let m = self.mark();
        let ok = self.record_pattern() && (!in_label || self.ends_pattern(self.pos));
        if !ok {
            self.reset(m);
        }
        ok
    }

    /// `Name[<T>] ( [component {, component}] )` where a component is `_`, a record pattern or `Type name`.
    fn record_pattern(&mut self) -> bool {
        if !self.enter() {
            return false;
        }
        let ok = self.record_pattern_inner();
        self.leave();
        ok
    }

    fn record_pattern_inner(&mut self) -> bool {
        if self.peek_is(1, "<") {
            if !self.type_() {
                return false;
            }
        } else {
            self.bump();
        }
        if !self.eat("(") {
            return false;
        }
        while !self.is(")") {
            if self.is("_") && (self.peek_is(1, ",") || self.peek_is(1, ")")) {
                self.bump();
            } else if self.try_record_pattern(false) {
            } else if !(self.type_() && self.kind() == Tok::Ident) {
                return false;
            } else {
                self.bump();
            }
            if !self.eat(",") {
                break;
            }
        }
        self.eat(")")
    }

    // ---------------------------------------------------------------- expressions

    /// `operand { op operand | instanceof pattern | ? expression : }`, with operator precedence ignored (see the
    /// module documentation). `&&`, `||` and the `?` of a conditional are decision points.
    fn expression(&mut self) {
        if !self.enter() {
            return;
        }
        loop {
            self.operand();
            // `instanceof` and postfix-like parts that may follow an operand
            while self.is("instanceof") {
                self.bump();
                self.eat("final");
                if !self.try_record_pattern(false) {
                    if !self.type_() {
                        self.fail();
                    } else if self.kind() == Tok::Ident {
                        self.bump(); // pattern variable
                    }
                }
            }
            if self.is("?") {
                self.decision();
                self.expression();
                self.expect(":");
                continue;
            }
            if self.binary_operator() {
                continue;
            }
            break;
        }
        self.leave();
    }

    /// Consumes a binary or assignment operator if one is here (adjacent `>`s count as one shift operator).
    fn binary_operator(&mut self) -> bool {
        if self.kind() != Tok::Op {
            return false;
        }
        let op = self.text_at(self.pos);
        let binary = matches!(
            op,
            b"=" | b"+=" | b"-=" | b"*=" | b"/=" | b"%=" | b"&=" | b"|=" | b"^=" | b"<<=" | b">=" | b">" | b"<" | b"<="
                | b"==" | b"!=" | b"+" | b"-" | b"*" | b"/" | b"%" | b"&" | b"|" | b"^" | b"<<"
        );
        if op == b"&&" || op == b"||" {
            self.decision();
            return true;
        }
        if !binary {
            return false;
        }
        let shift = op == b">";
        self.bump();
        if shift {
            // `>>`, `>>>`, `>>=`, `>>>=` arrive as separate `>` / `>=` tokens
            while (self.is(">") || self.is(">=")) && self.toks[self.pos - 1].end == self.toks[self.pos].start {
                let more = self.is(">");
                self.bump();
                if !more {
                    break;
                }
            }
        }
        true
    }

    /// True if token `i` can start an operand that is not `+`/`-`: what may follow `(Type)` in a cast.
    fn starts_operand(&self, i: usize) -> bool {
        match self.kind_at(i) {
            Tok::Ident | Tok::Number | Tok::Char | Tok::Str => true,
            Tok::Keyword => matches!(
                self.text_at(i),
                b"this" | b"super" | b"new" | b"true" | b"false" | b"null" | b"switch" | b"byte" | b"short" | b"int"
                    | b"long" | b"char" | b"float" | b"double" | b"boolean" | b"void"
            ),
            Tok::Op => {
                self.is_at(i, "(") || self.is_at(i, "!") || self.is_at(i, "~")
                    || ((self.is_at(i, "++") || self.is_at(i, "--")) && self.starts_operand(i + 1))
            }
            _ => false,
        }
    }

    /// `{ prefixOp | cast } (lambda | primary {postfix})`.
    fn operand(&mut self) {
        loop {
            if self.kind() == Tok::Op
                && matches!(self.text_at(self.pos), b"+" | b"-" | b"!" | b"~" | b"++" | b"--")
            {
                self.bump();
                continue;
            }
            if self.is("(") && !self.lambda_ahead() && self.try_cast() {
                continue;
            }
            break;
        }
        if self.lambda_ahead() {
            self.lambda();
            return;
        }
        let bare = self.primary();
        self.postfix(bare);
    }

    /// Tries `( Type {& Type} )` followed by an operand. It reads `(Foo) x`, `(Foo) !x`,
    /// `(Foo) ++x` and `(int) -x` as casts but `(Foo) - x` as a parenthesized expression minus `x`, i.e. only a
    /// primitive type may be followed by `+`/`-`. All or nothing.
    fn try_cast(&mut self) -> bool {
        let m = self.mark();
        self.bump(); // (
        let primitive = self.is_primitive_at(self.pos) && self.peek_is(1, ")");
        if !self.type_() {
            self.reset(m);
            return false;
        }
        while self.eat("&") {
            if !self.type_() {
                self.reset(m);
                return false;
            }
        }
        if !self.eat(")") {
            self.reset(m);
            return false;
        }
        let next = self.pos;
        if self.starts_operand(next) || (primitive && (self.is("+") || self.is("-"))) {
            return true;
        }
        self.reset(m);
        false
    }

    /// True if a lambda starts here: `name ->` or `( … ) ->`. The `->` ending a case label does not count.
    fn lambda_ahead(&self) -> bool {
        if self.kind() == Tok::Ident {
            return self.peek_is(1, "->") && self.pos + 1 != self.label_arrow;
        }
        if self.is("(") {
            let p = self.pair[self.pos];
            return p != NONE && self.is_at(p as usize + 1, "->") && p as usize + 1 != self.label_arrow;
        }
        false
    }

    /// `params -> (expression | block)`, where params are a name, `(a, b)` or formal parameters `(Type a, var b)`.
    fn lambda(&mut self) {
        if self.kind() == Tok::Ident {
            self.bump();
        } else {
            // Inferred parameters `(a, b)` have no types; otherwise parse formal parameters (which report types).
            let close = self.pair[self.pos] as usize;
            let mut inferred = true;
            let mut i = self.pos + 1;
            while i < close {
                if !(self.kind_at(i) == Tok::Ident && (self.is_at(i + 1, ",") || i + 1 == close)) {
                    inferred = false;
                    break;
                }
                i += 2;
            }
            if inferred {
                self.pos = close;
                self.bump();
            } else {
                self.formal_parameters();
            }
        }
        self.expect("->");
        if self.is("{") {
            self.block();
        } else {
            self.expression();
        }
    }

    /// A primary expression. Returns the token of a lone identifier (the possible object of a member access), else
    /// `NONE`.
    fn primary(&mut self) -> usize {
        let none = NONE as usize;
        match self.kind() {
            Tok::Number | Tok::Char | Tok::Str => {
                self.bump();
                none
            }
            Tok::Ident => {
                if self.type_before_member_ref_or_class() {
                    return none;
                }
                let i = self.pos;
                self.bump();
                if self.is("(") {
                    self.arguments(); // unqualified method call
                    return none;
                }
                i
            }
            Tok::Keyword => match self.text_at(self.pos) {
                b"true" | b"false" | b"null" | b"this" | b"super" => {
                    self.bump();
                    none
                }
                b"new" => {
                    self.creation();
                    none
                }
                b"switch" => {
                    self.switch();
                    none
                }
                _ if self.is_primitive_at(self.pos) => {
                    // `int.class`, `int[].class`, `int[]::new`
                    self.type_();
                    none
                }
                _ => {
                    self.fail();
                    none
                }
            },
            Tok::Op => {
                if self.is("(") {
                    if !self.enter() {
                        return none;
                    }
                    self.bump();
                    self.expression();
                    self.expect(")");
                    self.leave();
                } else if self.is("@") {
                    // annotated type in an expression (e.g. before `::`); report and continue with the type
                    if !self.type_() {
                        self.skip();
                    }
                } else {
                    self.fail();
                }
                none
            }
            _ => {
                self.fail();
                none
            }
        }
    }

    /// At an identifier: if a dotted name is followed by type arguments or `[]` and then `::`, or by `[]`/nothing
    /// and then `.class`, parse it as a type (a generic or array type in a method reference,
    /// or the type of a class literal) and return true. Plain `Foo::bar` and `a.b::c` stay expressions.
    fn type_before_member_ref_or_class(&mut self) -> bool {
        // end of `a.b.c`
        let mut k = self.pos + 1;
        while self.is_at(k, ".") && self.kind_at(k + 1) == Tok::Ident {
            k += 2;
        }
        let class_literal = self.is_at(k, ".") && self.is_at(k + 1, "class");
        let array = self.is_at(k, "[") && self.is_at(k + 1, "]");
        let generic = self.is_at(k, "<");
        if !(class_literal || array || generic) {
            return false;
        }
        let m = self.mark();
        if self.type_() && (self.is("::") || (self.is(".") && self.peek_is(1, "class"))) {
            return true;
        }
        self.reset(m);
        false
    }

    /// `new [<T>] annotations Type (args) [classBody]` or `new Type dims [initializer]`.
    fn creation(&mut self) {
        self.bump(); // new
        if self.is("<") && !self.type_arguments() {
            self.fail();
        }
        self.annotations();
        // The created type. `type_` also takes empty dimensions (`new int[][] {…}`); sized ones (`new int[n]`)
        // are left for the loop below.
        if !self.type_() {
            self.fail();
            return;
        }
        if self.is("(") {
            self.arguments();
            if self.is("{") {
                self.class_body(false, Body::Class);
            }
            return;
        }
        // Array creation: `[expr]…[]…` or (already consumed by type_) `[]… {init}`.
        let mut sized = false;
        while self.is("[") {
            if self.peek_is(1, "]") {
                self.bump();
                self.bump();
                continue;
            }
            if !self.enter() {
                return;
            }
            self.bump();
            self.expression();
            self.expect("]");
            self.leave();
            sized = true;
        }
        if !sized {
            if self.is("{") {
                self.variable_initializer();
            } else {
                self.fail();
            }
        }
    }

    /// `( [expression {, expression}] )`
    fn arguments(&mut self) {
        if !self.enter() {
            return;
        }
        let close = self.closing_paren();
        self.expect("(");
        while !self.is(")") && !self.at_eof() {
            let before = self.pos;
            self.expression();
            if !self.eat(",") {
                if !self.is(")") {
                    self.resync(close, before);
                }
                break;
            }
        }
        self.expect(")");
        self.leave();
    }

    /// Member accesses, calls, indexing, method references and postfix `++`/`--` after a primary. `bare` is the
    /// token of the primary if it was a lone identifier: then the first `.member` makes it a
    /// [`NodeKind::MemberObject`] (except `Outer.this` and `outer.new Inner()`).
    fn postfix(&mut self, mut bare: usize) {
        let none = NONE as usize;
        loop {
            if self.is(".") {
                let next = self.pos + 1;
                if self.is_at(next, "this") || self.is_at(next, "class") {
                    self.bump();
                    self.bump();
                } else if self.is_at(next, "new") {
                    self.bump();
                    self.creation();
                } else if self.kind_at(next) == Tok::Str {
                    // string template `STR."…"`
                    self.bump();
                    self.bump();
                } else if self.is_at(next, "super") || self.is_at(next, "<") || self.kind_at(next) == Tok::Ident {
                    if bare != none {
                        self.leaf(NodeKind::MemberObject, bare, bare);
                    }
                    self.bump();
                    if self.eat("super") {
                        if self.is("::") {
                            bare = none;
                            continue;
                        }
                        if !self.eat(".") {
                            // `Outer.super(…)` explicit constructor invocation
                            bare = none;
                            continue;
                        }
                    }
                    if self.is("<") && !self.type_arguments() {
                        self.fail();
                    }
                    self.expect_ident();
                    if self.is("(") {
                        self.arguments();
                    }
                } else {
                    self.bump();
                    self.fail();
                }
                bare = none;
            } else if self.is("[") {
                if !self.enter() {
                    return;
                }
                self.bump();
                self.expression();
                self.expect("]");
                self.leave();
                bare = none;
            } else if self.is("(") {
                // call on `this`/`super` (`this(…)`), or on something that is not callable (an error, which is
                // reported; parse the arguments anyway)
                self.arguments();
                bare = none;
            } else if self.is("::") {
                self.bump();
                if self.is("<") && !self.type_arguments() {
                    self.fail();
                }
                if !self.eat("new") {
                    self.expect_ident();
                }
                bare = none;
            } else if self.is("++") || self.is("--") {
                self.bump();
                bare = none;
            } else {
                return;
            }
        }
    }
}


#[cfg(test)]
mod tests {
    //! Tricky syntax, with expected results taken from the previous parser (see the module comment), plus
    //! robustness: mutated input, deep nesting.
    use super::*;

    /// Texts of all nodes of `kind`, sorted.
    fn texts(code: &str, kind: NodeKind) -> Vec<String> {
        let t = parse(code);
        let mut v: Vec<String> =
            t.nodes.iter().filter(|n| n.kind == kind).map(|n| t.text(code, n.first, n.last).to_string()).collect();
        v.sort();
        v
    }

    /// `texts` of a statement inside a method body.
    fn in_method(stmt: &str, kind: NodeKind) -> Vec<String> {
        texts(&format!("class A {{ void f() {{ {stmt} }} }}"), kind)
    }

    fn ok(code: &str) -> bool {
        !parse(code).error
    }

    #[test]
    fn lexes_literals_comments_and_lines() {
        let code = "a /* x\n y */ \"s\\\"t\" 'c' '\\'' 0x1.8p-3 1e+5f .5 1_000L \"\"\"\n tb \"\"\" >>= ->\nb";
        let l = lex(code);
        let kinds: Vec<Tok> = l.tokens.iter().map(|t| t.kind).collect();
        use Tok::*;
        assert_eq!(kinds, vec![Ident, Str, Char, Char, Number, Number, Number, Number, Str, Op, Op, Op, Ident, Eof]);
        assert!(!l.error);
        assert_eq!(l.tokens[1].line, 2);
        assert_eq!(l.tokens[12].line, 4); // after the text block's line break
        // `>` stays single so that `List<List<X>>` closes twice
        let texts: Vec<&str> = l.tokens[9..12].iter().map(|t| &code[t.start as usize..t.end as usize]).collect();
        assert_eq!(texts, vec![">", ">=", "->"]);
    }

    #[test]
    fn lexer_errors() {
        assert!(lex("a # b").error);
        assert!(lex("\"open\nx").error);
        assert!(lex("/* open").error);
        assert!(lex("f(]").error);
        assert!(lex("int\u{a0}x;").error); // no-break space is not whitespace
        assert!(!lex("\u{feff}class A {}").error); // leading byte-order mark
        assert!(!lex("class \u{e9}t\u{e9} { String \u{2c7}x = \"\0\"; }").error); // a NUL inside a string is fine
    }

    #[test]
    fn simple_and_scoped_type_names() {
        assert_eq!(in_method("Map.Entry<K, V> e;", NodeKind::ScopedTypeName), vec!["Map.Entry"]);
        assert_eq!(in_method("Map.Entry<K, V> e;", NodeKind::TypeName), vec!["K", "V"]);
        // leading parts' type arguments and annotations are part of the scoped name, not reported on their own
        assert_eq!(in_method("Outer<String>.Inner<Integer> x;", NodeKind::ScopedTypeName), vec!["Outer<String>.Inner"]);
        assert_eq!(in_method("Outer<String>.Inner<Integer> x;", NodeKind::TypeName), vec!["Integer"]);
        assert_eq!(in_method("java.util.@Nn List<@Nn String> l;", NodeKind::AnnotationName), vec!["Nn"]);
        // `var` and type parameters are type names too
        assert_eq!(texts("class A<T extends B & C> { <U> void f() { var x = 1; } }", NodeKind::TypeName), vec!["B", "C", "T", "U", "var"]);
    }

    #[test]
    fn casts_are_recognised_by_what_follows() {
        assert_eq!(in_method("x = (Foo) y;", NodeKind::TypeName), vec!["Foo"]);
        assert_eq!(in_method("x = (Foo) !y + (Bar) ++y + (Baz) (y) + (Q) this;", NodeKind::TypeName), vec!["Bar", "Baz", "Foo", "Q"]);
        // a reference type followed by + or - is a parenthesized expression; a primitive type is still a cast
        assert!(in_method("x = (Foo) - y; x = (foo) + 1; x = (y)++;", NodeKind::TypeName).is_empty());
        assert!(ok("class A { void f() { x = (int) -y; x = (A & B) () -> 1; } }"));
        assert_eq!(in_method("o = (Comparable<String> & Serializable) () -> 1;", NodeKind::TypeName), vec!["Comparable", "Serializable", "String"]);
    }

    #[test]
    fn member_objects_class_literals_and_method_references() {
        assert_eq!(in_method("Util.run(); Foo.BAR.baz(); a.b.C.d(); Outer.this.x = 1; o.new Inner();", NodeKind::MemberObject), vec!["Foo", "Util", "a"]);
        assert_eq!(in_method("Outer.super.m(); Collections.<T>empty();", NodeKind::MemberObject), vec!["Collections", "Outer"]);
        // class literals are types: qualified ones are scoped names, not member accesses
        assert_eq!(in_method("c = a.b.C.class; d = Foo[].class; e = int.class;", NodeKind::ScopedTypeName), vec!["a.b.C"]);
        assert_eq!(in_method("c = a.b.C.class; d = Foo[].class;", NodeKind::MemberObject), Vec::<String>::new());
        // `Foo::bar` keeps `Foo` an expression; generic and array forms are types
        assert!(in_method("f = String::valueOf;", NodeKind::TypeName).is_empty());
        assert_eq!(in_method("f = List<String>::size; g = Foo[]::new; h = Foo.Bar::m;", NodeKind::TypeName), vec!["Foo", "List", "String"]);
        assert_eq!(in_method("h = Foo.Bar::m;", NodeKind::MemberObject), vec!["Foo"]);
    }

    #[test]
    fn decisions() {
        let count = |stmt: &str| in_method(stmt, NodeKind::Decision).len();
        assert_eq!(count("if (a && b || c) {} else if (d) {} else {}"), 4);
        assert_eq!(count("for (;;) {} for (var x : xs) {} while (a) {} do {} while (b);"), 4);
        assert_eq!(count("try {} catch (A | B e) {} catch (C e) {} finally {}"), 2);
        assert_eq!(count("x = a ? b : c ? d : e; List<?> l;"), 2);
        assert_eq!(count("switch (x) { case 1: case 2: break; default: }"), 2);
        assert_eq!(count("y = switch (o) { case Integer i when i > 0 && i < 9 -> 1; case null, default -> 2; };"), 3);
        // lambdas and anonymous classes count towards the enclosing method
        assert_eq!(count("r = () -> { if (a) {} }; o = new Object() { void g() { while (b) {} } };"), 2);
    }

    #[test]
    fn patterns_and_switch_rules() {
        let code = "class A { Object f(Object o) { return switch (o) {
            case Point(int x, var y) -> 1;
            case Box<String>(Pair(var a, var b)) -> 2;
            case String s -> { yield s; }
            case Color.RED -> 3;
            case FOO -> 4;
            default -> throw new IllegalStateException();
        }; } }";
        assert!(ok(code));
        // a record pattern's plain name is not a type reference, a generic one is
        assert_eq!(texts(code, NodeKind::TypeName), vec!["Box", "IllegalStateException", "Object", "Object", "String", "String", "var", "var", "var"]);
        assert_eq!(texts(code, NodeKind::MemberObject), vec!["Color"]);
        assert!(ok("class A { void f() { if (o instanceof Point(int a, int b) && o instanceof final List<?> l) {} } }"));
    }

    #[test]
    fn declarations_and_counts() {
        let code = "@Ann package p;
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
        let t = parse(code);
        assert!(!t.error);
        let kinds: Vec<NodeKind> = t.children(0).iter().map(|&c| t.nodes[c].kind).collect();
        assert_eq!(kinds, vec![NodeKind::Package, NodeKind::Import, NodeKind::Import, NodeKind::TypeDecl]);
        let a = *t.children(0).last().unwrap();
        assert_eq!(t.nodes[a].flags, TYPE_ABSTRACT);
        let members: Vec<(NodeKind, u32)> = t
            .children(a)
            .into_iter()
            .filter(|&c| !matches!(t.nodes[c].kind, NodeKind::TypeName | NodeKind::ScopedTypeName | NodeKind::AnnotationName))
            .map(|c| (t.nodes[c].kind, t.nodes[c].count))
            .collect();
        use NodeKind::*;
        // a receiver parameter with modifiers is counted as a parameter
        assert_eq!(members, vec![(Field, 3), (Method, 2), (Method, 0), (Method, 1), (TypeDecl, 1), (TypeDecl, 0), (TypeDecl, 0), (Other, 0), (Other, 0)]);
        let import = t.children(0)[1];
        assert_eq!(t.nodes[import].flags, IMPORT_STATIC);
    }

    #[test]
    fn contextual_keywords_and_top_level_oddities() {
        assert!(ok("class A { void f() { yield = 1; int yield = 2; var record = 3; String sealed = \"s\"; f(record, sealed); } }"));
        assert!(ok("non-sealed class A {} sealed interface I permits A {} record R() {}"));
        assert!(ok("module m.n { requires transitive java.base; exports a.b to c; }"));
        assert!(ok("int x = 1; void top() {} class A {}")); // statements and methods are accepted at the top
        assert!(ok("class A { void f() { label: for (;;) { break label; } assert x : \"m\"; synchronized (this) {} } }"));
        assert!(ok("class A { String s = STR.\"a \\{b}\"; int[] m()[] { return null; } A() { this(1); } A(O o) { o.super(); } }"));
    }

    #[test]
    fn flags_invalid_code() {
        for bad in ["class A {", "class A { void f() { x = ; } }", "class A { int }", "class { }", "}", "class A { void f() { if x {} } }"] {
            assert!(parse(bad).error, "{bad}");
        }
    }

    #[test]
    fn recovers_declarations_after_errors() {
        let t = parse("class A { void f() { x = ; } int y; void g() {} }");
        assert!(t.error);
        let a = t.children(0)[0];
        let methods = t.children(a).iter().filter(|&&c| t.nodes[c].kind == NodeKind::Method).count();
        assert_eq!(methods, 2);
    }

    /// Checks the structural invariants of a tree: spans inside the token array, subtrees properly nested.
    fn check_tree(t: &Tree) {
        let n = t.nodes.len();
        for (i, node) in t.nodes.iter().enumerate() {
            assert!(node.end as usize > i && node.end as usize <= n);
            assert!(node.first <= node.last && (node.last as usize) < t.tokens.len());
            let mut j = i + 1;
            while j < node.end as usize {
                assert!(t.nodes[j].end <= node.end);
                j = t.nodes[j].end as usize;
            }
        }
        assert_eq!(t.nodes[0].end as usize, n);
    }

    const CORPUS: &str = r#"@Ann(v = Foo.class, o = {Bar.BAZ, @In(1)}) package p.q;
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
"#;

    /// Random byte deletions, insertions and truncations of a corpus file must never panic or break the tree.
    #[test]
    fn survives_mutated_input() {
        let mut seed: u64 = 0x9E37_79B9_7F4A_7C15;
        let mut next = move |bound: usize| {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed % bound.max(1) as u64) as usize
        };
        let alphabet = b"{}()[]<>;,.:?@=&|-\"'/*\\\n abcAB01";
        for _ in 0..3000 {
            let mut b = CORPUS.as_bytes().to_vec();
            for _ in 0..1 + next(4) {
                if b.is_empty() {
                    break;
                }
                let i = next(b.len());
                match next(4) {
                    0 => {
                        let k = 1 + next(8);
                        b.drain(i..(i + k).min(b.len()));
                    }
                    1 => b.insert(i, alphabet[next(alphabet.len())]),
                    2 => b.insert(i, next(256) as u8),
                    _ => b.truncate(i),
                }
            }
            let code = String::from_utf8_lossy(&b);
            let t = parse(&code);
            check_tree(&t);
        }
    }

    /// Pathological nesting must not overflow the stack (run on a thread with a small 2 MiB stack) and must finish.
    #[test]
    fn survives_deep_nesting() {
        let inputs: Vec<String> = vec![
            format!("class A {{ int x = {}1{}; }}", "(".repeat(100_000), ")".repeat(100_000)),
            format!("class A {{ void f() {} }}", "{".repeat(100_000)),
            format!("class A {{ int x = {}1; }}", "a + ".repeat(100_000)),
            format!("class A {{ void f() {{ {} {{}} }} }}", "if (a) {} else ".repeat(20_000)),
            format!("class A {{ {} x; }}", "List<".repeat(50_000)),
            format!("class A {{ Object x = {}1; }}", "y -> ".repeat(50_000)),
            format!("class A {{ int x = {}1; }}", "(A) -".repeat(50_000)),
            format!("class A {{ Object x = {}; }}", "new A() { int y = ".repeat(20_000)),
            format!("{}", "class A { ".repeat(50_000)),
            format!("@A({}) class A {{}}", "@A(".repeat(50_000)),
            format!("class A {{ int x = {}; }}", "a ? b : ".repeat(50_000) + "c"),
        ];
        let handle = std::thread::Builder::new()
            .stack_size(2 * 1024 * 1024)
            .spawn(move || {
                for code in &inputs {
                    let t = parse(code);
                    check_tree(&t);
                }
                // a long but shallow chain parses without error
                assert!(!parse(&format!("class A {{ void f() {{ {} {{}} }} }}", "if (a) {} else ".repeat(20_000))).error);
            })
            .unwrap();
        handle.join().unwrap();
    }
}
