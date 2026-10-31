/// Palettes, themes and metric definitions shared with the other viewers.
///
/// Everything that decides how a building or district looks, independent of OpenGL and ImGui: the colour ramp, the
/// type-kind colours, the day/night palettes, the height metrics and the colour metrics with their scale. The values
/// (hex colours, labels, the 98th-percentile log scale) mirror the browser viewer (`src/main/resources/web/app.js`)
/// and the Rust desktop viewer (`rust/src/gui/look.rs`), so the three show the same city in the same colours.
///
/// Colours are kept as 8-bit sRGB (`RGB`); `codecity.gui.app` converts them to 0..1 floats for instance data and to
/// linear light (`RGB.linear`) for the lighting uniforms.
module codecity.gui.look;

import codecity.city : Building, CityModel;
import codecity.model : TypeDecl;
import std.algorithm : clamp, filter, map, min, sort;
import std.array : array;
import std.datetime.systime : Clock;
import std.format : format;
import std.math : floor, isFinite, log1p, round, trunc;

/// An 8-bit sRGB colour.
struct RGB
{
    ubyte r, g, b;
    /// The colour in linear light, using the plain 2.2 gamma approximation that the renderer's shader also uses for
    /// its sRGB ↔ linear conversions (not the exact piecewise sRGB curve).
    float[3] linear() const
    {
        import std.math : pow;
        return [pow(r / 255.0f, 2.2f), pow(g / 255.0f, 2.2f), pow(b / 255.0f, 2.2f)];
    }
}

/// `0xRRGGBB` → `RGB`.
RGB hex(uint h) pure { return RGB(cast(ubyte)(h >> 16), cast(ubyte)(h >> 8), cast(ubyte) h); }

/// Linear interpolation from `a` (t = 0) to `b` (t = 1) per channel, on the sRGB byte values, rounded and clamped.
RGB mix(RGB a, RGB b, float t) pure
{
    ubyte l(ubyte x, ubyte y) { return cast(ubyte) clamp(round(x + (y - x) * t), 0, 255); }
    return RGB(l(a.r, b.r), l(a.g, b.g), l(a.b, b.b));
}

/// The sequential colour ramp for numeric metrics, from cool blue (low) through green and yellow to deep red (high).
immutable uint[9] ramp = [0x2b6cb0, 0x2a9fc0, 0x34c3a5, 0x8fd67a, 0xe8e15b, 0xf6b343, 0xef7d34, 0xdc3f2e, 0xa3123a];

/// Colour at position `t` (clamped to 0..1) along `ramp`: the nine stops are evenly spaced and the colour is
/// interpolated between the two neighbouring stops. The `min` keeps `t = 1` in the last segment (at its end) instead of
/// indexing past the array.
RGB rampColor(double t) pure
{
    const x = clamp(t, 0.0, 1.0) * (ramp.length - 1);
    const i = min(cast(size_t) floor(x), ramp.length - 2);
    return mix(hex(ramp[i]), hex(ramp[i + 1]), cast(float)(x - i));
}

/// A type kind (`TypeDecl.kind`) and its categorical colour, for the "Type kind" colour metric and the inspector badge.
struct Kind
{
    string name;
    uint color;
}

immutable Kind[11] kinds = [
    Kind("class", 0x6b8fc7), Kind("abstract", 0x9a86d8), Kind("interface", 0x2fb7a5), Kind("enum", 0xe3a63b),
    Kind("record", 0xd46fa8), Kind("annotation", 0x8a9aaa), Kind("struct", 0x4f86c6), Kind("trait", 0x26a69a),
    Kind("union", 0xc98a3a), Kind("type", 0xb7a3e0), Kind("module", 0x7c8ba1),
];

/// Accent colours: outgoing dependency arcs ("uses", orange), incoming ones ("used by", blue) and open-PR beacons
/// (violet).
enum uint outColor = 0xf97316, inColor = 0x0ea5e9, prColor = 0xc084fc;

/// Colour of a type kind; unknown kinds fall back to the "class" colour.
RGB kindColor(string kind)
{
    foreach (k; kinds) if (k.name == kind) return hex(k.color);
    return hex(kinds[0].color);
}

enum Theme { day, night }

