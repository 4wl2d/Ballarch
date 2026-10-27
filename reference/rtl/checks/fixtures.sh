#!/bin/sh
# Run the structural checks (checks/rules.ys) on every fixture in lint/.
# A fixture's "// EXPECT:" line says what must happen: "pass", or an extended
# regular expression that the failing run's log must match, so that each
# negative control fails for the reason it was written for.
# Usage: checks/fixtures.sh YOSYS BUILD_DIR
YOSYS=${1:-yosys}
BUILD=${2:-build}
mkdir -p "$BUILD"
status=0
for f in lint/*.sv; do
  top=$(basename "$f" .sv)
  log="$BUILD/$top.log"
  expect=$(sed -n 's,^// EXPECT: ,,p' "$f")
  if [ -z "$expect" ]; then echo "$f: no EXPECT line"; status=1; continue; fi
  if "$YOSYS" -q -p "read_verilog -sv $f; hierarchy -top $top; script checks/rules.ys" > "$log" 2>&1; then
    if [ "$expect" = "pass" ]; then echo "$f passes, as it must"
    else echo "$f was NOT rejected"; status=1; fi
  elif [ "$expect" = "pass" ]; then
    echo "$f was rejected, but must pass:"; grep -m2 ERROR "$log"; status=1
  elif grep -qE "$expect" "$log"; then
    echo "$f rejected: $(grep -m1 -oE "$expect" "$log" | cut -c1-80)"
  else
    echo "$f rejected for another reason than '$expect':"; grep -m2 ERROR "$log"; status=1
  fi
done
exit $status
