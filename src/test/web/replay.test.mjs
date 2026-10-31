// History-replay arithmetic of the browser viewer (replay.js). The first two tests are the scenarios of the desktop
// viewers' replay tests (rust/src/gui/replay.rs: playback_steps_and_stops_at_the_end,
// interpolates_absent_as_zero_and_hides_when_absent_on_both_sides) with the same numbers, so the viewers replay a
// city identically. Run with `node --test src/test/web` (scripts/test-web.sh).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as Replay from '../../main/resources/web/replay.js';

/** Asserts |actual − expected| ≤ 1e-12. */
function near(actual, expected) {
  assert.ok(Math.abs(actual - expected) <= 1e-12, `expected ${expected}, got ${actual}`);
}

/** A building track over three snapshots with nom/noa as given; everything else absent (−1) or 0. */
function building(nom, noa, extra = {}) {
  const n = nom.length;
  return {
    hist: {
      nom, noa, loc: nom.map((v) => (v < 0 ? -1 : v * 10)), cx: nom.map((v) => (v < 0 ? -1 : v * 2)),
      commits: Array(n).fill(0), authors: Array(n).fill(0), last: Array(n).fill(-1),
    },
    ...extra,
  };
}

test('playback steps and stops at the end', () => {
  const rp = Replay.start(5);
  assert.deepEqual(rp, { count: 5, p: 0, playing: true, speed: 0 });
  // 0.4 s, in two frames: the browser caps each frame at MAX_FRAME (0.25 s).
  Replay.play(rp, 0.2);
  Replay.play(rp, 0.2);
  near(rp.p, 0.5);
  rp.p = 0.5;
  assert.equal(Replay.frame(rp.p, rp.count).r, 1);
  assert.deepEqual(Replay.frame(rp.p, rp.count), { a: 0, b: 1, f: 0.5, r: 1 });
  // A long wait is likewise played as several capped frames.
  for (let i = 0; i < 20; i++) Replay.play(rp, 10);
  assert.equal(rp.p, 4);
  assert.equal(rp.playing, false);
  assert.deepEqual(Replay.frame(rp.p, rp.count), { a: 4, b: 4, f: 0, r: 4 });
  Replay.togglePlaying(rp);
  assert.equal(rp.p, 0);
  assert.equal(rp.playing, true);
  rp.p = 2.3;
  Replay.seek(rp, Replay.stepPosition(rp.p, -1, rp.count));
  assert.equal(rp.p, 2);
  assert.equal(rp.playing, false);
  Replay.seek(rp, Replay.stepPosition(rp.p, -1, rp.count));
  assert.equal(rp.p, 1);
  rp.p = 2.3;
  Replay.seek(rp, Replay.stepPosition(rp.p, 1, rp.count));
  assert.equal(rp.p, 3);
  for (const label of ['2×', '4×', '0.5×', '1×']) {
    Replay.nextSpeed(rp);
    assert.equal(Replay.speedLabel(rp.speed), label);
  }
});

test('interpolates absent as zero and hides when absent on both sides', () => {
  const nom = [-1, 4, 2];
  const noa = [-1, 4, 0];
  assert.equal(Replay.lerpMetric(nom, { a: 0, b: 1, f: 0.25 }), 1);
  assert.equal(Replay.lerpMetric(nom, { a: 1, b: 2, f: 0.5 }), 3);
  assert.equal(Replay.lerpMetric(nom, { a: 0, b: 0, f: 0 }), null);
  assert.equal(Replay.lerpSide(noa, { a: 0, b: 1, f: 0.5 }), Replay.side(4) / 2);
});

test('constants', () => {
  assert.equal(Replay.SECONDS_PER_SNAPSHOT, 0.8);
  assert.deepEqual(Replay.SPEEDS, [1, 2, 4, 0.5]);
  assert.equal(Replay.SUBJECT_MAX, 60);
  assert.equal(Replay.MAX_FRAME, 0.25);
});

test('hasHistory needs at least one snapshot', () => {
  assert.equal(Replay.hasHistory({ history: { snapshots: [{}] } }), true);
  for (const city of [null, undefined, {}, { history: null }, { history: {} }, { history: { snapshots: [] } }]) {
    assert.equal(Replay.hasHistory(city), false, JSON.stringify(city));
  }
});

test('advance: speed, frame cap, no going back, stop at the last snapshot', () => {
  assert.equal(Replay.advance(0, 0.2, 1, 10), 0.25);
  assert.equal(Replay.advance(1, 0.2, 2, 10), 1.5);
  assert.equal(Replay.advance(0, 0.2, 0.5, 10), 0.125);
  assert.equal(Replay.advance(8.9, 0.2, 4, 10), 9);
  assert.equal(Replay.advance(0, 5, 1, 10), 0.25 / 0.8); // capped frame
  assert.equal(Replay.advance(3, -1, 1, 10), 3); // a clock going backwards
});

test('clampPosition keeps p within [0, S − 1]', () => {
  assert.equal(Replay.clampPosition(-1, 5), 0);
  assert.equal(Replay.clampPosition(2.5, 5), 2.5);
  assert.equal(Replay.clampPosition(7, 5), 4);
  assert.equal(Replay.clampPosition(3, 1), 0);
  assert.equal(Replay.clampPosition(3, 0), 0);
});

