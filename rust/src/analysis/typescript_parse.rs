//! A dependency-free lexer and error-tolerant parser for TypeScript and TSX, producing the small syntax tree the
//! TypeScript analyser ([`super::typescript`]) works on.
//!
//! It extracts everything the analyser looks at: which declarations a file has, their names and line spans, class and
//! interface members, parameters, decision points and the identifiers that may refer to other declarations. It is not a
//! validator: it accepts a superset of TypeScript and never fails, it only notes whether it had to recover from a
//! syntax error ([`Tree::has_error`]). It replaced tree-sitter-typescript 0.23 and reproduces that grammar's results
//! for all of the above, so the analysis did not change; the snake_case construct names in the comments
//! (`method_definition`, …) are that grammar's.
//!
//! # Overview
//! - The **lexer** ([`Lexer`]) turns source bytes into [`Token`]s on demand. JavaScript's lexical grammar depends on
//!   the parser's state (is `/` a division or a regular expression? is `}` the end of a block or of a template
//!   substitution? is `>>` one operator or two closing type-argument brackets? is `it's` JSX text or a string?), so
//!   the lexer only produces the *default* reading of a token, and the parser asks for a *rescan* in another mode
//!   ([`Mode`]) where it knows better, exactly like the TypeScript compiler's scanner does.
//! - The **parser** ([`Parser`]) is a hand-written recursive-descent parser over that token stream, following the
//!   TypeScript grammar (statements, declarations, expressions with precedence climbing, types, JSX). Its output is
//!   twofold:
//!   1. A flat tree of [`Node`]s: only the constructs the analyser needs get a node kind of their own ([`NodeKind`]);
//!      everything else is a generic `Expr`, `Stmt` or `Type` node, or no node at all (most of a type, punctuation).
//!      Every node covers a contiguous range of tokens, so its text and its line span follow from the tokens.
//!   2. **Token flags** ([`REF`], [`DECISION`]): the parser marks the identifier tokens that are names in expression,
//!      pattern or type position (the names the analyser collects as references) and the tokens that are decision
//!      points for cyclomatic complexity (`if`, `for`, `while`, `do`, `case`, `catch`, the `?` of a conditional
//!      expression, and the `&&`, `||` and `??` operators). The analyser then only has to scan the token range of a
//!      node for flags; it never needs expression nodes for that.
//!
//! # Tree layout Nodes live in one `Vec` ([`Tree::nodes`]); the children of a node are a contiguous slice of a second
//! `Vec` ([`Tree::kids`]) given by `kids_start`/`kids_len`, in source order. The parser builds this bottom-up with a
//! stack: a [`Marker`] remembers the current token and stack height when a construct starts; nodes finished while
//! parsing it are pushed on the stack; [`Parser::finish`] moves the stack entries above the marker into `kids` as the
//! new node's children and pushes the new node. `precede` starts a marker *before* an already finished node (for
//! left-recursive constructs such as `a.b`, `a + b`, `f(x)`). Some children carry a [`Field`] tag so the analyser can
//! tell, for example, a variable's name from its value.
//!
//! # Grammar subset
//! The full statement, expression and type grammar of TypeScript 5 is parsed, including decorators,
//! `abstract` and `declare`, namespaces and `declare module`/`global`, enums, overloads, accessors, parameter
//! properties, generics (also generic calls `f<T>(x)` and arrow functions `<T,>(x: T) => x`), type predicates and
//! assertions, conditional, mapped and template literal types, `satisfies`, optional chaining, JSX (TSX only) and the
//! type assertion `<T>x` (plain TypeScript only). Not modelled: Flow syntax, legacy octal escapes, HTML comments
//! (`<!--`), and the distinction between strict and sloppy mode (contextual keywords such as `yield`, `await` and `let`
//! are treated as keywords wherever they can be one, regardless of mode).
//!
//! # Ambiguities
//! Where TypeScript itself needs unbounded lookahead (arrow functions `(a, b) => …` versus parenthesised expressions,
//! generic calls `f<T>(x)` versus comparisons, function types `(a: T) => U` versus parenthesised types), the parser
//! *speculates*: it takes a [`Snapshot`], tries the more specific reading and rolls back to the snapshot if that
//! reading hits an error (see [`Parser::bail`]). A rolled-back attempt drops its nodes and its tokens (they are lexed
//! again, possibly in a different mode). Failed arrow-function attempts are remembered by source offset, so nested
//! parentheses cannot make parsing exponential.
//!
//! # Error recovery
//! A syntax error never stops the parse; it increments [`Parser::errors`] (the file is then reported as having parse
//! errors) and the parser carries on:
//! - an expected token that is missing is treated as present (nothing is consumed);
//! - a token that cannot start the expected construct is wrapped in an `Error` node and skipped, so every loop makes
//!   progress; statement lists additionally skip any token a statement could not consume;
//! - recursion is capped at [`MAX_DEPTH`] nested constructs: deeper input is skipped token by token, so adversarial
//!   input (thousands of `(`) cannot overflow the stack.
//!
//! The parser never panics on any input: all indexing into the source and the token list is bounds-checked.

use std::collections::HashSet;

/// Token flag: the token is a name in expression, pattern or type position that may refer to a declaration. Property
/// names (`a.b`, `{ b: 1 }`, class and interface members), shorthand properties (`{ b }`), labels, JSX attribute names
/// and `undefined` are not marked.
pub const REF: u8 = 1;
/// Token flag: the token adds one to cyclomatic complexity (see the module documentation).
pub const DECISION: u8 = 2;
/// Token flag (internal): the lexer found the token malformed (unterminated string, comment, template or regex).
const BAD: u8 = 4;

/// Maximum nesting of statements, expressions, types and patterns. Real code stays far below this; deeper input is
/// treated as a syntax error. Chosen so that the deepest recursion fits comfortably into a 2 MiB thread stack.
pub const MAX_DEPTH: u32 = 400;

/// What a token is. Keywords and contextual keywords are `Ident` tokens: the parser compares their text.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TokKind {
    /// Identifier or keyword, e.g. `foo`, `if`, `type`. JSX names in `JsxName` mode may contain `-`.
    Ident,
    /// `#name` (private class member).
    PrivateName,
    Number,
    /// `'…'` or `"…"` (in `JsxString` mode a JSX attribute string, which has no escapes).
    String,
    /// `/…/flags`, only produced by a rescan in `Regex` mode.
    Regex,
    /// A template literal without substitutions: `` `…` ``.
    Template,
    /// The start of a template literal up to its first substitution: `` `…${ ``.
    TemplateHead,
    /// The part between two substitutions: `}…${`, only produced by a rescan in `TemplateContinue` mode.
    TemplateMiddle,
    /// The part after the last substitution: `` }…` ``, only produced by a rescan in `TemplateContinue` mode.
    TemplateTail,
    /// Operator or punctuation, e.g. `{`, `=>`, `?.`. The default mode never joins `>` with what follows (see
    /// `Greater` mode), so `Array<Array<T>>` closes with two `>` tokens.
    Punct,
    /// Text between JSX tags, only produced in `JsxChild` mode.
    JsxText,
    /// A character that cannot start any token (e.g. a stray control character).
    Invalid,
    /// End of input. The token list always ends with exactly one `Eof`.
    Eof,
}

/// One token: a kind, a byte range into the source and the 1-based lines it starts and ends on.
#[derive(Clone, Copy, Debug)]
pub struct Token {
    pub kind: TokKind,
    /// Byte offsets into the source, `start..end`.
    pub start: u32,
    pub end: u32,
    /// 1-based line of `start`, and of `end` (the number of `\n` before the offset, plus one).
    pub line: u32,
    pub end_line: u32,
    /// True when a line break (possibly inside a comment) separates this token from the previous one. Drives
    /// automatic semicolon insertion and the "no line break here" rules.
    pub nl_before: bool,
    /// [`REF`], [`DECISION`] (set by the parser) and the internal `BAD` flag (set by the lexer).
    pub flags: u8,
}

/// Lexing modes. `Normal` is used unless the parser rescans the current token in another mode.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    Normal,
    /// The current `/` or `/=` starts a regular expression literal.
    Regex,
    /// The current `}` ends a template substitution: lex the template text that follows.
    TemplateContinue,
    /// The current `>` may be the first character of `>=`, `>>`, `>>=`, `>>>` or `>>>=`.
    Greater,
    /// Inside a JSX element's children: text up to the next `{` or `<` (no whitespace or comment skipping).
    JsxChild,
    /// A JSX tag or attribute name, which may contain `-`.
    JsxName,
    /// A JSX attribute string: quoted, no escape sequences.
    JsxString,
}

/// The token producer. Stateless apart from the source: every call lexes one token starting at a given offset, which
/// makes rescanning a token in another mode trivial.
pub struct Lexer<'a> {
    src: &'a [u8],
    /// Byte offset of the start of every line (`0`, then one past every `\n`), for line numbers.
    line_starts: Vec<u32>,
}

/// Punctuators of the default mode, longest first so the first match is the longest one. `>` is deliberately only
/// a single character here (see [`Mode::Greater`]); `?.` is handled separately (it must not be followed by a digit).
const PUNCTUATORS: [&str; 47] = [
    "...", "===", "!==", "**=", "<<=", "&&=", "||=", "??=", "=>", "==", "!=", "<=", "+=", "-=", "*=", "/=", "%=",
    "&=", "|=", "^=", "&&", "||", "??", "++", "--", "**", "<<", "{", "}", "(", ")", "[", "]", ";", ",", "<", ">",
    "+", "-", "*", "/", "%", "&", "|", "^", "!", "~",
];

/// Single-character punctuators not in [`PUNCTUATORS`] (`?`, `:`, `=`, `@`, `.`, `#`).
const PUNCTUATORS_2: [&str; 6] = ["?", ":", "=", "@", ".", "#"];

fn is_id_start(b: u8) -> bool {
    b.is_ascii_alphabetic() || b == b'_' || b == b'$' || b >= 0x80
}

fn is_id_part(b: u8) -> bool {
    is_id_start(b) || b.is_ascii_digit()
}

impl<'a> Lexer<'a> {
    pub fn new(src: &'a [u8]) -> Self {
        let mut line_starts = vec![0u32];
        for (i, &b) in src.iter().enumerate() {
            if b == b'\n' {
                line_starts.push(i as u32 + 1);
            }
        }
        Lexer { src, line_starts }
    }

    /// 1-based line of a byte offset: one plus the number of `\n` before it.
    pub fn line_of(&self, offset: u32) -> u32 {
        self.line_starts.partition_point(|&s| s <= offset) as u32
    }

    fn byte(&self, i: usize) -> u8 {
        self.src.get(i).copied().unwrap_or(0)
    }

    /// Length of a non-ASCII whitespace character starting at `i` (U+00A0, U+1680, U+2000–U+200B, U+2028, U+2029,
    /// U+202F, U+205F, U+2060, U+3000, U+FEFF), or 0. Such characters separate tokens; any other non-ASCII character is
    /// part of an identifier.
    fn unicode_space(&self, i: usize) -> usize {
        let (a, b, c) = (self.byte(i), self.byte(i + 1), self.byte(i + 2));
        match a {
            0xC2 if b == 0xA0 => 2,
            0xE1 if b == 0x9A && c == 0x80 => 3,
            0xE2 if b == 0x80 && (0x80..=0x8B).contains(&c) => 3,
            0xE2 if b == 0x80 && (c == 0xA8 || c == 0xA9 || c == 0xAF) => 3,
            0xE2 if b == 0x81 && (c == 0x9F || c == 0xA0) => 3,
            0xE3 if b == 0x80 && c == 0x80 => 3,
            0xEF if b == 0xBB && c == 0xBF => 3,
            _ => 0,
        }
    }

    /// Skips whitespace and comments from `i`. Returns the new offset, whether a line break was crossed, and whether
    /// a block comment was left unterminated.
    fn skip_trivia(&self, mut i: usize) -> (usize, bool, bool) {
        let mut nl = false;
        let n = self.src.len();
        // A hashbang line (`#!/usr/bin/env node`) is only allowed at the very start of the file.
        if i == 0 && self.byte(0) == b'#' && self.byte(1) == b'!' {
            while i < n && self.src[i] != b'\n' {
                i += 1;
            }
        }
        while i < n {
            let b = self.src[i];
            match b {
                b' ' | b'\t' | b'\r' | 0x0B | 0x0C => i += 1,
                b'\n' => {
                    nl = true;
                    i += 1;
                }
                b'/' if self.byte(i + 1) == b'/' => {
                    while i < n && self.src[i] != b'\n' {
                        i += 1;
                    }
                }
                b'/' if self.byte(i + 1) == b'*' => {
                    i += 2;
                    loop {
                        if i >= n {
                            return (n, nl, true);
                        }
                        if self.src[i] == b'*' && self.byte(i + 1) == b'/' {
                            i += 2;
                            break;
                        }
                        if self.src[i] == b'\n' {
                            nl = true;
                        }
                        i += 1;
                    }
                }
                _ if b >= 0x80 => {
                    let len = self.unicode_space(i);
                    if len == 0 {
                        break;
                    }
                    // U+2028 and U+2029 are line terminators.
                    if len == 3 && b == 0xE2 && self.byte(i + 1) == 0x80 && (self.byte(i + 2) == 0xA8 || self.byte(i + 2) == 0xA9) {
                        nl = true;
                    }
                    i += len;
                }
                _ => break,
            }
        }
        (i, nl, false)
    }

    fn make(&self, kind: TokKind, start: usize, end: usize, nl_before: bool, bad: bool) -> Token {
        let (start, end) = (start as u32, end as u32);
        Token {
            kind,
            start,
            end,
            line: self.line_of(start),
            end_line: self.line_of(end),
            nl_before,
            flags: if bad { BAD } else { 0 },
        }
    }

    /// Lexes one token starting at byte offset `from` (the end of the previous token) in the given mode.
    pub fn lex(&self, from: usize, mode: Mode) -> Token {
        let n = self.src.len();
        let from = from.min(n);
        if mode == Mode::JsxChild {
            // JSX text keeps its whitespace and has no comments, so no trivia is skipped.
            let b = self.byte(from);
            if from >= n {
                return self.make(TokKind::Eof, n, n, false, false);
            }
            if b == b'{' || b == b'<' {
                return self.make(TokKind::Punct, from, from + 1, false, false);
            }
            let mut i = from;
            while i < n && self.src[i] != b'{' && self.src[i] != b'<' {
                i += 1;
            }
            return self.make(TokKind::JsxText, from, i, false, false);
        }
        let (i, nl, bad_comment) = self.skip_trivia(from);
        if i >= n {
            return self.make(TokKind::Eof, n, n, nl, bad_comment);
        }
        let b = self.src[i];
        match mode {
            Mode::Regex if b == b'/' => return self.lex_regex(i, nl),
            Mode::TemplateContinue if b == b'}' => {
                let (end, head, bad) = self.scan_template(i + 1);
                let kind = if head { TokKind::TemplateMiddle } else { TokKind::TemplateTail };
                return self.make(kind, i, end, nl, bad);
            }
            Mode::Greater if b == b'>' => {
                let rest = &self.src[i..];
                let len = [">>>=", ">>>", ">>=", ">>", ">="]
                    .iter()
                    .find(|p| rest.starts_with(p.as_bytes()))
                    .map(|p| p.len())
                    .unwrap_or(1);
                return self.make(TokKind::Punct, i, i + len, nl, false);
            }
            Mode::JsxName if is_id_start(b) => {
                let mut j = i;
                while j < n && (is_id_part(self.src[j]) || self.src[j] == b'-') && self.unicode_space(j) == 0 {
                    j += 1;
                }
                return self.make(TokKind::Ident, i, j, nl, false);
            }
            Mode::JsxString if b == b'"' || b == b'\'' => {
                let mut j = i + 1;
                while j < n && self.src[j] != b {
                    j += 1;
                }
                let bad = j >= n;
                return self.make(TokKind::String, i, (j + 1).min(n), nl, bad);
            }
            _ => {}
        }
        self.lex_normal(i, nl, bad_comment)
    }

