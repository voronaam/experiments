//! First-person walk mode: movement, collision and camera angles of a player walking through the city.
//!
//! This module is UI-independent: it knows nothing about windows, egui or three-d. The app ([`super::App`]) builds a
//! [`World`] from the model and the current building heights every frame, collects the held keys into an [`Input`]
//! and calls [`Walker::step`]; mouse motion goes to [`Walker::look`]. The camera then sits at [`Walker::eye`] and
//! looks along [`Walker::direction`]. The same rules, constants and test scenarios exist in the browser, Java and D
//! viewers, so all four walk identically.
//!
//! # Model
//! The player is a vertical cylinder of radius [`RADIUS`] seen from above as a circle, with its feet at `feet` and
//! its eyes [`EYE_HEIGHT`] higher. The city is a list of axis-aligned boxes ([`Solid`]): every district platform
//! (from the ground up to its top) and every visible building. Outside all platforms the ground is at `y = 0`.
//!
//! # Angles
//! `yaw` uses the orbit camera's convention, so the orbit yaw can be handed over unchanged: at yaw 0 the player
//! looks towards `-z` (into the city from the front), and the view direction is `(-sin yaw, ·, -cos yaw)`. `pitch`
//! is the elevation of the view above the horizon, positive looking up.

use crate::city::CityModel;
use crate::layout::PLATFORM;

/// Height of the eyes above the feet.
pub const EYE_HEIGHT: f32 = 0.6;
/// Radius of the player's circle (seen from above).
pub const RADIUS: f32 = 0.2;
/// Walking speed in world units per second.
pub const WALK_SPEED: f32 = 3.0;
/// Speed with Shift held.
pub const RUN_SPEED: f32 = 7.5;
/// Ledges up to this height above the feet are climbed automatically; anything taller is a wall.
pub const STEP_HEIGHT: f32 = 0.45;
/// Downward acceleration, units per second squared.
pub const GRAVITY: f32 = 18.0;
/// Upward speed at the start of a jump.
pub const JUMP_SPEED: f32 = 6.5;
/// Mouse-look sensitivity in radians per pixel of mouse motion.
pub const MOUSE_SENS: f32 = 0.0025;
/// Largest look angle above or below the horizon: 85°.
pub const PITCH_LIMIT: f32 = 85.0 * std::f32::consts::PI / 180.0;
/// Vertical field of view while walking, in degrees.
pub const FOV: f32 = 70.0;
/// Near clipping plane while walking: close enough not to cut into a wall the player stands against.
pub const NEAR: f32 = 0.05;
/// How far beyond the city's rectangle the player may walk.
pub const MARGIN: f32 = 30.0;
/// Longest physics substep. A longer frame is split into equal substeps no longer than this, so a slow frame never
/// lets the player tunnel through a wall or fall through a roof, yet slow machines still walk at full speed.
pub const MAX_DT: f32 = 0.05;
/// Longest frame time simulated; anything beyond (a stall, a window drag) is dropped rather than caught up.
pub const MAX_FRAME: f32 = 0.25;

/// Tolerance of the overlap test. A player pushed out to `min_x - RADIUS` is at distance RADIUS from the face only up
/// to rounding (in f32 and f64 alike, `(1.0 - 0.2) - 1.0` is a hair short of `-0.2`), and without this margin would
/// still count as overlapping; the next axis's push-out would then throw the player to the far side of the box.
const CONTACT: f32 = 1e-3;

/// An axis-aligned box the player collides with: `min_x..max_x` × `bottom..top` × `min_z..max_z`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Solid {
    pub min_x: f32,
    pub max_x: f32,
    pub min_z: f32,
    pub max_z: f32,
    pub bottom: f32,
    pub top: f32,
    /// A building rather than a district platform; only buildings push the player out at spawn.
    pub building: bool,
}

