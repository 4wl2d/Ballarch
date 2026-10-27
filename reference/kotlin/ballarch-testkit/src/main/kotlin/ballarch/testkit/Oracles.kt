package ballarch.testkit

import ballarch.kernel.BallId
import ballarch.kernel.CallId
import ballarch.kernel.EffectClass
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.RequestId
import ballarch.runtime.TraceEvent

/**
 * Executable forms of the kernel rules that the runtime is responsible for.
 * Each check reads only the trace and the simulated ground truth, so it can
 * judge any runtime implementation that emits the same trace events.
 *
 * Durable runs keep one identity space across restarts. A Transient restart
 * begins a new world (fresh state, reused call identities), so its trace is
 * split into epochs at each recovery and every epoch is checked against its
 * own ground truth.
 *
 * The same checks, named T3 and T5–T9, are defined over the language-neutral
 * trace format of `tools/TRACE-FORMAT.md` and implemented by `tools/oracles.py`,
 * which reports the same messages with `T` in place of `R`. [TraceJson] writes
 * that format, so a test can check that both agree.
 */
public object Oracles {
    public fun check(
        events: List<TraceEvent>,
        truth: GroundTruth,
        quiescent: Boolean,
        profile: Profile,
        crashed: Boolean = false,
    ): List<String> {
        val v = ArrayList<String>()
        serialProcessing(events, v)
        val epochs = if (profile == Profile.Transient) splitAtRecovery(events) else listOf(events)
        epochs.forEachIndexed { epoch, segment ->
            val t = truth.at(epoch)
            val prefix = if (epochs.size > 1) "[epoch $epoch] " else ""
            val before = v.size
            commitBeforeDispatch(segment, v)
            // Liveness is owed only by an epoch that ran to quiescence without losing its state.
            val live = quiescent && (profile == Profile.Durable || epoch == epochs.lastIndex)
            oneCompletionPerCall(segment, v, live)
            honestOutcomes(segment, t, v)
            noResendOfNonIdempotent(segment, t, v)
            oneReplyPerRequest(segment, v)
            for (i in before until v.size) v[i] = prefix + v[i]
        }
        return v
    }

    private fun splitAtRecovery(events: List<TraceEvent>): List<List<TraceEvent>> {
        val out = ArrayList<MutableList<TraceEvent>>()
        for (e in events) {
            if (e is TraceEvent.Recovered || out.isEmpty()) out += ArrayList<TraceEvent>()
            out.last() += e
        }
        return out
    }

    /** R3: decider invocations of one instance never overlap. */
    private fun serialProcessing(events: List<TraceEvent>, v: MutableList<String>) {
        val open = HashSet<BallId>()
        for (e in events) {
            when (e) {
                is TraceEvent.DecideStarted -> if (!open.add(e.id)) v += "R3: overlapping decisions on ${e.id} at ${e.at}"
                is TraceEvent.DecideFinished -> open.remove(e.id)
                is TraceEvent.Recovered -> open.clear()
                else -> Unit
            }
        }
    }

    /** R5: no call is sent before the decision that created it was committed. */
    private fun commitBeforeDispatch(events: List<TraceEvent>, v: MutableList<String>) {
        val committed = HashSet<CallId>()
        for (e in events) {
            when (e) {
                is TraceEvent.Committed -> committed += e.newCalls
                is TraceEvent.CallSent -> if (e.callId !in committed) v += "R5: ${e.callId} sent before its decision was committed"
                else -> Unit
            }
        }
    }

    /**
     * R6: at most one committed completion per call, and none for a call that no earlier commit created;
     * exactly one at quiescence when liveness is owed.
     */
    private fun oneCompletionPerCall(events: List<TraceEvent>, v: MutableList<String>, live: Boolean) {
        val created = LinkedHashSet<CallId>()
        val completed = LinkedHashMap<CallId, Int>()
        for (e in events) {
            if (e is TraceEvent.Committed) {
                e.completedCall?.let {
                    if (it !in created && it !in completed) v += "R6: $it completed but never committed"
                    completed.merge(it, 1, Int::plus)
                }
                created += e.newCalls
            }
        }
        completed.filterValues { it > 1 }.forEach { (id, n) -> v += "R6: $id completed $n times" }
        if (live) (created - completed.keys).forEach { id -> v += "R6: $id never completed" }
    }

    /**
     * R7: an outcome delivered to a decider is honest with respect to ground truth.
     * NotDone on an effectful port implies the effect never happened (checked at the
     * end of the run, so late deliveries count); Done implies the destination handled it.
     */
    private fun honestOutcomes(events: List<TraceEvent>, truth: EpochTruth, v: MutableList<String>) {
        for (e in events) {
            if (e !is TraceEvent.Committed) continue
            val id = e.completedCall ?: continue
            val outcome = e.completedOutcome ?: continue
            val effect = truth.effectOf[id] // null: never reached a simulated adapter (Ball port or unsent)
            when (outcome) {
                is Outcome.NotDone -> if (effect != null && effect != EffectClass.Safe && truth.appliedCount(id) > 0) {
                    v += "R7: $id reported NotDone(${reasonName(outcome.reason)}) but its effect was applied ${truth.appliedCount(id)} time(s)"
                }
                is Outcome.Done -> if (effect != null && id !in truth.processed) {
                    v += "R7: $id reported Done but the destination never handled it"
                }
                is Outcome.Unknown -> Unit
            }
        }
    }

    /** R8: a call on a non-idempotent port is sent at most once, across durable restarts. */
    private fun noResendOfNonIdempotent(events: List<TraceEvent>, truth: EpochTruth, v: MutableList<String>) {
        val sends = HashMap<CallId, Int>()
        for (e in events) if (e is TraceEvent.CallSent) sends.merge(e.callId, 1, Int::plus)
        for ((id, n) in sends) {
            if (n > 1 && truth.effectOf[id] == EffectClass.NonIdempotent) v += "R8: non-idempotent $id sent $n times"
        }
        for ((id, n) in truth.applied) {
            if (n > 1 && truth.effectOf[id] == EffectClass.NonIdempotent) v += "R8: non-idempotent $id applied $n times"
        }
    }

    /** R9: an accepted request is answered at most once. */
    private fun oneReplyPerRequest(events: List<TraceEvent>, v: MutableList<String>) {
        val replies = HashMap<Pair<BallId, RequestId>, Int>()
        for (e in events) if (e is TraceEvent.Replied) replies.merge(e.id to e.requestId, 1, Int::plus)
        replies.filterValues { it > 1 }.forEach { (k, n) -> v += "R9: ${k.first} replied $n times to ${k.second}" }
    }

    private fun reasonName(r: NotDoneReason<*>): String = r::class.simpleName ?: r.toString()
}
