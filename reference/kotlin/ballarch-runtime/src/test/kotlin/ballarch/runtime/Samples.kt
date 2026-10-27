package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.BallPort
import ballarch.kernel.Call
import ballarch.kernel.CallKey
import ballarch.kernel.Cancel
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.Intent
import ballarch.kernel.Notice
import ballarch.kernel.Outcome
import ballarch.kernel.Publish
import ballarch.kernel.Reply
import ballarch.kernel.RequestContext
import ballarch.kernel.RequestId
import ballarch.kernel.Step
import ballarch.kernel.Topic
import ballarch.kernel.accept
import ballarch.kernel.acceptLater
import ballarch.kernel.outcomeOf
import ballarch.kernel.payloadOf
import ballarch.kernel.reject
import ballarch.kernel.step

// ------------------------------------------------------------------ Counter

sealed interface CounterMsg {
    data object Increment : CounterMsg
    data object Get : CounterMsg
    data object Explode : CounterMsg
}

sealed interface CounterReply {
    data class Value(val value: Int) : CounterReply
    data object LimitReached : CounterReply
}

val CounterPort = BallPort<CounterMsg, CounterReply>("counter")
val CounterChanged = Topic<Int>("counter.changed")

class CounterBall(private val limit: Int = 3) : Ball<Int, CounterMsg, CounterReply> {
    override val type = "counter"
    override fun initial(key: String) = 0

    override fun decide(state: Int, request: CounterMsg, ctx: RequestContext): Decision<Int, CounterReply> = when (request) {
        CounterMsg.Increment ->
            if (state >= limit) reject(CounterReply.LimitReached)
            else accept(state + 1, CounterReply.Value(state + 1), Publish(CounterChanged, state + 1))
        CounterMsg.Get -> accept(state, CounterReply.Value(state))
        CounterMsg.Explode -> error("boom")
    }
}

// ------------------------------------------------------------------ Payer: one external, non-idempotent call

val PaymentPort = AdapterPort<Int, String>("payment", EffectClass.NonIdempotent)

sealed interface PayerState {
    data object Idle : PayerState
    data class Paying(val key: CallKey, val requestId: RequestId, val amount: Int) : PayerState
    data class Finished(val outcome: String) : PayerState
}

data class Pay(val amount: Int, val timeoutMillis: Long = 500)
data object CancelPayment

class PayerBall(
    private val port: AdapterPort<Int, String> = PaymentPort,
    override val type: String = "payer",
) : Ball<PayerState, Any, String> {
    override fun initial(key: String): PayerState = PayerState.Idle

    override fun decide(state: PayerState, request: Any, ctx: RequestContext): Decision<PayerState, String> = when (request) {
        is Pay -> {
            if (state is PayerState.Paying) {
                reject("busy")
            } else {
                val key = ctx.newKey()
                acceptLater(PayerState.Paying(key, ctx.requestId, request.amount), Call(key, port, request.amount, request.timeoutMillis))
            }
        }
        CancelPayment -> if (state is PayerState.Paying) accept(state, "cancelling", Cancel(state.key)) else reject("nothing to cancel")
        else -> reject("unknown request")
    }

    override fun complete(state: PayerState, completion: Completion<*>, ctx: Context): Step<PayerState, String> {
        val paying = state as? PayerState.Paying ?: return step(state)
        if (completion.key != paying.key) return step(state)
        val text = when (val o = completion.outcomeOf(port)!!) {
            is Outcome.Done -> "paid:${o.value}"
            is Outcome.NotDone -> "not-paid:${o.reason::class.simpleName}"
            is Outcome.Unknown -> "unknown:${o.reason}"
        }
        return step(PayerState.Finished(text), Reply(paying.requestId, text))
    }
}

// ------------------------------------------------------------------ Repeater: pays again after Unknown, naming one intent

sealed interface RepeatState {
    data object Idle : RepeatState

    /** [uncertain]: an earlier call of this payment ended `Unknown`, so a later `NotDone` does not show that nothing was paid. */
    data class Paying(
        val key: CallKey,
        val intent: Intent?,
        val requestId: RequestId,
        val pay: Pay,
        val calls: Int,
        val uncertain: Boolean,
    ) : RepeatState

