package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.EffectClass
import ballarch.kernel.Topic

/**
 * The composition root: the one place that states which Ball types exist,
 * which adapters implement which ports, and who subscribes to which topics.
 * Nothing is discovered at run time; unbound ports are rejected when a
 * decision tries to use them.
 */
public class Composition {
    internal val balls = LinkedHashMap<String, Ball<Any?, Any?, Any?>>()
    internal val adapters = LinkedHashMap<String, BoundAdapter>()
    internal val subscriptions = LinkedHashMap<String, MutableList<Subscription>>()

    @Suppress("UNCHECKED_CAST")
    public fun <S, M, R> ball(ball: Ball<S, M, R>): Composition = apply {
        require(ball.type !in balls) { "Ball type '${ball.type}' is registered twice" }
        require(ball.stateVersion >= 1) { "Ball type '${ball.type}' has state version ${ball.stateVersion}; versions start at 1" }
        balls[ball.type] = ball as Ball<Any?, Any?, Any?>
    }

    /**
     * Bind [adapter] to [port]. [retry] applies only to Safe and Idempotent ports: a
     * NonIdempotent port takes neither more than one attempt nor an attempt timeout.
     * [maxInFlight] bounds concurrent attempts, and further attempts wait for a free
     * slot: a new call in phase Pending, a retry or a re-send after a restart in phase Sending.
     *
     * [dedupWindowMillis] declares the deduplication window of an Idempotent port:
     * how long its destination is relied on to deduplicate an idempotency key after
     * the key's first use, less the longest time a request can stay in transit.
     * With a window, the runtime refuses, as a fault, a call whose deadline is
     * later than the window after the decision that issued its intent, or after
     * its own commit if it names none.
     *
     * [reconcile] declares reconciliation of an Idempotent or NonIdempotent port:
     * a call left without an answer is settled by a read of the declared status
     * port after the destination's delivery horizon ([Reconciliation]). The status
     * port must be bound in this composition too.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <Req, Res> adapter(
        port: AdapterPort<Req, Res>,
        adapter: Adapter<Req, Res>,
        retry: RetryPolicy = RetryPolicy.None,
        maxInFlight: Int = 64,
        dedupWindowMillis: Long? = null,
        reconcile: Reconciliation<Req, Res, *, *>? = null,
    ): Composition = apply {
        require(port.name !in adapters) { "port '${port.name}' is bound twice" }
        require(maxInFlight >= 1) { "maxInFlight must be at least 1" }
        require(retry.maxAttempts == 1 || port.effect != EffectClass.NonIdempotent) {
            "port '${port.name}' is NonIdempotent and cannot be retried"
        }
        require(retry.attemptTimeoutMillis == null || port.effect != EffectClass.NonIdempotent) {
            "port '${port.name}' is NonIdempotent and cannot have an attempt timeout"
        }
        require(dedupWindowMillis == null || dedupWindowMillis > 0) { "dedupWindowMillis must be positive" }
        require(dedupWindowMillis == null || port.effect == EffectClass.Idempotent) {
            "port '${port.name}' is ${port.effect}; only an Idempotent port declares a deduplication window"
        }
        require(reconcile == null || port.effect != EffectClass.Safe) {
            "port '${port.name}' is Safe; only an Idempotent or NonIdempotent port declares reconciliation"
        }
        adapters[port.name] = BoundAdapter(
            port, adapter as Adapter<Any?, Any?>, retry, maxInFlight, dedupWindowMillis, reconcile as Reconciliation<Any?, Any?, Any?, Any?>?,
        )
    }

    /** Deliver notices of [topic] to instances of [subscriberType]; [route] picks the instance key. */
    @Suppress("UNCHECKED_CAST")
    public fun <T> subscribe(topic: Topic<T>, subscriberType: String, route: (T) -> String): Composition = apply {
        subscriptions.getOrPut(topic.name) { mutableListOf() } += Subscription(subscriberType, route as (Any?) -> String)
    }

    internal fun validate() {
        for ((topic, subs) in subscriptions) {
            for (s in subs) require(s.subscriberType in balls) { "topic '$topic' routes to unknown Ball type '${s.subscriberType}'" }
        }
        for (bound in adapters.values) {
            val status = bound.reconcile?.status ?: continue
            require(adapters[status.name]?.port?.effect == EffectClass.Safe) {
                "port '${bound.port.name}' reconciles through '${status.name}', which the composition does not bind as a Safe port"
            }
        }
    }
}

internal class BoundAdapter(
    val port: AdapterPort<*, *>,
    val adapter: Adapter<Any?, Any?>,
    val retry: RetryPolicy,
    val maxInFlight: Int,
    val dedupWindowMillis: Long? = null,
    val reconcile: Reconciliation<Any?, Any?, Any?, Any?>? = null,
) {
    /**
     * The time by which an attempt of [call] ends without an answer if it has no
     * report, and after which no attempt is sent: the call's deadline less the
     * horizon and the status timeout. `null` without reconciliation.
     */
    fun cutoff(call: CallRecord): Long? = reconcile?.let { call.deadlineAt - it.horizonMillis - it.statusTimeoutMillis }
}

internal class Subscription(val subscriberType: String, val route: (Any?) -> String)

/** Finite bounds enforced by the runtime (rule R11). Exceeding one refuses new work before acceptance. */
public data class Limits(
    val maxInbox: Int = 1_024,
    val maxOpenCalls: Int = 256,
    val maxOutputsPerDecision: Int = 64,
    val maxTimeoutMillis: Long = 10 * 60 * 1_000L,
    val maxOutbox: Int = 1_024,
    /**
     * Answered inbound requests kept per instance to deduplicate repeats. A request on a Ball port is kept,
     * answered or not, until its call's deadline, since the caller may re-send it until then; while this many
     * are kept, the instance refuses another with `NotDone(Overloaded)`.
     */
    val repliedRetention: Int = 1_024,
    val repliedRetentionMillis: Long = 60 * 60 * 1_000L,
    /** Delay before retrying delivery of a notice to a full inbox. */
    val noticeRetryMillis: Long = 10,
) {
    init {
        require(maxInbox > 0 && maxOpenCalls > 0 && maxOutputsPerDecision > 0 && maxTimeoutMillis > 0 && maxOutbox > 0)
        require(repliedRetention >= 0 && repliedRetentionMillis >= 0 && noticeRetryMillis > 0)
    }
}
