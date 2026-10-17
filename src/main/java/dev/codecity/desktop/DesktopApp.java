package dev.codecity.desktop;

import dev.codecity.CityModel;
import dev.codecity.CityModel.Building;
import dev.codecity.layout.CityLayout;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/** Native desktop viewer: the same city as the web viewer, rendered with JavaFX 3D in an application window. */
public final class DesktopApp extends Application {

    /** Held strongly so the level set in {@link #launch} sticks. */
    private static final java.util.logging.Logger JAVAFX_LOGGER = java.util.logging.Logger.getLogger("javafx");
    private static volatile CityModel initialModel;
    private static volatile DesktopApp instance;

    /** Opens the window and blocks until it is closed. */
    public static void launch(CityModel model) {
        initialModel = model;
        // Running from a plain (non-modular) jar is fine for us; hide JavaFX's warning about it.
        JAVAFX_LOGGER.setLevel(java.util.logging.Level.SEVERE);
        Application.launch(DesktopApp.class);
    }

    /** Pushes a rebuilt model into the open window (watch mode). Safe to call from any thread. */
    public static void update(CityModel model) {
        DesktopApp app = instance;
        if (app != null) {
            Platform.runLater(() -> app.setModel(model, true));
        }
    }

    /** True when a graphical display is likely available. */
    public static boolean displayAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux") || os.contains("bsd")) {
            return System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
        }
        return true;
    }

    private final Preferences prefs = Preferences.userNodeForPackage(DesktopApp.class);
    private final String stylesheet = DesktopApp.class.getResource("/desktop/codecity.css").toExternalForm();

    private CityModel model;
    private CityView3D city;
    private Stage stage;
    private StackPane root;
    private InspectorPane inspector;
    private Look.HeightMetric heightMetric;
    private Look.ColorMetric colorMetric;
    private Look.Theme theme;
    private boolean showTests;
    private int selected = -1;
    private int hovered = -1;
    private BitSet matches;
    private List<Integer> results = List.of();
    private boolean swallowSlash;

    private final Label projectName = new Label();
    private final HBox stats = new HBox(6);
    private final ComboBox<Look.HeightMetric> heightBox = new ComboBox<>();
    private final ComboBox<Look.ColorMetric> colorBox = new ComboBox<>();
    private final VBox legend = new VBox(3);
    private final CheckBox testsBox = new CheckBox("Test classes");
    private final CheckBox labelsBox = new CheckBox("District labels");
    private final CheckBox beaconsBox = new CheckBox("Open-PR beacons");
    private final Button themeButton = new Button();
    private final TextField search = new TextField();
    private final ListView<Integer> resultList = new ListView<>();
    private final Popup resultPopup = new Popup();
    private final Label tooltip = new Label();
    private final Label toast = new Label();
    private final PauseTransition toastTimer = new PauseTransition(Duration.seconds(2.6));

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        if (!Platform.isSupported(ConditionalFeature.SCENE3D)) {
            showNo3D(stage);
            return;
        }
        heightMetric = enumPref("height", Look.HeightMetric.NOM);
        colorMetric = enumPref("color", null);
        theme = enumPref("theme", Look.Theme.DAY);
        showTests = prefs.getBoolean("tests", true);

        Pane labelLayer = new Pane();
        labelLayer.setMouseTransparent(true);
        city = new CityView3D(labelLayer, new CityView3D.Listener() {
            @Override
            public void hover(int building, int district, double x, double y) {
                onHover(building, district, x, y);
            }

            @Override
            public void click(int building) {
                select(building, true);
            }

            @Override
            public void districtDoubleClick(int district) {
                city.flyToDistrict(model.districts().get(district));
            }
        });
        Pane viewport = new Pane(city.subScene);
        city.subScene.widthProperty().bind(viewport.widthProperty());
        city.subScene.heightProperty().bind(viewport.heightProperty());

        inspector = new InspectorPane(new InspectorPane.Actions() {
            @Override
            public void select(int building) {
                DesktopApp.this.select(building, true);
            }

            @Override
            public void close() {
                DesktopApp.this.select(-1, false);
            }

            @Override
            public void openSource(Building b) {
                SourceViewer.show(stage, b, stylesheet);
            }

            @Override
            public void openLink(String url) {
                getHostServices().showDocument(url);
            }
        });
        inspector.hide();

        BorderPane hud = new BorderPane();
        hud.setPickOnBounds(false);
        hud.setPadding(new Insets(16));
        Node top = topBar();
        hud.setTop(top);
        BorderPane.setMargin(top, new Insets(0, 0, 12, 0));
        VBox controls = controls();
        hud.setLeft(controls);
        BorderPane.setAlignment(controls, Pos.TOP_LEFT);
        hud.setRight(inspector);
        Label hint = new Label("drag to orbit · right-drag to pan · scroll to zoom · click a building · / search · F frame · N night · Esc clear");
        hint.getStyleClass().addAll("panel", "hint");
        hud.setBottom(hint);
        BorderPane.setAlignment(hint, Pos.CENTER);
        BorderPane.setMargin(hint, new Insets(12, 0, 0, 0));

        Pane tooltipLayer = new Pane(tooltip);
        tooltipLayer.setMouseTransparent(true);
        tooltip.getStyleClass().add("tooltip-label");
        tooltip.setManaged(false);
        tooltip.setVisible(false);
        toast.getStyleClass().addAll("panel", "toast");
        toast.setVisible(false);
        toast.setMouseTransparent(true);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 64, 0));
        toastTimer.setOnFinished(e -> toast.setVisible(false));

        root = new StackPane(viewport, labelLayer, hud, tooltipLayer, toast);
        root.getStyleClass().add("app");
        Scene scene = new Scene(root, 1440, 900);
        scene.getStylesheets().add(stylesheet);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // The '/' that focuses the search field must not also be typed into it.
        scene.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (swallowSlash && "/".equals(e.getCharacter())) {
                e.consume();
            }
            swallowSlash = false;
        });

        stage.setScene(scene);
        stage.getIcons().add(icon());
        setModel(initialModel, false);
        stage.show();
        city.subScene.requestFocus(); // otherwise the search field grabs focus and swallows the shortcuts
        instance = this;
    }

    // ------------------------------------------------------------------ model & state

    private void setModel(CityModel next, boolean live) {
        String previous = selected >= 0 && model != null ? model.buildings().get(selected).type().fqn() : null;
        model = next;
        selected = -1;
        hovered = -1;
        stage.setTitle(next.name() + " · CodeCity");
        projectName.setText(next.name());
        if (colorMetric == null || !colorMetric.available(next)) {
            colorMetric = next.hasGit() ? Look.ColorMetric.COMMITS : Look.ColorMetric.CX;
        }
        heightBox.getSelectionModel().select(heightMetric);
        colorBox.getSelectionModel().select(colorMetric);
        boolean hasPulls = next.github() != null && next.github().pulls() != null;
        beaconsBox.setVisible(hasPulls);
        beaconsBox.setManaged(hasPulls);
        renderStats();
        city.setModel(next, live);
        city.setBeaconsVisible(beaconsBox.isSelected());
        applyTheme();
        reheight(!live);
        recolor();
        if (previous != null) {
            next.buildings().stream().filter(b -> b.type().fqn().equals(previous)).findFirst()
                    .ifPresent(b -> select(b.index(), false));
        }
        if (!search.getText().isBlank()) {
            runSearch(search.getText());
        }
        if (live) {
            toast("City rebuilt · " + Look.fmt(next.buildings().size()) + " classes");
        } else if (next.buildings().isEmpty()) {
            toast("No Java types found in this source tree");
        }
    }

    private boolean visible(int i) {
        return showTests || !model.buildings().get(i).type().test();
    }

    private void reheight(boolean rise) {
        List<Building> buildings = model.buildings();
        double[] values = buildings.stream().filter(b -> visible(b.index()))
                .mapToDouble(heightMetric.value).sorted().toArray();
        double p95 = Math.max(1, values.length == 0 ? 1 : values[Math.min(values.length - 1, (int) (0.95 * (values.length - 1)))]);
        double r = city.radius();
        double scale = Math.min(16, Math.max(8, r * 0.2)) / p95;
        double[] heights = new double[buildings.size()];
        for (Building b : buildings) {
            heights[b.index()] = visible(b.index())
                    ? Math.min(0.35 + heightMetric.value.applyAsDouble(b) * scale, r * 0.75) : 0;
        }
        city.setHeights(heights, rise);
        if (selected >= 0) {
            city.showArcs(selected, this::visible);
        }
    }

    private void recolor() {
        List<Building> buildings = model.buildings();
        Look.Scale scale = Look.Scale.of(colorMetric, buildings);
        BitSet related = new BitSet();
        if (selected >= 0) {
            Building s = buildings.get(selected);
            related.set(selected);
            Arrays.stream(s.deps()).forEach(related::set);
            Arrays.stream(s.incoming()).forEach(related::set);
        }
        Color[] colors = new Color[buildings.size()];
        for (Building b : buildings) {
            int i = b.index();
            Color c = scale.color(b);
            boolean outOfSearch = matches != null && !matches.get(i);
            boolean outOfFocus = selected >= 0 && !related.get(i);
            if (outOfSearch || outOfFocus) {
                c = c.interpolate(theme.dim, 0.8);
            }
            if (i == hovered && i != selected) {
                c = c.interpolate(Color.WHITE, 0.3);
            }
            colors[i] = c;
        }
        city.setColors(colors);
        renderLegend(scale);
    }

    private void select(int i, boolean fly) {
        selected = i;
        if (i < 0) {
            inspector.hide();
        } else {
            inspector.show(model, model.buildings().get(i), this::visible);
        }
        city.showArcs(i, this::visible);
        recolor();
        if (i >= 0 && fly) {
            city.flyToBuilding(i);
        }
    }

    private void applyTheme() {
        root.getStyleClass().removeAll("day", "night");
        root.getStyleClass().add(theme.css);
        // The search popup is a separate window, so it carries the theme classes itself.
        resultList.getStyleClass().removeAll("app", "day", "night");
        resultList.getStyleClass().addAll("app", theme.css);
        themeButton.setText(theme == Look.Theme.DAY ? "Night" : "Day");
        city.applyTheme(theme);
    }

    private void toggleTheme() {
        theme = theme.other();
        prefs.put("theme", theme.name());
        applyTheme();
        recolor();
        if (selected >= 0) {
            city.showArcs(selected, this::visible);
        }
    }

    // ------------------------------------------------------------------ HUD

    private Node topBar() {
        HBox brand = new HBox(10, logo(), new VBox(0, styled(new Label("CodeCity"), "brand-name"), styled(projectName, "project")));
        brand.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("Search classes…   ( / )");
        search.getStyleClass().add("search");
        search.setPrefWidth(440);
        search.textProperty().addListener((o, a, b) -> runSearch(b));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !results.isEmpty()) {
                select(results.getFirst(), true);
                resultPopup.hide();
                // Closing the popup hands focus back to the field; take it after that so shortcuts work.
                Platform.runLater(city.subScene::requestFocus);
            } else if (e.getCode() == KeyCode.DOWN && resultPopup.isShowing()) {
                resultList.requestFocus();
                resultList.getSelectionModel().selectFirst();
            }
        });
        resultList.getStyleClass().add("results");
        resultList.setPrefWidth(440);
        resultList.setPrefHeight(300);
        resultList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Integer i, boolean empty) {
                super.updateItem(i, empty);
                if (empty || i == null) {
                    setGraphic(null);
                    return;
                }
                Building b = model.buildings().get(i);
                setGraphic(new VBox(styled(new Label(b.type().name()), "result-name"),
                        styled(new Label(b.type().packageName()), "mono-muted")));
            }
        });
        resultList.setOnMouseClicked(e -> pickResult());
        resultList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                pickResult();
            }
        });
        resultPopup.getContent().add(resultList);
        resultPopup.setAutoHide(true);
        resultList.getStylesheets().add(stylesheet);

        Region left = new Region();
        Region right = new Region();
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox bar = new HBox(20, brand, left, search, right, stats);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().addAll("panel", "topbar");
        return bar;
    }

    private void pickResult() {
        Integer i = resultList.getSelectionModel().getSelectedItem();
        if (i != null) {
            select(i, true);
            resultPopup.hide();
            Platform.runLater(city.subScene::requestFocus);
        }
    }

    private VBox controls() {
        heightBox.getItems().setAll(Look.HeightMetric.values());
        heightBox.setMaxWidth(Double.MAX_VALUE);
        heightBox.setOnAction(e -> {
            Look.HeightMetric m = heightBox.getValue();
            if (m != null && m != heightMetric) {
                heightMetric = m;
                prefs.put("height", m.name());
                reheight(false);
            }
        });
        colorBox.getItems().setAll(Look.ColorMetric.values());
        colorBox.setMaxWidth(Double.MAX_VALUE);
        colorBox.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Look.ColorMetric m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) {
                    setText(null);
                    setDisable(false);
                    return;
                }
                boolean ok = model == null || m.available(model);
                setText(m.label + (ok ? "" : m.unavailableReason()));
                setDisable(!ok);
            }
        });
        colorBox.setOnAction(e -> {
            Look.ColorMetric m = colorBox.getValue();
            if (m == null || m == colorMetric) {
                return;
            }
            if (!m.available(model)) {
                colorBox.getSelectionModel().select(colorMetric);
                return;
            }
            colorMetric = m;
            prefs.put("color", m.name());
            recolor();
        });

        testsBox.setSelected(showTests);
        testsBox.setOnAction(e -> {
            showTests = testsBox.isSelected();
            prefs.putBoolean("tests", showTests);
            if (selected >= 0 && !visible(selected)) {
                select(-1, false);
            }
            reheight(false);
            recolor();
            if (selected >= 0) {
                inspector.show(model, model.buildings().get(selected), this::visible);
            }
            if (!search.getText().isBlank()) {
                runSearch(search.getText());
            }
        });
        labelsBox.setSelected(prefs.getBoolean("labels", true));
        city.setLabelsVisible(labelsBox.isSelected());
        labelsBox.setOnAction(e -> {
            prefs.putBoolean("labels", labelsBox.isSelected());
            city.setLabelsVisible(labelsBox.isSelected());
        });
        beaconsBox.setSelected(prefs.getBoolean("beacons", true));
        beaconsBox.setOnAction(e -> {
            prefs.putBoolean("beacons", beaconsBox.isSelected());
            city.setBeaconsVisible(beaconsBox.isSelected());
        });

        themeButton.setOnAction(e -> toggleTheme());
        Button frame = new Button("Frame");
        frame.setOnAction(e -> city.frameAll(true));
        Button snapshot = new Button("Snapshot");
        snapshot.setOnAction(e -> snapshot());
        HBox buttons = new HBox(6, themeButton, frame, snapshot);
        for (Node b : buttons.getChildren()) {
            HBox.setHgrow(b, Priority.ALWAYS);
            ((Button) b).setMaxWidth(Double.MAX_VALUE);
        }

        VBox box = new VBox(6,
                styled(new Label("HEIGHT"), "caption"), heightBox,
                spacer(8), styled(new Label("COLOUR"), "caption"), colorBox, legend,
                spacer(8), testsBox, labelsBox, beaconsBox,
                spacer(8), buttons);
        box.getStyleClass().addAll("panel", "controls");
        box.setPrefWidth(236);
        box.setMaxWidth(236);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        return box;
    }

    private void renderStats() {
        CityModel.Stats s = model.stats();
        stats.getChildren().clear();
        if (model.github() != null) {
            var gh = model.github();
            String prs = gh.pulls() == null ? ""
                    : " · " + gh.pulls().size() + (gh.pullsSource() != null && gh.pullsSource().startsWith("all") ? "" : "+") + " PRs";
            Hyperlink link = new Hyperlink(gh.repo().owner() + "/" + gh.repo().name() + prs);
            link.getStyleClass().addAll("chip", "gh-chip");
            link.setOnAction(e -> getHostServices().showDocument(gh.repo().webUrl()));
            stats.getChildren().add(link);
        }
        stats.getChildren().addAll(chip(s.types(), "classes"), chip(s.packages(), "packages"),
                chip(s.methods(), "methods"), chip(s.loc(), "LOC"));
        if (s.failedFiles() > 0) {
            Node warn = chip(s.failedFiles(), "parse errors");
            warn.getStyleClass().add("warn");
            stats.getChildren().add(warn);
        }
        stats.setAlignment(Pos.CENTER_RIGHT);
    }

    private static Node chip(long value, String label) {
        Label v = styled(new Label(Look.fmt(value)), "chip-value");
        Label l = styled(new Label(label), "chip-label");
        HBox chip = new HBox(5, v, l);
        chip.setAlignment(Pos.BASELINE_LEFT);
        chip.getStyleClass().add("chip");
        return chip;
    }

    private void renderLegend(Look.Scale scale) {
        legend.getChildren().clear();
        Look.ColorMetric m = scale.metric();
        if (m == Look.ColorMetric.KIND) {
            FlowPane swatches = new FlowPane(10, 4);
            Look.KINDS.forEach((kind, color) -> {
                Rectangle sw = new Rectangle(10, 10, color);
                sw.setArcWidth(4);
                sw.setArcHeight(4);
                Label l = styled(new Label(kind), "legend-text");
                swatches.getChildren().add(new HBox(6, sw, l));
            });
            legend.getChildren().add(swatches);
            return;
        }
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < Look.RAMP.length; i++) {
            stops.add(new Stop((double) i / (Look.RAMP.length - 1), Look.RAMP[i]));
        }
        Rectangle bar = new Rectangle(204, 8, new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, stops));
        bar.setArcWidth(8);
        bar.setArcHeight(8);
        String lo = show(m, scale.lo());
        String hi = show(m, scale.hi()) + "+";
        legend.getChildren().addAll(bar,
                ends(m.invert ? hi : lo, m.invert ? lo : hi),
                ends(m.lo, m.hi));
    }

    private static String show(Look.ColorMetric m, double v) {
        if (m == Look.ColorMetric.AGE) {
            return Math.round(v) + "d ago";
        }
        return v == Math.rint(v) ? Look.fmt(v) : String.format("%.1f", v);
    }

    private static Node ends(String left, String right) {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return new HBox(styled(new Label(left), "legend-text"), gap, styled(new Label(right), "legend-text"));
    }

    // ------------------------------------------------------------------ interaction

    private void onHover(int building, int district, double x, double y) {
        if (building != hovered) {
            hovered = building;
            recolor();
            city.subScene.setCursor(building >= 0 ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
        }
        String text = null;
        if (building >= 0) {
            Building b = model.buildings().get(building);
            var t = b.type();
            StringBuilder sb = new StringBuilder(t.name()).append("  ·  ").append(t.kind()).append('\n')
                    .append(t.packageName()).append('\n')
                    .append(t.nom()).append(" methods · ").append(t.noa()).append(" attrs · ")
                    .append(Look.fmt(t.loc())).append(" loc · cx ").append(t.complexity());
            if (b.history() != null) {
                sb.append(" · ").append(b.history().commits()).append(" commits");
            }
            if (b.prs().length > 0) {
                sb.append(" · ").append(b.prs().length).append(" open PR").append(b.prs().length > 1 ? "s" : "");
            }
            text = sb.toString();
        } else if (district >= 0) {
            CityLayout.District d = model.districts().get(district);
            if (d.depth() > 0) {
                text = d.label() + "\n" + d.id().replaceFirst("^module:", "") + "\n" + d.types() + " types · double-click to zoom";
            }
        }
        if (text == null) {
            tooltip.setVisible(false);
            return;
        }
        tooltip.setText(text);
        tooltip.setVisible(true);
        tooltip.autosize();
        double w = tooltip.getWidth();
        double h = tooltip.getHeight();
        tooltip.relocate(Math.min(x + 14, root.getWidth() - w - 8), Math.min(y + 14, root.getHeight() - h - 8));
    }

    private void runSearch(String text) {
        String q = text.strip().toLowerCase(Locale.ROOT);
        if (q.length() < 2) {
            matches = null;
            results = List.of();
            resultPopup.hide();
            recolor();
            return;
        }
        record Scored(int i, int score, int len) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Building b : model.buildings()) {
            if (!visible(b.index())) {
                continue;
            }
            String name = b.type().name().toLowerCase(Locale.ROOT);
            int score = name.equals(q) ? 0 : name.startsWith(q) ? 1 : name.contains(q) ? 2
                    : b.type().fqn().toLowerCase(Locale.ROOT).contains(q) ? 3 : -1;
            if (score >= 0) {
                scored.add(new Scored(b.index(), score, name.length()));
            }
        }
        scored.sort((a, b) -> a.score() != b.score() ? Integer.compare(a.score(), b.score()) : Integer.compare(a.len(), b.len()));
        matches = new BitSet();
        scored.forEach(s -> matches.set(s.i()));
        results = scored.stream().limit(12).map(Scored::i).toList();
        resultList.getItems().setAll(results);
        resultList.setPrefHeight(Math.min(300, results.size() * 44 + 12));
        if (!results.isEmpty() && search.isFocused() && stage.isShowing()) {
            var p = search.localToScreen(0, search.getHeight() + 6);
            if (p != null) {
                resultPopup.show(stage, p.getX(), p.getY());
            }
        } else {
            resultPopup.hide();
        }
        recolor();
    }

    private void onKey(KeyEvent e) {
        boolean typing = e.getTarget() instanceof TextInputControl;
        if (e.getCode() == KeyCode.ESCAPE) {
            if (resultPopup.isShowing()) {
                resultPopup.hide();
            } else if (selected >= 0) {
                select(-1, false);
            } else if (!search.getText().isEmpty()) {
                search.clear();
            }
            city.subScene.requestFocus();
            e.consume();
            return;
        }
        if (typing || e.isShortcutDown() || e.isAltDown()) {
            return;
        }
        if (e.getCode() == KeyCode.SLASH || "/".equals(e.getText())) {
            swallowSlash = true;
            search.requestFocus();
            search.selectAll();
            e.consume();
        } else if (e.getCode() == KeyCode.F) {
            city.frameAll(true);
        } else if (e.getCode() == KeyCode.N) {
            toggleTheme();
        }
    }

    private void snapshot() {
        WritableImage image = city.subScene.snapshot(new SnapshotParameters(), null);
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(model.name() + "-codecity.png");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            writePng(image, file);
            toast("Saved " + file.getName());
        } catch (IOException ex) {
            toast("Could not save: " + ex.getMessage());
        }
    }

    static void writePng(WritableImage image, File file) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(out, "png", file);
    }

    private void toast(String message) {
        toast.setText(message);
        toast.setVisible(true);
        toastTimer.playFromStart();
    }

    /** JavaFX falls back to a 2D-only software renderer when it cannot use the GPU; explain instead of a blank window. */
    private void showNo3D(Stage stage) {
        System.err.println("error: JavaFX could not enable 3D rendering on this display "
                + "(no usable OpenGL/Direct3D driver). Try -Dprism.forceGPU=true or run without --gui.");
        Label title = styled(new Label("3D rendering is not available"), "inspector-title");
        Label body = new Label("""
                JavaFX could not use your graphics driver, so it fell back to a 2D-only renderer.

                • Update or install your GPU driver (on Linux: Mesa or the vendor driver).
                • Or force it: java -Dprism.forceGPU=true -jar codecity.jar --gui …
                • Or use the browser viewer: run codecity without --gui.""");
        body.setWrapText(true);
        VBox box = new VBox(14, title, body);
        box.getStyleClass().addAll("panel", "controls");
        box.setMaxSize(560, Region.USE_PREF_SIZE);
        StackPane pane = new StackPane(box);
        pane.getStyleClass().addAll("app", "day");
        Scene scene = new Scene(pane, 760, 360);
        scene.getStylesheets().add(stylesheet);
        stage.setTitle("CodeCity");
        stage.setScene(scene);
        stage.show();
    }

    // ------------------------------------------------------------------ helpers

    private <E extends Enum<E>> E enumPref(String key, E fallback) {
        String v = prefs.get(key, null);
        if (v == null) {
            return fallback;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<E> type = (Class<E>) (fallback != null ? fallback.getDeclaringClass() : Look.ColorMetric.class);
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static <T extends Node> T styled(T node, String style) {
        node.getStyleClass().add(style);
        return node;
    }

    private static Region spacer(double height) {
        Region r = new Region();
        r.setMinHeight(height);
        return r;
    }

    private static final String[][] BARS = {{"5", "16", "5", "11", "#38bdf8"}, {"11.5", "7", "5", "20", "#f97316"},
            {"18", "12", "4", "15", "#facc15"}, {"23.5", "19", "4", "8", "#34d399"}};

    private static Node logo() {
        Pane pane = new Pane();
        for (String[] b : BARS) {
            Rectangle r = new Rectangle(Double.parseDouble(b[0]), Double.parseDouble(b[1]),
                    Double.parseDouble(b[2]), Double.parseDouble(b[3]));
            r.setFill(Color.web(b[4]));
            r.setArcWidth(2);
            r.setArcHeight(2);
            pane.getChildren().add(r);
        }
        pane.setPrefSize(30, 30);
        pane.setMinSize(30, 30);
        return pane;
    }

    private static Image icon() {
        Canvas canvas = new Canvas(64, 64);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#0f172a"));
        g.fillRoundRect(0, 0, 64, 64, 14, 14);
        for (String[] b : BARS) {
            g.setFill(Color.web(b[4]));
            g.fillRoundRect(Double.parseDouble(b[0]) * 2, Double.parseDouble(b[1]) * 2,
                    Double.parseDouble(b[2]) * 2, Double.parseDouble(b[3]) * 2, 3, 3);
        }
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, null);
    }
}
