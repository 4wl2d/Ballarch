# Ballarch in firmware (C11)

The same contract as the Kotlin reference, for a microcontroller that owns a device protocol. This is a reference for the rules, not a production RTOS component. It runs one Ball per runtime under the Durable profile. [Core §9.4](../../spec/ballarch-core-2.0.md#94-conformance-at-other-levels) states what it implements and what it lacks.

| Ballarch concept | Here |
|---|---|
| Ball, decider (R2) | a pure step function from (state, input, context) to (next state, calls, reply), in its own translation unit; it includes only [`ballarch.h`](include/ballarch.h) and gets a copy of the committed state |
| Atomic commit (R4) | one record (state + call ledger) written to one of two NVM slots with a sequence number and CRC-32 ([`src/ballarch_runtime.c`](src/ballarch_runtime.c)); a torn write leaves the previous record valid |
| Commit before dispatch (R5) | `transmit` is called only after the record that holds the call has been written |
| Write-ahead, no blind re-send (R8) | each attempt first records phase *Sending* and its attempt number; after a power loss a *Sending* call recorded NonIdempotent ends `Unknown(RecoveredAfterCrash)`. `nvm_write` reports failure; a failed write stops the commit or the send and quarantines the instance |
| Recorded effect class ([Core §6.1](../../spec/ballarch-core-2.0.md#61-evolution), item 2) | each call record keeps the effect class its port had when the call was committed (the low byte of `ba_call_rec.reserved`, class + 1). Retries, deadlines and recovery classify the call by it, never by the port table of the build that recovers it. The attempt budget is the current build's. A record written before the class was recorded has 0 there and is classified by the current port table |
| Honest outcomes (R7) | NACK before the command was latched is `NotDone` if no other attempt can have been delivered; a timeout or garbled answer after sending is `Unknown` (NonIdempotent) or a retry (Safe, Idempotent) |
| Idempotency key | the call key, which `transmit` receives with every attempt. Keys come from the revision and never from a clock or random source, so they repeat only when the 32-bit key wraps after enough committed decisions, or after the NVM slots are erased ([Core §10](../../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)) |
| Bounds (R11) | fixed tables: `BA_MAX_STATE` 32 bytes, `BA_MAX_CALLS` 4, `BA_MAX_OUTPUTS` 4, `BA_MAX_PORTS` 8, `BA_MAX_KEYS` 16 keys per decision, `BA_MAX_TIMEOUT_MS` one day. Preflight refuses a decision that exceeds them; `ba_boot` refuses a Ball that does not fit; `BA_STATE_FITS` checks a state type at compile time |
| Profile (R13) | Durable: state, call ledger and timers survive power loss. Time is a 64-bit millisecond count from a clock that keeps running (a board with a 32-bit tick counter extends it to 64 bits in `now`) |
| NVM record | a 16-byte header (magic, a layout fingerprint, the sequence number, and a CRC-32 of those three), then revision, calls, state and a CRC-32 of the whole. The fingerprint covers the record size, the bounds, the Ball type, the state size and `schema`; it does not cover the port table. Every record layout keeps the header at the same offsets. A committed record that cannot be used quarantines the instance instead of starting fresh: both slots corrupt, or a record written by a build with another layout or Ball type, with a valid header, that is newer than every record of this build. A write torn after part of a header (on word-programmed flash, the magic word alone) is not taken for such a record |
| Honest composition (R14) | [`ballarch_knowledge.h`](include/ballarch_knowledge.h): strong Kleene `ba_tri`, the (all, any) pair `ba_knowledge`, its seven named values, `ba_k_plus`, net effect with absorbing compensation (`ba_net`), claims that count what may still change as `Maybe` (`ba_claim`), `ba_k_admits`. Pure, and checked like a decider |
| Reads (R12) | `ba_state` is a read-only view of the committed state, without a revision |

**NVM wear.** Every commit and every write-ahead record rewrites the whole record in the other slot. An effectful call costs three NVM writes, plus one per retry ([Core §10](../../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)); here a Safe call costs the same ([`test/test_runtime.c`](test/test_runtime.c), tag `wear`). Choose a medium whose endurance covers that rate, or put wear levelling behind `nvm_write`. The reference alternates two slots and does nothing more.

**Calling context.** The `ba_*` entry points take no lock and are not reentrant. `ba_request`, `ba_report` and `ba_tick` may run the decider, write NVM and call `transmit` before they return. Call them only from the task that owns the runtime, never from `transmit` or another platform function. An interrupt handler queues its report (key, attempt, kind, value) for that task and calls nothing here.

Two examples:

- [`examples/dosing/`](examples/dosing/) is a therapy controller that gives a prescribed number of doses through an infusion pump whose dose command has no idempotency key. A dose with an unknown fate is never repeated: the controller waits past the link's delivery horizon and reads the pump's dose counter (a Safe port, retried once if the answer is garbled).
- [`examples/transfer/`](examples/transfer/) is a composite operation. A transfer needs a source valve and a destination valve open; the controller opens one, then the other, and closes what it opened when it cannot have both. OPEN is NonIdempotent and may arrive late; CLOSE(token) closes and fences the OPEN with that token, so it is an absorbing compensation, and it is Idempotent (the runtime retries it once). The controller's state carries its claim about the transfer, the knowledge (all, any) of the two valves, which STATUS returns. The claim is about the final effect: a call that is open or that the controller may still make counts as `Maybe`, and so does the undoing of a valve it may still close.

## Firmware updates

The start-up check compares only the layout fingerprint, and the fingerprint omits the port table ([Core §10](../../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)). So:

- An update that removes or renumbers a port needs an empty call ledger, or a new `schema`, so that committed records quarantine their instances instead of being misread.
- An update that changes the record layout must migrate the record (write it in the new layout with a higher sequence number), or start from an empty call ledger and erased slots.
- Erasing the slots restarts the call keys, which are also the idempotency keys. A destination that still remembers an old key may answer new work with its old reply. The 32-bit call key also wraps after enough committed decisions, with the same consequence.
- An update may change a port's effect class. Open calls keep the class they were committed with: a call committed NonIdempotent is neither re-sent nor retried, even if its port is now Idempotent. The attempt budget is the new build's, so a possibly sent call committed Idempotent on a port now declared NonIdempotent is not sent again and ends `Unknown(RetriesExhausted)`. This holds for records this build writes. A record written before the class was recorded (0 in the low byte of `ba_call_rec.reserved`) is classified by the new build's port table, so an update from such a build that changes a port's effect class needs an empty call ledger or a new `schema`.

## Checks

From this directory, with a C11 compiler and binutils:

```sh
make            # = make test purity mutants
make sanitize   # the tests under AddressSanitizer and UndefinedBehaviorSanitizer
make deep       # the explorations with larger bounds (see below)
```

### `test`

- **Algebra** ([`test/test_knowledge.c`](test/test_knowledge.c)). Exhaustive, against truth tables computed by enumerating the Boolean worlds each three-valued value admits, as the Kotlin `KnowledgeTest` does: the connectives equal supervaluation; `ba_k_plus` is a commutative monoid on the seven values with `NoEffects` as identity; for up to four constituents (each `Done`, `NotDone` or `Unknown`, with no compensation or an absorbing one that completed `Done`, `NotDone` or `Unknown`) the combined knowledge admits every consistent world and each coordinate is the most informative such value. That is 69,905 worlds; the test fails if the count differs from the Kotlin test's. The same holds for claims made before the operation ends (a call open or still to be made, a compensation that may still be made): 5,421,361 worlds (printed by the test).
- **Runtime** ([`test/test_runtime.c`](test/test_runtime.c)), deterministic: preflight refuses each kind of bad decision and commits and sends nothing; a decision that draws more than `BA_MAX_KEYS` keys is refused; a refused completion quarantines; an Idempotent call is retried with its own write-ahead record per attempt, a NACK of a retry ends it `Unknown`, and a stale report is ignored; a failed NVM write stops the commit or the send, and after a restart the `Pending` call is sent once, or ends `NotDone(DeadlineBeforeSend)` (`BA_R_NEVER_SENT`) past its deadline; an oversized Ball, corrupt slots and a newer record of another schema quarantine, while an older one left next to a migrated record is ignored; a write torn after the magic word, on the first commit or next to a committed record, is not taken for another build's record (a fresh start, or the committed record is loaded and its `Pending` call sent once); a decider that casts away `const` cannot change the committed state; a call is classified by the effect class recorded when it was committed (tag `effect-class`, seven cases), so a restart into a build that declares its port otherwise neither re-sends nor retries a call committed NonIdempotent, a call committed Idempotent on a port now declared NonIdempotent ends `Unknown(RetriesExhausted)`, and a call committed Idempotent that may have been sent, on a port now declared Safe, ends `Unknown`, never `NotDone`: at its deadline, on a NACK of its retry, and at a restart past its deadline or with its budget spent; one call of each class answered OK costs three NVM writes, and a retry one more (tag `wear`); a timer armed just before 2<sup>32</sup> ms fires at its due time.
- **Explorations** of both examples ([`test/explore.c`](test/explore.c)): a bounded exhaustive search over a tree of choices, with the simulated device as the ground truth.
  - *Faults.* At most one per run, placed at each power-loss point and NVM write that the run reaches before it: at every main-loop wakeup, before an NVM write, in the middle of one (a torn write), as a failed write that reports its error, and after the write-ahead record but before the bytes leave. The slots are erased flash (0xFF); a torn write leaves the slot erased except for its first 4 bytes (the magic word), its first 16 (the header) or its first half, and each of the three is explored. Points are chosen lazily, so every point of every explored run is tried, and every run is a distinct sequence of choices. The controller restarts after 250 ms or after 2,500 ms, which is past every deadline. The device keeps its power.
  - *Link fates.* Chosen for the first 6 transmissions of a dosing run and the first 7 of a transfer run; later transmissions are answered in time, and the output counts them. Dose and OPEN: answer, late answer, lost request, lost answer, error after applying, NACK, and a request that arrives late (500 ms) and is never answered. Counter query: answer, lost, garbled. CLOSE: answer, lost request, lost answer, error after applying, NACK.
  - *Guards.* A run that needs more choices, events or call keys than the harness holds fails the exploration instead of being cut short; a nondeterministic replay fails it too, and so does a main loop that wakes 16 times in a row without time advancing (a livelock).
  - *Runtime oracles, every run:* NVM holds the call as *Sending*, with this attempt number, when it is transmitted (R5, R8); a call traced as committed is in the newest valid record (R4, R5); a NonIdempotent call is transmitted at most once (R8); every committed call completes exactly once (R6); `NotDone` only if the device did not apply the request, and `Done` on an effectful port only if it did (R7); a timer completes `Done` no earlier than its due time; the instance is quarantined only after an injected write error.
  - *Dosing oracles:* the pump never gives more than the prescription; doses it applies are at least `TH_INTERVAL_MS` apart; the final state matches the pump. Every run starts 1.5 s before a 32-bit millisecond counter would wrap, so its timers and deadlines straddle that point.
  - *Transfer oracles:* every claim that others could read admits the valves' final positions (R14): the knowledge in each committed state, and the reply STATUS gives in that state (the decider is pure, so the test asks it without committing anything); each of these claims is one of the seven values; the controller settles, and is idle at the end only if no valve is open.
  - With the default bounds, dosing explores **937,229 runs** and transfer **982,599 runs**, with no violation. Both tests fail if their default bounds give another run total. Other bounds, including those of `make deep`, are not checked against a total.
- `BA_STATE_FITS` must reject a 40-byte state type at compile time.

### `purity`

[`tools/check_purity.sh`](tools/check_purity.sh) checks one decider translation unit for the usual ways a decider reaches outside its arguments: calls into other code, data that outlives a step, registers, special instructions, clocks, and addresses made from integers. It is a lint for mistakes and shortcuts, not a sandbox.

- **Object file**, compiled with `-fno-pic` (so that `const` tables of pointers are read-only data) and without stack-protector instrumentation. R2: no undefined symbol except compiler runtime helpers (`memcpy`, `memset`, `memmove`, the `__aeabi_*` helpers, and the libgcc/compiler-rt arithmetic helpers by the exact shape of their names, such as `__udivsi3`, `__udivmoddi4`, `__adddf3` or `__fixdfsi`, so that libc functions like `__gettimeofday` do not pass for one; Thumb-1 switch helpers) and functions of a pure dependency named with `--uses`, which must pass on its own. R1: no non-empty allocated section that is not read-only, judged by its flags whatever its name (`.data`, `.bss`, thread-local, small data, `.data.rel.ro`), and no common symbol.
- **Preprocessed source**, the decider's own lines: lines from every file that is not under one of the compiler's own include directories (as `cc -v` lists them). A header that calls itself a system header with `#pragma GCC system_header`, as CMSIS headers do, still counts as the decider's own. R2: the only system headers it includes are the C11 freestanding ones (`stddef.h`, `stdint.h`, `limits.h` and the like: types and macros, no functions), so an intrinsics header whose inline functions read a clock is refused; not the runtime header `ballarch_runtime.h`; no `#line` directive or line marker that relabels its lines as another file's; no `volatile`; no inline assembly; no compiler builtin except pure ones (`offsetof`, `expect`, `unreachable`, bit counts, byte swaps, overflow checks, `va_*`, `constant_p`, `choose_expr`, `types_compatible_p`) and no `__atomic`/`__sync` builtin; no cast to a pointer type (`NULL` expands to one: use `0`); no typedef of a pointer type, no `typeof` or `__auto_type`, through which a cast would not show; no `union`; no `uintptr_t`/`intptr_t`; an integer converted to a pointer without a cast is a compile error (`-Werror=int-conversion`).
- **Clang AST**, when clang is installed (`CLANG=` turns it off, `CLANG="clang --target=..."` sets its target), nodes in the decider's own files. R2: no cast to a pointer type however the type is spelled (a typedef after a struct body, a macro), no conversion of an integer to a pointer, and no call through a function pointer: only direct calls of named functions, which the object check then sees. This is the check that sees through spelling; without clang the source checks above work on the text.

It runs on the dosing decider, the algebra and the transfer decider (with `--uses src/ballarch_knowledge.c`), and on a positive control with `const` tables of numbers and of pointers, a struct copy and division; with clang also for a Cortex-M0 (`--target=thumbv6m-none-eabi`), where division needs `__aeabi_idiv` and `__aeabi_uldivmod`. Each negative control in [`tools/fixtures/`](tools/fixtures/) must be refused with its expected message:

| Fixture | What it does | Refused by |
|---|---|---|
| `impure_decider.c` | reads `time()`, keeps a static counter | external symbol; writable data; hosted header `time.h` |
| `helper_clock.c` | calls libc's `__gettimeofday`, whose name starts like a helper's | external symbol |
| `weak_decider.c`, `tls_decider.c` | a weak global counter; a thread-local one | writable data |
| `relro_counter.c` | a writable counter in a section named `.data.rel.ro.counter` (also for Cortex-M0) | writable data |
| `mmio_volatile.c` | a volatile register store | `volatile` |
| `mmio_cast.c` | a non-volatile store through an integer cast to a pointer | cast to a pointer type |
| `mmio_int_conversion.c` | an integer converted to a pointer without a cast | compile error, and the expected diagnostic `int-conversion` |
| `mmio_uintptr.c` | a `uintptr_t` turned into a pointer with `memcpy` | `uintptr_t` |
| `typedef_cast.c` | a store into backup SRAM through a pointer typedef (also for Cortex-M0) | pointer typedef; clang AST cast |
| `typeof_cast.c` | a SysTick read through a cast spelled with `__typeof__` | `typeof` |
| `fnptr_call.c` | a call of a fixed flash address through a function-pointer typedef | pointer typedef |
| `rom_table.c` | a call through a boot ROM's function table, typedef after a struct body | clang AST only: cast and indirect call |
| `builtin_clock.c` | the cycle counter through a builtin (`__builtin_readcyclecounter`, `__builtin_ia32_rdtsc`) | builtin outside the pure list |
| `intrinsic_header.c` | `__rdtsc()` from `<x86intrin.h>`, whose builtin is inside the system header | hosted system header |
| `syshdr/syshdr_decider.c` | register store and assembly in its own header marked `#pragma GCC system_header` | `volatile`; inline assembly |
| `line_directive.c` | `#line` relabels a register store as `/usr/include/stdint.h` | line directive |
| `line_marker.c` | a GNU line marker relabels a register store as `/usr/include/stdint.h` | line marker |
| `union_pun.c` | an integer becomes a pointer through a union | `union` |
| `asm_decider.c` | inline assembly | inline assembly |
| `backdoor_decider.c` | steps from the state pointer into the runtime without a cast and calls `transmit` | runtime header (and, with clang, the indirect call) |
| `uses_impure.c` | calls a helper (`impure_helper.c`) that keeps a counter | `--uses` dependency fails |
| `examples/transfer/transfer.c` without `--uses` | calls the algebra | external symbols `ba_*` |

A further control checks the checker: `expect_impure.sh` must fail for `syntax_error.c`, which does not compile for another reason, when it expects the `int-conversion` diagnostic.

**Not detected**, and left to review ([Core §9.2](../../spec/ballarch-core-2.0.md#92-a-conforming-application)): pointer arithmetic from an argument into other memory without a cast; `memcpy` of an ordinary integer into a pointer object, with `memcpy` declared by hand; a struct the decider declares to mirror the runtime's layout; reads of uninitialised memory; non-termination; deliberate evasion, such as a line marker hidden behind a comment or a line splice; and, without clang, a cast through a pointer typedef declared after a struct body (`rom_table.c`). The check is necessary, not sufficient. Pass target flags after the file (`sh tools/check_purity.sh dec.c --target=thumbv7em-none-eabi -mcpu=cortex-m4 -ffreestanding`) or in `CC`; set `NM` and `OBJDUMP` if the host binutils cannot read the object.

### `mutants`

47 seeded bugs in 52 checks (five bugs are checked by two tests). Each check builds its test with the bug and requires a counterexample from the named oracle; a crash, a sanitizer report or a counterexample from another oracle does not count. Likewise each negative control of `purity` must be refused with its expected message.

| Where | Seeded bug | Killed by |
|---|---|---|
| runtime | transmits before the write-ahead record; writes it only for the first attempt; ignores a failed NVM write | dosing exploration: write-ahead |
| runtime | re-sends a possibly-sent dose after a power loss | dosing exploration: resend |
| runtime | fires timers early; keeps time and deadlines in 32 bits | dosing exploration: timer due time |
| runtime | ends a call whose deadline passed after sending `Done` | dosing exploration: R7 against the pump |
| runtime | writes every record to the same slot (R4); leaves a completed call in the ledger | dosing exploration: completion |
| runtime | traces a call as committed before its record is written | dosing exploration: commit |
| runtime | takes a torn write that left the magic word for another build's record (no header CRC) | dosing exploration: quarantine; runtime tests |
| runtime | asks to be woken before a timer can fire | dosing exploration: livelock |
| decider | doses again after `Unknown`; reads the counter without waiting out the horizon; waits only `TH_RETRY_MS` after a dose | dosing exploration: dose spacing |
| decider | gives one dose more than prescribed | dosing exploration: overdose |
| decider | counts a dose with an `Unknown` outcome as given | dosing exploration: therapy state against the pump |
| harness | a nondeterministic replay; room for too few choices, events or call keys (the bound guards) | dosing exploration: replay, bound |
| runtime | takes a NACK of a retry as proof that no attempt applied | transfer exploration: R7 against the valves |
| decider | treats `Unknown` as no effect; counts the destination valve as `No` while it may still be opened; counts a valve as not undone while it may still be closed; STATUS answers `Done` while the destination valve is opening | transfer exploration: R14 claim |
| decider | builds the claim by hand as (source, destination) | transfer exploration: seven values |
| decider | never records that the transfer ended; returns to idle at the end | transfer exploration: transfer settles |
| device | CLOSE does not fence its token, so the compensation is not absorbing | transfer exploration: R14 claim |
| algebra | weak-Kleene `and`; an `Unknown` compensation counts as undoing; an open call counts as `No` | algebra check |
| runtime | disables preflight check 2 to 8 (one bug each); accepts more than 16 keys; no quarantine; NACK always conclusive; no write-ahead on retries; ignores NVM errors; no Ball check; starts fresh on corrupt slots; quarantines on one torn first write; no header CRC; ignores the layout; passes the committed state itself; 32-bit time | runtime tests |

Preflight check 1 (outputs per decision) has no seeded bug: while `BA_MAX_OUTPUTS` equals `BA_MAX_CALLS`, check 2 refuses the same decisions.

The transfer bug that counts the destination valve as `No` before it is requested makes an unlabelled snapshot, which [Core R14](../../spec/ballarch-core-2.0.md#51-author-rules) forbids as a claim about the operation. The exploration finds a false claim with it: `NotAllDone` while both valves end open. This reference therefore counts such a call as `Maybe` until the controller can no longer make it.

### `deep`

The same explorations with larger bounds. Each prints its run count; no total is asserted for these bounds.

| Exploration | Faults per run | Link fates explored |
|---|---|---|
| dosing | 1 | first 8 transmissions |
| transfer | 1 | every transmission |
| dosing | 2 | first 3 transmissions |
| transfer | 2 | first 4 transmissions |

## Not checked here

R3 holds by construction (one main loop calls into the runtime) and has no separate check. R9 (overdue replies), R10 (composition of several Balls) and the runtime part of R12 have nothing to check with one Ball per runtime and no deferred replies. R16 holds by construction: one runtime owns the NVM. The dosing example makes no claim about a composite operation, so R14 is checked only in the transfer example. Only the Durable profile is explored. Answers addressed to a controller that has lost power are dropped rather than delivered after the restart.

As Core §9.4 lists, this reference does not implement Cancel, intents, deduplication windows, attempt timeouts, reconciled ports, reads that return a revision (`ba_state` returns the committed state without one), state migration, or the conformance trace of [Core §9.6](../../spec/ballarch-core-2.0.md#96-conformance-trace). On a record of another layout it quarantines the instance and writes nothing, but it reports no record.

These are bounded checks of two examples on a simulated platform. They are not a proof for all programs, schedules or hardware, and the code has no software license yet (see [`NOTICE.md`](../../NOTICE.md)).
