/// Native desktop viewer: the same city as the web viewer, rendered with OpenGL and a Dear ImGui interface.
module codecity.gui.app;

import codecity.city : Building, CityModel;
import codecity.github : GitHubInfo;
import codecity.gui.gl;
import codecity.gui.glfw;
import codecity.gui.imgui;
import codecity.gui.look;
import codecity.gui.math;
import codecity.gui.png : encode;
import codecity.gui.renderer;
import codecity.gui.source;
import codecity.layout : platform;
import codecity.model;
import core.stdc.stdlib : malloc;
import core.stdc.string : memcpy;
import core.sync.mutex : Mutex;
import std.algorithm : canFind, clamp, max, min, sort;
import std.array : join, replace, split;
import std.conv : to;
import std.file : exists, isDir, mkdirRecurse, readText, write;
import std.format : format;
import std.json;
import std.math : abs, cos, exp, floor, PI, pow, sin, tan;
import std.path : buildPath, dirName;
import std.process : browse, environment;
import std.stdio : stderr, writeln;
import std.string : fromStringz, indexOf, toLower, toStringz, strip;
import std.uri : encodeComponent;

private enum fovY = 42.0f * PI / 180;
private immutable inter = cast(immutable(ubyte)[]) import("Inter.ttf");
private immutable jetbrains = cast(immutable(ubyte)[]) import("JetBrainsMono-Regular.ttf");

private __gshared double scrollAccumulated = 0;
private extern (C) void onScroll(GLFWwindow*, double, double y) nothrow @nogc { scrollAccumulated += y; }

/// Opens the window and blocks until it is closed.
int runGui(CityModel model, string watchRoot, CityModel delegate() rebuild)
{
    version (linux)
        if (environment.get("DISPLAY", "").length == 0 && environment.get("WAYLAND_DISPLAY", "").length == 0)
            throw new Exception("no graphical display found (DISPLAY / WAYLAND_DISPLAY unset). Run without --gui to use the browser viewer.");
    if (!glfwInit()) throw new Exception("cannot initialise GLFW: " ~ glfwError());
    scope (exit) glfwTerminate();
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1);
    glfwWindowHint(GLFW_SAMPLES, 4);
    auto window = glfwCreateWindow(1440, 900, (model.name ~ " · CodeCity").toStringz, null, null);
    if (window is null) throw new Exception("cannot open a window (OpenGL 3.3 needed): " ~ glfwError());
    scope (exit) glfwDestroyWindow(window);
    glfwMakeContextCurrent(window);
    glfwSwapInterval(1);
    if (auto missing = loadGL()) throw new Exception("OpenGL function missing: " ~ missing);
    glfwSetScrollCallback(window, &onScroll); // installed before ImGui so its backend chains to it

    igCreateContext(null);
    auto io = igGetIO_Nil();
    io.IniFilename = null; // never write imgui.ini into the working directory
    auto ui = addFont(io.Fonts, inter, 16);
    auto mono = addFont(io.Fonts, jetbrains, 14);
    ImGui_ImplGlfw_InitForOpenGL(window, true);
    ImGui_ImplOpenGL3_Init("#version 330");
    scope (exit)
    {
        ImGui_ImplOpenGL3_Shutdown();
        ImGui_ImplGlfw_Shutdown();
    }

    auto app = new App(model, ui, mono);
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

/// ImGui frees font data it owns, so hand it a malloc'd copy of the embedded font.
private ImFont* addFont(ImFontAtlas* atlas, immutable(ubyte)[] data, float size)
{
    auto copy = malloc(data.length);
    memcpy(copy, data.ptr, data.length);
    return ImFontAtlas_AddFontFromMemoryTTF(atlas, copy, cast(int) data.length, size, null, null);
}

// ------------------------------------------------------------------ preferences (shared with the Rust edition)

private struct Prefs
{
    HeightMetric height = HeightMetric.nom;
    bool hasColor;
    ColorMetric color;
    Theme theme = Theme.day;
    bool tests = true, labels = true, beacons = true, shadows = true;

