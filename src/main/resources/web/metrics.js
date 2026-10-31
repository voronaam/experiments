// Metrics and scales — what a building's height and colour show, and how values become heights and ramp positions,
// independent of three.js and the DOM.
//
// The definitions (labels, legend words, log scale clipped at the 98th percentile, recency inverted, density linear,
// the ramp and the kind colours) are shared with the desktop viewers (desktop/Look.java, rust/src/gui/look.rs,
// d/source/codecity/gui/look.d); keep them in sync. app.js turns ramp positions into three.js colours and heights into
// instance matrices; this module only does the arithmetic, so it can run (and be tested) anywhere.
//
// No imports: the exported page loads this module from a data: URL, which cannot resolve relative imports of its own.

/** The metric colour ramp, low to high: blue → teal → green → yellow → orange → red → crimson. */
export const RAMP = ['#2b6cb0', '#2a9fc0', '#34c3a5', '#8fd67a', '#e8e15b', '#f6b343', '#ef7d34', '#dc3f2e', '#a3123a'];

/** Building colour per type kind (the "Type kind" colour metric and the kind badges); unknown kinds use class's. */
export const KINDS = {
  class: '#6b8fc7', abstract: '#9a86d8', interface: '#2fb7a5', enum: '#e3a63b', record: '#d46fa8',
  annotation: '#8a9aaa', struct: '#4f86c6', trait: '#26a69a', union: '#c98a3a', type: '#b7a3e0', module: '#7c8ba1',
};

const DAY = 86400;
const nowSec = () => Date.now() / 1000;

/** What a building's height can show: label and value getter. */
export const HEIGHT_METRICS = {
  nom: { label: 'Methods (NOM)', get: (b) => b.nom },
  loc: { label: 'Lines of code', get: (b) => b.loc },
  cx: { label: 'Complexity (WMC)', get: (b) => b.cx },
  noa: { label: 'Attributes (NOA)', get: (b) => b.noa },
};

/**
 * What a building's colour can show: label, value getter, legend words (lo, hi) and flags — git / github (needs that
 * data), invert (low values are hot), linear (no log scale), categorical (kind colours instead of the ramp), unit.
 */
export const COLOR_METRICS = {
  commits: { label: 'Churn (commits)', git: true, get: (b) => b.commits ?? 0, lo: 'stable', hi: 'hot spot' },
  // `now` is the moment recency is measured from: the present, or the snapshot's commit time during replay.
  age: { label: 'Recency', git: true, get: (b, now = nowSec()) => (b.last ? Math.max(0, (now - b.last) / DAY) : Infinity), invert: true, lo: 'dormant', hi: 'just changed', unit: 'd' },
  authors: { label: 'Authors', git: true, get: (b) => b.authors ?? 0, lo: 'one owner', hi: 'many hands' },
  prs: { label: 'Open pull requests', github: true, get: (b) => b.prs?.length ?? 0, lo: 'none', hi: 'contested' },
  cx: { label: 'Complexity (WMC)', get: (b) => b.cx, lo: 'simple', hi: 'complex' },
  density: { label: 'Complexity / method', get: (b) => (b.nom ? b.cx / b.nom : 0), linear: true, lo: 'flat', hi: 'branchy' },
  loc: { label: 'Lines of code', get: (b) => b.loc, lo: 'small', hi: 'large' },
  kind: { label: 'Type kind', categorical: true },
};

/** The colour of a type kind; an unknown kind (or a name like "constructor") gets the class colour. */
export function kindColor(kind) {
  return Object.hasOwn(KINDS, kind) ? KINDS[kind] : KINDS.class;
}

/** The [kind, colour] pairs of KINDS that occur among the buildings, in KINDS order (the kind legend). */
export function presentKinds(buildings) {
  const present = new Set(buildings.map((b) => b.kind));
  return Object.entries(KINDS).filter(([k]) => present.has(k));
}

/**
 * The value at quantile q of the finite values, nearest rank with the rank rounded down: sorted v[⌊q · (n − 1)⌋].
 * 0 when there is no finite value.
 */
