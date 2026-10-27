package ballarch.examples.shop.app

import ballarch.examples.shop.checkout.CheckoutFlow
import ballarch.examples.shop.checkout.CheckoutReply
import ballarch.examples.shop.inventory.InventoryBall
import ballarch.examples.shop.orders.OrderBall
import ballarch.examples.shop.payments.api.PaymentResolved
import ballarch.runtime.Composition
import ballarch.runtime.Runtime

/** The shop's checkout Flow: one instance per order, keyed by the order id. */
val Checkout: CheckoutFlow = CheckoutFlow()

/** Composition root of the whole shop (slice S3). */
fun shopComposition(provider: ProviderAdapters, initialStock: Int): Composition =
    paymentsComposition(provider)
        .ball(Checkout)
        .ball(InventoryBall(initialStock))
        .ball(OrderBall)
        .subscribe(PaymentResolved, "checkout") { it.orderId }

/**
 * What a screen shows for the checkout of [orderId]. It reads the last
 * committed state, so it commits nothing and does not wait behind the Flow's
 * inbox (R12): a screen may call it every time it renders.
 */
fun checkoutStatus(runtime: Runtime, orderId: String): CheckoutReply.Status =
    CheckoutFlow.status(runtime.read(Checkout, orderId).state)
