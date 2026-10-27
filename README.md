<h1 align="center">Ballarch</h1>

<p align="center">
  <a href="spec/ballarch-core-2.0.md">
    <img src="assets/ballarch-hero.svg" alt="Ballarch: owners, obligations, honest outcomes" width="100%" />
  </a>
</p>

<p align="center">
  <strong>Owners, obligations and honest outcomes: one architecture from registers to services.</strong>
</p>

<p align="center">
  <a href="docs/tutorial.md">Tutorial</a> ·
  <a href="docs/structure.md">Structure</a> ·
  <a href="docs/compared-with-clean.md">Compared with Clean</a> ·
  <a href="docs/levels.md">Levels</a> ·
  <a href="docs/ai-agents.md">Agents</a> ·
  <a href="spec/ballarch-core-2.0.md">Specification</a> ·
  <a href="docs/ru/README.md">По-русски</a>
</p>

**Ballarch gives every effect one owner and every call one honest outcome, and it moves retries, idempotency keys, crash recovery and reconciliation out of features into one checked runtime rule.** You pay per effect, not per layer.

1. Clean Architecture says where code may depend. Ballarch says what code may claim happened.
2. Every external call ends `Done`, `NotDone` (with evidence) or `Unknown`. A timeout after sending an effectful call is `Unknown` unless a status read settles it, never "failed": the order may exist, and the code has to say so.
3. The runtime handles retries, keys, recovery and reconciliation once, from each port's declared effect class; screens, use cases and handlers do not.
4. No effects means no Ballarch. One effect means one file. Many effects produce one composable knowledge value. The same outcome and knowledge rules apply from a register block to a backend to an AI agent's tool calls.

## A timeout after a charge

1. The user taps **Pay**, and the app sends `POST /orders`.
2. The server creates the order and charges the card.
3. The response is lost, or arrives after the client's timeout.
4. The client throws, the repository returns `Failure`, and the screen says "Payment failed".
5. The user taps **Pay** again. With no idempotency key, or a new one per request, the server creates a second order.
6. Every layer did its job. The code claimed a fact it did not have.

## One Ball