export function quantile(values, q) {
  const v = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (!v.length) return 0;
  return v[Math.min(v.length - 1, Math.floor(q * (v.length - 1)))];
}

/** Whether a building counts in the present-day city: not gone, and not test code hidden by the test filter. */
export function presentVisible(b, showTests) {
  return (showTests || !b.test) && !b.gone;
}

/**
 * Height per unit of the height metric: the 95th percentile (at least 1) of the present-day city's visible buildings
 * maps to 8–16 units, 0.2 × the city's radius within that range. Replay keeps this present-day scale.
 */
export function heightScale(buildings, heightKey, showTests, cityRadius) {
  const metric = HEIGHT_METRICS[heightKey];
  const values = buildings.filter((b) => presentVisible(b, showTests)).map(metric.get);
  const p95 = Math.max(1, quantile(values, 0.95));
  return Math.min(16, Math.max(8, cityRadius * 0.2)) / p95;
}

/** A building's drawn height for a metric value: 0.35 plus value × scale, at most 0.75 × the city's radius. */
export function heightFor(value, scale, cityRadius) {
  return Math.min(0.35 + value * scale, cityRadius * 0.75);
}

/**
 * Where t ∈ [0, 1] (clamped) falls on RAMP: the colours of the two neighbouring stops and the fraction between them.
 * The 9 stops are spread evenly; t = 1 is the end of the last segment.
 */
export function rampStop(t) {
  const x = Math.min(1, Math.max(0, t)) * (RAMP.length - 1);
  const i = Math.min(RAMP.length - 2, Math.floor(x));
  return { from: RAMP[i], to: RAMP[i + 1], f: x - i };
}

/**
 * The colour scale of a ramp metric over the present-day values (NaN for gone types): hi is the 98th percentile of
 * the finite values, at least 1 (1e-6 for a linear metric), and lo the smallest finite value, at most hi. Colours
 * always start at 0; lo is only shown in the legend.
 */
export function colorScale(metric, present) {
  const finite = present.filter(Number.isFinite);
  const hi = Math.max(metric.linear ? 1e-6 : 1, quantile(finite, 0.98));
  const lo = finite.reduce((m, v) => Math.min(m, v), hi);
  return { lo, hi };
}

/**
 * The ramp position of value v on the scale up to hi: ln(1 + v) / ln(1 + hi), or v / hi for a linear metric, turned
 * round for an inverted one. A non-finite value (the recency of a type without commits) goes to the cold end. Values
 * above hi give positions above 1, which rampStop clamps.
 */
export function normalise(metric, hi, v) {
  if (!Number.isFinite(v)) return metric.invert ? 0 : 1;
  const t = metric.linear ? v / hi : Math.log1p(v) / Math.log1p(hi);
  return metric.invert ? 1 - t : t;
}

/** The colour metric in use: during replay the open-PR metric falls back to churn (PRs belong to today). */
export function effectiveColorKey(key, replaying) {
  return replaying && key === 'prs' ? 'commits' : key;
}

/** Why a metric cannot be used for this city (' (no git)', ' (no GitHub)'), or '' when it can. */
export function metricUnavailable(m, city) {
  if (m.git && !city.hasGit) return ' (no git)';
  if (m.github && !city.github?.pulls) return ' (no GitHub)';
  return '';
}

/**
 * The colour metric to use: `key` when it names an available metric of COLOR_METRICS, otherwise churn for a city with
 * git statistics and complexity without.
 */
export function chooseColorKey(key, city) {
  if (Object.hasOwn(COLOR_METRICS, key) && !metricUnavailable(COLOR_METRICS[key], city)) return key;
  return city.hasGit ? 'commits' : 'cx';
}

/** The height metric to use: `key` when it names one of HEIGHT_METRICS, otherwise methods. */
export function chooseHeightKey(key) {
  return Object.hasOwn(HEIGHT_METRICS, key) ? key : 'nom';
}
