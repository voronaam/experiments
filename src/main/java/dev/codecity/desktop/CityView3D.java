package dev.codecity.desktop;

import dev.codecity.CityModel;
import dev.codecity.CityModel.Building;
import dev.codecity.layout.CityLayout;
import javafx.animation.AnimationTimer;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.AmbientLight;
import javafx.scene.DirectionalLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The 3D city. All buildings form a single mesh (and all districts another) so thousands of classes stay cheap; each
 * building's colour is a cell in a palette texture, so recolouring never touches geometry.
 *
 * <p>JavaFX's y axis points down, so world heights are negated here.
 */
final class CityView3D {

    /** Faces per box: the bottom is never visible and is left out. */
    private static final int FACES = 10;
    private static final int CELL = 16;
    private static final int CELLS_PER_ROW = 64;
    /** Corner triples for the ten faces of a unit box, wound so normals point outwards. */
    private static final int[][] TRIANGLES = boxTriangles();
    private static final int[] SIDE_OF_TRIANGLE = {0, 0, 1, 1, 2, 2, 3, 3, 4, 4};

    interface Listener {
        void hover(int building, int district, double sceneX, double sceneY);

        void click(int building);

        void districtDoubleClick(int district);
    }

    final SubScene subScene;
    private final Group root = new Group();
    private final Group world = new Group();
    private final Group arcGroup = new Group();
    private final Group beaconGroup = new Group();
    private final Pane labelLayer;
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Translate target = new Translate();
    private final Rotate yaw = new Rotate(-35, Rotate.Y_AXIS);
    private final Rotate pitch = new Rotate(-38, Rotate.X_AXIS);
    private final Translate dolly = new Translate(0, 0, -200);
    private final AmbientLight ambient = new AmbientLight();
    private final DirectionalLight key = new DirectionalLight();
    private final DirectionalLight fill = new DirectionalLight();
    private final Box ground = new Box(1, 0.1, 1);
    private final PhongMaterial groundMaterial = new PhongMaterial();
    private final PhongMaterial buildingMaterial = new PhongMaterial();
    private final PhongMaterial districtMaterial = new PhongMaterial();
    private final Listener listener;

    private CityModel model;
    private TriangleMesh buildingMesh;
    private MeshView buildingsView;
    private MeshView districtsView;
    private float[] points = new float[0];
    private double[] heights = new double[0];
    private double[] heightsFrom = new double[0];
    private double[] heightsTo = new double[0];
    private long heightStart;
    private double heightDuration;
    private double radius = 50;
    private Look.Theme theme = Look.Theme.DAY;

    private record Arc(Point3D p0, Point3D p1, Point3D p2, Sphere dot, double speed, double[] t) {
    }

    private final List<Arc> arcs = new ArrayList<>();
    private final List<Integer> beaconBuildings = new ArrayList<>();
    private boolean beaconsVisible = true;
    private record LabelAnchor(Label label, Point3D at) {
    }

    private final List<LabelAnchor> labels = new ArrayList<>();
    private boolean labelsVisible = true;

    private Flight flight;

    private record Flight(long start, double duration, Point3D fromTarget, Point3D toTarget, double fromDist,
                          double toDist) {
    }

    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;

