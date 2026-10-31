/// The desktop viewer's rules as pure functions: what the search finds, how tall buildings are, which districts get a
/// label, which dependency arcs are drawn, what the inspector and the tooltip say, and where walk mode starts.
///
/// `codecity.gui.app` calls these with the current state and draws the result; nothing here touches the window,
/// ImGui or OpenGL, so every rule is compiled and tested in the `unittest` configuration. The Java
/// (`ViewRules.java`) and Rust (`rust/src/gui/rules.rs`) desktop viewers implement the same rules with the same
/// numbers, and the unittests below check the same scenarios as theirs, so all three show a city identically.
module codecity.gui.rules;

import codecity.city : Building, CityModel;
import codecity.github : GitHubInfo, PullRequest;
import codecity.gui.look : ColorMetric, fmt, mix, ratio, relTimeAt, RGB;
import codecity.gui.math : Vec3;
import walk = codecity.gui.walk;
import codecity.layout : District, Lot, platform;
import codecity.model : Language, TypeDecl;
import std.algorithm : canFind, clamp, max, min, sort, startsWith, SwapStrategy;
import std.array : join, split;
import std.conv : to;
import std.format : format;
import std.math : abs, cos, floor, hypot, sin;
import std.string : indexOf, strip, toLower;

// ------------------------------------------------------------------ search

/// Queries shorter than this (in UTF-16 code units, as in the browser viewer) clear the search.
enum minQuery = 2;
/// How many of the best matches the drop-down under the search field lists.
enum maxResults = 12;

/// Length of `s` in UTF-16 code units, the unit JavaScript's and Java's `length` count in; the search ranks ties by
/// it so that every viewer orders equally long names the same way.
size_t utf16Length(string s)
{
    import std.utf : byDchar, codeLength;
    size_t n;
    foreach (c; s.byDchar) n += codeLength!wchar(c);
    return n;
}

/// The result of `search`: whether a search is active (the query is long enough) and every match, best first.
struct Hits
{
    bool active;
    size_t[] ranked;
}

/// Searches the types that pass `visible` for `query` (trimmed, case-insensitive) and returns every match, best
/// first; inactive when the query is too short.
///
/// Ranking: exact simple name (0), name prefix (1), name substring (2), match anywhere in the fully qualified name
/// (3); within a rank, shorter names (in UTF-16 units) first, and equal entries in model order. The viewer highlights
/// every match in the city and lists the first `maxResults`.
Hits search(string query, const(TypeDecl)[] types, scope bool delegate(size_t) visible)
{
    const q = query.strip.toLower;
    if (utf16Length(q) < minQuery) return Hits(false, null);
    struct Scored { size_t i; int score; size_t len; }
    Scored[] scored;
    foreach (i, ref t; types)
    {
        if (!visible(i)) continue;
        const name = t.name.toLower;
        const score = name == q ? 0 : name.startsWith(q) ? 1 : name.canFind(q) ? 2 : t.fqn.toLower.canFind(q) ? 3 : -1;
        if (score >= 0) scored ~= Scored(i, score, utf16Length(name));
    }
    // ties keep type order (the sort is not stable by itself), like the stable sorts of the other viewers
    scored.sort!((a, b) => a.score != b.score ? a.score < b.score : a.len != b.len ? a.len < b.len : a.i < b.i);
    Hits hits = Hits(true, null);
    foreach (s; scored) hits.ranked ~= s.i;
    return hits;
}

// ------------------------------------------------------------------ heights

/// The scale of camera distances, heights and shadows: half the city's diagonal, at least 20.
double cityRadius(double width, double depth) pure
{
    return max(20.0, hypot(width, depth) / 2);
}

/// Height per unit of the height metric for a city of `radius` whose visible buildings have the metric `values`: the
/// 95th-percentile value (nearest rank, rounding the rank down; at least 1, so an all-zero metric does not divide by
/// zero) becomes 20% of the radius, kept between 8 and 16 units.
double heightScale(double[] values, double radius)
{
    auto v = values.dup;
    v.sort();
    const p95 = max(1.0, v.length ? v[min(cast(size_t)(0.95 * (v.length - 1)), v.length - 1)] : 1.0);
    return clamp(radius * 0.2, 8.0, 16.0) / p95;
}

/// The height of a visible building whose metric is `value`: a 0.35 base, so a value of 0 still shows, plus the value
/// on `scale`, capped at 75% of the radius so outliers do not tower over everything.
double buildingHeight(double value, double scale, double radius) pure
{
    return min(0.35 + value * scale, radius * 0.75);
}

// ------------------------------------------------------------------ colours

/// null when a model with or without git history and fetched pull requests can colour by `m`, otherwise the suffix
/// the colour menu shows after the metric's name.
string missing(ColorMetric m, bool hasGit, bool hasPulls) pure
{
    if ((m == ColorMetric.commits || m == ColorMetric.age || m == ColorMetric.authors) && !hasGit) return " (no git)";
    if (m == ColorMetric.prs && !hasPulls) return " (no GitHub)";
    return null;
}

/// The colour metric in effect: the user's choice (`chosen`, when `hasChoice`) when this model can provide it, else
/// churn when there is git history, else complexity.
ColorMetric effectiveColor(bool hasChoice, ColorMetric chosen, bool hasGit, bool hasPulls) pure
{
    if (hasChoice && missing(chosen, hasGit, hasPulls) is null) return chosen;
    return hasGit ? ColorMetric.commits : ColorMetric.cx;
}

