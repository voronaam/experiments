// Text and markup — escaping, link checks, number and date formatting, and the HUD's HTML fragments, independent of
// three.js and the DOM.
//
// Everything the viewer shows that comes from the city data (type, package and file names, pull-request titles and
// authors, commit subjects, URLs) is untrusted text: it reaches the page only through esc() inside the fragments built
// here, or through textContent in app.js. Link targets additionally go through httpsUrl(). app.js assigns the returned
// strings to innerHTML; this module only builds them, so it can run (and be tested) anywhere.
//
// No imports: the exported page loads this module from a data: URL, which cannot resolve relative imports of its own
// (see HtmlExporter). Values that live in other modules (kind colours, snapshot dates) are passed in by app.js.

/** Seconds per day. */
export const DAY = 86400;

/** Display names of the analysed languages; any other language is shown as its id. */
export const LANGUAGES = { java: 'Java', typescript: 'TypeScript', rust: 'Rust' };

/** The GitHub mark, inline SVG, used in front of GitHub links. */
export const GH_MARK = '<svg class="gh-mark" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>';

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** HTML-escapes any value (converted with String) for use in element content and in quoted attribute values. */
export function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ESCAPES[c]);
}

/**
 * The link target for a URL from the city data (the GitHub API's PR URLs, the repository URL): only https:// URLs
 * (any letter case) are kept, anything else (javascript:, data:, file:, http:, …) is replaced by an inert '#'. The
 * result must still go through esc.
 */
export function httpsUrl(u) {
  return /^https:\/\//i.test(String(u)) ? String(u) : '#';
}

/**
 * Tenths as text with one decimal: 123 → "12.3", −5 → "-0.5". Values with one decimal are rounded to whole tenths
 * first (half up, in integers where possible) and then written by this, the same way in all four viewers
 * (look.rs/Look.java/look.d `tenths`); toFixed would round binary values such as 10.35 down.
 */
export function tenths(t) {
  const a = Math.abs(t);
  return `${t < 0 ? '-' : ''}${Math.floor(a / 10)}.${a % 10}`;
}

/** x with one decimal, rounded half up: ⌊10x + 0.5⌋ tenths. */
export function oneDecimal(x) {
  return tenths(Math.floor(x * 10 + 0.5));
}

/** The ratio a / b (whole numbers, b > 0) with one decimal, rounded half up in integers: 5 / 4 → "1.3". */
export function ratio(a, b) {
  return tenths(Math.floor((20 * a + b) / (2 * b)));
}

/**
 * Compact number for chips and labels, like the desktop viewers: the number is first rounded half up to a whole
 * number (⌊n + 0.5⌋, so −2.5 → −2); from 100,000 it is shown in whole thousands (`151k`), from 10,000 in tenths of
 * thousands (`12.3k`), both rounded half up in integers, otherwise with the viewer's locale's separators (`9,876` in
 * English). `locale` is for tests; the viewer leaves it out.
 */
export function fmt(n, locale) {
  const v = Math.floor(n + 0.5);
  if (n >= 100000) return `${Math.floor((v + 500) / 1000)}k`;
  if (n >= 10000) return `${tenths(Math.floor((v + 50) / 100))}k`;
  return v.toLocaleString(locale);
}

/** Display name of a language id. */
export function languageName(lang) {
  return Object.hasOwn(LANGUAGES, lang) ? LANGUAGES[lang] : lang;
}

/**
 * Relative age of a Unix time (seconds) as seen at `now` (seconds): "today", "12d ago", "5mo ago" (30-day months)
 * or "1.5y ago" (tenths of 365-day years from the whole seconds, rounded half up in integers, as in the desktop
 * viewers); "—" when there is no time (0, undefined or NaN).
 */
export function relTime(epoch, now) {
  if (!epoch) return '—';
  const days = (now - epoch) / DAY;
  if (days < 1) return 'today';
  if (days < 30) return `${Math.round(days)}d ago`;
  if (days < 365) return `${Math.round(days / 30)}mo ago`;
  return `${ratio(Math.floor(now - epoch), 365 * DAY)}y ago`;
}

/** A colour-legend end value: days ago for recency (unit 'd'), a whole number via fmt, otherwise oneDecimal. */
export function legendValue(v, unit, locale) {
  return unit === 'd' ? `${Math.round(v)}d ago` : Number.isInteger(v) ? fmt(v, locale) : oneDecimal(v);
}

/**
 * The texts at the left and right end of a colour legend for the range lo..hi. The value beyond which every building
 * gets the end colour is marked "+"; for an inverted metric (recency) the high value is on the left.
 */
