// Walk-mode physics of the browser viewer (walk.js). The same scenarios with the same numbers are tested in the
// desktop viewers (desktop/WalkerTest.java, rust/src/gui/walk.rs, d/source/codecity/gui/walk.d), so all four viewers
// walk identically. Worlds are a 20 × 20 city (so the walkable area is ±40) with hand-placed boxes; time mostly
// advances in 0.05 s frames. Run with `node --test src/test/web` (scripts/test-web.sh).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as Walk from '../../main/resources/web/walk.js';

const TOL = 1e-4;
/** Yaw that faces +x (forward = (−sin yaw, −cos yaw)). */
const EAST = -Math.PI / 2;
const NONE = {};
const FORWARD = { forward: true };

/** Asserts |actual − expected| ≤ tol. */
function near(actual, expected, tol = TOL, what = '') {
  assert.ok(Math.abs(actual - expected) <= tol, `${what} expected ${expected}, got ${actual}`);
}

/** A building box: x0..x1 × z0..z1, from y0 to y1 (the desktop tests' Box(building, −1, x0, z0, x1, z1, y0, y1)). */
function building(x0, z0, x1, z1, y0, y1) {
  return Walk.box(x0, x1, z0, z1, y0, y1);
}

/** A district platform box (the desktop tests' Box(−1, district, …)). */
const platform = building;

/** A 20 × 20 city world with the given platforms and buildings. */
function world({ platforms = [], buildings = [] } = {}) {
  const half = 10;
  return {
    boxes: platforms, buildingBoxes: buildings,
    minX: -half - Walk.MARGIN, maxX: half + Walk.MARGIN, minZ: -half - Walk.MARGIN, maxZ: half + Walk.MARGIN,
  };
}

function run(p, input, w, steps) {
  for (let i = 0; i < steps; i++) Walk.step(p, 0.05, input, w);
}

test('constants match the desktop viewers', () => {
  assert.equal(Walk.EYE_HEIGHT, 0.6);
  assert.equal(Walk.RADIUS, 0.2);
  assert.equal(Walk.WALK_SPEED, 3.0);
  assert.equal(Walk.RUN_SPEED, 7.5);
  assert.equal(Walk.STEP_HEIGHT, 0.45);
  assert.equal(Walk.GRAVITY, 18);
  assert.equal(Walk.JUMP_SPEED, 6.5);
  assert.equal(Walk.MOUSE_SENS, 0.0025);
  near(Walk.PITCH_LIMIT, (85 * Math.PI) / 180, 1e-12);
  assert.equal(Walk.MARGIN, 30);
  assert.equal(Walk.MAX_DT, 0.05);
  assert.equal(Walk.MAX_FRAME, 0.25);
});

test('slides along a wall', () => {
  // A long wall at x = 1..3; walking forward-right (north-east at yaw 0) for 1 s keeps the z motion.
  const w = world({ buildings: [building(1, -5, 3, 5, 0, 5)] });
  const p = Walk.spawn(w, 0, 0, 0);
  run(p, { forward: true, right: true }, w, 20);
  near(p.x, 0.8);
  near(p.z, -3 / Math.sqrt(2));
  near(p.feet, 0);
});

test('steps onto a platform', () => {
  const w = world({ platforms: [platform(1, -5, 5, 5, 0, 0.4)] });
  const p = Walk.spawn(w, 0, 0, EAST);
  run(p, FORWARD, w, 20);
  near(p.x, 3.0);
  near(p.feet, 0.4);
  assert.ok(p.onGround);
});

test('blocked by a tall building', () => {
  const w = world({ buildings: [building(1, -1, 2, 1, 0, 3)] });
  const p = Walk.spawn(w, 0, 0, EAST);
  run(p, FORWARD, w, 20);
  near(p.x, 0.8);
  near(p.z, 0);
  near(p.feet, 0);
});

test('falling off a roof lands on the ground', () => {
  const w = world({ buildings: [building(-1, -1, 1, 1, 0, 2)] });
  const p = Walk.makePlayer(0, 0, 2, EAST);
  run(p, FORWARD, w, 10); // 0.5 s: x = 1.5, already off the roof edge (1.2) and falling
  near(p.x, 1.5);
  assert.equal(p.onGround, false);
  assert.ok(p.feet < 2);
  run(p, FORWARD, w, 20);
  near(p.x, 4.5);
  near(p.feet, 0);
  near(p.vy, 0);
  assert.ok(p.onGround);
});

