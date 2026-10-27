# Honest UI

A screen may show only what a Ball's committed state establishes about an effect. This guide derives what to show from that state: five statuses for one effect, seven knowledge values for an operation of several calls, and rules for snapshots, messages and accessibility. It is non-normative. The rules it applies are [Core §2.2](../spec/ballarch-core-2.0.md#22-outcomes), [§4.2](../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations), R14 and R15 ([Core §5.1](../spec/ballarch-core-2.0.md#51-author-rules)).

## Five statuses

Every effectful action on a screen is in one of five statuses. Each is a function of the Ball's stage, so the screen keeps no flag of its own:

| Status | Means | place-order stage | shop payment stage |
|---|---|---|---|
| Idle | nothing requested | `Idle` | `New` |
| Pending | a call is open; it may or may not have been sent | `Placing` | `Charging` |
| Done | the effect happened | `Placed` | `Captured` |
| NotDone | the effect did not happen: evidence, or a recorded refusal | `NotPlaced` | `NotCaptured` |
| Unknown | the effect may or may not have happened | `Checking` | `Unresolved` |

`Pending` covers both "not sent yet" and "sent, no answer yet". A decider sees no call phases, and the screen needs none. A call on a Safe port, such as the catalog search, never reaches `Unknown`, and `safeOutcomeFor` gives its completion a type without that case. The stages are in [`PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt) and [`PaymentBall.kt`](../reference/kotlin/examples/shop/payments/src/main/kotlin/ballarch/examples/shop/payments/PaymentBall.kt).

Put the mapping in a pure function next to the Ball.

Sketch: no reference code and no conformance claim.

```kotlin
enum class UiStatus { Idle, Pending, Done, NotDone, Unknown }

fun uiStatus(state: PlaceOrderState): UiStatus = when (state) {
    PlaceOrderState.Idle -> UiStatus.Idle
    is PlaceOrderState.Placing -> UiStatus.Pending
    is PlaceOrderState.Placed -> UiStatus.Done
    is PlaceOrderState.NotPlaced -> UiStatus.NotDone
    is PlaceOrderState.Checking -> UiStatus.Unknown
}
```

The `when` has no `else`, so a new stage does not compile until someone decides what the screen shows for it. Feed the function from `runtime.read(PlaceOrder, key)` or a typed `watch` ([structure guide](structure.md#reads)). The shop has reply-type views of the same state, `PaymentBall.status` and `CheckoutFlow.status`, and `checkoutStatus` applies one to a read. `PaymentBall.status` answers `Pending` for both `Charging` and `Unresolved`, so a screen that must tell Pending from Unknown maps `PaymentState` itself, as in the table.

## What to show

| Status | Message | Primary action |
|---|---|---|
| Idle | none | "Place order", enabled |
| Pending | "Placing your order…"; "Checking your order…" while a repeat with an earlier intent runs | disabled; a second tap joins the order in flight |
| Done | "Order o-1 placed." | "View order" |
| NotDone | "Your order was not placed: (reason). Nothing was ordered." | "Place order", enabled; a new order gets a new intent |
| Unknown, on an Idempotent port with an intent | "We could not confirm your order. It may have been placed." | "Try again", which repeats the order with the same intent |
| Unknown, on a NonIdempotent port | "We are checking your payment. Do not pay again." | none; "Contact support" if it stays unresolved |

Four rules hold the table together.

1. **Never show `Unknown` as failed**, in words, colour or icon. A user who reads "failed" may pay again. A Ball must not present `Unknown` as evidence that nothing happened (R15).
2. **Never re-enable a NonIdempotent submit after `Unknown`.** The Ball must not request the same effect until it has evidence that the first call had no effect (R15). The shop's payment never charges again after `Unknown`: `Unresolved` waits for an operator.
3. **"Try again" repeats the same intent.** Calls that name one intent carry one idempotency key, so a deduplicating server applies at most one of them ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)). `PlaceOrderTest` `trying again after Unknown repeats the order with the same key, which the server does not place twice` asserts four attempts with one key, and one order.
4. **"Try again" ends with the deduplication window.** After it, the runtime refuses the repeat at preflight and sends nothing (`PlaceOrderTest` `a week later the deduplication window refuses to try again, and nothing is sent`). That refusal is a preflight fault: the request ends `NotDone(Fault)` and the runtime records a fault, which the [structure guide](structure.md#http-status-codes) maps to HTTP 500. Treat it as a guard, not the user's path. Hide the button once `intent.issuedAt` plus the binding's window ([`PlaceOrderApp.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/app/PlaceOrderApp.kt)), less the call's timeout, has passed, and offer "Check your orders" instead. Or let `decide` compare `ctx.now` with `state.intent.issuedAt` and reject a late repeat itself, which the caller receives as `NotDone(Refused)`, not as a fault.