/// Everything that changes between the day and the night theme.
struct Palette
{
    RGB background, ground;
    /// Platform colours by nesting depth (index 0 is the city; deeper districts reuse the last entry).
    uint[6] districts;
    /// Sky light (applied everywhere, scaled by `ambientIntensity`) and the sun (directional, casts shadows).
    RGB ambient;
    float ambientIntensity;
    RGB sun;
    float sunIntensity;
    /// Night-only shader effects: `glow` is how much of a building's own colour it emits regardless of light,
    /// `windows` the brightness of the procedural lit office windows (see the fragment shader in the renderer).
    float glow, windows;
    /// What buildings outside the search results or the selection's neighbourhood fade towards.
    RGB dim;
}

/// The palette of a theme. Day: pale grey-blue city in bright warm sunlight, no effects. Night: near-black blue
/// platforms, a dim cold-blue sun, glowing buildings and lit windows.
Palette palette(Theme t)
{
    final switch (t)
    {
    case Theme.day:
        return Palette(hex(0xe8ecf1), hex(0xdfe5ec), [0xc3ccd7, 0xcfd7e0, 0xd9dfe7, 0xe2e7ed, 0xeaeef2, 0xf0f3f6],
            hex(0xffffff), 0.55, hex(0xfff4e5), 1.1, 0, 0, hex(0xd5dbe3));
    case Theme.night:
        return Palette(hex(0x04070d), hex(0x060b14), [0x0e1626, 0x121c30, 0x16223a, 0x1a2844, 0x1e2e4e, 0x223458],
            hex(0x5a6fa8), 0.22, hex(0x9db4ff), 0.35, 0.28, 1.4, hex(0x0c1220));
    }
}

/// What a building's height shows. The order matches `heightLabels` and the preference names in `codecity.gui.app`.
enum HeightMetric { nom, loc, cx, noa }

immutable string[4] heightLabels = ["Methods (NOM)", "Lines of code", "Complexity (WMC)", "Attributes (NOA)"];

/// The raw metric value for a type; `App.reheight` turns it into a height.
double heightValue(HeightMetric m, ref const TypeDecl t)
{
    final switch (m)
    {
    case HeightMetric.nom: return t.nom;
    case HeightMetric.loc: return t.loc;
    case HeightMetric.cx: return t.complexity;
    case HeightMetric.noa: return t.noa;
    }
}

/// History replay: the building's metric value in snapshot `k`, or -1 when the type does not exist there.
double heightValueAt(HeightMetric m, ref const Building b, size_t k)
{
    final switch (m)
    {
    case HeightMetric.nom: return b.hist.nom[k];
    case HeightMetric.loc: return b.hist.loc[k];
    case HeightMetric.cx: return b.hist.cx[k];
    case HeightMetric.noa: return b.hist.noa[k];
    }
}

/// What a building's colour shows. The order matches `colorLabels` / `colorWords` and the preference names in
/// `codecity.gui.app`. `kind` is categorical; all the others are numeric and coloured through `Scale`.
enum ColorMetric { commits, age, authors, prs, cx, density, loc, kind }

immutable string[8] colorLabels = [
    "Churn (commits)", "Recency", "Authors", "Open pull requests", "Complexity (WMC)", "Complexity / method",
    "Lines of code", "Type kind",
];
/// The words under the two ends of the legend (low end, high end); empty for the categorical `kind`.
immutable string[2][8] colorWords = [
    ["stable", "hot spot"], ["dormant", "just changed"], ["one owner", "many hands"], ["none", "contested"],
    ["simple", "complex"], ["flat", "branchy"], ["small", "large"], ["", ""],
];

/// null when available, otherwise why not: a suffix for the menu entry. The git metrics need git history, `prs`
/// needs GitHub pull requests.
string unavailable(ColorMetric m, CityModel model)
{
    if ((m == ColorMetric.commits || m == ColorMetric.age || m == ColorMetric.authors) && !model.hasGit) return " (no git)";
    if (m == ColorMetric.prs && !model.hasPulls) return " (no GitHub)";
    return null;
}

/// Current time in seconds since the epoch (UTC).
long now() { return Clock.currTime.toUnixTime; }