    data class Finished(val outcome: String) : RepeatState
}

/**
 * S8: pays through an Idempotent port and, after an `Unknown` outcome, pays
 * again, making at most [maxCalls] calls. With [sameIntent] every call names
 * the intent issued for the payment, so they carry one idempotency key and a
 * deduplicating destination applies at most one of them. Without it no call
 * names an intent (the negative control).
 */
class RepeaterBall(
    private val port: AdapterPort<Int, String>,
    private val sameIntent: Boolean = true,
    private val maxCalls: Int = 2,
) : Ball<RepeatState, Pay, String> {
    override val type = "repeater"

    override fun initial(key: String): RepeatState = RepeatState.Idle

    override fun decide(state: RepeatState, request: Pay, ctx: RequestContext): Decision<RepeatState, String> {
        if (state is RepeatState.Paying) return reject("busy")
        val intent = if (sameIntent) ctx.newIntent() else null
        val key = ctx.newKey()
        return acceptLater(
            RepeatState.Paying(key, intent, ctx.requestId, request, calls = 1, uncertain = false),
            Call(key, port, request.amount, request.timeoutMillis, intent = intent),
        )
    }

    override fun complete(state: RepeatState, completion: Completion<*>, ctx: Context): Step<RepeatState, String> {
        val paying = state as? RepeatState.Paying ?: return step(state)
        if (completion.key != paying.key) return step(state)
        val outcome = completion.outcomeOf(port)!!
        if (outcome is Outcome.Unknown && paying.calls < maxCalls) {
            val key = ctx.newKey()
            return step(
                paying.copy(key = key, calls = paying.calls + 1, uncertain = true),
                Call(key, port, paying.pay.amount, paying.pay.timeoutMillis, intent = paying.intent),
            )
        }
        val text = when (outcome) {
            is Outcome.Done -> "paid:${outcome.value}"
            is Outcome.NotDone -> if (paying.uncertain) "unknown:after-repeat" else "not-paid:${outcome.reason::class.simpleName}"
            is Outcome.Unknown -> "unknown:${outcome.reason}"
        }
        return step(RepeatState.Finished(text), Reply(paying.requestId, text))
    }
}

// ------------------------------------------------------------------ Relay: a Ball that calls the Counter Ball

sealed interface RelayState {
    data object Idle : RelayState
    data class Waiting(val key: CallKey, val requestId: RequestId) : RelayState
    data class Done(val text: String) : RelayState
}

data class Forward(val counterKey: String, val message: CounterMsg)

class RelayBall : Ball<RelayState, Forward, String> {
    override val type = "relay"
    override fun initial(key: String): RelayState = RelayState.Idle

    override fun decide(state: RelayState, request: Forward, ctx: RequestContext): Decision<RelayState, String> {
        val key = ctx.newKey()
        return acceptLater(RelayState.Waiting(key, ctx.requestId), Call(key, CounterPort, request.message, 1_000, target = request.counterKey))
    }

    override fun complete(state: RelayState, completion: Completion<*>, ctx: Context): Step<RelayState, String> {
        val waiting = state as? RelayState.Waiting ?: return step(state)
        val text = when (val o = completion.outcomeOf(CounterPort)!!) {
            is Outcome.Done -> "done:${o.value}"
            is Outcome.NotDone -> "not-done:${o.reason}"
            is Outcome.Unknown -> "unknown:${o.reason}"
        }
        return step(RelayState.Done(text), Reply(waiting.requestId, text))
    }
}

// ------------------------------------------------------------------ Audit: subscribes to counter changes

class AuditBall : Ball<List<Int>, Unit, Unit> {
    override val type = "audit"
    override fun initial(key: String): List<Int> = emptyList()
    override fun decide(state: List<Int>, request: Unit, ctx: RequestContext): Decision<List<Int>, Unit> = accept(state, Unit)
    override fun observe(state: List<Int>, notice: Notice<*>, ctx: Context): Step<List<Int>, Unit> {
        val value = notice.payloadOf(CounterChanged) ?: return step(state)
        return step(state + value)
    }
}
