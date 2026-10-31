/// Viewer preferences, saved as JSON to `$XDG_CONFIG_HOME/codecity/desktop.json` (default `~/.config/...`) after every
/// change.
///
/// The Rust edition (`rust/src/gui/prefs.rs`) reads and writes the same file with the same keys and value names, so a
/// choice made in one desktop viewer carries over to the other; both editions' tests read the shared examples in
/// `testdata/desktop-prefs/` and check that they write byte-identical files.
///
/// The file is one JSON object: `height` and `color` hold the metric's name as the Rust edition spells its enum
/// variants (`"Nom"`, `"Commits"`, …; `color` is `null` until the user picks one), `theme` is `"day"` or `"night"`, and
/// `tests`, `labels`, `beacons` and `shadows` are booleans. Keys are written in sorted order without spaces.
module codecity.gui.prefs;

import codecity.gui.look : ColorMetric, HeightMetric, Theme;
import std.file : mkdirRecurse, readText;
static import std.file;
import std.json;
import std.path : buildPath, dirName;
import std.process : environment;

/// The desktop viewer's settings. The defaults are what a first start (or an unreadable file) gives: method-count
/// heights, no colour chosen yet, day, and test code, labels, PR beacons and shadows all shown.
struct Prefs
{
    HeightMetric height = HeightMetric.nom;
    /// Whether the user ever chose a colour metric. Without one, the default depends on the model (churn when there is
    /// git history, complexity otherwise; see `codecity.gui.rules.effectiveColor`).
    bool hasColor;
    ColorMetric color;
    Theme theme = Theme.day;
    bool tests = true, labels = true, beacons = true, shadows = true;

    // The names stored in the file, in enum order. They are the Rust edition's enum variant names, so do not change
    // them without changing both editions.
    static immutable string[4] heightNames = ["Nom", "Loc", "Cx", "Noa"];
    static immutable string[8] colorNames = ["Commits", "Age", "Authors", "Prs", "Cx", "Density", "Loc", "Kind"];

    /// Where the settings live, from the environment (see `pathIn`).
    static string path()
    {
        return pathIn(environment.get("XDG_CONFIG_HOME", ""), environment.get("HOME", "."));
    }

    /// `<xdg>/codecity/desktop.json` when `xdg` (`XDG_CONFIG_HOME`) is not empty, else
    /// `<home>/.config/codecity/desktop.json`.
    static string pathIn(string xdg, string home)
    {
        return buildPath(xdg.length ? xdg : buildPath(home, ".config"), "codecity", "desktop.json");
    }

    /// Reads the settings file.
    static Prefs load()
    {
        return read(path());
    }

    /// Reads the settings from `file`. A missing or malformed file, or a missing, unknown or wrongly typed value,
    /// silently falls back to the default for that setting, so old or hand-edited files never stop the viewer from
    /// starting.
    static Prefs read(string file)
    {
        try return parse(readText(file));
        catch (Exception) return Prefs.init;
    }

    /// The settings in the JSON text `text`, by the rules of `read`.
    static Prefs parse(string text)
    {
        Prefs p;
        JSONValue v;
        try v = parseJSON(text);
        catch (Exception) return p;
        if (v.type != JSONType.object) return p;
        string s(string k) { auto x = k in v; return x && x.type == JSONType.string ? x.str : null; }
        // only a real boolean counts; anything else keeps the default, as in the Rust edition
        bool b(string k, bool d)
        {
            auto x = k in v;
            return x && x.type == JSONType.true_ ? true : x && x.type == JSONType.false_ ? false : d;
        }
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
        return p;
    }

    /// The settings as the file's JSON text.
    string json() const
    {
        JSONValue v = [
            "height": JSONValue(heightNames[height]),
            "color": hasColor ? JSONValue(colorNames[color]) : JSONValue(null),
            "theme": JSONValue(theme == Theme.night ? "night" : "day"),
            "tests": JSONValue(tests), "labels": JSONValue(labels), "beacons": JSONValue(beacons), "shadows": JSONValue(shadows),
        ];
        return v.toString;
    }

