#!/bin/sh
# Cross-edition parity: runs the Java, Rust and D editions on the same source trees and checks that they export the
# same city JSON (after the normalisation of scripts/city_json.py). Builds nothing: build the three editions first.
#
# Usage: scripts/parity.sh [--history] [--update-golden] [--keep] [repo ...]
#
#   (no repo)        the fixtures: testdata/{java,ts,rust,mixed}-project with --no-git, each with and without
#                    --no-tests, and the repository of testdata/make-history-repo.sh with git and with --history.
#                    Each result is also compared with its golden file in testdata/golden/.
#   repo ...         instead, these repositories: local paths or GitHub URLs (cloned into the CodeCity cache by the
#                    editions themselves), analysed with git history. No golden files are involved.
#   --history        with repos: analyse them with the history replay (--history).
#   --update-golden  with the fixtures: rewrite testdata/golden/ from the results, but only if all three editions
#                    agree on every fixture. Same as UPDATE_GOLDEN=1.
#   --keep           keep the exported files (their directory is printed).
#
# The editions are found at target/codecity.jar (./mvnw package), rust/target/release/codecity
# (cargo build --release) and d/bin/codecity (dub build -b release); override with JAVA_JAR, RUST_BIN and D_BIN.
# Exit status: 0 when every tree is identical in all editions (and matches its golden file), 1 otherwise.
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd)
JSON="$REPO/scripts/city_json.py"
JAVA_JAR=${JAVA_JAR:-$REPO/target/codecity.jar}
RUST_BIN=${RUST_BIN:-$REPO/rust/target/release/codecity}
D_BIN=${D_BIN:-$REPO/d/bin/codecity}
GOLDEN="$REPO/testdata/golden"

usage() {
    sed -n '2,/^set -u/p' "$0" | sed '$d; s/^# \{0,1\}//' >&2
    exit 2
}

history=; update=${UPDATE_GOLDEN:+1}; keep=
while [ $# -gt 0 ]; do
    case "$1" in
        --history) history=1 ;;
        --update-golden) update=1 ;;
        --keep) keep=1 ;;
        -h|--help) usage ;;
        -*) echo "unknown option $1" >&2; usage ;;
        *) break ;;
    esac
    shift
done
if [ -n "$update" ] && [ $# -gt 0 ]; then
    echo "--update-golden only works with the fixtures (no repo arguments)" >&2
    exit 2
fi
for f in "$JAVA_JAR" "$RUST_BIN" "$D_BIN"; do
    if [ ! -f "$f" ]; then
        echo "missing $f: build the three editions first (or set JAVA_JAR, RUST_BIN, D_BIN)" >&2
        exit 2
    fi
done

OUT=$(mktemp -d "${TMPDIR:-/tmp}/codecity-parity.XXXXXX")
if [ -n "$keep" ]; then
    echo "exports in $OUT"
else
    trap 'rm -rf "$OUT"' EXIT
fi
failed=0

# export_city <edition> <case> <tree> <options...>: writes $OUT/<case>-<edition>.html; on failure prints the end of the
# edition's output and returns non-zero.
export_city() {
    ed=$1 name=$2 tree=$3
    shift 3
    case $ed in
        java) set -- java -jar "$JAVA_JAR" "$@" ;;
        rust) set -- "$RUST_BIN" "$@" ;;
        d) set -- "$D_BIN" "$@" ;;
    esac
    if ! "$@" --no-github --export "$OUT/$name-$ed.html" "$tree" >"$OUT/$name-$ed.log" 2>&1; then
        echo "$name: $ed failed:"
        tail -n 3 "$OUT/$name-$ed.log" | sed 's/^/    /'
        return 1
    fi
}

# check <case> <tree> <options...>: exports the tree with every edition, compares Rust and D with Java, and (for a
# fixture) Java with the golden file, or with --update-golden keeps the normalised result for the update.
check() {
    name=$1 tree=$2
    shift 2
    ok=1
    for ed in java rust d; do
        export_city $ed "$name" "$tree" "$@" || ok=
    done
    if [ -n "$ok" ]; then
        for ed in rust d; do
            if ! python3 "$JSON" compare "$OUT/$name-java.html" "$OUT/$name-$ed.html" >"$OUT/$name-$ed.diff"; then
                echo "$name: java and $ed differ (expected = java):"
                cat "$OUT/$name-$ed.diff"
                ok=
            fi
        done
    fi
    if [ -z "$ok" ]; then
        failed=1
        return
    fi
    count=$(python3 "$JSON" count "$OUT/$name-java.html")
    golden="$GOLDEN/$name.json"
    if [ -z "$fixture" ]; then
        echo "$name: identical ($count buildings)"
    elif [ -n "$update" ]; then
        python3 "$JSON" normalise "$OUT/$name-java.html" >"$OUT/$name.golden" || failed=1
        echo "$name: identical ($count buildings)"
    elif [ ! -f "$golden" ]; then
        echo "$name: identical ($count buildings), but there is no golden file $golden"
        failed=1
    elif python3 "$JSON" compare "$golden" "$OUT/$name-java.html" >"$OUT/$name-golden.diff"; then
        echo "$name: identical ($count buildings), matches the golden file"
    else
        echo "$name: identical ($count buildings), but differs from the golden file (expected = golden):"
        cat "$OUT/$name-golden.diff"
        failed=1
    fi
}

fixture=
if [ $# -eq 0 ]; then
    fixture=1
    for f in java-project ts-project rust-project mixed-project; do
        check "$f" "$REPO/testdata/$f" --no-git
        check "$f.no-tests" "$REPO/testdata/$f" --no-git --no-tests
    done
    # The history repository is built under a fixed directory name, which is the city's name.
    if sh "$REPO/testdata/make-history-repo.sh" "$OUT/history/history-repo"; then
        check history-repo "$OUT/history/history-repo"
        check history-repo.history "$OUT/history/history-repo" --history
    else
        echo "history-repo: make-history-repo.sh failed"
        failed=1
    fi
    if [ -n "$update" ]; then
        if [ $failed -ne 0 ]; then
            echo "The editions disagree: golden files left unchanged." >&2
            exit 1
        fi
        mkdir -p "$GOLDEN"
        for g in "$OUT"/*.golden; do
            cp "$g" "$GOLDEN/$(basename "$g" .golden).json"
        done
        echo "Golden files updated in $GOLDEN; review them with git diff."
    fi
else
    for r in "$@"; do
        name=$(basename "${r%/}" .git)
        if [ -n "$history" ]; then
            check "$name" "$r" --history
        else
            check "$name" "$r"
        fi
    done
fi
exit $failed
