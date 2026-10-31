// Escaping, link checks, formatting and the HUD's markup (text.js). Every string from the city data is untrusted
// (type and file names come from the analysed code, PR titles and authors from GitHub, subjects from git), so the
// markup tests feed hostile strings into every field and check that none of them can open an element, add an
// attribute or turn into a link that is not https://. Run with `node --test src/test/web` (scripts/test-web.sh).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as Text from '../../main/resources/web/text.js';

const { esc, httpsUrl, fmt } = Text;

/** Strings an attacker could put into a type name, a PR title, a commit subject, a URL… */
const HOSTILE = [
  '<script>alert(1)</script>',
  '<img src=x onerror=alert(1)>',
  '"><svg onload=alert(1)>',
  "'><a href='javascript:alert(1)'>x</a>",
  '" onmouseover="alert(1)',
  "' onfocus='alert(1)' autofocus='",
  '</textarea></title></style><b>',
  '&lt;already&gt; &amp; escaped',
  '<!-- comment --><![CDATA[x]]>',
  '`${alert(1)}`',
  'javascript:alert(1)',
  'data:text/html,<script>alert(1)</script>',
  '\u0000< >﻿',
];

/** The tags the HUD markup may contain. */
const TAGS = new Set(['span', 'b', 'a', 'svg', 'path', 'div', 'h2', 'h3', 'button', 'i', 'li', 'code', 'option']);

/**
 * Checks markup structurally without a DOM: every tag is one of TAGS, every tag consists only of well-formed
 * double-quoted attributes (nothing injected between them), no attribute is an event handler, and every href is
 * https:// or '#'. Returns the list of [tag, attributes] for further checks.
 */
