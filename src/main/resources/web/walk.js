// Walk mode — first-person movement and collision through the city, independent of three.js and the DOM.
//
// app.js owns input, camera and HUD; this module only does the arithmetic, so it can run (and be tested) anywhere.
// The same rules, constants and step order are implemented by the desktop viewers (desktop/Walker.java,
// rust/src/gui/walk.rs, d/source/codecity/gui/walk.d); keep all four in sync.
//
// World: a list of axis-aligned solid boxes — every district platform (from y = 0 to its top) and every visible
// building (from its lot height to lot + current height) — plus the rectangle the player may roam in (the city
// rectangle grown by MARGIN). Outside every platform the ground is y = 0.
//
// Player: a vertical cylinder of radius RADIUS standing with its feet at `feet`; the eye is EYE_HEIGHT above that.
// Horizontally it is treated as a circle against the boxes' rectangles. Yaw 0 looks along −z (three.js's default
// camera direction) and grows counter-clockwise seen from above, so the forward vector is (−sin yaw, −cos yaw).

export const EYE_HEIGHT = 0.6;
export const RADIUS = 0.2;
export const WALK_SPEED = 3.0;
export const RUN_SPEED = 7.5;
export const STEP_HEIGHT = 0.45;
export const GRAVITY = 18;
export const JUMP_SPEED = 6.5;
export const MOUSE_SENS = 0.0025;
export const PITCH_LIMIT = (85 * Math.PI) / 180;
export const FOV = 70;
export const NEAR = 0.05;
export const MARGIN = 30;
export const MAX_DT = 0.05; // longest physics substep
export const MAX_FRAME = 0.25; // frame time beyond this is dropped (e.g. after a stall or a background tab)

/** A solid box: x/z extent and y range. */
export function box(minX, maxX, minZ, maxZ, bottom, top) {
  return { minX, maxX, minZ, maxZ, bottom, top };
}

/**
 * Builds the collision world from the city JSON and the buildings' current heights (one per building; a height of
 * about 0 means hidden). The building boxes are kept in `world.buildingBoxes` in building order so that
 * updateHeights can refresh them every frame without reallocating.
 */
export function makeWorld(city, heights) {
  const P = city.platform;
  const boxes = city.districts.map((d) => box(d.x - d.w / 2, d.x + d.w / 2, d.z - d.d / 2, d.z + d.d / 2, 0, (d.depth + 1) * P));
  const buildingBoxes = city.buildings.map((b) => box(b.x - b.side / 2, b.x + b.side / 2, b.z - b.side / 2, b.z + b.side / 2, b.y, b.y));
  const world = {
    boxes,
    buildingBoxes,
    minX: -city.width / 2 - MARGIN, maxX: city.width / 2 + MARGIN,
    minZ: -city.depth / 2 - MARGIN, maxZ: city.depth / 2 + MARGIN,
  };
  updateHeights(world, city, heights);
  return world;
}

/** Copies the current (possibly animating) heights into the building boxes; hidden buildings get no height. */
export function updateHeights(world, city, heights) {
  city.buildings.forEach((b, i) => {
    const h = heights[i];
    world.buildingBoxes[i].top = h > 0.001 ? b.y + h : b.y;
  });
}

// Every solid box: platforms first, then the buildings that currently have a height.
function* solids(world) {
  yield* world.boxes;
  if (world.buildingBoxes) for (const b of world.buildingBoxes) if (b.top > b.bottom) yield b;
}

// Overlaps shallower than this do not count. A push-out leaves the circle exactly touching the box, and rounding can
// make that look like a tiny overlap, which the next axis would then "resolve" by throwing the player across the box.
const EPSILON = 1e-9;

/** True when the circle at (x, z) with radius RADIUS overlaps the box's rectangle (touching does not count). */
export function overlaps(b, x, z) {
  const dx = x - Math.min(Math.max(x, b.minX), b.maxX);
  const dz = z - Math.min(Math.max(z, b.minZ), b.maxZ);
  return dx * dx + dz * dz < RADIUS * RADIUS - EPSILON;
}

/** Highest top under the circle at (x, z) that is no more than STEP_HEIGHT above `feet`; 0 when there is none. */
export function groundAt(world, x, z, feet) {
  let ground = 0;
  for (const b of solids(world)) {
    if (b.top <= feet + STEP_HEIGHT && b.top > ground && overlaps(b, x, z)) ground = b.top;
  }
  return ground;
}

// A box blocks the player when it rises more than a step above the feet and starts below the eyes.
function blocks(b, feet) {
  return b.top > feet + STEP_HEIGHT && b.bottom < feet + EYE_HEIGHT;
}

/** A player standing at (x, z) with the given view direction, feet at `feet`, not moving. */
export function makePlayer(x, z, feet = 0, yaw = 0, pitch = 0) {
  return { x, z, feet, vy: 0, onGround: true, yaw, pitch };
}

/**
 * Places a new player at (x, z): pushes it out of any building taller than STEP_HEIGHT above the ground there (along
 * the axis of least penetration, repeated while the push lands in another building) and stands it on the ground.
 * "The ground there" is the top of the platforms under the circle, so a spawn point next to a low building is fine.
 */
