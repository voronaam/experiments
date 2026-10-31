# Builds the history of the git-mining tests of all three editions (Java GitHistoryTest, Rust git.rs tests, D git.d
# unittests) in the current, empty directory. Usage:
#
#   sh git-log-repo.sh
#
# Fixed authors, committers and dates; the user's git configuration is not read. The commits cover what the per-file
# metrics (commits, distinct authors, first and last author time) have to get right:
#
#   1  2020-01-01  Ann <ann@example.com>       A.java, M.java, r/lib.rs, web/c.ts, notes.txt (no source), .mailmap
#   2  2020-01-02  Bob <bob@example.com>       A.java, r/lib.rs changed
#   3  2020-01-03  Ann <ann@old.example>       A.java changed; .mailmap maps this address to ann@example.com
#   4  2020-01-04  Cid <cid@example.com>       on branch "side": A.java changed
#   5  2020-01-05  Bob <bob@example.com>       on main: r/lib.rs changed
#   6  2020-01-06  Ann <ann@example.com>       merge of "side" that also changes M.java itself (merges do not count)
#   7  2020-01-07  Bob <bob@example.com>       web/c.ts renamed to web/d.ts (renames are not followed)
#
# The committer is always "CI Bot", one hour after the author, so a test can tell author times from committer times.
set -e
# Ignore the user's and the system's git configuration (signing, hooks, templates, default branch, line endings).
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null HOME=/nonexistent
export GIT_COMMITTER_NAME="CI Bot" GIT_COMMITTER_EMAIL=ci@example.com
git init -q --template= .
git symbolic-ref HEAD refs/heads/main
git config commit.gpgsign false
git config core.autocrlf false

# at <author name> <author e-mail> <day as YYYY-MM-DD>: exports that author and the dates for the next git command.
at() {
    export GIT_AUTHOR_NAME="$1" GIT_AUTHOR_EMAIL="$2" GIT_AUTHOR_DATE="$3T00:00:00 +0000" \
        GIT_COMMITTER_DATE="$3T01:00:00 +0000"
}

# commit <author name> <author e-mail> <day> <subject>: commits everything, staged and unstaged.
commit() {
    git add -A
    at "$1" "$2" "$3"
    git commit -q -m "$4"
}

mkdir -p r web
echo 'class A {}' > A.java
echo 'class M {}' > M.java
echo 'pub struct R;' > r/lib.rs
echo 'export class C {}' > web/c.ts
echo 'not a source file' > notes.txt
echo 'Ann Author <ann@example.com> <ann@old.example>' > .mailmap
commit "Ann Author" ann@example.com 2020-01-01 "one"

echo 'class A { int b; }' > A.java
echo 'pub struct R { b: u8 }' > r/lib.rs
commit "Bob Builder" bob@example.com 2020-01-02 "two"

echo 'class A { int b, c; }' > A.java
commit "Ann Author" ann@old.example 2020-01-03 "three"

git checkout -q -b side
echo 'class A { int b, c, d; }' > A.java
commit "Cid Coder" cid@example.com 2020-01-04 "four"

git checkout -q main
echo 'pub struct R { b: u8, c: u8 }' > r/lib.rs
commit "Bob Builder" bob@example.com 2020-01-05 "five"

at "Ann Author" ann@example.com 2020-01-06
git merge -q --no-ff --no-commit side >/dev/null 2>&1
echo 'class M { int m; }' > M.java
git add -A
git commit -q -m "six"

git mv web/c.ts web/d.ts
commit "Bob Builder" bob@example.com 2020-01-07 "seven"
