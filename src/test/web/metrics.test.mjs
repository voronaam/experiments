// Metrics and scales of the browser viewer (metrics.js): metric definitions, the height scale, the colour ramp and
// the 98th-percentile colour scale. The definitions and rules are those of the desktop viewers (desktop/Look.java,
// rust/src/gui/look.rs, d/source/codecity/gui/look.d). Run with `node --test src/test/web` (scripts/test-web.sh).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as M from '../../main/resources/web/metrics.js';

test('the ramp and kind colours match the desktop viewers', () => {
  assert.deepEqual(M.RAMP, ['#2b6cb0', '#2a9fc0', '#34c3a5', '#8fd67a', '#e8e15b', '#f6b343', '#ef7d34', '#dc3f2e', '#a3123a']);
  assert.deepEqual(Object.keys(M.KINDS), ['class', 'abstract', 'interface', 'enum', 'record', 'annotation', 'struct', 'trait', 'union', 'type', 'module']);
  assert.equal(M.kindColor('enum'), '#e3a63b');
  assert.equal(M.kindColor('mystery'), '#6b8fc7');
  assert.equal(M.kindColor('constructor'), '#6b8fc7'); // nothing inherited from Object
  assert.equal(M.kindColor(undefined), '#6b8fc7');
});

test('metric labels and legend words match the desktop viewers', () => {
  assert.deepEqual(Object.entries(M.HEIGHT_METRICS).map(([k, m]) => [k, m.label]),
    [['nom', 'Methods (NOM)'], ['loc', 'Lines of code'], ['cx', 'Complexity (WMC)'], ['noa', 'Attributes (NOA)']]);
  assert.deepEqual(Object.entries(M.COLOR_METRICS).map(([k, m]) => [k, m.label, m.lo, m.hi]), [
    ['commits', 'Churn (commits)', 'stable', 'hot spot'],
    ['age', 'Recency', 'dormant', 'just changed'],
    ['authors', 'Authors', 'one owner', 'many hands'],
    ['prs', 'Open pull requests', 'none', 'contested'],
    ['cx', 'Complexity (WMC)', 'simple', 'complex'],
    ['density', 'Complexity / method', 'flat', 'branchy'],
    ['loc', 'Lines of code', 'small', 'large'],
    ['kind', 'Type kind', undefined, undefined],
  ]);
  // Only recency is inverted, only density linear, only kind categorical.
  const flag = (f) => Object.keys(M.COLOR_METRICS).filter((k) => M.COLOR_METRICS[k][f]);
  assert.deepEqual(flag('invert'), ['age']);
  assert.deepEqual(flag('linear'), ['density']);
  assert.deepEqual(flag('categorical'), ['kind']);
  assert.deepEqual(flag('git'), ['commits', 'age', 'authors']);
  assert.deepEqual(flag('github'), ['prs']);
});

test('colour metric values; missing git data counts as 0, missing recency as infinitely old', () => {
  const C = M.COLOR_METRICS;
  const b = { nom: 4, cx: 10, loc: 99, commits: 7, authors: 2, last: 1000, prs: [1, 2] };
  assert.equal(C.commits.get(b), 7);
  assert.equal(C.authors.get(b), 2);
  assert.equal(C.prs.get(b), 2);
  assert.equal(C.cx.get(b), 10);
  assert.equal(C.density.get(b), 2.5);
  assert.equal(C.loc.get(b), 99);
  assert.equal(C.age.get(b, 1000 + 2 * 86400), 2);
  assert.equal(C.age.get(b, 0), 0); // a commit in the future is 0 days old
  const bare = { nom: 0, cx: 0, loc: 0 };
  assert.equal(C.commits.get(bare), 0);
  assert.equal(C.authors.get(bare), 0);
  assert.equal(C.prs.get(bare), 0);
  assert.equal(C.density.get(bare), 0);
  assert.equal(C.age.get(bare, 5), Infinity);
  // Without a time, recency is measured from now.
  const age = C.age.get({ last: Date.now() / 1000 - 86400 });
  assert.ok(age > 0.99 && age < 1.01, String(age));
});

