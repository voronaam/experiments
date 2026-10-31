// Stored viewer settings (prefs.js): parsing, defaults and validation of whatever localStorage holds. Run with
// `node --test src/test/web` (scripts/test-web.sh).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as Prefs from '../../main/resources/web/prefs.js';

const DEFAULTS = { heightKey: 'nom', colorKey: null, theme: 'day', showTests: true, labels: true, ao: true, beacons: true };

test('nothing stored gives the defaults', () => {
  assert.equal(Prefs.PREFS_KEY, 'codecity.prefs');
  assert.deepEqual(Prefs.parsePrefs(null), {});
  assert.deepEqual(Prefs.initialSettings(Prefs.parsePrefs(null), false), DEFAULTS);
  assert.equal(Prefs.initialSettings({}, true).theme, 'night'); // the system's dark mode
});

test('settings round-trip', () => {
  const s = { heightKey: 'loc', colorKey: 'age', theme: 'night', showTests: false, labels: false, ao: true, beacons: false };
  const text = Prefs.serialisePrefs({ ...s, city: { big: true }, selected: 4 });
  assert.equal(text, '{"heightKey":"loc","colorKey":"age","theme":"night","showTests":false,"labels":false,"ao":true,"beacons":false}');
  assert.deepEqual(Prefs.initialSettings(Prefs.parsePrefs(text), false), s);
  // A stored theme wins over the system's.
  assert.equal(Prefs.initialSettings({ theme: 'day' }, true).theme, 'day');
});

test('anything that is not a JSON object counts as nothing stored', () => {
  for (const text of ['', 'null', '42', '"night"', 'true', '[1,2]', '{', 'undefined', '{"a":1', '\u0000']) {
    assert.deepEqual(Prefs.parsePrefs(text), {}, JSON.stringify(text));
    assert.deepEqual(Prefs.initialSettings(Prefs.parsePrefs(text), false), DEFAULTS);
  }
});

test('fields of the wrong type or value fall back to their defaults', () => {
  const s = Prefs.initialSettings({
    heightKey: 5, colorKey: '', theme: 'purple', showTests: 'false', labels: 0, ao: null, beacons: {},
  }, false);
  assert.deepEqual(s, DEFAULTS);
  assert.equal(Prefs.initialSettings({ theme: '__proto__' }, true).theme, 'night');
  assert.equal(Prefs.initialSettings({ heightKey: ['loc'] }, false).heightKey, 'nom');
  // Metric keys are only checked for being strings here (metrics.js decides per city).
  assert.equal(Prefs.initialSettings({ heightKey: 'constructor' }, false).heightKey, 'constructor');
});

test('inherited and prototype keys are ignored', () => {
  const parsed = Prefs.parsePrefs('{"__proto__":{"theme":"night","showTests":false}}');
  assert.equal(Object.getPrototypeOf({}).theme, undefined); // no prototype pollution
  assert.deepEqual(Prefs.initialSettings(parsed, false), DEFAULTS);
});
