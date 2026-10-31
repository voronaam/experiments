package dev.codecity.desktop;

import dev.codecity.CityModel.Building;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A native source window with lightweight Java, TypeScript and Rust highlighting; the selected type's lines are
 * marked.
 *
 * <p>The highlighter is a single-pass hand-written scanner, not a real lexer: it knows comments, string and character
 * literals, Java text blocks, TypeScript template literals, annotations / decorators / Rust attributes and lifetimes,
 * numbers and a keyword list per language, and guesses
 * constants (ALL_CAPS) and types (Capitalised). Odd constructs may be coloured wrongly, but apart from dropped '\r'
 * characters every character ends up in exactly one token. The file is shown in a virtualised {@link ListView} with
 * one row per line.
 */
final class SourceViewer {

    private static final Set<String> TS_KEYWORDS = Set.of(
            "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch", "class", "const",
            "constructor", "continue", "declare", "default", "delete", "do", "else", "enum", "export", "extends",
            "false", "finally", "for", "from", "function", "get", "if", "implements", "import", "in", "instanceof",
            "interface", "keyof", "let", "namespace", "never", "new", "null", "number", "of", "private", "protected",
            "public", "readonly", "return", "set", "static", "string", "super", "switch", "this", "throw", "true",
            "try", "type", "typeof", "undefined", "unknown", "var", "void", "while", "yield", "satisfies");
    private static final Set<String> RUST_KEYWORDS = Set.of(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false",
            "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
            "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while",
            "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64",
            "bool", "char", "str");
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "yield", "sealed",
            "permits", "non-sealed", "true", "false", "null");

    private SourceViewer() {
    }

    /** A run of text and its CSS style class ({@code keyword}, {@code string}, {@code comment}, ...). */
    record Token(String text, String style) {
    }

    /**
     * Opens a window on {@code b}'s file, scrolled so the type's first line is near the top (three lines of context
     * above it). The rules are those of the web viewer's server ({@code api/source}): the file may have changed
     * since the analysis, so only a regular file is read (a symbolic link or FIFO swapped in for it is not followed
     * or opened) and files over 4 MiB are not loaded; invalid UTF-8 is replaced, and a read error is shown in place
     * of the code. Esc closes the window.
     */
    static void show(Window owner, Building b, String stylesheet) {
        String code;
        try {
            BasicFileAttributes attrs = Files.readAttributes(b.type().file(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            code = !attrs.isRegularFile() ? "// unable to read " + b.file()
                    : attrs.size() > 4L * 1024 * 1024 ? "// file too large to display"
                    : new String(Files.readAllBytes(b.type().file()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            code = "// unable to read " + b.file();
        }
        List<List<Token>> lines = highlight(code, b.type().language());
        int first = b.type().line();
        int last = b.type().endLine();
        int digits = String.valueOf(lines.size()).length();

        ListView<Integer> list = new ListView<>();
        list.getStyleClass().add("source-list");
        for (int i = 0; i < lines.size(); i++) {
            list.getItems().add(i);
        }
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Integer line, boolean empty) {
                super.updateItem(line, empty);
                // Cells are reused for different lines while scrolling, so the marker class is reset every time.
                getStyleClass().remove("in-type");
                if (empty || line == null) {
                    setGraphic(null);
                    return;
                }
                Label number = new Label(String.format("%" + digits + "d", line + 1));
                number.getStyleClass().add("line-number");
                TextFlow flow = new TextFlow();
                for (Token t : lines.get(line)) {
                    Text text = new Text(t.text());
                    text.getStyleClass().addAll("code", t.style());
                    flow.getChildren().add(text);
                }
                HBox row = new HBox(14, number, flow);
                setGraphic(row);
                if (line + 1 >= first && line + 1 <= last) {
                    getStyleClass().add("in-type");
                }
            }
        });

        Label title = new Label(b.file() + ":" + first);
        title.getStyleClass().add("source-title");
        BorderPane pane = new BorderPane(list);
        pane.setTop(title);
        BorderPane.setMargin(title, new Insets(10, 14, 10, 14));
        pane.getStyleClass().add("source-window");

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle(b.type().name() + " — " + b.file());
        Scene scene = new Scene(pane, 980, 720);
        scene.getStylesheets().add(stylesheet);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);
        stage.show();
        list.scrollTo(Math.max(0, first - 4));
    }

    /** Splits source into styled tokens per line; block comments and text blocks may span lines. */
    static List<List<Token>> highlight(String code) {
        return highlight(code, KEYWORDS);
    }

    /** Splits source in {@code language} into styled tokens per line (see {@link #highlight(String, Set)}). */
    static List<List<Token>> highlight(String code, dev.codecity.analysis.Language language) {
        return highlight(code, switch (language) {
            case JAVA -> KEYWORDS;
            case TYPESCRIPT -> TS_KEYWORDS;
            case RUST -> RUST_KEYWORDS;
        });
    }

    /**
     * The scanner, the same as the Rust and D viewers' ({@code gui/source.rs}, {@code gui/source.d}); the three are
     * held to identical tokens by the samples in {@code testdata/highlight/}. It works on code points, so a character
     * outside the BMP is never split. At each position the first matching rule wins, in this order: '\r' (dropped),
     * newline, continuation of a multi-line construct ({@code mode}: block comment, text block or template literal),
     * line comment, block comment, text block, template literal, Rust lifetime, string/char literal, annotation /
     * decorator / Rust attribute / TypeScript {@code #private} name, word (keyword / constant / type / plain), number,
     * anything else (plain). Plain characters are collected in {@code plain} and flushed as one token before the next
     * styled token, so the token count stays low. No token ever contains a line break, so each line gets its own list.
     * Lifetimes are only recognised in Rust code ({@code keywords} is {@code RUST_KEYWORDS}), so a TypeScript
     * {@code 'react'} stays a string.
     *
     * @return one token list per line, as many lists as the text has lines
     */
    static List<List<Token>> highlight(String code, Set<String> keywords) {
        // Every '\r' is dropped up front, so none ends up inside a comment, string or other token (CRLF files).
        int[] cs = code.codePoints().filter(c -> c != '\r').toArray();
        int n = cs.length;
        List<List<Token>> lines = new ArrayList<>();
        List<Token> line = new ArrayList<>();
        boolean rust = keywords == RUST_KEYWORDS;
        String modeStyle = null; // the style of a construct that continues onto the next line, or null
        String close = null; // and its closing delimiter
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < n) {
            int c = cs[i];
            if (c == '\r') {
                i++;
                continue;
            }
            if (c == '\n') {
                flush(plain, line);
                lines.add(line);
                line = new ArrayList<>();
                i++;
                continue;
            }
            if (modeStyle != null) {
                // The rest of this line, or up to and including the closing delimiter if it is on this line; in
                // literals a backslash steps over the next character, unless that is the line break.
                int j = i;
                while (j < n && cs[j] != '\n' && !starts(cs, j, close)) {
                    j += modeStyle.equals("string") && cs[j] == '\\' && j + 1 < n && cs[j + 1] != '\n' ? 2 : 1;
                }
                String style = modeStyle;
                if (j < n && starts(cs, j, close)) {
                    j += close.length();
                    modeStyle = null;
                }
                line.add(new Token(text(cs, i, j), style));
                i = j;
                continue;
            }
            if (starts(cs, i, "//")) {
                flush(plain, line);
                int j = i;
                while (j < n && cs[j] != '\n') {
                    j++;
                }
                line.add(new Token(text(cs, i, j), "comment"));
                i = j;
            } else if (starts(cs, i, "/*")) {
                flush(plain, line);
                modeStyle = "comment";
                close = "*/";
                line.add(new Token("/*", "comment"));
                i += 2;
            } else if (starts(cs, i, "\"\"\"")) {
                flush(plain, line);
                modeStyle = "string";
                close = "\"\"\"";
                line.add(new Token("\"\"\"", "string"));
                i += 3;
            } else if (c == '`') {
                flush(plain, line);
                modeStyle = "string";
                close = "`";
                line.add(new Token("`", "string"));
                i++;
            } else if (rust && c == '\'' && i + 2 < n && (Character.isAlphabetic(cs[i + 1]) || cs[i + 1] == '_')
                    && cs[i + 2] != '\'') {
                // a Rust lifetime such as 'a or 'static, not a character literal
                flush(plain, line);
                int j = i + 1;
                while (ident(cs, j)) {
                    j++;
                }
                line.add(new Token(text(cs, i, j), "annotation"));
                i = j;
            } else if (c == '"' || c == '\'') {
                flush(plain, line);
                int j = i + 1;
                // Single-line literal: runs to the matching quote or the end of the line (the line break itself is
                // never taken); a backslash skips the next character so an escaped quote does not end it, unless that
                // character is the line break.
                while (j < n && cs[j] != c && cs[j] != '\n') {
                    j += cs[j] == '\\' && j + 1 < n && cs[j + 1] != '\n' ? 2 : 1;
                }
                if (j < n && cs[j] == c) {
                    j++;
                }
                line.add(new Token(text(cs, i, j), "string"));
                i = j;
            } else if ((c == '@' || c == '#') && i + 1 < n && (Character.isAlphabetic(cs[i + 1]) || cs[i + 1] == '[')) {
                // Java annotations, TypeScript decorators and #private names, Rust attributes (#[derive(..)] up to
                // the first ']' on the line, or the end of the line)
                flush(plain, line);
                int j = i + 1;
                if (cs[j] == '[') {
                    while (j < n && cs[j] != ']' && cs[j] != '\n') {
                        j++;
                    }
                    if (j < n && cs[j] == ']') {
                        j++;
                    }
                } else {
                    while (ident(cs, j) || (j < n && cs[j] == '.')) {
                        j++;
                    }
                }
                line.add(new Token(text(cs, i, j), "annotation"));
                i = j;
            } else if (Character.isAlphabetic(c) || c == '_' || c == '$') {
                int j = i;
                while (ident(cs, j)) {
                    j++;
                }
                String word = text(cs, i, j);
                if (keywords.contains(word)) {
                    flush(plain, line);
                    line.add(new Token(word, "keyword"));
                } else if (j - i > 1 && Character.isAlphabetic(c) && word.equals(word.toUpperCase(Locale.ROOT))) {
                    // MAX_SIZE is a constant; a single capital letter (T) falls through to the type rule below.
                    flush(plain, line);
                    line.add(new Token(word, "constant"));
                } else if (Character.isUpperCase(c)) {
                    flush(plain, line);
                    line.add(new Token(word, "type"));
                } else {
                    plain.append(word);
                }
                i = j;
            } else if (c >= '0' && c <= '9') {
                // Letters, '.' and '_' are included so 0x1F, 1_000, 3.5f and 10u32 stay one token.
                flush(plain, line);
                int j = i;
                while (j < n && (alphanumeric(cs[j]) || cs[j] == '.' || cs[j] == '_')) {
                    j++;
                }
                line.add(new Token(text(cs, i, j), "number"));
                i = j;
            } else {
                plain.appendCodePoint(c);
                i++;
            }
        }
        flush(plain, line);
        lines.add(line);
        return lines;
    }

    /** Whether {@code s} starts at code point {@code i} of {@code cs}. */
    private static boolean starts(int[] cs, int i, String s) {
        if (i + s.length() > cs.length) {
            return false;
        }
        for (int k = 0; k < s.length(); k++) {
            if (cs[i + k] != s.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /** Whether code point {@code i} exists and continues an identifier: alphanumeric, '_' or '$'. */
    private static boolean ident(int[] cs, int i) {
        return i < cs.length && (alphanumeric(cs[i]) || cs[i] == '_' || cs[i] == '$');
    }

    /** A letter or a number in the Unicode sense (alphabetic, or a decimal, letter or other number), as in Rust. */
    private static boolean alphanumeric(int c) {
        int type = Character.getType(c);
        return Character.isAlphabetic(c) || type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /** Code points {@code from} (inclusive) to {@code to} (exclusive) of {@code cs} as a string. */
    private static String text(int[] cs, int from, int to) {
        return new String(cs, from, to - from);
    }

    private static void flush(StringBuilder plain, List<Token> line) {
        if (!plain.isEmpty()) {
            line.add(new Token(plain.toString(), "plain"));
            plain.setLength(0);
        }
    }
}
