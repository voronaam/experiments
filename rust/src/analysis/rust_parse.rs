//! A small, dependency-free Rust lexer and error-tolerant structural parser, tailored to what the Rust analyser
//! (`rust_lang.rs`) needs. (It replaced tree-sitter-rust 0.24; its classification rules reproduce that grammar's tree
//! where the analyser looks, so analysis results did not change.)
//!
//! # What the analyser needs
//! - the items of a file and of inline modules (`use`, `struct`, `union`, `enum`, `trait`, `impl`, `fn`, `const`,
//!   `static`, `mod`) with names, line spans and outer attributes (for `#[cfg(test)]`);
//! - per struct/union/enum the number of fields/variants, per function the number of (non-`self`) parameters;
//! - the header of an `impl` (the implemented trait and type);
//! - per item the names it refers to, classified by syntactic position (see "Reference leaves");
//! - per function body its decision points (see "Complexity leaves");
//! - whether the file had syntax errors.
//!
//! Everything else (expressions, patterns, types) is parsed only to classify names correctly and to find the decision
//! points, and leaves no nodes behind.
//!
//! # Lexer
//! [`lex`] turns the source into a flat token array. Token kinds: `Ident` (identifiers *and* keywords, including raw
//! identifiers `r#x`; keywords are recognised by text in the parser), `Lifetime` (`'a`, also used for labels),
//! `Literal` (numbers, strings, byte/C/raw strings, chars) and `Punct` (exactly one ASCII punctuation character).
//! Multi-character operators (`::`, `&&`, `->`, `..=`, …) are not tokens: each `Punct` records whether the next token
//! is a `Punct` that follows without any gap (`joint`), and the parser combines characters where an operator is
//! valid. This is how `>>` can close two generic argument lists and `&&x` can be two borrows, while `a && b` is still
//! a logical and.
//!
//! Comments and whitespace produce no tokens, but a token remembers whether a comment preceded it
//! (`comment_before`): a comment between an attribute and its item detaches the attribute from the item, which
//! matters for `#[cfg(test)]` detection. Delimiters `()[]{}` are matched by the lexer (`pair`), so the parser can
//! always find the end of a group even in broken code.
//!
//! Lines are 1-based and count `\n` only (as `text::lines` does); `line`/`end_line` are the lines of a
//! token's first and last character.
//!
//! # Syntax tree
//! The parser produces nodes in a flat vector in pre-order: a node's descendants are the nodes at indices
//! `i + 1 .. nodes[i].end`, its children are found by jumping from one child's `end` to the next. Each node covers the
//! tokens `first ..= last`. Node kinds are listed at [`NodeKind`]; `name` and `value` carry kind-specific data.
//!
//! # Reference leaves
//! The analyser collects referenced names from these leaves, so the parser emits one for every name of these kinds:
//! - [`NodeKind::TypeIdent`]: a single-segment name in type position (`Foo`, `T`,
//!   `_`, but not primitive types such as `u32`), the name of a struct/enum/union/trait/type alias, a generic type
//!   parameter, a struct literal or struct pattern name (`Foo { .. }`). Always a reference.
//! - [`NodeKind::Ident`]: a single-segment name in expressions and patterns, function,
//!   const, static, module and enum-variant names, lifetimes and labels (token text includes the `'`), and every
//!   identifier inside a macro's token tree. A reference only if capitalised.
//! - [`NodeKind::Path`]: a path with `::`. The whole text
//!   is the reference, and nothing inside it is looked at, so a path with generic arguments in the middle
//!   (`Vec::<T>::new`) or a long qualified path (`<T as Tr>::A::B`) becomes one `Path` node whose children are ignored.
//!   Generic arguments at the end (`a::B<C>`, `f::<T>()`) are not part of the path; they follow as siblings. A
//!   qualified path with one segment after the brackets (`<T as Tr>::Item`) emits no `Path` at all: only the
//!   types inside the brackets are references, and those are emitted as usual.
//! - `use` trees ([`NodeKind::UsePath`] etc.) are kept structured for the analyser and act as references too.
//!
//! # Complexity leaves
//! [`NodeKind::If`] (one per `if` keyword of an `if` expression, so `else if` counts; match guards do not),
//! [`NodeKind::While`], [`NodeKind::Loop`], [`NodeKind::For`], [`NodeKind::Match`], [`NodeKind::Arm`] and
//! [`NodeKind::AndOr`] (a binary `&&` or `||`). An `&&` that joins a `let` condition
//! (`if let A = b && c`) is a "let chain", not a binary expression, and does not count; nothing inside a macro's token
//! tree counts (macro arguments are not parsed).
//!
//! # Grammar subset
//! Items: everything stable Rust has (with visibility, `unsafe`/`const`/`async`/`extern "abi"`/`default`/`safe`
//! qualifiers, generics, where clauses), `macro_rules!` definitions and item-position macro calls. Expressions: the
//! full precedence ladder, closures, blocks (`unsafe`/`async`/`const`/`try`/`gen`, labelled), `if`/`if let` with let
//! chains, `match` with guards, loops, struct literals (not allowed in `if`/`while`/`match`/`for` headers, as in Rust),
//! ranges, casts, `?`, `.await`, turbofish. Patterns and types: all stable forms. Statements: `let` (with `else`),
//! items, expression statements (a block-like expression such as `if … {}` ends a statement unless followed by `.` or
//! `?`).
//!
//! # Error recovery
//! The parser never panics and always terminates:
//! - Every delimited group is parsed with a hard limit at its closing delimiter. Tokens left over inside a group are
//!   skipped (setting the error flag), and parsing resumes after the group. So an error never spreads beyond the
//!   innermost group it occurs in.
//! - Item and statement lists skip one token (or one whole group) when nothing could be parsed at a position.
//! - Recursion depth is capped ([`MAX_DEPTH`]); beyond it the rest of the current group is skipped as an error.
//! - Unmatched delimiters, unterminated literals/comments and unknown characters set the error flag.
//! The error flag is the analyser's "file had parse errors"; on valid code it is never set.

/// Marks "no token" in [`Node::name`] and "no matching delimiter" in [`Token::pair`].
pub const NONE: u32 = u32::MAX;

/// Maximum nesting of parse functions (expressions, types, patterns, blocks, item lists, use groups). Real code nests
/// a few dozen levels at most; the cap keeps hostile input from overflowing the stack.
pub const MAX_DEPTH: u32 = 200;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TokKind {
    /// An identifier or keyword (`foo`, `fn`, `Self`, `_`, `r#type`).
    Ident,
    /// `'a`, `'static`, or a label such as `'outer`.
    Lifetime,
    /// Number, string, byte string, raw string, C string or char literal.
    Literal,
    /// One ASCII punctuation character, including the delimiters.
    Punct,
}

#[derive(Clone, Copy, Debug)]
pub struct Token {
    pub kind: TokKind,
    /// Byte range in the source.
    pub start: u32,
    pub end: u32,
    /// 1-based lines of the first and the last character.
    pub line: u32,
    pub end_line: u32,
    /// Punct only: the next token is a Punct that starts exactly where this one ends.
    pub joint: bool,
    /// A comment lies between the previous token and this one.
    pub comment_before: bool,
    /// For `(`, `[`, `{`: index of the matching closer, and vice versa. [`NONE`] if unmatched or not a delimiter.
    pub pair: u32,
}

/// The kinds of syntax tree nodes. Only what the analyser needs is represented; see the module documentation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NodeKind {
    /// The whole file; its children are the top-level items.
    File,
    /// Outer attribute `#[…]`. Inner attribute `#![…]`.
    Attr,
    InnerAttr,
    /// A comment between two items of an item list (a leaf on the token after the comment).
    Comment,
    /// `use` declaration; its use tree is the last child.
    Use,
    /// A path inside a use tree (`a::b::C`), a leaf. `UsePrefix`: the `a::b` of `a::b::{…}` / `a::b::*`.
    UsePath,
    UsePrefix,
    /// `path as alias`: child `UsePath`, `name` = alias token.
    UseAs,
    /// `prefix::{…}` or `{…}`: optional `UsePrefix` child, then the nested use trees.
    UseGroup,
    /// `prefix::*` or `*`: optional `UsePrefix` child.
    UseGlob,
    /// Items. `name` = name token (or NONE). `Struct`/`Union`: `value` = number of fields; `Enum`: number of
    /// variants; `Fn`: number of counted parameters (`self` is not counted).
    Struct,
    Union,
    Enum,
    Trait,
    Impl,
    Fn,
    Const,
    Static,
    Mod,
    TypeAlias,
    MacroRules,
    ExternCrate,
    ForeignMod,
    /// `impl` header parts: the trait and the implemented type. `name`/`value` = first/last token of the type's
    /// "core": `&'a mut T` → `T`, `S<T>` → `S`, otherwise the whole type.
    ImplTrait,
    ImplType,
    /// A function's body block.
    Body,
    /// `{ items }` of a `mod`, `trait`, `impl` or `extern` block.
    DeclBody,
    /// Any other statement in an item list (macro call, `;`, stray expression).
    Other,
    /// Reference leaves (see module docs).
    TypeIdent,
    Ident,
    Path,
    /// Complexity leaves (see module docs).
    If,
    While,
    Loop,
    For,
    Match,
    Arm,
    AndOr,
}

#[derive(Clone, Copy, Debug)]
pub struct Node {
    pub kind: NodeKind,
    /// First and last token (inclusive).
    pub first: u32,
    pub last: u32,
    /// One past the last descendant: descendants are `i + 1 .. end`.
    pub end: u32,
    /// Kind-specific token index (see [`NodeKind`]), NONE if unused.
    pub name: u32,
    /// Kind-specific number (see [`NodeKind`]).
    pub value: u32,
}

/// Lexed and parsed file.
pub struct Tree {
    pub tokens: Vec<Token>,
    pub nodes: Vec<Node>,
    /// True if the lexer or the parser met anything that is not valid Rust.
    pub errors: bool,
    /// Index of the token at which the parser first found an error (0 for lexer errors, NONE without errors).
    /// Only read by diagnostics and tests.
    #[allow(dead_code)]
    pub first_error: u32,
}

impl Tree {
    /// Source text of one token ("" for NONE).
    pub fn tok_text<'a>(&self, code: &'a str, tok: u32) -> &'a str {
        match self.tokens.get(tok as usize) {
            Some(t) => code.get(t.start as usize..t.end as usize).unwrap_or(""),
            None => "",
        }
    }

    /// Source text from the start of token `first` to the end of token `last`, comments and whitespace included.
    pub fn range_text<'a>(&self, code: &'a str, first: u32, last: u32) -> &'a str {
        match (self.tokens.get(first as usize), self.tokens.get(last as usize)) {
            (Some(a), Some(b)) if a.start <= b.end => code.get(a.start as usize..b.end as usize).unwrap_or(""),
            _ => "",
        }
    }

    pub fn node_text<'a>(&self, code: &'a str, n: usize) -> &'a str {
        self.range_text(code, self.nodes[n].first, self.nodes[n].last)
    }

    /// 1-based line of the node's first token.
    pub fn first_line(&self, n: usize) -> u32 {
        self.tokens.get(self.nodes[n].first as usize).map(|t| t.line).unwrap_or(1)
    }

    /// 1-based line of the node's last character.
    pub fn last_line(&self, n: usize) -> u32 {
        self.tokens.get(self.nodes[n].last as usize).map(|t| t.end_line).unwrap_or(1)
    }

    /// Indices of the direct children of node `n`, in order.
    pub fn children(&self, n: usize) -> Vec<usize> {
        let mut out = Vec::new();
        let mut c = n + 1;
        let end = self.nodes[n].end as usize;
        while c < end {
            out.push(c);
            c = self.nodes[c].end as usize;
        }
        out
    }

    /// The first direct child of the given kind.
    pub fn child(&self, n: usize, kind: NodeKind) -> Option<usize> {
        self.children(n).into_iter().find(|&c| self.nodes[c].kind == kind)
    }
}

/// Lexes and parses one file.
pub fn parse(code: &str) -> Tree {
    let (tokens, lex_errors) = lex(code);
    let mut p = Parser {
        code: code.as_bytes(),
        src: code,
        toks: &tokens,
        pos: 0,
        limit: tokens.len(),
        nodes: Vec::with_capacity(tokens.len() / 3 + 4),
        errors: lex_errors,
        first_error: if lex_errors { 0 } else { NONE },
        depth: 0,
    };
    let f = p.open(NodeKind::File);
    p.parse_items(true);
    p.close(f);
    let nodes = std::mem::take(&mut p.nodes);
    let errors = p.errors;
    let first_error = p.first_error;
    Tree { tokens, nodes, errors, first_error }
}

