/// Native desktop viewer: the same city as the web viewer, rendered with OpenGL and a Dear ImGui interface.
///
/// This is the last stage of the pipeline (walk → parse/analyse → link dependencies → git history → city model →
/// layout → viewers) when CodeCity runs with `--gui`: instead of serving JSON to the browser viewer, it shows the
/// `CityModel` in a window. The Rust edition's desktop viewer (`rust/src/gui`) has the same features, controls and
/// preference file.
///
/// Pieces:
/// - `runGui` sets up GLFW, OpenGL and ImGui, starts the optional file watcher and runs the frame loop.
/// - `App` holds all viewer state (camera, selection, search, animations, panels) and does one frame in `App.frame`:
///   build the ImGui windows, handle mouse input, advance animations, upload changed instance data to the
///   `Renderer`, render the 3D scene. ImGui's draw data is rendered on top by `runGui` afterwards.
/// - Walk mode (G or the Walk button) swaps the orbit camera for a first-person one: `codecity.gui.walk` does the
///   movement and collisions, `App.enterWalk` / `exitWalk` / `handleWalk` the capture, keys and mouse look.
/// - History replay (R or the Replay button, only for a city built with `--history`) plays the city's growth over
///   the sampled commits: `App.enterReplay` / `exitReplay`, `App.applyReplay` for the displayed heights, footprints
///   and districts at the playback position, `App.timeline` for the bar at the bottom. The browser viewer and the
///   other desktop viewers play it back with the same rules.
/// - `codecity.gui.look` decides colours and metrics, `codecity.gui.renderer` draws, `codecity.gui.source`
///   highlights the source window.
///
/// Index invariant: `model.buildings[i]` belongs to `model.analysis.types[i]` (`buildings[i].index == i`), so one index
/// addresses a type, its building, `heights[i]` and `colors[i]`. `selected` / `hovered` are such indices, or -1.
///
/// Threads: everything here runs on the main thread, which owns the GL context and ImGui. The only other thread is the
/// file watcher's: it rebuilds the model off the main thread and hands it over through `pending` (see `runGui`).
///
/// Coordinates: world space as in `codecity.gui.math` (y up, the city centred on the origin, heights in layout units).
/// Mouse positions, ImGui positions and `project` use window coordinates (logical pixels, origin top left); only the
/// renderer and snapshots use framebuffer pixels, which differ on HiDPI screens.
module codecity.gui.app;

import codecity.city : Building, CityModel;
import codecity.github : GitHubInfo;
import codecity.gui.gl;
import codecity.gui.glfw;
import codecity.gui.imgui;
import codecity.gui.look;
import codecity.gui.math;
import codecity.gui.png : encode;
import codecity.gui.prefs : Prefs;
import codecity.gui.replay : caption, localDate, metricAt, Replay, replayColorMetric, sideAt;
import codecity.gui.rules;
import codecity.gui.renderer;
import codecity.gui.source;
import walk = codecity.gui.walk;
import codecity.layout : platform, side;
import codecity.model;
import core.stdc.stdlib : malloc;
import core.stdc.string : memcpy;
import core.sync.mutex : Mutex;
import std.algorithm : canFind, clamp, max, min, sort, SwapStrategy;
import std.array : join, replace, split;
import std.conv : to;
import std.datetime.systime : SysTime;
import std.file : exists, isDir, mkdirRecurse, readText, write;
import std.format : format;
import std.json;
import std.math : abs, ceil, cos, exp, floor, isNaN, PI, pow, sin, tan;
import std.path : buildPath, dirName;
import std.process : browse, environment;
import std.stdio : stderr, writeln;
import std.string : fromStringz, indexOf, toLower, toStringz, strip;

/// Vertical field of view of the orbit camera (42°); `camera` hands it to both the projection and `pick`. Walk mode
/// uses `walk.fov` instead.
private enum fovY = 42.0f * PI / 180;
// The UI fonts are compiled into the binary (string imports from `third_party/fonts`, see dub.json), so the viewer
// needs no font files at run time.
private immutable inter = cast(immutable(ubyte)[]) import("Inter.ttf");
private immutable jetbrains = cast(immutable(ubyte)[]) import("JetBrainsMono-Regular.ttf");

/// Scroll-wheel notches since the last frame. GLFW calls `onScroll` from inside `glfwPollEvents` on the main thread, so
/// no locking is needed; `__gshared` only because a C callback cannot reach `App`. `handleInput` consumes it.
private __gshared double scrollAccumulated = 0;
private extern (C) void onScroll(GLFWwindow*, double, double y) nothrow @nogc { scrollAccumulated += y; }

/// Walk mode's keys, latched by `onKey` when pressed, so that a tap shorter than a frame is not lost (polling
/// `glfwGetKey` once per frame misses it when frames are slow). `walkKeyPressed[k]` belongs to `walkKeys[k]`;
/// `walkClicked` latches a left-button press the same way. `App.frame` clears them after every frame, and
/// `App.enterWalk` too, so the key that started walk mode does not end it again. Main thread only, like the scroll.
private static immutable int[5] walkKeys = [GLFW_KEY_G, GLFW_KEY_ESCAPE, GLFW_KEY_SLASH, GLFW_KEY_F, GLFW_KEY_N];
private __gshared bool[5] walkKeyPressed;
private __gshared bool walkClicked;
private extern (C) void onKey(GLFWwindow*, int key, int, int action, int) nothrow @nogc
{
    if (action != GLFW_PRESS) return; // releases and auto-repeats are not new presses
    foreach (k, wk; walkKeys) if (wk == key) walkKeyPressed[k] = true;
}
private extern (C) void onMouseButton(GLFWwindow*, int button, int action, int) nothrow @nogc
{
    if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) walkClicked = true;
}

/// Opens the window and blocks until it is closed.
///
/// `watchRoot` non-empty turns on live mode: a file watcher calls `rebuild` on its own thread after changes and the
/// new model replaces the current one at the start of the next frame (keeping the camera, selection and search).
/// Throws when there is no display, GLFW cannot start, OpenGL 3.3 core is unavailable or a GL function is missing.
int runGui(CityModel model, string watchRoot, CityModel delegate() rebuild)
{
    version (linux)
        if (environment.get("DISPLAY", "").length == 0 && environment.get("WAYLAND_DISPLAY", "").length == 0)
            throw new Exception("no graphical display found (DISPLAY / WAYLAND_DISPLAY unset). Run without --gui to use the browser viewer.");
    if (!glfwInit()) throw new Exception("cannot initialise GLFW: " ~ glfwError());
    scope (exit) glfwTerminate();
    // OpenGL 3.3 core profile (forward-compatible, as macOS requires) with 4× multisampling for smooth edges
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1);
    glfwWindowHint(GLFW_SAMPLES, 4);
    auto window = glfwCreateWindow(1440, 900, (model.name ~ " · CodeCity").toStringz, null, null);
    if (window is null) throw new Exception("cannot open a window (OpenGL 3.3 needed): " ~ glfwError());
    scope (exit) glfwDestroyWindow(window);
    glfwMakeContextCurrent(window);
    glfwSwapInterval(1); // vsync: the loop below renders continuously, at most once per display refresh
    if (auto missing = loadGL()) throw new Exception("OpenGL function missing: " ~ missing);
    // installed before ImGui so that its backend chains to them
    glfwSetScrollCallback(window, &onScroll);
    glfwSetKeyCallback(window, &onKey);
    glfwSetMouseButtonCallback(window, &onMouseButton);

    igCreateContext(null);
    auto io = igGetIO_Nil();
    io.IniFilename = null; // never write imgui.ini into the working directory
    auto ui = addFont(io.Fonts, inter, 16);
    auto mono = addFont(io.Fonts, jetbrains, 14);
    // `true`: the backend installs its own GLFW input callbacks (chaining to ours); the GLSL version must match the
    // context created above
    ImGui_ImplGlfw_InitForOpenGL(window, true);
    ImGui_ImplOpenGL3_Init("#version 330");
    scope (exit)
    {
        ImGui_ImplOpenGL3_Shutdown();
        ImGui_ImplGlfw_Shutdown();
    }

    auto app = new App(model, ui, mono);
    // Hand-over from the watcher thread: it stores the newest rebuilt model in `pending` under `lock`; the frame loop
    // takes it out. Only the reference crosses threads and the watcher never touches the model afterwards. If several
    // rebuilds finish within one frame, only the last one is shown.
    auto lock = new Mutex;
    CityModel pending;
    if (watchRoot.length)
    {
        import codecity.watch : watch;
        watch(watchRoot, {
            auto next = rebuild();
            lock.lock();
            pending = next;
            lock.unlock();
        });
        writeln("Watching for changes …");
    }

    while (!glfwWindowShouldClose(window))
    {
        glfwPollEvents();
        lock.lock();
        auto next = pending;
        pending = null;
        lock.unlock();
        if (next !is null) app.setModel(next, true);

        // ImGui frame protocol: backends' NewFrame, igNewFrame, build the windows (inside `app.frame`, which also
        // renders the 3D scene), igRender, then draw ImGui's output over the scene
        ImGui_ImplOpenGL3_NewFrame();
        ImGui_ImplGlfw_NewFrame();
        igNewFrame();
        app.frame(window);
        igRender();
        ImGui_ImplOpenGL3_RenderDrawData(igGetDrawData());
        glfwSwapBuffers(window);
    }
    return 0;
}

private string glfwError()
{
    const(char)* description;
    glfwGetError(&description);
    return description is null ? "unknown error" : description.fromStringz.idup;
}

/// ImGui frees font data it owns, so hand it a malloc'd copy of the embedded font. `size` is the font's base size in
/// pixels; `igPushFont(null, size)` later draws the same font at other sizes.
private ImFont* addFont(ImFontAtlas* atlas, immutable(ubyte)[] data, float size)
{
    auto copy = malloc(data.length);
    memcpy(copy, data.ptr, data.length);
    return ImFontAtlas_AddFontFromMemoryTTF(atlas, copy, cast(int) data.length, size, null, null);
}

// ------------------------------------------------------------------ the app

/// One dependency arc for the particle animation: the quadratic Bézier's control points, the particle's speed (curve
/// parameter per second) and its current position `t` in 0..1, and its colour.
private struct Arc
{
    Vec3 p0, p1, p2;
    float speed, t;
    float[4] color;
}

/// Point at `t` on the quadratic Bézier curve p0 → p2 with control point p1.
private Vec3 bezier(Vec3 p0, Vec3 p1, Vec3 p2, float t)
{
    return p0 * ((1 - t) * (1 - t)) + p1 * (2 * (1 - t) * t) + p2 * (t * t);
}

/// The source window's content: the whole file, highlighted, with the selected type's line range.
private struct SourceView
{
    string title;
    Line[] lines;
    /// 1-based first and last line of the type, highlighted and scrolled to.
    size_t first, last;
    bool scrollPending = true;
    bool open = true;
}

