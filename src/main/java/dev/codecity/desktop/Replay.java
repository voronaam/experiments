package dev.codecity.desktop;

import dev.codecity.layout.CityLayout;

/**
 * Playback state of the history replay: a continuous position over the snapshots, play/pause and speed. Like
 * {@link Walker} it knows nothing about JavaFX; {@code DesktopApp} feeds it frame times and key presses and turns the
 * position into heights, footprints and colours. The browser, Rust and D viewers follow the same rules with the same
 * numbers, so a replay plays identically everywhere.
 *
 * <h2>Position</h2>
 * {@link #position()} {@code p} runs from 0 to {@code count - 1}. A frame shows snapshot {@code a = floor(p)} blended
 * into {@code b = min(a + 1, count - 1)} by {@code f = p - a} ({@link #lerp}); discrete things (colours, districts,
 * which buildings count as visible) use the nearest snapshot, {@link #snapshot()}, rounding halves up.
 */
final class Replay {

    /** Seconds per snapshot at 1×. */
    static final double SECONDS_PER_SNAPSHOT = 0.8;
    /** The speeds the speed button cycles through, in order. */
    static final double[] SPEEDS = {1, 2, 4, 0.5};

    private final int count;
    private double p;
    private boolean playing;
    private int speed;

    /** A replay over {@code count} snapshots (at least 1), at snapshot 0, paused, at 1×. */
    Replay(int count) {
        this.count = Math.max(1, count);
    }

    int count() {
        return count;
    }

    double position() {
        return p;
    }

    boolean playing() {
        return playing;
    }

    double speed() {
        return SPEEDS[speed];
    }

    /** Moves to the next speed: 1× → 2× → 4× → 0.5× → 1×. */
    void cycleSpeed() {
        speed = (speed + 1) % SPEEDS.length;
    }

    /** The snapshot the frame starts from, {@code floor(p)}. */
    int a() {
        return (int) Math.floor(p);
    }

    /** The snapshot the frame blends into, {@code min(a + 1, count - 1)}. */
    int b() {
        return Math.min(a() + 1, count - 1);
    }

    /** How far the frame is from {@link #a()} towards {@link #b()}, 0..1. */
    double f() {
        return p - a();
    }

    /** The nearest snapshot, {@code round(p)} with halves rounded up. */
    int snapshot() {
        return Math.min(count - 1, (int) Math.floor(p + 0.5));
    }

    /**
     * Advances a playing replay by {@code dt} seconds of wall time: {@code dt · speed / 0.8} snapshots. Reaching the
     * last snapshot stops there and pauses. Returns whether the position changed.
     */
    boolean advance(double dt) {
        if (!playing) {
            return false;
        }
        p = Math.min(count - 1, p + dt * speed() / SECONDS_PER_SNAPSHOT);
        if (p >= count - 1) {
            playing = false;
        }
        return true;
    }

    /** Starts playing, from the beginning if the position is at the end; or pauses. */
    void togglePlay() {
        if (playing) {
            playing = false;
        } else {
            play();
        }
    }

    /** Starts playing; at the end it restarts from snapshot 0. */
    void play() {
        if (p >= count - 1) {
            p = 0;
        }
        playing = count > 1;
    }

    /** Jumps to {@code position} (clamped to the snapshots) and pauses; the slider and Home/End use this. */
    void seek(double position) {
        p = Math.max(0, Math.min(count - 1, position));
        playing = false;
    }

    /**
     * Steps to the previous ({@code direction < 0}) or next whole snapshot and pauses. From between two snapshots it
     * goes to the nearer whole one in that direction, i.e. the one just before or after the current position.
     */
    void step(int direction) {
        seek(direction < 0 ? Math.ceil(p) - 1 : Math.floor(p) + 1);
    }

    /**
     * A metric between two snapshots: the linear blend of {@code va} and {@code vb} by {@code f}, an absent value
     * (-1) counting as 0. NaN when both are absent, meaning the building is not shown at all.
     */
    static double lerp(int va, int vb, double f) {
        if (va < 0 && vb < 0) {
            return Double.NaN;
        }
        double a = Math.max(0, va);
        double b = Math.max(0, vb);
        return a + (b - a) * f;
    }

    /** Footprint side for an attribute count with the layout's formula; 0 for an absent type (-1). */
    static double side(int noa) {
        return noa < 0 ? 0 : CityLayout.side(noa);
    }
}
