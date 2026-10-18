/// First-person "walk mode": movement, collision and spawn logic, independent of GLFW, OpenGL and ImGui.
///
/// The viewer (`codecity.gui.app`) builds a `World` from the city (one solid box per district platform and per visible
/// building), feeds the keyboard state in as an `Input` and the mouse motion into `Walker.look` every frame, calls
/// `Walker.step` and places the camera at `Walker.eye` looking along `Walker.forward`. The browser (walk.js), Java
/// (Walker.java) and Rust (walk.rs) viewers implement the same rules with the same constants, so walking feels the
/// same everywhere; the unit tests below use the same scenarios and expected numbers as theirs.
///
/// Conventions: world space as in `codecity.gui.math` (y up, the city centred on the origin). The player is a vertical
/// cylinder of radius `radius` whose bottom is at `feet`; collisions are tested in the ground plane as circle versus
/// rectangle, and in height by comparing box tops / bottoms with the feet. `yaw` 0 looks towards -z, positive yaw
/// turns left (like the orbit camera's yaw, so the orbit view direction carries over unchanged); `pitch` is positive
/// looking up.
module codecity.gui.walk;

import codecity.gui.math : Vec3;
import std.algorithm : clamp, max, min;
import std.math : abs, cos, PI, sin, sqrt;

/// Eye height above the feet.
enum float eyeHeight = 0.6f;
/// Radius of the player's circle in the ground plane.
enum float radius = 0.2f;
/// Horizontal speeds in units per second: walking, and running while Shift is held.
enum float walkSpeed = 3.0f, runSpeed = 7.5f;
/// Ledges up to this height above the feet are climbed automatically (a 0.4 platform is one step).
enum float stepHeight = 0.45f;
/// Downward acceleration (units / s²) and the upward speed a jump starts with (units / s).
enum float gravity = 18, jumpSpeed = 6.5f;
/// Mouse-look sensitivity in radians per pixel of mouse motion.
enum float mouseSensitivity = 0.0025f;
/// Pitch is kept within ±85° so the view never flips over the vertical (where `Mat4.lookAt`'s up vector breaks).
enum float pitchLimit = 85 * PI / 180;
/// Vertical field of view and near plane of the camera while walking (the eye gets close to walls).
enum float fov = 70 * PI / 180, nearPlane = 0.05f;
/// How far beyond the city rectangle the player may walk; the ground out there is y = 0.
enum float margin = 30;
/// Longest physics substep: a long frame is split into substeps no longer than this, so the player never moves far
/// enough in one go to tunnel through a wall.
enum float maxDt = 0.05f;
/// Longest frame time simulated; anything beyond (a stall: window dragged, machine asleep) is dropped. Below it, slow
/// renderers still walk at full speed because the whole frame time is simulated in substeps.
enum float maxFrame = 0.25f;
/// Extra distance a push-out leaves between the circle and a wall, so the next overlap test is clearly negative.
private enum float skin = 1e-6f;

/// A solid axis-aligned box: x from `x0` to `x1`, z from `z0` to `z1`, y from `bottom` to `top`.
struct Box
{
    float x0, z0, x1, z1, bottom, top;

    /// Whether the circle of `radius` around (`x`, `z`) overlaps the box's footprint (touching does not count). The
    /// closest point of the rectangle to the centre is found by clamping; the circle overlaps when it is nearer than
    /// the radius.
    bool overlaps(float x, float z) const
    {
        const dx = x - clamp(x, x0, x1), dz = z - clamp(z, z0, z1);
        return dx * dx + dz * dz < radius * radius;
    }
}

/// Everything the player can collide with: the solid boxes, and the half size of the city rectangle (the root
/// district), which with `margin` bounds where the player may go.
struct World
{
    Box[] boxes;
    float halfWidth = 0, halfDepth = 0;
}

/// The movement keys held this frame. `forward` and `right` are -1, 0 or 1 (W/S and D/A, or the arrow keys).
struct Input
{
    float forward = 0, right = 0;
    bool run, jump;
}

/// The walking player: position, view direction and vertical motion.
struct Walker
{
    float x = 0, z = 0;
    /// Height of the feet; the eye is `eyeHeight` above them.
    float feet = 0;
    /// Vertical speed (positive up); horizontal speed has no state since there is no inertia.
    float vy = 0;
    float yaw = 0, pitch = 0;
    bool onGround = true;

    /// The camera position.
    Vec3 eye() const { return Vec3(x, feet + eyeHeight, z); }