/// Whether building `i` stays undimmed while `selected` is selected: the selection itself and its direct neighbours in
/// both directions (what it uses, what uses it). Everything is related when nothing is selected (null).
bool related(const(Building)* selected, size_t i)
{
    return selected is null || selected.index == i || selected.deps.canFind(i) || selected.incoming.canFind(i);
}

/// A building's final colour from its metric colour `base`: mixed 80% towards the theme's `dim` colour when it is
/// outside the search matches or unrelated to the selection, then lightened 30% towards white when it is hovered (and
/// not the selected one).
RGB shade(RGB base, RGB dim, bool outOfSearch, bool outOfFocus, bool hovered)
{
    auto c = base;
    if (outOfSearch || outOfFocus) c = mix(c, dim, 0.8);
    if (hovered) c = mix(c, RGB(255, 255, 255), 0.3);
    return c;
}

// ------------------------------------------------------------------ districts and labels

/// Whether district `d` is part of the present-day view: always, except in a history city when its `types` is 0, as it
/// then holds only gone types (and would be an empty platform today).
bool presentDistrict(ref const District d, bool history) pure
{
    return !history || d.types > 0;
}

/// At most this many districts are considered for a label, largest first; at most `maxLabels` are placed.
enum labelCandidates = 24, maxLabels = 16;

/// A district name label: the text, its anchor in world space and the district's index.
struct Label
{
    string text;
    Vec3 at;
    size_t district;
}

/// Chooses which districts get a floating name label.
///
/// Labelling every district would bury the city in text, so only districts at nesting depth 1 and 2 (modules and
/// top-level packages; depth 0 is the city itself) that contain at least one type are candidates. The
/// `labelCandidates` largest by area are considered, biggest first (equal areas in model order), and a label is skipped
/// if its anchor is within 8 units sideways and 3 units in depth of one already placed, since the two would overlap on
/// screen. At most `maxLabels` labels are kept.
///
/// Each label is anchored at the middle of the district's front edge (`z + d/2`, as `z` runs back to front), 0.2 above
/// its platform: a district at depth `n` has its top at `(n + 1) * platform`, as in the layout.
Label[] chooseLabels(const(District)[] districts)
{
    size_t[] order;
    foreach (i, ref d; districts)
        if (d.depth >= 1 && d.depth <= 2 && d.types > 0) order ~= i;
    order.sort!((a, b) => districts[a].w * districts[a].d > districts[b].w * districts[b].d, SwapStrategy.stable);
    Label[] labels;
    double[2][] anchors;
    foreach (index; order[0 .. min(labelCandidates, $)])
    {
        const d = districts[index];
        const front = d.z + d.d / 2;
        bool crowded = anchors.length >= maxLabels;
        foreach (a; anchors) if (abs(a[0] - d.x) < 8 && abs(a[1] - front) < 3) crowded = true;
        if (crowded) continue;
        anchors ~= [d.x, front];
        labels ~= Label(d.label, Vec3(cast(float) d.x, cast(float)((d.depth + 1) * platform + 0.2), cast(float) front), index);
    }
    return labels;
}

// ------------------------------------------------------------------ dependency arcs

/// At most this many arcs are drawn per direction (uses / used by); above `thinArcsAbove` edges in total (before
/// filtering and capping) they are drawn thinner.
enum maxArcs = 200, thinArcsAbove = 40;

/// One dependency arc: from the depending building to its dependency, and whether it starts at the selection (an
/// outgoing "uses" arc, orange) or ends there (an incoming "used by" arc, blue).
struct ArcEnds
{
    size_t from, to;
    bool outgoing;
}

/// The arcs to draw for the selection (null for none): none when the selection is hidden; otherwise one to every
/// visible type it uses, then one from every visible type that uses it, each direction capped at `maxArcs` in model
/// order.
ArcEnds[] arcs(const(Building)* selected, scope bool delegate(size_t) visible)
{
    if (selected is null || !visible(selected.index)) return null;
    ArcEnds[] result;
    size_t count;
    foreach (j; selected.deps) if (visible(j) && count++ < maxArcs) result ~= ArcEnds(selected.index, j, true);
    count = 0;
    foreach (j; selected.incoming) if (visible(j) && count++ < maxArcs) result ~= ArcEnds(j, selected.index, false);
    return result;
}

/// The control point of the quadratic Bézier between the roofs `p0` and `p2`: above their midpoint, 2 units plus 35%
/// of their distance higher than the taller roof, so longer arcs rise higher and neighbours still get a visible hump.
Vec3 arcControl(Vec3 p0, Vec3 p2)
{
    const mid = (p0 + p2) * 0.5f;
    return Vec3(mid.x, max(p0.y, p2.y) + p0.distance(p2) * 0.35f + 2, mid.z);
}

/// The tube radius of an arc of length `dist` when the selection has `total` edges: thinner for busy selections,
/// growing slightly with length.
float arcRadius(size_t total, float dist) pure
{
    return (total > thinArcsAbove ? 0.03f : 0.055f) + dist * 0.0005f;
}

/// The particle speed on an arc of length `dist`, in curve parameter per second: about 14 / 1.2 ≈ 12 world units per
/// second along the straight line, at most 2.8 trips per second on short arcs.
float particleSpeed(float dist) pure
{
    return 14 / max(5.0f, dist * 1.2f);
}

// ------------------------------------------------------------------ inspector and tooltip

/// How many of the most complex methods the inspector lists.
enum inspectorMethods = 15;