/// All state of the desktop viewer, and the per-frame logic. Created once by `runGui`; `setModel` swaps the city.
///
/// Derived data is rebuilt eagerly by the method that invalidates it rather than per frame: `recolor` after anything
/// that changes colours (metric, theme, selection, hover, search), `buildArcs` via `select`, `reheight` after the
/// height metric or test toggle changes. Building instances are re-uploaded to the GPU only when `buildingsDirty`, and
/// the shadow map is only re-rendered when `geometryDirty` (heights or visibility changed), not for colour changes.
private final class App
{
    CityModel model;
    Renderer renderer;
    /// The UI font (Inter) and the code font (JetBrains Mono).
    ImFont* ui, mono;
    Prefs prefs;
    /// The colour metric in effect: the preferred one, unless the model cannot provide it (see `setModel`).
    ColorMetric colorMetric;
    Scale scale;
    Orbit orbit;
    /// Camera flight (`fly`): from `flightFrom` to `flightTarget` / `flightDistance` / `flightPitch`, starting at
    /// `flightStart`. The target, distance and (when a near-level view is tilted up) pitch animate; yaw stays as it
    /// is. Any mouse gesture or scroll cancels it.
    bool flying;
    Orbit flightFrom;
    Vec3 flightTarget;
    float flightDistance, flightPitch;
    double flightStart;
    /// Building heights by type index: the displayed ones, and the start and end of the running height animation.
    /// Hidden buildings (test code switched off, or gone today) have height 0, which also hides them from rendering
    /// and picking.
    float[] heights, heightsFrom, heightsTo;
    /// Displayed footprint side by type index: the lot's side, except during the history replay, where it follows
    /// the attribute count at the playback position (0 where the type does not exist, which hides the building).
    float[] sides;
    /// Start time of the height animation, -1 when none is running.
    double animationStart = -1, animationDuration;
    /// Current colour per building, including search dimming and hover highlight (see `recolor`).
    RGB[] colors;
    bool buildingsDirty = true, geometryDirty = true;
    /// Type indices, -1 for none; `hoveredDistrict` indexes `model.districts`.
    long selected = -1, hovered = -1, hoveredDistrict = -1;
    /// Search state: every matching type (for dimming the rest), whether a search is active (query of 2+ characters),
    /// and the best 12 matches for the drop-down.
    bool[size_t] matches;
    bool searching;
    size_t[] results;
    /// ImGui edits this NUL-terminated buffer in place; `search` is the last copy of it that was searched for.
    char[256] searchBuffer;
    string search;
    /// Whether the results drop-down is open, and whether the search field should grab keyboard focus next frame.
    bool searchOpen, focusSearch;
    /// Arcs of the selected building's dependencies, for the particles; the tube mesh itself lives in the renderer.
    Arc[] arcs;
    /// Type indices of the buildings with open pull requests (they get a beacon).
    size_t[] beaconBuildings;
    /// A district name drawn as a floating pill at a world position; `district` indexes `model.districts`.
    Label[] labels;
    SourceView source;
    bool sourceShown;
    string toastText;
    double toastUntil = 0;
    /// `glfwGetTime` seconds at the start of this frame and of the previous one.
    double time = 0, lastTime = 0;
    /// Mouse gesture state: a button went down outside the UI (`pressed`), and moved more than 4 px since
    /// (`dragging`). Positions are window coordinates; `last*` is the previous frame's for the per-frame delta.
    bool pressed, dragging;
    double pressX, pressY, lastX, lastY;
    int pressButton = -1;
    /// For district double-clicks.
    double lastClickTime = 0;
    long lastClickDistrict = -1;
    /// Half the city's diagonal (at least 20): the scale for camera distances, heights, the far plane and shadows.
    float radius = 50;
    /// The theme ImGui's base style was last set up for (ImGui styles are set once per theme change, not per frame).
    Theme stylesFor;
    bool stylesSet;
    /// Set by the Snapshot button; the snapshot is taken after this frame's 3D render, before ImGui draws.
    bool snapshotRequested;
    /// This frame's camera view-projection (for `project`).
    Mat4 viewProj;
    /// Window size in window coordinates (logical pixels), updated every frame.
    int winW = 1, winH = 1;
    /// The window, remembered each frame so that `enterWalk` / `exitWalk` can capture and release the cursor from
    /// wherever they are called (a key, a button).
    GLFWwindow* window;

    /// Walk mode: whether it is on, the first-person player, the orbit camera to restore when it ends, and when it
    /// started (for the fading hint). While walking the cursor is captured (`GLFW_CURSOR_DISABLED`) and ImGui ignores
    /// mouse and keyboard (`ConfigFlags_NoMouse` / `NoKeyboard`); `handleWalk` polls GLFW directly instead.
    bool walking;
    Walking session;
    /// The first-person player of the walk in progress.
    ref inout(walk.Walker) walker() inout return { return session.walker; }
    /// Cursor position of the previous walking frame (mouse look uses the difference); `walkMouseValid` is false on
    /// the first frame after capturing, whose position jump must not turn the view.
    double walkMouseX, walkMouseY;
    bool walkMouseValid;

    /// History replay: whether it is on, and the playback state (position, playing, speed; see
    /// `codecity.gui.replay`) over the snapshots `model.snapshots`.
    bool replaying;
    Replay replay;
    /// The present-day height scale (metric units → world units), fixed while replaying so that growth shows as
    /// growth instead of being renormalised per snapshot; recomputed only when the height metric or test toggle
    /// changes.
    double replayHeightScale = 1;
    /// The position the displayed heights and footprints were last computed for (NaN forces a recompute), and the
    /// snapshot (`replayIndex`) whose colours, districts, search results and arcs are shown (-1 forces a refresh).
    double replayApplied = double.nan;
    long replayShown = -1;

    this(CityModel model, ImFont* ui, ImFont* mono)
    {
        renderer = new Renderer;
        this.ui = ui;
        this.mono = mono;
        searchBuffer[] = 0; // D initialises char arrays to 0xFF, not to an empty C string
        prefs = Prefs.load();
        setModel(model, false);
    }

    /// Whether type `i`'s building is shown: test code can be hidden; during the history replay only the types that
    /// exist in the shown snapshot (`replayIndex`) count, otherwise only the types that exist today. Search, arcs,
    /// the inspector's dependency lists and picking all go by this.
    bool visible(size_t i) const
    {
        if (!prefs.tests && model.analysis.types[i].test) return false;
        if (replaying) return model.buildings[i].hist.exists(replayIndex);
        return !model.buildings[i].gone;
    }

    /// Whether type `i`'s building is shown in the present-day city (what `visible` says outside the replay).
    bool visibleToday(size_t i) const
    {
        return (prefs.tests || !model.analysis.types[i].test) && !model.buildings[i].gone;
    }

    /// Shows `next`. `live` is true for a rebuild after a file change: existing buildings start their height animation
    /// from their current height (matched by fully qualified name; new types from 0), the camera stays, and a toast
    /// confirms the rebuild. Otherwise (start-up) every building rises from the ground and the camera frames the whole
    /// city. In both cases the selection is restored by fully qualified name if that type still exists, and an active
    /// search is re-run.
    void setModel(CityModel next, bool live)
    {
        string previous = selected >= 0 ? model.analysis.types[selected].fqn : null;
        // current heights by fqn, since indices change meaning when types are added or removed
        float[string] oldHeights;
        if (live)
            foreach (i, h; heights) oldHeights[model.analysis.types[model.buildings[i].index].fqn] = h;
        model = next;
        selected = hovered = -1;
        // the preferred colour metric if this model supports it (git / GitHub data present), else a sensible default;
        // the preference itself is kept for the next model
        colorMetric = effectiveColor(prefs.hasColor, prefs.color, model.hasGit, model.hasPulls);
        const n = model.buildings.length;
        heights = new float[n];
        sides = new float[n];
        foreach (i, ref b; model.buildings) sides[i] = cast(float) b.lot.side;
        geometryDirty = true;
        foreach (i; 0 .. n) heights[i] = oldHeights.get(model.analysis.types[model.buildings[i].index].fqn, 0);
        radius = cast(float) cityRadius(model.width, model.depth);
        beaconBuildings = null;
        foreach (ref b; model.buildings) if (b.prs.length) beaconBuildings ~= b.index;
        applyPalette();
        buildLabels();
        reheight(!live);
        recolor();
        if (!live) frameAll(false);
        if (previous !is null)
            foreach (i, ref t; model.analysis.types)
                if (t.fqn == previous)
                {
                    select(i, false);
                    break;
                }
        if (search.length) runSearch();
        if (live) toast(format("City rebuilt · %s types", fmt(n)));
        else if (n == 0) toast("No Java, TypeScript or Rust types found here");
    }

    /// Re-creates everything whose colour comes from the theme: district platforms (one box per district from its
    /// depth's palette colour, `platform` thick, stacked by depth), the ground plane (a thin box 14 radii wide, far
    /// beyond the city so its edge is not normally visible), and via `select` the marker tint and `recolor`.
    void applyPalette()
    {
        auto p = palette(prefs.theme);
        uploadDistricts();
        renderer.setGround(Instance([0, -0.1f, 0], [radius * 14, 0.1f, radius * 14],
            [p.ground.r / 255.0f, p.ground.g / 255.0f, p.ground.b / 255.0f, 1]));
        buildingsDirty = true;
        select(selected, false);
    }

    /// Uploads the district platforms: one box per district from its depth's palette colour, `platform` thick,
    /// stacked by depth. A district that is not present in the replay's shown snapshot keeps its instance with a zero
    /// footprint, which makes it invisible.
    void uploadDistricts()
    {
        auto p = palette(prefs.theme);
        Instance[] ds;
        foreach (i, d; model.districts)
        {
            const c = hex(p.districts[min(d.depth, 5)]);
            const shown = districtShown(i);
            ds ~= Instance([cast(float) d.x, cast(float)(d.depth * platform), cast(float) d.z],
                [shown ? cast(float) d.w : 0, cast(float) platform, shown ? cast(float) d.d : 0],
                [c.r / 255.0f, c.g / 255.0f, c.b / 255.0f, 1]);
        }
        renderer.setDistricts(ds);
    }

    /// Whether district `i` is shown. During the replay: when it holds a building that exists in the shown snapshot.
    /// In the present-day view of a city with history: when it holds a type that exists today (a district of only
    /// gone types is hidden, with its label); without history always.
    bool districtShown(size_t i) const
    {
        if (replaying) return model.districtHist[i][replayIndex];
        return presentDistrict(model.districts[i], model.hasHistory);
    }

    /// Chooses the district labels: the largest districts first (by platform area), only nesting depth 1 and 2 (the
    /// top-level packages / modules and their children) and only districts containing types. The 24 largest such
    /// districts are considered; one is skipped when its anchor is within 8 units (x) and 3 units (z) of an earlier
    /// label, and at most 16 labels are kept. A label is anchored at the middle of the district's front edge, on top
    /// of its platform.
    void buildLabels()
    {
        labels = chooseLabels(model.districts);
    }

    /// Recomputes the target heights for the current height metric and starts the animation towards them: from 0
    /// when `rise` (a slower 1.4 s "city grows" effect), otherwise from the current heights (0.65 s).
    ///
    /// Heights are linear in the metric, scaled (`heightScale`) so that the 95th percentile of the visible types is
    /// `clamp(radius * 0.2, 8, 16)` units tall: big cities get taller buildings, but within limits. Every visible
    /// building is at least 0.35 tall and outliers are capped at `radius * 0.75` (`toHeight`).
    ///
    /// During the replay there is no animation: the present-day scale is recomputed for the (new) metric and the
    /// replay's heights are recomputed with it.
    void reheight(bool rise)
    {
        if (replaying)
        {
            replayHeightScale = heightScale();
            replayApplied = double.nan;
            return;
        }
        const s = heightScale();
        heightsTo = new float[model.buildings.length];
        foreach (i, ref t; model.analysis.types)
            heightsTo[i] = visibleToday(i) ? toHeight(heightValue(prefs.height, t), s) : 0;
        heightsFrom = rise ? new float[heightsTo.length] : heights.dup;
        if (rise) heightsFrom[] = 0;
        animationStart = time;
        animationDuration = rise ? 1.4 : 0.65;
    }

    /// The present-day height scale for the current height metric: world units per metric unit, from the 95th
    /// percentile of the types shown today (see `reheight`).
    double heightScale()
    {
        double[] values;
        foreach (i, ref t; model.analysis.types) if (visibleToday(i)) values ~= heightValue(prefs.height, t);
        return codecity.gui.rules.heightScale(values, radius);
    }

    /// The height of a building whose metric value is `value`, on scale `s`.
    float toHeight(double value, double s) const { return cast(float) buildingHeight(value, s, radius); }

    /// The heights buildings will have once the running animation ends (the current ones if none is running); arcs and
    /// the selection marker use these so they do not need to follow the animation.
    float[] targetHeights() { return animationStart >= 0 ? heightsTo : heights; }

    /// Recomputes every building's colour: its metric colour, mixed 80 % towards the theme's `dim` colour when it is
    /// outside the search matches or (with a selection) neither the selected building nor one of its direct
    /// dependencies / dependants, and lightened 30 % towards white while hovered. The scale is recomputed too, so it
    /// follows changes of metric and model.
    ///
    /// During the replay the colour comes from the shown snapshot's values (`Scale.colorAt`) on the present-day scale,
    /// and the open-PR metric, which only exists for today, falls back to churn.
    void recolor()
    {
        const replayColors = replaying && model.hasHistory;
        scale = Scale.of(replayColors ? replayColorMetric(colorMetric) : colorMetric, model);
        const shown = replayColors ? replayIndex : 0;
        const at = replayColors ? model.snapshots[shown].time : 0;
        const p = palette(prefs.theme);
        const(Building)* focus = selected >= 0 ? &model.buildings[selected] : null;
        colors.length = model.buildings.length;
        foreach (ref b; model.buildings)
        {
            auto c = replayColors ? scale.colorAt(model.analysis.types[b.index], b, shown, at)
                : scale.color(model.analysis.types[b.index], b);
            const outOfSearch = searching && b.index !in matches;
            colors[b.index] = shade(c, p.dim, outOfSearch, !related(focus, b.index), b.index == hovered && b.index != selected);
        }
        buildingsDirty = true;
    }