export function legendEnds(metric, lo, hi, locale) {
  const show = (v) => legendValue(v, metric.unit, locale);
  return metric.invert ? [show(hi) + '+', show(lo)] : [show(lo), show(hi) + '+'];
}

/** The colour legend's markup for a ramp metric: the gradient, the two end values and the metric's two words. */
export function rampLegendHtml(ramp, metric, lo, hi, locale) {
  const [left, right] = legendEnds(metric, lo, hi, locale);
  return `<div class="bar" style="background:linear-gradient(90deg,${ramp.join(',')})"></div>
    <div class="ends"><span>${left}</span><span>${right}</span></div>
    <div class="ends"><span>${metric.lo}</span><span>${metric.hi}</span></div>`;
}

/** The colour legend's markup for the type-kind metric: one swatch per [kind, colour] entry. */
export function kindLegendHtml(entries) {
  return `<div class="swatches">${entries.map(([k, c]) => `<span class="sw"><i style="background:${c}"></i>${k}</span>`).join('')}</div>`;
}

/**
 * The options of a metric <select>: one per entry of `metrics` (key → { label }), the current key selected, and the
 * reason a metric is unavailable (`unavailable(m)`, '' when it is available) appended to its label, which disables it.
 */
export function optionsHtml(metrics, selectedKey, unavailable = () => '') {
  return Object.entries(metrics)
    .map(([k, m]) => `<option value="${k}" ${k === selectedKey ? 'selected' : ''} ${unavailable(m) ? 'disabled' : ''}>${m.label}${unavailable(m)}</option>`).join('');
}

/** The height <select>'s options: like optionsHtml, but every metric is available (no disabled attribute). */
export function heightOptionsHtml(metrics, selectedKey) {
  return Object.entries(metrics)
    .map(([k, m]) => `<option value="${k}" ${k === selectedKey ? 'selected' : ''}>${m.label}</option>`).join('');
}

/**
 * The language chip's text: the language name alone for one language, otherwise every language with its share of
 * the types in whole percent, in the order of `languages` (id → type count). '' when there are none.
 */
export function languagesLabel(languages) {
  const langs = Object.entries(languages || {});
  if (!langs.length) return '';
  const total = langs.reduce((n, [, c]) => n + c, 0);
  return langs.map(([l, c]) => langs.length > 1 ? `${languageName(l)} ${Math.round((c / total) * 100)}%` : languageName(l)).join(' · ');
}

/**
 * The top bar's statistic chips for a city: the language mix and the GitHub repository (when known) first, then
 * types, packages, methods and lines of code, and a warning chip when files had syntax errors.
 */
export function statsHtml(city, locale) {
  const { stats } = city;
  const chips = [
    [fmt(stats.types, locale), 'types'],
    [fmt(stats.packages, locale), 'packages'],
    [fmt(stats.methods, locale), 'methods'],
    [fmt(stats.loc, locale), 'LOC'],
  ].map(([v, l]) => `<span class="chip"><b>${v}</b><span>${l}</span></span>`);
  const gh = city.github;
  if (gh) {
    const prs = gh.pulls ? ` · ${gh.pulls.length}${gh.pullsSource?.startsWith('all') ? '' : '+'} PRs` : '';
    chips.unshift(`<a class="chip gh" href="${esc(httpsUrl(gh.url))}" target="_blank" rel="noopener" title="Open on GitHub${gh.pullsSource ? ' · ' + esc(gh.pullsSource) : ''}">${GH_MARK}<b>${esc(gh.owner)}/${esc(gh.repo)}</b><span>${prs}</span></a>`);
  }
  const label = languagesLabel(stats.languages);
  if (label) chips.unshift(`<span class="chip"><span>${esc(label)}</span></span>`);
  if (stats.failedFiles) chips.push(`<span class="chip warn" title="Files with syntax errors were partially analysed"><b>${stats.failedFiles}</b><span>parse errors</span></span>`);
  return chips.join('');
}

/**
 * One URL path segment, percent-encoded: everything but the unreserved characters of RFC 3986 (A-Z a-z 0-9 - . _ ~)
 * becomes %XX per UTF-8 byte. encodeURIComponent also keeps ! ' ( ) *, which the desktop viewers of every edition
 * encode, so those are encoded here too and every viewer builds the same link.
 */
export function encodeSegment(s) {
  return encodeURIComponent(s).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
}

/**
 * Permalink into the repository on GitHub for a building's file: kind 'blob' or 'blame' links to the type's lines,
 * 'history' to the file's commits. Every path segment and the ref are percent-encoded (encodeSegment); the result
 * still goes through httpsUrl and esc.
 */
