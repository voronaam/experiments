package dev.codecity.analysis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A small, dependency-free Java lexer and error-tolerant structural parser, built for {@link JavaAnalyzer}.
 *
 * <p>It is not a full Java front end. It reads the whole language (Java 21, including records, sealed types, switch
 * expressions and patterns) but keeps only what the analyser needs, in a tiny syntax tree:
 * <ul>
 *   <li>the declarations: package, imports, types (class, interface, enum, record, annotation type) and their members
 *       (fields, enum constants, methods and constructors) with names, token spans and counts;</li>
 *   <li>the <em>references</em>: every type name ({@code List}, {@code Map.Entry}), annotation name and identifier used
 *       as the object of a member access or call ({@code Util} in {@code Util.run()});</li>
 *   <li>the <em>decision points</em> for McCabe complexity ({@code if}, loops, {@code catch}, {@code case},
 *       {@code ?:}, {@code &&}, {@code ||});</li>
 *   <li>whether the file had syntax errors.</li>
 * </ul>
 *
 * <p>The rules for what counts as which follow tree-sitter-java 0.23, which this parser replaced: the analyser's
 * output is meant to be identical. Where the syntax is ambiguous (is {@code (a) - b} a cast?), the comments
 * below say what it does. This is a faithful port of the Rust edition's {@code java_parse.rs} (and the D edition's
 * counterpart): same token kinds, node kinds, speculation points, error recovery and depth cap, so the three editions
 * produce the same city by construction.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>{@link #lex} turns the source into a flat array of tokens (comments and whitespace dropped), each with its
 *       kind, byte range and 1-based line. It also pairs up brackets ({@code pair}), so the parser can jump over a
 *       bracketed group in one step.</li>
 *   <li>{@link #parse} runs a recursive-descent parser over the tokens and produces a {@link Tree}: a flat array of
 *       nodes in pre-order, where each node knows the index just past its subtree ({@code end}). Children of node
 *       {@code i} are found by starting at {@code i + 1} and hopping from each child to its {@code end}.</li>
 * </ol>
 *
 * <h2>Grammar subset</h2>
 * The parser follows the Java grammar closely enough to tell types from expressions, which is what decides whether a
 * name is a type reference. Outline ({@code []} optional, <code>{}</code> repeated):
 * <pre>
 * file        = { package | import | module | typeDecl | ";" | statement | method }
 * typeDecl    = modifiers ("class" | "interface" | "enum" | "record" | "@interface") Ident header body
 * member      = ";" | block | "static" block | modifiers (typeDecl | constructor | method | field | compactCtor)
 * statement   = block | if | for | while | do | try | switch | return | throw | break | continue | yield | assert
 *             | synchronized | label ":" statement | localTypeDecl | localVariable ";" | expression ";"
 * expression  = operand { binaryOp operand | "instanceof" pattern | "?" expression ":" } ;  precedence is ignored
 * operand     = { prefixOp | cast } (lambda | primary { postfix })
 * type        = annotations (primitive | Ident [typeArgs] { "." annotations Ident [typeArgs] }) { annotations "[]" }
 * </pre>
 *
 * <p>Precedence is deliberately ignored: none of the analyser's metrics depend on how operators group, only on which
 * tokens are types, member-access objects and decision points.
 *
 * <p>Ambiguities are resolved by bounded look-ahead or by <em>speculation</em>: the parser saves its state (token
 * position, node count, error flag), tries one reading (say, "a type followed by a name" for a local variable), and
 * restores the state if that fails. Nodes pushed during a failed attempt are dropped by truncating the node array.
 *
 * <h2>Error recovery</h2>
 * The parser never stops and never throws. When a token does not fit, it sets the error flag and carries on:
 * <ul>
 *   <li>{@code expect} of a missing token flags the error without consuming anything;</li>
 *   <li>every loop over list items (members, statements, arguments, …) checks that an iteration consumed at least one
 *       token; if not, it flags the error and skips one token, or a whole bracketed group if the token opens one
 *       (using the bracket pairs from the lexer), so it always makes progress;</li>
 *   <li>recursion is capped at {@link #MAX_DEPTH} nested constructs; deeper input is skipped group by group and
 *       flagged, and from then on the parser no longer speculates (see {@code Parser.depthCapped}).</li>
 * </ul>
 * So a file with errors still yields every declaration the parser could recognise, which is what the analyser reports
 * (with the file counted as failed).
 *
 * <p>Everything works on byte offsets into the UTF-8 encoding of the source (as the Rust and D editions do, so that
 * identifier and literal boundaries agree); line numbers count {@code \n} only, as {@link SourceText#lines} does.
 */
public final class JavaParse {

    /**
     * Maximum nesting of statements, expressions, types and bodies before the parser gives up on the nested part (and
     * flags an error). Real code stays far below; the cap keeps adversarial input from overflowing the stack (the
     * parser recurses once per nesting level, with a few stack frames each, well within a default 512 KiB–1 MiB
     * thread stack).
     */
    public static final int MAX_DEPTH = 300;

    /** "No token" / "no pair" / "no node" marker in index fields. */
    public static final int NONE = -1;

    private JavaParse() {
    }

    // ------------------------------------------------------------------ tokens

    /** What a token is. Punctuation and operators are all {@link #OP}; the parser compares their text. */
    public enum Tok {
        /**
         * A name. Includes the contextual keywords ({@code record}, {@code var}, {@code yield}, {@code sealed},
         * {@code permits}, {@code when}, {@code module}, …), which are names everywhere except where the parser looks
         * for them, and {@code goto}/{@code const}, which are not reserved either.
         */
        IDENT,
        /** A reserved word (see {@link #KEYWORDS}), including the literals {@code true}, {@code false}, {@code null}. */
        KEYWORD,
        /** A numeric literal ({@code 12}, {@code 0x1F}, {@code 1.5e-3f}, {@code 1_000L}). */
        NUMBER,
        /** A character literal ({@code 'a'}, {@code '\n'}). */
        CHAR,
        /** A string literal ({@code "…"}) or text block ({@code """…"""}). */
        STR,
        /** An operator or punctuation (see {@link #OPERATORS}). */
        OP,
        /**
         * Anything the lexer could not read: a stray character ({@code #}, {@code \}), an unterminated literal or
         * comment. Its presence makes the file count as having errors.
         */
        ERROR,
        /** End of input. The token array always ends with exactly one, so look-ahead never runs off the end. */
        EOF
    }

    /**
     * The reserved words of Java that are lexed as keywords. {@code goto} and {@code const} are missing
     * on purpose (they are ordinary identifiers), as are all contextual keywords.
     */
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "continue", "default",
            "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null");

    /** Length of the longest keyword ({@code synchronized}, {@code implements}…): longer identifiers are never one. */
    private static final int MAX_KEYWORD_LENGTH = 12;

    /**
     * Operators and punctuation, longest first so the lexer can take the first match. {@code >} is always a token of
     * its own (except in {@code >=} and {@code ->}), so that the {@code >>} closing two type-argument lists is two
     * tokens; in expressions the parser glues adjacent {@code >}s back into shift operators.
     */
    private static final String[] OPERATORS = {
            "<<=", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "<<", "(", ")", "{", "}", "[", "]", ";", ",", ".", "@", "=", ">", "<", "!", "~", "?",
            ":", "+", "-", "*", "/", "&", "|", "^", "%",
    };

    /** {@link #OPERATORS} as bytes, for matching against the UTF-8 source. */
    private static final byte[][] OPERATOR_BYTES = Arrays.stream(OPERATORS)
            .map(s -> s.getBytes(StandardCharsets.US_ASCII)).toArray(byte[][]::new);

    /**
     * The result of {@link #lex}: the tokens as parallel arrays (kind, byte range {@code [start, end)} in the UTF-8
     * source, 1-based line it starts on), the bracket pairs and the error flag.
     */
    public static final class Lexed {
        public final Tok[] kind;
        public final int[] start;
        public final int[] end;
        public final int[] line;
        /** Number of tokens, the final {@link Tok#EOF} included. */
        public final int count;
        /**
         * For each token that opens a bracket ({@code (}, {@code [}, <code>{</code>), the index of the token that
         * closes it; {@link #NONE} for all other tokens and for unclosed brackets.
         */
        public final int[] pair;
        /** True if the lexer met something it could not read (an {@link Tok#ERROR} token, or mismatched brackets). */
        public final boolean error;

        Lexed(Tok[] kind, int[] start, int[] end, int[] line, int count, int[] pair, boolean error) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.line = line;
            this.count = count;
            this.pair = pair;
            this.error = error;
        }
    }

    /** Splits Java source into tokens, dropping whitespace and comments. See {@link #lex(byte[])}. */
    public static Lexed lex(String code) {
        return lex(code.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Splits UTF-8 encoded Java source into tokens, dropping whitespace and comments.
     *
     * <p>Whitespace is ASCII space, tab, form feed, vertical tab, {@code \r} and {@code \n} (other Unicode spaces are
     * errors). A byte-order mark at the very start is skipped. Identifiers are ASCII letters,
     * digits, {@code _} and {@code $}, plus any non-ASCII letter or digit (Unicode Alphabetic or Numeric, like Rust's
     * {@code char::is_alphanumeric}). Unicode escapes (a backslash, {@code u} and four hex digits) are not decoded.
     */
    public static Lexed lex(byte[] b) {
        int n = b.length;
        int cap = n / 4 + 16;
        Tok[] kinds = new Tok[cap];
        int[] starts = new int[cap];
        int[] ends = new int[cap];
        int[] lines = new int[cap];
        int count = 0;
        boolean error = false;
        int line = 1;
        int i = 0;
        // A byte-order mark (U+FEFF, EF BB BF in UTF-8) at the start of the file is not part of the code.
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < n) {
            int c = b[i] & 0xFF;
            // whitespace
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == 0x0B || c == 0x0C) {
                i++;
                continue;
            }
            int start = i;
            int startLine = line;
            // comments
            if (c == '/' && i + 1 < n && b[i + 1] == '/') {
                while (i < n && b[i] != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && b[i + 1] == '*') {
                i += 2;
                while (true) {
                    if (i >= n) {
                        error = true; // unterminated block comment
                        break;
                    }
                    if (b[i] == '*' && i + 1 < n && b[i + 1] == '/') {
                        i += 2;
                        break;
                    }
                    if (b[i] == '\n') {
                        line++;
                    }
                    i++;
                }
                continue;
            }
            Tok kind;
            int op;
            if (isIdentStart(b, i)) {
                i = skipIdent(b, i);
                kind = isKeyword(b, start, i) ? Tok.KEYWORD : Tok.IDENT;
            } else if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(b[i + 1]))) {
                i = skipNumber(b, i);
                kind = Tok.NUMBER;
            } else if (c == '"' && i + 2 < n && b[i + 1] == '"' && b[i + 2] == '"') {
                // Text block: runs to the next unescaped `"""`, across lines.
                i += 3;
                while (true) {
                    if (i >= n) {
                        kind = Tok.ERROR;
                        break;
                    }
                    if (b[i] == '\\') {
                        if (i + 1 < n && b[i + 1] == '\n') {
                            line++;
                        }
                        i += 2;
                        continue;
                    }
                    if (b[i] == '"' && i + 2 < n && b[i + 1] == '"' && b[i + 2] == '"') {
                        i += 3;
                        kind = Tok.STR;
                        break;
                    }
                    if (b[i] == '\n') {
                        line++;
                    }
                    i++;
                }
                i = Math.min(i, n);
            } else if (c == '"' || c == '\'') {
                // String or character literal: ends at the matching quote; a line break or the end of the file first
                // makes it an unterminated (error) token that stops before the line break.
                i++;
                while (true) {
                    if (i >= n || b[i] == '\n') {
                        kind = Tok.ERROR;
                        break;
                    }
                    if (b[i] == '\\') {
                        // `\` + line break is accepted inside character literals; keep it simple
                        // and let the escape swallow any following byte except a line break.
                        if (i + 1 < n && b[i + 1] != '\n') {
                            i += 2;
                        } else {
                            i++;
                        }
                        continue;
                    }
                    if ((b[i] & 0xFF) == c) {
                        i++;
                        kind = c == '"' ? Tok.STR : Tok.CHAR;
                        break;
                    }
                    i++;
                }
                // Move on to a character boundary: an escape may have stepped into the middle of a multi-byte char.
                while (i < n && isContinuation(b[i])) {
                    i++;
                }
            } else if ((op = matchOperator(b, i)) >= 0) {
                i += OPERATOR_BYTES[op].length;
                kind = Tok.OP;
            } else {
                // A stray character: one whole UTF-8 character becomes an error token.
                i++;
                while (i < n && isContinuation(b[i])) {
                    i++;
                }
                kind = Tok.ERROR;
            }
            if (kind == Tok.ERROR) {
                error = true;
            }
            if (count + 1 >= kinds.length) {
                int grown = kinds.length * 2;
                kinds = Arrays.copyOf(kinds, grown);
                starts = Arrays.copyOf(starts, grown);
                ends = Arrays.copyOf(ends, grown);
                lines = Arrays.copyOf(lines, grown);
            }
            kinds[count] = kind;
            starts[count] = start;
            ends[count] = i;
            lines[count] = startLine;
            count++;
        }
        // There is always room for the Eof token: the array grows while one slot is still free.
        kinds[count] = Tok.EOF;
        starts[count] = n;
        ends[count] = n;
        lines[count] = line;
        count++;

        // Pair brackets with a stack. A closer that does not match the innermost opener is left unpaired (and is an
        // error); so is an opener that is never closed.
        int[] pair = new int[count];
        Arrays.fill(pair, NONE);
        int[] stack = new int[16];
        int depth = 0;
        for (int k = 0; k < count; k++) {
            if (kinds[k] != Tok.OP || ends[k] - starts[k] != 1) {
                continue;
            }
            byte ch = b[starts[k]];
            switch (ch) {
                case '(', '[', '{' -> {
                    if (depth == stack.length) {
                        stack = Arrays.copyOf(stack, depth * 2);
                    }
                    stack[depth++] = k;
                }
                case ')', ']', '}' -> {
                    byte open = ch == ')' ? (byte) '(' : ch == ']' ? (byte) '[' : (byte) '{';
                    if (depth > 0 && b[starts[stack[depth - 1]]] == open) {
                        pair[stack[depth - 1]] = k;
                        depth--;
                    } else {
                        error = true;
                    }
                }
                default -> {
                }
            }
        }
        if (depth != 0) {
            error = true;
        }
        return new Lexed(kinds, starts, ends, lines, count, pair, error);
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiAlpha(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** True for a UTF-8 continuation byte ({@code 10xxxxxx}): not the start of a character. */
    private static boolean isContinuation(byte c) {
        return (c & 0xC0) == 0x80;
    }

    /** The code point of the (valid) UTF-8 sequence starting at {@code i}. */
    private static int codePointAt(byte[] b, int i) {
        int c = b[i] & 0xFF;
        if (c < 0x80) {
            return c;
        }
        int len = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : 2;
        int cp = c & (0x3F >> (len - 1));
        for (int k = 1; k < len && i + k < b.length; k++) {
            cp = (cp << 6) | (b[i + k] & 0x3F);
        }
        return cp;
    }

    /** Unicode Alphabetic or Numeric (general category Nd, Nl or No), i.e. Rust's {@code char::is_alphanumeric}. */
    private static boolean isAlphanumeric(int cp) {
        if (Character.isAlphabetic(cp)) {
            return true;
        }
        int t = Character.getType(cp);
        return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    /** True if an identifier starts at byte {@code i}: an ASCII letter, {@code _}, {@code $}, or a non-ASCII letter. */
    private static boolean isIdentStart(byte[] b, int i) {
        int c = b[i] & 0xFF;
        if (c < 0x80) {
            return isAsciiAlpha(c) || c == '_' || c == '$';
        }
        return Character.isAlphabetic(codePointAt(b, i));
    }

    /** The end of the identifier starting at {@code i}: letters, digits, {@code _}, {@code $}, non-ASCII alphanumerics. */
    private static int skipIdent(byte[] b, int i) {
        while (i < b.length) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                if (isAsciiAlpha(c) || isDigit(c) || c == '_' || c == '$') {
                    i++;
                    continue;
                }
                break;
            }
            int cp = codePointAt(b, i);
            if (!isAlphanumeric(cp)) {
                break;
            }
            i += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        }
        return i;
    }

    /** True if bytes {@code [start, end)} spell one of the {@link #KEYWORDS}. */
    private static boolean isKeyword(byte[] b, int start, int end) {
        int len = end - start;
        if (len < 2 || len > MAX_KEYWORD_LENGTH) {
            return false;
        }
        for (int k = start; k < end; k++) {
            if (b[k] < 'a' || b[k] > 'z') {
                return false; // keywords are lower-case ASCII
            }
        }
        return KEYWORDS.contains(new String(b, start, len, StandardCharsets.ISO_8859_1));
    }

    /**
     * The end of the numeric literal starting at {@code i}. Loose on purpose (validity does not matter here): letters,
     * digits and {@code _} continue it, as does one {@code .} (not followed by another {@code .}) and a sign right
     * after an exponent marker ({@code e}/{@code E} in decimal literals, {@code p}/{@code P} in hexadecimal ones).
     */
    private static int skipNumber(byte[] b, int i) {
        boolean hex = b[i] == '0' && i + 1 < b.length && (b[i + 1] == 'x' || b[i + 1] == 'X');
        boolean dot = false;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            if (isAsciiAlpha(c) || isDigit(c) || c == '_') {
                i++;
            } else if (c == '.' && !dot && !(i + 1 < b.length && b[i + 1] == '.')) {
                dot = true;
                i++;
            } else if ((c == '+' || c == '-') && i > 0) {
                byte p = b[i - 1];
                boolean exponent = hex ? (p == 'p' || p == 'P') : (p == 'e' || p == 'E');
                if (!exponent) {
                    break;
                }
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** Index into {@link #OPERATORS} of the first operator that starts at byte {@code i}, or -1. */
    private static int matchOperator(byte[] b, int i) {
        outer:
        for (int k = 0; k < OPERATOR_BYTES.length; k++) {
            byte[] op = OPERATOR_BYTES[k];
            if (i + op.length > b.length) {
                continue;
            }
            for (int j = 0; j < op.length; j++) {
                if (b[i + j] != op[j]) {
                    continue outer;
                }
            }
            return k;
        }
        return -1;
    }

    // ------------------------------------------------------------------ tree

    /** What a node stands for. */
    public enum NodeKind {
        /** The root (always node 0). Children: the top-level declarations and statements. */
        COMPILATION_UNIT,
        /** {@code package a.b;} (with any package annotations). Child: the {@link #NAME}. */
        PACKAGE,
        /**
         * {@code import [static] a.b.C [.*];}. {@code flags}: {@link #IMPORT_STATIC}, {@link #IMPORT_ASTERISK}.
         * Child: the {@link #NAME} (without the {@code .*}).
         */
        IMPORT,
        /** A dotted name of a package or import; tokens {@code first..=last}. */
        NAME,
        /** {@code module m { … }} (module-info.java). Its directives are skipped. */
        MODULE,
        /**
         * A class, interface, enum, record or annotation type declared at the top level or as a member of another
         * type. {@code sub}: one of the {@code TYPE_*} constants; {@code flags}: {@link #TYPE_ABSTRACT} if it has the
         * {@code abstract} modifier; {@code name}: the name token; {@code count}: for records, the number of record
         * components ({@code int... rest} excluded). Children: references from the header, and the members of its
         * body. Types declared inside code (local and anonymous classes) get no node; their contents belong to the
         * enclosing node.
         */
        TYPE_DECL,
        /** A field (or interface constant) declaration. {@code count}: number of declared variables. */
        FIELD,
        /** An enum constant, including its arguments and class body. */
        ENUM_CONSTANT,
        /**
         * A method or constructor of a type. {@code sub}: {@link #METHOD_CONSTRUCTOR} for constructors;
         * {@code name}: the name token; {@code count}: the number of parameters (a varargs parameter counts, a
         * receiver parameter {@code Foo this} does not).
         */
        METHOD,
        /**
         * Any other member or top-level item: initializer blocks, compact record constructors, annotation type
         * elements, top-level statements and methods, and unparsable member debris. Holds the references and decision
         * points inside it.
         */
        OTHER,
        /** A simple type name ({@code String}, {@code T}, {@code var}): token {@code first}. */
        TYPE_NAME,
        /**
         * A qualified type name ({@code Map.Entry}, {@code java.util.List}, {@code Outer<String>.Inner}): tokens
         * {@code first..=last}, which may include type arguments and annotations of the leading parts. Type names
         * inside those leading parts are <em>not</em> reported separately (the analyser does not
         * descend into a qualified name).
         */
        SCOPED_TYPE_NAME,
        /** The name of an annotation ({@code Override}, {@code javax.annotation.Nullable}): tokens {@code first..=last}. */
        ANNOTATION_NAME,
        /**
         * An identifier used as the object of a field access or method call ({@code Util} in {@code Util.run()} or
         * {@code Util.VALUE}, also {@code Outer.super.m()}): token {@code first}. Not reported for {@code Outer.this}
         * or {@code outer.new Inner()}.
         */
        MEMBER_OBJECT,
        /**
         * A decision point for McCabe complexity at token {@code first}: {@code if}, {@code for}, {@code while},
         * {@code do} (its trailing {@code while} is not another one), {@code catch}, a {@code case} label, a ternary
         * {@code ?}, {@code &&} or {@code ||}.
         */
        DECISION
    }

    /** {@link NodeKind#TYPE_DECL} {@code sub} values. */
    public static final int TYPE_CLASS = 0;
    public static final int TYPE_INTERFACE = 1;
    public static final int TYPE_ENUM = 2;
    public static final int TYPE_RECORD = 3;
    public static final int TYPE_ANNOTATION = 4;
    /** {@link NodeKind#TYPE_DECL} flag: declared {@code abstract}. */
    public static final int TYPE_ABSTRACT = 1;
    /** {@link NodeKind#METHOD} {@code sub} value of constructors (0 = method). */
    public static final int METHOD_CONSTRUCTOR = 1;
    /** {@link NodeKind#IMPORT} flags. */
    public static final int IMPORT_STATIC = 1;
    public static final int IMPORT_ASTERISK = 2;

    /**
     * A parsed file: tokens, nodes and the error flag.
     *
     * <p>Nodes are stored as parallel arrays indexed by node number (pre-order). Tokens {@code first..=last} are a
     * node's source span; {@code end} is the index of the first node after its subtree, so the children of {@code i}
     * are {@code i + 1}, then each child's {@code end}, while below {@code end(i)}.
     */
    public static final class Tree {
        private final byte[] code;
        private final Lexed tokens;
        private final NodeKind[] kind;
        private final int[] sub;
        private final int[] flags;
        private final int[] first;
        private final int[] last;
        private final int[] end;
        private final int[] name;
        private final int[] count;
        private final int size;
        private final boolean error;

        Tree(byte[] code, Lexed tokens, Nodes nodes, boolean error) {
            this.code = code;
            this.tokens = tokens;
            this.kind = nodes.kind;
            this.sub = nodes.sub;
            this.flags = nodes.flags;
            this.first = nodes.first;
            this.last = nodes.last;
            this.end = nodes.end;
            this.name = nodes.name;
            this.count = nodes.count;
            this.size = nodes.size;
            this.error = error;
        }

        /** True if the lexer or the parser met anything that is not valid Java. */
        public boolean error() {
            return error;
        }

        /** Number of nodes. */
        public int size() {
            return size;
        }

        /** Number of tokens, the final end-of-input token included. */
        public int tokenCount() {
            return tokens.count;
        }

        public NodeKind kind(int i) {
            return kind[i];
        }

        /** Kind-specific sub-kind (see {@link NodeKind}). */
        public int sub(int i) {
            return sub[i];
        }

        /** Kind-specific flag bits (see {@link NodeKind}). */
        public int flags(int i) {
            return flags[i];
        }

        public int first(int i) {
            return first[i];
        }

        public int last(int i) {
            return last[i];
        }

        /** Index of the first node after node {@code i}'s subtree. */
        public int end(int i) {
            return end[i];
        }

        /** A name token, or {@link #NONE}. */
        public int name(int i) {
            return name[i];
        }

        /** Kind-specific count (see {@link NodeKind}). */
        public int count(int i) {
            return count[i];
        }

        /** Indices of the direct children of node {@code i}. */
        public List<Integer> children(int i) {
            List<Integer> out = new ArrayList<>();
            for (int j = i + 1; j < end[i]; j = end[j]) {
                out.add(j);
            }
            return out;
        }

        /** The source text of tokens {@code first..=last} (including any comments and whitespace between them). */
        public String text(int firstToken, int lastToken) {
            int s = tokens.start[firstToken];
            int e = tokens.end[lastToken];
            return e < s ? "" : new String(code, s, e - s, StandardCharsets.UTF_8);
        }

        /** The source text of node {@code i}'s whole token span. */
        public String text(int i) {
            return text(first[i], last[i]);
        }

        /** The source text of a single token, "" for {@link #NONE}. */
        public String tokenText(int t) {
            return t == NONE ? "" : text(t, t);
        }

        /** 1-based line node {@code i} starts on. */
        public int firstLine(int i) {
            return tokens.line[first[i]];
        }

        /** 1-based line node {@code i} ends on: the line where its last token ends (a text block can span lines). */
        public int lastLine(int i) {
            int t = last[i];
            int line = tokens.line[t];
            for (int k = tokens.start[t]; k < tokens.end[t]; k++) {
                if (code[k] == '\n') {
                    line++;
                }
            }
            return line;
        }
    }

    /** Growable node storage (parallel arrays) used while parsing; {@link Tree} takes the arrays over. */
    static final class Nodes {
        NodeKind[] kind = new NodeKind[64];
        int[] sub = new int[64];
        int[] flags = new int[64];
        int[] first = new int[64];
        int[] last = new int[64];
        int[] end = new int[64];
        int[] name = new int[64];
        int[] count = new int[64];
        int size;

        /** Appends a node and returns its index. */
        int push(NodeKind k, int firstToken, int lastToken, int endNode) {
            if (size == kind.length) {
                int grown = size * 2;
                kind = Arrays.copyOf(kind, grown);
                sub = Arrays.copyOf(sub, grown);
                flags = Arrays.copyOf(flags, grown);
                first = Arrays.copyOf(first, grown);
                last = Arrays.copyOf(last, grown);
                end = Arrays.copyOf(end, grown);
                name = Arrays.copyOf(name, grown);
                count = Arrays.copyOf(count, grown);
            }
            int i = size++;
            kind[i] = k;
            sub[i] = 0;
            flags[i] = 0;
            first[i] = firstToken;
            last[i] = lastToken;
            end[i] = endNode;
            name[i] = NONE;
            count[i] = 0;
            return i;
        }
    }

    // ------------------------------------------------------------------ parser

    /** Parses a Java file. Never throws: problems set {@link Tree#error()}. */
    public static Tree parse(String code) {
        byte[] b = code.getBytes(StandardCharsets.UTF_8);
        Lexed lexed = lex(b);
        Parser p = new Parser(b, lexed);
        p.compilationUnit();
        return new Tree(b, lexed, p.nodes, p.error);
    }

    /** Which kind of body a member is parsed in; decides how {@code Type name()} is read. */
    private enum Body {
        /** A class, interface, enum (after the constants) or record body, or an anonymous class body. */
        CLASS,
        /** An annotation type body: {@code int value() default 1;} is an element, not a method. */
        ANNOTATION
    }

    /** Saved parser state for speculation (see the class documentation). */
    private record Mark(int pos, int nodes, boolean error) {
    }

    /** Modifier keywords accepted by {@link Parser#modifiers}. */
    private static final String[] MODIFIER_KEYWORDS = {
            "public", "protected", "private", "abstract", "static", "final", "strictfp", "default", "synchronized",
            "native", "transient", "volatile"};
    /** Keywords that can follow the contextual modifier {@code sealed} (see {@link Parser#continuesDeclaration}). */
    private static final String[] DECLARATION_KEYWORDS = {
            "public", "protected", "private", "abstract", "static", "final", "strictfp", "class", "interface"};
    /** Primitive types and {@code void} (see {@link Parser#isPrimitiveAt}). */
    private static final String[] PRIMITIVES = {
            "byte", "short", "int", "long", "char", "float", "double", "boolean", "void"};
    /** Keywords that start a local class or local variable declaration in a statement. */
    private static final String[] LOCAL_DECLARATION_KEYWORDS = {
            "class", "interface", "enum", "abstract", "final", "static", "strictfp", "public", "protected", "private",
            "synchronized", "native", "transient", "volatile", "default"};
    /** Keywords that can start an operand after a cast (see {@link Parser#startsOperand}). */
    private static final String[] OPERAND_KEYWORDS = {
            "this", "super", "new", "true", "false", "null", "switch", "byte", "short", "int", "long", "char", "float",
            "double", "boolean", "void"};
    /** Binary and assignment operators (single tokens; {@code >>} and friends arrive as several {@code >}). */
    private static final String[] BINARY_OPERATORS = {
            "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">=", ">", "<", "<=", "==", "!=", "+", "-", "*",
            "/", "%", "&", "|", "^", "<<"};
    /** Prefix operators of an operand. */
    private static final String[] PREFIX_OPERATORS = {"+", "-", "!", "~", "++", "--"};

    /** The recursive-descent parser over one file's tokens. Single-use. */
    private static final class Parser {
        private final byte[] code;
        private final Tok[] kinds;
        private final int[] starts;
        private final int[] ends;
        /** Number of tokens (the last one is {@link Tok#EOF}). */
        private final int len;
        private final int[] pair;
        /** Index of the current token. */
        private int pos;
        private final Nodes nodes = new Nodes();
        private boolean error;
        /** Current nesting of guarded constructs (see {@link #MAX_DEPTH}). */
        private int depth;
        /**
         * Set once the depth cap has been reached anywhere in the file. From then on the parser no longer tries the
         * speculative readings (a local variable, a cast, a type pattern, a record pattern, a type before :: or .class,
         * a method outside a class) and takes the plain one. Input nested past the cap at each of many such points
         * ({@code a < a < a < …}) would otherwise cost up to the cap's worth of steps per token; real code never gets
         * near the cap. The TypeScript parser does the same.
         */
        private boolean depthCapped;
        /**
         * Index of the {@code ->} that ends the {@code case} label being parsed (or {@link #NONE}): {@code case A -> x}
         * must not read {@code A -> x} as a lambda.
         */
        private int labelArrow = NONE;

        Parser(byte[] code, Lexed lexed) {
            this.code = code;
            this.kinds = lexed.kind;
            this.starts = lexed.start;
            this.ends = lexed.end;
            this.len = lexed.count;
            this.pair = lexed.pair;
            this.error = lexed.error;
        }

        // ---------------------------------------------------------------- token helpers

        /** True if token {@code i} (clamped to the {@link Tok#EOF}) is exactly {@code s}. */
        private boolean isAt(int i, String s) {
            int t = Math.min(i, len - 1);
            int start = starts[t];
            int n = s.length();
            if (ends[t] - start != n) {
                return false;
            }
            for (int k = 0; k < n; k++) {
                if (code[start + k] != s.charAt(k)) {
                    return false;
                }
            }
            return true;
        }

        /** True if token {@code i} is one of {@code options}. */
        private boolean isOneOf(int i, String[] options) {
            for (String s : options) {
                if (isAt(i, s)) {
                    return true;
                }
            }
            return false;
        }

        /** True if the current token is {@code s}. */
        private boolean is(String s) {
            return isAt(pos, s);
        }

        /** True if the token {@code k} places ahead is {@code s}. */
        private boolean peekIs(int k, String s) {
            return isAt(pos + k, s);
        }

        private Tok kindAt(int i) {
            return kinds[Math.min(i, len - 1)];
        }

        private Tok kind() {
            return kindAt(pos);
        }

        private boolean atEof() {
            return kind() == Tok.EOF;
        }

        /** Flags a syntax error at the current token. */
        private void fail() {
            error = true;
        }

        /** Moves to the next token (never past {@link Tok#EOF}). */
        private void bump() {
            if (pos + 1 < len) {
                pos++;
            }
        }

        /** Consumes the current token if it is {@code s}. */
        private boolean eat(String s) {
            if (is(s)) {
                bump();
                return true;
            }
            return false;
        }

        /** Consumes {@code s} or flags an error (consuming nothing). */
        private void expect(String s) {
            if (!eat(s)) {
                fail();
            }
        }

        /** Consumes an identifier or flags an error. Returns the token index, or {@link #NONE} if there was none. */
        private int expectIdent() {
            if (kind() == Tok.IDENT) {
                int i = pos;
                bump();
                return i;
            }
            fail();
            return NONE;
        }

        /** True if the tokens at {@code i} and {@code i + 1} touch (no whitespace or comment between): {@code non-sealed}. */
        private boolean adjacent(int i) {
            return i + 1 < len && ends[i] == starts[i + 1];
        }

        /** Error recovery: skips the current token, or the whole bracketed group if it opens one. Flags an error. */
        private void skip() {
            fail();
            int p = pair[pos];
            if (p != NONE) {
                pos = p;
            }
            bump();
        }

        /** If the current token is {@code (}, the index of its {@code )} (else {@link #NONE}), for {@link #resync}. */
        private int closingParen() {
            return is("(") ? pair[pos] : NONE;
        }

        /**
         * Error recovery inside a parenthesized list that hit an unexpected token: flags the error and jumps to the
         * list's closing parenthesis {@code close} if it is known and ahead, or else skips one token if the current
         * item (which started at {@code before}) consumed nothing.
         */
        private void resync(int close, int before) {
            fail();
            if (close != NONE && close > pos) {
                pos = close;
            } else if (pos == before) {
                skip();
            }
        }

        private Mark mark() {
            return new Mark(pos, nodes.size, error);
        }

        /** Undoes everything since {@code m}: position, nodes and error flag. */
        private void reset(Mark m) {
            pos = m.pos();
            nodes.size = Math.min(nodes.size, m.nodes());
            error = m.error();
        }

        /**
         * Enters a nested construct. Past {@link #MAX_DEPTH} it flags an error, skips one token or group (so callers
         * always make progress) and returns false; the caller then skips the construct. Pair with {@link #leave}.
         */
        private boolean enter() {
            if (depth >= MAX_DEPTH) {
                depthCapped = true;
                skip();
                return false;
            }
            depth++;
            return true;
        }

        private void leave() {
            depth--;
        }

        // ---------------------------------------------------------------- node helpers

        /** Starts a node at token {@code first}; its span and subtree end are set by {@link #close}. */
        private int open(NodeKind kind, int first) {
            return nodes.push(kind, first, first, 0);
        }

        /** Ends node {@code i}: its last token is the last one consumed, and its subtree is everything pushed since. */
        private void close(int i) {
            int last = Math.max(pos - 1, 0);
            nodes.last[i] = Math.max(last, nodes.first[i]);
            nodes.end[i] = nodes.size;
        }

        /** Adds a leaf node spanning tokens {@code first..=last}. */
        private void leaf(NodeKind kind, int first, int last) {
            nodes.push(kind, first, last, nodes.size + 1);
        }

        /** Adds a decision point at the current token and consumes it. */
        private void decision() {
            leaf(NodeKind.DECISION, pos, pos);
            bump();
        }

        // ---------------------------------------------------------------- compilation unit

        /**
         * {@code file = { package | import | module | typeDecl | ";" | statement | method }}.
         *
         * <p>Statements and methods are accepted at the top level (they become
         * {@link NodeKind#OTHER}); declarations may come in any order.
         */
        void compilationUnit() {
            int root = open(NodeKind.COMPILATION_UNIT, 0);
            while (!atEof()) {
                int before = pos;
                topLevelItem();
                if (pos == before) {
                    skip();
                }
            }
            close(root);
            nodes.last[root] = pos; // the Eof token
        }

        private void topLevelItem() {
            int start = pos;
            if (eat(";")) {
                return;
            }
            if (is("import")) {
                importDeclaration();
                return;
            }
            // Annotations may start a package declaration (package-info.java), a module or a type declaration, so
            // open a node first and decide its kind after the modifiers.
            int node = open(NodeKind.OTHER, start);
            int flags = modifiers();
            if (is("package")) {
                nodes.kind[node] = NodeKind.PACKAGE;
                bump();
                name();
                expect(";");
            } else if (isModuleStart()) {
                nodes.kind[node] = NodeKind.MODULE;
                module();
            } else if (isTypeDeclStart()) {
                typeDecl(node, flags, true);
            } else if (pos > start || looksLikeMethod()) {
                // A method outside any class, or modifiers followed by a variable declaration: parsed like a member,
                // but the node stays OTHER (it belongs to no type).
                memberRest(node, false, Body.CLASS, false);
            } else {
                statement();
            }
            close(node);
        }

        /** {@code import [static] a.b.C [.*] ;} */
        private void importDeclaration() {
            int node = open(NodeKind.IMPORT, pos);
            bump();
            if (eat("static")) {
                nodes.flags[node] |= IMPORT_STATIC;
            }
            name();
            if (is(".") && peekIs(1, "*")) {
                bump();
                bump();
                nodes.flags[node] |= IMPORT_ASTERISK;
            }
            expect(";");
            close(node);
        }

        /** A dotted name {@code a.b.c} as a {@link NodeKind#NAME} node. Stops before a {@code .} not followed by a name. */
        private void name() {
            if (kind() != Tok.IDENT) {
                fail();
                return;
            }
            int node = open(NodeKind.NAME, pos);
            bump();
            while (is(".") && kindAt(pos + 1) == Tok.IDENT) {
                bump();
                bump();
            }
            close(node);
        }

        /** {@code [open] module} followed by a name. */
        private boolean isModuleStart() {
            int k = is("open") ? 1 : 0;
            return peekIs(k, "module") && kindAt(pos + k + 1) == Tok.IDENT;
        }

        /**
         * {@code [open] module a.b { directives }}: the directives ({@code requires}, {@code exports}, …) only name
         * modules and packages, so the body is skipped as a bracketed group.
         */
        private void module() {
            eat("open");
            bump(); // module
            name();
            if (is("{")) {
                int p = pair[pos];
                if (p == NONE) {
                    skip();
                } else {
                    pos = p;
                    bump();
                }
            } else {
                fail();
            }
        }

        /** At the top level: {@code Type name (} (a method outside any class, which is accepted). */
        private boolean looksLikeMethod() {
            if (depthCapped) {
                return false;
            }
            Mark m = mark();
            boolean ok = type() && kind() == Tok.IDENT && peekIs(1, "(");
            reset(m);
            return ok;
        }

        // ---------------------------------------------------------------- declarations

        /**
         * Modifiers: annotations and modifier keywords, including the contextual {@code sealed} and
         * {@code non-sealed}. Returns {@link #TYPE_ABSTRACT} if {@code abstract} was among them.
         */
        private int modifiers() {
            int flags = 0;
            while (true) {
                if (is("@") && !peekIs(1, "interface")) {
                    annotation();
                } else if (kind() == Tok.KEYWORD && isOneOf(pos, MODIFIER_KEYWORDS)) {
                    if (is("abstract")) {
                        flags |= TYPE_ABSTRACT;
                    }
                    bump();
                } else if (is("sealed") && continuesDeclaration(pos + 1)) {
                    bump();
                } else if (is("non") && peekIs(1, "-") && peekIs(2, "sealed") && adjacent(pos) && adjacent(pos + 1)) {
                    bump();
                    bump();
                    bump();
                } else {
                    return flags;
                }
            }
        }

        /**
         * True if token {@code i} can follow a modifier: another modifier, an annotation or a type keyword. Used to
         * tell the modifier {@code sealed} from a type or variable named {@code sealed}.
         */
        private boolean continuesDeclaration(int i) {
            return switch (kindAt(i)) {
                case KEYWORD -> isOneOf(i, DECLARATION_KEYWORDS);
                case OP -> isAt(i, "@");
                case IDENT -> isAt(i, "sealed") || isAt(i, "non") || isAt(i, "record");
                default -> false;
            };
        }

        /** After modifiers: {@code class}, {@code interface}, {@code enum}, {@code @interface}, or {@code record Name (} / {@code record Name <}. */
        private boolean isTypeDeclStart() {
            return is("class")
                    || is("interface")
                    || is("enum")
                    || (is("@") && peekIs(1, "interface"))
                    || (is("record") && kindAt(pos + 1) == Tok.IDENT && (peekIs(2, "(") || peekIs(2, "<")));
        }

        /**
         * A type declaration from its keyword on; the modifiers are already parsed into {@code node}, which becomes
         * the {@link NodeKind#TYPE_DECL} (if {@code emit}; otherwise, for local types, {@code node} is whatever
         * encloses it).
         *
         * <p>Headers: {@code class N [<T>] [extends T] [implements Ts] [permits Ts]}, {@code interface N [<T>]
         * [extends Ts] [permits Ts]}, {@code enum N [implements Ts]}, {@code record N [<T>] (components)
         * [implements Ts]}, {@code @interface N}. The clauses are accepted in any order.
         */
        private void typeDecl(int node, int flags, boolean emit) {
            int sub;
            if (is("interface")) {
                sub = TYPE_INTERFACE;
            } else if (is("enum")) {
                sub = TYPE_ENUM;
            } else if (is("record")) {
                sub = TYPE_RECORD;
            } else if (is("@")) {
                bump(); // the `interface` follows
                sub = TYPE_ANNOTATION;
            } else {
                sub = TYPE_CLASS;
            }
            bump();
            int name = expectIdent();
            if (emit) {
                nodes.kind[node] = NodeKind.TYPE_DECL;
                nodes.sub[node] = sub;
                nodes.flags[node] = flags;
                nodes.name[node] = name;
            }
            while (true) {
                if (is("<")) {
                    typeParameters();
                } else if (sub == TYPE_RECORD && is("(")) {
                    int formal = formalParameters()[0];
                    if (emit) {
                        nodes.count[node] = formal;
                    }
                } else if (eat("extends") || eat("implements") || eat("permits")) {
                    typeList();
                } else {
                    break;
                }
            }
            if (!is("{")) {
                fail();
                return;
            }
            switch (sub) {
                case TYPE_ENUM -> enumBody(emit);
                case TYPE_ANNOTATION -> classBody(emit, Body.ANNOTATION);
                default -> classBody(emit, Body.CLASS);
            }
        }

        /** {@code Type {, Type}} (after {@code extends}, {@code implements}, {@code permits}, {@code throws}). */
        private void typeList() {
            while (true) {
                if (!type()) {
                    fail();
                    return;
                }
                if (!eat(",")) {
                    return;
                }
            }
        }

        /**
         * {@code < T [extends A & B], … >}. Each parameter name is reported as a {@link NodeKind#TYPE_NAME}.
         */
        private void typeParameters() {
            bump(); // <
            while (true) {
                annotations();
                if (kind() == Tok.IDENT) {
                    leaf(NodeKind.TYPE_NAME, pos, pos);
                    bump();
                } else {
                    fail();
                    break;
                }
                if (eat("extends")) {
                    while (true) {
                        if (!type()) {
                            fail();
                            break;
                        }
                        if (!eat("&")) {
                            break;
                        }
                    }
                }
                if (!eat(",")) {
                    break;
                }
            }
            expect(">");
        }

        /**
         * <code>{ members }</code>. With {@code emit}, members become nodes (fields, methods, nested types); without
         * (anonymous and local classes, enum constant bodies), only their references and decision points are recorded.
         */
        private void classBody(boolean emit, Body body) {
            if (!enter()) {
                return;
            }
            expect("{");
            while (!is("}") && !atEof()) {
                int before = pos;
                member(emit, body);
                if (pos == before) {
                    skip();
                }
            }
            expect("}");
            leave();
        }

        /**
         * <code>{ [constant {, constant}] [,] [; members] }</code>; a constant is
         * <code>annotations Name [(args)] [classBody]</code>.
         */
        private void enumBody(boolean emit) {
            if (!enter()) {
                return;
            }
            expect("{");
            while (kind() == Tok.IDENT || (is("@") && !peekIs(1, "interface"))) {
                int node = emit ? open(NodeKind.ENUM_CONSTANT, pos) : NONE;
                modifiers();
                expectIdent();
                if (is("(")) {
                    arguments();
                }
                if (is("{")) {
                    classBody(false, Body.CLASS);
                }
                if (node != NONE) {
                    close(node);
                }
                if (!eat(",")) {
                    break;
                }
            }
            if (!eat(";") && !is("}")) {
                fail();
            }
            while (!is("}") && !atEof()) {
                int before = pos;
                member(emit, Body.CLASS);
                if (pos == before) {
                    skip();
                }
            }
            expect("}");
            leave();
        }

        /** One member of a class body (see {@link #classBody}). */
        private void member(boolean emit, Body body) {
            int start = pos;
            if (eat(";")) {
                return;
            }
            int node = emit ? open(NodeKind.OTHER, start) : NONE;
            if (is("{")) {
                block(); // instance initializer
            } else if (is("static") && peekIs(1, "{")) {
                bump();
                block(); // static initializer
            } else {
                int flags = modifiers();
                if (isTypeDeclStart()) {
                    typeDecl(node, flags, emit);
                } else {
                    memberRest(node, emit, body, true);
                }
            }
            if (emit) {
                close(node);
            }
        }

        /**
         * The rest of a member after its modifiers: a constructor, compact constructor, method, annotation element or
         * field. Fills in {@code node} (when {@code emit}) as a {@link NodeKind#METHOD} or {@link NodeKind#FIELD};
         * anything else stays {@link NodeKind#OTHER}. {@code inClass} is false at the top level (methods there are not
         * members of any type).
         */
        private void memberRest(int node, boolean emit, Body body, boolean inClass) {
            if (is("<")) {
                // Generic method or constructor; annotations may follow the type parameters.
                typeParameters();
                annotations();
            }
            if (kind() == Tok.IDENT && peekIs(1, "(")) {
                // Constructor: `Name(params) [throws Ts] { body }`.
                int name = pos;
                bump();
                int[] params = formalParameters();
                if (eat("throws")) {
                    typeList();
                }
                methodBody();
                if (emit && inClass) {
                    nodes.kind[node] = NodeKind.METHOD;
                    nodes.sub[node] = METHOD_CONSTRUCTOR;
                    nodes.name[node] = name;
                    nodes.count[node] = params[0] + params[1];
                }
                return;
            }
            if (kind() == Tok.IDENT && peekIs(1, "{")) {
                // Compact canonical constructor of a record: `Name { body }`.
                bump();
                block();
                return;
            }
            if (!type()) {
                fail();
                return;
            }
            if (kind() != Tok.IDENT) {
                fail();
                return;
            }
            int name = pos;
            if (peekIs(1, "(")) {
                bump();
                if (body == Body.ANNOTATION && peekIs(1, ")")) {
                    // Annotation type element: `Type name() [dims] [default value];`.
                    bump();
                    bump();
                    dims();
                    if (eat("default")) {
                        elementValue();
                    }
                    expect(";");
                    return;
                }
                int[] params = formalParameters();
                dims();
                if (eat("throws")) {
                    typeList();
                }
                if (eat("default")) {
                    elementValue(); // only valid in annotation types, but harmless
                    expect(";");
                } else {
                    methodBody();
                }
                if (emit && inClass) {
                    nodes.kind[node] = NodeKind.METHOD;
                    nodes.name[node] = name;
                    nodes.count[node] = params[0] + params[1];
                }
                return;
            }
            // Field: `Type a [= x], b[] ;`.
            int count = variableDeclarators();
            expect(";");
            if (emit && inClass) {
                nodes.kind[node] = NodeKind.FIELD;
                nodes.count[node] = count;
            }
        }

        /** A method body: a block, or {@code ;} for abstract and interface methods. */
        private void methodBody() {
            if (!eat(";")) {
                if (is("{")) {
                    block();
                } else {
                    fail();
                }
            }
        }

        /** {@code name [dims] [= init] {, …}}. Returns the number of declarators. */
        private int variableDeclarators() {
            int count = 0;
            while (true) {
                if (expectIdent() == NONE) {
                    return count;
                }
                count++;
                dims();
                if (eat("=")) {
                    variableInitializer();
                }
                if (!eat(",")) {
                    return count;
                }
            }
        }

        /** An expression or an array initializer <code>{ a, b, }</code>. */
        private void variableInitializer() {
            if (!is("{")) {
                expression();
                return;
            }
            if (!enter()) {
                return;
            }
            bump();
            while (!is("}") && !atEof()) {
                int before = pos;
                variableInitializer();
                if (!eat(",") && !is("}")) {
                    fail();
                    if (pos == before) {
                        skip();
                    }
                }
            }
            expect("}");
            leave();
        }

        /**
         * {@code ( [receiver ,] param {, param} )} where a param is {@code modifiers Type [...] name [dims]} and a
         * receiver parameter is {@code Type [Outer.]this}. Returns {ordinary parameters, varargs parameters}; the
         * receiver is not counted.
         */
        private int[] formalParameters() {
            int formal = 0;
            int spread = 0;
            if (!enter()) {
                return new int[] {0, 0};
            }
            int close = closingParen();
            expect("(");
            while (!is(")") && !atEof()) {
                int before = pos;
                modifiers();
                boolean modified = pos > before;
                if (!type()) {
                    fail();
                } else if (eat("...")) {
                    annotations();
                    expectIdent();
                    dims();
                    spread++;
                } else if (eat("this")) {
                    // Receiver parameter `Foo this`, not counted. One with modifiers
                    // (`@A Foo this`, `final Foo this`) is read as an ordinary parameter named `this`, so that one counts.
                    if (modified) {
                        formal++;
                    }
                } else if (kind() == Tok.IDENT && peekIs(1, ".")) {
                    // receiver parameter of an inner class constructor: `Outer Outer.this`
                    while (kind() == Tok.IDENT && peekIs(1, ".")) {
                        bump();
                        bump();
                    }
                    expect("this");
                } else {
                    expectIdent();
                    dims();
                    formal++;
                }
                if (!eat(",")) {
                    if (!is(")")) {
                        resync(close, before);
                    }
                    break;
                }
            }
            expect(")");
            leave();
            return new int[] {formal, spread};
        }

        /** {@code { [annotations] [] }}, as after a type or a declarator name. */
        private void dims() {
            while (true) {
                Mark m = mark();
                annotations();
                if (is("[") && peekIs(1, "]")) {
                    bump();
                    bump();
                } else {
                    reset(m);
                    return;
                }
            }
        }

        // ---------------------------------------------------------------- annotations

        /** Zero or more annotations (not {@code @interface}). */
        private void annotations() {
            while (is("@") && !peekIs(1, "interface")) {
                annotation();
            }
        }

        /**
         * {@code @Name [( [value | key = value {, key = value}] )]}. Reports the name as a
         * {@link NodeKind#ANNOTATION_NAME}.
         */
        private void annotation() {
            bump(); // @
            int first = pos;
            if (expectIdent() == NONE) {
                return;
            }
            while (is(".") && kindAt(pos + 1) == Tok.IDENT) {
                bump();
                bump();
            }
            leaf(NodeKind.ANNOTATION_NAME, first, pos - 1);
            if (is("(")) {
                if (!enter()) {
                    return;
                }
                int close = closingParen();
                bump();
                while (!is(")") && !atEof()) {
                    int before = pos;
                    if (kind() == Tok.IDENT && peekIs(1, "=")) {
                        bump();
                        bump();
                    }
                    elementValue();
                    if (!eat(",")) {
                        if (!is(")")) {
                            resync(close, before);
                        }
                        break;
                    }
                }
                expect(")");
                leave();
            }
        }

        /** An annotation element value: an annotation, <code>{ values }</code> or an expression. */
        private void elementValue() {
            if (is("@")) {
                annotation();
            } else if (is("{")) {
                if (!enter()) {
                    return;
                }
                bump();
                while (!is("}") && !atEof()) {
                    int before = pos;
                    elementValue();
                    if (!eat(",") && !is("}")) {
                        fail();
                        if (pos == before) {
                            skip();
                        }
                    }
                }
                expect("}");
                leave();
            } else {
                expression();
            }
        }

        // ---------------------------------------------------------------- types

        /** True if token {@code i} is a primitive type keyword or {@code void} ({@code void} is accepted wherever a type is). */
        private boolean isPrimitiveAt(int i) {
            return kindAt(i) == Tok.KEYWORD && isOneOf(i, PRIMITIVES);
        }

        /**
         * Parses a type if one starts here: {@code annotations (primitive | classType) {annotations []}}. All or
         * nothing: on failure nothing is consumed or reported and it returns false.
         *
         * <p>A class type {@code A<X>.B<Y>} is reported as follows: a single name {@code A} as
         * {@link NodeKind#TYPE_NAME}, a qualified one as one {@link NodeKind#SCOPED_TYPE_NAME} covering everything up
         * to the last name (type arguments and annotations of the leading parts included, and not reported on their
         * own), plus whatever the last part's type arguments contain.
         */
        private boolean type() {
            if (!enter()) {
                return false;
            }
            boolean ok = typeInner();
            leave();
            return ok;
        }

        private boolean typeInner() {
            Mark m = mark();
            annotations();
            if (isPrimitiveAt(pos)) {
                bump();
            } else if (kind() == Tok.IDENT) {
                int first = pos;
                int last = pos;
                int mark = nodes.size;
                bump();
                if (is("<") && !typeArguments()) {
                    reset(m);
                    return false;
                }
                boolean scoped = false;
                while (is(".")) {
                    Mark dot = mark();
                    bump();
                    annotations();
                    if (kind() != Tok.IDENT) {
                        reset(dot);
                        break;
                    }
                    last = pos;
                    bump();
                    // The parts before this name are now inside the scoped name: drop what they reported.
                    nodes.size = Math.min(nodes.size, mark);
                    scoped = true;
                    if (is("<") && !typeArguments()) {
                        reset(m);
                        return false;
                    }
                }
                if (scoped) {
                    leaf(NodeKind.SCOPED_TYPE_NAME, first, last);
                } else {
                    leaf(NodeKind.TYPE_NAME, first, first);
                }
            } else {
                reset(m);
                return false;
            }
            dims();
            return true;
        }

        /**
         * {@code < [typeOrWildcard {, typeOrWildcard}] >} (empty for the diamond {@code <>}). All or nothing, like
         * {@link #type}.
         */
        private boolean typeArguments() {
            Mark m = mark();
            bump(); // <
            if (eat(">")) {
                return true;
            }
            while (true) {
                Mark w = mark();
                annotations();
                if (eat("?")) {
                    if (eat("extends") || eat("super")) {
                        if (!type()) {
                            reset(m);
                            return false;
                        }
                    }
                } else {
                    reset(w);
                    if (!type()) {
                        reset(m);
                        return false;
                    }
                }
                if (!eat(",")) {
                    break;
                }
            }
            if (!eat(">")) {
                reset(m);
                return false;
            }
            return true;
        }

        // ---------------------------------------------------------------- statements

        /** <code>{ statements }</code> */
        private void block() {
            if (!enter()) {
                return;
            }
            expect("{");
            statementsUntilBrace();
            expect("}");
            leave();
        }

        /** Statements up to (not including) the closing <code>}</code>. */
        private void statementsUntilBrace() {
            while (!is("}") && !atEof()) {
                int before = pos;
                statement();
                if (pos == before) {
                    skip();
                }
            }
        }

        private void statement() {
            if (!enter()) {
                return;
            }
            statementInner();
            leave();
        }

        private void statementInner() {
            switch (kind()) {
                case OP -> {
                    if (is("{")) {
                        block();
                    } else if (eat(";")) {
                        // empty statement
                    } else if (is("@")) {
                        localDeclaration();
                    } else if (is("<")) {
                        // `<T>this(…);` explicit constructor invocation with type arguments
                        if (!typeArguments()) {
                            fail();
                        }
                        expressionStatement();
                    } else {
                        expressionStatement();
                    }
                }
                case KEYWORD -> keywordStatement();
                case IDENT -> {
                    if (peekIs(1, ":")) {
                        // labeled statement
                        bump();
                        bump();
                        statement();
                    } else if (is("yield") && isYield()) {
                        bump();
                        expression();
                        expect(";");
                    } else if (isTypeDeclStart()
                            || ((is("sealed") || is("non")) && continuesDeclaration(pos + 1))) {
                        localDeclaration();
                    } else if (isLocalVariable()) {
                        localVariable();
                    } else {
                        expressionStatement();
                    }
                }
                default -> expressionStatement();
            }
        }

        /** A statement that starts with a keyword (see {@link #statementInner}). */
        private void keywordStatement() {
            if (is("if")) {
                ifStatement();
            } else if (is("while")) {
                decision();
                parenthesized();
                statement();
            } else if (is("do")) {
                decision();
                statement();
                expect("while");
                parenthesized();
                expect(";");
            } else if (is("for")) {
                forStatement();
            } else if (is("try")) {
                tryStatement();
            } else if (is("switch")) {
                switchBlock();
            } else if (is("return")) {
                bump();
                if (!is(";")) {
                    expression();
                }
                expect(";");
            } else if (is("throw")) {
                bump();
                expression();
                expect(";");
            } else if (is("break") || is("continue")) {
                bump();
                if (kind() == Tok.IDENT) {
                    bump();
                }
                expect(";");
            } else if (is("assert")) {
                bump();
                expression();
                if (eat(":")) {
                    expression();
                }
                expect(";");
            } else if (is("synchronized") && peekIs(1, "(")) {
                bump();
                parenthesized();
                block();
            } else if (isOneOf(pos, LOCAL_DECLARATION_KEYWORDS)) {
                localDeclaration();
            } else if (isPrimitiveAt(pos) && !peekIs(1, ".") && !peekIs(1, "::")) {
                localVariable();
            } else {
                expressionStatement();
            }
        }

        /**
         * {@code yield} starts a yield statement unless it is used as a name ({@code yield = 1;}, {@code yield.x()};
         * {@code yield(…)} is still a statement).
         */
        private boolean isYield() {
            int i = pos + 1;
            return switch (kindAt(i)) {
                case IDENT, NUMBER, CHAR, STR -> true;
                case KEYWORD -> !isAt(i, "instanceof");
                case OP -> isAt(i, "(") || isAt(i, "-") || isAt(i, "+") || isAt(i, "!") || isAt(i, "~")
                        || isAt(i, "++") || isAt(i, "--");
                default -> false;
            };
        }

        /** At an identifier: {@code Type name} follows, so this is a local variable declaration. */
        private boolean isLocalVariable() {
            if (depthCapped) {
                return false;
            }
            Mark m = mark();
            boolean ok = type() && kind() == Tok.IDENT;
            reset(m);
            return ok;
        }

        /** A statement that starts with modifiers: a local class/interface/enum/record or a local variable. */
        private void localDeclaration() {
            int flags = modifiers();
            if (isTypeDeclStart()) {
                typeDecl(NONE, flags, false);
            } else {
                localVariable();
            }
        }

        /** {@code Type declarators ;} (modifiers already parsed). */
        private void localVariable() {
            if (!type()) {
                fail();
                return;
            }
            variableDeclarators();
            expect(";");
        }

        /** {@code expression ;} */
        private void expressionStatement() {
            expression();
            expect(";");
        }

        /** {@code ( expression )} */
        private void parenthesized() {
            expect("(");
            expression();
            expect(")");
        }

        /** {@code if (c) s [else if (c) s]… [else s]}. The {@code else if} chain is a loop, so long chains do not nest. */
        private void ifStatement() {
            while (true) {
                decision(); // if
                parenthesized();
                statement();
                if (!eat("else")) {
                    return;
                }
                if (!is("if")) {
                    statement();
                    return;
                }
            }
        }

        /** {@code for (init; cond; update) s} or {@code for (modifiers Type name : expr) s}. */
        private void forStatement() {
            decision(); // for
            expect("(");
            // A declaration first (`modifiers Type name`), which an enhanced for continues with `:`.
            Mark m = mark();
            modifiers();
            if (type() && kind() == Tok.IDENT) {
                bump();
                dims();
                if (eat(":")) {
                    expression();
                    expect(")");
                    statement();
                    return;
                }
                // Classic for with a declaration: finish the first declarator, then any others.
                if (eat("=")) {
                    variableInitializer();
                }
                if (eat(",")) {
                    variableDeclarators();
                }
            } else {
                reset(m);
                expressionList(";");
            }
            expect(";");
            if (!is(";")) {
                expression();
            }
            expect(";");
            expressionList(")");
            expect(")");
            statement();
        }

        /** {@code [expression {, expression}]} up to (not including) {@code end}. */
        private void expressionList(String end) {
            if (is(end)) {
                return;
            }
            while (true) {
                expression();
                if (!eat(",")) {
                    return;
                }
            }
        }

        /** {@code try [(resources)] block {catch (T | U e) block} [finally block]}. */
        private void tryStatement() {
            bump(); // try
            if (is("(")) {
                bump();
                while (!is(")") && !atEof()) {
                    int before = pos;
                    // `modifiers Type name = expr` or an expression naming an existing resource.
                    Mark m = mark();
                    modifiers();
                    if (type() && kind() == Tok.IDENT) {
                        bump();
                        expect("=");
                        expression();
                    } else {
                        reset(m);
                        expression();
                    }
                    if (!eat(";") && !is(")")) {
                        fail();
                        if (pos == before) {
                            skip();
                        }
                        break;
                    }
                }
                expect(")");
            }
            block();
            while (is("catch")) {
                decision();
                expect("(");
                modifiers();
                while (true) {
                    if (!type()) {
                        fail();
                        break;
                    }
                    if (!eat("|")) {
                        break;
                    }
                }
                expectIdent();
                expect(")");
                block();
            }
            if (eat("finally")) {
                block();
            }
        }

        /**
         * <code>switch (x) { … }</code>, as a statement or an expression. The body is either statement groups
         * ({@code case A: case B: statements}) or rules ({@code case A, B -> expression; | block | throw}).
         */
        private void switchBlock() {
            bump(); // switch
            parenthesized();
            if (!is("{")) {
                fail();
                return;
            }
            if (!enter()) {
                return;
            }
            bump();
            while (!is("}") && !atEof()) {
                int before = pos;
                if (is("case") || is("default")) {
                    boolean arrow = switchLabel();
                    if (arrow) {
                        if (is("{")) {
                            block();
                        } else if (is("throw")) {
                            statement();
                        } else {
                            expressionStatement();
                        }
                    } else {
                        while (!is("case") && !is("default") && !is("}") && !atEof()) {
                            int b = pos;
                            statement();
                            if (pos == b) {
                                skip();
                            }
                        }
                    }
                }
                if (pos == before) {
                    skip();
                }
            }
            expect("}");
            leave();
        }

        /**
         * {@code case p {, p} [when guard]} or {@code default}, followed by {@code :} or {@code ->}. Returns true for
         * {@code ->}. Each {@code case} is a decision point; {@code default} is not.
         */
        private boolean switchLabel() {
            if (eat("default")) {
                if (eat("->")) {
                    return true;
                }
                expect(":");
                return false;
            }
            decision(); // case
            // Find the `->` that ends this label (the first `->` or `:` at bracket depth 0), so the label's own
            // expressions do not take `A -> x` for a lambda.
            int saved = labelArrow;
            labelArrow = labelEnd();
            while (true) {
                int before = pos;
                caseItem();
                if (pos == before || !eat(",")) {
                    break;
                }
            }
            if (is("when")) {
                bump();
                expression();
            }
            labelArrow = saved;
            if (eat("->")) {
                return true;
            }
            expect(":");
            return false;
        }

        /** Index of the {@code ->} ending the current case label, or {@link #NONE} if it ends with {@code :} (or is malformed). */
        private int labelEnd() {
            int i = pos;
            int ternary = 0;
            while (i < len) {
                Tok k = kinds[i];
                if (k == Tok.EOF) {
                    break;
                }
                if (k == Tok.OP) {
                    int p = pair[i];
                    if (p != NONE) {
                        i = p + 1;
                        continue;
                    }
                    if (isAt(i, "->")) {
                        return i;
                    }
                    if (isAt(i, "?")) {
                        ternary++;
                    } else if (isAt(i, ":")) {
                        if (ternary == 0) {
                            break;
                        }
                        ternary--;
                    } else if (isAt(i, ";") || isAt(i, "}") || isAt(i, ")") || isAt(i, "{")) {
                        break;
                    }
                }
                i++;
            }
            return NONE;
        }

        /**
         * One item of a case label: a record pattern, a type pattern ({@code String s}), an expression (a constant or
         * an enum name), or {@code default} (in {@code case null, default}).
         */
        private void caseItem() {
            // `case null, default ->` (Java 21)
            if (eat("default")) {
                return;
            }
            if (tryRecordPattern(true)) {
                return;
            }
            if (!depthCapped) {
                Mark m = mark();
                if (type() && kind() == Tok.IDENT && endsPattern(pos + 1)) {
                    bump();
                    return;
                }
                reset(m);
            }
            expression();
        }

        /** True if token {@code i} can follow a pattern in a case label. */
        private boolean endsPattern(int i) {
            return isAt(i, "->") || isAt(i, ":") || isAt(i, ",") || isAt(i, "when");
        }

        /**
         * Tries a record pattern {@code Name(components)} or {@code Name<T>(components)}; with {@code inLabel}, it must
         * also be followed by what ends a case label item. All or nothing. The plain name is not a type reference
         * (it is a plain identifier), a generic one is.
         */
        private boolean tryRecordPattern(boolean inLabel) {
            if (depthCapped || kind() != Tok.IDENT || !(peekIs(1, "(") || peekIs(1, "<"))) {
                return false;
            }
            Mark m = mark();
            boolean ok = recordPattern() && (!inLabel || endsPattern(pos));
            if (!ok) {
                reset(m);
            }
            return ok;
        }

        /** {@code Name[<T>] ( [component {, component}] )} where a component is {@code _}, a record pattern or {@code Type name}. */
        private boolean recordPattern() {
            if (!enter()) {
                return false;
            }
            boolean ok = recordPatternInner();
            leave();
            return ok;
        }

        private boolean recordPatternInner() {
            if (peekIs(1, "<")) {
                if (!type()) {
                    return false;
                }
            } else {
                bump();
            }
            if (!eat("(")) {
                return false;
            }
            while (!is(")")) {
                if (is("_") && (peekIs(1, ",") || peekIs(1, ")"))) {
                    bump();
                } else if (tryRecordPattern(false)) {
                    // nested record pattern
                } else if (!(type() && kind() == Tok.IDENT)) {
                    return false;
                } else {
                    bump();
                }
                if (!eat(",")) {
                    break;
                }
            }
            return eat(")");
        }

        // ---------------------------------------------------------------- expressions

        /**
         * {@code operand { op operand | instanceof pattern | ? expression : }}, with operator precedence ignored (see
         * the class documentation). {@code &&}, {@code ||} and the {@code ?} of a conditional are decision points.
         */
        private void expression() {
            if (!enter()) {
                return;
            }
            while (true) {
                operand();
                // `instanceof` and postfix-like parts that may follow an operand
                while (is("instanceof")) {
                    bump();
                    eat("final");
                    if (!tryRecordPattern(false)) {
                        if (!type()) {
                            fail();
                        } else if (kind() == Tok.IDENT) {
                            bump(); // pattern variable
                        }
                    }
                }
                if (is("?")) {
                    decision();
                    expression();
                    expect(":");
                    continue;
                }
                if (binaryOperator()) {
                    continue;
                }
                break;
            }
            leave();
        }

        /** Consumes a binary or assignment operator if one is here (adjacent {@code >}s count as one shift operator). */
        private boolean binaryOperator() {
            if (kind() != Tok.OP) {
                return false;
            }
            if (is("&&") || is("||")) {
                decision();
                return true;
            }
            if (!isOneOf(pos, BINARY_OPERATORS)) {
                return false;
            }
            boolean shift = is(">");
            bump();
            if (shift) {
                // `>>`, `>>>`, `>>=`, `>>>=` arrive as separate `>` / `>=` tokens
                while ((is(">") || is(">=")) && ends[pos - 1] == starts[pos]) {
                    boolean more = is(">");
                    bump();
                    if (!more) {
                        break;
                    }
                }
            }
            return true;
        }

        /** True if token {@code i} can start an operand that is not {@code +}/{@code -}: what may follow {@code (Type)} in a cast. */
        private boolean startsOperand(int i) {
            return switch (kindAt(i)) {
                case IDENT, NUMBER, CHAR, STR -> true;
                case KEYWORD -> isOneOf(i, OPERAND_KEYWORDS);
                case OP -> isAt(i, "(") || isAt(i, "!") || isAt(i, "~")
                        || ((isAt(i, "++") || isAt(i, "--")) && startsOperand(i + 1));
                default -> false;
            };
        }

        /** {@code { prefixOp | cast } (lambda | primary {postfix})}. */
        private void operand() {
            while (true) {
                if (kind() == Tok.OP && isOneOf(pos, PREFIX_OPERATORS)) {
                    bump();
                    continue;
                }
                if (is("(") && !lambdaAhead() && tryCast()) {
                    continue;
                }
                break;
            }
            if (lambdaAhead()) {
                lambda();
                return;
            }
            int bare = primary();
            postfix(bare);
        }

        /**
         * Tries {@code ( Type {& Type} )} followed by an operand. It reads {@code (Foo) x},
         * {@code (Foo) !x}, {@code (Foo) ++x} and {@code (int) -x} as casts but {@code (Foo) - x} as a parenthesized
         * expression minus {@code x}, i.e. only a primitive type may be followed by {@code +}/{@code -}. All or
         * nothing.
         */
        private boolean tryCast() {
            if (depthCapped) {
                return false;
            }
            Mark m = mark();
            bump(); // (
            boolean primitive = isPrimitiveAt(pos) && peekIs(1, ")");
            if (!type()) {
                reset(m);
                return false;
            }
            while (eat("&")) {
                if (!type()) {
                    reset(m);
                    return false;
                }
            }
            if (!eat(")")) {
                reset(m);
                return false;
            }
            if (startsOperand(pos) || (primitive && (is("+") || is("-")))) {
                return true;
            }
            reset(m);
            return false;
        }

        /** True if a lambda starts here: {@code name ->} or {@code ( … ) ->}. The {@code ->} ending a case label does not count. */
        private boolean lambdaAhead() {
            if (kind() == Tok.IDENT) {
                return peekIs(1, "->") && pos + 1 != labelArrow;
            }
            if (is("(")) {
                int p = pair[pos];
                return p != NONE && isAt(p + 1, "->") && p + 1 != labelArrow;
            }
            return false;
        }

        /** {@code params -> (expression | block)}, where params are a name, {@code (a, b)} or formal parameters {@code (Type a, var b)}. */
        private void lambda() {
            if (kind() == Tok.IDENT) {
                bump();
            } else {
                // Inferred parameters `(a, b)` have no types; otherwise parse formal parameters (which report types).
                int close = pair[pos];
                boolean inferred = true;
                for (int i = pos + 1; i < close; i += 2) {
                    if (!(kindAt(i) == Tok.IDENT && (isAt(i + 1, ",") || i + 1 == close))) {
                        inferred = false;
                        break;
                    }
                }
                if (inferred) {
                    pos = close;
                    bump();
                } else {
                    formalParameters();
                }
            }
            expect("->");
            if (is("{")) {
                block();
            } else {
                expression();
            }
        }

        /** A primary expression. Returns the token of a lone identifier (the possible object of a member access), else {@link #NONE}. */
        private int primary() {
            switch (kind()) {
                case NUMBER, CHAR, STR -> {
                    bump();
                    return NONE;
                }
                case IDENT -> {
                    if (typeBeforeMemberRefOrClass()) {
                        return NONE;
                    }
                    int i = pos;
                    bump();
                    if (is("(")) {
                        arguments(); // unqualified method call
                        return NONE;
                    }
                    return i;
                }
                case KEYWORD -> {
                    if (is("true") || is("false") || is("null") || is("this") || is("super")) {
                        bump();
                    } else if (is("new")) {
                        creation();
                    } else if (is("switch")) {
                        switchBlock();
                    } else if (isPrimitiveAt(pos)) {
                        // `int.class`, `int[].class`, `int[]::new`
                        type();
                    } else {
                        fail();
                    }
                    return NONE;
                }
                case OP -> {
                    if (is("(")) {
                        if (!enter()) {
                            return NONE;
                        }
                        bump();
                        expression();
                        expect(")");
                        leave();
                    } else if (is("@")) {
                        // annotated type in an expression (e.g. before `::`); report and continue with the type
                        if (!type()) {
                            skip();
                        }
                    } else {
                        fail();
                    }
                    return NONE;
                }
                default -> {
                    fail();
                    return NONE;
                }
            }
        }

        /**
         * At an identifier: if a dotted name is followed by type arguments or {@code []} and then {@code ::}, or by
         * {@code []}/nothing and then {@code .class}, parse it as a type (a generic or array
         * type in a method reference, or the type of a class literal) and return
         * true. Plain {@code Foo::bar} and {@code a.b::c} stay expressions.
         */
        private boolean typeBeforeMemberRefOrClass() {
            // end of `a.b.c`
            int k = pos + 1;
            while (isAt(k, ".") && kindAt(k + 1) == Tok.IDENT) {
                k += 2;
            }
            boolean classLiteral = isAt(k, ".") && isAt(k + 1, "class");
            boolean array = isAt(k, "[") && isAt(k + 1, "]");
            boolean generic = isAt(k, "<");
            if (depthCapped || !(classLiteral || array || generic)) {
                return false;
            }
            Mark m = mark();
            if (type() && (is("::") || (is(".") && peekIs(1, "class")))) {
                return true;
            }
            reset(m);
            return false;
        }

        /** {@code new [<T>] annotations Type (args) [classBody]} or {@code new Type dims [initializer]}. */
        private void creation() {
            bump(); // new
            if (is("<") && !typeArguments()) {
                fail();
            }
            annotations();
            // The created type. `type` also takes empty dimensions (`new int[][] {…}`); sized ones (`new int[n]`)
            // are left for the loop below.
            if (!type()) {
                fail();
                return;
            }
            if (is("(")) {
                arguments();
                if (is("{")) {
                    classBody(false, Body.CLASS);
                }
                return;
            }
            // Array creation: `[expr]…[]…` or (already consumed by type) `[]… {init}`.
            boolean sized = false;
            while (is("[")) {
                if (peekIs(1, "]")) {
                    bump();
                    bump();
                    continue;
                }
                if (!enter()) {
                    return;
                }
                bump();
                expression();
                expect("]");
                leave();
                sized = true;
            }
            if (!sized) {
                if (is("{")) {
                    variableInitializer();
                } else {
                    fail();
                }
            }
        }

        /** {@code ( [expression {, expression}] )} */
        private void arguments() {
            if (!enter()) {
                return;
            }
            int close = closingParen();
            expect("(");
            while (!is(")") && !atEof()) {
                int before = pos;
                expression();
                if (!eat(",")) {
                    if (!is(")")) {
                        resync(close, before);
                    }
                    break;
                }
            }
            expect(")");
            leave();
        }

        /**
         * Member accesses, calls, indexing, method references and postfix {@code ++}/{@code --} after a primary.
         * {@code bare} is the token of the primary if it was a lone identifier: then the first {@code .member} makes
         * it a {@link NodeKind#MEMBER_OBJECT} (except {@code Outer.this} and {@code outer.new Inner()}).
         */
        private void postfix(int bare) {
            while (true) {
                if (is(".")) {
                    int next = pos + 1;
                    if (isAt(next, "this") || isAt(next, "class")) {
                        bump();
                        bump();
                    } else if (isAt(next, "new")) {
                        bump();
                        creation();
                    } else if (kindAt(next) == Tok.STR) {
                        // string template `STR."…"`
                        bump();
                        bump();
                    } else if (isAt(next, "super") || isAt(next, "<") || kindAt(next) == Tok.IDENT) {
                        if (bare != NONE) {
                            leaf(NodeKind.MEMBER_OBJECT, bare, bare);
                        }
                        bump();
                        if (eat("super")) {
                            if (is("::")) {
                                bare = NONE;
                                continue;
                            }
                            if (!eat(".")) {
                                // `Outer.super(…)` explicit constructor invocation
                                bare = NONE;
                                continue;
                            }
                        }
                        if (is("<") && !typeArguments()) {
                            fail();
                        }
                        expectIdent();
                        if (is("(")) {
                            arguments();
                        }
                    } else {
                        bump();
                        fail();
                    }
                    bare = NONE;
                } else if (is("[")) {
                    if (!enter()) {
                        return;
                    }
                    bump();
                    expression();
                    expect("]");
                    leave();
                    bare = NONE;
                } else if (is("(")) {
                    // call on `this`/`super` (`this(…)`), or on something that is not callable (an error, which is
                    // reported; parse the arguments anyway)
                    arguments();
                    bare = NONE;
                } else if (is("::")) {
                    bump();
                    if (is("<") && !typeArguments()) {
                        fail();
                    }
                    if (!eat("new")) {
                        expectIdent();
                    }
                    bare = NONE;
                } else if (is("++") || is("--")) {
                    bump();
                    bare = NONE;
                } else {
                    return;
                }
            }
        }
    }
}
