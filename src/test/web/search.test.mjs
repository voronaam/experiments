// Search matching and ranking of the browser viewer (search.js). Run with `node --test src/test/web`
// (scripts/test-web.sh).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as Search from '../../main/resources/web/search.js';

const t = (name, id = `p.${name}`, extra = {}) => ({ name, id, ...extra });

test('queries shorter than two characters clear the search', () => {
  const bs = [t('A'), t('Ab')];
  assert.equal(Search.search(bs, ''), null);
  assert.equal(Search.search(bs, 'a'), null);
  assert.equal(Search.search(bs, '   a  '), null);
  assert.notEqual(Search.search(bs, 'ab'), null);
  assert.equal(Search.MIN_QUERY, 2);
  assert.equal(Search.MAX_RESULTS, 12);
});

test('ranks exact name, prefix, substring, then id; shorter names first; ties in city order', () => {
  const bs = [
    t('XmapperY', 'a.XmapperY'), // 0: name contains
    t('Other', 'mapper.Other'), // 1: id contains
    t('Mapper'), // 2: exact
    t('MapperImpl'), // 3: prefix
    t('MapperX'), // 4: prefix, shorter
    t('Zmapper'), // 5: contains, shorter than 0
    t('Unrelated'), // 6: no match
    t('MapperY'), // 7: prefix, same length as 4
  ];
  const r = Search.search(bs, '  MAPPER ');
  assert.deepEqual(r.results, [2, 4, 7, 3, 5, 0, 1]);
  assert.deepEqual([...r.matches].sort(), [0, 1, 2, 3, 4, 5, 7]);
});

test('score of a single building', () => {
  assert.equal(Search.score(t('Foo'), 'foo'), 0);
  assert.equal(Search.score(t('FooBar'), 'foo'), 1);
  assert.equal(Search.score(t('BarFoo'), 'foo'), 2);
  assert.equal(Search.score(t('Bar', 'foo.Bar'), 'foo'), 3);
  assert.equal(Search.score(t('Bar', 'x.Bar'), 'foo'), -1);
});

test('only visible buildings match', () => {
  const bs = [t('Alpha'), t('Alphabet', 'p.Alphabet', { test: true })];
  assert.deepEqual(Search.search(bs, 'alp', (b) => !b.test).results, [0]);
  assert.deepEqual(Search.search(bs, 'alp').results, [0, 1]);
});

test('lists at most twelve results but highlights every match', () => {
  const bs = Array.from({ length: 30 }, (_, k) => t(`Item${k}`));
  const r = Search.search(bs, 'item');
  assert.equal(r.results.length, 12);
  assert.equal(r.matches.size, 30);
  assert.deepEqual(r.results, [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
});

test('no match gives empty results, not null', () => {
  assert.deepEqual(Search.search([t('Foo')], 'zz'), { matches: new Set(), results: [] });
});

test('queries are plain text, not patterns', () => {
  const bs = [t('a.b'), t('axb'), t('Foo$1'), t('(x)')];
  assert.deepEqual(Search.search(bs, 'a.b').results, [0]);
  assert.deepEqual(Search.search(bs, 'o$1').results, [2]);
  assert.deepEqual(Search.search(bs, '(x').results, [3]);
  assert.deepEqual(Search.search([t('<script>')], '<scr').results, [0]);
});

test('case-insensitive beyond ASCII', () => {
  assert.deepEqual(Search.search([t('Ärger'), t('ÉCOLE')], 'är').results, [0]);
  assert.deepEqual(Search.search([t('Ärger'), t('ÉCOLE')], 'école').results, [1]);
});

test('ArrowDown and ArrowUp wrap around', () => {
  assert.equal(Search.moveActive(0, 1, 3), 1);
  assert.equal(Search.moveActive(2, 1, 3), 0);
  assert.equal(Search.moveActive(0, -1, 3), 2);
  assert.equal(Search.moveActive(1, -1, 3), 0);
  assert.equal(Search.moveActive(0, 1, 1), 0);
});
