/// Palettes, themes and metric definitions shared with the other viewers.
module codecity.gui.look;

import codecity.city : Building, CityModel;
import codecity.model : TypeDecl;
import std.algorithm : clamp, filter, map, min, sort;
import std.array : array;
import std.datetime.systime : Clock;
import std.format : format;
import std.math : floor, isFinite, log1p, round, trunc;

struct RGB
{
    ubyte r, g, b;
    float[3] linear() const
    {
        import std.math : pow;
        return [pow(r / 255.0f, 2.2f), pow(g / 255.0f, 2.2f), pow(b / 255.0f, 2.2f)];
    }
}

RGB hex(uint h) pure { return RGB(cast(ubyte)(h >> 16), cast(ubyte)(h >> 8), cast(ubyte) h); }

RGB mix(RGB a, RGB b, float t) pure
{
    ubyte l(ubyte x, ubyte y) { return cast(ubyte) clamp(round(x + (y - x) * t), 0, 255); }
    return RGB(l(a.r, b.r), l(a.g, b.g), l(a.b, b.b));
}

immutable uint[9] ramp = [0x2b6cb0, 0x2a9fc0, 0x34c3a5, 0x8fd67a, 0xe8e15b, 0xf6b343, 0xef7d34, 0xdc3f2e, 0xa3123a];

RGB rampColor(double t) pure
{
    const x = clamp(t, 0.0, 1.0) * (ramp.length - 1);
    const i = min(cast(size_t) floor(x), ramp.length - 2);
    return mix(hex(ramp[i]), hex(ramp[i + 1]), cast(float)(x - i));
}

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

enum uint outColor = 0xf97316, inColor = 0x0ea5e9, prColor = 0xc084fc;

RGB kindColor(string kind)
{
    foreach (k; kinds) if (k.name == kind) return hex(k.color);
    return hex(kinds[0].color);
}

enum Theme { day, night }

struct Palette
{
    RGB background, ground;
    uint[6] districts;
    RGB ambient;
    float ambientIntensity;
    RGB sun;
    float sunIntensity;
    float glow, windows;
    RGB dim;
}

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

enum HeightMetric { nom, loc, cx, noa }

immutable string[4] heightLabels = ["Methods (NOM)", "Lines of code", "Complexity (WMC)", "Attributes (NOA)"];

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

enum ColorMetric { commits, age, authors, prs, cx, density, loc, kind }

immutable string[8] colorLabels = [
    "Churn (commits)", "Recency", "Authors", "Open pull requests", "Complexity (WMC)", "Complexity / method",
    "Lines of code", "Type kind",
];
immutable string[2][8] colorWords = [
    ["stable", "hot spot"], ["dormant", "just changed"], ["one owner", "many hands"], ["none", "contested"],
    ["simple", "complex"], ["flat", "branchy"], ["small", "large"], ["", ""],
];

/// null when available, otherwise why not.
string unavailable(ColorMetric m, CityModel model)
{
    if ((m == ColorMetric.commits || m == ColorMetric.age || m == ColorMetric.authors) && !model.hasGit) return " (no git)";
    if (m == ColorMetric.prs && !model.hasPulls) return " (no GitHub)";
    return null;
}

long now() { return Clock.currTime.toUnixTime; }

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
struct Scale
{
    ColorMetric metric;
    double lo = 0, hi = 1;

    static Scale of(ColorMetric metric, CityModel model)
    {
        double[] finite;
        foreach (ref b; model.buildings)
        {
            const v = colorValue(metric, model.analysis.types[b.index], b);
            if (isFinite(v)) finite ~= v;
        }
        finite.sort();
        const p98 = finite.length ? finite[min(cast(size_t)(0.98 * (finite.length - 1)), finite.length - 1)] : 0;
        const linear = metric == ColorMetric.density;
        const floorValue = linear ? 1e-6 : 1;
        const hi = p98 > floorValue ? p98 : floorValue;
        const lo = finite.length ? (finite[0] < hi ? finite[0] : hi) : 0;
        return Scale(metric, lo, hi);
    }

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

    string show(double v) const
    {
        if (metric == ColorMetric.age) return format("%.0fd ago", round(v));
        return v == trunc(v) ? fmt(v) : format("%.1f", v);
    }
}

string fmt(double n)
{
    if (n >= 100_000) return format("%.0fk", round(n / 1000));
    if (n >= 10_000) return format("%.1fk", n / 1000);
    auto s = format("%d", cast(long) round(n));
    string output;
    foreach (i, c; s)
    {
        if (i > 0 && (s.length - i) % 3 == 0 && c != '-') output ~= ',';
        output ~= c;
    }
    return output;
}

string relTime(long epoch)
{
    if (epoch <= 0) return "—";
    const days = (now() - epoch) / 86400.0;
    if (days < 1) return "today";
    if (days < 30) return format("%.0fd ago", round(days));
    if (days < 365) return format("%.0fmo ago", round(days / 30));
    return format("%.1fy ago", days / 365);
}