export function githubLink(gh, kind, b) {
  const enc = (p) => p.split('/').map(encodeSegment).join('/');
  const path = enc(gh.prefix + b.file);
  const ref = enc(gh.ref);
  if (kind === 'history') return `${gh.url}/commits/${ref}/${path}`;
  return `${gh.url}/${kind}/${ref}/${path}#L${b.line}-L${b.endLine}`;
}

/**
 * The inspector's GitHub part for building `b`: File / Blame / History links, and when pull requests were fetched
 * the building's open PRs (number, title, author, age at `now`, files, draft) or why there are none. '' without
 * GitHub.
 */
export function githubSectionHtml(gh, b, now) {
  if (!gh) return '';
  const links = `<div class="gh-links">
      <a href="${esc(httpsUrl(githubLink(gh, 'blob', b)))}" target="_blank" rel="noopener">${GH_MARK}File</a>
      <a href="${esc(httpsUrl(githubLink(gh, 'blame', b)))}" target="_blank" rel="noopener">Blame</a>
      <a href="${esc(httpsUrl(githubLink(gh, 'history', b)))}" target="_blank" rel="noopener">History</a>
    </div>`;
  if (!gh.pulls) return links;
  const prs = (b.prs || []).map((k) => gh.pulls[k]);
  return `${links}
    <h3><span>Open pull requests</span><span>${prs.length}</span></h3>
    <div class="prs">${prs.map((pr) => `
      <a class="pr" href="${esc(httpsUrl(pr.url))}" target="_blank" rel="noopener">
        <span class="pr-num">#${esc(pr.number)}</span>
        <span class="pr-title">${esc(pr.title)}</span>
        <span class="pr-meta">${esc(pr.author || 'ghost')} · ${relTime(Date.parse(pr.updatedAt) / 1000, now)} · ${esc(pr.files)} files${pr.draft ? ' · <i>draft</i>' : ''}</span>
      </a>`).join('') || `<span class="muted">none${gh.pullsSource?.startsWith('all') ? '' : ' among the ' + esc(gh.pullsSource)}</span>`}
    </div>`;
}

/**
 * The inspector's markup for building `b`. Options:
 *   buildings   every building of the city (for the dependency chips' names)
 *   out, inc    indices of the visible types `b` uses and that use it
 *   hasGit      whether the city has git statistics (commits, authors, last change)
 *   live        whether a server is there to show the source (the Source button)
 *   kindColor   the badge colour of b.kind
 *   barColor    t ∈ [0, 1] → CSS colour of a method's complexity bar
 *   github      the GitHub section's markup (githubSectionHtml)
 *   now         the present in seconds, for the last change's age
 * The method list is capped at 15 (b.methods itself is capped at 300 by the analyser; the counts use b.nom).
 */
export function inspectorHtml(b, { buildings, out, inc, hasGit, live, kindColor, barColor, github, now, locale }) {
  const maxCx = Math.max(1, ...b.methods.map((m) => m[3]));
  const metric = (v, l) => `<div class="metric"><b>${v}</b><span>${l}</span></div>`;
  const depChip = (j, dir) => {
    const d = buildings[j];
    return `<span class="dep ${dir}" data-idx="${j}" title="${esc(d.id)}">${esc(d.name)}</span>`;
  };
  return `
    <div class="head">
      <div><h2>${esc(b.name)}</h2><div class="pkg">${esc(b.pkg || (b.lang === 'java' ? '(default package)' : '(root)'))}${b.module ? ' · ' + esc(b.module) : ''}</div></div>
      <button class="close" id="inspector-close" aria-label="Close">✕</button>
    </div>
    <div class="badges">
      <span class="badge kind" style="background:${esc(kindColor)}">${esc(b.kind)}</span>
      <span class="badge">${esc(languageName(b.lang))}</span>
      ${b.test ? '<span class="badge">test</span>' : ''}
    </div>
    <div class="metrics">
      ${metric(b.nom, 'methods')}${metric(b.noa, 'attributes')}${metric(fmt(b.loc, locale), 'lines of code')}
      ${metric(b.cx, 'complexity')}${metric(b.nom ? ratio(b.cx, b.nom) : '0', 'cx / method')}
      ${metric(out.length + ' / ' + inc.length, 'uses / used by')}
      ${hasGit ? metric(b.commits ?? 0, 'commits') + metric(b.authors ?? 0, 'authors') + metric(relTime(b.last, now), 'last change') : ''}
    </div>
    <div id="inspector-replay"></div>
    <div class="file-link"><span title="${esc(b.file)}:${b.line}">${esc(b.file)}:${b.line}</span>${live ? '<button id="view-source">Source</button>' : ''}</div>
    ${github}
    ${b.methods.length ? `<h3><span>Methods by complexity</span><span>${b.nom}</span></h3>
    <div class="methods">${b.methods.slice(0, 15).map((m) => `
      <div class="method" title="line ${m[1]} · ${m[2]} LOC · ${m[4]} params">
        <span class="m-name">${esc(m[0])}(${m[4] ? '…' : ''})</span><span class="m-val">cx ${m[3]} · ${m[2]} loc</span>
        <span class="m-bar"><i style="width:${(m[3] / maxCx) * 100}%;background:${barColor(m[3] / Math.max(10, maxCx))}"></i></span>
      </div>`).join('')}
      ${b.nom > 15 ? `<span class="muted">+ ${b.nom - 15} more</span>` : ''}
    </div>` : ''}
    <h3><span>Uses</span><span>${out.length}</span></h3>
    <div class="deps">${out.map((j) => depChip(j, 'out')).join('') || '<span class="muted">nothing in this project</span>'}</div>
    <h3><span>Used by</span><span>${inc.length}</span></h3>
    <div class="deps">${inc.map((j) => depChip(j, 'in')).join('') || '<span class="muted">no project types</span>'}</div>`;
}

