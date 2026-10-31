// Preferences — the viewer settings kept in localStorage between visits, parsed and checked, independent of three.js
// and the DOM.
//
// The stored value is JSON written by serialisePrefs. It is read back defensively: storage may hold anything (an older
// version's format, a hand edit, another page on the same origin), so a value that is not a JSON object counts as no
// preferences, and every field of the wrong type or outside its allowed values falls back to its default. The height
// and colour metric keys are only checked for being strings here; whether they name a usable metric depends on the
// city and is decided by metrics.js (chooseHeightKey, chooseColorKey) once it is loaded.
//
// No imports: the exported page loads this module from a data: URL, which cannot resolve relative imports of its own.

/** The localStorage key. */
export const PREFS_KEY = 'codecity.prefs';
/** The themes there are. */
export const THEMES = ['day', 'night'];
/** The on/off settings, all on by default. */
export const TOGGLES = ['showTests', 'labels', 'ao', 'beacons'];

/** The stored text (or null when nothing is stored) as a plain object; {} for anything else or unparsable text. */
export function parsePrefs(text) {
  let value;
  try { value = JSON.parse(text || '{}'); } catch { return {}; }
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

/**
 * The initial settings from parsed preferences: heightKey ('nom' unless a non-empty string), colorKey (null unless a
 * non-empty string; chosen later per city), theme ('day' or 'night', else from the system's dark-mode preference)
 * and the toggles (booleans, else on).
 */
export function initialSettings(prefs, prefersDark) {
  const key = (v) => (typeof v === 'string' && v ? v : null);
  const settings = {
    heightKey: key(prefs.heightKey) ?? 'nom',
    colorKey: key(prefs.colorKey),
    theme: THEMES.includes(prefs.theme) ? prefs.theme : prefersDark ? 'night' : 'day',
  };
  for (const t of TOGGLES) settings[t] = typeof prefs[t] === 'boolean' ? prefs[t] : true;
  return settings;
}

/** The text to store for the current settings (any object with the fields of initialSettings; others are dropped). */
export function serialisePrefs(s) {
  const { heightKey, colorKey, theme, showTests, labels, ao, beacons } = s;
  return JSON.stringify({ heightKey, colorKey, theme, showTests, labels, ao, beacons });
}
