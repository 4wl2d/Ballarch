package ballarch.runtime

import ballarch.kernel.Accept
import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.BallId
import ballarch.kernel.BallPort
import ballarch.kernel.Call
import ballarch.kernel.CallId
import ballarch.kernel.CallKey
import ballarch.kernel.Caller
import ballarch.kernel.Cancel
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.Intent
import ballarch.kernel.Notice
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.Output
import ballarch.kernel.Port
import ballarch.kernel.Publish
import ballarch.kernel.Reject
import ballarch.kernel.Reply
import ballarch.kernel.ReplyPlan
import ballarch.kernel.RequestContext
import ballarch.kernel.RequestId
import ballarch.kernel.Step
import ballarch.kernel.Timer
import ballarch.kernel.Topic
import ballarch.kernel.UnknownReason
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The reference runtime for Ballarch Core 2.
 *
 * It enforces the kernel rules that do not depend on the Ball author:
 * serial processing per instance (R3), atomic commit (R4), commit before
 * dispatch (R5), exactly one completion per call (R6), outcome classification
 * by send point and effect class (R7), runtime-owned bounded retries with no
 * re-send of non-idempotent calls (R8), reply accounting (R9), and bounds (R11).
 * It derives every call's idempotency key (R8), serves reads that commit
 * nothing (R12), and stops when its store reports that a later runtime took
 * the store over (R16). On a port whose binding declares [Reconciliation], it
 * settles a call left without an answer by a status read after the
 * destination's delivery horizon.
 *
 * Every method except [inspect], [record] and [failure] may be called from
 * any thread; work is executed on the [scheduler]'s loop. Those three read the
 * loop's own view and are meant for tests and single-threaded simulations.
 *
 * A Durable store may hold records committed by an earlier version of the
 * application. Construction reads every record and, before writing anything,
 * throws [IncompatibleStoreException] if one cannot be read or the
 * composition cannot serve it: a record of an unregistered Ball type, a state
 * version its Ball type cannot [migrate][Ball.migrate], an open call on a port
 * that is no longer bound, or an undelivered notice for a subscription that no
 * longer exists. A call is classified by the effect class recorded when it
 * was committed, never by the port's current binding.
 *
 * Construction then takes a new incarnation from the store ([Store.nextIncarnation]).
 * A store that excludes a second runtime, such as [FileStore], throws
 * [StoreLockedException] if another runtime holds it, and the runtime refuses
 * to start. A store that fences, such as [MemoryStore], instead refuses every
 * later write of the runtime it replaced: that runtime then stops without
 * dispatching anything from the refused write ([fenced]). Until then the
 * replaced runtime may still commit, so construction checks the records again
 * once it holds the incarnation. That refusal writes no record, but the
 * incarnation is taken, so the replaced runtime is fenced.
 *
 * @param keyspaces issues the keyspace of each record the runtime creates: the
 *   prefix of the idempotency keys of that instance's calls. Each value must
 *   differ from every value issued before, also by earlier runtimes whose calls
 *   reached the same destinations, and be 1 to 32 printable ASCII characters.
 *   The default draws 128 random bits.
 */
