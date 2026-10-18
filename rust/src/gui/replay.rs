//! History replay in the desktop viewer: the playback position and the rules that turn it into what each building
//! shows. The same rules as in the web viewer (`app.js`) and the Java and D desktop viewers, so every viewer replays
//! a city identically.
//!
//! The position `p` runs continuously over `[0, S − 1]` for `S` snapshots. Between two snapshots `a = ⌊p⌋` and
//! `b = min(a + 1, S − 1)`, heights and footprints are interpolated with `f = p − a`, so buildings grow smoothly.
//! Colours, district visibility, search and arcs use the nearest snapshot, `round(p)` with halves rounded up.
//!
//! This module holds the state and the pure arithmetic; `gui/mod.rs` owns the timeline bar and feeds the results to
//! the scene.

use super::look::{ColorMetric, HeightMetric};
use crate::history::{Snapshot, Track};
use crate::layout::side_of_noa;

/// Playback speeds, in the order the speed button cycles through them.
pub const SPEEDS: [f64; 4] = [1.0, 2.0, 4.0, 0.5];
/// Seconds per snapshot at 1×.
const SECONDS_PER_SNAPSHOT: f64 = 0.8;

/// A replay in progress.
pub struct Replay {
    /// Number of snapshots (at least 1).
    pub count: usize,
    /// Playback position in `[0, count − 1]`.
    pub p: f64,
    pub playing: bool,
    /// Index into [`SPEEDS`].
    pub speed: usize,
    /// Height per unit of the height metric: the present-day city's scale, fixed when the replay starts (and when the
    /// height metric or the test filter changes), so the city visibly grows instead of being renormalised.
    pub scale: f64,
    /// The position the buildings were last drawn for; None forces a redraw.
    pub drawn: Option<f64>,
    /// The snapshot colours, districts, arcs and search were last updated for; None forces an update.
    pub shown: Option<usize>,
}

impl Replay {
    /// Starts at the first snapshot, playing.
    pub fn new(count: usize, scale: f64) -> Replay {
        Replay { count: count.max(1), p: 0.0, playing: true, speed: 0, scale, drawn: None, shown: None }
    }

    fn end(&self) -> f64 {
        (self.count - 1) as f64
    }

    /// Advances by `dt` seconds while playing; stops (paused) at the last snapshot.
    pub fn advance(&mut self, dt: f64) {
        if self.playing {
            self.p += dt * SPEEDS[self.speed] / SECONDS_PER_SNAPSHOT;
            if self.p >= self.end() {
                self.p = self.end();
                self.playing = false;
            }
        }
    }

    /// Play or pause; playing from the end starts over from the first snapshot.
    pub fn toggle(&mut self) {
        if !self.playing && self.p >= self.end() {
            self.p = 0.0;
        }
        self.playing = !self.playing;
    }

    /// Moves to the previous (`-1`) or next (`+1`) whole snapshot, and pauses. From between two snapshots, the
    /// previous one is the one just before the position and the next the one just after it.
    pub fn step(&mut self, dir: i32) {
        self.playing = false;
        let to = if dir < 0 { self.p.ceil() - 1.0 } else { self.p.floor() + 1.0 };
        self.p = to.clamp(0.0, self.end());
    }

    /// Jumps to snapshot `k` (clamped) and pauses.
    pub fn seek(&mut self, k: usize) {
        self.playing = false;
        self.p = (k as f64).min(self.end());
    }

    /// Cycles 1× → 2× → 4× → 0.5×.
    pub fn next_speed(&mut self) {
        self.speed = (self.speed + 1) % SPEEDS.len();
    }

    /// The speed as shown on its button, e.g. `2×` or `0.5×`.
    pub fn speed_label(&self) -> String {
        format!("{}×", SPEEDS[self.speed])
    }

    /// `round(p)`, halves up: the snapshot for colours, districts, search, arcs and the timeline text.
    pub fn snapshot(&self) -> usize {
        ((self.p + 0.5).floor() as usize).min(self.count - 1)
    }

    /// `(a, b, f)`: the snapshots on either side of the position and how far it is from `a` towards `b`.
    pub fn frame(&self) -> (usize, usize, f64) {
        let a = (self.p.floor().max(0.0) as usize).min(self.count - 1);
        (a, (a + 1).min(self.count - 1), self.p - a as f64)
    }
}

/// A building's height-metric value at the position `(a, b, f)`: the two snapshots' values interpolated, an absent
/// value (−1) counting as 0. None when the type exists in neither snapshot: the building is hidden.
pub fn metric_at(track: &Track, metric: HeightMetric, (a, b, f): (usize, usize, f64)) -> Option<f64> {
    let values = match metric {
        HeightMetric::Nom => &track.nom,
        HeightMetric::Loc => &track.loc,
        HeightMetric::Cx => &track.cx,
        HeightMetric::Noa => &track.noa,
    };
    let (va, vb) = (values[a], values[b]);
    if va < 0 && vb < 0 {
        return None;
    }
    Some(lerp(va.max(0) as f64, vb.max(0) as f64, f))
}

