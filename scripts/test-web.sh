#!/bin/sh
# Runs the browser viewer's unit tests: src/test/web/*.test.mjs with Node's built-in test runner (node:test and
# node:assert only; no npm packages, no package.json). They test the viewer's pure modules in
# src/main/resources/web (walk.js, replay.js, text.js, metrics.js, search.js, city.js, prefs.js), which use neither
# three.js nor the DOM. Usage:
#
#   scripts/test-web.sh [extra node --test options]
#
# Needs Node 22 or later on PATH (the tests use the test runner's glob support); without it the tests are skipped
# with a message and exit status 0, so the other editions' checks still run where Node is not installed.
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd)
if ! command -v node >/dev/null 2>&1; then
    echo "test-web: skipped, node is not installed (Node 22 or later runs src/test/web/*.test.mjs)"
    exit 0
fi
major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
if [ "$major" -lt 22 ]; then
    echo "test-web: skipped, node $(node --version) is too old (Node 22 or later runs src/test/web/*.test.mjs)"
    exit 0
fi
cd "$REPO" && exec node --test "$@" 'src/test/web/*.test.mjs'
