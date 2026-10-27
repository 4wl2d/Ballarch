package ballarch.runtime

import ballarch.kernel.Ball
import ballarch.kernel.BallId
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.Notice
import ballarch.kernel.Outcome
import ballarch.kernel.RequestContext
import ballarch.kernel.Step
import ballarch.kernel.step

/**
 * A scheduler under direct test control: ready tasks and timers are run only
 * when the test says so, so a test can place an event exactly at a boundary
 * (for example process a request at the instant its deadline expires).
 */
class ManualScheduler(private var time: Long = 1_000) : Scheduler {
    private class Timed(val at: Long, val seq: Long, val body: () -> Unit) {
        var cancelled = false
    }

    private val ready = ArrayDeque<() -> Unit>()
    private val timers = ArrayList<Timed>()
    private var seq = 0L

    override fun now(): Long = time

    override fun execute(task: () -> Unit) {
        ready.addLast(task)
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val t = Timed(time + delayMillis.coerceAtLeast(0), seq++, task)
        timers += t
        return Cancellable { t.cancelled = true }
    }

    /** Runs up to [max] ready tasks at the current time, without firing timers. */
    fun runReady(max: Int = Int.MAX_VALUE): Int {
        var n = 0
        while (n < max && ready.isNotEmpty()) {
            ready.removeFirst()()
            n++
        }
        return n
    }

    /** Moves the clock forward without running anything. */
    fun jumpTo(t: Long) {
        check(t >= time) { "time cannot go back" }
        time = t
    }

    /**
     * Fires the timers due up to [t] in order, running ready tasks after each,
     * and leaves the clock at [t]. With [readyFirst], ready tasks run first.
     */
    fun advanceTo(t: Long, readyFirst: Boolean = true) {
        if (readyFirst) runReady()
        while (true) {
            val next = timers.filter { !it.cancelled && it.at <= t }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            timers.remove(next)
            time = maxOf(time, next.at)
            next.body()
            runReady()
        }
        time = maxOf(time, t)
    }

    fun advanceBy(millis: Long) = advanceTo(time + millis)

    /** Time of the earliest live timer, if any. */
    fun nextTimerAt(): Long? = timers.filter { !it.cancelled }.minOfOrNull { it.at }
}

/** An adapter whose invocations the test answers by hand. */
class ScriptedAdapter<Req, Res>(private val onSend: (Invocation<Req>) -> Unit = {}) : Adapter<Req, Res> {
    class Sent<Req, Res>(val invocation: Invocation<Req>, val report: (AdapterResult<Res>) -> Unit) {
        var cancelled = false
    }

    val sent = ArrayList<Sent<Req, Res>>()

    override fun execute(invocation: Invocation<Req>, done: (AdapterResult<Res>) -> Unit): Cancellable {
        val s = Sent(invocation, done)
        sent += s
        onSend(invocation)
        return Cancellable { s.cancelled = true }
    }
}

typealias Log = List<Any?>

/** A request that carries its own decision; lets one test Ball express any decision. */
class Act(private val label: String, val decide: (Log, RequestContext) -> Decision<Log, Any?>) {
    override fun toString(): String = label
}

/**
 * A Ball whose decisions come from the [Act] it receives. By default it
 * appends every completion and notice it receives to its state.
 */
class ScriptBall(
    override val type: String = "script",
    private val onComplete: (Log, Completion<*>, Context) -> Step<Log, Any?> = { s, c, _ -> step(s + c) },
    private val onObserve: (Log, Notice<*>, Context) -> Step<Log, Any?> = { s, n, _ -> step(s + n) },
) : Ball<Log, Any?, Any?> {
    override fun initial(key: String): Log = emptyList()

    override fun decide(state: Log, request: Any?, ctx: RequestContext): Decision<Log, Any?> = (request as Act).decide(state, ctx)

    override fun complete(state: Log, completion: Completion<*>, ctx: Context): Step<Log, Any?> = onComplete(state, completion, ctx)

    override fun observe(state: Log, notice: Notice<*>, ctx: Context): Step<Log, Any?> = onObserve(state, notice, ctx)
}

/** A runtime on a [ManualScheduler] with every external outcome recorded, and restarts on the same store. */
class Rig(
    val limits: Limits = Limits(),
    val store: MemoryStore = MemoryStore(),
    val scheduler: ManualScheduler = ManualScheduler(),
    private val wiring: () -> Composition,
) {
    val tracer = RecordingTracer()
    lateinit var runtime: Runtime
        private set

    /** Every outcome delivered for each request, in submission order; a request must get at most one. */
    val outcomes = ArrayList<MutableList<Outcome<Any?>>>()

    fun start(): Rig = apply {
        runtime = Runtime(wiring(), scheduler, store, limits, tracer)
        runtime.start()
        scheduler.runReady()
    }

    /** Crashes the runtime and starts a new one on the same store at time [at] (the Durable profile). */
    fun restartAt(at: Long): Rig = apply {
        runtime.crash()
        scheduler.jumpTo(at)
        start()
    }

    fun send(target: BallId, message: Any?, timeoutMillis: Long = 1_000, idempotencyKey: String? = null, run: Boolean = true): Int {
        val index = outcomes.size
        val received = ArrayList<Outcome<Any?>>()
        outcomes += received
        runtime.request<Any?, Any?>(target, message, timeoutMillis, idempotencyKey) { received += it }
        if (run) scheduler.runReady()
        return index
    }

    fun outcome(index: Int): Outcome<Any?>? {
        val all = outcomes[index]
        check(all.size <= 1) { "request $index received ${all.size} outcomes: $all" }
        return all.singleOrNull()
    }

    fun log(id: BallId): Log = runtime.inspect(id) as Log

    fun completions(id: BallId): List<Completion<*>> = log(id).filterIsInstance<Completion<*>>()

    inline fun <reified E : TraceEvent> events(): List<E> = tracer.events.filterIsInstance<E>()
}