test('jumps only from the ground', () => {
  const w = world();
  const p = Walk.spawn(w, 0, 0, 0);
  const jump = { jump: true };
  Walk.step(p, 0.05, jump, w);
  near(p.vy, 5.6); // 6.5 - 18 * 0.05
  near(p.feet, 0.28);
  assert.equal(p.onGround, false);
  Walk.step(p, 0.05, jump, w); // still held in the air: no second jump
  near(p.vy, 4.7);
  near(p.feet, 0.515);
  run(p, NONE, w, 20);
  near(p.feet, 0);
  assert.ok(p.onGround);
});

test('spawn pushes out of a building', () => {
  const w = world({ buildings: [building(-1, -1, 1, 1, 0, 3)] });
  const p = Walk.spawn(w, 0.7, 0.1, 0);
  near(p.x, 1.2); // shortest way out is +x (0.5) rather than ±z (1.1, 1.3) or -x (1.9)
  near(p.z, 0.1);
  near(p.feet, 0);
  assert.ok(p.onGround);

  // A building no taller than a step is stood on instead.
  const low = world({ buildings: [building(-1, -1, 1, 1, 0, 0.3)] });
  const q = Walk.spawn(low, 0.7, 0.1, 0);
  near(q.x, 0.7);
  near(q.feet, 0.3);
});

test('clamps at the margin', () => {
  const w = world();
  const p = Walk.spawn(w, 39, 0, EAST);
  run(p, FORWARD, w, 20);
  near(p.x, 40);
  const q = Walk.spawn(w, 0, -39, 0);
  run(q, FORWARD, w, 20);
  near(q.z, -40);
});

test('long frames are capped at MAX_FRAME', () => {
  const w = world();
  const p = Walk.spawn(w, 0, 0, EAST);
  Walk.step(p, 1.0, FORWARD, w); // only 0.25 s of it is simulated
  near(p.x, 0.75);
  // A negative frame time (a clock going backwards) does not move the player.
  Walk.step(p, -1, FORWARD, w);
  near(p.x, 0.75);
});

test('frames are split into equal substeps', () => {
  // A 0.12 s frame runs as three 0.04 s substeps, exactly like three 0.04 s frames.
  const w = world({ platforms: [platform(0.3, -5, 5, 5, 0, 0.4)] });
  const input = { forward: true, jump: true };
  const a = Walk.spawn(w, 0, 0, EAST);
  const b = Walk.spawn(w, 0, 0, EAST);
  Walk.step(a, 0.12, input, w);
  for (let i = 0; i < 3; i++) Walk.step(b, 0.04, input, w);
  near(a.x, b.x, 1e-12);
  near(a.feet, b.feet, 1e-12);
  near(a.vy, b.vy, 1e-12);
  near(a.x, 0.36);
});

test('mouse and keys work like a shooter', () => {
  // Mouse right turns the view clockwise seen from above (from −z towards +x), mouse up looks up.
  const w = world();
  const p = Walk.spawn(w, 0, 0, 0);
  Walk.look(p, 100, -100);
  near(p.yaw, -0.25);
  near(p.pitch, 0.25);
  const fx = -Math.sin(p.yaw);
  const fz = -Math.cos(p.yaw);
  assert.ok(fx > 0 && fz < 0);
  // D for 1 s moves along the view's right, forward × up = (−fz, fx) on the ground; W along the view.
  run(p, { right: true }, w, 20);
  near(p.x, -3 * fz);
  near(p.z, 3 * fx);
  const q = Walk.spawn(w, 0, 0, p.yaw);
  run(q, FORWARD, w, 20);
  near(q.x, 3 * fx);
  near(q.z, 3 * fz);
});

// The crosshair test of the desktop viewers (crosshairHitsTheNearestBox) has no counterpart here: the browser picks
// with three.js's raycaster against the instanced meshes, not with walk.js.

test('pitch is clamped, yaw is not', () => {
  const p = Walk.makePlayer(0, 0);
  Walk.look(p, 0, -100000);
  near(p.pitch, Walk.PITCH_LIMIT, 1e-12);
  Walk.look(p, 0, 200000);
  near(p.pitch, -Walk.PITCH_LIMIT, 1e-12);
  Walk.look(p, -10000, 0);
  near(p.yaw, 25);
});

