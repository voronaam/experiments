// Search — which types match the search box and in what order they are listed, independent of three.js and the DOM.
//
// A query is trimmed and compared case-insensitively. Shorter than MIN_QUERY characters it is no search at all (every
// building is shown normally). Otherwise every visible type whose name or id contains it matches, ranked: name equals
// the query, name starts with it, name contains it, id (package-qualified) contains it; within a rank shorter names
// come first, then city order. All matches are highlighted in the city; the drop-down lists the first MAX_RESULTS.
//
// No imports: the exported page loads this module from a data: URL, which cannot resolve relative imports of its own.

/** Queries shorter than this (after trimming) clear the search. */
export const MIN_QUERY = 2;
/** At most this many results are listed; the rest are only highlighted. */
export const MAX_RESULTS = 12;

/** The normalised form of a query: trimmed and lower case. */
export function normaliseQuery(q) {
  return q.trim().toLowerCase();
}

/** Rank of a building for a normalised query: 0 name equal, 1 name prefix, 2 name substring, 3 id substring, −1 none. */
export function score(b, q) {
  const name = b.name.toLowerCase();
  if (name === q) return 0;
  if (name.startsWith(q)) return 1;
  if (name.includes(q)) return 2;
  if (b.id.toLowerCase().includes(q)) return 3;
  return -1;
}

/**
 * Runs a query over the buildings, considering only those for which `visible(b)` holds. Null when the query is too
 * short; otherwise { matches: Set of every matching index, results: the first MAX_RESULTS indices in rank order }.
 */
export function search(buildings, query, visible = () => true) {
  const q = normaliseQuery(query);
  if (q.length < MIN_QUERY) return null;
  const scored = [];
  buildings.forEach((b, i) => {
    if (!visible(b)) return;
    const s = score(b, q);
    // The length of the lower-case name (which may differ from the name's, e.g. for 'İ').
    if (s >= 0) scored.push({ i, score: s, len: b.name.toLowerCase().length });
  });
  // Array.prototype.sort is stable, so equal ranks and lengths keep city order.
  scored.sort((a, b) => a.score - b.score || a.len - b.len);
  return { matches: new Set(scored.map((s) => s.i)), results: scored.slice(0, MAX_RESULTS).map((s) => s.i) };
}

/** The highlighted result after ArrowDown (dir +1) or ArrowUp (dir −1) among `count` results, wrapping around. */
export function moveActive(active, dir, count) {
  return (active + (dir > 0 ? 1 : count - 1)) % count;
}
