package ballarch.testkit

import ballarch.kernel.AdapterPort
import ballarch.kernel.CallId
import ballarch.kernel.EffectClass
import ballarch.runtime.Adapter
import ballarch.runtime.AdapterResult
import ballarch.runtime.Cancellable
import ballarch.runtime.Invocation

/**
 * What really happened at simulated destinations, independent of what any
 * caller believes. Oracles compare callers' outcomes against it.
 */
public class GroundTruth {
    /**
     * Identity epoch. Durable runtimes keep call identities across restarts (epoch
     * stays 0); a Transient restart starts a new world whose call identities may
     * repeat, so the harness increments the epoch and ground truth is kept per epoch.
     */
    public var epoch: Int = 0
    private val byEpoch = HashMap<Int, EpochTruth>()

    public fun at(epoch: Int): EpochTruth = byEpoch.getOrPut(epoch) { EpochTruth() }

    public val current: EpochTruth get() = at(epoch)

    /** Ground truth of epoch 0 (the only epoch for Durable runs). */
    public val applied: MutableMap<CallId, Int> get() = at(0).applied
    public val processed: MutableSet<CallId> get() = at(0).processed
    public val deliveries: MutableMap<CallId, Int> get() = at(0).deliveries
    public val effectOf: MutableMap<CallId, EffectClass> get() = at(0).effectOf

    public fun appliedCount(id: CallId): Int = applied[id] ?: 0

    /**
     * What the simulated Idempotent destinations remember: the answer to the
     * first delivery of each deduplication key, by port. It survives restarts
     * of the runtime, as a real destination's memory does, so a key that
     * repeats in a later epoch is answered from it.
     */
    internal val remembered: MutableMap<Pair<String, String>, Remembered> = HashMap()

    /**
     * The answer of the first application of each idempotency key, by port:
     * what a destination's status endpoint reports ([SimPort.status]). Like
     * [remembered], it survives restarts of the runtime.
     */
    internal val applications: MutableMap<Pair<String, String>, Remembered> = HashMap()

    internal class Remembered(val answer: Any?, val firstUsedAt: Long)
}

public class EpochTruth {
    /** Effect applications per call. Safe ports never apply effects. */
    public val applied: MutableMap<CallId, Int> = LinkedHashMap()

    /** Requests the destination actually handled, whatever the effect class. */
    public val processed: MutableSet<CallId> = LinkedHashSet()

    /** Requests that reached the destination (including duplicates). */
    public val deliveries: MutableMap<CallId, Int> = LinkedHashMap()

    /** Effect class of each call's port, recorded on first delivery attempt. */
    public val effectOf: MutableMap<CallId, EffectClass> = LinkedHashMap()

    public fun appliedCount(id: CallId): Int = applied[id] ?: 0
}

/** The fate of one attempt in the simulated network and destination. */
public enum class Fate {
    /** The destination applies the request and answers in time. */
    Reply,

    /** The destination applies the request; the answer arrives after the deadline. */
    ReplyLate,

    /** The request is lost before reaching the destination. */
    LoseRequest,

    /** The destination applies the request; the answer is lost. */
    LoseReply,

    /** The destination applies the request; the connection then fails with an error. */
    ErrorAfterApply,

    /** The connection is refused before the request is written: provably nothing applied. */
    RefuseConnection,

    /** The destination refuses the request without applying it. */
    Refuse,

    /**
     * The request is held up in the network and reaches the destination after
     * the call's deadline, by `lateDeliveryMillis`; no answer arrives. It may
     * land after the caller has given up on it, for example after a
     * compensation, which tests whether that compensation is absorbing. Not in
     * [classic]: explorations opt in.
     */
    DeliverLate,

    /**
     * The request reaches the destination later than the destination's delivery
     * horizon allows, by `lateDeliveryMillis`, and no answer arrives. A status
     * read made after the horizon but before the request lands misses it, so a
     * reconciled port reports `NotDone` for an effect that takes place later: a
     * destination that breaks the horizon it declared, for the negative control
     * of reconciliation. Needs the [SimPort]'s `horizonMillis`. Not in [classic].
     */
    ApplyAfterHorizon,
    ;

    public companion object {
        /** Fates in which a request lands no later than its answer would: the default for [SimPort]. */
        public val classic: List<Fate> = listOf(Reply, ReplyLate, LoseRequest, LoseReply, ErrorAfterApply, RefuseConnection, Refuse)
    }
}

/**
 * A simulated adapter plus destination. Each attempt's fate is taken from the
 * [chooser]. When the port is [EffectClass.Idempotent], the destination
 * deduplicates deliveries by [dedupKey], like a provider honouring
 * idempotency keys: a delivery whose key it remembers is answered with the
 * first answer and applies nothing. It remembers keys across restarts of the
 * runtime ([GroundTruth]).
 *
 * @param lateDeliveryMillis how long after the call's deadline a [Fate.DeliverLate]
 *   request arrives. The default, ten network latencies, puts it after a
 *   compensation that the deadline triggers and sends at once.
 * @param dedupKey the key the destination deduplicates by: by default the
 *   idempotency key the runtime supplies, which an honest adapter passes on.
 * @param forgetKeysAfterMillis how long after a key's first use the destination
 *   still deduplicates it; `null`, the default, for ever. A destination that
 *   forgets a key before a call's deadline can apply a repeat again.
 * @param horizonMillis the delivery horizon the destination declares for
 *   [ballarch.runtime.Reconciliation]: every request that reaches it does so within this time of
 *   its send. Every fate but [Fate.DeliverLate] and [Fate.ApplyAfterHorizon]
 *   keeps a horizon of at least [latencyMillis].
 */