/// The raw value of a colour metric for one building. Files without git history count as 0 commits / authors; their
/// `age` is infinite, which `Scale.color` paints as the coldest colour ("dormant"). `age` is in days since the last
/// commit, measured from now, so it changes while the viewer runs. `density` is complexity per method.
double colorValue(ColorMetric m, ref const TypeDecl t, ref const Building b)
{
    final switch (m)
    {
    case ColorMetric.commits: return b.hasHistory ? b.history.commits : 0;
    case ColorMetric.age:
        if (!b.hasHistory) return double.infinity;
        const days = (now() - b.history.lastCommit) / 86400.0;
        return days > 0 ? days : 0;
    case ColorMetric.authors: return b.hasHistory ? b.history.authors : 0;
    case ColorMetric.prs: return b.prs.length;
    case ColorMetric.cx: return t.complexity;
    case ColorMetric.density: return t.nom == 0 ? 0 : cast(double) t.complexity / t.nom;
    case ColorMetric.loc: return t.loc;
    case ColorMetric.kind: return 0;
    }
}

/// History replay: the raw value of a colour metric for one building in snapshot `k`, whose author time is `at`.
/// The git metrics use the commits up to that snapshot (`Building.hist`) and recency is measured from `at` instead
/// of now; the code metrics use the snapshot's values. Open pull requests belong to the present, so `prs` is never
/// asked for here (the viewer falls back to churn during replay).
double colorValueAt(ColorMetric m, ref const Building b, size_t k, long at)
{
    final switch (m)
    {
    case ColorMetric.commits, ColorMetric.prs: return b.hist.commits[k];
    case ColorMetric.age:
        if (b.hist.last[k] < 0) return double.infinity;
        const days = (at - b.hist.last[k]) / 86400.0;
        return days > 0 ? days : 0;
    case ColorMetric.authors: return b.hist.authors[k];
    // an absent type (-1) counts as 0, like in the other viewers
    case ColorMetric.cx: return b.hist.cx[k] > 0 ? b.hist.cx[k] : 0;
    case ColorMetric.density: return b.hist.nom[k] <= 0 ? 0 : cast(double) b.hist.cx[k] / b.hist.nom[k];
    case ColorMetric.loc: return b.hist.loc[k] > 0 ? b.hist.loc[k] : 0;
    case ColorMetric.kind: return 0;
    }
}

/// Colour scale for a metric: log-scaled and clipped at the 98th percentile.
///
/// A position `x` in 0..1 along the ramp is `log1p(v) / log1p(hi)` (or `v / hi` for `density`, whose values are small
/// ratios), so one huge outlier does not wash every other building out to blue and everything at or above `hi` is
/// the hottest colour. `age` is inverted: few days since the last commit is hot.
struct Scale
{
    ColorMetric metric;
    /// The legend's end values: `lo` is the smallest finite value (shown only, the colour mapping always starts at 0),
    /// `hi` the 98th percentile.
    double lo = 0, hi = 1;

    /// Builds the scale for `metric` from the finite values of all buildings (including hidden test types, but not
    /// the types that are gone today, which only the history replay shows).
    static Scale of(ColorMetric metric, CityModel model)
    {
        double[] values;
        foreach (ref b; model.buildings)
            if (!b.gone) values ~= colorValue(metric, model.analysis.types[b.index], b);
        return over(metric, values);
    }

    /// The scale for `metric` over the raw metric `values` of the buildings it is measured on; infinite values are
    /// left out.
    static Scale over(ColorMetric metric, const(double)[] values)
    {
        double[] finite;
        foreach (v; values) if (isFinite(v)) finite ~= v;
        finite.sort();
        // nearest-rank percentile, rounding the index down; the `min` only guards against rounding at the top
        const p98 = finite.length ? finite[min(cast(size_t)(0.98 * (finite.length - 1)), finite.length - 1)] : 0;
        const linear = metric == ColorMetric.density;
        // `hi` is at least 1 (or 1e-6 for the linear scale) so the divisions in `color` never divide by zero and a
        // city of all-zero values does not stretch tiny differences over the whole ramp
        const floorValue = linear ? 1e-6 : 1;
        const hi = p98 > floorValue ? p98 : floorValue;
        const lo = finite.length ? (finite[0] < hi ? finite[0] : hi) : 0;
        return Scale(metric, lo, hi);
    }

    /// The building's colour on this scale (or its kind colour for `ColorMetric.kind`). Values above `hi` saturate at
    /// the hot end because `rampColor` clamps.
    RGB color(ref const TypeDecl t, ref const Building b) const
    {
        if (metric == ColorMetric.kind) return kindColor(t.kind);
        return valueColor(colorValue(metric, t, b));
    }