test('frame rounds halves up and stays within the snapshots', () => {
  assert.deepEqual(Replay.frame(2.5, 5), { a: 2, b: 3, f: 0.5, r: 3 });
  assert.deepEqual(Replay.frame(2.49, 5).r, 2);
  assert.deepEqual(Replay.frame(0, 1), { a: 0, b: 0, f: 0, r: 0 });
  assert.deepEqual(Replay.frame(3.75, 4), { a: 3, b: 3, f: 0.75, r: 3 });
});

test('stepPosition moves to the neighbouring whole snapshot', () => {
  assert.equal(Replay.stepPosition(3.4, -1, 10), 3);
  assert.equal(Replay.stepPosition(3, -1, 10), 2);
  assert.equal(Replay.stepPosition(3.4, 1, 10), 4);
  assert.equal(Replay.stepPosition(3, 1, 10), 4);
  assert.equal(Replay.stepPosition(0, -1, 10), 0);
  assert.equal(Replay.stepPosition(9, 1, 10), 9);
  // Rounding noise just off a whole snapshot counts as on it.
  assert.equal(Replay.stepPosition(3 + 1e-12, -1, 10), 2);
  assert.equal(Replay.stepPosition(3 - 1e-12, 1, 10), 4);
});

test('togglePlaying: pause, resume, restart from the end, a single snapshot never plays', () => {
  const rp = Replay.start(3);
  Replay.togglePlaying(rp);
  assert.deepEqual([rp.p, rp.playing], [0, false]);
  rp.p = 1.5;
  Replay.togglePlaying(rp);
  assert.deepEqual([rp.p, rp.playing], [1.5, true]);
  const one = Replay.start(1);
  Replay.togglePlaying(one); // pause
  Replay.togglePlaying(one); // would restart, but there is nothing to play
  assert.deepEqual([one.p, one.playing], [0, false]);
});

test('play does nothing while paused; seek clamps and pauses', () => {
  const rp = Replay.start(4);
  rp.playing = false;
  Replay.play(rp, 0.2);
  assert.equal(rp.p, 0);
  rp.playing = true;
  Replay.seek(rp, 10);
  assert.deepEqual([rp.p, rp.playing], [3, false]);
  Replay.seek(rp, -2);
  assert.equal(rp.p, 0);
});

test('side is the layout formula 1.2 + 0.8 √noa, absent counting as 0', () => {
  assert.equal(Replay.side(0), 1.2);
  assert.equal(Replay.side(4), 2.8);
  assert.equal(Replay.lerpSide([-1, -1], { a: 0, b: 1, f: 0.5 }), 0);
  assert.equal(Replay.lerpSide([0, 4], { a: 0, b: 1, f: 0.5 }), 2);
  near(Replay.lerpSide([4, -1], { a: 0, b: 1, f: 0.25 }), 2.1);
});

test('exists and valuesAt read one snapshot, absent values as 0', () => {
  const b = building([-1, 3, 5], [-1, 1, 2], { prs: [0], kind: 'enum' });
  b.hist.commits = [0, 2, 4];
  b.hist.authors = [0, 1, 2];
  b.hist.last = [-1, 1000, 2000];
  assert.equal(Replay.exists(b, 0), false);
  assert.equal(Replay.exists(b, 1), true);
  assert.deepEqual(Replay.valuesAt(b, 0), { nom: 0, noa: 0, loc: 0, cx: 0, commits: 0, authors: 0, last: undefined, prs: [0], kind: 'enum' });
  assert.deepEqual(Replay.valuesAt(b, 2), { nom: 5, noa: 2, loc: 50, cx: 10, commits: 4, authors: 2, last: 2000, prs: [0], kind: 'enum' });
});

test('formatDate is the local calendar date', () => {
  const tz = process.env.TZ;
  try {
    process.env.TZ = 'UTC';
    assert.equal(Replay.formatDate(1220227200), '2008-09-01'); // 2008-09-01T00:00:00Z
    assert.equal(Replay.formatDate(0), '1970-01-01');
    process.env.TZ = 'America/Los_Angeles';
    assert.equal(Replay.formatDate(1220227200), '2008-08-31');
    process.env.TZ = 'Asia/Tokyo';
    assert.equal(Replay.formatDate(1220227200 - 3600), '2008-09-01');
  } finally {
    if (tz === undefined) delete process.env.TZ;
    else process.env.TZ = tz;
  }
});

test('shortSha and truncateSubject', () => {
  assert.equal(Replay.shortSha('3f2a1c9deadbeef'), '3f2a1c9');
  assert.equal(Replay.shortSha('abc'), 'abc');
  assert.equal(Replay.truncateSubject('short'), 'short');
  assert.equal(Replay.truncateSubject('x'.repeat(60)), 'x'.repeat(60));
  assert.equal(Replay.truncateSubject('x'.repeat(61)), 'x'.repeat(59) + '…');
  // Code points, not UTF-16 units: emoji are never split.
  const cut = Replay.truncateSubject('😀'.repeat(70));
  assert.equal([...cut].length, 60);
  assert.equal(cut, '😀'.repeat(59) + '…');
  assert.equal(Replay.truncateSubject(42), '42');
});