    static immutable string[4] heightNames = ["Nom", "Loc", "Cx", "Noa"];
    static immutable string[8] colorNames = ["Commits", "Age", "Authors", "Prs", "Cx", "Density", "Loc", "Kind"];

    static string path()
    {
        const xdg = environment.get("XDG_CONFIG_HOME", "");
        return buildPath(xdg.length ? xdg : buildPath(environment.get("HOME", "."), ".config"), "codecity", "desktop.json");
    }

    static Prefs load()
    {
        Prefs p;
        try
        {
            auto v = parseJSON(readText(path()));
            string s(string k) { auto x = k in v; return x && x.type == JSONType.string ? x.str : null; }
            bool b(string k, bool d) { auto x = k in v; return x ? x.type == JSONType.true_ : d; }
            foreach (i, n; heightNames) if (s("height") == n) p.height = cast(HeightMetric) i;
            foreach (i, n; colorNames)
                if (s("color") == n)
                {
                    p.color = cast(ColorMetric) i;
                    p.hasColor = true;
                }
            p.theme = s("theme") == "night" ? Theme.night : Theme.day;
            p.tests = b("tests", true);
            p.labels = b("labels", true);
            p.beacons = b("beacons", true);
            p.shadows = b("shadows", true);
        }
        catch (Exception) {}
        return p;
    }

    void save() const
    {
        JSONValue v = [
            "height": JSONValue(heightNames[height]),
            "color": hasColor ? JSONValue(colorNames[color]) : JSONValue(null),
            "theme": JSONValue(theme == Theme.night ? "night" : "day"),
            "tests": JSONValue(tests), "labels": JSONValue(labels), "beacons": JSONValue(beacons), "shadows": JSONValue(shadows),
        ];
        try
        {
            mkdirRecurse(path().dirName);
            write(path(), v.toString);
        }
        catch (Exception) {}
    }
}

// ------------------------------------------------------------------ the app

private struct Orbit
{
    Vec3 target;
    float yaw = 0.6f, pitch = 0.62f, distance = 100;

    Vec3 position() const
    {
        return target + Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance;
    }
}

private struct Arc
{
    Vec3 p0, p1, p2;
    float speed, t;
    float[4] color;
}

private Vec3 bezier(Vec3 p0, Vec3 p1, Vec3 p2, float t)
{
    return p0 * ((1 - t) * (1 - t)) + p1 * (2 * (1 - t) * t) + p2 * (t * t);
}

private struct SourceView
{
    string title;
    Line[] lines;
    size_t first, last;
    bool scrollPending = true;
    bool open = true;
}

private final class App
{
    CityModel model;
    Renderer renderer;
    ImFont* ui, mono;
    Prefs prefs;
    ColorMetric colorMetric;
    Scale scale;
    Orbit orbit;
    bool flying;
    Orbit flightFrom;
    Vec3 flightTarget;
    float flightDistance;
    double flightStart;
    float[] heights, heightsFrom, heightsTo;
    double animationStart = -1, animationDuration;
    RGB[] colors;
    bool buildingsDirty = true;
    long selected = -1, hovered = -1, hoveredDistrict = -1;
    bool[size_t] matches;
    bool searching;
    size_t[] results;
    char[256] searchBuffer;
    string search;
    bool searchOpen, focusSearch;
    Arc[] arcs;
    size_t[] beaconBuildings;
    struct Label { string text; Vec3 at; }
    Label[] labels;
    SourceView source;
    bool sourceShown;
    string toastText;
    double toastUntil = 0;
    double time = 0, lastTime = 0;
    bool pressed, dragging;
    double pressX, pressY, lastX, lastY;
    int pressButton = -1;
    double lastClickTime = 0;
    long lastClickDistrict = -1;
    float radius = 50;
    Theme stylesFor;
    bool stylesSet;
    bool snapshotRequested;
    Mat4 viewProj;
    int winW = 1, winH = 1;

    this(CityModel model, ImFont* ui, ImFont* mono)
    {
        renderer = new Renderer;
        this.ui = ui;
        this.mono = mono;
        searchBuffer[] = 0; // D initialises char arrays to 0xFF, not to an empty C string
        prefs = Prefs.load();
        setModel(model, false);
    }

