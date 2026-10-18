package dev.codecity.desktop;

import dev.codecity.desktop.Walker.Box;
import dev.codecity.desktop.Walker.Input;
import dev.codecity.desktop.Walker.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walk-mode physics. The same scenarios with the same numbers are tested in the Rust and D viewers. Worlds are a
 * 20 × 20 city (so the walkable area is ±40) with hand-placed boxes; time mostly advances in 0.05 s frames.
 */
class WalkerTest {

    private static final double TOL = 1e-4;
    /** Yaw that faces +x (forward = (−sin yaw, −cos yaw)). */
    private static final double EAST = -Math.PI / 2;
    private static final Input FORWARD = new Input(1, 0, false, false);

    private static Box building(double x0, double z0, double x1, double z1, double y0, double y1) {
        return new Box(0, -1, x0, z0, x1, z1, y0, y1);
    }

    private static World world(Box... boxes) {
        return new World(List.of(boxes), 10, 10);
    }

    private static Walker at(double x, double z, double yaw, World w) {
        Walker p = new Walker();
        p.spawn(x, z, yaw, w);
        return p;
    }

    private static void run(Walker p, Input in, World w, int steps) {
        for (int i = 0; i < steps; i++) {
            p.step(0.05, in, w);
        }
    }

    @Test
    void slidesAlongAWall() {
        // A long wall at x = 1..3; walking forward-right (north-east at yaw 0) for 1 s keeps the z motion.
        World w = world(building(1, -5, 3, 5, 0, 5));
        Walker p = at(0, 0, 0, w);
        run(p, new Input(1, 1, false, false), w, 20);
        assertEquals(0.8, p.x, TOL);
        assertEquals(-3 / Math.sqrt(2), p.z, TOL);
        assertEquals(0, p.feet, TOL);
    }

    @Test
    void stepsOntoAPlatform() {
        World w = world(new Box(-1, 0, 1, -5, 5, 5, 0, 0.4));
        Walker p = at(0, 0, EAST, w);
        run(p, FORWARD, w, 20);
        assertEquals(3.0, p.x, TOL);
        assertEquals(0.4, p.feet, TOL);
        assertTrue(p.onGround);
    }

    @Test
    void blockedByATallBuilding() {
        World w = world(building(1, -1, 2, 1, 0, 3));
        Walker p = at(0, 0, EAST, w);
        run(p, FORWARD, w, 20);
        assertEquals(0.8, p.x, TOL);
        assertEquals(0, p.z, TOL);
        assertEquals(0, p.feet, TOL);
    }

    @Test
    void fallingOffARoofLandsOnTheGround() {
        World w = world(building(-1, -1, 1, 1, 0, 2));
        Walker p = new Walker();
        p.x = 0;
        p.z = 0;
        p.feet = 2;
        p.onGround = true;
        p.yaw = EAST;
        run(p, FORWARD, w, 10); // 0.5 s: x = 1.5, already off the roof edge (1.2) and falling
        assertEquals(1.5, p.x, TOL);
        assertFalse(p.onGround);
        assertTrue(p.feet < 2);
        run(p, FORWARD, w, 20);
        assertEquals(4.5, p.x, TOL);
        assertEquals(0, p.feet, TOL);
        assertEquals(0, p.vy, TOL);
        assertTrue(p.onGround);
    }

    @Test
    void jumpsOnlyFromTheGround() {
        World w = world();
        Walker p = at(0, 0, 0, w);
        Input jump = new Input(0, 0, false, true);
        p.step(0.05, jump, w);
        assertEquals(5.6, p.vy, TOL); // 6.5 - 18 * 0.05
        assertEquals(0.28, p.feet, TOL);
        assertFalse(p.onGround);
        p.step(0.05, jump, w); // still held in the air: no second jump
        assertEquals(4.7, p.vy, TOL);
        assertEquals(0.515, p.feet, TOL);
        run(p, Input.NONE, w, 20);
        assertEquals(0, p.feet, TOL);
        assertTrue(p.onGround);
    }

    @Test
    void spawnPushesOutOfABuilding() {
        World w = world(building(-1, -1, 1, 1, 0, 3));
        Walker p = at(0.7, 0.1, 0, w);
        assertEquals(1.2, p.x, TOL); // shortest way out is +x (0.5) rather than ±z (1.1, 1.3) or -x (1.9)
        assertEquals(0.1, p.z, TOL);
        assertEquals(0, p.feet, TOL);
        assertTrue(p.onGround);

        // A building no taller than a step is stood on instead.
        World low = world(building(-1, -1, 1, 1, 0, 0.3));
        Walker q = at(0.7, 0.1, 0, low);
        assertEquals(0.7, q.x, TOL);
        assertEquals(0.3, q.feet, TOL);
    }

    @Test
    void clampsAtTheMargin() {
        World w = world();
        Walker p = at(39, 0, EAST, w);
        run(p, FORWARD, w, 20);
        assertEquals(40, p.x, TOL);
        Walker q = at(0, -39, 0, w);
        run(q, FORWARD, w, 20);
        assertEquals(-40, q.z, TOL);
    }

    @Test
    void longFramesAreCappedAtMaxFrame() {
        World w = world();
        Walker p = at(0, 0, EAST, w);
        p.step(1.0, FORWARD, w); // only 0.25 s of it is simulated
        assertEquals(0.75, p.x, TOL);
    }

    @Test
    void framesAreSplitIntoEqualSubsteps() {
        // A 0.12 s frame runs as three 0.04 s substeps, exactly like three 0.04 s frames.
        World w = world(new Box(-1, 0, 0.3, -5, 5, 5, 0, 0.4));
        Input in = new Input(1, 0, false, true);
        Walker a = at(0, 0, EAST, w);
        Walker b = at(0, 0, EAST, w);
        a.step(0.12, in, w);
        for (int i = 0; i < 3; i++) {
            b.step(0.04, in, w);
        }
        assertEquals(b.x, a.x, 1e-12);
        assertEquals(b.feet, a.feet, 1e-12);
        assertEquals(b.vy, a.vy, 1e-12);
        assertEquals(0.36, a.x, TOL);
    }

    @Test
    void crosshairHitsTheNearestBox() {
        World w = new World(List.of(new Box(7, -1, -1, -6, 1, -4, 0, 3), new Box(8, -1, -1, -12, 1, -10, 0, 3)), 10, 10);
        Walker p = at(0, 0, 0, w);
        assertEquals(7, p.target(w).building());
        p.yaw = Math.PI; // facing +z: nothing there
        assertEquals(null, p.target(w));
    }
}
