package ballarch.examples.shop.orders.api

import ballarch.kernel.BallPort

/** Public contract of the orders feature. One instance per order (= checkout id). */
sealed interface OrderRequest {
    /** Confirm the order after its payment was captured. Idempotent. */
    data class Confirm(val paymentReference: String) : OrderRequest

    data object Get : OrderRequest
}

sealed interface OrderReply {
    data class Confirmed(val paymentReference: String) : OrderReply
    data object NotConfirmed : OrderReply
}

val OrdersPort = BallPort<OrderRequest, OrderReply>("order")