impl Solid {
    /// Whether the player's circle centred on `(x, z)` overlaps the box's footprint: the distance from the centre to
    /// the nearest point of the rectangle is less than [`RADIUS`] (minus [`CONTACT`]). Touching does not count, so a
    /// player pushed out to `min_x - RADIUS` can slide along the face.
    fn overlaps(&self, x: f32, z: f32) -> bool {
        let dx = x - x.clamp(self.min_x, self.max_x);
        let dz = z - z.clamp(self.min_z, self.max_z);
        dx * dx + dz * dz < (RADIUS - CONTACT) * (RADIUS - CONTACT)
    }

    /// Whether the box is a wall for feet at `feet`: too tall to step onto, and not entirely above the head.
    fn blocks(&self, feet: f32) -> bool {
        self.top > feet + STEP_HEIGHT && self.bottom < feet + EYE_HEIGHT
    }
}

/// Everything the player can collide with, plus the city's extent for the walking limit.
pub struct World {
    pub solids: Vec<Solid>,
    /// Half the city's width (x) and depth (z); the city is centred on the origin.
    pub half_width: f32,
    pub half_depth: f32,
}

impl World {
    /// The solids of a laid-out city at the given (possibly mid-animation) building heights. Buildings of height
    /// ≤ 0.001 are hidden (see `Scene::set_buildings`) and left out.
    ///
    /// A district at nesting depth `n` is a box from the ground to `(n + 1) · PLATFORM` (the platforms below it are
    /// separate boxes, which changes nothing for collisions); a building is a box from its lot's `y`, the top of its
    /// platform, up to `y + height`.
    pub fn from_model(model: &CityModel, heights: &[f32]) -> World {
        let mut solids = Vec::with_capacity(model.districts.len() + model.buildings.len());
        for d in &model.districts {
            solids.push(Solid {
                min_x: (d.x - d.w / 2.0) as f32,
                max_x: (d.x + d.w / 2.0) as f32,
                min_z: (d.z - d.d / 2.0) as f32,
                max_z: (d.z + d.d / 2.0) as f32,
                bottom: 0.0,
                top: ((d.depth + 1) as f64 * PLATFORM) as f32,
                building: false,
            });
        }
        for b in &model.buildings {
            let h = heights[b.index];
            if h <= 0.001 {
                continue;
            }
            let half = (b.lot.side / 2.0) as f32;
            let (x, z, y) = (b.lot.x as f32, b.lot.z as f32, b.lot.y as f32);
            solids.push(Solid { min_x: x - half, max_x: x + half, min_z: z - half, max_z: z + half, bottom: y, top: y + h, building: true });
        }
        World { solids, half_width: (model.width / 2.0) as f32, half_depth: (model.depth / 2.0) as f32 }
    }

    /// Ground height under the circle at `(x, z)` for feet at `feet`: the highest top of the boxes under the circle
    /// that can be stood on from there (at most [`STEP_HEIGHT`] above the feet), or 0 if there is none.
    fn ground(&self, x: f32, z: f32, feet: f32) -> f32 {
        self.solids.iter().filter(|s| s.top <= feet + STEP_HEIGHT && s.overlaps(x, z)).fold(0.0, |g, s| g.max(s.top))
    }
}

/// Movement keys held this frame (W/A/S/D or arrows, Shift, Space).
#[derive(Clone, Copy, Debug, Default)]
pub struct Input {
    pub forward: bool,
    pub back: bool,
    pub left: bool,
    pub right: bool,
    pub run: bool,
    pub jump: bool,
}

/// The walking player: position, vertical motion and view angles.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Walker {
    pub x: f32,
    pub z: f32,
    /// Height of the feet.
    pub feet: f32,
    /// Vertical speed, positive upwards.
    pub vy: f32,
    pub yaw: f32,
    pub pitch: f32,
    pub on_ground: bool,
}

