package dev.codecity.desktop;

import dev.codecity.CityModel;
import dev.codecity.CityModel.Building;
import dev.codecity.analysis.MethodInfo;
import dev.codecity.analysis.TypeInfo;
import dev.codecity.github.GitHubInfo;
import dev.codecity.github.PullRequest;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Right-hand panel describing the selected class. */
final class InspectorPane extends ScrollPane {

    interface Actions {
        void select(int building);

        void close();

        void openSource(Building b);

        void openLink(String url);
    }

    private final VBox content = new VBox();
    private final Actions actions;

    InspectorPane(Actions actions) {
        this.actions = actions;
        content.getStyleClass().add("inspector-content");
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().addAll("panel", "inspector");
        setPrefWidth(360);
        setMinWidth(360);
        setMaxWidth(360);
    }

    void show(CityModel model, Building b, IntPredicate visible) {
        content.getChildren().clear();
        TypeInfo t = b.type();

        Label name = new Label(t.name());
        name.getStyleClass().add("inspector-title");
        name.setWrapText(true);
        String noPackage = t.language() == dev.codecity.analysis.Language.JAVA ? "(default package)" : "(root)";
        Label pkg = new Label((t.packageName().isEmpty() ? noPackage : t.packageName())
                + (t.module().isEmpty() ? "" : " · " + t.module()));
        pkg.getStyleClass().add("mono-muted");
        pkg.setWrapText(true);
        Button close = new Button("✕");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> actions.close());
        VBox titles = new VBox(2, name, pkg);
        HBox.setHgrow(titles, Priority.ALWAYS);
        HBox head = new HBox(10, titles, close);

        Label kind = new Label(t.kind());
        kind.getStyleClass().add("badge");
        kind.setStyle("-fx-background-color: " + Look.hex(Look.kind(t.kind())) + "; -fx-text-fill: white;");
        Label language = new Label(t.language().displayName);
        language.getStyleClass().add("badge");
        HBox badges = new HBox(6, kind, language);
        if (t.test()) {
            Label test = new Label("test");
            test.getStyleClass().add("badge");
            badges.getChildren().add(test);
        }

        int[] out = filter(b.deps(), visible);
        int[] in = filter(b.incoming(), visible);
        GridPane metrics = new GridPane();
        metrics.getStyleClass().add("metrics");
        for (int c = 0; c < 3; c++) {
            javafx.scene.layout.ColumnConstraints col = new javafx.scene.layout.ColumnConstraints();
            col.setPercentWidth(100 / 3.0);
            metrics.getColumnConstraints().add(col);
        }
        int cell = 0;
        cell = metric(metrics, cell, String.valueOf(t.nom()), "methods");
        cell = metric(metrics, cell, String.valueOf(t.noa()), "attributes");
        cell = metric(metrics, cell, Look.fmt(t.loc()), "lines of code");
        cell = metric(metrics, cell, String.valueOf(t.complexity()), "complexity");
        cell = metric(metrics, cell, t.nom() == 0 ? "0" : String.format("%.1f", (double) t.complexity() / t.nom()), "cx / method");
        cell = metric(metrics, cell, out.length + " / " + in.length, "uses / used by");
        if (model.hasGit()) {
            cell = metric(metrics, cell, b.history() == null ? "0" : String.valueOf(b.history().commits()), "commits");
            cell = metric(metrics, cell, b.history() == null ? "0" : String.valueOf(b.history().authors()), "authors");
            metric(metrics, cell, b.history() == null ? "—" : Look.relTime(b.history().lastCommit()), "last change");
        }

        Label file = new Label(b.file() + ":" + t.line());
        file.getStyleClass().add("mono-muted");
        file.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        file.setTooltip(new Tooltip(b.file()));
        HBox.setHgrow(file, Priority.ALWAYS);
        file.setMaxWidth(Double.MAX_VALUE);
        Button source = new Button("Source");
        source.setMinWidth(Region.USE_PREF_SIZE);
        file.setMinWidth(0);
        source.setOnAction(e -> actions.openSource(b));
        HBox fileRow = new HBox(8, file, source);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        fileRow.getStyleClass().add("file-row");

        content.getChildren().addAll(head, badges, metrics, fileRow);
        gitHub(model.github(), b);

        if (!b.methods().isEmpty()) {
            content.getChildren().add(section("Methods by complexity", t.methods().size()));
            int maxCx = Math.max(1, b.methods().stream().mapToInt(MethodInfo::complexity).max().orElse(1));
            VBox methods = new VBox(4);
            b.methods().stream().limit(15).forEach(m -> methods.getChildren().add(methodRow(m, maxCx)));
            if (b.methods().size() > 15) {
                Label more = new Label("+ " + (b.methods().size() - 15) + " more");
                more.getStyleClass().add("muted");
                methods.getChildren().add(more);
            }
            content.getChildren().add(methods);
        }