The example keeps ports, stages and a pure decider in one file, and the binding in an `app` sub-package, outside the decider code ([layout](docs/structure.md#layout)).

From [`PlaceOrder.kt`](reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):

```kotlin
/** `POST /orders` with the `Idempotency-Key` header the runtime supplies. */
val Orders = AdapterPort<Cart, Order>("orders.place", EffectClass.Idempotent)

/** `GET /orders?key=…`: the order placed with that idempotency key, or `null`. */
val OrderLookup = AdapterPort<String, Order?>("orders.lookup", EffectClass.Safe)

// ---- the request and the stages, including the one in which nobody can know yet

data class Place(val cart: Cart)

sealed interface PlaceOrderState {
    data object Idle : PlaceOrderState

    /** [repeat]: an earlier call with this [intent] ended `Unknown`. */
    data class Placing(val cart: Cart, val intent: Intent, val call: CallRef<Order>, val repeat: Boolean) : PlaceOrderState

    data class Placed(val orderId: String) : PlaceOrderState

    data class NotPlaced(val reason: String) : PlaceOrderState

    /** The server may or may not have placed the order. Placing it again repeats it with the same [intent]. */
    data class Checking(val cart: Cart, val intent: Intent) : PlaceOrderState
}

// ---- the Ball: it answers Place with the stage the order is in

object PlaceOrder : Ball<PlaceOrderState, Place, PlaceOrderState> {
    override val type = "place-order"

    override fun initial(key: String): PlaceOrderState = Idle

    override fun decide(state: PlaceOrderState, request: Place, ctx: RequestContext): Decision<PlaceOrderState, PlaceOrderState> =
        when (state) {
            Idle, is NotPlaced -> place(request.cart, ctx.newIntent(), repeat = false, ctx)
            // "Try again" after Unknown: the same order with the same intent, so the server places it at most once.
            is Checking -> if (request.cart == state.cart) place(state.cart, state.intent, repeat = true, ctx) else reject(state)
            // A double tap joins the order in flight.
            is Placing -> if (request.cart == state.cart) accept(state, state) else reject(state)
            is Placed -> accept(state, state)
        }

    private fun place(cart: Cart, intent: Intent, repeat: Boolean, ctx: Context): Decision<PlaceOrderState, PlaceOrderState> {
        val (call, send) = ctx.call(Orders, cart, timeoutMillis = 60_000, intent = intent)
        val placing = Placing(cart, intent, call, repeat)
        return accept(placing, placing, send)
    }

    override fun complete(state: PlaceOrderState, completion: Completion<*>, ctx: Context): Step<PlaceOrderState, PlaceOrderState> {
        if (state !is Placing) return step(state)
        return when (val outcome = completion.outcomeFor(state.call)) {
            is Outcome.Done -> step(Placed(outcome.value.id))
            // After an Unknown, NotDone is about this call only: the earlier one may have placed the order.
            is Outcome.NotDone -> step(if (state.repeat) Checking(state.cart, state.intent) else NotPlaced("${outcome.reason}"))
            is Outcome.Unknown -> step(Checking(state.cart, state.intent))
            null -> step(state) // a completion of another call
        }
    }
}
```

The binding, from [`PlaceOrderApp.kt`](reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/app/PlaceOrderApp.kt):

```kotlin
fun Composition.placeOrder(orders: Adapter<Cart, Order>, lookup: Adapter<String, Order?>): Composition =
    ball(PlaceOrder)
        .adapter(
            Orders, orders,
            RetryPolicy(maxAttempts = 3, backoffMillis = 1_000, attemptTimeoutMillis = 10_000),
            // The server keeps a key for 24 hours, less the time a request may still be in transit.
            dedupWindowMillis = 24 * 60 * 60 * 1_000L - ORDERS_HORIZON_MILLIS,
            reconcile = Reconciliation(
                status = OrderLookup,
                horizonMillis = ORDERS_HORIZON_MILLIS,
                statusTimeoutMillis = 5_000,
                query = { it.idempotencyKey },
                verdict = { order -> if (order != null) StatusVerdict.Applied(order) else StatusVerdict.NotApplied },
            ),
        )
        .adapter(OrderLookup, lookup)
```

A screen or handler sends `Place(cart)` with `runtime.request` and shows the stage from `runtime.read(PlaceOrder, key)` or `watch`, which commit nothing ([structure](docs/structure.md#reads)).

### What the runtime does for you

- **Classification.** A call that never left ends `NotDone`; one that may have left without an answer ends `Unknown` ([Core §3.2](spec/ballarch-core-2.0.md#32-call-lifecycle)).
- **Keys.** All attempts of a call carry one idempotency key, and calls naming one intent share it, so "Try again" cannot place a second order while the server remembers the key.
- **Retries.** Three attempts; one silent for 10 s is retried, and its late answer still counts. The composition refuses retries on a `NonIdempotent` port.
- **Window.** A call that could end after the server forgets its key is refused before sending ([Core §3.5](spec/ballarch-core-2.0.md#35-bounds-and-preflight)).
- **Recovery.** Under Durable, a crash loses no call; one possibly sent is re-sent with its key while attempts remain.
- **Reconciliation.** With no answer and no attempts left, the runtime waits out the 30 s delivery horizon and looks the order up by key; if that settles nothing, the call ends `Unknown` and the stage is `Checking`.

## Where code goes vs what code knows

| Style | Settles | Ballarch keeps | Ballarch adds | Ballarch costs |
|---|---|---|---|---|
| Clean Architecture | where code may depend | adapters at the edge; the read path | effect classes, three outcomes, one runtime for retries, keys and recovery | a runtime; a named stage per step |
| Hexagonal | the inside owns its ports | ports and adapters | the application declares each port's class; adapters never retry | adapters must report evidence honestly |
| MVI, Elm, TCA | a pure reducer; effects as values | the pure decider | commit before any effect; one typed completion per call | serialisable state under Durable |
| Actors | one owner, one message at a time | the single owner | atomic commit of state and outputs; one completion per call | a commit per step |
| Durable-execution engines | progress survives a crash | durable progress and timers | outcomes by port class; recovery without re-running code; knowledge | explicit stages; you host the runtime and store, without an operations UI or worker scaling |

Careful code in any of these styles can handle the failure above, per feature or in a team helper that every feature must remember to use. Ballarch puts it in one runtime with a written contract and conformance tests, and makes `Unknown` a type. [Compared with Clean](docs/compared-with-clean.md) builds one feature both ways.

## The effect ladder

- **Rung 0.** No call on an `Idempotent` or `NonIdempotent` port: plain code.
- **Rung 1.** One effect, possibly repeated, such as a submit: one Ball in one file. Five concepts: Ball and decider, port and effect class, three outcomes, intent, composition root.
- **Rung 2.** Several effectful calls, such as reserve and charge: a *Flow*, a Ball whose state is the workflow, claiming a knowledge value ([Core §4.2](spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations)) under the Durable profile.

**Not a Ball** ([Core §1](spec/ballarch-core-2.0.md#1-scope)): screens and ViewModels, repositories, use cases, caches and paging, mappers, stateless services, navigation, `Safe` reads, and a write that is one transaction in the database holding its state. When one of them needs an effectful call, that call moves into a Ball.

## Five guarantees

A conforming runtime with conforming adapters gives 2–4 by construction for its claimed profile, if declared effect classes and delivery horizons hold. 1 and 5 also need the [author rules](spec/ballarch-core-2.0.md#51-author-rules).

1. **One owner.** Each piece of governed state has one owner, which changes it only by committing its decisions.
2. **Nothing leaves early.** No effect is requested before its decision commits.
3. **One honest outcome.** Every call ends exactly once, never claiming nothing happened when something may have.
4. **No split decisions.** A crash keeps a decision's state and requests together, or neither.
5. **No overclaiming.** A multi-call claim never exceeds the outcomes; a settled composite that meets [Core §7.4](spec/ballarch-core-2.0.md#74-composition-closure) answers like one call.

## Where it applies

- **Mobile:** submits, payments and offline queues; the screen never shows `Unknown` as failed. [Structure](docs/structure.md), [honest UI](docs/honest-ui.md).
- **Backend:** a Ball per aggregate key, owned by one runtime at a time; across servers, partition keys yourself and use a store that fences writes (none in the reference). Flows replace hand-written sagas. [Structure](docs/structure.md).
- **Firmware and drivers:** the send horizon is the transmit or doorbell; a reset after sending leaves the call `Unknown`. [Levels](docs/levels.md), [C reference](reference/c/).
- **RTL:** `always_comb` decides, the clock edge commits, `valid && ready` sends. [Levels](docs/levels.md), [RTL reference](reference/rtl/).
- **AI agents:** tools are ports with declared classes; the model proposes, deterministic code makes the calls, and the report's status comes from their outcomes, not from the model. [Agents](docs/ai-agents.md).

The Kotlin reference is JVM-only and unpublished, so build it from source; it has no Android module, mobile store or device test. [Bindings](docs/bindings.md) maps it onto Android and sketches Swift, Rust and TypeScript, without a conformance claim.

## What is new and what is not

Lineage: Helland's entities ([Helland 2007](https://www.cidrdb.org/cidr2007/papers/cidr07p15.pdf), accessed 2026-10-27) with an executable outcome contract, plus an algebra for what a multi-step operation may claim. As far as the author could establish, four things are new ([Core §12](spec/ballarch-core-2.0.md#12-lineage-and-what-is-new-informative)):

1. **Composite knowledge** (the lead claim): an order-independent (all, any) value in Kleene's three-valued logic that counts only absorbing compensations as undoing, nests, and is returned as the settled reply. The structure is classical; the application is new.
2. **One outcome rule for every port**, enforced by the runtime: a new combination of an existing rule.
3. **Keys, windows and reconciliation in the runtime**, a modest combination.
4. **The same outcome and knowledge rules at three levels**, a narrow combination: structural rules differ, and levels are not composed.

Not new: single owners, pure deciders, commit before dispatch, three outcomes, and adapters at the edge.

## Evidence you can run

Figures are asserted by the named test unless marked printed. [Appendix F](spec/ballarch-core-2.0.md#appendix-f-checks-and-evidence-in-the-reference-implementations-informative) lists every check.

| Check | Result |
|---|---|
| [`PlaceOrderTest`](reference/kotlin/examples/place-order/src/test/kotlin/ballarch/examples/placeorder/PlaceOrderTest.kt) | Under lost requests, lost answers, refused connections and a crash, no order is placed twice, and `NotPlaced` only when none was. After three lost answers, "Try again" sends a fourth attempt with the same key and one order is placed; a week later it is refused and nothing is sent. |
| [`KnowledgeTest`](reference/kotlin/ballarch-kernel/src/test/kotlin/ballarch/kernel/KnowledgeTest.kt), [`test_knowledge.c`](reference/c/test/test_knowledge.c) | The algebra is sound and coordinate-wise exact on 69,905 worlds (Kotlin, C) and 127,551 with two compensation attempts (Kotlin). |
| [`TripFlowTest`](reference/kotlin/examples/trip/src/test/kotlin/ballarch/examples/trip/TripFlowTest.kt) | 6,438 runs under every fate and crash point: no claim contradicts the bookings; a two-valued status is wrong in 1,190. |
| [`ItineraryFlowTest`](reference/kotlin/examples/itinerary/src/test/kotlin/ballarch/examples/itinerary/ItineraryFlowTest.kt) | A Flow of two Flows: no false claim in 130,321 runs without a crash, or in 29,774 with faults and a crash point (28,933 of them with a crash). |
| [`ExplorationTest`](reference/kotlin/ballarch-runtime/src/test/kotlin/ballarch/runtime/ExplorationTest.kt) and siblings | Every scenario and boundary case of Core Appendix E; explored runs are checked against ground truth. |
| [`RuntimeTest`](reference/kotlin/ballarch-runtime/src/test/kotlin/ballarch/runtime/RuntimeTest.kt) | An effectful call answered `Done` costs three durable writes; reads cost none. |
| [Firmware](reference/c/) | 937,229 and 982,599 runs of two controllers under power loss, NVM faults and link faults: no violation (default bounds). |
| [RTL](reference/rtl/) | Every bench check passes (8,764 scenarios per parameter set and 1,458, printed by `tb_issuer.sv` and `tb_composite.sv`). |
| [Alloy](formal/alloy/) | P1–P6 and L1 hold within bounds; six seeded bugs each yield a counterexample. |

With JDK 21, Python 3.8 or later, gcc or clang, Icarus Verilog, Verilator and Yosys:

```sh
(cd reference/kotlin && ./gradlew build checkModuleBoundaries)
python3 tools/oracles.py reference/kotlin/ballarch-runtime/build/conformance-traces/*.jsonl
python3 tools/oracles.py --expect-violations reference/kotlin/ballarch-runtime/build/conformance-traces/seeded/*.jsonl
make -C reference/c
make -C reference/rtl
bash formal/alloy/fetch-alloy.sh && python3 formal/alloy/run.py
```

Each level's checks must also catch seeded bugs. CI runs all of these except the seeded-trace command; the Kotlin build checks those traces with `tools/oracles.py` (`TraceConformanceTest`).

## Costs and limits

From [Core §10](spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations):

- **Writes.** Under Durable, an effectful call takes three durable writes, plus one per retry and one on entering reconciliation.
- **One writer per key:** a hot key is a queue.
- **Explicit stages,** serialisable under Durable, and a runtime and store to host.
- **Not verified:** declared effect classes, windows and delivery horizons; decider termination, adapter honesty beyond the adapter checks, absorbing compensations, ownership boundaries, and calls between levels.
- **Cross-Ball invariants** need a coordinating Ball, a reservation or a database constraint.

It does not fit stateless code, CRUD in one transaction, consensus, or loops that cannot afford a commit per input. It makes no measured claim about productivity, code size or agent success. See the [decision guide](docs/decision-guide.md).

## Agent skill

[`skills/ballarch/`](skills/ballarch/) is an agent skill for code that changes state through calls that can fail or apply twice. It checks fit, makes the agent declare port classes and handle all three outcomes, and forbids retry loops, invented keys and timeouts reported as failures. Only its Kotlin and C templates have reference code. Copy the directory with its `LICENSE` and `NOTICE.md` into your agent's skills directory as `ballarch` ([Agent Skills format](https://agentskills.io/specification), accessed 2026-10-27).

**Not yet evaluated:** no result shows that it helps.

## Repository map

| Path | Contents |
|---|---|
| [`spec/`](spec/ballarch-core-2.0.md) | The normative specification |
| [`reference/kotlin/`](reference/kotlin/README.md) | Kernel, runtime, test kit, architecture rules, examples |
| [`reference/c/`](reference/c/) | Firmware runtime (C11) |
| [`reference/rtl/`](reference/rtl/) | Hardware Balls (SystemVerilog) |
| [`formal/alloy/`](formal/alloy/) | Alloy model of the call protocol |
| [`tools/`](tools/) | [Trace format](tools/TRACE-FORMAT.md) and checker |
| [`docs/`](docs/) | Guides |
| [`docs/ru/`](docs/ru/README.md) | Russian reading edition (non-normative) |
| [`skills/ballarch/`](skills/ballarch/) | The agent skill |

Ballarch was called Pokeball up to Core 1.5 ([migration](docs/migration-from-1.5.md)). See [CONTRIBUTING.md](CONTRIBUTING.md) and [CITATION.cff](CITATION.cff).

## License and authorship

Copyright © 2026 **Vladislav Tomilov (4wl2d)**.

Text and diagrams are licensed under [CC BY 4.0](LICENSE); see [NOTICE.md](NOTICE.md). **The code has no software license yet.** A software license remains the copyright holder's decision. Ballarch is not affiliated with Nintendo; the project's former name is not used as a trademark.

Ballarch Architecture by Vladislav Tomilov (4wl2d).