impl Walker {
    /// A player standing at `(x, z)`, looking horizontally along `yaw`.
    ///
    /// If the circle there overlaps a building that is taller than [`STEP_HEIGHT`] above the platform ground, the
    /// player is pushed out along the axis and side of least penetration, and that is repeated (a push can land in a
    /// neighbouring building) until the spot is free; 16 rounds are plenty for any real layout. The feet then start
    /// on the ground at the final spot, low buildings included, with no vertical speed.
    pub fn spawn(world: &World, x: f32, z: f32, yaw: f32) -> Walker {
        let (mut x, mut z) = (x, z);
        // Ground from the platforms alone: the "ground there" the building heights are measured against.
        let platform = |x: f32, z: f32| world.solids.iter().filter(|s| !s.building && s.overlaps(x, z)).fold(0.0f32, |g, s| g.max(s.top));
        for _ in 0..16 {
            let ground = platform(x, z);
            let Some(s) = world.solids.iter().find(|s| s.building && s.top > ground + STEP_HEIGHT && s.overlaps(x, z)) else { break };
            // How far the circle would have to move to clear each of the four faces; the smallest move wins.
            let moves = [
                ((x + RADIUS) - s.min_x, s.min_x - RADIUS, true),
                (s.max_x - (x - RADIUS), s.max_x + RADIUS, true),
                ((z + RADIUS) - s.min_z, s.min_z - RADIUS, false),
                (s.max_z - (z - RADIUS), s.max_z + RADIUS, false),
            ];
            let (_, to, along_x) = moves.into_iter().fold(moves[0], |best, m| if m.0 < best.0 { m } else { best });
            if along_x {
                x = to;
            } else {
                z = to;
            }
        }
        let feet = world.ground(x, z, platform(x, z));
        Walker { x, z, feet, vy: 0.0, yaw, pitch: 0.0, on_ground: true }
    }

    /// Mouse look: `dx`/`dy` are mouse motion in pixels (right and down positive). Moving right turns right, moving
    /// up looks up. Yaw is unlimited, pitch stays within ±[`PITCH_LIMIT`].
    pub fn look(&mut self, dx: f32, dy: f32) {
        self.yaw -= dx * MOUSE_SENS;
        self.pitch = (self.pitch - dy * MOUSE_SENS).clamp(-PITCH_LIMIT, PITCH_LIMIT);
    }

    /// Advances the player by a frame of `dt` seconds: capped at [`MAX_FRAME`], then split into equal substeps of at
    /// most [`MAX_DT`], each running [`Walker::substep`].
    pub fn step(&mut self, dt: f32, input: Input, world: &World) {
        let frame = dt.clamp(0.0, MAX_FRAME);
        // At least one substep; the small tolerance keeps e.g. 0.1 s at two substeps despite rounding.
        let n = ((frame / MAX_DT) - 1e-4).ceil().max(1.0);
        for _ in 0..n as u32 {
            self.substep(frame / n, input, world);
        }
    }

