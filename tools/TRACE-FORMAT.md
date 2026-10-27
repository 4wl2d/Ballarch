# Conformance trace format, version 1

A conformance trace records one run of a Ballarch runtime under a simulated environment: the events the runtime emitted, and what really happened at the simulated destinations (the ground truth). The format is JSON Lines, so a runtime written in any language can produce it. [`oracles.py`](oracles.py) checks a trace against the trace properties T3 and T5–T9, which are the checkable parts of the runtime rules R3 and R5–R9 of the [specification](../spec/ballarch-core-2.0.md).

The Kotlin reference writes this format with `TraceJson.write` or `Harness.writeTrace` in `ballarch-testkit`. Its `Oracles.check` implements the same properties and reports the same messages, with `R` in place of `T`. A test in `ballarch-runtime` checks that the two agree on sample and seeded-violation traces.

## File

- The file is UTF-8 text with one JSON object per line. Blank lines are ignored.
- Every object has a string field `event` that names its kind.
- The first line is the `Header`. The last line is the `End`. A file without an `End` line is incomplete and is rejected.
- Runtime events appear in the order the runtime emitted them.
- `Destination` lines may appear anywhere between the `Header` and the `End`. Writers put them after the runtime events.
- Readers ignore fields they do not know. An unknown `event` name is an error in version 1.

## Values

| Name | JSON | Example |
|---|---|---|
| instance | object `{"type": string, "key": string}`: a Ball instance | `{"type":"payer","key":"p1"}` |
| call | object `{"instance": instance, "key": string}`: the calling instance and the call key | `{"instance":{"type":"payer","key":"p1"},"key":"1.0"}` |
| request | string: an inbound request identity | `"ext:1:0"` |
| outcome | object `{"kind": "Done"}`, `{"kind": "NotDone", "reason": R}` or `{"kind": "Unknown", "reason": U}`, with an optional string `detail` | `{"kind":"NotDone","reason":"NotApplied","detail":"connection refused"}` |
| effect class | string `"Safe"`, `"Idempotent"` or `"NonIdempotent"` | `"NonIdempotent"` |

`R` is one of `Refused`, `Overloaded`, `Cancelled`, `DeadlineBeforeSend`, `NoAnswer`, `NotApplied`, `IdempotencyConflict`, `Fault`. `U` is one of `DeadlineAfterSend`, `LostAfterSend`, `RecoveredAfterCrash`, `RetriesExhausted`. Application values (states, messages, reply values) are not part of the format. The properties do not depend on them.

Messages print an instance as `type/key` and a call as `type/key#key`.

## Lines

### Header and End

| Event | Fields |
|---|---|
| `Header` | `format`: `"ballarch-trace"`; `version`: `1`; `profile`: `"Durable"` or `"Transient"` |
| `End` | `quiescent`: `true` if the run stopped because no work was left (no queued task, timer or message), `false` if it was cut off |

### Runtime events