    /// History replay: the building's colour in snapshot `k` (author time `at`) on this scale, i.e. on the
    /// present-day scale, so colours stay comparable over time.
    RGB colorAt(ref const TypeDecl t, ref const Building b, size_t k, long at) const
    {
        if (metric == ColorMetric.kind) return kindColor(t.kind);
        return valueColor(colorValueAt(metric, b, k, at));
    }

    /// The colour of the raw value `v` of this scale's metric for a type of kind `kind` (its kind colour for
    /// `ColorMetric.kind`).
    RGB colorOf(double v, string kind) const
    {
        if (metric == ColorMetric.kind) return kindColor(kind);
        return valueColor(v);
    }

    /// The legend's texts under the two ends of the ramp: the formatted `lo` and `hi` with a `+` (values above `hi`
    /// are clipped there). Recency is inverted on the ramp, so its values are swapped: the oldest age is on the left
    /// under "dormant", the newest on the right.
    string[2] ends() const
    {
        const lo = show(this.lo), hi = show(this.hi) ~ "+";
        return metric == ColorMetric.age ? [hi, lo] : [lo, hi];
    }

    /// The ramp colour of a raw value of this scale's (numeric) metric.
    private RGB valueColor(double v) const
    {
        const invert = metric == ColorMetric.age;
        double x;
        if (!isFinite(v)) x = invert ? 0 : 1;
        else
        {
            x = metric == ColorMetric.density ? v / hi : log1p(v) / log1p(hi);
            if (invert) x = 1 - x;
        }
        return rampColor(x);
    }

    /// A value formatted for the legend: days for `age`, otherwise whole numbers via `fmt` and fractions to one place.
    string show(double v) const
    {
        if (metric == ColorMetric.age) return format("%.0fd ago", round(v));
        return v == trunc(v) ? fmt(v) : oneDecimal(v);
    }
}

/// Tenths as a number with one decimal: 123 → "12.3", -5 → "-0.5". Every one-decimal number the viewers show is
/// rounded to tenths first (half up) and then written by this, the same way in every edition.
string tenths(long t)
{
    const a = t < 0 ? -t : t;
    return format("%s%d.%d", t < 0 ? "-" : "", a / 10, a % 10);
}

/// `x` with one decimal, rounded half up: floor(10x + 0.5) tenths.
string oneDecimal(double x)
{
    return tenths(cast(long) floor(x * 10 + 0.5));
}

/// The ratio `a / b` (b > 0) with one decimal, rounded half up in integers: 5 / 4 → "1.3".
string ratio(long a, long b)
{
    const n = 20 * a + b, d = 2 * b;
    // floor division, also for a negative numerator
    return tenths(n >= 0 ? n / d : -((-n + d - 1) / d));
}

/// Compact number formatting: `1,234`, `12.3k`, `123k`; negative numbers keep all digits (`-1,234`).
string fmt(double n)
{
    // rounded half up to a whole number first; thousands and tenths of thousands then follow in integers, so a tie
    // (12,250 → 12.3k) rounds up exactly as in the other viewers, whatever the binary value of n / 1000
    const v = cast(long) floor(n + 0.5);
    if (n >= 100_000) return format("%dk", (v + 500) / 1000);
    if (n >= 10_000) return tenths((v + 50) / 100) ~ "k";
    // separators are counted on the digits only, so the minus sign does not shift them
    auto s = format("%d", v < 0 ? -v : v);
    string output = v < 0 ? "-" : "";
    foreach (i, c; s)
    {
        if (i > 0 && (s.length - i) % 3 == 0) output ~= ',';
        output ~= c;
    }
    return output;
}

// fmt: signs and separators (the same cases as Rust's `look_tests.rs` and Java's `LookTest`)
unittest
{
    assert(fmt(-123) == "-123");
    assert(fmt(-1234) == "-1,234");
    assert(fmt(0) == "0");
    assert(fmt(999.4) == "999");
    assert(fmt(999.5) == "1,000");
    assert(fmt(9876) == "9,876");
    assert(fmt(10_000) == "10.0k");
    assert(fmt(12_345) == "12.3k");
    assert(fmt(99_999) == "100.0k");
    assert(fmt(100_000) == "100k");
    assert(fmt(1_234_567) == "1235k");
    // negative halves round up too (towards +∞)
    assert(fmt(-2.5) == "-2");
}