    /// Advances the player by `dt` seconds (at most [`MAX_DT`], see [`Walker::step`]):
    ///
    /// 1. The horizontal velocity is the input direction (relative to yaw, diagonals normalised) times the walking or
    ///    running speed; there is no inertia, and the same control applies in the air.
    /// 2. The move is applied along x, then along z, so that running into a wall at an angle slides along it. After
    ///    each axis, every box that overlaps the circle and is a wall at the current feet height pushes the player
    ///    back out along that axis, to the side the player came from, until the circle just touches it.
    /// 3. On ground higher than the feet (a step up) the feet snap onto it; otherwise gravity applies and the player
    ///    lands when the feet reach the ground.
    /// 4. The position is kept within the city's rectangle widened by [`MARGIN`].
    fn substep(&mut self, dt: f32, input: Input, world: &World) {
        if input.jump && self.on_ground {
            self.vy = JUMP_SPEED;
            self.on_ground = false;
        }
        // 1. Input direction in (forward, right) components.
        let f = input.forward as i32 as f32 - input.back as i32 as f32;
        let r = input.right as i32 as f32 - input.left as i32 as f32;
        let len = f.hypot(r);
        if len > 0.0 {
            let speed = if input.run { RUN_SPEED } else { WALK_SPEED };
            let (s, c) = self.yaw.sin_cos();
            // forward = (-sin yaw, -cos yaw), right = (cos yaw, -sin yaw); see the module docs.
            let vx = (-s * f + c * r) / len * speed;
            let vz = (-c * f - s * r) / len * speed;
            // 2. One axis at a time.
            let before = self.x;
            self.x += vx * dt;
            self.resolve(world, before, true);
            let before = self.z;
            self.z += vz * dt;
            self.resolve(world, before, false);
        }
        // 3. Vertical motion. Snapping up onto a ledge counts as standing on it, even mid-jump.
        let ground = world.ground(self.x, self.z, self.feet);
        if self.feet < ground {
            self.feet = ground;
            self.vy = 0.0;
            self.on_ground = true;
        } else {
            self.vy -= GRAVITY * dt;
            self.feet += self.vy * dt;
            if self.feet <= ground {
                self.feet = ground;
                self.vy = 0.0;
                self.on_ground = true;
            } else {
                self.on_ground = false;
            }
        }
        // 4. Walking limit.
        self.x = self.x.clamp(-world.half_width - MARGIN, world.half_width + MARGIN);
        self.z = self.z.clamp(-world.half_depth - MARGIN, world.half_depth + MARGIN);
    }

    /// Pushes the player out of every wall it overlaps, along x (`along_x`) or z, until the circle just touches the
    /// box: at a face that is `RADIUS` away, past a corner only as far as the circle reaches given its offset on the
    /// other axis (`sqrt(RADIUS² - gap²)`), so sliding round a corner is smooth.
    ///
    /// `before` is the position on that axis before the move. A player that was on the low side of the box (at or
    /// below its low edge) goes back there, one on the high side likewise; one that was level with the box on this
    /// axis (it can only have entered across the other axis' edge, e.g. a box that appeared) goes to the nearer side.
    /// Touching is not overlapping (see [`CONTACT`]), so the next axis' push-out leaves the player where it is.
    fn resolve(&mut self, world: &World, before: f32, along_x: bool) {
        for s in &world.solids {
            if !s.blocks(self.feet) || !s.overlaps(self.x, self.z) {
                continue;
            }
            // Distance from the circle's centre to the box on the other axis (0 if level with it).
            let gap = if along_x { self.z - self.z.clamp(s.min_z, s.max_z) } else { self.x - self.x.clamp(s.min_x, s.max_x) };
            let reach = (RADIUS * RADIUS - gap * gap).max(0.0).sqrt();
            let (pos, lo, hi) = if along_x { (&mut self.x, s.min_x, s.max_x) } else { (&mut self.z, s.min_z, s.max_z) };
            let to_lo = if before <= lo { true } else if before >= hi { false } else { *pos < (lo + hi) / 2.0 };
            *pos = if to_lo { lo - reach } else { hi + reach };
        }
    }

    /// Eye position: above the feet by [`EYE_HEIGHT`].
    pub fn eye(&self) -> [f32; 3] {
        [self.x, self.feet + EYE_HEIGHT, self.z]
    }

    /// Unit view direction from yaw and pitch.
    pub fn direction(&self) -> [f32; 3] {
        let (s, c) = self.yaw.sin_cos();
        let (ps, pc) = self.pitch.sin_cos();
        [-s * pc, ps, -c * pc]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Yaw that faces +x (forward = (-sin yaw, -cos yaw)).
    const EAST: f32 = -std::f32::consts::FRAC_PI_2;

    /// A box; `building` decides whether it counts for the spawn push-out.
    fn solid(min_x: f32, max_x: f32, min_z: f32, max_z: f32, bottom: f32, top: f32, building: bool) -> Solid {
        Solid { min_x, max_x, min_z, max_z, bottom, top, building }
    }

    /// A 20 × 20 city (half extents 10) with the given solids.
    fn world(solids: Vec<Solid>) -> World {
        World { solids, half_width: 10.0, half_depth: 10.0 }
    }

    /// A player standing at `(x, z)` with feet at `feet`.
    fn at(x: f32, z: f32, feet: f32, yaw: f32) -> Walker {
        Walker { x, z, feet, vy: 0.0, yaw, pitch: 0.0, on_ground: true }
    }

    /// Steps `n` frames of 0.05 s with the same input.
    fn run(w: &mut Walker, world: &World, input: Input, n: usize) {
        for _ in 0..n {
            w.step(0.05, input, world);
        }
    }

    const FORWARD: Input = Input { forward: true, back: false, left: false, right: false, run: false, jump: false };

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-4
    }

