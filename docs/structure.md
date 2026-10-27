# Structuring an application

This guide shows where code goes in an application built with Ballarch: which parts are Balls, and how screens, background jobs and HTTP handlers use them. It is non-normative; the [specification](../spec/ballarch-core-2.0.md) holds where they differ. The code comes from the [Kotlin examples](../reference/kotlin/examples/).

## The floor and the ladder

**The floor.** A component needs a Ball when it calls a port whose effect can be uncertain: an Idempotent or NonIdempotent port, including another Ball's port ([Core §1](../spec/ballarch-core-2.0.md#1-scope)). Everything else is plain code that Core does not constrain.

Climb only as far as a feature needs:

| Rung | When | What you write | Example |
|---|---|---|---|
| 0 | no Idempotent or NonIdempotent call | plain code: a class, a reducer, a repository | [`PlainOrderDraft.kt`](../reference/kotlin/examples/order-draft/src/main/kotlin/ballarch/examples/orderdraft/plain/PlainOrderDraft.kt) |
| 1 | one effect, possibly repeated | one Ball in one file: its stages, `decide`, `complete`, a port with its effect class, and the three outcomes | [`PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt) |
| 2 | several effects reported as one operation, with compensation | a Flow whose reply is the operation's settled knowledge, under the Durable profile | the [trip example](../reference/kotlin/examples/trip/) |

A Ball without effects, such as the [order-draft Ball](../reference/kotlin/examples/order-draft/src/main/kotlin/ballarch/examples/orderdraft/OrderDraft.kt), is allowed but never required.

## Layout

Keep a feature together: its Ball, ports, messages, adapters and tests ([Core §7.1](../spec/ballarch-core-2.0.md#71-module-structure)):

```text
app/
  Composition.kt        the composition root: one line per Ball and per binding, such as placeOrder(orders, lookup)
placeorder/
  PlaceOrder.kt         ports, stages and the Ball: decider code, checked by deciderPurity
  PlaceOrderTest.kt     the decider as a function, and one fault exploration
placeorder/app/
  PlaceOrderApp.kt      the binding Composition.placeOrder: retries, window, reconciliation
placeorder/ui/
  PlaceOrderScreen.kt   reads or watches the Ball, renders, sends requests
placeorder/http/
  OrdersHttp.kt         adapters: the HTTP client, DTOs and their mapping
```

§7.1 suggests one package, but the reference purity rule treats every class in a package that holds a Ball as decider code. So screens, adapters and bindings go in sub-packages, as in the shop. The [place-order example](../reference/kotlin/examples/place-order/) follows the rule too: `PlaceOrder.kt` holds the decider code, `app/PlaceOrderApp.kt` the binding, and `BallarchRulesTest` checks both with every rule. The shop's root lists each Ball and binding once:

From [`reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt`](../reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt):

```kotlin
fun shopComposition(provider: ProviderAdapters, initialStock: Int): Composition =
    paymentsComposition(provider)
        .ball(Checkout)
        .ball(InventoryBall(initialStock))
        .ball(OrderBall)
        .subscribe(PaymentResolved, "checkout") { it.orderId }
```

API modules per feature are optional; with them, as in the shop, the build checks that features use only each other's public types (R10).

Above all, make every call on an Idempotent or NonIdempotent port from a Ball; code that makes one elsewhere is outside Core's guarantees.

## What is not a Ball

| Thing | Where it goes |
|---|---|
| Screen, Activity, Fragment, ViewModel, SwiftUI view | UI code that reads Balls and sends them requests |
| Repository, DAO | plain code over the database or a cache |
| Use case | a function, or nothing; one that only forwards a request adds a layer and no guarantee |
| Cache, read model, paging source | plain code; a Ball reaches it through a Safe port |
| Form input, focus, scroll position | the UI toolkit's state |
| Mapper, DTO | inside the adapter that needs it |
| Stateless service: pricing, validation, formatting | a pure function |
| Navigation stack | the navigator |
| CRUD inside one database transaction | plain code: the transaction is its only effect |

## Kinds of state

| Kind | Example | Home |
|---|---|---|
| Business state and obligations | an order being placed, a payment, the open calls | a Ball's state, committed by the runtime |
| View state | whether the button is enabled, the status line | derived from Ball state by a pure function, never stored |
| Ephemeral input | text being typed, the selected tab | the UI toolkit and its saved-state API |
| Remote read data | products, search results, prices | a cache; a Ball that needs it calls a Safe port |
| Navigation | the back stack | the navigator |

Derived view state cannot show "placed" while the Ball is in `Checking`; the [honest UI guide](honest-ui.md) gives the derivation.

## Reads

Code outside Balls reads a Ball's committed state with a read, not a request (R12, [Core §5.1](../spec/ballarch-core-2.0.md#51-author-rules)). A read commits nothing, never enters the inbox and works from any thread. During a step it returns the state before the step (`RuntimeTest` `a read during a step sees the state before it`).

From [`reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt`](../reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt):

```kotlin
fun checkoutStatus(runtime: Runtime, orderId: String): CheckoutReply.Status =
    CheckoutFlow.status(runtime.read(Checkout, orderId).state)
```

`read(ball, key)` is typed by the Ball; `read(id)` returns a `Snapshot<Any?>`. `ShopAppTest` `a screen reads the checkout status, which commits nothing` asserts that reads add no store write. A typed `watch` delivers the current `Snapshot`, then one per commit, on the runtime's loop:

From [`reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/app/CatalogApp.kt`](../reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/app/CatalogApp.kt):

```kotlin
fun showSearch(runtime: Runtime, screen: String, show: (Display) -> Unit) {
    runtime.watch(CatalogSearchBall, screen) { show(it.state.display) }
}
```

The reference `watch` cannot remove a listener. Register one per instance at application scope and feed a flow, such as a `StateFlow`, that screens collect. Or call `read` whenever a screen renders.

- **Ball to Ball.** A Ball reads another only by a request that the target accepts with its state unchanged, an immediate reply and no outputs (R12).
- **External data.** A Ball reads it through a Safe adapter port, such as `OrderLookup` in place-order. A Safe call never ends `Unknown`.
- **Several Balls on one screen.** Read each and combine them in one pure UI function.

## Navigation

Navigation follows Ball state and is consumed once. It is never a Ball output: a Ball outputs only calls, replies, notices and cancels. Google's guide says the same of ViewModel events, which "should always result in a UI state update" ([UI events](https://developer.android.com/topic/architecture/ui-layer/events), accessed 2026-10-27).

The screen navigates to the receipt when it sees `Placed`, and keeps the handled revision in the UI's saved state, so a later render does not navigate again.

A wizard whose steps only collect input is UI state, plus one request at submit. A wizard whose steps make effectful calls, such as "hold the seat, then pay", is one Flow ([Core §7.2](../spec/ballarch-core-2.0.md#72-workflows)). Each screen shows one of its stages.

## Double submit and latest-wins

Steps of one instance never overlap (R3). A second tap reaches `decide` after the first tap's decision is committed, and meets the stage it left:

From [`reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):