public class Runtime(
    composition: Composition,
    private val scheduler: Scheduler,
    private val store: Store,
    private val limits: Limits = Limits(),
    private val tracer: Tracer = Tracer.None,
    private val keyspaces: () -> String = ::randomKeyspace,
) {
    private val balls = composition.balls.toMap()
    private val adapters: Map<String, BoundAdapter> = composition.adapters.toMap().let { bound ->
        if (Timer.port.name in bound) bound
        // A timer is Safe: after a Durable restart it is re-armed (a new attempt) to its original due time.
        else bound + (Timer.port.name to BoundAdapter(Timer.port, timerAdapter(scheduler), RetryPolicy(maxAttempts = Int.MAX_VALUE), Int.MAX_VALUE))
    }
    private val subscriptions = composition.subscriptions.mapValues { it.value.toList() }

    init {
        composition.validate()
        val problems = storeProblems()
        if (problems.isNotEmpty()) throw IncompatibleStoreException(problems)
    }

    /** Distinguishes runtime starts; stale callbacks from an earlier incarnation are ignored. */
    public val incarnation: Long = store.nextIncarnation()

    init {
        // A runtime replaced on a fencing store commits until the incarnation above is taken.
        val late = storeProblems()
        if (late.isNotEmpty()) throw IncompatibleStoreException(late)
    }

    @Volatile private var alive = true
    @Volatile private var fencedBy: FencedException? = null
    private var started = false
    private var externalSeq = 0L
    private val instances = HashMap<BallId, Instance>()
    private val inFlight = HashMap<String, Int>()
    private val waiting = HashMap<String, ArrayDeque<CallId>>()
    private val watchers = HashMap<BallId, MutableList<(Snapshot<Any?>) -> Unit>>()

    /** The last committed state of each instance this runtime loaded or committed; read from any thread. */
    private val published = ConcurrentHashMap<BallId, Snapshot<Any?>>()

    // ---------------------------------------------------------------- public API

    /** Recover open work from the store and begin processing. Call once. */
    public fun start() {
        onLoop {
            check(!started) { "runtime already started" }
            started = true
            recover()
        }
    }

    /**
     * Simulate an abrupt crash: nothing more is processed and every pending
     * callback is ignored. Durable state is whatever the store already holds.
     * A store that excludes other runtimes stays held, as by a process that
     * still runs: close a [FileStore] to let a successor take it over.
     */
    public fun crash() {
        alive = false
    }

    /**
     * Submit a request from outside the runtime (UI, HTTP handler, test).
     *
     * With an [idempotencyKey], repeats with an equal message join or replay the
     * original, and a repeat with a different message is refused with
     * [NotDoneReason.IdempotencyConflict].
     */
    public fun <M, R> request(
        target: BallId,
        message: M,
        timeoutMillis: Long,
        idempotencyKey: String? = null,
        caller: Caller = Caller.Anonymous,
        onOutcome: (Outcome<R>) -> Unit,
    ) {
        require(timeoutMillis > 0) { "timeout must be positive" }
        @Suppress("UNCHECKED_CAST")
        val callback = onOutcome as (Outcome<Any?>) -> Unit
        onLoop {
            val inst = instanceOrNull(target)
            if (inst == null) {
                callback(Outcome.NotDone(NotDoneReason.NotApplied("unknown Ball type '${target.type}'")))
                return@onLoop
            }
            val requestId = idempotencyKey?.let { RequestId("idem:$it") } ?: RequestId("ext:$incarnation:${externalSeq++}")
            val waiter = ExternalWaiter(callback)
            val input = Input.Request(
                requestId = requestId,
                message = message,
                caller = caller,
                deadlineAt = scheduler.now() + timeoutMillis,
                replyTo = ReplyTarget.External,
                dedup = idempotencyKey != null,
                waiter = waiter,
            )
            waiter.timer = scheduler.schedule(timeoutMillis) { onLoopNow { externalDeadline(inst, input, waiter) } }
            // A repeat of a request the instance already accepted joins or replays it, even when the inbox is
            // full: refusing it would report NotDone for an operation that may have taken effect (Core §3.3).
            if (input.dedup && requestId in inst.record.inbound) {
                handleRequest(inst, input)
                return@onLoop
            }
            if (!inst.offer(input)) waiter.finish(refusal(inst))
        }
    }

    /** Observe committed states of [id]. The listener runs on the loop after each commit. */
    public fun watch(id: BallId, listener: (Any?) -> Unit) {
        onLoop { watchers.getOrPut(id) { mutableListOf() } += { listener(it.state) } }
    }

    /**
     * Observe the committed states of the instance [key] of [ball]'s type: the
     * listener receives the current [Snapshot] at once, then one after each
     * commit, in order of revision. It runs on the loop; a request it submits is
     * processed after the step that called it.
     */
    public fun <S> watch(ball: Ball<S, *, *>, key: String, listener: (Snapshot<S>) -> Unit) {
        val id = BallId(registered(ball).type, key)
        @Suppress("UNCHECKED_CAST")
        val typed = listener as (Snapshot<Any?>) -> Unit
        onLoop {
            typed(read(id))
            watchers.getOrPut(id) { mutableListOf() } += typed
        }
    }

    /**
     * Reads the last committed state of [id] and its revision, from any thread
     * (R12). An instance that never committed reads as its initial state at
     * revision 0. A read commits nothing, never enters the instance's inbox,
     * creates no record and requests no effect, so it neither waits behind a
     * step nor sees one half done: during a step it returns the state before it.
     *
     * @throws IllegalArgumentException if the composition registers no Ball type [BallId.type].
     */
    public fun read(id: BallId): Snapshot<Any?> {
        published[id]?.let { return it }
        val ball = requireNotNull(balls[id.type]) { "unknown Ball type '${id.type}'" }
        val record = store.load(id) ?: return Snapshot(id, 0, ball.initial(id.key))
        return Snapshot(id, record.revision, migrated(ball, record).state)
    }

    /** [read] typed by [ball], which names a registered Ball type and its state type. */
    public fun <S> read(ball: Ball<S, *, *>, key: String): Snapshot<S> {
        @Suppress("UNCHECKED_CAST")
        return read(BallId(registered(ball).type, key)) as Snapshot<S>
    }

    private fun registered(ball: Ball<*, *, *>): Ball<*, *, *> =
        ball.also { require(it.type in balls) { "Ball type '${it.type}' is not registered in the composition" } }

    /** Committed state of [id], or `null` if the type is unknown. For tests and simulations. */
    public fun inspect(id: BallId): Any? = instanceOrNull(id)?.record?.state

    /** The full committed record of [id], if the instance was ever addressed. For tests. */
    public fun record(id: BallId): InstanceRecord? = instances[id]?.record ?: store.load(id)

    /** Whether an instance stopped after a fault in a completion or notice handler. */
    public fun failure(id: BallId): String? = instances[id]?.failed

    /**
     * The store's refusal of a write, if a later runtime took the store over
     * (R16). This runtime then stopped: it commits, sends and answers nothing
     * more. `null` while it runs. Readable from any thread.
     */
    public val fenced: FencedException? get() = fencedBy

    // ---------------------------------------------------------------- internals: instances

    private sealed interface Input {
        data class Request(
            val requestId: RequestId,
            val message: Any?,
            val caller: Caller,
            val deadlineAt: Long,
            val replyTo: ReplyTarget,
            val dedup: Boolean,
            val waiter: ExternalWaiter? = null,
        ) : Input

        data class CompletionInput(val key: CallKey, val port: Port<*, *>, val outcome: Outcome<Any?>) : Input

        data class NoticeInput(val topic: String, val source: BallId, val sequence: Long, val payload: Any?) : Input
    }

    private class ExternalWaiter(val callback: (Outcome<Any?>) -> Unit) {
        var done = false
        var timer: Cancellable? = null

        fun finish(outcome: Outcome<Any?>) {
            if (done) return
            done = true
            timer?.cancel()
            callback(outcome)
        }
    }

    private inner class Instance(val id: BallId, val ball: Ball<Any?, Any?, Any?>, var record: InstanceRecord) {
        val inbox = ArrayDeque<Input>()
        var drainScheduled = false
        var deciding = false
        var failed: String? = null
        val resolved = HashSet<CallKey>()
        val deadlineTimers = HashMap<CallKey, Cancellable>()
        val attemptTimers = HashMap<CallKey, Cancellable>()
        val cancelHandles = HashMap<CallKey, Cancellable>()
        val currentAttempt = HashMap<CallKey, Int>()
        val statusQueries = HashMap<CallKey, StatusQuery>()
        val externalWaiters = HashMap<RequestId, MutableList<ExternalWaiter>>()
        val noticesInFlight = HashSet<Pair<Long, BallId>>()
        var outboxRetryScheduled = false

        fun offer(input: Input): Boolean {
            if (failed != null || inbox.size >= limits.maxInbox) return false
            inbox.addLast(input)
            scheduleDrain()
            return true
        }

        fun scheduleDrain() {
            if (drainScheduled || failed != null) return
            drainScheduled = true
            scheduler.execute { onLoopNow { drainOne(this) } }
        }
    }

    /** The status query of a call in phase Reconciling: the timer that starts it and then ends it, and the status adapter's handle. */
    private class StatusQuery {
        var timer: Cancellable? = null
        var handle: Cancellable? = null

        fun stop() {
            timer?.cancel()
            handle?.cancel()
        }
    }

    private fun instanceOrNull(id: BallId): Instance? {
        instances[id]?.let { return it }
        val ball = balls[id.type] ?: return null
        val stored = store.load(id)?.let { migrated(ball, it) }
        stored?.let { published[id] = Snapshot(id, it.revision, it.state) }
        val record = stored
            ?: InstanceRecord(id = id, state = ball.initial(id.key), revision = 0, stateVersion = ball.stateVersion, keyspace = newKeyspace())
        return Instance(id, ball, record).also { instances[id] = it }
    }

    private fun newKeyspace(): String = keyspaces().also { ks ->
        require(ks.length in 1..MAX_KEYSPACE && ks.all { it in '!'..'~' }) {
            "a keyspace must be 1 to $MAX_KEYSPACE printable ASCII characters, not '$ks'"
        }
    }

    /** [record] with its state in the shape [ball] commits now. The store keeps the old shape until the next commit. */
    private fun migrated(ball: Ball<Any?, Any?, Any?>, record: InstanceRecord): InstanceRecord =
        if (record.stateVersion == ball.stateVersion) record
        else record.copy(state = ball.migrate(record.stateVersion, record.state), stateVersion = ball.stateVersion)

    private fun drainOne(inst: Instance) {
        inst.drainScheduled = false
        if (inst.failed != null) return
        val input = inst.inbox.removeFirstOrNull() ?: return
        when (input) {
            is Input.Request -> handleRequest(inst, input)
            is Input.CompletionInput -> handleCompletion(inst, input)
            is Input.NoticeInput -> handleNotice(inst, input)
        }
        if (inst.inbox.isNotEmpty()) inst.scheduleDrain()
    }

    /** Runs a decider function under the serial-processing guard. Returns null on a fault. */
    private fun <T> decideGuarded(inst: Instance, what: String, block: () -> T): Result<T> {
        check(!inst.deciding) { "re-entrant decision on ${inst.id}" }
        inst.deciding = true
        tracer.record(TraceEvent.DecideStarted(scheduler.now(), inst.id, what))
        return try {
            Result.success(block())
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            inst.deciding = false
            tracer.record(TraceEvent.DecideFinished(scheduler.now(), inst.id))
        }
    }

    // ---------------------------------------------------------------- inputs

    private fun handleRequest(inst: Instance, input: Input.Request) {
        val now = scheduler.now()
        val existing = inst.record.inbound[input.requestId]
        if (existing != null) {
            when {
                input.replyTo == ReplyTarget.External && existing.message != input.message ->
                    input.waiter?.finish(Outcome.NotDone(NotDoneReason.IdempotencyConflict))
                existing.reply != null -> deliverReplyTo(inst, input, existing.reply)
                else -> input.waiter?.let { inst.externalWaiters.getOrPut(input.requestId) { mutableListOf() } += it }
            }
            return
        }
        if (input.waiter?.done == true) return // the external caller already timed out; withdrawn
        if (now > input.deadlineAt) {
            reject(inst, input, Outcome.NotDone(NotDoneReason.DeadlineBeforeSend))
            return
        }
        // A call on a Ball port may be re-sent until its deadline, and its entry is kept until then (pruneReplied).
        // So the retention bound limits how many such requests an instance holds, and one more is refused (R11).
        if (input.replyTo is ReplyTarget.ToCall && inst.record.inbound.values.count { awaitsResend(it, now) } >= limits.repliedRetention) {
            reject(inst, input, Outcome.NotDone(NotDoneReason.Overloaded))
            return
        }
        val ctx = RtRequestContext(inst.id, now, inst.record.revision + 1, input.requestId, input.caller)
        val result = decideGuarded(inst, "request ${input.requestId}") { inst.ball.decide(inst.record.state, input.message, ctx) }
        val decision: Decision<Any?, Any?> = result.getOrElse { e ->
            fault(inst, "decide threw ${e::class.simpleName}: ${e.message}")
            reject(inst, input, Outcome.NotDone(NotDoneReason.Fault("decide failed: ${e.message}")))
            return
        }
        when (decision) {
            is Reject -> reject(inst, input, Outcome.NotDone(NotDoneReason.Refused(decision.reason)))
            is Accept -> {
                val problem = preflight(inst, ctx.now, decision.outputs, currentRequest = input.requestId)
                if (problem != null) {
                    if (problem.overload) {
                        reject(inst, input, Outcome.NotDone(NotDoneReason.Overloaded))
                    } else {
                        fault(inst, problem.message)
                        reject(inst, input, Outcome.NotDone(NotDoneReason.Fault(problem.message)))
                    }
                    return
                }
                val immediate = (decision.reply as? ReplyPlan.Now)?.let { Outcome.Done(it.value) }
                val entry = InboundRecord(
                    requestId = input.requestId,
                    replyTo = input.replyTo,
                    message = input.message,
                    deadlineAt = input.deadlineAt,
                    reply = immediate,
                    repliedAt = immediate?.let { now },
                )
                val keepEntry = immediate == null || input.dedup
                input.waiter?.let { inst.externalWaiters.getOrPut(input.requestId) { mutableListOf() } += it }
                val committed = commit(inst, ctx, decision.state, decision.outputs, completed = null,
                    inboundEntry = if (keepEntry) entry else null, lastSeenUpdate = null,
                    immediateReply = input.requestId.takeIf { immediate != null })
                if (!committed) return
                if (immediate != null) {
                    deliverReply(inst, input.requestId, input.replyTo, immediate)
                } else {
                    scheduler.schedule((input.deadlineAt - now).coerceAtLeast(0)) {
                        onLoopNow { checkReplyOverdue(inst, input.requestId) }
                    }
                }
            }
        }
    }

    private fun handleCompletion(inst: Instance, input: Input.CompletionInput) {
        val call = inst.record.calls[input.key]
        if (call == null) {
            tracer.record(TraceEvent.LateReportDropped(scheduler.now(), CallId(inst.id, input.key), "Completion"))
            return
        }
        val ctx = RtContext(inst.id, scheduler.now(), inst.record.revision + 1)
        @Suppress("UNCHECKED_CAST")
        val completion = Completion(input.key, input.port as Port<*, Any?>, input.outcome)
        val result = decideGuarded(inst, "completion ${input.key}") { inst.ball.complete(inst.record.state, completion, ctx) }
        val step = result.getOrElse { e ->
            quarantine(inst, input, "complete threw ${e::class.simpleName}: ${e.message}")
            return
        }
        applyStep(inst, input, ctx, step, completed = input.key, lastSeenUpdate = null, completedOutcome = input.outcome)
    }

    private fun handleNotice(inst: Instance, input: Input.NoticeInput) {
        val seen = inst.record.lastSeen[input.source] ?: 0L
        if (input.sequence <= seen) {
            acknowledgeNotice(input.source, input.sequence, inst.id)
            return
        }
        val ctx = RtContext(inst.id, scheduler.now(), inst.record.revision + 1)
        val notice = Notice(Topic<Any?>(input.topic), input.source, input.sequence, input.payload)
        val result = decideGuarded(inst, "notice ${input.topic}#${input.sequence}") { inst.ball.observe(inst.record.state, notice, ctx) }
        val step = result.getOrElse { e ->
            quarantine(inst, input, "observe threw ${e::class.simpleName}: ${e.message}")
            return
        }
        if (applyStep(inst, input, ctx, step, completed = null, lastSeenUpdate = input.source to input.sequence)) {
            acknowledgeNotice(input.source, input.sequence, inst.id)
        }
    }

    private fun applyStep(
        inst: Instance,
        input: Input,
        ctx: RtContext,
        step: Step<Any?, Any?>,
        completed: CallKey?,
        lastSeenUpdate: Pair<BallId, Long>?,
        completedOutcome: Outcome<Any?>? = null,
    ): Boolean {
        val problem = preflight(inst, ctx.now, step.outputs, currentRequest = null, closing = completed)
        if (problem != null) {
            quarantine(inst, input, problem.message)
            return false
        }
        return commit(inst, ctx, step.state, step.outputs, completed, inboundEntry = null, lastSeenUpdate = lastSeenUpdate, completedOutcome = completedOutcome)
    }

    /** A completion or notice cannot be refused; a faulty handler stops the instance. */
    private fun quarantine(inst: Instance, input: Input, message: String) {
        fault(inst, message)
        inst.failed = message
        inst.inbox.addFirst(input)
    }

    private fun fault(inst: Instance, message: String) {
        tracer.record(TraceEvent.Fault(scheduler.now(), inst.id, message))
    }

    // ---------------------------------------------------------------- preflight (R6, R9, R11)

    private class Problem(val message: String, val overload: Boolean)

    private fun preflight(
        inst: Instance,
        now: Long,
        outputs: List<Output<Any?>>,
        currentRequest: RequestId?,
        closing: CallKey? = null,
    ): Problem? {
        if (outputs.size > limits.maxOutputsPerDecision) {
            return Problem("decision has ${outputs.size} outputs; limit is ${limits.maxOutputsPerDecision}", overload = true)
        }
        val openKeys = inst.record.calls.keys - setOfNotNull(closing)
        val newKeys = HashSet<CallKey>()
        val replied = HashSet<RequestId>()
        var notices = 0
        for (o in outputs) {
            when (o) {
                is Call<*, *> -> {
                    if (o.key in openKeys || !newKeys.add(o.key)) return Problem("call key ${o.key} is not unique", overload = false)
                    if (!isBound(o.port)) return Problem("port ${o.port.name} is not bound in the composition", overload = false)
                    if (o.port.name == Timer.port.name && (o.request !is Long || o.timeoutMillis != (o.request as Long) + Timer.MARGIN_MILLIS)) {
                        return Problem("calls on ${Timer.port.name} must be made with Timer.after", overload = false)
                    }
                    if (o.timeoutMillis > limits.maxTimeoutMillis) {
                        return Problem("timeout ${o.timeoutMillis} ms exceeds ${limits.maxTimeoutMillis} ms", overload = false)
                    }
                    val bound = adapters[o.port.name]?.takeIf { o.port is AdapterPort<*, *> }
                    if (o.intent != null && bound?.port?.effect != EffectClass.Idempotent) {
                        return Problem("call ${o.key} names an intent, but ${o.port.name} is not an Idempotent adapter port", overload = false)
                    }
                    val window = bound?.dedupWindowMillis
                    val from = o.intent?.issuedAt ?: now
                    if (window != null && now + o.timeoutMillis - from > window) {
                        val since = if (o.intent != null) "its intent was issued" else "its commit"
                        return Problem(
                            "call ${o.key} on ${o.port.name} ends ${now + o.timeoutMillis - from} ms after $since, " +
                                "beyond the port's deduplication window of $window ms",
                            overload = false,
                        )
                    }
                    val reconcile = bound?.reconcile
                    if (reconcile != null && o.timeoutMillis <= reconcile.horizonMillis + reconcile.statusTimeoutMillis) {
                        return Problem(
                            "call ${o.key} on ${o.port.name} has a timeout of ${o.timeoutMillis} ms, which must exceed the port's delivery " +
                                "horizon and status timeout together, ${reconcile.horizonMillis + reconcile.statusTimeoutMillis} ms",
                            overload = false,
                        )
                    }
                }
                is Cancel -> if (o.key !in openKeys) return Problem("cancel of unknown or closed call ${o.key}", overload = false)
                is Reply -> {
                    val open = inst.record.inbound[o.to]
                    if (o.to == currentRequest) return Problem("reply to the current request must use ReplyPlan.Now", overload = false)
                    if (open == null || open.reply != null) return Problem("reply to ${o.to}, which is not an open request", overload = false)
                    if (!replied.add(o.to)) return Problem("two replies to ${o.to}", overload = false)
                }
                is Publish<*> -> notices++
            }
        }
        if (openKeys.size + newKeys.size > limits.maxOpenCalls) {
            return Problem("open calls would exceed ${limits.maxOpenCalls}", overload = true)
        }
        if (inst.record.outbox.size + notices > limits.maxOutbox) {
            return Problem("notice outbox would exceed ${limits.maxOutbox}", overload = true)
        }
        return null
    }

    private fun isBound(port: Port<*, *>): Boolean = when (port) {
        is AdapterPort<*, *> -> port.name in adapters
        is BallPort<*, *> -> port.ballType in balls
    }

    // ---------------------------------------------------------------- commit (R4) and dispatch (R5)

    /** Commits a decision and dispatches its outputs. Returns false, having dispatched nothing, if the store fenced this runtime. */
    private fun commit(
        inst: Instance,
        ctx: Context,
        newState: Any?,
        outputs: List<Output<Any?>>,
        completed: CallKey?,
        inboundEntry: InboundRecord?,
        lastSeenUpdate: Pair<BallId, Long>?,
        completedOutcome: Outcome<Any?>? = null,
        immediateReply: RequestId? = null,
    ): Boolean {
        val now = ctx.now
        val old = inst.record
        val calls = LinkedHashMap(old.calls)
        if (completed != null) calls.remove(completed)
        val inbound = LinkedHashMap(old.inbound)
        if (inboundEntry != null) inbound[inboundEntry.requestId] = inboundEntry
        val outbox = old.outbox.toMutableList()
        var nextSeq = old.nextNoticeSequence
        val newCalls = ArrayList<CallKey>()
        val cancels = ArrayList<CallKey>()
        val replies = ArrayList<Pair<RequestId, Outcome<Any?>>>()
        val newNotices = ArrayList<NoticeRecord>()
        for (o in outputs) {
            when (o) {
                is Call<*, *> -> {
                    val effect = adapters[o.port.name]?.port?.effect ?: o.port.effect
                    calls[o.key] = CallRecord(o.key, o.port.name, effect, o.target, o.request, now + o.timeoutMillis, Phase.Pending, attempts = 0, intent = o.intent)
                    newCalls += o.key
                }
                is Cancel -> {
                    calls[o.key] = calls.getValue(o.key).copy(cancelRequested = true)
                    cancels += o.key
                }
                is Reply -> {
                    val done = Outcome.Done(o.value)
                    inbound[o.to] = inbound.getValue(o.to).copy(reply = done, repliedAt = now)
                    replies += o.to to done
                }
                is Publish<*> -> {
                    val subs = resolveSubscribers(o.topic.name, o.payload)
                    val n = NoticeRecord(nextSeq++, o.topic.name, o.payload, subs)
                    if (subs.isNotEmpty()) outbox += n
                    newNotices += n
                }
            }
        }
        pruneReplied(inbound, now)
        val lastSeen = if (lastSeenUpdate == null) old.lastSeen else old.lastSeen + lastSeenUpdate
        val record = old.copy(
            state = newState,
            stateVersion = inst.ball.stateVersion,
            revision = old.revision + 1,
            calls = calls,
            inbound = inbound,
            outbox = outbox,
            nextNoticeSequence = nextSeq,
            lastSeen = lastSeen,
        )
        if (!persist(record)) return false // the atomic commit point
        inst.record = record
        val snapshot = Snapshot(inst.id, record.revision, record.state)
        published[inst.id] = snapshot
        completed?.let {
            inst.resolved.remove(it)
            inst.deadlineTimers.remove(it)?.cancel()
            inst.attemptTimers.remove(it)?.cancel()
            inst.cancelHandles.remove(it)
            inst.currentAttempt.remove(it)
            inst.statusQueries.remove(it)?.stop()
        }
        tracer.record(
            TraceEvent.Committed(
                at = now,
                id = inst.id,
                revision = record.revision,
                newCalls = newCalls.map { CallId(inst.id, it) },
                completedCall = completed?.let { CallId(inst.id, it) },
                completedOutcome = completedOutcome,
                replies = listOfNotNull(immediateReply) + replies.map { it.first },
                published = newNotices.size,
            ),
        )
        // ---- dispatch strictly after the commit ----
        watchers[inst.id]?.forEach { it(snapshot) }
        for ((to, outcome) in replies) deliverReply(inst, to, inbound.getValue(to).replyTo, outcome)
        for (key in newCalls) {
            armDeadline(inst, key)
            startSend(inst, key)
        }
        for (key in cancels) cancelCall(inst, key)
        if (newNotices.any { it.pendingSubscribers.isNotEmpty() }) deliverOutbox(inst)
        return true
    }

    /**
     * Forgets answered requests beyond the retention bounds, oldest first, but never one that its caller may
     * still re-send: then the repeat is answered from the record and not decided again (Core §3.3, §3.4).
     */
    private fun pruneReplied(inbound: LinkedHashMap<RequestId, InboundRecord>, now: Long) {
        val replied = inbound.values.filter { it.reply != null }
        val expired = replied.filter { now - (it.repliedAt ?: now) > limits.repliedRetentionMillis && !awaitsResend(it, now) }
        expired.forEach { inbound.remove(it.requestId) }
        val excess = replied.size - expired.size - limits.repliedRetention
        if (excess > 0) {
            replied.filter { it !in expired && !awaitsResend(it, now) }.sortedBy { it.repliedAt }.take(excess)
                .forEach { inbound.remove(it.requestId) }
        }
    }

    /**
     * Whether the caller of [entry]'s request may still send it again: a call on a Ball port, which a Durable
     * runtime re-sends after a restart until the call's deadline. Requests from outside are repeated only by
     * their clients, within the retention bounds.
     */
    private fun awaitsResend(entry: InboundRecord, now: Long): Boolean = entry.replyTo is ReplyTarget.ToCall && now <= entry.deadlineAt

    private fun saveLedger(inst: Instance, call: CallRecord): Boolean {
        val record = inst.record.copy(calls = inst.record.calls + (call.key to call))
        if (!persist(record)) return false
        inst.record = record
        return true
    }

    /**
     * Writes [record] as this runtime's incarnation. If the store refuses because a
     * later runtime took it over (R16), this runtime stops at once, like a crash, and
     * the caller dispatches nothing from the write: returns false.
     */
    private fun persist(record: InstanceRecord): Boolean {
        try {
            store.save(record, incarnation)
        } catch (e: FencedException) {
            fencedBy = e
            alive = false
            return false
        }
        return true
    }

    // ---------------------------------------------------------------- calls (R6, R7, R8)

    private fun armDeadline(inst: Instance, key: CallKey) {
        val call = inst.record.calls[key] ?: return
        inst.deadlineTimers[key]?.cancel()
        inst.deadlineTimers[key] = scheduler.schedule((call.deadlineAt - scheduler.now()).coerceAtLeast(0)) {
            onLoopNow { onDeadline(inst, key) }
        }
    }

    private fun startSend(inst: Instance, key: CallKey) {
        val call = inst.record.calls[key] ?: return
        if (key in inst.resolved || !alive || call.phase == Phase.Reconciling) return
        val now = scheduler.now()
        if (now >= call.deadlineAt) return // the deadline handler decides
        if (call.cancelRequested && call.phase == Phase.Pending) {
            resolve(inst, key, Outcome.NotDone(NotDoneReason.Cancelled))
            return
        }
        val bound = adapters[call.portName]
        if (bound != null && call.cancelRequested) {
            // Cancelled between attempts (before a retry, or before a re-send after a restart): no further attempt
            // is made. A cancelled timer has not fired (Core §2.4); other attempts ended without an answer. A cancel
            // does not stop a call on a Ball port, so such a call is re-sent as usual.
            if (call.portName == Timer.port.name) resolve(inst, key, Outcome.NotDone(NotDoneReason.NotApplied("timer cancelled")))
            else endWithoutAnswer(inst, key, call)
            return
        }
        // On a reconciled port, no attempt is sent after the cutoff: a status read could not follow it before the deadline.
        val cutoff = bound?.cutoff(call)
        if (cutoff != null && now >= cutoff) {
            if (call.phase == Phase.Sending) endWithoutAnswer(inst, key, call) // else never sent: the deadline handler resolves it
            return
        }
        if (bound != null) {
            val busy = inFlight[call.portName] ?: 0
            if (busy >= bound.maxInFlight) {
                val q = waiting.getOrPut(call.portName) { ArrayDeque() }
                val id = CallId(inst.id, key)
                if (id !in q) {
                    q.addLast(id)
                    // A retry or re-send still waiting at the cutoff gets no attempt; then it ends without an answer.
                    if (cutoff != null && call.phase == Phase.Sending) {
                        scheduler.schedule(cutoff - now) { onLoopNow { endWaitingAtCutoff(inst, key) } }
                    }
                }
                return
            }
            inFlight[call.portName] = busy + 1
        }
        // Write-ahead: the durable record says "possibly sent" before any transport runs.
        val attempt = call.attempts + 1
        if (!saveLedger(inst, call.copy(phase = Phase.Sending, attempts = attempt, lastSentAt = now))) return
        inst.currentAttempt[key] = attempt
        val callId = CallId(inst.id, key)
        tracer.record(TraceEvent.CallSent(now, callId, call.portName, attempt))
        if (bound != null) {
            val invocation = Invocation(callId, call.request, attempt, call.deadlineAt, idempotencyKey(inst.record.keyspace, callId, call.intent))
            // A report that arrives after this runtime crashed is dropped by onLoopNow;
            // its successor has already classified the call from the durable record.
            val handle = try {
                bound.adapter.execute(invocation) { result ->
                    scheduler.execute { onLoopNow { onAdapterResult(inst, key, attempt, result) } }
                }
            } catch (e: Exception) {
                scheduler.execute { onLoopNow { onAdapterResult(inst, key, attempt, AdapterResult.Failed("adapter threw: ${e.message}")) } }
                null
            }
            if (handle != null) inst.cancelHandles[key] = handle
            // The attempt ends without an answer at its attempt timeout, or at the cutoff of a reconciled port. A call
            // committed NonIdempotent takes no attempt timeout, also when a later binding of its port declares one.
            val attemptTimeout = bound.retry.attemptTimeoutMillis.takeIf { call.effect != EffectClass.NonIdempotent }
            listOfNotNull(attemptTimeout, cutoff?.let { it - now }).minOrNull()?.let { stop ->
                inst.attemptTimers[key] = scheduler.schedule(stop) { onLoopNow { onAttemptTimeout(inst, key, attempt) } }
            }
        } else {
            deliverToBall(inst, call, callId)
        }
    }

    /**
     * The cutoff of a reconciled port passed while a retry or re-send of [key] still waited for a slot: no attempt
     * remains, so the call ends without an answer ([endWithoutAnswer]) at the cutoff, not at its deadline.
     */
    private fun endWaitingAtCutoff(inst: Instance, key: CallKey) {
        val call = inst.record.calls[key] ?: return
        if (key in inst.resolved || call.phase != Phase.Sending) return
        if (waiting[call.portName]?.remove(CallId(inst.id, key)) != true) return // it took a slot; its attempt ends at the cutoff
        endWithoutAnswer(inst, key, call)
    }

    private fun releaseSlot(portName: String) {
        val busy = inFlight[portName] ?: return
        val free = (busy - 1).coerceAtLeast(0)
        inFlight[portName] = free
        val q = waiting[portName] ?: return
        while (q.isNotEmpty()) {
            val next = q.removeFirst()
            val inst = instances[next.caller] ?: continue
            if (inst.record.calls[next.key] == null || next.key in inst.resolved || next.key in inst.currentAttempt) continue
            // A first attempt (phase Pending) or a later one (a retry or a re-send after a restart, phase Sending).
            startSend(inst, next.key)
            if ((inFlight[portName] ?: 0) > free) return // the slot was taken; a call past its cutoff leaves it to the next
        }
    }

    private fun onAdapterResult(inst: Instance, key: CallKey, attempt: Int, result: AdapterResult<Any?>) {
        val call = inst.record.calls[key]
        val callId = CallId(inst.id, key)
        if (call == null || key in inst.resolved) {
            tracer.record(TraceEvent.LateReportDropped(scheduler.now(), callId, reportKind(result)))
            return
        }
        val bound = adapters.getValue(call.portName)
        val current = inst.currentAttempt[key] == attempt
        if (current) {
            inst.currentAttempt.remove(key)
            inst.attemptTimers.remove(key)?.cancel()
            releaseSlot(call.portName)
        }
        // Evidence of non-application covers only the attempt that produced it. It is
        // conclusive for the call when no other attempt can have been delivered, or
        // when the port has no effects at all.
        val conclusive = call.attempts == 1 || call.effect == EffectClass.Safe
        when (result) {
            is AdapterResult.Ok -> resolve(inst, key, Outcome.Done(result.value))
            is AdapterResult.Refused ->
                if (conclusive) resolve(inst, key, Outcome.NotDone(NotDoneReason.Refused(result.value)))
                else if (current) failAttempt(inst, key, call, bound)
            is AdapterResult.NotApplied ->
                if (conclusive) resolve(inst, key, Outcome.NotDone(NotDoneReason.NotApplied(result.evidence)))
                else if (current) failAttempt(inst, key, call, bound)
            is AdapterResult.Failed -> if (current) failAttempt(inst, key, call, bound)
        }
    }

    /** The kind of an adapter's report, without its values, for [TraceEvent.LateReportDropped]. */
    private fun reportKind(result: AdapterResult<*>): String = when (result) {
        is AdapterResult.Ok -> "Ok"
        is AdapterResult.Refused -> "Refused"
        is AdapterResult.NotApplied -> "NotApplied"
        is AdapterResult.Failed -> "Failed"
    }

    private fun outcomeKind(outcome: Outcome<*>): String = when (outcome) {
        is Outcome.Done -> "Done"
        is Outcome.NotDone -> "NotDone"
        is Outcome.Unknown -> "Unknown"
    }

    /**
     * The attempt timeout of [attempt] passed without a report: the adapter is asked to
     * stop it, and it ends without an answer (Core §3.2). A report it delivers later is
     * handled like one from any earlier attempt: an answer still resolves the call.
     */
    private fun onAttemptTimeout(inst: Instance, key: CallKey, attempt: Int) {
        val call = inst.record.calls[key] ?: return
        if (key in inst.resolved || inst.currentAttempt[key] != attempt) return
        inst.attemptTimers.remove(key)
        inst.currentAttempt.remove(key)
        inst.cancelHandles.remove(key)?.cancel()
        releaseSlot(call.portName)
        failAttempt(inst, key, call, adapters.getValue(call.portName))
    }

    private fun failAttempt(inst: Instance, key: CallKey, call: CallRecord, bound: BoundAdapter) {
        val effect = call.effect
        val retry = bound.retry
        val now = scheduler.now()
        val canRetry = effect != EffectClass.NonIdempotent &&
            call.attempts < retry.maxAttempts &&
            now + retry.backoffMillis < (bound.cutoff(call) ?: call.deadlineAt) &&
            !call.cancelRequested
        if (canRetry) {
            scheduler.schedule(retry.backoffMillis) { onLoopNow { if (key !in inst.resolved) startSend(inst, key) } }
            return
        }
        endWithoutAnswer(inst, key, call)
    }

    /**
     * Ends a possibly-sent call that gets no further attempt with [outcome]; on a
     * reconciled port, a call that would end `Unknown` enters phase Reconciling instead.
     */
    private fun endWithoutAnswer(inst: Instance, key: CallKey, call: CallRecord, outcome: Outcome<Any?> = noAnswer(call)) {
        if (outcome is Outcome.Unknown && adapters[call.portName]?.reconcile != null) startReconciling(inst, key, call, outcome.reason)
        else resolve(inst, key, outcome)
    }

    /** Records phase Reconciling with the reason of an `Unknown` outcome, then waits for the status query. */
    private fun startReconciling(inst: Instance, key: CallKey, call: CallRecord, reason: UnknownReason) {
        if (key in inst.resolved || !alive) return
        if (!saveLedger(inst, call.copy(phase = Phase.Reconciling, unknownReason = reason))) return
        awaitStatus(inst, key)
    }

    /** Schedules the status query of a reconciling call for when the horizon has passed since its last attempt was sent. */
    private fun awaitStatus(inst: Instance, key: CallKey) {
        val call = inst.record.calls[key] ?: return
        val reconcile = adapters[call.portName]?.reconcile
        if (reconcile == null) { // recovered under a binding without reconciliation
            resolve(inst, key, Outcome.Unknown(unknownReasonOf(call)))
            return
        }
        val now = scheduler.now()
        val due = (call.lastSentAt ?: now) + reconcile.horizonMillis
        val query = StatusQuery()
        inst.statusQueries[key] = query
        query.timer = scheduler.schedule((due - now).coerceAtLeast(0)) { onLoopNow { queryStatus(inst, key, query) } }
    }

    /** One attempt on the status port about a reconciling call; not a call, so no `CallSent`. */
    private fun queryStatus(inst: Instance, key: CallKey, query: StatusQuery) {
        val call = inst.record.calls[key] ?: return
        if (key in inst.resolved || inst.statusQueries[key] !== query) return
        val reconcile = adapters.getValue(call.portName).reconcile!!
        val status = adapters.getValue(reconcile.status.name)
        val callId = CallId(inst.id, key)
        val idempotencyKey = idempotencyKey(inst.record.keyspace, callId, call.intent)
        val request = try {
            reconcile.query(Invocation(callId, call.request, call.attempts, call.deadlineAt, idempotencyKey))
        } catch (e: Exception) {
            onStatus(inst, key, query, AdapterResult.Failed("status query threw: ${e.message}"))
            return
        }
        query.timer = scheduler.schedule(reconcile.statusTimeoutMillis) { onLoopNow { onStatus(inst, key, query, result = null) } }
        val invocation = Invocation(callId, request, attempt = 1, scheduler.now() + reconcile.statusTimeoutMillis, idempotencyKey)
        query.handle = try {
            status.adapter.execute(invocation) { result -> scheduler.execute { onLoopNow { onStatus(inst, key, query, result) } } }
        } catch (e: Exception) {
            scheduler.execute { onLoopNow { onStatus(inst, key, query, AdapterResult.Failed("status adapter threw: ${e.message}")) } }
            null
        }
    }

    /** The status port's report about a reconciling call, or `null` when its status timeout passed without one. */
    private fun onStatus(inst: Instance, key: CallKey, query: StatusQuery, result: AdapterResult<Any?>?) {
        val call = inst.record.calls[key] ?: return
        if (key in inst.resolved || inst.statusQueries[key] !== query) return
        inst.statusQueries.remove(key)
        query.timer?.cancel()
        if (result == null) query.handle?.cancel()
        val reconcile = adapters.getValue(call.portName).reconcile!!
        val verdict = (result as? AdapterResult.Ok)?.let { ok ->
            try {
                reconcile.verdict(ok.value)
            } catch (e: Exception) {
                StatusVerdict.Inconclusive
            }
        } ?: StatusVerdict.Inconclusive
        when (verdict) {
            is StatusVerdict.Applied -> resolve(inst, key, Outcome.Done(verdict.value), reconciled = true)
            StatusVerdict.NotApplied -> resolve(
                inst, key,
                Outcome.NotDone(NotDoneReason.NotApplied("${reconcile.status.name} reports it not applied after the delivery horizon")),
                reconciled = true,
            )
            StatusVerdict.Inconclusive -> resolve(inst, key, Outcome.Unknown(unknownReasonOf(call)))
        }
    }

    /** The reason of a reconciling call's `Unknown` outcome: the one recorded when it entered the phase. */
    private fun unknownReasonOf(call: CallRecord): UnknownReason = call.unknownReason ?: UnknownReason.LostAfterSend

    /** The outcome of a possibly-sent call whose attempts all ended without an answer, when no attempt follows. */
    private fun noAnswer(call: CallRecord): Outcome<Any?> = when (call.effect) {
        EffectClass.Safe -> Outcome.NotDone(NotDoneReason.NoAnswer)
        EffectClass.Idempotent ->
            Outcome.Unknown(if (call.attempts > 1) UnknownReason.RetriesExhausted else UnknownReason.LostAfterSend)
        EffectClass.NonIdempotent -> Outcome.Unknown(UnknownReason.LostAfterSend)
    }

    private fun onDeadline(inst: Instance, key: CallKey) {
        val call = inst.record.calls[key] ?: return
        if (key in inst.resolved) return
        inst.deadlineTimers.remove(key)
        if (call.phase == Phase.Pending) {
            waiting[call.portName]?.remove(CallId(inst.id, key))
            resolve(inst, key, Outcome.NotDone(NotDoneReason.DeadlineBeforeSend))
            return
        }
        if (call.phase == Phase.Reconciling) { // the status query did not settle it in time
            resolve(inst, key, Outcome.Unknown(unknownReasonOf(call)))
            return
        }
        inst.cancelHandles.remove(key)?.cancel()
        if (inst.currentAttempt.remove(key) != null && call.portName in adapters) releaseSlot(call.portName)
        resolve(inst, key, if (call.effect == EffectClass.Safe) Outcome.NotDone(NotDoneReason.NoAnswer) else Outcome.Unknown(UnknownReason.DeadlineAfterSend))
    }

    private fun cancelCall(inst: Instance, key: CallKey) {
        val call = inst.record.calls[key] ?: return
        if (key in inst.resolved) return
        if (call.phase == Phase.Pending) {
            waiting[call.portName]?.remove(CallId(inst.id, key))
            resolve(inst, key, Outcome.NotDone(NotDoneReason.Cancelled))
            return
        }
        // Possibly sent: ask the adapter to stop. Its report decides the outcome;
        // a cancellation never erases a result that already happened.
        inst.cancelHandles[key]?.cancel()
    }

    private fun portOf(inst: Instance, call: CallRecord): Port<*, *> =
        adapters[call.portName]?.port ?: BallPort<Any?, Any?>(call.portName.removePrefix(BALL_PORT_PREFIX))

    /**
     * Chooses the single terminal outcome of a call and queues it for the caller (R6).
     * [reconciled]: the outcome came from a status read of a reconciled port.
     */
    private fun resolve(inst: Instance, key: CallKey, outcome: Outcome<Any?>, reconciled: Boolean = false) {
        if (!alive) return // stopped by a fenced write within this task
        val call = inst.record.calls[key] ?: return
        if (!inst.resolved.add(key)) return
        inst.deadlineTimers.remove(key)?.cancel()
        inst.attemptTimers.remove(key)?.cancel()
        inst.statusQueries.remove(key)?.stop()
        if (inst.currentAttempt.remove(key) != null && call.portName in adapters) releaseSlot(call.portName)
        tracer.record(TraceEvent.CallResolved(scheduler.now(), CallId(inst.id, key), outcome, reconciled))
        val input = Input.CompletionInput(key, portOf(inst, call), outcome)
        // Completions bypass the inbox bound: they close obligations the instance already holds.
        inst.inbox.addLast(input)
        inst.scheduleDrain()
    }

    // ---------------------------------------------------------------- Ball-to-Ball requests (R9)

    private fun deliverToBall(caller: Instance, call: CallRecord, callId: CallId) {
        val destType = call.portName.removePrefix(BALL_PORT_PREFIX)
        val dest = instanceOrNull(BallId(destType, call.target!!))
        if (dest == null) {
            resolve(caller, call.key, Outcome.NotDone(NotDoneReason.NotApplied("unknown Ball type '$destType'")))
            return
        }
        val requestId = RequestId(callId.toString())
        val accepted = dest.record.inbound[requestId]
        if (accepted != null) {
            // A re-send after a restart of a request the destination accepted joins it (Core §3.3) without using
            // the inbox: its stored answer now, or the reply the destination still owes to this call.
            accepted.reply?.let { routeBallReply(callId, it) }
            return
        }
        val input = Input.Request(
            requestId = requestId,
            message = call.request,
            caller = Caller(ball = caller.id),
            deadlineAt = call.deadlineAt,
            replyTo = ReplyTarget.ToCall(callId),
            dedup = true,
        )
        // No earlier attempt was accepted: its entry would be kept until the call's deadline (pruneReplied). So a
        // refusal here is evidence of non-application for every attempt.
        if (!dest.offer(input)) resolve(caller, call.key, refusal(dest))
    }

    private fun refusal(inst: Instance): Outcome<Any?> =
        inst.failed?.let { Outcome.NotDone(NotDoneReason.Fault("instance ${inst.id} is quarantined: $it")) }
            ?: Outcome.NotDone(NotDoneReason.Overloaded)

    private fun reject(inst: Instance, input: Input.Request, outcome: Outcome<Any?>) {
        tracer.record(TraceEvent.RequestRejected(scheduler.now(), inst.id, input.requestId, outcome))
        deliverReplyTo(inst, input, outcome)
    }

    private fun deliverReplyTo(inst: Instance, input: Input.Request, outcome: Outcome<Any?>) {
        when (val target = input.replyTo) {
            ReplyTarget.External -> input.waiter?.finish(outcome)
            is ReplyTarget.ToCall -> routeBallReply(target.callId, outcome)
        }
    }

    private fun deliverReply(inst: Instance, requestId: RequestId, target: ReplyTarget, outcome: Outcome<Any?>) {
        tracer.record(TraceEvent.Replied(scheduler.now(), inst.id, requestId, outcome))
        when (target) {
            ReplyTarget.External -> inst.externalWaiters.remove(requestId)?.forEach { it.finish(outcome) }
            is ReplyTarget.ToCall -> routeBallReply(target.callId, outcome)
        }
    }

    private fun routeBallReply(callId: CallId, outcome: Outcome<Any?>) {
        val caller = instanceOrNull(callId.caller) ?: return
        val call = caller.record.calls[callId.key]
        if (call == null || callId.key in caller.resolved) {
            tracer.record(TraceEvent.LateReportDropped(scheduler.now(), callId, outcomeKind(outcome)))
            return
        }
        resolve(caller, callId.key, outcome)
    }

    private fun externalDeadline(inst: Instance, input: Input.Request, waiter: ExternalWaiter) {
        if (waiter.done) return
        val stillQueued = inst.inbox.remove(input)
        val accepted = inst.record.inbound[input.requestId]
        waiter.finish(
            when {
                stillQueued -> Outcome.NotDone(NotDoneReason.DeadlineBeforeSend)
                accepted != null -> Outcome.Unknown(UnknownReason.DeadlineAfterSend)
                else -> Outcome.Unknown(UnknownReason.DeadlineAfterSend)
            },
        )
        inst.externalWaiters[input.requestId]?.remove(waiter)
    }

    private fun checkReplyOverdue(inst: Instance, requestId: RequestId) {
        val entry = inst.record.inbound[requestId] ?: return
        if (entry.reply == null) tracer.record(TraceEvent.ReplyOverdue(scheduler.now(), inst.id, requestId))
    }

    // ---------------------------------------------------------------- notices

    private fun resolveSubscribers(topic: String, payload: Any?): Set<BallId> =
        subscriptions[topic].orEmpty().mapTo(LinkedHashSet()) { BallId(it.subscriberType, it.route(payload)) }

    /** Delivers unacknowledged notices in sequence order per subscriber; never drops accepted ones. */
    private fun deliverOutbox(publisher: Instance) {
        val blocked = HashSet<BallId>()
        for (n in publisher.record.outbox) {
            for (sub in n.pendingSubscribers) {
                if (sub in blocked) continue
                val tag = n.sequence to sub
                if (tag in publisher.noticesInFlight) continue
                val target = instanceOrNull(sub)
                if (target == null) continue
                if (target.offer(Input.NoticeInput(n.topic, publisher.id, n.sequence, n.payload))) {
                    publisher.noticesInFlight += tag
                } else {
                    blocked += sub
                }
            }
        }
        if (blocked.isNotEmpty() && !publisher.outboxRetryScheduled) {
            publisher.outboxRetryScheduled = true
            scheduler.schedule(limits.noticeRetryMillis) {
                onLoopNow {
                    publisher.outboxRetryScheduled = false
                    deliverOutbox(publisher)
                }
            }
        }
    }

    private fun acknowledgeNotice(source: BallId, sequence: Long, subscriber: BallId) {
        val publisher = instanceOrNull(source) ?: return
        publisher.noticesInFlight -= (sequence to subscriber)
        val outbox = publisher.record.outbox.mapNotNull { n ->
            if (n.sequence != sequence) n
            else (n.pendingSubscribers - subscriber).takeIf { it.isNotEmpty() }?.let { n.copy(pendingSubscribers = it) }
        }
        if (outbox != publisher.record.outbox) {
            val record = publisher.record.copy(outbox = outbox)
            if (persist(record)) publisher.record = record
        }
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Why this composition cannot take over the store, one entry per offending
     * record or obligation. Reads only; runs before the runtime writes anything.
     */
    private fun storeProblems(): List<String> {
        val ids = try {
            store.ids()
        } catch (e: Exception) {
            throw IncompatibleStoreException(listOf("the store cannot list its records: ${e.message ?: e}"), e)
        }
        val problems = ArrayList<String>()
        for (id in ids.sortedBy { it.toString() }) {
            val ball = balls[id.type]
            if (ball == null) {
                problems += "record $id names Ball type '${id.type}', which the composition no longer registers; drain the " +
                    "record under a composition that registers it, then remove the type's records with Store.delete"
                continue
            }
            val record = try {
                store.load(id)
            } catch (e: Exception) {
                problems += "record $id cannot be read: ${e.message ?: e}"
                continue
            } ?: continue
            if (record.stateVersion != ball.stateVersion) {
                try {
                    ball.migrate(record.stateVersion, record.state)
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Throwable) {
                    problems += "record $id holds state version ${record.stateVersion}, which Ball type '${id.type}' " +
                        "(state version ${ball.stateVersion}) cannot migrate: ${e.message}"
                }
            }
            for (call in record.calls.values.sortedBy { it.key.value }) {
                if (!serves(call.portName)) {
                    problems += "open call ${CallId(id, call.key)} uses port '${call.portName}', which the composition " +
                        "no longer binds; drain the call before removing the port"
                }
            }
            for (n in record.outbox) {
                for (sub in n.pendingSubscribers) {
                    if (subscriptions[n.topic].orEmpty().none { it.subscriberType == sub.type }) {
                        problems += "notice ${n.topic}#${n.sequence} of $id is still owed to $sub, but the composition no longer " +
                            "subscribes Ball type '${sub.type}' to topic '${n.topic}'; deliver it before removing the subscription"
                    }
                }
            }
        }
        return problems
    }

    /** Whether an open call on [portName] can still be sent and answered: a bound adapter or a registered Ball type. */
    private fun serves(portName: String): Boolean =
        portName in adapters || (portName.startsWith(BALL_PORT_PREFIX) && portName.removePrefix(BALL_PORT_PREFIX) in balls)

    private fun recover() {
        val now = scheduler.now()
        val recovered = store.ids().mapNotNull { instanceOrNull(it) }
        for (inst in recovered) {
            for (call in inst.record.calls.values.sortedBy { it.key.value }) {
                if (now >= call.deadlineAt) {
                    val outcome: Outcome<Any?> = when {
                        call.phase == Phase.Pending -> Outcome.NotDone(NotDoneReason.DeadlineBeforeSend)
                        call.phase == Phase.Reconciling -> Outcome.Unknown(unknownReasonOf(call))
                        call.effect == EffectClass.Safe -> Outcome.NotDone(NotDoneReason.NoAnswer)
                        else -> Outcome.Unknown(UnknownReason.DeadlineAfterSend)
                    }
                    resolve(inst, call.key, outcome)
                    continue
                }
                armDeadline(inst, call.key)
                when {
                    call.phase == Phase.Pending -> startSend(inst, call.key)
                    call.phase == Phase.Reconciling -> awaitStatus(inst, call.key) // resumes its status query
                    // On a reconciled port, the call enters phase Reconciling instead.
                    call.effect == EffectClass.NonIdempotent ->
                        endWithoutAnswer(inst, call.key, call, Outcome.Unknown(UnknownReason.RecoveredAfterCrash))
                    else -> {
                        // The attempt budget is the current binding's. A port since rebound as
                        // NonIdempotent allows one attempt, so such a call is never re-sent.
                        val bound = adapters[call.portName]
                        if (bound == null || call.attempts < bound.retry.maxAttempts) {
                            startSend(inst, call.key) // Ball ports deduplicate; repeatable adapters retry
                        } else {
                            endWithoutAnswer(
                                inst, call.key, call,
                                if (call.effect == EffectClass.Safe) Outcome.NotDone(NotDoneReason.NoAnswer)
                                else Outcome.Unknown(UnknownReason.RetriesExhausted),
                            )
                        }
                    }
                }
            }
            for (entry in inst.record.inbound.values) {
                if (entry.reply == null) {
                    scheduler.schedule((entry.deadlineAt - now).coerceAtLeast(0)) { onLoopNow { checkReplyOverdue(inst, entry.requestId) } }
                }
            }
            if (inst.record.outbox.isNotEmpty()) deliverOutbox(inst)
        }
        tracer.record(TraceEvent.Recovered(now, incarnation, recovered.size))
    }

    // ---------------------------------------------------------------- loop helpers

    private inline fun onLoop(crossinline block: () -> Unit) {
        scheduler.execute { onLoopNow { block() } }
    }

    private inline fun onLoopNow(block: () -> Unit) {
        if (alive) block()
    }

    private open class RtContext(
        override val self: BallId,
        override val now: Long,
        override val revision: Long,
    ) : Context {
        private var issued = 0
        override fun newKey(): CallKey = CallKey("$revision.${issued++}")
    }

    private class RtRequestContext(
        self: BallId,
        now: Long,
        revision: Long,
        override val requestId: RequestId,
        override val caller: Caller,
    ) : RtContext(self, now, revision), RequestContext

    private companion object {
        /** Name prefix of every [BallPort]. */
        const val BALL_PORT_PREFIX = "ball:"

        /** Longest keyspace, so that a derived idempotency key has at most 76 characters. */
        const val MAX_KEYSPACE = 32
    }
}

/**
 * The last committed state of one instance and the revision that committed it
 * (rule R12). Revision 0 is the initial state of an instance with no commit.
 */
public data class Snapshot<out S>(val id: BallId, val revision: Long, val state: S)

/**
 * The idempotency key of every attempt of a call: the record's [keyspace], then
 * a digest of the caller and the call's [intent], or of its call key when it
 * names none. A record without a keyspace, written before keyspaces existed,
 * keeps the call identity as key, so its open calls are re-sent with the key
 * they were first sent with.
 */
internal fun idempotencyKey(keyspace: String?, callId: CallId, intent: Intent?): String {
    val subject = if (intent == null) "${callId.caller}#${callId.key}" else "${callId.caller}~${intent.id}"
    if (keyspace == null) return subject
    val digest = MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8))
    return keyspace + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/** 128 random bits in hexadecimal: the default keyspace of a new record. */