test('quantile: nearest rank rounded down, ignoring non-finite values', () => {
  assert.equal(M.quantile([], 0.5), 0);
  assert.equal(M.quantile([NaN, Infinity], 0.5), 0);
  assert.equal(M.quantile([5], 0.98), 5);
  assert.equal(M.quantile([3, 1, 2], 0.5), 2);
  const hundred = Array.from({ length: 100 }, (_, k) => 100 - k); // 100 .. 1
  assert.equal(M.quantile(hundred, 0.95), 95); // rank ⌊0.95 × 99⌋ = 94 → 95
  assert.equal(M.quantile(hundred, 0.98), 98);
  assert.equal(M.quantile(hundred, 1), 100);
  assert.equal(M.quantile(hundred, 0), 1);
  assert.equal(M.quantile([1, NaN, 3, Infinity, 2], 1), 3);
  // Numeric, not string, order.
  assert.equal(M.quantile([10, 9, 100], 0.5), 10);
});

test('presentVisible: gone types never, test code only with the filter on', () => {
  assert.equal(M.presentVisible({}, false), true);
  assert.equal(M.presentVisible({ test: true }, true), true);
  assert.equal(M.presentVisible({ test: true }, false), false);
  assert.equal(M.presentVisible({ gone: true }, true), false);
});

test('heightScale maps the 95th percentile to 8–16 units', () => {
  const buildings = Array.from({ length: 100 }, (_, k) => ({ nom: k + 1, loc: 10 * (k + 1), cx: 0, noa: 0 }));
  // p95 of nom is 95; radius 50 → 10 units.
  assert.equal(M.heightScale(buildings, 'nom', true, 50), 10 / 95);
  assert.equal(M.heightScale(buildings, 'loc', true, 50), 10 / 950);
  // Small and large cities clamp at 8 and 16 units.
  assert.equal(M.heightScale(buildings, 'nom', true, 20), 8 / 95);
  assert.equal(M.heightScale(buildings, 'nom', true, 1000), 16 / 95);
  // A metric that is 0 everywhere uses p95 = 1.
  assert.equal(M.heightScale(buildings, 'cx', true, 50), 10);
  // Hidden test code and gone types do not count.
  const mixed = [{ nom: 1 }, { nom: 1000, test: true }, { nom: 500, gone: true }];
  assert.equal(M.heightScale(mixed, 'nom', false, 50), 10);
  // With them: [1, 1000], whose p95 (rank ⌊0.95 × 1⌋ = 0) is still 1; with a third value it becomes 1000.
  assert.equal(M.heightScale(mixed, 'nom', true, 50), 10);
  assert.equal(M.heightScale([...mixed, { nom: 2000, test: true }], 'nom', true, 50), 10 / 1000);
  assert.equal(M.heightScale([], 'nom', true, 50), 10);
});

test('heightFor: a 0.35 base, capped at 0.75 × the radius', () => {
  assert.equal(M.heightFor(0, 1, 50), 0.35);
  assert.equal(M.heightFor(10, 0.5, 50), 5.35);
  assert.equal(M.heightFor(1000, 1, 50), 37.5);
});

test('rampStop picks the segment and fraction, clamping t', () => {
  assert.deepEqual(M.rampStop(0), { from: '#2b6cb0', to: '#2a9fc0', f: 0 });
  assert.deepEqual(M.rampStop(-3), { from: '#2b6cb0', to: '#2a9fc0', f: 0 });
  assert.deepEqual(M.rampStop(0.5), { from: '#e8e15b', to: '#f6b343', f: 0 });
  assert.deepEqual(M.rampStop(1), { from: '#dc3f2e', to: '#a3123a', f: 1 });
  assert.deepEqual(M.rampStop(7), { from: '#dc3f2e', to: '#a3123a', f: 1 });
  const s = M.rampStop(0.3); // x = 2.4
  assert.deepEqual([s.from, s.to], ['#34c3a5', '#8fd67a']);
  assert.ok(Math.abs(s.f - 0.4) < 1e-12);
});