/// The inspector's line under the type name: the package (or `(default package)` for Java, `(root)` otherwise) and the
/// module after a dot, if any.
string packageLine(ref const TypeDecl t)
{
    const pkg = t.pkg.length ? t.pkg : (t.language == Language.java ? "(default package)" : "(root)");
    return pkg ~ (t.module_.length ? " · " ~ t.module_ : "");
}

/// The inspector's metric tiles, as [value, label] in display order: methods, attributes, lines, complexity,
/// complexity per method (one decimal) and the visible dependency counts; with git history also commits, authors and
/// the age of the last change relative to `now` (a file without history shows 0, 0 and "—").
string[2][] metricCells(ref const TypeDecl t, ref const Building b, bool hasGit, size_t uses, size_t usedBy, long now)
{
    string[2][] cells = [
        [t.nom.to!string, "methods"], [t.noa.to!string, "attributes"], [fmt(t.loc), "lines of code"],
        [t.complexity.to!string, "complexity"],
        [t.nom == 0 ? "0" : ratio(t.complexity, t.nom), "cx / method"],
        [format("%d / %d", uses, usedBy), "uses / used by"],
    ];
    if (hasGit)
    {
        cells ~= [b.hasHistory ? b.history.commits.to!string : "0", "commits"];
        cells ~= [b.hasHistory ? b.history.authors.to!string : "0", "authors"];
        cells ~= [b.hasHistory ? relTimeAt(b.history.lastCommit, now) : "—", "last change"];
    }
    return cells;
}

/// One method in the inspector's list: its name with `(…)` when it takes parameters, else `()`.
string methodName(string name, uint parameters) pure
{
    return name ~ (parameters ? "(…)" : "()");
}

/// The line under the method list when the type has more methods than are listed (null otherwise): counted on all of
/// the type's methods (`total`), like the section header, not on the model's capped list of the most complex 300.
string moreMethods(size_t total)
{
    return total > inspectorMethods ? format("+ %d more", total - inspectorMethods) : null;
}

/// The second line of an open pull request in the inspector: author (`ghost` when unknown), how long ago it was updated
/// relative to `now` ("—" when the timestamp does not parse), its file count and whether it is a draft.
string prMeta(const PullRequest pr, long now)
{
    return format("%s · %s · %d files%s", pr.author.length ? pr.author : "ghost", relTimeAt(parseIso(pr.updatedAt), now),
        pr.files.length, pr.draft ? " · draft" : "");
}

/// What the inspector says when no fetched pull request touches the file: plain "none" when every open PR was
/// fetched, otherwise which ones were searched (e.g. "none among the latest 30 open PRs (no token)").
string noPulls(string source) pure
{
    return source.startsWith("all") ? "none" : "none among the " ~ source;
}

/// One URL path segment, percent-encoded: every byte of the UTF-8 form except the unreserved characters of RFC 3986
/// (`A-Z a-z 0-9 - . _ ~`) becomes `%XX` (upper-case hex), as in the other editions' viewers (std.uri's
/// encodeComponent also keeps `!'()*`).
string encodeSegment(string s)
{
    import std.ascii : isAlphaNum;
    string out_;
    foreach (ubyte b; cast(const(ubyte)[]) s)
        out_ ~= b < 0x80 && (isAlphaNum(cast(char) b) || "-._~".canFind(cast(char) b)) ? format("%c", cast(char) b) : format("%%%02X", b);
    return out_;
}

/// GitHub web URL for a file: `kind` is the URL segment, "blob" (file view), "blame" or "commits" (history), giving
/// `https://<host>/<owner>/<repo>/<kind>/<ref>/<prefix><file>`. The path is the analysed root's position in the
/// repository (`prefix`) plus the building's file path; each path segment is percent-encoded separately so the slashes
/// survive. File and blame links jump to the type's line range.
string githubLink(const GitHubInfo gh, string kind, string file, uint line, uint endLine)
{
    string enc(string p) { string[] parts; foreach (s; p.split("/")) parts ~= encodeSegment(s); return parts.join("/"); }
    const url = format("%s/%s/%s/%s", gh.repo.webUrl, kind, enc(gh.gitRef), enc(gh.prefix ~ file));
    return kind == "commits" ? url : format("%s#L%d-L%d", url, line, endLine);
}

/// The numbers row of a building's hover card in the present-day city: methods, attributes, lines, complexity, then
/// the commit count when the file has history and the open pull requests touching it, if any.
string tooltipRow(ref const TypeDecl t, ref const Building b)
{
    auto row = format("%d methods · %d attrs · %s loc · cx %d", t.nom, t.noa, fmt(t.loc), t.complexity);
    if (b.hasHistory) row ~= format(" · %d commits", b.history.commits);
    if (b.prs.length) row ~= format(" · %d open PR%s", b.prs.length, b.prs.length > 1 ? "s" : "");
    return row;
}

/// The numbers row of a building's hover card during a replay, for snapshot `k` taken on `date`: the snapshot's
/// methods, attributes, lines and complexity (0 where the type does not exist), its commit count when the file has git
/// statistics today, and the date; no pull requests, which belong to today. The same text as the browser viewer's.
string replayTooltipRow(ref const Building b, size_t k, string date)
{
    int z(int v) { return v > 0 ? v : 0; }
    const h = &b.hist;
    auto row = format("%d methods · %d attrs · %s loc · cx %d", z(h.nom[k]), z(h.noa[k]), fmt(z(h.loc[k])), z(h.cx[k]));
    if (b.hasHistory) row ~= format(" · %d commits", h.commits[k]);
    return row ~ " · at " ~ date;
}

