package ballarch.testkit

import ballarch.kernel.AdapterPort
import ballarch.kernel.BallId
import ballarch.kernel.CallId
import ballarch.kernel.CallKey
import ballarch.kernel.EffectClass
import ballarch.runtime.Adapter
import ballarch.runtime.AdapterResult
import ballarch.runtime.Invocation
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** How a controllable test destination treats the next request. */
public enum class DestinationFate {
    /** Apply and answer normally. */
    Answer,

    /** Apply, then answer only after the adapter's deadline. */
    AnswerLate,

    /** Apply, then drop the connection without an answer. */
    ApplyThenDisconnect,

    /** Apply, then answer with a server error. */
    ApplyThenServerError,

    /** Refuse without applying (for example a validation error). */
    RejectWithoutApplying,

    /** Be unreachable: the connection is refused before anything is written. */
    Unreachable,
}

/**
 * A destination whose behaviour the conformance kit controls and whose ground
 * truth it can read. Implementations wrap a real server (for example an HTTP
 * server in a test) or a fake.
 */
public interface ControllableDestination {
    public fun behave(fate: DestinationFate)

    /** Number of times the request identified by [key] reached the destination. */
    public fun received(key: String): Int

    /** Number of times the effect of [key] was applied. */
    public fun applied(key: String): Int

    /** The idempotency key the destination saw for [key], if the adapter sent one. */
    public fun idempotencyKeySeen(key: String): String?
}

/**
 * Checks an adapter against the adapter contract of Core 2 (rules R7, R8 and
 * specification §9.3) by driving it once per [DestinationFate]:
 *
 * - C1: at most one report per invocation;
 * - C2: no internal retry (at most one delivery per invocation);
 * - C3: `NotApplied`/`Refused` only when nothing was applied;
 * - C4: `Ok` only when the destination processed the request;
 * - C5: for Idempotent ports, the adapter passes the idempotency key it is
 *   given and creates none: the destination sees that key, and a second
 *   attempt with the same key, made once the first has settled and answered
 *   normally, applies nothing more at a destination that deduplicates keys.
 *
 * It waits in real time, so it can test adapters that use real I/O and threads.
 * Every check uses a fresh call identity and idempotency key, so one
 * destination can serve several checks without their counts mixing.
 */
public object AdapterConformance {
    private val runs = AtomicLong()

    public data class Report(val fate: DestinationFate, val results: List<String>, val violations: List<String>)

    public fun <Req, Res> check(
        port: AdapterPort<Req, Res>,
        adapter: Adapter<Req, Res>,
        destination: ControllableDestination,
        request: (key: String) -> Req,
        /** How the destination identifies a request (usually from the request payload or idempotency key). */
        keyOf: (CallId) -> String = { it.toString() },
        deadlineMillis: Long = 300,
        settleMillis: Long = 600,
        fates: List<DestinationFate> = DestinationFate.entries,
    ): List<Report> {
        val run = runs.incrementAndGet()
        return fates.map { fate -> checkOne(port, adapter, destination, request, keyOf, deadlineMillis, settleMillis, fate, run) }
    }

    private fun <Req, Res> checkOne(
        port: AdapterPort<Req, Res>,
        adapter: Adapter<Req, Res>,
        destination: ControllableDestination,
        request: (key: String) -> Req,
        keyOf: (CallId) -> String,
        deadlineMillis: Long,
        settleMillis: Long,
        fate: DestinationFate,
        run: Long,
    ): Report {
        destination.behave(fate)
        val callId = CallId(BallId("conformance", "c"), CallKey("$run-${fate.name}"))
        val key = keyOf(callId)
        val idempotencyKey = "conformance$run-${fate.name}"
        fun attempt(n: Int): List<AdapterResult<Res>> {
            val reports = CopyOnWriteArrayList<AdapterResult<Res>>()
            val first = CountDownLatch(1)
            val invocation = Invocation(callId, request(key), n, System.currentTimeMillis() + deadlineMillis, idempotencyKey)
            try {
                adapter.execute(invocation) { r ->
                    reports += r
                    first.countDown()
                }
            } catch (e: Exception) {
                reports += AdapterResult.Failed("adapter threw ${e::class.simpleName}")
                first.countDown()
            }
            first.await(deadlineMillis + settleMillis, TimeUnit.MILLISECONDS)
            Thread.sleep(settleMillis) // late reports and late deliveries
            return reports
        }
        val reports = attempt(1)
        val v = ArrayList<String>()
        val received = destination.received(key)
        val applied = destination.applied(key)
        if (reports.size > 1) v += "C1: ${reports.size} reports for one invocation: $reports"
        if (received > 1) v += "C2: request reached the destination $received times (adapter retried)"
        for (r in reports) {
            when (r) {
                is AdapterResult.NotApplied, is AdapterResult.Refused ->
                    if (applied > 0) v += "C3: reported ${r::class.simpleName} but the effect was applied $applied time(s)"
                is AdapterResult.Ok -> if (received == 0) v += "C4: reported Ok but the destination never received the request"
                is AdapterResult.Failed -> Unit
            }
        }
        if (port.effect == EffectClass.Idempotent) {
            val seen = destination.idempotencyKeySeen(key)
            if (received > 0 && seen != idempotencyKey) v += "C5: the destination saw idempotency key $seen, not the supplied $idempotencyKey"
            destination.behave(DestinationFate.Answer)
            attempt(2)
            val total = destination.applied(key)
            if (total > 1) v += "C5: a second attempt with idempotency key $idempotencyKey applied the effect again ($total times in all)"
        }
        return Report(fate, reports.map { it::class.simpleName ?: "?" }, v)
    }
}