        content.getChildren().addAll(section("Uses", out.length), deps(model, out, "out", "nothing in this project"));
        content.getChildren().addAll(section("Used by", in.length), deps(model, in, "in", "no project types"));
        setVvalue(0);
        setVisible(true);
        setManaged(true);
    }

    void hide() {
        setVisible(false);
        setManaged(false);
    }

    private void gitHub(GitHubInfo gh, Building b) {
        if (gh == null) {
            return;
        }
        HBox links = new HBox(6,
                linkButton("File on GitHub", link(gh, "blob", b)),
                linkButton("Blame", link(gh, "blame", b)),
                linkButton("History", link(gh, "commits", b)));
        links.getStyleClass().add("gh-links");
        content.getChildren().add(links);
        if (gh.pulls() == null) {
            return;
        }
        content.getChildren().add(section("Open pull requests", b.prs().length));
        VBox prs = new VBox(4);
        for (int k : b.prs()) {
            PullRequest pr = gh.pulls().get(k);
            Label number = new Label("#" + pr.number());
            number.getStyleClass().add("pr-number");
            Label title = new Label(pr.title());
            title.getStyleClass().add("pr-title");
            String updated = pr.updatedAt() == null ? "" : " · " + Look.relTime(Instant.parse(pr.updatedAt()).getEpochSecond());
            Label meta = new Label((pr.author() == null ? "ghost" : pr.author()) + updated + " · "
                    + pr.files().size() + " files" + (pr.draft() ? " · draft" : ""));
            meta.getStyleClass().add("muted");
            VBox text = new VBox(1, title, meta);
            HBox row = new HBox(8, number, text);
            row.getStyleClass().add("pr");
            row.setOnMouseClicked(e -> actions.openLink(pr.url()));
            Tooltip.install(row, new Tooltip(pr.url()));
            prs.getChildren().add(row);
        }
        if (b.prs().length == 0) {
            String source = gh.pullsSource() == null ? "" : gh.pullsSource();
            Label none = new Label(source.startsWith("all") ? "none" : "none among the " + source);
            none.getStyleClass().add("muted");
            prs.getChildren().add(none);
        }
        content.getChildren().add(prs);
    }

    /** github.com/owner/repo/{blob|blame|commits}/ref/path#Lx-Ly */
    static String link(GitHubInfo gh, String kind, Building b) {
        String path = encodePath(gh.prefix() + b.file());
        String url = gh.repo().webUrl() + "/" + kind + "/" + encodePath(gh.ref()) + "/" + path;
        return kind.equals("commits") ? url : url + "#L" + b.type().line() + "-L" + b.type().endLine();
    }

    private static String encodePath(String path) {
        return Stream.of(path.split("/"))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private Hyperlink linkButton(String text, String url) {
        Hyperlink h = new Hyperlink(text);
        h.getStyleClass().add("gh-link");
        h.setTooltip(new Tooltip(url));
        h.setOnAction(e -> actions.openLink(url));
        h.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(h, Priority.ALWAYS);
        return h;
    }

    private static int[] filter(int[] indices, IntPredicate visible) {
        return java.util.Arrays.stream(indices).filter(visible).toArray();
    }

    private static int metric(GridPane grid, int cell, String value, String label) {
        Label v = new Label(value);
        v.getStyleClass().add("metric-value");
        Label l = new Label(label);
        l.getStyleClass().add("metric-label");
        v.setMinWidth(0);
        l.setMinWidth(0);
        VBox box = new VBox(1, v, l);
        box.getStyleClass().add("metric");
        box.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(box, Priority.ALWAYS);
        grid.add(box, cell % 3, cell / 3);
        return cell + 1;
    }

    private static Node section(String title, int count) {
        Label t = new Label(title.toUpperCase());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label c = new Label(String.valueOf(count));
        HBox row = new HBox(t, spacer, c);
        row.getStyleClass().add("section");
        return row;
    }

    private static Node methodRow(MethodInfo m, int maxCx) {
        Label name = new Label(m.name() + (m.parameters() > 0 ? "(…)" : "()"));
        name.getStyleClass().add("method-name");
        name.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label value = new Label("cx " + m.complexity() + " · " + m.loc() + " loc");
        value.getStyleClass().add("muted");
        value.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = new HBox(8, name, spacer, value);
        // Bars are regions sized relative to the row, so the panel never needs a fixed minimum width.
        Region bar = new Region();
        bar.setStyle("-fx-background-radius: 2; -fx-background-color: "
                + Look.hex(Look.ramp(m.complexity() / (double) Math.max(10, maxCx))) + ";");
        bar.setMinWidth(0);
        StackPane bars = new StackPane(bar);
        bars.getStyleClass().add("bar-track");
        bars.setMinSize(0, 3);
        bars.setPrefHeight(3);
        bars.setMaxHeight(3);
        bars.setAlignment(Pos.CENTER_LEFT);
        bar.maxWidthProperty().bind(bars.widthProperty().multiply((double) m.complexity() / maxCx));
        VBox row = new VBox(2, top, bars);
        Tooltip.install(row, new Tooltip("line " + m.line() + " · " + m.loc() + " LOC · " + m.parameters() + " params"));
        return row;
    }

    private Node deps(CityModel model, int[] indices, String direction, String empty) {
        FlowPane pane = new FlowPane(4, 4);
        pane.setPrefWrapLength(300);
        for (int j : indices) {
            Building d = model.buildings().get(j);
            Label chip = new Label(d.type().name());
            chip.getStyleClass().addAll("dep", direction);
            chip.setMaxWidth(300); // very long nested names would otherwise widen the whole panel
            chip.setTooltip(new Tooltip(d.type().fqn()));
            chip.setOnMouseClicked(e -> actions.select(j));
            pane.getChildren().add(chip);
        }
        if (indices.length == 0) {
            Label none = new Label(empty);
            none.getStyleClass().add("muted");
            pane.getChildren().add(none);
        }
        return pane;
    }
}