    CityView3D(Pane labelLayer, Listener listener) {
        this.labelLayer = labelLayer;
        this.listener = listener;
        camera.setFieldOfView(42);
        camera.setNearClip(0.3);
        camera.getTransforms().addAll(target, yaw, pitch, dolly);

        ground.setMaterial(groundMaterial);
        ground.setTranslateY(0.06);
        ground.setMouseTransparent(true);
        key.setDirection(new Point3D(-0.45, 1, -0.6));
        fill.setDirection(new Point3D(0.6, 0.35, 0.8));
        buildingMaterial.setSpecularColor(Color.gray(0.18));
        buildingMaterial.setSpecularPower(24);
        districtMaterial.setSpecularColor(Color.BLACK);
        groundMaterial.setSpecularColor(Color.BLACK);
        arcGroup.setMouseTransparent(true);
        beaconGroup.setMouseTransparent(true);

        world.getChildren().addAll(ground, arcGroup, beaconGroup);
        root.getChildren().addAll(world, ambient, key, fill, camera);
        subScene = new SubScene(root, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setCamera(camera);
        installMouse();

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                tick(now);
            }
        }.start();
    }

    // ------------------------------------------------------------------ model

    void setModel(CityModel next, boolean keepCamera) {
        this.model = next;
        world.getChildren().removeIf(n -> n == buildingsView || n == districtsView);
        int n = next.buildings().size();
        double[] previous = heights;
        heights = new double[n];
        heightsFrom = new double[n];
        heightsTo = new double[n];
        if (keepCamera) {
            System.arraycopy(previous, 0, heights, 0, Math.min(previous.length, n));
        }

        radius = Math.max(20, Math.hypot(next.width(), next.depth()) / 2);
        camera.setFarClip(radius * 30);
        ground.setWidth(radius * 14);
        ground.setDepth(radius * 14);

        districtsView = new MeshView(districtMesh(next.districts()));
        districtsView.setMaterial(districtMaterial);
        districtsView.setCullFace(CullFace.BACK);

        buildingMesh = buildingMesh(next.buildings());
        buildingsView = new MeshView(buildingMesh);
        buildingsView.setMaterial(buildingMaterial);
        buildingsView.setCullFace(CullFace.BACK);
        world.getChildren().addAll(0, List.of(districtsView, buildingsView));

        buildLabels();
        clearArcs();
        buildBeacons();
        applyTheme(theme);
        if (!keepCamera) {
            frameAll(false);
        }
    }

    private static int[][] boxTriangles() {
        // Corner k: x = k & 1, z = (k >> 1) & 1, top = k >> 2 (JavaFX y = -1 at the top).
        int[][] quads = {{4, 5, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 3, 7, 6}};
        double[][] outward = {{0, -1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
        int[][] result = new int[10][];
        for (int q = 0; q < quads.length; q++) {
            int[] c = quads[q];
            result[q * 2] = wind(c[0], c[1], c[2], outward[q]);
            result[q * 2 + 1] = wind(c[0], c[2], c[3], outward[q]);
        }
        return result;
    }

    private static double[] corner(int k) {
        return new double[]{k & 1, (k >> 2) == 1 ? -1 : 0, (k >> 1) & 1};
    }

    /** Orders a triangle so that the right-hand normal (b - a) x (c - a) points along {@code out}. */
    private static int[] wind(int a, int b, int c, double[] out) {
        double[] pa = corner(a);
        double[] pb = corner(b);
        double[] pc = corner(c);
        double[] u = {pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2]};
        double[] v = {pc[0] - pa[0], pc[1] - pa[1], pc[2] - pa[2]};
        double[] n = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
        double dot = n[0] * out[0] + n[1] * out[1] + n[2] * out[2];
        return dot > 0 ? new int[]{a, b, c} : new int[]{a, c, b};
    }

    /** One box per item; texture coordinate {@code i} addresses palette cell {@code i}. */
    private static TriangleMesh boxMesh(int count, int cells) {
        TriangleMesh mesh = new TriangleMesh();
        float[] tex = new float[cells * 2];
        int rows = Math.max(1, (cells + CELLS_PER_ROW - 1) / CELLS_PER_ROW);
        float w = CELLS_PER_ROW * CELL;
        float h = rows * CELL;
        for (int i = 0; i < cells; i++) {
            tex[i * 2] = ((i % CELLS_PER_ROW) * CELL + CELL / 2f) / w;
            tex[i * 2 + 1] = ((i / CELLS_PER_ROW) * CELL + CELL / 2f) / h;
        }
        mesh.getTexCoords().setAll(tex);
        mesh.getPoints().setAll(new float[count * 8 * 3]);
        int[] faces = new int[count * FACES * 6];
        int[] groups = new int[count * FACES];
        for (int i = 0; i < count; i++) {
            for (int f = 0; f < FACES; f++) {
                int[] tri = TRIANGLES[f];
                int o = (i * FACES + f) * 6;
                for (int v = 0; v < 3; v++) {
                    faces[o + v * 2] = i * 8 + tri[v];
                }
                groups[i * FACES + f] = 1 << SIDE_OF_TRIANGLE[f];
            }
        }
        mesh.getFaces().setAll(faces);
        mesh.getFaceSmoothingGroups().setAll(groups);
        return mesh;
    }

    private static void writeBox(float[] pts, int box, double x0, double z0, double x1, double z1, double y0, double y1) {
        for (int k = 0; k < 8; k++) {
            int o = (box * 8 + k) * 3;
            pts[o] = (float) ((k & 1) == 0 ? x0 : x1);
            pts[o + 1] = (float) -((k >> 2) == 1 ? y1 : y0);
            pts[o + 2] = (float) (((k >> 1) & 1) == 0 ? z0 : z1);
        }
    }

    private TriangleMesh districtMesh(List<CityLayout.District> districts) {
        int levels = theme.districts.length;
        TriangleMesh mesh = boxMesh(districts.size(), levels);
        float[] pts = new float[districts.size() * 24];
        int[] faces = mesh.getFaces().toArray(null);
        for (int i = 0; i < districts.size(); i++) {
            CityLayout.District d = districts.get(i);
            double y0 = d.depth() * CityLayout.PLATFORM;
            writeBox(pts, i, d.x() - d.w() / 2, d.z() - d.d() / 2, d.x() + d.w() / 2, d.z() + d.d() / 2,
                    y0, y0 + CityLayout.PLATFORM);
            int cell = Math.min(d.depth(), levels - 1);
            for (int k = i * FACES * 6 + 1; k < (i + 1) * FACES * 6; k += 2) {
                faces[k] = cell;
            }
        }
        mesh.getFaces().setAll(faces);
        mesh.getPoints().setAll(pts);
        return mesh;
    }

    private TriangleMesh buildingMesh(List<Building> buildings) {
        int n = Math.max(1, buildings.size());
        TriangleMesh mesh = boxMesh(n, n);
        int[] faces = mesh.getFaces().toArray(null);
        for (int i = 0; i < n; i++) {
            for (int k = i * FACES * 6 + 1; k < (i + 1) * FACES * 6; k += 2) {
                faces[k] = i;
            }
        }
        mesh.getFaces().setAll(faces);
        points = new float[n * 24];
        return mesh;
    }

    // ------------------------------------------------------------------ heights & colours

    /** Animates buildings to new heights; {@code rise} grows the whole city from the ground. */
    void setHeights(double[] targets, boolean rise) {
        heightsFrom = rise ? new double[targets.length] : heights.clone();
        heightsTo = targets.clone();
        heightStart = System.nanoTime();
        heightDuration = rise ? 1.4 : 0.65;
    }

    double heightOf(int i) {
        return i < heightsTo.length ? heightsTo[i] : 0;
    }

    double radius() {
        return radius;
    }

    private void writeBuildings() {
        List<Building> buildings = model.buildings();
        for (int i = 0; i < buildings.size(); i++) {
            CityLayout.Lot lot = buildings.get(i).lot();
            double h = heights[i];
            double s = h > 0.001 ? lot.side() / 2 : 0;
            writeBox(points, i, lot.x() - s, lot.z() - s, lot.x() + s, lot.z() + s, lot.y(), lot.y() + Math.max(h, 0));
        }
        buildingMesh.getPoints().set(0, points, 0, points.length);
    }

    void setColors(Color[] colors) {
        buildingMaterial.setDiffuseMap(palette(colors, 1));
        buildingMaterial.setSelfIlluminationMap(theme.glow > 0 ? palette(colors, theme.glow) : null);
    }

    private static WritableImage palette(Color[] colors, double scale) {
        int cells = Math.max(1, colors.length);
        int rows = (cells + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
        WritableImage img = new WritableImage(CELLS_PER_ROW * CELL, rows * CELL);
        PixelWriter pw = img.getPixelWriter();
        for (int i = 0; i < colors.length; i++) {
            Color c = colors[i];
            Color s = scale == 1 ? c : Color.color(c.getRed() * scale, c.getGreen() * scale, c.getBlue() * scale);
            int x0 = (i % CELLS_PER_ROW) * CELL;
            int y0 = (i / CELLS_PER_ROW) * CELL;
            for (int y = 0; y < CELL; y++) {
                for (int x = 0; x < CELL; x++) {
                    pw.setColor(x0 + x, y0 + y, s);
                }
            }
        }
        return img;
    }

    void applyTheme(Look.Theme t) {
        this.theme = t;
        subScene.setFill(t.background);
        ambient.setColor(t.ambient);
        key.setColor(t.keyLight.deriveColor(0, 1, t.keyIntensity, 1));
        fill.setColor(t.fillLight.deriveColor(0, 1, t.fillIntensity, 1));
        groundMaterial.setDiffuseColor(t.ground);
        districtMaterial.setDiffuseMap(palette(t.districts, 1));
        buildingMaterial.setSpecularColor(t == Look.Theme.NIGHT ? Color.gray(0.35) : Color.gray(0.18));
    }

    // ------------------------------------------------------------------ labels

    private void buildLabels() {
        labels.forEach(l -> labelLayer.getChildren().remove(l.label()));
        labels.clear();
        List<CityLayout.District> candidates = model.districts().stream()
                .filter(d -> d.depth() >= 1 && d.depth() <= 2 && d.types() > 0)
                .sorted((a, b) -> Double.compare(b.w() * b.d(), a.w() * a.d()))
                .limit(24)
                .toList();
        List<double[]> anchors = new ArrayList<>();
        for (CityLayout.District d : candidates) {
            double front = d.z() + d.d() / 2;
            boolean crowded = anchors.stream().anyMatch(a -> Math.abs(a[0] - d.x()) < 8 && Math.abs(a[1] - front) < 3);
            if (crowded || anchors.size() >= 16) {
                continue;
            }
            anchors.add(new double[]{d.x(), front});
            Label label = new Label(d.label());
            label.getStyleClass().add("district-label");
            label.setManaged(false);
            label.setMouseTransparent(true);
            labelLayer.getChildren().add(label);
            labels.add(new LabelAnchor(label, new Point3D(d.x(), -((d.depth() + 1) * CityLayout.PLATFORM + 0.2), front)));
        }
    }

    void setLabelsVisible(boolean visible) {
        labelsVisible = visible;
    }

    private void placeLabels() {
        for (LabelAnchor a : labels) {
            Point3D inCamera = camera.sceneToLocal(world.localToScene(a.at()));
            boolean show = labelsVisible && inCamera != null && inCamera.getZ() > 0;
            a.label().setVisible(show);
            if (!show) {
                continue;
            }
            Point3D onScreen = world.localToScene(a.at(), true);
            Point2D local = labelLayer.sceneToLocal(onScreen.getX(), onScreen.getY());
            a.label().autosize();
            a.label().relocate(local.getX() - a.label().getWidth() / 2, local.getY() - a.label().getHeight());
        }
    }

    // ------------------------------------------------------------------ arcs & beacons

    /** Draws dependency arcs for the selection: orange to what it uses, blue from what uses it. */
    void showArcs(int selected, IntPredicate visible) {
        clearArcs();
        if (selected < 0) {
            return;
        }
        Building b = model.buildings().get(selected);
        int total = b.deps().length + b.incoming().length;
        List<double[]> outRings = new ArrayList<>();
        List<double[]> inRings = new ArrayList<>();
        int count = 0;
        for (int j : b.deps()) {
            if (visible.test(j) && count++ < 200) {
                addArc(selected, j, Look.OUT, outRings, total);
            }
        }
        count = 0;
        for (int j : b.incoming()) {
            if (visible.test(j) && count++ < 200) {
                addArc(j, selected, Look.IN, inRings, total);
            }
        }
        addTubes(outRings, Look.OUT);
        addTubes(inRings, Look.IN);
    }

    private Point3D topOf(int i) {
        CityLayout.Lot lot = model.buildings().get(i).lot();
        return new Point3D(lot.x(), -(lot.y() + heightOf(i)), lot.z());
    }

    private void addArc(int from, int to, Color color, List<double[]> curves, int total) {
        Point3D p0 = topOf(from);
        Point3D p2 = topOf(to);
        double dist = p0.distance(p2);
        Point3D mid = p0.midpoint(p2);
        Point3D p1 = new Point3D(mid.getX(), Math.min(p0.getY(), p2.getY()) - dist * 0.35 - 2, mid.getZ());
        curves.add(new double[]{p0.getX(), p0.getY(), p0.getZ(), p1.getX(), p1.getY(), p1.getZ(),
                p2.getX(), p2.getY(), p2.getZ(), (total > 40 ? 0.03 : 0.055) + dist * 0.0005});
        Sphere dot = new Sphere(0.18 + dist * 0.002, 8);
        dot.setMaterial(glow(color));
        arcGroup.getChildren().add(dot);
        arcs.add(new Arc(p0, p1, p2, dot, 14 / Math.max(5, dist * 1.2), new double[]{Math.random()}));
    }

    private static Point3D bezier(Point3D p0, Point3D p1, Point3D p2, double t) {
        double a = (1 - t) * (1 - t);
        double b = 2 * (1 - t) * t;
        double c = t * t;
        return new Point3D(a * p0.getX() + b * p1.getX() + c * p2.getX(),
                a * p0.getY() + b * p1.getY() + c * p2.getY(),
                a * p0.getZ() + b * p1.getZ() + c * p2.getZ());
    }

    /** All arcs of one colour as a single mesh of thin square tubes. */
    private void addTubes(List<double[]> curves, Color color) {
        if (curves.isEmpty()) {
            return;
        }
        final int segments = 24;
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().setAll(0, 0);
        float[] pts = new float[curves.size() * (segments + 1) * 4 * 3];
        int[] faces = new int[curves.size() * segments * 4 * 2 * 6];
        int p = 0;
        int f = 0;
        int base = 0;
        for (double[] c : curves) {
            Point3D p0 = new Point3D(c[0], c[1], c[2]);
            Point3D p1 = new Point3D(c[3], c[4], c[5]);
            Point3D p2 = new Point3D(c[6], c[7], c[8]);
            double r = c[9];
            for (int k = 0; k <= segments; k++) {
                double t = (double) k / segments;
                Point3D at = bezier(p0, p1, p2, t);
                Point3D tangent = bezier(p0, p1, p2, Math.min(1, t + 0.01)).subtract(bezier(p0, p1, p2, Math.max(0, t - 0.01)));
                Point3D u = tangent.crossProduct(Rotate.Y_AXIS);
                if (u.magnitude() < 1e-6) {
                    u = Rotate.X_AXIS;
                }
                u = u.normalize().multiply(r);
                Point3D v = tangent.crossProduct(u).normalize().multiply(r);
                for (Point3D q : new Point3D[]{at.add(u), at.add(v), at.subtract(u), at.subtract(v)}) {
                    pts[p++] = (float) q.getX();
                    pts[p++] = (float) q.getY();
                    pts[p++] = (float) q.getZ();
                }
            }
            for (int k = 0; k < segments; k++) {
                for (int s = 0; s < 4; s++) {
                    int a = base + k * 4 + s;
                    int b = base + k * 4 + (s + 1) % 4;
                    int c2 = base + (k + 1) * 4 + (s + 1) % 4;
                    int d = base + (k + 1) * 4 + s;
                    for (int idx : new int[]{a, b, c2, a, c2, d}) {
                        faces[f++] = idx;
                        faces[f++] = 0;
                    }
                }
            }
            base += (segments + 1) * 4;
        }
        mesh.getPoints().setAll(pts);
        mesh.getFaces().setAll(faces);
        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setMaterial(glow(color));
        arcGroup.getChildren().add(0, view);
    }

    private PhongMaterial glow(Color color) {
        PhongMaterial m = new PhongMaterial(Color.BLACK);
        m.setSpecularColor(Color.BLACK);
        WritableImage img = new WritableImage(1, 1);
        img.getPixelWriter().setColor(0, 0, color);
        m.setSelfIlluminationMap(img);
        return m;
    }

    private void clearArcs() {
        arcGroup.getChildren().clear();
        arcs.clear();
    }

    private void buildBeacons() {
        beaconGroup.getChildren().clear();
        beaconBuildings.clear();
        PhongMaterial material = glow(Look.PR);
        for (Building b : model.buildings()) {
            if (b.prs().length == 0) {
                continue;
            }
            beaconBuildings.add(b.index());
            double size = 0.7 + Math.min(1.2, b.prs().length * 0.25);
            Box diamond = new Box(size * 0.6, size * 0.6, size * 0.6);
            diamond.getTransforms().addAll(new Rotate(45, Rotate.Z_AXIS), new Rotate(35, Rotate.X_AXIS));
            diamond.setMaterial(material);
            Group g = new Group(diamond);
            beaconGroup.getChildren().add(g);
        }
    }

    void setBeaconsVisible(boolean visible) {
        beaconsVisible = visible;
        beaconGroup.setVisible(visible);
    }

    boolean hasBeacons() {
        return !beaconBuildings.isEmpty();
    }

    // ------------------------------------------------------------------ camera

    void frameAll(boolean animate) {
        Point3D centre = Point3D.ZERO;
        double dist = radius * 2.9;
        if (animate) {
            flyTo(centre, dist);
        } else {
            setTarget(centre);
            dolly.setZ(-dist);
            yaw.setAngle(-35);
            pitch.setAngle(-38);
        }
    }

    void flyToBuilding(int i) {
        CityLayout.Lot lot = model.buildings().get(i).lot();
        double h = heightOf(i);
        flyTo(new Point3D(lot.x(), -(lot.y() + h * 0.5), lot.z()), Math.max(Math.max(h * 1.8, lot.side() * 7), 22));
    }

    void flyToDistrict(CityLayout.District d) {
        flyTo(new Point3D(d.x(), -(d.depth() + 1) * CityLayout.PLATFORM, d.z()), Math.max(d.w(), d.d()) * 1.25 + 10);
    }

    private void flyTo(Point3D to, double distance) {
        flight = new Flight(System.nanoTime(), 0.9, new Point3D(target.getX(), target.getY(), target.getZ()), to,
                -dolly.getZ(), distance);
        if (-pitch.getAngle() < 20) {
            pitch.setAngle(-20);
        }
    }

    private void setTarget(Point3D p) {
        target.setX(p.getX());
        target.setY(p.getY());
        target.setZ(p.getZ());
    }

    private void installMouse() {
        subScene.setFocusTraversable(true);
        subScene.setOnMousePressed(e -> {
            subScene.requestFocus(); // clicking the city leaves the search field, so shortcuts work again
            pressX = lastX = e.getSceneX();
            pressY = lastY = e.getSceneY();
            flight = null;
        });
        subScene.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - lastX;
            double dy = e.getSceneY() - lastY;
            lastX = e.getSceneX();
            lastY = e.getSceneY();
            listener.hover(-1, -1, 0, 0);
            if (e.getButton() == MouseButton.PRIMARY && !e.isShiftDown()) {
                yaw.setAngle(yaw.getAngle() + dx * 0.3);
                double p = Math.max(4, Math.min(86, -pitch.getAngle() + dy * 0.3));
                pitch.setAngle(-p);
            } else {
                double scale = -dolly.getZ() * 0.0016;
                double a = Math.toRadians(yaw.getAngle());
                double rx = Math.cos(a), rz = -Math.sin(a);
                double fx = Math.sin(a), fz = Math.cos(a);
                target.setX(target.getX() - rx * dx * scale + fx * dy * scale);
                target.setZ(target.getZ() - rz * dx * scale + fz * dy * scale);
            }
        });
        subScene.setOnScroll(e -> {
            flight = null;
            double dist = -dolly.getZ() * Math.exp(-e.getDeltaY() * 0.0025);
            dolly.setZ(-Math.max(4, Math.min(radius * 6, dist)));
        });
        subScene.setOnMouseMoved(e -> {
            Hit hit = pick(e.getPickResult());
            listener.hover(hit.building, hit.district, e.getSceneX(), e.getSceneY());
        });
        subScene.setOnMouseExited(e -> listener.hover(-1, -1, 0, 0));
        subScene.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY
                    || Math.hypot(e.getSceneX() - pressX, e.getSceneY() - pressY) > 5) {
                return;
            }
            Hit hit = pick(e.getPickResult());
            if (e.getClickCount() == 2 && hit.district >= 0 && hit.building < 0) {
                listener.districtDoubleClick(hit.district);
            } else if (e.getClickCount() == 1) {
                listener.click(hit.building);
            }
        });
    }

    private record Hit(int building, int district) {
    }

    private Hit pick(PickResult r) {
        if (r == null || model == null) {
            return new Hit(-1, -1);
        }
        Node node = r.getIntersectedNode();
        int face = r.getIntersectedFace();
        if (node == buildingsView && face >= 0) {
            int i = face / FACES;
            return new Hit(i < model.buildings().size() ? i : -1, -1);
        }
        if (node == districtsView && face >= 0) {
            return new Hit(-1, face / FACES);
        }
        return new Hit(-1, -1);
    }

    // ------------------------------------------------------------------ frame loop

    private long lastFrame;

    private void tick(long now) {
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (model == null) {
            return;
        }
        if (flight != null) {
            double k = Math.min(1, (now - flight.start()) / 1e9 / flight.duration());
            double e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
            Point3D from = flight.fromTarget();
            Point3D to = flight.toTarget();
            setTarget(from.add(to.subtract(from).multiply(e)));
            dolly.setZ(-(flight.fromDist() + (flight.toDist() - flight.fromDist()) * e));
            if (k >= 1) {
                flight = null;
            }
        }
        if (heightDuration > 0) {
            double k = Math.min(1, (now - heightStart) / 1e9 / heightDuration);
            double e = 1 - Math.pow(1 - k, 3);
            for (int i = 0; i < heights.length; i++) {
                heights[i] = heightsFrom[i] + (heightsTo[i] - heightsFrom[i]) * e;
            }
            writeBuildings();
            if (k >= 1) {
                heightDuration = 0;
            }
        }
        for (Arc a : arcs) {
            a.t()[0] = (a.t()[0] + dt * a.speed()) % 1;
            Point3D p = bezier(a.p0(), a.p1(), a.p2(), a.t()[0]);
            a.dot().setTranslateX(p.getX());
            a.dot().setTranslateY(p.getY());
            a.dot().setTranslateZ(p.getZ());
        }
        if (beaconsVisible) {
            double t = now / 1e9;
            for (int k = 0; k < beaconBuildings.size(); k++) {
                int i = beaconBuildings.get(k);
                Node g = beaconGroup.getChildren().get(k);
                CityLayout.Lot lot = model.buildings().get(i).lot();
                double h = heights[i];
                g.setVisible(h > 0.001);
                g.setTranslateX(lot.x());
                g.setTranslateY(-(lot.y() + h + 1.4 + Math.sin(t * 2 + i) * 0.25));
                g.setTranslateZ(lot.z());
                g.setRotationAxis(Rotate.Y_AXIS);
                g.setRotate(Math.toDegrees(t * 1.2 + i) % 360);
            }
        }
        placeLabels();
    }

}
