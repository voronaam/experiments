#!/bin/sh
# Runs every test suite of the three editions, then the cross-edition parity check on the fixtures, and prints a
# summary. Usage:
#
#   scripts/test-all.sh
#
# Steps, each logged to its own file (the end of a failing step's log is printed):
#
#   java      ./mvnw -q package            JUnit suite, then target/codecity.jar
#   rust      cargo test --release         #[test] suite, then cargo build --release
#   d         dub test                     unittest suite, then dub build -b release --force (dub test overwrites
#                                          bin/codecity with the test runner)
#   web       scripts/test-web.sh          node --test on the browser viewer's modules (skipped without Node)
#   parity    scripts/parity.sh            the three binaries on the fixtures, compared with each other and with
#                                          testdata/golden/
#
# The golden-file tests (GoldenCityTest, golden_tests.rs, golden.d) are part of each edition's own suite. A step that
# fails does not stop the others, except that parity needs all three builds. Without network access, set
# MAVEN_ARGS=-o and CARGO_NET_OFFLINE=true. Exit status: 0 when every step passed.
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd)
LOGS=$(mktemp -d "${TMPDIR:-/tmp}/codecity-test-all.XXXXXX")
summary=
failed=0

# step <name> <directory> <command...>: runs the command there, logging to $LOGS/<name>.log, and records the result.
step() {
    name=$1 dir=$2
    shift 2
    printf '%-8s %s ... ' "$name" "$*"
    start=$(date +%s)
    if (cd "$dir" && "$@") >"$LOGS/$name.log" 2>&1; then
        result=passed
    else
        result=FAILED
        failed=1
    fi
    secs=$(($(date +%s) - start))
    echo "$result (${secs}s)"
    if [ $result = FAILED ]; then
        tail -n 30 "$LOGS/$name.log" | sed 's/^/    /'
    fi
    summary="$summary$(printf '%-8s %-7s %4ss  %s' "$name" "$result" "$secs" "$LOGS/$name.log")
"
    [ $result = passed ]
}

built=1
step java "$REPO" ./mvnw -q package || built=
step rust "$REPO/rust" sh -c 'cargo test --release && cargo build --release' || built=
step d "$REPO/d" sh -c 'dub test && dub build -b release --force' || built=
step web "$REPO" scripts/test-web.sh
if [ -n "$built" ]; then
    step parity "$REPO" scripts/parity.sh
else
    echo "parity   skipped: an edition did not build"
    summary="${summary}parity   skipped
"
    failed=1
fi

echo
echo "Summary (logs in $LOGS):"
printf '%s' "$summary"
exit $failed