/// The inspector's heading of the replay box for a snapshot taken on `date`.
string replayHeading(string date) pure
{
    return "Replaying — values at " ~ date;
}

/// What the inspector says about a type that does not exist in the replayed snapshot, and about one that no longer
/// exists today.
enum notInSnapshot = "Not in this snapshot", removed = "Removed before today";

/// The inspector's replay tiles for snapshot `k` as [value, label]: methods, attributes, lines of code and
/// complexity; null when the type does not exist there (the inspector then says `notInSnapshot`).
string[2][] replayCells(ref const Building b, size_t k)
{
    const h = &b.hist;
    if (!h.exists(k)) return null;
    return [[h.nom[k].to!string, "methods"], [h.noa[k].to!string, "attributes"], [fmt(h.loc[k]), "lines of code"],
        [h.cx[k].to!string, "complexity"]];
}

/// A district's hover card as [title, id, row], or null for the city platform itself (depth 0). Module ids carry a
/// `module:` prefix in the layout to keep them apart from packages, which is not shown; the double-click hint is left
/// out while walking, where double-clicks do nothing.
string[] districtTooltip(ref const District d, bool walking)
{
    if (d.depth == 0) return null;
    const id = d.id.startsWith("module:") ? d.id["module:".length .. $] : d.id;
    return [d.label, id, format("%d types%s", d.types, walking ? "" : " · double-click to zoom")];
}

/// Seconds since the epoch for an ISO-8601 timestamp such as `2026-09-30T12:00:00Z` or `2026-09-30T14:00:00+02:00`;
/// 0 (shown as "—" by `relTime`) when it cannot be parsed.
long parseIso(string s)
{
    import std.datetime.systime : SysTime;
    try return SysTime.fromISOExtString(s).toUnixTime;
    catch (Exception) return 0;
}

// ------------------------------------------------------------------ camera and walk mode

/// The orbit camera: it always looks at `target` from `distance` away. `yaw` is the angle around the vertical axis (0
/// puts the camera on the +z side, i.e. in front of the city) and `pitch` the elevation above the horizon, both in
/// radians.
struct Orbit
{
    Vec3 target;
    float yaw = 0.6f, pitch = 0.62f, distance = 100;

    /// The default three-quarter view of the whole city (yaw 0.6, pitch 0.62 rad, ≈ 36° above the horizon) for a city
    /// of `radius`: at 2.6 radii the whole city fits in the 42° field of view.
    static Orbit framing(float radius)
    {
        return Orbit(Vec3(0, 0, 0), 0.6f, 0.62f, radius * 2.6f);
    }

    /// The eye position: spherical to Cartesian coordinates around the target.
    Vec3 position() const
    {
        return target + Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance;
    }
}

/// Where walk mode starts, as [x, z, yaw] for `walk.Walker.spawn`: with a building selected (`selected` not null), on
/// the street in front of it (its +z side, just clear of the wall: half its lot side, the walker's radius and 0.2 out
/// from its centre), facing it (yaw 0 looks towards -z); otherwise at the orbit target, facing the way the orbit camera
/// looks (its yaw uses the walker's convention).
float[3] walkStart(const(Lot)* selected, ref const Orbit orbit)
{
    if (selected !is null)
        return [cast(float) selected.x, cast(float)(selected.z + selected.side / 2) + walk.radius + 0.2f, 0];
    return [orbit.target.x, orbit.target.z, orbit.yaw];
}

/// First-person walk mode in progress: the walker, and the orbit camera from before, restored exactly on leaving.
struct Walking
{
    walk.Walker walker;
    /// The orbit camera when walking started.
    Orbit saved;
    /// When walking started (`glfwGetTime` seconds), for fading out the key hint.
    double since = 0;

    /// Starts walking in `world` from where `walkStart` says, remembering `orbit`.
    static Walking begin(ref const walk.World world, const(Lot)* selected, Orbit orbit, double now)
    {
        const at = walkStart(selected, orbit);
        return Walking(walk.Walker.spawn(at[0], at[1], at[2], world), orbit, now);
    }

    /// Stops walking: the orbit camera to go back to, exactly as it was when walking started.
    Orbit end() const
    {
        return saved;
    }
}

// ------------------------------------------------------------------ tests
//
// The same scenarios as Rust's `rules_tests.rs` and Java's `ViewRulesTest`, with the same expected values. Most run on
// the city of `testdata/java-project`, built without git like its golden file, so its indices and numbers are fixed.

