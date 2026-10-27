#!/bin/sh
# Run one seeded bug: build its bench with the given Icarus options, run it to
# the end, and require a failed check, and if EXPECT is not "-" a failure whose
# message contains EXPECT. A build error or a crash does not count as a kill.
# The bench's sources come from the environment (TB_ISSUER, TB_COMPOSITE).
# Usage: checks/mutant.sh IVERILOG VVP BUILD_DIR NAME BENCH EXPECT [IVERILOG OPTIONS...]
IVERILOG=$1
VVP=$2
BUILD=$3
NAME=$4
BENCH=$5
EXPECT=$6
shift 6
case "$BENCH" in
  tb_issuer) FILES=$TB_ISSUER ;;
  tb_composite) FILES=$TB_COMPOSITE ;;
  *) echo "mutant $NAME: unknown bench $BENCH"; exit 1 ;;
esac
if [ -z "$FILES" ]; then echo "mutant $NAME: no sources for $BENCH"; exit 1; fi
if ! "$IVERILOG" -g2012 "$@" -s "$BENCH" -o "$BUILD/tb_$NAME" $FILES > "$BUILD/$NAME.build.log" 2>&1; then
  echo "mutant $NAME does not build:"
  cat "$BUILD/$NAME.build.log"
  exit 1
fi
"$VVP" -n "$BUILD/tb_$NAME" +verbose > "$BUILD/$NAME.log" 2>&1
if ! grep -q 'RESULT: FAIL' "$BUILD/$NAME.log"; then
  echo "mutant $NAME survived"
  exit 1
fi
if [ "$EXPECT" = "-" ]; then
  echo "mutant $NAME killed: $(grep -m1 '^FAIL' "$BUILD/$NAME.log")"
elif grep -qF -- "$EXPECT" "$BUILD/$NAME.log"; then
  echo "mutant $NAME killed: $(grep -m1 -F -- "$EXPECT" "$BUILD/$NAME.log")"
else
  echo "mutant $NAME failed, but no check reported '$EXPECT'"
  exit 1
fi