    #[test]
    fn walking_into_a_wall_slides_along_it() {
        // A long wall at x = 1..3; walking forward-right (yaw 0: towards -z and +x) for 1 s keeps the z motion.
        let world = world(vec![solid(1.0, 3.0, -5.0, 5.0, 0.0, 5.0, true)]);
        let mut w = Walker::spawn(&world, 0.0, 0.0, 0.0);
        run(&mut w, &world, Input { forward: true, right: true, ..Default::default() }, 20);
        assert!(close(w.x, 0.8), "x = {}", w.x);
        assert!(close(w.z, -3.0 / 2f32.sqrt()), "z = {}", w.z);
        assert!(close(w.feet, 0.0));
    }

    #[test]
    fn steps_onto_a_platform() {
        // A 0.4 platform at x = 1..5; walking +x for 1 s ends on it at x = 3.
        let world = world(vec![solid(1.0, 5.0, -5.0, 5.0, 0.0, 0.4, false)]);
        let mut w = Walker::spawn(&world, 0.0, 0.0, EAST);
        run(&mut w, &world, FORWARD, 20);
        assert!(close(w.x, 3.0), "x = {}", w.x);
        assert!(close(w.feet, 0.4), "feet = {}", w.feet);
        assert!(w.on_ground);
    }

    #[test]
    fn blocked_by_a_tall_building() {
        let world = world(vec![solid(1.0, 2.0, -1.0, 1.0, 0.0, 3.0, true)]);
        let mut w = Walker::spawn(&world, 0.0, 0.0, EAST);
        run(&mut w, &world, FORWARD, 20);
        assert!(close(w.x, 0.8), "x = {}", w.x);
        assert!(close(w.z, 0.0));
        assert!(close(w.feet, 0.0));
    }

    #[test]
    fn slides_round_a_corner_only_as_far_as_the_circle_reaches() {
        // Passing a tall box's corner 0.1 beside it (z = 1.1 against its z = 1 edge): the push-out stops where the
        // circle just touches the corner, 1 - sqrt(0.2² - 0.1²) ≈ 0.8268, not a full radius back at 0.8.
        let world = world(vec![solid(1.0, 2.0, -1.0, 1.0, 0.0, 3.0, true)]);
        let mut w = at(0.7, 1.1, 0.0, EAST);
        w.step(0.05, FORWARD, &world);
        assert!(close(w.x, 1.0 - 0.03f32.sqrt()), "x = {}", w.x);
        assert!(close(w.z, 1.1));
    }

    #[test]
    fn falling_off_a_roof_lands_on_the_ground() {
        // On a 2-high roof at x = -1..1, walking +x: past the edge (1.2) after 0.5 s and falling; landed by 1.5 s.
        let world = world(vec![solid(-1.0, 1.0, -1.0, 1.0, 0.0, 2.0, true)]);
        let mut w = at(0.0, 0.0, 2.0, EAST);
        run(&mut w, &world, FORWARD, 10);
        assert!(close(w.x, 1.5), "x = {}", w.x);
        assert!(!w.on_ground);
        assert!(w.feet < 2.0);
        run(&mut w, &world, FORWARD, 20);
        assert!(close(w.x, 4.5), "x = {}", w.x);
        assert!(close(w.feet, 0.0), "feet = {}", w.feet);
        assert!(close(w.vy, 0.0));
        assert!(w.on_ground);
    }

