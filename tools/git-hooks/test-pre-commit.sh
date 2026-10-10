#!/bin/sh
# FILE: tools/git-hooks/test-pre-commit.sh
# ROLE: SCRIPT
#
# PURPOSE: Self-test of the SEC-03 pre-commit hook in a throwaway repository: 6 cases that must
#   be blocked (incl. range mode) and 2 that must be allowed. Prints PASS/FAIL per case.
#
# USAGE (Git Bash, from the repo root): sh tools/git-hooks/test-pre-commit.sh
#
# NOTE: this file is itself committed through the hook and scanned in range mode, so every
#   blocked payload is assembled at runtime from fragments. No line here may match a hook rule.
#
# EXIT CODE: 0 only if all cases PASS.

# START_BLOCK_SETUP
HOOK_SRC=$(cd "$(dirname "$0")" && pwd)/pre-commit
[ -f "$HOOK_SRC" ] || { echo "FAIL setup: hook not found at $HOOK_SRC"; exit 2; }

WORK=$(mktemp -d "${TMPDIR:-/tmp}/hooktest.XXXXXX") || { echo "FAIL setup: mktemp"; exit 2; }
trap 'cd /; rm -rf "$WORK"' EXIT
cd "$WORK" || exit 2
git init -q .
git config user.name hooktest
git config user.email hooktest@example.invalid
git config commit.gpgsign false
git config core.autocrlf false
mkdir -p .git/hooks
tr -d '\r' < "$HOOK_SRC" > .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
# END_BLOCK_SETUP

# START_BLOCK_PAYLOADS
K='"Init'; K="${K}String\""
V=$(printf 'E%.0s' $(seq 1 30))
H='hm'; H="${H}ac="
V2=$(printf 'A%.0s' $(seq 1 27))
W='JADX'; W="$W WARN"
# END_BLOCK_PAYLOADS

fails=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; fails=$((fails + 1)); }

# Clean the index and working files between cases.
reset_case() {
    git reset -q >/dev/null 2>&1
    for f in "$@"; do rm -rf "$f"; done
}

# expect_block <case> <path...>: stage the paths (force, bypassing any ignore), commit must fail.
expect_block() {
    name=$1; shift
    git add -f -- "$@"
    if git commit -q -m "case $name" >/dev/null 2>&1; then
        fail "$name"
        git reset -q --soft HEAD~1 2>/dev/null
    else
        pass "$name"
    fi
    reset_case "$@"
}

# expect_allow <case> <path>: commit must succeed.
expect_allow() {
    name=$1; shift
    git add -f -- "$@"
    if git commit -q -m "case $name" >/dev/null 2>&1; then
        pass "$name"
    else
        fail "$name"
        reset_case "$@"
    fi
}

# START_BLOCK_ALLOW_CASES
printf 'plain text\n' > Readme.md
expect_allow allow-plain-readme Readme.md

printf 'val j = %s:"SYNTH%s"\n' "$K" "$V" > Fixture.kt
expect_allow allow-synth-fixture Fixture.kt
# END_BLOCK_ALLOW_CASES

# START_BLOCK_BLOCK_CASES
mkdir -p lib/arm64-v8a
printf '\177ELF fake\n' > lib/arm64-v8a/libPPPP_API.so
expect_block block-native-lib lib/arm64-v8a/libPPPP_API.so
rm -rf lib

mkdir -p captures
printf 'flow bytes\n' > captures/run.flows
expect_block block-capture-flows captures/run.flows
rm -rf captures

printf 'val j = %s:"%s"\n' "$K" "$V" > Leak.kt
expect_block block-initstring-value Leak.kt

printf 'val u = "https://gw.example.invalid/v5/x?seq=1&%s%s"\n' "$H" "$V2" > Leak2.kt
expect_block block-hmac-query Leak2.kt

printf 'class A {\n    // %s: Code restructure failed\n}\n' "$W" > Decomp.java
expect_block block-decompiled-java Decomp.java
# END_BLOCK_BLOCK_CASES

# START_BLOCK_RANGE_CASE
# Simulate a commit that reached history without the hook, then scan it in range mode.
mv .git/hooks/pre-commit .git/hooks/pre-commit.off
printf 'val j = %s:"%s"\n' "$K" "$V" > Leak.kt
git add -f Leak.kt
git commit -q -m "bypassed" >/dev/null 2>&1
mv .git/hooks/pre-commit.off .git/hooks/pre-commit
sh .git/hooks/pre-commit --range HEAD~1..HEAD >/dev/null 2>&1
rc=$?
if [ "$rc" -eq 1 ]; then pass block-range-mode; else fail "block-range-mode (exit=$rc)"; fi
# END_BLOCK_RANGE_CASE

if [ "$fails" -ne 0 ]; then
    echo "RESULT: $fails case(s) failed"
    exit 1
fi
echo "RESULT: all 8 cases passed"
exit 0
