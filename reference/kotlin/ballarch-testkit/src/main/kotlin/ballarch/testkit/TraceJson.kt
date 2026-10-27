package ballarch.testkit

import ballarch.kernel.BallId
import ballarch.kernel.CallId
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.Timer
import ballarch.runtime.TraceEvent
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a run as a conformance trace: the language-neutral JSON Lines format
 * of `tools/TRACE-FORMAT.md`, which `tools/oracles.py` checks. A trace holds a
 * `Header` line, one line per [TraceEvent], the ground truth, and an `End`
 * line. The ground truth is one `Destination` line per call and epoch that was
 * handed to a simulated destination: [SimPort] records a call when an attempt
 * is handed to it, before any fault, so a call whose every request was lost
 * has a line too.
 *
 * Application values (states, messages, reply values) are not written; the
 * trace properties do not depend on them.
 */
public object TraceJson {
    /** Value of the header's `format` field. */
    public const val FORMAT: String = "ballarch-trace"

    /** Value of the header's `version` field. */
    public const val VERSION: Int = 1

    /** Writes the trace to [path], replacing it, and creates missing parent directories. */
    public fun write(path: Path, events: List<TraceEvent>, truth: GroundTruth, profile: Profile, quiescent: Boolean) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(path).use { write(it, events, truth, profile, quiescent) }
    }

    /**
     * Writes the trace to [out]. Runtime events carry their epoch: always 0
     * under the Durable profile; under the Transient profile it starts at 0 and
     * increases at each `Recovered` event that follows an earlier event, as
     * [Oracles.check] splits the trace.
     *
     * @throws IllegalArgumentException if the ground truth does not match the
     *   calls sent, as `tools/oracles.py` requires: in each epoch, a call sent
     *   on any port but the timer and other Balls' ports has ground truth, and
     *   only such calls have it. A run that binds an adapter port to something
     *   other than a [SimPort] cannot be written.
     */
    public fun write(out: Writer, events: List<TraceEvent>, truth: GroundTruth, profile: Profile, quiescent: Boolean) {
        val epochs = epochsOf(events, profile)
        requireMatchingGroundTruth(events, epochs, truth)
        out.line { field("event", "Header"); field("format", FORMAT); field("version", VERSION); field("profile", profile.name) }
        events.forEachIndexed { i, e -> out.line { event(e, epochs[i]) } }
        for (n in 0..truth.epoch) {
            val t = truth.at(n)
            for ((call, effect) in t.effectOf) {
                out.line {
                    field("event", "Destination")
                    field("epoch", n)
                    raw("call", call(call))
                    field("effectClass", effect.name)
                    field("deliveries", t.deliveries[call] ?: 0)
                    field("processed", call in t.processed)
                    field("applied", t.appliedCount(call))
                }
            }
        }
        out.line { field("event", "End"); field("quiescent", quiescent) }
    }

    /** The epoch of each event. */
    private fun epochsOf(events: List<TraceEvent>, profile: Profile): IntArray {
        var epoch = 0
        return IntArray(events.size) { i ->
            if (profile == Profile.Transient && events[i] is TraceEvent.Recovered && i > 0) epoch++
            epoch
        }
    }

    /** Whether calls on [port] go to a simulated destination: every port but the timer and the ports of other Balls. */
    private fun isSimulated(port: String): Boolean = port != Timer.port.name && !port.startsWith(BALL_PORT_PREFIX)

    private fun requireMatchingGroundTruth(events: List<TraceEvent>, epochs: IntArray, truth: GroundTruth) {
        val sent = LinkedHashSet<Pair<Int, CallId>>()
        events.forEachIndexed { i, e -> if (e is TraceEvent.CallSent && isSimulated(e.port)) sent += epochs[i] to e.callId }
        val recorded = LinkedHashSet<Pair<Int, CallId>>()
        for (n in 0..truth.epoch) truth.at(n).effectOf.keys.forEach { recorded += n to it }
        val unrecorded = sent - recorded
        require(unrecorded.isEmpty()) {
            "no ground truth for calls sent on adapter ports (epoch to call): $unrecorded; bind every adapter port to a SimPort"
        }
        val unsent = recorded - sent
        require(unsent.isEmpty()) { "ground truth for calls that were not sent in that epoch (epoch to call): $unsent" }
    }

    /** Name prefix of every [ballarch.kernel.BallPort]. */
    private const val BALL_PORT_PREFIX = "ball:"

    private fun name(e: TraceEvent): String = when (e) {
        is TraceEvent.DecideStarted -> "DecideStarted"
        is TraceEvent.DecideFinished -> "DecideFinished"
        is TraceEvent.Committed -> "Committed"
        is TraceEvent.CallSent -> "CallSent"
        is TraceEvent.CallResolved -> "CallResolved"
        is TraceEvent.LateReportDropped -> "LateReportDropped"
        is TraceEvent.RequestRejected -> "RequestRejected"
        is TraceEvent.Replied -> "Replied"
        is TraceEvent.Fault -> "Fault"
        is TraceEvent.ReplyOverdue -> "ReplyOverdue"
        is TraceEvent.Recovered -> "Recovered"
    }

    private fun name(r: NotDoneReason<*>): String = when (r) {
        is NotDoneReason.Refused -> "Refused"
        NotDoneReason.Overloaded -> "Overloaded"
        NotDoneReason.Cancelled -> "Cancelled"
        NotDoneReason.DeadlineBeforeSend -> "DeadlineBeforeSend"
        NotDoneReason.NoAnswer -> "NoAnswer"
        is NotDoneReason.NotApplied -> "NotApplied"
        NotDoneReason.IdempotencyConflict -> "IdempotencyConflict"
        is NotDoneReason.Fault -> "Fault"
    }

    private fun Line.event(e: TraceEvent, epoch: Int) {
        field("event", name(e))
        field("at", e.at)
        field("epoch", epoch)
        when (e) {
            is TraceEvent.DecideStarted -> { raw("instance", instance(e.id)); field("input", e.input) }
            is TraceEvent.DecideFinished -> raw("instance", instance(e.id))
            is TraceEvent.Committed -> {
                raw("instance", instance(e.id))
                field("revision", e.revision)
                raw("newCalls", e.newCalls.joinToString(",", "[", "]") { call(it) })
                raw("completedCall", e.completedCall?.let { call(it) } ?: "null")
                raw("completedOutcome", e.completedOutcome?.let { outcome(it) } ?: "null")
                raw("replies", e.replies.joinToString(",", "[", "]") { string(it.value) })
                field("published", e.published)
            }
            is TraceEvent.CallSent -> { raw("call", call(e.callId)); field("port", e.port); field("attempt", e.attempt) }
            is TraceEvent.CallResolved -> {
                raw("call", call(e.callId))
                raw("outcome", outcome(e.outcome))
                if (e.reconciled) field("reconciled", true) // optional, written only when true
            }
            is TraceEvent.LateReportDropped -> { raw("call", call(e.callId)); field("report", e.report) }
            is TraceEvent.RequestRejected -> { raw("instance", instance(e.id)); field("request", e.requestId.value); raw("outcome", outcome(e.outcome)) }
            is TraceEvent.Replied -> { raw("instance", instance(e.id)); field("request", e.requestId.value); raw("outcome", outcome(e.outcome)) }
            is TraceEvent.Fault -> { raw("instance", instance(e.id)); field("message", e.message) }
            is TraceEvent.ReplyOverdue -> { raw("instance", instance(e.id)); field("request", e.requestId.value) }
            is TraceEvent.Recovered -> { field("incarnation", e.incarnation); field("instances", e.instances) }
        }
    }

    private fun instance(id: BallId): String = "{\"type\":${string(id.type)},\"key\":${string(id.key)}}"

    private fun call(id: CallId): String = "{\"instance\":${instance(id.caller)},\"key\":${string(id.key.value)}}"

    private fun outcome(o: Outcome<*>): String = when (o) {
        is Outcome.Done -> "{\"kind\":\"Done\"}"
        is Outcome.Unknown -> "{\"kind\":\"Unknown\",\"reason\":${string(o.reason.name)}}"
        is Outcome.NotDone -> {
            val r = o.reason
            val detail = when (r) {
                is NotDoneReason.NotApplied -> ",\"detail\":${string(r.evidence)}"
                is NotDoneReason.Fault -> ",\"detail\":${string(r.message)}"
                else -> ""
            }
            "{\"kind\":\"NotDone\",\"reason\":${string(name(r))}$detail}"
        }
    }

    /** A JSON string literal. Characters below U+0020 are escaped; everything else is written as is (UTF-8). */
    private fun string(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** One JSON object written as one line, fields in insertion order. */
    private class Line {
        val text = StringBuilder("{")

        fun raw(name: String, json: String) {
            if (text.length > 1) text.append(',')
            text.append(string(name)).append(':').append(json)
        }

        fun field(name: String, value: String) = raw(name, string(value))

        fun field(name: String, value: Long) = raw(name, value.toString())

        fun field(name: String, value: Int) = raw(name, value.toString())

        fun field(name: String, value: Boolean) = raw(name, value.toString())
    }

    private inline fun Writer.line(build: Line.() -> Unit) {
        val line = Line().apply(build)
        write(line.text.append('}').toString())
        write("\n")
    }
}
