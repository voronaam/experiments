package dev.codecity.analysis;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * A dependency-free lexer and error-tolerant parser for TypeScript and TSX, producing the small syntax tree the
 * TypeScript analyser ({@link TypeScriptAnalyzer}) works on.
 *
 * <p>It extracts everything the analyser looks at: which declarations a file has, their names and line spans, class and
 * interface members, parameters, decision points and the identifiers that may refer to other declarations. It is not a
 * validator: it accepts a superset of TypeScript and never fails, it only notes whether it had to recover from a syntax
 * error ({@link Tree#hasError}). It replaced tree-sitter-typescript 0.23 and reproduces that grammar's results for all
 * of the above, so the analysis did not change; the snake_case construct names in the comments
 * ({@code method_definition}, …) are that grammar's. It is a faithful port of the Rust edition's
 * {@code typescript_parse.rs} (same token kinds, node kinds, speculation points, error recovery and depth cap), so all
 * editions produce identical output by construction.
 *
 * <h2>Overview</h2>
 * <ul>
 *   <li>The <b>lexer</b> ({@link Lexer}) turns the source's UTF-8 bytes into tokens on demand. JavaScript's lexical
 *       grammar depends on the parser's state (is {@code /} a division or a regular expression? is <code>}</code> the
 *       end of a block or of a template substitution? is {@code >>} one operator or two closing type-argument
 *       brackets? is {@code it's} JSX text or a string?), so the lexer only produces the <em>default</em> reading of a
 *       token, and the parser asks for a <em>rescan</em> in another {@link Mode} where it knows better, exactly like
 *       the TypeScript compiler's scanner does.</li>
 *   <li>The <b>parser</b> ({@link Parser}) is a hand-written recursive-descent parser over that token stream,
 *       following the TypeScript grammar (statements, declarations, expressions with precedence climbing, types,
 *       JSX). Its output is twofold:
 *       <ol>
 *         <li>A flat tree of nodes: only the constructs the analyser needs get a node kind of their own
 *             ({@link NodeKind}); everything else is a generic {@code EXPR}, {@code STMT} or {@code TYPE} node, or no
 *             node at all (most of a type, punctuation). Every node covers a contiguous range of tokens, so its text
 *             and its line span follow from the tokens.</li>
 *         <li><b>Token flags</b> ({@link #REF}, {@link #DECISION}): the parser marks the identifier tokens that are
 *             names in expression, pattern or type position (the names the analyser collects as references) and the
 *             tokens that are decision points for cyclomatic complexity ({@code if}, {@code for}, {@code while}, {@code
 *             do}, {@code case}, {@code catch}, the {@code ?} of a conditional expression, and the {@code &&}, {@code
 *             ||} and {@code ??} operators). The analyser then only has to scan the token range of a node for flags; it
 *             never needs expression nodes for that.</li>
 *       </ol></li>
 * </ul>
 *
 * <h2>Tree layout</h2> Nodes live in parallel arrays ({@link Tree}); the children of a node are a contiguous slice of a
 * second array ({@code kids}) given by {@code kidsStart}/{@code kidsLen}, in source order. The parser builds this
 * bottom-up with a stack: a {@link Marker} remembers the current token and stack height when a construct starts; nodes
 * finished while parsing it are pushed on the stack; {@link Parser#finish} moves the stack entries above the marker
 * into {@code kids} as the new node's children and pushes the new node. {@link Parser#precede} starts a marker
 * <em>before</em> an already finished node (for left-recursive constructs such as {@code a.b}, {@code a + b},
 * {@code f(x)}). Some children carry a {@link Field} tag so the analyser can tell, for example, a variable's name
 * from its value.
 *
 * <h2>Grammar subset</h2> The full statement, expression and type grammar of TypeScript 5 is parsed, including
 * decorators, {@code abstract} and {@code declare}, namespaces and {@code declare module}/{@code global}, enums,
 * overloads, accessors, parameter properties, generics (also generic calls {@code f<T>(x)} and arrow functions {@code
 * <T,>(x: T) => x}), type predicates and assertions, conditional, mapped and template literal types, {@code satisfies},
 * optional chaining, JSX (TSX only) and the type assertion {@code <T>x} (plain TypeScript only). Not modelled: Flow
 * syntax, legacy octal escapes, HTML comments ({@code <!--}), and the distinction between strict and sloppy mode
 * (contextual keywords such as {@code yield}, {@code await} and {@code let} are treated as keywords wherever they can
 * be one, regardless of mode).
 *
 * <h2>Ambiguities</h2>
 * Where TypeScript itself needs unbounded lookahead (arrow functions {@code (a, b) => …} versus parenthesised
 * expressions, generic calls {@code f<T>(x)} versus comparisons, function types {@code (a: T) => U} versus
 * parenthesised types), the parser <em>speculates</em>: it takes a {@link Snapshot}, tries the more specific reading
 * and rolls back to the snapshot if that reading hits an error (see {@link Parser#bail}). A rolled-back attempt drops
 * its nodes and its tokens (they are lexed again, possibly in a different mode). Failed arrow-function attempts are
 * remembered by source offset, so nested parentheses cannot make parsing exponential, and once the depth cap has been
 * reached the parser stops speculating (see {@code Parser.depthCapped}), so input nested past the cap at every token
 * cannot make it quadratic.
 *
 * <h2>Error recovery</h2>
 * A syntax error never stops the parse; it increments the parser's error count (the file is then reported as having
 * parse errors) and the parser carries on:
 * <ul>
 *   <li>an expected token that is missing is treated as present (nothing is consumed);</li>
 *   <li>a token that cannot start the expected construct is wrapped in an {@code ERROR} node and skipped, so every
 *       loop makes progress; statement lists additionally skip any token a statement could not consume;</li>
 *   <li>recursion is capped at {@link #MAX_DEPTH} nested constructs: deeper input is skipped token by token, so
 *       adversarial input (thousands of {@code (}) cannot overflow the stack.</li>
 * </ul>
 * The parser never throws on any input: all indexing into the source and the token list is bounds-checked.
 *
 * <h2>Offsets and text</h2>
 * The source is lexed as UTF-8 bytes (the same bytes the Rust edition sees: {@link SourceText#read} decodes invalid
 * UTF-8 as Latin-1, as the Rust edition does), so token offsets are byte offsets and node text is decoded from them.
 */
final class TypeScriptParse {

    /**
     * Token flag: the token is a name in expression, pattern or type position that may refer to a declaration. Property
     * names ({@code a.b}, {@code { b: 1 }}, class and interface members), shorthand properties ({@code { b }}), labels,
     * JSX attribute names and {@code undefined} are not marked.
     */
    static final int REF = 1;
    /** Token flag: the token adds one to cyclomatic complexity (see the class documentation). */
    static final int DECISION = 2;
    /** Token flag (internal): the lexer found the token malformed (unterminated string, comment, template or regex). */
    private static final int BAD = 4;

    /**
     * Maximum nesting of statements, expressions, types and patterns. Real code stays far below this; deeper input is
     * treated as a syntax error. Chosen so that the deepest recursion fits comfortably into a 1 MiB thread stack (the
     * JVM's default, also for the fork-join workers that parse files in parallel).
     */
    static final int MAX_DEPTH = 400;

    private TypeScriptParse() {
    }

    /** What a token is. Keywords and contextual keywords are {@code IDENT} tokens: the parser compares their text. */
    enum TokKind {
        /** Identifier or keyword, e.g. {@code foo}, {@code if}, {@code type}. JSX names may contain {@code -}. */
        IDENT,
        /** {@code #name} (private class member). */
        PRIVATE_NAME,
        NUMBER,
        /** {@code '…'} or {@code "…"} (in {@code JSX_STRING} mode a JSX attribute string, which has no escapes). */
        STRING,
        /** {@code /…/flags}, only produced by a rescan in {@code REGEX} mode. */
        REGEX,
        /** A template literal without substitutions. */
        TEMPLATE,
        /** The start of a template literal up to its first substitution: <code>`…${</code>. */
        TEMPLATE_HEAD,
        /** The part between two substitutions, only produced by a rescan in {@code TEMPLATE_CONTINUE} mode. */
        TEMPLATE_MIDDLE,
        /** The part after the last substitution, only produced by a rescan in {@code TEMPLATE_CONTINUE} mode. */
        TEMPLATE_TAIL,
        /**
         * Operator or punctuation, e.g. <code>{</code>, {@code =>}, {@code ?.}. The default mode never joins {@code >}
         * with what follows (see {@link Mode#GREATER}), so {@code Array<Array<T>>} closes with two {@code >} tokens.
         */
        PUNCT,
        /** Text between JSX tags, only produced in {@code JSX_CHILD} mode. */
        JSX_TEXT,
        /** A character that cannot start any token (e.g. a stray control character). */
        INVALID,
        /** End of input. The token list always ends with exactly one {@code EOF}. */
        EOF
    }

    /** Lexing modes. {@code NORMAL} is used unless the parser rescans the current token in another mode. */
    enum Mode {
        NORMAL,
        /** The current {@code /} or {@code /=} starts a regular expression literal. */
        REGEX,
        /** The current <code>}</code> ends a template substitution: lex the template text that follows. */
        TEMPLATE_CONTINUE,
        /** The current {@code >} may be the first character of {@code >=}, {@code >>}, {@code >>=}, … */
        GREATER,
        /** Inside a JSX element's children: text up to the next <code>{</code> or {@code <} (no trivia skipping). */
        JSX_CHILD,
        /** A JSX tag or attribute name, which may contain {@code -}. */
        JSX_NAME,
        /** A JSX attribute string: quoted, no escape sequences. */
        JSX_STRING
    }

    /**
     * The token list, as parallel arrays (one entry per token) so that a file's tokens cost no objects. Tokens are
     * appended by the lexer and truncated by rescans and rolled-back speculation. Appending may replace the arrays, so
     * the parser always computes a token index (which may lex) before reading an array field: in
     * {@code toks.kind[peek(k)]} Java would read the field first and index the old array.
     */
    static final class Tokens {
        TokKind[] kind = new TokKind[64];
        /** Byte offsets into the source, {@code start..end}. */
        int[] start = new int[64];
        int[] end = new int[64];
        /** 1-based line of {@code start}, and of {@code end} (the number of {@code \n} before the offset, plus one). */
        int[] line = new int[64];
        int[] endLine = new int[64];
        /**
         * True when a line break (possibly inside a comment) separates this token from the previous one. Drives
         * automatic semicolon insertion and the "no line break here" rules.
         */
        boolean[] nlBefore = new boolean[64];
        /** {@link #REF}, {@link #DECISION} (set by the parser) and the internal {@code BAD} flag (set by the lexer). */
        byte[] flags = new byte[64];
        int size;

        Tokens(int capacity) {
            grow(Math.max(capacity, 16));
        }

        private void grow(int capacity) {
            kind = Arrays.copyOf(kind, capacity);
            start = Arrays.copyOf(start, capacity);
            end = Arrays.copyOf(end, capacity);
            line = Arrays.copyOf(line, capacity);
            endLine = Arrays.copyOf(endLine, capacity);
            nlBefore = Arrays.copyOf(nlBefore, capacity);
            flags = Arrays.copyOf(flags, capacity);
        }

        void push(TokKind k, int s, int e, int l, int el, boolean nl, int f) {
            if (size == kind.length) {
                grow(size * 2);
            }
            kind[size] = k;
            start[size] = s;
            end[size] = e;
            line[size] = l;
            endLine[size] = el;
            nlBefore[size] = nl;
            flags[size] = (byte) f;
            size++;
        }

        /** Drops the tokens from index {@code n} on (no-op if there are fewer). */
        void truncate(int n) {
            if (n < size) {
                size = n;
            }
        }
    }

    /**
     * Punctuators of the default mode, longest first so the first match is the longest one. {@code >} is deliberately
     * only a single character here (see {@link Mode#GREATER}); {@code ?.} is handled separately (it must not be
     * followed by a digit). The single characters {@code ? : = @ . #} come last.
     */
    private static final String[] PUNCTUATORS = {
            "...", "===", "!==", "**=", "<<=", "&&=", "||=", "??=", "=>", "==", "!=", "<=", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "&&", "||", "??", "++", "--", "**", "<<", "{", "}", "(", ")", "[", "]", ";", ",", "<", ">",
            "+", "-", "*", "/", "%", "&", "|", "^", "!", "~",
            "?", ":", "=", "@", ".", "#",
    };

    /** The operators a {@code >} becomes in {@link Mode#GREATER}, longest first. */
    private static final String[] GREATER_OPS = {">>>=", ">>>", ">>=", ">>", ">="};

    static boolean isIdStart(int b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || b == '_' || b == '$' || b >= 0x80;
    }

    static boolean isIdPart(int b) {
        return isIdStart(b) || isDigit(b);
    }

    private static boolean isDigit(int b) {
        return b >= '0' && b <= '9';
    }

    private static boolean isAlphanumeric(int b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || isDigit(b);
    }

    private static boolean isHexDigit(int b) {
        return isDigit(b) || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    // ------------------------------------------------------------------------------------------------------- lexer

    /**
     * The token producer. Stateless apart from the source: every call lexes one token starting at a given offset,
     * which makes rescanning a token in another mode trivial.
     */
    static final class Lexer {
        private final byte[] src;
        private final int n;
        /** Byte offset of the start of every line ({@code 0}, then one past every {@code \n}), for line numbers. */
        private final int[] lineStarts;
        private final int lineCount;
        // Secondary results of skipTrivia and scanTemplate (the lexer is used by one parser on one thread).
        private boolean triviaNewline;
        private boolean triviaBadComment;
        private boolean templateHead;
        private boolean templateBad;

        Lexer(byte[] src) {
            this.src = src;
            this.n = src.length;
            int count = 1;
            for (byte b : src) {
                if (b == '\n') {
                    count++;
                }
            }
            lineStarts = new int[count];
            int k = 1;
            for (int i = 0; i < n; i++) {
                if (src[i] == '\n') {
                    lineStarts[k++] = i + 1;
                }
            }
            lineCount = count;
        }

        /** 1-based line of a byte offset: one plus the number of {@code \n} before it. */
        int lineOf(int offset) {
            // the number of line starts <= offset
            int lo = 0;
            int hi = lineCount;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (lineStarts[mid] <= offset) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /** The byte at {@code i} as 0..255, or 0 beyond the end. */
        private int b(int i) {
            return i >= 0 && i < n ? src[i] & 0xFF : 0;
        }

        /**
         * Length of a non-ASCII whitespace character starting at {@code i} (U+00A0, U+1680, U+2000–U+200B, U+2028,
         * U+2029, U+202F, U+205F, U+2060, U+3000, U+FEFF), or 0. Such characters separate tokens; any other non-ASCII
         * character is part of an identifier.
         */
        private int unicodeSpace(int i) {
            int a = b(i);
            int x = b(i + 1);
            int c = b(i + 2);
            switch (a) {
                case 0xC2:
                    return x == 0xA0 ? 2 : 0;
                case 0xE1:
                    return x == 0x9A && c == 0x80 ? 3 : 0;
                case 0xE2:
                    if (x == 0x80 && ((c >= 0x80 && c <= 0x8B) || c == 0xA8 || c == 0xA9 || c == 0xAF)) {
                        return 3;
                    }
                    return x == 0x81 && (c == 0x9F || c == 0xA0) ? 3 : 0;
                case 0xE3:
                    return x == 0x80 && c == 0x80 ? 3 : 0;
                case 0xEF:
                    return x == 0xBB && c == 0xBF ? 3 : 0;
                default:
                    return 0;
            }
        }

        /**
         * Skips whitespace and comments from {@code i}. Returns the new offset; {@link #triviaNewline} tells whether a
         * line break was crossed and {@link #triviaBadComment} whether a block comment was left unterminated.
         */
        private int skipTrivia(int i) {
            boolean nl = false;
            triviaBadComment = false;
            // A hashbang line (#!/usr/bin/env node) is only allowed at the very start of the file.
            if (i == 0 && b(0) == '#' && b(1) == '!') {
                while (i < n && src[i] != '\n') {
                    i++;
                }
            }
            while (i < n) {
                int c = src[i] & 0xFF;
                if (c == ' ' || c == '\t' || c == '\r' || c == 0x0B || c == 0x0C) {
                    i++;
                } else if (c == '\n') {
                    nl = true;
                    i++;
                } else if (c == '/' && b(i + 1) == '/') {
                    while (i < n && src[i] != '\n') {
                        i++;
                    }
                } else if (c == '/' && b(i + 1) == '*') {
                    i += 2;
                    while (true) {
                        if (i >= n) {
                            triviaNewline = nl;
                            triviaBadComment = true;
                            return n;
                        }
                        if (src[i] == '*' && b(i + 1) == '/') {
                            i += 2;
                            break;
                        }
                        if (src[i] == '\n') {
                            nl = true;
                        }
                        i++;
                    }
                } else if (c >= 0x80) {
                    int len = unicodeSpace(i);
                    if (len == 0) {
                        break;
                    }
                    // U+2028 and U+2029 are line terminators.
                    if (len == 3 && c == 0xE2 && b(i + 1) == 0x80 && (b(i + 2) == 0xA8 || b(i + 2) == 0xA9)) {
                        nl = true;
                    }
                    i += len;
                } else {
                    break;
                }
            }
            triviaNewline = nl;
            return i;
        }

        /** Appends a token over {@code start..end} to {@code out}. */
        private void make(Tokens out, TokKind kind, int start, int end, boolean nlBefore, boolean bad) {
            out.push(kind, start, end, lineOf(start), lineOf(end), nlBefore, bad ? BAD : 0);
        }

        /** Lexes one token starting at byte offset {@code from} (the end of the previous token) in the given mode. */
        void lex(int from, Mode mode, Tokens out) {
            from = Math.min(Math.max(from, 0), n);
            if (mode == Mode.JSX_CHILD) {
                // JSX text keeps its whitespace and has no comments, so no trivia is skipped.
                int c = b(from);
                if (from >= n) {
                    make(out, TokKind.EOF, n, n, false, false);
                    return;
                }
                if (c == '{' || c == '<') {
                    make(out, TokKind.PUNCT, from, from + 1, false, false);
                    return;
                }
                int i = from;
                while (i < n && src[i] != '{' && src[i] != '<') {
                    i++;
                }
                make(out, TokKind.JSX_TEXT, from, i, false, false);
                return;
            }
            int i = skipTrivia(from);
            boolean nl = triviaNewline;
            boolean badComment = triviaBadComment;
            if (i >= n) {
                make(out, TokKind.EOF, n, n, nl, badComment);
                return;
            }
            int c = src[i] & 0xFF;
            switch (mode) {
                case REGEX -> {
                    if (c == '/') {
                        lexRegex(out, i, nl);
                        return;
                    }
                }
                case TEMPLATE_CONTINUE -> {
                    if (c == '}') {
                        int end = scanTemplate(i + 1);
                        make(out, templateHead ? TokKind.TEMPLATE_MIDDLE : TokKind.TEMPLATE_TAIL, i, end, nl,
                                templateBad);
                        return;
                    }
                }
                case GREATER -> {
                    if (c == '>') {
                        int len = 1;
                        for (String p : GREATER_OPS) {
                            if (startsWith(i, p)) {
                                len = p.length();
                                break;
                            }
                        }
                        make(out, TokKind.PUNCT, i, i + len, nl, false);
                        return;
                    }
                }
                case JSX_NAME -> {
                    if (isIdStart(c)) {
                        int j = i;
                        while (j < n && (isIdPart(src[j] & 0xFF) || src[j] == '-') && unicodeSpace(j) == 0) {
                            j++;
                        }
                        make(out, TokKind.IDENT, i, j, nl, false);
                        return;
                    }
                }
                case JSX_STRING -> {
                    if (c == '"' || c == '\'') {
                        int j = i + 1;
                        while (j < n && (src[j] & 0xFF) != c) {
                            j++;
                        }
                        make(out, TokKind.STRING, i, Math.min(j + 1, n), nl, j >= n);
                        return;
                    }
                }
                default -> {
                }
            }
            lexNormal(out, i, nl, badComment);
        }

        /** Does the source at {@code i} start with the ASCII string {@code p}? */
        private boolean startsWith(int i, String p) {
            if (i + p.length() > n) {
                return false;
            }
            for (int k = 0; k < p.length(); k++) {
                if (src[i + k] != p.charAt(k)) {
                    return false;
                }
            }
            return true;
        }

        /** The default reading of the token at {@code i} (trivia already skipped). */
        private void lexNormal(Tokens out, int i, boolean nl, boolean badComment) {
            int c = src[i] & 0xFF;
            // identifiers and keywords (including \\uXXXX escapes)
            if ((isIdStart(c) && unicodeSpace(i) == 0) || (c == '\\' && b(i + 1) == 'u')) {
                make(out, TokKind.IDENT, i, scanIdent(i), nl, badComment);
                return;
            }
            if (isDigit(c) || (c == '.' && isDigit(b(i + 1)))) {
                make(out, TokKind.NUMBER, i, scanNumber(i), nl, badComment);
                return;
            }
            switch (c) {
                case '"', '\'' -> {
                    int j = i + 1;
                    boolean bad = true;
                    while (j < n) {
                        int d = src[j] & 0xFF;
                        if (d == c) {
                            j++;
                            bad = false;
                            break;
                        }
                        if (d == '\n') {
                            break;
                        }
                        if (d == '\\') {
                            // \ + CR LF is a line continuation of two bytes after the backslash
                            j += b(j + 1) == '\r' && b(j + 2) == '\n' ? 3 : 2;
                            continue;
                        }
                        j++;
                    }
                    make(out, TokKind.STRING, i, Math.min(j, n), nl, bad || badComment);
                    return;
                }
                case '`' -> {
                    int end = scanTemplate(i + 1);
                    make(out, templateHead ? TokKind.TEMPLATE_HEAD : TokKind.TEMPLATE, i, end, nl,
                            templateBad || badComment);
                    return;
                }
                case '#' -> {
                    if (isIdStart(b(i + 1)) || b(i + 1) == '\\') {
                        make(out, TokKind.PRIVATE_NAME, i, scanIdent(i + 1), nl, badComment);
                        return;
                    }
                }
                case '?' -> {
                    if (b(i + 1) == '.' && !isDigit(b(i + 2))) {
                        make(out, TokKind.PUNCT, i, i + 2, nl, badComment);
                        return;
                    }
                }
                default -> {
                }
            }
            for (String p : PUNCTUATORS) {
                if (startsWith(i, p)) {
                    make(out, TokKind.PUNCT, i, i + p.length(), nl, badComment);
                    return;
                }
            }
            // Anything else (a control character, a lone backslash): one byte, or a whole UTF-8 sequence.
            int j = i + 1;
            while (j < n && (src[j] & 0xC0) == 0x80) {
                j++;
            }
            make(out, TokKind.INVALID, i, j, nl, true);
        }

        /** End of an identifier starting at {@code i}: identifier characters and {@code \}{@code u} escapes. */
        private int scanIdent(int i) {
            while (i < n) {
                int c = src[i] & 0xFF;
                if (c == '\\' && b(i + 1) == 'u') {
                    i += 2;
                    if (b(i) == '{') {
                        while (i < n && src[i] != '}') {
                            i++;
                        }
                        i = Math.min(i + 1, n);
                    } else {
                        int k = 0;
                        while (k < 4 && i < n && isHexDigit(src[i] & 0xFF)) {
                            i++;
                            k++;
                        }
                    }
                } else if (isIdPart(c) && unicodeSpace(i) == 0) {
                    i++;
                } else {
                    break;
                }
            }
            return i;
        }

        /**
         * End of a numeric literal starting at {@code i}: digits, letters (hex digits, prefixes, {@code e}, {@code n}),
         * {@code _} and {@code .}, and a sign directly after an exponent {@code e} of a decimal number.
         */
        private int scanNumber(int i) {
            boolean hex = b(i) == '0' && (b(i + 1) == 'x' || b(i + 1) == 'X');
            boolean dot = false;
            while (i < n) {
                int c = src[i] & 0xFF;
                if (c == '.') {
                    // 1..toString(): only one dot belongs to the number
                    if (dot || hex) {
                        break;
                    }
                    dot = true;
                    i++;
                } else if ((c == 'e' || c == 'E') && !hex && (b(i + 1) == '+' || b(i + 1) == '-')) {
                    i += 2;
                } else if (isAlphanumeric(c) || c == '_') {
                    i++;
                } else {
                    break;
                }
            }
            return i;
        }

        /**
         * Scans template text starting at {@code i} (just after the backtick or <code>}</code>) up to the closing
         * backtick or the next <code>${</code>. Returns the end offset; {@link #templateHead} tells whether it stopped
         * at <code>${</code> (a head or middle part) and {@link #templateBad} whether input ended first.
         */
        private int scanTemplate(int i) {
            templateHead = false;
            templateBad = false;
            while (i < n) {
                int c = src[i] & 0xFF;
                if (c == '`') {
                    return i + 1;
                } else if (c == '\\') {
                    i += 2;
                } else if (c == '$' && b(i + 1) == '{') {
                    templateHead = true;
                    return i + 2;
                } else {
                    i++;
                }
            }
            templateBad = true;
            return n;
        }

        /** A regular expression literal at {@code i}: up to the closing {@code /} outside a class, then the flags. */
        private void lexRegex(Tokens out, int i, boolean nl) {
            int j = i + 1;
            boolean inClass = false;
            boolean bad = true;
            while (j < n) {
                int c = src[j] & 0xFF;
                if (c == '\\') {
                    if (b(j + 1) == '\n') {
                        break;
                    }
                    j += 2;
                    continue;
                }
                if (c == '\n') {
                    break;
                }
                if (c == '[') {
                    inClass = true;
                } else if (c == ']') {
                    inClass = false;
                } else if (c == '/' && !inClass) {
                    j++;
                    bad = false;
                    break;
                }
                j++;
            }
            j = Math.min(j, n);
            int k = j;
            if (!bad) {
                while (k < n && isIdPart(src[k] & 0xFF) && (src[k] & 0xFF) < 0x80) {
                    k++;
                }
            }
            make(out, TokKind.REGEX, i, k, nl, bad);
        }
    }

    // -------------------------------------------------------------------------------------------------------- tree

    /**
     * Node kinds. Most correspond to one grammar construct (whose grammar name the comments give); constructs the
     * analyser never distinguishes share {@code STMT}, {@code EXPR} or {@code TYPE}.
     */
    enum NodeKind {
        /** The whole file; children are statements ({@code program}). */
        PROGRAM,
        /** <code>{ statements }</code>: a block statement, function body or namespace body ({@code statement_block}). */
        BLOCK,
        /** {@code expr;} ({@code expression_statement}); its first child is the expression. */
        EXPR_STMT,
        /** Any other statement ({@code if}, {@code for}, {@code return}, …), labelled or empty statement. */
        STMT,
        /**
         * {@code import … from "m";} ({@code import_statement}). Children: {@code IMPORT_DEFAULT},
         * {@code IMPORT_NAMESPACE}, {@code IMPORT_SPECIFIER}, and the module specifier string with field
         * {@code SOURCE} (absent for {@code import x = require("m")}).
         */
        IMPORT,
        /** {@code D} in {@code import D from "m"}. */
        IMPORT_DEFAULT,
        /** {@code * as N} in {@code import * as N from "m"}; its child {@code NAME} is {@code N}. */
        IMPORT_NAMESPACE,
        /** {@code a} or {@code a as b} inside an import list; children {@code NAME} (field NAME), optionally ALIAS. */
        IMPORT_SPECIFIER,
        /** {@code import A = B.C;} ({@code import_alias}). */
        IMPORT_ALIAS,
        /**
         * {@code export …} without {@code default} ({@code export_statement}): the exported declaration has field
         * {@code DECL}; re-exports and {@code export = x} have no {@code DECL} child.
         */
        EXPORT,
        /** {@code export default …}: a declaration (field {@code DECL}) or an expression (field {@code VALUE}). */
        EXPORT_DEFAULT,
        /** {@code declare …} ({@code ambient_declaration}); its first child is the declaration. */
        AMBIENT,
        /** {@code var}/{@code let}/{@code const} declaration; children are {@code DECLARATOR}s. */
        VAR_DECL,
        /** {@code name: T = value}: child {@code PATTERN} (field {@code NAME}), optional value (field {@code VALUE}). */
        DECLARATOR,
        /** A binding pattern: an identifier or a destructuring pattern, as a declarator's name. */
        PATTERN,
        /** {@code function f() {}} ({@code function_declaration}), also {@code async}. */
        FUNCTION_DECL,
        /** {@code function* f() {}} ({@code generator_function_declaration}). */
        GENERATOR_DECL,
        /** A function overload or ambient function without body ({@code function_signature}). */
        FUNCTION_SIG,
        /** {@code class C {}} ({@code class_declaration}). Children: decorators, NAME, heritage, CLASS_BODY. */
        CLASS_DECL,
        /** {@code abstract class C {}} ({@code abstract_class_declaration}). */
        ABSTRACT_CLASS_DECL,
        /** A class expression ({@code class}), e.g. {@code const C = class {}} or {@code export default class {}}. */
        CLASS_EXPR,
        /** The members of a class ({@code class_body}). */
        CLASS_BODY,
        /** {@code @expr} ({@code decorator}). */
        DECORATOR,
        /**
         * A method, constructor or accessor with a body ({@code method_definition}). Starts at its modifiers;
         * decorators before it are separate siblings in the class body.
         */
        METHOD_DEF,
        /** A method without body: an overload, or a method in an interface or object type ({@code method_signature}). */
        METHOD_SIG,
        /** A class field ({@code public_field_definition}), including its decorators. Optional VALUE. */
        FIELD_DEF,
        /** {@code [key: K]: V} or a mapped type clause ({@code index_signature}). */
        INDEX_SIG,
        /** <code>static { … }</code> ({@code class_static_block}). */
        STATIC_BLOCK,
        /** {@code interface I {}} ({@code interface_declaration}); body is an {@code OBJECT_TYPE} (field BODY). */
        INTERFACE,
        /** The members of an object literal type, mapped type or interface body ({@code object_type}). */
        OBJECT_TYPE,
        /** {@code name?: T} in an object type ({@code property_signature}). */
        PROPERTY_SIG,
        /** {@code (x): T} in an object type ({@code call_signature}). */
        CALL_SIG,
        /** {@code new (x): T} in an object type ({@code construct_signature}). */
        CONSTRUCT_SIG,
        /** {@code enum E {}} ({@code enum_declaration}); its {@code ENUM_BODY} has one {@code ENUM_MEMBER} per member. */
        ENUM,
        ENUM_BODY,
        ENUM_MEMBER,
        /** {@code type T = …;} ({@code type_alias_declaration}); the aliased type has field {@code VALUE}. */
        TYPE_ALIAS,
        /** {@code namespace N {}}, {@code module M {}}, {@code declare module "m" {}}: NAME and optional BODY. */
        MODULE,
        /** {@code ( parameters )} ({@code formal_parameters}), also the single parameter of {@code x => …}. */
        PARAMS,
        /** One parameter ({@code required_parameter}/{@code optional_parameter}). */
        PARAM,
        /** A constructor parameter with an accessibility modifier ({@code private x: T}), which also declares a field. */
        PARAM_PROPERTY,
        /** {@code =>} function ({@code arrow_function}). */
        ARROW,
        /** {@code function () {}} as an expression ({@code function_expression}). */
        FUNCTION_EXPR,
        /** {@code function* () {}} as an expression ({@code generator_function}). */
        GENERATOR_EXPR,
        /** {@code f(args)} or a tagged template ({@code call_expression}): callee first, arguments with field ARGS. */
        CALL,
        /** {@code ( args )} of a call ({@code arguments}); children are the argument expressions. */
        ARGS,
        /** An identifier used as an expression ({@code identifier}). */
        IDENT,
        /** A string literal ({@code string}). */
        STR,
        /** A template literal ({@code template_string}). */
        TEMPLATE,
        /** Any other expression. */
        EXPR,
        /** A type that is not a plain object type (union, reference, function type, …). */
        TYPE,
        /**
         * A name: of a declaration, member, module or import binding. Covers one token, or several for {@code A.B.C}
         * and computed member names.
         */
        NAME,
        /** Tokens skipped during error recovery. */
        ERROR
    }

    /** Tags on children: their role in the parent (a declaration's name, a variable's value, a function's body, …). */
    enum Field {
        NONE, NAME, ALIAS, VALUE, BODY, PARAMS, ARGS, SOURCE, DECL
    }

    /**
     * The parse result: tokens (with flags), nodes (as parallel arrays indexed by node id) and the root node.
     * Each node has a kind, a field tag (set by its parent), a token range {@code first..end} (end exclusive; empty
     * only for nodes created at an error) and its children {@code kids[kidsStart .. kidsStart + kidsLen]}.
     */
    static final class Tree {
        final byte[] code;
        final Tokens tokens;
        final NodeKind[] kind;
        final Field[] field;
        final int[] first;
        final int[] end;
        final int[] kidsStart;
        final int[] kidsLen;
        final int nodeCount;
        final int[] kids;
        final int root;
        /** True when the parser had to recover from at least one syntax error. */
        final boolean hasError;
        /** Byte offset of the token where the first syntax error was noticed (for diagnostics; 0 without errors). */
        final int firstError;

        private Tree(byte[] code, Parser p, int root) {
            this.code = code;
            this.tokens = p.toks;
            this.kind = p.nKind;
            this.field = p.nField;
            this.first = p.nFirst;
            this.end = p.nEnd;
            this.kidsStart = p.nKidsStart;
            this.kidsLen = p.nKidsLen;
            this.nodeCount = p.nodeCount;
            this.kids = p.kids.items;
            this.root = root;
            this.hasError = p.errors > 0;
            this.firstError = p.firstError < 0 ? 0 : p.firstError;
        }

        NodeKind kind(int n) {
            return kind[n];
        }

        /** The children of {@code n} in source order. */
        int[] children(int n) {
            return Arrays.copyOfRange(kids, kidsStart[n], kidsStart[n] + kidsLen[n]);
        }

        /** The first child of {@code n} tagged with {@code f}, or -1. */
        int field(int n, Field f) {
            for (int i = kidsStart[n], e = kidsStart[n] + kidsLen[n]; i < e; i++) {
                if (field[kids[i]] == f) {
                    return kids[i];
                }
            }
            return -1;
        }

        /** The source text of a node: from the start of its first token to the end of its last ("" if empty). */
        String text(int n) {
            if (end[n] <= first[n]) {
                return "";
            }
            return slice(tokens.start[first[n]], tokens.end[end[n] - 1]);
        }

        String tokenText(int t) {
            return slice(tokens.start[t], tokens.end[t]);
        }

        /**
         * Decodes the bytes {@code a..b}; "" when the range is not on character boundaries (as Rust's
         * {@code str::get}). Token boundaries always are, apart from pathological input.
         */
        private String slice(int a, int b) {
            if (a < 0 || b > code.length || a > b || (a < code.length && (code[a] & 0xC0) == 0x80)
                    || (b < code.length && (code[b] & 0xC0) == 0x80)) {
                return "";
            }
            return new String(code, a, b - a, StandardCharsets.UTF_8);
        }

        /** 1-based line the node starts on. */
        int firstLine(int n) {
            return first[n] < tokens.size ? tokens.line[first[n]] : 1;
        }

        /** 1-based line the node ends on (inclusive). */
        int lastLine(int n) {
            if (end[n] <= first[n]) {
                return firstLine(n);
            }
            return tokens.endLine[end[n] - 1];
        }

        /** First token index of a node. */
        int tokenFirst(int n) {
            return first[n];
        }

        /** One past the last token index of a node (at least {@link #tokenFirst}). */
        int tokenEnd(int n) {
            return Math.max(end[n], first[n]);
        }

        /** The flags ({@link #REF}, {@link #DECISION}) of token {@code t}. */
        int flags(int t) {
            return tokens.flags[t];
        }
    }

    /** Parses {@code code} as TypeScript ({@code tsx == false}) or TSX. Never throws on any input. */
    static Tree parse(String code, boolean tsx) {
        byte[] bytes = code.getBytes(StandardCharsets.UTF_8);
        Parser p = new Parser(bytes, tsx);
        int root = p.parseProgram();
        // Make sure the token list is complete (it ends with the EOF token), so ranges are always valid.
        p.ensure(0);
        return new Tree(bytes, p, root);
    }

    // ------------------------------------------------------------------------------------------------------ parser

    /** A growable list of ints (the kids array and the node stack). */
    private static final class IntList {
        int[] items = new int[64];
        int size;

        void add(int v) {
            if (size == items.length) {
                items = Arrays.copyOf(items, size * 2);
            }
            items[size++] = v;
        }
    }

    /** The start of a node under construction: its first token and the stack height (see the class comment). */
    private record Marker(int tok, int stack) {
    }

    /**
     * Everything needed to roll back a failed speculative parse, plus the enclosing speculation's error base (restored
     * by {@link Parser#endSpec}).
     */
    private record Snapshot(int pos, int nodes, int kids, int stack, int errors, int base) {
    }

    /**
     * Words that can never be an identifier in an expression (reserved words). Contextual keywords ({@code type},
     * {@code async}, {@code of}, {@code get}, …) are not listed: they are identifiers wherever they are not used as
     * keywords.
     */
    private static final String[] RESERVED = {
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum",
            "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null",
            "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with",
    };

    /**
     * Predefined type names such as {@code string} and {@code any} (plus the literal types {@code null},
     * {@code undefined}, {@code true}, {@code false} and {@code this}): never references.
     */
    private static final String[] PREDEFINED_TYPES = {
            "any", "number", "boolean", "string", "symbol", "void", "unknown", "never", "object", "null", "undefined",
            "true", "false", "this", "unique",
    };

    /** Class member modifiers. */
    private static final String[] MODIFIERS = {
            "public", "private", "protected", "static", "override", "readonly", "async", "abstract", "declare", "accessor",
    };

    /** Assignment operators ({@code >>=} and {@code >>>=} only appear after a {@link Mode#GREATER} rescan). */
    private static final String[] ASSIGN_OPS = {
            "=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=",
    };

    /** Words that can start a declaration after {@code declare}. */
    private static final String[] DECLARATION_STARTS = {
            "var", "let", "const", "function", "async", "class", "abstract", "enum", "interface", "type", "namespace",
            "module", "global",
    };

    /** Statements handled by {@link Parser#controlStatement}. */
    private static final String[] CONTROL_WORDS = {
            "if", "for", "while", "do", "switch", "try", "return", "throw", "break", "continue", "debugger", "with",
    };

    private static final String[] VAR_WORDS = {"var", "let", "const"};
    private static final String[] STATIC_GET_SET = {"static", "get", "set"};
    private static final String[] GET_SET = {"get", "set"};
    private static final String[] ACCESSIBILITY = {"public", "private", "protected"};
    private static final String[] READONLY_OVERRIDE = {"readonly", "override"};
    private static final String[] OBJECT_MODIFIERS = {"async", "get", "set"};
    private static final String[] TYPE_MEMBER_MODIFIERS = {
            "readonly", "public", "private", "protected", "static", "override", "get", "set", "async",
    };
    private static final String[] TYPE_PARAMETER_MODIFIERS = {"const", "in", "out"};
    /** Punctuators that can start an expression (for {@code yield} and type-argument disambiguation). */
    private static final String[] EXPRESSION_START_PUNCT = {
            "(", "[", "{", "+", "-", "!", "~", "++", "--", "<", "/", "/=", "@", "#", "...",
    };
    /** Identifiers that cannot start an expression (they continue one). */
    private static final String[] NOT_EXPRESSION_START = {"in", "of", "as", "satisfies", "instanceof"};
    private static final String[] PREFIX_PUNCT = {"!", "~", "+", "-", "++", "--"};
    private static final String[] PREFIX_WORDS = {"typeof", "void", "delete"};
    private static final String[] TYPE_OPERAND_PUNCT = {"(", "[", "{", "<", "-", "*"};

    /**
     * The recursive-descent parser. One instance parses one file on one thread. Method names follow the reference
     * implementation ({@code parse_assignment} is {@link #parseAssignment}, and so on), so the two can be compared
     * function by function.
     */
    static final class Parser {
        private final Lexer lx;
        private final byte[] src;
        /** Tokens lexed so far. Tokens at and after {@code pos} are lookahead and may be re-lexed (see rescan). */
        final Tokens toks;
        /** Index of the current token. */
        private int pos;
        // the nodes, as parallel arrays
        NodeKind[] nKind;
        Field[] nField;
        int[] nFirst;
        int[] nEnd;
        int[] nKidsStart;
        int[] nKidsLen;
        int nodeCount;
        final IntList kids = new IntList();
        /** Finished nodes that have no parent yet (see the class documentation). */
        private final IntList stack = new IntList();
        /** Number of syntax errors so far (rolled back with failed speculation). */
        int errors;
        /** Nesting of speculative parses, and the error count when the innermost one started. */
        private int spec;
        private int specBase;
        /** Current recursion depth (see {@link #MAX_DEPTH}). */
        private int depth;
        private final boolean tsx;
        /** Source offsets of {@code (}/{@code <} tokens where an arrow function was tried and failed. */
        private final Set<Integer> arrowFailed = new HashSet<>();
        /**
         * Set once {@link #MAX_DEPTH} has been reached anywhere in the file. From then on the parser no longer
         * speculates and takes the plain reading of every ambiguity (a comparison rather than type arguments, a
         * parenthesised expression or type rather than an arrow function or function type). Input that nests past
         * the cap at each of many speculation points ({@code a < a < a < …}) would otherwise cost up to
         * {@code MAX_DEPTH} steps per token, each re-lexed after the rollback; real code never gets near the cap.
         */
        private boolean depthCapped;
        /** Offset of the first error outside speculation (-1 for none). */
        int firstError = -1;

        Parser(byte[] src, boolean tsx) {
            this.lx = new Lexer(src);
            this.src = src;
            this.tsx = tsx;
            this.toks = new Tokens(src.length / 4 + 16);
            int capacity = src.length / 8 + 16;
            nKind = new NodeKind[capacity];
            nField = new Field[capacity];
            nFirst = new int[capacity];
            nEnd = new int[capacity];
            nKidsStart = new int[capacity];
            nKidsLen = new int[capacity];
        }

        // ------------------------------------------------------------------ tokens

        /** Makes sure the token {@code k} positions ahead is lexed (or the list ends with EOF). */
        void ensure(int k) {
            while (toks.size <= pos + k) {
                if (toks.size > 0 && toks.kind[toks.size - 1] == TokKind.EOF) {
                    return;
                }
                int from = toks.size > 0 ? toks.end[toks.size - 1] : 0;
                lx.lex(from, Mode.NORMAL, toks);
            }
        }

        /** Index of the token {@code k} positions ahead of the current one (the EOF beyond the end). */
        private int peek(int k) {
            ensure(k);
            return Math.min(pos + k, toks.size - 1);
        }

        private int cur() {
            return peek(0);
        }

        /** Is token {@code t}'s text the ASCII string {@code w}? */
        private boolean textIs(int t, String w) {
            int s = toks.start[t];
            int len = toks.end[t] - s;
            if (len != w.length()) {
                return false;
            }
            for (int i = 0; i < len; i++) {
                if (src[s + i] != w.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        /** Is token {@code t}'s text one of {@code words}? */
        private boolean textIn(int t, String[] words) {
            for (String w : words) {
                if (textIs(t, w)) {
                    return true;
                }
            }
            return false;
        }

        /** Is the token {@code k} ahead the punctuator {@code p}? */
        private boolean nthP(int k, String p) {
            int t = peek(k);
            return toks.kind[t] == TokKind.PUNCT && textIs(t, p);
        }

        /** Is the token {@code k} ahead the word (keyword or identifier) {@code w}? */
        private boolean nthKw(int k, String w) {
            int t = peek(k);
            return toks.kind[t] == TokKind.IDENT && textIs(t, w);
        }

        private boolean atP(String p) {
            return nthP(0, p);
        }

        private boolean atKw(String w) {
            return nthKw(0, w);
        }

        private TokKind nthKind(int k) {
            int t = peek(k); // before reading the array: peek may grow it
            return toks.kind[t];
        }

        private TokKind curKind() {
            return nthKind(0);
        }

        private boolean atEof() {
            return curKind() == TokKind.EOF;
        }

        /** Is the current token one of the given words? */
        private boolean atAnyKw(String[] words) {
            int t = cur();
            return toks.kind[t] == TokKind.IDENT && textIn(t, words);
        }

        /** Was there a line break before the token {@code k} ahead? */
        private boolean nthNl(int k) {
            int t = peek(k);
            return toks.nlBefore[t];
        }

        /** Consumes the current token (never the EOF) and returns its index. */
        private int bump() {
            int t = cur();
            if (toks.kind[t] == TokKind.EOF) {
                return pos;
            }
            if ((toks.flags[t] & BAD) != 0) {
                error();
            }
            pos++;
            return pos - 1;
        }

        /** Consumes the current token and sets {@code flag} on it. */
        private int bumpFlag(int flag) {
            int i = bump();
            if (i < toks.size && toks.kind[i] != TokKind.EOF) {
                toks.flags[i] |= (byte) flag;
            }
            return i;
        }

        /** Sets {@code flag} on token {@code i}, if it exists. */
        private void flagToken(int i, int flag) {
            if (i >= 0 && i < toks.size) {
                toks.flags[i] |= (byte) flag;
            }
        }

        private boolean eatP(String p) {
            if (atP(p)) {
                bump();
                return true;
            }
            return false;
        }

        private boolean eatKw(String w) {
            if (atKw(w)) {
                bump();
                return true;
            }
            return false;
        }

        /** Consumes {@code p} or records an error (the missing token is treated as present). */
        private void expectP(String p) {
            if (!eatP(p)) {
                error();
            }
        }

        /** Re-lexes the current token in another mode, discarding all lookahead. */
        private void rescan(Mode mode) {
            toks.truncate(pos);
            int from = toks.size > 0 ? toks.end[toks.size - 1] : 0;
            lx.lex(from, mode, toks);
        }

        /**
         * Statement terminator: {@code ;}, or automatic semicolon insertion before <code>}</code>, at the end of input
         * or after a line break.
         */
        private void semicolon() {
            if (eatP(";")) {
                return;
            }
            int t = cur();
            if (toks.kind[t] == TokKind.EOF || toks.nlBefore[t] || atP("}")) {
                return;
            }
            error();
        }

        /** Can automatic semicolon insertion end a statement here (no {@code ;} needed)? */
        private boolean canEndStatement() {
            int t = cur();
            return toks.kind[t] == TokKind.EOF || toks.nlBefore[t] || atP("}") || atP(";");
        }

        // ------------------------------------------------------------------ nodes, errors, speculation

        private Marker mark() {
            return new Marker(pos, stack.size);
        }

        /** A marker that starts at the already finished node {@code n}, which must be on top of the stack. */
        private Marker precede(int n) {
            return new Marker(nFirst[n], Math.max(stack.size - 1, 0));
        }

        /**
         * Finishes a node that started at {@code m} and ends at the last consumed token; nodes finished since
         * {@code m} become its children.
         */
        private int finish(Marker m, NodeKind kind) {
            int from = Math.min(m.stack(), stack.size);
            int kidsStart = kids.size;
            for (int i = from; i < stack.size; i++) {
                kids.add(stack.items[i]);
            }
            int kidsLen = stack.size - from;
            stack.size = from;
            if (nodeCount == nKind.length) {
                int c = nodeCount * 2;
                nKind = Arrays.copyOf(nKind, c);
                nField = Arrays.copyOf(nField, c);
                nFirst = Arrays.copyOf(nFirst, c);
                nEnd = Arrays.copyOf(nEnd, c);
                nKidsStart = Arrays.copyOf(nKidsStart, c);
                nKidsLen = Arrays.copyOf(nKidsLen, c);
            }
            int id = nodeCount++;
            nKind[id] = kind;
            nField[id] = Field.NONE;
            nFirst[id] = m.tok();
            nEnd[id] = Math.max(pos, m.tok());
            nKidsStart[id] = kidsStart;
            nKidsLen[id] = kidsLen;
            stack.add(id);
            return id;
        }

        private void setField(int n, Field f) {
            nField[n] = f;
        }

        private void error() {
            errors++;
            if (spec == 0 && firstError < 0) {
                int t = cur();
                firstError = toks.start[t];
            }
        }

        /**
         * True while speculating and the current attempt has already hit an error: loops stop early so that a failed
         * attempt is abandoned quickly.
         */
        private boolean bail() {
            return spec > 0 && errors > specBase;
        }

        /** Skips one token as an {@code ERROR} node (used where nothing else applies, to guarantee progress). */
        private int errorNode() {
            error();
            Marker m = mark();
            if (!bail()) {
                bump();
            }
            return finish(m, NodeKind.ERROR);
        }

        /** Starts a speculative parse; returns what {@link #endSpec} needs. */
        private Snapshot beginSpec() {
            Snapshot s = new Snapshot(pos, nodeCount, kids.size, stack.size, errors, specBase);
            spec++;
            specBase = errors;
            return s;
        }

        /**
         * Ends a speculative parse: on failure (any error since it began) rolls back and returns false. Tokens from
         * the snapshot position on are dropped and lexed again later, as the failed attempt may have rescanned some of
         * them in another mode or flagged them.
         */
        private boolean endSpec(Snapshot s, boolean ok) {
            ok = ok && errors == specBase;
            spec--;
            specBase = s.base();
            if (!ok) {
                pos = s.pos();
                toks.truncate(s.pos());
                nodeCount = Math.min(nodeCount, s.nodes());
                kids.size = Math.min(kids.size, s.kids());
                stack.size = Math.min(stack.size, s.stack());
                errors = s.errors();
            }
            return ok;
        }

        /** Enters a nested construct; false (after recording an error) when {@link #MAX_DEPTH} is reached. */
        private boolean enter() {
            if (depth >= MAX_DEPTH) {
                depthCapped = true;
                error();
                return false;
            }
            depth++;
            return true;
        }

        private void leave() {
            depth--;
        }

        // ------------------------------------------------------------------ names

        /** Is the current token a word usable as a binding or reference name (any identifier but reserved words)? */
        private boolean atIdentifier() {
            return nthIdentifier(0);
        }

        private boolean nthIdentifier(int k) {
            int t = peek(k);
            return toks.kind[t] == TokKind.IDENT && !textIn(t, RESERVED);
        }

        /** Consumes an identifier that is a reference (flags it {@link #REF} unless it is {@code undefined}). */
        private int bumpRef() {
            if (atKw("undefined")) {
                return bump();
            }
            return bumpFlag(REF);
        }

        /**
         * A declaration's name: a {@code NAME} node over one identifier, flagged as reference (a declared name counts
         * like any other name). Records an error and creates nothing (-1) if there is no identifier.
         */
        private int declName() {
            if (curKind() != TokKind.IDENT) {
                error();
                return -1;
            }
            Marker m = mark();
            bumpRef();
            int n = finish(m, NodeKind.NAME);
            setField(n, Field.NAME);
            return n;
        }

        private static boolean isPropertyNameKind(TokKind k) {
            return k == TokKind.IDENT || k == TokKind.STRING || k == TokKind.NUMBER || k == TokKind.PRIVATE_NAME;
        }

        /** Is the current token the start of a property name (identifier or keyword, string, number, [, #name)? */
        private boolean atPropertyName() {
            return isPropertyNameKind(curKind()) || atP("[");
        }

        private boolean nthPropertyName(int k) {
            return isPropertyNameKind(nthKind(k)) || nthP(k, "[");
        }

        /**
         * A property name of a class member, object literal property, interface member or enum member: a {@code NAME}
         * node (field NAME). Plain names are not references; a computed name {@code [expr]} is parsed as an
         * expression.
         */
        private int propertyName() {
            Marker m = mark();
            if (atP("[")) {
                bump();
                parseAssignment(false);
                expectP("]");
            } else if (atPropertyName()) {
                bump();
            } else {
                error();
            }
            int n = finish(m, NodeKind.NAME);
            setField(n, Field.NAME);
            return n;
        }

        // ------------------------------------------------------------------ statements

        int parseProgram() {
            Marker m = mark();
            statementList(false);
            // a lexing error at the very end (unterminated block comment) is attached to the EOF token
            int t = cur();
            if ((toks.flags[t] & BAD) != 0) {
                error();
            }
            return finish(m, NodeKind.PROGRAM);
        }

        /**
         * Statements until the end of input, or until <code>}</code> when {@code inBlock}. Tokens that cannot start a
         * statement are skipped (one at a time, as errors).
         */
        private void statementList(boolean inBlock) {
            while (true) {
                if (atEof() || (inBlock && atP("}"))) {
                    return;
                }
                int before = pos;
                parseStatement();
                if (pos == before) {
                    errorNode();
                }
                if (bail()) {
                    return;
                }
            }
        }

        /** <code>{ statements }</code> as a {@code BLOCK} node. */
        private int parseBlock() {
            Marker m = mark();
            if (!enter()) {
                return errorNode();
            }
            expectP("{");
            statementList(true);
            expectP("}");
            leave();
            return finish(m, NodeKind.BLOCK);
        }

        private int parseStatement() {
            if (!enter()) {
                return errorNode();
            }
            int n = statement();
            leave();
            return n;
        }

        private int statement() {
            int t = cur();
            TokKind k = toks.kind[t];
            if (k == TokKind.PUNCT) {
                if (atP("{")) {
                    return parseBlock();
                }
                if (atP(";")) {
                    Marker m = mark();
                    bump();
                    return finish(m, NodeKind.STMT);
                }
                if (atP("@")) {
                    return decoratedStatement();
                }
                return expressionStatement();
            }
            if (k != TokKind.IDENT) {
                return expressionStatement();
            }
            Marker m = mark();
            if (textIs(t, "import") && !nthP(1, "(") && !nthP(1, ".")) {
                return importStatement();
            }
            if (textIs(t, "export")) {
                return exportStatement(m);
            }
            if (textIs(t, "var")) {
                return varDeclaration(m, true);
            }
            if (textIs(t, "let") && (nthIdentifier(1) || nthP(1, "[") || nthP(1, "{"))) {
                return varDeclaration(m, true);
            }
            if (textIs(t, "const") && nthKw(1, "enum")) {
                return enumDeclaration(m);
            }
            if (textIs(t, "const")) {
                return varDeclaration(m, true);
            }
            if (textIn(t, CONTROL_WORDS)) {
                return controlStatement(m);
            }
            int n = tryDeclaration(m);
            if (n >= 0) {
                return n;
            }
            // label: statement
            if (atIdentifier() && nthP(1, ":")) {
                bump(); // statement_identifier: not a reference
                bump();
                parseStatement();
                return finish(m, NodeKind.STMT);
            }
            return expressionStatement();
        }

        /**
         * Declarations that may also follow {@code export} or {@code declare}: functions, classes, interfaces, type
         * aliases, enums, namespaces, modules, {@code declare …} and {@code global { }}. -1 (nothing consumed) if the
         * current tokens do not start one; {@code var}/{@code let}/{@code const} are handled by the caller.
         */
        private int tryDeclaration(Marker m) {
            int t = cur();
            if (toks.kind[t] != TokKind.IDENT) {
                return -1;
            }
            boolean nextSameLine = !nthNl(1);
            if (textIs(t, "function")) {
                return functionDeclaration(m);
            }
            if (textIs(t, "async") && nthKw(1, "function") && nextSameLine) {
                return functionDeclaration(m);
            }
            if (textIs(t, "class")) {
                return parseClass(m, NodeKind.CLASS_DECL);
            }
            if (textIs(t, "abstract") && nthKw(1, "class") && nextSameLine) {
                bump();
                return parseClass(m, NodeKind.ABSTRACT_CLASS_DECL);
            }
            if (textIs(t, "interface") && nthKind(1) == TokKind.IDENT && nextSameLine) {
                return interfaceDeclaration(m);
            }
            if (textIs(t, "type") && nthKind(1) == TokKind.IDENT && nextSameLine) {
                return typeAlias(m);
            }
            if (textIs(t, "enum") && nthKind(1) == TokKind.IDENT) {
                return enumDeclaration(m);
            }
            if ((textIs(t, "namespace") || textIs(t, "module")) && nextSameLine
                    && (nthKind(1) == TokKind.IDENT || nthKind(1) == TokKind.STRING)) {
                return moduleDeclaration(m);
            }
            if (textIs(t, "declare") && nextSameLine && nthIsDeclarationStart(1)) {
                bump();
                Marker inner = mark();
                if (atKw("global")) {
                    bump();
                    if (atP("{")) {
                        parseBlock();
                    } else {
                        semicolon();
                    }
                    finish(inner, NodeKind.STMT);
                } else if (atKw("module") && nthP(1, ".")) {
                    // declare module.exports: T;
                    bump();
                    bump();
                    bump();
                    if (eatP(":")) {
                        parseType();
                    }
                    semicolon();
                    finish(inner, NodeKind.STMT);
                } else if (atAnyKw(VAR_WORDS) && !nthKw(1, "enum")) {
                    varDeclaration(inner, true);
                } else if (tryDeclaration(inner) < 0) {
                    error();
                }
                return finish(m, NodeKind.AMBIENT);
            }
            if (textIs(t, "global") && nthP(1, "{")) {
                bump();
                parseBlock();
                return finish(m, NodeKind.STMT);
            }
            return -1;
        }

        /** Can the token {@code k} ahead start a declaration after {@code declare}? */
        private boolean nthIsDeclarationStart(int k) {
            int t = peek(k);
            return toks.kind[t] == TokKind.IDENT && textIn(t, DECLARATION_STARTS);
        }

        /** Decorators before a class declaration, {@code abstract class} or {@code export} statement. */
        private int decoratedStatement() {
            Marker m = mark();
            while (atP("@")) {
                decorator();
            }
            if (atKw("export")) {
                return exportStatement(m);
            }
            if (atKw("abstract") && nthKw(1, "class")) {
                bump();
                return parseClass(m, NodeKind.ABSTRACT_CLASS_DECL);
            }
            if (atKw("class")) {
                return parseClass(m, NodeKind.CLASS_DECL);
            }
            error();
            return finish(m, NodeKind.STMT);
        }

        private int expressionStatement() {
            Marker m = mark();
            parseExpression(false);
            semicolon();
            return finish(m, NodeKind.EXPR_STMT);
        }

        /** {@code var}/{@code let}/{@code const} declarations. {@code semi} is false inside a {@code for (…)} header. */
        private int varDeclaration(Marker m, boolean semi) {
            bump(); // var, let or const
            while (true) {
                declarator(!semi);
                if (bail() || !eatP(",")) {
                    break;
                }
            }
            if (semi) {
                semicolon();
            }
            return finish(m, NodeKind.VAR_DECL);
        }

        /** {@code pattern [!] [: type] [= value]}. */
        private int declarator(boolean noIn) {
            Marker m = mark();
            Marker pm = mark();
            bindingPattern();
            int name = finish(pm, NodeKind.PATTERN);
            setField(name, Field.NAME);
            eatP("!");
            if (eatP(":")) {
                parseType();
            }
            if (eatP("=")) {
                int v = parseAssignment(noIn);
                setField(v, Field.VALUE);
            }
            return finish(m, NodeKind.DECLARATOR);
        }

        /** {@code [async] function [*] name<T>(params): R { body }}, or a signature without body. */
        private int functionDeclaration(Marker m) {
            eatKw("async");
            bump(); // function
            boolean generator = eatP("*");
            if (curKind() == TokKind.IDENT && !atP("(")) {
                declName();
            } else {
                error();
            }
            callSignature();
            if (atP("{")) {
                parseBlock();
                return finish(m, generator ? NodeKind.GENERATOR_DECL : NodeKind.FUNCTION_DECL);
            }
            semicolon();
            return finish(m, NodeKind.FUNCTION_SIG);
        }

        /**
         * {@code <T>(params): R}, the part of every function, method and signature between name and body. The
         * parameter list gets field PARAMS.
         */
        private void callSignature() {
            if (atP("<")) {
                typeParameters();
            }
            parameters();
            if (eatP(":")) {
                returnType();
            }
        }

        /**
         * A class declaration or expression; {@code m} may include decorators and {@code abstract}. The current token
         * is {@code class}.
         */
        private int parseClass(Marker m, NodeKind kind) {
            bump(); // class
            if (atIdentifier() && !atKw("implements")) {
                declName();
            } else if (kind != NodeKind.CLASS_EXPR) {
                error();
            }
            if (atP("<")) {
                typeParameters();
            }
            if (eatKw("extends")) {
                while (true) {
                    parseLhsExpression();
                    if (atP("<")) {
                        typeArguments();
                    }
                    if (bail() || !eatP(",")) {
                        break;
                    }
                }
            }
            if (eatKw("implements")) {
                while (true) {
                    parseType();
                    if (bail() || !eatP(",")) {
                        break;
                    }
                }
            }
            classBody();
            return finish(m, kind);
        }

        private int classBody() {
            Marker m = mark();
            expectP("{");
            while (!atP("}") && !atEof() && !bail()) {
                int before = pos;
                classMember();
                if (pos == before) {
                    errorNode();
                }
            }
            expectP("}");
            int n = finish(m, NodeKind.CLASS_BODY);
            setField(n, Field.BODY);
            return n;
        }

        /**
         * Can a modifier at the current position really be a modifier, given the token after it? (TypeScript's
         * {@code nextTokenCanFollowModifier}: a property name, {@code [}, <code>{</code>, {@code *}, {@code ...} or
         * {@code #name}, on the same line except after {@code static}, {@code get} and {@code set}.)
         */
        private boolean modifierFollows() {
            boolean nextNl = nthNl(1);
            boolean sameLineNeeded = !atAnyKw(STATIC_GET_SET);
            if (sameLineNeeded && nextNl) {
                return false;
            }
            return nthPropertyName(1) || nthP(1, "{") || nthP(1, "*") || nthP(1, "...");
        }

        /**
         * One class member. Decorators belong to a field (they start its span) but are separate siblings before a
         * method.
         */
        private void classMember() {
            if (eatP(";")) {
                return;
            }
            if (!enter()) {
                errorNode();
                return;
            }
            Marker m = mark();
            while (atP("@")) {
                decorator();
            }
            if (atKw("static") && nthP(1, "{")) {
                bump();
                parseBlock();
                finish(m, NodeKind.STATIC_BLOCK);
                leave();
                return;
            }
            Marker mm = mark();
            while ((atAnyKw(MODIFIERS) || atAnyKw(GET_SET)) && modifierFollows()) {
                bump();
            }
            eatP("*");
            if (atP("[") && nthIdentifier(1) && nthP(2, ":")) {
                indexSignatureRest(mm);
                memberSeparator();
                leave();
                return;
            }
            propertyName();
            if (!eatP("?")) {
                eatP("!");
            }
            if (atP("(") || atP("<")) {
                callSignature();
                if (atP("{")) {
                    parseBlock();
                    finish(mm, NodeKind.METHOD_DEF);
                    eatP(";");
                } else {
                    finish(mm, NodeKind.METHOD_SIG);
                    memberSeparator();
                }
            } else {
                if (eatP(":")) {
                    parseType();
                }
                if (eatP("=")) {
                    int v = parseAssignment(false);
                    setField(v, Field.VALUE);
                }
                finish(m, NodeKind.FIELD_DEF);
                memberSeparator();
            }
            leave();
        }

        /** {@code ;} or {@code ,} after a class member, or automatic semicolon insertion. */
        private void memberSeparator() {
            if (eatP(";") || eatP(",")) {
                return;
            }
            if (!canEndStatement()) {
                error();
            }
        }

        /** The rest of an index signature {@code [key: K]: V} from the {@code [} (modifiers consumed with {@code m}). */
        private int indexSignatureRest(Marker m) {
            bump(); // [
            bumpRef(); // the key name counts as a reference
            bump(); // :
            parseType();
            expectP("]");
            if (eatP(":")) {
                parseType();
            }
            return finish(m, NodeKind.INDEX_SIG);
        }

        private int decorator() {
            Marker m = mark();
            bump(); // @
            if (atP("(")) {
                parsePrimary();
            } else if (curKind() == TokKind.IDENT) {
                bumpRef();
                while (atP(".")) {
                    bump();
                    TokKind k = curKind();
                    if (k == TokKind.IDENT || k == TokKind.PRIVATE_NAME) {
                        bump();
                    } else {
                        error();
                        break;
                    }
                }
                if (atP("<")) {
                    typeArguments();
                }
                if (atP("(")) {
                    arguments();
                }
            } else {
                error();
            }
            return finish(m, NodeKind.DECORATOR);
        }

        private int interfaceDeclaration(Marker m) {
            bump(); // interface
            declName();
            if (atP("<")) {
                typeParameters();
            }
            if (eatKw("extends")) {
                while (true) {
                    parseType();
                    if (bail() || !eatP(",")) {
                        break;
                    }
                }
            }
            int body = objectType();
            setField(body, Field.BODY);
            return finish(m, NodeKind.INTERFACE);
        }

        private int typeAlias(Marker m) {
            bump(); // type
            declName();
            if (atP("<")) {
                typeParameters();
            }
            expectP("=");
            int v = parseType();
            setField(v, Field.VALUE);
            semicolon();
            return finish(m, NodeKind.TYPE_ALIAS);
        }

        /** {@code [const] enum E { A, B = 1 }}. */
        private int enumDeclaration(Marker m) {
            eatKw("const");
            bump(); // enum
            declName();
            Marker bm = mark();
            expectP("{");
            while (!atP("}") && !atEof() && !bail()) {
                int before = pos;
                Marker em = mark();
                propertyName();
                if (eatP("=")) {
                    parseAssignment(false);
                }
                finish(em, NodeKind.ENUM_MEMBER);
                if (!eatP(",")) {
                    break;
                }
                if (pos == before) {
                    errorNode();
                }
            }
            expectP("}");
            int body = finish(bm, NodeKind.ENUM_BODY);
            setField(body, Field.BODY);
            return finish(m, NodeKind.ENUM);
        }

        /** {@code namespace A.B { }}, {@code module M { }}, {@code module "m" { }} or a body-less ambient module. */
        private int moduleDeclaration(Marker m) {
            bump(); // namespace or module
            Marker nm = mark();
            if (curKind() == TokKind.STRING) {
                bump();
            } else {
                bumpRef();
                while (atP(".") && nthKind(1) == TokKind.IDENT) {
                    bump();
                    bump(); // property_identifier
                }
            }
            int name = finish(nm, NodeKind.NAME);
            setField(name, Field.NAME);
            if (atP("{")) {
                int b = parseBlock();
                setField(b, Field.BODY);
            } else {
                semicolon();
            }
            return finish(m, NodeKind.MODULE);
        }

        private static boolean isIdentOrString(TokKind k) {
            return k == TokKind.IDENT || k == TokKind.STRING;
        }

        /** {@code import …;} in all its forms (the current token is {@code import}). */
        private int importStatement() {
            Marker m = mark();
            bump(); // import
            // type/typeof modifier, unless it is itself the default import (import type from "m")
            if ((atKw("type") || atKw("typeof"))
                    && (nthP(1, "{") || nthP(1, "*") || (nthKind(1) == TokKind.IDENT && !nthKw(1, "from")))) {
                bump();
            }
            if (curKind() == TokKind.STRING) {
                Marker s = mark();
                bump();
                int source = finish(s, NodeKind.STR);
                setField(source, Field.SOURCE);
                semicolon();
                return finish(m, NodeKind.IMPORT);
            }
            if (curKind() == TokKind.IDENT && nthP(1, "=")) {
                // import x = require("m") (no module source for the analyser) or import A = B.C (an alias)
                bump();
                bump();
                NodeKind kind = atKw("require") && nthP(1, "(") ? NodeKind.IMPORT : NodeKind.IMPORT_ALIAS;
                parseAssignment(false);
                semicolon();
                return finish(m, kind);
            }
            if ((curKind() == TokKind.IDENT && !atKw("from")) || (atKw("from") && nthKw(1, "from"))) {
                Marker d = mark();
                bump();
                finish(d, NodeKind.IMPORT_DEFAULT);
                eatP(",");
            }
            if (atP("*")) {
                Marker ns = mark();
                bump();
                if (!eatKw("as")) {
                    error();
                }
                Marker nm = mark();
                if (curKind() == TokKind.IDENT) {
                    bump();
                } else {
                    error();
                }
                finish(nm, NodeKind.NAME);
                finish(ns, NodeKind.IMPORT_NAMESPACE);
            } else if (atP("{")) {
                bump();
                while (!atP("}") && !atEof() && !bail()) {
                    Marker sm = mark();
                    // type/typeof modifier of one specifier (but { type } and { type as x } import "type")
                    if ((atKw("type") || atKw("typeof")) && isIdentOrString(nthKind(1))
                            && !(nthKw(1, "as") && !isIdentOrString(nthKind(2)))) {
                        bump();
                    }
                    if (!isIdentOrString(curKind())) {
                        error();
                        break;
                    }
                    Marker nm = mark();
                    bump();
                    int name = finish(nm, NodeKind.NAME);
                    setField(name, Field.NAME);
                    if (eatKw("as")) {
                        Marker am = mark();
                        if (isIdentOrString(curKind())) {
                            bump();
                        } else {
                            error();
                        }
                        int alias = finish(am, NodeKind.NAME);
                        setField(alias, Field.ALIAS);
                    }
                    finish(sm, NodeKind.IMPORT_SPECIFIER);
                    if (!eatP(",")) {
                        break;
                    }
                }
                expectP("}");
            }
            if (eatKw("from")) {
                if (curKind() == TokKind.STRING) {
                    Marker s = mark();
                    bump();
                    int source = finish(s, NodeKind.STR);
                    setField(source, Field.SOURCE);
                } else {
                    error();
                }
            } else {
                error();
            }
            importAttributes();
            semicolon();
            return finish(m, NodeKind.IMPORT);
        }

        /** <code>with { type: "json" }</code> / {@code assert { … }} after an import or re-export. */
        private void importAttributes() {
            if ((atKw("with") || atKw("assert")) && nthP(1, "{") && !nthNl(0)) {
                bump();
                parsePrimary();
            }
        }

        /** {@code export …} in all its forms; {@code m} may include decorators. */
        private int exportStatement(Marker m) {
            bump(); // export
            if (atP("*")) {
                bump();
                if (eatKw("as")) {
                    bump();
                }
                expectKwFromSource();
                semicolon();
                return finish(m, NodeKind.EXPORT);
            }
            if (atP("{") || (atKw("type") && nthP(1, "{"))) {
                eatKw("type");
                exportClause();
                if (atKw("from")) {
                    expectKwFromSource();
                }
                semicolon();
                return finish(m, NodeKind.EXPORT);
            }
            if (eatP("=")) {
                parseExpression(false);
                semicolon();
                return finish(m, NodeKind.EXPORT);
            }
            if (atKw("as") && nthKw(1, "namespace")) {
                bump();
                bump();
                bump();
                semicolon();
                return finish(m, NodeKind.EXPORT);
            }
            if (atKw("import")) {
                int d = importStatement();
                setField(d, Field.DECL);
                return finish(m, NodeKind.EXPORT);
            }
            if (eatKw("default")) {
                Marker dm = mark();
                // A named function or class is a declaration; an anonymous one is an expression.
                boolean namedFunction = (atKw("function") && (nthKind(1) == TokKind.IDENT
                        || (nthP(1, "*") && nthKind(2) == TokKind.IDENT)))
                        || (atKw("async") && nthKw(1, "function") && !nthNl(1)
                        && (nthKind(2) == TokKind.IDENT || (nthP(2, "*") && nthKind(3) == TokKind.IDENT)));
                boolean namedClass = atKw("class") && nthIdentifier(1) && !nthKw(1, "implements");
                int decl;
                if (namedFunction) {
                    decl = functionDeclaration(dm);
                } else if (namedClass) {
                    decl = parseClass(dm, NodeKind.CLASS_DECL);
                } else if (atKw("abstract") && nthKw(1, "class")) {
                    bump();
                    decl = parseClass(dm, NodeKind.ABSTRACT_CLASS_DECL);
                } else if (atKw("interface") && nthKind(1) == TokKind.IDENT) {
                    decl = interfaceDeclaration(dm);
                } else {
                    decl = -1;
                }
                if (decl >= 0) {
                    setField(decl, Field.DECL);
                } else {
                    int v = parseAssignment(false);
                    setField(v, Field.VALUE);
                    semicolon();
                }
                return finish(m, NodeKind.EXPORT_DEFAULT);
            }
            Marker dm = mark();
            int decl;
            if (atAnyKw(VAR_WORDS) && !nthKw(1, "enum")) {
                decl = varDeclaration(dm, true);
            } else if (atKw("const")) {
                decl = enumDeclaration(dm);
            } else if (atP("@")) {
                while (atP("@")) {
                    decorator();
                }
                if (atKw("abstract")) {
                    bump();
                    decl = parseClass(dm, NodeKind.ABSTRACT_CLASS_DECL);
                } else if (atKw("class")) {
                    decl = parseClass(dm, NodeKind.CLASS_DECL);
                } else {
                    decl = -1;
                }
            } else {
                decl = tryDeclaration(dm);
            }
            if (decl >= 0) {
                setField(decl, Field.DECL);
            } else {
                error();
            }
            return finish(m, NodeKind.EXPORT);
        }

        /** {@code from "m"} of a re-export. */
        private void expectKwFromSource() {
            if (!eatKw("from")) {
                error();
                return;
            }
            if (curKind() == TokKind.STRING) {
                bump();
            } else {
                error();
            }
            importAttributes();
        }

        /** <code>{ a, b as c, type d }</code> of an export. */
        private void exportClause() {
            expectP("{");
            while (!atP("}") && !atEof() && !bail()) {
                if ((atKw("type") || atKw("typeof")) && isIdentOrString(nthKind(1)) && !nthKw(1, "as")) {
                    bump();
                }
                if (isIdentOrString(curKind())) {
                    bump();
                } else {
                    error();
                    break;
                }
                if (eatKw("as")) {
                    if (isIdentOrString(curKind())) {
                        bump();
                    } else {
                        error();
                    }
                }
                if (!eatP(",")) {
                    break;
                }
            }
            expectP("}");
        }

        /**
         * {@code if}, loops, {@code switch}, {@code try}, jumps, {@code debugger}, {@code with}. Decision points are
         * flagged on their keyword.
         */
        private int controlStatement(Marker m) {
            int t = cur();
            if (textIs(t, "if")) {
                // else-if chains are parsed iteratively (they are not nested deeper), so long chains cannot reach
                // MAX_DEPTH; the analyser only needs the flags, not the nesting.
                while (true) {
                    bumpFlag(DECISION);
                    parenExpression();
                    parseStatement();
                    if (!eatKw("else")) {
                        break;
                    }
                    if (!atKw("if")) {
                        parseStatement();
                        break;
                    }
                }
            } else if (textIs(t, "for")) {
                bumpFlag(DECISION);
                eatKw("await");
                forHeader();
                parseStatement();
            } else if (textIs(t, "while")) {
                bumpFlag(DECISION);
                parenExpression();
                parseStatement();
            } else if (textIs(t, "do")) {
                bumpFlag(DECISION);
                parseStatement();
                if (!eatKw("while")) {
                    error();
                }
                parenExpression();
                eatP(";");
            } else if (textIs(t, "switch")) {
                bump();
                parenExpression();
                expectP("{");
                while (!atP("}") && !atEof() && !bail()) {
                    if (atKw("case")) {
                        bumpFlag(DECISION);
                        parseExpression(false);
                        expectP(":");
                    } else if (atKw("default")) {
                        bump();
                        expectP(":");
                    } else {
                        errorNode();
                        continue;
                    }
                    while (!atP("}") && !atKw("case") && !atKw("default") && !atEof() && !bail()) {
                        int before = pos;
                        parseStatement();
                        if (pos == before) {
                            errorNode();
                        }
                    }
                }
                expectP("}");
            } else if (textIs(t, "try")) {
                bump();
                parseBlock();
                if (atKw("catch")) {
                    bumpFlag(DECISION);
                    if (eatP("(")) {
                        bindingPattern();
                        if (eatP(":")) {
                            parseType();
                        }
                        expectP(")");
                    }
                    parseBlock();
                }
                if (eatKw("finally")) {
                    parseBlock();
                }
            } else if (textIs(t, "return") || textIs(t, "throw")) {
                bump();
                if (!canEndStatement()) {
                    parseExpression(false);
                }
                semicolon();
            } else if (textIs(t, "break") || textIs(t, "continue")) {
                bump();
                if (curKind() == TokKind.IDENT && !nthNl(0)) {
                    bump(); // label: statement_identifier, not a reference
                }
                semicolon();
            } else if (textIs(t, "debugger")) {
                bump();
                semicolon();
            } else {
                // with
                bump();
                parenExpression();
                parseStatement();
            }
            return finish(m, NodeKind.STMT);
        }

        private void parenExpression() {
            expectP("(");
            parseExpression(false);
            expectP(")");
        }

        /** {@code ( init; test; update )}, {@code ( x of xs )} or {@code ( k in obj )}. */
        private void forHeader() {
            expectP("(");
            if (!atP(";")) {
                boolean isDecl = atKw("var") || atKw("const")
                        || (atKw("let") && (nthIdentifier(1) || nthP(1, "[") || nthP(1, "{")));
                if (isDecl) {
                    varDeclaration(mark(), false);
                } else {
                    parseExpression(true);
                }
                if (atKw("of") || atKw("in")) {
                    bump();
                    parseExpression(false);
                    expectP(")");
                    return;
                }
            }
            expectP(";");
            if (!atP(";")) {
                parseExpression(false);
            }
            expectP(";");
            if (!atP(")")) {
                parseExpression(false);
            }
            expectP(")");
        }

        // ------------------------------------------------------------------ patterns and parameters

        /** A binding target: identifier (a reference), <code>{ … }</code> or {@code [ … ]} destructuring, or rest. */
        private void bindingPattern() {
            if (!enter()) {
                errorNode();
                return;
            }
            if (eatP("...")) {
                bindingPattern();
            } else if (atP("{")) {
                bump();
                while (!atP("}") && !atEof() && !bail()) {
                    if (eatP("...")) {
                        bindingPattern();
                    } else if (atPropertyName()) {
                        boolean computed = atP("[");
                        boolean plain = atIdentifier();
                        propertyNameUnmarked();
                        if (eatP(":")) {
                            bindingElement();
                        } else {
                            // shorthand { a } / { a = 1 }: shorthand_property_identifier_pattern, not a reference
                            if (!plain || computed) {
                                error();
                            }
                            if (eatP("=")) {
                                parseAssignment(false);
                            }
                        }
                    } else {
                        error();
                        break;
                    }
                    if (!eatP(",")) {
                        break;
                    }
                }
                expectP("}");
            } else if (atP("[")) {
                bump();
                while (!atP("]") && !atEof() && !bail()) {
                    if (eatP(",")) {
                        continue; // hole
                    }
                    bindingElement();
                    if (!eatP(",")) {
                        break;
                    }
                }
                expectP("]");
            } else if (atIdentifier()) {
                bumpRef();
            } else {
                error();
            }
            leave();
        }

        /** A property name without creating a node (inside patterns). */
        private void propertyNameUnmarked() {
            if (atP("[")) {
                bump();
                parseAssignment(false);
                expectP("]");
            } else {
                bump();
            }
        }

        /** {@code pattern [= default]}. */
        private void bindingElement() {
            bindingPattern();
            if (eatP("=")) {
                parseAssignment(false);
            }
        }

        /** {@code ( parameters )} as a PARAMS node with field PARAMS. */
        private int parameters() {
            Marker m = mark();
            expectP("(");
            while (!atP(")") && !atEof() && !bail()) {
                parameter();
                if (!eatP(",")) {
                    break;
                }
            }
            expectP(")");
            int n = finish(m, NodeKind.PARAMS);
            setField(n, Field.PARAMS);
            return n;
        }

        /** {@code @dec private readonly name?: T = default}, {@code ...rest: T[]} or {@code this: T}. */
        private int parameter() {
            Marker m = mark();
            while (atP("@")) {
                decorator();
            }
            boolean property = false;
            while (true) {
                boolean nextOk = nthKind(1) == TokKind.IDENT || nthP(1, "{") || nthP(1, "[") || nthP(1, "...");
                if (atAnyKw(ACCESSIBILITY) && nextOk) {
                    property = true;
                    bump();
                } else if (atAnyKw(READONLY_OVERRIDE) && nextOk) {
                    bump();
                } else {
                    break;
                }
            }
            if (atKw("this")) {
                bump();
            } else {
                bindingPattern();
            }
            eatP("?");
            if (eatP(":")) {
                parseType();
            }
            if (eatP("=")) {
                parseAssignment(false);
            }
            return finish(m, property ? NodeKind.PARAM_PROPERTY : NodeKind.PARAM);
        }

        // ------------------------------------------------------------------ expressions

        /** Expression with the comma operator. */
        private int parseExpression(boolean noIn) {
            int first = parseAssignment(noIn);
            if (atP(",")) {
                Marker m = precede(first);
                while (eatP(",")) {
                    parseAssignment(noIn);
                    if (bail()) {
                        break;
                    }
                }
                return finish(m, NodeKind.EXPR);
            }
            return first;
        }

        /**
         * Assignment-level expression: arrow function, {@code yield}, conditional, assignment. {@code noIn} disallows
         * the {@code in} operator (in a {@code for (…)} initializer).
         */
        private int parseAssignment(boolean noIn) {
            if (!enter()) {
                return errorNode();
            }
            int n = assignment(noIn);
            leave();
            return n;
        }

        private boolean isAssignOp(int t) {
            return toks.kind[t] == TokKind.PUNCT && textIn(t, ASSIGN_OPS);
        }

        private int assignment(boolean noIn) {
            int arrow = tryArrow(noIn);
            if (arrow >= 0) {
                return arrow;
            }
            if (atKw("yield") && !nthP(1, "=>")) {
                Marker m = mark();
                bump();
                boolean star = eatP("*");
                if (star || (!canEndStatement() && atExpressionStart())) {
                    parseAssignment(noIn);
                }
                return finish(m, NodeKind.EXPR);
            }
            int left = conditional(noIn);
            if (atP(">")) {
                rescan(Mode.GREATER);
            }
            if (isAssignOp(cur())) {
                Marker m = precede(left);
                bump();
                parseAssignment(noIn);
                return finish(m, NodeKind.EXPR);
            }
            return left;
        }

        /** Can the current token start an expression (for {@code yield} and type-argument disambiguation)? */
        private boolean atExpressionStart() {
            int t = cur();
            return switch (toks.kind[t]) {
                case EOF, INVALID -> false;
                case PUNCT -> textIn(t, EXPRESSION_START_PUNCT);
                case IDENT -> !textIn(t, NOT_EXPRESSION_START);
                default -> true;
            };
        }

        /** {@code test ? a : b}; the {@code ?} is a decision point. */
        private int conditional(boolean noIn) {
            int test = binary(0, noIn);
            if (atP("?")) {
                Marker m = precede(test);
                bumpFlag(DECISION);
                parseAssignment(false);
                expectP(":");
                parseAssignment(noIn);
                return finish(m, NodeKind.EXPR);
            }
            return test;
        }

        /** Precedence of the binary operator at the current token (0 if none). {@code >} must already be rescanned. */
        private int binaryPrecedence(boolean noIn) {
            int t = cur();
            TokKind k = toks.kind[t];
            if (k == TokKind.IDENT) {
                if (textIs(t, "instanceof")) {
                    return 8;
                }
                if (textIs(t, "in") && !noIn) {
                    return 8;
                }
                if ((textIs(t, "as") || textIs(t, "satisfies")) && !toks.nlBefore[t]) {
                    return 8;
                }
                return 0;
            }
            if (k != TokKind.PUNCT) {
                return 0;
            }
            int s = toks.start[t];
            int len = toks.end[t] - s;
            int c0 = src[s];
            if (len == 1) {
                return switch (c0) {
                    case '|' -> 4;
                    case '^' -> 5;
                    case '&' -> 6;
                    case '<', '>' -> 8;
                    case '+', '-' -> 10;
                    case '*', '/', '%' -> 11;
                    default -> 0;
                };
            }
            if (len == 2) {
                int c1 = src[s + 1];
                if (c0 == '?' && c1 == '?') {
                    return 1;
                }
                if (c0 == '|' && c1 == '|') {
                    return 2;
                }
                if (c0 == '&' && c1 == '&') {
                    return 3;
                }
                if ((c0 == '=' || c0 == '!') && c1 == '=') {
                    return 7;
                }
                if ((c0 == '<' || c0 == '>') && c1 == '=') {
                    return 8;
                }
                if ((c0 == '<' && c1 == '<') || (c0 == '>' && c1 == '>')) {
                    return 9;
                }
                if (c0 == '*' && c1 == '*') {
                    return 12;
                }
                return 0;
            }
            if (len == 3) {
                if (textIs(t, "===") || textIs(t, "!==")) {
                    return 7;
                }
                if (textIs(t, ">>>")) {
                    return 9;
                }
            }
            return 0;
        }

        /** Binary operators by precedence climbing. {@code &&}, {@code ||} and {@code ??} are decision points. */
        private int binary(int min, boolean noIn) {
            int left = parseUnary();
            while (true) {
                if (bail()) {
                    return left;
                }
                if (atP(">")) {
                    rescan(Mode.GREATER);
                }
                int prec = binaryPrecedence(noIn);
                if (prec == 0 || (prec <= min && !(prec == 12 && min == 12))) {
                    return left;
                }
                Marker m = precede(left);
                if (atKw("as") || atKw("satisfies")) {
                    bump();
                    if (!eatKw("const")) {
                        parseType();
                    }
                    left = finish(m, NodeKind.EXPR);
                    continue;
                }
                boolean decision = atP("&&") || atP("||") || atP("??");
                bumpFlag(decision ? DECISION : 0);
                // ** is right-associative: parse its right side at one level lower (and count the recursion, as a
                // chain of ** nests without bound)
                if (!enter()) {
                    return errorNode();
                }
                binary(prec == 12 ? 11 : prec, noIn);
                leave();
                left = finish(m, NodeKind.EXPR);
            }
        }

        /** Prefix operators, {@code await}, and (plain TypeScript only) the type assertion {@code <T>expr}. */
        private int parseUnary() {
            if (!enter()) {
                return errorNode();
            }
            int n = unary();
            leave();
            return n;
        }

        private int unary() {
            int t = cur();
            boolean prefix = switch (toks.kind[t]) {
                case PUNCT -> textIn(t, PREFIX_PUNCT);
                case IDENT -> textIn(t, PREFIX_WORDS)
                        || (textIs(t, "await") && !nthP(1, "=>") && !nthP(1, ")") && !nthP(1, ",") && !nthP(1, ";"));
                default -> false;
            };
            if (prefix) {
                Marker m = mark();
                bump();
                parseUnary();
                return finish(m, NodeKind.EXPR);
            }
            if (!tsx && atP("<")) {
                Marker m = mark();
                typeArguments();
                parseUnary();
                return finish(m, NodeKind.EXPR);
            }
            int e = parseLhsExpression();
            if ((atP("++") || atP("--")) && !nthNl(0)) {
                Marker m = precede(e);
                bump();
                return finish(m, NodeKind.EXPR);
            }
            return e;
        }

        /** Member access, calls (also generic {@code f<T>()} and tagged templates), non-null {@code x!}, {@code new}. */
        private int parseLhsExpression() {
            int e = atKw("new") ? newExpression() : parsePrimary();
            return callTail(e, true);
        }

        /** {@code new X<T>(args)} and {@code new.target}. The callee is a member expression without calls. */
        private int newExpression() {
            Marker m = mark();
            if (!enter()) {
                return errorNode();
            }
            bump(); // new
            if (atP(".")) {
                bump();
                bump(); // target
                leave();
                return finish(m, NodeKind.EXPR);
            }
            int callee = atKw("new") ? newExpression() : parsePrimary();
            callTail(callee, false);
            if (atP("<")) {
                tryTypeArgumentsInExpression();
            }
            if (atP("(")) {
                int a = arguments();
                setField(a, Field.ARGS);
            }
            leave();
            return finish(m, NodeKind.EXPR);
        }

        private static boolean isTemplateStart(TokKind k) {
            return k == TokKind.TEMPLATE || k == TokKind.TEMPLATE_HEAD;
        }

        /** The postfix part of a left-hand-side expression. Without {@code calls} (the callee of new), stops at (. */
        private int callTail(int e, boolean calls) {
            while (true) {
                if (bail()) {
                    return e;
                }
                int t = cur();
                TokKind k = toks.kind[t];
                if (isTemplateStart(k)) {
                    if (!calls) {
                        return e;
                    }
                    // tagged template: a call with the template as its argument
                    Marker m = precede(e);
                    int a = template();
                    setField(a, Field.ARGS);
                    e = finish(m, NodeKind.CALL);
                    continue;
                }
                if (k != TokKind.PUNCT) {
                    return e;
                }
                if (textIs(t, ".")) {
                    Marker m = precede(e);
                    bump();
                    TokKind c = curKind();
                    if (c == TokKind.IDENT || c == TokKind.PRIVATE_NAME) {
                        bump(); // property_identifier
                    } else {
                        error();
                    }
                    e = finish(m, NodeKind.EXPR);
                } else if (textIs(t, "?.")) {
                    Marker m = precede(e);
                    bump();
                    if (atP("(") || atP("<")) {
                        if (atP("<")) {
                            typeArguments();
                        }
                        int a = arguments();
                        setField(a, Field.ARGS);
                        e = finish(m, NodeKind.CALL);
                        continue;
                    }
                    TokKind c = curKind();
                    if (atP("[")) {
                        bump();
                        parseExpression(false);
                        expectP("]");
                    } else if (c == TokKind.IDENT || c == TokKind.PRIVATE_NAME) {
                        bump();
                    } else {
                        error();
                    }
                    e = finish(m, NodeKind.EXPR);
                } else if (textIs(t, "[")) {
                    Marker m = precede(e);
                    bump();
                    parseExpression(false);
                    expectP("]");
                    e = finish(m, NodeKind.EXPR);
                } else if (textIs(t, "!") && !toks.nlBefore[t]) {
                    Marker m = precede(e);
                    bump();
                    e = finish(m, NodeKind.EXPR);
                } else if (textIs(t, "(") && calls) {
                    Marker m = precede(e);
                    int a = arguments();
                    setField(a, Field.ARGS);
                    e = finish(m, NodeKind.CALL);
                } else if (textIs(t, "<") && calls) {
                    // f<T>(x), f<T>`…` or an instantiation expression f<T>; otherwise a comparison
                    Marker m = precede(e);
                    if (!tryTypeArgumentsInExpression()) {
                        return e;
                    }
                    if (atP("(")) {
                        int a = arguments();
                        setField(a, Field.ARGS);
                        e = finish(m, NodeKind.CALL);
                    } else if (isTemplateStart(curKind())) {
                        int a = template();
                        setField(a, Field.ARGS);
                        e = finish(m, NodeKind.CALL);
                    } else {
                        e = finish(m, NodeKind.EXPR);
                    }
                } else {
                    return e;
                }
            }
        }

        /**
         * Speculatively parses {@code <T, U>} after an expression; keeps it only if what follows makes it type
         * arguments (TypeScript's {@code canFollowTypeArgumentsInExpression}).
         */
        private boolean tryTypeArgumentsInExpression() {
            if (depthCapped) {
                return false;
            }
            Snapshot s = beginSpec();
            typeArguments();
            boolean ok = !bail() && typeArgumentsCanFollow();
            return endSpec(s, ok);
        }

        private boolean typeArgumentsCanFollow() {
            int t = cur();
            TokKind k = toks.kind[t];
            if (atP("(") || isTemplateStart(k)) {
                return true;
            }
            if (atP("<") || atP(">") || atP("+") || atP("-")) {
                return false;
            }
            if (toks.nlBefore[t] || k == TokKind.EOF) {
                return true;
            }
            boolean binary = binaryPrecedence(false) > 0 || isAssignOp(t) || atP("?") || atP(",");
            return binary || !atExpressionStart();
        }

        /** {@code ( args )} as an ARGS node. */
        private int arguments() {
            Marker m = mark();
            expectP("(");
            while (!atP(")") && !atEof() && !bail()) {
                if (atP("...")) {
                    Marker sm = mark();
                    bump();
                    parseAssignment(false);
                    finish(sm, NodeKind.EXPR);
                } else {
                    parseAssignment(false);
                }
                if (!eatP(",")) {
                    break;
                }
            }
            expectP(")");
            return finish(m, NodeKind.ARGS);
        }

        /** Primary expressions: names, literals, (), arrays, objects, functions, classes, templates, regexes, JSX. */
        private int parsePrimary() {
            int t = cur();
            Marker m = mark();
            switch (toks.kind[t]) {
                case IDENT -> {
                    if (textIs(t, "function")) {
                        return functionExpression(m);
                    }
                    if (textIs(t, "async") && nthKw(1, "function") && !nthNl(1)) {
                        return functionExpression(m);
                    }
                    if (textIs(t, "class")) {
                        return parseClass(m, NodeKind.CLASS_EXPR);
                    }
                    if (textIs(t, "this") || textIs(t, "super") || textIs(t, "null") || textIs(t, "true")
                            || textIs(t, "false") || textIs(t, "import")) {
                        bump();
                        return finish(m, NodeKind.EXPR);
                    }
                    if (textIn(t, RESERVED)) {
                        return errorNode();
                    }
                    bumpRef();
                    return finish(m, NodeKind.IDENT);
                }
                case STRING -> {
                    bump();
                    return finish(m, NodeKind.STR);
                }
                case NUMBER, REGEX, PRIVATE_NAME -> {
                    bump();
                    return finish(m, NodeKind.EXPR);
                }
                case TEMPLATE, TEMPLATE_HEAD -> {
                    return template();
                }
                case PUNCT -> {
                    if (textIs(t, "(")) {
                        bump();
                        parseExpression(false);
                        if (eatP(":")) {
                            parseType();
                        }
                        expectP(")");
                        return finish(m, NodeKind.EXPR);
                    }
                    if (textIs(t, "[")) {
                        bump();
                        while (!atP("]") && !atEof() && !bail()) {
                            if (eatP(",")) {
                                continue;
                            }
                            eatP("...");
                            parseAssignment(false);
                            if (!eatP(",")) {
                                break;
                            }
                        }
                        expectP("]");
                        return finish(m, NodeKind.EXPR);
                    }
                    if (textIs(t, "{")) {
                        return objectLiteral();
                    }
                    if (textIs(t, "/") || textIs(t, "/=")) {
                        rescan(Mode.REGEX);
                        bump();
                        return finish(m, NodeKind.EXPR);
                    }
                    if (textIs(t, "<") && tsx) {
                        return jsxElement();
                    }
                    if (textIs(t, "@")) {
                        while (atP("@")) {
                            decorator();
                        }
                        if (atKw("class")) {
                            return parseClass(m, NodeKind.CLASS_EXPR);
                        }
                        error();
                        return finish(m, NodeKind.ERROR);
                    }
                    return errorNode();
                }
                default -> {
                    return errorNode();
                }
            }
        }

        /** {@code [async] function [*] [name](params) { }} as an expression. */
        private int functionExpression(Marker m) {
            eatKw("async");
            bump(); // function
            boolean generator = eatP("*");
            if (curKind() == TokKind.IDENT) {
                declName();
            }
            callSignature();
            parseBlock();
            return finish(m, generator ? NodeKind.GENERATOR_EXPR : NodeKind.FUNCTION_EXPR);
        }

        /**
         * <code>{ a: 1, b, ...c, m() {}, get x() {}, [k]: v }</code>. Property names and shorthand properties are not
         * references.
         */
        private int objectLiteral() {
            Marker m = mark();
            if (!enter()) {
                return errorNode();
            }
            bump(); // {
            while (!atP("}") && !atEof() && !bail()) {
                if (eatP(",")) {
                    continue;
                }
                Marker pm = mark();
                if (eatP("...")) {
                    parseAssignment(false);
                    finish(pm, NodeKind.EXPR);
                } else {
                    // async, get, set and * are modifiers only when a property name follows
                    while (atAnyKw(OBJECT_MODIFIERS) && (nthPropertyName(1) || nthP(1, "*"))
                            && !(atKw("async") && nthNl(1))) {
                        bump();
                    }
                    eatP("*");
                    if (!atPropertyName()) {
                        errorNode();
                        continue;
                    }
                    propertyName();
                    eatP("?");
                    if (atP("(") || atP("<")) {
                        callSignature();
                        parseBlock();
                    } else if (eatP(":")) {
                        parseAssignment(false);
                    } else if (eatP("=")) {
                        parseAssignment(false);
                    }
                    finish(pm, NodeKind.EXPR);
                }
                if (!atP("}") && !eatP(",")) {
                    error();
                    break;
                }
            }
            expectP("}");
            leave();
            return finish(m, NodeKind.EXPR);
        }

        /** A template literal; substitutions are expressions. */
        private int template() {
            return templateWith(false);
        }

        /** A template literal; substitutions are types when {@code types} (template literal types). */
        private int templateWith(boolean types) {
            Marker m = mark();
            if (curKind() == TokKind.TEMPLATE) {
                bump();
                return finish(m, NodeKind.TEMPLATE);
            }
            if (!enter()) {
                return errorNode();
            }
            bump(); // head
            while (true) {
                if (types) {
                    parseType();
                } else {
                    parseExpression(false);
                }
                if (!atP("}")) {
                    error();
                    break;
                }
                rescan(Mode.TEMPLATE_CONTINUE);
                TokKind k = curKind();
                bump();
                if (k != TokKind.TEMPLATE_MIDDLE || bail()) {
                    break;
                }
            }
            leave();
            return finish(m, NodeKind.TEMPLATE);
        }

        /**
         * Tries to parse an arrow function at the current position (-1 if there is none). Plain {@code x => …} and
         * {@code async x => …} are recognised directly; parenthesised parameters (and generic arrows
         * {@code <T>(…) =>}) speculatively, up to {@code =>}.
         */
        private int tryArrow(boolean noIn) {
            int t = cur();
            if (toks.kind[t] == TokKind.IDENT) {
                boolean isAsync = atKw("async") && !nthNl(1);
                // x => …
                if (atIdentifier() && nthP(1, "=>")) {
                    Marker m = mark();
                    Marker pm = mark();
                    Marker qm = mark();
                    bumpRef();
                    finish(qm, NodeKind.PARAM);
                    int p = finish(pm, NodeKind.PARAMS);
                    setField(p, Field.PARAMS);
                    bump(); // =>
                    arrowBody(noIn);
                    return finish(m, NodeKind.ARROW);
                }
                // async x => …
                if (isAsync && nthIdentifier(1) && nthP(2, "=>")) {
                    Marker m = mark();
                    bump();
                    Marker pm = mark();
                    Marker qm = mark();
                    bumpRef();
                    finish(qm, NodeKind.PARAM);
                    int p = finish(pm, NodeKind.PARAMS);
                    setField(p, Field.PARAMS);
                    bump();
                    arrowBody(noIn);
                    return finish(m, NodeKind.ARROW);
                }
                if (!(isAsync && (nthP(1, "(") || nthP(1, "<")))) {
                    return -1;
                }
            } else if (!(atP("(") || atP("<"))) {
                return -1;
            }
            if (atP("<") && tsx) {
                // In TSX <T>(x) => x would be JSX; generic arrows are written <T,>(…) or <T extends U>(…).
                boolean generic = (nthKind(1) == TokKind.IDENT && (nthP(2, ",") || nthKw(2, "extends")))
                        || (nthKw(1, "const") && nthKind(2) == TokKind.IDENT);
                if (!generic) {
                    return -1;
                }
            }
            int start = toks.start[t];
            if (depthCapped || arrowFailed.contains(start)) {
                return -1;
            }
            Snapshot s = beginSpec();
            Marker m = mark();
            eatKw("async");
            if (atP("<")) {
                typeParameters();
            }
            if (!bail()) {
                parameters();
            }
            if (!bail() && eatP(":")) {
                returnType();
            }
            boolean ok = !bail() && atP("=>");
            if (!endSpec(s, ok)) {
                arrowFailed.add(start);
                return -1;
            }
            bump(); // =>
            arrowBody(noIn);
            return finish(m, NodeKind.ARROW);
        }

        private void arrowBody(boolean noIn) {
            if (atP("{")) {
                parseBlock();
            } else {
                parseAssignment(noIn);
            }
        }

        // ------------------------------------------------------------------ JSX

        /**
         * A JSX element or fragment starting at {@code <} (TSX only). Tag names are references (also for intrinsic
         * elements like {@code div}); attribute names and member parts ({@code <A.B>}'s {@code B}) are not.
         */
        private int jsxElement() {
            Marker m = mark();
            if (!enter()) {
                return errorNode();
            }
            bump(); // <
            if (eatP(">")) {
                jsxChildren();
                leave();
                return finish(m, NodeKind.EXPR);
            }
            jsxName();
            if (atP("<")) {
                typeArguments();
            }
            while (!bail()) {
                if (atP("{")) {
                    bump();
                    eatP("...");
                    parseAssignment(false);
                    expectP("}");
                    continue;
                }
                if (curKind() == TokKind.IDENT) {
                    rescan(Mode.JSX_NAME);
                    bump(); // attribute name: property_identifier
                    if (atP(":")) {
                        // namespaced attribute a:b: both parts count as references
                        flagToken(pos - 1, REF);
                        bump();
                        rescan(Mode.JSX_NAME);
                        bumpFlag(REF);
                    }
                    if (eatP("=")) {
                        if (curKind() == TokKind.STRING) {
                            rescan(Mode.JSX_STRING);
                            bump();
                        } else if (atP("{")) {
                            bump();
                            parseExpression(false);
                            expectP("}");
                        } else if (atP("<")) {
                            jsxElement();
                        } else {
                            error();
                        }
                    }
                    continue;
                }
                break;
            }
            if (atP("/") && nthP(1, ">")) {
                bump();
                bump();
                leave();
                return finish(m, NodeKind.EXPR);
            }
            if (!atP(">")) {
                error();
                leave();
                return finish(m, NodeKind.EXPR);
            }
            bump();
            jsxChildren();
            leave();
            return finish(m, NodeKind.EXPR);
        }

        /** {@code Name}, {@code my-element}, {@code A.B.C} or {@code ns:name} after {@code <} or {@code </}. */
        private void jsxName() {
            if (curKind() != TokKind.IDENT) {
                error();
                return;
            }
            rescan(Mode.JSX_NAME);
            bumpFlag(REF);
            if (atP(":")) {
                bump();
                rescan(Mode.JSX_NAME);
                bumpFlag(REF);
                return;
            }
            while (atP(".")) {
                bump();
                if (curKind() == TokKind.IDENT) {
                    bump(); // property_identifier
                } else {
                    error();
                    return;
                }
            }
        }

        /** Children after an opening tag, up to and including the closing tag. */
        private void jsxChildren() {
            while (true) {
                if (bail()) {
                    return;
                }
                rescan(Mode.JSX_CHILD);
                TokKind k = curKind();
                if (k == TokKind.JSX_TEXT) {
                    bump();
                } else if (k == TokKind.EOF) {
                    error();
                    return;
                } else if (atP("{")) {
                    bump();
                    if (!atP("}")) {
                        eatP("...");
                        parseExpression(false);
                    }
                    expectP("}");
                } else if (nthP(1, "/")) {
                    // closing tag </Name> or </>
                    bump();
                    bump();
                    if (!atP(">")) {
                        jsxName();
                    }
                    expectP(">");
                    return;
                } else {
                    jsxElement();
                }
            }
        }

        // ------------------------------------------------------------------ types

        /** A type, as a node: an OBJECT_TYPE when the whole type is an object literal type, otherwise a TYPE node. */
        private int parseType() {
            Marker m = mark();
            if (!enter()) {
                return errorNode();
            }
            typeInner(false);
            leave();
            // Do not wrap a lone object type, so the analyser can see type P = { … }.
            if (stack.size == m.stack() + 1) {
                int top = stack.size > 0 ? stack.items[stack.size - 1] : 0;
                if (nKind[top] == NodeKind.OBJECT_TYPE && nFirst[top] == m.tok() && nEnd[top] == pos) {
                    return top;
                }
            }
            return finish(m, NodeKind.TYPE);
        }

        /** {@code noConditional}: in the extends clause of a conditional type, which cannot itself be conditional. */
        private void typeInner(boolean noConditional) {
            if (atKw("new") || (atKw("abstract") && nthKw(1, "new"))) {
                eatKw("abstract");
                bump();
                if (atP("<")) {
                    typeParameters();
                }
                parameters();
                expectP("=>");
                returnType();
                return;
            }
            if (atP("<") || (atP("(") && tryFunctionType())) {
                if (atP("<")) {
                    typeParameters();
                    parameters();
                    expectP("=>");
                    returnType();
                }
                return;
            }
            unionType(noConditional);
            if (!noConditional && atKw("extends") && !nthNl(0)) {
                bump();
                if (!enter()) {
                    return;
                }
                typeInner(true);
                leave();
                expectP("?");
                parseTypeNoNode();
                expectP(":");
                parseTypeNoNode();
            }
        }

        /** A nested type that needs no node of its own. */
        private void parseTypeNoNode() {
            if (!enter()) {
                errorNode();
                return;
            }
            typeInner(false);
            leave();
        }

        /** Speculatively parses {@code (params) => R} at {@code (}. True if it was a function type (consumed). */
        private boolean tryFunctionType() {
            int t = cur();
            int start = toks.start[t];
            if (depthCapped || arrowFailed.contains(start)) {
                return false;
            }
            Snapshot s = beginSpec();
            parameters();
            boolean ok = !bail() && atP("=>");
            if (!endSpec(s, ok)) {
                arrowFailed.add(start);
                return false;
            }
            bump();
            returnType();
            return true;
        }

        /** After {@code :} of a signature: {@code x is T}, {@code asserts x [is T]}, {@code this is T} or a type. */
        private void returnType() {
            if (atKw("asserts") && nthKind(1) == TokKind.IDENT && !nthNl(1)) {
                bump();
                if (atKw("this")) {
                    bump();
                } else {
                    bumpRef();
                }
                if (eatKw("is")) {
                    parseType();
                }
                return;
            }
            if (curKind() == TokKind.IDENT && nthKw(1, "is") && !nthNl(1)) {
                if (atKw("this")) {
                    bump();
                } else {
                    bumpRef();
                }
                bump();
                parseType();
                return;
            }
            parseType();
        }

        private void unionType(boolean noConditional) {
            eatP("|");
            intersectionType(noConditional);
            while (!bail() && eatP("|")) {
                intersectionType(noConditional);
            }
        }

        private void intersectionType(boolean noConditional) {
            eatP("&");
            typeOperator(noConditional);
            while (!bail() && eatP("&")) {
                typeOperator(noConditional);
            }
        }

        /** {@code keyof T}, {@code readonly T[]}, {@code unique symbol}, {@code infer U [extends C]}, or a postfix type. */
        private void typeOperator(boolean noConditional) {
            if (!enter()) {
                errorNode();
                return;
            }
            if ((atKw("keyof") || atKw("readonly") || atKw("unique")) && typeOperandFollows()) {
                bump();
                typeOperator(noConditional);
            } else if (atKw("infer") && nthKind(1) == TokKind.IDENT) {
                bump();
                bumpRef();
                if (atKw("extends") && !nthNl(0) && !depthCapped) {
                    // infer U extends C – unless the extends starts the conditional type around it (TypeScript's
                    // rule: inside the extends clause of a conditional type, it is always a constraint)
                    Snapshot s = beginSpec();
                    bump();
                    typeInner(true);
                    boolean ok = !bail() && (noConditional || !atP("?"));
                    endSpec(s, ok);
                }
            } else {
                postfixType();
            }
            leave();
        }

        /** Is the token after a type operator keyword the start of a type (otherwise the keyword is a type name)? */
        private boolean typeOperandFollows() {
            TokKind k = nthKind(1);
            if (k == TokKind.IDENT || k == TokKind.STRING || k == TokKind.NUMBER || isTemplateStart(k)) {
                return true;
            }
            for (String p : TYPE_OPERAND_PUNCT) {
                if (nthP(1, p)) {
                    return true;
                }
            }
            return false;
        }

        /** A primary type followed by {@code []} or {@code [K]} (not across a line break). */
        private void postfixType() {
            primaryType();
            while (!bail() && atP("[") && !nthNl(0)) {
                bump();
                if (!eatP("]")) {
                    parseType();
                    expectP("]");
                }
            }
        }

        private void primaryType() {
            int t = cur();
            switch (toks.kind[t]) {
                case IDENT -> {
                    if (textIs(t, "typeof")) {
                        bump();
                        typeQuery();
                        return;
                    }
                    if (textIs(t, "import") && nthP(1, "(")) {
                        bump();
                        arguments();
                        while (atP(".") && nthKind(1) == TokKind.IDENT) {
                            bump();
                            bump(); // property_identifier
                        }
                        if (atP("<") && !nthNl(0)) {
                            typeArguments();
                        }
                        return;
                    }
                    if (textIn(t, PREDEFINED_TYPES) && !nthP(1, ".")) {
                        boolean unique = textIs(t, "unique");
                        bump();
                        if (unique) {
                            eatKw("symbol");
                        }
                        return;
                    }
                    // A type reference A, A.B, A.B.C<T>: the first and the last name are references, the ones in
                    // between are property names.
                    int first = bumpFlag(REF);
                    int last = first;
                    while (atP(".") && nthKind(1) == TokKind.IDENT) {
                        bump();
                        last = bump();
                    }
                    flagToken(last, REF);
                    if (atP("<") && !nthNl(0)) {
                        typeArguments();
                    }
                }
                case STRING, NUMBER -> bump();
                case TEMPLATE, TEMPLATE_HEAD -> templateWith(true);
                case PUNCT -> {
                    if (textIs(t, "-") && nthKind(1) == TokKind.NUMBER) {
                        bump();
                        bump();
                    } else if (textIs(t, "(")) {
                        bump();
                        parseType();
                        expectP(")");
                    } else if (textIs(t, "[")) {
                        tupleType();
                    } else if (textIs(t, "{")) {
                        objectType();
                    } else if (textIs(t, "*")) {
                        bump();
                    } else if (textIs(t, "?")) {
                        bump();
                        primaryType();
                    } else {
                        errorNode();
                    }
                }
                default -> errorNode();
            }
        }

        /**
         * After {@code typeof}: {@code x}, {@code x.y}, {@code x[0]}, {@code import("m").x}, optionally with type
         * arguments {@code typeof f<T>}. Only the first name is a reference.
         */
        private void typeQuery() {
            if (atKw("import") && nthP(1, "(")) {
                bump();
                arguments();
            } else if (atKw("this")) {
                bump();
            } else if (curKind() == TokKind.IDENT) {
                bumpRef();
            } else {
                error();
                return;
            }
            while (true) {
                TokKind next = nthKind(1);
                if ((atP(".") || atP("?.")) && (next == TokKind.IDENT || next == TokKind.PRIVATE_NAME)) {
                    bump();
                    bump();
                } else if (atP("[") && !nthNl(0) && !nthP(1, "]")) {
                    bump();
                    parseType();
                    expectP("]");
                } else {
                    break;
                }
            }
            if (atP("<") && !nthNl(0)) {
                typeArguments();
            }
        }

        /** {@code [A, b?: B, ...C[]]}. Names of named members are references. */
        private void tupleType() {
            bump(); // [
            while (!atP("]") && !atEof() && !bail()) {
                eatP("...");
                if (curKind() == TokKind.IDENT && (nthP(1, ":") || (nthP(1, "?") && nthP(2, ":")))) {
                    bumpRef();
                    eatP("?");
                    bump(); // :
                }
                parseType();
                eatP("?");
                if (!eatP(",")) {
                    break;
                }
            }
            expectP("]");
        }

        /**
         * The members of an object type as an OBJECT_TYPE node: property, method, call, construct and index
         * signatures, and mapped type clauses.
         */
        private int objectType() {
            Marker m = mark();
            if (!enter()) {
                return errorNode();
            }
            expectP("{");
            while (!atP("}") && !atEof() && !bail()) {
                int before = pos;
                typeMember();
                if (!(eatP(";") || eatP(",") || canEndStatement())) {
                    error();
                }
                if (pos == before) {
                    errorNode();
                }
            }
            expectP("}");
            leave();
            return finish(m, NodeKind.OBJECT_TYPE);
        }

        /** One member of an object type or interface body. */
        private void typeMember() {
            Marker m = mark();
            if (atP("(") || atP("<")) {
                callSignature();
                finish(m, NodeKind.CALL_SIG);
                return;
            }
            if (atKw("new") && (nthP(1, "(") || nthP(1, "<"))) {
                bump();
                callSignature();
                finish(m, NodeKind.CONSTRUCT_SIG);
                return;
            }
            // mapped type modifiers +readonly / -readonly
            if ((atP("+") || atP("-")) && nthKw(1, "readonly")) {
                bump();
            }
            while (atAnyKw(TYPE_MEMBER_MODIFIERS) && modifierFollows()) {
                bump();
            }
            // index signature [k: K]: V or mapped type [K in T as U]?: V
            if (atP("[") && nthKind(1) == TokKind.IDENT && (nthP(2, ":") || nthKw(2, "in"))) {
                bump();
                bumpRef();
                if (eatKw("in")) {
                    parseType();
                    if (eatKw("as")) {
                        parseType();
                    }
                } else {
                    bump(); // :
                    parseType();
                }
                expectP("]");
                if ((atP("+") || atP("-")) && nthP(1, "?")) {
                    bump();
                }
                eatP("?");
                if (eatP(":")) {
                    parseType();
                }
                finish(m, NodeKind.INDEX_SIG);
                return;
            }
            propertyName();
            eatP("?");
            if (atP("(") || atP("<")) {
                callSignature();
                finish(m, NodeKind.METHOD_SIG);
                return;
            }
            if (eatP(":")) {
                parseType();
            }
            finish(m, NodeKind.PROPERTY_SIG);
        }

        /** {@code <T, U>} type arguments. */
        private void typeArguments() {
            expectP("<");
            while (!atP(">") && !atEof() && !bail()) {
                parseType();
                if (!eatP(",")) {
                    break;
                }
            }
            expectP(">");
        }

        /** {@code <const T extends C = D, in out U>} type parameters. Names are references (type_identifier). */
        private void typeParameters() {
            expectP("<");
            while (!atP(">") && !atEof() && !bail()) {
                // const, in, out modifiers (when another name follows)
                while (atAnyKw(TYPE_PARAMETER_MODIFIERS) && nthKind(1) == TokKind.IDENT) {
                    bump();
                }
                if (curKind() == TokKind.IDENT) {
                    bumpRef();
                } else {
                    error();
                    break;
                }
                if (eatKw("extends") || eatP(":")) {
                    parseType();
                }
                if (eatP("=")) {
                    parseType();
                }
                if (!eatP(",")) {
                    break;
                }
            }
            expectP(">");
        }
    }
}