version (unittest)
{
    import codecity.analysis : SourceAnalyzer;
    import codecity.git : realPath;
    import codecity.gui.look : hex;
    import codecity.model : FileHistory;

    /// 2026-09-30T12:00:00Z, the "now" of every age in these tests.
    private enum long testNow = 1_790_769_600, day = 86_400;
    /// Building indices in the java-project city (see `testdata/golden/java-project.json`).
    private enum size_t mainType = 4, mainTest = 5, registry = 11;

    /// The city of `testdata/<name>`, with test code, without git and GitHub.
    CityModel fixture(string name)
    {
        import std.path : buildNormalizedPath, dirName;
        const root = buildNormalizedPath(__FILE_FULL_PATH__.dirName, "..", "..", "..", "..", "testdata", name);
        return CityModel.build(new SourceAnalyzer, realPath(root), true, false, false, GitHubInfo.init, 0);
    }

    /// `#rrggbb` of a colour.
    string hexOf(RGB c) { return format("#%02x%02x%02x", c.r, c.g, c.b); }

    bool close(double a, double b) { return abs(a - b) < 1e-3; }

    /// A district for the label scenarios.
    District district(string label, uint depth, double x, double w, uint types)
    {
        return District(label, label, depth, x, 0, w, 2, types);
    }

    /// The java-project city as walk mode sees it: method-count heights, lot footprints, every district shown.
    walk.World walkWorld(CityModel m)
    {
        const r = cityRadius(m.width, m.depth);
        double[] noms;
        foreach (ref t; m.analysis.types) noms ~= t.nom;
        const s = heightScale(noms, r);
        auto heights = new float[m.buildings.length], sides = new float[m.buildings.length];
        foreach (i, ref t; m.analysis.types)
        {
            heights[i] = cast(float) buildingHeight(t.nom, s, r);
            sides[i] = cast(float) m.buildings[i].lot.side;
        }
        auto shown = new bool[m.districts.length];
        shown[] = true;
        return walk.World.of(m, heights, sides, shown);
    }
}

// search ranks exact, then prefix, then substring, then qualified name
unittest
{
    auto m = fixture("java-project");
    bool delegate(size_t) all = (size_t i) => true;
    assert(search("registry", m.analysis.types, all).ranked == [11, 16, 14, 17, 12, 15, 13]);
    assert(search("  REG ", m.analysis.types, all).ranked == [11, 16, 14, 17, 12, 15, 13]);
    assert(search("st", m.analysis.types, all).ranked == [19, 5, 11, 9, 16, 14, 17, 12, 15, 13]);
    assert(search("acme.core.util", m.analysis.types, all).ranked == [19]);
    const none = search("zzz", m.analysis.types, all);
    assert(none.active && none.ranked.length == 0);
}

// search needs two characters (UTF-16 units) and skips hidden types
unittest
{
    auto m = fixture("java-project");
    bool delegate(size_t) all = (size_t i) => true;
    assert(!search("x", m.analysis.types, all).active);
    assert(!search("  x  ", m.analysis.types, all).active);
    assert(!search("", m.analysis.types, all).active);
    // one character outside the BMP is two UTF-16 units, as in the browser: a search, with no hits
    assert(search("😀", m.analysis.types, all).active);
    // "é" is one unit (two UTF-8 bytes): still too short
    assert(!search("é", m.analysis.types, all).active);
    assert(search("test", m.analysis.types, all).ranked == [mainTest, 9]);
    const noTests = search("test", m.analysis.types, (size_t i) => !m.analysis.types[i].test);
    assert(noTests.active && noTests.ranked.length == 0);
}

// search ties are ordered by UTF-16 length
unittest
{
    auto m = fixture("java-project");
    bool delegate(size_t) firstTwo = (size_t i) => i < 2;
    assert(utf16Length("Äb") == 2 && utf16Length("😀") == 2);
    m.analysis.types[0].name = "Abcd";
    m.analysis.types[1].name = "Äbc";
    assert(search("bc", m.analysis.types, firstTwo).ranked == [1, 0]);
}

// heights scale the 95th percentile to a fifth of the radius
unittest
{
    assert(cityRadius(17.131, 27.531) == 20);
    assert(cityRadius(60, 80) == 50);
    assert(heightScale([], 20) == 8);
    assert(heightScale([0, 0, 0], 20) == 8);
    double[] hundred;
    foreach (k; 0 .. 101) hundred ~= k;
    assert(close(heightScale(hundred, 50), 10.0 / 95));
    assert(close(heightScale(hundred, 10), 8.0 / 95));
    assert(close(heightScale(hundred, 200), 16.0 / 95));
    assert(heightScale([4, 1, 2], 20) == 4);
    assert(buildingHeight(0, 2, 20) == 0.35);
    assert(buildingHeight(3, 2, 20) == 6.35);
    assert(buildingHeight(1e9, 1, 20) == 15);
}

// fixture heights follow the method count
unittest
{
    auto m = fixture("java-project");
    const r = cityRadius(m.width, m.depth);
    double[] noms;
    foreach (ref t; m.analysis.types) noms ~= t.nom;
    const s = heightScale(noms, r);
    assert(s == 2);
    assert(buildingHeight(m.analysis.types[0].nom, s, r) == 2.35);
    assert(buildingHeight(m.analysis.types[registry].nom, s, r) == 15);
}

// the colour metric falls back when the model lacks its data
unittest
{
    with (ColorMetric)
    {
        assert(effectiveColor(false, cx, true, false) == commits);
        assert(effectiveColor(false, commits, false, false) == cx);
        assert(effectiveColor(true, loc, false, false) == loc);
        assert(effectiveColor(true, age, true, false) == age);
        assert(effectiveColor(true, age, false, true) == cx);
        assert(effectiveColor(true, prs, true, false) == commits);
        assert(effectiveColor(true, prs, false, true) == prs);
        assert(missing(authors, false, true) == " (no git)");
        assert(missing(prs, true, false) == " (no GitHub)");
        assert(missing(kind, false, false) is null);
    }
}

// a selection dims everything but its neighbours
unittest
{
    auto m = fixture("java-project");
    size_t[] rel;
    foreach (i; 0 .. m.buildings.length) if (related(&m.buildings[registry], i)) rel ~= i;
    assert(rel == [4, 5, 11, 14, 15, 16, 18]);
    foreach (i; 0 .. m.buildings.length) assert(related(null, i));
}