```kotlin
        when (state) {
            Idle, is NotPlaced -> place(request.cart, ctx.newIntent(), repeat = false, ctx)
            // "Try again" after Unknown: the same order with the same intent, so the server places it at most once.
            is Checking -> if (request.cart == state.cart) place(state.cart, state.intent, repeat = true, ctx) else reject(state)
            // A double tap joins the order in flight.
            is Placing -> if (request.cart == state.cart) accept(state, state) else reject(state)
            is Placed -> accept(state, state)
        }
```

`PlaceOrderTest` `decide places a new order, joins a double tap, and repeats an unknown one with its intent` checks each row. A disabled button helps the user; it does not prevent the second order. With `InlineScheduler` on the main thread, a request is decided, committed and answered before `request` returns (`InlineSchedulerTest`), so the next frame shows the order as pending.

A search wants the latest query to win. The catalog Ball cancels the previous search to save work, and its `complete` reads only the completion of the latest call (`CatalogSearchTest`):

From [`reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/CatalogSearch.kt`](../reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/CatalogSearch.kt):

```kotlin
        val latest = state.pending ?: return step(state)
        val outcome = completion.safeOutcomeFor(latest.call) ?: return step(state) // an older search: never replaces the display
```

Join, reject, queue and replace are branches of `decide` on the current stage, not an admission DSL.

## Process death

