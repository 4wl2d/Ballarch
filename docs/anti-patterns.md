# Anti-patterns

Each entry names a mistake, why it is wrong, the fix, the rule it breaks, and what catches it. Rules are those of [Core §5](../spec/ballarch-core-2.0.md#5-kernel-rules); C1–C6 are the [adapter checks](../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative). Section numbers (§) refer to the [Core specification](../spec/ballarch-core-2.0.md). "Caught by" lists only checks that exist in this repository; "Review" means that no mechanism exists.

## Deciders and state

| Anti-pattern | Why it is wrong | Fix | Rule | Caught by |
|---|---|---|---|---|
| **Reading the clock or a random generator in a decider** (`System.currentTimeMillis()`, `Instant.now()`, `Random`) | Decisions stop being reproducible, and tests and recovery diverge. | Take time from `ctx.now`, and keys and intents from the context. | R2 | `deciderPurity` (architecture rule) |
| **I/O, sleeping or threads in a decider** | The runtime cannot commit what happened, so the effect escapes commit-before-dispatch. | Make the effect a call on a port; wait with `Timer.after`. | R2, R5 | `deciderPurity` |
| **Mutable values in state or messages** (`var` properties, `MutableList`) | Another holder can change committed state behind the runtime's back. | Use immutable data classes and read-only collections. | R1 | `immutableValues` |
| **Top-level mutable variables shared by features** | A hidden writer and a hidden channel between features. | Move the fact into one Ball's state. | R1, R10 | `noGlobalMutableState` |
| **A feature importing another feature's implementation** | Couples to private types and bypasses the port. | Depend only on its Ball port, request, reply, topic and payload types. | R10 | `featuresUseOnlyPublicApis`, `checkModuleBoundaries` |
| **Waiting for another Ball inside a decision** | Deadlock, or a decision that depends on uncommitted state. | Call its port and handle the completion in `complete`. | R3, §3.4 | Impossible through the kernel API (no synchronous call exists) |
| **A global or static variable in a firmware decider** | State that no NVM commit covers; it diverges after power loss. | Keep it in the committed record. | R1, R2 | `tools/check_purity.sh` in [`reference/c`](../reference/c/) |
| **Inferring a latch in RTL next-state logic, or driving one register from two processes** | Hidden or contested state: the clock edge no longer commits one owner's decision. | Give every signal a default in `always_comb`, and write each register from one `always_ff`. | R1, R2 | Yosys `check -assert` (two drivers) and the latch and storage checks of [`reference/rtl/checks/rules.ys`](../reference/rtl/checks/rules.ys) |

## Ports, adapters and keys

| Anti-pattern | Why it is wrong | Fix | Rule | Caught by |
|---|---|---|---|---|
| **Declaring a port `Idempotent` because retries "usually" work, or because the request only sets a value** (a PUT, a register write) | The runtime will retry, and the destination applies the effect twice, or applies a late attempt after a later request. | Declare `Idempotent` only if the destination deduplicates by the idempotency key the runtime supplies ([Core §2](../spec/ballarch-core-2.0.md#2-model)); otherwise `NonIdempotent`. | §2.6, R8 | Review: effect classes are declarations. The adapter checks (C5) confirm that an adapter passes the supplied key. |
| **Trusting MCP tool hints** (`readOnlyHint`, `idempotentHint`) | As of 2026-10-27, the [MCP specification](https://modelcontextprotocol.io/specification/2026-07-28/server/tools) makes annotations hints that clients must treat as untrusted unless the server is trusted. A wrong hint lets the runtime retry a tool that applies twice. | Declare the class in your own code. No hint, or an untrusted server, means `NonIdempotent` ([mapping](ai-agents.md#4-tools-are-ports)). | §2.6 | Review |
| **A retry loop in an adapter or a feature**, including an SDK's transparent retries | Retries are invisible to the runtime and multiply its own. A NonIdempotent call can be applied several times. | One attempt per invocation. Declare a `RetryPolicy` with an attempt timeout in the binding, for Safe and Idempotent ports only. | R8, C2 | Adapter checks (C2); the composition refuses a retry policy on a NonIdempotent port. A loop in a feature: review. |
| **A new key per retry or resubmit** (a fresh UUID per HTTP send, a key the model chooses) | The destination sees each attempt as a new request and applies each one. | Pass `invocation.idempotencyKey`. To resubmit after `Unknown`, name the earlier call's intent. | R8, §3.2 | Adapter checks (C5); for a resubmit, your own exploration, as in scenario S8, whose control without the intent applies twice |
| **An intent reused for a different request** | The destination deduplicates by key: it replays the first answer or refuses the new request, so the new effect never happens. | One intent per effect. Issue a new intent when the request changes; the place-order example rejects a different cart while an order is in flight or being checked. | §3.2 | Review |
| **A deadline beyond the deduplication window** | The destination forgets the key before the last attempt, and that attempt applies the effect again. | Declare `dedupWindowMillis`: the key's retention less the time in transit. Past the window, reconcile. | §3.5 | Preflight refuses the call as a fault when a window is declared (S9; `PlaceOrderTest`: `a week later the deduplication window refuses to try again, and nothing is sent`). Without a window: review. |
| **Mapping a timeout or an exception after send to failure** (an adapter's `NotApplied` for a timeout or a 5xx; a `catch` that shows "failed") | A false `NotDone`: the business believes nothing happened when it may have. | Report `Failed` and let the runtime classify it. Report `NotApplied` only with evidence covering every attempt, such as a connection refused before the request was written. | R7, C3 | Adapter checks (C3); trace property T7 in simulation |
| **Treating a transport acknowledgment as `Done`** (a returned `write()`, a TCP acknowledgment, a NIC transmit completion, a broker's publish confirm) | It shows that bytes left or reached the next hop, not that the destination processed the request. | Report `Ok` only on the destination's answer. | §2.2, C4 | Review |
| **Resetting before capturing status** (a reset, flush or abort while a request may be in flight) | The action destroys the evidence of whether the request was applied. | Read and record the status first. Without it, report `Failed` for every attempt that may have been delivered. | C6 | Review |
| **A retry policy without an attempt timeout** | An attempt that its adapter never ends lasts until the deadline. The call ends `Unknown` (`NotDone(NoAnswer)` on a Safe port), and the attempt budget goes unused. | Set `attemptTimeoutMillis` in the `RetryPolicy`, below the call's timeout. | §3.2, §10 | Review; scenario S12 shows the difference |

## Calls and outcomes

| Anti-pattern | Why it is wrong | Fix | Rule | Caught by |
|---|---|---|---|---|
| **Treating `Unknown` as failure in a decider** (refunding, or calling a NonIdempotent port again) | May charge twice, or report failure after an effect occurred. | Keep a checking stage. On an Idempotent adapter port, repeat by naming the intent; otherwise reconcile. | R15 | Your own exploration test (see the [tutorial](tutorial.md)) |
| **Showing `Unknown` as an error, or re-enabling Pay** | The user pays again for an order that may exist. | Say that it may have happened ("We could not confirm your order. It may have been placed."); say "Checking…" only while a check or repeat runs. Offer "Try again" only on an Idempotent port, with the same intent; never re-enable a NonIdempotent submit ([honest UI](honest-ui.md)). | R15 | Your own exploration test; `PlaceOrderTest` has one: `under every fault and crash point no order is placed twice, and no Unknown is shown as NotPlaced` |
| **Acting on any completion** (no `CallRef` check) | A late or superseded completion changes the wrong state. | Keep the `CallRef` in the stage and match with `outcomeFor(ref)`; `null` means another call. | §8.3 | Your own tests; the runtime delivers each completion once but cannot know which one matters |
| **A Ball that never replies to a request it accepted** | The caller waits until its deadline and gets `Unknown`. | Reply now, or with a `Reply` output before the deadline. | R9 | `ReplyOverdue` trace event; `Harness.assertNoReplyLeaks()` (trace property T9 checks only the at-most-one-reply half of R9) |
| **Using a timer completion as "time has come" without checking** | After a restart the timer can fire late; with a custom timer adapter, possibly early. | Keep the due time in state and compare it with `ctx.now`. | §2.4 | Review; exploration tests |
| **Claiming exactly-once delivery** | No profile provides it for external effects. | Claim at most once or effectively once, naming the profile, runtime, scenario and evidence. | R13, §9.5 | Review |

## Composite operations and reports

| Anti-pattern | Why it is wrong | Fix | Rule | Caught by |
|---|---|---|---|---|
| **Reporting a workflow as "failed" after one of its steps ended `Unknown` or took effect** | The customer is told nothing happened while a charge or booking exists. | Report the knowledge value: `Partial`, `NotAllDone`, `SomeDone` or `Unknown`. | R14, R15 | Your own exploration test against ground truth; the trip example shows one |
| **Exposing a composite's partial effect as a plain `NotDone` or `Done`** | The caller loses the fact that something, but not everything, happened. | Return `Knowledge` in the reply type. | §7.4 | Review |
| **A snapshot used as the reply** (answering before the operation is settled, with calls not yet made counted as `No`) | A later call may contradict it, and the caller keeps that false outcome, because a completion never changes. | Accept with `acceptLater` and reply with the settled knowledge. Label progress with its stage. | R14, §7.4 | Review; your own exploration test that compares each reply with the ground truth once the operation is settled. The firmware transfer and the RTL composite each have a seeded bug of this kind, and their checks catch it. |
| **A model-written final status** | A model may report "done" for a run that duplicated or missed an effect. | Compute the status from `Knowledge` over the tool ledger; the model writes only the narrative ([agents](ai-agents.md#7-the-final-report)). | R14 | Review |
| **Counting a compensation that does not fence the original request** | A late delivery of the original request can take effect after the "undo". | Count only absorbing compensations, and name the fence or the delivery horizon in a comment. | R14, §4.2 | An exploration in which requests can arrive late: the trip example's control without the providers' fence, and the firmware transfer's seeded bug whose `CLOSE` does not fence its token |
| **One Ball per table row with a cross-Ball invariant** | An invariant spread over several owners cannot be enforced in one decision. | Put facts under one invariant in one Ball, or use a reservation or a storage constraint. | R1, §8.1 | Review |
| **One giant Ball for the whole application** | One sequential writer becomes the throughput limit. | Separate facts with independent lifecycles into their own Balls. | §8.1, §10 | Review; load tests |

## UI and runtime lifetime

| Anti-pattern | Why it is wrong | Fix | Rule | Caught by |
|---|---|---|---|---|
| **Reading UI status through `decide`** (a `GetStatus` request) | Each read commits a decision and raises the revision: a store write under the Durable profile, and a wait in the inbox. | Read with `runtime.read` or `watch`, and derive the status with a pure function, as the shop's `checkoutStatus` does. | R12 | Review |
| **A runtime scoped to a screen or ViewModel** | Open calls and their completions go with the screen. The next screen starts a second runtime, which under the Durable profile competes for the store. | Create one runtime at application scope; screens read and watch it ([structure](structure.md)). | §6, R16 | With a `FileStore`, a second runtime on the same directory fails to start (`StoreLockedException`); otherwise review |
| **Publishing notices to a subscriber that can fail** | Quarantine blocks the notices. Once the publisher's outbox is full, a request whose decision publishes is refused with `Overloaded`, and a completion or notice whose decision publishes quarantines the publisher too. | Make `observe` handle every notice the topic can carry, and test it with each. | §10 | `Fault` trace; the outbox bound (R11) |

## Deploying changes

| Anti-pattern | Why it is wrong | Fix | Rule | Caught by |
|---|---|---|---|---|
| **Removing a port, a Ball type or a subscription while the store still holds its open calls, records or notices** | The open obligations lose their destination or their decider. | Drain them under a composition that still provides it, then remove it. | §6.1 | The Durable runtime refuses to start (`IncompatibleStoreException`) and names each offending record |
| **Changing the shape of a Ball's state without raising `stateVersion` and writing `migrate`** | The new code reads states that the old code committed as if they had the new shape. | Raise `stateVersion` and supply `migrate`. | §6.1 | Only when the codec cannot decode the record: the runtime then refuses to start and names it. A change the codec accepts is left to review. |
