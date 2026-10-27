# Bindings: Ballarch in your language

Core's outcome and knowledge rules name no language ([Core §11](../spec/ballarch-core-2.0.md#11-interpretation-across-levels-informative)). Reference code exists in Kotlin on the JVM, C11 and SystemVerilog. This guide lists what any runtime must do, maps the Kotlin reference onto Android, and sketches Swift, Rust and TypeScript.

Every Swift, Rust and TypeScript block, and every Kotlin block not copied from the reference, is marked **Sketch: no reference code and no conformance claim**. Nothing here runs or tests a sketch.

## 1. The runtime contract

A runtime conforms when it meets [Core §9.1](../spec/ballarch-core-2.0.md#91-a-conforming-runtime) for the profiles it claims. That comes down to ten parts. A Transient runtime needs parts 1–8 and 10; the Durable profile adds part 9. R-numbers are [Core §5](../spec/ballarch-core-2.0.md#5-kernel-rules) rules.

1. **Serial steps per instance.** One input of one instance at a time. An input submitted during a step waits for that step and its dispatch. Steps may run on the submitting thread or on a loop ([Core §3.1](../spec/ballarch-core-2.0.md#31-the-instance-step), R3).
2. **Decide, preflight, commit, dispatch.** Commit the new state with all its calls, replies and notices in one atomic write. Only then send, reply or publish. A rejected or failed decision commits nothing (R4, R5).
3. **A call table.** Per open call: phase (`Pending`, `Sending`, and `Reconciling` if offered), the effect class recorded at commit, attempts, deadline, intent and idempotency key. The runtime derives the key from the caller and the intent, or from the call identity. No key repeats while a destination may remember it, also after a Transient restart ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)).
4. **One classify function.** The table of §3.2, implemented once, keyed by recorded class and phase. Answers, reports, deadlines, cancels and restarts all go through it. Adapters report; they never choose an outcome (R7).
5. **Deadlines, retries and timers.** Every call has a finite deadline and exactly one completion by it (R6). Only Safe and Idempotent ports are retried, within the binding's budget and attempt timeout. A retry budget or attempt timeout on a NonIdempotent port is refused when the composition is built (R8). The timer port is bound ([Core §2.4](../spec/ballarch-core-2.0.md#24-time)).
6. **Requests, Ball calls and notices.** Inbound requests are deduplicated by request identity. A call on a Ball port is a request whose identity is the call identity, kept until the call's deadline. Each subscriber gets each notice at most once, in order ([§3.3](../spec/ballarch-core-2.0.md#33-inbound-request-lifecycle), [§3.4](../spec/ballarch-core-2.0.md#34-ball-to-ball-calls), [§3.6](../spec/ballarch-core-2.0.md#36-notices)).
7. **Bounds and preflight.** Finite bounds, and new work refused before it is accepted. Preflight refuses an intent on a port that is not an Idempotent adapter port, and a deadline beyond a declared deduplication window ([Core §3.5](../spec/ballarch-core-2.0.md#35-bounds-and-preflight), R11).
8. **Reads.** The last committed state and revision, served without committing, queueing or creating a record (R12).
9. **The Durable profile.** A store that replaces a record atomically, issues a new incarnation per start, and excludes or fences a second runtime (R16), or one documented as single-runtime, whose deployment then ensures it (R13). A write-ahead record before every attempt. Recovery by the restart rows of §3.2. The start-up checks of [§6.1](../spec/ballarch-core-2.0.md#61-evolution) before any write, and again once the new incarnation is taken, since on a store that fences the replaced runtime may commit until then ([Core §6](../spec/ballarch-core-2.0.md#6-profiles)).
10. **A trace emitter.** Events of [trace format v1](../tools/TRACE-FORMAT.md) at the moments it names, so one checker can judge explored runs.

## 2. Kotlin and Android

The kernel (`ballarch-kernel`) and runtime (`ballarch-runtime`) are plain Kotlin, built and tested on the JVM only ([Kotlin reference](../reference/kotlin/README.md)). There is no Android build or device test here; this section maps the pieces without claiming the mapping was run.

### Schedulers

From [`Scheduler.kt`](../reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Scheduler.kt):

```kotlin
public class InlineScheduler(
    private val post: (Runnable) -> Unit,
    private val timer: ScheduledExecutorService = defaultTimer,
    private val clock: () -> Long = System::currentTimeMillis,
) : Scheduler {
```

- `InlineScheduler` runs steps on the thread that constructs it. On Android, construct it on the main thread as `InlineScheduler(post = Handler(Looper.getMainLooper())::post)`. A request submitted there is decided, committed and answered before `request` returns, unless it waits for an adapter. Reports from other threads arrive through `post`. `InlineSchedulerTest` checks this with a test `post`, not a real `Handler`.
- `ThreadScheduler(name)` runs steps on a dedicated thread.

Store writes run on the scheduler's thread. So a Transient UI Ball, such as a search, runs on an `InlineScheduler` with a `MemoryStore`, and a Durable Ball, such as an order, on a `ThreadScheduler` with a durable store: no disk write on the main thread.

Balls that call each other share a runtime: a Ball-port call reaches only Ball types in its own composition. Keep runtimes application-scoped, never per screen ([structure guide](structure.md)).

### Adapters with coroutines

From [`Adapter.kt`](../reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Adapter.kt):

```kotlin
public fun interface Adapter<Req, Res> {
    public fun execute(invocation: Invocation<Req>, done: (AdapterResult<Res>) -> Unit): Cancellable?
}
```

```kotlin
// Sketch: no reference code and no conformance claim.
class SuspendAdapter<Req, Res>(
    private val scope: CoroutineScope, // application-scoped
    private val send: suspend (Invocation<Req>) -> AdapterResult<Res>,
) : Adapter<Req, Res> {
    override fun execute(invocation: Invocation<Req>, done: (AdapterResult<Res>) -> Unit): Cancellable {
        val job = scope.launch(Dispatchers.IO) {
            val result = try {
                send(invocation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdapterResult.Failed(e.toString()) // it may have left: the runtime classifies it
            }
            done(result)
        }
        return Cancellable { job.cancel() }
    }
}
```

`send` follows the adapter checks of [Core Appendix D](../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative):

- it passes `invocation.idempotencyKey` to a destination that accepts one, and never makes one up (C5);
- it does not retry, so turn off the HTTP client's retry interceptors (C2);
- it returns `NotApplied` only with evidence, such as a connection refused before anything was written; an exception after the request may have been written is `Failed` (C3);
- it leaves timing to the binding's attempt timeout; a cancelled job reports nothing, and the runtime ends the attempt.

### State into Compose or views

```kotlin
// Sketch: no reference code and no conformance claim.
fun <S> Runtime.stateFlow(ball: Ball<S, *, *>, key: String): StateFlow<Snapshot<S>> {
    val flow = MutableStateFlow(read(ball, key))
    watch(ball, key) { flow.value = it }
    return flow.asStateFlow()
}
```

A typed `watch` delivers the current snapshot, then one per commit (`RuntimeTest`: `a typed watch receives the current snapshot, then one for each commit`). No call removes a watcher, so create one flow per instance at application scope. Each watched key keeps its listener for the life of the runtime, so watch a bounded set of keys, such as the current checkout, not one per row of a list; read the others when they render. The [honest UI guide](honest-ui.md) maps state to what a screen shows.

### A Room store

A `Store` has `load`, `save(record)`, `save(record, incarnation)`, `ids`, `delete` and `nextIncarnation` ([`Store.kt`](../reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Store.kt)). A Room store, sketched:

- One row per instance: `type`, `key`, `stateVersion` and a blob encoded by a `RecordCodec` such as `JavaSerializationCodec`. With type and key outside the blob, a record that no longer decodes can still be listed and deleted.
- Each `save` replaces one row in one transaction. `load` may run on any thread, because `Runtime.read` calls it.
- A one-row table holds the latest incarnation, and `nextIncarnation` increments it. `save(record, incarnation)` reads it in the same transaction and throws `FencedException` when the writer is older (R16). A check per row is not enough: a replaced runtime could still write a row the new one has not written yet.

### Kotlin Multiplatform

`Intent` and `CallRef` implement `java.io.Serializable`, so the kernel is JVM code, not a Multiplatform library. A shared core would export serialisable state and messages and leave runtime and adapters to each platform, as Crux's Rust core describes effects as data for a native shell to perform ([Crux documentation](https://redbadger.github.io/crux/), accessed 2026-10-27).

## 3. Swift and iOS

```swift
// Sketch: no reference code and no conformance claim.
protocol Ball {
    associatedtype State: Codable & Sendable
    associatedtype Request: Sendable
    associatedtype Reply: Sendable
    static func initial(key: String) -> State
    static func decide(_ state: State, _ request: Request, _ ctx: inout Context) -> Decision<State, Reply>
    static func complete(_ state: State, _ completion: Completion, _ ctx: inout Context) -> Step<State, Reply>
}
enum Outcome<Value> { case done(Value), notDone(NotDoneReason), unknown(UnknownReason) }
```

- Switch over `Outcome` without `default:`, so a missing `.unknown` is a compile error.
- Run steps in a `@MainActor` class for UI Balls, or an `actor` for Durable Balls. Keep each step synchronous, with no `await` between reading state and committing. Actor-isolated functions are reentrant: other work can run at every suspension point, and the proposal advises synchronous state updates ([SE-0306](https://github.com/apple/swift-evolution/blob/main/proposals/0306-actors.md), accessed 2026-10-27). If a commit must `await` a database, the runtime starts no other step of that instance until it is done (R3).
- Adapters are `async` functions in a `Task`, each reporting once. `task.cancel()` asks an adapter to stop; it is not evidence, so it never decides the outcome.
- Store: SQLite, one transaction per commit, fenced by incarnation as above. With files, an atomic rename is not enough: sync before dispatch ([Core §6](../spec/ballarch-core-2.0.md#6-profiles)).
- UI: copy snapshots from reads into an `@Observable` model on the main actor. The [Observation](https://developer.apple.com/documentation/observation) framework needs iOS 17 or later (accessed 2026-10-27); before that, use an `ObservableObject`.
- Not mapped here: background `URLSession` transfers.

## 4. Rust, sans-IO

In the sans-IO style a protocol is a state machine without I/O or clock reads; an event loop feeds it input and time and performs what it asks ([Firezone 2024](https://www.firezone.dev/blog/sans-io), accessed 2026-10-27).

```rust
// Sketch: no reference code and no conformance claim.
pub trait Ball {
    type State: Clone;
    type Request;
    type Reply;
    fn decide(s: &Self::State, r: &Self::Request, ctx: &mut Context) -> Decision<Self::State, Self::Reply>;
    fn complete(s: &Self::State, c: &Completion, ctx: &mut Context) -> Step<Self::State, Self::Reply>;
}
pub trait Driver { // driven by the host's event loop; nothing blocks
    fn handle_request(&mut self, now: Instant, to: BallId, msg: Message) -> RequestId;
    fn handle_report(&mut self, now: Instant, call: CallId, attempt: u32, report: Report);
    fn handle_timeout(&mut self, now: Instant);
    fn poll_commit(&mut self) -> Option<(u64, Record)>; // write durably, then ack_commit
    fn ack_commit(&mut self, seq: u64);
    fn poll_transmit(&mut self) -> Option<Transmit>; // attempts and cancels
    fn poll_timeout(&self) -> Option<Instant>;
}
```

- `poll_transmit` yields nothing for a call until `ack_commit` has covered its decision (R5) and, under Durable, its write-ahead record (R8).
- A taken attempt is a `#[must_use]` `Sending` value that only `handle_report`, or the deadline in `handle_timeout`, resolves. `#[must_use]` only warns, so a drop must decide nothing. In async Rust a dropped future is cancelled and its work can be lost ([Oxide RFD 400](https://rfd.shared.oxide.computer/rfd/400), accessed 2026-10-27): a dropped send is an attempt without a report, never `NotDone`.
- `Cancel` is an output from `poll_transmit`; the call still ends by the table.
- On `no_std` targets, use fixed-capacity collections such as [`heapless`](https://docs.rs/heapless/latest/heapless/) (accessed 2026-10-27), with the bounds in the types.
- Purity: deciders live in a crate that depends only on the kernel crate and builds as `#![no_std]`, so it cannot reach `std::time`, `std::fs` or `std::net`. Necessary, not sufficient: a `static` with interior mutability passes.

## 5. TypeScript

```ts
// Sketch: no reference code and no conformance claim.
type Outcome<V> = { kind: "done"; value: V } | { kind: "notDone"; reason: string }
  | { kind: "unknown"; reason: string };
function assertNever(x: never): never { throw new Error(`unhandled: ${JSON.stringify(x)}`); }
```

- **States** are discriminated unions; a `switch` ending in `default: return assertNever(x)` fails to compile when a case is missing.
- **Scheduling.** Queue inputs per instance and drain them from `queueMicrotask`, one step at a time per instance. A step never `await`s; if a commit awaits storage, the instance takes no other step until it resolves (R3).
- **Browser store.** One IndexedDB `readwrite` transaction per commit; dispatch after its `complete` event. Use `durability: "strict"` when the commit must reach persistent storage first ([MDN](https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/transaction), accessed 2026-10-27).
- **Server store.** Postgres, one transaction per commit. A one-row `owner` table holds the latest incarnation. Each commit runs `SELECT incarnation FROM owner FOR SHARE` and rolls back as fenced if the value is not its own. `nextIncarnation` is `UPDATE owner SET incarnation = incarnation + 1 RETURNING incarnation`, which waits for commits in progress (R16).
- **React.** `useSyncExternalStore(subscribe, getSnapshot)` over the runtime's read; return the same object until the revision changes.
- **Purity lint.** In `*.ball.ts`, forbid `fetch`, `Date`, `Math.random`, timers and `crypto` with ESLint's `no-restricted-globals` and `no-restricted-properties` ([ESLint](https://eslint.org/docs/latest/rules/no-restricted-globals), accessed 2026-10-27). Necessary, not sufficient.

An HTTP adapter sends `Idempotency-Key: inv.idempotencyKey` and bounds the attempt with `AbortSignal.timeout(ms)` or the binding's attempt timeout. The IETF draft gives 409 and 422 idempotency meanings, and its error responses carry a body or `Link` header that names the problem ([draft-ietf-httpapi-idempotency-key-header-08](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-08) §2.7, accessed 2026-10-27). Other servers, including one that follows the [structure guide](structure.md#http-status-codes), also use 409 and 422 for business refusals. Tell them apart by the body.

| Response | Report | Why |
|---|---|---|
| 2xx with a readable body | `Ok(value)` | the destination processed it |
| 409 whose body says a request with this key is in progress | `Failed` | retry advice, not evidence ([Appendix D](../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative), C6) |
| 422 whose body says the key was reused with a different request | `NotApplied`; log a defect | nothing was applied; one intent was named for two requests, or the body differs per attempt |
| any other 4xx the API documents as "nothing done", such as a 409 or 422 refusal from a server that follows the structure guide | `Refused(value)` | refused before any effect |
| 5xx, network error, abort, timeout | `Failed` | it may have been applied; `fetch` cannot tell whether it left |

Cloudflare Durable Objects already give the commit horizon: an object's output gate holds outgoing messages while a storage write is in progress, and discards them if it fails ([Varda 2021](https://blog.cloudflare.com/durable-objects-easy-fast-correct-choose-three/), accessed 2026-10-27). Effect classes, keys, the call table and the classification still need a runtime.

## 6. C firmware

The [C reference](../reference/c/README.md) runs one Ball instance per firmware task.

- A Ball type is a `ba_ball`: a port table of effect classes and a pure `step` function in its own translation unit, which includes only `ballarch.h`.
- The board supplies a `ba_platform`: `nvm_write` and `nvm_read` over two record slots, `transmit`, a 64-bit millisecond clock that never wraps, and an optional trace hook.
- The runtime commits state and call ledger as one CRC-checked record, and records `Sending` before each transmission.

It passes its call key as the idempotency key; keys repeat when the 32-bit key wraps or the NVM slots are erased. It has no Cancel, intents, deduplication windows, attempt timeouts or reconciled ports, and emits no conformance trace ([Core §9.4](../spec/ballarch-core-2.0.md#94-conformance-at-other-levels)). Drivers, interrupts and DMA are in [levels](levels.md).

## 7. Writing a runtime in a new language

1. **Start Transient.** Build parts 1–8 and 10. Add part 9 and the options once those pass.
2. **Simulate the destinations.** Bind every adapter port to a simulated destination that injects the fates of [Appendix E](../spec/ballarch-core-2.0.md#appendix-e-conformance-scenarios-normative) and records what really happened to each call. An Idempotent one deduplicates by the supplied key and remembers keys across a restart ([Producing a trace](../tools/TRACE-FORMAT.md#producing-a-trace)). The Kotlin testkit does this with `Harness`, `SimPort` and `TraceJson`.
3. **Explore.** Of S1–S8, run each scenario that names a profile under that profile if you claim it, and S3 and S8, which name none, under each profile you claim; run S9–S12 for the options you offer, as Appendix E states them. Write each run as a trace. Write B1–B5 as deterministic tests.
4. **Check the traces.** Explored traces must pass; seeded violations must fail ([Checking a trace](../tools/TRACE-FORMAT.md#checking-a-trace)):

   ```sh
   python3 tools/oracles.py path/to/traces/*.jsonl
   python3 tools/oracles.py --expect-violations path/to/seeded/*.jsonl
   ```

5. **Cover what traces do not.** Traces do not show atomic commit (R4), bounds (R11), profile claims (R13), exclusive ownership (R16), reads or idempotency keys ([Core §9.6](../spec/ballarch-core-2.0.md#96-conformance-trace)). Crash exploration shows R4, the boundary tests the bounds, S11 ownership and S4b the keys. Profile claims and reads need their own tests or review.
6. **Map the lifecycle** to the transitions of the [Alloy model](../formal/alloy/README.md): commit, markSending, send, retry, receive, timeout, crash and recover (§9.1, item 3).

This is evidence for the runs explored, not a proof. A claim names the profile, the runtime, the scenario and the evidence ([Core §9.5](../spec/ballarch-core-2.0.md#95-claims)).