private fun randomKeyspace(): String = ByteArray(16).also { KEYSPACE_RANDOM.nextBytes(it) }.joinToString("") { "%02x".format(it) }

private val KEYSPACE_RANDOM = SecureRandom()

/**
 * The store holds records that the composition cannot serve, or that cannot
 * be read, so the runtime refused to start and wrote no record. A refusal
 * before the incarnation is taken wrote nothing; one after it, for a record
 * the replaced runtime committed in between, has taken the incarnation. Each
 * entry of [problems] names one record or obligation; [cause] is the store's error when
 * it could not list or read its records. Drain open calls and notices under
 * the old composition, migrate the state, remove the records of a retired Ball
 * type with [Store.delete], or restore what is missing.
 */
public class IncompatibleStoreException(public val problems: List<String>, cause: Throwable? = null) :
    IllegalStateException("the store holds records this composition cannot serve:\n  " + problems.joinToString("\n  "), cause)

/**
 * The built-in timer adapter: answers at the timer's due time, on the runtime's scheduler.
 * Preflight admits only calls made with [Timer.after], whose deadline is the due time
 * plus [Timer.MARGIN_MILLIS]; so every attempt, including a re-arm after a Durable
 * restart, waits until the original due time and never fires before it.
 */
@Suppress("UNCHECKED_CAST")
private fun timerAdapter(scheduler: Scheduler): Adapter<Any?, Any?> = Adapter<Long, Unit> { invocation, done ->
    val due = invocation.deadlineAt - Timer.MARGIN_MILLIS
    val fired = scheduler.schedule((due - scheduler.now()).coerceAtLeast(0)) { done(AdapterResult.Ok(Unit)) }
    Cancellable {
        fired.cancel()
        done(AdapterResult.NotApplied("timer cancelled"))
    }
} as Adapter<Any?, Any?>
