# Builds a hostile git repository in the current (empty) directory, for the security tests of all three editions
# (Java HostileRepoTest, Rust history.rs tests, D history.d unittests). Usage:
#
#   sh hostile-repo.sh <marker directory>
#
# The marker directory must exist and be absolute. Two kinds of attack are planted:
#
# 1. .git/config sets every key we know of that makes git run a program (fsmonitor, hooks, pager, gpg for
#    log.showSignature, external diff and textconv, filters, credential helper, ssh/proxy commands, upload-pack of a
#    promisor remote for lazy fetches, ...). Each program only creates "<marker directory>/hit-<key>", so a test
#    asserts that no "hit-*" file appears after CodeCity has run its git commands here.
# 2. The commits contain tree entries git itself would never check out: "../../EscA.java" (two ".." directories),
#    an entry whose name is the absolute marker directory (so the path is "<marker directory>/EscB.java"), a ".git"
#    directory and a backslash name. The history replay must not write any of them; a test asserts that neither
#    "<temp dir>/EscA.java" nor "<marker directory>/EscB.java" exists afterwards.
#
# A second remote, "gh", has a GitHub URL, so that GitHub detection runs all of its git commands.
#
# The newest commit is GPG-"signed" (a gpgsig header), so that log.showSignature would call gpg.program, and adds a
# file whose blob is then deleted from the object database, so that reading it would make git fetch it lazily from
# the promisor remote.
set -e
M="$1"
B="$M/bin"
mkdir -p "$B/hooks"
# One program per key: it records that it ran and fails.
prog() {
    printf '#!/bin/sh\ntouch "%s/hit-%s"\nexit 1\n' "$M" "$2" > "$1"
    chmod +x "$1"
}
for k in fsmonitor pager gpg gpgssh gpgx509 diffext textconv diffcmd filter-process filter-smudge filter-clean \
         cred ssh gitproxy askpass editor altrefs uploadpack receivepack ext; do
    prog "$B/$k" "$k"
done
for h in pre-commit post-checkout post-merge post-rewrite reference-transaction pre-auto-gc post-index-change \
         pre-push fsmonitor-watchman push-to-checkout; do
    prog "$B/hooks/$h" "hook-$h"
done

export GIT_AUTHOR_NAME=Ann GIT_AUTHOR_EMAIL=ann@example.com GIT_COMMITTER_NAME=Ann GIT_COMMITTER_EMAIL=ann@example.com
git init -q .

# tree <mode> <name> <sha> ...: writes a tree object with exactly these entries (no validation, no sorting).
tree() {
    while [ $# -gt 0 ]; do
        printf '%s %s\0' "$1" "$2"
        hex2bin "$3"
        shift 3
    done | git hash-object -t tree -w --literally --stdin
}
# The 20 raw bytes of a 40-hex object id (octal escapes, which every printf understands).
hex2bin() {
    h=$1
    while [ -n "$h" ]; do
        printf "\\$(printf %o "0x${h%"${h#??}"}")"
        h=${h#??}
    done
}
blob() { printf '%s\n' "$1" | git hash-object -w --stdin; }
# commit <date> <tree> [<parent>]
commit() {
    GIT_AUTHOR_DATE="$1 +0000" GIT_COMMITTER_DATE="$1 +0000" git commit-tree ${3:+-p "$3"} -m "c $1" "$2"
}

a=$(blob 'class A { void a() {} }')
esc=$(blob 'class Esc {}')
dotdot=$(tree 40000 .. "$(tree 100644 EscA.java "$esc")")
abs=$(tree 100644 EscB.java "$esc")
dotgit=$(tree 100644 X.java "$esc")
# 1: A.java and ../../EscA.java (read with ls-tree)
c1=$(commit 2020-01-01T00:00:00 "$(tree 40000 .. "$dotdot" 100644 A.java "$a")")
# 2: adds <marker>/EscB.java, .git/X.java and a backslash name (read with diff-tree)
t2=$(tree 40000 "$M" "$abs" 40000 .. "$dotdot" 40000 .git "$dotgit" 100644 A.java "$a" 100644 'B\..\..\EscC.java' "$esc")
c2=$(commit 2020-01-02T00:00:00 "$t2" "$c1")
# 3: adds Z.java, whose blob goes missing below; signed
z=$(blob 'class Z {}')
t3=$(tree 40000 "$M" "$abs" 40000 .. "$dotdot" 40000 .git "$dotgit" 100644 A.java "$a" 100644 'B\..\..\EscC.java' "$esc" 100644 Z.java "$z")
c3=$( {
    printf 'tree %s\nparent %s\n' "$t3" "$c2"
    printf 'author Ann <ann@example.com> 1578009600 +0000\ncommitter Ann <ann@example.com> 1578009600 +0000\n'
    printf 'gpgsig -----BEGIN PGP SIGNATURE-----\n \n iQEzBAABCAAdFiEE\n -----END PGP SIGNATURE-----\n\nc 3\n'
} | git hash-object -t commit -w --literally --stdin)
git update-ref refs/heads/master "$c3"
git symbolic-ref HEAD refs/heads/master
rm -f ".git/objects/$(echo "$z" | cut -c1-2)/$(echo "$z" | cut -c3-)"
printf '* filter=x diff=x\n' > .gitattributes

# A second repository to be the promisor remote; it is never actually fetched from.
git init -q --bare "$M/other.git"

cat >> .git/config <<EOF
[core]
	repositoryformatversion = 1
	fsmonitor = $B/fsmonitor
	hooksPath = $B/hooks
	pager = $B/pager
	sshCommand = $B/ssh
	gitProxy = $B/gitproxy
	askPass = $B/askpass
	editor = $B/editor
	alternateRefsCommand = $B/altrefs
[extensions]
	partialClone = origin
[remote "origin"]
	url = $M/other.git
	promisor = true
	partialclonefilter = blob:none
	uploadpack = $B/uploadpack
	receivepack = $B/receivepack
	fetch = +refs/heads/*:refs/remotes/origin/*
[remote "gh"]
	url = https://github.com/o/r
[log]
	showSignature = true
[gpg]
	program = $B/gpg
[gpg "ssh"]
	program = $B/gpgssh
[gpg "x509"]
	program = $B/gpgx509
[diff]
	external = $B/diffext
[diff "x"]
	textconv = $B/textconv
	command = $B/diffcmd
[filter "x"]
	process = $B/filter-process
	smudge = $B/filter-smudge
	clean = $B/filter-clean
[credential]
	helper = $B/cred
[protocol]
	allow = always
[protocol "file"]
	allow = always
[protocol "ext"]
	allow = always
[pager]
	log = $B/pager
	branch = $B/pager
[gc]
	auto = 1
EOF