    /// Selects type `i` (-1 clears the selection), rebuilding its arcs, the translucent marker box around it (0.35
    /// wider than the lot and 0.3 taller than the building) and the colours. `fly` also moves the camera to it.
    /// Called with the current selection to refresh these after heights or the theme change.
    void select(long i, bool fly)
    {
        selected = i;
        buildArcs();
        updateMarker();
        recolor();
        if (i >= 0 && fly) flyToBuilding(i);
    }

    /// Places the translucent marker box around the selected building (0.35 wider than its footprint and 0.3 taller
    /// than the building), or removes it. Called by `select`, and every replay frame because the building's size
    /// changes while playing.
    void updateMarker()
    {
        if (selected < 0)
        {
            renderer.setMarker(null);
            return;
        }
        auto b = &model.buildings[selected];
        const s = sides[selected] + 0.35f;
        float[4] tint = [0.07f, 0.09f, 0.15f, 0.2f];
        if (night) tint = [1, 1, 1, 0.24f];
        renderer.setMarker([Instance([cast(float) b.lot.x, cast(float) b.lot.y - 0.02f, cast(float) b.lot.z],
            [s, targetHeights()[selected] + 0.3f, s], tint)]);
    }

    /// Builds the dependency arcs of the selected building: orange to each type it uses, blue from each type that uses
    /// it (only arcs whose both ends are visible, then at most 200 of each). Each arc is a quadratic Bézier from roof centre to roof centre
    /// whose control point is above the midpoint, higher for longer arcs, so arcs clear the buildings in between. It
    /// is drawn as a thin tube: 24 segments, each joining two square cross-sections ("rings" of 4 points). The tubes go
    /// into one mesh (`renderer.setArcs`); `arcs` keeps the curves for the particles that `animate` moves along them.
    void buildArcs()
    {
        arcs = null;
        ColoredVertex[] vertices;
        if (selected >= 0)
        {
            auto h = targetHeights();
            auto b = &model.buildings[selected];
            const total = b.deps.length + b.incoming.length;
            const night = prefs.theme == Theme.night;
            float[4] tint(uint c)
            {
                auto rgb = hex(c);
                if (night) rgb = mix(rgb, RGB(255, 255, 255), 0.25);
                return [rgb.r / 255.0f, rgb.g / 255.0f, rgb.b / 255.0f, 1];
            }
            Vec3 top(size_t k)
            {
                auto bk = &model.buildings[k];
                return Vec3(cast(float) bk.lot.x, cast(float) bk.lot.y + h[k], cast(float) bk.lot.z);
            }
            void add(size_t from, size_t to, float[4] color)
            {
                const p0 = top(from), p2 = top(to);
                const dist = p0.distance(p2);
                const p1 = arcControl(p0, p2);
                // tube radius: thinner when there are many arcs, slightly thicker for long ones so they stay visible
                const r = arcRadius(total, dist);
                enum segments = 24;
                Vec3[4][segments + 1] rings;
                foreach (k; 0 .. segments + 1)
                {
                    const t = cast(float) k / segments;
                    const at = bezier(p0, p1, p2, t);
                    // a frame around the curve: the tangent by central difference, `u` horizontal and perpendicular to
                    // it, `v` perpendicular to both; a vertical tangent (both ends at the same x/z position) falls back
                    // to the x axis
                    const tangent = bezier(p0, p1, p2, min(1.0f, t + 0.01f)) - bezier(p0, p1, p2, max(0.0f, t - 0.01f));
                    auto u = tangent.cross(Vec3(0, 1, 0));
                    if (u.length < 1e-6) u = Vec3(1, 0, 0);
                    u = u.normalized * r;
                    const v = tangent.cross(u).normalized * r;
                    rings[k] = [at + u, at + v, at - u, at - v];
                }
                // each side of each segment is a quad (two triangles) between consecutive rings
                foreach (k; 0 .. segments)
                    foreach (s; 0 .. 4)
                    {
                        const a = rings[k][s], bb = rings[k][(s + 1) % 4], c = rings[k + 1][(s + 1) % 4], d = rings[k + 1][s];
                        foreach (q; [a, bb, c, a, c, d]) vertices ~= ColoredVertex([q.x, q.y, q.z], color);
                    }
                import std.random : uniform01;
                // speed in curve parameter per second: about 14 / 1.2 ≈ 12 units of straight-line distance per second
                // on long arcs, at most 2.8 trips per second on short ones; a random start spreads the particles out
                arcs ~= Arc(p0, p1, p2, particleSpeed(dist), uniform01!float, color);
            }
            // which arcs: see `codecity.gui.rules.arcs`
            foreach (a; codecity.gui.rules.arcs(b, (size_t j) => visible(j)))
                add(a.from, a.to, tint(a.outgoing ? outColor : inColor));
        }
        renderer.setArcs(vertices);
        if (arcs.length == 0) renderer.setParticles(null);
    }

    /// Points the camera at the city's centre from 2.6 radii away, so the whole city fits in view; `animate` flies
    /// there, otherwise it jumps and also resets yaw and pitch.
    void frameAll(bool animate)
    {
        if (animate) fly(Vec3(0, 0, 0), radius * 2.6f);
        else orbit = Orbit.framing(radius);
    }

    /// Starts a 0.9 s camera flight to `target` / `distance` (see `frame`).
    void fly(Vec3 target, float distance)
    {
        // the orbit camera must come back exactly as it was when walk mode ends
        if (walking) return;
        // from a very flat angle, raise the camera to at least 0.35 rad (20°) during the flight so the target is not
        // hidden behind the buildings in front of it
        flightPitch = max(orbit.pitch, 0.35f);
        flightFrom = orbit;
        flightTarget = target;
        flightDistance = distance;
        flightStart = time;
        flying = true;
    }

    /// Flies to look at the middle of building `i` from far enough to see it whole (and at least 22 units away).
    void flyToBuilding(size_t i)
    {
        auto b = &model.buildings[i];
        const h = targetHeights()[i];
        fly(Vec3(cast(float) b.lot.x, cast(float) b.lot.y + h * 0.5f, cast(float) b.lot.z), max(h * 1.8f, cast(float) b.lot.side * 7, 22.0f));
    }

    /// Flies to look at the top of district `i`'s platform from far enough to see the whole platform.
    void flyToDistrict(size_t i)
    {
        auto d = model.districts[i];
        fly(Vec3(cast(float) d.x, cast(float)((d.depth + 1) * platform), cast(float) d.z), cast(float)(max(d.w, d.d) * 1.25 + 10));
    }

    /// Searches type names for `search` (case-insensitive, at least 2 characters, hidden types excluded). Ranking:
    /// exact name, then name prefix, then name substring, then a match anywhere in the fully qualified name; within a
    /// rank, shorter names first (the sort is not stable, so ties between equally long names have no fixed order).
    /// All matches are highlighted in the city; the best 12 are listed in the drop-down.
    void runSearch()
    {
        matches = null;
        results = null;
        if (utf16Length(search.strip.toLower) < minQuery)
        {
            searching = false;
            recolor();
            return;
        }
        const hits = codecity.gui.rules.search(search, model.analysis.types, (size_t i) => visible(i));
        foreach (i; hits.ranked) matches[i] = true;
        results = hits.ranked[0 .. min(maxResults, $)].dup;
        searching = true;
        recolor();
    }

    /// Shows a short message above the bottom hint for 2.6 seconds.
    void toast(string text)
    {
        toastText = text;
        toastUntil = time + 2.6;
    }

    /// Opens the source window on type `i`'s file, scrolled to and highlighting the type's lines. The file is read
    /// from disk now, so it shows the current contents (which may have changed since the analysis). The rules are
    /// those of the browser viewer's `api/source`: only a regular file is read (a symbolic link or FIFO swapped in for
    /// it is not followed or opened), files over 4 MiB are not loaded, invalid UTF-8 is replaced, and a read error is
    /// shown as the window's only line.
    void openSource(size_t i)
    {
        import std.encoding : sanitize;
        import std.file : attrIsFile, getLinkAttributes, getSize, read;
        auto t = &model.analysis.types[i];
        const unreadable = "// unable to read " ~ model.buildings[i].file;
        string code;
        try
        {
            if (!attrIsFile(getLinkAttributes(t.file))) code = unreadable;
            else if (getSize(t.file) > 4UL * 1024 * 1024) code = "// file too large to display";
            else code = sanitize(cast(string) read(t.file));
        }
        catch (Exception) code = unreadable;
        source = SourceView(format("%s:%d", model.buildings[i].file, t.line), highlight(code, t.language), t.line, t.endLine);
        sourceShown = true;
    }

    /// GitHub URL for type `i`'s file: `kind` is "blob" (file view), "blame" or "commits" (history). The path is the
    /// analysed root's position in the repository (`prefix`) plus the building's file path; each path segment is
    /// percent-encoded separately so the slashes survive. File and blame links jump to the type's line range.
    string githubLink(string kind, size_t i)
    {
        auto t = &model.analysis.types[i];
        return codecity.gui.rules.githubLink(model.github, kind, model.buildings[i].file, t.line, t.endLine);
    }

    /// Opens `url` in the default browser; failures become a toast. Only https:// links are opened (see
    /// `isWebLink`): PR URLs come from the GitHub API.
    void openUrl(string url)
    {
        if (!isWebLink(url)) return toast("Not opening a link that is not https://");
        try browse(url);
        catch (Exception e) toast("Could not open browser: " ~ e.msg);
    }

    // -------------------------------------------------------------- per frame