    /// The default reading of the token at `i` (trivia already skipped).
    fn lex_normal(&self, i: usize, nl: bool, bad_comment: bool) -> Token {
        let n = self.src.len();
        let b = self.src[i];
        // identifiers and keywords (including `\uXXXX` escapes)
        if (is_id_start(b) && self.unicode_space(i) == 0) || (b == b'\\' && self.byte(i + 1) == b'u') {
            let j = self.scan_ident(i);
            return self.make(TokKind::Ident, i, j, nl, bad_comment);
        }
        if b.is_ascii_digit() || (b == b'.' && self.byte(i + 1).is_ascii_digit()) {
            let j = self.scan_number(i);
            return self.make(TokKind::Number, i, j, nl, bad_comment);
        }
        match b {
            b'"' | b'\'' => {
                let mut j = i + 1;
                let mut bad = true;
                while j < n {
                    let c = self.src[j];
                    if c == b {
                        j += 1;
                        bad = false;
                        break;
                    }
                    if c == b'\n' {
                        break;
                    }
                    if c == b'\\' {
                        // `\` + CR LF is a line continuation of two bytes after the backslash
                        j += if self.byte(j + 1) == b'\r' && self.byte(j + 2) == b'\n' { 3 } else { 2 };
                        continue;
                    }
                    j += 1;
                }
                return self.make(TokKind::String, i, j.min(n), nl, bad || bad_comment);
            }
            b'`' => {
                let (end, head, bad) = self.scan_template(i + 1);
                let kind = if head { TokKind::TemplateHead } else { TokKind::Template };
                return self.make(kind, i, end, nl, bad || bad_comment);
            }
            b'#' if is_id_start(self.byte(i + 1)) || self.byte(i + 1) == b'\\' => {
                let j = self.scan_ident(i + 1);
                return self.make(TokKind::PrivateName, i, j, nl, bad_comment);
            }
            b'?' if self.byte(i + 1) == b'.' && !self.byte(i + 2).is_ascii_digit() => {
                return self.make(TokKind::Punct, i, i + 2, nl, bad_comment);
            }
            _ => {}
        }
        let rest = &self.src[i..];
        for p in PUNCTUATORS.iter().chain(PUNCTUATORS_2.iter()) {
            if rest.starts_with(p.as_bytes()) {
                return self.make(TokKind::Punct, i, i + p.len(), nl, bad_comment);
            }
        }
        // Anything else (a control character, a lone backslash): one byte, or a whole UTF-8 sequence.
        let mut j = i + 1;
        while j < n && (self.src[j] & 0xC0) == 0x80 {
            j += 1;
        }
        self.make(TokKind::Invalid, i, j, nl, true)
    }

    /// End of an identifier starting at `i`: identifier characters and `\u` escapes.
    fn scan_ident(&self, mut i: usize) -> usize {
        let n = self.src.len();
        while i < n {
            let c = self.src[i];
            if c == b'\\' && self.byte(i + 1) == b'u' {
                i += 2;
                if self.byte(i) == b'{' {
                    while i < n && self.src[i] != b'}' {
                        i += 1;
                    }
                    i = (i + 1).min(n);
                } else {
                    let mut k = 0;
                    while k < 4 && i < n && self.src[i].is_ascii_hexdigit() {
                        i += 1;
                        k += 1;
                    }
                }
            } else if is_id_part(c) && self.unicode_space(i) == 0 {
                i += 1;
            } else {
                break;
            }
        }
        i
    }

    /// End of a numeric literal starting at `i`: digits, letters (hex digits, prefixes, `e`, `n`), `_` and `.`, and
    /// a sign directly after an exponent `e` of a decimal number.
    fn scan_number(&self, mut i: usize) -> usize {
        let n = self.src.len();
        let hex = self.byte(i) == b'0' && matches!(self.byte(i + 1), b'x' | b'X');
        let mut dot = false;
        while i < n {
            let c = self.src[i];
            if c == b'.' {
                // `1..toString()`: only one dot belongs to the number
                if dot || hex {
                    break;
                }
                dot = true;
                i += 1;
            } else if (c == b'e' || c == b'E') && !hex && matches!(self.byte(i + 1), b'+' | b'-') {
                i += 2;
            } else if c.is_ascii_alphanumeric() || c == b'_' {
                i += 1;
            } else {
                break;
            }
        }
        i
    }

    /// Scans template text starting at `i` (just after `` ` `` or `}`) up to the closing `` ` `` or the next `${`.
    /// Returns the end offset, whether it stopped at `${` (a head or middle part) and whether input ended first.
    fn scan_template(&self, mut i: usize) -> (usize, bool, bool) {
        let n = self.src.len();
        while i < n {
            match self.src[i] {
                b'`' => return (i + 1, false, false),
                b'\\' => i += 2,
                b'$' if self.byte(i + 1) == b'{' => return (i + 2, true, false),
                _ => i += 1,
            }
        }
        (n, false, true)
    }

    /// A regular expression literal at `i`: up to the closing `/` outside a character class, then the flags.
    fn lex_regex(&self, i: usize, nl: bool) -> Token {
        let n = self.src.len();
        let mut j = i + 1;
        let mut class = false;
        let mut bad = true;
        while j < n {
            match self.src[j] {
                b'\\' => {
                    if self.byte(j + 1) == b'\n' {
                        break;
                    }
                    j += 2;
                    continue;
                }
                b'\n' => break,
                b'[' => class = true,
                b']' => class = false,
                b'/' if !class => {
                    j += 1;
                    bad = false;
                    break;
                }
                _ => {}
            }
            j += 1;
        }
        let j = j.min(n);
        let mut k = j;
        if !bad {
            while k < n && is_id_part(self.src[k]) && self.src[k] < 0x80 {
                k += 1;
            }
        }
        self.make(TokKind::Regex, i, k, nl, bad)
    }
}

// ------------------------------------------------------------------------------------------------------------ tree

/// Node kinds. Most correspond to one grammar construct (whose grammar name the comments give); constructs the analyser
/// never distinguishes share `Stmt`, `Expr` or `Type`.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum NodeKind {
    /// The whole file; children are statements (`program`).
    Program,
    /// `{ statements }`: a block statement, function body or namespace body (`statement_block`).
    Block,
    /// `expr;` (`expression_statement`); its first child is the expression.
    ExprStmt,
    /// Any other statement (`if`, `for`, `return`, …), labelled or empty statement.
    Stmt,
    /// `import … from "m";` (`import_statement`). Children: `ImportDefault`, `ImportNamespace`, `ImportSpecifier`,
    /// and the module specifier string with field `Source` (absent for `import x = require("m")`).
    Import,
    /// `D` in `import D from "m"`.
    ImportDefault,
    /// `* as N` in `import * as N from "m"`; its child `Name` is `N`.
    ImportNamespace,
    /// `a` or `a as b` inside `import { … }`; children `Name` (field `Name`) and optionally `Name` (field `Alias`).
    ImportSpecifier,
    /// `import A = B.C;` (`import_alias`).
    ImportAlias,
    /// `export …` without `default` (`export_statement`): the exported declaration has field `Decl`; re-exports
    /// (`export { a } from "b"`, `export * from "c"`) and `export = x` have no `Decl` child.
    Export,
    /// `export default …`: a declaration (field `Decl`) or an expression (field `Value`).
    ExportDefault,
    /// `declare …` (`ambient_declaration`); its first child is the declaration (or a `Block` for `declare global`).
    Ambient,
    /// `var`/`let`/`const` declaration (`variable_declaration`/`lexical_declaration`); children are `Declarator`s.
    VarDecl,
    /// `name: T = value` (`variable_declarator`): child `Pattern` (field `Name`), optional value (field `Value`).
    Declarator,
    /// A binding pattern: an identifier or a destructuring pattern, as a declarator's name.
    Pattern,
    /// `function f() {}` (`function_declaration`), also `async`.
    FunctionDecl,
    /// `function* f() {}` (`generator_function_declaration`).
    GeneratorDecl,
    /// A function overload or ambient function without body (`function_signature`).
    FunctionSig,
    /// `class C {}` (`class_declaration`). Children: decorators, `Name` (field `Name`), heritage, `ClassBody`.
    ClassDecl,
    /// `abstract class C {}` (`abstract_class_declaration`).
    AbstractClassDecl,
    /// A class expression (`class`), e.g. `const C = class {}` or `export default class {}`.
    ClassExpr,
    /// `{ members }` of a class (`class_body`).
    ClassBody,
    /// `@expr` (`decorator`).
    Decorator,
    /// A method, constructor or accessor with a body (`method_definition`). Starts at its modifiers; decorators before
    /// it are separate siblings in the class body.
    MethodDef,
    /// A method without body: an overload in a class, or a method in an interface or object type
    /// (`method_signature`; `abstract_method_signature` for abstract ones).
    MethodSig,
    /// A class field (`public_field_definition`), including its decorators. Optional value with field `Value`.
    FieldDef,
    /// `[key: K]: V` or a mapped type clause (`index_signature`).
    IndexSig,
    /// `static { … }` (`class_static_block`).
    StaticBlock,
    /// `interface I {}` (`interface_declaration`); body is an `ObjectType` (field `Body`).
    Interface,
    /// `{ members }` as a type: object literal type, mapped type or interface body (`object_type`).
    ObjectType,
    /// `name?: T` in an object type (`property_signature`).
    PropertySig,
    /// `(x): T` in an object type (`call_signature`).
    CallSig,
    /// `new (x): T` in an object type (`construct_signature`).
    ConstructSig,
    /// `enum E {}` (`enum_declaration`); its `EnumBody` has one `EnumMember` per member.
    Enum,
    EnumBody,
    EnumMember,
    /// `type T = …;` (`type_alias_declaration`); the aliased type has field `Value`.
    TypeAlias,
    /// `namespace N {}`, `module M {}`, `declare module "m" {}` (`internal_module`/`module`): `Name` (field `Name`)
    /// and an optional `Block` (field `Body`).
    Module,
    /// `( parameters )` (`formal_parameters`), also the single unparenthesised parameter of `x => …`.
    Params,
    /// One parameter (`required_parameter`/`optional_parameter`).
    Param,
    /// A constructor parameter with an accessibility modifier (`private x: T`), which also declares a field.
    ParamProperty,
    /// `=>` function (`arrow_function`).
    Arrow,
    /// `function () {}` as an expression (`function_expression`).
    FunctionExpr,
    /// `function* () {}` as an expression (`generator_function`).
    GeneratorExpr,
    /// `f(args)`, ``tag`…` `` (`call_expression`): the callee is the first child, the arguments have field `Args`.
    Call,
    /// `( args )` of a call (`arguments`); children are the argument expressions.
    Args,
    /// An identifier used as an expression (`identifier`).
    Ident,
    /// A string literal (`string`).
    Str,
    /// A template literal (`template_string`).
    Template,
    /// Any other expression.
    Expr,
    /// A type that is not a plain object type (union, reference, function type, …).
    Type,
    /// A name: of a declaration, member, module or import binding. Covers one token, or several for `A.B.C` and
    /// computed member names.
    Name,
    /// Tokens skipped during error recovery.
    Error,
}

/// Tags on children: their role in the parent (a declaration's name, a variable's value, a function's body, …).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Field {
    None,
    Name,
    Alias,
    Value,
    Body,
    Params,
    Args,
    Source,
    Decl,
}

/// One node: a kind, a field tag (set by the parent), its token range and its children.
#[derive(Clone, Copy, Debug)]
pub struct Node {
    pub kind: NodeKind,
    pub field: Field,
    /// Token range `first..end` (end exclusive). Empty only for nodes created at an error.
    pub first: u32,
    pub end: u32,
    /// The children are `kids[kids_start .. kids_start + kids_len]`.
    pub kids_start: u32,
    pub kids_len: u32,
}

/// The parse result: tokens (with flags), nodes and the root node.
pub struct Tree<'a> {
    pub code: &'a str,
    pub tokens: Vec<Token>,
    pub nodes: Vec<Node>,
    pub kids: Vec<u32>,
    pub root: u32,
    /// True when the parser had to recover from at least one syntax error.
    pub has_error: bool,
    /// Byte offset of the token where the first syntax error was noticed (for diagnostics; 0 without errors). Only
    /// the tests read it today, hence the `allow`.
    #[allow(dead_code)]
    pub first_error: u32,
}

impl<'a> Tree<'a> {
    pub fn kind(&self, n: u32) -> NodeKind {
        self.nodes[n as usize].kind
    }

    /// The children of `n` in source order.
    pub fn children(&self, n: u32) -> &[u32] {
        let node = &self.nodes[n as usize];
        &self.kids[node.kids_start as usize..(node.kids_start + node.kids_len) as usize]
    }

    /// The first child of `n` tagged with `field`.
    pub fn field(&self, n: u32, field: Field) -> Option<u32> {
        self.children(n).iter().copied().find(|&c| self.nodes[c as usize].field == field)
    }

    /// The source text of a node: from the start of its first token to the end of its last ("" if empty).
    pub fn text(&self, n: u32) -> &'a str {
        let node = &self.nodes[n as usize];
        if node.end <= node.first {
            return "";
        }
        let a = self.tokens[node.first as usize].start as usize;
        let b = self.tokens[node.end as usize - 1].end as usize;
        self.code.get(a..b).unwrap_or("")
    }

    pub fn token_text(&self, t: usize) -> &'a str {
        let tok = &self.tokens[t];
        self.code.get(tok.start as usize..tok.end as usize).unwrap_or("")
    }

    /// 1-based line the node starts on.
    pub fn first_line(&self, n: u32) -> u32 {
        let node = &self.nodes[n as usize];
        self.tokens.get(node.first as usize).map(|t| t.line).unwrap_or(1)
    }

    /// 1-based line the node ends on (inclusive).
    pub fn last_line(&self, n: u32) -> u32 {
        let node = &self.nodes[n as usize];
        if node.end <= node.first {
            return self.first_line(n);
        }
        self.tokens[node.end as usize - 1].end_line
    }

    /// The token indices a node covers.
    pub fn token_range(&self, n: u32) -> std::ops::Range<usize> {
        let node = &self.nodes[n as usize];
        node.first as usize..(node.end.max(node.first)) as usize
    }
}

/// Parses `code` as TypeScript (`tsx == false`) or TSX.
pub fn parse(code: &str, tsx: bool) -> Tree<'_> {
    let mut p = Parser {
        lx: Lexer::new(code.as_bytes()),
        src: code.as_bytes(),
        toks: Vec::with_capacity(code.len() / 4 + 16),
        pos: 0,
        nodes: Vec::with_capacity(code.len() / 8 + 16),
        kids: Vec::new(),
        stack: Vec::new(),
        errors: 0,
        spec: 0,
        spec_base: 0,
        depth: 0,
        tsx,
        arrow_failed: HashSet::new(),
        depth_capped: false,
        first_error: None,
    };
    let root = p.parse_program();
    // Make sure the token list is complete (it ends with the Eof token), so ranges are always valid.
    p.ensure(0);
    Tree { code, has_error: p.errors > 0, first_error: p.first_error.unwrap_or(0), tokens: p.toks, nodes: p.nodes, kids: p.kids, root }
}

// ---------------------------------------------------------------------------------------------------------- parser

/// The start of a node under construction: its first token and the stack height (see the module documentation).
#[derive(Clone, Copy)]
struct Marker {
    tok: u32,
    stack: u32,
}