// ------------------------------------------------------------------ lexer

fn is_ident_start(c: u8) -> bool {
    c.is_ascii_alphabetic() || c == b'_' || c >= 0x80
}

fn is_ident_continue(c: u8) -> bool {
    c.is_ascii_alphanumeric() || c == b'_' || c >= 0x80
}

/// Length in bytes of the UTF-8 character starting with byte `c`.
fn utf8_len(c: u8) -> usize {
    if c < 0xC0 {
        1
    } else if c < 0xE0 {
        2
    } else if c < 0xF0 {
        3
    } else {
        4
    }
}

/// End of a `"…"` string whose opening quote is at `i` (backslash escapes skipped), and whether it was terminated.
fn quoted_end(b: &[u8], i: usize) -> (usize, bool) {
    let mut j = i + 1;
    while j < b.len() {
        match b[j] {
            b'\\' => j += 2,
            b'"' => return (j + 1, true),
            _ => j += 1,
        }
    }
    (b.len(), false)
}

/// If a raw string (`r"…"`, `r#"…"#`, …) starts with the `r` at `i`, its end and whether it was terminated.
fn raw_string_end(b: &[u8], i: usize) -> Option<(usize, bool)> {
    let mut j = i + 1;
    let mut hashes = 0;
    while j < b.len() && b[j] == b'#' {
        hashes += 1;
        j += 1;
    }
    if j >= b.len() || b[j] != b'"' {
        return None;
    }
    j += 1;
    while j < b.len() {
        if b[j] == b'"' && b.len() - (j + 1) >= hashes && b[j + 1..j + 1 + hashes].iter().all(|&c| c == b'#') {
            return Some((j + 1 + hashes, true));
        }
        j += 1;
    }
    Some((b.len(), false))
}

/// End of a number literal starting at `i`: digits, letters (suffixes, hex digits), `_`, one decimal point followed
/// by a digit (or not followed by `.` or an identifier: `1.`), and an exponent sign after `e`/`E`.
fn number_end(b: &[u8], i: usize) -> usize {
    let n = b.len();
    let hex = b[i] == b'0' && i + 1 < n && matches!(b[i + 1], b'x' | b'o' | b'b');
    let mut j = i;
    let mut dot = hex;
    while j < n {
        let c = b[j];
        if c.is_ascii_alphanumeric() || c == b'_' {
            if !hex && (c == b'e' || c == b'E') && j + 2 < n + 1 && j + 1 < n && matches!(b[j + 1], b'+' | b'-') {
                if j + 2 < n && b[j + 2].is_ascii_digit() {
                    j += 3;
                    continue;
                }
            }
            j += 1;
        } else if c == b'.' && !dot {
            let next = if j + 1 < n { b[j + 1] } else { 0 };
            if next.is_ascii_digit() {
                dot = true;
                j += 1;
            } else if next != b'.' && !is_ident_start(next) {
                return j + 1; // `1.`
            } else {
                break;
            }
        } else {
            break;
        }
    }
    j
}

/// Splits the source into tokens; returns them and whether anything was malformed (unterminated literal or comment,
/// unknown character, unbalanced delimiter).
pub fn lex(code: &str) -> (Vec<Token>, bool) {
    let b = code.as_bytes();
    let n = b.len();
    let mut toks: Vec<Token> = Vec::with_capacity(n / 5 + 4);
    let mut errors = false;
    let mut i = 0;
    let mut line = 1u32;
    let mut comment = false;
    // A shebang line (`#!/usr/bin/env …`, but not an inner attribute `#![…]`) is skipped.
    if b.starts_with(b"#!") {
        let mut j = 2;
        while j < n && matches!(b[j], b' ' | b'\t' | b'\r' | 0x0b | 0x0c) {
            j += 1;
        }
        if j >= n || b[j] != b'[' {
            while i < n && b[i] != b'\n' {
                i += 1;
            }
        }
    }
    while i < n {
        let c = b[i];
        if c == b'\n' {
            line += 1;
            i += 1;
            continue;
        }
        if matches!(c, b' ' | b'\t' | b'\r' | 0x0b | 0x0c) {
            i += 1;
            continue;
        }
        let next = if i + 1 < n { b[i + 1] } else { 0 };
        if c == b'/' && next == b'/' {
            while i < n && b[i] != b'\n' {
                i += 1;
            }
            comment = true;
            continue;
        }
        if c == b'/' && next == b'*' {
            // Block comments nest.
            let mut depth = 1;
            i += 2;
            while i < n && depth > 0 {
                if b[i] == b'/' && i + 1 < n && b[i + 1] == b'*' {
                    depth += 1;
                    i += 2;
                } else if b[i] == b'*' && i + 1 < n && b[i + 1] == b'/' {
                    depth -= 1;
                    i += 2;
                } else {
                    if b[i] == b'\n' {
                        line += 1;
                    }
                    i += 1;
                }
            }
            if depth > 0 {
                errors = true;
            }
            comment = true;
            continue;
        }
        let start = i;
        let kind;
        if (c == b'r' || ((c == b'b' || c == b'c') && next == b'r')) && {
            let r = if c == b'r' { i } else { i + 1 };
            raw_string_end(b, r).is_some()
        } {
            let r = if c == b'r' { i } else { i + 1 };
            let (end, ok) = raw_string_end(b, r).unwrap_or((n, false));
            errors |= !ok;
            i = end;
            kind = TokKind::Literal;
        } else if (c == b'b' || c == b'c') && next == b'"' {
            let (end, ok) = quoted_end(b, i + 1);
            errors |= !ok;
            i = end;
            kind = TokKind::Literal;
        } else if c == b'b' && next == b'\'' {
            let (end, ok) = char_end(b, i + 1).unwrap_or((i + 2, false));
            errors |= !ok;
            i = end;
            kind = TokKind::Literal;
        } else if c == b'r' && next == b'#' && i + 2 < n && is_ident_start(b[i + 2]) {
            i += 2;
            while i < n && is_ident_continue(b[i]) {
                i += 1;
            }
            kind = TokKind::Ident;
        } else if is_ident_start(c) {
            while i < n && is_ident_continue(b[i]) {
                i += 1;
            }
            kind = TokKind::Ident;
        } else if c.is_ascii_digit() {
            i = number_end(b, i);
            kind = TokKind::Literal;
        } else if c == b'"' {
            let (end, ok) = quoted_end(b, i);
            errors |= !ok;
            i = end;
            kind = TokKind::Literal;
        } else if c == b'\'' {
            if let Some((end, ok)) = char_end(b, i) {
                errors |= !ok;
                i = end;
                kind = TokKind::Literal;
            } else if i + 1 < n && is_ident_start(b[i + 1]) {
                i += 1;
                if b[i] == b'r' && i + 2 < n && b[i + 1] == b'#' && is_ident_start(b[i + 2]) {
                    i += 2;
                }
                while i < n && is_ident_continue(b[i]) {
                    i += 1;
                }
                kind = TokKind::Lifetime;
            } else {
                errors = true;
                i += 1;
                kind = TokKind::Punct;
            }
        } else {
            // Any other ASCII character: punctuation. Characters Rust does not use (`` ` ``, control characters) are
            // errors but still become tokens so that nothing is lost.
            if !c.is_ascii_punctuation() || c == b'`' || c == b'\\' {
                errors = true;
            }
            i += 1;
            kind = TokKind::Punct;
        }
        let end_line = line + b[start..i].iter().filter(|&&x| x == b'\n').count() as u32;
        if let Some(prev) = toks.last_mut() {
            if prev.kind == TokKind::Punct && kind == TokKind::Punct && prev.end as usize == start {
                prev.joint = true;
            }
        }
        toks.push(Token {
            kind,
            start: start as u32,
            end: i as u32,
            line,
            end_line,
            joint: false,
            comment_before: comment,
            pair: NONE,
        });
        line = end_line;
        comment = false;
    }
    errors |= match_delimiters(code, &mut toks);
    (toks, errors)
}

/// If a char literal starts with the quote at `i` (`'a'`, `'\n'`, `'\u{1F600}'`), its end and whether it was
/// terminated. `None` means it is not a char literal (a lifetime, or a lone quote).
fn char_end(b: &[u8], i: usize) -> Option<(usize, bool)> {
    let n = b.len();
    if i + 1 >= n {
        return None;
    }
    if b[i + 1] == b'\\' {
        // An escape: scan to the closing quote on the same line.
        let mut j = i + 3;
        while j < n && b[j] != b'\'' && b[j] != b'\n' {
            j += 1;
        }
        return Some(if j < n && b[j] == b'\'' { (j + 1, true) } else { (j.min(n), false) });
    }
    let len = utf8_len(b[i + 1]);
    if i + 1 + len < n && b[i + 1 + len] == b'\'' && b[i + 1] != b'\n' {
        return Some((i + 2 + len, true));
    }
    None
}

/// Pairs `(`/`)`, `[`/`]`, `{`/`}`. A closer that does not match the innermost open delimiter is left unpaired if
/// no open delimiter matches it, otherwise the delimiters opened after that match stay unpaired. Returns whether any
/// delimiter stayed unpaired.
fn match_delimiters(code: &str, toks: &mut [Token]) -> bool {
    let b = code.as_bytes();
    let mut stack: Vec<usize> = Vec::new();
    let mut errors = false;
    for k in 0..toks.len() {
        if toks[k].kind != TokKind::Punct {
            continue;
        }
        let c = b[toks[k].start as usize];
        let open = match c {
            b')' => b'(',
            b']' => b'[',
            b'}' => b'{',
            b'(' | b'[' | b'{' => {
                stack.push(k);
                continue;
            }
            _ => continue,
        };
        match stack.iter().rposition(|&o| b[toks[o].start as usize] == open) {
            Some(at) => {
                if at + 1 != stack.len() {
                    errors = true;
                }
                let o = stack[at];
                stack.truncate(at);
                toks[o].pair = k as u32;
                toks[k].pair = o as u32;
            }
            None => errors = true,
        }
    }
    errors || !stack.is_empty()
}

// ------------------------------------------------------------------ parser

/// Primitive types: in type position they produce no [`NodeKind::TypeIdent`] leaf (they are no references).
const PRIMITIVES: [&str; 17] = [
    "u8", "i8", "u16", "i16", "u32", "i32", "u64", "i64", "u128", "i128", "isize", "usize", "f32", "f64", "bool",
    "str", "char",
];

/// The item a statement starts with (see [`Parser::item_start`]).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum ItemKw {
    Fn,
    Struct,
    Union,
    Enum,
    Trait,
    Impl,
    Mod,
    Use,
    Type,
    Const,
    Static,
    ExternCrate,
    ForeignMod,
    MacroRules,
}

/// Where a path occurs; decides whether `<` starts generic arguments and how a single-segment path is classified.
#[derive(Clone, Copy, PartialEq, Eq)]
enum PathCtx {
    Expr,
    Type,
    Pattern,
}

/// What [`Parser::parse_path`] found. Nodes for the path itself are emitted by [`Parser::emit_path`].
struct PathInfo {
    /// First token, and one past the last token of the "name part" (the path without trailing generic arguments).
    start: usize,
    name_end: usize,
    /// Node count when the path started (nodes for a qualified `<…>` prefix or middle generics follow it).
    mark: usize,
    /// Node count where trailing generic arguments start (equal to the node count after the path if none).
    tail_mark: usize,
    /// Number of `::`-separated segments, not counting a `<…>` prefix.
    segs: u32,
    /// Starts with `<T as Tr>` / `<T>`.
    bracket: bool,
    /// Starts with `::`.
    leading: bool,
    /// Generic arguments before the last segment (`Vec::<T>::new`).
    mid: bool,
    /// Generic arguments after the last segment (`Vec<T>`, `f::<T>`).
    tail_args: bool,
}

impl PathInfo {
    fn single(&self) -> bool {
        self.segs == 1 && !self.bracket && !self.leading && !self.mid
    }
}

/// Facts about a parsed expression that its context needs.
#[derive(Clone, Copy, Default)]
struct ExprInfo {
    /// Ends with a block and needs no `;` as a statement (`if`, `match`, loops, blocks, brace macros).
    block_like: bool,
    /// The whole expression is one macro call (needs no `;` as a statement either).
    macro_call: bool,
    /// A `let` condition or a let chain (its `&&`s are not binary expressions).
    is_let: bool,
}

struct Parser<'a> {
    code: &'a [u8],
    src: &'a str,
    toks: &'a [Token],
    pos: usize,
    /// End of the current group: the index of its closing delimiter (or the token count at the top level). Nothing
    /// at or after it is ever looked at.
    limit: usize,
    nodes: Vec<Node>,
    errors: bool,
    /// Token index of the first error found by the parser (for diagnostics and tests).
    first_error: u32,
    depth: u32,
}