/**
 * The inspector's replay box: the type's values `v` (Replay.valuesAt) in the snapshot dated `date`, or a note that
 * it does not exist there (`exists` false); a type that is gone today is marked as removed.
 */
export function replayNoteHtml(b, v, date, exists, locale) {
  const metric = (val, l) => `<div class="metric"><b>${val}</b><span>${l}</span></div>`;
  return `<div class="replay-note">
      ${b.gone ? '<span class="gone">Removed before today</span>' : ''}
      <span>Replaying — values at ${date}</span>
      ${exists
        ? `<div class="metrics">${metric(v.nom, 'methods')}${metric(v.noa, 'attributes')}${metric(fmt(v.loc, locale), 'lines of code')}${metric(v.cx, 'complexity')}</div>`
        : '<div class="muted">Not in this snapshot</div>'}
    </div>`;
}

/**
 * A building's hover tooltip: name, kind, package and the values `v` (the building itself, or its values in the
 * replayed snapshot). `at` is that snapshot's date during replay and null otherwise; open PRs are only shown outside
 * replay, commits only when the city has git statistics.
 */
export function buildingTooltipHtml(b, v, at, locale) {
  const replaying = at != null;
  const atHtml = replaying ? `<span class="t-at">· at ${at}</span>` : '';
  return `<b>${esc(b.name)}</b> <span class="t-pkg">${esc(b.kind)}</span><div class="t-pkg">${esc(b.pkg)}</div>
      <div class="t-row"><span>${v.nom} methods</span><span>${v.noa} attrs</span><span>${fmt(v.loc, locale)} loc</span><span>cx ${v.cx}</span>${b.commits != null ? `<span>${v.commits} commits</span>` : ''}${b.prs && !replaying ? `<span class="t-pr">${b.prs.length} open PR${b.prs.length > 1 ? 's' : ''}</span>` : ''}${atHtml}</div>`;
}

/** A district's hover tooltip: name, id (without the "module:" prefix of a module district) and type count. */
export function districtTooltipHtml(d) {
  return `<b>${esc(d.name)}</b><div class="t-pkg">${esc(d.id.replace(/^module:/, ''))}</div><div class="t-row"><span>${d.types} types</span><span>double-click to zoom</span></div>`;
}

/**
 * The search drop-down: one entry per result (building indices, the `active` one highlighted), a note how many
 * more matches are only highlighted in the city (`total` matches in all), or "No matching types".
 */
export function searchResultsHtml(buildings, results, active, total) {
  return results.length
    ? results.map((i, k) => `<li data-idx="${i}" class="${k === active ? 'active' : ''}"><span class="r-name">${esc(buildings[i].name)}</span><span class="r-pkg">${esc(buildings[i].pkg)}</span></li>`).join('')
      + (total > results.length ? `<li class="r-empty">${total - results.length} more highlighted in the city</li>` : '')
    : '<li class="r-empty">No matching types</li>';
}

/** The timeline's commit line: date, short sha, (already truncated) subject and author. */
export function timelineHtml(date, sha, subject, author) {
  return `<b>${date}</b> · <code>${esc(sha)}</code> · ${esc(subject)} — ${esc(author)}`;
}
