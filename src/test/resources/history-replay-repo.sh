# Builds the history of the replay rule tests of all three editions (Java CityHistoryTest, Rust history.rs tests,
# D history.d unittests) in the current, empty directory. Usage:
#
#   sh history-replay-repo.sh
#
# Fixed authors, committers and dates; the user's git configuration is not read. Each commit exercises one rule of the
# replay (the union city, gone types, a type that comes back, a district that empties, per-snapshot git activity, a
# shrinking type whose lot keeps its widest size, test code, and a file outside the analysed sub-directory):
#
#   1  2020-01-01  Ann  src/A.java (A: 3 fields), src/p/P.java (p.P), src/test/ATest.java (test code), other/O.java
#   2  2020-01-02  Bob  A shrinks to 1 field; src/p/P.java deleted; src/C.java and src/D.java added
#   3  2020-01-03  Ann  src/C.java and src/D.java deleted
#   4  2020-01-04  Cid  src/C.java added again, now with a method
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

# commit <author name> <author e-mail> <day as YYYY-MM-DD> <subject>: commits everything, staged and unstaged.
commit() {
    git add -A
    GIT_AUTHOR_NAME="$1" GIT_AUTHOR_EMAIL="$2" GIT_AUTHOR_DATE="$3T00:00:00 +0000" \
        GIT_COMMITTER_DATE="$3T01:00:00 +0000" git commit -q -m "$4"
}

mkdir -p src/p src/test other
echo 'class A { int a, b, c; void m() {} }' > src/A.java
echo 'package p; class P {}' > src/p/P.java
echo 'class ATest {}' > src/test/ATest.java
echo 'class O {}' > other/O.java
commit "Ann Author" ann@example.com 2020-01-01 "one"

echo 'class A { int a; void m() {} }' > src/A.java
rm src/p/P.java
echo 'class C {}' > src/C.java
echo 'class D {}' > src/D.java
commit "Bob Builder" bob@example.com 2020-01-02 "two"

rm src/C.java src/D.java
commit "Ann Author" ann@example.com 2020-01-03 "three"

echo 'class C { void c() {} }' > src/C.java
commit "Cid Coder" cid@example.com 2020-01-04 "four"
