# Ballarch in register-transfer logic (SystemVerilog)

Two hardware blocks that are Balls:

- [`rtl/ballarch_issuer.sv`](rtl/ballarch_issuer.sv) accepts commands from upstream, issues each as a bus transaction to a device, and reports exactly one completion per command: `DONE`, `NOT_DONE` or `UNKNOWN`.
- [`rtl/ballarch_composite.sv`](rtl/ballarch_composite.sv) performs one two-step operation, "configure, then fire", through the issuer, and keeps a status register that says what is known about the operation's effects, computed with the knowledge algebra of [Core §4.2](../../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations) (rule R14). [`rtl/ballarch_composite_top.sv`](rtl/ballarch_composite_top.sv) connects the two.

Both blocks are Transient: registers do not survive reset. [Core §9.4](../../spec/ballarch-core-2.0.md#94-conformance-at-other-levels) states what this reference implements and what it lacks.

## The issuer

| Ballarch concept | Here |
|---|---|
| State owned by one writer (R1) | registers written by a single `always_ff` |
| Pure decider (R2) | one `always_comb` with a default for every next-state signal: no latch, so no state that a clock edge does not commit |
| Atomic commit (R4) | every register updates on the rising edge of `clk` |
| Commit before dispatch (R5) | every output is a function of registered state only |
| Send horizon | the `m_valid && m_ready` handshake: on this bus a master may withdraw a request that was not yet accepted |
| Effect classes | READ is Safe. CONFIG is Idempotent because the device deduplicates it: every attempt of one command carries the same command id. FIRE is NonIdempotent. The fourth encoding is undefined and is refused without being sent. Retry is decoded positively (READ and CONFIG only). |
| Idempotency key | the command id, not reused within the response window |
| Obligation and deadline (R6) | one command in flight; a deadline per phase: an offer is held at most `TIMEOUT + 1` cycles, and the wait for its answer lasts at most as long; a fresh tag for every attempt |
| Honest outcomes, no blind re-send (R7, R8) | withdrawal of a first attempt is `NOT_DONE`; no answer is `UNKNOWN` (FIRE, and CONFIG after its retries) or `NOT_DONE` (READ); FIRE is never re-issued |
| Profile (R13) | Transient |

### The bus and device contract

The block is correct only under this contract, and its parameters are checked against it:

- **Responses.** A response arrives within `RESP_WINDOW` cycles of its handshake, or never. The tag of an attempt is `{command id, attempt number}`, and a response is taken only if it carries the current attempt's tag.
- **Order.** The device applies a request when it accepts it, so requests take effect in issue order.
- **Deduplication.** The device applies a CONFIG only if it has not applied a CONFIG with the same command id within the last `RESP_WINDOW` cycles. Without that, CONFIG would not be Idempotent in the sense of Core §2 (the destination deduplicates deliveries that carry the same key), and the issuer could not retry it.
- **Tag reuse.** A command starts at least 3 cycles after the previous command's last handshake, and commands start at least 4 cycles apart, so a command id is sent again no sooner than `4 * 2**CMD_W` cycles after its last use. Elaboration therefore requires `RESP_WINDOW < 4 * 2**CMD_W`: no late response can match a later command, and the device has forgotten a command id before it recurs.
- **Retries.** An attempt is offered for at most `TIMEOUT + 1` cycles and then waits at most as long for its answer, so a command's last retry is handshaken at most `(MAX_ATTEMPTS - 1) * (2 * TIMEOUT + 2)` cycles after its first attempt. Elaboration requires this to be at most `RESP_WINDOW`, so the device still remembers the command id when the last retry arrives. This is the hardware form of a deduplication window ([Core §3.5](../../spec/ballarch-core-2.0.md#35-bounds-and-preflight)). With the defaults (`TIMEOUT = 12`, `MAX_ATTEMPTS = 2`, `CMD_W = 4`), `RESP_WINDOW` is 63 cycles.
- **Reset.** After a reset the block accepts no command for `RESP_WINDOW` cycles. Responses to attempts made before the reset have then arrived, and the device has forgotten their command ids, before any tag is reused, even if the device and the bus were not reset with the block.

With the parameter check removed, the bench finds a wrong outcome at the nearest window each bound refuses (see `sim`). The check is computed in 64-bit arithmetic, so no parameter value wraps around, and it also requires `CMD_W` from 1 to 28, so that `4 * 2**CMD_W` fits an `int`. Every counter is sized from the parameters, so no parameter is truncated. Out-of-range parameters stop Verilator and Yosys at elaboration and Icarus when the simulation starts.

### Other buses

- **AXI.** VALID must remain asserted until the handshake once it is asserted ([Arm IHI 0022](https://documentation-service.arm.com/static/68b03beb01ae952d9559f9eb), Issue L, §A2.3, accessed 2026-10-27), so the send horizon is the assertion of VALID. An effectful request not accepted by its deadline may still be accepted later, so it ends `UNKNOWN`, not `NOT_DONE`. AXI keeps only transactions with the same ID in order. With a fresh ID per attempt, an earlier CONFIG attempt can reach the device after a later command's CONFIG and overwrite it, which deduplication by command id does not prevent. CONFIG is then Idempotent only if every write to the device uses one ID, or if the device also refuses command ids older than the last it applied. Otherwise it is NonIdempotent.
- **PCIe.** Posted versus non-posted requests is not an effect class. It says whether the requester gets a completion, and so whether it can ever have evidence. A non-posted request with no completion by its deadline is `UNKNOWN` if it may have an effect. A posted write never returns a completion: its outcome stays `UNKNOWN` until a later read from the same device returns, which is how a driver makes sure earlier writes have reached the device ([Linux, "Device I/O"](https://docs.kernel.org/driver-api/device-io.html), accessed 2026-10-27). The effect class belongs to the target: a posted write to a doorbell and a non-posted read of a read-to-clear register are both NonIdempotent.

## The composite: knowledge in hardware (R14)

The operation has two constituents: A = CONFIG(data), then B = FIRE. B is requested only if A may have taken effect; after A completes `NOT_DONE` the operation stops, and B can no longer be made.

The status register holds the knowledge (all, any) of the current or last operation in strong Kleene logic, two bits per coordinate, `{may be Yes, may be No}`: Yes = `10`, No = `01`, Maybe = `11`. In this encoding AND is `{a1 & b1, a0 | b0}` and OR is `{a1 | b1, a0 & b0}`, the set of truth values the result can take. A constituent's effect is:

- Yes if it completed `DONE`, which here means that the device answered OK, so the effect took place;
- No if it completed `NOT_DONE`, or if the operation can no longer make it;
- Maybe if it completed `UNKNOWN`, is open, or may still be made.

all = A AND B, and any = A OR B. Each value of the register is a claim about the operation's final effect, valid from the cycle after the operation is accepted. Before the first operation the register reads NoEffects (Yes, No), the knowledge of an operation with no constituents. While an operation runs it reads Unknown, or SomeDone once CONFIG has completed `DONE`. The completion is the knowledge seen as one call: `DONE` if (Yes, Yes), `NOT_DONE` if any is No, otherwise `UNKNOWN`. The block is Transient, so the register covers only operations since its last reset.

The composite follows the same rules as the issuer. Its decider is one latch-free `always_comb`, all its registers update on the rising edge of `clk`, and every output is registered state, including the request it sends to the issuer.

## Checks

With Icarus Verilog 12, Verilator 5 and Yosys 0.33 or later, from this directory:

```sh
make            # = make lint params rules sim mutants
```

The seeded bugs run in parallel, one bench per job (`JOBS`, by default the output of `nproc`, or 2); `make JOBS=1` runs them one at a time.

### `lint`

Verilator `--lint-only -Wall` on the issuer, on the composite, and on the hierarchy, with no warnings.

### `params`

[`checks/params.sh`](checks/params.sh) runs ten parameter sets outside the ranges above, and each must be refused by Verilator, Yosys and Icarus with the block's own message. Among them:

- `TIMEOUT=40 MAX_ATTEMPTS=5` with the default window, whose retries outlast the window;
- `TIMEOUT=1073741823 MAX_ATTEMPTS=2` and `TIMEOUT=1 MAX_ATTEMPTS=1073741825`, whose retries span 2^31 and 2^32 cycles, which a 32-bit computation would wrap to -2^31 and to 0;
- `CMD_W=0` and `CMD_W=29`, outside the range of command-id widths.

Four legal sets must pass lint and the structural checks, among them the smallest legal block and `TIMEOUT = 2^31 - 1` with one attempt.

### `rules`: structural checks of R1, R2, R4 and R5

[`checks/rules.ys`](checks/rules.ys) runs on each block separately and on the hierarchy. Inside a hierarchy a combinational path from one block to another is not a path between the top's ports, so the top alone would not see it: both blocks' seeded combinational requests pass the check of the top and fail the check of their own block. On the netlist after `proc`, `flatten` and `opt`, each check proves the following, and no more:

- **R2, latches.** No latch cell (`$dlatch`, `$adlatch`, `$dlatchsr`, `$sr`) remains after `proc`, and `proc` itself rejects a latch inferred in `always_comb`.
- **R2 and R4, storage.** Every cell is one of the eleven Yosys flip-flop types or a stateless operator (logic, arithmetic, comparison, shift and multiplexer cells; the list is in the script). Anything else is rejected: a memory, a latch of any kind, a gate-level cell, a black box, a module with no definition. So the flip-flops are the block's only storage, whatever the source used: `always_comb`, `always @*`, `always_latch` or an array.
- **R1.** Yosys `check -assert`, run before `opt` and again after `flatten`, finds no signal with two drivers.
- **R4 and R5, clock.** The clock pin of every flip-flop is connected to the `clk` input port, and every flip-flop triggers on the rising edge. A wire that is only named `clk`, a gated or derived clock, and a falling edge are rejected.
- **R5, outputs.** No path from an input other than `rst_n` to any output avoids a flip-flop's clock, data, enable or synchronous-reset pin. A path into an asynchronous pin (reset, set or load) continues through the flip-flop to its output. `rst_n` reaches outputs only through flip-flops.
- **R4, asynchronous pins.** No input other than `rst_n` reaches an asynchronous pin of any flip-flop, including one whose output reaches no output port.

Together these say: the block's state is held only in flip-flops that change at the rising edge of `clk` or while `rst_n` resets them, and every output is a function of that state alone. So no output and no state changes in response to an input except at the commit edge (R4, R5). The checks rely on the port names `clk` and `rst_n`, and they do not check how `rst_n` is used inside the block. They also reject some correct designs, for example one that keeps a register file in a memory, instantiates gate-level cells, or uses the falling edge.

The fixtures in [`lint/`](lint/) must each fail for the reason named in their `EXPECT` line; the positive control must pass.

| Fixture | Violation | Rejected by |
|---|---|---|
| `r1_two_owners.sv` | two processes write one register | R1 |
| `r2_latch_always_comb.sv` | incomplete `always_comb` | R2 (`proc`) |
| `r2_latch_always_latch.sv` | explicit `always_latch` | R2 (latch cell) |
| `r2_latch_always_star.sv` | incomplete `always @*` | R2 (latch cell) |
| `r2_memory_async_write.sv` | a memory written from `always @*`, with no clock | storage |
| `r2_blackbox.sv` | a black box between registers, which may hold any state | storage |
| `r4_two_edges.sv` | one register on the rising and one on the falling edge of `clk` | clock (edge) |
| `r4_async_internal.sv` | the request sets an internal register asynchronously; no output follows it directly | asynchronous pins |
| `r5_async_set.sv` | the request drives an asynchronous set of an output register | R5 (outputs) |
| `r5_input_clock.sv` | the request is used as a clock | clock (port) |
| `r5_gated_clock.sv` | the registers are clocked by an internal wire named `clk` that the request gates | clock (port) |
| `r5_memory_input_clock.sv` | the request is the write clock of a memory read by an output | storage |
| `r5_comb_irq.sv` | an output called `irq` follows an input | R5 (outputs; every output is checked) |
| `r5_reset_to_output.sv` | `rst_n` drives an output combinationally | R5 (outputs) |
| `r5_passthrough_submodule.sv` | a path through a submodule | R5 (outputs) |
| `ok_sync_reset_enable.sv` | none: synchronous reset with enable | must pass |

### `sim`

[`tb/tb_device.sv`](tb/tb_device.sv) models the bus and the device and is the ground truth. It counts attempts from the bus (handshakes and withdrawn offers), never from the issuer's tags, applies effects, deduplicates CONFIG by command id, and answers one response per cycle. Each attempt meets one of these fates:

- answered OK in time: at the first edge after the handshake, two edges after it, or at the issuer's deadline, `TIMEOUT + 1` edges after it, the last edge in time;
- applied and answered late (in the cycle after the next handshake on the bus, the worst moment for a stale answer, or at the end of the window);
- applied and the answer lost, after being accepted at once or only in the last cycle the issuer may hold the offer, after `TIMEOUT` cycles of waiting;
- accepted but neither applied nor answered;
- refused with an error in time;
- never accepted;
- and, in the wrap scenarios and long runs, answered at the end of the window.

When two answers are due at the same edge, one that cannot wait goes first, then one whose fate names that edge, then the one with the earlier bound. A correct issuer has only one answer in time outstanding, so the answers at the first edge and at the deadline keep their edge. The benches print how many did not; that count is printed, not asserted. The model stops the run if it cannot keep a fate's meaning or the response window.

[`tb/tb_issuer.sv`](tb/tb_issuer.sv) runs 8,764 scenarios at each parameter set (printed by the bench):

- *pairs*: every operation, including the undefined one, under every combination of nine fates of its first two attempts, followed at once by a READ, CONFIG or FIRE under each fate of its first attempt, so that late answers to the first command arrive while the second waits for its own;
- *reset*: the issuer alone is reset with an answer in flight, and the next command, which the device refuses, reuses the first command's tag;
- *wrap*: an answer at the end of the window, then commands answered at the first edge until the first command's id recurs;
- *dense*: one operation repeated with every answer at the first edge, so that command ids recur as soon as the issuer can reuse them;
- *long runs*: command ids wrap around several times under random operations and fates.

For every command it checks:

- R6: exactly one completion, none without a command, and every queued command accepted; no offer held for more than `TIMEOUT + 1` cycles, and after every handshake a retry offer or the completion within `TIMEOUT + 2` cycles;
- R5: no bus request in the cycle that accepts the command;
- R7: the kind is the one [Core §3.2](../../spec/ballarch-core-2.0.md#32-call-lifecycle) requires for the attempts' fates. `NOT_DONE` comes only if no effect was applied. `DONE` comes only after an OK answer to the current attempt, only if the effect was applied, and for READ only with that answer's data. `UNKNOWN` never comes for READ or the undefined operation;
- R8: FIRE is accepted at most once, at most `MAX_ATTEMPTS` attempts are made, and a CONFIG is applied at most once;
- that the undefined operation is never offered on the bus;
- that no tag is sent again within `RESP_WINDOW` cycles of its last use, across issuer resets too.

The issuer bench runs at three parameter sets: the defaults; `TIMEOUT=5 MAX_ATTEMPTS=3 RESP_WINDOW=40`; and `TIMEOUT=1 MAX_ATTEMPTS=1 CMD_W=1 RESP_WINDOW=7`, where command ids recur every second command and the window is the longest the check allows. With the check removed, `make mutants` runs the bench at windows the check refuses, and each run must fail:

- With 1-bit command ids, at a window of 8, the nearest window the check refuses: in the dense scenarios a command id recurs exactly 8 cycles after its last use, and a CONFIG with a recurring id is taken by the device for a duplicate and reported `DONE` without its effect. At a window of 10, a late answer also completes a refused command as `DONE`.
- With `TIMEOUT=5 MAX_ATTEMPTS=3`, where the retries span 24 cycles, at a window of 23: a CONFIG retry is applied a second time.

[`tb/tb_composite.sv`](tb/tb_composite.sv) runs 1,458 scenarios with 2,916 operations (printed by the bench). Each first operation meets every combination of nine fates for CONFIG's two attempts and FIRE's attempt. A second operation follows, either answered OK throughout or with CONFIG never accepted, so that a status left over from the first operation would be wrong. For every operation it checks:

- exactly one completion, and none without an operation;
- no request to the issuer in the cycle that accepts the operation;
- NoEffects in the status register before the first operation;
- soundness: every value of the status register while the operation is current admits the device's ground truth, and is one of the seven values the algebra produces;
- exactness: the final status equals the knowledge computed by supervaluation from the outcomes §3.2 requires, independently of the Kleene tables in the design, and the completion is that knowledge seen as one call.

### `mutants`

Twenty-eight seeded bugs, and the issuer with its parameter check removed at three windows the check refuses, must each make a bench run to the end and report a failed check; a build error or a crash does not count. Every check of both benches fails for at least one of them. The issuer bench must catch these:

| Seeded bug | Caught by |
|---|---|
| `MUTANT_RETRY_FIRE`: re-issues FIRE | FIRE accepted twice (R8); R7 kind |
| `MUTANT_TIMEOUT_NOT_DONE`: `NOT_DONE` when an accepted request times out | R7 kind; `NOT_DONE` although applied |
| `MUTANT_TIMEOUT_DONE`: `DONE` when an accepted CONFIG or FIRE times out | R7 kind; `DONE` without an answer; `DONE` without the effect |
| `MUTANT_WITHDRAWN_RETRY_NOT_DONE`: `NOT_DONE` when a CONFIG retry is withdrawn before acceptance, although an earlier attempt may have been applied | R7 kind; `NOT_DONE` although applied |
| `MUTANT_COMB_DISPATCH`: bus request driven combinationally | R5 bench check, most other checks, and the structural R5 check |
| `MUTANT_NO_TAG_CHECK`: takes any response | `DONE` without an answer to the current attempt; R7 kind; READ data; `DONE` without the effect |
| `MUTANT_SAME_TAG_RETRY`: a retry keeps the previous tag | tag window; `DONE` without an answer to the current attempt; READ data; R7 kind |
| `MUTANT_RETRY_NEW_CMD`: a retry gets a new command id | CONFIG applied twice (R8) |
| `MUTANT_SHORT_TAG`: command ids counted in two bits | tag window; a CONFIG with a recurring id reported `DONE` without its effect |
| `MUTANT_NO_DRAIN`: no wait after reset | tag window; a refused command reported `DONE` from the old answer (R7 kind, `DONE` without an answer, READ data, `DONE` without the effect) |
| `MUTANT_FAIL_OPEN`: the undefined operation is sent and retried | fail closed; R7 kind; `UNKNOWN` for the undefined operation |
| `MUTANT_READ_DATA`: READ completes without its answer's data | READ data |
| `MUTANT_LOST_COMPLETION`: no completion when a first attempt is withdrawn | R6 (no completion) |
| `MUTANT_DOUBLE_COMPLETION`: a completion held for two cycles | R6 (a completion without a command) |
| `MUTANT_STUCK_DRAIN`: the wait after reset never ends | R6 (commands never accepted) |
| `MUTANT_SHORT_DEADLINE`: waits 1 cycle for an answer instead of `TIMEOUT` | R7 kind (an answer at the deadline is missed) |
| `MUTANT_SHORT_OFFER`: withdraws an offer one cycle before its deadline | R7 kind (an offer accepted in its last cycle is withdrawn instead) |
| `MUTANT_LONG_OFFER`: holds an offer one cycle past its deadline | R6 (phase deadline) |
| `MUTANT_UNCHECKED_PARAMS`, 1-bit command ids, window 8 | tag window; a CONFIG with a recurring id reported `DONE` without its effect |
| `MUTANT_UNCHECKED_PARAMS`, 1-bit command ids, window 10 | tag window; a late answer completes a refused command as `DONE` (R7 kind, `DONE` without an answer, READ data, `DONE` without the effect) |
| `MUTANT_UNCHECKED_PARAMS`, `TIMEOUT=5 MAX_ATTEMPTS=3`, window 23 | CONFIG applied twice (R8) |

`MUTANT_WITHDRAWN_RETRY_NOT_DONE` breaks R7, the rule the Kotlin runtime enforces with its `Pending` and `Sending` phases: once an attempt may have been delivered, a later withdrawal is not evidence that nothing was applied. The bench catches it in the scenarios where CONFIG's first attempt is accepted without a timely answer and its retry is never accepted.

The composite bench must catch these:

| Seeded bug | Caught by |
|---|---|
| `MUTANT_K_UNKNOWN_AS_NO`: an `UNKNOWN` constituent counts as No | soundness, exactness, completion kind |
| `MUTANT_K_UNKNOWN_AS_YES`: an `UNKNOWN` constituent counts as Yes | soundness, exactness, completion kind |
| `MUTANT_K_ANY_AS_AND`: any computed with AND | soundness, exactness, completion kind |
| `MUTANT_K_UNSENT_AS_NO`: B counts as No until requested, which claims all = No for an operation that may still complete | soundness only: every final value is right, but the status after acceptance claims all = No |
| `MUTANT_K_WEAK_KLEENE`: weak Kleene connectives (Maybe absorbs) | exactness only: it never lies, but claims less than the algebra derives |
| `MUTANT_K_COMB_REQUEST`: request to the issuer driven combinationally | R5 bench check (and through it soundness, exactness and completion kind), and the structural R5 check of the composite |
| `MUTANT_K_PLAIN_AND`: the two-bit encodings ANDed bit by bit | an encoding the algebra never produces; soundness; exactness; completion kind |
| `MUTANT_K_RESET_UNKNOWN`: the status starts at Unknown instead of NoEffects | NoEffects before the first operation |
| `MUTANT_K_SILENT_STOP`: no completion when the operation stops after CONFIG's `NOT_DONE` | R6 (no completion) |
| `MUTANT_K_REPORT_TWICE`: a completion held for two cycles | R6 (a completion without an operation) |

## Not checked here

- R3: each block takes one input per cycle by construction.
- R9: no block defers a reply.
- R10: composition is the instantiation hierarchy, and nothing checks it.
- R11: one command or operation in flight, with back-pressure through `req_ready` and `op_ready`. There is no overload test.
- R12: the status register can be read freely and is written only by the composite's decisions, by construction.
- R14 is checked for this one composite, against this device model.
- R16 and the evolution rules of Core §6.1 do not apply to a Transient block.
- As Core §9.4 lists, the blocks do not implement Cancel, intents, deduplication windows or attempt timeouts as binding options, reconciled ports, reads that return a revision (the status register has none), or the timer port; their deadlines are counters per phase. They do not emit the conformance trace of [Core §9.6](../../spec/ballarch-core-2.0.md#96-conformance-trace).
- The bench does not model reordering (AXI with distinct IDs), several masters, duplicated responses, responses later than `RESP_WINDOW` (outside the contract; the runs with the parameter check removed show what they break), clock-domain crossings or X-propagation.
- The structural checks see the netlist Yosys builds, with the limits stated under `rules`.

These are bounded checks of two blocks in simulation and on the netlist, not formal verification of the RTL, and the code has no software license yet (see [`NOTICE.md`](../../NOTICE.md)).