/// Saved parser state for backtracking (see `parse_params`).
struct Checkpoint {
    pos: usize,
    nodes: usize,
    errors: bool,
    first_error: u32,
}

impl<'a> Parser<'a> {
    // ---------------------------------------------------------- token helpers

    fn at_end(&self) -> bool {
        self.pos >= self.limit
    }

    fn text(&self, i: usize) -> &'a str {
        let t = &self.toks[i];
        self.src.get(t.start as usize..t.end as usize).unwrap_or("")
    }

    fn is_kind(&self, i: usize, k: TokKind) -> bool {
        i < self.limit && self.toks[i].kind == k
    }

    fn is_ident(&self, i: usize) -> bool {
        self.is_kind(i, TokKind::Ident)
    }

    /// Token `i` is the punctuation character `ch`.
    fn punct_at(&self, i: usize, ch: u8) -> bool {
        i < self.limit && self.toks[i].kind == TokKind::Punct && self.code[self.toks[i].start as usize] == ch
    }

    fn punct(&self, ch: u8) -> bool {
        self.punct_at(self.pos, ch)
    }

    /// Tokens from `i` spell the operator `s` without gaps (`::`, `&&`, `..=`).
    fn op_at(&self, i: usize, s: &[u8]) -> bool {
        for (k, &ch) in s.iter().enumerate() {
            if !self.punct_at(i + k, ch) {
                return false;
            }
            if k + 1 < s.len() && !self.toks[i + k].joint {
                return false;
            }
        }
        true
    }

    fn op(&self, s: &[u8]) -> bool {
        self.op_at(self.pos, s)
    }

    fn kw_at(&self, i: usize, s: &str) -> bool {
        self.is_ident(i) && self.text(i) == s
    }

    fn kw(&self, s: &str) -> bool {
        self.kw_at(self.pos, s)
    }

    fn is_opener(&self, i: usize) -> bool {
        self.punct_at(i, b'(') || self.punct_at(i, b'[') || self.punct_at(i, b'{')
    }

    /// A single `:` (not `::`).
    fn colon_at(&self, i: usize) -> bool {
        self.punct_at(i, b':') && !self.op_at(i, b"::")
    }

    fn bump(&mut self) {
        if self.pos < self.limit {
            self.pos += 1;
        }
    }

    fn bump_n(&mut self, n: usize) {
        for _ in 0..n {
            self.bump();
        }
    }

    fn eat_punct(&mut self, ch: u8) -> bool {
        if self.punct(ch) {
            self.bump();
            true
        } else {
            false
        }
    }

    fn expect_punct(&mut self, ch: u8) {
        if !self.eat_punct(ch) {
            self.err();
        }
    }

    fn eat_kw(&mut self, s: &str) -> bool {
        if self.kw(s) {
            self.bump();
            true
        } else {
            false
        }
    }

    /// Skips one token, or a whole group if at an opening delimiter.
    fn skip_tree(&mut self) {
        let p = self.toks.get(self.pos).map(|t| t.pair).unwrap_or(NONE);
        if self.is_opener(self.pos) && p != NONE && (p as usize) < self.limit {
            self.pos = p as usize + 1;
        } else {
            self.bump();
        }
    }

    /// Enters the group opening at the current token: the limit becomes its closing delimiter. Returns the outer
    /// limit, to be handed to [`Parser::close_group`], or `None` (error, nothing consumed) if not at a matched opener.
    fn open_group(&mut self) -> Option<usize> {
        if !self.is_opener(self.pos) {
            self.err();
            return None;
        }
        let p = self.toks[self.pos].pair;
        if p == NONE || p as usize >= self.limit {
            self.err();
            return None;
        }
        let saved = self.limit;
        self.limit = p as usize;
        self.pos += 1;
        Some(saved)
    }

    /// Leaves the current group: anything not parsed inside it is an error; continues after the closer.
    fn close_group(&mut self, saved: usize) {
        if self.pos < self.limit {
            self.err();
        }
        self.pos = self.limit + 1;
        self.limit = saved;
    }

    /// Counts one level of recursion. Beyond [`MAX_DEPTH`], gives up on the rest of the current group.
    fn enter(&mut self) -> bool {
        if self.depth >= MAX_DEPTH {
            self.err();
            self.pos = self.limit;
            return false;
        }
        self.depth += 1;
        true
    }

    fn leave(&mut self) {
        self.depth -= 1;
    }

    fn checkpoint(&self) -> Checkpoint {
        Checkpoint { pos: self.pos, nodes: self.nodes.len(), errors: self.errors, first_error: self.first_error }
    }

    fn restore(&mut self, c: Checkpoint) {
        self.pos = c.pos;
        self.nodes.truncate(c.nodes);
        self.errors = c.errors;
        self.first_error = c.first_error;
    }

    /// Records a syntax error at the current token.
    fn err(&mut self) {
        if !self.errors {
            self.first_error = self.pos.min(self.toks.len()) as u32;
        }
        self.errors = true;
    }

    // ---------------------------------------------------------- node helpers

    /// Starts a node at the current token; finish it with [`Parser::close`].
    fn open(&mut self, kind: NodeKind) -> usize {
        self.open_at(kind, self.pos)
    }

    fn open_at(&mut self, kind: NodeKind, first: usize) -> usize {
        self.nodes.push(Node { kind, first: first as u32, last: first as u32, end: 0, name: NONE, value: 0 });
        self.nodes.len() - 1
    }

    /// Ends node `n` at the last consumed token; everything emitted since it was opened are its descendants.
    fn close(&mut self, n: usize) {
        let first = self.nodes[n].first as usize;
        let last = if self.pos > first { self.pos - 1 } else { first };
        self.nodes[n].last = last as u32;
        self.nodes[n].end = self.nodes.len() as u32;
    }

    fn leaf(&mut self, kind: NodeKind, tok: usize) {
        self.leaf_range(kind, tok, tok);
    }

    fn leaf_range(&mut self, kind: NodeKind, first: usize, last: usize) {
        let end = self.nodes.len() as u32 + 1;
        self.nodes.push(Node { kind, first: first as u32, last: last as u32, end, name: NONE, value: 0 });
    }

    /// Inserts a node at index `at` whose descendants are the nodes `at .. cover_end` emitted so far. Used where the
    /// kind of a node is only known after its contents were parsed (qualified paths, `impl` headers).
    fn wrap(&mut self, at: usize, cover_end: usize, kind: NodeKind, first: usize, last: usize) -> usize {
        for n in &mut self.nodes[at..] {
            n.end += 1;
        }
        let node = Node { kind, first: first as u32, last: last as u32, end: cover_end as u32 + 1, name: NONE, value: 0 };
        self.nodes.insert(at, node);
        at
    }

    // ---------------------------------------------------------- items

    /// Parses a list of items until the end of the current group. `top`: the file itself, where statements are
    /// accepted too; inside `{ … }` of a module, trait, impl or extern block only items and macro calls are
    /// valid.
    fn parse_items(&mut self, top: bool) {
        if !self.enter() {
            return;
        }
        while !self.at_end() {
            let before = self.pos;
            if self.toks[self.pos].comment_before {
                self.leaf(NodeKind::Comment, self.pos);
            }
            if self.at_attr() {
                self.parse_attr();
            } else if let Some((kw, at)) = self.item_start(self.pos) {
                self.parse_item(kw, at);
            } else {
                let n = self.open(NodeKind::Other);
                if !top && !self.punct(b';') && !self.is_macro_call(self.pos) {
                    self.err();
                }
                self.parse_stmt_rest();
                self.close(n);
            }
            if self.pos == before {
                self.err();
                self.skip_tree();
            }
        }
        self.leave();
    }

    /// At `#[` or `#![`.
    fn at_attr(&self) -> bool {
        self.punct(b'#') && (self.punct_at(self.pos + 1, b'[') || (self.punct_at(self.pos + 1, b'!') && self.punct_at(self.pos + 2, b'[')))
    }

    /// Parses an outer (`#[…]`) or inner (`#![…]`) attribute: a path, then `= expr` or a token tree.
    fn parse_attr(&mut self) {
        let inner = self.punct_at(self.pos + 1, b'!');
        let n = self.open(if inner { NodeKind::InnerAttr } else { NodeKind::Attr });
        self.bump_n(if inner { 2 } else { 1 });
        if let Some(saved) = self.open_group() {
            if self.is_ident(self.pos) || self.op(b"::") {
                let p = self.parse_path(PathCtx::Expr);
                self.emit_path(&p, Some(NodeKind::Ident));
            } else {
                self.err();
            }
            if self.punct(b'=') {
                self.bump();
                self.parse_expr(false);
            } else if self.is_opener(self.pos) {
                self.token_tree(false);
            }
            self.close_group(saved);
        } else {
            self.bump();
        }
        self.close(n);
    }

    /// Skips outer attributes (still emitting their references), e.g. before fields, parameters and match arms.
    fn parse_outer_attrs(&mut self) {
        while self.at_attr() {
            self.parse_attr();
        }
    }

    /// If an item starts at token `at`, its kind and the index of its keyword, after visibility and qualifiers
    /// (`pub(crate) const unsafe fn` → `Fn` at `fn`). Contextual keywords (`union`, `auto`, `default`, `safe`,
    /// `macro_rules`) and words that also start expressions (`const {`, `unsafe {`, `async move`, `static ||`) are
    /// told apart by the following tokens.
    fn item_start(&self, at: usize) -> Option<(ItemKw, usize)> {
        let mut j = at;
        if self.kw_at(j, "pub") {
            j += 1;
            if self.punct_at(j, b'(')
                && (self.kw_at(j + 1, "crate") || self.kw_at(j + 1, "self") || self.kw_at(j + 1, "super") || self.kw_at(j + 1, "in"))
            {
                let p = self.toks[j].pair;
                if p != NONE && (p as usize) < self.limit {
                    j = p as usize + 1;
                }
            }
        }
        loop {
            if !self.is_ident(j) {
                return None;
            }
            let next_is = |s: &[&str]| s.iter().any(|k| self.kw_at(j + 1, k));
            match self.text(j) {
                "fn" => return Some((ItemKw::Fn, j)),
                "struct" => return Some((ItemKw::Struct, j)),
                "enum" => return Some((ItemKw::Enum, j)),
                "trait" => return Some((ItemKw::Trait, j)),
                "impl" => return Some((ItemKw::Impl, j)),
                "mod" => return Some((ItemKw::Mod, j)),
                "use" => return Some((ItemKw::Use, j)),
                "type" => return Some((ItemKw::Type, j)),
                "union" => {
                    let ok = self.is_ident(j + 1)
                        && (self.punct_at(j + 2, b'{') || self.punct_at(j + 2, b'<') || self.kw_at(j + 2, "where"));
                    return if ok { Some((ItemKw::Union, j)) } else { None };
                }
                "static" => {
                    let ok = next_is(&["mut", "ref"])
                        || (self.is_ident(j + 1) && !self.kw_at(j + 1, "move") && self.colon_at(j + 2));
                    return if ok { Some((ItemKw::Static, j)) } else { None };
                }
                "macro_rules" => {
                    return if self.punct_at(j + 1, b'!') && !self.op_at(j + 1, b"!=") {
                        Some((ItemKw::MacroRules, j))
                    } else {
                        None
                    };
                }
                "extern" => {
                    if self.kw_at(j + 1, "crate") {
                        return Some((ItemKw::ExternCrate, j));
                    }
                    let k = if self.is_kind(j + 1, TokKind::Literal) { j + 2 } else { j + 1 };
                    if self.punct_at(k, b'{') {
                        return Some((ItemKw::ForeignMod, k));
                    }
                    j = k;
                }
                "const" => {
                    if next_is(&["fn", "unsafe", "async", "extern"]) {
                        j += 1;
                    } else if self.is_ident(j + 1) {
                        return Some((ItemKw::Const, j));
                    } else {
                        return None;
                    }
                }
                "async" => {
                    if next_is(&["fn", "unsafe", "extern"]) {
                        j += 1;
                    } else {
                        return None;
                    }
                }
                "unsafe" => {
                    if next_is(&["fn", "impl", "trait", "extern", "auto", "mod"]) {
                        j += 1;
                    } else {
                        return None;
                    }
                }
                "safe" => {
                    if next_is(&["fn", "static"]) {
                        j += 1;
                    } else {
                        return None;
                    }
                }
                "default" => {
                    if next_is(&["fn", "impl", "unsafe", "const", "async", "type", "extern"]) {
                        j += 1;
                    } else {
                        return None;
                    }
                }
                "auto" => {
                    return if self.kw_at(j + 1, "trait") { Some((ItemKw::Trait, j + 1)) } else { None };
                }
                _ => return None,
            }
        }
    }

    /// `path!` followed by a delimiter at token `i`.
    fn is_macro_call(&self, i: usize) -> bool {
        let mut j = i;
        if self.op_at(j, b"::") {
            j += 2;
        }
        loop {
            if !self.is_ident(j) {
                return false;
            }
            j += 1;
            if self.op_at(j, b"::") {
                j += 2;
                continue;
            }
            break;
        }
        self.punct_at(j, b'!') && !self.op_at(j, b"!=") && self.is_opener(j + 1)
    }

    /// `pub`, `pub(crate)`, `pub(in a::b)`: only the `in` path has references.
    fn parse_visibility(&mut self) {
        if !self.eat_kw("pub") {
            return;
        }
        if self.punct(b'(')
            && (self.kw_at(self.pos + 1, "crate")
                || self.kw_at(self.pos + 1, "self")
                || self.kw_at(self.pos + 1, "super")
                || self.kw_at(self.pos + 1, "in"))
        {
            if let Some(saved) = self.open_group() {
                if self.eat_kw("in") {
                    let p = self.parse_path(PathCtx::Expr);
                    self.emit_path(&p, Some(NodeKind::Ident));
                } else {
                    self.bump();
                }
                self.close_group(saved);
            }
        }
    }

    /// Emits the name token of an item as a reference leaf of the given kind and stores it as the item's name.
    fn item_name(&mut self, n: usize, kind: NodeKind) {
        if self.is_ident(self.pos) {
            self.nodes[n].name = self.pos as u32;
            self.leaf(kind, self.pos);
            self.bump();
        } else {
            self.err();
        }
    }

    /// Parses an item whose keyword (as found by [`Parser::item_start`]) is at token `kwpos`.
    fn parse_item(&mut self, kw: ItemKw, kwpos: usize) {
        let kind = match kw {
            ItemKw::Fn => NodeKind::Fn,
            ItemKw::Struct => NodeKind::Struct,
            ItemKw::Union => NodeKind::Union,
            ItemKw::Enum => NodeKind::Enum,
            ItemKw::Trait => NodeKind::Trait,
            ItemKw::Impl => NodeKind::Impl,
            ItemKw::Mod => NodeKind::Mod,
            ItemKw::Use => NodeKind::Use,
            ItemKw::Type => NodeKind::TypeAlias,
            ItemKw::Const => NodeKind::Const,
            ItemKw::Static => NodeKind::Static,
            ItemKw::ExternCrate => NodeKind::ExternCrate,
            ItemKw::ForeignMod => NodeKind::ForeignMod,
            ItemKw::MacroRules => NodeKind::MacroRules,
        };
        let n = self.open(kind);
        self.parse_visibility();
        // Qualifiers (`const`, `async`, `unsafe`, `extern "C"`, …) carry no references.
        if kwpos >= self.pos && kwpos < self.limit {
            self.pos = kwpos;
        }
        match kw {
            ItemKw::Fn => self.parse_fn(n),
            ItemKw::Struct | ItemKw::Union => {
                self.bump();
                self.item_name(n, NodeKind::TypeIdent);
                self.parse_generic_params_opt();
                self.parse_where_opt();
                if self.punct(b'{') {
                    self.nodes[n].value = self.parse_fields();
                } else if kw == ItemKw::Struct && self.punct(b'(') {
                    self.nodes[n].value = self.parse_tuple_fields();
                    self.parse_where_opt();
                    self.expect_punct(b';');
                } else {
                    self.expect_punct(b';');
                }
            }
            ItemKw::Enum => {
                self.bump();
                self.item_name(n, NodeKind::TypeIdent);
                self.parse_generic_params_opt();
                self.parse_where_opt();
                self.nodes[n].value = self.parse_variants();
            }
            ItemKw::Trait => {
                self.bump();
                self.item_name(n, NodeKind::TypeIdent);
                self.parse_generic_params_opt();
                if self.colon_at(self.pos) {
                    self.bump();
                    self.parse_bounds_opt();
                }
                self.parse_where_opt();
                if self.punct(b'=') {
                    // trait alias
                    self.bump();
                    self.parse_bounds_opt();
                    self.parse_where_opt();
                    self.expect_punct(b';');
                } else {
                    self.parse_decl_body();
                }
            }
            ItemKw::Impl => self.parse_impl(),
            ItemKw::Mod => {
                self.bump();
                self.item_name(n, NodeKind::Ident);
                if !self.eat_punct(b';') {
                    self.parse_decl_body();
                }
            }
            ItemKw::Use => {
                self.bump();
                self.parse_use_tree();
                self.expect_punct(b';');
            }
            ItemKw::Type => {
                self.bump();
                self.item_name(n, NodeKind::TypeIdent);
                self.parse_generic_params_opt();
                if self.colon_at(self.pos) {
                    self.bump();
                    self.parse_bounds_opt();
                }
                self.parse_where_opt();
                if self.eat_punct(b'=') {
                    self.parse_type(true);
                }
                self.parse_where_opt();
                self.expect_punct(b';');
            }
            ItemKw::Const | ItemKw::Static => {
                self.bump();
                if kw == ItemKw::Static {
                    self.eat_kw("ref");
                    self.eat_kw("mut");
                }
                self.item_name(n, NodeKind::Ident);
                self.parse_generic_params_opt();
                if self.colon_at(self.pos) {
                    self.bump();
                    self.parse_type(true);
                } else {
                    self.err();
                }
                if self.eat_punct(b'=') {
                    self.parse_expr(false);
                }
                self.parse_where_opt();
                self.expect_punct(b';');
            }
            ItemKw::ExternCrate => {
                self.bump_n(2);
                self.item_name(n, NodeKind::Ident);
                if self.eat_kw("as") {
                    if self.is_ident(self.pos) {
                        self.leaf(NodeKind::Ident, self.pos);
                        self.bump();
                    } else {
                        self.err();
                    }
                }
                self.expect_punct(b';');
            }
            ItemKw::ForeignMod => self.parse_decl_body(),
            ItemKw::MacroRules => {
                self.bump_n(2);
                self.item_name(n, NodeKind::Ident);
                let paren = !self.punct(b'{');
                if self.is_opener(self.pos) {
                    self.token_tree(true);
                    if paren {
                        self.expect_punct(b';');
                    }
                } else {
                    self.err();
                }
            }
        }
        self.close(n);
    }

    /// `fn name<…>(params) -> T where … { body }` or `… ;` (a signature in a trait or extern block).
    fn parse_fn(&mut self, n: usize) {
        self.bump();
        self.item_name(n, NodeKind::Ident);
        self.parse_generic_params_opt();
        if self.punct(b'(') {
            self.nodes[n].value = self.parse_params();
        } else {
            self.err();
        }
        if self.op(b"->") {
            self.bump_n(2);
            self.parse_type(true);
        }
        self.parse_where_opt();
        if self.punct(b'{') {
            let b = self.open(NodeKind::Body);
            self.parse_block();
            self.close(b);
        } else {
            self.expect_punct(b';');
        }
    }

    /// `impl<…> !? Trait for Type where … { items }`. The trait and type are wrapped into `ImplTrait`/`ImplType`
    /// nodes once it is known which is which (the first type is the trait iff `for` follows it).
    fn parse_impl(&mut self) {
        self.bump();
        if self.punct(b'<') {
            self.parse_generic_params();
        }
        self.eat_kw("const");
        // `impl !Trait for T`; but `impl ! {}` implements for the never type
        if self.punct(b'!') && self.can_start_type(self.pos + 1) {
            self.bump();
        }
        let mark = self.nodes.len();
        let start = self.pos;
        let core = self.parse_type(true);
        if self.kw("for") && !self.punct_at(self.pos + 1, b'<') {
            let t = self.wrap(mark, self.nodes.len(), NodeKind::ImplTrait, start, self.pos.saturating_sub(1).max(start));
            self.nodes[t].name = core.0 as u32;
            self.nodes[t].value = core.1 as u32;
            self.bump();
            let mark = self.nodes.len();
            let start = self.pos;
            let core = self.parse_type(true);
            let t = self.wrap(mark, self.nodes.len(), NodeKind::ImplType, start, self.pos.saturating_sub(1).max(start));
            self.nodes[t].name = core.0 as u32;
            self.nodes[t].value = core.1 as u32;
        } else {
            let t = self.wrap(mark, self.nodes.len(), NodeKind::ImplType, start, self.pos.saturating_sub(1).max(start));
            self.nodes[t].name = core.0 as u32;
            self.nodes[t].value = core.1 as u32;
        }
        self.parse_where_opt();
        if !self.eat_punct(b';') {
            self.parse_decl_body();
        }
    }

    /// `{ items }` of a module, trait, impl or extern block.
    fn parse_decl_body(&mut self) {
        if !self.punct(b'{') {
            self.err();
            return;
        }
        let b = self.open(NodeKind::DeclBody);
        if let Some(saved) = self.open_group() {
            self.parse_items(false);
            self.close_group(saved);
        }
        self.close(b);
    }

    /// `{ attrs* vis? name: Type, … }`; returns the number of fields.
    fn parse_fields(&mut self) -> u32 {
        let mut count = 0;
        let Some(saved) = self.open_group() else { return 0 };
        while !self.at_end() {
            self.parse_outer_attrs();
            if self.at_end() {
                break;
            }
            self.parse_visibility();
            if (self.is_ident(self.pos) || self.is_kind(self.pos, TokKind::Literal)) && self.colon_at(self.pos + 1) {
                self.bump_n(2); // field names are not references
                self.parse_type(true);
                count += 1;
            } else {
                self.err();
                break;
            }
            if !self.eat_punct(b',') {
                break;
            }
        }
        self.close_group(saved);
        count
    }

    /// `( attrs* vis? Type, … )`; returns the number of fields.
    fn parse_tuple_fields(&mut self) -> u32 {
        let mut count = 0;
        let Some(saved) = self.open_group() else { return 0 };
        while !self.at_end() {
            self.parse_outer_attrs();
            if self.at_end() {
                break;
            }
            self.parse_visibility();
            let before = self.pos;
            self.parse_type(true);
            if self.pos == before {
                break;
            }
            count += 1;
            if !self.eat_punct(b',') {
                break;
            }
        }
        self.close_group(saved);
        count
    }

    /// `{ attrs* vis? Name ({…} | (…))? (= expr)?, … }`; returns the number of variants.
    fn parse_variants(&mut self) -> u32 {
        let mut count = 0;
        let Some(saved) = self.open_group() else { return 0 };
        while !self.at_end() {
            self.parse_outer_attrs();
            if self.at_end() {
                break;
            }
            self.parse_visibility();
            if !self.is_ident(self.pos) {
                self.err();
                break;
            }
            // Variant names are `Ident` leaves, so they are references when capitalised.
            self.leaf(NodeKind::Ident, self.pos);
            self.bump();
            count += 1;
            if self.punct(b'{') {
                self.parse_fields();
            } else if self.punct(b'(') {
                self.parse_tuple_fields();
            }
            if self.eat_punct(b'=') {
                self.parse_expr(false);
            }
            if !self.eat_punct(b',') {
                break;
            }
        }
        self.close_group(saved);
        count
    }

    /// Function parameters `( … )`; returns how many count as parameters: `pattern: Type`, including
    /// `self: Box<Self>`, but not `self`/`&mut self`, variadic `...`, `_` or anonymous types (`fn(u8)`).
    fn parse_params(&mut self) -> u32 {
        let mut count = 0;
        let Some(saved) = self.open_group() else { return 0 };
        while !self.at_end() {
            self.parse_outer_attrs();
            if self.at_end() {
                break;
            }
            let before = self.pos;
            // self parameter: `&'a mut self`, `mut self`, `self`
            let mut j = self.pos;
            let mut amp = false;
            if self.punct_at(j, b'&') {
                amp = true;
                j += 1;
                if self.is_kind(j, TokKind::Lifetime) {
                    j += 1;
                }
            }
            if self.kw_at(j, "mut") {
                j += 1;
            }
            if self.kw_at(j, "self") && !self.op_at(j + 1, b"::") {
                if amp && self.is_kind(self.pos + 1, TokKind::Lifetime) {
                    self.leaf(NodeKind::Ident, self.pos + 1);
                }
                self.pos = j + 1;
                if !amp && self.colon_at(self.pos) {
                    self.bump();
                    self.parse_type(true);
                    count += 1;
                }
            } else if self.op(b"...") {
                self.bump_n(3);
            } else if self.kw("_") && (self.punct_at(self.pos + 1, b',') || self.pos + 1 >= self.limit) {
                self.bump();
            } else {
                let cp = self.checkpoint();
                self.eat_kw("mut");
                self.parse_pattern_no_alt();
                if self.colon_at(self.pos) {
                    self.bump();
                    if self.op(b"...") {
                        self.bump_n(3);
                    } else {
                        self.parse_type(true);
                        count += 1;
                    }
                } else {
                    self.restore(cp);
                    self.parse_type(true);
                }
            }
            if self.pos == before {
                self.err();
                break;
            }
            if !self.eat_punct(b',') {
                break;
            }
        }
        self.close_group(saved);
        count
    }

    // ---------------------------------------------------------- use trees

    /// A use tree: `a::b`, `a::b as c`, `a::{…}`, `a::*`, `{…}`, `*`.
    fn parse_use_tree(&mut self) {
        let start = self.pos;
        if self.punct(b'{') {
            let n = self.open(NodeKind::UseGroup);
            self.parse_use_list();
            self.close(n);
            return;
        }
        if self.punct(b'*') {
            self.leaf(NodeKind::UseGlob, self.pos);
            self.bump();
            return;
        }
        let mut path_end = self.pos;
        if self.op(b"::") {
            self.bump_n(2);
        }
        while self.is_ident(self.pos) {
            self.bump();
            path_end = self.pos;
            if self.op(b"::") && self.is_ident(self.pos + 2) {
                self.bump_n(2);
            } else {
                break;
            }
        }
        let has_path = path_end > start;
        if self.op(b"::") && self.punct_at(self.pos + 2, b'{') || (!has_path && self.punct(b'{')) {
            let n = self.open_at(NodeKind::UseGroup, start);
            if has_path {
                self.leaf_range(NodeKind::UsePrefix, start, path_end - 1);
            }
            if self.op(b"::") {
                self.bump_n(2);
            }
            self.parse_use_list();
            self.close(n);
        } else if self.op(b"::") && self.punct_at(self.pos + 2, b'*') || (!has_path && self.punct(b'*')) {
            let n = self.open_at(NodeKind::UseGlob, start);
            if has_path {
                self.leaf_range(NodeKind::UsePrefix, start, path_end - 1);
            }
            if self.op(b"::") {
                self.bump_n(2);
            }
            self.bump();
            self.close(n);
        } else if !has_path {
            self.err();
        } else if self.kw("as") {
            let n = self.open_at(NodeKind::UseAs, start);
            self.leaf_range(NodeKind::UsePath, start, path_end - 1);
            self.bump();
            if self.is_ident(self.pos) {
                self.nodes[n].name = self.pos as u32;
                self.bump();
            } else {
                self.err();
            }
            self.close(n);
        } else {
            self.leaf_range(NodeKind::UsePath, start, path_end - 1);
        }
    }

    /// `{ tree, … }`. Nested groups count towards [`MAX_DEPTH`] like any other recursion.
    fn parse_use_list(&mut self) {
        let Some(saved) = self.open_group() else { return };
        if self.enter() {
            while !self.at_end() {
                let before = self.pos;
                self.parse_use_tree();
                if self.pos == before || !self.eat_punct(b',') {
                    break;
                }
            }
            self.leave();
        }
        self.close_group(saved);
    }

    // ---------------------------------------------------------- generics

    fn parse_generic_params_opt(&mut self) {
        if self.punct(b'<') {
            self.parse_generic_params();
        }
    }

    /// `<'a: 'b, T: Bound = Default, const N: usize = 3>`. Type parameter names are `type_identifier`s.
    fn parse_generic_params(&mut self) {
        self.bump();
        while !self.at_end() && !self.punct(b'>') {
            let before = self.pos;
            self.parse_outer_attrs();
            if self.is_kind(self.pos, TokKind::Lifetime) {
                self.leaf(NodeKind::Ident, self.pos);
                self.bump();
                if self.colon_at(self.pos) {
                    self.bump();
                    self.parse_bounds_opt();
                }
            } else if self.kw("const") && self.is_ident(self.pos + 1) {
                self.bump();
                self.leaf(NodeKind::Ident, self.pos);
                self.bump();
                if self.colon_at(self.pos) {
                    self.bump();
                    self.parse_type(true);
                }
                if self.eat_punct(b'=') {
                    if self.punct(b'{') {
                        self.parse_block();
                    } else if self.is_ident(self.pos) {
                        self.leaf(NodeKind::Ident, self.pos);
                        self.bump();
                    } else {
                        self.eat_punct(b'-');
                        if self.is_kind(self.pos, TokKind::Literal) {
                            self.bump();
                        } else {
                            self.err();
                        }
                    }
                }
            } else if self.is_ident(self.pos) {
                self.leaf(NodeKind::TypeIdent, self.pos);
                self.bump();
                if self.colon_at(self.pos) {
                    self.bump();
                    self.parse_bounds_opt();
                }
                if self.eat_punct(b'=') {
                    self.parse_type(true);
                }
            } else {
                self.err();
                break;
            }
            if self.pos == before || !self.eat_punct(b',') {
                break;
            }
        }
        self.expect_punct(b'>');
    }

    /// Bounds after `:` (`'a + Clone + ?Sized + for<'b> Fn(&'b T)`), possibly empty.
    fn parse_bounds_opt(&mut self) {
        if self.can_start_type(self.pos) {
            self.parse_type(true);
        }
    }

    /// `where T: A + B, 'a: 'b, for<'c> &'c T: C` (ends before `{`, `;` or `=`).
    fn parse_where_opt(&mut self) {
        if !self.eat_kw("where") {
            return;
        }
        while !self.at_end() {
            let before = self.pos;
            if self.is_kind(self.pos, TokKind::Lifetime) {
                self.leaf(NodeKind::Ident, self.pos);
                self.bump();
            } else if self.can_start_type(self.pos) {
                if self.kw("for") && self.punct_at(self.pos + 1, b'<') {
                    self.bump();
                    self.parse_generic_params();
                }
                self.parse_type(false);
            } else {
                break;
            }
            if self.colon_at(self.pos) {
                self.bump();
                self.parse_bounds_opt();
            } else {
                self.err();
            }
            if self.pos == before || !self.eat_punct(b',') {
                break;
            }
        }
    }

    /// Generic arguments `<T, 'a, 3, {N}, Item = U, Item: Bound>` (at the `<`).
    fn parse_generic_args(&mut self) {
        self.bump();
        while !self.at_end() && !self.punct(b'>') {
            let before = self.pos;
            if self.is_kind(self.pos, TokKind::Lifetime) {
                self.leaf(NodeKind::Ident, self.pos);
                self.bump();
            } else if self.is_kind(self.pos, TokKind::Literal) || self.kw("true") || self.kw("false") {
                self.bump();
            } else if self.punct(b'-') && self.is_kind(self.pos + 1, TokKind::Literal) {
                self.bump_n(2);
            } else if self.punct(b'{') {
                self.parse_block();
            } else {
                self.parse_type(true);
                if self.punct(b'=') && !self.op(b"==") {
                    self.bump();
                    self.parse_type(true);
                }
            }
            if self.colon_at(self.pos) {
                self.bump();
                self.parse_bounds_opt();
            }
            if self.pos == before || !self.eat_punct(b',') {
                break;
            }
        }
        self.expect_punct(b'>');
    }

    // ---------------------------------------------------------- paths

    /// Parses a path (`a::b::C`, `::a`, `<T as Tr>::X`, `Vec::<T>::new`, `Vec<T>` in types) up to and including
    /// trailing generic arguments. Nodes for generic arguments and the `<…>` prefix are emitted; the path's own
    /// node is emitted by [`Parser::emit_path`] once the caller knows what follows.
    fn parse_path(&mut self, ctx: PathCtx) -> PathInfo {
        let mut p = PathInfo {
            start: self.pos,
            name_end: self.pos,
            mark: self.nodes.len(),
            tail_mark: 0,
            segs: 0,
            bracket: false,
            leading: false,
            mid: false,
            tail_args: false,
        };
        if self.punct(b'<') {
            p.bracket = true;
            self.bump();
            self.parse_type(true);
            if self.eat_kw("as") {
                self.parse_type(true);
            }
            self.expect_punct(b'>');
            if self.op(b"::") {
                self.bump_n(2);
            } else {
                self.err();
            }
        } else if self.op(b"::") {
            p.leading = true;
            self.bump_n(2);
        }
        loop {
            if !self.is_ident(self.pos) {
                self.err();
                break;
            }
            self.bump();
            p.segs += 1;
            p.name_end = self.pos;
            let args_mark = self.nodes.len();
            let mut args = false;
            if ctx == PathCtx::Type && self.punct(b'<') && !self.op(b"<=") {
                self.parse_generic_args();
                args = true;
            } else if self.op(b"::") && self.punct_at(self.pos + 2, b'<') {
                self.bump_n(2);
                self.parse_generic_args();
                args = true;
            }
            if args {
                if self.op(b"::") && self.is_ident(self.pos + 2) {
                    p.mid = true;
                    self.bump_n(2);
                    continue;
                }
                p.tail_args = true;
                p.tail_mark = args_mark;
                break;
            }
            if self.op(b"::") && self.is_ident(self.pos + 2) {
                self.bump_n(2);
                continue;
            }
            break;
        }
        if !p.tail_args {
            p.tail_mark = self.nodes.len();
        }
        p
    }

    /// Emits the node for a parsed path (see "Reference leaves" in the module docs). `single`: the leaf kind for a
    /// one-segment path, or `None` for no node. `self`, `super` and `crate` alone are never identifiers.
    fn emit_path(&mut self, p: &PathInfo, single: Option<NodeKind>) {
        if p.segs == 0 {
            return;
        }
        if p.bracket && p.segs == 1 && !p.mid {
            return; // the types in the brackets were emitted already
        }
        if p.bracket || p.mid {
            self.wrap(p.mark, p.tail_mark, NodeKind::Path, p.start, p.name_end - 1);
        } else if p.single() {
            let t = self.text(p.start);
            if let Some(k) = single {
                if !matches!(t, "self" | "super" | "crate") {
                    self.leaf(k, p.start);
                }
            }
        } else {
            self.leaf_range(NodeKind::Path, p.start, p.name_end - 1);
        }
    }

    // ---------------------------------------------------------- types

    fn can_start_type(&self, i: usize) -> bool {
        if i >= self.limit {
            return false;
        }
        match self.toks[i].kind {
            TokKind::Ident => !matches!(self.text(i), "where" | "as" | "in" | "else"),
            TokKind::Lifetime => true,
            TokKind::Literal => false,
            TokKind::Punct => {
                matches!(self.code[self.toks[i].start as usize], b'(' | b'[' | b'&' | b'*' | b'!' | b'?' | b'<')
                    || self.op_at(i, b"::")
            }
        }
    }

    /// Parses a type. `plus`: `A + B` bound lists are allowed (not after `as` or inside `&`/`*`). Returns the first
    /// and last token of the type's "core" as the analyser's impl-type name: `&'a mut T` → core of `T`, `a::B<T>` →
    /// `a::B`, anything else → the whole type.
    fn parse_type(&mut self, plus: bool) -> (usize, usize) {
        let start = self.pos;
        if !self.enter() {
            return (start, start);
        }
        let mut core = (start, start);
        let mut whole = true;
        if self.at_end() {
            self.err();
        } else if self.punct(b'(') || self.punct(b'[') {
            let array = self.punct(b'[');
            if let Some(saved) = self.open_group() {
                while !self.at_end() {
                    let before = self.pos;
                    self.parse_type(true);
                    if array && self.eat_punct(b';') {
                        self.parse_expr(false);
                    }
                    if self.pos == before || !self.eat_punct(b',') {
                        break;
                    }
                }
                self.close_group(saved);
            } else {
                self.bump();
            }
        } else if self.punct(b'&') || self.punct(b'*') {
            let star = self.punct(b'*');
            self.bump();
            if !star && self.is_kind(self.pos, TokKind::Lifetime) {
                self.leaf(NodeKind::Ident, self.pos);
                self.bump();
            }
            if !self.eat_kw("mut") && star {
                self.eat_kw("const");
            }
            core = self.parse_type(false);
            whole = false;
        } else if self.punct(b'!') {
            self.bump();
        } else if self.punct(b'?') {
            self.bump();
            self.parse_type(false);
        } else if self.is_kind(self.pos, TokKind::Lifetime) {
            self.leaf(NodeKind::Ident, self.pos);
            self.bump();
        } else if self.kw("impl") || self.kw("dyn") {
            self.bump();
            self.parse_type(plus);
        } else if self.kw("for") && self.punct_at(self.pos + 1, b'<') {
            self.bump();
            self.parse_generic_params();
            self.parse_type(false);
        } else if self.kw("fn") || self.kw("unsafe") || self.kw("extern") {
            self.eat_kw("unsafe");
            if self.eat_kw("extern") && self.is_kind(self.pos, TokKind::Literal) {
                self.bump();
            }
            if self.eat_kw("fn") && self.punct(b'(') {
                self.parse_params();
                if self.op(b"->") {
                    self.bump_n(2);
                    self.parse_type(false);
                }
            } else {
                self.err();
            }
        } else if self.kw("use") && self.punct_at(self.pos + 1, b'<') {
            // precise capturing `use<'a, T>`
            self.bump();
            self.parse_generic_args();
        } else if self.is_ident(self.pos) || self.op(b"::") || self.punct(b'<') {
            let p = self.parse_path(PathCtx::Type);
            let single_kind = if p.single() && !p.tail_args && PRIMITIVES.contains(&self.text(p.start)) {
                None
            } else {
                Some(NodeKind::TypeIdent)
            };
            if !p.tail_args && self.punct(b'!') && !self.op(b"!=") && self.is_opener(self.pos + 1) {
                self.emit_path(&p, Some(NodeKind::Ident));
                self.bump();
                self.token_tree(false);
            } else if !p.tail_args && self.punct(b'(') {
                // `Fn(A) -> B` sugar: a function type with the path as its trait
                self.emit_path(&p, single_kind);
                self.parse_params();
                if self.op(b"->") {
                    self.bump_n(2);
                    self.parse_type(false);
                }
            } else {
                self.emit_path(&p, single_kind);
                core = (p.start, p.name_end.saturating_sub(1).max(p.start));
                whole = false;
            }
        } else {
            self.err();
        }
        if plus && self.punct(b'+') && !self.op(b"+=") {
            while self.punct(b'+') && !self.op(b"+=") {
                self.bump();
                if self.can_start_type(self.pos) {
                    self.parse_type(false);
                }
            }
            whole = true;
        }
        if whole {
            core = (start, self.pos.saturating_sub(1).max(start));
        }
        self.leave();
        core
    }

    // ---------------------------------------------------------- patterns

    /// A pattern with alternatives (`A | B`, also with a leading `|`).
    fn parse_pattern(&mut self) {
        if self.punct(b'|') && !self.op(b"||") {
            self.bump();
        }
        self.parse_pattern_no_alt();
        while self.punct(b'|') && !self.op(b"||") && !self.op(b"|=") {
            self.bump();
            self.parse_pattern_no_alt();
        }
    }

    /// One pattern without top-level alternatives (closure parameters cannot have them).
    fn parse_pattern_no_alt(&mut self) {
        if !self.enter() {
            return;
        }
        if self.at_end() {
            self.err();
        } else if self.is_kind(self.pos, TokKind::Literal) {
            self.bump();
            self.parse_range_pattern_tail();
        } else if self.punct(b'-') && self.is_kind(self.pos + 1, TokKind::Literal) {
            self.bump_n(2);
            self.parse_range_pattern_tail();
        } else if self.punct(b'&') {
            self.bump();
            self.eat_kw("mut");
            self.parse_pattern_no_alt();
        } else if self.punct(b'(') || self.punct(b'[') {
            self.parse_pattern_list();
        } else if self.op(b"..") {
            if self.op(b"..=") || self.op(b"...") {
                self.bump_n(3);
                self.parse_range_bound();
            } else {
                self.bump_n(2);
                if self.can_start_range_bound() {
                    self.parse_range_bound();
                }
            }
        } else if self.kw("_") {
            self.bump();
        } else if self.kw("ref") || self.kw("mut") {
            self.bump();
            self.eat_kw("mut");
            self.parse_pattern_no_alt();
        } else if self.kw("const") && self.punct_at(self.pos + 1, b'{') {
            self.bump();
            self.parse_block();
        } else if self.kw("true") || self.kw("false") {
            self.bump();
        } else if self.is_ident(self.pos) || self.op(b"::") || self.punct(b'<') {
            let p = self.parse_path(PathCtx::Pattern);
            if !p.tail_args && self.punct(b'!') && !self.op(b"!=") && self.is_opener(self.pos + 1) {
                self.emit_path(&p, Some(NodeKind::Ident));
                self.bump();
                self.token_tree(false);
            } else if self.punct(b'(') {
                let k = if p.tail_args { NodeKind::TypeIdent } else { NodeKind::Ident };
                self.emit_path(&p, Some(k));
                self.parse_pattern_list();
            } else if self.punct(b'{') {
                self.emit_path(&p, Some(NodeKind::TypeIdent));
                self.parse_struct_pattern_fields();
            } else if self.punct(b'@') && p.single() {
                self.emit_path(&p, Some(NodeKind::Ident));
                self.bump();
                self.parse_pattern_no_alt();
            } else {
                self.emit_path(&p, Some(NodeKind::Ident));
                self.parse_range_pattern_tail();
            }
        } else {
            self.err();
        }
        self.leave();
    }

    /// `( p, … )` or `[ p, … ]`.
    fn parse_pattern_list(&mut self) {
        let Some(saved) = self.open_group() else {
            self.bump();
            return;
        };
        while !self.at_end() {
            let before = self.pos;
            self.parse_pattern();
            if self.pos == before || !self.eat_punct(b',') {
                break;
            }
        }
        self.close_group(saved);
    }

    /// `{ a: p, ref mut b, 0: c, .. }`; field names are not references.
    fn parse_struct_pattern_fields(&mut self) {
        let Some(saved) = self.open_group() else {
            self.bump();
            return;
        };
        while !self.at_end() {
            let before = self.pos;
            self.parse_outer_attrs();
            if self.op(b"..") {
                self.bump_n(2);
            } else {
                self.eat_kw("ref");
                self.eat_kw("mut");
                if (self.is_ident(self.pos) || self.is_kind(self.pos, TokKind::Literal)) && self.colon_at(self.pos + 1) {
                    self.bump_n(2);
                    self.parse_pattern();
                } else if self.is_ident(self.pos) {
                    self.bump();
                } else {
                    self.err();
                }
            }
            if self.pos == before || !self.eat_punct(b',') {
                break;
            }
        }
        self.close_group(saved);
    }

    /// After a literal or path in a pattern: an optional range `..= end`, `.. end` or `..`.
    fn parse_range_pattern_tail(&mut self) {
        if self.op(b"..=") || self.op(b"...") {
            self.bump_n(3);
            self.parse_range_bound();
        } else if self.op(b"..") {
            self.bump_n(2);
            if self.can_start_range_bound() {
                self.parse_range_bound();
            }
        }
    }

    fn can_start_range_bound(&self) -> bool {
        self.is_kind(self.pos, TokKind::Literal)
            || (self.punct(b'-') && self.is_kind(self.pos + 1, TokKind::Literal))
            || (self.is_ident(self.pos) && !self.kw("if"))
            || self.op(b"::")
            || self.punct(b'<')
    }

    fn parse_range_bound(&mut self) {
        if self.is_kind(self.pos, TokKind::Literal) {
            self.bump();
        } else if self.punct(b'-') && self.is_kind(self.pos + 1, TokKind::Literal) {
            self.bump_n(2);
        } else if self.is_ident(self.pos) || self.op(b"::") || self.punct(b'<') {
            let p = self.parse_path(PathCtx::Pattern);
            self.emit_path(&p, Some(NodeKind::Ident));
        } else {
            self.err();
        }
    }

    // ---------------------------------------------------------- token trees

    /// A macro's token tree (at its opening delimiter): every identifier and lifetime inside is an `Ident` leaf (macro
    /// arguments are not parsed). In a `macro_rules!` definition (`rules`), `$name` is a
    /// metavariable, not an identifier.
    fn token_tree(&mut self, rules: bool) {
        let p = self.toks[self.pos].pair;
        if p == NONE || p as usize >= self.limit {
            self.err();
            self.bump();
            return;
        }
        let close = p as usize;
        for i in self.pos + 1..close {
            match self.toks[i].kind {
                TokKind::Ident => {
                    let meta = rules && self.punct_at(i - 1, b'$') && self.toks[i - 1].end == self.toks[i].start;
                    if !meta {
                        self.leaf(NodeKind::Ident, i);
                    }
                }
                TokKind::Lifetime => self.leaf(NodeKind::Ident, i),
                _ => {}
            }
        }
        self.pos = close + 1;
    }

    // ---------------------------------------------------------- statements and blocks

    /// `{ statements }` at the current `{`.
    fn parse_block(&mut self) {
        if !self.punct(b'{') {
            self.err();
            return;
        }
        let Some(saved) = self.open_group() else {
            self.bump();
            return;
        };
        if self.enter() {
            while !self.at_end() {
                let before = self.pos;
                self.parse_stmt();
                if self.pos == before {
                    self.err();
                    self.skip_tree();
                }
            }
            self.leave();
        }
        self.close_group(saved);
    }

    fn parse_stmt(&mut self) {
        if self.eat_punct(b';') {
            return;
        }
        if self.at_attr() {
            self.parse_attr();
            return;
        }
        if let Some((kw, at)) = self.item_start(self.pos) {
            self.parse_item(kw, at);
            return;
        }
        self.parse_stmt_rest();
    }

    /// A `let` or expression statement (or `;`).
    fn parse_stmt_rest(&mut self) {
        if self.eat_punct(b';') {
            return;
        }
        if self.kw("let") {
            self.bump();
            self.parse_pattern();
            if self.colon_at(self.pos) {
                self.bump();
                self.parse_type(true);
            }
            if self.eat_punct(b'=') {
                self.parse_expr(false);
                if self.eat_kw("else") {
                    self.parse_block();
                }
            }
            self.expect_punct(b';');
            return;
        }
        let info = self.parse_expr_stmt(false);
        if !self.eat_punct(b';') && !self.at_end() && !info.block_like && !info.macro_call {
            self.err();
        }
    }

    /// An expression in statement position (also used for match arm bodies): a block-like expression (`if`, `match`,
    /// a loop, a block, a brace macro) ends there unless followed by `.` or `?`.
    fn parse_expr_stmt(&mut self, ns: bool) -> ExprInfo {
        let info = self.parse_prefix(ns, false);
        if info.block_like && !(self.punct(b'.') && !self.op(b"..")) && !self.punct(b'?') {
            return info;
        }
        self.parse_infix(info, 0, ns, false)
    }

    // ---------------------------------------------------------- expressions

    /// A full expression. `ns`: no struct literals (in `if`/`while`/`match`/`for` headers).
    fn parse_expr(&mut self, ns: bool) -> ExprInfo {
        self.parse_expr_bp(0, ns, false)
    }

    /// Pratt parser entry: an expression whose operators all bind at least as tightly as `min_bp`. `cond`: in an
    /// `if`/`while` condition or match guard, where `let` is allowed.
    fn parse_expr_bp(&mut self, min_bp: u8, ns: bool, cond: bool) -> ExprInfo {
        let lhs = self.parse_prefix(ns, cond);
        self.parse_infix(lhs, min_bp, ns, cond)
    }

    /// Can token `i` start an expression? Decides whether `return`, `break`, `..` have an operand.
    fn can_start_expr(&self, i: usize, ns: bool) -> bool {
        if i >= self.limit {
            return false;
        }
        match self.toks[i].kind {
            TokKind::Literal | TokKind::Lifetime => true,
            TokKind::Ident => !matches!(self.text(i), "as" | "else" | "in" | "where"),
            TokKind::Punct => match self.code[self.toks[i].start as usize] {
                b'(' | b'[' | b'!' | b'-' | b'*' | b'&' | b'|' | b'<' | b'#' => true,
                b'{' => !ns,
                b':' => self.op_at(i, b"::"),
                b'.' => self.op_at(i, b".."),
                _ => false,
            },
        }
    }

    /// The binary operator at the current position: (token count, left binding power, right binding power, is `&&`
    /// or `||`, is a range). Binding powers: assignment 1 (right-associative), range 2, `||` 3, `&&` 4,
    /// comparison 5, `|` 6, `^` 7, `&` 8, shift 9, additive 10, multiplicative 11 (`as` 12, unary 13, postfix 14).
    fn binop(&self) -> Option<(usize, u8, u8, bool, bool)> {
        if !self.is_kind(self.pos, TokKind::Punct) {
            return None;
        }
        let c = self.code[self.toks[self.pos].start as usize];
        let assign2 = |s: &[u8]| self.op(s);
        Some(match c {
            b'=' => {
                if self.op(b"==") {
                    (2, 5, 6, false, false)
                } else if self.op(b"=>") {
                    return None;
                } else {
                    (1, 1, 1, false, false)
                }
            }
            b'!' => {
                if self.op(b"!=") {
                    (2, 5, 6, false, false)
                } else {
                    return None;
                }
            }
            b'<' | b'>' => {
                let s: &[u8] = if c == b'<' { b"<<=" } else { b">>=" };
                if self.op(s) {
                    (3, 1, 1, false, false)
                } else if self.op(&s[..2]) {
                    (2, 9, 10, false, false)
                } else if self.op(if c == b'<' { b"<=" } else { b">=" }) {
                    (2, 5, 6, false, false)
                } else {
                    (1, 5, 6, false, false)
                }
            }
            b'&' => {
                if self.op(b"&&") {
                    (2, 4, 5, true, false)
                } else if assign2(b"&=") {
                    (2, 1, 1, false, false)
                } else {
                    (1, 8, 9, false, false)
                }
            }
            b'|' => {
                if self.op(b"||") {
                    (2, 3, 4, true, false)
                } else if assign2(b"|=") {
                    (2, 1, 1, false, false)
                } else {
                    (1, 6, 7, false, false)
                }
            }
            b'^' | b'+' | b'-' | b'*' | b'/' | b'%' => {
                if self.punct_at(self.pos + 1, b'=') && self.toks[self.pos].joint {
                    (2, 1, 1, false, false)
                } else if c == b'-' && self.op(b"->") {
                    return None;
                } else {
                    match c {
                        b'^' => (1, 7, 8, false, false),
                        b'+' | b'-' => (1, 10, 11, false, false),
                        _ => (1, 11, 12, false, false),
                    }
                }
            }
            b'.' => {
                if self.op(b"..=") || self.op(b"...") {
                    (3, 2, 3, false, true)
                } else if self.op(b"..") {
                    (2, 2, 3, false, true)
                } else {
                    return None;
                }
            }
            _ => return None,
        })
    }

    /// Postfix and binary operators after an operand (see [`Parser::binop`]).
    fn parse_infix(&mut self, mut info: ExprInfo, min_bp: u8, ns: bool, cond: bool) -> ExprInfo {
        loop {
            if self.at_end() {
                break;
            }
            if self.punct(b'?') {
                self.bump();
                info = ExprInfo::default();
                continue;
            }
            if self.punct(b'.') && !self.op(b"..") {
                self.bump();
                if self.kw("await") {
                    self.bump();
                } else if self.is_ident(self.pos) {
                    self.bump(); // field or method name: not a reference
                    if self.op(b"::") && self.punct_at(self.pos + 2, b'<') {
                        self.bump_n(2);
                        self.parse_generic_args();
                    }
                } else if self.is_kind(self.pos, TokKind::Literal) {
                    self.bump();
                } else {
                    self.err();
                }
                info = ExprInfo::default();
                continue;
            }
            if self.punct(b'(') || self.punct(b'[') {
                self.parse_expr_list();
                info = ExprInfo::default();
                continue;
            }
            if self.kw("as") {
                if 12 < min_bp {
                    break;
                }
                self.bump();
                self.parse_type(false);
                info = ExprInfo::default();
                continue;
            }
            let Some((len, lbp, rbp, logic, range)) = self.binop() else { break };
            if lbp < min_bp {
                break;
            }
            let op_tok = self.pos;
            self.bump_n(len);
            if range {
                if self.can_start_expr(self.pos, ns) {
                    self.parse_expr_bp(rbp, ns, false);
                }
                info = ExprInfo::default();
                continue;
            }
            let rhs = self.parse_expr_bp(rbp, ns, cond);
            let and = logic && self.code[self.toks[op_tok].start as usize] == b'&';
            if and && (info.is_let || rhs.is_let) {
                info = ExprInfo { is_let: true, ..ExprInfo::default() };
            } else {
                if logic {
                    self.leaf(NodeKind::AndOr, op_tok);
                }
                info = ExprInfo::default();
            }
        }
        info
    }

    /// `( … )` call arguments / tuple / parenthesised expression, or `[ … ]` index / array (`[x; n]`): comma
    /// separated expressions with optional attributes.
    fn parse_expr_list(&mut self) {
        let Some(saved) = self.open_group() else {
            self.bump();
            return;
        };
        while !self.at_end() {
            let before = self.pos;
            self.parse_outer_attrs();
            if self.at_end() {
                break;
            }
            self.parse_expr(false);
            if self.eat_punct(b';') {
                self.parse_expr(false);
            }
            if self.pos == before || !self.eat_punct(b',') {
                break;
            }
        }
        self.close_group(saved);
    }

    /// An operand: literals, paths, struct literals, macro calls, groups, blocks, control flow, closures, unary and
    /// prefix-range operators. Unary operators bind tighter than everything but postfix operators.
    fn parse_prefix(&mut self, ns: bool, cond: bool) -> ExprInfo {
        if !self.enter() {
            return ExprInfo::default();
        }
        let info = self.parse_prefix_inner(ns, cond);
        self.leave();
        info
    }

    fn parse_prefix_inner(&mut self, ns: bool, cond: bool) -> ExprInfo {
        let block = ExprInfo { block_like: true, ..ExprInfo::default() };
        let plain = ExprInfo::default();
        if self.at_end() {
            self.err();
            return plain;
        }
        let tok = self.toks[self.pos];
        match tok.kind {
            TokKind::Literal => {
                self.bump();
                plain
            }
            TokKind::Lifetime => {
                // label: `'a: loop { … }`
                if self.colon_at(self.pos + 1) {
                    self.leaf(NodeKind::Ident, self.pos);
                    self.bump_n(2);
                    if self.kw("loop") || self.kw("while") || self.kw("for") || self.punct(b'{') {
                        return self.parse_prefix_inner(ns, cond);
                    }
                }
                self.err();
                self.bump();
                plain
            }
            TokKind::Punct => {
                let c = self.code[tok.start as usize];
                match c {
                    b'(' | b'[' => {
                        self.parse_expr_list();
                        plain
                    }
                    b'{' => {
                        self.parse_block();
                        block
                    }
                    b'|' => self.parse_closure(ns),
                    b'-' | b'!' | b'*' => {
                        self.bump();
                        self.parse_expr_bp(13, ns, false);
                        plain
                    }
                    b'&' => {
                        self.bump();
                        if self.kw("raw") && (self.kw_at(self.pos + 1, "const") || self.kw_at(self.pos + 1, "mut")) {
                            self.bump_n(2);
                        } else {
                            self.eat_kw("mut");
                        }
                        self.parse_expr_bp(13, ns, false);
                        plain
                    }
                    b'.' if self.op(b"..") => {
                        let len = if self.op(b"..=") || self.op(b"...") { 3 } else { 2 };
                        self.bump_n(len);
                        if self.can_start_expr(self.pos, ns) {
                            self.parse_expr_bp(3, ns, false);
                        }
                        plain
                    }
                    b'<' | b':' if c == b'<' || self.op(b"::") => self.parse_path_expr(ns),
                    b'#' if self.at_attr() => {
                        self.parse_attr();
                        self.parse_prefix_inner(ns, cond)
                    }
                    _ => {
                        self.err();
                        plain
                    }
                }
            }
            TokKind::Ident => match self.text(self.pos) {
                "if" => {
                    self.parse_if();
                    block
                }
                "match" => {
                    self.parse_match();
                    block
                }
                "while" => {
                    self.leaf(NodeKind::While, self.pos);
                    self.bump();
                    self.parse_expr_bp(0, true, true);
                    self.parse_block();
                    block
                }
                "loop" => {
                    self.leaf(NodeKind::Loop, self.pos);
                    self.bump();
                    self.parse_block();
                    block
                }
                "for" => {
                    self.leaf(NodeKind::For, self.pos);
                    self.bump();
                    self.parse_pattern();
                    if !self.eat_kw("in") {
                        self.err();
                    }
                    self.parse_expr_bp(0, true, false);
                    self.parse_block();
                    block
                }
                "unsafe" | "const" | "try" if self.punct_at(self.pos + 1, b'{') => {
                    self.bump();
                    self.parse_block();
                    block
                }
                "async" | "gen" if self.punct_at(self.pos + 1, b'{') || (self.kw_at(self.pos + 1, "move") && self.punct_at(self.pos + 2, b'{')) => {
                    self.bump();
                    self.eat_kw("move");
                    self.parse_block();
                    block
                }
                "async" | "move" | "static"
                    if self.punct_at(self.pos + 1, b'|') || self.kw_at(self.pos + 1, "move") =>
                {
                    self.parse_closure(ns)
                }
                "return" | "yield" | "become" => {
                    self.bump();
                    if self.can_start_expr(self.pos, ns) {
                        self.parse_expr_bp(1, ns, false);
                    }
                    plain
                }
                "break" => {
                    self.bump();
                    if self.is_kind(self.pos, TokKind::Lifetime) {
                        self.leaf(NodeKind::Ident, self.pos);
                        self.bump();
                    }
                    if self.can_start_expr(self.pos, ns) {
                        self.parse_expr_bp(1, ns, false);
                    }
                    plain
                }
                "continue" => {
                    self.bump();
                    if self.is_kind(self.pos, TokKind::Lifetime) {
                        self.leaf(NodeKind::Ident, self.pos);
                        self.bump();
                    }
                    plain
                }
                "let" => {
                    // `let` condition (only valid in `if`/`while` conditions and match guards)
                    if !cond {
                        self.err();
                    }
                    self.bump();
                    self.parse_pattern();
                    self.expect_punct(b'=');
                    self.parse_expr_bp(5, true, false);
                    ExprInfo { is_let: true, ..ExprInfo::default() }
                }
                "true" | "false" => {
                    self.bump();
                    plain
                }
                _ => self.parse_path_expr(ns),
            },
        }
    }

    /// A path in expression position, possibly a macro call or struct literal.
    fn parse_path_expr(&mut self, ns: bool) -> ExprInfo {
        let p = self.parse_path(PathCtx::Expr);
        if !p.tail_args && self.punct(b'!') && !self.op(b"!=") && self.is_opener(self.pos + 1) {
            self.emit_path(&p, Some(NodeKind::Ident));
            self.bump();
            let brace = self.punct(b'{');
            self.token_tree(false);
            return ExprInfo { block_like: brace, macro_call: true, is_let: false };
        }
        if self.punct(b'{') && !ns {
            // struct literal: the name is a type_identifier
            self.emit_path(&p, Some(NodeKind::TypeIdent));
            self.parse_struct_literal_fields();
            return ExprInfo::default();
        }
        self.emit_path(&p, Some(NodeKind::Ident));
        ExprInfo::default()
    }

    /// `{ a: x, b, 0: y, ..base }`: field names are not references, shorthand fields are identifiers.
    fn parse_struct_literal_fields(&mut self) {
        let Some(saved) = self.open_group() else {
            self.bump();
            return;
        };
        while !self.at_end() {
            let before = self.pos;
            self.parse_outer_attrs();
            if self.op(b"..") {
                self.bump_n(2);
                if !self.at_end() && !self.punct(b',') {
                    self.parse_expr(false);
                }
            } else if (self.is_ident(self.pos) || self.is_kind(self.pos, TokKind::Literal)) && self.colon_at(self.pos + 1) {
                self.bump_n(2);
                self.parse_expr(false);
            } else if self.is_ident(self.pos) {
                self.leaf(NodeKind::Ident, self.pos);
                self.bump();
            } else {
                self.err();
            }
            if self.pos == before || !self.eat_punct(b',') {
                break;
            }
        }
        self.close_group(saved);
    }

    /// `if cond { … } else if cond { … } else { … }`, iteratively so that long `else if` chains need no recursion.
    fn parse_if(&mut self) {
        loop {
            self.leaf(NodeKind::If, self.pos);
            self.bump();
            self.parse_expr_bp(0, true, true);
            self.parse_block();
            if self.eat_kw("else") {
                if self.kw("if") {
                    continue;
                }
                self.parse_block();
            }
            break;
        }
    }

    /// `match x { attrs* pat (if guard)? => body,? … }`.
    fn parse_match(&mut self) {
        self.leaf(NodeKind::Match, self.pos);
        self.bump();
        self.parse_expr_bp(0, true, false);
        if !self.punct(b'{') {
            self.err();
            return;
        }
        let Some(saved) = self.open_group() else {
            self.bump();
            return;
        };
        while !self.at_end() {
            let before = self.pos;
            while self.at_attr() {
                self.parse_attr();
            }
            if self.at_end() {
                break;
            }
            self.leaf(NodeKind::Arm, self.pos);
            self.parse_pattern();
            if self.eat_kw("if") {
                self.parse_expr_bp(0, false, true);
            }
            if self.op(b"=>") {
                self.bump_n(2);
            } else {
                self.err();
            }
            let body = self.parse_expr_stmt(false);
            if !self.eat_punct(b',') && !self.at_end() && !body.block_like && !body.macro_call {
                self.err();
            }
            if self.pos == before {
                self.err();
                self.skip_tree();
            }
        }
        self.close_group(saved);
    }

    /// `static? async? move? |params| body` or `… || -> T { … }`.
    fn parse_closure(&mut self, ns: bool) -> ExprInfo {
        self.eat_kw("static");
        self.eat_kw("async");
        self.eat_kw("move");
        if !self.eat_punct(b'|') {
            self.err();
            return ExprInfo::default();
        }
        while !self.at_end() && !self.punct(b'|') {
            let before = self.pos;
            self.parse_outer_attrs();
            self.parse_pattern_no_alt();
            if self.colon_at(self.pos) {
                self.bump();
                self.parse_type(false);
            }
            if self.pos == before || !self.eat_punct(b',') {
                break;
            }
        }
        self.expect_punct(b'|');
        if self.op(b"->") {
            self.bump_n(2);
            self.parse_type(false);
            self.parse_block();
        } else {
            self.parse_expr_bp(0, ns, false);
        }
        ExprInfo::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// (kind, text) of every reference leaf, in node order.
    fn leaves(code: &str) -> Vec<(NodeKind, String)> {
        let t = parse(code);
        assert!(!t.errors, "unexpected parse error in {code}");
        t.nodes
            .iter()
            .filter(|n| matches!(n.kind, NodeKind::TypeIdent | NodeKind::Ident | NodeKind::Path))
            .map(|n| (n.kind, t.range_text(code, n.first, n.last).to_string()))
            .collect()
    }

    fn count(code: &str, kind: NodeKind) -> usize {
        parse(code).nodes.iter().filter(|n| n.kind == kind).count()
    }

    #[test]
    fn lexes_raw_strings_lifetimes_chars_and_nested_comments() {
        let code = "r#\"a \" b\"# 'a 'b' '\\'' b'x' /* x /* y */ z */ r#type 1.0 1..2 x.0";
        let (toks, err) = lex(code);
        assert!(!err);
        let kinds: Vec<TokKind> = toks.iter().map(|t| t.kind).collect();
        use TokKind::*;
        assert_eq!(kinds, vec![Literal, Lifetime, Literal, Literal, Literal, Ident, Literal, Literal, Punct, Punct, Literal, Ident, Punct, Literal]);
        assert!(toks[5].comment_before);
    }

    #[test]
    fn classifies_names_by_position() {
        use NodeKind::*;
        let l = leaves("fn f(x: a::B<C>) { let y = Foo { z }; Vec::<T>::new(); bar::<U>(); <T as Tr>::f(); x.Method(); }");
        let s: Vec<(NodeKind, &str)> = l.iter().map(|(k, t)| (*k, t.as_str())).collect();
        assert_eq!(s, vec![(Ident, "f"), (Ident, "x"), (TypeIdent, "C"), (Path, "a::B"), (Ident, "y"), (TypeIdent, "Foo"), (Ident, "z"), (Path, "Vec::<T>::new"), (TypeIdent, "T"), (TypeIdent, "U"), (Ident, "bar"), (TypeIdent, "T"), (TypeIdent, "Tr"), (Ident, "x")]);
    }

    #[test]
    fn macro_arguments_are_identifiers_except_metavariables_in_rules() {
        let l = leaves("fn f() { m!(Foo::bar, $Baz); macro_rules! r { ($T:ty) => { impl X for $T {} } } }");
        let names: Vec<&str> = l.iter().map(|(_, t)| t.as_str()).collect();
        assert_eq!(names, vec!["f", "m", "Foo", "bar", "Baz", "r", "ty", "impl", "X", "for"]);
    }

    #[test]
    fn counts_decision_points_but_not_let_chains_guards_or_macros() {
        let code = "fn f() { if a && b {} else if let Some(x) = y && z {} match q { A if c => 1, _ => 2 } while x || y {} for i in 0.. {} loop {} assert!(a && b); }";
        assert_eq!(count(code, NodeKind::If), 2);
        assert_eq!(count(code, NodeKind::AndOr), 2);
        assert_eq!(count(code, NodeKind::Arm), 2);
        assert_eq!(count(code, NodeKind::For), 1);
    }

    #[test]
    fn struct_literals_are_not_allowed_in_conditions() {
        let t = parse("fn f() { if x == foo { bar } }");
        assert!(!t.errors);
        assert_eq!(count("fn f() { if x == foo { bar } }", NodeKind::TypeIdent), 0);
    }

    #[test]
    fn block_like_statements_end_unless_a_method_follows() {
        assert!(!parse("fn f() { if a {} *b = 1; match x {}.foo(); m!{} m!(x) fn g() {} }").errors);
    }

    #[test]
    fn counts_fields_variants_and_parameters() {
        let code = "struct S(pub A, #[x] B); enum E { A, B(u8), C { x: u8 } } trait T { fn f(&self, self: Box<Self>, u32, _, a: u8); }";
        let t = parse(code);
        assert!(!t.errors);
        let v: Vec<(NodeKind, u32)> = t.nodes.iter().filter(|n| matches!(n.kind, NodeKind::Struct | NodeKind::Enum | NodeKind::Fn)).map(|n| (n.kind, n.value)).collect();
        assert_eq!(v, vec![(NodeKind::Struct, 2), (NodeKind::Enum, 3), (NodeKind::Fn, 2)]);
    }

    #[test]
    fn generics_close_with_shift_tokens() {
        assert!(!parse("fn f() -> Vec<Vec<u8>> { let x: Vec<u8>= v; a >> b }").errors);
    }

    #[test]
    fn impl_headers_keep_the_core_type() {
        let code = "impl<'a, T> !Send for &'a mut S<T> {}";
        let t = parse(code);
        let ty = t.nodes.iter().find(|n| n.kind == NodeKind::ImplType).unwrap();
        assert_eq!(t.range_text(code, ty.name, ty.value), "S");
        let tr = t.nodes.iter().find(|n| n.kind == NodeKind::ImplTrait).unwrap();
        assert_eq!(t.range_text(code, tr.name, tr.value), "Send");
    }

    #[test]
    fn broken_input_is_flagged_and_does_not_panic() {
        for code in ["fn f( {", "struct", "impl<", "}}}", "fn f() { let x = ; }", "'", "r#\"", "/* x", "#!["] {
            assert!(parse(code).errors, "{code}");
        }
        let deep = "(".repeat(10_000) + &")".repeat(10_000);
        assert!(parse(&format!("fn f() {{ {deep} }}")).errors);
        let deep_mods = "mod a { ".repeat(5_000) + &"}".repeat(5_000);
        assert!(parse(&deep_mods).errors);
    }

    #[test]
    fn deeply_nested_use_groups_are_capped() {
        let deep = format!("use {}{};", "{".repeat(200_000), "}".repeat(200_000));
        let t = parse(&format!("{deep} fn after() {{}}"));
        assert!(t.errors);
        // the item after the over-deep use is still parsed
        assert_eq!(t.nodes.iter().filter(|n| n.kind == NodeKind::Fn).count(), 1);
        let ok = format!("use {}a{};", "{".repeat(MAX_DEPTH as usize - 1), "}".repeat(MAX_DEPTH as usize - 1));
        assert!(!parse(&ok).errors);
    }

    #[test]
    fn use_trees_keep_their_structure() {
        let code = "use a::{self, b::C as D, e::*, {f}}; use ::g::h; use i::*;";
        let t = parse(code);
        assert!(!t.errors);
        let kinds: Vec<NodeKind> = t.nodes.iter().map(|n| n.kind).collect();
        use NodeKind::*;
        assert_eq!(kinds, vec![File, Use, UseGroup, UsePrefix, UsePath, UseAs, UsePath, UseGlob, UsePrefix, UseGroup, UsePath, Use, UsePath, Use, UseGlob, UsePrefix]);
    }

    #[test]
    fn comments_between_items_are_recorded() {
        let t = parse("#[cfg(test)]\n// note\nmod tests {}\n");
        let kinds: Vec<NodeKind> = t.nodes.iter().map(|n| n.kind).collect();
        assert_eq!(&kinds[..5], &[NodeKind::File, NodeKind::Attr, NodeKind::Ident, NodeKind::Ident, NodeKind::Comment]);
    }

    #[test]
    fn literals_that_look_like_code_are_skipped() {
        let code = r##"fn f() { let s = r#"} fn g() { "#; let c = '{'; let b = b"}"; /* } /* { */ } */ let l: &'static str = "\"}"; }"##;
        let t = parse(code);
        assert!(!t.errors);
        assert_eq!(t.nodes.iter().filter(|n| n.kind == NodeKind::Fn).count(), 1);
    }

    #[test]
    fn closures_ranges_and_casts_parse() {
        for code in [
            "fn f() { let a = |x: u8, (y, z)| -> u8 { x }; let b = move || a; v.iter().map(|&x| x as u32 + 1).sum::<u32>(); }",
            "fn f() { for i in 0..n {} let r = ..=5; let s = &x[1..]; if let 1..=9 | 20.. = n {} }",
            "fn f() { 'outer: loop { break 'outer; } let x = 'a: { break 'a 1 }; async move { x.await? }; }",
            "fn f() -> impl Fn(&str) -> Box<dyn Error + Send + 'static> { |_| todo!() }",
            "fn f<T: ?Sized + for<'a> Fn(&'a T)>(x: &T) where T: Iterator<Item = u8> {}",
            "fn f() { let Some(x) = y else { return }; let S { a, b: [c, ..], .. } = s; }",
            "const _: () = { impl X for Y {} }; static mut S: [u8; 4 * N] = [0; 4 * N];",
            "extern \"C\" { fn printf(f: *const c_char, ...) -> c_int; static errno: c_int; }",
        ] {
            let t = parse(code);
            assert!(!t.errors, "{code}: error at token {}", t.first_error);
        }
    }

    /// Tiny deterministic PRNG (xorshift) so the fuzz test is reproducible without a crate.
    fn next(state: &mut u64) -> u64 {
        *state ^= *state << 13;
        *state ^= *state >> 7;
        *state ^= *state << 17;
        *state
    }

    /// Mutates real source files (random deletions, insertions of syntax-relevant characters, truncations) and
    /// checks that the parser and the analyser neither panic nor loop, and that the tree stays well-formed.
    #[test]
    fn survives_mutated_sources() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src/analysis");
        let pieces = ["{", "}", "(", ")", "[", "]", "<", ">", "'", "\"", "#", "r#", "/*", "*/", "//", "::", "|", "&&", "$", "é", "\n", "fn ", "impl ", "match ", "if "];
        let mut state = 0x9E37_79B9_7F4A_7C15u64;
        for name in ["rust_parse.rs", "rust_lang.rs", "text.rs"] {
            let Ok(code) = std::fs::read_to_string(dir.join(name)) else { continue };
            let chars: Vec<char> = code.chars().collect();
            for _ in 0..60 {
                let mut c = chars.clone();
                for _ in 0..1 + next(&mut state) % 8 {
                    if c.is_empty() {
                        break;
                    }
                    let at = (next(&mut state) % c.len() as u64) as usize;
                    match next(&mut state) % 3 {
                        0 => {
                            let len = (1 + next(&mut state) % 20) as usize;
                            c.drain(at..(at + len).min(c.len()));
                        }
                        1 => {
                            let p = pieces[(next(&mut state) % pieces.len() as u64) as usize];
                            for (k, ch) in p.chars().enumerate() {
                                c.insert(at + k, ch);
                            }
                        }
                        _ => c.truncate(at),
                    }
                }
                let mutated: String = c.into_iter().collect();
                let t = parse(&mutated);
                for (i, n) in t.nodes.iter().enumerate() {
                    assert!(n.end as usize > i && n.end as usize <= t.nodes.len());
                    assert!(n.first <= n.last || t.tokens.is_empty());
                }
                crate::analysis::rust_lang::parse(&dir, &dir.join(name), &mutated);
            }
        }
    }

    /// Diagnostic helper: `PARSE_FILE=path cargo test show_first_error -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn show_first_error() {
        let path = std::env::var("PARSE_FILE").unwrap();
        let code = std::fs::read_to_string(&path).unwrap();
        let t = parse(&code);
        if t.first_error == NONE {
            println!("no errors");
        } else if let Some(tok) = t.tokens.get(t.first_error as usize) {
            println!("first error at line {}: {:?}", tok.line, code.lines().nth(tok.line as usize - 1));
        } else {
            println!("error at end of file");
        }
    }
}