/// Everything needed to roll back a failed speculative parse.
#[derive(Clone, Copy)]
struct Snapshot {
    pos: usize,
    nodes: usize,
    kids: usize,
    stack: usize,
    errors: u32,
}

/// Words that can never be an identifier in an expression (reserved words). Contextual keywords (`type`, `async`,
/// `of`, `get`, …) are not listed: they are identifiers wherever they are not used as keywords.
const RESERVED: [&str; 36] = [
    "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum",
    "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null",
    "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with",
];

/// Predefined type names such as `string` and `any` (plus the literal types `null`, `undefined`, `true`, `false` and
/// `this`): never references.
const PREDEFINED_TYPES: [&str; 15] = [
    "any", "number", "boolean", "string", "symbol", "void", "unknown", "never", "object", "null", "undefined",
    "true", "false", "this", "unique",
];

/// Class member modifiers.
const MODIFIERS: [&str; 10] =
    ["public", "private", "protected", "static", "override", "readonly", "async", "abstract", "declare", "accessor"];

/// Assignment operators (`>>=` and `>>>=` only appear after a [`Mode::Greater`] rescan).
const ASSIGN_OPS: [&str; 16] =
    ["=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??="];

pub struct Parser<'a> {
    lx: Lexer<'a>,
    src: &'a [u8],
    /// Tokens lexed so far. Tokens at and after `pos` are lookahead and may be re-lexed (see [`Parser::rescan`]).
    toks: Vec<Token>,
    /// Index of the current token.
    pos: usize,
    nodes: Vec<Node>,
    kids: Vec<u32>,
    /// Finished nodes that have no parent yet (see the module documentation).
    stack: Vec<u32>,
    /// Number of syntax errors so far (rolled back with failed speculation).
    errors: u32,
    /// Nesting of speculative parses, and the error count when the innermost one started.
    spec: u32,
    spec_base: u32,
    /// Current recursion depth (see [`MAX_DEPTH`]).
    depth: u32,
    tsx: bool,
    /// Source offsets of `(`/`<` tokens where an arrow function was tried and failed.
    arrow_failed: HashSet<u32>,
    /// Set once [`MAX_DEPTH`] has been reached anywhere in the file. From then on the parser no longer speculates and
    /// takes the plain reading of every ambiguity (a comparison rather than type arguments, a parenthesised expression
    /// or type rather than an arrow function or function type). Input that nests past the cap at each of many
    /// speculation points (`a < a < a < …`) would otherwise cost up to `MAX_DEPTH` steps per token, each re-lexed
    /// after the rollback; real code never gets near the cap.
    depth_capped: bool,
    /// Offset of the first error outside speculation (see [`Tree::first_error`]).
    first_error: Option<u32>,
}

impl<'a> Parser<'a> {
    // ------------------------------------------------------------------ tokens

    /// Makes sure the token `k` positions ahead is lexed (or the list ends with Eof).
    fn ensure(&mut self, k: usize) {
        while self.toks.len() <= self.pos + k {
            if let Some(last) = self.toks.last() {
                if last.kind == TokKind::Eof {
                    return;
                }
            }
            let from = self.toks.last().map(|t| t.end as usize).unwrap_or(0);
            let t = self.lx.lex(from, Mode::Normal);
            self.toks.push(t);
        }
    }

    /// The token `k` positions ahead of the current one (Eof beyond the end).
    fn peek(&mut self, k: usize) -> Token {
        self.ensure(k);
        let i = (self.pos + k).min(self.toks.len() - 1);
        self.toks[i]
    }

    fn cur(&mut self) -> Token {
        self.peek(0)
    }

