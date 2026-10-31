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

import java.util.List;
import java.util.function.IntPredicate;

/**
 * Right-hand panel describing the selected class: name and package, kind/language badges, a grid of metrics, file
 * location with a Source button, GitHub links and open PRs, the most complex methods, and clickable chips for the types
 * it uses and is used by. The content is rebuilt from scratch on every {@link #show}; the panel holds no state of its
 * own and reports user actions through {@link Actions}. Mirrors the web viewer's inspector.
 */
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

    /** Which replay snapshot to describe: its index and its date as shown ({@code YYYY-MM-DD}). */
    record ReplayAt(int snapshot, String date) {
    }

    /** {@link #show(CityModel, Building, IntPredicate, ReplayAt)} outside replay. */
    void show(CityModel model, Building b, IntPredicate visible) {
        show(model, b, visible, null);
    }

    /**
     * Fills the panel for {@code b} and makes it visible. Dependency lists and counts only include buildings that pass
     * {@code visible}, so they agree with the arcs drawn in the city when test code is hidden. Git metrics are shown
     * only when the model has git history. During a history replay ({@code replay} non-null) the present-day details
     * stay, followed by the replayed snapshot's four metrics; a type that no longer exists says so.
     */
    void show(CityModel model, Building b, IntPredicate visible, ReplayAt replay) {
        content.getChildren().clear();
        TypeInfo t = b.type();

        Label name = new Label(t.name());
        name.getStyleClass().add("inspector-title");
        name.setWrapText(true);
        Label pkg = new Label(ViewRules.packageLine(t));
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
        for (String[] c : ViewRules.metricCells(b, model.hasGit(), out.length, in.length, System.currentTimeMillis() / 1000)) {
            cell = metric(metrics, cell, c[0], c[1]);
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

        content.getChildren().addAll(head, badges, metrics);
        if (b.gone()) {
            content.getChildren().add(styled(new Label(ViewRules.REMOVED), "replay-note"));
        }
        if (replay != null && b.hist() != null) {
            List<String[]> cells = ViewRules.replayCells(b, replay.snapshot());
            content.getChildren().addAll(styled(new Label(ViewRules.replayHeading(replay.date())), "replay-note"),
                    cells == null ? styled(new Label(ViewRules.NOT_IN_SNAPSHOT), "muted") : replayMetrics(cells));
        }
        content.getChildren().add(fileRow);
        gitHub(model.github(), b);

        if (!b.methods().isEmpty()) {
            content.getChildren().add(section("Methods by complexity", t.methods().size()));
            int maxCx = Math.max(1, b.methods().stream().mapToInt(MethodInfo::complexity).max().orElse(1));
            VBox methods = new VBox(4);
            b.methods().stream().limit(ViewRules.INSPECTOR_METHODS).forEach(m -> methods.getChildren().add(methodRow(m, maxCx)));
            // Counted on all of the type's methods like the header; b.methods() is capped at 300.
            String moreText = ViewRules.moreMethods(t.methods().size());
            if (moreText != null) {
                Label more = new Label(moreText);
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

    /** Hides the panel and, by unmanaging it, gives its width back to the layout. */
    void hide() {
        setVisible(false);
        setManaged(false);
    }

    /**
     * GitHub links for the file and, when pull requests were fetched, the open PRs touching it ({@code b.prs()} holds
     * indices into {@code gh.pulls()}). When none touch it and the PR list was incomplete, the message names what was
     * searched ("none among the latest 30 open PRs") instead of claiming there are none.
     */
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
            Label meta = new Label(ViewRules.prMeta(pr, System.currentTimeMillis() / 1000));
            meta.getStyleClass().add("muted");
            VBox text = new VBox(1, title, meta);
            HBox row = new HBox(8, number, text);
            row.getStyleClass().add("pr");
            row.setOnMouseClicked(e -> actions.openLink(pr.url()));
            Tooltip.install(row, new Tooltip(pr.url()));
            prs.getChildren().add(row);
        }
        if (b.prs().length == 0) {
            Label none = new Label(ViewRules.noPulls(gh.pullsSource()));
            none.getStyleClass().add("muted");
            prs.getChildren().add(none);
        }
        content.getChildren().add(prs);
    }

    /**
     * github.com/owner/repo/{blob|blame|commits}/ref/path#Lx-Ly ({@link GitHubInfo#link}).
     */
    static String link(GitHubInfo gh, String kind, Building b) {
        return gh.link(kind, b.file(), b.type().line(), b.type().endLine());
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

    /** The replayed snapshot's tiles ({@link ViewRules#replayCells}) in one row of four. */
    private static GridPane replayMetrics(List<String[]> cells) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("metrics");
        for (int c = 0; c < 4; c++) {
            javafx.scene.layout.ColumnConstraints col = new javafx.scene.layout.ColumnConstraints();
            col.setPercentWidth(25);
            grid.getColumnConstraints().add(col);
        }
        for (int i = 0; i < cells.size(); i++) {
            metric(grid, 0, cells.get(i)[0], cells.get(i)[1]);
            // metric() places by a 3-column grid; move the box into column i of the single row.
            Node box = grid.getChildren().getLast();
            GridPane.setConstraints(box, i, 0);
        }
        return grid;
    }

    private static <T extends Node> T styled(T node, String style) {
        node.getStyleClass().add(style);
        return node;
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

    /**
     * One method: name, complexity and size, and a bar. The bar's length is relative to the most complex method of
     * this type; its colour is on an absolute scale where complexity 10 and above is the hot end of the ramp (unless
     * the type's maximum is higher, then that is the hot end).
     */
    private static Node methodRow(MethodInfo m, int maxCx) {
        Label name = new Label(ViewRules.methodName(m.name(), m.parameters()));
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

    /** Chips for dependency targets; {@code direction} ("out"/"in") is a style class, colouring them like the arcs. */
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
