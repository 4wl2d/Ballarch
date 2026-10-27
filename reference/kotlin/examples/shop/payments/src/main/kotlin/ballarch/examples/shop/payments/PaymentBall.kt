package ballarch.examples.shop.payments

import ballarch.examples.shop.payments.api.PaymentReply
import ballarch.examples.shop.payments.api.PaymentRequest
import ballarch.examples.shop.payments.api.PaymentResolution
import ballarch.examples.shop.payments.api.PaymentResolved
import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.CallRef
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.Outcome
import ballarch.kernel.Output
import ballarch.kernel.Publish
import ballarch.kernel.Reply
import ballarch.kernel.RequestContext
import ballarch.kernel.RequestId
import ballarch.kernel.Step
import ballarch.kernel.accept
import ballarch.kernel.acceptLater
import ballarch.kernel.call
import ballarch.kernel.outcomeFor
import ballarch.kernel.reject
import ballarch.kernel.step

// ---------------------------------------------------------------- provider ports (private to this feature)

data class ChargeRequest(val orderId: String, val amount: Long)

sealed interface ChargeResult {
    data class Charged(val reference: String) : ChargeResult
    data object Declined : ChargeResult
}

/** The provider has no idempotency keys: every delivered charge charges again. */
val ProviderCharge = AdapterPort<ChargeRequest, ChargeResult>("provider.charge", EffectClass.NonIdempotent)

data class StatusRequest(val orderId: String)

sealed interface ChargeStatus {
    data class Charged(val reference: String) : ChargeStatus
    data object NoCharge : ChargeStatus
}

/** The provider's status of an order's charge. The composition reconciles [ProviderCharge] through it. */
val ProviderStatus = AdapterPort<StatusRequest, ChargeStatus>("provider.status", EffectClass.Safe)

// ---------------------------------------------------------------- state

sealed interface PaymentState {
    data object New : PaymentState

    data class Charging(val amount: Long, val call: CallRef<ChargeResult>, val waiting: List<RequestId>) : PaymentState

    data class Captured(val amount: Long, val reference: String) : PaymentState

    data class NotCaptured(val amount: Long, val reason: String) : PaymentState

    /** The provider's status settled nothing, so the charge may or may not have happened; an operator must decide. */
    data class Unresolved(val amount: Long) : PaymentState
}

/**
 * S2: captures a payment exactly once per order through a non-idempotent provider.
 *
 * - A charge is sent once (the runtime never re-sends a non-idempotent call).
 * - The composition reconciles the charge port: a charge left without an answer
 *   is settled by the provider's status once its delivery horizon has passed,
 *   so the completion is usually `Done` or `NotDone`. `NotDone` proves that
 *   nothing was charged.
 * - `Unknown` remains only when the status settled nothing: the payment is
 *   then [PaymentState.Unresolved], and never charged again.
 *
 * @param chargeTimeoutMillis the charge's deadline, which covers the
 *   reconciliation: it must exceed the binding's horizon plus its status timeout.
 */
class PaymentBall(
    private val chargeTimeoutMillis: Long = 1_000,
) : Ball<PaymentState, PaymentRequest, PaymentReply> {
    override val type = "payment"

    override fun initial(key: String): PaymentState = PaymentState.New

    override fun decide(state: PaymentState, request: PaymentRequest, ctx: RequestContext): Decision<PaymentState, PaymentReply> =
        when (request) {
            is PaymentRequest.Capture -> capture(state, request.amount, ctx)
        }

    private fun capture(state: PaymentState, amount: Long, ctx: RequestContext): Decision<PaymentState, PaymentReply> = when (state) {
        PaymentState.New -> {
            val (ref, call) = ctx.call(ProviderCharge, ChargeRequest(ctx.self.key, amount), chargeTimeoutMillis)
            acceptLater(PaymentState.Charging(amount, ref, listOf(ctx.requestId)), call)
        }
        is PaymentState.Charging -> when {
            state.amount != amount -> reject(PaymentReply.AmountMismatch)
            state.waiting.size >= MAX_WAITING -> reject(PaymentReply.Busy)
            else -> acceptLater(state.copy(waiting = state.waiting + ctx.requestId))
        }
        else -> if (amountOf(state) != amount) reject(PaymentReply.AmountMismatch) else accept(state, status(state))
    }

    override fun complete(state: PaymentState, completion: Completion<*>, ctx: Context): Step<PaymentState, PaymentReply> {
        if (state !is PaymentState.Charging) return step(state) // stale completion
        val outcome = completion.outcomeFor(state.call) ?: return step(state)
        return charged(state, outcome, ctx)
    }

    private fun charged(state: PaymentState.Charging, outcome: Outcome<ChargeResult>, ctx: Context): Step<PaymentState, PaymentReply> {
        val next = when (outcome) {
            is Outcome.Done -> when (val r = outcome.value) {
                is ChargeResult.Charged -> PaymentState.Captured(state.amount, r.reference)
                ChargeResult.Declined -> PaymentState.NotCaptured(state.amount, "declined")
            }
            is Outcome.NotDone -> PaymentState.NotCaptured(state.amount, "not charged")
            is Outcome.Unknown -> PaymentState.Unresolved(state.amount)
        }
        val replies: List<Output<PaymentReply>> = state.waiting.map { Reply(it, status(next)) }
        return if (next is PaymentState.Unresolved) Step(next, replies) else Step(next, replies + resolved(ctx, next))
    }

    private fun resolved(ctx: Context, s: PaymentState): Output<PaymentReply> = when (s) {
        is PaymentState.Captured -> Publish(PaymentResolved, PaymentResolution(ctx.self.key, captured = true, reference = s.reference))
        is PaymentState.NotCaptured -> Publish(PaymentResolved, PaymentResolution(ctx.self.key, captured = false, reference = null))
        else -> error("not a resolution: $s")
    }

    private fun amountOf(state: PaymentState): Long = when (state) {
        PaymentState.New -> -1
        is PaymentState.Charging -> state.amount
        is PaymentState.Captured -> state.amount
        is PaymentState.NotCaptured -> state.amount
        is PaymentState.Unresolved -> state.amount
    }

    companion object {
        const val MAX_WAITING = 8

        /**
         * What a payment in [state] says about itself: the answer to a repeated
         * Capture, and what code outside Balls shows from a read of committed
         * state (R12). Pure, like the deciders.
         */
        fun status(state: PaymentState): PaymentReply = when (state) {
            PaymentState.New -> PaymentReply.NoPayment
            is PaymentState.Charging, is PaymentState.Unresolved -> PaymentReply.Pending
            is PaymentState.Captured -> PaymentReply.Captured(state.reference)
            is PaymentState.NotCaptured -> PaymentReply.NotCaptured(state.reason)
        }
    }
}
