package ballarch.examples.placeorder

import ballarch.examples.placeorder.PlaceOrderState.Checking
import ballarch.examples.placeorder.PlaceOrderState.Idle
import ballarch.examples.placeorder.PlaceOrderState.NotPlaced
import ballarch.examples.placeorder.PlaceOrderState.Placed
import ballarch.examples.placeorder.PlaceOrderState.Placing
import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.CallRef
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.Intent
import ballarch.kernel.Outcome
import ballarch.kernel.RequestContext
import ballarch.kernel.Step
import ballarch.kernel.accept
import ballarch.kernel.call
import ballarch.kernel.outcomeFor
import ballarch.kernel.reject
import ballarch.kernel.step

// ---- the ports: the orders server deduplicates an idempotency key for 24 hours, and finds an order by its key

data class Cart(val items: List<String>)

data class Order(val id: String)

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
