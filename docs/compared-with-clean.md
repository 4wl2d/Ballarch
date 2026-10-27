# Ballarch compared with Clean Architecture

Clean Architecture constrains dependencies: "source code dependencies can only point inwards" ([Martin 2012](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html), accessed 2026-10-27). Ballarch constrains claims about effects: what code may say happened after it called something that can fail or take effect twice. The two questions are independent, so the two approaches combine. Ballarch keeps adapters at the edge and one composition root, and a team can keep its layers around a Ball.

This guide works through one feature in both styles. It makes no measured comparison. The failure rows are illustrative, and the [specification](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations) makes no claim about productivity, code size or defect rates.

## The feature

An Android app places an order. The user fills a cart and taps "Place order". The app calls `POST /orders` on a server that accepts an `Idempotency-Key` header and deduplicates a key for 24 hours. It also offers `GET /orders?key=…`, which returns the order placed with that key, if any.

Real APIs keep keys for a limited time. As of 2026-10-27, Stripe's API v1 may remove a key once it is at least 24 hours old, and a request that reuses a removed key is processed as a new request ([Stripe, idempotent requests](https://docs.stripe.com/api/idempotent_requests), accessed 2026-10-27).

## The files

| Clean, following Google's guide with its optional domain layer | Ballarch |
|---|---|
| `PlaceOrderScreen.kt`: the composable | `PlaceOrderScreen.kt`: renders the Ball's stage, read with `read` or `watch` |
| `PlaceOrderViewModel.kt` and `PlaceOrderUiState.kt` | (the screen maps the stage to text with a pure function) |
| `PlaceOrderUseCase.kt` | `PlaceOrder.kt`: ports, stages and the Ball |
| `OrderRepository.kt` and `OrderRepositoryImpl.kt` | (none) |
| `OrdersApi.kt`, `OrderDto.kt` and its mapper, `Order.kt` | `OrdersHttp.kt`: the two adapters, with the HTTP client and the mapping |
| a DI module | `PlaceOrderApp.kt`: the binding, and one call to `placeOrder(…)` in the composition root |
| tests of the ViewModel and the repository | `PlaceOrderTest.kt` |
| (none) | once per app: the runtime, its scheduler and store in `Application`; a Room or SQLite `Store` (not in the reference, see [bindings](bindings.md#a-room-store)) |

Google's guide calls the domain layer optional, and warns that forcing a use case onto every call "can add complexity for little benefit" ([domain layer](https://developer.android.com/topic/architecture/domain-layer), accessed 2026-10-27). Without it, the use case and the repository interface go.

The Ballarch column has no repository or use case because this feature does not need one. A team that wants them can keep them as plain code; they are not Balls. The table is a typical layout, not a measure of effort, and the Ballarch column also depends on a runtime.

`PlaceOrder.kt`, `PlaceOrderApp.kt` and `PlaceOrderTest.kt` are in the tree, in [`examples/place-order`](../reference/kotlin/examples/place-order/). The example is plain Kotlin/JVM: the screen, the HTTP adapters and an Android store are not in the tree.

## What happens when things go wrong

The Clean column describes a straightforward implementation. The ViewModel calls the use case in `viewModelScope`, the repository sends one `POST`, and any exception becomes an error state with "Try again". If it sends an idempotency key at all, it creates one per request. The rows show what happens when nobody adds code for that case. **They are illustrative, not measured.**

| Situation | Straightforward Clean (illustrative) | Ballarch, the `place-order` example | Checked by |
|---|---|---|---|
| The server created the order, and its answer was lost | A timeout becomes "Order failed". "Try again" sends a new request, and the server creates a second order. | The runtime retries with the same key when an attempt ends without an answer, and the server returns the first order. If every answer is lost, the runtime reads `GET /orders?key=…` once the 30-second delivery horizon has passed, and settles the call. If that read fails too, the stage is `Checking`, and "Try again" repeats the order with the same intent and key. | `under every fault and crash point no order is placed twice, and no Unknown is shown as NotPlaced`; `trying again after Unknown repeats the order with the same key, which the server does not place twice` |
| Double tap | Two coroutines send two requests, and the server creates two orders. | The second `Place` meets `Placing` with the same cart and joins it. Nothing is sent. A different cart is rejected. | `decide places a new order, joins a double tap, and repeats an unknown one with its intent` |
| The process dies during the request | Nothing records that the request was sent. The user submits again, and a new request can create a second order. | Under the Durable profile, the call and its phase are stored before the request leaves. After a restart, the runtime sends a call that was never sent, and re-sends with the original key or reconciles one that may have been sent ([Core §3.7](../spec/ballarch-core-2.0.md#37-crash-and-recovery)). | the exploration above, with a crash before any one of the first 16 scheduler tasks |
| The user leaves the screen | "Any coroutine launched in this scope is automatically canceled if the `ViewModel` is cleared" ([coroutines](https://developer.android.com/topic/libraries/architecture/coroutines), accessed 2026-10-27). The request may already be on the wire, and the app never learns whether the order exists. | The runtime is application-scoped, so leaving the screen does not touch the call. When the screen returns, it reads the stage. | by construction: the Ball does not live in the screen |
| "Try again" a week after an unknown result | A new request creates a second order. A stored key would not help: the server has forgotten it. | The binding declares a deduplication window of 24 hours minus the horizon. Preflight refuses the call, because it would end beyond the window after its intent was issued. Nothing is sent, and the stage stays `Checking`. The order is still unsettled: the app must look it up or hand it to support. | `a week later the deduplication window refuses to try again, and nothing is sent` |
| The server rejects the cart with a 422 | The error state is shown. This is correct: nothing was created. | An adapter that maps 422 to `NotApplied`, as the shop example's HTTP adapter does, ends the call `NotDone` on its first attempt, and the stage becomes `NotPlaced` with the reason. The result is the same. The one difference: after an `Unknown`, a `NotDone` describes only the repeat, so the stage returns to `Checking`. | `complete shows NotPlaced only when no earlier call of the order may have placed it` |

The last rule is visible in `complete`. The `when` is an expression over a sealed `Outcome`, so it does not compile without the `Unknown` branch.

From [`reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):

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

## A fair reading

Careful Clean can match every row:

- the ViewModel can ignore a second tap while a request is in flight;
- the repository can create one key per order, store it with the draft and reuse it;
- the request can run in a scope that outlives the screen. Google's data-layer guide recommends this, and recommends WorkManager for "business-oriented operations" that "should survive process death" ([data layer](https://developer.android.com/topic/architecture/data-layer), accessed 2026-10-27);
- the UI state can gain an "unknown" case, and a lookup by key can settle it;
- the repository can refuse to reuse a key older than the server keeps it.

The difference is where that code lives. In Clean, each feature writes its own key store, in-flight guard, recovery and reconciliation, or calls a shared helper the team wrote; nothing in the architecture requires the helper or checks it. In Ballarch they are written once, in a runtime that conformance tests check:

- the runtime classifies every outcome from the effect class each port declares, and derives keys from intents ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle));
- the composition refuses a retry policy on a NonIdempotent port, and preflight refuses a deadline beyond a declared window ([Core §3.5](../spec/ballarch-core-2.0.md#35-bounds-and-preflight));
- `Unknown` is a case of `Outcome`, so the compiler asks for the branch.

What Ballarch does not do: it does not choose what the screen says. The author still writes the `Checking` stage and the rule for "Try again". Reconciliation is declared in the binding, but only works if the server offers a lookup by key and keeps a delivery horizon ([Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)).

Careful Clean is as correct as Ballarch when every feature gets this care. Ballarch is a bet that the care should not have to be repeated per feature. Whether that bet pays off in a team is not measured.

## What Ballarch costs

- **A runtime dependency.** The kernel and the runtime, application-scoped, with a scheduler. An inline scheduler can run a Transient UI Ball's steps on the main thread. A Durable Ball belongs on a background loop, so that store writes stay off the main thread.
- **A sealed stage per step.** Including one for "nobody knows yet", which the UI must render ([honest UI](honest-ui.md)).
- **Serialisable state under the Durable profile.** Stages, intents and call references are stored. A change of state shape needs a state version and a migration ([Core §6.1](../spec/ballarch-core-2.0.md#61-evolution)).
- **A store.** It must meet the store contract ([Core §6](../spec/ballarch-core-2.0.md#6-profiles)). One effectful call answered `Done` costs three durable writes (asserted by `RuntimeTest`). The reference has a memory store and a file store, and no Room or SQLite store; [bindings](bindings.md) describes one.
- **Five concepts.** A Ball with its decider, a port and its effect class, the three outcomes, an intent, and a binding in the composition root.
- **Declarations nothing verifies.** The effect class, the deduplication window and the delivery horizon come from the server's contract. If they are wrong, the guarantees are wrong ([Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)).
- **A server that helps.** Without an idempotency key the port is NonIdempotent, and without a lookup by key every lost answer ends `Unknown` and reaches the user.

## Adopting it in a Clean app

Ballarch governs only Balls ([Core §1](../spec/ballarch-core-2.0.md#1-scope)). You can move one feature at a time.

1. **Keep the read path.** Repositories, caches, paging, Room queries and mappers stay as they are. They are not Balls.
2. **Move the write path.** Each use case that sends an effectful request becomes a Ball. The repository method that sent it becomes an adapter: it keeps its HTTP call, passes the runtime's idempotency key, maps the answer to an adapter result and never retries. Check that the HTTP client in the adapter does not retry by itself either ([R8](../spec/ballarch-core-2.0.md#52-runtime-rules)).
3. **Change the ViewModel.** It sends requests to the runtime instead of calling the use case. It reads the Ball's stage with `read` or `watch` and maps it to UI state with a pure function ([structure](structure.md)).
4. **Bind it.** Declare the effect class, window and horizon in the composition root.

Code that calls an effectful port outside a Ball is outside Core's guarantees, so a mixed app makes no claim for the parts not yet moved.

## Where next

- The whole example: [`PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt), [`PlaceOrderApp.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/app/PlaceOrderApp.kt) and [`PlaceOrderTest.kt`](../reference/kotlin/examples/place-order/src/test/kotlin/ballarch/examples/placeorder/PlaceOrderTest.kt).
- The [tutorial](tutorial.md) builds a Ball step by step.
- [Structuring an application](structure.md) says what is not a Ball, and where UI state, reads and navigation go.
- The [decision guide](decision-guide.md) says when Ballarch is not worth its cost.