    fn text_of(&self, t: &Token) -> &'a [u8] {
        self.src.get(t.start as usize..t.end as usize).unwrap_or(b"")
    }

    /// Is the token `k` ahead the punctuator `p`?
    fn nth_p(&mut self, k: usize, p: &str) -> bool {
        let t = self.peek(k);
        t.kind == TokKind::Punct && self.text_of(&t) == p.as_bytes()
    }

    /// Is the token `k` ahead the word (keyword or identifier) `w`?
    fn nth_kw(&mut self, k: usize, w: &str) -> bool {
        let t = self.peek(k);
        t.kind == TokKind::Ident && self.text_of(&t) == w.as_bytes()
    }

    fn at_p(&mut self, p: &str) -> bool {
        self.nth_p(0, p)
    }

    fn at_kw(&mut self, w: &str) -> bool {
        self.nth_kw(0, w)
    }

    fn nth_kind(&mut self, k: usize) -> TokKind {
        self.peek(k).kind
    }

    fn at_eof(&mut self) -> bool {
        self.cur().kind == TokKind::Eof
    }

    /// Is the current token one of the given words?
    fn at_any_kw(&mut self, words: &[&str]) -> bool {
        let t = self.cur();
        t.kind == TokKind::Ident && words.iter().any(|w| self.text_of(&t) == w.as_bytes())
    }

    /// Consumes the current token (never the Eof) and returns its index.
    fn bump(&mut self) -> usize {
        let t = self.cur();
        if t.kind == TokKind::Eof {
            return self.pos;
        }
        if t.flags & BAD != 0 {
            self.error();
        }
        self.pos += 1;
        self.pos - 1
    }

    /// Consumes the current token and sets `flag` on it.
    fn bump_flag(&mut self, flag: u8) -> usize {
        let i = self.bump();
        if i < self.toks.len() && self.toks[i].kind != TokKind::Eof {
            self.toks[i].flags |= flag;
        }
        i
    }

    fn eat_p(&mut self, p: &str) -> bool {
        if self.at_p(p) {
            self.bump();
            true
        } else {
            false
        }
    }

    fn eat_kw(&mut self, w: &str) -> bool {
        if self.at_kw(w) {
            self.bump();
            true
        } else {
            false
        }
    }

    /// Consumes `p` or records an error (the missing token is treated as present).
    fn expect_p(&mut self, p: &str) {
        if !self.eat_p(p) {
            self.error();
        }
    }

    /// Re-lexes the current token in another mode, discarding all lookahead.
    fn rescan(&mut self, mode: Mode) {
        self.toks.truncate(self.pos);
        let from = self.toks.last().map(|t| t.end as usize).unwrap_or(0);
        let t = self.lx.lex(from, mode);
        self.toks.push(t);
    }

    /// Statement terminator: `;`, or automatic semicolon insertion before `}`, at the end of input or after a line
    /// break.
    fn semicolon(&mut self) {
        if self.eat_p(";") {
            return;
        }
        let t = self.cur();
        if t.kind == TokKind::Eof || t.nl_before || self.at_p("}") {
            return;
        }
        self.error();
    }

    /// Can automatic semicolon insertion end a statement here (no `;` needed)?
    fn can_end_statement(&mut self) -> bool {
        let t = self.cur();
        t.kind == TokKind::Eof || t.nl_before || self.at_p("}") || self.at_p(";")
    }

    // ------------------------------------------------------------------ nodes, errors, speculation

    fn mark(&self) -> Marker {
        Marker { tok: self.pos as u32, stack: self.stack.len() as u32 }
    }

    /// A marker that starts at the already finished node `n`, which must be on top of the stack.
    fn precede(&self, n: u32) -> Marker {
        Marker { tok: self.nodes[n as usize].first, stack: self.stack.len().saturating_sub(1) as u32 }
    }

    /// Finishes a node that started at `m` and ends at the last consumed token; nodes finished since `m` become its
    /// children.
    fn finish(&mut self, m: Marker, kind: NodeKind) -> u32 {
        let from = (m.stack as usize).min(self.stack.len());
        let kids_start = self.kids.len() as u32;
        self.kids.extend_from_slice(&self.stack[from..]);
        let kids_len = (self.stack.len() - from) as u32;
        self.stack.truncate(from);
        let id = self.nodes.len() as u32;
        self.nodes.push(Node { kind, field: Field::None, first: m.tok, end: (self.pos as u32).max(m.tok), kids_start, kids_len });
        self.stack.push(id);
        id
    }

    fn set_field(&mut self, n: u32, f: Field) {
        self.nodes[n as usize].field = f;
    }

    fn error(&mut self) {
        self.errors += 1;
        if self.spec == 0 && self.first_error.is_none() {
            let t = self.cur();
            self.first_error = Some(t.start);
        }
    }

    /// True while speculating and the current attempt has already hit an error: loops stop early so that a failed
    /// attempt is abandoned quickly.
    fn bail(&self) -> bool {
        self.spec > 0 && self.errors > self.spec_base
    }

    /// Skips one token as an `Error` node (used where nothing else applies, to guarantee progress).
    fn error_node(&mut self) -> u32 {
        self.error();
        let m = self.mark();
        if !self.bail() {
            self.bump();
        }
        self.finish(m, NodeKind::Error)
    }

    fn snapshot(&self) -> Snapshot {
        Snapshot { pos: self.pos, nodes: self.nodes.len(), kids: self.kids.len(), stack: self.stack.len(), errors: self.errors }
    }

    /// Rolls back to a snapshot. Tokens from the snapshot position on are dropped and lexed again later, as the
    /// failed attempt may have rescanned some of them in another mode or flagged them.
    fn restore(&mut self, s: Snapshot) {
        self.pos = s.pos;
        self.toks.truncate(s.pos);
        self.nodes.truncate(s.nodes);
        self.kids.truncate(s.kids);
        self.stack.truncate(s.stack);
        self.errors = s.errors;
    }

    /// Starts a speculative parse; returns what [`Parser::end_spec`] needs.
    fn begin_spec(&mut self) -> (Snapshot, u32) {
        let s = self.snapshot();
        let base = self.spec_base;
        self.spec += 1;
        self.spec_base = self.errors;
        (s, base)
    }

    /// Ends a speculative parse: on failure (any error since it began) rolls back and returns false.
    fn end_spec(&mut self, s: Snapshot, base: u32, ok: bool) -> bool {
        let ok = ok && self.errors == self.spec_base;
        self.spec -= 1;
        self.spec_base = base;
        if !ok {
            self.restore(s);
        }
        ok
    }

    /// Enters a nested construct; false (after recording an error) when [`MAX_DEPTH`] is reached.
    fn enter(&mut self) -> bool {
        if self.depth >= MAX_DEPTH {
            self.depth_capped = true;
            self.error();
            return false;
        }
        self.depth += 1;
        true
    }

    fn leave(&mut self) {
        self.depth -= 1;
    }

    // ------------------------------------------------------------------ names

    /// Is the current token a word usable as a binding or reference name (any identifier except reserved words)?
    fn at_identifier(&mut self) -> bool {
        let t = self.cur();
        t.kind == TokKind::Ident && !RESERVED.iter().any(|w| self.text_of(&t) == w.as_bytes())
    }

    fn nth_identifier(&mut self, k: usize) -> bool {
        let t = self.peek(k);
        t.kind == TokKind::Ident && !RESERVED.iter().any(|w| self.text_of(&t) == w.as_bytes())
    }

    /// Consumes an identifier that is a reference (flags it [`REF`] unless it is `undefined`).
    fn bump_ref(&mut self) -> usize {
        let is_undefined = self.at_kw("undefined");
        if is_undefined {
            self.bump()
        } else {
            self.bump_flag(REF)
        }
    }

    /// A declaration's name: a `Name` node over one identifier, flagged as reference (a declared name counts like any
    /// other name). Records an error and creates nothing if there is no identifier.
    fn decl_name(&mut self) -> Option<u32> {
        if self.cur().kind != TokKind::Ident {
            self.error();
            return None;
        }
        let m = self.mark();
        self.bump_ref();
        let n = self.finish(m, NodeKind::Name);
        self.set_field(n, Field::Name);
        Some(n)
    }

    /// Is the current token the start of a property name (identifier or keyword, string, number, `[`, `#name`)?
    fn at_property_name(&mut self) -> bool {
        matches!(self.cur().kind, TokKind::Ident | TokKind::String | TokKind::Number | TokKind::PrivateName) || self.at_p("[")
    }

    fn nth_property_name(&mut self, k: usize) -> bool {
        matches!(self.nth_kind(k), TokKind::Ident | TokKind::String | TokKind::Number | TokKind::PrivateName)
            || self.nth_p(k, "[")
    }

    /// A property name of a class member, object literal property, interface member or enum member: a `Name` node
    /// (field `Name`). Plain names are not references; a computed name `[expr]` is parsed as an expression.
    fn property_name(&mut self) -> u32 {
        let m = self.mark();
        if self.at_p("[") {
            self.bump();
            self.parse_assignment(false);
            self.expect_p("]");
        } else if self.at_property_name() {
            self.bump();
        } else {
            self.error();
        }
        let n = self.finish(m, NodeKind::Name);
        self.set_field(n, Field::Name);
        n
    }

    // ------------------------------------------------------------------ statements

    fn parse_program(&mut self) -> u32 {
        let m = self.mark();
        self.statement_list(false);
        // a lexing error at the very end (unterminated block comment) is attached to the Eof token
        if self.cur().flags & BAD != 0 {
            self.error();
        }
        self.finish(m, NodeKind::Program)
    }

    /// Statements until the end of input, or until `}` when `in_block`. Tokens that cannot start a statement are
    /// skipped (one at a time, as errors).
    fn statement_list(&mut self, in_block: bool) {
        loop {
            if self.at_eof() || (in_block && self.at_p("}")) {
                return;
            }
            let before = self.pos;
            self.parse_statement();
            if self.pos == before {
                self.error_node();
            }
            if self.bail() {
                return;
            }
        }
    }

    /// `{ statements }` as a `Block` node.
    fn parse_block(&mut self) -> u32 {
        let m = self.mark();
        if !self.enter() {
            return self.error_node();
        }
        self.expect_p("{");
        self.statement_list(true);
        self.expect_p("}");
        self.leave();
        self.finish(m, NodeKind::Block)
    }

    fn parse_statement(&mut self) -> u32 {
        if !self.enter() {
            return self.error_node();
        }
        let n = self.statement();
        self.leave();
        n
    }

    fn statement(&mut self) -> u32 {
        let t = self.cur();
        if t.kind == TokKind::Punct {
            if self.at_p("{") {
                return self.parse_block();
            }
            if self.at_p(";") {
                let m = self.mark();
                self.bump();
                return self.finish(m, NodeKind::Stmt);
            }
            if self.at_p("@") {
                return self.decorated_statement();
            }
            return self.expression_statement();
        }
        if t.kind != TokKind::Ident {
            return self.expression_statement();
        }
        let m = self.mark();
        let word = self.text_of(&t);
        match word {
            b"import" if !self.nth_p(1, "(") && !self.nth_p(1, ".") => self.import_statement(),
            b"export" => self.export_statement(m),
            b"var" => self.var_declaration(m, true),
            b"let" if self.nth_identifier(1) || self.nth_p(1, "[") || self.nth_p(1, "{") => self.var_declaration(m, true),
            b"const" if self.nth_kw(1, "enum") => self.enum_declaration(m),
            b"const" => self.var_declaration(m, true),
            b"if" | b"for" | b"while" | b"do" | b"switch" | b"try" | b"return" | b"throw" | b"break" | b"continue"
            | b"debugger" | b"with" => self.control_statement(m),
            _ => {
                if let Some(n) = self.try_declaration(m) {
                    return n;
                }
                // `label: statement`
                if self.at_identifier() && self.nth_p(1, ":") {
                    self.bump(); // statement_identifier: not a reference
                    self.bump();
                    self.parse_statement();
                    return self.finish(m, NodeKind::Stmt);
                }
                self.expression_statement()
            }
        }
    }

    /// Declarations that may also follow `export` or `declare`: functions, classes, interfaces, type aliases,
    /// enums, namespaces, modules, `declare …` and `global { }`. None (nothing consumed) if the current tokens do not
    /// start one; `var`/`let`/`const` are handled by the caller.
    fn try_declaration(&mut self, m: Marker) -> Option<u32> {
        let t = self.cur();
        if t.kind != TokKind::Ident {
            return None;
        }
        let next_same_line = !self.peek(1).nl_before;
        let word = self.text_of(&t);
        let n = match word {
            b"function" => self.function_declaration(m),
            b"async" if self.nth_kw(1, "function") && next_same_line => self.function_declaration(m),
            b"class" => self.class(m, NodeKind::ClassDecl),
            b"abstract" if self.nth_kw(1, "class") && next_same_line => {
                self.bump();
                self.class(m, NodeKind::AbstractClassDecl)
            }
            b"interface" if self.nth_kind(1) == TokKind::Ident && next_same_line => self.interface_declaration(m),
            b"type" if self.nth_kind(1) == TokKind::Ident && next_same_line => self.type_alias(m),
            b"enum" if self.nth_kind(1) == TokKind::Ident => self.enum_declaration(m),
            b"namespace" | b"module"
                if next_same_line && matches!(self.nth_kind(1), TokKind::Ident | TokKind::String) =>
            {
                self.module_declaration(m)
            }
            b"declare" if next_same_line && self.nth_is_declaration_start(1) => {
                self.bump();
                let inner = self.mark();
                if self.at_kw("global") {
                    self.bump();
                    if self.at_p("{") {
                        self.parse_block();
                    } else {
                        self.semicolon();
                    }
                    self.finish(inner, NodeKind::Stmt);
                } else if self.at_kw("module") && self.nth_p(1, ".") {
                    // `declare module.exports: T;`
                    self.bump();
                    self.bump();
                    self.bump();
                    if self.eat_p(":") {
                        self.parse_type();
                    }
                    self.semicolon();
                    self.finish(inner, NodeKind::Stmt);
                } else if self.at_any_kw(&["var", "let", "const"]) && !self.nth_kw(1, "enum") {
                    self.var_declaration(inner, true);
                } else if self.try_declaration(inner).is_none() {
                    self.error();
                }
                self.finish(m, NodeKind::Ambient)
            }
            b"global" if self.nth_p(1, "{") => {
                self.bump();
                self.parse_block();
                self.finish(m, NodeKind::Stmt)
            }
            _ => return None,
        };
        Some(n)
    }

    /// Can the token `k` ahead start a declaration after `declare`?
    fn nth_is_declaration_start(&mut self, k: usize) -> bool {
        let t = self.peek(k);
        t.kind == TokKind::Ident
            && [
                "var", "let", "const", "function", "async", "class", "abstract", "enum", "interface", "type",
                "namespace", "module", "global",
            ]
            .iter()
            .any(|w| self.text_of(&t) == w.as_bytes())
    }

    /// Decorators before a class declaration, `abstract class` or `export` statement.
    fn decorated_statement(&mut self) -> u32 {
        let m = self.mark();
        while self.at_p("@") {
            self.decorator();
        }
        if self.at_kw("export") {
            return self.export_statement(m);
        }
        if self.at_kw("abstract") && self.nth_kw(1, "class") {
            self.bump();
            return self.class(m, NodeKind::AbstractClassDecl);
        }
        if self.at_kw("class") {
            return self.class(m, NodeKind::ClassDecl);
        }
        self.error();
        self.finish(m, NodeKind::Stmt)
    }

    fn expression_statement(&mut self) -> u32 {
        let m = self.mark();
        self.parse_expression(false);
        self.semicolon();
        self.finish(m, NodeKind::ExprStmt)
    }

    /// `var`/`let`/`const` declarations. `semi` is false inside a `for (…)` header, where `no_in` also applies.
    fn var_declaration(&mut self, m: Marker, semi: bool) -> u32 {
        self.bump(); // var, let or const
        loop {
            self.declarator(!semi);
            if self.bail() || !self.eat_p(",") {
                break;
            }
        }
        if semi {
            self.semicolon();
        }
        self.finish(m, NodeKind::VarDecl)
    }

    /// `pattern [!] [: type] [= value]`.
    fn declarator(&mut self, no_in: bool) -> u32 {
        let m = self.mark();
        let pm = self.mark();
        self.binding_pattern();
        let name = self.finish(pm, NodeKind::Pattern);
        self.set_field(name, Field::Name);
        self.eat_p("!");
        if self.eat_p(":") {
            self.parse_type();
        }
        if self.eat_p("=") {
            let v = self.parse_assignment(no_in);
            self.set_field(v, Field::Value);
        }
        self.finish(m, NodeKind::Declarator)
    }

    /// `[async] function [*] name<T>(params): R { body }`, or a signature without body (overload, ambient).
    fn function_declaration(&mut self, m: Marker) -> u32 {
        self.eat_kw("async");
        self.bump(); // function
        let generator = self.eat_p("*");
        if self.cur().kind == TokKind::Ident && !self.at_p("(") {
            self.decl_name();
        } else {
            self.error();
        }
        self.call_signature();
        if self.at_p("{") {
            self.parse_block();
            self.finish(m, if generator { NodeKind::GeneratorDecl } else { NodeKind::FunctionDecl })
        } else {
            self.semicolon();
            self.finish(m, NodeKind::FunctionSig)
        }
    }

    /// `<T>(params): R`, the part of every function, method and signature between name and body. The parameter list
    /// gets field `Params`.
    fn call_signature(&mut self) {
        if self.at_p("<") {
            self.type_parameters();
        }
        self.parameters();
        if self.eat_p(":") {
            self.return_type();
        }
    }

    /// A class declaration or expression; `m` may include decorators and `abstract`. The current token is `class`.
    fn class(&mut self, m: Marker, kind: NodeKind) -> u32 {
        self.bump(); // class
        if self.at_identifier() && !self.at_kw("implements") {
            self.decl_name();
        } else if kind != NodeKind::ClassExpr {
            self.error();
        }
        if self.at_p("<") {
            self.type_parameters();
        }
        if self.eat_kw("extends") {
            loop {
                self.parse_lhs_expression();
                if self.at_p("<") {
                    self.type_arguments();
                }
                if self.bail() || !self.eat_p(",") {
                    break;
                }
            }
        }
        if self.eat_kw("implements") {
            loop {
                self.parse_type();
                if self.bail() || !self.eat_p(",") {
                    break;
                }
            }
        }
        self.class_body();
        self.finish(m, kind)
    }

    fn class_body(&mut self) -> u32 {
        let m = self.mark();
        self.expect_p("{");
        while !self.at_p("}") && !self.at_eof() && !self.bail() {
            let before = self.pos;
            self.class_member();
            if self.pos == before {
                self.error_node();
            }
        }
        self.expect_p("}");
        let n = self.finish(m, NodeKind::ClassBody);
        self.set_field(n, Field::Body);
        n
    }

    /// Can a modifier at the current position really be a modifier, given the token after it? (TypeScript's
    /// `nextTokenCanFollowModifier`: a property name, `[`, `{`, `*`, `...` or `#name`, on the same line except after
    /// `static`, `get` and `set`.)
    fn modifier_follows(&mut self) -> bool {
        let next = self.peek(1);
        let same_line_needed = !self.at_any_kw(&["static", "get", "set"]);
        if same_line_needed && next.nl_before {
            return false;
        }
        self.nth_property_name(1) || self.nth_p(1, "{") || self.nth_p(1, "*") || self.nth_p(1, "...")
    }

    /// One class member. Decorators belong to a field (they start its span) but are separate siblings before a method.
    fn class_member(&mut self) {
        if self.eat_p(";") {
            return;
        }
        if !self.enter() {
            self.error_node();
            return;
        }
        let m = self.mark();
        while self.at_p("@") {
            self.decorator();
        }
        if self.at_kw("static") && self.nth_p(1, "{") {
            self.bump();
            self.parse_block();
            self.finish(m, NodeKind::StaticBlock);
            self.leave();
            return;
        }
        let mm = self.mark();
        loop {
            if self.at_any_kw(&MODIFIERS) || self.at_any_kw(&["get", "set"]) {
                if self.modifier_follows() {
                    self.bump();
                    continue;
                }
            }
            break;
        }
        self.eat_p("*");
        if self.at_p("[") && self.nth_identifier(1) && self.nth_p(2, ":") {
            self.index_signature_rest(mm);
            self.member_separator();
            self.leave();
            return;
        }
        self.property_name();
        if !self.eat_p("?") {
            self.eat_p("!");
        }
        if self.at_p("(") || self.at_p("<") {
            self.call_signature();
            if self.at_p("{") {
                self.parse_block();
                self.finish(mm, NodeKind::MethodDef);
                self.eat_p(";");
            } else {
                self.finish(mm, NodeKind::MethodSig);
                self.member_separator();
            }
        } else {
            if self.eat_p(":") {
                self.parse_type();
            }
            if self.eat_p("=") {
                let v = self.parse_assignment(false);
                self.set_field(v, Field::Value);
            }
            self.finish(m, NodeKind::FieldDef);
            self.member_separator();
        }
        self.leave();
    }

    /// `;` or `,` after a class member, or automatic semicolon insertion.
    fn member_separator(&mut self) {
        if self.eat_p(";") || self.eat_p(",") {
            return;
        }
        if !self.can_end_statement() {
            self.error();
        }
    }

    /// The rest of an index signature `[key: K]: V` from the `[` (modifiers already consumed with `m`).
    fn index_signature_rest(&mut self, m: Marker) -> u32 {
        self.bump(); // [
        self.bump_ref(); // the key name counts as a reference
        self.bump(); // :
        self.parse_type();
        self.expect_p("]");
        if self.eat_p(":") {
            self.parse_type();
        }
        self.finish(m, NodeKind::IndexSig)
    }

    fn decorator(&mut self) -> u32 {
        let m = self.mark();
        self.bump(); // @
        if self.at_p("(") {
            self.parse_primary();
        } else if self.cur().kind == TokKind::Ident {
            self.bump_ref();
            while self.at_p(".") {
                self.bump();
                if matches!(self.cur().kind, TokKind::Ident | TokKind::PrivateName) {
                    self.bump();
                } else {
                    self.error();
                    break;
                }
            }
            if self.at_p("<") {
                self.type_arguments();
            }
            if self.at_p("(") {
                self.arguments();
            }
        } else {
            self.error();
        }
        self.finish(m, NodeKind::Decorator)
    }

    fn interface_declaration(&mut self, m: Marker) -> u32 {
        self.bump(); // interface
        self.decl_name();
        if self.at_p("<") {
            self.type_parameters();
        }
        if self.eat_kw("extends") {
            loop {
                self.parse_type();
                if self.bail() || !self.eat_p(",") {
                    break;
                }
            }
        }
        let body = self.object_type();
        self.set_field(body, Field::Body);
        self.finish(m, NodeKind::Interface)
    }

    fn type_alias(&mut self, m: Marker) -> u32 {
        self.bump(); // type
        self.decl_name();
        if self.at_p("<") {
            self.type_parameters();
        }
        self.expect_p("=");
        let v = self.parse_type();
        self.set_field(v, Field::Value);
        self.semicolon();
        self.finish(m, NodeKind::TypeAlias)
    }

    /// `[const] enum E { A, B = 1 }`.
    fn enum_declaration(&mut self, m: Marker) -> u32 {
        self.eat_kw("const");
        self.bump(); // enum
        self.decl_name();
        let bm = self.mark();
        self.expect_p("{");
        while !self.at_p("}") && !self.at_eof() && !self.bail() {
            let before = self.pos;
            let em = self.mark();
            self.property_name();
            if self.eat_p("=") {
                self.parse_assignment(false);
            }
            self.finish(em, NodeKind::EnumMember);
            if !self.eat_p(",") {
                break;
            }
            if self.pos == before {
                self.error_node();
            }
        }
        self.expect_p("}");
        let body = self.finish(bm, NodeKind::EnumBody);
        self.set_field(body, Field::Body);
        self.finish(m, NodeKind::Enum)
    }

    /// `namespace A.B { }`, `module M { }`, `module "m" { }` or a body-less `declare module "m";`.
    fn module_declaration(&mut self, m: Marker) -> u32 {
        self.bump(); // namespace or module
        let nm = self.mark();
        if self.cur().kind == TokKind::String {
            self.bump();
        } else {
            self.bump_ref();
            while self.at_p(".") && self.nth_kind(1) == TokKind::Ident {
                self.bump();
                self.bump(); // property_identifier
            }
        }
        let name = self.finish(nm, NodeKind::Name);
        self.set_field(name, Field::Name);
        if self.at_p("{") {
            let b = self.parse_block();
            self.set_field(b, Field::Body);
        } else {
            self.semicolon();
        }
        self.finish(m, NodeKind::Module)
    }

    /// `import …;` in all its forms (the current token is `import`).
    fn import_statement(&mut self) -> u32 {
        let m = self.mark();
        self.bump(); // import
        // `type`/`typeof` modifier, unless it is itself the default import (`import type from "m"`)
        if (self.at_kw("type") || self.at_kw("typeof"))
            && (self.nth_p(1, "{") || self.nth_p(1, "*") || (self.nth_kind(1) == TokKind::Ident && !self.nth_kw(1, "from")))
        {
            self.bump();
        }
        if self.cur().kind == TokKind::String {
            let s = self.mark();
            self.bump();
            let src = self.finish(s, NodeKind::Str);
            self.set_field(src, Field::Source);
            self.semicolon();
            return self.finish(m, NodeKind::Import);
        }
        if self.cur().kind == TokKind::Ident && self.nth_p(1, "=") {
            // `import x = require("m")` (no module source for the analyser) or `import A = B.C` (an alias)
            self.bump();
            self.bump();
            let kind = if self.at_kw("require") && self.nth_p(1, "(") { NodeKind::Import } else { NodeKind::ImportAlias };
            self.parse_assignment(false);
            self.semicolon();
            return self.finish(m, kind);
        }
        if self.cur().kind == TokKind::Ident && !self.at_kw("from") || (self.at_kw("from") && self.nth_kw(1, "from")) {
            let d = self.mark();
            self.bump();
            self.finish(d, NodeKind::ImportDefault);
            self.eat_p(",");
        }
        if self.at_p("*") {
            let ns = self.mark();
            self.bump();
            if !self.eat_kw("as") {
                self.error();
            }
            let nm = self.mark();
            if self.cur().kind == TokKind::Ident {
                self.bump();
            } else {
                self.error();
            }
            self.finish(nm, NodeKind::Name);
            self.finish(ns, NodeKind::ImportNamespace);
        } else if self.at_p("{") {
            self.bump();
            while !self.at_p("}") && !self.at_eof() && !self.bail() {
                let sm = self.mark();
                // `type`/`typeof` modifier of one specifier (but `{ type }` and `{ type as x }` import "type")
                if (self.at_kw("type") || self.at_kw("typeof"))
                    && matches!(self.nth_kind(1), TokKind::Ident | TokKind::String)
                    && !(self.nth_kw(1, "as") && !matches!(self.nth_kind(2), TokKind::Ident | TokKind::String))
                {
                    self.bump();
                }
                if !matches!(self.cur().kind, TokKind::Ident | TokKind::String) {
                    self.error();
                    break;
                }
                let nm = self.mark();
                self.bump();
                let name = self.finish(nm, NodeKind::Name);
                self.set_field(name, Field::Name);
                if self.eat_kw("as") {
                    let am = self.mark();
                    if matches!(self.cur().kind, TokKind::Ident | TokKind::String) {
                        self.bump();
                    } else {
                        self.error();
                    }
                    let alias = self.finish(am, NodeKind::Name);
                    self.set_field(alias, Field::Alias);
                }
                self.finish(sm, NodeKind::ImportSpecifier);
                if !self.eat_p(",") {
                    break;
                }
            }
            self.expect_p("}");
        }
        if self.eat_kw("from") {
            if self.cur().kind == TokKind::String {
                let s = self.mark();
                self.bump();
                let src = self.finish(s, NodeKind::Str);
                self.set_field(src, Field::Source);
            } else {
                self.error();
            }
        } else {
            self.error();
        }
        self.import_attributes();
        self.semicolon();
        self.finish(m, NodeKind::Import)
    }

    /// `with { type: "json" }` / `assert { … }` after an import or re-export.
    fn import_attributes(&mut self) {
        if (self.at_kw("with") || self.at_kw("assert")) && self.nth_p(1, "{") && !self.peek(0).nl_before {
            self.bump();
            self.parse_primary();
        }
    }

    /// `export …` in all its forms; `m` may include decorators.
    fn export_statement(&mut self, m: Marker) -> u32 {
        self.bump(); // export
        if self.at_p("*") {
            self.bump();
            if self.eat_kw("as") {
                self.bump();
            }
            self.expect_kw_from_source();
            self.semicolon();
            return self.finish(m, NodeKind::Export);
        }
        if self.at_p("{") || (self.at_kw("type") && self.nth_p(1, "{")) {
            self.eat_kw("type");
            self.export_clause();
            if self.at_kw("from") {
                self.expect_kw_from_source();
            }
            self.semicolon();
            return self.finish(m, NodeKind::Export);
        }
        if self.eat_p("=") {
            self.parse_expression(false);
            self.semicolon();
            return self.finish(m, NodeKind::Export);
        }
        if self.at_kw("as") && self.nth_kw(1, "namespace") {
            self.bump();
            self.bump();
            self.bump();
            self.semicolon();
            return self.finish(m, NodeKind::Export);
        }
        if self.at_kw("import") {
            let d = self.import_statement();
            self.set_field(d, Field::Decl);
            return self.finish(m, NodeKind::Export);
        }
        if self.eat_kw("default") {
            let dm = self.mark();
            // A named function or class is a declaration; an anonymous one is an expression.
            let named_function = (self.at_kw("function") && (self.nth_kind(1) == TokKind::Ident || (self.nth_p(1, "*") && self.nth_kind(2) == TokKind::Ident)))
                || (self.at_kw("async") && self.nth_kw(1, "function") && !self.peek(1).nl_before
                    && (self.nth_kind(2) == TokKind::Ident || (self.nth_p(2, "*") && self.nth_kind(3) == TokKind::Ident)));
            let named_class = self.at_kw("class") && self.nth_identifier(1) && !self.nth_kw(1, "implements");
            let decl = if named_function {
                Some(self.function_declaration(dm))
            } else if named_class {
                Some(self.class(dm, NodeKind::ClassDecl))
            } else if self.at_kw("abstract") && self.nth_kw(1, "class") {
                self.bump();
                Some(self.class(dm, NodeKind::AbstractClassDecl))
            } else if self.at_kw("interface") && self.nth_kind(1) == TokKind::Ident {
                Some(self.interface_declaration(dm))
            } else {
                None
            };
            match decl {
                Some(d) => self.set_field(d, Field::Decl),
                None => {
                    let v = self.parse_assignment(false);
                    self.set_field(v, Field::Value);
                    self.semicolon();
                }
            }
            return self.finish(m, NodeKind::ExportDefault);
        }
        let dm = self.mark();
        let decl = if self.at_any_kw(&["var", "let", "const"]) && !self.nth_kw(1, "enum") {
            Some(self.var_declaration(dm, true))
        } else if self.at_kw("const") {
            Some(self.enum_declaration(dm))
        } else if self.at_p("@") {
            while self.at_p("@") {
                self.decorator();
            }
            if self.at_kw("abstract") {
                self.bump();
                Some(self.class(dm, NodeKind::AbstractClassDecl))
            } else if self.at_kw("class") {
                Some(self.class(dm, NodeKind::ClassDecl))
            } else {
                None
            }
        } else {
            self.try_declaration(dm)
        };
        match decl {
            Some(d) => self.set_field(d, Field::Decl),
            None => self.error(),
        }
        self.finish(m, NodeKind::Export)
    }

    /// `from "m"` of a re-export.
    fn expect_kw_from_source(&mut self) {
        if !self.eat_kw("from") {
            self.error();
            return;
        }
        if self.cur().kind == TokKind::String {
            self.bump();
        } else {
            self.error();
        }
        self.import_attributes();
    }

    /// `{ a, b as c, type d }` of an export.
    fn export_clause(&mut self) {
        self.expect_p("{");
        while !self.at_p("}") && !self.at_eof() && !self.bail() {
            if (self.at_kw("type") || self.at_kw("typeof")) && matches!(self.nth_kind(1), TokKind::Ident | TokKind::String) && !self.nth_kw(1, "as") {
                self.bump();
            }
            if matches!(self.cur().kind, TokKind::Ident | TokKind::String) {
                self.bump();
            } else {
                self.error();
                break;
            }
            if self.eat_kw("as") {
                if matches!(self.cur().kind, TokKind::Ident | TokKind::String) {
                    self.bump();
                } else {
                    self.error();
                }
            }
            if !self.eat_p(",") {
                break;
            }
        }
        self.expect_p("}");
    }

    /// `if`, loops, `switch`, `try`, jumps, `debugger`, `with`. Decision points are flagged on their keyword.
    fn control_statement(&mut self, m: Marker) -> u32 {
        let t = self.cur();
        let word = self.text_of(&t);
        match word {
            b"if" => {
                // `else if` chains are parsed iteratively (they are not nested deeper), so long chains cannot reach
                // MAX_DEPTH; the analyser only needs the flags, not the nesting.
                loop {
                    self.bump_flag(DECISION);
                    self.paren_expression();
                    self.parse_statement();
                    if !self.eat_kw("else") {
                        break;
                    }
                    if !self.at_kw("if") {
                        self.parse_statement();
                        break;
                    }
                }
            }
            b"for" => {
                self.bump_flag(DECISION);
                self.eat_kw("await");
                self.for_header();
                self.parse_statement();
            }
            b"while" => {
                self.bump_flag(DECISION);
                self.paren_expression();
                self.parse_statement();
            }
            b"do" => {
                self.bump_flag(DECISION);
                self.parse_statement();
                if !self.eat_kw("while") {
                    self.error();
                }
                self.paren_expression();
                self.eat_p(";");
            }
            b"switch" => {
                self.bump();
                self.paren_expression();
                self.expect_p("{");
                while !self.at_p("}") && !self.at_eof() && !self.bail() {
                    if self.at_kw("case") {
                        self.bump_flag(DECISION);
                        self.parse_expression(false);
                        self.expect_p(":");
                    } else if self.at_kw("default") {
                        self.bump();
                        self.expect_p(":");
                    } else {
                        self.error_node();
                        continue;
                    }
                    while !self.at_p("}") && !self.at_kw("case") && !self.at_kw("default") && !self.at_eof() && !self.bail() {
                        let before = self.pos;
                        self.parse_statement();
                        if self.pos == before {
                            self.error_node();
                        }
                    }
                }
                self.expect_p("}");
            }
            b"try" => {
                self.bump();
                self.parse_block();
                if self.at_kw("catch") {
                    self.bump_flag(DECISION);
                    if self.eat_p("(") {
                        self.binding_pattern();
                        if self.eat_p(":") {
                            self.parse_type();
                        }
                        self.expect_p(")");
                    }
                    self.parse_block();
                }
                if self.eat_kw("finally") {
                    self.parse_block();
                }
            }
            b"return" | b"throw" => {
                self.bump();
                if !self.can_end_statement() {
                    self.parse_expression(false);
                }
                self.semicolon();
            }
            b"break" | b"continue" => {
                self.bump();
                if self.cur().kind == TokKind::Ident && !self.cur().nl_before {
                    self.bump(); // label: statement_identifier, not a reference
                }
                self.semicolon();
            }
            b"debugger" => {
                self.bump();
                self.semicolon();
            }
            _ => {
                // with
                self.bump();
                self.paren_expression();
                self.parse_statement();
            }
        }
        self.finish(m, NodeKind::Stmt)
    }

    fn paren_expression(&mut self) {
        self.expect_p("(");
        self.parse_expression(false);
        self.expect_p(")");
    }

    /// `( init; test; update )`, `( x of xs )` or `( k in obj )`.
    fn for_header(&mut self) {
        self.expect_p("(");
        if !self.at_p(";") {
            let is_decl = self.at_kw("var")
                || self.at_kw("const")
                || (self.at_kw("let") && (self.nth_identifier(1) || self.nth_p(1, "[") || self.nth_p(1, "{")));
            if is_decl {
                let m = self.mark();
                self.var_declaration(m, false);
            } else {
                self.parse_expression(true);
            }
            if self.at_kw("of") || self.at_kw("in") {
                self.bump();
                self.parse_expression(false);
                self.expect_p(")");
                return;
            }
        }
        self.expect_p(";");
        if !self.at_p(";") {
            self.parse_expression(false);
        }
        self.expect_p(";");
        if !self.at_p(")") {
            self.parse_expression(false);
        }
        self.expect_p(")");
    }

    // ------------------------------------------------------------------ patterns and parameters

    /// A binding target: identifier (a reference), `{ … }` or `[ … ]` destructuring, or `...rest`.
    fn binding_pattern(&mut self) {
        if !self.enter() {
            self.error_node();
            return;
        }
        if self.eat_p("...") {
            self.binding_pattern();
        } else if self.at_p("{") {
            self.bump();
            while !self.at_p("}") && !self.at_eof() && !self.bail() {
                if self.eat_p("...") {
                    self.binding_pattern();
                } else if self.at_property_name() {
                    let computed = self.at_p("[");
                    let plain = self.at_identifier();
                    self.property_name_unmarked();
                    if self.eat_p(":") {
                        self.binding_element();
                    } else {
                        // shorthand `{ a }` / `{ a = 1 }`: shorthand_property_identifier_pattern, not a reference
                        if !plain || computed {
                            self.error();
                        }
                        if self.eat_p("=") {
                            self.parse_assignment(false);
                        }
                    }
                } else {
                    self.error();
                    break;
                }
                if !self.eat_p(",") {
                    break;
                }
            }
            self.expect_p("}");
        } else if self.at_p("[") {
            self.bump();
            while !self.at_p("]") && !self.at_eof() && !self.bail() {
                if self.eat_p(",") {
                    continue; // hole
                }
                self.binding_element();
                if !self.eat_p(",") {
                    break;
                }
            }
            self.expect_p("]");
        } else if self.at_identifier() {
            self.bump_ref();
        } else {
            self.error();
        }
        self.leave();
    }

    /// A property name without creating a node (inside patterns).
    fn property_name_unmarked(&mut self) {
        if self.at_p("[") {
            self.bump();
            self.parse_assignment(false);
            self.expect_p("]");
        } else {
            self.bump();
        }
    }

    /// `pattern [= default]`.
    fn binding_element(&mut self) {
        self.binding_pattern();
        if self.eat_p("=") {
            self.parse_assignment(false);
        }
    }

    /// `( parameters )` as a `Params` node with field `Params`.
    fn parameters(&mut self) -> u32 {
        let m = self.mark();
        self.expect_p("(");
        while !self.at_p(")") && !self.at_eof() && !self.bail() {
            self.parameter();
            if !self.eat_p(",") {
                break;
            }
        }
        self.expect_p(")");
        let n = self.finish(m, NodeKind::Params);
        self.set_field(n, Field::Params);
        n
    }

    /// `@dec private readonly name?: T = default`, `...rest: T[]` or `this: T`.
    fn parameter(&mut self) -> u32 {
        let m = self.mark();
        while self.at_p("@") {
            self.decorator();
        }
        let mut property = false;
        loop {
            let next_ok = {
                let k = self.peek(1);
                matches!(k.kind, TokKind::Ident) || self.nth_p(1, "{") || self.nth_p(1, "[") || self.nth_p(1, "...")
            };
            if self.at_any_kw(&["public", "private", "protected"]) && next_ok {
                property = true;
                self.bump();
            } else if self.at_any_kw(&["readonly", "override"]) && next_ok {
                self.bump();
            } else {
                break;
            }
        }
        if self.at_kw("this") {
            self.bump();
        } else {
            self.binding_pattern();
        }
        self.eat_p("?");
        if self.eat_p(":") {
            self.parse_type();
        }
        if self.eat_p("=") {
            self.parse_assignment(false);
        }
        self.finish(m, if property { NodeKind::ParamProperty } else { NodeKind::Param })
    }

    // ------------------------------------------------------------------ expressions

    /// Expression with the comma operator.
    fn parse_expression(&mut self, no_in: bool) -> u32 {
        let first = self.parse_assignment(no_in);
        if self.at_p(",") {
            let m = self.precede(first);
            while self.eat_p(",") {
                self.parse_assignment(no_in);
                if self.bail() {
                    break;
                }
            }
            return self.finish(m, NodeKind::Expr);
        }
        first
    }

    /// Assignment-level expression: arrow function, `yield`, conditional, assignment. `no_in` disallows the `in`
    /// operator (in a `for (…)` initializer).
    fn parse_assignment(&mut self, no_in: bool) -> u32 {
        if !self.enter() {
            return self.error_node();
        }
        let n = self.assignment(no_in);
        self.leave();
        n
    }

    fn assignment(&mut self, no_in: bool) -> u32 {
        if let Some(arrow) = self.try_arrow(no_in) {
            return arrow;
        }
        if self.at_kw("yield") && !self.nth_p(1, "=>") {
            let m = self.mark();
            self.bump();
            let star = self.eat_p("*");
            if star || (!self.can_end_statement() && self.at_expression_start()) {
                self.parse_assignment(no_in);
            }
            return self.finish(m, NodeKind::Expr);
        }
        let left = self.conditional(no_in);
        if self.at_p(">") {
            self.rescan(Mode::Greater);
        }
        let t = self.cur();
        if t.kind == TokKind::Punct && ASSIGN_OPS.iter().any(|o| self.text_of(&t) == o.as_bytes()) {
            let m = self.precede(left);
            self.bump();
            self.parse_assignment(no_in);
            return self.finish(m, NodeKind::Expr);
        }
        left
    }

    /// Can the current token start an expression (for `yield` and type-argument disambiguation)?
    fn at_expression_start(&mut self) -> bool {
        let t = self.cur();
        match t.kind {
            TokKind::Eof | TokKind::Invalid => false,
            TokKind::Punct => {
                let s = self.text_of(&t);
                [
                    "(", "[", "{", "+", "-", "!", "~", "++", "--", "<", "/", "/=", "@", "#", "...",
                ]
                .iter()
                .any(|p| s == p.as_bytes())
            }
            TokKind::Ident => !matches!(self.text_of(&t), b"in" | b"of" | b"as" | b"satisfies" | b"instanceof"),
            _ => true,
        }
    }

    /// `test ? a : b`; the `?` is a decision point.
    fn conditional(&mut self, no_in: bool) -> u32 {
        let test = self.binary(0, no_in);
        if self.at_p("?") {
            let m = self.precede(test);
            self.bump_flag(DECISION);
            self.parse_assignment(false);
            self.expect_p(":");
            self.parse_assignment(no_in);
            return self.finish(m, NodeKind::Expr);
        }
        test
    }

    /// Precedence of the binary operator at the current token (0 if it is none). `>` must already be rescanned.
    fn binary_precedence(&mut self, no_in: bool) -> u32 {
        let t = self.cur();
        let s = self.text_of(&t);
        if t.kind == TokKind::Ident {
            return match s {
                b"instanceof" => 8,
                b"in" if !no_in => 8,
                b"as" | b"satisfies" if !t.nl_before => 8,
                _ => 0,
            };
        }
        if t.kind != TokKind::Punct {
            return 0;
        }
        match s {
            b"??" => 1,
            b"||" => 2,
            b"&&" => 3,
            b"|" => 4,
            b"^" => 5,
            b"&" => 6,
            b"==" | b"!=" | b"===" | b"!==" => 7,
            b"<" | b">" | b"<=" | b">=" => 8,
            b"<<" | b">>" | b">>>" => 9,
            b"+" | b"-" => 10,
            b"*" | b"/" | b"%" => 11,
            b"**" => 12,
            _ => 0,
        }
    }

    /// Binary operators by precedence climbing. `&&`, `||` and `??` are decision points.
    fn binary(&mut self, min: u32, no_in: bool) -> u32 {
        let mut left = self.parse_unary();
        loop {
            if self.bail() {
                return left;
            }
            if self.at_p(">") {
                self.rescan(Mode::Greater);
            }
            let prec = self.binary_precedence(no_in);
            if prec == 0 || prec <= min && !(prec == 12 && min == 12) {
                return left;
            }
            let m = self.precede(left);
            if self.at_kw("as") || self.at_kw("satisfies") {
                self.bump();
                if !self.eat_kw("const") {
                    self.parse_type();
                }
                left = self.finish(m, NodeKind::Expr);
                continue;
            }
            let decision = self.at_p("&&") || self.at_p("||") || self.at_p("??");
            self.bump_flag(if decision { DECISION } else { 0 });
            // `**` is right-associative: parse its right side at one level lower (and count the recursion, as a
            // chain of `**` nests without bound)
            if !self.enter() {
                return self.error_node();
            }
            self.binary(if prec == 12 { 11 } else { prec }, no_in);
            self.leave();
            left = self.finish(m, NodeKind::Expr);
        }
    }

    /// Prefix operators, `await`, and (plain TypeScript only) the type assertion `<T>expr`.
    fn parse_unary(&mut self) -> u32 {
        if !self.enter() {
            return self.error_node();
        }
        let n = self.unary();
        self.leave();
        n
    }

    fn unary(&mut self) -> u32 {
        let t = self.cur();
        let s = self.text_of(&t);
        let prefix = match t.kind {
            TokKind::Punct => matches!(s, b"!" | b"~" | b"+" | b"-" | b"++" | b"--"),
            TokKind::Ident => {
                matches!(s, b"typeof" | b"void" | b"delete")
                    || (s == b"await" && !self.nth_p(1, "=>") && !self.nth_p(1, ")") && !self.nth_p(1, ",") && !self.nth_p(1, ";"))
            }
            _ => false,
        };
        if prefix {
            let m = self.mark();
            self.bump();
            self.parse_unary();
            return self.finish(m, NodeKind::Expr);
        }
        if !self.tsx && self.at_p("<") {
            let m = self.mark();
            self.type_arguments();
            self.parse_unary();
            return self.finish(m, NodeKind::Expr);
        }
        let e = self.parse_lhs_expression();
        let t = self.cur();
        if (self.at_p("++") || self.at_p("--")) && !t.nl_before {
            let m = self.precede(e);
            self.bump();
            return self.finish(m, NodeKind::Expr);
        }
        e
    }

    /// Member access, calls (also generic `f<T>()` and tagged templates), non-null `x!`, `new`.
    fn parse_lhs_expression(&mut self) -> u32 {
        let e = if self.at_kw("new") { self.new_expression() } else { self.parse_primary() };
        self.call_tail(e, true)
    }

    /// `new X<T>(args)` and `new.target`. The callee is a member expression without calls.
    fn new_expression(&mut self) -> u32 {
        let m = self.mark();
        if !self.enter() {
            return self.error_node();
        }
        self.bump(); // new
        if self.at_p(".") {
            self.bump();
            self.bump(); // target
            self.leave();
            return self.finish(m, NodeKind::Expr);
        }
        let callee = if self.at_kw("new") { self.new_expression() } else { self.parse_primary() };
        self.call_tail(callee, false);
        if self.at_p("<") {
            self.try_type_arguments_in_expression();
        }
        if self.at_p("(") {
            let a = self.arguments();
            self.set_field(a, Field::Args);
        }
        self.leave();
        self.finish(m, NodeKind::Expr)
    }

    /// The postfix part of a left-hand-side expression. Without `calls` (the callee of `new`), stops before `(`.
    fn call_tail(&mut self, mut e: u32, calls: bool) -> u32 {
        loop {
            if self.bail() {
                return e;
            }
            let t = self.cur();
            if t.kind == TokKind::Template || t.kind == TokKind::TemplateHead {
                if !calls {
                    return e;
                }
                // tagged template: a call with the template as its argument
                let m = self.precede(e);
                let a = self.template();
                self.set_field(a, Field::Args);
                e = self.finish(m, NodeKind::Call);
                continue;
            }
            if t.kind != TokKind::Punct {
                return e;
            }
            let s = self.text_of(&t);
            match s {
                b"." => {
                    let m = self.precede(e);
                    self.bump();
                    if matches!(self.cur().kind, TokKind::Ident | TokKind::PrivateName) {
                        self.bump(); // property_identifier
                    } else {
                        self.error();
                    }
                    e = self.finish(m, NodeKind::Expr);
                }
                b"?." => {
                    let m = self.precede(e);
                    self.bump();
                    if self.at_p("(") || self.at_p("<") {
                        if self.at_p("<") {
                            self.type_arguments();
                        }
                        let a = self.arguments();
                        self.set_field(a, Field::Args);
                        e = self.finish(m, NodeKind::Call);
                        continue;
                    }
                    if self.at_p("[") {
                        self.bump();
                        self.parse_expression(false);
                        self.expect_p("]");
                    } else if matches!(self.cur().kind, TokKind::Ident | TokKind::PrivateName) {
                        self.bump();
                    } else {
                        self.error();
                    }
                    e = self.finish(m, NodeKind::Expr);
                }
                b"[" => {
                    let m = self.precede(e);
                    self.bump();
                    self.parse_expression(false);
                    self.expect_p("]");
                    e = self.finish(m, NodeKind::Expr);
                }
                b"!" if !t.nl_before => {
                    let m = self.precede(e);
                    self.bump();
                    e = self.finish(m, NodeKind::Expr);
                }
                b"(" if calls => {
                    let m = self.precede(e);
                    let a = self.arguments();
                    self.set_field(a, Field::Args);
                    e = self.finish(m, NodeKind::Call);
                }
                b"<" if calls => {
                    // `f<T>(x)`, ``f<T>`…` `` or an instantiation expression `f<T>`; otherwise a comparison
                    let m = self.precede(e);
                    if !self.try_type_arguments_in_expression() {
                        return e;
                    }
                    if self.at_p("(") {
                        let a = self.arguments();
                        self.set_field(a, Field::Args);
                        e = self.finish(m, NodeKind::Call);
                    } else if matches!(self.cur().kind, TokKind::Template | TokKind::TemplateHead) {
                        let a = self.template();
                        self.set_field(a, Field::Args);
                        e = self.finish(m, NodeKind::Call);
                    } else {
                        e = self.finish(m, NodeKind::Expr);
                    }
                }
                _ => return e,
            }
        }
    }

    /// Speculatively parses `<T, U>` after an expression; keeps it only if what follows makes it type arguments
    /// (TypeScript's `canFollowTypeArgumentsInExpression`).
    fn try_type_arguments_in_expression(&mut self) -> bool {
        if self.depth_capped {
            return false;
        }
        let (s, base) = self.begin_spec();
        self.type_arguments();
        let ok = !self.bail() && self.type_arguments_can_follow();
        self.end_spec(s, base, ok)
    }

    fn type_arguments_can_follow(&mut self) -> bool {
        let t = self.cur();
        if self.at_p("(") || matches!(t.kind, TokKind::Template | TokKind::TemplateHead) {
            return true;
        }
        if self.at_p("<") || self.at_p(">") || self.at_p("+") || self.at_p("-") {
            return false;
        }
        if t.nl_before || t.kind == TokKind::Eof {
            return true;
        }
        let binary = self.binary_precedence(false) > 0
            || (t.kind == TokKind::Punct && ASSIGN_OPS.iter().any(|o| self.text_of(&t) == o.as_bytes()))
            || self.at_p("?")
            || self.at_p(",");
        binary || !self.at_expression_start()
    }

    /// `( args )` as an `Args` node.
    fn arguments(&mut self) -> u32 {
        let m = self.mark();
        self.expect_p("(");
        while !self.at_p(")") && !self.at_eof() && !self.bail() {
            if self.at_p("...") {
                let sm = self.mark();
                self.bump();
                self.parse_assignment(false);
                self.finish(sm, NodeKind::Expr);
            } else {
                self.parse_assignment(false);
            }
            if !self.eat_p(",") {
                break;
            }
        }
        self.expect_p(")");
        self.finish(m, NodeKind::Args)
    }

    /// Primary expressions: names, literals, `( )`, arrays, objects, functions, classes, templates, regexes, JSX.
    fn parse_primary(&mut self) -> u32 {
        let t = self.cur();
        let m = self.mark();
        match t.kind {
            TokKind::Ident => {
                let s = self.text_of(&t);
                match s {
                    b"function" => return self.function_expression(m),
                    b"async" if self.nth_kw(1, "function") && !self.peek(1).nl_before => return self.function_expression(m),
                    b"class" => return self.class(m, NodeKind::ClassExpr),
                    b"this" | b"super" | b"null" | b"true" | b"false" | b"import" => {
                        self.bump();
                        return self.finish(m, NodeKind::Expr);
                    }
                    _ => {}
                }
                if RESERVED.iter().any(|w| s == w.as_bytes()) {
                    return self.error_node();
                }
                self.bump_ref();
                self.finish(m, NodeKind::Ident)
            }
            TokKind::String => {
                self.bump();
                self.finish(m, NodeKind::Str)
            }
            TokKind::Number | TokKind::Regex | TokKind::PrivateName => {
                self.bump();
                self.finish(m, NodeKind::Expr)
            }
            TokKind::Template | TokKind::TemplateHead => self.template(),
            TokKind::Punct => {
                let s = self.text_of(&t);
                match s {
                    b"(" => {
                        self.bump();
                        self.parse_expression(false);
                        if self.eat_p(":") {
                            self.parse_type();
                        }
                        self.expect_p(")");
                        self.finish(m, NodeKind::Expr)
                    }
                    b"[" => {
                        self.bump();
                        while !self.at_p("]") && !self.at_eof() && !self.bail() {
                            if self.eat_p(",") {
                                continue;
                            }
                            if self.eat_p("...") {
                                self.parse_assignment(false);
                            } else {
                                self.parse_assignment(false);
                            }
                            if !self.eat_p(",") {
                                break;
                            }
                        }
                        self.expect_p("]");
                        self.finish(m, NodeKind::Expr)
                    }
                    b"{" => self.object_literal(),
                    b"/" | b"/=" => {
                        self.rescan(Mode::Regex);
                        self.bump();
                        self.finish(m, NodeKind::Expr)
                    }
                    b"<" if self.tsx => self.jsx_element(),
                    b"@" => {
                        while self.at_p("@") {
                            self.decorator();
                        }
                        if self.at_kw("class") {
                            return self.class(m, NodeKind::ClassExpr);
                        }
                        self.error();
                        self.finish(m, NodeKind::Error)
                    }
                    _ => self.error_node(),
                }
            }
            _ => self.error_node(),
        }
    }

    /// `[async] function [*] [name](params) { }` as an expression.
    fn function_expression(&mut self, m: Marker) -> u32 {
        self.eat_kw("async");
        self.bump(); // function
        let generator = self.eat_p("*");
        if self.cur().kind == TokKind::Ident {
            self.decl_name();
        }
        self.call_signature();
        self.parse_block();
        self.finish(m, if generator { NodeKind::GeneratorExpr } else { NodeKind::FunctionExpr })
    }

    /// `{ a: 1, b, ...c, m() {}, get x() {}, [k]: v }`. Property names and shorthand properties are not references.
    fn object_literal(&mut self) -> u32 {
        let m = self.mark();
        if !self.enter() {
            return self.error_node();
        }
        self.bump(); // {
        while !self.at_p("}") && !self.at_eof() && !self.bail() {
            if self.eat_p(",") {
                continue;
            }
            let pm = self.mark();
            if self.eat_p("...") {
                self.parse_assignment(false);
                self.finish(pm, NodeKind::Expr);
            } else {
                // `async`, `get`, `set` and `*` are modifiers only when a property name follows
                loop {
                    if self.at_any_kw(&["async", "get", "set"]) && (self.nth_property_name(1) || self.nth_p(1, "*")) && !(self.at_kw("async") && self.peek(1).nl_before) {
                        self.bump();
                        continue;
                    }
                    break;
                }
                self.eat_p("*");
                if !self.at_property_name() {
                    self.error_node();
                    continue;
                }
                self.property_name();
                self.eat_p("?");
                if self.at_p("(") || self.at_p("<") {
                    self.call_signature();
                    self.parse_block();
                } else if self.eat_p(":") {
                    self.parse_assignment(false);
                } else if self.eat_p("=") {
                    self.parse_assignment(false);
                }
                self.finish(pm, NodeKind::Expr);
            }
            if !self.at_p("}") && !self.eat_p(",") {
                self.error();
                break;
            }
        }
        self.expect_p("}");
        self.leave();
        self.finish(m, NodeKind::Expr)
    }

    /// A template literal; substitutions are expressions (or types, for template literal types).
    fn template(&mut self) -> u32 {
        self.template_with(false)
    }

    fn template_with(&mut self, types: bool) -> u32 {
        let m = self.mark();
        if self.cur().kind == TokKind::Template {
            self.bump();
            return self.finish(m, NodeKind::Template);
        }
        if !self.enter() {
            return self.error_node();
        }
        self.bump(); // head
        loop {
            if types {
                self.parse_type();
            } else {
                self.parse_expression(false);
            }
            if !self.at_p("}") {
                self.error();
                break;
            }
            self.rescan(Mode::TemplateContinue);
            let k = self.cur().kind;
            self.bump();
            if k != TokKind::TemplateMiddle || self.bail() {
                break;
            }
        }
        self.leave();
        self.finish(m, NodeKind::Template)
    }

    /// Tries to parse an arrow function at the current position. Plain `x => …` and `async x => …` are recognised
    /// directly; parenthesised parameters (and generic arrows `<T>(…) =>`) speculatively, up to `=>`.
    fn try_arrow(&mut self, no_in: bool) -> Option<u32> {
        let t = self.cur();
        if t.kind == TokKind::Ident {
            let is_async = self.at_kw("async") && !self.peek(1).nl_before;
            // x => …
            if self.at_identifier() && self.nth_p(1, "=>") {
                let m = self.mark();
                let pm = self.mark();
                let qm = self.mark();
                self.bump_ref();
                self.finish(qm, NodeKind::Param);
                let p = self.finish(pm, NodeKind::Params);
                self.set_field(p, Field::Params);
                self.bump(); // =>
                self.arrow_body(no_in);
                return Some(self.finish(m, NodeKind::Arrow));
            }
            // async x => …
            if is_async && self.nth_identifier(1) && self.nth_p(2, "=>") {
                let m = self.mark();
                self.bump();
                let pm = self.mark();
                let qm = self.mark();
                self.bump_ref();
                self.finish(qm, NodeKind::Param);
                let p = self.finish(pm, NodeKind::Params);
                self.set_field(p, Field::Params);
                self.bump();
                self.arrow_body(no_in);
                return Some(self.finish(m, NodeKind::Arrow));
            }
            if !(is_async && (self.nth_p(1, "(") || self.nth_p(1, "<"))) {
                return None;
            }
        } else if !(self.at_p("(") || self.at_p("<")) {
            return None;
        }
        if self.at_p("<") && self.tsx {
            // In TSX `<T>(x) => x` would be JSX; generic arrows are written `<T,>(…)` or `<T extends U>(…)`.
            let generic = (self.nth_kind(1) == TokKind::Ident && (self.nth_p(2, ",") || self.nth_kw(2, "extends")))
                || (self.nth_kw(1, "const") && self.nth_kind(2) == TokKind::Ident);
            if !generic {
                return None;
            }
        }
        let start = t.start;
        if self.depth_capped || self.arrow_failed.contains(&start) {
            return None;
        }
        let (s, base) = self.begin_spec();
        let m = self.mark();
        self.eat_kw("async");
        if self.at_p("<") {
            self.type_parameters();
        }
        if !self.bail() {
            self.parameters();
        }
        if !self.bail() && self.eat_p(":") {
            self.return_type();
        }
        let ok = !self.bail() && self.at_p("=>");
        if !self.end_spec(s, base, ok) {
            self.arrow_failed.insert(start);
            return None;
        }
        self.bump(); // =>
        self.arrow_body(no_in);
        Some(self.finish(m, NodeKind::Arrow))
    }

    fn arrow_body(&mut self, no_in: bool) {
        if self.at_p("{") {
            self.parse_block();
        } else {
            self.parse_assignment(no_in);
        }
    }

    // ------------------------------------------------------------------ JSX

    /// A JSX element or fragment starting at `<` (TSX only). Tag names are references (also for intrinsic elements like
    /// `div`); attribute names and member parts (`<A.B>`'s `B`) are not.
    fn jsx_element(&mut self) -> u32 {
        let m = self.mark();
        if !self.enter() {
            return self.error_node();
        }
        self.bump(); // <
        if self.eat_p(">") {
            self.jsx_children();
            self.leave();
            return self.finish(m, NodeKind::Expr);
        }
        self.jsx_name();
        if self.at_p("<") {
            self.type_arguments();
        }
        loop {
            if self.bail() {
                break;
            }
            if self.at_p("{") {
                self.bump();
                self.eat_p("...");
                self.parse_assignment(false);
                self.expect_p("}");
                continue;
            }
            if self.cur().kind == TokKind::Ident {
                self.rescan(Mode::JsxName);
                self.bump(); // attribute name: property_identifier
                if self.at_p(":") {
                    // namespaced attribute `a:b`: both parts count as references
                    if let Some(t) = self.toks.get_mut(self.pos - 1) {
                        t.flags |= REF;
                    }
                    self.bump();
                    self.rescan(Mode::JsxName);
                    self.bump_flag(REF);
                }
                if self.eat_p("=") {
                    if self.cur().kind == TokKind::String {
                        self.rescan(Mode::JsxString);
                        self.bump();
                    } else if self.at_p("{") {
                        self.bump();
                        self.parse_expression(false);
                        self.expect_p("}");
                    } else if self.at_p("<") {
                        self.jsx_element();
                    } else {
                        self.error();
                    }
                }
                continue;
            }
            break;
        }
        if self.at_p("/") && self.nth_p(1, ">") {
            self.bump();
            self.bump();
            self.leave();
            return self.finish(m, NodeKind::Expr);
        }
        if !self.at_p(">") {
            self.error();
            self.leave();
            return self.finish(m, NodeKind::Expr);
        }
        self.bump();
        self.jsx_children();
        self.leave();
        self.finish(m, NodeKind::Expr)
    }

    /// `Name`, `my-element`, `A.B.C` or `ns:name` after `<` or `</`.
    fn jsx_name(&mut self) {
        if self.cur().kind != TokKind::Ident {
            self.error();
            return;
        }
        self.rescan(Mode::JsxName);
        self.bump_flag(REF);
        if self.at_p(":") {
            self.bump();
            self.rescan(Mode::JsxName);
            self.bump_flag(REF);
            return;
        }
        while self.at_p(".") {
            self.bump();
            if self.cur().kind == TokKind::Ident {
                self.bump(); // property_identifier
            } else {
                self.error();
                return;
            }
        }
    }

    /// Children after an opening tag, up to and including the closing tag.
    fn jsx_children(&mut self) {
        loop {
            if self.bail() {
                return;
            }
            self.rescan(Mode::JsxChild);
            let t = self.cur();
            match t.kind {
                TokKind::JsxText => {
                    self.bump();
                }
                TokKind::Eof => {
                    self.error();
                    return;
                }
                _ => {
                    if self.at_p("{") {
                        self.bump();
                        if !self.at_p("}") {
                            self.eat_p("...");
                            self.parse_expression(false);
                        }
                        self.expect_p("}");
                    } else if self.nth_p(1, "/") {
                        // closing tag `</Name>` or `</>`
                        self.bump();
                        self.bump();
                        if !self.at_p(">") {
                            self.jsx_name();
                        }
                        self.expect_p(">");
                        return;
                    } else {
                        self.jsx_element();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ types

    /// A type, as a node: an `ObjectType` when the whole type is an object literal type, otherwise a `Type` node.
    fn parse_type(&mut self) -> u32 {
        let m = self.mark();
        if !self.enter() {
            return self.error_node();
        }
        self.type_inner(false);
        self.leave();
        // Do not wrap a lone object type, so the analyser can see `type P = { … }`.
        if self.stack.len() == m.stack as usize + 1 {
            let top = *self.stack.last().unwrap_or(&0);
            let node = self.nodes[top as usize];
            if node.kind == NodeKind::ObjectType && node.first == m.tok && node.end == self.pos as u32 {
                return top;
            }
        }
        self.finish(m, NodeKind::Type)
    }

    /// `no_conditional`: in the `extends` clause of a conditional type, which cannot itself be conditional.
    fn type_inner(&mut self, no_conditional: bool) {
        if self.at_kw("new") || (self.at_kw("abstract") && self.nth_kw(1, "new")) {
            self.eat_kw("abstract");
            self.bump();
            if self.at_p("<") {
                self.type_parameters();
            }
            self.parameters();
            self.expect_p("=>");
            self.return_type();
            return;
        }
        if self.at_p("<") || (self.at_p("(") && self.try_function_type()) {
            if self.at_p("<") {
                self.type_parameters();
                self.parameters();
                self.expect_p("=>");
                self.return_type();
            }
            return;
        }
        self.union_type(no_conditional);
        if !no_conditional && self.at_kw("extends") && !self.cur().nl_before {
            self.bump();
            if !self.enter() {
                return;
            }
            self.type_inner(true);
            self.leave();
            self.expect_p("?");
            self.parse_type_no_node();
            self.expect_p(":");
            self.parse_type_no_node();
        }
    }

    /// A nested type that needs no node of its own.
    fn parse_type_no_node(&mut self) {
        if !self.enter() {
            self.error_node();
            return;
        }
        self.type_inner(false);
        self.leave();
    }

    /// Speculatively parses `(params) => R` at `(`. True if it was a function type (already consumed).
    fn try_function_type(&mut self) -> bool {
        let start = self.cur().start;
        if self.depth_capped || self.arrow_failed.contains(&start) {
            return false;
        }
        let (s, base) = self.begin_spec();
        self.parameters();
        let ok = !self.bail() && self.at_p("=>");
        if !self.end_spec(s, base, ok) {
            self.arrow_failed.insert(start);
            return false;
        }
        self.bump();
        self.return_type();
        true
    }

    /// After `:` of a signature: `x is T`, `asserts x [is T]`, `this is T` or a type.
    fn return_type(&mut self) {
        if self.at_kw("asserts") && (self.nth_kind(1) == TokKind::Ident) && !self.peek(1).nl_before {
            self.bump();
            if self.at_kw("this") {
                self.bump();
            } else {
                self.bump_ref();
            }
            if self.eat_kw("is") {
                self.parse_type();
            }
            return;
        }
        if self.cur().kind == TokKind::Ident && self.nth_kw(1, "is") && !self.peek(1).nl_before {
            if self.at_kw("this") {
                self.bump();
            } else {
                self.bump_ref();
            }
            self.bump();
            self.parse_type();
            return;
        }
        self.parse_type();
    }

    fn union_type(&mut self, no_conditional: bool) {
        self.eat_p("|");
        self.intersection_type(no_conditional);
        while !self.bail() && self.eat_p("|") {
            self.intersection_type(no_conditional);
        }
    }

    fn intersection_type(&mut self, no_conditional: bool) {
        self.eat_p("&");
        self.type_operator(no_conditional);
        while !self.bail() && self.eat_p("&") {
            self.type_operator(no_conditional);
        }
    }

    /// `keyof T`, `readonly T[]`, `unique symbol`, `infer U [extends C]`, or a postfix type.
    fn type_operator(&mut self, no_conditional: bool) {
        if !self.enter() {
            self.error_node();
            return;
        }
        if (self.at_kw("keyof") || self.at_kw("readonly") || self.at_kw("unique")) && self.type_operand_follows() {
            self.bump();
            self.type_operator(no_conditional);
        } else if self.at_kw("infer") && self.nth_kind(1) == TokKind::Ident {
            self.bump();
            self.bump_ref();
            if self.at_kw("extends") && !self.cur().nl_before && !self.depth_capped {
                // `infer U extends C` – unless the `extends` starts the conditional type around it (TypeScript's
                // rule: inside the `extends` clause of a conditional type, it is always a constraint)
                let (s, base) = self.begin_spec();
                self.bump();
                self.type_inner(true);
                let ok = !self.bail() && (no_conditional || !self.at_p("?"));
                self.end_spec(s, base, ok);
            }
        } else {
            self.postfix_type();
        }
        self.leave();
    }

    /// Is the token after a type operator keyword the start of a type (otherwise the keyword is a type name)?
    fn type_operand_follows(&mut self) -> bool {
        let k = self.nth_kind(1);
        matches!(k, TokKind::Ident | TokKind::String | TokKind::Number | TokKind::Template | TokKind::TemplateHead)
            || ["(", "[", "{", "<", "-", "*"].iter().any(|p| self.nth_p(1, p))
    }

    /// A primary type followed by `[]` or `[K]` (not across a line break).
    fn postfix_type(&mut self) {
        self.primary_type();
        while !self.bail() && self.at_p("[") && !self.cur().nl_before {
            self.bump();
            if !self.eat_p("]") {
                self.parse_type();
                self.expect_p("]");
            }
        }
    }

    fn primary_type(&mut self) {
        let t = self.cur();
        match t.kind {
            TokKind::Ident => {
                let s = self.text_of(&t);
                match s {
                    b"typeof" => {
                        self.bump();
                        self.type_query();
                        return;
                    }
                    b"import" if self.nth_p(1, "(") => {
                        self.bump();
                        self.arguments();
                        while self.at_p(".") && self.nth_kind(1) == TokKind::Ident {
                            self.bump();
                            self.bump(); // property_identifier
                        }
                        if self.at_p("<") && !self.cur().nl_before {
                            self.type_arguments();
                        }
                        return;
                    }
                    _ => {}
                }
                if PREDEFINED_TYPES.iter().any(|w| s == w.as_bytes()) && !self.nth_p(1, ".") {
                    self.bump();
                    if s == b"unique" {
                        self.eat_kw("symbol");
                    }
                    return;
                }
                // A type reference `A`, `A.B`, `A.B.C<T>`: the first and the last name are references, the ones in
                // between are property names.
                let first = self.bump_flag(REF);
                let mut last = first;
                while self.at_p(".") && self.nth_kind(1) == TokKind::Ident {
                    self.bump();
                    last = self.bump();
                }
                if let Some(t) = self.toks.get_mut(last) {
                    t.flags |= REF;
                }
                if self.at_p("<") && !self.cur().nl_before {
                    self.type_arguments();
                }
            }
            TokKind::String | TokKind::Number => {
                self.bump();
            }
            TokKind::Template | TokKind::TemplateHead => {
                self.template_with(true);
            }
            TokKind::Punct => {
                let s = self.text_of(&t);
                match s {
                    b"-" if self.nth_kind(1) == TokKind::Number => {
                        self.bump();
                        self.bump();
                    }
                    b"(" => {
                        self.bump();
                        self.parse_type();
                        self.expect_p(")");
                    }
                    b"[" => self.tuple_type(),
                    b"{" => {
                        self.object_type();
                    }
                    b"*" => {
                        self.bump();
                    }
                    b"?" => {
                        self.bump();
                        self.primary_type();
                    }
                    _ => {
                        self.error_node();
                    }
                }
            }
            _ => {
                self.error_node();
            }
        }
    }

    /// After `typeof`: `x`, `x.y`, `x[0]`, `import("m").x`, optionally with type arguments `typeof f<T>`. Only the
    /// first name is a reference.
    fn type_query(&mut self) {
        if self.at_kw("import") && self.nth_p(1, "(") {
            self.bump();
            self.arguments();
        } else if self.at_kw("this") {
            self.bump();
        } else if self.cur().kind == TokKind::Ident {
            self.bump_ref();
        } else {
            self.error();
            return;
        }
        loop {
            if (self.at_p(".") || self.at_p("?.")) && matches!(self.nth_kind(1), TokKind::Ident | TokKind::PrivateName) {
                self.bump();
                self.bump();
            } else if self.at_p("[") && !self.cur().nl_before && !self.nth_p(1, "]") {
                self.bump();
                self.parse_type();
                self.expect_p("]");
            } else {
                break;
            }
        }
        if self.at_p("<") && !self.cur().nl_before {
            self.type_arguments();
        }
    }

    /// `[A, b?: B, ...C[]]`. Names of named members are references.
    fn tuple_type(&mut self) {
        self.bump(); // [
        while !self.at_p("]") && !self.at_eof() && !self.bail() {
            self.eat_p("...");
            if self.cur().kind == TokKind::Ident && (self.nth_p(1, ":") || (self.nth_p(1, "?") && self.nth_p(2, ":"))) {
                self.bump_ref();
                self.eat_p("?");
                self.bump(); // :
            }
            self.parse_type();
            self.eat_p("?");
            if !self.eat_p(",") {
                break;
            }
        }
        self.expect_p("]");
    }

    /// `{ members }` as an `ObjectType` node: property, method, call, construct and index signatures, and mapped
    /// type clauses.
    fn object_type(&mut self) -> u32 {
        let m = self.mark();
        if !self.enter() {
            return self.error_node();
        }
        self.expect_p("{");
        while !self.at_p("}") && !self.at_eof() && !self.bail() {
            let before = self.pos;
            self.type_member();
            if !(self.eat_p(";") || self.eat_p(",") || self.can_end_statement()) {
                self.error();
            }
            if self.pos == before {
                self.error_node();
            }
        }
        self.expect_p("}");
        self.leave();
        self.finish(m, NodeKind::ObjectType)
    }

    /// One member of an object type or interface body.
    fn type_member(&mut self) {
        let m = self.mark();
        if self.at_p("(") || self.at_p("<") {
            self.call_signature();
            self.finish(m, NodeKind::CallSig);
            return;
        }
        if self.at_kw("new") && (self.nth_p(1, "(") || self.nth_p(1, "<")) {
            self.bump();
            self.call_signature();
            self.finish(m, NodeKind::ConstructSig);
            return;
        }
        // mapped type modifiers `+readonly` / `-readonly`
        if (self.at_p("+") || self.at_p("-")) && self.nth_kw(1, "readonly") {
            self.bump();
        }
        loop {
            if self.at_any_kw(&["readonly", "public", "private", "protected", "static", "override", "get", "set", "async"])
                && self.modifier_follows()
            {
                self.bump();
                continue;
            }
            break;
        }
        // index signature `[k: K]: V` or mapped type `[K in T as U]?: V`
        if self.at_p("[") && self.nth_kind(1) == TokKind::Ident && (self.nth_p(2, ":") || self.nth_kw(2, "in")) {
            self.bump();
            self.bump_ref();
            if self.eat_kw("in") {
                self.parse_type();
                if self.eat_kw("as") {
                    self.parse_type();
                }
            } else {
                self.bump(); // :
                self.parse_type();
            }
            self.expect_p("]");
            if (self.at_p("+") || self.at_p("-")) && self.nth_p(1, "?") {
                self.bump();
            }
            self.eat_p("?");
            if self.eat_p(":") {
                self.parse_type();
            }
            self.finish(m, NodeKind::IndexSig);
            return;
        }
        self.property_name();
        self.eat_p("?");
        if self.at_p("(") || self.at_p("<") {
            self.call_signature();
            self.finish(m, NodeKind::MethodSig);
            return;
        }
        if self.eat_p(":") {
            self.parse_type();
        }
        self.finish(m, NodeKind::PropertySig);
    }

    /// `<T, U>` type arguments.
    fn type_arguments(&mut self) {
        self.expect_p("<");
        while !self.at_p(">") && !self.at_eof() && !self.bail() {
            self.parse_type();
            if !self.eat_p(",") {
                break;
            }
        }
        self.expect_p(">");
    }

    /// `<const T extends C = D, in out U>` type parameters. Names are references (type_identifier).
    fn type_parameters(&mut self) {
        self.expect_p("<");
        while !self.at_p(">") && !self.at_eof() && !self.bail() {
            // `const`, `in`, `out` modifiers (when another name follows)
            while self.at_any_kw(&["const", "in", "out"]) && self.nth_kind(1) == TokKind::Ident {
                self.bump();
            }
            if self.cur().kind == TokKind::Ident {
                self.bump_ref();
            } else {
                self.error();
                break;
            }
            if self.eat_kw("extends") || self.eat_p(":") {
                self.parse_type();
            }
            if self.eat_p("=") {
                self.parse_type();
            }
            if !self.eat_p(",") {
                break;
            }
        }
        self.expect_p(">");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The identifiers flagged as references, in source order.
    fn refs(code: &str, tsx: bool) -> Vec<String> {
        let t = parse(code, tsx);
        assert!(!t.has_error, "unexpected syntax error at {} in {code:?}", t.first_error);
        (0..t.tokens.len()).filter(|&i| t.tokens[i].flags & REF != 0).map(|i| t.token_text(i).to_string()).collect()
    }

    /// The tokens flagged as decision points, in source order.
    fn decisions(code: &str, tsx: bool) -> Vec<String> {
        let t = parse(code, tsx);
        assert!(!t.has_error, "unexpected syntax error at {} in {code:?}", t.first_error);
        (0..t.tokens.len()).filter(|&i| t.tokens[i].flags & DECISION != 0).map(|i| t.token_text(i).to_string()).collect()
    }

    /// Kinds of the program's top-level statements.
    fn top(code: &str, tsx: bool) -> Vec<NodeKind> {
        let t = parse(code, tsx);
        assert!(!t.has_error, "unexpected syntax error at {} in {code:?}", t.first_error);
        t.children(t.root).iter().map(|&c| t.kind(c)).collect()
    }

    #[test]
    fn regex_versus_division() {
        assert_eq!(refs("let a = b / c / d;", false), ["a", "b", "c", "d"]);
        assert_eq!(refs("let r = /[/'`]+/g.test(s);", false), ["r", "s"]);
        assert_eq!(refs("if (x) /a`b/.exec(y)", false), ["x", "y"]);
        assert_eq!(refs("f(a)/2; g(/=/);", false), ["f", "a", "g"]);
    }

    #[test]
    fn templates_nest_and_close_at_the_right_brace() {
        assert_eq!(refs("const s = `a${b ? `c${d}` : { e }.e}f${g}`;", false), ["s", "b", "d", "g"]);
        assert_eq!(decisions("const s = `${a ? 1 : 2}`;", false), ["?"]);
    }

    #[test]
    fn generics_versus_comparison() {
        assert_eq!(refs("f<T>(x); a < b; c < d > (e);", false), ["f", "T", "x", "a", "b", "c", "d", "e"]);
        assert_eq!(refs("const m = new Map<string, Array<Set<K>>>();", false), ["m", "Map", "Array", "Set", "K"]);
        assert_eq!(refs("x = y >> 2; z >>>= 1; w >= v;", false), ["x", "y", "z", "w", "v"]);
        assert_eq!(refs("const g = <T,>(x: T) => x;", true), ["g", "T", "x", "T", "x"]);
        assert_eq!(refs("const g = <T>(x: T) => <T>x;", false), ["g", "T", "x", "T", "T", "x"]);
    }

    #[test]
    fn arrow_functions_versus_parentheses() {
        let t = parse("a = (b, c) => d; e = (f, g); h = async (i) => i; j = async x => x; k = (l): M => l;", false);
        assert!(!t.has_error);
        let arrows = t.nodes.iter().filter(|n| n.kind == NodeKind::Arrow).count();
        assert_eq!(arrows, 4);
        assert_eq!(decisions("x = a ? (b) : c;", false), ["?"]);
        assert_eq!(decisions("x = a ? (b): c => d : e;", false), ["?"]);
    }

    #[test]
    fn property_names_and_shorthands_are_not_references() {
        assert_eq!(refs("x = { a: b, c, [d]: e, f() { return g.h; }, ...i };", false), ["x", "b", "d", "e", "g", "i"]);
        assert_eq!(refs("const { a, b: c, d = e, ...f } = g;", false), ["c", "e", "f", "g"]);
        assert_eq!(refs("class A { b: C; d() {} #e = f; }", false), ["A", "C", "f"]);
        assert_eq!(refs("interface I { a: B; c(d: E): F }", false), ["I", "B", "d", "E", "F"]);
        assert_eq!(refs("enum E { A = B.C, D }", false), ["E", "B"]);
        assert_eq!(refs("lbl: for (;;) { break lbl; }", false), Vec::<String>::new());
        assert_eq!(refs("let u = undefined;", false), ["u"]);
    }

    #[test]
    fn type_references() {
        assert_eq!(refs("type T = A.B.C<D> | typeof e.f | keyof G | [h: I] | `${J}`;", false), ["T", "A", "C", "D", "e", "G", "h", "I", "J"]);
        assert_eq!(refs("type T<U> = U extends infer V extends W ? V : never;", false), ["T", "U", "U", "V", "W", "V"]);
        assert_eq!(refs("function f(x): x is Y { return true; }", false), ["f", "x", "x", "Y"]);
        assert_eq!(refs("let a: readonly typeof b.c[] = d;", false), ["a", "b", "d"]);
        assert_eq!(refs("let a: Array<typeof import('m')>;", false), ["a", "Array"]);
        assert_eq!(refs("type M = { readonly [K in keyof T as `x${K}`]-?: T[K] };", false), ["M", "K", "T", "K", "T", "K"]);
    }

    #[test]
    fn jsx() {
        let code = "const a = <A.B c=\"d\\\" e:f={g}><div>it's {h} // not a comment</div><I {...j} /></A.B>;";
        assert_eq!(refs(code, true), ["a", "A", "e", "f", "g", "div", "h", "div", "I", "j", "A"]);
        assert_eq!(refs("const f = <><my-el x-y=\"1\">a > b</my-el></>;", true), ["f", "my-el", "my-el"]);
    }

    #[test]
    fn decision_points() {
        let code = "if (a && b || c ?? d) {} else if (e) {} for (;;) {} for (x of y) {} while (z) {} do {} while (w);\n\
                    switch (v) { case 1: break; default: } try {} catch {} x &&= y; let t: A extends B ? C : D; o?.p;";
        assert_eq!(decisions(code, false), ["if", "&&", "||", "??", "if", "for", "for", "while", "do", "case", "catch"]);
    }

    #[test]
    fn declarations() {
        use NodeKind::*;
        let code = "import a, { b as c, type d } from 'm';\nimport * as n from 'n';\nimport x = require('x');\n\
            export default class {}\nexport declare const q: number;\n@dec export abstract class P {}\n\
            declare global { interface Window {} }\nnamespace A.B { export const c = 1; }\ndeclare module 'm' { }\n\
            export const enum E { A }\ntype T = { a: 1 };\nfunction f(): void;\nfunction f() {}\nlet type = 1;\n";
        assert_eq!(
            top(code, false),
            [Import, Import, Import, ExportDefault, Export, Export, Ambient, Module, Ambient, Export, TypeAlias, FunctionSig, FunctionDecl, VarDecl]
        );
        let t = parse("class C { @d x = 1; @d m() {} static { } [k: string]: any; constructor(private a, readonly b) {} o(): void; }", false);
        let body = t.field(t.children(t.root)[0], Field::Body).unwrap();
        let kinds: Vec<_> = t.children(body).iter().map(|&c| t.kind(c)).collect();
        assert_eq!(kinds, [FieldDef, Decorator, MethodDef, StaticBlock, IndexSig, MethodDef, MethodSig]);
        // a field's span includes its decorators, a method's does not
        assert_eq!(t.text(t.children(body)[0]), "@d x = 1");
        assert_eq!(t.text(t.children(body)[2]), "m() {}");
    }

    #[test]
    fn line_spans() {
        let t = parse("\nconst x = 1 // c\n\nclass A {\n  m() {\n  }\n}\n", false);
        let kids = t.children(t.root);
        assert_eq!((t.first_line(kids[0]), t.last_line(kids[0])), (2, 2));
        assert_eq!((t.first_line(kids[1]), t.last_line(kids[1])), (4, 7));
    }

    /// Runs `f` on a thread with a 2 MiB stack, the size of a rayon worker's.
    fn on_small_stack(f: impl FnOnce() + Send + 'static) {
        std::thread::Builder::new().stack_size(2 << 20).spawn(f).unwrap().join().unwrap();
    }

    #[test]
    fn deep_nesting_is_an_error_not_a_crash() {
        on_small_stack(|| {
            // (prefix, opening part, closing part, suffix), nested 20 000 times and 100 times
            let cases = [
                ("", "(", ")", ""), ("", "[", "]", ""), ("", "{", "}", ""), ("", "a = ", "", ""), ("", "!", "", ""),
                ("", "x ? ", " : y", ""), ("", "2 ** ", "2", ""), ("", "<div>", "</div>", ""), ("type T = ", "A<", ">", ""),
                ("", "`${", "}`", ""), ("", "new ", "", ""), ("", "if (x) ", "", ""), ("", "(a) => ", "a", ""),
                ("", "function f() {", "}", ""), ("let ", "[", "]", " = y"), ("type T = ", "keyof ", "", ""),
            ];
            for (prefix, open, close, suffix) in cases {
                let code = format!("{prefix}{}x{}{suffix}", open.repeat(20_000), close.repeat(20_000));
                assert!(parse(&code, true).has_error, "{open}");
                // modest nesting is fine
                let code = format!("{prefix}{}x{}{suffix}", open.repeat(100), close.repeat(100));
                assert!(!parse(&code, true).has_error, "{open}");
            }
            let chain = format!("if (a) {{}}{}", " else if (b) {}".repeat(20_000));
            assert!(!parse(&chain, false).has_error);
        });
    }

    /// A sample with a bit of every construct, for the fuzz test.
    const CORPUS: &str = r#"#!/usr/bin/env node
import React, { useState as useS, type FC } from "react";
import * as path from 'path';
export * from "./a"; export { b as default } from './b';
/** doc */
@Component({ selector: "x" })
export abstract class Store<T extends object = {}> extends Base<T> implements IStore, Other {
  private static readonly items: Map<string, T[]> = new Map();
  #secret = 1; declare x?: number; accessor y = 2;
  constructor(private readonly db: Db, public name = `n${1 + 2}`) { super(db); }
  @Input() get value(): T | undefined { return this.items?.get("a")?.[0]; }
  set value(v) { if (v && !this.x) this.x ??= 1; }
  abstract total(): number;
  async *gen<U>(...args: U[]): AsyncGenerator<U> { for await (const a of args) yield a; }
  onChange = (e: Event): void => { e.preventDefault(); };
}
namespace NS.Inner { export const enum E { A = 1 << 2, B = A | 1, "c" = 3 } }
declare module "m" { export function f(x: number): string; }
type Fn = <A>(a: A, ...rest: [b?: string, ...c: number[]]) => asserts a is NonNullable<A>;
type M<T> = { -readonly [K in keyof T as `get${Capitalize<K & string>}`]+?: () => T[K] } & Record<string, unknown>;
type C<T> = T extends (infer U)[] ? U : T extends Promise<infer V> ? V : never;
interface I extends J, K.L<M> { (x: number): string; new (y: string): I; readonly [k: string]: any; m?<T>(): void; get g(): number }
function overload(a: string): void;
function overload(a: any) { label: switch (typeof a) { case "string": break label; default: return a as unknown as never; } }
const re = /ab+c\/[/]/gi, div = 4 / 2 / 1, tpl = tag`x${re}y`, n = 0x1F_n, f = 1.5e-3;
let obj = { a, b: 2, [c]: 3, d() {}, get e() { return 1 }, async *f() {}, ...g };
const App: FC<{ title: string }> = ({ title }) => <div className="app" onClick={() => setX(x => x + 1)}>{title} it's &amp; <></></div>;
try { throw new Error("x"); } catch (e: unknown) { console.log(e satisfies unknown); } finally { }
do x++; while (x < 10)
for (let i = 0, j = 1; i < j; i++) for (const k in obj) if (k) continue; else break;
export default function* () { yield* other(); }
"#;

    #[test]
    fn corpus_parses_cleanly() {
        let t = parse(CORPUS, true);
        assert!(!t.has_error, "syntax error at {}: {:?}", t.first_error, &CORPUS[t.first_error as usize..][..40]);
    }

    /// Checks the structural invariants of a tree: children lie within their parent's token range, in order.
    fn check_tree(t: &Tree) {
        assert_eq!(t.tokens.last().map(|t| t.kind), Some(TokKind::Eof));
        for (i, n) in t.nodes.iter().enumerate() {
            assert!(n.end as usize <= t.tokens.len());
            let mut prev = n.first;
            for &c in t.children(i as u32) {
                let k = &t.nodes[c as usize];
                assert!(k.first >= prev && k.end <= n.end.max(n.first), "child outside parent");
                prev = k.end.max(k.first);
            }
            let _ = (t.text(i as u32), t.first_line(i as u32), t.last_line(i as u32));
        }
    }

    #[test]
    fn fuzzed_input_never_panics() {
        on_small_stack(|| {
            // A deterministic linear congruential generator, so failures are reproducible.
            let mut seed: u64 = 0x2545F4914F6CDD1D;
            let mut next = move |n: usize| {
                seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                ((seed >> 33) as usize) % n.max(1)
            };
            let base = CORPUS.as_bytes();
            let alphabet = b"(){}[]<>/`'\"$#@!?:;,.=+-*&|\\\n x0";
            for round in 0..1500 {
                let mut bytes = base.to_vec();
                for _ in 0..1 + next(8) {
                    let at = next(bytes.len() + 1);
                    match next(3) {
                        0 if !bytes.is_empty() && at < bytes.len() => {
                            let len = 1 + next(20).min(bytes.len() - at - 1);
                            bytes.drain(at..at + len);
                        }
                        1 => bytes.insert(at, alphabet[next(alphabet.len())]),
                        _ => bytes.truncate(at),
                    }
                }
                // The analyser receives valid UTF-8 (mod.rs maps invalid bytes to Latin-1 characters); do the same.
                let code: String = match String::from_utf8(bytes) {
                    Ok(s) => s,
                    Err(e) => e.into_bytes().iter().map(|&b| b as char).collect(),
                };
                let t = parse(&code, round % 2 == 0);
                check_tree(&t);
            }
            // binary garbage
            let garbage: String = (0..20_000).map(|_| (next(256) as u8) as char).collect();
            check_tree(&parse(&garbage, true));
        });
    }
}
