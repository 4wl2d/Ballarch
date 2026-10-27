#!/bin/sh
# Parameter ranges of ballarch_issuer. Each "reject" set must be refused by all
# three tools with the block's own message (Verilator lint and Yosys at
# elaboration, Icarus when the simulation starts); each "accept" set must pass
# lint and the structural checks. No parameter is truncated to fit a counter.
# Usage: checks/params.sh VERILATOR YOSYS IVERILOG VVP BUILD_DIR
VERILATOR=${1:-verilator}
YOSYS=${2:-yosys}
IVERILOG=${3:-iverilog}
VVP=${4:-vvp}
BUILD=${5:-build}
mkdir -p "$BUILD"
RTL=rtl/ballarch_issuer.sv
status=0

# verdict  TIMEOUT MAX_ATTEMPTS CMD_W RESP_WINDOW  reason
while read -r verdict t m c w reason; do
  case "$verdict" in reject|accept) ;; *) continue ;; esac
  name="TIMEOUT=$t MAX_ATTEMPTS=$m CMD_W=$c RESP_WINDOW=$w"
  vlog="$BUILD/params_v.log"; ylog="$BUILD/params_y.log"; ilog="$BUILD/params_i.log"
  $VERILATOR --lint-only -Wall -GTIMEOUT=$t -GMAX_ATTEMPTS=$m -GCMD_W=$c -GRESP_WINDOW=$w $RTL > "$vlog" 2>&1; v=$?
  $YOSYS -q -p "read_verilog -sv $RTL; hierarchy -top ballarch_issuer -chparam TIMEOUT $t -chparam MAX_ATTEMPTS $m -chparam CMD_W $c -chparam RESP_WINDOW $w; script checks/rules.ys" > "$ylog" 2>&1; y=$?
  if [ "$verdict" = accept ]; then
    if [ $v -eq 0 ] && [ $y -eq 0 ]; then echo "accepted, as it must: $name ($reason)"
    else echo "REJECTED, but must be accepted: $name"; cat "$vlog" "$ylog" | grep -m3 -iE 'error|warning'; status=1; fi
  else
    $IVERILOG -g2012 -Ptb_issuer.TIMEOUT=$t -Ptb_issuer.MAX_ATTEMPTS=$m -Ptb_issuer.CMD_W=$c -Ptb_issuer.RESP_WINDOW=$w \
      -o "$BUILD/params_tb" tb/tb_pkg.sv $RTL tb/tb_device.sv tb/tb_issuer.sv > "$ilog" 2>&1 && $VVP -n "$BUILD/params_tb" >> "$ilog" 2>&1
    if [ $v -ne 0 ] && grep -q 'ballarch_issuer: need' "$vlog" && [ $y -ne 0 ] && grep -q 'ballarch_issuer: need' "$ylog" \
       && grep -q 'ballarch_issuer: parameters out of range' "$ilog" && ! grep -q 'RESULT:' "$ilog"; then
      echo "rejected by Verilator, Yosys and Icarus: $name ($reason)"
    else
      echo "NOT rejected by every tool: $name (verilator=$v yosys=$y)"; status=1
    fi
  fi
done <<'EOF'
reject 0  2 4 63   TIMEOUT below 1
reject 12 0 4 63   MAX_ATTEMPTS below 1
reject 1  1 0 3    CMD_W below 1
reject 1  1 29 3   CMD_W above 28
reject 40 5 4 63   retries outlast the response window (truncated to 8 cycles and 1 attempt before)
reject 12 2 4 20   retries outlast the response window
reject 1073741823 2 4 63   retries span 2**31 cycles (wrapped to a negative number in 32 bits)
reject 1 1073741825 4 63   retries span 2**32 cycles (wrapped to 0 in 32 bits)
reject 12 2 4 64   window too long for 4-bit command ids
reject 12 2 2 15   window too long for 2-bit command ids
accept 40 5 9 400  long timeout and five attempts
accept 5  3 4 40   the bench's second parameter set
accept 1  1 1 3    the smallest legal block
accept 2147483647 1 4 63   the longest timeout, with one attempt
EOF
exit $status
