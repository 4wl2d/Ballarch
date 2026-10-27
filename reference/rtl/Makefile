# Ballarch RTL reference: lint, parameter ranges, structural rule checks,
# simulation against a device model, and seeded bugs that must be caught.
# Requires Icarus Verilog (12), Verilator (5) and Yosys (0.33 or later).
IVERILOG ?= iverilog
VVP ?= vvp
VERILATOR ?= verilator
YOSYS ?= yosys
# Seeded bugs run in parallel, one bench per job.
JOBS ?= $(shell nproc 2>/dev/null || echo 2)

ISSUER := rtl/ballarch_issuer.sv
RTL := $(ISSUER) rtl/ballarch_composite.sv rtl/ballarch_composite_top.sv
# Each block is checked on its own: a path inside the hierarchy is not a path
# between the top's ports.
BLOCKS := ballarch_issuer ballarch_composite ballarch_composite_top
TB_ISSUER := tb/tb_pkg.sv $(ISSUER) tb/tb_device.sv tb/tb_issuer.sv
TB_COMPOSITE := tb/tb_pkg.sv $(RTL) tb/tb_device.sv tb/tb_composite.sv

ISSUER_MUTANTS := MUTANT_RETRY_FIRE MUTANT_TIMEOUT_NOT_DONE MUTANT_TIMEOUT_DONE \
	MUTANT_WITHDRAWN_RETRY_NOT_DONE MUTANT_COMB_DISPATCH MUTANT_NO_TAG_CHECK \
	MUTANT_SAME_TAG_RETRY MUTANT_RETRY_NEW_CMD MUTANT_SHORT_TAG MUTANT_NO_DRAIN \
	MUTANT_FAIL_OPEN MUTANT_READ_DATA MUTANT_LOST_COMPLETION MUTANT_DOUBLE_COMPLETION \
	MUTANT_STUCK_DRAIN MUTANT_SHORT_DEADLINE MUTANT_SHORT_OFFER MUTANT_LONG_OFFER
COMPOSITE_MUTANTS := MUTANT_K_UNKNOWN_AS_NO MUTANT_K_UNKNOWN_AS_YES MUTANT_K_ANY_AS_AND MUTANT_K_UNSENT_AS_NO \
	MUTANT_K_WEAK_KLEENE MUTANT_K_COMB_REQUEST MUTANT_K_PLAIN_AND MUTANT_K_RESET_UNKNOWN \
	MUTANT_K_SILENT_STOP MUTANT_K_REPORT_TWICE
# Seeded bugs the structural R5 check must reject too, as mutant:block.
STRUCTURAL_MUTANTS := MUTANT_COMB_DISPATCH:ballarch_issuer MUTANT_K_COMB_REQUEST:ballarch_composite
# The parameter check removed, and the bench run at windows the check refuses.
# RESP_WINDOW < 4 * 2**CMD_W = 8 with 1-bit command ids: at 8, the smallest
# window refused, a CONFIG whose id recurs is taken for a duplicate and reported
# DONE without its effect; at 10 a late answer completes a refused command.
# (MAX_ATTEMPTS - 1) * (2 * TIMEOUT + 2) = 24 <= RESP_WINDOW: at 23, the largest
# window refused, a CONFIG retry is applied a second time.
UNCHECKED_TAG := -Ptb_issuer.TIMEOUT=1 -Ptb_issuer.MAX_ATTEMPTS=1 -Ptb_issuer.CMD_W=1
UNCHECKED_RETRY := -Ptb_issuer.TIMEOUT=5 -Ptb_issuer.MAX_ATTEMPTS=3 -Ptb_issuer.CMD_W=4

.PHONY: all lint params rules sim mutants clean
all: lint params rules sim mutants

lint:
	$(VERILATOR) --lint-only -Wall $(ISSUER)
	$(VERILATOR) --lint-only -Wall rtl/ballarch_composite.sv
	$(VERILATOR) --lint-only -Wall --top-module ballarch_composite_top $(RTL)

