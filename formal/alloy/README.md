# Alloy model of the call protocol

[`obligations.als`](obligations.als) models one caller Ball under the Durable profile, a network that loses and duplicates messages, and one destination that deduplicates repeats. It abstracts business state away: a committed decision creates a call, and the call must receive exactly one terminal outcome. [Core §4.1](../../spec/ballarch-core-2.0.md#41-the-call-protocol) states the properties.

## Checks

From the repository root, with Python 3, curl and a Java runtime:

```
bash formal/alloy/fetch-alloy.sh
python3 formal/alloy/run.py
```

`fetch-alloy.sh` downloads the Alloy 6.2.0 distribution jar from Maven Central and checks its SHA-1. `run.py` runs every `check` and `run` command of the model and compares each result with the command's `expect` annotation. It exits with a non-zero status if any result differs, and writes a summary to `formal/alloy/build/RESULTS.md` (`--out` changes the path; `--alloy` or `ALLOY_JAR` points to another jar).

What the commands check:

- **Properties of the correct protocol** (`expect 0`, no counterexample): P1–P6 for 3 calls and traces of up to 14 steps, and L1 for 2 calls and 12 steps.
- **Seeded runtime bugs** (`expect 1`, a counterexample must exist): `SendBeforeCommit` breaks P1, `DoubleCompletion` breaks P2, `NotDoneAfterSend` breaks P3, `ResendAfterCrash` and `NoWriteAhead` break P4, and `SplitCommit` breaks P6 and L1.
- **Reachability** (`expect 1`): each outcome of a NonIdempotent call, an `Unknown` call whose effect was applied, a re-sent Idempotent call, a crash with an open call, a duplicated delivery without a second effect, and a fair trace with an awaited call and a crash. These show that the properties do not hold vacuously.

These are bounded results, not proofs for all sizes.

## Correspondence with the reference runtime

[Core §9.1](../../spec/ballarch-core-2.0.md#91-a-conforming-runtime) asks a conforming runtime to relate its call lifecycle to this model. For the Kotlin reference ([`Runtime.kt`](../../reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Runtime.kt), [`Store.kt`](../../reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Store.kt)) the correspondence is:

| Model | Reference runtime |
|---|---|
| `Phase`: `Pending`, `Sending` | `ballarch.runtime.Phase` in the call ledger of `InstanceRecord`. Its third value, `Reconciling`, exists only on reconciled ports, which the model does not cover. |
| `kind`, fixed for each call | `CallRecord.effect`: the effect class the port had when the call was committed (a Ball port: `Idempotent`). Retries, attempt timeouts, deadlines and recovery classify the call by it, never by the port's current binding ([Core §6.1](../../spec/ballarch-core-2.0.md#61-evolution)). |
| `Created`, `Awaiting` | the call ledger and the business state of one `InstanceRecord`, which is saved as a whole |
| `commit[c]` | `commit`: one `store.save` of the new state, the new ledger entries in phase `Pending`, replies and notices |
| `markSending[c]` | `startSend`: `saveLedger` with phase `Sending` and the next attempt number, before any transport runs |
| `send[c]` | `startSend`: `adapter.execute`, or delivery to a Ball port, after the write-ahead record |
| `retry[c]` | `failAttempt`: a new attempt for Safe and Idempotent ports within the retry budget; composition refuses retry policies on NonIdempotent ports. An attempt also ends without an answer at its attempt timeout (`onAttemptTimeout`), which then calls `failAttempt`. Each new attempt saves the ledger again with phase `Sending` and a higher attempt number. In the model the phase does not change, so that save is a stutter. |
| `receive[c]` | `onAdapterResult` with `AdapterResult.Ok`, or a Ball reply, then `resolve` with `Done` |
| `timeout[c]` | `onDeadline`: `Pending` gives `NotDone(DeadlineBeforeSend)`; `Sending` gives `NotDone(NoAnswer)` for a Safe call and `Unknown(DeadlineAfterSend)` otherwise. The model has no clock, so `timeout` also stands for a call that ends early after its last attempt failed (`endWithoutAnswer`) or that is past its budget after a restart (`recover`). |
| `crash` | `Runtime.crash()`: the instance stops processing, and callbacks from that incarnation are ignored. Only what `Store` holds survives. |
| `recover` | `Runtime.start()` on the same store calls `recover`. A `Pending` call is sent. A `Sending` call recorded NonIdempotent ends `Unknown(RecoveredAfterCrash)`. A `Sending` repeatable call is sent again within the attempt budget of its port's current binding; past that budget it ends `Unknown(RetriesExhausted)`, or `NotDone(NoAnswer)` for a Safe call. A call past its deadline is classified as by `timeout`. |
| `deliver`, `dropRequest`, `dropResponse` | the destination and the network; in tests, the simulated destinations (`SimPort`) and `ScriptedAdapter` |
| `stutter` | any step that changes no modelled variable, for example a decision without calls |

## What the model does not cover

Its properties say nothing about the following. The trace properties of [Core §9.6](../../spec/ballarch-core-2.0.md#96-conformance-trace) and the reference tests listed in [Appendix F](../../spec/ballarch-core-2.0.md#appendix-f-checks-and-evidence-in-the-reference-implementations-informative) check them instead.

- **Evidence of non-application.** `AdapterResult.Refused` and `AdapterResult.NotApplied` give `NotDone` only when the evidence covers every attempt that may have been delivered (`onAdapterResult`).
- **Transport failure before the deadline.** A failure reported by an adapter ends the attempt through `failAttempt`, which retries or ends the call. The model has no such report, only `retry` and a `timeout` that may happen at any time.
- **Attempt budgets.** The model retries a repeatable call without a bound. A budget only removes retries; a call past it ends as by `timeout`.
- **Idempotency keys and intents.** The model's destination deduplicates repeats for the whole trace. Keys, intents and deduplication windows are checked by `ExplorationTest` (S4b, S8, S9) and `RuntimeRulesTest`.
- **Attempt timeouts.** Checked by `RuntimeRulesTest` and `ExplorationTest` (S12).
- **Reconciled ports** and the phase `Reconciling`. Checked by `ReconciliationTest` (S10).
- **Exclusive ownership** (R16). The model has one runtime. Checked by `OwnershipTest` (S11).
- **Cancellation.** `Cancel` withdraws a `Pending` call with `NotDone(Cancelled)` and is otherwise a request to the adapter to stop.
- **Several destinations and several instances.**
- **Inbound requests, replies, notices and reads.**
- **Code changes across a restart.** In the model the code never changes. The start-up check of Core §6.1, which refuses a store that the new composition cannot serve before anything is written, is checked by `EvolutionTest`.

The model is scoped to the Durable profile. For a Transient runtime the correspondence holds for executions without a crash: a Transient crash ends the instance together with its calls ([Core §6](../../spec/ballarch-core-2.0.md#6-profiles)).