    /// The unit view direction for `yaw` / `pitch` (yaw 0, pitch 0 is -z).
    Vec3 forward() const { return Vec3(-sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch)); }

    /// Mouse look: `dx` / `dy` are the mouse motion in pixels (right / down positive). Moving the mouse right turns
    /// right (yaw decreases), moving it down looks down. Yaw is unlimited; pitch is clamped to ±`pitchLimit`.
    void look(double dx, double dy)
    {
        yaw -= cast(float)(dx * mouseSensitivity);
        pitch = clamp(pitch - cast(float)(dy * mouseSensitivity), -pitchLimit, pitchLimit);
    }

    /// Places a new walker at (`x`, `z`) facing `yaw` with its feet on the ground there.
    ///
    /// If the spot is inside a building taller than `stepHeight` above that ground, the walker is pushed out of it to
    /// the nearest free point: of the four ways out of the box (past each of its sides), the shortest one is taken
    /// (minimum-penetration axis). This is repeated (up to 8 times) in case the way out leads into a neighbour.
    static Walker spawn(float x, float z, float yaw, ref const World world)
    {
        Walker w;
        w.x = x;
        w.z = z;
        w.yaw = yaw;
        foreach (attempt; 0 .. 8)
        {
            const ground = w.standingHeight(world);
            bool moved;
            foreach (ref b; world.boxes)
            {
                if (!b.overlaps(w.x, w.z) || b.top <= ground + stepHeight || b.bottom >= ground + eyeHeight) continue;
                // distances to move past each side: left, right, front (-z), back (+z)
                const left = w.x - (b.x0 - radius), right = (b.x1 + radius) - w.x;
                const front = w.z - (b.z0 - radius), back = (b.z1 + radius) - w.z;
                const best = min(left, right, front, back);
                if (best == left) w.x = b.x0 - radius - skin;
                else if (best == right) w.x = b.x1 + radius + skin;
                else if (best == front) w.z = b.z0 - radius - skin;
                else w.z = b.z1 + radius + skin;
                moved = true;
                break;
            }
            if (!moved) break;
        }
        w.feet = w.standingHeight(world);
        w.vy = 0;
        w.onGround = true;
        return w;
    }

    /// The height the walker can stand at here when starting from y = 0 and climbing every ledge within
    /// `stepHeight` (nested district platforms are 0.4 apart, so this climbs a whole stack of them). Used for the
    /// spawn point, where there is no previous height to step from.
    private float standingHeight(ref const World world) const
    {
        float h = 0;
        foreach (iteration; 0 .. 64)
        {
            const next = groundBelow(world, h);
            if (next <= h) break;
            h = next;
        }
        return h;
    }

    /// The ground under the circle for feet at `at`: the highest top of the boxes under the circle that is at most
    /// `stepHeight` above the feet, or 0 (the ground plane) if there is none.
    private float groundBelow(ref const World world, float at) const
    {
        float ground = 0;
        foreach (ref b; world.boxes)
            if (b.top <= at + stepHeight && b.top > ground && b.overlaps(x, z)) ground = b.top;
        return ground;
    }

    /// Pushes the walker out of every box it now overlaps that blocks it (its top more than `stepHeight` above the
    /// feet and its bottom below the eye), along one axis only: back against the direction of the move `delta` on that
    /// axis (`axis` 0 = x, 1 = z). With no movement on the axis (a building grew around the walker), it is pushed
    /// towards whichever side of the box its centre is on. It is pushed just far enough for the circle to touch the
    /// box: when the centre is beside the box on the other axis (`gap` away), the circle reaches only
    /// sqrt(radius² - gap²) along this one, so corners are rounded rather than stopping the walker a full radius
    /// early.
    private void pushOut(ref const World world, int axis, float delta)
    {
        foreach (ref b; world.boxes)
        {
            if (b.top <= feet + stepHeight || b.bottom >= feet + eyeHeight || !b.overlaps(x, z)) continue;
            if (axis == 0)
            {
                const gap = z - clamp(z, b.z0, b.z1);
                const reach = sqrt(radius * radius - gap * gap) + skin;
                const back = delta != 0 ? delta > 0 : x < (b.x0 + b.x1) / 2;
                x = back ? b.x0 - reach : b.x1 + reach;
            }
            else
            {
                const gap = x - clamp(x, b.x0, b.x1);
                const reach = sqrt(radius * radius - gap * gap) + skin;
                const back = delta != 0 ? delta > 0 : z < (b.z0 + b.z1) / 2;
                z = back ? b.z0 - reach : b.z1 + reach;
            }
        }
    }

    /// Advances the walker by a frame of `dt` seconds: the frame time is capped at `maxFrame` and split into the
    /// fewest equal substeps of at most `maxDt` (0.12 s → 3 × 0.04 s), each a full `substep`.
    void step(float dt, Input input, ref const World world)
    {
        dt = min(dt, maxFrame);
        if (dt <= 0) return;
        const n = cast(int)(dt / maxDt - 1e-4f) + 1; // ceil(dt / maxDt), robust against rounding just above n
        foreach (i; 0 .. n) substep(dt / n, input, world);
    }

    /// One physics substep of `dt` (at most `maxDt`) seconds:
    /// 1. The horizontal velocity is the input direction (relative to yaw, diagonals normalised) times the walking or
    ///    running speed; there is no acceleration, and the same control applies in the air. A jump starts only from
    ///    the ground.
    /// 2. The move is done along x, then along z, each followed by `pushOut`, so the walker slides along walls.
    /// 3. The ground is the highest box top under the circle within `stepHeight` of the feet (0 if none). Feet below
    ///    it (a step up) snap onto it; otherwise gravity accelerates the walker down until it lands.
    /// 4. The position is clamped to the city rectangle plus `margin`.
    private void substep(float dt, Input input, ref const World world)
    {
        // 1. velocity: forward is (-sin yaw, -cos yaw) and right (cos yaw, -sin yaw) in the x/z plane
        float f = input.forward, r = input.right;
        const len = sqrt(f * f + r * r);
        if (len > 1)
        {
            f /= len;
            r /= len;
        }
        const speed = input.run ? runSpeed : walkSpeed;
        const s = sin(yaw), c = cos(yaw);
        const vx = (-s * f + c * r) * speed, vz = (-c * f - s * r) * speed;
        if (input.jump && onGround)
        {
            vy = jumpSpeed;
            onGround = false;
        }
        // 2. move axis by axis, so a wall stops only the component of the motion that runs into it
        x += vx * dt;
        pushOut(world, 0, vx);
        z += vz * dt;
        pushOut(world, 1, vz);
        // 3. vertical: step up, or fall / rise under gravity and land
        const ground = groundBelow(world, feet);
        if (feet < ground)
        {
            feet = ground;
            vy = 0;
            onGround = true;
        }
        else
        {
            vy -= gravity * dt;
            feet += vy * dt;
            if (feet <= ground)
            {
                feet = ground;
                vy = 0;
                onGround = true;
            }
            else onGround = false;
        }
        // 4. stay near the city
        x = clamp(x, -world.halfWidth - margin, world.halfWidth + margin);
        z = clamp(z, -world.halfDepth - margin, world.halfDepth + margin);
    }
}