// shading dims, then lightens the hovered building
unittest
{
    const base = hex(0x2b6cb0), dim = hex(0xd5dbe3);
    assert(hexOf(shade(base, dim, false, false, false)) == "#2b6cb0");
    assert(hexOf(shade(base, dim, true, false, false)) == "#b3c5d9");
    assert(hexOf(shade(base, dim, false, true, false)) == "#b3c5d9");
    assert(hexOf(shade(base, dim, true, true, false)) == "#b3c5d9");
    assert(hexOf(shade(base, dim, false, false, true)) == "#6b98c8");
    assert(hexOf(shade(base, dim, true, false, true)) == "#cad6e4");
}

// labels go to the largest uncrowded shallow districts
unittest
{
    auto ds = [district("city", 0, 0, 100, 50), district("deep", 3, 0, 50, 1), district("empty", 1, 0, 40, 0)];
    foreach (k; 0 .. 30) ds ~= district(format("p%d", k), 1 + k % 2, k == 1 ? 5 : k * 10.0, 30 - k * 0.5, 1);
    auto labels = chooseLabels(ds);
    size_t[] kept, expected = [3];
    foreach (l; labels) kept ~= l.district;
    foreach (k; 5 .. 20) expected ~= k;
    assert(kept == expected);
    assert(labels.length == maxLabels);
    assert(labels[0].text == "p0");
    assert(close(labels[0].at.x, 0) && close(labels[0].at.y, 1.0) && close(labels[0].at.z, 1));
    assert(close(labels[2].at.x, 30) && close(labels[2].at.y, 1.4));
}

// labels consider only the 24 largest candidates, and crowding is checked against the labels kept
unittest
{
    District[] ds;
    foreach (k; 0 .. 30) ds ~= district(format("p%d", k), 1, k * 10.0, 1 + k, 1);
    size_t[] kept, expected;
    foreach (l; chooseLabels(ds)) kept ~= l.district;
    foreach_reverse (k; 14 .. 30) expected ~= k;
    assert(kept == expected);
    kept = null;
    foreach (l; chooseLabels([district("a", 1, 0, 10, 1), district("b", 1, 7, 9, 1), district("c", 1, 14, 8, 1)])) kept ~= l.district;
    assert(kept == [0, 2]);
}

// fixture labels skip districts that would overlap
unittest
{
    auto m = fixture("java-project");
    auto labels = chooseLabels(m.districts);
    assert(labels.length == 2);
    assert(labels[0].text == "core" && labels[0].district == 1);
    assert(labels[1].text == "app" && labels[1].district == 4);
    assert(close(labels[0].at.x, -0.366) && close(labels[0].at.y, 1.0) && close(labels[0].at.z, 2.7655));
    assert(close(labels[1].at.x, -3.4) && close(labels[1].at.z, 12.766));
}

// present-day districts hide only the emptied ones of a history city
unittest
{
    const a = district("a", 1, 0, 1, 2), b = district("b", 1, 0, 1, 0);
    assert(presentDistrict(a, false) && presentDistrict(b, false));
    assert(presentDistrict(a, true) && !presentDistrict(b, true));
}

// arcs join the selection with its visible neighbours
unittest
{
    auto m = fixture("java-project");
    bool delegate(size_t) all = (size_t i) => true;
    // the model's dependency order differs between editions (the golden files sort it): compare sorted, outgoing first
    auto sorted = arcs(&m.buildings[registry], all);
    sorted.sort!((a, b) => a.outgoing != b.outgoing ? a.outgoing : a.from != b.from ? a.from < b.from : a.to < b.to);
    assert(sorted == [ArcEnds(11, 14, true), ArcEnds(11, 15, true), ArcEnds(11, 16, true),
        ArcEnds(11, 18, true), ArcEnds(4, 11, false), ArcEnds(5, 11, false), ArcEnds(15, 11, false)]);
    bool delegate(size_t) noTests = (size_t i) => !m.analysis.types[i].test;
    auto filtered = arcs(&m.buildings[registry], noTests);
    assert(filtered.length == 6);
    foreach (a; filtered) assert(a.from != mainTest);
    assert(arcs(&m.buildings[mainTest], noTests).length == 0);
    assert(arcs(null, all).length == 0);
    assert(arcs(&m.buildings[mainType], all).length == 7);
}

// arcs are capped at 200 per direction, after filtering
unittest
{
    Building b;
    foreach (j; 1 .. 251) b.deps ~= j;
    foreach (j; 251 .. 261) b.incoming ~= j;
    size_t outgoing, incoming;
    auto all = arcs(&b, (size_t i) => true);
    foreach (a; all) (a.outgoing ? outgoing : incoming)++;
    assert(outgoing == maxArcs && incoming == 10);
    assert(all[199].to == 200);
    outgoing = 0;
    foreach (a; arcs(&b, (size_t i) => i % 2 == 0 || i > 250)) if (a.outgoing) outgoing++;
    assert(outgoing == 125);
}

// the arc shape rises with length and thins for busy selections
unittest
{
    const p0 = Vec3(0, 2, 0), p2 = Vec3(6, 4, 8);
    const p1 = arcControl(p0, p2);
    assert(close(p1.x, 3) && close(p1.z, 4));
    assert(close(p1.y, 4 + p0.distance(p2) * 0.35 + 2));
    assert(close(arcRadius(40, 10), 0.06));
    assert(close(arcRadius(41, 10), 0.035));
    assert(close(particleSpeed(1), 2.8));
    assert(close(particleSpeed(10), 14.0 / 12));
}