    #[test]
    fn jumps_only_from_the_ground() {
        let world = world(vec![]);
        let jump = Input { jump: true, ..Default::default() };
        let mut w = Walker::spawn(&world, 0.0, 0.0, 0.0);
        w.step(0.05, jump, &world);
        assert!(close(w.vy, 5.6), "vy = {}", w.vy); // 6.5 - 18 * 0.05
        assert!(close(w.feet, 0.28), "feet = {}", w.feet);
        assert!(!w.on_ground);
        // Still held in the air: no second jump, gravity just keeps slowing the rise.
        w.step(0.05, jump, &world);
        assert!(close(w.vy, 4.7), "vy = {}", w.vy);
        assert!(close(w.feet, 0.515), "feet = {}", w.feet);
        run(&mut w, &world, Input::default(), 20);
        assert!(close(w.feet, 0.0));
        assert!(w.on_ground);
    }

    #[test]
    fn spawn_pushes_out_of_buildings() {
        // Inside a 3-high building at x, z = -1..1: the shortest way out is +x (0.5) rather than ±z (1.1, 1.3) or
        // -x (1.9).
        let world = world(vec![solid(-1.0, 1.0, -1.0, 1.0, 0.0, 3.0, true)]);
        let w = Walker::spawn(&world, 0.7, 0.1, 0.0);
        assert!(close(w.x, 1.2) && close(w.z, 0.1), "at ({}, {})", w.x, w.z);
        assert!(close(w.feet, 0.0));
        assert!(w.on_ground);
        // A building no taller than STEP_HEIGHT is stood on instead.
        let low = self::world(vec![solid(-1.0, 1.0, -1.0, 1.0, 0.0, 0.3, true)]);
        let w = Walker::spawn(&low, 0.7, 0.1, 0.0);
        assert!(close(w.x, 0.7));
        assert!(close(w.feet, 0.3));
    }

    #[test]
    fn clamped_at_the_margin() {
        // City half extent 10 + MARGIN 30 = 40.
        let world = world(vec![]);
        let mut w = Walker::spawn(&world, 39.0, 0.0, EAST);
        run(&mut w, &world, FORWARD, 20);
        assert!(close(w.x, 40.0), "x = {}", w.x);
        let mut w = Walker::spawn(&world, 0.0, -39.0, 0.0);
        run(&mut w, &world, FORWARD, 20);
        assert!(close(w.z, -40.0), "z = {}", w.z);
    }

    #[test]
    fn long_frames_are_capped_and_split_into_substeps() {
        let world = world(vec![]);
        // A 1 s frame only simulates MAX_FRAME = 0.25 s: 0.75 units at walking speed.
        let mut w = Walker::spawn(&world, 0.0, 0.0, EAST);
        w.step(1.0, FORWARD, &world);
        assert!(close(w.x, 0.75), "x = {}", w.x);
        // A 0.12 s frame is three 0.04 s substeps, exactly like three 0.04 s frames (checked on a jump, where the
        // substep length matters).
        let jump = Input { jump: true, ..FORWARD };
        let mut a = Walker::spawn(&world, 0.0, 0.0, EAST);
        a.step(0.12, jump, &world);
        let mut b = Walker::spawn(&world, 0.0, 0.0, EAST);
        for _ in 0..3 {
            b.step(0.04, jump, &world);
        }
        assert!(close(a.x, b.x) && close(a.feet, b.feet) && close(a.vy, b.vy), "{a:?} vs {b:?}");
        assert!(close(a.x, 0.36));
        assert!(close(a.vy, 6.5 - 18.0 * 0.12));
    }

    #[test]
    fn look_clamps_pitch() {
        let mut w = at(0.0, 0.0, 0.0, 0.0);
        w.look(100.0, -10_000.0);
        assert!(close(w.yaw, -0.25));
        assert!(close(w.pitch, PITCH_LIMIT));
    }
}