// The tests use the same scenarios and numbers as the Java (WalkerTest) and Rust editions: a 20 × 20 city (walkable
// ±40) with hand-placed boxes, time advancing in 0.05 s steps. Yaw 0 faces -z, `east` (-π/2) faces +x.
version (unittest)
{
    private enum float tolerance = 1e-4f;
    private enum float east = -PI / 2;
    private immutable forwardOnly = Input(1, 0);

    private bool near(float a, float b) { return abs(a - b) < tolerance; }

    private World world(Box[] boxes...) { return World(boxes.dup, 10, 10); }

    /// Runs `steps` frames of 0.05 s with the same input.
    private void run(ref Walker w, Input input, ref const World world, int steps)
    {
        foreach (i; 0 .. steps) w.step(0.05f, input, world);
    }
}

// Walking into a wall slides along it: walking forward-right at yaw 0 into a long wall at x = 1..3 for 1 s stops x
// at the wall (1 - radius) while z keeps moving at 3 / √2 per second.
unittest
{
    auto w = world(Box(1, -5, 3, 5, 0, 5));
    auto p = Walker.spawn(0, 0, 0, w);
    run(p, Input(1, 1), w, 20);
    assert(near(p.x, 0.8f));
    assert(near(p.z, -3 / sqrt(2.0f)));
    assert(near(p.feet, 0));
}

// Stepping onto a 0.4 platform: walking east for 1 s from x = 0 onto a platform starting at x = 1 ends at x = 3,
// standing on it.
unittest
{
    auto w = world(Box(1, -5, 5, 5, 0, 0.4f));
    auto p = Walker.spawn(0, 0, east, w);
    run(p, forwardOnly, w, 20);
    assert(near(p.x, 3));
    assert(near(p.feet, 0.4f));
    assert(p.onGround);
}

// Blocked by a building taller than a step: the walker stays in front of it, on the ground.
unittest
{
    auto w = world(Box(1, -1, 2, 1, 0, 3));
    auto p = Walker.spawn(0, 0, east, w);
    run(p, forwardOnly, w, 20);
    assert(near(p.x, 0.8f));
    assert(near(p.z, 0));
    assert(near(p.feet, 0));
}

