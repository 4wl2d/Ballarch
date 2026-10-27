#!/bin/sh
# A negative control for check_purity.sh: the fixture must fail, and the output must contain every
# expected message, so that each check is shown to catch what it is meant to catch.
#
# Usage (from reference/c): sh tools/expect_impure.sh 'message' ['message']... -- [--uses pure.c]... fixture.c [cc flags]
set -u
expected=""
n=0
while [ "$1" != "--" ]; do
    n=$((n + 1))
    eval "expected_$n=\$1"
    shift
done
shift
out=$(sh tools/check_purity.sh "$@" 2>&1) && {
    echo "negative control not detected: $*"
    echo "$out"
    exit 1
}
i=1
while [ "$i" -le "$n" ]; do
    eval "message=\$expected_$i"
    if ! printf '%s\n' "$out" | grep -qF "$message"; then
        echo "negative control rejected, but not for \"$message\": $*"
        echo "$out"
        exit 1
    fi
    expected="$expected \"$message\""
    i=$((i + 1))
done
fixture=""
for arg in "$@"; do case $arg in *.c) fixture=$arg ;; esac; done
echo "negative control rejected as expected:$expected ($fixture)"
