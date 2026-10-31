/// History replay in the desktop viewer: the playback position and the rules that turn it into what each building
/// shows. The same rules as in the browser viewer (`replay.js`) and the Java and Rust desktop viewers
/// (`Replay.java`, `rust/src/gui/replay.rs`), so every viewer replays a city identically.
///
/// The position `p` runs continuously over [0, S − 1] for S snapshots. Between two snapshots a = ⌊p⌋ and
/// b = min(a + 1, S − 1), heights and footprints are interpolated with f = p − a, so buildings grow smoothly. Colours,
/// district visibility, search and arcs use the nearest snapshot, round(p) with halves rounded up.
///
/// This module holds the state and the pure arithmetic; `codecity.gui.app` owns the timeline bar and feeds the results
/// to the renderer.
module codecity.gui.replay;

import codecity.city : TypeHistory;
import codecity.gui.look : ColorMetric, HeightMetric;
import codecity.history : Snapshot;
import codecity.layout : side;
import std.algorithm : clamp, min;
import std.conv : to;
import std.format : format;
import std.math : ceil, floor;

/// Playback speeds, in the order the speed button cycles through them, and their button labels.
immutable double[4] speeds = [1, 2, 4, 0.5];
immutable string[4] speedLabels = ["1×", "2×", "4×", "0.5×"];
/// Seconds per snapshot at 1×.
enum secondsPerSnapshot = 0.8;

/// The snapshots on either side of the position and how far it is from `a` towards `b`.
struct Frame
{
    size_t a, b;
    double f;
}

/// A replay in progress.
struct Replay
{
    /// Number of snapshots (at least 1).
    size_t count = 1;
    /// Playback position in [0, count − 1].
    double p = 0;
    bool playing;
    /// Index into `speeds`.
    size_t speed;

    /// A replay over `count` snapshots (at least 1), at the first snapshot, playing, at 1×.
    static Replay start(size_t count)
    {
        Replay r;
        r.count = count > 0 ? count : 1;
        r.playing = true;
        return r;
    }

    private double end() const { return count - 1.0; }

    /// Advances by `dt` seconds while playing: `dt · speed / 0.8` snapshots. Reaching the last snapshot stops there,
    /// paused.
    void advance(double dt)
    {
        if (!playing) return;
        p = min(p + dt * speeds[speed] / secondsPerSnapshot, end);
        if (p >= end) playing = false;
    }

    /// Play or pause; playing from the end starts over from the first snapshot.
    void toggle()
    {
        if (!playing && p >= end) p = 0;
        playing = !playing;
    }

    /// Moves to the previous (`dir < 0`) or next whole snapshot, and pauses. From between two snapshots, the previous
    /// one is the one just before the position and the next the one just after it.
    void step(int dir)
    {
        seek(dir < 0 ? ceil(p) - 1 : floor(p) + 1);
    }

    /// Jumps to position `to` (clamped to the snapshots) and pauses; the slider and Home / End use this.
    void seek(double to)
    {
        p = clamp(to, 0.0, end);
        playing = false;
    }

    /// Cycles 1× → 2× → 4× → 0.5×.
    void nextSpeed()
    {
        speed = (speed + 1) % speeds.length;
    }

    /// The speed as shown on its button, e.g. `2×` or `0.5×`.
    string speedLabel() const
    {
        return speedLabels[speed];
    }

    /// round(p), halves up: the snapshot for colours, districts, search, arcs and the timeline text.
    size_t snapshot() const
    {
        return min(cast(size_t) floor(p + 0.5), count - 1);
    }

    /// The snapshots on either side of the position and the blend between them.
    Frame frame() const
    {
        const a = min(cast(size_t) floor(p), count - 1);
        return Frame(a, min(a + 1, count - 1), p - a);
    }
}

