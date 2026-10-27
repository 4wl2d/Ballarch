---
name: ballarch
description: "Use when writing or reviewing code that changes state and calls something that can fail or take effect twice — payments, order or form submits, sends, bookings, offline queues and sync, device or register commands, AI-agent tool calls — or code that uses Ballarch (Ball, decider, port, effect class, Done/NotDone/Unknown). Kotlin, Swift, Rust, TypeScript, C, SystemVerilog."
---

# Ballarch

Every effect has one owner, a Ball. Every call ends `Done`, `NotDone` (evidence that nothing happened) or `Unknown` (it may have happened). The runtime, not feature code, does retries, idempotency keys, recovery and reconciliation. To review, check sections 3–6; edit only when asked.

## 1. Fit check

Ballarch applies only where code calls an Idempotent or NonIdempotent port: something that changes the world and can fail after the request left. Rendering, view state, navigation, caches, mappers, Safe reads and writes inside one database transaction stay plain code. If nothing calls such a port, write plain code and stop. Add no layers for Ballarch: one effect is one Ball in one file; keep adapters, screens and wiring out of the Ball's package.

## 2. Name the owner

One Ball type owns the facts that change with the effect, keyed by an order, a device or an agent task. Facts under one invariant share a Ball. A screen, ViewModel, repository or service is not a Ball.

## 3. Declare ports

Declare ports where the application is wired:

- **Class.** `Safe`: no effect. `Idempotent`: the destination deduplicates by the key the runtime sends. `NonIdempotent`: each delivery may act. If unsure, `NonIdempotent`. Tool annotations, API docs and datasheets inform the class, never set it. A read that clears, pops or acknowledges is NonIdempotent.
- **Window** for an Idempotent port: how long the destination remembers a key, less transit time.
- **Delivery horizon and Safe status port**, where they exist, for reconciliation.
- **Retries and attempt timeouts** only on Safe and Idempotent ports.

## 4. Write the Ball

- State is sealed stages, including "checking": it may or may not have happened.
- `decide` calls through the context and stores each call reference, plus an intent from the context for an effect it may repeat.
- `complete` matches the stored reference and handles `Done`, `NotDone`, `Unknown` and "another call" exhaustively.
- After `Unknown` on an Idempotent adapter port, repeat only with the same intent, inside the window. Calls to other Balls take no intent: carry an operation key. A repeat's `NotDone` covers only itself: stay in "checking".
- After `Unknown` on a NonIdempotent port, never repeat until a Safe status read made after the delivery horizon reports nothing happened. A reconciled port makes that read itself and ends `NotDone`, not `Unknown`.
- A second submit joins the in-flight stage or is rejected.
- Deciders are pure: time, keys and intents come from the context; no I/O, clocks, randomness, globals or threads.
- The UI reads committed state (never by a request) and derives Idle, Pending, Done, NotDone or Unknown with a pure function.
- A multi-call operation reports a knowledge value (Done, NotDone, Partial, Unknown, NotAllDone, SomeDone, NoEffects) from all its calls, counting open or still-possible ones as "maybe". Reply once settled.
- Use the Durable profile when an effect outlives the process.

## 5. Outcome by class

| Port class | `Done` | `NotDone` | `Unknown` |
|---|---|---|---|
| Safe | use the value | nothing happened; ask again | cannot occur |
| Idempotent (adapter) | use the value | nothing happened; may request again | "checking"; repeat with the same intent, inside the window |
| NonIdempotent | use the value | nothing happened; may request again | "checking"; no repeat until evidence |

## 6. Never

- write a retry loop in a feature, adapter or client configuration;
- generate an idempotency key, or let a model choose one;
- map a timeout or exception after sending to failure;
- show `Unknown` as failed, or re-enable a NonIdempotent submit;
- report "done" unless the knowledge value is `Done`;
- give a call a deadline beyond its port's deduplication window;
- edit oracles, conformance tests or fault explorations to make them pass.

## 7. Templates

Kotlin and C use the reference APIs (`ballarch.kernel`, `ba_*`). Swift, Rust and TypeScript are sketches: no reference code and no conformance claim.

Kotlin:

```kotlin
val Charges = AdapterPort<Long, String>("payments.charge", EffectClass.Idempotent)
sealed interface Pay {
    data object Idle : Pay; data class Paid(val receipt: String) : Pay; data class NotPaid(val why: String) : Pay
    data class Charging(val cents: Long, val intent: Intent, val call: CallRef<String>, val repeat: Boolean) : Pay
    data class Checking(val cents: Long, val intent: Intent) : Pay // may or may not have happened
}
fun charge(cents: Long, intent: Intent, repeat: Boolean, ctx: Context): Accept<Pay, Pay> { // ctx.newIntent() and false, or Checking's intent and true
    val (call, send) = ctx.call(Charges, cents, timeoutMillis = 60_000, intent = intent)
    return Pay.Charging(cents, intent, call, repeat).let { accept(it, it, send) }
}
fun completeCharge(s: Pay.Charging, c: Completion<*>): Step<Pay, Pay> = when (val o = c.outcomeFor(s.call)) {
    is Outcome.Done -> step(Pay.Paid(o.value))
    is Outcome.NotDone -> step(if (s.repeat) Pay.Checking(s.cents, s.intent) else Pay.NotPaid("${o.reason}")) // a repeat's NotDone says nothing of the first call
    is Outcome.Unknown -> step(Pay.Checking(s.cents, s.intent)) // never NotPaid
    null -> step(s) } // a completion of another call
```

