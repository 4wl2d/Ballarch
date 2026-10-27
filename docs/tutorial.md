# Tutorial: place an order once

This tutorial builds one Ball: the "Place order" button of a shop app. The orders server deduplicates the `Idempotency-Key` header for 24 hours and can find an order by that key. The Ball must never place an order twice, and never show "not placed" while the order may exist.

The code is [`examples/place-order`](../reference/kotlin/examples/place-order/). For contrast, [`examples/tutorial`](../reference/kotlin/examples/tutorial/) sends one email through a mail service with no idempotency key, and the [shop](../reference/kotlin/examples/shop/) shows a checkout's status on a screen. Each code block is copied from the file named above it. Run the tests with JDK 21:

```sh
cd reference/kotlin
./gradlew :examples:place-order:test :examples:tutorial:test
```

## 1. Is it a Ball?

Ballarch applies where code requests an effect whose outcome can be uncertain: a call on an Idempotent or NonIdempotent port, including a call to another Ball ([Core §1](../spec/ballarch-core-2.0.md#1-scope)). Screens, navigation, caches, Safe reads, stateless code and a write whose only effect is one transaction in its own database stay plain code. Placing an order changes the server, and a timeout can come after the server acted, so it needs a Ball. Editing the cart does not. See the [decision guide](decision-guide.md) and [Structuring an application](structure.md).

## 2. Name the effect, its class and its window

A port is a typed contract with an effect class. The class is a fact about the destination, declared by the application ([Core §2.6](../spec/ballarch-core-2.0.md#26-declaring-effect-classes)). If you do not know it, declare NonIdempotent.

From [`PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):
```kotlin
// ---- the ports: the orders server deduplicates an idempotency key for 24 hours, and finds an order by its key

data class Cart(val items: List<String>)

data class Order(val id: String)

/** `POST /orders` with the `Idempotency-Key` header the runtime supplies. */
val Orders = AdapterPort<Cart, Order>("orders.place", EffectClass.Idempotent)

/** `GET /orders?key=…`: the order placed with that idempotency key, or `null`. */
val OrderLookup = AdapterPort<String, Order?>("orders.lookup", EffectClass.Safe)
```

The server deduplicates by key, so `Orders` is Idempotent. The lookup changes nothing, so it is Safe. The reminder's mail service has no key, so its port is NonIdempotent:

From [`Reminder.kt`](../reference/kotlin/examples/tutorial/src/main/kotlin/ballarch/examples/tutorial/Reminder.kt):
```kotlin
data class Email(val to: String, val text: String)

val Mailer = AdapterPort<Email, Unit>("mailer", EffectClass.NonIdempotent)
```

| Class | The runtime may retry | After `Unknown`, the Ball may |
|---|---|---|
| Safe | yes | nothing: a Safe call never ends `Unknown` |
| Idempotent | yes, with one key | repeat the effect with the same intent |
| NonIdempotent | never | not repeat it without evidence that nothing happened (R15) |

The *deduplication window* is how long the server remembers a key, less the time a request can stay in transit: here 24 hours less 30 seconds. Step 7 declares it.

## 3. Model the state, including what you cannot know

Each stage is a case of a sealed interface. `Checking` is not an error: it says that the server may or may not have placed the order.

From [`PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):
```kotlin
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
```

`Placing` keeps two identities. The `CallRef` names the call whose completion matters. The `Intent` names the effect, so that a later call can repeat it with the same key. Both are serialisable, as Durable state must be.

## 4. Decide with `ctx.call` and `newIntent`

`decide` is a pure function of the state, the request and the context ([R2](../spec/ballarch-core-2.0.md#51-author-rules)). It reads no clock, makes no key and does no I/O.

From [`PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):
```kotlin
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
```

- `ctx.newIntent()` issues an intent stamped with this decision's time, from which the window counts.
- `ctx.call(...)` returns a `CallRef` to keep in state and a `Call` to return as an output.
- A second tap with the same cart joins the order in flight: same state, no output.
- In `Checking`, the same cart repeats the call with the earlier intent, so the server sees the earlier key.
- A different cart is rejected: an intent never names a different effect ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)).

The runtime commits the state and the call in one write, and sends the call only after it ([R4, R5](../spec/ballarch-core-2.0.md#52-runtime-rules)). A timer is a call too: `val (timer, wait) = Timer.after(ctx, request.delayMillis)` in the reminder.

## 5. Complete with `outcomeFor`

Every committed call ends in exactly one completion: `Done`, `NotDone` with evidence, or `Unknown` ([Core §2.2](../spec/ballarch-core-2.0.md#22-outcomes)). The runtime picks it from the port's class and the adapter's report.

From [`PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):
```kotlin
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
```

`outcomeFor(state.call)` returns the outcome typed by the port, or `null` for a completion of another call. The `when` is exhaustive, so the compiler makes you handle `Unknown`.

The `repeat` flag matters: after an `Unknown`, a `NotDone` covers only the latest call, and an earlier one may have placed the order. So the Ball returns to `Checking`.

A NonIdempotent port has no intent to repeat. The reminder records `MaybeSent` and stops:

From [`Reminder.kt`](../reference/kotlin/examples/tutorial/src/main/kotlin/ballarch/examples/tutorial/Reminder.kt):
```kotlin
            // The one completion of the send, typed by the port.
            state is ReminderState.Sending ->
                when (val outcome = completion.outcomeFor(state.call)) {
                    is Outcome.Done -> step(ReminderState.Sent)
                    is Outcome.NotDone -> step(ReminderState.NotSent(outcome.reason.toString()))
                    is Outcome.Unknown -> step(ReminderState.MaybeSent)
                    null -> step(state) // a completion of another call
                }
```

Sending again could send the email twice, which R15 forbids without evidence that the first was not sent ([Core §5.1](../spec/ballarch-core-2.0.md#51-author-rules)). On a Safe port, `completion.safeOutcomeFor(ref)` returns a `SafeOutcome` with no `Unknown` case ([`CatalogSearch.kt`](../reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/CatalogSearch.kt)).

## 6. Reads and honest UI

A screen reads the last committed state; it does not send a request ([R12](../spec/ballarch-core-2.0.md#51-author-rules)). `runtime.read(ball, key)` returns a `Snapshot(id, revision, state)` on any thread, commits nothing and never waits behind a step. `runtime.watch(ball, key) { snapshot -> ... }` delivers the current snapshot, then one per commit ([`CatalogApp.kt`](../reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/app/CatalogApp.kt)).

From [`ShopApp.kt`](../reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt):
```kotlin
/**
 * What a screen shows for the checkout of [orderId]. It reads the last
 * committed state, so it commits nothing and does not wait behind the Flow's
 * inbox (R12): a screen may call it every time it renders.
 */
fun checkoutStatus(runtime: Runtime, orderId: String): CheckoutReply.Status =
    CheckoutFlow.status(runtime.read(Checkout, orderId).state)
```

Place-order ships no status function. A screen maps each stage to one of five statuses with a pure function, such as the [honest UI sketch](honest-ui.md#five-statuses):

| Stage | Status | The screen says | Primary action |
|---|---|---|---|
| `Idle` | Idle | nothing yet | Place order |
| `Placing` | Pending | "Placing your order…" | none; a second tap joins |
| `Placed` | Done | "Order placed" | none |
| `NotPlaced` | NotDone | "Not placed", with the reason | Place order |
| `Checking` | Unknown | "We could not confirm your order. It may have been placed.", never "failed" | Try again, which repeats the intent |

"Try again" is safe only because the port is Idempotent and the Ball repeats the intent. For the reminder's `MaybeSent`, a screen says the email may have been sent and offers no resend. The reminder's `Status` request is for other Balls: a request is decided and committed, a read is not. See [Honest UI](honest-ui.md).

## 7. Compose

The composition root binds each port to an adapter and says how the runtime treats it. `ORDERS_HORIZON_MILLIS` is 30 seconds: a request reaches the server within that time of its send, or never.

From [`PlaceOrderApp.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/app/PlaceOrderApp.kt):
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

- **Retries.** Up to three attempts, with a one-second backoff, all with one idempotency key. An attempt with no report after 10 seconds ends without an answer and is retried while attempts and time remain.
- **Deduplication window.** Before committing, the runtime refuses a call whose deadline falls later than the window after its intent was issued ([Core §3.5](../spec/ballarch-core-2.0.md#35-bounds-and-preflight)). A "Try again" a week later gets `NotDone(Fault)`, and nothing is sent. The runtime records it as a fault, so treat the window as a guard and hide the button in time ([honest UI](honest-ui.md#what-to-show)).
- **Reconciliation.** A call that no attempt answered does not end `Unknown` at once. Once the horizon has passed since the last send, the runtime asks the lookup port once: an order found ends the call `Done`, none found `NotDone(NotApplied)`, no answer `Unknown`. The call's timeout must exceed the horizon plus the status timeout.

Worked out from the binding (no test asserts these times; `ReconciliationTest` asserts the same rule with smaller values): if nothing ever answers, attempts start at 0, 11 and 22 seconds; the last ends at 25, the deadline less 35 seconds; the lookup is sent at 52; and the call resolves by 57.

Building the composition refuses retries or an attempt timeout on a NonIdempotent port, a window on a port that is not Idempotent, and reconciliation on a Safe port. The reminder's composition root, [`ReminderApp.kt`](../reference/kotlin/examples/tutorial/src/main/kotlin/ballarch/examples/tutorial/app/ReminderApp.kt), binds its mailer with no options: `.adapter(Mailer, mailer)`. The shop reconciles its NonIdempotent charge port ([`PaymentsApp.kt`](../reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/PaymentsApp.kt)).

Your `Orders` adapter reports each attempt once, never retries, sends `invocation.idempotencyKey` as the `Idempotency-Key` header, and reports `NotApplied` only with evidence ([Core Appendix D](../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative)). `AdapterConformance` in `ballarch-testkit` checks this against a simulated destination.

To run it, call `Runtime(composition, scheduler, store).start()`. `ThreadScheduler()` steps on a loop thread of its own; `InlineScheduler(post = ...)` steps on the thread that constructs it, such as a UI thread, and posts inputs from other threads to it. `MemoryStore()` keeps nothing past the process (Transient); `FileStore(directory)` is Durable, so keep its writes off a UI thread. See [Bindings](bindings.md).

A screen or handler sends a request and gets its outcome in a callback on the runtime's loop.

From [`InlineSchedulerTest.kt`](../reference/kotlin/ballarch-runtime/src/test/kotlin/ballarch/runtime/InlineSchedulerTest.kt):
```kotlin
        var outcome: Outcome<Any?>? = null
        runtime.request<CounterMsg, Any?>(BallId("counter", "c"), CounterMsg.Increment, 1_000) { outcome = it }
        assertEquals(Outcome.Done(CounterReply.Value(1)), outcome)
```

For place-order the call is `runtime.request<Place, PlaceOrderState>(BallId("place-order", key), Place(cart), timeoutMillis) { outcome -> … }`, and the screen shows the stage from `read` or `watch`.

## 8. Test the decider as a function

A decider is a function, so a table test needs no runtime and no mocks. `FixedContext` supplies time, keys and intents.

From [`PlaceOrderTest.kt`](../reference/kotlin/examples/place-order/src/test/kotlin/ballarch/examples/placeorder/PlaceOrderTest.kt):
```kotlin
    @Test
    fun `complete shows NotPlaced only when no earlier call of the order may have placed it`() {
        val order = Order("o-1")
        val refused = Outcome.NotDone(NotDoneReason.NotApplied("connection refused"))
        // whether an earlier call of the order ended Unknown, the outcome -> the next stage
        val rows: List<Triple<Boolean, Outcome<Order>, PlaceOrderState>> = listOf(
            Triple(false, Outcome.Done(order), Placed("o-1")),
            Triple(false, refused, NotPlaced("NotApplied(evidence=connection refused)")),
            Triple(false, Outcome.Unknown(UnknownReason.RetriesExhausted), Checking(cart, intent)),
            Triple(true, Outcome.Done(order), Placed("o-1")),
            Triple(true, refused, Checking(cart, intent)),
            Triple(true, Outcome.Unknown(UnknownReason.LostAfterSend), Checking(cart, intent)),
        )
        for ((repeat, outcome, expected) in rows) {
            val step = PlaceOrder.complete(Placing(cart, intent, call, repeat), Completion(call.key, Orders, outcome), ctx)
            assertEquals(expected, step.state, "repeat=$repeat, $outcome")
            assertEquals(emptyList(), step.outputs)
        }
        val placing = Placing(cart, intent, call, repeat = false)
        assertEquals(placing, PlaceOrder.complete(placing, Completion(CallKey("1.0"), Orders, Outcome.Done(order)), ctx).state)
    }
```

Check purity in the build too. `BallarchRules.deciderPurity` in `ballarch-archrules` rejects I/O, concurrency, reflection, the runtime, clocks and randomness in every class of a package that contains a Ball. Place-order and the tutorial keep their bindings in an `app` sub-package, which holds no Ball, and `BallarchRulesTest` runs every rule on both examples. Keep your bindings out of packages that hold a Ball too.

## 9. Test under every failure

The test kit runs the real runtime on a simulated clock. A `SimPort` draws a fate for each attempt (an answer, a lost request, a lost answer after the order was placed, a refused connection) and records what the server did. `ExhaustiveChooser(maxChoices = 6)` tries every sequence of a run's first six choices, and `maybeCrashOnce` adds a crash before one of the first 16 scheduler tasks, or none.

From [`PlaceOrderTest.kt`](../reference/kotlin/examples/place-order/src/test/kotlin/ballarch/examples/placeorder/PlaceOrderTest.kt):
```kotlin
    @Test
    fun `under every fault and crash point no order is placed twice, and no Unknown is shown as NotPlaced`() {
        val finals = sortedMapOf<String, Int>()
        val runs = explore(ExhaustiveChooser(maxChoices = 6)) { chooser ->
            val h = Harness(chooser, Profile.Durable) {
                val orders = SimPort(Orders, scheduler, chooser, truth, FATES, horizonMillis = ORDERS_HORIZON_MILLIS) { Order("o-1") }
                Composition().placeOrder(orders, orders.status { it })
            }
            h.maybeCrashOnce(window = 16)
            h.start()
            h.send(id, Place(cart))
            h.runUntilIdle()
            if (h.runtime.inspect(id) is Checking) { // the user taps "Try again"
                h.send(id, Place(cart))
                h.runUntilIdle()
            }
            h.assertInvariants()
            val placed = h.truth.applied.values.sum()
            val state = h.runtime.inspect(id)
            when (state) {
                is Placed -> assertEquals(1, placed, "choices=${chooser.history}")
                is NotPlaced -> assertEquals(0, placed, "choices=${chooser.history}")
                is Checking, Idle -> assertTrue(placed <= 1, "choices=${chooser.history}")
                else -> fail("unfinished: $state; choices=${chooser.history}")
            }
            finals.merge(state::class.simpleName!!, 1, Int::plus)
        }
        println("place order (Idempotent, Durable, 3 attempts, reconciled, crash window 16, one try again): $runs runs, stages $finals")
        assertTrue(finals.keys.containsAll(setOf("Placed", "NotPlaced", "Checking")), "$finals")
    }
```

`assertInvariants` checks the runtime's rules on the trace against the ground truth: serial steps, commit before send, one completion per call, honest outcomes, no blind re-send, one reply per request. The `when` checks your rule: the stage never contradicts the server.

Try breaking it. In `complete`, replace the `NotDone` line with `is Outcome.NotDone -> step(NotPlaced("${outcome.reason}"))`. The decider table fails on its `repeat` row. The exploration finds a run that ends `NotPlaced` with one order placed (`expected: <0> but was: <1>`): three attempts got no answer, the third after the order was placed, the lookup was lost, and "Try again" met a refused connection.

`Harness.writeTrace(path)` writes a run as a conformance trace ([Core §9.6](../spec/ballarch-core-2.0.md#96-conformance-trace)) that `tools/oracles.py` checks without the JVM. The runtime's explorations write traces during the build; from the repository root:

```sh
python3 tools/oracles.py reference/kotlin/ballarch-runtime/build/conformance-traces/*.jsonl
```

## 10. Where next

- [Structuring an application](structure.md): where Balls, screens, adapters and the composition root go.
- [Honest UI](honest-ui.md), [Bindings](bindings.md) and [Compared with Clean Architecture](compared-with-clean.md).
- [Decision guide](decision-guide.md) and [anti-patterns](anti-patterns.md): when Ballarch is worth its cost, and what to avoid.
- The [shop](../reference/kotlin/examples/shop/), a checkout Flow over three Balls, and the [trip](../reference/kotlin/examples/trip/), which reports only what the knowledge algebra establishes ([Core §4.2](../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations)).
- The [Kotlin reference](../reference/kotlin/README.md): modules, commands and what the tests check.
- The [specification](../spec/ballarch-core-2.0.md): the model, rules R1–R16 and conformance.