    bool visible(size_t i) const { return prefs.tests || !model.analysis.types[model.buildings[i].index].test; }

    void setModel(CityModel next, bool live)
    {
        string previous = selected >= 0 ? model.analysis.types[selected].fqn : null;
        model = next;
        selected = hovered = -1;
        colorMetric = prefs.hasColor && unavailable(prefs.color, model) is null ? prefs.color
            : model.hasGit ? ColorMetric.commits : ColorMetric.cx;
        const n = model.buildings.length;
        auto old = heights;
        heights = new float[n];
        foreach (i; 0 .. n) heights[i] = live && i < old.length ? old[i] : 0;
        radius = max(20.0f, cast(float)((model.width ^^ 2 + model.depth ^^ 2) ^^ 0.5 / 2));
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

    void applyPalette()
    {
        auto p = palette(prefs.theme);
        Instance[] ds;
        foreach (d; model.districts)
        {
            const c = hex(p.districts[min(d.depth, 5)]);
            ds ~= Instance([cast(float) d.x, cast(float)(d.depth * platform), cast(float) d.z],
                [cast(float) d.w, cast(float) platform, cast(float) d.d], [c.r / 255.0f, c.g / 255.0f, c.b / 255.0f, 1]);
        }
        renderer.setDistricts(ds);
        renderer.setGround(Instance([0, -0.1f, 0], [radius * 14, 0.1f, radius * 14],
            [p.ground.r / 255.0f, p.ground.g / 255.0f, p.ground.b / 255.0f, 1]));
        buildingsDirty = true;
        select(selected, false);
    }

    void buildLabels()
    {
        auto candidates = model.districts.dup;
        candidates.sort!((a, b) => a.w * a.d > b.w * b.d);
        double[2][] anchors;
        labels = null;
        size_t taken;
        foreach (d; candidates)
        {
            if (d.depth < 1 || d.depth > 2 || d.types == 0) continue;
            if (taken++ >= 24) break;
            const front = d.z + d.d / 2;
            bool crowded = anchors.length >= 16;
            foreach (a; anchors) if (abs(a[0] - d.x) < 8 && abs(a[1] - front) < 3) crowded = true;
            if (crowded) continue;
            anchors ~= [d.x, front];
            labels ~= Label(d.label, Vec3(cast(float) d.x, cast(float)((d.depth + 1) * platform + 0.2), cast(float) front));
        }
    }

    void reheight(bool rise)
    {
        double[] values;
        foreach (i, ref t; model.analysis.types) if (visible(i)) values ~= heightValue(prefs.height, t);
        values.sort();
        const p95 = max(1.0, values.length ? values[min(cast(size_t)(0.95 * (values.length - 1)), values.length - 1)] : 1.0);
        const s = clamp(radius * 0.2, 8.0, 16.0) / p95;
        heightsTo = new float[model.buildings.length];
        foreach (i, ref t; model.analysis.types)
            heightsTo[i] = visible(i) ? cast(float) min(0.35 + heightValue(prefs.height, t) * s, radius * 0.75) : 0;
        heightsFrom = rise ? new float[heightsTo.length] : heights.dup;
        if (rise) heightsFrom[] = 0;
        animationStart = time;
        animationDuration = rise ? 1.4 : 0.65;
    }

    float[] targetHeights() { return animationStart >= 0 ? heightsTo : heights; }

    void recolor()
    {
        scale = Scale.of(colorMetric, model);
        const p = palette(prefs.theme);
        bool[size_t] related;
        if (selected >= 0)
        {
            related[selected] = true;
            foreach (d; model.buildings[selected].deps) related[d] = true;
            foreach (d; model.buildings[selected].incoming) related[d] = true;
        }
        colors.length = model.buildings.length;
        foreach (ref b; model.buildings)
        {
            auto c = scale.color(model.analysis.types[b.index], b);
            const outOfSearch = searching && b.index !in matches;
            const outOfFocus = selected >= 0 && b.index !in related;
            if (outOfSearch || outOfFocus) c = mix(c, p.dim, 0.8);
            if (b.index == hovered && b.index != selected) c = mix(c, RGB(255, 255, 255), 0.3);
            colors[b.index] = c;
        }
        buildingsDirty = true;
    }

    void select(long i, bool fly)
    {
        selected = i;
        buildArcs();
        auto h = targetHeights();
        if (i >= 0)
        {
            auto b = &model.buildings[i];
            const s = cast(float) b.lot.side + 0.35f;
            float[4] tint = [0.07f, 0.09f, 0.15f, 0.2f];
            if (night) tint = [1, 1, 1, 0.24f];
            renderer.setMarker([Instance([cast(float) b.lot.x, cast(float) b.lot.y - 0.02f, cast(float) b.lot.z], [s, h[i] + 0.3f, s], tint)]);
        }
        else renderer.setMarker(null);
        recolor();
        if (i >= 0 && fly) flyToBuilding(i);
    }

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
                const mid = (p0 + p2) * 0.5f;
                const p1 = Vec3(mid.x, max(p0.y, p2.y) + dist * 0.35f + 2, mid.z);
                const r = (total > 40 ? 0.03f : 0.055f) + dist * 0.0005f;
                enum segments = 24;
                Vec3[4][segments + 1] rings;
                foreach (k; 0 .. segments + 1)
                {
                    const t = cast(float) k / segments;
                    const at = bezier(p0, p1, p2, t);
                    const tangent = bezier(p0, p1, p2, min(1.0f, t + 0.01f)) - bezier(p0, p1, p2, max(0.0f, t - 0.01f));
                    auto u = tangent.cross(Vec3(0, 1, 0));
                    if (u.length < 1e-6) u = Vec3(1, 0, 0);
                    u = u.normalized * r;
                    const v = tangent.cross(u).normalized * r;
                    rings[k] = [at + u, at + v, at - u, at - v];
                }
                foreach (k; 0 .. segments)
                    foreach (s; 0 .. 4)
                    {
                        const a = rings[k][s], bb = rings[k][(s + 1) % 4], c = rings[k + 1][(s + 1) % 4], d = rings[k + 1][s];
                        foreach (q; [a, bb, c, a, c, d]) vertices ~= ColoredVertex([q.x, q.y, q.z], color);
                    }
                import std.random : uniform01;
                arcs ~= Arc(p0, p1, p2, 14 / max(5.0f, dist * 1.2f), uniform01!float, color);
            }
            size_t count;
            foreach (j; b.deps) if (visible(j) && count++ < 200) add(selected, j, tint(outColor));
            count = 0;
            foreach (j; b.incoming) if (visible(j) && count++ < 200) add(j, selected, tint(inColor));
        }
        renderer.setArcs(vertices);
        if (arcs.length == 0) renderer.setParticles(null);
    }

    void frameAll(bool animate)
    {
        if (animate) fly(Vec3(0, 0, 0), radius * 2.6f);
        else orbit = Orbit(Vec3(0, 0, 0), 0.6f, 0.62f, radius * 2.6f);
    }

    void fly(Vec3 target, float distance)
    {
        if (orbit.pitch < 0.35f) orbit.pitch = 0.35f;
        flightFrom = orbit;
        flightTarget = target;
        flightDistance = distance;
        flightStart = time;
        flying = true;
    }

    void flyToBuilding(size_t i)
    {
        auto b = &model.buildings[i];
        const h = targetHeights()[i];
        fly(Vec3(cast(float) b.lot.x, cast(float) b.lot.y + h * 0.5f, cast(float) b.lot.z), max(h * 1.8f, cast(float) b.lot.side * 7, 22.0f));
    }

    void flyToDistrict(size_t i)
    {
        auto d = model.districts[i];
        fly(Vec3(cast(float) d.x, cast(float)((d.depth + 1) * platform), cast(float) d.z), cast(float)(max(d.w, d.d) * 1.25 + 10));
    }

    void runSearch()
    {
        const q = search.strip.toLower;
        matches = null;
        results = null;
        if (q.length < 2)
        {
            searching = false;
            recolor();
            return;
        }
        struct Scored { size_t i; int score; size_t len; }
        Scored[] scored;
        foreach (i, ref t; model.analysis.types)
        {
            if (!visible(i)) continue;
            const name = t.name.toLower;
            int score = name == q ? 0 : name.indexOf(q) == 0 ? 1 : name.canFind(q) ? 2 : t.fqn.toLower.canFind(q) ? 3 : -1;
            if (score >= 0) scored ~= Scored(i, score, name.length);
        }
        scored.sort!((a, b) => a.score != b.score ? a.score < b.score : a.len < b.len);
        foreach (s; scored) matches[s.i] = true;
        foreach (s; scored[0 .. min(12, $)]) results ~= s.i;
        searching = true;
        recolor();
    }

    void toast(string text)
    {
        toastText = text;
        toastUntil = time + 2.6;
    }

    void openSource(size_t i)
    {
        auto t = &model.analysis.types[i];
        string code;
        try code = readText(t.file);
        catch (Exception e) code = "// unable to read: " ~ e.msg;
        source = SourceView(format("%s:%d", model.buildings[i].file, t.line), highlight(code, t.language), t.line, t.endLine);
        sourceShown = true;
    }

    string githubLink(string kind, size_t i)
    {
        auto gh = &model.github;
        auto t = &model.analysis.types[i];
        string enc(string p) { string[] parts; foreach (s; p.split("/")) parts ~= encodeComponent(s); return parts.join("/"); }
        const url = format("%s/%s/%s/%s", gh.repo.webUrl, kind, enc(gh.gitRef), enc(gh.prefix ~ model.buildings[i].file));
        return kind == "commits" ? url : format("%s#L%d-L%d", url, t.line, t.endLine);
    }

    void openUrl(string url)
    {
        try browse(url);
        catch (Exception e) toast("Could not open browser: " ~ e.msg);
    }

    // -------------------------------------------------------------- per frame

    void frame(GLFWwindow* window)
    {
        time = glfwGetTime();
        const dt = cast(float) min(0.1, time - lastTime);
        lastTime = time;
        int fbW, fbH;
        glfwGetFramebufferSize(window, &fbW, &fbH);
        glfwGetWindowSize(window, &winW, &winH);
        if (winW <= 0 || winH <= 0) return;

        drawInterface();
        handleInput(window);

        if (animationStart >= 0)
        {
            const k = cast(float) clamp((time - animationStart) / animationDuration, 0.0, 1.0);
            const e = 1 - pow(1 - k, 3);
            foreach (i; 0 .. heights.length) heights[i] = heightsFrom[i] + (heightsTo[i] - heightsFrom[i]) * e;
            buildingsDirty = true;
            if (k >= 1)
            {
                animationStart = -1;
                select(selected, false);
            }
        }
        if (buildingsDirty)
        {
            Instance[] data;
            data.reserve(model.buildings.length);
            foreach (ref b; model.buildings)
            {
                const h = heights[b.index];
                const s = h > 0.001f ? cast(float) b.lot.side : 0;
                const c = colors[b.index];
                data ~= Instance([cast(float) b.lot.x, cast(float) b.lot.y, cast(float) b.lot.z], [s, max(h, 0.0001f), s],
                    [c.r / 255.0f, c.g / 255.0f, c.b / 255.0f, 1]);
            }
            renderer.setBuildings(data);
            buildingsDirty = false;
        }
        if (flying)
        {
            const k = cast(float) clamp((time - flightStart) / 0.9, 0.0, 1.0);
            const e = k < 0.5 ? 4 * k * k * k : 1 - pow(-2 * k + 2, 3) / 2;
            orbit.target = flightFrom.target + (flightTarget - flightFrom.target) * e;
            orbit.distance = flightFrom.distance + (flightDistance - flightFrom.distance) * e;
            if (k >= 1) flying = false;
        }
        animate(dt);

        const eye = orbit.position;
        viewProj = Mat4.perspective(fovY, cast(float) winW / winH, 0.3f, radius * 30) * Mat4.lookAt(eye, orbit.target, Vec3(0, 1, 0));
        auto p = palette(prefs.theme);
        Renderer.Lighting light;
        const a = p.ambient.linear, s = p.sun.linear;
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
        if (prefs.beacons)
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

    /// World → window coordinates (logical pixels); false when behind the camera.
    bool project(Vec3 p, out float x, out float y)
    {
        const c = viewProj.transform(p);
        if (c[3] <= 0.01f) return false;
        x = (c[0] / c[3] * 0.5f + 0.5f) * winW;
        y = (1 - (c[1] / c[3] * 0.5f + 0.5f)) * winH;
        return true;
    }

    /// Nearest building or district under the cursor: (isBuilding, index), index -1 for nothing.
    void pick(double mx, double my, out long building, out long district)
    {
        building = district = -1;
        const eye = orbit.position;
        const forward = (orbit.target - eye).normalized;
        const right = forward.cross(Vec3(0, 1, 0)).normalized;
        const up = right.cross(forward);
        const tanHalf = tan(fovY / 2);
        const nx = cast(float)(2 * mx / winW - 1), ny = cast(float)(1 - 2 * my / winH);
        const dir = (forward + right * (nx * tanHalf * winW / winH) + up * (ny * tanHalf)).normalized;
        float best = float.max;
        foreach (ref b; model.buildings)
        {
            const h = heights[b.index];
            if (h <= 0.001f) continue;
            const s = cast(float) b.lot.side / 2;
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
            const t = rayBox(eye, dir, Vec3(cast(float)(d.x - d.w / 2), cast(float)(d.depth * platform), cast(float)(d.z - d.d / 2)),
                Vec3(cast(float)(d.x + d.w / 2), cast(float)((d.depth + 1) * platform), cast(float)(d.z + d.d / 2)));
            // the topmost (deepest) platform under the cursor is hit first
            if (t >= 0 && t < bestDistrict - 1e-4)
            {
                bestDistrict = t;
                district = i;
            }
        }
        if (building >= 0 && bestDistrict < best - 1e-3) building = -1;
        if (building >= 0) district = -1;
    }

    void handleInput(GLFWwindow* window)
    {
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
                orbit.yaw -= dx * 0.006f;
                orbit.pitch = clamp(orbit.pitch + dy * 0.006f, 0.07f, 1.5f);
            }
            else
            {
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
                orbit.distance = clamp(orbit.distance * cast(float) exp(-scrollAccumulated * 0.12), 4.0f, radius * 6);
            }
            scrollAccumulated = 0;
        }
    }

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

    void snapshot(int w, int h)
    {
        auto pixels = renderer.readPixels(w, h);
        auto rows = new ubyte[pixels.length];
        foreach (y; 0 .. h) // OpenGL rows run bottom-up
            rows[y * w * 4 .. (y + 1) * w * 4] = pixels[(h - 1 - y) * w * 4 .. (h - y) * w * 4];
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

    void text(string s) { igTextUnformatted(s.ptr, s.ptr + s.length); }
    void mutedText(string s) { igPushStyleColor_Vec4(Col.Text, mutedColor); text(s); igPopStyleColor(1); }
    void small(string s, bool weak = true)
    {
        igPushFont(null, 12.5f);
        if (weak) mutedText(s);
        else text(s);
        igPopFont();
    }

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

    void shortcuts()
    {
        const typing = igIsAnyItemActive();
        if (igIsKeyPressed_Bool(ImGuiKey.Escape, false))
        {
            if (sourceShown) sourceShown = false;
            else if (searchOpen) searchOpen = false;
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
    }

    void toggleTheme()
    {
        prefs.theme = night ? Theme.day : Theme.night;
        prefs.save();
        applyPalette();
    }

    enum fixedFlags = WindowFlags.NoTitleBar | WindowFlags.NoResize | WindowFlags.NoMove | WindowFlags.NoSavedSettings
        | WindowFlags.NoCollapse | WindowFlags.NoFocusOnAppearing;

    void drawLabels()
    {
        if (!prefs.labels) return;
        auto list = igGetBackgroundDrawList_Nil();
        foreach (l; labels)
        {
            float x, y;
            if (!project(l.at, x, y)) continue;
            const size = igCalcTextSize(l.text.ptr, l.text.ptr + l.text.length, false, -1);
            const p0 = ImVec2(x - size.x / 2 - 8, y - size.y - 10), p1 = ImVec2(x + size.x / 2 + 8, y - 4);
            ImDrawList_AddRectFilled(list, p0, p1, night ? rgba(13, 19, 33, 215) : rgba(255, 255, 255, 220), 99, 0);
            ImDrawList_AddRect(list, p0, p1, rgba(0, 0, 0, 25), 99, 0, 1);
            ImDrawList_AddText_Vec2(list, ImVec2(p0.x + 8, p0.y + 3), night ? rgba(226, 232, 240) : rgba(15, 23, 42),
                l.text.ptr, l.text.ptr + l.text.length);
        }
    }

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

    static uint igColor(ImVec4 c)
    {
        return rgba(cast(ubyte)(c.x * 255), cast(ubyte)(c.y * 255), cast(ubyte)(c.z * 255), cast(ubyte)(c.w * 255));
    }

    void logo()
    {
        const pos = igGetCursorScreenPos();
        auto dl = igGetWindowDrawList();
        static immutable float[5][4] bars = [[5, 16, 5, 11, 0x38bdf8], [11.5, 7, 5, 20, 0xf97316], [18, 12, 4, 15, 0xfacc15], [23.5, 19, 4, 8, 0x34d399]];
        foreach (b; bars)
        {
            const c = hex(cast(uint) b[4]);
            const k = 30.0f / 32;
            ImDrawList_AddRectFilled(dl, ImVec2(pos.x + b[0] * k, pos.y + b[1] * k), ImVec2(pos.x + (b[0] + b[2]) * k, pos.y + (b[1] + b[3]) * k), rgba(c.r, c.g, c.b), 1, 0);
        }
        igDummy(ImVec2(30, 30));
    }

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
        igSameLine(winW - 32 - 14 - width, -1);
        igSetCursorPosY(y0 + 3);
        foreach (i, c; chips)
        {
            if (i) igSameLine(0, 6);
            if (i == 0 && model.hasGithub)
            {
                if (igSmallButton(c[0].toStringz)) openUrl(model.github.repo.webUrl);
                igSetItemTooltip("Open on GitHub");
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
                const why = unavailable(cast(ColorMetric) i, model);
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
        igSetItemTooltip("Save a PNG to ~/Pictures (or the current directory)");
        igEnd();
    }

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
            const rw = igCalcTextSize(row[1].ptr, row[1].ptr + row[1].length, false, -1).x * 12.5f / 16;
            igSameLine(width - rw + 14, -1);
            small(row[1]);
        }
    }

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
        igSetItemTooltip("Close (Esc)");
        const pkg = t.pkg.length ? t.pkg : (t.language == Language.java ? "(default package)" : "(root)");
        igPushFont(mono, 12);
        mutedText(pkg ~ (t.module_.length ? " · " ~ t.module_ : ""));
        igPopFont();
        badge(t.kind, kindColor(t.kind), true);
        igSameLine(0, 6);
        badge(t.language.displayName, RGB(0, 0, 0), false);
        if (t.test)
        {
            igSameLine(0, 6);
            badge("test", RGB(0, 0, 0), false);
        }
        igSpacing();
        size_t[] uses, usedBy;
        foreach (j; b.deps) if (visible(j)) uses ~= j;
        foreach (j; b.incoming) if (visible(j)) usedBy ~= j;
        string[2][] cells = [
            [t.nom.to!string, "methods"], [t.noa.to!string, "attributes"], [fmt(t.loc), "lines of code"],
            [t.complexity.to!string, "complexity"], [t.nom == 0 ? "0" : format("%.1f", cast(double) t.complexity / t.nom), "cx / method"],
            [format("%d / %d", uses.length, usedBy.length), "uses / used by"],
        ];
        if (model.hasGit)
        {
            cells ~= [b.hasHistory ? b.history.commits.to!string : "0", "commits"];
            cells ~= [b.hasHistory ? b.history.authors.to!string : "0", "authors"];
            cells ~= [b.hasHistory ? relTime(b.history.lastCommit) : "—", "last change"];
        }
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
        igSetItemTooltip(b.file.toStringz);
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
                    const label = format("#%d  %s\n%s · %s · %d files%s", pr.number, pr.title, pr.author.length ? pr.author : "ghost",
                        relTime(parseIso(pr.updatedAt)), pr.files.length, pr.draft ? " · draft" : "");
                    if (igSelectable_Bool(label.toStringz, false, 0, ImVec2(0, 0))) openUrl(pr.url);
                    igSetItemTooltip(pr.url.toStringz);
                    igPopID();
                }
                if (b.prs.length == 0)
                    small(model.github.pullsSource.length >= 3 && model.github.pullsSource[0 .. 3] == "all" ? "none" : "none among the " ~ model.github.pullsSource);
            }
        }
        if (b.methods.length)
        {
            section("METHODS BY COMPLEXITY", t.methods.length);
            uint maxCx = 1;
            foreach (m; b.methods) maxCx = max(maxCx, m.complexity);
            const width = igGetContentRegionAvail().x;
            foreach (m; b.methods[0 .. min(15, $)])
            {
                igPushFont(mono, 12.5f);
                text(m.name ~ (m.parameters ? "(…)" : "()"));
                igPopFont();
                igSetItemTooltip(format("line %d · %d LOC · %d params", m.line, m.loc, m.parameters).toStringz);
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
            if (b.methods.length > 15) small(format("+ %d more", b.methods.length - 15));
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
                if (x > 0 && x + w < avail) igSameLine(0, 6);
                else x = 0;
                x += w + 6;
                igPushID_Int(cast(int) j);
                igPushStyleColor_Vec4(Col.Border, color(accent.r, accent.g, accent.b));
                if (igSmallButton(name.toStringz)) select(j, true);
                igPopStyleColor(1);
                igSetItemTooltip(model.analysis.types[j].fqn.toStringz);
                igPopID();
            }
            if (list.length == 0) small(pass == 0 ? "nothing in this project" : "no project types");
        }
        igEnd();
    }

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

    void tooltip()
    {
        if (pressed || igIsWindowHovered(HoveredFlags_AnyWindow)) return;
        string title, sub, row;
        if (hovered >= 0)
        {
            auto t = &model.analysis.types[hovered];
            auto b = &model.buildings[hovered];
            title = t.name ~ "  ·  " ~ t.kind;
            sub = t.pkg;
            row = format("%d methods · %d attrs · %s loc · cx %d", t.nom, t.noa, fmt(t.loc), t.complexity);
            if (b.hasHistory) row ~= format(" · %d commits", b.history.commits);
            if (b.prs.length) row ~= format(" · %d open PR%s", b.prs.length, b.prs.length > 1 ? "s" : "");
        }
        else if (hoveredDistrict >= 0 && model.districts[hoveredDistrict].depth > 0)
        {
            auto d = model.districts[hoveredDistrict];
            title = d.label;
            sub = d.id.replace("module:", "");
            row = format("%d types · double-click to zoom", d.types);
        }
        else return;
        igPushStyleColor_Vec4(Col.PopupBg, color(15, 23, 42, 236));
        igPushStyleColor_Vec4(Col.Text, color(255, 255, 255));
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
            igTextColored(color(110, 118, 129), "%.*s", cast(int) number.length, number.ptr);
            foreach (tok; source.lines[row])
            {
                igSameLine(0, 0);
                const c = styleColor(tok.style);
                igTextColored(color(cast(ubyte)(c >> 16), cast(ubyte)(c >> 8), cast(ubyte) c), "%.*s", cast(int) tok.text.length, tok.text.ptr);
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
        pill("##hint", 40, "drag to orbit · right-drag to pan · scroll to zoom · click a building · / search · F frame · N night · Esc clear", false);
        if (time < toastUntil) pill("##toast", 86, toastText, true);
    }
}

/// Seconds since the epoch for an ISO-8601 UTC timestamp such as `2026-09-30T12:00:00Z`.
private long parseIso(string s)
{
    import std.datetime.systime : SysTime;
    try return SysTime.fromISOExtString(s).toUnixTime;
    catch (Exception) return 0;
}