// the inspector shows metrics and git history
unittest
{
    auto m = fixture("java-project");
    string[2][] plain = [["8", "methods"], ["4", "attributes"], ["111", "lines of code"], ["25", "complexity"],
        ["3.1", "cx / method"], ["4 / 3", "uses / used by"]];
    string[2][] gitless = [["0", "commits"], ["0", "authors"], ["—", "last change"]];
    string[2][] counts = [["12", "commits"], ["3", "authors"], ["5d ago", "last change"]];
    assert(metricCells(m.analysis.types[registry], m.buildings[registry], false, 4, 3, testNow) == plain);
    assert(metricCells(m.analysis.types[registry], m.buildings[registry], true, 4, 3, testNow)
        == plain ~ gitless);
    m.buildings[registry].hasHistory = true;
    m.buildings[registry].history = FileHistory(12, 3, testNow - 5 * day, testNow - 400 * day);
    assert(metricCells(m.analysis.types[registry], m.buildings[registry], true, 4, 3, testNow)[6 .. $]
        == counts);
    m.analysis.types[1].loc = 12_345;
    const c = metricCells(m.analysis.types[1], m.buildings[1], false, 0, 0, testNow);
    assert(c[0][0] == "0" && c[2][0] == "12.3k" && c[4][0] == "0");
}

// the inspector's package line and method names
unittest
{
    auto m = fixture("java-project");
    assert(packageLine(m.analysis.types[registry]) == "com.acme.core · core");
    assert(packageLine(m.analysis.types[0]) == "(default package)");
    // a Rust type at the root of its crate: "(root)" and the crate's module
    auto mixed = fixture("mixed-project");
    assert(mixed.analysis.types[4].name == "Counter");
    assert(packageLine(mixed.analysis.types[4]) == "(root) · mix_native");
    string[] names;
    foreach (x; m.buildings[registry].methods[0 .. 3]) names ~= methodName(x.name, x.parameters);
    assert(names == ["retries(…)", "add(…)", "snapshot()"]);
}

// the inspector lists 15 methods and counts the rest
unittest
{
    import std.file : mkdirRecurse, rmdirRecurse, tempDir, write;
    import std.path : buildPath;
    import std.process : thisProcessID;
    assert(moreMethods(0) is null && moreMethods(15) is null);
    assert(moreMethods(16) == "+ 1 more");
    assert(moreMethods(321) == "+ 306 more");
    const dir = buildPath(tempDir, format("codecity-big-%d", thisProcessID));
    mkdirRecurse(dir);
    scope (exit) rmdirRecurse(dir);
    string src = "class Big {\n";
    foreach (k; 0 .. 320) src ~= format("  void m%d() {}\n", k);
    src ~= "  int hot(int a) { if (a > 1) { return 1; } if (a > 2) { return 2; } return 0; }\n}\n";
    write(buildPath(dir, "Big.java"), src);
    auto m = CityModel.build(new SourceAnalyzer, realPath(dir), true, false, false, GitHubInfo.init, 0);
    assert(m.analysis.types[0].methods.length == 321);
    assert(m.buildings[0].methods.length == 300);
    assert(m.buildings[0].methods[0].name == "hot");
    assert(moreMethods(m.analysis.types[0].methods.length) == "+ 306 more");
}

// the inspector describes pull requests
unittest
{
    PullRequest pr(long n, string author, string updated, bool draft, size_t files)
    {
        PullRequest p = {number: n, title: format("PR %d", n), url: format("https://github.com/o/r/pull/%d", n),
            author: author, updatedAt: updated, draft: draft};
        foreach (k; 0 .. files) p.files ~= format("f%d", k);
        return p;
    }
    assert(prMeta(pr(7, "ada", "2026-09-25T12:00:00Z", true, 2), testNow) == "ada · 5d ago · 2 files · draft");
    assert(prMeta(pr(8, null, "2026-09-30T11:00:00Z", false, 1), testNow) == "ghost · today · 1 files");
    assert(prMeta(pr(9, "bo", "yesterday", false, 0), testNow) == "bo · — · 0 files");
    assert(noPulls("all open PRs") == "none");
    assert(noPulls("latest 30 open PRs (no token)") == "none among the latest 30 open PRs (no token)");
    assert(noPulls(null) == "none among the ");
}

// GitHub links encode each path segment
unittest
{
    import codecity.github : GitHubRepo;
    import codecity.gui.look : isWebLink;
    GitHubInfo github(string gitRef, string prefix)
    {
        GitHubInfo gh;
        gh.repo = GitHubRepo("github.com", "o", "r");
        gh.gitRef = gitRef;
        gh.prefix = prefix;
        return gh;
    }
    const gh = github("main", "sub dir/");
    const file = "core/src/A B#1Ä.java";
    assert(githubLink(gh, "blob", file, 3, 9) == "https://github.com/o/r/blob/main/sub%20dir/core/src/A%20B%231%C3%84.java#L3-L9");
    assert(githubLink(gh, "blame", file, 3, 9) == "https://github.com/o/r/blame/main/sub%20dir/core/src/A%20B%231%C3%84.java#L3-L9");
    assert(githubLink(github("feature/x y", ""), "commits", "a/b.rs", 1, 2) == "https://github.com/o/r/commits/feature/x%20y/a/b.rs");
    assert(isWebLink(githubLink(gh, "blob", file, 1, 1)));
}