/// A building's height-metric value at frame `at`: the two snapshots' values interpolated, an absent value (−1)
/// counting as 0. False (and `value` unset) when the type exists in neither snapshot: the building is hidden.
bool metricAt(ref const TypeHistory h, HeightMetric metric, Frame at, out double value)
{
    const(int)[] values;
    final switch (metric)
    {
    case HeightMetric.nom: values = h.nom; break;
    case HeightMetric.loc: values = h.loc; break;
    case HeightMetric.cx: values = h.cx; break;
    case HeightMetric.noa: values = h.noa; break;
    }
    const va = values[at.a], vb = values[at.b];
    if (va < 0 && vb < 0) return false;
    value = lerp(va < 0 ? 0 : va, vb < 0 ? 0 : vb, at.f);
    return true;
}

/// The footprint side at frame `at`: the layout's side formula for each snapshot's attribute count, interpolated, with
/// an absent type counting as side 0 (so a new building spreads out from its lot's centre).
double sideAt(ref const TypeHistory h, Frame at)
{
    double s(size_t k) { return h.exists(k) ? side(cast(uint) h.noa[k]) : 0; }
    return lerp(s(at.a), s(at.b), at.f);
}

private double lerp(double x, double y, double f) pure { return x + (y - x) * f; }

/// The colour metric used during replay: open pull requests only exist today, so that metric falls back to churn.
ColorMetric replayColorMetric(ColorMetric m) pure
{
    return m == ColorMetric.prs ? ColorMetric.commits : m;
}

/// The timeline's text: local date, short commit id, the subject cut to 60 characters (code points, with an ellipsis),
/// then the author.
string caption(const Snapshot s)
{
    auto subject = s.subject.to!dstring;
    const text = subject.length > 60 ? (subject[0 .. 59] ~ "…"d).to!string : s.subject;
    return format("%s · %s · %s — %s", localDate(s.time), s.commit[0 .. min(7, $)], text, s.author);
}

/// The local calendar date of a Unix time as YYYY-MM-DD.
string localDate(long epoch)
{
    import std.datetime.systime : SysTime;
    const t = SysTime.fromUnixTime(epoch);
    return format("%04d-%02d-%02d", t.year, cast(int) t.month, t.day);
}

// ------------------------------------------------------------------ tests
//
// The same scenarios as Rust's `replay.rs` tests and Java's `ReplayTest`, with the same numbers.

version (unittest)
{
    import std.math : abs, isInfinity;
    private bool near(double a, double b) { return abs(a - b) < 1e-9; }

    /// A history of `n` snapshots in which the type never exists.
    private TypeHistory absent(size_t n)
    {
        TypeHistory h;
        h.nom = new int[n]; h.nom[] = -1;
        h.noa = h.nom.dup; h.loc = h.nom.dup; h.cx = h.nom.dup;
        h.commits = new int[n]; h.authors = new int[n];
        h.last = new long[n]; h.last[] = -1;
        return h;
    }
}

// playback steps and stops at the end
unittest
{
    auto r = Replay.start(5);
    r.advance(0.4);
    assert(r.p == 0.5 && r.snapshot == 1 && r.frame == Frame(0, 1, 0.5));
    r.advance(10);
    assert(r.p == 4 && !r.playing && r.frame == Frame(4, 4, 0));
    r.toggle();
    assert(r.p == 0 && r.playing);
    r.p = 2.3;
    r.step(-1);
    assert(r.p == 2 && !r.playing);
    r.step(-1);
    assert(r.p == 1);
    r.p = 2.3;
    r.step(1);
    assert(r.p == 3);
    foreach (label; ["2×", "4×", "0.5×", "1×"])
    {
        r.nextSpeed();
        assert(r.speedLabel == label);
    }
}

// speeds cycle and scale the playback rate
unittest
{
    auto r = Replay.start(10);
    r.advance(0.8);
    assert(near(r.p, 1));
    r.nextSpeed();
    r.advance(0.8);
    assert(near(r.p, 3));
    r.nextSpeed();
    r.advance(0.2);
    assert(near(r.p, 4));
    r.nextSpeed();
    assert(r.speedLabel == "0.5×");
    r.advance(1.6);
    assert(near(r.p, 5));
}

