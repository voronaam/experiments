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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A native source window with lightweight Java highlighting; the selected type's lines are marked. */
final class SourceViewer {

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "yield", "sealed",
            "permits", "non-sealed", "true", "false", "null");

    private SourceViewer() {
    }

    private record Token(String text, String style) {
    }

    static void show(Window owner, Building b, String stylesheet) {
        String code;
        try {
            code = Files.size(b.type().file()) > 4L * 1024 * 1024 ? "// file too large to display"
                    : Files.readString(b.type().file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            code = "// unable to read " + b.type().file() + ": " + e.getMessage();
        }
        List<List<Token>> lines = highlight(code);
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
        List<List<Token>> lines = new ArrayList<>();
        List<Token> line = new ArrayList<>();
        String mode = null; // "comment" or "string" for constructs that continue onto the next line
        int i = 0;
        int n = code.length();
        StringBuilder plain = new StringBuilder();
        while (i < n) {
            char c = code.charAt(i);
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
            if (mode != null) {
                String close = mode.equals("comment") ? "*/" : "\"\"\"";
                int end = code.indexOf(close, i);
                int nl = code.indexOf('\n', i);
                if (end >= 0 && (nl < 0 || end < nl)) {
                    line.add(new Token(code.substring(i, end + close.length()), mode));
                    i = end + close.length();
                    mode = null;
                } else {
                    int stop = nl < 0 ? n : nl;
                    line.add(new Token(code.substring(i, stop), mode));
                    i = stop;
                }
                continue;
            }
            if (code.startsWith("//", i)) {
                flush(plain, line);
                int nl = code.indexOf('\n', i);
                int stop = nl < 0 ? n : nl;
                line.add(new Token(code.substring(i, stop), "comment"));
                i = stop;
            } else if (code.startsWith("/*", i)) {
                flush(plain, line);
                mode = "comment";
                line.add(new Token("/*", "comment"));
                i += 2;
            } else if (code.startsWith("\"\"\"", i)) {
                flush(plain, line);
                mode = "string";
                line.add(new Token("\"\"\"", "string"));
                i += 3;
            } else if (c == '"' || c == '\'') {
                flush(plain, line);
                int j = i + 1;
                while (j < n && code.charAt(j) != c && code.charAt(j) != '\n') {
                    j += code.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + 1);
                line.add(new Token(code.substring(i, j), "string"));
                i = j;
            } else if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(code.charAt(i + 1))) {
                flush(plain, line);
                int j = i + 1;
                while (j < n && (Character.isJavaIdentifierPart(code.charAt(j)) || code.charAt(j) == '.')) {
                    j++;
                }
                line.add(new Token(code.substring(i, j), "annotation"));
                i = j;
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(code.charAt(j))) {
                    j++;
                }
                String word = code.substring(i, j);
                if (KEYWORDS.contains(word)) {
                    flush(plain, line);
                    line.add(new Token(word, "keyword"));
                } else if (word.length() > 1 && word.equals(word.toUpperCase()) && Character.isLetter(word.charAt(0))) {
                    flush(plain, line);
                    line.add(new Token(word, "constant"));
                } else if (Character.isUpperCase(word.charAt(0))) {
                    flush(plain, line);
                    line.add(new Token(word, "type"));
                } else {
                    plain.append(word);
                }
                i = j;
            } else if (Character.isDigit(c)) {
                flush(plain, line);
                int j = i;
                while (j < n && (Character.isLetterOrDigit(code.charAt(j)) || code.charAt(j) == '.' || code.charAt(j) == '_')) {
                    j++;
                }
                line.add(new Token(code.substring(i, j), "number"));
                i = j;
            } else {
                plain.append(c);
                i++;
            }
        }
        flush(plain, line);
        lines.add(line);
        return lines;
    }

    private static void flush(StringBuilder plain, List<Token> line) {
        if (!plain.isEmpty()) {
            line.add(new Token(plain.toString(), "plain"));
            plain.setLength(0);
        }
    }
}