public class SimPort<Req, Res>(
    public val port: AdapterPort<Req, Res>,
    private val scheduler: SimScheduler,
    private val chooser: Chooser,
    private val truth: GroundTruth,
    private val fates: List<Fate> = Fate.classic,
    private val latencyMillis: Long = 10,
    private val lateDeliveryMillis: Long = 10 * latencyMillis,
    private val refuse: ((Req) -> Res)? = null,
    private val dedupKey: (Invocation<Req>) -> String = { it.idempotencyKey },
    private val forgetKeysAfterMillis: Long? = null,
    private val horizonMillis: Long? = null,
    private val handle: (Req) -> Res,
) : Adapter<Req, Res> {
    override fun execute(invocation: Invocation<Req>, done: (AdapterResult<Res>) -> Unit): Cancellable? {
        val allowed = if (refuse == null) fates - Fate.Refuse else fates
        val fate = allowed[chooser.choose(allowed.size, "${port.name}:${invocation.callId.key}#${invocation.attempt}")]
        val id = invocation.callId
        val memory = this.truth.remembered
        val applications = this.truth.applications
        val truth = truth.current // captured at invocation: late deliveries count in the sender's epoch
        truth.effectOf.putIfAbsent(id, port.effect)
        fun deliver(): Res {
            truth.deliveries.merge(id, 1, Int::plus)
            truth.processed += id
            val key = port.name to dedupKey(invocation)
            if (port.effect == EffectClass.Idempotent) {
                val remembered = memory[key]?.takeIf { forgetKeysAfterMillis == null || scheduler.now() - it.firstUsedAt <= forgetKeysAfterMillis }
                @Suppress("UNCHECKED_CAST")
                if (remembered != null) return remembered.answer as Res
            }
            if (port.effect != EffectClass.Safe) truth.applied.merge(id, 1, Int::plus)
            return handle(invocation.request).also {
                if (port.effect == EffectClass.Idempotent) memory[key] = GroundTruth.Remembered(it, scheduler.now())
                if (port.effect != EffectClass.Safe) {
                    applications.putIfAbsent(port.name to invocation.idempotencyKey, GroundTruth.Remembered(it, scheduler.now()))
                }
            }
        }
        when (fate) {
            Fate.Reply -> scheduler.schedule(latencyMillis) { done(AdapterResult.Ok(deliver())) }
            Fate.ReplyLate -> scheduler.schedule(latencyMillis) {
                val answer = deliver()
                scheduler.schedule((invocation.deadlineAt - scheduler.now()).coerceAtLeast(0) + latencyMillis) {
                    done(AdapterResult.Ok(answer))
                }
            }
            Fate.LoseRequest -> Unit
            Fate.LoseReply -> scheduler.schedule(latencyMillis) { deliver() }
            Fate.ErrorAfterApply -> scheduler.schedule(latencyMillis) {
                deliver()
                done(AdapterResult.Failed("connection reset after write"))
            }
            Fate.RefuseConnection -> scheduler.schedule(latencyMillis) { done(AdapterResult.NotApplied("connection refused")) }
            Fate.Refuse -> scheduler.schedule(latencyMillis) {
                truth.deliveries.merge(id, 1, Int::plus)
                truth.processed += id
                done(AdapterResult.Refused(refuse!!(invocation.request)))
            }
            Fate.DeliverLate ->
                scheduler.schedule((invocation.deadlineAt - scheduler.now()).coerceAtLeast(0) + lateDeliveryMillis) { deliver() }
            Fate.ApplyAfterHorizon ->
                scheduler.schedule(requireNotNull(horizonMillis) { "Fate.ApplyAfterHorizon needs horizonMillis" } + lateDeliveryMillis) { deliver() }
        }
        return null
    }

    /**
     * The destination's status endpoint: the adapter of a Safe status port of a
     * [ballarch.runtime.Reconciliation] whose query is the call's idempotency key. It answers
     * [answer] of the answer that the first application of a request with that
     * key returned, or of `null` if none was applied. It reads the destination
     * when the query arrives, [latencyMillis] after it was sent, so it is honest
     * while every request lands within the horizon. Each query's fate is chosen
     * from [fates]: [Fate.Reply] answers, and every other fate loses the query.
     * A status query is not a call, so it leaves no ground truth.
     */
    public fun <S> status(fates: List<Fate> = listOf(Fate.Reply, Fate.LoseRequest), answer: (Res?) -> S): Adapter<String, S> =
        Adapter { invocation, done ->
            val fate = fates[chooser.choose(fates.size, "${port.name}:status:${invocation.callId.key}")]
            if (fate == Fate.Reply) {
                scheduler.schedule(latencyMillis) {
                    @Suppress("UNCHECKED_CAST")
                    done(AdapterResult.Ok(answer(truth.applications[port.name to invocation.request]?.answer as Res?)))
                }
            }
            null
        }
}
