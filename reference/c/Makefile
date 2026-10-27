# Ballarch firmware reference: build and check from this directory with `make`.
CC ?= cc
CFLAGS ?= -std=c11 -O2 -Wall -Wextra -Wpedantic -Wshadow -Wstrict-prototypes -Werror
INC := -Iinclude -Itest -Iexamples/dosing -Iexamples/transfer
HDR := $(wildcard include/*.h test/*.h examples/*/*.h)

RUNTIME := src/ballarch_runtime.c
KNOWLEDGE := src/ballarch_knowledge.c
SRC_knowledge := $(KNOWLEDGE) test/test_knowledge.c
SRC_runtime := $(RUNTIME) test/test_runtime.c
SRC_dosing := $(RUNTIME) examples/dosing/dosing.c examples/dosing/dosing_ball.c test/explore.c test/test_dosing.c
SRC_transfer := $(RUNTIME) $(KNOWLEDGE) examples/transfer/transfer.c examples/transfer/transfer_ball.c test/explore.c test/test_transfer.c
TESTS := knowledge runtime dosing transfer

# Seeded bugs, as test:MACRO:tag. Each must make that test fail with a counterexample from the
# oracle named by the tag; a crash, a sanitizer report or another oracle does not count.
# Preflight check 1 (outputs per decision) has no mutant: while BA_MAX_OUTPUTS equals
# BA_MAX_CALLS, check 2 refuses the same decisions.
MUTANTS := \
  dosing:BA_MUTANT_NO_WRITE_AHEAD:write-ahead \
  dosing:BA_MUTANT_NO_WRITE_AHEAD_ON_RETRY:write-ahead \
  dosing:BA_MUTANT_RESEND_AFTER_CRASH:resend \
  dosing:BA_MUTANT_EARLY_TIMER:timer-due \
  dosing:BA_MUTANT_32BIT_TIME:timer-due \
  dosing:BA_MUTANT_IGNORE_NVM_ERROR:write-ahead \
  dosing:BA_MUTANT_TIMEOUT_AS_DONE:outcome \
  dosing:BA_MUTANT_ONE_SLOT:completion \
  dosing:BA_MUTANT_KEEP_CLOSED_CALL:completion \
  dosing:BA_MUTANT_TRACE_BEFORE_COMMIT:commit \
  dosing:BA_MUTANT_NO_HEAD_CRC:quarantine \
  dosing:BA_MUTANT_EARLY_WAKEUP:livelock \
  dosing:TH_MUTANT_RETRY_UNKNOWN:spacing \
  dosing:TH_MUTANT_NO_HORIZON:spacing \
  dosing:TH_MUTANT_SHORT_INTERVAL:spacing \
  dosing:TH_MUTANT_EXTRA_DOSE:overdose \
  dosing:TH_MUTANT_UNKNOWN_AS_GIVEN:therapy \
  dosing:EX_MUTANT_NONDETERMINISM:replay \
  dosing:EX_MAX_DEPTH=40:bound \
  dosing:EX_MAX_EVENTS=3:bound \
  dosing:EX_MAX_LOG=2:bound \
  transfer:BA_MUTANT_NACK_ALWAYS_CONCLUSIVE:outcome \
  transfer:TF_MUTANT_UNKNOWN_AS_NO:claim \
  transfer:TF_MUTANT_UNMADE_AS_NO:claim \
  transfer:TF_MUTANT_PENDING_UNDO_AS_NO:claim \
  transfer:TF_MUTANT_NO_FENCE:claim \
  transfer:TF_MUTANT_STATUS_LIES:claim \
  transfer:TF_MUTANT_HAND_PAIR:seven-values \
  transfer:TF_MUTANT_NO_SETTLE:transfer \
  transfer:TF_MUTANT_FORGET:transfer \
  knowledge:BA_MUTANT_WEAK_AND:algebra \
  knowledge:BA_MUTANT_UNKNOWN_UNDO_COUNTS:algebra \
  knowledge:BA_MUTANT_OPEN_AS_NO:algebra \
  runtime:BA_MUTANT_PREFLIGHT=2:preflight-2 \
  runtime:BA_MUTANT_PREFLIGHT=3:preflight-3 \
  runtime:BA_MUTANT_PREFLIGHT=4:preflight-4 \
  runtime:BA_MUTANT_PREFLIGHT=5:preflight-5 \
  runtime:BA_MUTANT_PREFLIGHT=6:preflight-6 \
  runtime:BA_MUTANT_PREFLIGHT=7:preflight-7 \
  runtime:BA_MUTANT_PREFLIGHT=8:preflight-8 \
  runtime:BA_MUTANT_UNBOUNDED_KEYS:keys \
  runtime:BA_MUTANT_NO_QUARANTINE:quarantine \
  runtime:BA_MUTANT_NACK_ALWAYS_CONCLUSIVE:retry \
  runtime:BA_MUTANT_NO_WRITE_AHEAD_ON_RETRY:retry \
  runtime:BA_MUTANT_IGNORE_NVM_ERROR:nvm-error \
  runtime:BA_MUTANT_NO_BALL_CHECK:ball-check \
  runtime:BA_MUTANT_FRESH_ON_CORRUPT:corrupt \
  runtime:BA_MUTANT_NO_HEAD_CRC:corrupt \
  runtime:BA_MUTANT_QUARANTINE_ON_TORN:corrupt \
  runtime:BA_MUTANT_IGNORE_LAYOUT:layout \
  runtime:BA_MUTANT_STATE_IN_PLACE:state-copy \
  runtime:BA_MUTANT_32BIT_TIME:time