// Falling off a roof lands on the ground: standing on a 2 tall building (x, z = -1..1) and walking east, after 0.5 s
// the walker is at x = 1.5, past the edge (1.2) and falling; after another 1 s it is at x = 4.5 on the ground.
unittest
{
    auto w = world(Box(-1, -1, 1, 1, 0, 2));
    Walker p;
    p.feet = 2;
    p.yaw = east;
    run(p, forwardOnly, w, 10);
    assert(near(p.x, 1.5f));
    assert(!p.onGround);
    assert(p.feet < 2);
    run(p, forwardOnly, w, 20);
    assert(near(p.x, 4.5f));
    assert(near(p.feet, 0));
    assert(near(p.vy, 0));
    assert(p.onGround);
}

// Jumping only from the ground: the first step starts the jump (vy = 6.5 - 18 * 0.05 = 5.6, feet = 0.28); holding
// Space in the air does not restart it (vy = 4.7, feet = 0.515); it comes back down onto the ground.
unittest
{
    auto w = world();
    auto p = Walker.spawn(0, 0, 0, w);
    auto jump = Input(0, 0, false, true);
    p.step(0.05f, jump, w);
    assert(near(p.vy, 5.6f) && near(p.feet, 0.28f) && !p.onGround);
    p.step(0.05f, jump, w);
    assert(near(p.vy, 4.7f) && near(p.feet, 0.515f) && !p.onGround);
    run(p, Input(), w, 20);
    assert(near(p.feet, 0) && p.onGround);
}

// Spawn push-out: inside a 3 tall building (x, z = -1..1) at (0.7, 0.1), the shortest way out is past the +x side
// (0.5, against 1.1 / 1.3 for ±z and 1.9 for -x), to x = 1.2. A building no taller than a step is stood on instead.
unittest
{
    auto w = world(Box(-1, -1, 1, 1, 0, 3));
    auto p = Walker.spawn(0.7f, 0.1f, 0, w);
    assert(near(p.x, 1.2f) && near(p.z, 0.1f) && near(p.feet, 0) && p.onGround);
    auto low = world(Box(-1, -1, 1, 1, 0, 0.3f));
    auto q = Walker.spawn(0.7f, 0.1f, 0, low);
    assert(near(q.x, 0.7f) && near(q.feet, 0.3f));
}

// Clamping at the margin: in a 20 × 20 city the walker cannot go beyond ±(10 + 30) = ±40.
unittest
{
    auto w = world();
    auto p = Walker.spawn(39, 0, east, w);
    run(p, forwardOnly, w, 20);
    assert(near(p.x, 40));
    auto q = Walker.spawn(0, -39, 0, w);
    run(q, forwardOnly, w, 20);
    assert(near(q.z, -40));
}

// A stalled frame (dt = 1) moves exactly `maxFrame` (0.25 s) worth: 0.75 units at walking speed.
unittest
{
    auto w = world();
    auto p = Walker.spawn(0, 0, 0, w);
    p.step(1, forwardOnly, w);
    assert(near(p.z, -0.75f));
}

// A 0.12 s frame is 3 substeps of 0.04 s: jumping and walking through it gives the same result as three 0.04 s
// frames.
unittest
{
    auto w = world(Box(1, -5, 5, 5, 0, 0.4f));
    auto a = Walker.spawn(0, 0, east, w), b = a;
    auto input = Input(1, 0, false, true);
    a.step(0.12f, input, w);
    foreach (i; 0 .. 3) b.step(0.04f, input, w);
    assert(near(a.x, b.x) && near(a.z, b.z) && near(a.feet, b.feet) && near(a.vy, b.vy) && a.onGround == b.onGround);
    assert(near(a.x, 0.36f)); // 3 × 0.04 × 3
}

// Mouse look: yaw is unlimited, pitch stops at ±85°.
unittest
{
    Walker p;
    p.look(0, -10_000);
    assert(near(p.pitch, pitchLimit));
    p.look(0, 20_000);
    assert(near(p.pitch, -pitchLimit));
    p.look(-100, 0);
    assert(near(p.yaw, 0.25f));
}

// Mouse and keys work like a shooter: mouse right turns the view clockwise seen from above (from -z towards +x), mouse
// up looks up; D for 1 s moves along the camera's right (forward × up, as in `Mat4.lookAt`), W along the view.
unittest
{
    auto w = world();
    auto p = Walker.spawn(0, 0, 0, w);
    p.look(100, -100);
    assert(near(p.yaw, -0.25f) && near(p.pitch, 0.25f));
    const f = p.forward;
    assert(f.x > 0 && f.y > 0 && f.z < 0);
    const flat = Vec3(-sin(p.yaw), 0, -cos(p.yaw));
    const right = flat.cross(Vec3(0, 1, 0));
    run(p, Input(0, 1), w, 20);
    assert(near(p.x, 3 * right.x) && near(p.z, 3 * right.z));
    auto q = Walker.spawn(0, 0, p.yaw, w);
    run(q, Input(1, 0), w, 20);
    assert(near(q.x, 3 * flat.x) && near(q.z, 3 * flat.z));
}
