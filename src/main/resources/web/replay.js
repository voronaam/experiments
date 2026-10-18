// History replay — the timeline arithmetic behind the Replay button, independent of three.js and the DOM.
//
// A city exported with --history carries `history.snapshots` (S sampled commits, oldest first, the last one HEAD) and,
// on every building, `hist` arrays with one value per snapshot: nom/noa/loc/cx (−1 where the type does not exist in
// that snapshot) and commits/authors/last (git stats up to that snapshot); every district has `hist`, 1 where it
// contains an existing building. app.js owns the scene, input and HUD; this module only maps a continuous playback
// position p ∈ [0, S−1] onto displayed values, so it can run (and be tested) anywhere. The desktop viewers implement
// the same rules and constants (desktop/Replay.java, rust/src/gui/replay.rs, d/source/codecity/gui/replay.d); keep
// all four in sync.
//
// Interpolation at p: a = floor(p), b = min(a + 1, S − 1), f = p − a. Heights and footprints blend linearly between
// snapshots a and b (an absent value counts as 0; a building absent in both is hidden). Everything discrete — colour,
// district visibility, which buildings count for search and arcs, the snapshot shown in the timeline — uses snapshot
// r = round(p), rounding halves up.

export const SECONDS_PER_SNAPSHOT = 0.8; // at 1× speed
export const SPEEDS = [1, 2, 4, 0.5]; // the speed button cycles through these, starting at 1×
export const SUBJECT_MAX = 60; // longer commit subjects are cut to this many characters, ending in "…"
export const MAX_FRAME = 0.25; // frame time beyond this is dropped (e.g. after a stall or a background tab)

/** True when the city JSON carries a replayable history. */
export function hasHistory(city) {
  return !!city?.history?.snapshots?.length;
}

/**
 * The new position after a frame of `dt` seconds at `speed`; it stops at the last snapshot (S − 1). The frame time is
 * capped at MAX_FRAME, so a slow renderer still plays at nearly full speed but a stall does not skip ahead.
 */
export function advance(p, dt, speed, count) {
  return Math.min(p + (Math.min(Math.max(dt, 0), MAX_FRAME) * speed) / SECONDS_PER_SNAPSHOT, count - 1);
}

/** Clamps a position into [0, S − 1]. */
export function clampPosition(p, count) {
  return Math.min(Math.max(p, 0), Math.max(0, count - 1));
}

/** Interpolation frame at p: the two snapshots a and b to blend, the fraction f, and r = round(p) (half up). */
export function frame(p, count) {
  const a = Math.floor(p);
  return { a, b: Math.min(a + 1, count - 1), f: p - a, r: Math.min(Math.floor(p + 0.5), count - 1) };
}

/** The whole snapshot before (dir −1) or after (dir +1) p; from between two snapshots that is the nearer one in that direction. */
export function stepPosition(p, dir, count) {
  const target = dir < 0 ? Math.ceil(p - 1e-9) - 1 : Math.floor(p + 1e-9) + 1;
  return clampPosition(target, count);
}

/** Linear interpolation of a per-snapshot metric; −1 (absent) counts as 0. Null when absent in both snapshots. */
export function lerpMetric(values, fr) {
  const va = values[fr.a], vb = values[fr.b];
  if (va < 0 && vb < 0) return null;
  return Math.max(va, 0) + (Math.max(vb, 0) - Math.max(va, 0)) * fr.f;
}

/** Footprint side for a number of attributes — the layout's formula (CityLayout.side). */
export function side(noa) {
  return 1.2 + 0.8 * Math.sqrt(noa);
}

/** Interpolated footprint side: lerp between side(noa_a) and side(noa_b), an absent snapshot counting as side 0. */
export function lerpSide(noa, fr) {
  const sa = noa[fr.a] < 0 ? 0 : side(noa[fr.a]);
  const sb = noa[fr.b] < 0 ? 0 : side(noa[fr.b]);
  return sa + (sb - sa) * fr.f;
}

/** True when the building exists in snapshot k. */
export function exists(building, k) {
  return building.hist.nom[k] >= 0;
}

/**
 * The building's metrics in snapshot k, shaped like a building of the city JSON (nom, noa, loc, cx, commits, authors,
 * last), so the viewer's metric getters work on it unchanged. `last` is undefined when there is no commit yet.
 */
export function valuesAt(building, k) {
  const h = building.hist;
  return {
    nom: Math.max(0, h.nom[k]), noa: Math.max(0, h.noa[k]), loc: Math.max(0, h.loc[k]), cx: Math.max(0, h.cx[k]),
    commits: h.commits[k], authors: h.authors[k], last: h.last[k] >= 0 ? h.last[k] : undefined,
    prs: building.prs, kind: building.kind,
  };
}

/** Snapshot date in the viewer's local time zone as YYYY-MM-DD. */
export function formatDate(epochSeconds) {
  const d = new Date(epochSeconds * 1000);
  const two = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

/** Short commit id: the first 7 characters of the sha. */
export function shortSha(sha) {
  return String(sha).slice(0, 7);
}

/** The subject cut to SUBJECT_MAX characters (code points, so emoji are not split), "…" marking a cut. */
export function truncateSubject(subject) {
  const chars = [...String(subject)];
  return chars.length > SUBJECT_MAX ? chars.slice(0, SUBJECT_MAX - 1).join('') + '…' : chars.join('');
}
