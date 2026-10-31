// Visibility, districts, labels and dependency arcs of the browser viewer (city.js). Run with
// `node --test src/test/web` (scripts/test-web.sh).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as City from '../../main/resources/web/city.js';
import * as Replay from '../../main/resources/web/replay.js';

test('isVisible: test filter, gone types, replayed snapshot', () => {
  const live = { hist: { nom: [-1, 2] } };
  const gone = { gone: true, hist: { nom: [3, -1] } };
  const testCode = { test: true, hist: { nom: [1, 1] } };
  assert.equal(City.isVisible(live, true, null), true);
  assert.equal(City.isVisible(gone, true, null), false);
  assert.equal(City.isVisible(testCode, false, null), false);
  assert.equal(City.isVisible(testCode, true, null), true);
  // During replay existence in snapshot r decides, whether gone today or not.
  assert.equal(City.isVisible(live, true, 0), false);
  assert.equal(City.isVisible(live, true, 1), true);
  assert.equal(City.isVisible(gone, true, 0), true);
  assert.equal(City.isVisible(testCode, false, 0), false);
  // Snapshot 0 is a snapshot, not "no replay".
  for (const b of [live, gone]) for (const r of [0, 1]) assert.equal(City.isVisible(b, true, r), Replay.exists(b, r));
});

test('districtShown: present types, or the snapshot hist', () => {
  assert.equal(City.districtShown({ types: 2 }, null), true);
  assert.equal(City.districtShown({ types: 0 }, null), false);
  assert.equal(City.districtShown({ types: 0, hist: [1, 0] }, 0), true);
  assert.equal(City.districtShown({ types: 5, hist: [1, 0] }, 1), false);
  assert.equal(City.districtShown({ types: 0 }, 1), true); // no hist: shown
});

const graph = () => [
  { deps: [1, 2] },
  { deps: [2] },
  { deps: [] },
  { deps: [0] },
];

test('incomingDeps reverses deps in city order', () => {
  assert.deepEqual(City.incomingDeps(graph()), [[3], [0], [0, 1], []]);
  assert.deepEqual(City.incomingDeps([]), []);
});

test('related: the selection and its neighbours', () => {
  const bs = graph();
  const inc = City.incomingDeps(bs);
  assert.deepEqual([...City.related(0, bs, inc)].sort(), [0, 1, 2, 3]);
  assert.deepEqual([...City.related(2, bs, inc)].sort(), [0, 1, 2]);
  assert.equal(City.related(-1, bs, inc).size, 0);
});

test('arcEnds: only visible ends, none from a hidden selection', () => {
  const bs = graph();
  bs[2].hidden = true;
  const inc = City.incomingDeps(bs);
  const visible = (b) => !b.hidden;
  assert.deepEqual(City.arcEnds(0, bs, inc, visible), { out: [1], inc: [3], faint: false });
  assert.deepEqual(City.arcEnds(2, bs, inc, visible), { out: [], inc: [], faint: false });
});

test('arcEnds caps each direction at 200 after filtering and fades beyond 40 edges', () => {
  const n = 500;
  const bs = Array.from({ length: n + 1 }, (_, k) => ({ deps: [], hidden: k % 2 === 1 }));
  bs[0].deps = Array.from({ length: n }, (_, k) => k + 1);
  for (let k = 1; k <= 30; k++) bs[k].deps = [0];
  const inc = City.incomingDeps(bs);
  const arcs = City.arcEnds(0, bs, inc, (b) => !b.hidden);
  assert.equal(arcs.out.length, City.ARC_CAP);
  assert.deepEqual(arcs.out.slice(0, 3), [2, 4, 6]); // filtered first, then capped
  assert.equal(arcs.inc.length, 15);
  assert.equal(arcs.faint, true);
  // Exactly 40 edges is not yet faint; hidden edges count.
  const few = [{ deps: Array.from({ length: 40 }, (_, k) => k + 1) }, ...Array.from({ length: 40 }, () => ({ deps: [], hidden: true }))];
  assert.equal(City.arcEnds(0, few, City.incomingDeps(few), (b) => !b.hidden).faint, false);
  few[1].deps = [0];
  assert.equal(City.arcEnds(0, few, City.incomingDeps(few), (b) => !b.hidden).faint, true);
});

test('arc shape and speed', () => {
  const shape = City.arcShape(3, 5, 10);
  assert.equal(shape.peak, 10.5);
  assert.ok(Math.abs(shape.radius - 0.068) < 1e-12);
  assert.ok(Math.abs(shape.dot - 0.2) < 1e-12);
  assert.equal(City.arcSpeed(2), 14 / 5);
  assert.equal(City.arcSpeed(28), 0.5);
});

test('labelledDistricts: depth 1–2, largest first, one per shared front edge, at most 16', () => {
  const d = (x, z, w, dd, depth, types = 1) => ({ x, z, w, d: dd, depth, types });
  const districts = [
    d(0, 0, 100, 100, 0), // root: never
    d(0, 0, 10, 10, 1), // 1
    d(0, 1, 10, 8, 2), // 2: front edge at z 5, x 0 — shares 1's (z 5)
    d(50, 0, 20, 20, 1), // 3: largest
    d(-40, 0, 5, 5, 2, 0), // 4: no present types
    d(-50, 0, 5, 5, 3), // 5: too deep
  ];
  const shown = (dist) => dist.types > 0;
  assert.deepEqual(City.labelledDistricts(districts, shown), [3, 1]);
  // Sixteen at most, from the 24 largest.
  const many = Array.from({ length: 30 }, (_, k) => d(k * 20, 0, 10, 30 - k, 1));
  assert.deepEqual(City.labelledDistricts(many, shown), Array.from({ length: 16 }, (_, k) => k));
});

test('cityRadius: half the diagonal, at least 20', () => {
  assert.equal(City.cityRadius({ width: 60, depth: 80 }), 50);
  assert.equal(City.cityRadius({ width: 1, depth: 1 }), 20);
});