params:
	@./checks/params.sh "$(VERILATOR)" "$(YOSYS)" "$(IVERILOG)" "$(VVP)" build

rules:
	@mkdir -p build
	@for top in $(BLOCKS); do \
	  $(YOSYS) -q -p "read_verilog -sv $(RTL); hierarchy -top $$top; script checks/rules.ys" > build/rules_$$top.log 2>&1 \
	    || { echo "$$top fails the structural checks:"; grep -m3 ERROR build/rules_$$top.log; exit 1; }; \
	  echo "R1/R2/R4/R5 structural checks passed for $$top"; \
	done
	@./checks/fixtures.sh "$(YOSYS)" build

# $(1): name, $(2): files, $(3): top, $(4): iverilog options
define bench
	$(IVERILOG) -g2012 $(4) -s $(3) -o build/$(1) $(2)
	$(VVP) -n build/$(1) > build/$(1).log
	@grep RESULT build/$(1).log
	@grep -q 'RESULT: PASS' build/$(1).log
endef

sim:
	@mkdir -p build
	$(call bench,tb_issuer,$(TB_ISSUER),tb_issuer,)
	$(call bench,tb_issuer_t5_a3,$(TB_ISSUER),tb_issuer,-Ptb_issuer.TIMEOUT=5 -Ptb_issuer.MAX_ATTEMPTS=3 -Ptb_issuer.RESP_WINDOW=40)
	$(call bench,tb_issuer_t1_a1_c1,$(TB_ISSUER),tb_issuer,-Ptb_issuer.TIMEOUT=1 -Ptb_issuer.MAX_ATTEMPTS=1 -Ptb_issuer.CMD_W=1 -Ptb_issuer.RESP_WINDOW=7)
	$(call bench,tb_composite,$(TB_COMPOSITE),tb_composite,)

# A mutant counts as killed only if its bench ran to the end and reported a
# failed check (checks/mutant.sh); a build error or a crash does not count.
# Each line: name, bench, a message its failure must include (- for any), options.
mutants:
	@mkdir -p build
	@{ for m in $(ISSUER_MUTANTS); do echo "$$m tb_issuer - -D$$m"; done; \
	   for m in $(COMPOSITE_MUTANTS); do echo "$$m tb_composite - -D$$m"; done; \
	   echo "MUTANT_UNCHECKED_PARAMS_W8 tb_issuer 'DONE, but the device did not apply' -DMUTANT_UNCHECKED_PARAMS $(UNCHECKED_TAG) -Ptb_issuer.RESP_WINDOW=8"; \
	   echo "MUTANT_UNCHECKED_PARAMS_W10 tb_issuer 'DONE, but Core 3.2 requires NOT_DONE' -DMUTANT_UNCHECKED_PARAMS $(UNCHECKED_TAG) -Ptb_issuer.RESP_WINDOW=10"; \
	   echo "MUTANT_UNCHECKED_PARAMS_W23 tb_issuer 'CONFIG applied 2 times' -DMUTANT_UNCHECKED_PARAMS $(UNCHECKED_RETRY) -Ptb_issuer.RESP_WINDOW=23"; \
	 } | TB_ISSUER="$(TB_ISSUER)" TB_COMPOSITE="$(TB_COMPOSITE)" \
	     xargs -L 1 -P $(JOBS) ./checks/mutant.sh "$(IVERILOG)" "$(VVP)" build
	@for mb in $(STRUCTURAL_MUTANTS); do m=$${mb%%:*}; top=$${mb##*:}; \
	  if $(YOSYS) -q -p "read_verilog -sv -D$$m $(RTL); hierarchy -top $$top; script checks/rules.ys" > build/rules_$$m.log 2>&1; then \
	    echo "mutant $$m passed the structural R5 check of $$top"; exit 1; \
	  elif grep -q 'Assertion failed.*o:\*' build/rules_$$m.log; then echo "mutant $$m also rejected by the structural R5 check of $$top"; \
	  else echo "structural check of $$m failed for another reason:"; grep -m2 ERROR build/rules_$$m.log; exit 1; fi; \
	done

clean:
	rm -rf build