    /// One frame, between `igNewFrame` and `igRender`.
    ///
    /// Order matters: the ImGui windows are built first, so that `handleInput` can ask ImGui whether the mouse is
    /// over a window this frame; then input; then the height animation, instance upload, camera flight and particles;
    /// finally the 3D render (and a snapshot of it, if requested). A minimised window (size 0) skips everything.
    void frame(GLFWwindow* window)
    {
        time = glfwGetTime();
        // capped so that a stall (window dragged, machine asleep) does not make the particles jump
        const dt = cast(float) min(0.1, time - lastTime);
        // walk mode simulates the full frame time (it caps it itself at `walk.maxFrame`)
        const frameTime = cast(float)(time - lastTime);
        lastTime = time;
        int fbW, fbH;
        glfwGetFramebufferSize(window, &fbW, &fbH);
        glfwGetWindowSize(window, &winW, &winH);
        this.window = window;
        if (winW <= 0 || winH <= 0) return;

        drawInterface();
        handleInput(window, frameTime);
        walkKeyPressed[] = false;
        walkClicked = false;

        if (replaying)
        {
            replay.advance(dt); // stops (paused) at the last snapshot
            applyReplay();
        }
        else if (animationStart >= 0)
        {
            const k = cast(float) clamp((time - animationStart) / animationDuration, 0.0, 1.0);
            const e = 1 - pow(1 - k, 3); // ease-out cubic: fast start, gentle landing
            foreach (i; 0 .. heights.length) heights[i] = heightsFrom[i] + (heightsTo[i] - heightsFrom[i]) * e;
            buildingsDirty = geometryDirty = true;
            if (k >= 1)
            {
                animationStart = -1;
                // rebuild the selection's arcs and marker from the final heights
                select(selected, false);
            }
        }
        if (buildingsDirty)
        {
            Instance[] data;
            data.reserve(model.buildings.length);
            foreach (ref b; model.buildings)
            {
                // Hidden (and not yet risen) buildings keep their instance but get a zero footprint, which makes the
                // box degenerate and invisible; the height never goes to exactly 0. Instances stay in index order.
                const h = heights[b.index];
                const s = h > 0.001f ? sides[b.index] : 0;
                const c = colors[b.index];
                data ~= Instance([cast(float) b.lot.x, cast(float) b.lot.y, cast(float) b.lot.z], [s, max(h, 0.0001f), s],
                    [c.r / 255.0f, c.g / 255.0f, c.b / 255.0f, 1]);
            }
            renderer.setBuildings(data, geometryDirty);
            buildingsDirty = geometryDirty = false;
        }
        if (flying)
        {
            const k = cast(float) clamp((time - flightStart) / 0.9, 0.0, 1.0);
            const e = k < 0.5 ? 4 * k * k * k : 1 - pow(-2 * k + 2, 3) / 2; // ease-in-out cubic over 0.9 s
            orbit.target = flightFrom.target + (flightTarget - flightFrom.target) * e;
            orbit.distance = flightFrom.distance + (flightDistance - flightFrom.distance) * e;
            orbit.pitch = flightFrom.pitch + (flightPitch - flightFrom.pitch) * e;
            if (k >= 1) flying = false;
        }
        animate(dt);

        Vec3 eye, forward;
        float fov;
        camera(eye, forward, fov);
        // Near plane 0.3 units for the orbit camera, 0.05 while walking (the eye can be 0.2 from a wall); far plane 30
        // radii, well beyond the farthest zoom (6 radii) plus the ground's half-width (7 radii). Only this camera
        // projection changes: the shadow pass uses its own orthographic sun camera (`Renderer.render`), so the near
        // plane does not affect shadows.
        viewProj = Mat4.perspective(fov, cast(float) winW / winH, walking ? walk.nearPlane : 0.3f, radius * 30)
            * Mat4.lookAt(eye, eye + forward, Vec3(0, 1, 0));
        auto p = palette(prefs.theme);
        Renderer.Lighting light;
        const a = p.ambient.linear, s = p.sun.linear;
        // the shader works in linear light, so the palette's sRGB light colours are linearised before scaling
        light.ambient = Vec3(a[0], a[1], a[2]) * p.ambientIntensity;
        light.sun = Vec3(s[0], s[1], s[2]) * p.sunIntensity;
        light.glow = p.glow;
        light.windows = p.windows;
        light.shadows = prefs.shadows;
        const bg = p.background;
        renderer.render(fbW, fbH, viewProj, eye, radius, Vec3(bg.r / 255.0f, bg.g / 255.0f, bg.b / 255.0f), light);
        if (snapshotRequested)
        {
            snapshotRequested = false;
            snapshot(fbW, fbH);
        }
    }

    /// Per-frame animation of the small things: moves one particle along each dependency arc (wrapping from the end
    /// back to the start) and bobs a violet gem above every building with open PRs (bigger for more PRs, capped; the
    /// phase depends on the index so they do not bob in step). Both instance lists are rebuilt and re-uploaded every
    /// frame (the particles only while there are arcs). Particles use the unit box, whose origin is its bottom face,
    /// so they are lowered by half their size to sit centred on the curve; the gem is already centred on its origin.
    void animate(float dt)
    {
        if (arcs.length)
        {
            Instance[] data;
            foreach (ref arc; arcs)
            {
                arc.t = (arc.t + dt * arc.speed) % 1.0f;
                const at = bezier(arc.p0, arc.p1, arc.p2, arc.t);
                const size = 0.3f + arc.p0.distance(arc.p2) * 0.003f;
                data ~= Instance([at.x, at.y - size / 2, at.z], [size, size, size], arc.color);
            }
            renderer.setParticles(data);
        }
        Instance[] gems;
        // open pull requests belong to the present, so the replay shows no beacons
        if (prefs.beacons && !replaying)
            foreach (i; beaconBuildings)
            {
                auto b = &model.buildings[i];
                const h = heights[i];
                if (h <= 0.001f) continue;
                const size = 0.7f + min(1.2f, b.prs.length * 0.25f);
                const y = cast(float) b.lot.y + h + 1.4f + sin(cast(float) time * 2 + i) * 0.25f;
                const c = hex(prColor);
                gems ~= Instance([cast(float) b.lot.x, y, cast(float) b.lot.z], [size, size, size], [c.r / 255.0f, c.g / 255.0f, c.b / 255.0f, 1]);
            }
        renderer.setBeacons(gems);
    }

    /// The camera of the current mode: eye position, unit viewing direction and vertical field of view. The orbit
    /// camera looks from `orbit.position` at `orbit.target`; the walk camera from `walker.eye` along `walker.forward`.
    /// The projection and `pick` both use this, so picking rays match what is drawn.
    void camera(out Vec3 eye, out Vec3 forward, out float fov) const
    {
        if (walking)
        {
            eye = walker.eye;
            forward = walker.forward;
            fov = walk.fov;
        }
        else
        {
            eye = orbit.position;
            forward = (orbit.target - eye).normalized;
            fov = fovY;
        }
    }

    /// World → window coordinates (logical pixels); false when behind the camera. NDC y points up but window y points
    /// down, hence the flip. Points off-screen but in front of the camera return true with coordinates outside the
    /// window.
    bool project(Vec3 p, out float x, out float y)
    {
        const c = viewProj.transform(p);
        if (c[3] <= 0.01f) return false;
        x = (c[0] / c[3] * 0.5f + 0.5f) * winW;
        y = (1 - (c[1] / c[3] * 0.5f + 0.5f)) * winH;
        return true;
    }

    /// What is under the cursor at window position (`mx`, `my`): sets `building` (a type index) or `district` (an index
    /// into `model.districts`), at most one of them; -1 for nothing.
    ///
    /// Picking is done on the CPU by casting a ray from the eye through the cursor and intersecting it with every
    /// building's box (as currently animated) and every district platform (`rayBox`); there is no GPU picking buffer.
    /// The ray is built from the same camera basis as `Mat4.lookAt` and the same field of view as the projection, so
    /// it passes exactly through the pixel under the cursor: `nx`, `ny` are the cursor in NDC (-1..1, y up), scaled by
    /// tan(fov / 2) (and the aspect ratio horizontally) to reach the image plane at distance 1.
    ///
    /// The nearest building wins unless a platform is hit clearly (more than 1e-3) closer, e.g. the cursor is on a
    /// platform edge in front of the building. Hidden buildings are skipped. Cost is linear in the number of types and
    /// districts, done once per frame while hovering.
    void pick(double mx, double my, out long building, out long district)
    {
        building = district = -1;
        Vec3 eye, forward;
        float fov;
        camera(eye, forward, fov);
        const right = forward.cross(Vec3(0, 1, 0)).normalized;
        const up = right.cross(forward);
        const tanHalf = tan(fov / 2);
        const nx = cast(float)(2 * mx / winW - 1), ny = cast(float)(1 - 2 * my / winH);
        const dir = (forward + right * (nx * tanHalf * winW / winH) + up * (ny * tanHalf)).normalized;
        float best = float.max;
        foreach (ref b; model.buildings)
        {
            const h = heights[b.index];
            // during the replay, buildings still fading in or out but absent from the shown snapshot are not picked
            if (h <= 0.001f || sides[b.index] <= 0.001f || (replaying && !visible(b.index))) continue;
            const s = sides[b.index] / 2;
            const t = rayBox(eye, dir, Vec3(cast(float) b.lot.x - s, cast(float) b.lot.y, cast(float) b.lot.z - s),
                Vec3(cast(float) b.lot.x + s, cast(float) b.lot.y + h, cast(float) b.lot.z + s));
            if (t >= 0 && t < best)
            {
                best = t;
                building = b.index;
            }
        }
        float bestDistrict = float.max;
        foreach (i, d; model.districts)
        {
            if (!districtShown(i)) continue;
            const t = rayBox(eye, dir, Vec3(cast(float)(d.x - d.w / 2), cast(float)(d.depth * platform), cast(float)(d.z - d.d / 2)),
                Vec3(cast(float)(d.x + d.w / 2), cast(float)((d.depth + 1) * platform), cast(float)(d.z + d.d / 2)));
            // the topmost (deepest) platform under the cursor is hit first; on (near) ties the earlier district in
            // `model.districts` (pre-order) wins because of the 1e-4 margin
            if (t >= 0 && t < bestDistrict - 1e-4)
            {
                bestDistrict = t;
                district = i;
            }
        }
        if (building >= 0 && bestDistrict < best - 1e-3) building = -1;
        if (building >= 0) district = -1;
    }

    /// Mouse handling for the 3D view (keyboard shortcuts are in `shortcuts`).
    ///
    /// A gesture starts when any button goes down while the cursor is not over an ImGui window or widget, and lasts
    /// until all buttons are up, even if the cursor moves over the UI meanwhile. Left drag orbits, right or middle
    /// drag pans; a left press and release that moved at most 4 px (Manhattan distance) is a click. Without a gesture
    /// the cursor hover-picks a building or district (highlight and tooltip). The wheel zooms. Mouse buttons are
    /// polled each frame rather than handled by callbacks, so a press and release within one frame is missed.
    /// While walking, all of this is replaced by `handleWalk`.
    void handleInput(GLFWwindow* window, float dt)
    {
        if (walking)
        {
            handleWalk(window, dt);
            return;
        }
        double mx, my;
        glfwGetCursorPos(window, &mx, &my);
        const overUi = igIsWindowHovered(HoveredFlags_AnyWindow) || igIsAnyItemActive();
        int down = -1;
        foreach (button; [GLFW_MOUSE_BUTTON_LEFT, GLFW_MOUSE_BUTTON_RIGHT, GLFW_MOUSE_BUTTON_MIDDLE])
            if (glfwGetMouseButton(window, button) == GLFW_PRESS)
            {
                down = button;
                break;
            }
        if (down >= 0 && !pressed && !overUi)
        {
            pressed = true;
            dragging = false;
            pressButton = down;
            pressX = lastX = mx;
            pressY = lastY = my;
            flying = false;
        }
        if (pressed)
        {
            const dx = cast(float)(mx - lastX), dy = cast(float)(my - lastY);
            lastX = mx;
            lastY = my;
            if (abs(mx - pressX) + abs(my - pressY) > 4) dragging = true;
            if (pressButton == GLFW_MOUSE_BUTTON_LEFT)
            {
                // 0.006 rad per pixel; pitch stays between 0.07 rad (4°, just above the ground) and 1.5 rad (86°,
                // short of straight down, where lookAt's up vector would break)
                orbit.yaw -= dx * 0.006f;
                orbit.pitch = clamp(orbit.pitch + dy * 0.006f, 0.07f, 1.5f);
            }
            else
            {
                // Pan in the ground plane. (cy, 0, -sy) is the camera's right vector and (-sy, 0, -cy) its forward
                // vector projected onto the ground, so the city follows the cursor; the step grows with the distance
                // so panning feels the same at any zoom.
                const k = orbit.distance * 0.0016f;
                const sy = sin(orbit.yaw), cy = cos(orbit.yaw);
                orbit.target = orbit.target - Vec3(cy, 0, -sy) * (dx * k) + Vec3(-sy, 0, -cy) * (dy * k);
            }
            if (down < 0)
            {
                pressed = false;
                if (!dragging && pressButton == GLFW_MOUSE_BUTTON_LEFT) click(mx, my);
            }
        }
        else if (!overUi)
        {
            long b, d;
            pick(mx, my, b, d);
            hoveredDistrict = d;
            if (b != hovered)
            {
                hovered = b;
                recolor();
            }
        }
        else if (hovered >= 0 || hoveredDistrict >= 0)
        {
            hovered = hoveredDistrict = -1;
            recolor();
        }
        if (scrollAccumulated != 0)
        {
            if (!overUi)
            {
                flying = false;
                // exponential zoom: each notch scales the distance by e^±0.12 (about 11 %), between 4 units and 6 radii
                orbit.distance = clamp(orbit.distance * cast(float) exp(-scrollAccumulated * 0.12), 4.0f, radius * 6);
            }
            scrollAccumulated = 0; // scrolling over the UI belongs to ImGui and is dropped here
        }
    }

    // -------------------------------------------------------------- history replay

    /// The snapshot whose colours, districts, search results and arcs are shown: the playback position rounded,
    /// halves up.
    size_t replayIndex() const
    {
        return replay.snapshot;
    }

    void toggleReplay()
    {
        if (replaying) exitReplay();
        else enterReplay();
    }