// paused playback stands still and seeking clamps
unittest
{
    auto r = Replay.start(5);
    r.toggle();
    assert(!r.playing);
    r.advance(3);
    assert(r.p == 0);
    r.seek(99);
    assert(r.p == 4 && !r.playing);
    r.step(1);
    assert(r.p == 4);
    r.seek(0);
    r.step(-1);
    assert(r.p == 0);
    r.seek(2);
    r.step(1);
    assert(r.p == 3);
    r.step(-1);
    assert(r.p == 2);
}

// the nearest snapshot rounds halves up
unittest
{
    auto r = Replay.start(4);
    foreach (c; [[0.0, 0, 0, 1, 0.0], [0.49, 0, 0, 1, 0.49], [0.5, 1, 0, 1, 0.5], [2.5, 3, 2, 3, 0.5], [3.0, 3, 3, 3, 0.0]])
    {
        r.p = c[0];
        assert(r.snapshot == c[1]);
        const f = r.frame;
        assert(f.a == c[2] && f.b == c[3] && near(f.f, c[4]));
    }
}

// a single snapshot never plays
unittest
{
    auto r = Replay.start(1);
    r.advance(0.1);
    assert(r.p == 0 && !r.playing);
    r.toggle();
    r.advance(0.1);
    assert(r.p == 0 && !r.playing);
    assert(r.snapshot == 0 && r.frame == Frame(0, 0, 0));
    assert(Replay.start(0).count == 1);
}

// interpolates absent as zero and hides when absent on both sides
unittest
{
    auto h = absent(3);
    h.nom = [-1, 4, 2];
    h.noa = [-1, 4, 0];
    double v;
    assert(metricAt(h, HeightMetric.nom, Frame(0, 1, 0.25), v) && v == 1);
    assert(metricAt(h, HeightMetric.nom, Frame(1, 2, 0.5), v) && v == 3);
    assert(!metricAt(h, HeightMetric.nom, Frame(0, 0, 0), v));
    assert(sideAt(h, Frame(0, 1, 0.5)) == side(4) / 2);
    assert(sideAt(h, Frame(0, 0, 0)) == 0);
    assert(sideAt(h, Frame(2, 2, 0)) == side(0));
}

// colour values come from the snapshot
unittest
{
    import codecity.city : Building;
    import codecity.gui.look : colorValueAt;
    Building b;
    b.hist = absent(3);
    b.hist.nom = [-1, 0, 4];
    b.hist.cx = [-1, 3, 10];
    b.hist.loc = [-1, 7, 20];
    b.hist.commits = [0, 2, 5];
    b.hist.authors = [0, 1, 2];
    b.hist.last = [-1, 1_000, 1_000 + 3 * 86_400];
    const at = 1_000 + 10 * 86_400;
    with (ColorMetric)
    {
        assert(colorValueAt(commits, b, 2, at) == 5);
        assert(replayColorMetric(prs) == commits);
        assert(colorValueAt(replayColorMetric(prs), b, 1, at) == 2);
        assert(colorValueAt(authors, b, 2, at) == 2);
        assert(colorValueAt(age, b, 2, at) == 7);
        assert(isInfinity(colorValueAt(age, b, 0, at)));
        assert(colorValueAt(age, b, 2, 0) == 0);
        assert(colorValueAt(cx, b, 0, at) == 0);
        assert(colorValueAt(density, b, 1, at) == 0);
        assert(colorValueAt(density, b, 2, at) == 2.5);
        assert(colorValueAt(loc, b, 2, at) == 20);
        assert(colorValueAt(kind, b, 2, at) == 0);
    }
}

// the caption cuts long subjects by characters
unittest
{
    import std.algorithm : canFind, endsWith;
    import std.array : replicate;
    Snapshot s(string subject) { return Snapshot("abcdef1234", 1_790_769_600, "Ann", subject); }
    const short_ = caption(s("fix it"));
    assert(short_.endsWith(" · abcdef1 · fix it — Ann") && short_.length == 10 + " · abcdef1 · fix it — Ann".length);
    const sixty = "x".replicate(60);
    assert(caption(s(sixty)).canFind(" · " ~ sixty ~ " — "));
    assert(caption(s("😀".replicate(61))).endsWith(" · " ~ "😀".replicate(59) ~ "… — Ann"));
}
