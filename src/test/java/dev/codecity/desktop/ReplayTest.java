package dev.codecity.desktop;

import dev.codecity.CityModel.Building;
import dev.codecity.history.Snapshot;
import dev.codecity.history.TypeHistory;
import dev.codecity.layout.CityLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the history replay's playback and arithmetic ({@link Replay}). Rust's {@code replay.rs} tests and D's
 * {@code replay.d} check the same scenarios with the same numbers (Rust and D start a replay playing; here
 * {@code DesktopApp} calls {@link Replay#play} right after creating one, so the tests do too).
 */
class ReplayTest {

    private static final double EPS = 1e-9;

    /** A replay over {@code count} snapshots, started like the viewer starts one. */
    private static Replay started(int count) {
        Replay r = new Replay(count);
        r.play();
        return r;
    }

    @Test
    void playbackStepsAndStopsAtTheEnd() {
        Replay r = started(5);
        r.advance(0.4);
        assertEquals(0.5, r.position());
        assertEquals(1, r.snapshot());
        assertEquals(0, r.a());
        assertEquals(1, r.b());
        assertEquals(0.5, r.f());
        r.advance(10);
        assertEquals(4, r.position());
        assertFalse(r.playing());
        assertEquals(4, r.a());
        assertEquals(4, r.b());
        r.togglePlay();
        assertEquals(0, r.position());
        assertTrue(r.playing());
        r.seek(2.3);
        r.step(-1);
        assertEquals(2, r.position());
        assertFalse(r.playing());
        r.step(-1);
        assertEquals(1, r.position());
        r.seek(2.3);
        r.step(1);
        assertEquals(3, r.position());
        for (String label : new String[]{"2×", "4×", "0.5×", "1×"}) {
            r.cycleSpeed();
            assertEquals(label, r.speedLabel());
        }
    }

    @Test
    void speedsCycleAndScaleThePlaybackRate() {
        Replay r = started(10);
        assertEquals("1×", r.speedLabel());
        r.advance(0.8);
        assertEquals(1, r.position(), EPS);
        r.cycleSpeed();
        r.advance(0.8);
        assertEquals(3, r.position(), EPS);
        r.cycleSpeed();
        r.advance(0.2);
        assertEquals(4, r.position(), EPS);
        r.cycleSpeed();
        assertEquals("0.5×", r.speedLabel());
        r.advance(1.6);
        assertEquals(5, r.position(), EPS);
    }

    @Test
    void pausedPlaybackStandsStillAndSeekingClamps() {
        Replay r = started(5);
        r.togglePlay();
        assertFalse(r.playing());
        assertFalse(r.advance(3));
        assertEquals(0, r.position());
        r.seek(99);
        assertEquals(4, r.position());
        assertFalse(r.playing());
        r.step(1);
        assertEquals(4, r.position());
        r.seek(0);
        r.step(-1);
        assertEquals(0, r.position());
        r.seek(2);
        r.step(1);
        assertEquals(3, r.position());
        r.step(-1);
        assertEquals(2, r.position());
    }

    @Test
    void nearestSnapshotRoundsHalvesUp() {
        Replay r = started(4);
        double[][] cases = {{0, 0, 0, 1, 0}, {0.49, 0, 0, 1, 0.49}, {0.5, 1, 0, 1, 0.5}, {2.5, 3, 2, 3, 0.5}, {3, 3, 3, 3, 0}};
        for (double[] c : cases) {
            r.seek(c[0]);
            assertEquals((int) c[1], r.snapshot(), "p = " + c[0]);
            assertEquals((int) c[2], r.a());
            assertEquals((int) c[3], r.b());
            assertEquals(c[4], r.f(), EPS);
        }
    }

    @Test
    void aSingleSnapshotNeverPlays() {
        Replay r = started(1);
        r.advance(0.1);
        assertEquals(0, r.position());
        assertFalse(r.playing());
        r.togglePlay();
        r.advance(0.1);
        assertEquals(0, r.position());
        assertFalse(r.playing());
        assertEquals(0, r.snapshot());
        assertEquals(1, new Replay(0).count());
    }

    @Test
    void interpolatesAbsentAsZeroAndHidesWhenAbsentOnBothSides() {
        assertEquals(1, Replay.lerp(-1, 4, 0.25));
        assertEquals(3, Replay.lerp(4, 2, 0.5));
        assertTrue(Double.isNaN(Replay.lerp(-1, -1, 0)));
        assertEquals(0, Replay.side(-1));
        assertEquals(CityLayout.side(0), Replay.side(0));
        assertEquals(CityLayout.side(4), Replay.side(4));
    }

    @Test
    void colourValuesComeFromTheSnapshot() {
        TypeHistory h = new TypeHistory(3);
        System.arraycopy(new int[]{-1, 0, 4}, 0, h.nom, 0, 3);
        System.arraycopy(new int[]{-1, 3, 10}, 0, h.cx, 0, 3);
        System.arraycopy(new int[]{-1, 7, 20}, 0, h.loc, 0, 3);
        System.arraycopy(new int[]{0, 2, 5}, 0, h.commits, 0, 3);
        System.arraycopy(new int[]{0, 1, 2}, 0, h.authors, 0, 3);
        System.arraycopy(new long[]{-1, 1_000, 1_000 + 3 * 86_400}, 0, h.last, 0, 3);
        Building b = new Building(0, null, null, "", null, new int[0], new int[0], new int[0], List.of(), h, false);
        long at = 1_000 + 10 * 86_400;
        assertEquals(5, Look.ColorMetric.COMMITS.replayValue(b, 2, at));
        // Open pull requests only exist today: churn stands in.
        assertEquals(2, Look.ColorMetric.PRS.replayValue(b, 1, at));
        assertEquals(2, Look.ColorMetric.AUTHORS.replayValue(b, 2, at));
        assertEquals(7, Look.ColorMetric.AGE.replayValue(b, 2, at));
        assertEquals(Double.POSITIVE_INFINITY, Look.ColorMetric.AGE.replayValue(b, 0, at));
        assertEquals(0, Look.ColorMetric.AGE.replayValue(b, 2, 0));
        assertEquals(0, Look.ColorMetric.CX.replayValue(b, 0, at));
        assertEquals(0, Look.ColorMetric.DENSITY.replayValue(b, 1, at));
        assertEquals(2.5, Look.ColorMetric.DENSITY.replayValue(b, 2, at));
        assertEquals(20, Look.ColorMetric.LOC.replayValue(b, 2, at));
        assertEquals(0, Look.ColorMetric.KIND.replayValue(b, 2, at));
    }

    @Test
    void captionCutsLongSubjectsByCharacters() {
        String tail = " · abcdef1 · fix it — Ann";
        String s = Replay.caption(new Snapshot("abcdef1234", 1_790_769_600L, "Ann", "fix it", 1));
        assertTrue(s.endsWith(tail), s);
        assertEquals(10, s.length() - tail.length());
        String sixty = "x".repeat(60);
        assertTrue(Replay.caption(new Snapshot("abcdef1234", 0, "Ann", sixty, 1)).contains(" · " + sixty + " — "));
        // 61 characters outside the BMP: the first 59 and an ellipsis, never half a character.
        String emoji = Replay.caption(new Snapshot("abcdef1234", 0, "Ann", "😀".repeat(61), 1));
        assertTrue(emoji.endsWith(" · " + "😀".repeat(59) + "… — Ann"), emoji);
    }
}