M0 := --target=thumbv6m-none-eabi -mcpu=cortex-m0 -ffreestanding

.PHONY: all test purity mutants sanitize deep clean
all: test purity mutants

build/test_%: $(HDR) $(RUNTIME) $(KNOWLEDGE) test/explore.c test/test_%.c examples/dosing/*.c examples/transfer/*.c
	@mkdir -p build
	$(CC) $(CFLAGS) $(INC) $(SRC_$*) -o $@

test: $(TESTS:%=build/test_%)
	./build/test_knowledge
	./build/test_runtime
	./build/test_dosing
	./build/test_transfer
	@if $(CC) -std=c11 -c -Iinclude tools/fixtures/oversize_state.c -o build/oversize.o 2> build/oversize.log; then \
	  echo "BA_STATE_FITS accepted a 40-byte state"; exit 1; \
	elif grep -q 'exceeds BA_MAX_STATE' build/oversize.log; then echo "BA_STATE_FITS rejects a 40-byte state (expected)"; \
	else cat build/oversize.log; exit 1; fi

# The deciders must pass; each negative control must be refused with its message. The messages
# that start with ": " come from the preprocessed-source checks, not from the clang AST check.
purity:
	@mkdir -p build
	sh tools/check_purity.sh examples/dosing/dosing.c
	sh tools/check_purity.sh src/ballarch_knowledge.c
	sh tools/check_purity.sh --uses src/ballarch_knowledge.c examples/transfer/transfer.c
	sh tools/check_purity.sh tools/fixtures/pure_decider.c
	@if command -v clang > /dev/null 2>&1; then \
	  set -e; for f in examples/dosing/dosing.c src/ballarch_knowledge.c tools/fixtures/pure_decider.c; do \
	    CC=clang sh tools/check_purity.sh $$f $(M0); done; \
	  CC=clang sh tools/check_purity.sh --uses src/ballarch_knowledge.c examples/transfer/transfer.c $(M0); \
	  sh tools/expect_impure.sh '(clang AST): casts to a pointer type' '(clang AST): calls through a function pointer' -- tools/fixtures/rom_table.c; \
	  CC=clang sh tools/expect_impure.sh ': pointer typedefs or typeof' '(clang AST): casts to a pointer type' -- tools/fixtures/typedef_cast.c $(M0); \
	  CC=clang sh tools/expect_impure.sh 'R1 violated' '.data.rel.ro.counter decisions' -- tools/fixtures/relro_counter.c $(M0); \
	else echo "clang not found: the Cortex-M0 purity checks and the clang AST controls were skipped"; fi
	sh tools/expect_impure.sh 'R1 violated' 'references external symbols: time' ': includes system headers that are not C11 freestanding' -- tools/fixtures/impure_decider.c
	sh tools/expect_impure.sh 'volatile access' -- tools/fixtures/mmio_volatile.c
	sh tools/expect_impure.sh ': casts to a pointer type' -- tools/fixtures/mmio_cast.c
	sh tools/expect_impure.sh 'does not compile' 'int-conversion' -- tools/fixtures/mmio_int_conversion.c
	sh tools/expect_impure.sh 'integer/pointer conversion type: uintptr_t' -- tools/fixtures/mmio_uintptr.c
	sh tools/expect_impure.sh 'inline assembly: __asm__' -- tools/fixtures/asm_decider.c
	sh tools/expect_impure.sh 'R1 violated' 'weak_decisions' -- tools/fixtures/weak_decider.c
	sh tools/expect_impure.sh 'R1 violated' 'tls_decisions' -- tools/fixtures/tls_decider.c
	sh tools/expect_impure.sh 'R1 violated' '.data.rel.ro.counter decisions' -- tools/fixtures/relro_counter.c
	sh tools/expect_impure.sh 'includes the runtime header' -- tools/fixtures/backdoor_decider.c
	sh tools/expect_impure.sh 'which fails the purity checks' -- --uses tools/fixtures/impure_helper.c tools/fixtures/uses_impure.c
	sh tools/expect_impure.sh 'references external symbols: ba_' -- examples/transfer/transfer.c
	sh tools/expect_impure.sh 'references external symbols: __gettimeofday' -- tools/fixtures/helper_clock.c
	sh tools/expect_impure.sh ': pointer typedefs or typeof' -- tools/fixtures/fnptr_call.c
	sh tools/expect_impure.sh ': pointer typedefs or typeof' -- tools/fixtures/typedef_cast.c
	sh tools/expect_impure.sh ': pointer typedefs or typeof' -- tools/fixtures/typeof_cast.c
	sh tools/expect_impure.sh ': compiler builtins outside the pure list' -- tools/fixtures/builtin_clock.c
	sh tools/expect_impure.sh ': volatile access' ': inline assembly' -- tools/fixtures/syshdr/syshdr_decider.c
	sh tools/expect_impure.sh ': includes system headers that are not C11 freestanding' -- tools/fixtures/intrinsic_header.c
	sh tools/expect_impure.sh ': line directives that relabel' -- tools/fixtures/line_directive.c
	sh tools/expect_impure.sh 'line_marker.c:16:# 1 "/usr/include/stdint.h"' -- tools/fixtures/line_marker.c
	sh tools/expect_impure.sh ': union' -- tools/fixtures/union_pun.c
	@if sh tools/expect_impure.sh 'does not compile' 'int-conversion' -- tools/fixtures/syntax_error.c > build/control.log 2>&1; then \
	  echo "expect_impure.sh accepted a fixture that fails for another reason"; cat build/control.log; exit 1; \
	else echo "expect_impure.sh refuses a fixture that fails for another reason (expected)"; fi

mutants: $(HDR) $(RUNTIME) $(KNOWLEDGE)
	@mkdir -p build
	@for entry in $(MUTANTS); do \
	  t=$${entry%%:*}; rest=$${entry#*:}; m=$${rest%%:*}; tag=$${rest#*:}; \
	  case $$t in \
	    knowledge) src="$(SRC_knowledge)" ;; runtime) src="$(SRC_runtime)" ;; \
	    dosing) src="$(SRC_dosing)" ;; transfer) src="$(SRC_transfer)" ;; esac; \
	  log=build/mutant_$$t.$$m.log; \
	  $(CC) $(CFLAGS) -D$$m $(INC) $$src -o build/mutant || exit 1; \
	  ./build/mutant > $$log 2>&1; status=$$?; \
	  if [ $$status -eq 0 ]; then echo "mutant $$m survived $$t"; cat $$log; exit 1; fi; \
	  if [ $$status -ne 1 ] || grep -qE 'Sanitizer|runtime error:' $$log || \
	     ! grep -qE "^(counterexample in run [0-9]+|  also) \[$$tag\]" $$log; then \
	    echo "mutant $$m failed $$t (exit $$status), but not with a counterexample from [$$tag]:"; cat $$log; exit 1; fi; \
	  echo "mutant $$m killed by $$t: $$(grep -E "^(counterexample in run [0-9]+|  also) \[$$tag\]" $$log | head -1)"; \
	done

# The tests again, under AddressSanitizer and UndefinedBehaviorSanitizer.
sanitize:
	$(MAKE) clean
	$(MAKE) test CFLAGS="-std=c11 -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer -Wall -Wextra -Werror"

# Larger bounds than `make`; minutes, not seconds.
deep: $(HDR)
	@mkdir -p build
	$(CC) $(CFLAGS) -DFATE_BOUND=8 $(INC) $(SRC_dosing) -o build/deep_dosing
	$(CC) $(CFLAGS) -DFATE_BOUND=10 $(INC) $(SRC_transfer) -o build/deep_transfer
	$(CC) $(CFLAGS) -DFATE_BOUND=3 -DEX_FAULTS=2 $(INC) $(SRC_dosing) -o build/deep_dosing_2faults
	$(CC) $(CFLAGS) -DFATE_BOUND=4 -DEX_FAULTS=2 $(INC) $(SRC_transfer) -o build/deep_transfer_2faults
	./build/deep_dosing
	./build/deep_transfer
	./build/deep_dosing_2faults
	./build/deep_transfer_2faults

clean:
	rm -rf build
