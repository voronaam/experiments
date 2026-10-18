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

    /// Builds the scale for `metric` from the finite values of all buildings (including hidden test types).
    static Scale of(ColorMetric metric, CityModel model)
    {
        double[] finite;
        foreach (ref b; model.buildings)
        {
            const v = colorValue(metric, model.analysis.types[b.index], b);
            if (isFinite(v)) finite ~= v;
        }
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
        const v = colorValue(metric, t, b);
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
        return v == trunc(v) ? fmt(v) : format("%.1f", v);
    }
}

/// Compact number formatting: `1,234`, `12.3k`, `123k`; negative numbers keep all digits (`-1,234`).
string fmt(double n)
{
    if (n >= 100_000) return format("%.0fk", round(n / 1000));
    if (n >= 10_000) return format("%.1fk", n / 1000);
    const v = cast(long) round(n);
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

unittest
{
    assert(fmt(-123) == "-123");
    assert(fmt(-1234) == "-1,234");
    assert(fmt(9876) == "9,876");
    assert(fmt(12_345) == "12.3k");
}

/// "today", "5d ago", "3mo ago" or "1.2y ago" for a Unix timestamp (months are 30 days, years 365); "—" for 0 or less,
/// which callers use for "unknown".
string relTime(long epoch)
{
    if (epoch <= 0) return "—";
    const days = (now() - epoch) / 86400.0;
    if (days < 1) return "today";
    if (days < 30) return format("%.0fd ago", round(days));
    if (days < 365) return format("%.0fmo ago", round(days / 30));
    return format("%.1fy ago", days / 365);
}
