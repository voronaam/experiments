package dev.codecity.desktop;

import dev.codecity.CityModel;
import dev.codecity.CityModel.Building;
import dev.codecity.layout.CityLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Movement and collision for walk mode, the first-person way of exploring the city. This class knows nothing about
 * JavaFX: {@link CityView3D} feeds it the keys and mouse motion and copies {@link #x}, {@link #eyeY()}, {@link #z},
 * {@link #yaw} and {@link #pitch} into the camera every frame. The browser, Rust and D viewers implement the same rules
 * with the same constants, so walking feels identical everywhere.
 *
 * <h2>Coordinates</h2>
 * Layout coordinates with y up (unlike the JavaFX scene, where y points down). The player is a vertical cylinder of
 * {@link #RADIUS} standing with its feet at {@link #feet}; the eye is {@link #EYE_HEIGHT} above the feet. A yaw of 0
 * looks towards −z, and the yaw grows counter-clockwise seen from above (turning left); a positive pitch looks up.
 *
 * <h2>Physics</h2>
 * The world is a list of axis-aligned {@link Box solids}: every district platform (from y = 0 up to its top) and every
 * visible building. One {@link #step} moves the player along x, then along z (so walls are slid along rather than
 * stopping the player dead), pushing back out of boxes too tall to step onto, then resolves the vertical: step up onto
 * low ledges, otherwise fall under gravity until landing. There is no acceleration: the input sets the horizontal
 * velocity directly, on the ground and in the air alike.
 */
final class Walker {

    /** Eye height above the feet. */
    static final double EYE_HEIGHT = 0.6;
    /** Radius of the player's circle on the ground plane. */
    static final double RADIUS = 0.2;
    /** Walking and running speed (Shift held), in units per second. */
    static final double WALK_SPEED = 3.0;
    static final double RUN_SPEED = 7.5;
    /** Ledges up to this height above the feet are climbed automatically; higher ones block. */
    static final double STEP_HEIGHT = 0.45;
    /** Downward acceleration (units/s²) and the upward speed of a jump (units/s). */
    static final double GRAVITY = 18;
    static final double JUMP_SPEED = 6.5;
    /** Look rotation per pixel of mouse motion, in radians. */
    static final double MOUSE_SENS = 0.0025;
    /** How far up or down the player can look. */
    static final double PITCH_LIMIT = Math.toRadians(85);
    /** Vertical field of view (degrees) and near clipping plane while walking. */
    static final double FOV = 70;
    static final double NEAR = 0.05;
    /** How far beyond the city rectangle the player may walk; the ground out there is y = 0. */
    static final double MARGIN = 30;
    /** Longest physics substep, so a long frame cannot shoot the player through a wall. */
    static final double MAX_DT = 0.05;
    /** Longest frame time simulated at all; anything beyond (a stall, a dragged window) is dropped. */
    static final double MAX_FRAME = 0.25;
    /** Extra distance added when pushing out of a box, so the circle ends up clear of it despite rounding. */
    private static final double EPS = 1e-6;

    /**
     * What the player wants to do this frame. {@code forward} and {@code right} are −1, 0 or 1 (W/S and D/A);
     * {@code run} is Shift and {@code jump} is Space, both as long as they are held.
     */
    record Input(double forward, double right, boolean run, boolean jump) {
        static final Input NONE = new Input(0, 0, false, false);
    }

    /**
     * A solid: footprint {@code x0..x1} × {@code z0..z1}, from height {@code y0} up to {@code y1}. Exactly one of
     * {@code building} and {@code district} is an index into the model, the other is -1; they are only used to report
     * what the crosshair points at.
     */
    record Box(int building, int district, double x0, double z0, double x1, double z1, double y0, double y1) {
    }

    /**
     * Everything the player can collide with, plus the walkable area: the city rectangle
     * ({@code ±halfWidth} × {@code ±halfDepth}, the city being centred on the origin) widened by {@link #MARGIN}.
     */
    record World(List<Box> boxes, double halfWidth, double halfDepth) {

        /**
         * The solids of {@code model} with the buildings at their current, possibly animating, {@code heights}.
         * Buildings of (almost) zero height are hidden or filtered out and are left out, as they are when drawn.
         */
        static World of(CityModel model, double[] heights) {
            return of(model, heights, null, null);
        }

        /**
         * {@link #of(CityModel, double[])} for a history replay frame: footprints of {@code sides[i]} centred on the
         * lots instead of the lots' own sides, and only the districts with {@code districtShown[d]}. Either may be
         * null for the present-day city.
         */
        static World of(CityModel model, double[] heights, double[] sides, boolean[] districtShown) {
            List<Box> boxes = new ArrayList<>();
            List<CityLayout.District> districts = model.districts();
            for (int i = 0; i < districts.size(); i++) {
                if (districtShown != null && !districtShown[i]) {
                    continue;
                }
                CityLayout.District d = districts.get(i);
                boxes.add(new Box(-1, i, d.x() - d.w() / 2, d.z() - d.d() / 2, d.x() + d.w() / 2, d.z() + d.d() / 2,
                        0, (d.depth() + 1) * CityLayout.PLATFORM));
            }
            for (Building b : model.buildings()) {
                double h = b.index() < heights.length ? heights[b.index()] : 0;
                if (h <= 0.001) {
                    continue;
                }
                CityLayout.Lot lot = b.lot();
                double s = (sides != null ? sides[b.index()] : lot.side()) / 2;
                boxes.add(new Box(b.index(), -1, lot.x() - s, lot.z() - s, lot.x() + s, lot.z() + s,
                        lot.y(), lot.y() + h));
            }
            return new World(boxes, model.width() / 2, model.depth() / 2);
        }
    }

    /** Position of the player's centre on the ground plane and height of the feet. */
    double x;
    double z;
    double feet;
    /** Vertical speed (units/s, up positive) and whether the feet rest on something. */
    double vy;
    boolean onGround;
    /** View direction in radians, see the class comment. */
    double yaw;
    double pitch;

    /** Height of the eye, where the camera goes. */
    double eyeY() {
        return feet + EYE_HEIGHT;
    }

    /**
     * Places the player at {@code (x, z)} looking horizontally along {@code yaw}, standing still on the ground. If
     * that spot is inside a building too tall to stand on (taller than {@link #STEP_HEIGHT} above the platform there),
     * the player is pushed out to the nearest side: the axis with the smallest penetration. That is repeated a few
     * times in case the push lands inside a neighbour.
     */
    void spawn(double x, double z, double yaw, World world) {
        this.x = x;
        this.z = z;
        this.yaw = yaw;
        this.pitch = 0;
        this.vy = 0;
        for (int pass = 0; pass < 8; pass++) {
            double floor = platformHeight(world);
            Box hit = null;
            for (Box b : world.boxes()) {
                if (b.building() >= 0 && b.y1() > floor + STEP_HEIGHT && overlaps(b)) {
                    hit = b;
                    break;
                }
            }
            if (hit == null) {
                break;
            }
            // Distance to move to clear each side of the box grown by the radius; take the shortest.
            double left = this.x - (hit.x0() - RADIUS);
            double right = hit.x1() + RADIUS - this.x;
            double front = this.z - (hit.z0() - RADIUS);
            double back = hit.z1() + RADIUS - this.z;
            double min = Math.min(Math.min(left, right), Math.min(front, back));
            if (min == left) {
                this.x = hit.x0() - RADIUS - EPS;
            } else if (min == right) {
                this.x = hit.x1() + RADIUS + EPS;
            } else if (min == front) {
                this.z = hit.z0() - RADIUS - EPS;
            } else {
                this.z = hit.z1() + RADIUS + EPS;
            }
        }
        feet = groundHeight(world, platformHeight(world));
        onGround = true;
    }

    /** Turns the view by a mouse motion in pixels: right turns right, up looks up. Pitch is clamped. */
    void look(double dx, double dy) {
        yaw -= dx * MOUSE_SENS;
        pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, pitch - dy * MOUSE_SENS));
    }

    /**
     * Advances the simulation by one frame of {@code dt} seconds. The frame time is capped at {@link #MAX_FRAME} and
     * split into equal substeps of at most {@link #MAX_DT}, so a slow renderer still walks at full speed while each
     * collision step stays short.
     */
    void step(double dt, Input in, World world) {
        double frame = Math.min(Math.max(dt, 0), MAX_FRAME);
        // The small tolerance keeps an exact multiple (0.25 / 0.05) from rounding up to one substep too many.
        int n = (int) Math.ceil(frame / MAX_DT - 1e-9);
        for (int i = 0; i < n; i++) {
            substep(frame / n, in, world);
        }
    }

    /** One physics substep of {@code dt} ≤ {@link #MAX_DT}; the numbered comments follow the shared walk-mode spec. */
    private void substep(double dt, Input in, World world) {
        // 1. Horizontal velocity: the input direction relative to the yaw, normalised so diagonals are not faster.
        //    Forward is (−sin yaw, −cos yaw) and right is (cos yaw, −sin yaw).
        double f = in.forward();
        double r = in.right();
        double len = Math.hypot(f, r);
        double vx = 0;
        double vz = 0;
        if (len > 0) {
            double speed = (in.run() ? RUN_SPEED : WALK_SPEED) / len;
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            vx = (-sin * f + cos * r) * speed;
            vz = (-cos * f - sin * r) * speed;
        }

        // 2. Move one axis at a time and push back out of walls along that axis.
        double oldX = x;
        x += vx * dt;
        for (Box b : world.boxes()) {
            if (blocks(b) && overlaps(b)) {
                double reach = Math.sqrt(RADIUS * RADIUS - sq(gap(z, b.z0(), b.z1()))) + EPS;
                x = pushLow(oldX, x, b.x0(), b.x1()) ? b.x0() - reach : b.x1() + reach;
            }
        }
        double oldZ = z;
        z += vz * dt;
        for (Box b : world.boxes()) {
            if (blocks(b) && overlaps(b)) {
                double reach = Math.sqrt(RADIUS * RADIUS - sq(gap(x, b.x0(), b.x1()))) + EPS;
                z = pushLow(oldZ, z, b.z0(), b.z1()) ? b.z0() - reach : b.z1() + reach;
            }
        }

        // A jump starts only from the ground; gravity below then carries it.
        if (in.jump() && onGround) {
            vy = JUMP_SPEED;
            onGround = false;
        }

        // 3. Vertical: snap up onto a ledge we stepped onto (that counts as standing on it, even mid-jump), otherwise
        // fall (or rise) and land.
        double ground = groundHeight(world, feet);
        if (feet < ground) {
            feet = ground;
            vy = 0;
            onGround = true;
        } else {
            vy -= GRAVITY * dt;
            feet += vy * dt;
            if (feet <= ground) {
                feet = ground;
                vy = 0;
                onGround = true;
            } else {
                onGround = false;
            }
        }

        // 4. Stay within the city rectangle plus the margin.
        double mx = world.halfWidth() + MARGIN;
        double mz = world.halfDepth() + MARGIN;
        x = Math.max(-mx, Math.min(mx, x));
        z = Math.max(-mz, Math.min(mz, z));
    }

    /**
     * What the crosshair points at: the nearest box hit by the view ray from the eye, or null. Platforms are tested
     * with their drawn thickness ({@link CityLayout#PLATFORM}) rather than as solids down to the ground, so a ray
     * striking a kerb reports the district actually drawn there, not a nested one.
     */
    Box target(World world) {
        double cp = Math.cos(pitch);
        double[] o = {x, eyeY(), z};
        double[] d = {-Math.sin(yaw) * cp, Math.sin(pitch), -Math.cos(yaw) * cp};
        Box best = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (Box b : world.boxes()) {
            double y0 = b.district() >= 0 ? b.y1() - CityLayout.PLATFORM : b.y0();
            double[] lo = {b.x0(), y0, b.z0()};
            double[] hi = {b.x1(), b.y1(), b.z1()};
            // Slab test: intersect the ray's parameter ranges inside each pair of parallel faces.
            double tNear = Double.NEGATIVE_INFINITY;
            double tFar = Double.POSITIVE_INFINITY;
            boolean miss = false;
            for (int a = 0; a < 3 && !miss; a++) {
                if (Math.abs(d[a]) < 1e-12) {
                    miss = o[a] < lo[a] || o[a] > hi[a];
                    continue;
                }
                double t1 = (lo[a] - o[a]) / d[a];
                double t2 = (hi[a] - o[a]) / d[a];
                tNear = Math.max(tNear, Math.min(t1, t2));
                tFar = Math.min(tFar, Math.max(t1, t2));
                miss = tNear > tFar;
            }
            // Only boxes in front of the eye; one the eye is inside of (tNear < 0) is ignored.
            if (!miss && tNear > 0 && tNear < bestT) {
                bestT = tNear;
                best = b;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ helpers

    /** A box walls the player off if it rises more than a step above the feet and starts below the eye. */
    private boolean blocks(Box b) {
        return b.y1() > feet + STEP_HEIGHT && b.y0() < feet + EYE_HEIGHT;
    }

    /** Circle-vs-rectangle: whether the player's circle overlaps the box's footprint (touching does not count). */
    private boolean overlaps(Box b) {
        return sq(gap(x, b.x0(), b.x1())) + sq(gap(z, b.z0(), b.z1())) < RADIUS * RADIUS;
    }

    /** Distance from {@code v} to the interval {@code lo..hi}, 0 inside it. */
    private static double gap(double v, double lo, double hi) {
        return Math.max(Math.max(lo - v, v - hi), 0);
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * Which side to push out to along one axis: the side the player came from, or, when the player was already inside
     * the interval before the move (a building grew around them), the nearer half.
     */
    private static boolean pushLow(double before, double now, double lo, double hi) {
        if (before <= lo) {
            return true;
        }
        if (before >= hi) {
            return false;
        }
        return now < (lo + hi) / 2;
    }

    /** Height of the highest platform under the player's circle, 0 off the city. */
    private double platformHeight(World world) {
        double top = 0;
        for (Box b : world.boxes()) {
            if (b.district() >= 0 && overlaps(b)) {
                top = Math.max(top, b.y1());
            }
        }
        return top;
    }

    /** Highest top under the circle that can be stood on from {@code from}: at most a step above it; 0 if none. */
    private double groundHeight(World world, double from) {
        double top = 0;
        for (Box b : world.boxes()) {
            if (b.y1() <= from + STEP_HEIGHT && overlaps(b)) {
                top = Math.max(top, b.y1());
            }
        }
        return top;
    }
}