    /// Starts the replay at the first snapshot, playing. The present-day height scale is taken now and kept; a
    /// running height animation is dropped (the replay sets the heights itself every frame).
    void enterReplay()
    {
        if (replaying || !model.hasHistory) return;
        replayHeightScale = heightScale();
        replaying = true;
        replay = Replay.start(model.snapshots.length);
        replayApplied = double.nan;
        replayShown = -1;
        animationStart = -1;
        applyReplay();
    }

    /// Leaves the replay and returns to the present-day city: footprints back to the lots, every district shown,
    /// heights animated back to today's, colours, search and arcs of today. A selected type that is gone today is
    /// deselected, since it is not part of the present city.
    void exitReplay()
    {
        if (!replaying) return;
        replaying = false;
        replay.playing = false;
        foreach (i, ref b; model.buildings) sides[i] = cast(float) b.lot.side;
        uploadDistricts();
        reheight(false);
        if (selected >= 0 && !visible(selected)) selected = -1;
        if (search.length) runSearch();
        select(selected, false);
        buildingsDirty = geometryDirty = true;
    }

    /// Play / pause; playing from the last snapshot starts over from the first.
    void togglePlay()
    {
        replay.toggle();
    }

    /// Jumps to position `p` and pauses.
    void seek(double p)
    {
        replay.seek(p);
    }

    /// Updates the scene for the playback position, once per frame while replaying (and only when something
    /// changed). With a = ⌊p⌋, b = min(a + 1, S − 1) and f = p − a, a building's height is the height metric
    /// interpolated between snapshots a and b on the present-day scale, and its footprint side the layout's side
    /// formula interpolated the same way, an absent type counting as 0 in both; a building absent from both is
    /// hidden. When the rounded position (`replayIndex`) changes, the colours, district platforms, search results
    /// and arcs, which all show that one snapshot, are refreshed too.
    void applyReplay()
    {
        if (replay.p == replayApplied) return;
        replayApplied = replay.p;
        const at = replay.frame;
        foreach (i, ref bd; model.buildings)
        {
            double v;
            if (!metricAt(bd.hist, prefs.height, at, v) || (!prefs.tests && model.analysis.types[i].test))
            {
                heights[i] = sides[i] = 0;
                continue;
            }
            heights[i] = toHeight(v, replayHeightScale);
            sides[i] = cast(float) sideAt(bd.hist, at);
        }
        buildingsDirty = geometryDirty = true;
        if (replayShown != replayIndex)
        {
            replayShown = replayIndex;
            uploadDistricts();
            if (search.length) runSearch();
            // rebuilds the arcs (only between buildings of this snapshot) and recolours
            select(selected, false);
        }
        else updateMarker();
    }


    // -------------------------------------------------------------- walk mode

    /// Builds the solids for `walk.Walker` from the current scene: one box per district platform (from the ground up
    /// to its top at (depth + 1) × `platform`) and one per visible building at its current, possibly animating
    /// height and footprint (the replay changes both); districts hidden by the replay are left out. Rebuilt every walking frame, so growing or hidden buildings take effect on the next step.
    walk.World walkWorld()
    {
        auto shown = new bool[model.districts.length];
        foreach (i; 0 .. shown.length) shown[i] = districtShown(i);
        return walk.World.of(model, heights, sides, shown);
    }

    void toggleWalk()
    {
        if (walking) exitWalk();
        else enterWalk();
    }

    /// Starts walk mode. The orbit camera is remembered (a running flight is stopped where it is). The player spawns
    /// on the street in front of the selected building (on its +z side, facing it), or else at the orbit target
    /// facing the way the camera looks; `walk.Walker.spawn` moves it out of any building it lands in. Then the cursor
    /// is captured and ImGui stops taking input.
    void enterWalk()
    {
        if (walking || window is null) return;
        flying = false;
        pressed = dragging = false;
        auto world = walkWorld();
        session = Walking.begin(world, selected >= 0 ? &model.buildings[selected].lot : null, orbit, time);
        walking = true;
        searchOpen = false;
        // Hide the cursor and lock it to the window; GLFW then reports unbounded virtual positions whose differences
        // are the mouse motion. Raw motion (no OS acceleration) where the platform has it.
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        // ImGui must neither hover / click windows under the hidden cursor nor take the movement keys
        igGetIO_Nil().ConfigFlags |= ConfigFlags_NoMouse | ConfigFlags_NoKeyboard;
        walkMouseValid = false;
        walkKeyPressed[] = false;
        walkClicked = false;
        scrollAccumulated = 0;
        hovered = hoveredDistrict = -1;
        recolor();
    }