Swift:

```swift
enum Pay: Codable, Sendable {
    case idle, paid(receipt: String), notPaid(why: String)
    case charging(cents: Int, intent: Intent, call: CallRef, isRepeat: Bool)
    case checking(cents: Int, intent: Intent) // may or may not have happened
}
func complete(_ state: Pay, _ c: Completion) -> Step<Pay> {
    guard case let .charging(cents, intent, call, isRepeat) = state, c.ref == call else { return Step(state) }
    switch c.outcome(as: String.self) { // no default: a missing case does not compile
    case .done(let receipt): return Step(.paid(receipt: receipt))
    case .notDone(let reason): return Step(isRepeat ? .checking(cents: cents, intent: intent) : .notPaid(why: "\(reason)"))
    case .unknown: return Step(.checking(cents: cents, intent: intent)) // never notPaid
    }
}
```

Rust:

```rust
enum Pay { Idle, Paid(String), NotPaid(String),
    Charging { cents: u64, intent: Intent, call: CallRef, repeat: bool },
    Checking { cents: u64, intent: Intent } } // may or may not have happened
fn complete(state: Pay, c: &Completion<String>) -> Step<Pay> {
    match state {
        Pay::Charging { cents, intent, call, repeat } if c.call == call => match &c.outcome {
            Outcome::Done(receipt) => Step::to(Pay::Paid(receipt.clone())),
            Outcome::NotDone(why) => Step::to(if repeat { Pay::Checking { cents, intent } } else { Pay::NotPaid(why.to_string()) }),
            Outcome::Unknown(_) => Step::to(Pay::Checking { cents, intent }), // never NotPaid
        },
        other => Step::to(other), // not charging, or another call
    }
}
```

TypeScript:

```ts
type Pay = { s: "idle" } | { s: "paid"; receipt: string } | { s: "notPaid"; why: string }
  | { s: "charging"; cents: number; intent: Intent; call: CallKey; repeat: boolean }
  | { s: "checking"; cents: number; intent: Intent }; // may or may not have happened
function complete(state: Pay, c: Completion<string>): Step<Pay> {
  if (state.s !== "charging" || c.call !== state.call) return { state }; // another call
  const o = c.outcome;
  switch (o.kind) { // a missing case fails to compile at assertNever
    case "done": return { state: { s: "paid", receipt: o.value } };
    case "notDone": return { state: state.repeat ? { s: "checking", cents: state.cents, intent: state.intent } : { s: "notPaid", why: o.reason } };
    case "unknown": return { state: { s: "checking", cents: state.cents, intent: state.intent } }; // never notPaid
    default: return assertNever(o);
  }
}
```

C (call keys are idempotency keys; no intents):

```c
enum { IDLE, CHARGING, PAID, NOT_PAID, CHECKING }; enum { PORT_CHARGE = 1 }; /* CHECKING: may or may not have happened */
typedef struct { uint8_t stage; int32_t cents; uint32_t call; } pay_state;
static ba_verdict pay_step(const void *s, const ba_input *in, ba_ctx *ctx, void *n, ba_outputs *out, int32_t *reply) {
    const pay_state *st = s; pay_state *next = n; *next = *st;
    if (in->kind == BA_IN_REQUEST) {                 /* a second tap joins; CHECKING never re-sends */
        if (st->stage == IDLE || st->stage == NOT_PAID) {
            ba_call c = {PORT_CHARGE, ba_new_key(ctx), in->arg, 60000};
            next->stage = CHARGING; next->cents = in->arg; next->call = c.key; ba_emit(out, c);
        }
        *reply = next->stage; return BA_ACCEPT;
    }
    if (st->stage == CHARGING && in->key == st->call) /* Unknown, or anything else, is CHECKING */
        next->stage = in->outcome.kind == BA_DONE ? PAID : in->outcome.kind == BA_NOT_DONE ? NOT_PAID : CHECKING;
    return BA_ACCEPT;
}
```

SystemVerilog: one `always_comb` decider with defaults; the clock edge commits; the send horizon is `valid && ready` (on AXI, VALID); no tag reuse within the response window.

## 8. No runtime in this language?

Never improvise one in feature code. Implement ten parts, or ask first:

1. one step at a time per instance;
2. decide, check, commit state and outputs atomically, then dispatch;
3. a call table: phase, class recorded at commit, attempts, deadline, intent, and a runtime-derived key never repeated while a destination may remember it;
4. one function that classifies every outcome by recorded class and phase;
5. a deadline and exactly one completion per call; retries only for Safe and Idempotent ports; a timer port;
6. request deduplication, calls between Balls, and notices delivered at most once, in order;
7. bounds, and checks before commit, including intents and windows;
8. reads that commit nothing;
9. for durability: atomic writes, a write-ahead record before each send, recovery by recorded class, one runtime per store;
10. a trace of runtime events and ground truth for a checker.

## 9. Checks to run

- the project's decider purity rule (bytecode rule, lint or crate dependencies);
- composition refusals (retries, attempt timeouts or a window on the wrong class);
- decider tests as plain functions: every stage against every outcome;
- a fault and crash exploration against simulated destinations with ground truth;
- the trace checker, if the project has one.

## 10. Report

End with:

```text
Checked: <the tests, explorations and rules that ran, and their results>
Not checked: <what did not run, and why>
Assumptions: effect classes, deduplication windows, delivery horizons, adapter honesty
```
