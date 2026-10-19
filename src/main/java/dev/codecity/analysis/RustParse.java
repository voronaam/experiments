package dev.codecity.analysis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small, dependency-free Rust lexer and error-tolerant structural parser, tailored to what {@link RustAnalyzer}
 * needs. (It replaced tree-sitter-rust 0.24; its classification rules reproduce that grammar's tree where the analyser
 * looks, so analysis results did not change.) It is a faithful port
 * of the Rust edition's {@code rust_parse.rs}: same token kinds, node kinds, backtracking points, error recovery and
 * depth cap, so that both editions produce the same trees by construction.
 *
 * <h2>What the analyser needs</h2>
 * <ul>
 *   <li>the items of a file and of inline modules ({@code use}, {@code struct}, {@code union}, {@code enum},
 *       {@code trait}, {@code impl}, {@code fn}, {@code const}, {@code static}, {@code mod}) with names, line spans
 *       and outer attributes (for {@code #[cfg(test)]});</li>
 *   <li>per struct/union/enum the number of fields/variants, per function the number of (non-{@code self})
 *       parameters;</li>
 *   <li>the header of an {@code impl} (the implemented trait and type);</li>
 *   <li>per item the names it refers to, classified by syntactic position (see "Reference leaves");</li>
 *   <li>per function body its decision points (see "Complexity leaves");</li>
 *   <li>whether the file had syntax errors.</li>
 * </ul>
 * Everything else (expressions, patterns, types) is parsed only to classify names correctly and to find the decision
 * points, and leaves no nodes behind.
 *
 * <h2>Lexer</h2>
 * {@link #lex} turns the source, as UTF-8 bytes, into a flat token array. Token kinds: {@code IDENT} (identifiers
 * <em>and</em> keywords, including raw identifiers {@code r#x}; keywords are recognised by text in the parser),
 * {@code LIFETIME} ({@code 'a}, also used for labels), {@code LITERAL} (numbers, strings, byte/C/raw strings, chars)
 * and {@code PUNCT} (exactly one ASCII punctuation character). Multi-character operators ({@code ::}, {@code &&},
 * {@code ->}, {@code ..=}, …) are not tokens: each punctuation token records whether the next token is punctuation that
 * follows without any gap ({@code joint}), and the parser combines characters where an operator is valid. This is how
 * {@code >>} can close two generic argument lists and {@code &&x} can be two borrows, while {@code a && b} is still a
 * logical and.
 *
 * <p>Comments and whitespace produce no tokens, but a token remembers whether a comment preceded it
 * ({@code commentBefore}): a comment between an attribute and its item detaches the attribute from the item, which
 * matters for {@code #[cfg(test)]} detection. Delimiters {@code ()[]{}} are matched by the lexer ({@code pair}), so the
 * parser can always find the end of a group even in broken code.
 *
 * <p>Lines are 1-based and count {@code \n} only (as {@link SourceText#lines} does); {@code line} and
 * {@code endLine} are the lines of a token's first and last character. Offsets are byte offsets into the UTF-8
 * encoding, as in the Rust edition.
 *
 * <h2>Syntax tree</h2>
 * The parser produces nodes in a flat list in pre-order: a node's descendants are the nodes at indices
 * {@code i + 1 .. nodes[i].end - 1}, its children are found by jumping from one child's {@code end} to the next. Each
 * node covers the tokens {@code first ..= last}. Node kinds are listed at {@link NodeKind}; {@code name} and
 * {@code value} carry kind-specific data.
 *
 * <h2>Reference leaves</h2>
 * The analyser collects referenced names from these leaves, so the parser emits one for every name of these kinds:
 * <ul>
 *   <li>{@link NodeKind#TYPE_IDENT}: a single-segment name in type position
 *       ({@code Foo}, {@code T}, {@code _}, but not primitive types such as {@code u32}), the name of a
 *       struct/enum/union/trait/type alias, a generic type parameter, a struct literal or struct pattern name
 *       ({@code Foo { .. }}). Always a reference.</li>
 *   <li>{@link NodeKind#IDENT}: a single-segment name in expressions and patterns,
 *       function, const, static, module and enum-variant names, lifetimes and labels (token text includes the
 *       {@code '}), and every identifier inside a macro's token tree. A reference only if capitalised.</li>
 *   <li>{@link NodeKind#PATH}: a path with
 *       {@code ::}. The whole text is the reference, and nothing inside it is looked at, so a path with generic
 *       arguments in the middle ({@code Vec::<T>::new}) or a long qualified path ({@code <T as Tr>::A::B}) becomes one
 *       {@code PATH} node whose children are ignored. Generic arguments at the end ({@code a::B<C>}, {@code f::<T>()})
 *       are not part of the path; they follow as siblings. A qualified path with one segment after the brackets
 *       ({@code <T as Tr>::Item}) emits no {@code PATH} at all: only the types inside the brackets
 *       are references, and those are emitted as usual.</li>
 *   <li>{@code use} trees ({@link NodeKind#USE_PATH} etc.) are kept structured for the analyser and act as references
 *       too.</li>
 * </ul>
 *
 * <h2>Complexity leaves</h2>
 * {@link NodeKind#IF} (one per {@code if} keyword of an {@code if} expression, so {@code else if} counts; match guards
 * do not), {@link NodeKind#WHILE}, {@link NodeKind#LOOP}, {@link NodeKind#FOR}, {@link NodeKind#MATCH},
 * {@link NodeKind#ARM} and {@link NodeKind#AND_OR} (a binary {@code &&} or {@code ||}). An
 * {@code &&} that joins a {@code let} condition ({@code if let A = b && c}) is a "let chain", not a binary expression,
 * and does not count; nothing inside a macro's token tree counts (macro arguments are not parsed).
 *
 * <h2>Grammar subset</h2>
 * Items: everything stable Rust has (with visibility, {@code unsafe}/{@code const}/{@code async}/{@code extern "abi"}/
 * {@code default}/{@code safe} qualifiers, generics, where clauses), {@code macro_rules!} definitions and item-position
 * macro calls. Expressions: the full precedence ladder, closures, blocks ({@code unsafe}/{@code async}/{@code const}/
 * {@code try}/{@code gen}, labelled), {@code if}/{@code if let} with let chains, {@code match} with guards, loops,
 * struct literals (not allowed in {@code if}/{@code while}/{@code match}/{@code for} headers, as in Rust), ranges,
 * casts, {@code ?}, {@code .await}, turbofish. Patterns and types: all stable forms. Statements: {@code let} (with
 * {@code else}), items, expression statements (a block-like expression such as {@code if … {}} ends a statement unless
 * followed by {@code .} or {@code ?}).
 *
 * <h2>Error recovery</h2>
 * The parser never throws and always terminates:
 * <ul>
 *   <li>Every delimited group is parsed with a hard limit at its closing delimiter. Tokens left over inside a group
 *       are skipped (setting the error flag), and parsing resumes after the group. So an error never spreads beyond
 *       the innermost group it occurs in.</li>
 *   <li>Item and statement lists skip one token (or one whole group) when nothing could be parsed at a position.</li>
 *   <li>Recursion depth is capped ({@link #MAX_DEPTH}); beyond it the rest of the current group is skipped as an
 *       error. With the cap, a parse needs at most a few thousand stack frames, well within a default thread
 *       stack.</li>
 *   <li>Unmatched delimiters, unterminated literals/comments and unknown characters set the error flag.</li>
 * </ul>
 * The error flag is the analyser's "file had parse errors"; on valid code it is never set.
 */
public final class RustParse {

    /** Marks "no token" in {@link Node#name} and "no matching delimiter" in {@link Token#pair}. */
    public static final int NONE = -1;

    /**
     * Maximum nesting of parse functions (expressions, types, patterns, blocks, item lists, use groups). Real code
     * nests a few dozen levels at most; the cap keeps hostile input from overflowing the stack.
     */
    public static final int MAX_DEPTH = 200;

    private RustParse() {
    }

    /** Token kinds; see the class documentation. */
    public enum TokKind {
        /** An identifier or keyword ({@code foo}, {@code fn}, {@code Self}, {@code _}, {@code r#type}). */
        IDENT,
        /** {@code 'a}, {@code 'static}, or a label such as {@code 'outer}. */
        LIFETIME,
        /** Number, string, byte string, raw string, C string or char literal. */
        LITERAL,
        /** One ASCII punctuation character, including the delimiters. */
        PUNCT
    }

    /** One token. Mutable only while the lexer builds it. */
    public static final class Token {
        public final TokKind kind;
        /** Byte range in the UTF-8 source. */
        public final int start;
        public final int end;
        /** 1-based lines of the first and the last character. */
        public final int line;
        public final int endLine;
        /** Punctuation only: the next token is punctuation that starts exactly where this one ends. */
        public boolean joint;
        /** A comment lies between the previous token and this one. */
        public final boolean commentBefore;
        /** For {@code (}, {@code [}, {@code {}: index of the matching closer, and vice versa. {@link #NONE} if unmatched. */
        public int pair = NONE;

        Token(TokKind kind, int start, int end, int line, int endLine, boolean commentBefore) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.line = line;
            this.endLine = endLine;
            this.commentBefore = commentBefore;
        }
    }

    /** The kinds of syntax tree nodes. Only what the analyser needs is represented; see the class documentation. */
    public enum NodeKind {
        /** The whole file; its children are the top-level items. */
        FILE,
        /** Outer attribute {@code #[…]}. */
        ATTR,
        /** Inner attribute {@code #![…]}. */
        INNER_ATTR,
        /** A comment between two items of an item list (a leaf on the token after the comment). */
        COMMENT,
        /** {@code use} declaration; its use tree is the last child. */
        USE,
        /** A path inside a use tree ({@code a::b::C}), a leaf. */
        USE_PATH,
        /** The {@code a::b} of {@code a::b::{…}} / {@code a::b::*}, a leaf. */
        USE_PREFIX,
        /** {@code path as alias}: child {@code USE_PATH}, {@code name} = alias token. */
        USE_AS,
        /** {@code prefix::{…}} or {@code {…}}: optional {@code USE_PREFIX} child, then the nested use trees. */
        USE_GROUP,
        /** {@code prefix::*} or {@code *}: optional {@code USE_PREFIX} child. */
        USE_GLOB,
        /**
         * Items. {@code name} = name token (or NONE). {@code STRUCT}/{@code UNION}: {@code value} = number of fields;
         * {@code ENUM}: number of variants; {@code FN}: number of counted parameters ({@code self} is not counted).
         */
        STRUCT, UNION, ENUM, TRAIT, IMPL, FN, CONST, STATIC, MOD, TYPE_ALIAS, MACRO_RULES, EXTERN_CRATE, FOREIGN_MOD,
        /**
         * {@code impl} header parts: the trait and the implemented type. {@code name}/{@code value} = first/last token
         * of the type's "core": {@code &'a mut T} → {@code T}, {@code S<T>} → {@code S}, otherwise the whole type.
         */
        IMPL_TRAIT, IMPL_TYPE,
        /** A function's body block. */
        BODY,
        /** {@code { items }} of a {@code mod}, {@code trait}, {@code impl} or {@code extern} block. */
        DECL_BODY,
        /** Any other statement in an item list (macro call, {@code ;}, stray expression). */
        OTHER,
        /** Reference leaves (see class docs). */
        TYPE_IDENT, IDENT, PATH,
        /** Complexity leaves (see class docs). */
        IF, WHILE, LOOP, FOR, MATCH, ARM, AND_OR
    }

    /** One syntax tree node. */
    public static final class Node {
        public final NodeKind kind;
        /** First and last token (inclusive). */
        public final int first;
        public int last;
        /** One past the last descendant: descendants are {@code i + 1 .. end - 1}. */
        public int end;
        /** Kind-specific token index (see {@link NodeKind}), {@link #NONE} if unused. */
        public int name = NONE;
        /** Kind-specific number (see {@link NodeKind}). */
        public int value;

        Node(NodeKind kind, int first, int last, int end) {
            this.kind = kind;
            this.first = first;
            this.last = last;
            this.end = end;
        }
    }

    /** Lexed and parsed file. */
    public static final class Tree {
        /** The source as UTF-8; token offsets index into it. */
        public final byte[] code;
        public final Token[] tokens;
        public final List<Node> nodes;
        /** True if the lexer or the parser met anything that is not valid Rust. */
        public final boolean errors;
        /**
         * Index of the token at which the parser first found an error (0 for lexer errors, {@link #NONE} without
         * errors). Only read by diagnostics and tests.
         */
        public final int firstError;

        Tree(byte[] code, Token[] tokens, List<Node> nodes, boolean errors, int firstError) {
            this.code = code;
            this.tokens = tokens;
            this.nodes = nodes;
            this.errors = errors;
            this.firstError = firstError;
        }

        public Node node(int n) {
            return nodes.get(n);
        }

        /** Source text of one token ("" for NONE). */
        public String tokText(int tok) {
            if (tok < 0 || tok >= tokens.length) {
                return "";
            }
            return utf8(code, tokens[tok].start, tokens[tok].end);
        }

        /** Source text from the start of token {@code first} to the end of token {@code last}, comments included. */
        public String rangeText(int first, int last) {
            if (first < 0 || first >= tokens.length || last < 0 || last >= tokens.length) {
                return "";
            }
            int a = tokens[first].start;
            int b = tokens[last].end;
            return a <= b ? utf8(code, a, b) : "";
        }

        public String nodeText(int n) {
            Node node = nodes.get(n);
            return rangeText(node.first, node.last);
        }

        /** 1-based line of the node's first token. */
        public int firstLine(int n) {
            int t = nodes.get(n).first;
            return t >= 0 && t < tokens.length ? tokens[t].line : 1;
        }

        /** 1-based line of the node's last character. */
        public int lastLine(int n) {
            int t = nodes.get(n).last;
            return t >= 0 && t < tokens.length ? tokens[t].endLine : 1;
        }

        /** Indices of the direct children of node {@code n}, in order. */
        public List<Integer> children(int n) {
            List<Integer> out = new ArrayList<>();
            int end = nodes.get(n).end;
            for (int c = n + 1; c < end; c = nodes.get(c).end) {
                out.add(c);
            }
            return out;
        }

        /** The first direct child of the given kind, or -1. */
        public int child(int n, NodeKind kind) {
            int end = nodes.get(n).end;
            for (int c = n + 1; c < end; c = nodes.get(c).end) {
                if (nodes.get(c).kind == kind) {
                    return c;
                }
            }
            return -1;
        }
    }

    /**
     * Decodes a byte range. The Rust edition returns "" for a range that does not fall on character boundaries; token
     * boundaries always do on valid UTF-8, which is all {@link String#getBytes} produces.
     */
    static String utf8(byte[] b, int start, int end) {
        return new String(b, start, end - start, StandardCharsets.UTF_8);
    }

    /** Lexes and parses one file. All recursion, nested {@code use} groups included, is bounded by {@link #MAX_DEPTH}. */
    public static Tree parse(String source) {
        byte[] code = source.getBytes(StandardCharsets.UTF_8);
        Lexed lexed = lex(code);
        Parser p = new Parser(code, lexed.tokens, lexed.errors);
        int f = p.open(NodeKind.FILE);
        p.parseItems(true);
        p.close(f);
        return new Tree(code, lexed.tokens, p.nodes, p.errors, p.firstError);
    }

    // ------------------------------------------------------------------ lexer

    /** The lexer's result: the tokens and whether anything was malformed. */
    record Lexed(Token[] tokens, boolean errors) {
    }

    private static boolean isAsciiAlpha(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(int c) {
        return isAsciiAlpha(c) || c == '_' || c >= 0x80;
    }

    private static boolean isIdentContinue(int c) {
        return isAsciiAlpha(c) || isDigit(c) || c == '_' || c >= 0x80;
    }

    /** Rust's {@code u8::is_ascii_punctuation}. */
    private static boolean isAsciiPunctuation(int c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }

    /** Whitespace other than {@code \n} (which counts lines). */
    private static boolean isBlank(int c) {
        return c == ' ' || c == '\t' || c == '\r' || c == 0x0b || c == 0x0c;
    }

    /** Length in bytes of the UTF-8 character starting with byte {@code c}. */
    private static int utf8Len(int c) {
        if (c < 0xC0) {
            return 1;
        } else if (c < 0xE0) {
            return 2;
        } else if (c < 0xF0) {
            return 3;
        }
        return 4;
    }

    /** Byte {@code i} as an unsigned value. */
    private static int at(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    /**
     * End of a {@code "…"} string whose opening quote is at {@code i} (backslash escapes skipped), packed with whether
     * it was terminated: {@code end * 2 + (terminated ? 1 : 0)}, see {@link #endOf}/{@link #ok}.
     */
    private static long quotedEnd(byte[] b, int i) {
        int j = i + 1;
        while (j < b.length) {
            int c = at(b, j);
            if (c == '\\') {
                j += 2;
            } else if (c == '"') {
                return pack(j + 1, true);
            } else {
                j++;
            }
        }
        return pack(b.length, false);
    }

    private static long pack(int end, boolean ok) {
        return (long) end * 2 + (ok ? 1 : 0);
    }

    private static int endOf(long packed) {
        return (int) (packed >> 1);
    }

    private static boolean ok(long packed) {
        return (packed & 1) != 0;
    }

    /**
     * If a raw string ({@code r"…"}, {@code r#"…"#}, …) starts with the {@code r} at {@code i}, its end and whether it
     * was terminated (packed, see {@link #quotedEnd}); -1 if no raw string starts there.
     */
    private static long rawStringEnd(byte[] b, int i) {
        int j = i + 1;
        int hashes = 0;
        while (j < b.length && b[j] == '#') {
            hashes++;
            j++;
        }
        if (j >= b.length || b[j] != '"') {
            return -1;
        }
        j++;
        while (j < b.length) {
            if (b[j] == '"' && b.length - (j + 1) >= hashes && allHashes(b, j + 1, hashes)) {
                return pack(j + 1 + hashes, true);
            }
            j++;
        }
        return pack(b.length, false);
    }

    private static boolean allHashes(byte[] b, int from, int count) {
        for (int k = 0; k < count; k++) {
            if (b[from + k] != '#') {
                return false;
            }
        }
        return true;
    }

    /**
     * End of a number literal starting at {@code i}: digits, letters (suffixes, hex digits), {@code _}, one decimal
     * point followed by a digit (or not followed by {@code .} or an identifier: {@code 1.}), and an exponent sign after
     * {@code e}/{@code E}.
     */
    private static int numberEnd(byte[] b, int i) {
        int n = b.length;
        boolean hex = b[i] == '0' && i + 1 < n && (b[i + 1] == 'x' || b[i + 1] == 'o' || b[i + 1] == 'b');
        int j = i;
        boolean dot = hex;
        while (j < n) {
            int c = at(b, j);
            if (isAsciiAlpha(c) || isDigit(c) || c == '_') {
                if (!hex && (c == 'e' || c == 'E') && j + 1 < n && (b[j + 1] == '+' || b[j + 1] == '-')) {
                    if (j + 2 < n && isDigit(at(b, j + 2))) {
                        j += 3;
                        continue;
                    }
                }
                j++;
            } else if (c == '.' && !dot) {
                int next = j + 1 < n ? at(b, j + 1) : 0;
                if (isDigit(next)) {
                    dot = true;
                    j++;
                } else if (next != '.' && !isIdentStart(next)) {
                    return j + 1; // `1.`
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return j;
    }

    /**
     * If a char literal starts with the quote at {@code i} ({@code 'a'}, {@code '\n'}, a Unicode escape), its end and
     * whether it was terminated (packed, see {@link #quotedEnd}); -1 if it is not a char literal (a lifetime, or a lone
     * quote).
     */
    private static long charEnd(byte[] b, int i) {
        int n = b.length;
        if (i + 1 >= n) {
            return -1;
        }
        if (b[i + 1] == '\\') {
            // An escape: scan to the closing quote on the same line.
            int j = i + 3;
            while (j < n && b[j] != '\'' && b[j] != '\n') {
                j++;
            }
            return j < n && b[j] == '\'' ? pack(j + 1, true) : pack(Math.min(j, n), false);
        }
        int len = utf8Len(at(b, i + 1));
        if (i + 1 + len < n && b[i + 1 + len] == '\'' && b[i + 1] != '\n') {
            return pack(i + 2 + len, true);
        }
        return -1;
    }

    /**
     * Splits the source into tokens; returns them and whether anything was malformed (unterminated literal or comment,
     * unknown character, unbalanced delimiter).
     */
    static Lexed lex(byte[] b) {
        int n = b.length;
        List<Token> toks = new ArrayList<>(n / 5 + 4);
        boolean errors = false;
        int i = 0;
        int line = 1;
        boolean comment = false;
        // A shebang line (`#!/usr/bin/env …`, but not an inner attribute `#![…]`) is skipped.
        if (n >= 2 && b[0] == '#' && b[1] == '!') {
            int j = 2;
            while (j < n && isBlank(at(b, j))) {
                j++;
            }
            if (j >= n || b[j] != '[') {
                while (i < n && b[i] != '\n') {
                    i++;
                }
            }
        }
        while (i < n) {
            int c = at(b, i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (isBlank(c)) {
                i++;
                continue;
            }
            int next = i + 1 < n ? at(b, i + 1) : 0;
            if (c == '/' && next == '/') {
                while (i < n && b[i] != '\n') {
                    i++;
                }
                comment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                // Block comments nest.
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (b[i] == '/' && i + 1 < n && b[i + 1] == '*') {
                        depth++;
                        i += 2;
                    } else if (b[i] == '*' && i + 1 < n && b[i + 1] == '/') {
                        depth--;
                        i += 2;
                    } else {
                        if (b[i] == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                if (depth > 0) {
                    errors = true;
                }
                comment = true;
                continue;
            }
            int start = i;
            TokKind kind;
            long raw = c == 'r' ? rawStringEnd(b, i) : (c == 'b' || c == 'c') && next == 'r' ? rawStringEnd(b, i + 1) : -1;
            if (raw >= 0) {
                errors |= !ok(raw);
                i = endOf(raw);
                kind = TokKind.LITERAL;
            } else if ((c == 'b' || c == 'c') && next == '"') {
                long q = quotedEnd(b, i + 1);
                errors |= !ok(q);
                i = endOf(q);
                kind = TokKind.LITERAL;
            } else if (c == 'b' && next == '\'') {
                long q = charEnd(b, i + 1);
                if (q < 0) {
                    q = pack(i + 2, false);
                }
                errors |= !ok(q);
                i = endOf(q);
                kind = TokKind.LITERAL;
            } else if (c == 'r' && next == '#' && i + 2 < n && isIdentStart(at(b, i + 2))) {
                i += 2;
                while (i < n && isIdentContinue(at(b, i))) {
                    i++;
                }
                kind = TokKind.IDENT;
            } else if (isIdentStart(c)) {
                while (i < n && isIdentContinue(at(b, i))) {
                    i++;
                }
                kind = TokKind.IDENT;
            } else if (isDigit(c)) {
                i = numberEnd(b, i);
                kind = TokKind.LITERAL;
            } else if (c == '"') {
                long q = quotedEnd(b, i);
                errors |= !ok(q);
                i = endOf(q);
                kind = TokKind.LITERAL;
            } else if (c == '\'') {
                long q = charEnd(b, i);
                if (q >= 0) {
                    errors |= !ok(q);
                    i = endOf(q);
                    kind = TokKind.LITERAL;
                } else if (i + 1 < n && isIdentStart(at(b, i + 1))) {
                    i++;
                    if (b[i] == 'r' && i + 2 < n && b[i + 1] == '#' && isIdentStart(at(b, i + 2))) {
                        i += 2;
                    }
                    while (i < n && isIdentContinue(at(b, i))) {
                        i++;
                    }
                    kind = TokKind.LIFETIME;
                } else {
                    errors = true;
                    i++;
                    kind = TokKind.PUNCT;
                }
            } else {
                // Any other ASCII character: punctuation. Characters Rust does not use (`` ` ``, control characters)
                // are errors but still become tokens so that nothing is lost.
                if (!isAsciiPunctuation(c) || c == '`' || c == '\\') {
                    errors = true;
                }
                i++;
                kind = TokKind.PUNCT;
            }
            int endLine = line;
            for (int k = start; k < i; k++) {
                if (b[k] == '\n') {
                    endLine++;
                }
            }
            if (!toks.isEmpty()) {
                Token prev = toks.getLast();
                if (prev.kind == TokKind.PUNCT && kind == TokKind.PUNCT && prev.end == start) {
                    prev.joint = true;
                }
            }
            toks.add(new Token(kind, start, i, line, endLine, comment));
            line = endLine;
            comment = false;
        }
        Token[] arr = toks.toArray(new Token[0]);
        errors |= matchDelimiters(b, arr);
        return new Lexed(arr, errors);
    }

    /** Lexes source text (for tests). */
    static Lexed lex(String code) {
        return lex(code.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Pairs {@code (}/{@code )}, {@code [}/{@code ]}, {@code {}/{@code }}. A closer that does not match the innermost
     * open delimiter is left unpaired if no open delimiter matches it, otherwise the delimiters opened after that match
     * stay unpaired. Returns whether any delimiter stayed unpaired.
     */
    private static boolean matchDelimiters(byte[] b, Token[] toks) {
        int[] stack = new int[16];
        int size = 0;
        boolean errors = false;
        for (int k = 0; k < toks.length; k++) {
            if (toks[k].kind != TokKind.PUNCT) {
                continue;
            }
            int c = b[toks[k].start];
            int open;
            if (c == ')') {
                open = '(';
            } else if (c == ']') {
                open = '[';
            } else if (c == '}') {
                open = '{';
            } else if (c == '(' || c == '[' || c == '{') {
                if (size == stack.length) {
                    stack = Arrays.copyOf(stack, size * 2);
                }
                stack[size++] = k;
                continue;
            } else {
                continue;
            }
            int at = size - 1;
            while (at >= 0 && b[toks[stack[at]].start] != open) {
                at--;
            }
            if (at >= 0) {
                if (at + 1 != size) {
                    errors = true;
                }
                int o = stack[at];
                size = at;
                toks[o].pair = k;
                toks[k].pair = o;
            } else {
                errors = true;
            }
        }
        return errors || size > 0;
    }

    // ------------------------------------------------------------------ parser

    /** Primitive types: in type position they produce no {@link NodeKind#TYPE_IDENT} leaf (they are no references). */
    private static final java.util.Set<String> PRIMITIVES = java.util.Set.of(
            "u8", "i8", "u16", "i16", "u32", "i32", "u64", "i64", "u128", "i128", "isize", "usize", "f32", "f64", "bool",
            "str", "char");

    /** The item a statement starts with (see {@link Parser#itemStart}). */
    enum ItemKw {
        FN, STRUCT, UNION, ENUM, TRAIT, IMPL, MOD, USE, TYPE, CONST, STATIC, EXTERN_CRATE, FOREIGN_MOD, MACRO_RULES
    }

    /** Where a path occurs; decides whether {@code <} starts generic arguments and how a single-segment path is classified. */
    enum PathCtx {
        EXPR, TYPE, PATTERN
    }

    /** What {@link Parser#parsePath} found. Nodes for the path itself are emitted by {@link Parser#emitPath}. */
    static final class PathInfo {
        /** First token, and one past the last token of the "name part" (the path without trailing generic arguments). */
        int start;
        int nameEnd;
        /** Node count when the path started (nodes for a qualified {@code <…>} prefix or middle generics follow it). */
        int mark;
        /** Node count where trailing generic arguments start (equal to the node count after the path if none). */
        int tailMark;
        /** Number of {@code ::}-separated segments, not counting a {@code <…>} prefix. */
        int segs;
        /** Starts with {@code <T as Tr>} / {@code <T>}. */
        boolean bracket;
        /** Starts with {@code ::}. */
        boolean leading;
        /** Generic arguments before the last segment ({@code Vec::<T>::new}). */
        boolean mid;
        /** Generic arguments after the last segment ({@code Vec<T>}, {@code f::<T>}). */
        boolean tailArgs;

        boolean single() {
            return segs == 1 && !bracket && !leading && !mid;
        }
    }

    /*
     * Facts about a parsed expression that its context needs, as bit flags (the Rust edition's ExprInfo).
     */
    /** Ends with a block and needs no {@code ;} as a statement ({@code if}, {@code match}, loops, blocks, brace macros). */
    private static final int BLOCK_LIKE = 1;
    /** The whole expression is one macro call (needs no {@code ;} as a statement either). */
    private static final int MACRO_CALL = 2;
    /** A {@code let} condition or a let chain (its {@code &&}s are not binary expressions). */
    private static final int IS_LET = 4;
    private static final int PLAIN = 0;

    /**
     * A binary operator (see {@link Parser#binop}): token count, left and right binding power, whether it is
     * {@code &&}/{@code ||}, whether it is a range.
     */
    private record BinOp(int len, int lbp, int rbp, boolean logic, boolean range) {
    }

    private static final BinOp CMP1 = new BinOp(1, 5, 6, false, false);
    private static final BinOp CMP2 = new BinOp(2, 5, 6, false, false);
    private static final BinOp ASSIGN1 = new BinOp(1, 1, 1, false, false);
    private static final BinOp ASSIGN2 = new BinOp(2, 1, 1, false, false);
    private static final BinOp ASSIGN3 = new BinOp(3, 1, 1, false, false);
    private static final BinOp SHIFT = new BinOp(2, 9, 10, false, false);
    private static final BinOp AND_AND = new BinOp(2, 4, 5, true, false);
    private static final BinOp OR_OR = new BinOp(2, 3, 4, true, false);
    private static final BinOp BIT_AND = new BinOp(1, 8, 9, false, false);
    private static final BinOp BIT_OR = new BinOp(1, 6, 7, false, false);
    private static final BinOp BIT_XOR = new BinOp(1, 7, 8, false, false);
    private static final BinOp ADD = new BinOp(1, 10, 11, false, false);
    private static final BinOp MUL = new BinOp(1, 11, 12, false, false);
    private static final BinOp RANGE3 = new BinOp(3, 2, 3, false, true);
    private static final BinOp RANGE2 = new BinOp(2, 2, 3, false, true);

    /** The recursive-descent parser over one file's tokens. */
    private static final class Parser {
        final byte[] code;
        final Token[] toks;
        int pos;
        /**
         * End of the current group: the index of its closing delimiter (or the token count at the top level). Nothing
         * at or after it is ever looked at.
         */
        int limit;
        final List<Node> nodes;
        boolean errors;
        /** Token index of the first error found by the parser (for diagnostics and tests). */
        int firstError;
        int depth;

        Parser(byte[] code, Token[] toks, boolean lexErrors) {
            this.code = code;
            this.toks = toks;
            this.limit = toks.length;
            this.nodes = new ArrayList<>(toks.length / 3 + 4);
            this.errors = lexErrors;
            this.firstError = lexErrors ? 0 : NONE;
        }

        // ---------------------------------------------------------- token helpers

        boolean atEnd() {
            return pos >= limit;
        }

        /** Text of token {@code i}. */
        String text(int i) {
            return utf8(code, toks[i].start, toks[i].end);
        }

        /** Token {@code i}'s text equals the ASCII string {@code s} (compared without decoding). */
        boolean textIs(int i, String s) {
            Token t = toks[i];
            int len = t.end - t.start;
            if (len != s.length()) {
                return false;
            }
            for (int k = 0; k < len; k++) {
                if (code[t.start + k] != s.charAt(k)) {
                    return false;
                }
            }
            return true;
        }

        boolean isKind(int i, TokKind k) {
            return i < limit && toks[i].kind == k;
        }

        boolean isIdent(int i) {
            return isKind(i, TokKind.IDENT);
        }

        /** Token {@code i} is the punctuation character {@code ch}. */
        boolean punctAt(int i, char ch) {
            return i < limit && toks[i].kind == TokKind.PUNCT && code[toks[i].start] == ch;
        }

        boolean punct(char ch) {
            return punctAt(pos, ch);
        }

        /** Tokens from {@code i} spell the operator {@code s} without gaps ({@code ::}, {@code &&}, {@code ..=}). */
        boolean opAt(int i, String s) {
            for (int k = 0; k < s.length(); k++) {
                if (!punctAt(i + k, s.charAt(k))) {
                    return false;
                }
                if (k + 1 < s.length() && !toks[i + k].joint) {
                    return false;
                }
            }
            return true;
        }

        boolean op(String s) {
            return opAt(pos, s);
        }

        boolean kwAt(int i, String s) {
            return isIdent(i) && textIs(i, s);
        }

        boolean kw(String s) {
            return kwAt(pos, s);
        }

        boolean isOpener(int i) {
            return punctAt(i, '(') || punctAt(i, '[') || punctAt(i, '{');
        }

        /** A single {@code :} (not {@code ::}). */
        boolean colonAt(int i) {
            return punctAt(i, ':') && !opAt(i, "::");
        }

        void bump() {
            if (pos < limit) {
                pos++;
            }
        }

        void bumpN(int n) {
            for (int k = 0; k < n; k++) {
                bump();
            }
        }

        boolean eatPunct(char ch) {
            if (punct(ch)) {
                bump();
                return true;
            }
            return false;
        }

        void expectPunct(char ch) {
            if (!eatPunct(ch)) {
                err();
            }
        }

        boolean eatKw(String s) {
            if (kw(s)) {
                bump();
                return true;
            }
            return false;
        }

        /** Skips one token, or a whole group if at an opening delimiter. */
        void skipTree() {
            int p = pos < toks.length ? toks[pos].pair : NONE;
            if (isOpener(pos) && p != NONE && p < limit) {
                pos = p + 1;
            } else {
                bump();
            }
        }

        /**
         * Enters the group opening at the current token: the limit becomes its closing delimiter. Returns the outer
         * limit, to be handed to {@link #closeGroup}, or -1 (error, nothing consumed) if not at a matched opener.
         */
        int openGroup() {
            if (!isOpener(pos)) {
                err();
                return -1;
            }
            int p = toks[pos].pair;
            if (p == NONE || p >= limit) {
                err();
                return -1;
            }
            int saved = limit;
            limit = p;
            pos++;
            return saved;
        }

        /** Leaves the current group: anything not parsed inside it is an error; continues after the closer. */
        void closeGroup(int saved) {
            if (pos < limit) {
                err();
            }
            pos = limit + 1;
            limit = saved;
        }

        /** Counts one level of recursion. Beyond {@link #MAX_DEPTH}, gives up on the rest of the current group. */
        boolean enter() {
            if (depth >= MAX_DEPTH) {
                err();
                pos = limit;
                return false;
            }
            depth++;
            return true;
        }

        void leave() {
            depth--;
        }

        /** Saved parser state for backtracking (see {@link #parseParams}): position, node count, error state. */
        long[] checkpoint() {
            return new long[]{pos, nodes.size(), errors ? 1 : 0, firstError};
        }

        void restore(long[] c) {
            pos = (int) c[0];
            nodes.subList((int) c[1], nodes.size()).clear();
            errors = c[2] != 0;
            firstError = (int) c[3];
        }

        /** Records a syntax error at the current token. */
        void err() {
            if (!errors) {
                firstError = Math.min(pos, toks.length);
            }
            errors = true;
        }

        // ---------------------------------------------------------- node helpers

        /** Starts a node at the current token; finish it with {@link #close}. */
        int open(NodeKind kind) {
            return openAt(kind, pos);
        }

        int openAt(NodeKind kind, int first) {
            nodes.add(new Node(kind, first, first, 0));
            return nodes.size() - 1;
        }

        /** Ends node {@code n} at the last consumed token; everything emitted since it was opened are its descendants. */
        void close(int n) {
            Node node = nodes.get(n);
            node.last = pos > node.first ? pos - 1 : node.first;
            node.end = nodes.size();
        }

        void leaf(NodeKind kind, int tok) {
            leafRange(kind, tok, tok);
        }

        void leafRange(NodeKind kind, int first, int last) {
            nodes.add(new Node(kind, first, last, nodes.size() + 1));
        }

        /**
         * Inserts a node at index {@code at} whose descendants are the nodes {@code at .. coverEnd - 1} emitted so far.
         * Used where the kind of a node is only known after its contents were parsed (qualified paths, {@code impl}
         * headers).
         */
        int wrap(int at, int coverEnd, NodeKind kind, int first, int last) {
            for (int k = at; k < nodes.size(); k++) {
                nodes.get(k).end++;
            }
            nodes.add(at, new Node(kind, first, last, coverEnd + 1));
            return at;
        }

        // ---------------------------------------------------------- items

        /**
         * Parses a list of items until the end of the current group. {@code top}: the file itself, where statements
         * are accepted too; inside {@code { … }} of a module, trait, impl or extern block only items and macro
         * calls are valid.
         */
        void parseItems(boolean top) {
            if (!enter()) {
                return;
            }
            while (!atEnd()) {
                int before = pos;
                if (toks[pos].commentBefore) {
                    leaf(NodeKind.COMMENT, pos);
                }
                if (atAttr()) {
                    parseAttr();
                } else {
                    long item = itemStart(pos);
                    if (item >= 0) {
                        parseItem(itemKw(item), itemAt(item));
                    } else {
                        int n = open(NodeKind.OTHER);
                        if (!top && !punct(';') && !isMacroCall(pos)) {
                            err();
                        }
                        parseStmtRest();
                        close(n);
                    }
                }
                if (pos == before) {
                    err();
                    skipTree();
                }
            }
            leave();
        }

        /** At {@code #[} or {@code #![}. */
        boolean atAttr() {
            return punct('#') && (punctAt(pos + 1, '[') || (punctAt(pos + 1, '!') && punctAt(pos + 2, '[')));
        }

        /** Parses an outer ({@code #[…]}) or inner ({@code #![…]}) attribute: a path, then {@code = expr} or a token tree. */
        void parseAttr() {
            boolean inner = punctAt(pos + 1, '!');
            int n = open(inner ? NodeKind.INNER_ATTR : NodeKind.ATTR);
            bumpN(inner ? 2 : 1);
            int saved = openGroup();
            if (saved >= 0) {
                if (isIdent(pos) || op("::")) {
                    PathInfo p = parsePath(PathCtx.EXPR);
                    emitPath(p, NodeKind.IDENT);
                } else {
                    err();
                }
                if (punct('=')) {
                    bump();
                    parseExpr(false);
                } else if (isOpener(pos)) {
                    tokenTree(false);
                }
                closeGroup(saved);
            } else {
                bump();
            }
            close(n);
        }

        /** Skips outer attributes (still emitting their references), e.g. before fields, parameters and match arms. */
        void parseOuterAttrs() {
            while (atAttr()) {
                parseAttr();
            }
        }

        /** Packs an {@link #itemStart} result. */
        private static long item(ItemKw kw, int at) {
            return ((long) at << 8) | kw.ordinal();
        }

        private static ItemKw itemKw(long item) {
            return ItemKw.values()[(int) (item & 0xFF)];
        }

        private static int itemAt(long item) {
            return (int) (item >>> 8);
        }

        /** Any of the keywords is at token {@code i}. */
        boolean kwAnyAt(int i, String... s) {
            for (String k : s) {
                if (kwAt(i, k)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * If an item starts at token {@code at}, its kind and the index of its keyword, after visibility and
         * qualifiers ({@code pub(crate) const unsafe fn} → {@code FN} at {@code fn}), packed (see {@link #itemKw},
         * {@link #itemAt}); -1 if none. Contextual keywords ({@code union}, {@code auto}, {@code default},
         * {@code safe}, {@code macro_rules}) and words that also start expressions ({@code const {}, {@code unsafe {},
         * {@code async move}, {@code static ||}) are told apart by the following tokens.
         */
        long itemStart(int at) {
            int j = at;
            if (kwAt(j, "pub")) {
                j++;
                if (punctAt(j, '(') && kwAnyAt(j + 1, "crate", "self", "super", "in")) {
                    int p = toks[j].pair;
                    if (p != NONE && p < limit) {
                        j = p + 1;
                    }
                }
            }
            while (true) {
                if (!isIdent(j)) {
                    return -1;
                }
                switch (text(j)) {
                    case "fn":
                        return item(ItemKw.FN, j);
                    case "struct":
                        return item(ItemKw.STRUCT, j);
                    case "enum":
                        return item(ItemKw.ENUM, j);
                    case "trait":
                        return item(ItemKw.TRAIT, j);
                    case "impl":
                        return item(ItemKw.IMPL, j);
                    case "mod":
                        return item(ItemKw.MOD, j);
                    case "use":
                        return item(ItemKw.USE, j);
                    case "type":
                        return item(ItemKw.TYPE, j);
                    case "union": {
                        boolean ok = isIdent(j + 1) && (punctAt(j + 2, '{') || punctAt(j + 2, '<') || kwAt(j + 2, "where"));
                        return ok ? item(ItemKw.UNION, j) : -1;
                    }
                    case "static": {
                        boolean ok = kwAnyAt(j + 1, "mut", "ref")
                                || (isIdent(j + 1) && !kwAt(j + 1, "move") && colonAt(j + 2));
                        return ok ? item(ItemKw.STATIC, j) : -1;
                    }
                    case "macro_rules":
                        return punctAt(j + 1, '!') && !opAt(j + 1, "!=") ? item(ItemKw.MACRO_RULES, j) : -1;
                    case "extern": {
                        if (kwAt(j + 1, "crate")) {
                            return item(ItemKw.EXTERN_CRATE, j);
                        }
                        int k = isKind(j + 1, TokKind.LITERAL) ? j + 2 : j + 1;
                        if (punctAt(k, '{')) {
                            return item(ItemKw.FOREIGN_MOD, k);
                        }
                        j = k;
                        break;
                    }
                    case "const":
                        if (kwAnyAt(j + 1, "fn", "unsafe", "async", "extern")) {
                            j++;
                        } else if (isIdent(j + 1)) {
                            return item(ItemKw.CONST, j);
                        } else {
                            return -1;
                        }
                        break;
                    case "async":
                        if (kwAnyAt(j + 1, "fn", "unsafe", "extern")) {
                            j++;
                        } else {
                            return -1;
                        }
                        break;
                    case "unsafe":
                        if (kwAnyAt(j + 1, "fn", "impl", "trait", "extern", "auto", "mod")) {
                            j++;
                        } else {
                            return -1;
                        }
                        break;
                    case "safe":
                        if (kwAnyAt(j + 1, "fn", "static")) {
                            j++;
                        } else {
                            return -1;
                        }
                        break;
                    case "default":
                        if (kwAnyAt(j + 1, "fn", "impl", "unsafe", "const", "async", "type", "extern")) {
                            j++;
                        } else {
                            return -1;
                        }
                        break;
                    case "auto":
                        return kwAt(j + 1, "trait") ? item(ItemKw.TRAIT, j + 1) : -1;
                    default:
                        return -1;
                }
            }
        }

        /** {@code path!} followed by a delimiter at token {@code i}. */
        boolean isMacroCall(int i) {
            int j = i;
            if (opAt(j, "::")) {
                j += 2;
            }
            while (true) {
                if (!isIdent(j)) {
                    return false;
                }
                j++;
                if (opAt(j, "::")) {
                    j += 2;
                    continue;
                }
                break;
            }
            return punctAt(j, '!') && !opAt(j, "!=") && isOpener(j + 1);
        }

        /** {@code pub}, {@code pub(crate)}, {@code pub(in a::b)}: only the {@code in} path has references. */
        void parseVisibility() {
            if (!eatKw("pub")) {
                return;
            }
            if (punct('(') && kwAnyAt(pos + 1, "crate", "self", "super", "in")) {
                int saved = openGroup();
                if (saved >= 0) {
                    if (eatKw("in")) {
                        PathInfo p = parsePath(PathCtx.EXPR);
                        emitPath(p, NodeKind.IDENT);
                    } else {
                        bump();
                    }
                    closeGroup(saved);
                }
            }
        }

        /** Emits the name token of an item as a reference leaf of the given kind and stores it as the item's name. */
        void itemName(int n, NodeKind kind) {
            if (isIdent(pos)) {
                nodes.get(n).name = pos;
                leaf(kind, pos);
                bump();
            } else {
                err();
            }
        }

        /** Parses an item whose keyword (as found by {@link #itemStart}) is at token {@code kwpos}. */
        void parseItem(ItemKw kw, int kwpos) {
            NodeKind kind = switch (kw) {
                case FN -> NodeKind.FN;
                case STRUCT -> NodeKind.STRUCT;
                case UNION -> NodeKind.UNION;
                case ENUM -> NodeKind.ENUM;
                case TRAIT -> NodeKind.TRAIT;
                case IMPL -> NodeKind.IMPL;
                case MOD -> NodeKind.MOD;
                case USE -> NodeKind.USE;
                case TYPE -> NodeKind.TYPE_ALIAS;
                case CONST -> NodeKind.CONST;
                case STATIC -> NodeKind.STATIC;
                case EXTERN_CRATE -> NodeKind.EXTERN_CRATE;
                case FOREIGN_MOD -> NodeKind.FOREIGN_MOD;
                case MACRO_RULES -> NodeKind.MACRO_RULES;
            };
            int n = open(kind);
            parseVisibility();
            // Qualifiers (`const`, `async`, `unsafe`, `extern "C"`, …) carry no references.
            if (kwpos >= pos && kwpos < limit) {
                pos = kwpos;
            }
            switch (kw) {
                case FN -> parseFn(n);
                case STRUCT, UNION -> {
                    bump();
                    itemName(n, NodeKind.TYPE_IDENT);
                    parseGenericParamsOpt();
                    parseWhereOpt();
                    if (punct('{')) {
                        nodes.get(n).value = parseFields();
                    } else if (kw == ItemKw.STRUCT && punct('(')) {
                        nodes.get(n).value = parseTupleFields();
                        parseWhereOpt();
                        expectPunct(';');
                    } else {
                        expectPunct(';');
                    }
                }
                case ENUM -> {
                    bump();
                    itemName(n, NodeKind.TYPE_IDENT);
                    parseGenericParamsOpt();
                    parseWhereOpt();
                    nodes.get(n).value = parseVariants();
                }
                case TRAIT -> {
                    bump();
                    itemName(n, NodeKind.TYPE_IDENT);
                    parseGenericParamsOpt();
                    if (colonAt(pos)) {
                        bump();
                        parseBoundsOpt();
                    }
                    parseWhereOpt();
                    if (punct('=')) {
                        // trait alias
                        bump();
                        parseBoundsOpt();
                        parseWhereOpt();
                        expectPunct(';');
                    } else {
                        parseDeclBody();
                    }
                }
                case IMPL -> parseImpl();
                case MOD -> {
                    bump();
                    itemName(n, NodeKind.IDENT);
                    if (!eatPunct(';')) {
                        parseDeclBody();
                    }
                }
                case USE -> {
                    bump();
                    parseUseTree();
                    expectPunct(';');
                }
                case TYPE -> {
                    bump();
                    itemName(n, NodeKind.TYPE_IDENT);
                    parseGenericParamsOpt();
                    if (colonAt(pos)) {
                        bump();
                        parseBoundsOpt();
                    }
                    parseWhereOpt();
                    if (eatPunct('=')) {
                        parseType(true);
                    }
                    parseWhereOpt();
                    expectPunct(';');
                }
                case CONST, STATIC -> {
                    bump();
                    if (kw == ItemKw.STATIC) {
                        eatKw("ref");
                        eatKw("mut");
                    }
                    itemName(n, NodeKind.IDENT);
                    parseGenericParamsOpt();
                    if (colonAt(pos)) {
                        bump();
                        parseType(true);
                    } else {
                        err();
                    }
                    if (eatPunct('=')) {
                        parseExpr(false);
                    }
                    parseWhereOpt();
                    expectPunct(';');
                }
                case EXTERN_CRATE -> {
                    bumpN(2);
                    itemName(n, NodeKind.IDENT);
                    if (eatKw("as")) {
                        if (isIdent(pos)) {
                            leaf(NodeKind.IDENT, pos);
                            bump();
                        } else {
                            err();
                        }
                    }
                    expectPunct(';');
                }
                case FOREIGN_MOD -> parseDeclBody();
                case MACRO_RULES -> {
                    bumpN(2);
                    itemName(n, NodeKind.IDENT);
                    boolean paren = !punct('{');
                    if (isOpener(pos)) {
                        tokenTree(true);
                        if (paren) {
                            expectPunct(';');
                        }
                    } else {
                        err();
                    }
                }
            }
            close(n);
        }

        /** {@code fn name<…>(params) -> T where … { body }} or {@code … ;} (a signature in a trait or extern block). */
        void parseFn(int n) {
            bump();
            itemName(n, NodeKind.IDENT);
            parseGenericParamsOpt();
            if (punct('(')) {
                nodes.get(n).value = parseParams();
            } else {
                err();
            }
            if (op("->")) {
                bumpN(2);
                parseType(true);
            }
            parseWhereOpt();
            if (punct('{')) {
                int b = open(NodeKind.BODY);
                parseBlock();
                close(b);
            } else {
                expectPunct(';');
            }
        }

        /**
         * {@code impl<…> !? Trait for Type where … { items }}. The trait and type are wrapped into
         * {@code IMPL_TRAIT}/{@code IMPL_TYPE} nodes once it is known which is which (the first type is the trait iff
         * {@code for} follows it).
         */
        void parseImpl() {
            bump();
            if (punct('<')) {
                parseGenericParams();
            }
            eatKw("const");
            // `impl !Trait for T`; but `impl ! {}` implements for the never type
            if (punct('!') && canStartType(pos + 1)) {
                bump();
            }
            int mark = nodes.size();
            int start = pos;
            long core = parseType(true);
            if (kw("for") && !punctAt(pos + 1, '<')) {
                wrapImplPart(mark, NodeKind.IMPL_TRAIT, start, core);
                bump();
                int mark2 = nodes.size();
                int start2 = pos;
                long core2 = parseType(true);
                wrapImplPart(mark2, NodeKind.IMPL_TYPE, start2, core2);
            } else {
                wrapImplPart(mark, NodeKind.IMPL_TYPE, start, core);
            }
            parseWhereOpt();
            if (!eatPunct(';')) {
                parseDeclBody();
            }
        }

        /** Wraps the nodes of an impl header type (from {@code mark}) into an {@code IMPL_TRAIT}/{@code IMPL_TYPE} node. */
        private void wrapImplPart(int mark, NodeKind kind, int start, long core) {
            int t = wrap(mark, nodes.size(), kind, start, Math.max(pos - 1, start));
            nodes.get(t).name = coreFirst(core);
            nodes.get(t).value = coreLast(core);
        }

        /** {@code { items }} of a module, trait, impl or extern block. */
        void parseDeclBody() {
            if (!punct('{')) {
                err();
                return;
            }
            int b = open(NodeKind.DECL_BODY);
            int saved = openGroup();
            if (saved >= 0) {
                parseItems(false);
                closeGroup(saved);
            }
            close(b);
        }

        /** {@code { attrs* vis? name: Type, … }}; returns the number of fields. */
        int parseFields() {
            int count = 0;
            int saved = openGroup();
            if (saved < 0) {
                return 0;
            }
            while (!atEnd()) {
                parseOuterAttrs();
                if (atEnd()) {
                    break;
                }
                parseVisibility();
                if ((isIdent(pos) || isKind(pos, TokKind.LITERAL)) && colonAt(pos + 1)) {
                    bumpN(2); // field names are not references
                    parseType(true);
                    count++;
                } else {
                    err();
                    break;
                }
                if (!eatPunct(',')) {
                    break;
                }
            }
            closeGroup(saved);
            return count;
        }

        /** {@code ( attrs* vis? Type, … )}; returns the number of fields. */
        int parseTupleFields() {
            int count = 0;
            int saved = openGroup();
            if (saved < 0) {
                return 0;
            }
            while (!atEnd()) {
                parseOuterAttrs();
                if (atEnd()) {
                    break;
                }
                parseVisibility();
                int before = pos;
                parseType(true);
                if (pos == before) {
                    break;
                }
                count++;
                if (!eatPunct(',')) {
                    break;
                }
            }
            closeGroup(saved);
            return count;
        }

        /** {@code { attrs* vis? Name ({…} | (…))? (= expr)?, … }}; returns the number of variants. */
        int parseVariants() {
            int count = 0;
            int saved = openGroup();
            if (saved < 0) {
                return 0;
            }
            while (!atEnd()) {
                parseOuterAttrs();
                if (atEnd()) {
                    break;
                }
                parseVisibility();
                if (!isIdent(pos)) {
                    err();
                    break;
                }
                // Variant names are IDENT leaves, so they are references when capitalised.
                leaf(NodeKind.IDENT, pos);
                bump();
                count++;
                if (punct('{')) {
                    parseFields();
                } else if (punct('(')) {
                    parseTupleFields();
                }
                if (eatPunct('=')) {
                    parseExpr(false);
                }
                if (!eatPunct(',')) {
                    break;
                }
            }
            closeGroup(saved);
            return count;
        }

        /**
         * Function parameters {@code ( … )}; returns how many count as parameters: {@code pattern: Type},
         * including {@code self: Box<Self>}, but not {@code self}/{@code &mut self}, variadic {@code ...}, {@code _} or
         * anonymous types ({@code fn(u8)}). A parameter is first tried as {@code pattern: Type}; without the colon the
         * parser backtracks and reads it as a type.
         */
        int parseParams() {
            int count = 0;
            int saved = openGroup();
            if (saved < 0) {
                return 0;
            }
            while (!atEnd()) {
                parseOuterAttrs();
                if (atEnd()) {
                    break;
                }
                int before = pos;
                // self parameter: `&'a mut self`, `mut self`, `self`
                int j = pos;
                boolean amp = false;
                if (punctAt(j, '&')) {
                    amp = true;
                    j++;
                    if (isKind(j, TokKind.LIFETIME)) {
                        j++;
                    }
                }
                if (kwAt(j, "mut")) {
                    j++;
                }
                if (kwAt(j, "self") && !opAt(j + 1, "::")) {
                    if (amp && isKind(pos + 1, TokKind.LIFETIME)) {
                        leaf(NodeKind.IDENT, pos + 1);
                    }
                    pos = j + 1;
                    if (!amp && colonAt(pos)) {
                        bump();
                        parseType(true);
                        count++;
                    }
                } else if (op("...")) {
                    bumpN(3);
                } else if (kw("_") && (punctAt(pos + 1, ',') || pos + 1 >= limit)) {
                    bump();
                } else {
                    long[] cp = checkpoint();
                    eatKw("mut");
                    parsePatternNoAlt();
                    if (colonAt(pos)) {
                        bump();
                        if (op("...")) {
                            bumpN(3);
                        } else {
                            parseType(true);
                            count++;
                        }
                    } else {
                        restore(cp);
                        parseType(true);
                    }
                }
                if (pos == before) {
                    err();
                    break;
                }
                if (!eatPunct(',')) {
                    break;
                }
            }
            closeGroup(saved);
            return count;
        }

        // ---------------------------------------------------------- use trees

        /** A use tree: {@code a::b}, {@code a::b as c}, {@code a::{…}}, {@code a::*}, {@code {…}}, {@code *}. */
        void parseUseTree() {
            int start = pos;
            if (punct('{')) {
                int n = open(NodeKind.USE_GROUP);
                parseUseList();
                close(n);
                return;
            }
            if (punct('*')) {
                leaf(NodeKind.USE_GLOB, pos);
                bump();
                return;
            }
            int pathEnd = pos;
            if (op("::")) {
                bumpN(2);
            }
            while (isIdent(pos)) {
                bump();
                pathEnd = pos;
                if (op("::") && isIdent(pos + 2)) {
                    bumpN(2);
                } else {
                    break;
                }
            }
            boolean hasPath = pathEnd > start;
            if ((op("::") && punctAt(pos + 2, '{')) || (!hasPath && punct('{'))) {
                int n = openAt(NodeKind.USE_GROUP, start);
                if (hasPath) {
                    leafRange(NodeKind.USE_PREFIX, start, pathEnd - 1);
                }
                if (op("::")) {
                    bumpN(2);
                }
                parseUseList();
                close(n);
            } else if ((op("::") && punctAt(pos + 2, '*')) || (!hasPath && punct('*'))) {
                int n = openAt(NodeKind.USE_GLOB, start);
                if (hasPath) {
                    leafRange(NodeKind.USE_PREFIX, start, pathEnd - 1);
                }
                if (op("::")) {
                    bumpN(2);
                }
                bump();
                close(n);
            } else if (!hasPath) {
                err();
            } else if (kw("as")) {
                int n = openAt(NodeKind.USE_AS, start);
                leafRange(NodeKind.USE_PATH, start, pathEnd - 1);
                bump();
                if (isIdent(pos)) {
                    nodes.get(n).name = pos;
                    bump();
                } else {
                    err();
                }
                close(n);
            } else {
                leafRange(NodeKind.USE_PATH, start, pathEnd - 1);
            }
        }

        /** {@code { tree, … }}. Nested groups count towards {@link #MAX_DEPTH} like any other recursion. */
        void parseUseList() {
            int saved = openGroup();
            if (saved < 0) {
                return;
            }
            if (enter()) {
                while (!atEnd()) {
                    int before = pos;
                    parseUseTree();
                    if (pos == before || !eatPunct(',')) {
                        break;
                    }
                }
                leave();
            }
            closeGroup(saved);
        }

        // ---------------------------------------------------------- generics

        void parseGenericParamsOpt() {
            if (punct('<')) {
                parseGenericParams();
            }
        }

        /** {@code <'a: 'b, T: Bound = Default, const N: usize = 3>}. Type parameter names are {@code type_identifier}s. */
        void parseGenericParams() {
            bump();
            while (!atEnd() && !punct('>')) {
                int before = pos;
                parseOuterAttrs();
                if (isKind(pos, TokKind.LIFETIME)) {
                    leaf(NodeKind.IDENT, pos);
                    bump();
                    if (colonAt(pos)) {
                        bump();
                        parseBoundsOpt();
                    }
                } else if (kw("const") && isIdent(pos + 1)) {
                    bump();
                    leaf(NodeKind.IDENT, pos);
                    bump();
                    if (colonAt(pos)) {
                        bump();
                        parseType(true);
                    }
                    if (eatPunct('=')) {
                        if (punct('{')) {
                            parseBlock();
                        } else if (isIdent(pos)) {
                            leaf(NodeKind.IDENT, pos);
                            bump();
                        } else {
                            eatPunct('-');
                            if (isKind(pos, TokKind.LITERAL)) {
                                bump();
                            } else {
                                err();
                            }
                        }
                    }
                } else if (isIdent(pos)) {
                    leaf(NodeKind.TYPE_IDENT, pos);
                    bump();
                    if (colonAt(pos)) {
                        bump();
                        parseBoundsOpt();
                    }
                    if (eatPunct('=')) {
                        parseType(true);
                    }
                } else {
                    err();
                    break;
                }
                if (pos == before || !eatPunct(',')) {
                    break;
                }
            }
            expectPunct('>');
        }

        /** Bounds after {@code :} ({@code 'a + Clone + ?Sized + for<'b> Fn(&'b T)}), possibly empty. */
        void parseBoundsOpt() {
            if (canStartType(pos)) {
                parseType(true);
            }
        }

        /** {@code where T: A + B, 'a: 'b, for<'c> &'c T: C} (ends before {@code {}, {@code ;} or {@code =}). */
        void parseWhereOpt() {
            if (!eatKw("where")) {
                return;
            }
            while (!atEnd()) {
                int before = pos;
                if (isKind(pos, TokKind.LIFETIME)) {
                    leaf(NodeKind.IDENT, pos);
                    bump();
                } else if (canStartType(pos)) {
                    if (kw("for") && punctAt(pos + 1, '<')) {
                        bump();
                        parseGenericParams();
                    }
                    parseType(false);
                } else {
                    break;
                }
                if (colonAt(pos)) {
                    bump();
                    parseBoundsOpt();
                } else {
                    err();
                }
                if (pos == before || !eatPunct(',')) {
                    break;
                }
            }
        }

        /** Generic arguments {@code <T, 'a, 3, {N}, Item = U, Item: Bound>} (at the {@code <}). */
        void parseGenericArgs() {
            bump();
            while (!atEnd() && !punct('>')) {
                int before = pos;
                if (isKind(pos, TokKind.LIFETIME)) {
                    leaf(NodeKind.IDENT, pos);
                    bump();
                } else if (isKind(pos, TokKind.LITERAL) || kw("true") || kw("false")) {
                    bump();
                } else if (punct('-') && isKind(pos + 1, TokKind.LITERAL)) {
                    bumpN(2);
                } else if (punct('{')) {
                    parseBlock();
                } else {
                    parseType(true);
                    if (punct('=') && !op("==")) {
                        bump();
                        parseType(true);
                    }
                }
                if (colonAt(pos)) {
                    bump();
                    parseBoundsOpt();
                }
                if (pos == before || !eatPunct(',')) {
                    break;
                }
            }
            expectPunct('>');
        }

        // ---------------------------------------------------------- paths

        /**
         * Parses a path ({@code a::b::C}, {@code ::a}, {@code <T as Tr>::X}, {@code Vec::<T>::new}, {@code Vec<T>} in
         * types) up to and including trailing generic arguments. Nodes for generic arguments and the {@code <…>} prefix
         * are emitted; the path's own node is emitted by {@link #emitPath} once the caller knows what follows.
         */
        PathInfo parsePath(PathCtx ctx) {
            PathInfo p = new PathInfo();
            p.start = pos;
            p.nameEnd = pos;
            p.mark = nodes.size();
            if (punct('<')) {
                p.bracket = true;
                bump();
                parseType(true);
                if (eatKw("as")) {
                    parseType(true);
                }
                expectPunct('>');
                if (op("::")) {
                    bumpN(2);
                } else {
                    err();
                }
            } else if (op("::")) {
                p.leading = true;
                bumpN(2);
            }
            while (true) {
                if (!isIdent(pos)) {
                    err();
                    break;
                }
                bump();
                p.segs++;
                p.nameEnd = pos;
                int argsMark = nodes.size();
                boolean args = false;
                if (ctx == PathCtx.TYPE && punct('<') && !op("<=")) {
                    parseGenericArgs();
                    args = true;
                } else if (op("::") && punctAt(pos + 2, '<')) {
                    bumpN(2);
                    parseGenericArgs();
                    args = true;
                }
                if (args) {
                    if (op("::") && isIdent(pos + 2)) {
                        p.mid = true;
                        bumpN(2);
                        continue;
                    }
                    p.tailArgs = true;
                    p.tailMark = argsMark;
                    break;
                }
                if (op("::") && isIdent(pos + 2)) {
                    bumpN(2);
                    continue;
                }
                break;
            }
            if (!p.tailArgs) {
                p.tailMark = nodes.size();
            }
            return p;
        }

        /**
         * Emits the node for a parsed path (see "Reference leaves" in the class docs). {@code single}: the leaf kind for
         * a one-segment path, or null for no node. {@code self}, {@code super} and {@code crate} alone are never
         * identifiers.
         */
        void emitPath(PathInfo p, NodeKind single) {
            if (p.segs == 0) {
                return;
            }
            if (p.bracket && p.segs == 1 && !p.mid) {
                return; // the types in the brackets were emitted already
            }
            if (p.bracket || p.mid) {
                wrap(p.mark, p.tailMark, NodeKind.PATH, p.start, p.nameEnd - 1);
            } else if (p.single()) {
                if (single != null && !textIs(p.start, "self") && !textIs(p.start, "super") && !textIs(p.start, "crate")) {
                    leaf(single, p.start);
                }
            } else {
                leafRange(NodeKind.PATH, p.start, p.nameEnd - 1);
            }
        }

        // ---------------------------------------------------------- types

        boolean canStartType(int i) {
            if (i >= limit) {
                return false;
            }
            return switch (toks[i].kind) {
                case IDENT -> !(textIs(i, "where") || textIs(i, "as") || textIs(i, "in") || textIs(i, "else"));
                case LIFETIME -> true;
                case LITERAL -> false;
                case PUNCT -> {
                    byte c = code[toks[i].start];
                    yield c == '(' || c == '[' || c == '&' || c == '*' || c == '!' || c == '?' || c == '<'
                            || opAt(i, "::");
                }
            };
        }

        /** Packs the first and last token of a type's core (see {@link #parseType}). */
        private static long core(int first, int last) {
            return ((long) first << 32) | (last & 0xFFFFFFFFL);
        }

        private static int coreFirst(long core) {
            return (int) (core >> 32);
        }

        private static int coreLast(long core) {
            return (int) core;
        }

        /**
         * Parses a type. {@code plus}: {@code A + B} bound lists are allowed (not after {@code as} or inside
         * {@code &}/{@code *}). Returns the first and last token of the type's "core" (packed, see {@link #coreFirst},
         * {@link #coreLast}) as the analyser's impl-type name: {@code &'a mut T} → core of {@code T},
         * {@code a::B<T>} → {@code a::B}, anything else → the whole type.
         */
        long parseType(boolean plus) {
            int start = pos;
            if (!enter()) {
                return core(start, start);
            }
            long core = core(start, start);
            boolean whole = true;
            if (atEnd()) {
                err();
            } else if (punct('(') || punct('[')) {
                boolean array = punct('[');
                int saved = openGroup();
                if (saved >= 0) {
                    while (!atEnd()) {
                        int before = pos;
                        parseType(true);
                        if (array && eatPunct(';')) {
                            parseExpr(false);
                        }
                        if (pos == before || !eatPunct(',')) {
                            break;
                        }
                    }
                    closeGroup(saved);
                } else {
                    bump();
                }
            } else if (punct('&') || punct('*')) {
                boolean star = punct('*');
                bump();
                if (!star && isKind(pos, TokKind.LIFETIME)) {
                    leaf(NodeKind.IDENT, pos);
                    bump();
                }
                if (!eatKw("mut") && star) {
                    eatKw("const");
                }
                core = parseType(false);
                whole = false;
            } else if (punct('!')) {
                bump();
            } else if (punct('?')) {
                bump();
                parseType(false);
            } else if (isKind(pos, TokKind.LIFETIME)) {
                leaf(NodeKind.IDENT, pos);
                bump();
            } else if (kw("impl") || kw("dyn")) {
                bump();
                parseType(plus);
            } else if (kw("for") && punctAt(pos + 1, '<')) {
                bump();
                parseGenericParams();
                parseType(false);
            } else if (kw("fn") || kw("unsafe") || kw("extern")) {
                eatKw("unsafe");
                if (eatKw("extern") && isKind(pos, TokKind.LITERAL)) {
                    bump();
                }
                if (eatKw("fn") && punct('(')) {
                    parseParams();
                    if (op("->")) {
                        bumpN(2);
                        parseType(false);
                    }
                } else {
                    err();
                }
            } else if (kw("use") && punctAt(pos + 1, '<')) {
                // precise capturing `use<'a, T>`
                bump();
                parseGenericArgs();
            } else if (isIdent(pos) || op("::") || punct('<')) {
                PathInfo p = parsePath(PathCtx.TYPE);
                NodeKind singleKind = p.single() && !p.tailArgs && PRIMITIVES.contains(text(p.start))
                        ? null : NodeKind.TYPE_IDENT;
                if (!p.tailArgs && punct('!') && !op("!=") && isOpener(pos + 1)) {
                    emitPath(p, NodeKind.IDENT);
                    bump();
                    tokenTree(false);
                } else if (!p.tailArgs && punct('(')) {
                    // `Fn(A) -> B` sugar: a function type with the path as its trait
                    emitPath(p, singleKind);
                    parseParams();
                    if (op("->")) {
                        bumpN(2);
                        parseType(false);
                    }
                } else {
                    emitPath(p, singleKind);
                    core = core(p.start, Math.max(p.nameEnd - 1, p.start));
                    whole = false;
                }
            } else {
                err();
            }
            if (plus && punct('+') && !op("+=")) {
                while (punct('+') && !op("+=")) {
                    bump();
                    if (canStartType(pos)) {
                        parseType(false);
                    }
                }
                whole = true;
            }
            if (whole) {
                core = core(start, Math.max(pos - 1, start));
            }
            leave();
            return core;
        }

        // ---------------------------------------------------------- patterns

        /** A pattern with alternatives ({@code A | B}, also with a leading {@code |}). */
        void parsePattern() {
            if (punct('|') && !op("||")) {
                bump();
            }
            parsePatternNoAlt();
            while (punct('|') && !op("||") && !op("|=")) {
                bump();
                parsePatternNoAlt();
            }
        }

        /** One pattern without top-level alternatives (closure parameters cannot have them). */
        void parsePatternNoAlt() {
            if (!enter()) {
                return;
            }
            if (atEnd()) {
                err();
            } else if (isKind(pos, TokKind.LITERAL)) {
                bump();
                parseRangePatternTail();
            } else if (punct('-') && isKind(pos + 1, TokKind.LITERAL)) {
                bumpN(2);
                parseRangePatternTail();
            } else if (punct('&')) {
                bump();
                eatKw("mut");
                parsePatternNoAlt();
            } else if (punct('(') || punct('[')) {
                parsePatternList();
            } else if (op("..")) {
                if (op("..=") || op("...")) {
                    bumpN(3);
                    parseRangeBound();
                } else {
                    bumpN(2);
                    if (canStartRangeBound()) {
                        parseRangeBound();
                    }
                }
            } else if (kw("_")) {
                bump();
            } else if (kw("ref") || kw("mut")) {
                bump();
                eatKw("mut");
                parsePatternNoAlt();
            } else if (kw("const") && punctAt(pos + 1, '{')) {
                bump();
                parseBlock();
            } else if (kw("true") || kw("false")) {
                bump();
            } else if (isIdent(pos) || op("::") || punct('<')) {
                PathInfo p = parsePath(PathCtx.PATTERN);
                if (!p.tailArgs && punct('!') && !op("!=") && isOpener(pos + 1)) {
                    emitPath(p, NodeKind.IDENT);
                    bump();
                    tokenTree(false);
                } else if (punct('(')) {
                    emitPath(p, p.tailArgs ? NodeKind.TYPE_IDENT : NodeKind.IDENT);
                    parsePatternList();
                } else if (punct('{')) {
                    emitPath(p, NodeKind.TYPE_IDENT);
                    parseStructPatternFields();
                } else if (punct('@') && p.single()) {
                    emitPath(p, NodeKind.IDENT);
                    bump();
                    parsePatternNoAlt();
                } else {
                    emitPath(p, NodeKind.IDENT);
                    parseRangePatternTail();
                }
            } else {
                err();
            }
            leave();
        }

        /** {@code ( p, … )} or {@code [ p, … ]}. */
        void parsePatternList() {
            int saved = openGroup();
            if (saved < 0) {
                bump();
                return;
            }
            while (!atEnd()) {
                int before = pos;
                parsePattern();
                if (pos == before || !eatPunct(',')) {
                    break;
                }
            }
            closeGroup(saved);
        }

        /** {@code { a: p, ref mut b, 0: c, .. }}; field names are not references. */
        void parseStructPatternFields() {
            int saved = openGroup();
            if (saved < 0) {
                bump();
                return;
            }
            while (!atEnd()) {
                int before = pos;
                parseOuterAttrs();
                if (op("..")) {
                    bumpN(2);
                } else {
                    eatKw("ref");
                    eatKw("mut");
                    if ((isIdent(pos) || isKind(pos, TokKind.LITERAL)) && colonAt(pos + 1)) {
                        bumpN(2);
                        parsePattern();
                    } else if (isIdent(pos)) {
                        bump();
                    } else {
                        err();
                    }
                }
                if (pos == before || !eatPunct(',')) {
                    break;
                }
            }
            closeGroup(saved);
        }

        /** After a literal or path in a pattern: an optional range {@code ..= end}, {@code .. end} or {@code ..}. */
        void parseRangePatternTail() {
            if (op("..=") || op("...")) {
                bumpN(3);
                parseRangeBound();
            } else if (op("..")) {
                bumpN(2);
                if (canStartRangeBound()) {
                    parseRangeBound();
                }
            }
        }

        boolean canStartRangeBound() {
            return isKind(pos, TokKind.LITERAL)
                    || (punct('-') && isKind(pos + 1, TokKind.LITERAL))
                    || (isIdent(pos) && !kw("if"))
                    || op("::")
                    || punct('<');
        }

        void parseRangeBound() {
            if (isKind(pos, TokKind.LITERAL)) {
                bump();
            } else if (punct('-') && isKind(pos + 1, TokKind.LITERAL)) {
                bumpN(2);
            } else if (isIdent(pos) || op("::") || punct('<')) {
                PathInfo p = parsePath(PathCtx.PATTERN);
                emitPath(p, NodeKind.IDENT);
            } else {
                err();
            }
        }

        // ---------------------------------------------------------- token trees

        /**
         * A macro's token tree (at its opening delimiter): every identifier and lifetime inside is an {@code IDENT}
         * leaf (macro arguments are not parsed). In a {@code macro_rules!} definition
         * ({@code rules}), {@code $name} is a metavariable, not an identifier.
         */
        void tokenTree(boolean rules) {
            int p = pos < toks.length ? toks[pos].pair : NONE;
            if (p == NONE || p >= limit) {
                err();
                bump();
                return;
            }
            for (int i = pos + 1; i < p; i++) {
                switch (toks[i].kind) {
                    case IDENT -> {
                        boolean meta = rules && punctAt(i - 1, '$') && toks[i - 1].end == toks[i].start;
                        if (!meta) {
                            leaf(NodeKind.IDENT, i);
                        }
                    }
                    case LIFETIME -> leaf(NodeKind.IDENT, i);
                    default -> {
                    }
                }
            }
            pos = p + 1;
        }

        // ---------------------------------------------------------- statements and blocks

        /** {@code { statements }} at the current {@code {}. */
        void parseBlock() {
            if (!punct('{')) {
                err();
                return;
            }
            int saved = openGroup();
            if (saved < 0) {
                bump();
                return;
            }
            if (enter()) {
                while (!atEnd()) {
                    int before = pos;
                    parseStmt();
                    if (pos == before) {
                        err();
                        skipTree();
                    }
                }
                leave();
            }
            closeGroup(saved);
        }

        void parseStmt() {
            if (eatPunct(';')) {
                return;
            }
            if (atAttr()) {
                parseAttr();
                return;
            }
            long item = itemStart(pos);
            if (item >= 0) {
                parseItem(itemKw(item), itemAt(item));
                return;
            }
            parseStmtRest();
        }

        /** A {@code let} or expression statement (or {@code ;}). */
        void parseStmtRest() {
            if (eatPunct(';')) {
                return;
            }
            if (kw("let")) {
                bump();
                parsePattern();
                if (colonAt(pos)) {
                    bump();
                    parseType(true);
                }
                if (eatPunct('=')) {
                    parseExpr(false);
                    if (eatKw("else")) {
                        parseBlock();
                    }
                }
                expectPunct(';');
                return;
            }
            int info = parseExprStmt(false);
            if (!eatPunct(';') && !atEnd() && (info & (BLOCK_LIKE | MACRO_CALL)) == 0) {
                err();
            }
        }

        /**
         * An expression in statement position (also used for match arm bodies): a block-like expression ({@code if},
         * {@code match}, a loop, a block, a brace macro) ends there unless followed by {@code .} or {@code ?}.
         */
        int parseExprStmt(boolean ns) {
            int info = parsePrefix(ns, false);
            if ((info & BLOCK_LIKE) != 0 && !(punct('.') && !op("..")) && !punct('?')) {
                return info;
            }
            return parseInfix(info, 0, ns, false);
        }

        // ---------------------------------------------------------- expressions

        /** A full expression. {@code ns}: no struct literals (in {@code if}/{@code while}/{@code match}/{@code for} headers). */
        int parseExpr(boolean ns) {
            return parseExprBp(0, ns, false);
        }

        /**
         * Pratt parser entry: an expression whose operators all bind at least as tightly as {@code minBp}.
         * {@code cond}: in an {@code if}/{@code while} condition or match guard, where {@code let} is allowed.
         */
        int parseExprBp(int minBp, boolean ns, boolean cond) {
            int lhs = parsePrefix(ns, cond);
            return parseInfix(lhs, minBp, ns, cond);
        }

        /** Can token {@code i} start an expression? Decides whether {@code return}, {@code break}, {@code ..} have an operand. */
        boolean canStartExpr(int i, boolean ns) {
            if (i >= limit) {
                return false;
            }
            return switch (toks[i].kind) {
                case LITERAL, LIFETIME -> true;
                case IDENT -> !(textIs(i, "as") || textIs(i, "else") || textIs(i, "in") || textIs(i, "where"));
                case PUNCT -> switch (code[toks[i].start]) {
                    case '(', '[', '!', '-', '*', '&', '|', '<', '#' -> true;
                    case '{' -> !ns;
                    case ':' -> opAt(i, "::");
                    case '.' -> opAt(i, "..");
                    default -> false;
                };
            };
        }

        /**
         * The binary operator at the current position, or null. Binding powers: assignment 1 (right-associative),
         * range 2, {@code ||} 3, {@code &&} 4, comparison 5, {@code |} 6, {@code ^} 7, {@code &} 8, shift 9,
         * additive 10, multiplicative 11 ({@code as} 12, unary 13, postfix 14).
         */
        BinOp binop() {
            if (!isKind(pos, TokKind.PUNCT)) {
                return null;
            }
            byte c = code[toks[pos].start];
            switch (c) {
                case '=':
                    if (op("==")) {
                        return CMP2;
                    } else if (op("=>")) {
                        return null;
                    }
                    return ASSIGN1;
                case '!':
                    return op("!=") ? CMP2 : null;
                case '<':
                case '>': {
                    String s = c == '<' ? "<<=" : ">>=";
                    if (op(s)) {
                        return ASSIGN3;
                    } else if (op(s.substring(0, 2))) {
                        return SHIFT;
                    } else if (op(c == '<' ? "<=" : ">=")) {
                        return CMP2;
                    }
                    return CMP1;
                }
                case '&':
                    if (op("&&")) {
                        return AND_AND;
                    } else if (op("&=")) {
                        return ASSIGN2;
                    }
                    return BIT_AND;
                case '|':
                    if (op("||")) {
                        return OR_OR;
                    } else if (op("|=")) {
                        return ASSIGN2;
                    }
                    return BIT_OR;
                case '^':
                case '+':
                case '-':
                case '*':
                case '/':
                case '%':
                    if (punctAt(pos + 1, '=') && toks[pos].joint) {
                        return ASSIGN2;
                    } else if (c == '-' && op("->")) {
                        return null;
                    }
                    return c == '^' ? BIT_XOR : (c == '+' || c == '-') ? ADD : MUL;
                case '.':
                    if (op("..=") || op("...")) {
                        return RANGE3;
                    } else if (op("..")) {
                        return RANGE2;
                    }
                    return null;
                default:
                    return null;
            }
        }

        /** Postfix and binary operators after an operand (see {@link #binop}). */
        int parseInfix(int info, int minBp, boolean ns, boolean cond) {
            while (true) {
                if (atEnd()) {
                    break;
                }
                if (punct('?')) {
                    bump();
                    info = PLAIN;
                    continue;
                }
                if (punct('.') && !op("..")) {
                    bump();
                    if (kw("await")) {
                        bump();
                    } else if (isIdent(pos)) {
                        bump(); // field or method name: not a reference
                        if (op("::") && punctAt(pos + 2, '<')) {
                            bumpN(2);
                            parseGenericArgs();
                        }
                    } else if (isKind(pos, TokKind.LITERAL)) {
                        bump();
                    } else {
                        err();
                    }
                    info = PLAIN;
                    continue;
                }
                if (punct('(') || punct('[')) {
                    parseExprList();
                    info = PLAIN;
                    continue;
                }
                if (kw("as")) {
                    if (12 < minBp) {
                        break;
                    }
                    bump();
                    parseType(false);
                    info = PLAIN;
                    continue;
                }
                BinOp b = binop();
                if (b == null || b.lbp() < minBp) {
                    break;
                }
                int opTok = pos;
                bumpN(b.len());
                if (b.range()) {
                    if (canStartExpr(pos, ns)) {
                        parseExprBp(b.rbp(), ns, false);
                    }
                    info = PLAIN;
                    continue;
                }
                int rhs = parseExprBp(b.rbp(), ns, cond);
                boolean and = b.logic() && code[toks[opTok].start] == '&';
                if (and && ((info | rhs) & IS_LET) != 0) {
                    info = IS_LET;
                } else {
                    if (b.logic()) {
                        leaf(NodeKind.AND_OR, opTok);
                    }
                    info = PLAIN;
                }
            }
            return info;
        }

        /**
         * {@code ( … )} call arguments / tuple / parenthesised expression, or {@code [ … ]} index / array
         * ({@code [x; n]}): comma separated expressions with optional attributes.
         */
        void parseExprList() {
            int saved = openGroup();
            if (saved < 0) {
                bump();
                return;
            }
            while (!atEnd()) {
                int before = pos;
                parseOuterAttrs();
                if (atEnd()) {
                    break;
                }
                parseExpr(false);
                if (eatPunct(';')) {
                    parseExpr(false);
                }
                if (pos == before || !eatPunct(',')) {
                    break;
                }
            }
            closeGroup(saved);
        }

        /**
         * An operand: literals, paths, struct literals, macro calls, groups, blocks, control flow, closures, unary and
         * prefix-range operators. Unary operators bind tighter than everything but postfix operators.
         */
        int parsePrefix(boolean ns, boolean cond) {
            if (!enter()) {
                return PLAIN;
            }
            int info = parsePrefixInner(ns, cond);
            leave();
            return info;
        }

        /**
         * The operand itself. A label ({@code 'a:}) or outer attributes before it are consumed in a loop rather than
         * by recursion (as the Rust edition does), so that a long run of attributes cannot exhaust the stack; the
         * result is the same.
         */
        int parsePrefixInner(boolean ns, boolean cond) {
            while (true) {
                if (atEnd()) {
                    err();
                    return PLAIN;
                }
                Token tok = toks[pos];
                switch (tok.kind) {
                    case LITERAL:
                        bump();
                        return PLAIN;
                    case LIFETIME:
                        // label: `'a: loop { … }`
                        if (colonAt(pos + 1)) {
                            leaf(NodeKind.IDENT, pos);
                            bumpN(2);
                            if (kw("loop") || kw("while") || kw("for") || punct('{')) {
                                continue;
                            }
                        }
                        err();
                        bump();
                        return PLAIN;
                    case PUNCT:
                        if (code[tok.start] == '#' && atAttr()) {
                            parseAttr();
                            continue;
                        }
                        return parsePunctPrefix(code[tok.start], ns);
                    default:
                        return parseIdentPrefix(ns, cond);
                }
            }
        }

        /** {@link #parsePrefixInner} at a punctuation token {@code c} (other than the start of an attribute). */
        private int parsePunctPrefix(byte c, boolean ns) {
            switch (c) {
                case '(':
                case '[':
                    parseExprList();
                    return PLAIN;
                case '{':
                    parseBlock();
                    return BLOCK_LIKE;
                case '|':
                    return parseClosure(ns);
                case '-':
                case '!':
                case '*':
                    bump();
                    parseExprBp(13, ns, false);
                    return PLAIN;
                case '&':
                    bump();
                    if (kw("raw") && (kwAt(pos + 1, "const") || kwAt(pos + 1, "mut"))) {
                        bumpN(2);
                    } else {
                        eatKw("mut");
                    }
                    parseExprBp(13, ns, false);
                    return PLAIN;
                default:
                    break;
            }
            if (c == '.' && op("..")) {
                int len = op("..=") || op("...") ? 3 : 2;
                bumpN(len);
                if (canStartExpr(pos, ns)) {
                    parseExprBp(3, ns, false);
                }
                return PLAIN;
            }
            if (c == '<' || (c == ':' && op("::"))) {
                return parsePathExpr(ns);
            }
            err();
            return PLAIN;
        }

        /** {@link #parsePrefixInner} at an identifier or keyword. */
        private int parseIdentPrefix(boolean ns, boolean cond) {
            String t = text(pos);
            switch (t) {
                case "if":
                    parseIf();
                    return BLOCK_LIKE;
                case "match":
                    parseMatch();
                    return BLOCK_LIKE;
                case "while":
                    leaf(NodeKind.WHILE, pos);
                    bump();
                    parseExprBp(0, true, true);
                    parseBlock();
                    return BLOCK_LIKE;
                case "loop":
                    leaf(NodeKind.LOOP, pos);
                    bump();
                    parseBlock();
                    return BLOCK_LIKE;
                case "for":
                    leaf(NodeKind.FOR, pos);
                    bump();
                    parsePattern();
                    if (!eatKw("in")) {
                        err();
                    }
                    parseExprBp(0, true, false);
                    parseBlock();
                    return BLOCK_LIKE;
                default:
                    break;
            }
            if ((t.equals("unsafe") || t.equals("const") || t.equals("try")) && punctAt(pos + 1, '{')) {
                bump();
                parseBlock();
                return BLOCK_LIKE;
            }
            if ((t.equals("async") || t.equals("gen"))
                    && (punctAt(pos + 1, '{') || (kwAt(pos + 1, "move") && punctAt(pos + 2, '{')))) {
                bump();
                eatKw("move");
                parseBlock();
                return BLOCK_LIKE;
            }
            if ((t.equals("async") || t.equals("move") || t.equals("static"))
                    && (punctAt(pos + 1, '|') || kwAt(pos + 1, "move"))) {
                return parseClosure(ns);
            }
            switch (t) {
                case "return":
                case "yield":
                case "become":
                    bump();
                    if (canStartExpr(pos, ns)) {
                        parseExprBp(1, ns, false);
                    }
                    return PLAIN;
                case "break":
                    bump();
                    if (isKind(pos, TokKind.LIFETIME)) {
                        leaf(NodeKind.IDENT, pos);
                        bump();
                    }
                    if (canStartExpr(pos, ns)) {
                        parseExprBp(1, ns, false);
                    }
                    return PLAIN;
                case "continue":
                    bump();
                    if (isKind(pos, TokKind.LIFETIME)) {
                        leaf(NodeKind.IDENT, pos);
                        bump();
                    }
                    return PLAIN;
                case "let":
                    // `let` condition (only valid in `if`/`while` conditions and match guards)
                    if (!cond) {
                        err();
                    }
                    bump();
                    parsePattern();
                    expectPunct('=');
                    parseExprBp(5, true, false);
                    return IS_LET;
                case "true":
                case "false":
                    bump();
                    return PLAIN;
                default:
                    return parsePathExpr(ns);
            }
        }

        /** A path in expression position, possibly a macro call or struct literal. */
        int parsePathExpr(boolean ns) {
            PathInfo p = parsePath(PathCtx.EXPR);
            if (!p.tailArgs && punct('!') && !op("!=") && isOpener(pos + 1)) {
                emitPath(p, NodeKind.IDENT);
                bump();
                boolean brace = punct('{');
                tokenTree(false);
                return (brace ? BLOCK_LIKE : 0) | MACRO_CALL;
            }
            if (punct('{') && !ns) {
                // struct literal: the name is a type_identifier
                emitPath(p, NodeKind.TYPE_IDENT);
                parseStructLiteralFields();
                return PLAIN;
            }
            emitPath(p, NodeKind.IDENT);
            return PLAIN;
        }

        /** {@code { a: x, b, 0: y, ..base }}: field names are not references, shorthand fields are identifiers. */
        void parseStructLiteralFields() {
            int saved = openGroup();
            if (saved < 0) {
                bump();
                return;
            }
            while (!atEnd()) {
                int before = pos;
                parseOuterAttrs();
                if (op("..")) {
                    bumpN(2);
                    if (!atEnd() && !punct(',')) {
                        parseExpr(false);
                    }
                } else if ((isIdent(pos) || isKind(pos, TokKind.LITERAL)) && colonAt(pos + 1)) {
                    bumpN(2);
                    parseExpr(false);
                } else if (isIdent(pos)) {
                    leaf(NodeKind.IDENT, pos);
                    bump();
                } else {
                    err();
                }
                if (pos == before || !eatPunct(',')) {
                    break;
                }
            }
            closeGroup(saved);
        }

        /** {@code if cond { … } else if cond { … } else { … }}, iteratively so that long {@code else if} chains need no recursion. */
        void parseIf() {
            while (true) {
                leaf(NodeKind.IF, pos);
                bump();
                parseExprBp(0, true, true);
                parseBlock();
                if (eatKw("else")) {
                    if (kw("if")) {
                        continue;
                    }
                    parseBlock();
                }
                break;
            }
        }

        /** {@code match x { attrs* pat (if guard)? => body,? … }}. */
        void parseMatch() {
            leaf(NodeKind.MATCH, pos);
            bump();
            parseExprBp(0, true, false);
            if (!punct('{')) {
                err();
                return;
            }
            int saved = openGroup();
            if (saved < 0) {
                bump();
                return;
            }
            while (!atEnd()) {
                int before = pos;
                while (atAttr()) {
                    parseAttr();
                }
                if (atEnd()) {
                    break;
                }
                leaf(NodeKind.ARM, pos);
                parsePattern();
                if (eatKw("if")) {
                    parseExprBp(0, false, true);
                }
                if (op("=>")) {
                    bumpN(2);
                } else {
                    err();
                }
                int body = parseExprStmt(false);
                if (!eatPunct(',') && !atEnd() && (body & (BLOCK_LIKE | MACRO_CALL)) == 0) {
                    err();
                }
                if (pos == before) {
                    err();
                    skipTree();
                }
            }
            closeGroup(saved);
        }

        /** {@code static? async? move? |params| body} or {@code … || -> T { … }}. */
        int parseClosure(boolean ns) {
            eatKw("static");
            eatKw("async");
            eatKw("move");
            if (!eatPunct('|')) {
                err();
                return PLAIN;
            }
            while (!atEnd() && !punct('|')) {
                int before = pos;
                parseOuterAttrs();
                parsePatternNoAlt();
                if (colonAt(pos)) {
                    bump();
                    parseType(false);
                }
                if (pos == before || !eatPunct(',')) {
                    break;
                }
            }
            expectPunct('|');
            if (op("->")) {
                bumpN(2);
                parseType(false);
                parseBlock();
            } else {
                parseExprBp(0, ns, false);
            }
            return PLAIN;
        }
    }
}
