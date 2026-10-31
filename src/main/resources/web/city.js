// City rules — which buildings, districts and dependency arcs are shown, and where labels go, independent of three.js
// and the DOM.
//
// A building is shown when the test filter allows it and it exists: in the present-day city that is every type that
// is not gone, during replay every type of the replayed snapshot r (its hist.nom[r] ≥ 0, as Replay.exists). Search,
// arcs and the inspector's dependency lists follow this rule. The desktop viewers implement the same rules
// (desktop/CityView3D.java, rust/src/gui, d/source/codecity/gui); keep them in sync. app.js draws the results.
//
// `r` below is the replayed snapshot, or null outside replay.
//
// No imports: the exported page loads this module from a data: URL, which cannot resolve relative imports of its own.

/** At most this many arcs are drawn per direction (after dropping the hidden ends). */
export const ARC_CAP = 200;
/** With more dependency edges than this in total (shown or not), arcs are drawn fainter. */
export const FAINT_ARCS = 40;
/** At most this many district labels are placed, chosen from the LABEL_CANDIDATES largest districts. */
export const MAX_LABELS = 16;
export const LABEL_CANDIDATES = 24;

/** Whether building b is shown: see the module comment. */
export function isVisible(b, showTests, r) {
  if (!showTests && b.test) return false;
  return r != null ? b.hist.nom[r] >= 0 : !b.gone;
}

/**
 * Whether district d is drawn: in the present-day city when it holds present types (types > 0), during replay unless
 * its hist marks it empty in snapshot r (a district without hist is shown).
 */
export function districtShown(d, r) {
  return r != null ? d.hist?.[r] !== 0 : d.types > 0;
}

/** For every building, the indices of the buildings that depend on it (the reverse of deps), in city order. */
export function incomingDeps(buildings) {
  const incoming = buildings.map(() => []);
  buildings.forEach((b, i) => b.deps.forEach((j) => incoming[j].push(i)));
  return incoming;
}

/** The selection and its direct neighbours (what it uses and what uses it); empty without a selection (sel < 0). */
export function related(sel, buildings, incoming) {
  const set = new Set();
  if (sel >= 0) {
    set.add(sel);
    buildings[sel].deps.forEach((j) => set.add(j));
    incoming[sel].forEach((j) => set.add(j));
  }
  return set;
}

/**
 * The dependency arcs of selected building i: `out` (what it uses) and `inc` (what uses it), each only to visible
 * buildings and capped at ARC_CAP (filtered first, then capped); none when i itself is hidden. `faint` when i has
 * more than FAINT_ARCS edges in total, visible or not.
 */
export function arcEnds(i, buildings, incoming, visible) {
  const shown = (list) => (visible(buildings[i]) ? list.filter((j) => visible(buildings[j])).slice(0, ARC_CAP) : []);
  return {
    out: shown(buildings[i].deps),
    inc: shown(incoming[i]),
    faint: buildings[i].deps.length + incoming[i].length > FAINT_ARCS,
  };
}

/**
 * The shape of an arc between two roofs at heights y0 and y1, `dist` apart: the Bézier control point's height (above
 * the taller roof by 35% of the distance plus 2, so long arcs arch higher), the tube radius and the travelling dot's
 * size.
 */
export function arcShape(y0, y1, dist) {
  return { peak: Math.max(y0, y1) + dist * 0.35 + 2, radius: 0.06 + dist * 0.0008, dot: 0.18 + dist * 0.002 };
}

/** The travelling dot's speed in curve fractions per second, roughly constant in world units (curves under 5 count as 5). */
export function arcSpeed(length) {
  return 14 / Math.max(5, length);
}

/**
 * Which districts get a label: of those at depth 1–2 for which `shown(d, i)` holds, the LABEL_CANDIDATES largest by
 * area, then in that order each one whose front edge is not within 8 (x) and 3 (z) of an already labelled one (a
 * district whose only content is one sub-district shares its front edge), at most MAX_LABELS. Returns indices.
 */
export function labelledDistricts(districts, shown) {
  const candidates = districts
    .map((d, i) => ({ d, i }))
    .filter(({ d, i }) => d.depth >= 1 && d.depth <= 2 && shown(d, i))
    .sort((a, b) => b.d.w * b.d.d - a.d.w * a.d.d)
    .slice(0, LABEL_CANDIDATES);
  const anchors = [];
  const chosen = [];
  for (const { d, i } of candidates) {
    if (anchors.some((a) => Math.abs(a.x - d.x) < 8 && Math.abs(a.z - (d.z + d.d / 2)) < 3) || anchors.length >= MAX_LABELS) continue;
    anchors.push({ x: d.x, z: d.z + d.d / 2 });
    chosen.push(i);
  }
  return chosen;
}

/** The radius the camera, lights and fog are scaled to: half the city's diagonal, at least 20. */
export function cityRadius(city) {
  return Math.max(20, Math.hypot(city.width, city.depth) / 2);
}