/// The footprint side at `(a, b, f)`: the layout's side formula for each snapshot's attribute count, interpolated,
/// with an absent type counting as side 0 (so a new building spreads out from its lot's centre).
pub fn side_at(track: &Track, (a, b, f): (usize, usize, f64)) -> f64 {
    let side = |k: usize| if track.exists(k) { side_of_noa(track.noa[k].max(0) as u32) } else { 0.0 };
    lerp(side(a), side(b), f)
}

fn lerp(x: f64, y: f64, f: f64) -> f64 {
    x + (y - x) * f
}

/// The colour metric used during replay: open pull requests only exist today, so that metric falls back to churn.
pub fn color_metric(metric: ColorMetric) -> ColorMetric {
    if metric == ColorMetric::Prs { ColorMetric::Commits } else { metric }
}

/// The raw colour-metric value of a building in snapshot `k` (taken at `snapshot`), like
/// [`ColorMetric::value`] for the present: churn, authors and recency come from the snapshot's git statistics, recency
/// as days before the snapshot's time (infinite when the file had no commit yet). Absent metrics count as 0.
pub fn color_value(metric: ColorMetric, track: &Track, k: usize, snapshot: &Snapshot) -> f64 {
    let v = |values: &Vec<i32>| values[k].max(0) as f64;
    match color_metric(metric) {
        ColorMetric::Commits | ColorMetric::Prs => v(&track.commits),
        ColorMetric::Authors => v(&track.authors),
        ColorMetric::Age if track.last[k] < 0 => f64::INFINITY,
        ColorMetric::Age => ((snapshot.time - track.last[k]) as f64 / 86400.0).max(0.0),
        ColorMetric::Cx => v(&track.cx),
        ColorMetric::Density => if track.nom[k] <= 0 { 0.0 } else { v(&track.cx) / v(&track.nom) },
        ColorMetric::Loc => v(&track.loc),
        ColorMetric::Kind => 0.0,
    }
}

/// The timeline's commit text: `subject` cut to 60 characters (with an ellipsis), then ` — author`.
pub fn caption(s: &Snapshot) -> String {
    let subject: String = if s.subject.chars().count() > 60 { format!("{}…", s.subject.chars().take(59).collect::<String>()) } else { s.subject.clone() };
    format!("{} · {} · {subject} — {}", local_date(s.time), &s.commit[..s.commit.len().min(7)], s.author)
}

/// The local calendar date `YYYY-MM-DD` of a Unix time, in the viewer's time zone (UTC where it cannot be found).
pub fn local_date(secs: i64) -> String {
    #[cfg(all(unix, target_pointer_width = "64"))]
    {
        // The C library's `localtime_r`, which applies the TZ setting and the zone database; `struct tm` starts with
        // nine ints (sec, min, hour, mday, mon, year, wday, yday, isdst), followed by fields only some platforms
        // have, so the struct is padded generously. time_t is 64 bits on every 64-bit Unix.
        #[repr(C)]
        struct Tm {
            fields: [std::ffi::c_int; 9],
            rest: [u64; 8],
        }
        extern "C" {
            fn localtime_r(t: *const i64, tm: *mut Tm) -> *mut Tm;
        }
        let mut tm = Tm { fields: [0; 9], rest: [0; 8] };
        // SAFETY: both pointers are valid for the call, and `tm` is large enough for the platform's struct tm.
        if !unsafe { localtime_r(&secs, &mut tm) }.is_null() {
            let f = tm.fields;
            return format!("{:04}-{:02}-{:02}", f[5] + 1900, f[4] + 1, f[3]);
        }
    }
    crate::city::iso_date(secs)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn playback_steps_and_stops_at_the_end() {
        let mut r = Replay::new(5, 1.0);
        r.advance(0.4);
        assert_eq!(r.p, 0.5);
        assert_eq!(r.snapshot(), 1);
        assert_eq!(r.frame(), (0, 1, 0.5));
        r.advance(10.0);
        assert_eq!((r.p, r.playing), (4.0, false));
        assert_eq!(r.frame(), (4, 4, 0.0));
        r.toggle();
        assert_eq!((r.p, r.playing), (0.0, true));
        r.p = 2.3;
        r.step(-1);
        assert_eq!((r.p, r.playing), (2.0, false));
        r.step(-1);
        assert_eq!(r.p, 1.0);
        r.p = 2.3;
        r.step(1);
        assert_eq!(r.p, 3.0);
        for label in ["2×", "4×", "0.5×", "1×"] {
            r.next_speed();
            assert_eq!(r.speed_label(), label);
        }
    }

    #[test]
    fn interpolates_absent_as_zero_and_hides_when_absent_on_both_sides() {
        let mut t = Track::absent(3);
        t.nom = vec![-1, 4, 2];
        t.noa = vec![-1, 4, 0];
        assert_eq!(metric_at(&t, HeightMetric::Nom, (0, 1, 0.25)), Some(1.0));
        assert_eq!(metric_at(&t, HeightMetric::Nom, (1, 2, 0.5)), Some(3.0));
        assert_eq!(metric_at(&t, HeightMetric::Nom, (0, 0, 0.0)), None);
        assert_eq!(side_at(&t, (0, 1, 0.5)), side_of_noa(4) / 2.0);
    }
}
