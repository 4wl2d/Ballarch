package ballarch.examples.placeorder.app

import ballarch.examples.placeorder.Cart
import ballarch.examples.placeorder.Order
import ballarch.examples.placeorder.OrderLookup
import ballarch.examples.placeorder.Orders
import ballarch.examples.placeorder.PlaceOrder
import ballarch.runtime.Adapter
import ballarch.runtime.Composition
import ballarch.runtime.Reconciliation
import ballarch.runtime.RetryPolicy
import ballarch.runtime.StatusVerdict

/** A request reaches the orders server within this time of its send, or never. */
const val ORDERS_HORIZON_MILLIS: Long = 30_000

/**
 * Binds [PlaceOrder] to the orders server: [orders] sends the idempotency key the
 * runtime supplies, and [lookup] finds an order by its key. The runtime retries
 * lost attempts, the server deduplicates their key, and a call left without an
 * answer is settled by [lookup] once the horizon has passed.
 */
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
