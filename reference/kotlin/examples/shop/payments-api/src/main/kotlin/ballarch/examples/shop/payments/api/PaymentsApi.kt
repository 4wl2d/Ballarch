package ballarch.examples.shop.payments.api

import ballarch.kernel.BallPort
import ballarch.kernel.Topic

/**
 * Public contract of the payments feature. Other features depend on this module
 * only. One payment instance exists per order; its instance key is the order id.
 * Code outside Balls shows a payment's status from a read of its committed
 * state (R12), not with a request.
 */
sealed interface PaymentRequest {
    /** Capture [amount] for this order. Repeating it is safe: the answer reflects the order's payment. */
    data class Capture(val amount: Long) : PaymentRequest
}

sealed interface PaymentReply {
    data class Captured(val reference: String) : PaymentReply

    data class NotCaptured(val reason: String) : PaymentReply

    /** The provider's answer is not known yet; a [PaymentResolved] notice follows. */
    data object Pending : PaymentReply

    data object AmountMismatch : PaymentReply

    data object NoPayment : PaymentReply

    data object Busy : PaymentReply
}

val PaymentsPort = BallPort<PaymentRequest, PaymentReply>("payment")

/** Published once per order when its payment is known to be captured or not captured. */
data class PaymentResolution(val orderId: String, val captured: Boolean, val reference: String?)

val PaymentResolved = Topic<PaymentResolution>("payment.resolved")
