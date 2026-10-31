#!/usr/bin/env python3
"""City JSON helpers for the end-to-end tests: extract, normalise, compare and format (standard library only).

Usage:
  city_json.py normalise FILE              print FILE (an --export HTML page or a JSON file) normalised, in golden format
  city_json.py compare EXPECTED ACTUAL     exit 1 and print the first differences if the normalised documents differ
  city_json.py count FILE                  print the number of buildings

Normalisation removes what changes from run to run, so that two exports of the same tree can be compared. The Java
(GoldenCityTest), Rust (golden_tests.rs) and D (golden.d) test suites implement exactly the same rules:

  * the top-level "generatedAt" and "buildMillis" and "stats"."buildMillis" are removed (wall-clock time);
  * the top-level "root" (the analysed directory's absolute path) becomes "<root>";
  * every building's "deps" array is sorted (the editions may list the same edges in different orders).

Everything else must match exactly. The city name is not normalised: the tests analyse every tree under a fixed
directory name. Comparison ignores the order of object keys and compares numbers by value.

The golden format is ordinary JSON, indented, with arrays of scalars (and arrays of such arrays, e.g. methods) kept on
one line, so that a golden file diffs well in review.
"""
import json
import re
import sys

MAX_DIFFS = 10


def load(path):
    """The city document in an --export HTML page, or a JSON file."""
    with open(path, encoding='utf-8') as f:
        text = f.read()
    m = re.search(r'<script id="city-data" type="application/json">(.*?)</script>', text, re.S)
    return json.loads(m.group(1) if m else text)


def normalise(city):
    """Applies the normalisation rules above, in place; returns the document."""
    city.pop('generatedAt', None)
    city.pop('buildMillis', None)
    if isinstance(city.get('stats'), dict):
        city['stats'].pop('buildMillis', None)
    if 'root' in city:
        city['root'] = '<root>'
    for b in city.get('buildings', []):
        if isinstance(b.get('deps'), list):
            b['deps'].sort()
    return city


def flat(v):
    """True for a value printed on one line: a scalar, or an array of flat values."""
    return not isinstance(v, (dict, list)) or (isinstance(v, list) and all(flat(x) for x in v))


def dumps(v, indent=0):
    """The golden format: indented objects and arrays, flat arrays on one line."""
    if flat(v):
        return json.dumps(v, ensure_ascii=False, separators=(', ', ': ') if isinstance(v, list) else None)
    pad, inner = ' ' * indent, ' ' * (indent + 2)
    if isinstance(v, dict):
        items = [f'{inner}{json.dumps(k, ensure_ascii=False)}: {dumps(x, indent + 2)}' for k, x in v.items()]
        return '{\n' + ',\n'.join(items) + '\n' + pad + '}'
    return '[\n' + ',\n'.join(inner + dumps(x, indent + 2) for x in v) + '\n' + pad + ']'


def label(v):
    """A short description of a value for a difference report."""
    s = json.dumps(v, ensure_ascii=False)
    return s if len(s) <= 80 else s[:77] + '...'


def ids(v):
    """The "id" of every element if v is an array of objects that all have one (districts, buildings), else None."""
    if isinstance(v, list) and v and all(isinstance(x, dict) and 'id' in x for x in v):
        return [x['id'] for x in v]
    return None


def diff(a, b, path, out):
    """Appends up to MAX_DIFFS differences between expected a and actual b to out, depth first."""
    if len(out) >= MAX_DIFFS:
        return
    num = (int, float)
    if isinstance(a, num) and isinstance(b, num) and not isinstance(a, bool) and not isinstance(b, bool):
        if float(a) != float(b):
            out.append(f'{path}: expected {label(a)}, got {label(b)}')
        return
    if type(a) is not type(b):
        out.append(f'{path}: expected {label(a)}, got {label(b)}')
    elif isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in b:
                out.append(f'{path}.{k}: missing (expected {label(a[k])})')
            elif k not in a:
                out.append(f'{path}.{k}: unexpected (got {label(b[k])})')
            else:
                diff(a[k], b[k], f'{path}.{k}', out)
            if len(out) >= MAX_DIFFS:
                return
    elif isinstance(a, list):
        ia, ib = ids(a), ids(b)
        if ia is not None and ib is not None and ia != ib:
            # Elements identified by id: name what is missing or extra instead of a cascade of shifted elements.
            missing = [x for x in ia if x not in set(ib)]
            extra = [x for x in ib if x not in set(ia)]
            out.append(f'{path}: ids differ; missing {label(missing)}, unexpected {label(extra)}'
                       if missing or extra else f'{path}: same ids in a different order')
            return
        if len(a) != len(b):
            out.append(f'{path}: expected {len(a)} elements, got {len(b)}')
        for i, (x, y) in enumerate(zip(a, b)):
            name = f'{path}[{i}]' + (f'{{{x["id"]}}}' if isinstance(x, dict) and 'id' in x else '')
            diff(x, y, name, out)
            if len(out) >= MAX_DIFFS:
                return
    elif a != b:
        out.append(f'{path}: expected {label(a)}, got {label(b)}')


def main(argv):
    if len(argv) == 3 and argv[1] == 'normalise':
        print(dumps(normalise(load(argv[2]))))
        return 0
    if len(argv) == 3 and argv[1] == 'count':
        print(len(load(argv[2]).get('buildings', [])))
        return 0
    if len(argv) == 4 and argv[1] == 'compare':
        out = []
        diff(normalise(load(argv[2])), normalise(load(argv[3])), '$', out)
        for line in out:
            print('   ', line)
        return 1 if out else 0
    print(__doc__.split('\n\n')[1], file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
