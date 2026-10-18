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
import javafx.scene.Cursor;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.PickResult;
import javafx.scene.robot.Robot;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * The 3D city. All buildings form a single mesh (and all districts another) so thousands of classes stay cheap; each
 * building's colour is a cell in a palette texture, so recolouring never touches geometry.
 *
 * <p>JavaFX's y axis points down, so world heights are negated here.
 *
 * <h2>Meshes</h2>
 * Building {@code i} is box {@code i} of the building mesh: points {@code 8i .. 8i+7} and triangles
 * {@code FACES*i .. FACES*i + FACES-1}. That fixed layout is what makes the rest cheap:
 * <ul>
 *   <li>Heights change by rewriting the point array only ({@link #writeBuildings}); faces never change.</li>
 *   <li>Colours: every triangle of box {@code i} uses texture coordinate {@code i}, which points at the centre of
 *       cell {@code i} of a palette image ({@link #palette}). Recolouring replaces the image.</li>
 *   <li>Picking: a {@link PickResult} gives the face index, and {@code face / FACES} is the building
 *       ({@link #pick}).</li>
 * </ul>
 * Districts use the same scheme, except that a palette cell stands for a nesting depth instead of a single district.
 *
 * <h2>Coordinates</h2>
 * Layout coordinates are used unchanged for x and z ({@link CityLayout}: the city is centred on the origin); y is the
 * negated layout height. The camera orbits a target point: its transform chain is
 * {@code target → yaw → pitch → dolly}, i.e. move to the target, turn around the vertical axis, tilt, then back off
 * along the view axis by the dolly distance. Negative pitch angles look down on the city.
 *
 * <h2>Animation</h2>
 * An {@link AnimationTimer} calls {@link #tick} once per frame on the JavaFX thread. It advances camera flights,
 * height transitions, the dots travelling along dependency arcs and the bobbing PR beacons, and keeps the 2D district
 * labels pinned to their 3D anchors. Every method of this class must be called on the JavaFX thread.
 *
 * <h2>Walk mode</h2>
 * While walking ({@link #enterWalk}), the orbit chain is reused as a first-person camera: the target is the eye, the
 * dolly is 0, and yaw/pitch come from the {@link Walker}. JavaFX has no pointer lock, so the cursor is hidden and a
 * {@link Robot} puts it back in the middle of the view every frame; how far it had moved from there is the look delta.
 * Mouse events are ignored while walking (including the synthetic move the recentring causes), except a primary press,
 * which selects what the crosshair points at. The crosshair target is found by casting the view ray against the
 * walker's boxes ({@link Walker#target}) rather than by JavaFX picking, which only works at the cursor.
 *
 * <h2>History replay</h2>
 * During replay {@code DesktopApp} drives the geometry frame by frame: {@link #showFrame} sets every building's height
 * and footprint at once, without animation, and {@link #setDistrictsShown} hides the districts that did not exist yet
 * (their boxes, and labels, collapse to nothing). {@link #setHeights} returns to the present-day footprints.
 *
 * <p>The web viewer draws the same scene with three.js instancing instead.
 */
final class CityView3D {

    /** Faces per box: the bottom is never visible and is left out. */
    private static final int FACES = 10;
    /**
     * Pixel size of one palette cell. Texture coordinates point at a cell's centre, so the cell must be wide enough
     * that texture filtering never blends in a neighbouring colour.
     */
    private static final int CELL = 16;
    /** Palette cells per texture row: rows are 64 × 16 = 1024 px wide, and as many rows are added as needed. */
    private static final int CELLS_PER_ROW = 64;
    /** Corner triples for the ten faces of a unit box, wound so normals point outwards. */
    private static final int[][] TRIANGLES = boxTriangles();
    /**
     * Which of the five visible sides each triangle belongs to. Each side gets its own smoothing group, so normals are
     * not averaged across a box edge and the boxes are shaded flat with crisp edges.
     */
    private static final int[] SIDE_OF_TRIANGLE = {0, 0, 1, 1, 2, 2, 3, 3, 4, 4};

    /** Callbacks to the window; building and district indices are -1 for "none". */
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
    private TriangleMesh districtMesh;
    /** Corner points of every district box as laid out, so hidden districts can be restored. */
    private float[] districtPoints = new float[0];
    /** Which districts are drawn during replay; null shows all. */
    private boolean[] districtShown;
    /** Footprint side per building during replay; null uses the lots' sides. */
    private double[] sides;
    private MeshView buildingsView;
    private MeshView districtsView;
    /** Point buffer of the building mesh, rewritten while heights animate and copied into the mesh in one go. */
    private float[] points = new float[0];
    /** Current, start and target height per building (world units); see {@link #setHeights}. */
    private double[] heights = new double[0];
    private double[] heightsFrom = new double[0];
    private double[] heightsTo = new double[0];
    /** Start time ({@link System#nanoTime}) and length in seconds of the height animation; 0 when none is running. */
    private long heightStart;
    private double heightDuration;
    /** Half the city's diagonal, at least 20. Far plane, ground size, zoom limit and default distance scale with it. */
    private double radius = 50;
    private Look.Theme theme = Look.Theme.DAY;

    /**
     * A dependency arc: quadratic Bézier from {@code p0} via control point {@code p1} to {@code p2}, and the dot that
     * travels along it. {@code t} is a one-element array so the record can hold the dot's mutable position on the
     * curve (0..1); {@code speed} is in curve fractions per second.
     */
    private record Arc(Point3D p0, Point3D p1, Point3D p2, Sphere dot, double speed, double[] t) {
    }

    private final List<Arc> arcs = new ArrayList<>();
    /** Building index of each beacon; entry {@code k} belongs to child {@code k} of {@link #beaconGroup}. */
    private final List<Integer> beaconBuildings = new ArrayList<>();
    private boolean beaconsVisible = true;
    /**
     * A 2D district label, the world point it is pinned to (the front edge of the district, on top of it) and the
     * district's index.
     */
    private record LabelAnchor(Label label, Point3D at, int district) {
    }

    private final List<LabelAnchor> labels = new ArrayList<>();
    private boolean labelsVisible = true;

    private Flight flight;

    /**
     * A camera move in progress: orbit target, dolly distance and pitch (in degrees below the horizon) are
     * interpolated; the yaw is left alone.
     */
    private record Flight(long start, double duration, Point3D fromTarget, Point3D toTarget, double fromDist,
                          double toDist, double fromPitch, double toPitch) {
    }

    /** Where the mouse button went down (to tell a click from a drag) and where the last drag event was. */
    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;

    /** Walk mode state: the walker, or null while orbiting, and the orbit camera to restore afterwards. */
    private Walker walker;
    private double[] savedOrbit;
    private Walker.Input walkInput = Walker.Input.NONE;
    private Robot robot;
    /** False until the cursor has been centred once after entering, so its starting offset is not taken as a look. */
    private boolean recentred;
    /** What the crosshair pointed at last frame, to report hover changes only. */
    private int aimBuilding = -1;
    private int aimDistrict = -1;

    CityView3D(Pane labelLayer, Listener listener) {
        this.labelLayer = labelLayer;
        this.listener = listener;
        // Same field of view and near plane as the web viewer's camera.
        camera.setFieldOfView(42);
        camera.setNearClip(0.3);
        camera.getTransforms().addAll(target, yaw, pitch, dolly);

        ground.setMaterial(groundMaterial);
        // The 0.1-thick ground slab spans y = 0.01 .. 0.11, i.e. just below the city's base at y = 0 (y points
        // down), so its top never z-fights with the bottom of the lowest platform.
        ground.setTranslateY(0.06);
        ground.setMouseTransparent(true);
        // Key light from above (positive y is down), fill light from a lower, opposite angle.
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

    /**
     * Replaces the city. Both meshes are rebuilt from scratch, as are labels and beacons; arcs are cleared.
     *
     * <p>With {@code keepCamera} (a live rebuild) the camera stays where it is and the current heights are carried
     * over by fully qualified name (indices are not stable across rebuilds), so the caller's next {@link #setHeights}
     * animates from what is on screen; new types start at 0. Without it, heights start at 0 and the camera frames the
     * whole city. Either way the targets are 0 until the caller
     * calls {@link #setHeights}.
     */
    void setModel(CityModel next, boolean keepCamera) {
        Map<String, Double> previous = new HashMap<>();
        if (keepCamera && model != null) {
            for (int i = 0; i < heights.length; i++) {
                previous.put(model.buildings().get(i).type().fqn(), heights[i]);
            }
        }
        this.model = next;
        world.getChildren().removeIf(n -> n == buildingsView || n == districtsView);
        int n = next.buildings().size();
        heights = new double[n];
        heightsFrom = new double[n];
        heightsTo = new double[n];
        sides = null;
        districtShown = null;
        for (int i = 0; i < n; i++) {
            heights[i] = previous.getOrDefault(next.buildings().get(i).type().fqn(), 0.0);
        }

        // Everything scales with the city's radius: the ground slab reaches 7 radii out in every direction, the zoom
        // limit is 6 radii and the far plane, at 30 radii, lies beyond both.
        radius = Math.max(20, Math.hypot(next.width(), next.depth()) / 2);
        camera.setFarClip(radius * 30);
        ground.setWidth(radius * 14);
        ground.setDepth(radius * 14);

        districtMesh = districtMesh(next.districts());
        districtsView = new MeshView(districtMesh);
        districtsView.setMaterial(districtMaterial);
        districtsView.setCullFace(CullFace.BACK);

        buildingMesh = buildingMesh(next.buildings());
        buildingsView = new MeshView(buildingMesh);
        buildingsView.setMaterial(buildingMaterial);
        buildingsView.setCullFace(CullFace.BACK);
        // Inserted before the ground, arcs and beacons in child order.
        world.getChildren().addAll(0, List.of(districtsView, buildingsView));

        buildLabels();
        clearArcs();
        buildBeacons();
        applyTheme(theme);
        if (!keepCamera) {
            frameAll(false);
        }
    }

    /**
     * The ten triangles of a box without its bottom: two per side, top first, then the sides at x = 0, x = 1, z = 0
     * and z = 1 (the order {@link #SIDE_OF_TRIANGLE} relies on). Winding is computed rather than written out, see
     * {@link #wind}.
     *
     * <pre>
     *        6 ──── 7        corner k:  x = bit 0,  z = bit 1,  top = bit 2
     *       /│     /│
     *      4 ──── 5 │        top    4 5 7 6
     *      │ 2 ───│ 3        x = 0  0 2 6 4      z = 0  0 1 5 4
     *      │/     │/         x = 1  1 3 7 5      z = 1  2 3 7 6
     *      0 ──── 1
     * </pre>
     */
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

    /**
     * Orders a triangle so that the right-hand normal (b - a) x (c - a) points along {@code out}. The meshes are drawn
     * with {@link CullFace#BACK}, and this consistent outward winding is what keeps the outside faces visible.
     */
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

    /**
     * One box per item; texture coordinate {@code i} addresses palette cell {@code i}.
     *
     * <p>The faces array uses JavaFX's default {@code POINT_TEXCOORD} format: six ints per triangle,
     * {@code p0 t0 p1 t1 p2 t2}. Here every texture index is left at 0; callers fill the odd slots with the palette
     * cell of each box. All points start at the origin (degenerate boxes) until written by {@link #writeBox}.
     *
     * @param count number of boxes
     * @param cells number of palette cells the texture coordinates must address
     */
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

    /**
     * Writes the eight corners of box {@code box} into {@code pts} (three floats each), using the corner numbering of
     * {@link #boxTriangles}. {@code y0}/{@code y1} are world heights (up positive); they are negated here.
     */
    private static void writeBox(float[] pts, int box, double x0, double z0, double x1, double z1, double y0, double y1) {
        for (int k = 0; k < 8; k++) {
            int o = (box * 8 + k) * 3;
            pts[o] = (float) ((k & 1) == 0 ? x0 : x1);
            pts[o + 1] = (float) -((k >> 2) == 1 ? y1 : y0);
            pts[o + 2] = (float) (((k >> 1) & 1) == 0 ? z0 : z1);
        }
    }

    /**
     * One box per district, {@link CityLayout#PLATFORM} thick and stacked by depth. Each district's faces use the
     * palette cell of its depth, clamped to the last theme colour, so the theme can recolour all platforms by swapping
     * one small texture.
     */
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
            // Odd slots of the face array are texture-coordinate indices (see boxMesh).
            for (int k = i * FACES * 6 + 1; k < (i + 1) * FACES * 6; k += 2) {
                faces[k] = cell;
            }
        }
        mesh.getFaces().setAll(faces);
        mesh.getPoints().setAll(pts);
        districtPoints = pts;
        return mesh;
    }

    /**
     * One box per building with its own palette cell. Points are not written here: they depend on the animated heights
     * and are filled in by {@link #writeBuildings} on the next frames.
     */
    private TriangleMesh buildingMesh(List<Building> buildings) {
        // An empty city still gets one (degenerate) box, so the mesh and palette are never empty; pick() ignores it.
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

    /**
     * Animates buildings to new heights; {@code rise} grows the whole city from the ground. A new call interrupts a
     * running animation and continues from the current heights. Durations (1.4 s, 0.65 s) match the web viewer.
     */
    void setHeights(double[] targets, boolean rise) {
        sides = null;
        heightsFrom = rise ? new double[targets.length] : heights.clone();
        heightsTo = targets.clone();
        heightStart = System.nanoTime();
        heightDuration = rise ? 1.4 : 0.65;
    }

    /** Target height of building {@code i}: where it is going, not where an animation has it right now. */
    double heightOf(int i) {
        return i < heightsTo.length ? heightsTo[i] : 0;
    }

    double radius() {
        return radius;
    }

    /**
     * Shows one replay frame: every building's height and footprint side ({@code sides[i]}, centred on its lot) at
     * once, stopping any height animation. A building of zero height is hidden as usual.
     */
    void showFrame(double[] frameHeights, double[] frameSides) {
        heights = frameHeights.clone();
        heightsFrom = frameHeights.clone();
        heightsTo = frameHeights.clone();
        heightDuration = 0;
        sides = frameSides.clone();
        writeBuildings();
    }

    /**
     * Draws only the districts with {@code shown[d]} (null: all of them). A hidden district's box collapses to a point,
     * so it can neither be seen nor picked, and its label is hidden.
     */
    void setDistrictsShown(boolean[] shown) {
        districtShown = shown == null ? null : shown.clone();
        float[] pts = districtPoints.clone();
        if (shown != null) {
            for (int d = 0; d < shown.length; d++) {
                if (!shown[d]) {
                    Arrays.fill(pts, d * 24, d * 24 + 24, 0f);
                }
            }
        }
        districtMesh.getPoints().setAll(pts);
    }

    private boolean districtShown(int d) {
        return districtShown == null || districtShown[d];
    }

    /**
     * Rewrites every building box from the current heights and uploads all points at once. A building with (almost)
     * no height also gets a zero footprint, so hidden buildings vanish completely and cannot be picked.
     */
    private void writeBuildings() {
        List<Building> buildings = model.buildings();
        for (int i = 0; i < buildings.size(); i++) {
            CityLayout.Lot lot = buildings.get(i).lot();
            double h = heights[i];
            double s = h > 0.001 ? (sides != null ? sides[i] : lot.side()) / 2 : 0;
            writeBox(points, i, lot.x() - s, lot.z() - s, lot.x() + s, lot.z() + s, lot.y(), lot.y() + Math.max(h, 0));
        }
        buildingMesh.getPoints().set(0, points, 0, points.length);
    }

    /**
     * Sets building colours ({@code colors[i]} for building {@code i}) by replacing the palette texture. In a theme
     * with
     * {@link Look.Theme#glow}, a darkened copy also becomes the self-illumination map, so buildings glow in their own
     * colour at night. Must be called again after {@link #applyTheme}, which does not touch building colours.
     */
    void setColors(Color[] colors) {
        buildingMaterial.setDiffuseMap(palette(colors, 1));
        buildingMaterial.setSelfIlluminationMap(theme.glow > 0 ? palette(colors, theme.glow) : null);
    }

    /**
     * Paints colour {@code i}, multiplied by {@code scale}, into a solid {@code CELL × CELL} square at cell {@code i};
     * the layout must match the texture coordinates made by {@link #boxMesh}. Both derive the row count from the
     * number of cells, so the caller must pass as many colours as the mesh has cells (at least the same row count).
     */
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

    /** Background, lights, ground and district colours for {@code t}; building colours follow in {@link #setColors}. */
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

    /**
     * Picks which districts get a floating name label. Candidates are the districts at depth 1 and 2 that contain
     * types, largest area first, at most 24 of them; a candidate is skipped when its anchor would sit within 8 units
     * along x and 3 along z of an existing one, and at most 16 labels are kept. The rest of the city stays unlabelled
     * to avoid clutter (hovering a district shows its name instead).
     */
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
            labels.add(new LabelAnchor(label, new Point3D(d.x(), -((d.depth() + 1) * CityLayout.PLATFORM + 0.2), front),
                    model.districts().indexOf(d)));
        }
    }

    void setLabelsVisible(boolean visible) {
        labelsVisible = visible;
    }

    /**
     * Projects each label's 3D anchor onto the label layer and centres the label just above that point. A label is
     * hidden when its anchor lies behind the camera (camera-space z ≤ 0; the JavaFX camera looks along +z), where
     * the projection would otherwise mirror it onto the screen.
     */
    private void placeLabels() {
        for (LabelAnchor a : labels) {
            Point3D inCamera = camera.sceneToLocal(world.localToScene(a.at()));
            // Hidden while walking: at street level they would show through the buildings in front of them.
            boolean show = labelsVisible && walker == null && inCamera != null && inCamera.getZ() > 0
                    && districtShown(a.district());
            a.label().setVisible(show);
            if (!show) {
                continue;
            }
            // rootScene = true projects through the SubScene's camera into the enclosing Scene's 2D coordinates.
            Point3D onScreen = world.localToScene(a.at(), true);
            Point2D local = labelLayer.sceneToLocal(onScreen.getX(), onScreen.getY());
            a.label().autosize();
            a.label().relocate(local.getX() - a.label().getWidth() / 2, local.getY() - a.label().getHeight());
        }
    }

    // ------------------------------------------------------------------ arcs & beacons

    /**
     * Draws dependency arcs for the selection: orange to what it uses, blue from what uses it. Only edges whose both
     * ends are visible are kept, and of those at most 200 per direction are drawn; past 40 edges in total (visible or not) the tubes get thinner.
     * Arcs connect roof tops at the target heights, so they are drawn where the buildings end up even while heights
     * are still animating. A negative {@code selected} just clears the arcs.
     */
    void showArcs(int selected, IntPredicate visible) {
        clearArcs();
        if (selected < 0 || !visible.test(selected)) {
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

    /**
     * Adds one arc: a Bézier curve record for {@link #addTubes} ({@code x,y,z} of the three control points and the
     * tube radius) and a glowing dot that {@link #tick} moves along the curve. The control point sits above the
     * midpoint, higher than the taller roof by 35% of the distance plus 2 units, so long arcs rise higher and
     * neighbours still get a visible hump. Dot speed is chosen so that the dot covers roughly the same world distance
     * per second on any arc, with short arcs not getting faster than 2.8 laps per second; a random phase keeps
     * the dots from moving in lockstep.
     */
    private void addArc(int from, int to, Color color, List<double[]> curves, int total) {
        Point3D p0 = topOf(from);
        Point3D p2 = topOf(to);
        double dist = p0.distance(p2);
        Point3D mid = p0.midpoint(p2);
        // Math.min of y is the taller roof, and subtracting moves up, because y points down.
        Point3D p1 = new Point3D(mid.getX(), Math.min(p0.getY(), p2.getY()) - dist * 0.35 - 2, mid.getZ());
        curves.add(new double[]{p0.getX(), p0.getY(), p0.getZ(), p1.getX(), p1.getY(), p1.getZ(),
                p2.getX(), p2.getY(), p2.getZ(), (total > 40 ? 0.03 : 0.055) + dist * 0.0005});
        Sphere dot = new Sphere(0.18 + dist * 0.002, 8);
        dot.setMaterial(glow(color));
        arcGroup.getChildren().add(dot);
        arcs.add(new Arc(p0, p1, p2, dot, 14 / Math.max(5, dist * 1.2), new double[]{Math.random()}));
    }

    /** Point at {@code t} on the quadratic Bézier curve: (1-t)²·p0 + 2(1-t)t·p1 + t²·p2. */
    private static Point3D bezier(Point3D p0, Point3D p1, Point3D p2, double t) {
        double a = (1 - t) * (1 - t);
        double b = 2 * (1 - t) * t;
        double c = t * t;
        return new Point3D(a * p0.getX() + b * p1.getX() + c * p2.getX(),
                a * p0.getY() + b * p1.getY() + c * p2.getY(),
                a * p0.getZ() + b * p1.getZ() + c * p2.getZ());
    }

    /**
     * All arcs of one colour as a single mesh of thin square tubes.
     *
     * <p>Each curve is sampled at 25 points ({@code segments = 24}). At every sample a ring of four vertices is placed
     * around the curve, at ±u and ±v, where u is perpendicular to the tangent and horizontal (tangent × y) and v is
     * perpendicular to both; a vertical tangent, where that cross product vanishes, falls back to the x axis.
     * Consecutive rings are joined by four quads (two triangles each). The tangent is a central difference over
     * ±0.01 in t. Winding is not consistent around the tube, so back-face culling is off.
     *
     * <pre>
     *   ring k+1:  d ── c2        quad per side s: (a, b, c2) + (a, c2, d)
     *              │  ╱ │
     *   ring k:    a ── b         a = ring k corner s, b = ring k corner s+1
     * </pre>
     */
    private void addTubes(List<double[]> curves, Color color) {
        if (curves.isEmpty()) {
            return;
        }
        final int segments = 24;
        TriangleMesh mesh = new TriangleMesh();
        // A single texture coordinate: the colour comes from the 1×1 self-illumination map of glow().
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

    /**
     * An unlit-looking material: black diffuse and specular, so lights add nothing, and a 1×1 self-illumination map,
     * which JavaFX adds at full strength regardless of lighting. JavaFX has no plain emissive colour, hence the image.
     */
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

    /**
     * One spinning diamond per building with open pull requests, bigger with more PRs (capped at 5 PRs). The diamond
     * is a cube tilted 45° about z and 35° about x so that it stands roughly on a corner; it is wrapped in a group so
     * {@link #tick} can spin the group about the vertical axis without disturbing the tilt.
     */
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

    /** Looks at the whole city from the default angle (or flies there, keeping the current angles). */
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

    /**
     * Flies to the middle of building {@code i}, far enough back to see it whole: 1.8 × its height, 7 × its side, but
     * never closer than 22 units.
     */
    void flyToBuilding(int i) {
        CityLayout.Lot lot = model.buildings().get(i).lot();
        double h = heightOf(i);
        flyTo(new Point3D(lot.x(), -(lot.y() + h * 0.5), lot.z()), Math.max(Math.max(h * 1.8, lot.side() * 7), 22));
    }

    /** Flies to the top of district {@code d}, backing off by 1.25 × its longer side plus 10 units. */
    void flyToDistrict(CityLayout.District d) {
        flyTo(new Point3D(d.x(), -(d.depth() + 1) * CityLayout.PLATFORM, d.z()), Math.max(d.w(), d.d()) * 1.25 + 10);
    }

    /**
     * Starts a 0.9 s flight to a new orbit target and dolly distance. If the camera looks down at less than 20°, it is
     * tilted to 20° during the flight, so the destination is not seen edge-on.
     */
    private void flyTo(Point3D to, double distance) {
        if (walker != null) {
            return; // the walker owns the camera; selecting by crosshair must not fly off
        }
        flight = new Flight(System.nanoTime(), 0.9, new Point3D(target.getX(), target.getY(), target.getZ()), to,
                -dolly.getZ(), distance, -pitch.getAngle(), Math.max(20, -pitch.getAngle()));
    }

    private void setTarget(Point3D p) {
        target.setX(p.getX());
        target.setY(p.getY());
        target.setZ(p.getZ());
    }

    /**
     * Mouse controls: primary drag orbits (yaw, and pitch clamped to 4°..86° below the horizon); any other drag, or
     * shift + primary drag, pans across the ground plane; the wheel zooms exponentially, clamped between 4 units and 6
     * radii. Pressing the mouse or scrolling cancels a flight. A click only counts if the mouse moved at most 5 px
     * since the press, so the end of a drag never selects anything.
     */
    private void installMouse() {
        subScene.setFocusTraversable(true);
        subScene.setOnMousePressed(e -> {
            subScene.requestFocus(); // clicking the city leaves the search field, so shortcuts work again
            if (walker != null) {
                if (e.getButton() == MouseButton.PRIMARY) {
                    listener.click(aimBuilding);
                }
                return;
            }
            pressX = lastX = e.getSceneX();
            pressY = lastY = e.getSceneY();
            flight = null;
        });
        subScene.setOnMouseDragged(e -> {
            if (walker != null) {
                return;
            }
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
                // Pan speed grows with the camera distance, so the ground moves roughly with the cursor at any zoom.
                // (rx, rz) and (fx, fz) are the camera's sideways and front/back directions on the ground plane,
                // derived from the yaw, so panning follows the view direction rather than the world axes.
                double scale = -dolly.getZ() * 0.0016;
                double a = Math.toRadians(yaw.getAngle());
                double rx = Math.cos(a), rz = -Math.sin(a);
                double fx = Math.sin(a), fz = Math.cos(a);
                target.setX(target.getX() - rx * dx * scale + fx * dy * scale);
                target.setZ(target.getZ() - rz * dx * scale + fz * dy * scale);
            }
        });
        subScene.setOnScroll(e -> {
            if (walker != null) {
                return;
            }
            flight = null;
            double dist = -dolly.getZ() * Math.exp(-e.getDeltaY() * 0.0025);
            dolly.setZ(-Math.max(4, Math.min(radius * 6, dist)));
        });
        subScene.setOnMouseMoved(e -> {
            if (walker != null) {
                return;
            }
            Hit hit = pick(e.getPickResult());
            listener.hover(hit.building, hit.district, e.getSceneX(), e.getSceneY());
        });
        subScene.setOnMouseExited(e -> {
            if (walker == null) {
                listener.hover(-1, -1, 0, 0);
            }
        });
        subScene.setOnMouseClicked(e -> {
            if (walker != null || e.getButton() != MouseButton.PRIMARY
                    || Math.hypot(e.getSceneX() - pressX, e.getSceneY() - pressY) > 5) {
                return;
            }
            Hit hit = pick(e.getPickResult());
            // JavaFX delivers a double click as a click with count 1 followed by one with count 2. The first one
            // selects (or, on a platform, clears the selection); the second zooms to a district.
            if (e.getClickCount() == 2 && hit.district >= 0 && hit.building < 0) {
                listener.districtDoubleClick(hit.district);
            } else if (e.getClickCount() == 1) {
                listener.click(hit.building);
            }
        });
    }

    // ------------------------------------------------------------------ walk mode

    boolean walking() {
        return walker != null;
    }

    /** Keys currently held while walking; read by the next frames. */
    void setWalkInput(Walker.Input input) {
        walkInput = input;
    }

    /**
     * Starts walk mode. The orbit camera is saved for {@link #exitWalk}. With a building selected the player spawns
     * on the street in front of it (towards +z), facing it; otherwise at the orbit target, facing the way the camera
     * looked. The cursor is hidden; {@link #tick} recentres it every frame.
     */
    void enterWalk(int selected) {
        if (walker != null || model == null) {
            return;
        }
        flight = null;
        savedOrbit = new double[]{target.getX(), target.getY(), target.getZ(), yaw.getAngle(), pitch.getAngle(),
                dolly.getZ()};
        Walker.World world = Walker.World.of(model, heights, sides, districtShown);
        walker = new Walker();
        if (selected >= 0 && selected < model.buildings().size()) {
            CityLayout.Lot lot = model.buildings().get(selected).lot();
            walker.spawn(lot.x(), lot.z() + lot.side() / 2 + Walker.RADIUS + 0.2, 0, world);
        } else {
            // The orbit camera looks along (sin a, cos a) on the ground; the walker's yaw 0 looks along −z.
            walker.spawn(target.getX(), target.getZ(), Math.toRadians(yaw.getAngle()) - Math.PI, world);
        }
        camera.setFieldOfView(Walker.FOV);
        camera.setNearClip(Walker.NEAR);
        if (robot == null) {
            robot = new Robot();
        }
        recentred = false;
        aimBuilding = aimDistrict = -2; // forces a hover report on the first frame
        subScene.setCursor(Cursor.NONE);
        if (subScene.getScene() != null) {
            subScene.getScene().setCursor(Cursor.NONE);
        }
        placeWalkCamera();
    }

    /** Leaves walk mode: the orbit camera is restored exactly, the cursor comes back and the hover is cleared. */
    void exitWalk() {
        if (walker == null) {
            return;
        }
        walker = null;
        walkInput = Walker.Input.NONE;
        setTarget(new Point3D(savedOrbit[0], savedOrbit[1], savedOrbit[2]));
        yaw.setAngle(savedOrbit[3]);
        pitch.setAngle(savedOrbit[4]);
        dolly.setZ(savedOrbit[5]);
        camera.setFieldOfView(42);
        camera.setNearClip(0.3);
        subScene.setCursor(Cursor.DEFAULT);
        if (subScene.getScene() != null) {
            subScene.getScene().setCursor(Cursor.DEFAULT);
        }
        listener.hover(-1, -1, 0, 0);
    }

    /**
     * One walk-mode frame: mouse look, physics, camera, crosshair hover. The look delta is the cursor's offset from the
     * view's centre on screen (rounded to whole pixels, so a centre between pixels does not read as constant motion),
     * after which the cursor is moved back there.
     */
    private void walkFrame(double dt) {
        Point2D centre = subScene.localToScreen(subScene.getWidth() / 2, subScene.getHeight() / 2);
        if (centre != null) {
            double cx = Math.round(centre.getX());
            double cy = Math.round(centre.getY());
            Point2D mouse = robot.getMousePosition();
            double dx = mouse.getX() - cx;
            double dy = mouse.getY() - cy;
            if (recentred && (Math.abs(dx) >= 0.5 || Math.abs(dy) >= 0.5)) {
                walker.look(dx, dy);
            }
            if (!recentred || Math.abs(dx) >= 0.5 || Math.abs(dy) >= 0.5) {
                robot.mouseMove(cx, cy);
                recentred = true;
            }
        }
        Walker.World world = Walker.World.of(model, heights, sides, districtShown);
        walker.step(dt, walkInput, world);
        placeWalkCamera();
        Walker.Box aim = walker.target(world);
        int b = aim == null ? -1 : aim.building();
        int d = aim == null ? -1 : aim.district();
        if (b != aimBuilding || d != aimDistrict) {
            aimBuilding = b;
            aimDistrict = d;
            Point2D mid = subScene.localToScene(subScene.getWidth() / 2, subScene.getHeight() / 2);
            listener.hover(b, d, mid.getX(), mid.getY());
        }
    }

    /**
     * Turns the orbit chain into an eye: target at the eye (y negated for JavaFX), no dolly. The JavaFX yaw rotation
     * looks along (sin a, cos a), the walker's yaw along (−sin, −cos), hence the 180°; a positive JavaFX pitch tilts
     * the view up, as the walker's does.
     */
    private void placeWalkCamera() {
        setTarget(new Point3D(walker.x, -walker.eyeY(), walker.z));
        yaw.setAngle(Math.toDegrees(walker.yaw) + 180);
        pitch.setAngle(Math.toDegrees(walker.pitch));
        dolly.setZ(0);
    }

    private record Hit(int building, int district) {
    }

    /**
     * Maps a JavaFX pick to a building or district index. Each box has exactly {@link #FACES} triangles in order, so
     * the face index divided by {@code FACES} is the box. The ground, arcs and beacons are mouse-transparent and never
     * show up here; the degenerate box of an empty city is ignored.
     */
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
    /** Time of the previous walk-mode frame, 0 when not walking. */
    private long lastWalkFrame;

    /**
     * Per-frame update ({@code now} in nanoseconds). The frame time {@code dt} is capped at 0.1 s so that after a stall
     * the arc dots do not jump. Flights use a cubic ease-in-out and height changes a cubic ease-out, as in the web
     * viewer.
     */
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
            pitch.setAngle(-(flight.fromPitch() + (flight.toPitch() - flight.fromPitch()) * e));
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
        if (walker != null) {
            // The walker gets the real frame time (it caps and substeps it itself), not the 0.1 s cap above.
            walkFrame(lastWalkFrame == 0 ? 0 : (now - lastWalkFrame) / 1e9);
            lastWalkFrame = now;
        } else {
            lastWalkFrame = 0;
        }
        for (Arc a : arcs) {
            a.t()[0] = (a.t()[0] + dt * a.speed()) % 1;
            Point3D p = bezier(a.p0(), a.p1(), a.p2(), a.t()[0]);
            a.dot().setTranslateX(p.getX());
            a.dot().setTranslateY(p.getY());
            a.dot().setTranslateZ(p.getZ());
        }
        if (beaconsVisible) {
            // Each beacon floats 1.4 units above its roof, bobs ±0.25 and spins; adding the building index to the
            // phase keeps them out of step. Beacons on hidden (zero-height) buildings are hidden.
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