test('running is faster than walking and diagonals are not', () => {
  const w = world();
  const p = Walk.spawn(w, 0, 0, EAST);
  run(p, { forward: true, run: true }, w, 20);
  near(p.x, 7.5);
  // Forward and left together move at the same speed as forward alone.
  const q = Walk.spawn(w, 0, 0, 0);
  run(q, { forward: true, left: true }, w, 20);
  near(Math.hypot(q.x, q.z), 3);
  // Opposite keys cancel.
  const r = Walk.spawn(w, 0, 0, 0);
  run(r, { forward: true, back: true, left: true, right: true }, w, 20);
  near(r.x, 0);
  near(r.z, 0);
});

test('the eye is EYE_HEIGHT above the feet', () => {
  assert.deepEqual(Walk.eye(Walk.makePlayer(1, 2, 0.4)), { x: 1, y: 0.4 + Walk.EYE_HEIGHT, z: 2 });
});

test('overlaps: touching is not overlapping', () => {
  const b = building(0, 0, 1, 1, 0, 1);
  assert.equal(Walk.overlaps(b, -Walk.RADIUS, 0.5), false); // circle just touches the left side
  assert.equal(Walk.overlaps(b, -Walk.RADIUS + 0.01, 0.5), true);
  assert.equal(Walk.overlaps(b, 0.5, 0.5), true); // inside
  // Near a corner the circle's roundness counts: (−0.15, −0.15) is 0.212 from the corner.
  assert.equal(Walk.overlaps(b, -0.15, -0.15), false);
  assert.equal(Walk.overlaps(b, -0.1, -0.1), true);
});

test('groundAt takes the highest top within a step', () => {
  const w = world({ platforms: [platform(-5, -5, 5, 5, 0, 0.3)], buildings: [building(-1, -1, 1, 1, 0.3, 0.7), building(2, 2, 3, 3, 0.3, 3)] });
  near(Walk.groundAt(w, 0, 0, 0.3), 0.7); // the low building on the platform: 0.4 above the feet
  near(Walk.groundAt(w, 0, 0, 0), 0.3); // from the street the low building is more than a step up
  near(Walk.groundAt(w, 2.5, 2.5, 0.3), 0.3); // the tall building is no ground
  near(Walk.groundAt(w, 9, 9, 0), 0); // off the platform
});

test('makeWorld builds platforms and buildings from the city JSON', () => {
  const city = {
    platform: 0.5, width: 20, depth: 10,
    districts: [{ x: 0, z: 0, w: 20, d: 10, depth: 0 }, { x: 2, z: 1, w: 4, d: 6, depth: 1 }],
    buildings: [{ x: 2, z: 1, y: 1, side: 2 }, { x: -5, z: 0, y: 0.5, side: 1 }],
  };
  const w = Walk.makeWorld(city, [3, 0]);
  assert.deepEqual(w.boxes, [Walk.box(-10, 10, -5, 5, 0, 0.5), Walk.box(0, 4, -2, 4, 0, 1)]);
  assert.deepEqual(w.buildingBoxes, [Walk.box(1, 3, 0, 2, 1, 4), Walk.box(-5.5, -4.5, -0.5, 0.5, 0.5, 0.5)]);
  assert.deepEqual([w.minX, w.maxX, w.minZ, w.maxZ], [-40, 40, -35, 35]);
  // Heights change in place; a height of (about) 0 hides the building.
  Walk.updateHeights(w, city, [0.0005, 2]);
  assert.equal(w.buildingBoxes[0].top, 1);
  assert.equal(w.buildingBoxes[1].top, 2.5);
});

test('a hidden building neither blocks nor carries', () => {
  const city = {
    platform: 0.5, width: 20, depth: 20, districts: [],
    buildings: [{ x: 1.5, z: 0, y: 0, side: 1 }],
  };
  const w = Walk.makeWorld(city, [0]);
  const p = Walk.spawn(w, 0, 0, EAST);
  run(p, FORWARD, w, 20);
  near(p.x, 3);
  // Once it has a height, the same walk is blocked at its wall.
  Walk.updateHeights(w, city, [3]);
  const q = Walk.spawn(w, 0, 0, EAST);
  run(q, FORWARD, w, 20);
  near(q.x, 0.8);
});