export function spawn(world, x, z, yaw = 0, pitch = 0) {
  for (let iter = 0; iter < 16; iter++) {
    const base = platformTop(world, x, z);
    let moved = false;
    for (const b of world.buildingBoxes || []) {
      if (b.top <= b.bottom || b.top <= base + STEP_HEIGHT || !overlaps(b, x, z)) continue;
      // Distance to move left / right / towards −z / towards +z until the circle just touches the box.
      const left = x + RADIUS - b.minX;
      const right = b.maxX - (x - RADIUS);
      const back = z + RADIUS - b.minZ;
      const front = b.maxZ - (z - RADIUS);
      const min = Math.min(left, right, back, front);
      if (min === left) x = b.minX - RADIUS;
      else if (min === right) x = b.maxX + RADIUS;
      else if (min === back) z = b.minZ - RADIUS;
      else z = b.maxZ + RADIUS;
      moved = true;
      break;
    }
    if (!moved) break;
  }
  x = clamp(x, world.minX, world.maxX);
  z = clamp(z, world.minZ, world.maxZ);
  return makePlayer(x, z, groundAt(world, x, z, platformTop(world, x, z)), yaw, pitch);
}

// Highest platform top under the circle (0 off the city).
function platformTop(world, x, z) {
  let top = 0;
  for (const b of world.boxes) if (b.top > top && overlaps(b, x, z)) top = b.top;
  return top;
}

/** Turns the view by a mouse movement in pixels: yaw is unlimited, pitch is clamped to ±PITCH_LIMIT. */
export function look(p, dx, dy) {
  p.yaw -= dx * MOUSE_SENS;
  p.pitch = clamp(p.pitch - dy * MOUSE_SENS, -PITCH_LIMIT, PITCH_LIMIT);
}

/**
 * Advances the player by one frame of `dt` seconds. The frame time is capped at MAX_FRAME and split into equal
 * substeps of at most MAX_DT, so collisions stay reliable and a slow renderer still walks at full speed.
 */
export function step(p, dt, input, world) {
  const frame = Math.min(Math.max(dt, 0), MAX_FRAME);
  // The small tolerance keeps e.g. 0.25 / 0.05 at 5 substeps despite rounding.
  const n = Math.max(1, Math.ceil(frame / MAX_DT - 1e-9));
  for (let i = 0; i < n; i++) substep(p, frame / n, input, world);
  return p;
}

/**
 * One physics substep (dt ≤ MAX_DT). `input` has booleans forward, back, left, right, run and jump. Order:
 *   1. horizontal velocity = input direction (normalised) × speed — no inertia, same control in the air;
 *      a jump starts only from the ground;
 *   2. move along x, push out of blocking boxes along x; then the same along z (so walls slide);
 *   3. stand on the ground if the move stepped up onto something, otherwise fall under gravity and land;
 *   4. keep x/z inside the city rectangle ± MARGIN.
 */
function substep(p, dt, input, world) {
  // 1. Input direction in the horizontal plane, relative to yaw.
  let fwd = (input.forward ? 1 : 0) - (input.back ? 1 : 0);
  let side = (input.right ? 1 : 0) - (input.left ? 1 : 0);
  const len = Math.hypot(fwd, side);
  if (len > 0) { fwd /= len; side /= len; }
  const speed = input.run ? RUN_SPEED : WALK_SPEED;
  const sin = Math.sin(p.yaw), cos = Math.cos(p.yaw);
  // forward = (−sin, −cos), right = (cos, −sin)
  const vx = (-sin * fwd + cos * side) * speed;
  const vz = (-cos * fwd - sin * side) * speed;
  if (input.jump && p.onGround) { p.vy = JUMP_SPEED; p.onGround = false; }

  // 2. One axis at a time; a blocking box pushes the player back to where the circle just touches it.
  p.x += vx * dt;
  for (const b of solids(world)) {
    if (!blocks(b, p.feet) || !overlaps(b, p.x, p.z)) continue;
    const cx = (b.minX + b.maxX) / 2;
    p.x = vx > 0 || (vx === 0 && p.x < cx) ? b.minX - RADIUS : b.maxX + RADIUS;
  }
  p.z += vz * dt;
  for (const b of solids(world)) {
    if (!blocks(b, p.feet) || !overlaps(b, p.x, p.z)) continue;
    const cz = (b.minZ + b.maxZ) / 2;
    p.z = vz > 0 || (vz === 0 && p.z < cz) ? b.minZ - RADIUS : b.maxZ + RADIUS;
  }

  // 3. Step up, or fall and land.
  const ground = groundAt(world, p.x, p.z, p.feet);
  if (p.feet < ground) {
    p.feet = ground;
    p.vy = 0;
    p.onGround = true;
  } else {
    p.vy -= GRAVITY * dt;
    p.feet += p.vy * dt;
    if (p.feet <= ground) {
      p.feet = ground;
      p.vy = 0;
      p.onGround = true;
    } else {
      p.onGround = false;
    }
  }

  // 4. Stay near the city.
  p.x = clamp(p.x, world.minX, world.maxX);
  p.z = clamp(p.z, world.minZ, world.maxZ);
}

/** Eye position of the player. */
export function eye(p) {
  return { x: p.x, y: p.feet + EYE_HEIGHT, z: p.z };
}

function clamp(v, lo, hi) {
  return Math.min(Math.max(v, lo), hi);
}
