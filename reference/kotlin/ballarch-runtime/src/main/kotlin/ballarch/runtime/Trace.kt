package ballarch.runtime

import ballarch.kernel.BallId
import ballarch.kernel.CallId
import ballarch.kernel.Outcome
import ballarch.kernel.RequestId

/**
 * Observable runtime events. The test kit checks the specification's invariants
 * against this stream; production code may export it as metrics or logs.
 */
public sealed interface TraceEvent {
    public val at: Long

    /** A decider invocation began. Used to check serial processing. */
    public data class DecideStarted(override val at: Long, val id: BallId, val input: String) : TraceEvent

    public data class DecideFinished(override val at: Long, val id: BallId) : TraceEvent

    /** A decision was committed atomically, together with the calls it created. */
    public data class Committed(
        override val at: Long,
        val id: BallId,
        val revision: Long,
        val newCalls: List<CallId>,
        val completedCall: CallId?,
        /** Outcome of [completedCall] as delivered to the decider. */
        val completedOutcome: Outcome<*>?,
        /** Requests answered by this commit: an immediate reply to the decided request, then deferred replies. */
        val replies: List<RequestId>,
        val published: Int,
    ) : TraceEvent

    /** The write-ahead record for an attempt was saved and the transport is about to be invoked. */
    public data class CallSent(override val at: Long, val callId: CallId, val port: String, val attempt: Int) : TraceEvent

    /**
     * A terminal outcome was chosen for a call and queued for its caller. [reconciled]
     * is true when the outcome came from a status read of a reconciled port
     * ([Reconciliation]); the trace properties do not depend on it.
     */
    public data class CallResolved(
        override val at: Long,
        val callId: CallId,
        val outcome: Outcome<*>,
        val reconciled: Boolean = false,
    ) : TraceEvent

    /**
     * A report arrived for a call that was already resolved or is unknown; it was dropped.
     * [report] names its kind and never holds application values: `Ok`, `Refused`,
     * `NotApplied` or `Failed` for an adapter's report, `Done`, `NotDone` or `Unknown`
     * for another Ball's reply, and `Completion` for a completion of a closed call.
     */
    public data class LateReportDropped(override val at: Long, val callId: CallId, val report: String) : TraceEvent

    public data class RequestRejected(override val at: Long, val id: BallId, val requestId: RequestId, val outcome: Outcome<*>) : TraceEvent

    public data class Replied(override val at: Long, val id: BallId, val requestId: RequestId, val outcome: Outcome<*>) : TraceEvent

    /** A decider threw, or returned outputs that violate the kernel rules. Nothing was committed. */
    public data class Fault(override val at: Long, val id: BallId, val message: String) : TraceEvent

    /** An accepted request stayed unanswered past its deadline: an obligation leak in the Ball. */
    public data class ReplyOverdue(override val at: Long, val id: BallId, val requestId: RequestId) : TraceEvent

    public data class Recovered(override val at: Long, val incarnation: Long, val instances: Int) : TraceEvent
}

public fun interface Tracer {
    public fun record(event: TraceEvent)

    public companion object {
        public val None: Tracer = Tracer { }
    }
}

/** Collects every event; convenient for tests. */
public class RecordingTracer : Tracer {
    private val list = ArrayList<TraceEvent>()

    @Synchronized
    override fun record(event: TraceEvent) {
        list += event
    }

    @get:Synchronized
    public val events: List<TraceEvent> get() = list.toList()
}