    /// Ends walk mode: restores the remembered orbit camera exactly, releases the cursor and gives ImGui its input
    /// back. The selection is kept.
    void exitWalk()
    {
        if (!walking) return;
        walking = false;
        orbit = session.end();
        flying = false;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_FALSE);
        igGetIO_Nil().ConfigFlags &= ~(ConfigFlags_NoMouse | ConfigFlags_NoKeyboard);
        hovered = hoveredDistrict = -1;
        recolor();
    }

    /// Input while walking, read from GLFW (ImGui's input is switched off): the held movement keys are polled, presses
    /// of G / Esc / / / F / N and left clicks come latched from `onKey` / `onMouseButton`:
    /// - losing the window focus, G or Esc leaves walk mode; / and F leave it and then search / frame the city as
    ///   usual; N still toggles the theme;
    /// - mouse motion turns the view (`walk.Walker.look`);
    /// - W/A/S/D or the arrow keys move, Shift runs, Space jumps (`walk.Walker.step`);
    /// - the building under the crosshair (the screen centre) is hovered, and a left click selects it like a normal
    ///   click (without the camera flight) or clears the selection on empty space. The wheel does nothing.
    void handleWalk(GLFWwindow* window, float dt)
    {
        scrollAccumulated = 0;
        if (glfwGetWindowAttrib(window, GLFW_FOCUSED) == 0)
        {
            exitWalk();
            return;
        }
        bool pressedKey(size_t k) { return walkKeyPressed[k]; }
        if (pressedKey(0) || pressedKey(1))
        {
            exitWalk();
            return;
        }
        if (pressedKey(2))
        {
            exitWalk();
            focusSearch = true;
            return;
        }
        if (pressedKey(3))
        {
            exitWalk();
            frameAll(true);
            return;
        }
        if (pressedKey(4)) toggleTheme();

        double mx, my;
        glfwGetCursorPos(window, &mx, &my);
        if (walkMouseValid) walker.look(mx - walkMouseX, my - walkMouseY);
        walkMouseX = mx;
        walkMouseY = my;
        walkMouseValid = true;

        bool down(int key) { return glfwGetKey(window, key) == GLFW_PRESS; }
        walk.Input input;
        input.forward = (down(GLFW_KEY_W) || down(GLFW_KEY_UP) ? 1 : 0) - (down(GLFW_KEY_S) || down(GLFW_KEY_DOWN) ? 1 : 0);
        input.right = (down(GLFW_KEY_D) || down(GLFW_KEY_RIGHT) ? 1 : 0) - (down(GLFW_KEY_A) || down(GLFW_KEY_LEFT) ? 1 : 0);
        input.run = down(GLFW_KEY_LEFT_SHIFT) || down(GLFW_KEY_RIGHT_SHIFT);
        input.jump = down(GLFW_KEY_SPACE);
        auto world = walkWorld();
        walker.step(dt, input, world);

        // the crosshair is the hover point; districts are not hovered (their tooltip offers a double-click zoom,
        // which walk mode does not have)
        long b, d;
        pick(winW / 2.0, winH / 2.0, b, d);
        hoveredDistrict = -1;
        if (b != hovered)
        {
            hovered = b;
            recolor();
        }
        if (walkClicked)
        {
            if (b >= 0) select(b, false);
            else if (selected >= 0) select(-1, false);
        }
    }

    /// A left click in the 3D view: a building selects it and flies to it; anything else clears the selection, and a
    /// second click on the same district within 0.4 s (a double-click) also flies to that district.
    void click(double mx, double my)
    {
        long b, d;
        pick(mx, my, b, d);
        searchOpen = false;
        if (b >= 0)
        {
            select(b, true);
            return;
        }
        if (d >= 0)
        {
            if (lastClickDistrict == d && time - lastClickTime < 0.4) flyToDistrict(d);
            lastClickTime = time;
            lastClickDistrict = d;
        }
        if (selected >= 0) select(-1, false);
    }

    /// Saves the 3D view (without the interface, which is drawn later) as `<city>-codecity-<unix time>.png` in
    /// `~/Pictures` if it exists, else in the current directory, at framebuffer resolution.
    void snapshot(int w, int h)
    {
        auto pixels = renderer.readPixels(w, h);
        auto rows = new ubyte[pixels.length];
        foreach (y; 0 .. h) // OpenGL rows run bottom-up
            rows[y * w * 4 .. (y + 1) * w * 4] = pixels[(h - 1 - y) * w * 4 .. (h - y) * w * 4];
        // force opaque: blending (the selection marker) also blends into the framebuffer's alpha channel
        foreach (i; 0 .. w * h) rows[i * 4 + 3] = 255;
        import std.datetime.systime : Clock;
        const home = environment.get("HOME", ".");
        const dir = buildPath(home, "Pictures").exists && buildPath(home, "Pictures").isDir ? buildPath(home, "Pictures") : ".";
        const path = buildPath(dir, format("%s-codecity-%d.png", model.name, Clock.currTime.toUnixTime));
        try
        {
            write(path, encode(w, h, rows));
            toast("Saved " ~ path);
        }
        catch (Exception e) toast("Could not save: " ~ e.msg);
    }

    // -------------------------------------------------------------- interface

    bool night() const { return prefs.theme == Theme.night; }

    ImVec4 panelColor() const { return night ? color(13, 19, 33, 240) : color(255, 255, 255, 242); }
    ImVec4 faintColor() const { return night ? color(148, 163, 184, 26) : color(15, 23, 42, 14); }
    ImVec4 mutedColor() const { return night ? color(148, 163, 184) : color(91, 102, 120); }
    ImVec4 textColor() const { return night ? color(226, 232, 240) : color(15, 23, 42); }

    // ImGui wants C strings; the `_Unformatted` call takes a begin/end pair instead, so D slices need no NUL and no
    // copy, and `%` in the text is not interpreted.
    void text(string s) { igTextUnformatted(s.ptr, s.ptr + s.length); }
    /// The hover tooltip of the last item. `igSetItemTooltip` takes a printf format, so the text (file paths, PR URLs,
    /// type names: all from the analysed repository) goes in as a `%s` argument, never as the format itself.
    void itemTooltip(string s) { igSetItemTooltip("%s", s.toStringz); }
    void coloredText(ImVec4 c, string s) { igPushStyleColor_Vec4(Col.Text, c); text(s); igPopStyleColor(1); }
    void mutedText(string s) { coloredText(mutedColor, s); }
    /// Text at 12.5 px; `weak` (the default) in the muted colour. `igPushFont(null, size)` keeps the current font and
    /// only changes the size (ImGui 1.92 scales fonts dynamically).
    void small(string s, bool weak = true)
    {
        igPushFont(null, 12.5f);
        if (weak) mutedText(s);
        else text(s);
        igPopFont();
    }

    /// Builds all ImGui windows for this frame. The base style (dark/light) is re-applied only when the theme changed;
    /// the shared rounding, padding and panel colours are pushed around the whole interface and popped at the end
    /// (the pop counts must match the pushes: 5 style variables, 6 colours).
    void drawInterface()
    {
        if (!stylesSet || stylesFor != prefs.theme)
        {
            if (night) igStyleColorsDark(igGetStyle());
            else igStyleColorsLight(igGetStyle());
            stylesFor = prefs.theme;
            stylesSet = true;
        }
        igPushStyleVar_Float(StyleVar.WindowRounding, 14);
        igPushStyleVar_Float(StyleVar.FrameRounding, 8);
        igPushStyleVar_Float(StyleVar.WindowBorderSize, 1);
        igPushStyleVar_Vec2(StyleVar.WindowPadding, ImVec2(14, 12));
        igPushStyleVar_Vec2(StyleVar.FramePadding, ImVec2(9, 5));
        igPushStyleColor_Vec4(Col.WindowBg, panelColor);
        igPushStyleColor_Vec4(Col.PopupBg, panelColor);
        igPushStyleColor_Vec4(Col.Border, night ? color(148, 163, 184, 36) : color(15, 23, 42, 24));
        igPushStyleColor_Vec4(Col.Text, textColor);
        igPushStyleColor_Vec4(Col.FrameBg, faintColor);
        igPushStyleColor_Vec4(Col.Button, faintColor);

        drawLabels();
        topBar();
        controls();
        inspector();
        tooltip();
        sourceWindow();
        bottomOverlays();
        shortcuts();

        igPopStyleColor(6);
        igPopStyleVar(5);
    }

    /// Keyboard shortcuts. Esc closes the topmost thing, one per press: source window, search drop-down, the history
    /// replay, selection, then the search text (the order all viewers use; walk mode handles its own Esc first). It works even while typing; the letter shortcuts (F frame, N night,
    /// / search, G walk, R replay, and while replaying P play/pause, `,` / `.` step, Home / End) do not, so they can
    /// be typed into the search field.
    void shortcuts()
    {
        // while walking, `handleWalk` reads the keys from GLFW
        if (walking) return;
        const typing = igIsAnyItemActive();
        if (igIsKeyPressed_Bool(ImGuiKey.Escape, false))
        {
            if (sourceShown) sourceShown = false;
            else if (searchOpen) searchOpen = false;
            else if (replaying) exitReplay();
            else if (selected >= 0) select(-1, false);
            else if (search.length)
            {
                search = null;
                searchBuffer[] = 0;
                runSearch();
            }
        }
        if (typing) return;
        if (igIsKeyPressed_Bool(ImGuiKey.F, false)) frameAll(true);
        if (igIsKeyPressed_Bool(ImGuiKey.N, false)) toggleTheme();
        if (igIsKeyPressed_Bool(ImGuiKey.Slash, false)) focusSearch = true;
        if (igIsKeyPressed_Bool(ImGuiKey.G, false)) enterWalk();
        if (model.hasHistory && igIsKeyPressed_Bool(ImGuiKey.R, false)) toggleReplay();
        if (replaying)
        {
            const last = model.snapshots.length - 1;
            if (igIsKeyPressed_Bool(ImGuiKey.P, false)) togglePlay();
            // the previous / next whole snapshot, from wherever between two snapshots the position is
            if (igIsKeyPressed_Bool(ImGuiKey.Comma, true)) replay.step(-1);
            if (igIsKeyPressed_Bool(ImGuiKey.Period, true)) replay.step(1);
            if (igIsKeyPressed_Bool(ImGuiKey.Home, false)) seek(0);
            if (igIsKeyPressed_Bool(ImGuiKey.End, false)) seek(last);
        }
    }

    /// Switches day ↔ night, saves the preference and recolours everything.
    void toggleTheme()
    {
        prefs.theme = night ? Theme.day : Theme.night;
        prefs.save();
        applyPalette();
    }

    /// Flags for the viewer's fixed panels: placed by code every frame, never moved, resized, collapsed or saved.
    enum fixedFlags = WindowFlags.NoTitleBar | WindowFlags.NoResize | WindowFlags.NoMove | WindowFlags.NoSavedSettings
        | WindowFlags.NoCollapse | WindowFlags.NoFocusOnAppearing;

    /// Draws the district labels as rounded pills centred just above their projected anchors. They go into ImGui's
    /// background draw list: over the 3D scene but under every ImGui window. Labels are not depth-tested, so a label
    /// can show through a building in front of it.
    void drawLabels()
    {
        // hidden while walking: they are not depth-tested and would show through the walls next to the walker; the
        // preference itself is untouched, so they come back on exit if switched on
        if (!prefs.labels || walking) return;
        auto list = igGetBackgroundDrawList_Nil();
        foreach (l; labels)
        {
            float x, y;
            if (!districtShown(l.district) || !project(l.at, x, y)) continue;
            const size = igCalcTextSize(l.text.ptr, l.text.ptr + l.text.length, false, -1);
            const p0 = ImVec2(x - size.x / 2 - 8, y - size.y - 10), p1 = ImVec2(x + size.x / 2 + 8, y - 4);
            ImDrawList_AddRectFilled(list, p0, p1, night ? rgba(13, 19, 33, 215) : rgba(255, 255, 255, 220), 99, 0);
            ImDrawList_AddRect(list, p0, p1, rgba(0, 0, 0, 25), 99, 0, 1);
            ImDrawList_AddText_Vec2(list, ImVec2(p0.x + 8, p0.y + 3), night ? rgba(226, 232, 240) : rgba(15, 23, 42),
                l.text.ptr, l.text.ptr + l.text.length);
        }
    }

    /// A rounded stats chip: `value` in the text colour followed by `label` muted (or just the label). Drawn
    /// directly into the window's draw list; `igDummy` then reserves its space in the layout.
    void chip(string value, string label)
    {
        const s = value.length ? value ~ "  " ~ label : label;
        const size = igCalcTextSize(s.ptr, s.ptr + s.length, false, -1);
        const pos = igGetCursorScreenPos();
        auto dl = igGetWindowDrawList();
        ImDrawList_AddRectFilled(dl, ImVec2(pos.x, pos.y), ImVec2(pos.x + size.x + 18, pos.y + size.y + 8), igColor(faintColor), 99, 0);
        if (value.length) ImDrawList_AddText_Vec2(dl, ImVec2(pos.x + 9, pos.y + 4), igColor(textColor), value.ptr, value.ptr + value.length);
        const vw = value.length ? igCalcTextSize(value.ptr, value.ptr + value.length, false, -1).x + 6 : 0;
        ImDrawList_AddText_Vec2(dl, ImVec2(pos.x + 9 + vw, pos.y + 4), igColor(mutedColor), label.ptr, label.ptr + label.length);
        igDummy(ImVec2(size.x + 18, size.y + 8));
    }

    /// An `ImVec4` colour (0..1 floats) packed as ImGui's 32-bit `ImU32`, for draw-list calls.
    static uint igColor(ImVec4 c)
    {
        return rgba(cast(ubyte)(c.x * 255), cast(ubyte)(c.y * 255), cast(ubyte)(c.z * 255), cast(ubyte)(c.w * 255));
    }

    /// The CodeCity logo, four coloured bars, drawn at 30 × 30 px.
    void logo()
    {
        const pos = igGetCursorScreenPos();
        auto dl = igGetWindowDrawList();
        // [x, y, width, height, 0xRRGGBB] on a 32-unit design grid, scaled by k to 30 px
        static immutable float[5][4] bars = [[5, 16, 5, 11, 0x38bdf8], [11.5, 7, 5, 20, 0xf97316], [18, 12, 4, 15, 0xfacc15], [23.5, 19, 4, 8, 0x34d399]];
        foreach (b; bars)
        {
            const c = hex(cast(uint) b[4]);
            const k = 30.0f / 32;
            ImDrawList_AddRectFilled(dl, ImVec2(pos.x + b[0] * k, pos.y + b[1] * k), ImVec2(pos.x + (b[0] + b[2]) * k, pos.y + (b[1] + b[3]) * k), rgba(c.r, c.g, c.b), 1, 0);
        }
        igDummy(ImVec2(30, 30));
    }

    /// The top bar across the window: logo and city name, the search field (and below it the results drop-down while
    /// searching), and right-aligned stats chips (GitHub repository as a button, languages, counts). The pixel offsets
    /// position the items within the bar by hand.
    void topBar()
    {
        igSetNextWindowPos(ImVec2(16, 16), Cond.Always, ImVec2(0, 0));
        igSetNextWindowSize(ImVec2(winW - 32, 0), Cond.Always);
        igBegin("##topbar", null, fixedFlags | WindowFlags.NoScrollbar | WindowFlags.AlwaysAutoResize);
        const y0 = igGetCursorPosY();
        logo();
        igSameLine(0, 10);
        igBeginGroup();
        igSetCursorPosY(y0 - 2);
        text("CodeCity");
        igSetCursorPosY(y0 + 15);
        small(model.name);
        igEndGroup();
        igSameLine(240, -1);
        igSetCursorPosY(y0 + 3);
        igSetNextItemWidth(380);
        if (focusSearch)
        {
            igSetKeyboardFocusHere(0);
            focusSearch = false;
        }
        const entered = igInputTextWithHint("##search", "Search types…   ( / )", searchBuffer.ptr, searchBuffer.length, InputTextFlags_EnterReturnsTrue, null, null);
        const searchMin = igGetItemRectMin(), searchMax = igGetItemRectMax();
        if (igIsItemActive()) searchOpen = true;
        // ImGui edits the buffer in place; compare with the last search to detect typing and search on every change
        const current = searchBuffer.ptr.fromStringz.idup;
        if (current != search)
        {
            search = current;
            searchOpen = true;
            runSearch();
        }
        if (entered && results.length)
        {
            select(results[0], true);
            searchOpen = false;
        }
        // stats chips, right-aligned
        string[2][] chips;
        if (model.hasGithub)
        {
            const gh = &model.github;
            // "+" when not every open PR was fetched (`pullsSource` starts with "all" when all were)
            const prs = gh.hasPulls ? format(" · %d%s PRs", gh.pulls.length, gh.pullsSource.length >= 3 && gh.pullsSource[0 .. 3] == "all" ? "" : "+") : "";
            chips ~= [gh.repo.owner ~ "/" ~ gh.repo.name ~ prs, ""];
        }
        const s = &model.stats;
        if (s.languages.length)
        {
            size_t total;
            foreach (l; s.languages) total += l.count;
            string[] parts;
            foreach (l; s.languages)
                // percentage of types per language, rounded to nearest
                parts ~= s.languages.length > 1 ? format("%s %d%%", l.language.displayName, (100 * l.count + total / 2) / total) : l.language.displayName;
            chips ~= ["", parts.join(" · ")];
        }
        chips ~= [fmt(s.types), "types"];
        chips ~= [fmt(s.packages), "packages"];
        chips ~= [fmt(s.methods), "methods"];
        chips ~= [fmt(s.loc), "LOC"];
        if (s.failedFiles) chips ~= [fmt(s.failedFiles), "parse errors"];
        float width = 0;
        foreach (c; chips)
        {
            const t = c[0].length ? c[0] ~ "  " ~ c[1] : c[1];
            width += igCalcTextSize(t.ptr, t.ptr + t.length, false, -1).x + 18 + 6;
        }
        // right-align: bar width (window minus 16 px margins) minus the right padding (14) minus the chips' width
        // (text + 18 px chip padding + 6 px spacing each; the GitHub button is assumed to be about as wide as a chip)
        igSameLine(winW - 32 - 14 - width, -1);
        igSetCursorPosY(y0 + 3);
        foreach (i, c; chips)
        {
            if (i) igSameLine(0, 6);
            if (i == 0 && model.hasGithub)
            {
                if (igSmallButton(c[0].toStringz)) openUrl(model.github.repo.webUrl);
                itemTooltip("Open on GitHub");
            }
            else chip(c[0], c[1]);
        }
        igEnd();

        if (searchOpen && searching)
        {
            igSetNextWindowPos(ImVec2(searchMin.x, searchMax.y + 6), Cond.Always, ImVec2(0, 0));
            igSetNextWindowSize(ImVec2(max(380, searchMax.x - searchMin.x), 0), Cond.Always);
            igBegin("##results", null, fixedFlags | WindowFlags.AlwaysAutoResize);
            if (results.length == 0) mutedText("No matching types");
            foreach (i; results)
            {
                auto t = &model.analysis.types[i];
                igPushID_Int(cast(int) i);
                if (igSelectable_Bool((t.name ~ "\n" ~ (t.pkg.length ? t.pkg : "(root)")).toStringz, false, 0, ImVec2(0, 0)))
                {
                    select(i, true);
                    searchOpen = false;
                }
                igPopID();
            }
            const more = matches.length > results.length ? matches.length - results.length : 0;
            if (more) small(format("%d more highlighted in the city", more));
            igEnd();
        }
    }

    /// The left panel: height and colour metric pickers (colour metrics the model cannot provide are disabled with
    /// the reason), the legend, the display toggles and the Day/Night, Frame and Snapshot buttons. Every change is
    /// saved to the preferences at once.
    void controls()
    {
        igSetNextWindowPos(ImVec2(16, 92), Cond.Always, ImVec2(0, 0));
        igSetNextWindowSize(ImVec2(240, 0), Cond.Always);
        igBegin("##controls", null, fixedFlags | WindowFlags.AlwaysAutoResize);
        small("HEIGHT");
        igSetNextItemWidth(-1);
        if (igBeginCombo("##height", heightLabels[prefs.height].toStringz, 0))
        {
            foreach (i, label; heightLabels)
                if (igSelectable_Bool(label.toStringz, prefs.height == i, 0, ImVec2(0, 0)))
                {
                    prefs.height = cast(HeightMetric) i;
                    prefs.save();
                    reheight(false);
                }
            igEndCombo();
        }
        igSpacing();
        small("COLOUR");
        igSetNextItemWidth(-1);
        if (igBeginCombo("##colour", colorLabels[colorMetric].toStringz, 0))
        {
            foreach (i, label; colorLabels)
            {
                const why = missing(cast(ColorMetric) i, model.hasGit, model.hasPulls);
                igBeginDisabled(why !is null);
                if (igSelectable_Bool((label ~ why).toStringz, colorMetric == i, 0, ImVec2(0, 0)))
                {
                    colorMetric = cast(ColorMetric) i;
                    prefs.color = colorMetric;
                    prefs.hasColor = true;
                    prefs.save();
                    recolor();
                }
                igEndDisabled();
            }
            igEndCombo();
        }
        legend();
        igSpacing();
        if (igCheckbox("Test code", &prefs.tests))
        {
            prefs.save();
            // hiding tests may hide the selection; heights, colours and search results all depend on visibility
            if (selected >= 0 && !visible(selected)) select(-1, false);
            reheight(false);
            recolor();
            if (search.length) runSearch();
        }
        if (igCheckbox("District labels", &prefs.labels)) prefs.save();
        if (model.hasPulls && igCheckbox("Open-PR beacons", &prefs.beacons)) prefs.save();
        if (igCheckbox("Shadows", &prefs.shadows))
        {
            prefs.save();
            renderer.invalidateShadows();
        }
        igSpacing();
        const w = (igGetContentRegionAvail().x - 12) / 3;
        if (igButton(night ? "Day" : "Night", ImVec2(w, 28))) toggleTheme();
        igSameLine(0, 6);
        if (igButton("Frame", ImVec2(w, 28))) frameAll(true);
        igSameLine(0, 6);
        if (igButton("Snapshot", ImVec2(w, 28))) snapshotRequested = true;
        itemTooltip("Save a PNG to ~/Pictures (or the current directory)");
        // the Walk button, highlighted (white on blue) while walk mode is on
        if (walking)
        {
            igPushStyleColor_Vec4(Col.Button, color(56, 139, 253));
            igPushStyleColor_Vec4(Col.Text, color(255, 255, 255));
        }
        if (igButton(walking ? "Walking  ·  G / Esc to exit" : "Walk  (G)", ImVec2(igGetContentRegionAvail().x, 28))) toggleWalk();
        if (walking) igPopStyleColor(2);
        itemTooltip("Walk through the city in first person");
        // the Replay button (only with history), highlighted like Walk while the replay runs
        if (model.hasHistory)
        {
            if (replaying)
            {
                igPushStyleColor_Vec4(Col.Button, color(56, 139, 253));
                igPushStyleColor_Vec4(Col.Text, color(255, 255, 255));
            }
            if (igButton(replaying ? "Replaying  ·  R / Esc to exit" : "Replay  (R)", ImVec2(igGetContentRegionAvail().x, 28)))
                toggleReplay();
            if (replaying) igPopStyleColor(2);
            itemTooltip("Watch the city grow commit by commit");
        }
        igEnd();
    }

    /// Legend for the colour metric: for "Type kind", a two-column list of the kinds present in this model with their
    /// swatches; otherwise the ramp as 64 small rectangles, the scale's end values (`hi` with "+", as everything above
    /// it has the same colour) and the words describing both ends. For `age` the scale is inverted (old is cold), so
    /// the values swap sides.
    void legend()
    {
        auto dl = igGetWindowDrawList();
        if (scale.metric == ColorMetric.kind)
        {
            bool[string] present;
            foreach (ref t; model.analysis.types) present[t.kind] = true;
            size_t n;
            foreach (k; kinds)
            {
                if (k.name !in present) continue;
                if (n % 2) igSameLine(110, -1);
                const pos = igGetCursorScreenPos();
                const c = hex(k.color);
                ImDrawList_AddRectFilled(dl, ImVec2(pos.x, pos.y + 4), ImVec2(pos.x + 10, pos.y + 14), rgba(c.r, c.g, c.b), 3, 0);
                igDummy(ImVec2(14, 0));
                igSameLine(0, 0);
                small(k.name, false);
                n++;
            }
            return;
        }
        const pos = igGetCursorScreenPos();
        const width = igGetContentRegionAvail().x;
        enum steps = 64;
        foreach (k; 0 .. steps)
        {
            const c = rampColor(cast(double) k / (steps - 1));
            ImDrawList_AddRectFilled(dl, ImVec2(pos.x + width * k / steps, pos.y + 4), ImVec2(pos.x + width * (k + 1) / steps + 0.5f, pos.y + 12), rgba(c.r, c.g, c.b), 0, 0);
        }
        igDummy(ImVec2(width, 14));
        const lo = scale.show(scale.lo), hi = scale.show(scale.hi) ~ "+";
        const invert = scale.metric == ColorMetric.age;
        foreach (row; [[invert ? hi : lo, invert ? lo : hi], colorWords[scale.metric]])
        {
            small(row[0]);
            // measured at the base font size (16 px), scaled to the 12.5 px that `small` draws with
            const rw = igCalcTextSize(row[1].ptr, row[1].ptr + row[1].length, false, -1).x * 12.5f / 16;
            igSameLine(width - rw + 14, -1);
            small(row[1]);
        }
    }

    /// The right panel for the selected type: name, package and module, kind / language / test badges, a grid of
    /// metrics (plus git history when available), the file location with Source and GitHub buttons, open pull
    /// requests touching the file, the 15 most complex methods with bars, and the types it uses / is used by as
    /// clickable chips (hidden test types left out). Anchored to the right edge with pivot (1, 0).
    void inspector()
    {
        if (selected < 0) return;
        const i = cast(size_t) selected;
        auto t = &model.analysis.types[i];
        auto b = &model.buildings[i];
        igSetNextWindowPos(ImVec2(winW - 16, 92), Cond.Always, ImVec2(1, 0));
        igSetNextWindowSize(ImVec2(360, winH - 160), Cond.Always);
        igBegin("##inspector", null, fixedFlags);
        igPushFont(null, 19);
        text(t.name);
        igPopFont();
        igSameLine(igGetWindowWidth() - 40, -1);
        if (igSmallButton(" × ")) select(-1, false);
        itemTooltip("Close (Esc)");
        igPushFont(mono, 12);
        mutedText(packageLine(*t));
        igPopFont();
        badge(t.kind, kindColor(t.kind), true);
        igSameLine(0, 6);
        badge(t.language.displayName, RGB(0, 0, 0), false);
        if (t.test)
        {
            igSameLine(0, 6);
            badge("test", RGB(0, 0, 0), false);
        }
        if (b.gone)
        {
            igSpacing();
            mutedText(removed);
        }
        if (replaying)
        {
            // the details below stay today's (or, for a gone type, its last seen ones); this box gives the
            // snapshot's values as four tiles, or says that the type does not exist there
            const k = replayIndex;
            igSpacing();
            text(replayHeading(localDate(model.snapshots[k].time)));
            const replayed = replayCells(*b, k);
            if (replayed is null) small(notInSnapshot);
            else if (igBeginTable("##replay-metrics", 4, TableFlags_SizingStretchSame, ImVec2(0, 0), 0))
            {
                foreach (c; replayed)
                {
                    igTableNextColumn();
                    text(c[0]);
                    small(c[1]);
                }
                igEndTable();
            }
        }
        igSpacing();
        size_t[] uses, usedBy;
        foreach (j; b.deps) if (visible(j)) uses ~= j;
        foreach (j; b.incoming) if (visible(j)) usedBy ~= j;
        const cells = metricCells(*t, *b, model.hasGit, uses.length, usedBy.length, now());
        if (igBeginTable("##metrics", 3, TableFlags_SizingStretchSame, ImVec2(0, 0), 0))
        {
            foreach (c; cells)
            {
                igTableNextColumn();
                const pos = igGetCursorScreenPos();
                const w = igGetContentRegionAvail().x;
                ImDrawList_AddRectFilled(igGetWindowDrawList(), pos, ImVec2(pos.x + w, pos.y + 48), igColor(faintColor), 10, 0);
                const x0 = igGetCursorPosX();
                igDummy(ImVec2(0, 2));
                igSetCursorPosX(x0 + 8);
                igPushFont(null, 17);
                text(c[0]);
                igPopFont();
                igSetCursorPosX(x0 + 8);
                small(c[1]);
                igDummy(ImVec2(0, 6));
            }
            igEndTable();
        }
        igSpacing();
        igPushFont(mono, 12);
        mutedText(format("%s:%d", b.file, t.line));
        igPopFont();
        itemTooltip(b.file);
        if (igButton("Source", ImVec2(0, 0))) openSource(i);
        if (model.hasGithub)
        {
            igSameLine(0, 6);
            foreach (k, pair; [["File on GitHub", "blob"], ["Blame", "blame"], ["History", "commits"]])
            {
                if (k) igSameLine(0, 6);
                if (igButton(pair[0].toStringz, ImVec2(0, 0))) openUrl(githubLink(pair[1], i));
            }
            if (model.github.hasPulls)
            {
                section("OPEN PULL REQUESTS", b.prs.length);
                foreach (k; b.prs)
                {
                    auto pr = &model.github.pulls[k];
                    igPushID_Int(cast(int) k);
                    const label = format("#%d  %s\n%s", pr.number, pr.title, prMeta(*pr, now()));
                    if (igSelectable_Bool(label.toStringz, false, 0, ImVec2(0, 0))) openUrl(pr.url);
                    itemTooltip(pr.url);
                    igPopID();
                }
                if (b.prs.length == 0)
                    small(noPulls(model.github.pullsSource));
            }
        }
        if (b.methods.length)
        {
            section("METHODS BY COMPLEXITY", t.methods.length);
            // `b.methods` is sorted by complexity, most complex first (see codecity.city). Bar lengths are relative to
            // the most complex method; bar colours use at least 10 as the top so a type of simple methods is not red.
            uint maxCx = 1;
            foreach (m; b.methods) maxCx = max(maxCx, m.complexity);
            const width = igGetContentRegionAvail().x;
            foreach (m; b.methods[0 .. min(inspectorMethods, $)])
            {
                igPushFont(mono, 12.5f);
                text(methodName(m.name, m.parameters));
                igPopFont();
                itemTooltip(format("line %d · %d LOC · %d params", m.line, m.loc, m.parameters));
                const value = format("cx %d · %d loc", m.complexity, m.loc);
                const vw = igCalcTextSize(value.ptr, value.ptr + value.length, false, -1).x * 12.5f / 16;
                igSameLine(width - vw + 14, -1);
                small(value);
                const pos = igGetCursorScreenPos();
                auto dl = igGetWindowDrawList();
                ImDrawList_AddRectFilled(dl, pos, ImVec2(pos.x + width, pos.y + 3), igColor(faintColor), 2, 0);
                const c = rampColor(cast(double) m.complexity / max(10, maxCx));
                ImDrawList_AddRectFilled(dl, pos, ImVec2(pos.x + width * m.complexity / maxCx, pos.y + 3), rgba(c.r, c.g, c.b), 2, 0);
                igDummy(ImVec2(width, 5));
            }
            // counted on `t.methods` like the section header: `b.methods` is capped at `maxMethods` (300) in
            // codecity.city
            if (const more = moreMethods(t.methods.length)) small(more);
        }
        foreach (pass; 0 .. 2)
        {
            auto list = pass == 0 ? uses : usedBy;
            section(pass == 0 ? "USES" : "USED BY", list.length);
            const avail = igGetContentRegionAvail().x;
            float x = 0;
            const accent = hex(pass == 0 ? outColor : inColor);
            foreach (j; list)
            {
                auto name = model.analysis.types[j].name;
                const w = igCalcTextSize(name.ptr, name.ptr + name.length, false, -1).x + 18;
                // flow layout: stay on this line while the chip fits, else start a new one
                if (x > 0 && x + w < avail) igSameLine(0, 6);
                else x = 0;
                x += w + 6;
                igPushID_Int(cast(int) j);
                igPushStyleColor_Vec4(Col.Border, color(accent.r, accent.g, accent.b));
                if (igSmallButton(name.toStringz)) select(j, true);
                igPopStyleColor(1);
                itemTooltip(model.analysis.types[j].fqn);
                igPopID();
            }
            if (list.length == 0) small(pass == 0 ? "nothing in this project" : "no project types");
        }
        igEnd();
    }

    /// A small pill: white text on `fill` when `solid`, otherwise muted text on the faint background.
    void badge(string label, RGB fill, bool solid)
    {
        igPushFont(null, 12.5f);
        const size = igCalcTextSize(label.ptr, label.ptr + label.length, false, -1);
        const pos = igGetCursorScreenPos();
        auto dl = igGetWindowDrawList();
        const w = size.x + 16, h = size.y + 6;
        ImDrawList_AddRectFilled(dl, pos, ImVec2(pos.x + w, pos.y + h), solid ? rgba(fill.r, fill.g, fill.b) : igColor(faintColor), 99, 0);
        ImDrawList_AddText_Vec2(dl, ImVec2(pos.x + 8, pos.y + 3), solid ? rgba(255, 255, 255) : igColor(mutedColor), label.ptr, label.ptr + label.length);
        igPopFont();
        igDummy(ImVec2(w, h));
    }

    /// An inspector section heading with its item count right-aligned.
    void section(string title, size_t count)
    {
        igSpacing();
        igSpacing();
        small(title);
        const c = count.to!string;
        const w = igCalcTextSize(c.ptr, c.ptr + c.length, false, -1).x * 12.5f / 16;
        igSameLine(igGetWindowWidth() - w - 18, -1);
        small(c);
    }

    /// The hover tooltip for a building (name, kind, package, key metrics) or a district other than the city itself.
    /// Not shown during a mouse gesture or while the cursor is over an ImGui window. While walking it shows the
    /// building under the crosshair, just below the screen centre.
    void tooltip()
    {
        if (!walking && (pressed || igIsWindowHovered(HoveredFlags_AnyWindow))) return;
        string title, sub, row;
        if (hovered >= 0)
        {
            auto t = &model.analysis.types[hovered];
            auto b = &model.buildings[hovered];
            title = t.name ~ "  ·  " ~ t.kind;
            sub = t.pkg;
            if (replaying)
            {
                // the shown snapshot's values (picking only hovers buildings that exist in it)
                const k = replayIndex;
                row = replayTooltipRow(*b, k, localDate(model.snapshots[k].time));
            }
            else
            {
                row = tooltipRow(*t, *b);
            }
        }
        else if (hoveredDistrict >= 0 && model.districts[hoveredDistrict].depth > 0)
        {
            const card = districtTooltip(model.districts[hoveredDistrict], walking);
            title = card[0];
            sub = card[1];
            row = card[2];
        }
        else return;
        igPushStyleColor_Vec4(Col.PopupBg, color(15, 23, 42, 236));
        igPushStyleColor_Vec4(Col.Text, color(255, 255, 255));
        // a tooltip follows the mouse unless positioned explicitly; the captured cursor is meaningless while walking
        if (walking) igSetNextWindowPos(ImVec2(winW / 2.0f, winH / 2.0f + 22), Cond.Always, ImVec2(0.5f, 0));
        igBeginTooltip();
        text(title);
        igPushFont(mono, 11.5f);
        igPushStyleColor_Vec4(Col.Text, color(148, 163, 184));
        text(sub);
        igPopStyleColor(1);
        igPopFont();
        igPushStyleColor_Vec4(Col.Text, color(203, 213, 225));
        small(row, false);
        igPopStyleColor(1);
        igEndTooltip();
        igPopStyleColor(2);
    }

    /// The movable source window with the highlighted file.
    ///
    /// Files can have thousands of lines, so the list is virtualised: every row has the fixed height `lineH`, only
    /// the rows in view (plus 40 below) are submitted, and the cursor is positioned by hand so the scroll range still
    /// covers all `n` lines (the final `igDummy` at `n * lineH` extends the content). On opening it scrolls once so
    /// the type's first line is the fourth row, and marks the type's lines with a blue band and bar. `###source` gives
    /// the window a fixed ID while its visible title changes.
    void sourceWindow()
    {
        if (!sourceShown) return;
        igSetNextWindowSize(ImVec2(980, 680), Cond.Appearing);
        igSetNextWindowPos(ImVec2(winW / 2.0f, winH / 2.0f), Cond.Appearing, ImVec2(0.5f, 0.5f));
        igPushStyleColor_Vec4(Col.WindowBg, color(13, 17, 23));
        igPushStyleColor_Vec4(Col.Text, color(201, 209, 217));
        bool open = true;
        igBegin((source.title ~ "###source").toStringz, &open, WindowFlags.NoSavedSettings | WindowFlags.NoCollapse);
        igBeginChild_Str("##code", ImVec2(0, 0), 0, WindowFlags.HorizontalScrollbar);
        igPushFont(mono, 13);
        const lineH = igGetTextLineHeight() + 2;
        if (source.scrollPending)
        {
            igSetScrollY_Float((source.first > 4 ? source.first - 4 : 0) * lineH);
            source.scrollPending = false;
        }
        const n = source.lines.length;
        const scrollY = igGetScrollY();
        const visibleRows = cast(size_t)(igGetContentRegionAvail().y / lineH) + 3;
        const first = min(cast(size_t)(scrollY / lineH), n);
        const last = min(first + visibleRows + 40, n);
        igSetCursorPosY(first * lineH);
        const digits = n.to!string.length;
        auto dl = igGetWindowDrawList();
        foreach (row; first .. last)
        {
            const pos = igGetCursorScreenPos();
            if (row + 1 >= source.first && row + 1 <= source.last)
            {
                ImDrawList_AddRectFilled(dl, ImVec2(pos.x - 8, pos.y), ImVec2(pos.x + 4000, pos.y + lineH), rgba(56, 139, 253, 30), 0, 0);
                ImDrawList_AddRectFilled(dl, ImVec2(pos.x - 8, pos.y), ImVec2(pos.x - 6, pos.y + lineH), rgba(56, 139, 253), 0, 0);
            }
            const number = format("%*d   ", digits, row + 1);
            coloredText(color(110, 118, 129), number);
            foreach (tok; source.lines[row])
            {
                igSameLine(0, 0);
                const c = styleColor(tok.style);
                coloredText(color(cast(ubyte)(c >> 16), cast(ubyte)(c >> 8), cast(ubyte) c), tok.text);
            }
            igSetCursorPosY((row + 1) * lineH);
        }
        igSetCursorPosY(n * lineH);
        igDummy(ImVec2(1, 1));
        igPopFont();
        igEndChild();
        igEnd();
        igPopStyleColor(2);
        if (!open) sourceShown = false;
    }

    /// The hint pill at the bottom and, above it, the current toast. Both ignore mouse input (`NoInputs`), so they do
    /// not block clicks into the city. Centring uses the text width measured at 16 px, scaled for the 12.5 px hint.
    void bottomOverlays()
    {
        void pill(string id, float y, string message, bool strong)
        {
            const size = igCalcTextSize(message.ptr, message.ptr + message.length, false, -1);
            const scaled = strong ? size.x : size.x * 12.5f / 16;
            igSetNextWindowPos(ImVec2(winW / 2.0f - scaled / 2 - 14, winH - y), Cond.Always, ImVec2(0, 0));
            igPushStyleVar_Float(StyleVar.WindowRounding, 99);
            igBegin(id.toStringz, null, fixedFlags | WindowFlags.AlwaysAutoResize | WindowFlags.NoInputs);
            if (strong) text(message);
            else small(message);
            igEnd();
            igPopStyleVar(1);
        }
        if (walking)
        {
            // the crosshair: two thin lines crossing at the screen centre, dark by day and light at night
            auto list = igGetBackgroundDrawList_Nil();
            const cx = winW / 2.0f, cy = winH / 2.0f;
            const c = night ? rgba(226, 232, 240, 220) : rgba(15, 23, 42, 200);
            ImDrawList_AddRectFilled(list, ImVec2(cx - 8, cy - 0.75f), ImVec2(cx + 8, cy + 0.75f), c, 0, 0);
            ImDrawList_AddRectFilled(list, ImVec2(cx - 0.75f, cy - 8), ImVec2(cx + 0.75f, cy + 8), c, 0, 0);
            // the controls hint: fully visible for 5 s, then fading out over the sixth second
            const alpha = cast(float) clamp(6 - (time - session.since), 0.0, 1.0);
            if (alpha > 0)
            {
                igPushStyleVar_Float(StyleVar.Alpha, alpha);
                pill("##walkhint", replaying ? 100 : 40, "WASD move · Mouse look · Shift run · Space jump · Click select · G/Esc exit", false);
                igPopStyleVar(1);
            }
        }
        // the timeline takes the hint's place at the bottom while replaying; the toast moves above it
        else if (!replaying)
            pill("##hint", 40, "drag to orbit · right-drag to pan · scroll to zoom · click a building · / search · F frame · N night · G walk"
                ~ (model.hasHistory ? " · R replay" : "") ~ " · Esc clear", false);
        if (replaying) timeline();
        if (time < toastUntil) pill("##toast", replaying ? 120 : 86, toastText, true);
    }

    /// The replay's timeline bar at the bottom centre:
    /// `[play/pause] [slider over 0..S−1] date · short sha · subject — author [speed] [Now]`. The play button draws
    /// its icon itself (a triangle, or two bars while playing). Dragging the slider moves the position and pauses;
    /// the speed button cycles 1× → 2× → 4× → 0.5×; Now leaves the replay. The text describes the shown snapshot
    /// (`replayIndex`), the subject cut to 60 characters. The bar has a fixed width (1200 px, or the window less its
    /// margins), with the speed and Now buttons at its right end, so it does not jump about while the text changes.
    void timeline()
    {
        const k = replayIndex;
        const snap = &model.snapshots[k];
        const width = min(winW - 32.0f, 1200.0f);
        igSetNextWindowPos(ImVec2(winW / 2.0f, winH - 16.0f), Cond.Always, ImVec2(0.5f, 1));
        igSetNextWindowSize(ImVec2(width, 0), Cond.Always);
        igPushStyleVar_Float(StyleVar.WindowRounding, 12);
        igBegin("##timeline", null, fixedFlags | WindowFlags.AlwaysAutoResize | WindowFlags.NoScrollbar);
        if (igButton("##play", ImVec2(30, 26))) togglePlay();
        itemTooltip(replay.playing ? "Pause (P)" : "Play (P)");
        {
            const lo = igGetItemRectMin(), hi = igGetItemRectMax();
            const cx = (lo.x + hi.x) / 2, cy = (lo.y + hi.y) / 2;
            auto dl = igGetWindowDrawList();
            const c = igColor(textColor);
            if (replay.playing)
            {
                ImDrawList_AddRectFilled(dl, ImVec2(cx - 5, cy - 6), ImVec2(cx - 1.5f, cy + 6), c, 1, 0);
                ImDrawList_AddRectFilled(dl, ImVec2(cx + 1.5f, cy - 6), ImVec2(cx + 5, cy + 6), c, 1, 0);
            }
            else ImDrawList_AddTriangleFilled(dl, ImVec2(cx - 4, cy - 7), ImVec2(cx - 4, cy + 7), ImVec2(cx + 7, cy), c);
        }
        igSameLine(0, 10);
        igSetNextItemWidth(280);
        float position = cast(float) replay.p;
        if (igSliderFloat("##position", &position, 0, cast(float)(model.snapshots.length - 1), "", SliderFlags_NoInput))
            seek(position);
        igSameLine(0, 12);
        // the subject cut to 60 characters (code points, not bytes, so a multi-byte character is never split)
        text(caption(*snap));
        // right-aligned: the window's right padding (14), Now (46), the gap (6) and the speed button (46)
        igSameLine(width - 14 - 46 - 6 - 46, -1);
        if (igButton(replay.speedLabel.toStringz, ImVec2(46, 26)))
            replay.nextSpeed();
        itemTooltip("Playback speed");
        igSameLine(0, 6);
        if (igButton("Now", ImVec2(46, 26))) exitReplay();
        itemTooltip("Back to today's city (R / Esc)");
        igEnd();
        igPopStyleVar(1);
    }
}