A `NotDone` after a repeat says nothing about the earlier call, which may have placed the order. Place-order therefore shows `NotPlaced` only for a first call:

From [`reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):

```kotlin
        return when (val outcome = completion.outcomeFor(state.call)) {
            is Outcome.Done -> step(Placed(outcome.value.id))
            // After an Unknown, NotDone is about this call only: the earlier one may have placed the order.
            is Outcome.NotDone -> step(if (state.repeat) Checking(state.cart, state.intent) else NotPlaced("${outcome.reason}"))
            is Outcome.Unknown -> step(Checking(state.cart, state.intent))
            null -> step(state) // a completion of another call
        }
```

`PlaceOrderTest` `under every fault and crash point no order is placed twice, and no Unknown is shown as NotPlaced` explores this. Each of up to three attempts has four fates: answered, request lost, answer lost after placing, connection refused. The status read has two, and each run has no crash or one crash before any of its first 16 tasks, then one "Try again" if the order ends in `Checking`. It asserts that `Placed` means one order, `NotPlaced` none, and `Checking` at most one. It prints 1,727 runs: 1,352 end `Placed`, 87 `NotPlaced`, 285 `Checking` and 3 `Idle`, in which the crash came before the order's decision was committed, so nothing was sent (printed by that test).

Google Play Billing has the same third state. As of 2026-10-27, a purchase can be `PENDING`, and an app should "grant entitlement only when the state is PURCHASED" ([Integrate the Google Play Billing Library](https://developer.android.com/google/play/billing/integrate), accessed 2026-10-27).

## Operations of several calls

A Flow that answers for several calls reports one of seven knowledge values ([Core §4.2](../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations)). For a trip of a flight and a hotel:

| Knowledge | Message | Next action |
|---|---|---|
| Done | "Your trip is booked." | show the bookings |
| NotDone | "Nothing was booked." | offer to start again |
| NoEffects | none: the operation requested nothing | as Idle |
| Partial | "Part of your trip went through: the flight is booked, the hotel is not." | show each part; offer to cancel the part that went through, or contact support |
| SomeDone | "Not finished. Part of it went through; checking the rest." | no retry; show what is known |
| NotAllDone | "Not finished; checking whether anything went through." | no retry |
| Unknown | "Checking your booking…" | no retry |

"Checking" is true while the Flow still works. Once it has settled with `SomeDone`, `NotAllDone` or `Unknown`, nothing more will be learned on its own. Say "We could not confirm your booking", route the case to support with the Flow's record, and still never say "failed".

`TripFlowTest` `the trip's knowledge admits the real bookings under every fate and crash point` asserts what this prevents. A two-valued status was wrong in 1,190 runs, all among the 5,565 in which the trip gave up compensating. The algebra split the 5,565 into `NotAllDone` (2,977), `Unknown` (2,037), `SomeDone` (379) and `Partial` (172), and no claim contradicted the bookings.

A reply with only three outcomes loses information: `Partial`, `NotAllDone` and `SomeDone` all become `Unknown` ([Core §7.4](../spec/ballarch-core-2.0.md#74-composition-closure)). Put the knowledge in the reply type, as the trip's `TripReply.Status` does.

## Snapshots are labelled

`Runtime.read` returns a `Snapshot`: the committed state and the revision that committed it. What a screen derives from it is true of that revision.

An operation of several calls adds a trap. A status that counts the calls a Ball has not made yet as not done describes only the instant it was made, and a later call can contradict it. Core calls this a snapshot of the operation. It must say so, for example by carrying the operation's stage, and must never be offered as the outcome (R14). The trip's reply carries its stage, and only a settled one is the result:

From [`reference/kotlin/examples/trip/src/main/kotlin/ballarch/examples/trip/api/TripApi.kt`](../reference/kotlin/examples/trip/src/main/kotlin/ballarch/examples/trip/api/TripApi.kt):

```kotlin
fun settledKnowledge(reply: TripReply): Knowledge? = when (reply) {
    is TripReply.Status -> reply.knowledge.takeIf { reply.stage == Stage.Settled }
}
```

So show "Booking the hotel… (flight booked)" while the trip runs, and "Trip booked" only once it has settled. In the trip exploration, a status that counted calls without a completion as not done was contradicted in 5,920 runs (asserted by the same `TripFlowTest` test). The shop's `CheckoutReply.Status` also carries its phase.

## Messages and navigation are state

Google's UI-layer guide keeps a user message in UI state: once the message is shown, "the UI needs to notify the ViewModel of that" ([UI events](https://developer.android.com/topic/architecture/ui-layer/events), accessed 2026-10-27). With Ballarch, the outcome is already Ball state: `NotPlaced(reason)` stays until the next order. What the UI acknowledges is that it showed the message for one revision. Keep the last shown revision in UI state, and show a snackbar when a snapshot with a higher revision reaches `Placed` or `NotPlaced`. A message that must outlive the screen, such as a banner kept until the user dismisses it, is business state: give the Ball a `Dismiss` request that moves it on.

Navigation works the same way ([structure guide](structure.md#navigation)).

## Over HTTP and in agent reports

The same rules apply to an HTTP response. A backend answers `Unknown` with 202 and a status resource, never with 5xx or 504. The [HTTP table in the structure guide](structure.md#http-status-codes) maps every outcome. A client that receives 202 shows Pending or Unknown, not failed.

An agent's final report follows the knowledge table. Its status is the task's knowledge value, computed from the outcomes of its tool calls, and only its narrative comes from the model ([agents guide](ai-agents.md)).

## Accessibility

- **Text, not colour alone.** WCAG 2.2 success criterion 1.4.1 requires that colour "is not used as the only visual means of conveying information" ([WCAG 2.2](https://www.w3.org/TR/WCAG22/#use-of-color), accessed 2026-10-27). Give `Unknown` its own words and icon, distinct from an error's.
- **Announce changes without moving focus.** Criterion 4.1.3 asks that status messages reach assistive technology without receiving focus ([WCAG 2.2](https://www.w3.org/TR/WCAG22/#status-messages)). On Android, make the status text a polite live region: [`View.setAccessibilityLiveRegion`](https://developer.android.com/reference/android/view/View#setAccessibilityLiveRegion(int)), or [`LiveRegionMode.Polite`](https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/LiveRegionMode) in Compose. As of 2026-10-27, [`View.announceForAccessibility`](https://developer.android.com/reference/android/view/View#announceForAccessibility(java.lang.CharSequence)) is deprecated in API level 36. On iOS, post [`UIAccessibility.Notification.announcement`](https://developer.apple.com/documentation/uikit/uiaccessibility/notification/announcement). All accessed 2026-10-27.
- **Say why a control is disabled.** A disabled "Place order" sits next to the text "Placing your order…", which assistive technology reads.
