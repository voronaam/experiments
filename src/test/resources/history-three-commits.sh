# Builds the shared three-commit history of the replay tests of all three editions (Java CityHistoryTest, Rust
# history.rs tests, D history.d unittests) in the current, empty directory. Usage:
#
#   sh history-three-commits.sh
#
# Every commit has the same fixed author and committer ("Ann Author" <ann@example.com>) and dates one day apart, and
# the user's git configuration is not read, so the scenario is literally the same in every edition:
#
#   1  2020-01-01T00:00:00Z  add A    src/A.java: class A with one method
#   2  2020-01-02T00:00:00Z  add B    src/B.java: class B with two methods; A gets a second method
#   3  2020-01-03T00:00:00Z  drop B   src/B.java deleted; A gets a field
set -e
# Ignore the user's and the system's git configuration (signing, hooks, templates, default branch, line endings).
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null HOME=/nonexistent
export GIT_AUTHOR_NAME="Ann Author" GIT_AUTHOR_EMAIL=ann@example.com
export GIT_COMMITTER_NAME="Ann Author" GIT_COMMITTER_EMAIL=ann@example.com
git init -q --template= .
git symbolic-ref HEAD refs/heads/main
git config commit.gpgsign false
git config core.autocrlf false

# commit <date> <subject>: commits everything, staged and unstaged, with that author and committer date.
commit() {
    git add -A
    GIT_AUTHOR_DATE="$1 +0000" GIT_COMMITTER_DATE="$1 +0000" git commit -q -m "$2"
}

mkdir src
echo 'class A { void a() {} }' > src/A.java
commit 2020-01-01T00:00:00 "add A"
echo 'class B { void x() {} void y() {} }' > src/B.java
echo 'class A { void a() {} void b() {} }' > src/A.java
commit 2020-01-02T00:00:00 "add B"
rm src/B.java
echo 'class A { int f; void a() {} void b() {} }' > src/A.java
commit 2020-01-03T00:00:00 "drop B"