Mobile systems end processes without notice. On Android, "the system can kill the process at any time to reclaim memory", and `onDestroy()` is not guaranteed to run ([Processes and app lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle)). In Compose, `SavedStateHandle` updates made in the background "might be lost" if the process is killed ([Saved State module](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)). On iOS, "the system terminates background apps as needed" to free resources ([Preparing your UI to run in the background](https://developer.apple.com/documentation/uikit/preparing-your-ui-to-run-in-the-background)). All accessed 2026-10-27.

So:

1. **Runtimes are application-scoped.** Build each once per process, in `Application.onCreate` or the app's dependency container. An app may have two: a Transient one on the main thread for UI Balls and a Durable one on a background loop (item 2). Balls that call each other share one. A runtime scoped to a ViewModel dies with the screen, and its open calls lose their completions.
2. **Balls with effects are Durable.** Their store replaces one instance's record in one transaction, takes a new incarnation at each start, and excludes or fences a second runtime ([Core §6](../spec/ballarch-core-2.0.md#6-profiles), R16). Room, SQLite or Core Data can carry it. The reference ships `MemoryStore` and a JVM `FileStore`, not a mobile store, and has no Swift runtime ([bindings guide](bindings.md)). Run the Durable runtime on a background loop (`ThreadScheduler`), since every commit writes the store; `InlineScheduler(post = Handler(Looper.getMainLooper())::post)` suits the Transient one.
3. **Recovery happens at start.** The runtime re-sends an Idempotent call with its original key while attempts and time remain. A NonIdempotent call that may have been sent is reconciled on a reconciled port, and otherwise ends `Unknown(RecoveredAfterCrash)` ([Core §3.7](../spec/ballarch-core-2.0.md#37-crash-and-recovery)).
4. **WorkManager and BGTaskScheduler only wake the runtime.** When a watcher sees a Ball enter a waiting stage, it schedules a job that restarts a killed process. The job starts the runtime if needed, waits until a read shows the Ball out of that stage (for place-order, out of `Placing`), and returns. It never sends the request and never returns `Result.retry()` to repeat it: only the runtime retries (R8). WorkManager keeps work "across app restarts and device reboots" ([WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)), and for a BGTaskScheduler task "the system launches the app to run the task" ([background tasks](https://developer.apple.com/documentation/uikit/using-background-tasks-to-update-your-app)); both accessed 2026-10-27. If the system stops the job early, or the process dies between the commit and the enqueue, the call stays in the store and the runtime recovers it at its next start.
5. **Saved state holds only unsent input and the Ball's key**, such as the draft id. Everything else is read again.

Under the Transient profile a restart forgets the Ball's calls. In the trip example, a client that books again after a Transient restart gets a false settled claim in 608 of 3,073 settled runs, and 152 runs book a flight twice (`TripFlowTest` `the trip requires the Durable profile`). The place-order exploration crashes a Durable runtime before any of its first 16 tasks, and asserts that no order is placed twice and no `Unknown` is shown as `NotPlaced` (`PlaceOrderTest`).

## Offline

- **A short outage.** An attempt whose request or answer is lost ends without an answer, and the runtime retries an Idempotent call within its deadline; an attempt timeout ends a hung attempt early. Place-order allows three attempts, a 1 s backoff, a 10 s attempt timeout and a 60 s deadline. A first attempt refused before anything was sent, as with no network at all, ends the call `NotDone` at once: nothing was ordered.
- **A long outage.** Do not hold a call open: its timeout is bounded (`Limits.maxTimeoutMillis`, ten minutes by default). When a call ends `NotDone` for lack of network, the Ball can move the request to a `Queued` stage instead of showing it as not placed. An inbound adapter turns the platform's signal into a request: [`ConnectivityManager.NetworkCallback`](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback) on Android, [`NWPathMonitor`](https://developer.apple.com/documentation/network/nwpathmonitor) on iOS (both accessed 2026-10-27), or the job of item 4 with a network constraint. The Ball then calls with a new intent: the earlier call had no effect, and an intent's window runs from the decision that issued it. Only `Checking`, where an earlier call may have reached the server, keeps its intent across a wait.
- **After `Unknown`.** Repeat with the same intent while the call fits the window. `PlaceOrderTest` `trying again after Unknown repeats the order with the same key, which the server does not place twice` asserts four attempts with one key, and one order. Beyond the window a repeat could be applied twice, so preflight refuses it ([Core §3.5](../spec/ballarch-core-2.0.md#35-bounds-and-preflight)) and nothing is sent (`a week later the deduplication window refuses to try again, and nothing is sent`). Reconcile instead, through a Safe status port or with the user and support.
- **Concurrent offline edits** that must merge are outside Core. An edit made offline is a request that the owning Ball decides when it arrives.

## Backend services

- **A Ball is an aggregate**: one instance per key, such as an order id. The HTTP handler parses and authenticates, then calls `runtime.request(target, message, timeoutMillis, idempotencyKey, caller) { outcome -> … }`, with the principal in `Caller` ([Core §8](../spec/ballarch-core-2.0.md#8-guidance-non-normative)). The callback runs on the runtime's loop; complete the framework's asynchronous response there (no reference code).
- **One transaction per decision.** A Durable store replaces an instance's record atomically: state, call ledger, open and answered requests, outbox. The write is durable before anything is dispatched. In SQL that is one row per instance, or several tables in one transaction.
- **Existing tables.** The store holds only the Ball's own record. Domain tables stay where they are, and a Ball changes them through an adapter port. An insert with a unique constraint on the runtime's idempotency key, whose adapter answers a duplicate with the existing row, is Idempotent.
- **Inbound idempotency keys.** Pass the `Idempotency-Key` header as `idempotencyKey`; it becomes the request identity. A repeat with the same body joins the open request or gets the stored reply; another body gets `NotDone(IdempotencyConflict)` ([Core §3.3](../spec/ballarch-core-2.0.md#33-inbound-request-lifecycle)). Answered requests are kept for `Limits.repliedRetentionMillis` (one hour by default), at most `Limits.repliedRetention` per instance. Publish that as your key expiry, as the IETF draft asks ([draft-ietf-httpapi-idempotency-key-header-08](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-08) §2.3, dated 2026-10-22, accessed 2026-10-27). A client's window must fit inside it: place-order declares 24 hours less 30 s, so a Ballarch server behind it needs at least 24 hours. Deduplication is per instance, so name the instance from the request, such as a client-chosen order id. A repeat after the retention then still meets the Ball's stage.

### HTTP status codes

Map the outcome of `runtime.request` to a response that claims no more than the outcome:

| Outcome | Response | What the client learns |
|---|---|---|
| `Done(reply)` about a finished request | 200, or 201 with `Location` for a created resource | applied; the reply says what happened, possibly a recorded business refusal |
| `Done(reply)` naming a stage that has not settled | 202 with the stage and a status link | accepted, not finished |
| `Done(reply)` with settled knowledge | 200 with the knowledge value | final; it no longer changes |
| `NotDone(Refused(reply))` | a 4xx chosen by the reply: 409 for a conflict with the current stage, 422 for a request the decider refuses | not applied; change it and send again |
| `NotDone(IdempotencyConflict)` | 422 (draft §2.7) | the key was reused with another body |
| `NotDone(Overloaded)`, `NotDone(DeadlineBeforeSend)` | 503 with `Retry-After` | not applied; send again later with the same key |
| `NotDone(Fault(message))` | 500, with a body that says the request was not applied | not applied; a defect was recorded |
| `Unknown(reason)` | 202 with a link to a status resource, never 5xx or 504 | it may or may not take effect |

After a 202, RFC 9110 says, the request "might or might not eventually be acted upon", and the response should point to a status monitor ([RFC 9110 §15.3.3](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.3), accessed 2026-10-27). That is `Unknown`. A client that reads a 500 or a gateway's 504 as failure may submit again without the key. The status resource answers `GET` with a read, labelled with the Ball's stage; the client polls it, or repeats the request with the same key, which joins it or receives its reply. For a repeat that arrives while the first request is open, the draft asks for 409; the runtime joins it instead. Both are honest.

- **Workflows.** A multi-step operation with compensation is one Flow, not a hand-written saga ([Core §7.2](../spec/ballarch-core-2.0.md#72-workflows)). It replies once settled, with the settled knowledge ([Core §7.4](../spec/ballarch-core-2.0.md#74-composition-closure)), as the trip does.
- **Deploys.** The runtime restarts from committed state and never re-runs old decisions with new code (R4). A changed state shape needs a version and `migrate`; a port or Ball type is drained before removal, or the runtime refuses to start ([Core §6.1](../spec/ballarch-core-2.0.md#61-evolution), `EvolutionTest`). With a store that excludes or fences (R16), a rolling deploy cannot leave two runtimes committing to one instance (`OwnershipTest`). Spreading instances over several servers is partitioning, which Core leaves to you; the reference `FileStore` excludes only on one host.

## What this structure costs

A runtime dependency and, under the Durable profile, a store that meets the contract; the reference has no Room, Core Data or SQL store. Three durable writes per effectful call, and none per read (`RuntimeTest`). One writer per instance, so a hot key is a queue. An explicit stage per step, serialisable under Durable. [Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations) lists the limits; the [decision guide](decision-guide.md) says when to choose something else.