// ISO timestamps with offsets
unittest
{
    assert(parseIso("2026-09-30T12:00:00Z") == testNow);
    assert(parseIso("2026-09-30T14:00:00+02:00") == testNow);
    assert(parseIso("2026-09-30T06:30:00.5-05:30") == testNow);
    assert(parseIso("1970-01-01T00:00:00Z") == 0);
    assert(parseIso("2024-02-29T00:00:00Z") == 1_709_164_800);
    assert(parseIso("yesterday") == 0);
    assert(parseIso("") == 0);
}

// tooltips summarise buildings and districts
unittest
{
    auto m = fixture("java-project");
    assert(tooltipRow(m.analysis.types[registry], m.buildings[registry]) == "8 methods · 4 attrs · 111 loc · cx 25");
    m.buildings[registry].hasHistory = true;
    m.buildings[registry].history = FileHistory(12, 3, testNow, testNow);
    m.buildings[registry].prs = [0];
    assert(tooltipRow(m.analysis.types[registry], m.buildings[registry])
        == "8 methods · 4 attrs · 111 loc · cx 25 · 12 commits · 1 open PR");
    m.buildings[registry].prs = [0, 1];
    import std.algorithm : endsWith;
    assert(tooltipRow(m.analysis.types[registry], m.buildings[registry]).endsWith(" · 2 open PRs"));
    assert(districtTooltip(m.districts[0], false) is null);
    assert(districtTooltip(m.districts[1], false) == ["core", "core", "13 types · double-click to zoom"]);
    assert(districtTooltip(m.districts[2], true) == ["com.acme.core", "core/com.acme.core", "13 types"]);
}

// the replay texts match the browser viewer's
unittest
{
    import codecity.city : TypeHistory;
    auto m = fixture("java-project");
    TypeHistory h;
    h.nom = [-1, 3]; h.noa = [-1, 1]; h.loc = [-1, 12_345]; h.cx = [-1, 5]; h.commits = [0, 4]; h.authors = [0, 0];
    h.last = [-1, -1];
    m.buildings[registry].hist = h;
    // snapshot 1: the snapshot's numbers and date; commits only for a file with git statistics today
    assert(replayTooltipRow(m.buildings[registry], 1, "2026-09-30") == "3 methods · 1 attrs · 12.3k loc · cx 5 · at 2026-09-30");
    m.buildings[registry].hasHistory = true;
    m.buildings[registry].history = FileHistory(12, 3, testNow, testNow);
    m.buildings[registry].prs = [0];
    assert(replayTooltipRow(m.buildings[registry], 1, "2026-09-30")
        == "3 methods · 1 attrs · 12.3k loc · cx 5 · 4 commits · at 2026-09-30");
    // snapshot 0, where the type does not exist: zeros
    assert(replayTooltipRow(m.buildings[registry], 0, "2026-01-01")
        == "0 methods · 0 attrs · 0 loc · cx 0 · 0 commits · at 2026-01-01");
    assert(replayHeading("2026-09-30") == "Replaying — values at 2026-09-30");
    string[2][] cells = [["3", "methods"], ["1", "attributes"], ["12.3k", "lines of code"], ["5", "complexity"]];
    assert(replayCells(m.buildings[registry], 1) == cells);
    assert(replayCells(m.buildings[registry], 0) is null);
    assert(notInSnapshot == "Not in this snapshot" && removed == "Removed before today");
}

// walking starts in front of the selection, facing it
unittest
{
    auto m = fixture("java-project");
    const orbit = Orbit(Vec3(2, 0, 3), 0.6f, 0.62f, 52);
    const at = walkStart(&m.buildings[registry].lot, orbit);
    assert(close(at[0], 3.434) && close(at[1], -7.566) && at[2] == 0);
    assert(walkStart(null, orbit) == [2.0f, 3.0f, 0.6f]);
    auto world = walkWorld(m);
    auto w = Walking.begin(world, &m.buildings[registry].lot, orbit, 7.5);
    assert(close(w.walker.x, 3.434) && close(w.walker.z, -7.566));
    assert(close(w.walker.feet, 1.2));
    assert(w.since == 7.5);
    const dir = w.walker.forward;
    assert(close(dir.x, 0) && dir.z < -0.99);
}

// leaving walk mode restores the orbit camera exactly
unittest
{
    auto m = fixture("java-project");
    auto world = walkWorld(m);
    const orbit = Orbit.framing(20);
    assert(orbit.distance == 52);
    const eye = orbit.position;
    assert(close(eye.x, 23.896) && close(eye.y, 30.214) && close(eye.z, 34.930));
    auto w = Walking.begin(world, null, orbit, 0);
    foreach (k; 0 .. 20)
    {
        w.walker.look(13, -4);
        walk.Input input;
        input.forward = 1;
        w.walker.step(0.05f, input, world);
    }
    assert(w.walker.x != 0 || w.walker.z != 0);
    assert(w.end() == orbit);
}

unittest
{
    // GitHub links keep only the unreserved characters of RFC 3986, as in every edition's viewers.
    assert(encodeSegment("a!b'c(d)e*f~g-h_i.j,k \u00e9") == "a%21b%27c%28d%29e%2Af~g-h_i.j%2Ck%20%C3%A9");
    assert(encodeSegment("A-Z_0.9~") == "A-Z_0.9~");
}