    /// Writes the settings file.
    void save() const
    {
        write(path());
    }

    /// Writes the settings to `file`, creating its directory if needed. Errors are ignored: losing a view preference is
    /// not worth interrupting the user for.
    void write(string file) const
    {
        try
        {
            mkdirRecurse(file.dirName);
            std.file.write(file, json());
        }
        catch (Exception) {}
    }
}

// ------------------------------------------------------------------ tests
//
// The same scenarios as Rust's `prefs_tests.rs`, on the shared examples in `testdata/desktop-prefs/`.

version (unittest)
{
    import std.path : buildNormalizedPath;

    /// `testdata/desktop-prefs/<name>`.
    private string example(string name)
    {
        return buildNormalizedPath(__FILE_FULL_PATH__.dirName, "..", "..", "..", "..", "testdata", "desktop-prefs", name);
    }

    /// Settings with every field given; `color` is used when `hasColor`.
    private Prefs prefs(HeightMetric height, bool hasColor, ColorMetric color, Theme theme, bool[4] flags)
    {
        return Prefs(height, hasColor, hasColor ? color : ColorMetric.init, theme, flags[0], flags[1], flags[2], flags[3]);
    }

    /// A fresh temporary directory, removed by the caller.
    private string tempDirectory(string name)
    {
        import std.file : tempDir;
        import std.format : format;
        import std.process : thisProcessID;
        const dir = buildPath(tempDir, format("codecity-prefs-%s-%d", name, thisProcessID));
        mkdirRecurse(dir);
        return dir;
    }
}

// defaults on first start
unittest
{
    import std.file : rmdirRecurse;
    assert(Prefs.init == prefs(HeightMetric.nom, false, ColorMetric.init, Theme.day, [true, true, true, true]));
    const dir = tempDirectory("defaults");
    scope (exit) rmdirRecurse(dir);
    assert(Prefs.read(buildPath(dir, "missing.json")) == Prefs.init);
    assert(Prefs.init.json == readText(example("defaults.json")));
}

// reads the shared examples
unittest
{
    with (HeightMetric) with (Theme)
    {
        assert(Prefs.read(example("defaults.json")) == Prefs.init);
        assert(Prefs.read(example("all-set.json")) == prefs(cx, true, ColorMetric.age, night, [false, false, false, true]));
        assert(Prefs.read(example("corrupt.json")) == Prefs.init);
        assert(Prefs.read(example("not-an-object.json")) == Prefs.init);
        assert(Prefs.read(example("wrong-types.json")) == prefs(nom, false, ColorMetric.init, day, [true, true, true, false]));
        assert(Prefs.read(example("partial.json")) == prefs(noa, false, ColorMetric.init, day, [true, true, true, false]));
    }
}

// writes what it reads
unittest
{
    import std.file : rmdirRecurse;
    import std.traits : EnumMembers;
    const text = readText(example("all-set.json"));
    assert(Prefs.parse(text).json == text);
    const dir = tempDirectory("roundtrip");
    scope (exit) rmdirRecurse(dir);
    const file = buildPath(dir, "config", "codecity", "desktop.json");
    foreach (k, height; [EnumMembers!HeightMetric])
        foreach (c; 0 .. 9)
        {
            const theme = k % 2 ? Theme.night : Theme.day;
            const p = prefs(height, c < 8, cast(ColorMetric)(c % 8), theme, [k == 0, k == 1, k == 2, k == 3]);
            p.write(file);
            assert(Prefs.read(file) == p);
        }
}

// an unwritable file is ignored
unittest
{
    import std.file : isDir, rmdirRecurse;
    const dir = tempDirectory("unwritable");
    scope (exit) rmdirRecurse(dir);
    Prefs.init.write(dir);
    assert(isDir(dir));
    assert(Prefs.read(dir) == Prefs.init);
}

// the file location follows XDG, then HOME
unittest
{
    assert(Prefs.pathIn("/x", "/h") == "/x/codecity/desktop.json");
    assert(Prefs.pathIn("", "/h") == "/h/.config/codecity/desktop.json");
}