// one-decimal ties round half up, also where n / 1000 is not exact in binary (10.35 is stored as 10.3499…)
unittest
{
    assert(fmt(12_250) == "12.3k");
    assert(fmt(10_350) == "10.4k");
    assert(fmt(12_249) == "12.2k");
    assert(fmt(150_500) == "151k");
    assert(ratio(5, 4) == "1.3");
    assert(ratio(23, 20) == "1.2");
    assert(ratio(1, 3) == "0.3");
    assert(ratio(25, 8) == "3.1");
    assert(oneDecimal(1.25) == "1.3");
    assert(oneDecimal(2.5) == "2.5");
    assert(oneDecimal(-0.25) == "-0.2");
    assert(tenths(0) == "0.0" && tenths(-5) == "-0.5" && tenths(123) == "12.3");
    enum long now = 1_790_769_600, day = 86_400;
    assert(relTimeAt(now - 456 * day - 6 * 3600, now) == "1.3y ago");
    assert(Scale.over(ColorMetric.density, [0.25, 1.25, 1.25]).ends == ["0.3", "1.3+"]);
}

/// Whether `url` may be handed to the system browser: an `https://` URL without spaces or control characters. Link
/// targets come from the city data (pull request URLs from the GitHub API, the repository URL from the analysed tree's
/// git remote), so anything else (`file:`, `javascript:`, custom URL schemes that start other applications,
/// option-like strings) is refused.
bool isWebLink(string url)
{
    import std.algorithm : any;
    import std.uni : sicmp;
    return url.length >= 8 && sicmp(url[0 .. 8], "https://") == 0 && !url.any!(c => c <= ' ' || c == 0x7f);
}

unittest
{
    assert(isWebLink("https://github.com/o/r/pull/1"));
    assert(isWebLink("HTTPS://github.com/o/r"));
    foreach (bad; ["javascript:alert(1)", "file:///etc/passwd", "http://x", "--help", "https://x y", "https://x\n", "ssh://h", "", "https:/", "https://x\x7f"])
        assert(!isWebLink(bad), bad);
}

/// "today", "5d ago", "3mo ago" or "1.2y ago" for a Unix timestamp (months are 30 days, years 365); "—" for 0 or less,
/// which callers use for "unknown".
string relTime(long epoch)
{
    return relTimeAt(epoch, now());
}

/// `relTime` measured at `now` (Unix seconds) instead of the current time.
string relTimeAt(long epoch, long now)
{
    if (epoch <= 0) return "—";
    const days = (now - epoch) / 86400.0;
    if (days < 1) return "today";
    if (days < 30) return format("%.0fd ago", round(days));
    if (days < 365) return format("%.0fmo ago", round(days / 30));
    // tenths of years in integers: half up of (seconds · 10) / (seconds per 365 days)
    return ratio(now - epoch, 365L * 86_400) ~ "y ago";
}

// ------------------------------------------------------------------ tests
//
// The same values as Rust's `look_tests.rs` and Java's `LookTest`.

version (unittest)
{
    /// `#rrggbb` of a colour.
    private string hexOf(RGB c) { return format("#%02x%02x%02x", c.r, c.g, c.b); }
}

// the ramp hits its stops and blends between them
unittest
{
    assert(hexOf(rampColor(0)) == "#2b6cb0");
    assert(hexOf(rampColor(0.5)) == "#e8e15b");
    assert(hexOf(rampColor(1)) == "#a3123a");
    assert(hexOf(rampColor(-1)) == "#2b6cb0");
    assert(hexOf(rampColor(2)) == "#a3123a");
    assert(hexOf(rampColor(1.0 / 32)) == "#2b79b4");
    assert(hexOf(rampColor(0.3)) == "#58cb94");
    assert(hexOf(rampColor(0.7)) == "#f2933a");
    assert(hexOf(rampColor(0.9)) == "#d13630");
}

// kinds have fixed colours and unknown ones look like classes
unittest
{
    string[] order;
    foreach (k; kinds) order ~= k.name;
    assert(order == ["class", "abstract", "interface", "enum", "record", "annotation", "struct", "trait", "union", "type", "module"]);
    assert(hexOf(kindColor("class")) == "#6b8fc7");
    assert(hexOf(kindColor("trait")) == "#26a69a");
    assert(hexOf(kindColor("module")) == "#7c8ba1");
    assert(hexOf(kindColor("widget")) == "#6b8fc7");
    assert(hexOf(hex(outColor)) == "#f97316" && hexOf(hex(inColor)) == "#0ea5e9" && hexOf(hex(prColor)) == "#c084fc");
}