function checkMarkup(html) {
  const tags = [];
  for (const m of html.matchAll(/<(\/?)([a-zA-Z0-9]+)([^>]*)>/g)) {
    const [, close, name, rest] = m;
    assert.ok(TAGS.has(name.toLowerCase()), `unexpected <${name}> in ${html}`);
    if (close) { assert.equal(rest.trim(), '', `junk in closing tag: ${m[0]}`); continue; }
    const attrs = {};
    const leftover = rest.replace(/\s+([a-zA-Z-]+)(?:="([^"]*)")?/g, (_, k, v) => { attrs[k.toLowerCase()] = v ?? ''; return ''; });
    assert.ok(/^\s*\/?$/.test(leftover), `malformed attributes in ${m[0]}`);
    for (const k of Object.keys(attrs)) assert.ok(!k.startsWith('on'), `event handler ${k} in ${m[0]}`);
    if ('href' in attrs) assert.ok(/^https:\/\//i.test(attrs.href) || attrs.href === '#', `href ${attrs.href}`);
    tags.push([name, attrs]);
  }
  // No '<' that is not part of a tag (an unescaped '<' in text could start one in the browser).
  assert.ok(!/<(?![a-zA-Z/])/.test(html), `stray < in ${html}`);
  return tags;
}

/** Asserts that `html` contains `s` only in escaped form. */
function escapedIn(html, s) {
  if (/[&<>"']/.test(s)) assert.ok(!html.includes(s), `unescaped ${JSON.stringify(s)}`);
  assert.ok(html.includes(esc(s)), `missing ${JSON.stringify(esc(s))}`);
}

test('esc escapes the five HTML metacharacters', () => {
  assert.equal(esc(`<a href="x" title='y'>&</a>`), '&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;&amp;&lt;/a&gt;');
  assert.equal(esc('plain text, ü 😀'), 'plain text, ü 😀');
  assert.equal(esc('&amp;'), '&amp;amp;'); // escaping is not idempotent: text is never pre-escaped
  assert.equal(esc(''), '');
});

test('esc converts any value to a string', () => {
  assert.equal(esc(42), '42');
  assert.equal(esc(null), 'null');
  assert.equal(esc(undefined), 'undefined');
  assert.equal(esc({ toString: () => '<x>' }), '&lt;x&gt;');
  assert.equal(esc(['<a>', 'b']), '&lt;a&gt;,b');
});

test('esc leaves no metacharacter in hostile strings', () => {
  for (const s of HOSTILE) {
    const e = esc(s);
    assert.ok(!/[<>"']/.test(e), e);
    // Every '&' starts one of the five entities.
    assert.ok(!/&(?!amp;|lt;|gt;|quot;|#39;)/.test(e), e);
  }
});

test('httpsUrl keeps https:// URLs only', () => {
  for (const u of ['https://github.com/o/r/pull/1', 'HTTPS://github.com/o/r', 'hTtPs://x']) assert.equal(httpsUrl(u), u);
  for (const u of [
    'javascript:alert(1)', 'JavaScript:alert(1)', ' javascript:alert(1)', 'java\tscript:alert(1)',
    'data:text/html,<script>alert(1)</script>', 'vbscript:msgbox', 'file:///etc/passwd', 'http://x',
    '//evil.example/x', '/relative', '', 'https:/x', 'https:x', 'https:\\\\evil', ' https://x', '\nhttps://x',
    '\u0000https://x', 'ｈttps://x', 'https%3A//x', '#https://x', 'ssh://h', '--help',
  ]) {
    assert.equal(httpsUrl(u), '#', JSON.stringify(u));
  }
  assert.equal(httpsUrl(null), '#');
  assert.equal(httpsUrl(undefined), '#');
  assert.equal(httpsUrl({ toString: () => 'https://x' }), 'https://x');
});

test('httpsUrl only checks the scheme; esc makes the rest safe in an attribute', () => {
  // Unlike the desktop viewers' isWebLink, the browser accepts spaces and control characters after the scheme: the
  // browser strips or encodes them, and the scheme cannot change. The quote below must not end the attribute.
  const u = 'https://x" onclick="alert(1)';
  assert.equal(httpsUrl(u), u);
  assert.equal(esc(httpsUrl(u)), 'https://x&quot; onclick=&quot;alert(1)');
  assert.equal(httpsUrl('https://x y'), 'https://x y');
  assert.equal(httpsUrl('https://x\n'), 'https://x\n');
});

test('fmt formats like the desktop viewers in English', () => {
  assert.equal(fmt(0, 'en-US'), '0');
  assert.equal(fmt(-123, 'en-US'), '-123');
  assert.equal(fmt(-1234, 'en-US'), '-1,234');
  assert.equal(fmt(9876, 'en-US'), '9,876');
  assert.equal(fmt(9999, 'en-US'), '9,999');
  assert.equal(fmt(10000, 'en-US'), '10.0k');
  assert.equal(fmt(12345, 'en-US'), '12.3k');
  assert.equal(fmt(99999, 'en-US'), '100.0k');
  assert.equal(fmt(100000, 'en-US'), '100k');
  assert.equal(fmt(1234567, 'en-US'), '1235k');
  // Rounded half up to a whole number first, like the desktop viewers.
  assert.equal(fmt(2.5, 'en-US'), '3');
  assert.equal(fmt(-2.5, 'en-US'), '-2');
  assert.equal(fmt(1234.4, 'en-US'), '1,234');
  // The viewer itself uses the browser's locale.
  assert.equal(fmt(1234, 'de-DE'), '1.234');
  assert.equal(fmt(1234), (1234).toLocaleString());
});

// The same ties as the desktop viewers' tests (LookTest, look_tests.rs, look.d): one-decimal numbers round half up on
// the exact decimal value, also where it is not exact in binary (12,250 / 1000 and 10,350 / 1000 are ties; the latter
// is stored as 10.3499…), so all four viewers show the same text.
test('one-decimal ties round half up like the desktop viewers', () => {
  assert.equal(fmt(12250, 'en-US'), '12.3k');
  assert.equal(fmt(10350, 'en-US'), '10.4k');
  assert.equal(fmt(12249, 'en-US'), '12.2k');
  assert.equal(fmt(150500, 'en-US'), '151k');
  // 1.25 years exactly.
  assert.equal(Text.relTime(1_790_769_600 - 456.25 * 86400, 1_790_769_600), '1.3y ago');
  assert.deepEqual(Text.legendEnds({ invert: false }, 0.25, 1.25, 'en-US'), ['0.3', '1.3+']);
});

test('tenths, oneDecimal and ratio round half up in integers', () => {
  assert.equal(Text.tenths(123), '12.3');
  assert.equal(Text.tenths(0), '0.0');
  assert.equal(Text.tenths(-5), '-0.5');
  assert.equal(Text.tenths(-123), '-12.3');
  assert.equal(Text.oneDecimal(10.35), '10.4'); // 10.35 is 10.3499… in binary; toFixed(1) gives 10.3
  assert.equal(Text.oneDecimal(0.25), '0.3');
  assert.equal(Text.oneDecimal(2), '2.0');
  // The desktop viewers' ratio cases (look_tests.rs).
  assert.equal(Text.ratio(5, 4), '1.3');
  assert.equal(Text.ratio(23, 20), '1.2');
  assert.equal(Text.ratio(1, 3), '0.3');
  assert.equal(Text.ratio(25, 8), '3.1');
  // cx per method in the inspector uses it: 5 / 4 = 1.25 → 1.3.
  assert.ok(inspect(building({ cx: 5, nom: 4 })).includes('<b>1.3</b><span>cx / method</span>'));
});

test('languageName knows the analysed languages and nothing inherited', () => {
  assert.equal(Text.languageName('java'), 'Java');
  assert.equal(Text.languageName('typescript'), 'TypeScript');
  assert.equal(Text.languageName('rust'), 'Rust');
  assert.equal(Text.languageName('kotlin'), 'kotlin');
  assert.equal(Text.languageName('constructor'), 'constructor');
  assert.equal(Text.languageName('__proto__'), '__proto__');
});

test('relTime: today, days, 30-day months, years, no date', () => {
  const now = 1_700_000_000;
  const D = Text.DAY;
  assert.equal(Text.relTime(0, now), '—');
  assert.equal(Text.relTime(undefined, now), '—');
  assert.equal(Text.relTime(NaN, now), '—');
  assert.equal(Text.relTime(now, now), 'today');
  assert.equal(Text.relTime(now - D + 1, now), 'today');
  assert.equal(Text.relTime(now + 5 * D, now), 'today'); // in the future
  assert.equal(Text.relTime(now - D, now), '1d ago');
  assert.equal(Text.relTime(now - 2.5 * D, now), '3d ago');
  assert.equal(Text.relTime(now - 29.4 * D, now), '29d ago');
  assert.equal(Text.relTime(now - 30 * D, now), '1mo ago');
  assert.equal(Text.relTime(now - 364 * D, now), '12mo ago');
  assert.equal(Text.relTime(now - 365 * D, now), '1.0y ago');
  assert.equal(Text.relTime(now - 548 * D, now), '1.5y ago');
});

test('legend values and ends', () => {
  assert.equal(Text.legendValue(12.4, 'd'), '12d ago');
  assert.equal(Text.legendValue(12.5, 'd'), '13d ago');
  assert.equal(Text.legendValue(12345, undefined, 'en-US'), '12.3k');
  assert.equal(Text.legendValue(3, undefined, 'en-US'), '3');
  assert.equal(Text.legendValue(0.25, undefined, 'en-US'), '0.3');
  assert.deepEqual(Text.legendEnds({}, 0, 40, 'en-US'), ['0', '40+']);
  assert.deepEqual(Text.legendEnds({ invert: true, unit: 'd' }, 0.4, 365, 'en-US'), ['365d ago+', '0d ago']);
  assert.deepEqual(Text.legendEnds({ linear: true }, 0.5, 2.75, 'en-US'), ['0.5', '2.8+']);
});

test('ramp and kind legends', () => {
  const html = Text.rampLegendHtml(['#000', '#fff'], { lo: 'stable', hi: 'hot spot' }, 0, 7, 'en-US');
  assert.equal(html, `<div class="bar" style="background:linear-gradient(90deg,#000,#fff)"></div>
    <div class="ends"><span>0</span><span>7+</span></div>
    <div class="ends"><span>stable</span><span>hot spot</span></div>`);
  assert.equal(Text.kindLegendHtml([['class', '#6b8fc7'], ['enum', '#e3a63b']]),
    '<div class="swatches"><span class="sw"><i style="background:#6b8fc7"></i>class</span><span class="sw"><i style="background:#e3a63b"></i>enum</span></div>');
  checkMarkup(html);
});

test('metric options mark the selection and unavailable metrics', () => {
  const metrics = { a: { label: 'A' }, b: { label: 'B', git: true } };
  assert.equal(Text.optionsHtml(metrics, 'a', (m) => (m.git ? ' (no git)' : '')),
    '<option value="a" selected >A</option><option value="b"  disabled>B (no git)</option>');
  assert.equal(Text.heightOptionsHtml(metrics, 'b'), '<option value="a" >A</option><option value="b" selected>B</option>');
});

test('languagesLabel: one language by name, several with shares', () => {
  assert.equal(Text.languagesLabel(undefined), '');
  assert.equal(Text.languagesLabel({}), '');
  assert.equal(Text.languagesLabel({ java: 7 }), 'Java');
  assert.equal(Text.languagesLabel({ java: 2, typescript: 1 }), 'Java 67% · TypeScript 33%');
  assert.equal(Text.languagesLabel({ rust: 1, go: 1 }), 'Rust 50% · go 50%');
});

/** A city as the analyser writes it, small. */
function city(overrides = {}) {
  return {
    name: 'demo',
    stats: { types: 12345, packages: 3, methods: 100000, loc: 9876, languages: { java: 3, rust: 1 }, failedFiles: 2 },
    github: { url: 'https://github.com/o/r', owner: 'o', repo: 'r', ref: 'main', prefix: '', pulls: [{}, {}], pullsSource: 'latest 2 open PRs (no token)' },
    ...overrides,
  };
}

test('statsHtml: chips in order', () => {
  const html = Text.statsHtml(city(), 'en-US');
  const chips = [...html.matchAll(/class="chip( gh| warn)?"/g)].map((m) => m[1] || '');
  assert.deepEqual(chips, ['', ' gh', '', '', '', '', ' warn']);
  assert.ok(html.startsWith('<span class="chip"><span>Java 75% · Rust 25%</span></span><a class="chip gh" href="https://github.com/o/r"'));
  assert.ok(html.includes('<b>12.3k</b><span>types</span>'));
  assert.ok(html.includes('<b>100k</b><span>methods</span>'));
  assert.ok(html.includes('<b>9,876</b><span>LOC</span>'));
  assert.ok(html.includes('<span> · 2+ PRs</span>')); // a sample of the PRs: "+"
  assert.ok(html.includes('<b>2</b><span>parse errors</span>'));
  assert.ok(Text.statsHtml(city({ github: { ...city().github, pullsSource: 'all 2 open PRs' } }), 'en-US').includes('<span> · 2 PRs</span>'));
  checkMarkup(html);
});

test('statsHtml without GitHub, languages or parse errors', () => {
  const html = Text.statsHtml(city({ github: undefined, stats: { types: 1, packages: 1, methods: 0, loc: 5 } }), 'en-US');
  assert.equal(html, '<span class="chip"><b>1</b><span>types</span></span><span class="chip"><b>1</b><span>packages</span></span>'
    + '<span class="chip"><b>0</b><span>methods</span></span><span class="chip"><b>5</b><span>LOC</span></span>');
});

test('statsHtml escapes hostile repository data and refuses non-https links', () => {
  for (const s of HOSTILE) {
    const html = Text.statsHtml(city({ github: { url: s, owner: s, repo: s, pulls: [], pullsSource: s }, stats: { types: 1, packages: 1, methods: 1, loc: 1, languages: { [s]: 1, java: 1 } } }), 'en-US');
    checkMarkup(html);
    assert.ok(html.includes('href="#"'));
    escapedIn(html, s);
  }
});

const GH = { url: 'https://github.com/o/r', ref: 'feature/x y', prefix: 'sub dir/', pulls: [] };

test('githubLink encodes the ref and every path segment', () => {
  const b = { file: 'src/a#b?c.java', line: 3, endLine: 9 };
  assert.equal(Text.githubLink(GH, 'blob', b), 'https://github.com/o/r/blob/feature/x%20y/sub%20dir/src/a%23b%3Fc.java#L3-L9');
  assert.equal(Text.githubLink(GH, 'blame', b), 'https://github.com/o/r/blame/feature/x%20y/sub%20dir/src/a%23b%3Fc.java#L3-L9');
  assert.equal(Text.githubLink(GH, 'history', b), 'https://github.com/o/r/commits/feature/x%20y/sub%20dir/src/a%23b%3Fc.java');
  // A file name cannot break out of the path or the attribute.
  const evil = Text.githubLink(GH, 'blob', { file: '../"><script>/x', line: 1, endLine: 1 });
  assert.ok(!/[<>"]/.test(evil), evil);
});

test('githubLink keeps only the unreserved characters, as every edition does', () => {
  const b = { file: "a!b'c(d)e*f~g-h_i.j,k é", line: 1, endLine: 2 };
  assert.equal(Text.githubLink(GH, 'history', b), 'https://github.com/o/r/commits/feature/x%20y/sub%20dir/a%21b%27c%28d%29e%2Af~g-h_i.j%2Ck%20%C3%A9');
  assert.equal(Text.encodeSegment('A-Z_0.9~'), 'A-Z_0.9~');
});

test('githubSectionHtml: links, PRs, none', () => {
  const now = 1_700_000_000;
  const gh = {
    ...GH, pullsSource: 'all open PRs',
    pulls: [{ url: 'https://github.com/o/r/pull/7', number: 7, title: 'Fix it', author: 'ann', updatedAt: new Date((now - 2 * 86400) * 1000).toISOString(), files: 3, draft: true }],
  };
  assert.equal(Text.githubSectionHtml(undefined, {}, now), '');
  const b = { file: 'A.java', line: 1, endLine: 2, prs: [0] };
  const html = Text.githubSectionHtml(gh, b, now);
  const hrefs = checkMarkup(html).filter(([t]) => t === 'a').map(([, a]) => a.href);
  assert.deepEqual(hrefs, [
    'https://github.com/o/r/blob/feature/x%20y/sub%20dir/A.java#L1-L2',
    'https://github.com/o/r/blame/feature/x%20y/sub%20dir/A.java#L1-L2',
    'https://github.com/o/r/commits/feature/x%20y/sub%20dir/A.java',
    'https://github.com/o/r/pull/7',
  ]);
  assert.ok(html.includes('<h3><span>Open pull requests</span><span>1</span></h3>'));
  assert.ok(html.includes('<span class="pr-meta">ann · 2d ago · 3 files · <i>draft</i></span>'));
  const none = Text.githubSectionHtml({ ...gh, pullsSource: 'latest 30 open PRs' }, { ...b, prs: undefined }, now);
  assert.ok(none.includes('<span class="muted">none among the latest 30 open PRs</span>'));
  assert.ok(Text.githubSectionHtml(gh, { ...b, prs: [] }, now).includes('<span class="muted">none</span>'));
  // Without fetched PRs only the links.
  assert.ok(!Text.githubSectionHtml({ ...GH, pulls: undefined }, b, now).includes('Open pull requests'));
  // A PR without author is the ghost user's; an unparsable date has no age.
  const ghost = Text.githubSectionHtml({ ...gh, pulls: [{ ...gh.pulls[0], author: null, updatedAt: 'garbage', draft: false }] }, b, now);
  assert.ok(ghost.includes('<span class="pr-meta">ghost · — · 3 files</span>'));
});

test('githubSectionHtml escapes hostile PR data and refuses non-https PR links', () => {
  for (const s of HOSTILE) {
    const gh = { url: s, ref: s, prefix: s, pullsSource: s, pulls: [{ url: s, number: s, title: s, author: s, updatedAt: s, files: s, draft: false }] };
    const html = Text.githubSectionHtml(gh, { file: s, line: 1, endLine: 2, prs: [0] }, 0);
    checkMarkup(html);
    for (const field of ['pr-num', 'pr-title']) assert.ok(html.includes(`class="${field}"`));
    escapedIn(html.split('class="pr-title">')[1], s);
  }
  // A PR URL on https stays a link even when the repository URL is hostile.
  const html = Text.githubSectionHtml({ url: 'javascript:x', ref: 'm', prefix: '', pulls: [{ url: 'https://ok/1', number: 1, title: 't', files: 1 }] }, { file: 'f', prs: [0] }, 0);
  assert.deepEqual(checkMarkup(html).filter(([t]) => t === 'a').map(([, a]) => a.href), ['#', '#', '#', 'https://ok/1']);
});

/** A building as in the city JSON. */
function building(overrides = {}) {
  return {
    id: 'com.x.Foo', name: 'Foo', pkg: 'com.x', kind: 'class', lang: 'java', file: 'src/Foo.java', line: 3, endLine: 40,
    nom: 2, noa: 1, loc: 12345, cx: 7, deps: [], methods: [['run', 5, 10, 6, 1], ['get', 20, 2, 1, 0]], ...overrides,
  };
}

const inspect = (b, o = {}) => Text.inspectorHtml(b, {
  buildings: [b, building({ id: 'com.x.Bar', name: 'Bar' })], out: [1], inc: [], hasGit: false, live: false,
  kindColor: '#6b8fc7', barColor: (t) => `t${t}`, github: '', now: 0, locale: 'en-US', ...o,
});

test('inspectorHtml shows the metrics, methods and dependencies', () => {
  const html = inspect(building());
  checkMarkup(html);
  assert.ok(html.includes('<h2>Foo</h2><div class="pkg">com.x</div>'));
  assert.ok(html.includes('<span class="badge kind" style="background:#6b8fc7">class</span>'));
  assert.ok(html.includes('<span class="badge">Java</span>'));
  assert.ok(html.includes('<b>12.3k</b><span>lines of code</span>'));
  assert.ok(html.includes('<b>3.5</b><span>cx / method</span>'));
  assert.ok(html.includes('<b>1 / 0</b><span>uses / used by</span>'));
  assert.ok(!html.includes('commits')); // no git
  assert.ok(!html.includes('view-source')); // exported page: no server
  // Methods: bar width relative to the most complex, colour relative to max(10, it).
  assert.ok(html.includes('<i style="width:100%;background:t0.6"></i>'));
  assert.ok(html.includes('<span class="m-name">run(…)</span><span class="m-val">cx 6 · 10 loc</span>'));
  assert.ok(html.includes('<span class="m-name">get()</span>'));
  assert.ok(html.includes('<span class="dep out" data-idx="1" title="com.x.Bar">Bar</span>'));
  assert.ok(html.includes('<span class="muted">no project types</span>'));
});

test('inspectorHtml: git, live server, test code, default package, module, long method lists', () => {
  const now = 1_700_000_000;
  const many = Array.from({ length: 20 }, (_, k) => [`m${k}`, k, 1, 1, 0]);
  const html = inspect(building({ pkg: '', test: true, module: 'core', commits: 4, authors: 2, last: now - 3 * 86400, nom: 20, methods: many }),
    { hasGit: true, live: true, now, out: [] });
  assert.ok(html.includes('<div class="pkg">(default package) · core</div>'));
  assert.ok(html.includes('<span class="badge">test</span>'));
  assert.ok(html.includes('<b>4</b><span>commits</span>'));
  assert.ok(html.includes('<b>3d ago</b><span>last change</span>'));
  assert.ok(html.includes('<button id="view-source">Source</button>'));
  assert.equal((html.match(/class="method"/g) || []).length, 15);
  assert.ok(html.includes('<span class="muted">+ 5 more</span>'));
  assert.ok(html.includes('<span class="muted">nothing in this project</span>'));
  assert.ok(inspect(building({ pkg: '', lang: 'rust' })).includes('<div class="pkg">(root)</div>'));
  assert.ok(!inspect(building({ methods: [], nom: 0 })).includes('Methods by complexity'));
});

test('inspectorHtml escapes hostile names everywhere', () => {
  for (const s of HOSTILE) {
    const b = building({ id: s, name: s, pkg: s, module: s, kind: s, lang: s, file: s, methods: [[s, 1, 1, 1, 1]] });
    const html = inspect(b, { buildings: [b, b], out: [1], inc: [0], kindColor: s });
    checkMarkup(html);
    escapedIn(html, s);
  }
});

test('replayNoteHtml', () => {
  const v = { nom: 1, noa: 2, loc: 30000, cx: 4 };
  const html = Text.replayNoteHtml({ gone: true }, v, '2020-01-02', true, 'en-US');
  checkMarkup(html);
  assert.ok(html.includes('<span class="gone">Removed before today</span>'));
  assert.ok(html.includes('<span>Replaying — values at 2020-01-02</span>'));
  assert.ok(html.includes('<b>30.0k</b><span>lines of code</span>'));
  const absent = Text.replayNoteHtml({}, v, '2020-01-02', false, 'en-US');
  assert.ok(absent.includes('<div class="muted">Not in this snapshot</div>'));
  assert.ok(!absent.includes('gone'));
});

test('buildingTooltipHtml: present day and replay', () => {
  const b = building({ commits: 3, prs: [0, 1] });
  const html = Text.buildingTooltipHtml(b, b, null, 'en-US');
  checkMarkup(html);
  assert.ok(html.startsWith('<b>Foo</b> <span class="t-pkg">class</span><div class="t-pkg">com.x</div>'));
  assert.ok(html.includes('<span>2 methods</span><span>1 attrs</span><span>12.3k loc</span><span>cx 7</span><span>3 commits</span><span class="t-pr">2 open PRs</span></div>'));
  assert.ok(Text.buildingTooltipHtml({ ...b, prs: [0] }, b, null, 'en-US').includes('1 open PR</span>'));
  // During replay: the snapshot's values and date, no PRs.
  const v = { nom: 1, noa: 0, loc: 5, cx: 1, commits: 1 };
  const replay = Text.buildingTooltipHtml(b, v, '2020-01-02', 'en-US');
  assert.ok(replay.includes('<span>1 methods</span><span>0 attrs</span><span>5 loc</span><span>cx 1</span><span>1 commits</span><span class="t-at">· at 2020-01-02</span></div>'));
  // No git: no commits.
  assert.ok(!Text.buildingTooltipHtml(building(), building(), null, 'en-US').includes('commits'));
});

test('tooltips escape hostile names', () => {
  for (const s of HOSTILE) {
    const b = building({ name: s, kind: s, pkg: s });
    const html = Text.buildingTooltipHtml(b, b, null, 'en-US');
    checkMarkup(html);
    escapedIn(html, s);
    const d = Text.districtTooltipHtml({ name: s, id: 'module:' + s, types: 3 });
    checkMarkup(d);
    escapedIn(d, s);
  }
  assert.equal(Text.districtTooltipHtml({ name: 'core', id: 'module:core', types: 3 }),
    '<b>core</b><div class="t-pkg">core</div><div class="t-row"><span>3 types</span><span>double-click to zoom</span></div>');
});

test('searchResultsHtml', () => {
  const bs = [building({ name: 'A', pkg: 'p' }), building({ name: 'B', pkg: 'q' })];
  assert.equal(Text.searchResultsHtml(bs, [1, 0], 0, 2),
    '<li data-idx="1" class="active"><span class="r-name">B</span><span class="r-pkg">q</span></li><li data-idx="0" class=""><span class="r-name">A</span><span class="r-pkg">p</span></li>');
  assert.ok(Text.searchResultsHtml(bs, [0], 0, 30).endsWith('<li class="r-empty">29 more highlighted in the city</li>'));
  assert.equal(Text.searchResultsHtml(bs, [], 0, 0), '<li class="r-empty">No matching types</li>');
  for (const s of HOSTILE) {
    const html = Text.searchResultsHtml([building({ name: s, pkg: s })], [0], 0, 1);
    checkMarkup(html);
    escapedIn(html, s);
  }
});

test('timelineHtml escapes the commit data', () => {
  assert.equal(Text.timelineHtml('2020-01-02', 'abc1234', 'Add <b>', 'Ann & Bob'),
    '<b>2020-01-02</b> · <code>abc1234</code> · Add &lt;b&gt; — Ann &amp; Bob');
  for (const s of HOSTILE) {
    const html = Text.timelineHtml('2020-01-02', s, s, s);
    checkMarkup(html);
    escapedIn(html, s);
  }
});