test('colorScale: 98th percentile, floors, lo at most hi', () => {
  const C = M.COLOR_METRICS;
  const hundred = Array.from({ length: 100 }, (_, k) => k + 1);
  assert.deepEqual(M.colorScale(C.commits, hundred), { lo: 1, hi: 98 });
  assert.deepEqual(M.colorScale(C.commits, [0, 0, 0]), { lo: 0, hi: 1 }); // log metrics span at least 0..1
  assert.deepEqual(M.colorScale(C.density, [0, 0]), { lo: 0, hi: 1e-6 });
  assert.deepEqual(M.colorScale(C.density, [0.5, 2]), { lo: 0.5, hi: 0.5 });
  assert.deepEqual(M.colorScale(C.commits, []), { lo: 1, hi: 1 });
  // Gone types (NaN) and recency without commits (Infinity) are left out.
  assert.deepEqual(M.colorScale(C.age, [NaN, Infinity, 3, 9]), { lo: 3, hi: 3 });
  assert.deepEqual(M.colorScale(C.commits, [NaN, 5, 0.5]), { lo: 0.5, hi: 1 });
});

test('normalise: log scale, linear for density, inverted recency, infinite at the cold end', () => {
  const C = M.COLOR_METRICS;
  assert.equal(M.normalise(C.commits, 98, 0), 0);
  assert.equal(M.normalise(C.commits, 98, 98), 1);
  assert.ok(Math.abs(M.normalise(C.commits, 99, 9) - Math.log(10) / Math.log(100)) < 1e-12); // 0.5
  assert.ok(M.normalise(C.commits, 98, 500) > 1); // clamped by rampStop
  assert.equal(M.normalise(C.density, 4, 1), 0.25);
  assert.equal(M.normalise(C.age, Math.E - 1, 0), 1); // just changed: hot
  assert.equal(M.normalise(C.age, Math.E - 1, Math.E - 1), 0);
  assert.equal(M.normalise(C.age, 10, Infinity), 0); // never changed: cold
  assert.equal(M.normalise(C.commits, 10, NaN), 1);
});

test('during replay open PRs fall back to churn', () => {
  assert.equal(M.effectiveColorKey('prs', true), 'commits');
  assert.equal(M.effectiveColorKey('prs', false), 'prs');
  assert.equal(M.effectiveColorKey('cx', true), 'cx');
});

test('metric availability and the choice of metrics', () => {
  const C = M.COLOR_METRICS;
  const plain = { hasGit: false };
  const git = { hasGit: true, github: { pulls: [] } };
  assert.equal(M.metricUnavailable(C.commits, plain), ' (no git)');
  assert.equal(M.metricUnavailable(C.prs, plain), ' (no GitHub)');
  assert.equal(M.metricUnavailable(C.prs, { github: {} }), ' (no GitHub)');
  assert.equal(M.metricUnavailable(C.prs, git), ''); // fetched, even if none are open
  assert.equal(M.metricUnavailable(C.cx, plain), '');
  assert.equal(M.chooseColorKey('loc', plain), 'loc');
  assert.equal(M.chooseColorKey('age', plain), 'cx');
  assert.equal(M.chooseColorKey('age', git), 'age');
  assert.equal(M.chooseColorKey(null, git), 'commits');
  assert.equal(M.chooseColorKey('nonsense', plain), 'cx');
  for (const bad of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) {
    assert.equal(M.chooseColorKey(bad, git), 'commits', bad);
    assert.equal(M.chooseHeightKey(bad), 'nom', bad);
  }
  assert.equal(M.chooseHeightKey('loc'), 'loc');
  assert.equal(M.chooseHeightKey(undefined), 'nom');
});

test('presentKinds lists the kinds that occur, in legend order', () => {
  assert.deepEqual(M.presentKinds([{ kind: 'enum' }, { kind: 'class' }, { kind: 'enum' }, { kind: 'odd' }]),
    [['class', '#6b8fc7'], ['enum', '#e3a63b']]);
  assert.deepEqual(M.presentKinds([]), []);
});