// day and night palettes
unittest
{
    const day = palette(Theme.day), night = palette(Theme.night);
    assert(hexOf(day.background) == "#e8ecf1" && hexOf(day.ground) == "#dfe5ec" && hexOf(day.dim) == "#d5dbe3");
    assert(day.districts == [0xc3ccd7, 0xcfd7e0, 0xd9dfe7, 0xe2e7ed, 0xeaeef2, 0xf0f3f6]);
    assert(hexOf(night.background) == "#04070d" && hexOf(night.ground) == "#060b14" && hexOf(night.dim) == "#0c1220");
    assert(night.districts == [0x0e1626, 0x121c30, 0x16223a, 0x1a2844, 0x1e2e4e, 0x223458]);
    assert(day.glow == 0 && night.glow > 0);
}

// metric menus and legend words
unittest
{
    assert(heightLabels == ["Methods (NOM)", "Lines of code", "Complexity (WMC)", "Attributes (NOA)"]);
    assert(colorLabels == ["Churn (commits)", "Recency", "Authors", "Open pull requests", "Complexity (WMC)",
        "Complexity / method", "Lines of code", "Type kind"]);
    assert(colorWords[ColorMetric.age] == ["dormant", "just changed"]);
    assert(colorWords[ColorMetric.density] == ["flat", "branchy"]);
    assert(colorWords[ColorMetric.kind] == ["", ""]);
}

// the scale clips at the 98th percentile on a log ramp
unittest
{
    double[] hundred;
    foreach (k; 0 .. 101) hundred ~= k;
    const s = Scale.over(ColorMetric.cx, hundred);
    assert(s.lo == 0 && s.hi == 98);
    assert(hexOf(s.colorOf(0, "class")) == "#2b6cb0");
    assert(hexOf(s.colorOf(3, "class")) == "#5acb93");
    assert(hexOf(s.colorOf(98, "class")) == "#a3123a");
    assert(hexOf(s.colorOf(500, "class")) == "#a3123a");
    assert(s.ends == ["0", "98+"]);
    const empty = Scale.over(ColorMetric.loc, []);
    assert(empty.lo == 0 && empty.hi == 1);
    const zeros = Scale.over(ColorMetric.commits, [0, 0, double.infinity]);
    assert(zeros.lo == 0 && zeros.hi == 1);
    assert(hexOf(zeros.colorOf(double.infinity, "class")) == "#a3123a");
    assert(Scale.over(ColorMetric.loc, [50, 12_345, 12_345]).ends == ["50", "12.3k+"]);
}

// density is linear and recency inverted
unittest
{
    const d = Scale.over(ColorMetric.density, [0.5, 2.5, 2.5]);
    assert(d.lo == 0.5 && d.hi == 2.5);
    assert(hexOf(d.colorOf(1.5, "class")) == "#f3bc48");
    assert(d.ends == ["0.5", "2.5+"]);
    assert(Scale.over(ColorMetric.density, []).hi == 1e-6);
    const a = Scale.over(ColorMetric.age, [0, 3, 30, 365, double.infinity]);
    assert(a.lo == 0 && a.hi == 30);
    assert(hexOf(a.colorOf(0, "class")) == "#a3123a");
    assert(hexOf(a.colorOf(3, "class")) == "#f3be49");
    assert(hexOf(a.colorOf(30, "class")) == "#2b6cb0");
    assert(hexOf(a.colorOf(365, "class")) == "#2b6cb0");
    assert(hexOf(a.colorOf(double.infinity, "class")) == "#2b6cb0");
    assert(a.ends == ["30d ago+", "0d ago"]);
    assert(hexOf(Scale.over(ColorMetric.kind, []).colorOf(0, "trait")) == "#26a69a");
}

// ages relative to now
unittest
{
    enum long now = 1_790_769_600, day = 86_400;
    assert(relTimeAt(0, now) == "—");
    assert(relTimeAt(-5, now) == "—");
    assert(relTimeAt(now - 3600, now) == "today");
    assert(relTimeAt(now + day, now) == "today");
    assert(relTimeAt(now - 5 * day, now) == "5d ago");
    assert(relTimeAt(now - 29 * day - 14 * 3600, now) == "30d ago");
    assert(relTimeAt(now - 45 * day, now) == "2mo ago");
    assert(relTimeAt(now - 364 * day, now) == "12mo ago");
    assert(relTimeAt(now - 400 * day, now) == "1.1y ago");
}