Every runtime event has `at`, the runtime clock in milliseconds (an integer), and `epoch` (see [Epochs](#epochs)).

| Event | Further fields | Emitted when |
|---|---|---|
| `DecideStarted` | `instance`, `input` (string, free text) | a decider function (`decide`, `complete`, `observe`) starts |
| `DecideFinished` | `instance` | that decider function returns or throws |
| `Committed` | `instance`, `revision` (integer), `newCalls` (array of calls), `completedCall` (call or `null`), `completedOutcome` (outcome or `null`), `replies` (array of requests), `published` (integer) | a decision is committed atomically, before any of its outputs is dispatched. `newCalls` are the calls it created, `completedCall` the call whose completion it consumed, with the outcome the decider saw, and `replies` the requests it answered |
| `CallSent` | `call`, `port` (string, see [Ports](#ports)), `attempt` (integer, from 1) | the record saying the attempt may have been sent is saved, before the transport is invoked |
| `CallResolved` | `call`, `outcome`; optional `reconciled` (boolean) | the runtime chose the call's terminal outcome and queued it for the caller. `reconciled` is `true` when the outcome came from a status read of a reconciled port (Core §3.2). Writers omit it otherwise, and no property depends on it |
| `LateReportDropped` | `call`, `report` (string: the kind of the report, without its values: `Ok`, `Refused`, `NotApplied` or `Failed` for an adapter's report; `Done`, `NotDone` or `Unknown` for another Ball's reply; `Completion` for a completion of a closed call) | a report arrived for a call already resolved or closed, and was dropped |
| `RequestRejected` | `instance`, `request`, `outcome` | a request was refused before acceptance |
| `Replied` | `instance`, `request`, `outcome` | a reply to an accepted request is delivered |
| `Fault` | `instance`, `message` (string) | a decider threw or broke a kernel rule; nothing was committed |
| `ReplyOverdue` | `instance`, `request` | an accepted request is still unanswered at its deadline |
| `Recovered` | `incarnation` (integer), `instances` (integer) | a runtime finished its start-up recovery. Under the Transient profile it precedes every other event of that runtime |

Readers accept any `report` string. It, `input`, `message` and an outcome's `detail` are text for people: no property depends on them.

### Ports

`CallSent.port` names the port of the call. A call to another Ball is written with the port `ball:<type>`, where `<type>` is the destination's Ball type, and a timer with the built-in port `ballarch.timer`. Every other port is an *adapter port*. In a conformance run every adapter port is bound to a simulated destination; calls to other Balls and timers have none. The status port of a reconciled binding is an adapter port too, but a status read about a call is not a call: it has no `CallSent` and no `Destination` line.

### Ground truth

| Event | Fields |
|---|---|
| `Destination` | `epoch`; `call`; `effectClass`; `deliveries` (integer: times a request of the call reached the destination, duplicates included); `processed` (boolean: the destination handled the call at least once); `applied` (integer: times its effect was applied, 0 for a Safe destination) |

The simulated environment, not the runtime, writes the ground truth: one `Destination` line for each call that was handed to a simulated destination in an epoch, that is, each call with a `CallSent` on an adapter port in that epoch, with the counts at the end of the run. The environment records a call when an attempt is handed to it, before any fault applies, so a call whose every request was lost has a line with `deliveries` 0, `processed` false and `applied` 0. Calls never sent, calls to other Balls and timers have no line.

A trace whose ground truth does not match its calls is not valid, and `oracles.py` rejects it at the earliest offending line:

- **G1.** Every `Destination` line's `epoch` is an epoch of the trace: 0 under the Durable profile; from 0 to the epoch of the last runtime event under the Transient profile.
- **G2.** Each call with a `CallSent` on an adapter port has exactly one `Destination` line in that epoch.
- **G3.** Each `Destination` line's call has a `CallSent` on an adapter port in that epoch.

Without G2, a writer that left out the line of a call whose every request was lost would hide a false `Done` from T7.

## Epochs

Under the **Durable** profile every runtime event has epoch 0. Restarts continue one world: the same call identities and the same ground truth.

Under the **Transient** profile a restart begins a new world. State is empty again and call identities may repeat. Epochs number these worlds. The first runtime event has epoch 0. Each `Recovered` event that is not the first runtime event starts the next epoch, and it and the events after it carry the new number. `Destination` lines carry the epoch whose runtime sent the call. A late delivery after a crash still counts in the sender's epoch. A trace whose runtime events do not follow this numbering is rejected.

## Properties

Under the Transient profile T5–T9 are judged on each epoch separately, against that epoch's `Destination` lines. A message from an epoch is prefixed `[epoch N] ` when the trace has more than one epoch. Counts in `Destination` lines are those at the end of the run, so late deliveries count.

**T3, serial processing (R3).** Between `DecideStarted(i)` and the next `DecideFinished(i)` or `Recovered`, no other `DecideStarted(i)` occurs. This is judged over the whole trace, and a `Recovered` ends every open decision.
Message: `T3: overlapping decisions on <instance> at <at>`.

**T5, commit before dispatch (R5).** Every `CallSent(c)` is preceded, in its epoch, by a `Committed` whose `newCalls` contains `c`.
Message: `T5: <call> sent before its decision was committed`.

**T6, one completion per call (R6).** Each call is the `completedCall` of at most one `Committed` in its epoch, and only of one that follows a `Committed` of that epoch whose `newCalls` contains it. If the `End` line says `quiescent` and the epoch owes liveness, every call in some `newCalls` of the epoch is also the `completedCall` of exactly one `Committed`. Under the Durable profile the single epoch owes liveness. Under the Transient profile only the last epoch does, since a crash loses the calls of the others.
Messages: `T6: <call> completed but never committed`, `T6: <call> completed <n> times`, `T6: <call> never completed`.

**T7, honest outcomes (R7).** For each `Committed` with a `completedCall` `c` that has a `Destination` line in its epoch (by G2, every call sent on an adapter port):
- if `completedOutcome` is `NotDone` and `effectClass` is not `Safe`, then `applied` is 0;
- if `completedOutcome` is `Done`, then `processed` is true.

Messages: `T7: <call> reported NotDone(<reason>) but its effect was applied <n> time(s)`, `T7: <call> reported Done but the destination never handled it`.

**T8, no re-send of non-idempotent calls (R8).** For each call whose `Destination` line has `effectClass` `NonIdempotent`, the epoch holds at most one `CallSent` for it, and `applied` is at most 1. Under the Durable profile this holds across restarts.
Messages: `T8: non-idempotent <call> sent <n> times`, `T8: non-idempotent <call> applied <n> times`.

**T9, one reply per request (R9).** Each `(instance, request)` pair occurs in at most one `Replied` of its epoch.
Message: `T9: <instance> replied <n> times to <request>`.

### What the properties do not cover

- R4 (atomic commit) shows only indirectly, as the absence of half-applied decisions after recovery. Crash exploration is the evidence. R11 (bounds and preflight) and R13 (honest profile claims) are not trace properties.
- T6 does not check that a completion arrives by the call's deadline. T9 checks only the at-most-once half of R9. An answer that never comes appears as a `ReplyOverdue` event, which the properties do not judge.
- T7 and T8 judge only calls sent on adapter ports, so they need a simulated environment that records ground truth. Calls between Balls are covered by T5, T6 and the destination's own deduplication.
- The properties are checked on the runs that were explored. They hold for other runs only as far as the exploration covered them.

## Producing a trace

A runtime in another language needs three things:

1. A tracer that emits the runtime events above at the moments named in the table, with the runtime's own clock.
2. A simulated environment that binds every adapter port, injects faults (lost requests and replies, errors after the effect, refusals, late replies, crashes and restarts) and records the ground truth of each call from the moment an attempt is handed to it. For Idempotent destinations it deduplicates by the idempotency key the runtime supplies and remembers keys across restarts, as a real destination does; call identities may repeat after a Transient restart, keys do not.
3. A writer that renders both in this format and numbers epochs as described above.

The Kotlin reference does all three in `ballarch-testkit`: `Harness`, `SimPort` with `GroundTruth`, and `TraceJson`.

## Checking a trace

```sh
python3 tools/oracles.py path/to/*.jsonl
python3 tools/oracles.py --expect-violations path/to/seeded/*.jsonl
```

The script prints one line per violation, `TRACE: MESSAGE`, and a summary on standard error (`--quiet` omits it). The exit status is 0 if every trace satisfies every property, 1 if a property is violated, and 2 if a file cannot be read or is not a valid trace. With `--expect-violations` every trace must violate a property: the script prints `TRACE: no violation found` for each trace that passes, and exits with 0 if none passes, 1 if one does, and 2 if a file is not valid. It needs Python 3.8 or later and only the standard library. Its tests are in [`test_oracles.py`](test_oracles.py): `python3 -m unittest discover -s tools -p 'test_*.py'`.

The Kotlin build (`./gradlew build` in `reference/kotlin`) writes sample traces to `reference/kotlin/ballarch-runtime/build/conformance-traces/`. The traces at its top level come from the exploration tests, with and without crashes, and from the clean runs that the seeded violations were made from. All of them must pass. The traces in its `seeded/` subdirectory each carry a seeded violation, and each must fail. From the repository root, after the build:

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/oracles.py reference/kotlin/ballarch-runtime/build/conformance-traces/*.jsonl
python3 tools/oracles.py --expect-violations reference/kotlin/ballarch-runtime/build/conformance-traces/seeded/*.jsonl
```

Each command exits with 0 when the traces conform.

## Example

A Durable run in which a payer makes one non-idempotent call that succeeds:

```json
{"event":"Header","format":"ballarch-trace","version":1,"profile":"Durable"}
{"event":"Recovered","at":1000,"epoch":0,"incarnation":1,"instances":0}
{"event":"DecideStarted","at":1000,"epoch":0,"instance":{"type":"payer","key":"p1"},"input":"request ext:1:0"}
{"event":"DecideFinished","at":1000,"epoch":0,"instance":{"type":"payer","key":"p1"}}
{"event":"Committed","at":1000,"epoch":0,"instance":{"type":"payer","key":"p1"},"revision":1,"newCalls":[{"instance":{"type":"payer","key":"p1"},"key":"1.0"}],"completedCall":null,"completedOutcome":null,"replies":[],"published":0}
{"event":"CallSent","at":1000,"epoch":0,"call":{"instance":{"type":"payer","key":"p1"},"key":"1.0"},"port":"payment","attempt":1}
{"event":"CallResolved","at":1010,"epoch":0,"call":{"instance":{"type":"payer","key":"p1"},"key":"1.0"},"outcome":{"kind":"Done"}}
{"event":"DecideStarted","at":1010,"epoch":0,"instance":{"type":"payer","key":"p1"},"input":"completion 1.0"}
{"event":"DecideFinished","at":1010,"epoch":0,"instance":{"type":"payer","key":"p1"}}
{"event":"Committed","at":1010,"epoch":0,"instance":{"type":"payer","key":"p1"},"revision":2,"newCalls":[],"completedCall":{"instance":{"type":"payer","key":"p1"},"key":"1.0"},"completedOutcome":{"kind":"Done"},"replies":["ext:1:0"],"published":0}
{"event":"Replied","at":1010,"epoch":0,"instance":{"type":"payer","key":"p1"},"request":"ext:1:0","outcome":{"kind":"Done"}}
{"event":"Destination","epoch":0,"call":{"instance":{"type":"payer","key":"p1"},"key":"1.0"},"effectClass":"NonIdempotent","deliveries":1,"processed":true,"applied":1}
{"event":"End","quiescent":true}
```

If the second `Committed` reported `{"kind":"NotDone","reason":"NoAnswer"}` instead, `oracles.py` would print `T7: payer/p1#1.0 reported NotDone(NoAnswer) but its effect was applied 1 time(s)` and exit with status 1. `TraceConformanceTest` in `ballarch-runtime` checks that `TraceJson` writes exactly these lines.
