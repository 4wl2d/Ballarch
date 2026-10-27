package ballarch.runtime

import ballarch.kernel.BallId
import ballarch.kernel.CallId
import ballarch.kernel.CallKey
import ballarch.kernel.EffectClass
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.RequestId
import ballarch.testkit.GroundTruth
import ballarch.testkit.Harness
import ballarch.testkit.Oracles
import ballarch.testkit.Profile
import ballarch.testkit.RandomChooser
import ballarch.testkit.SimPort
import ballarch.testkit.TraceJson
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The language-neutral conformance trace (`tools/TRACE-FORMAT.md`): the exact
 * lines [TraceJson] writes, and seeded violations that [Oracles.check] and
 * `tools/oracles.py` must both find, with the same messages. The clean runs the
 * violations are made from are written with the samples, which must all pass;
 * the seeded traces go to their own directory, where each must fail.
 */
class TraceConformanceTest {
    private val payer = BallId("payer", "p1")
    private val call = CallId(payer, CallKey("1.0"))
    private val request = RequestId("ext:1:0")

    // ---------------------------------------------------------------- the format

    @Test
    fun `TraceJson writes the documented lines`() {
        val events = listOf(
            TraceEvent.Recovered(1_000, incarnation = 1, instances = 0),
            TraceEvent.DecideStarted(1_000, payer, "request ext:1:0"),
            TraceEvent.DecideFinished(1_000, payer),
            TraceEvent.Committed(1_000, payer, 1, listOf(call), null, null, emptyList(), 0),
            TraceEvent.CallSent(1_000, call, "payment", 1),
            TraceEvent.CallResolved(1_010, call, Outcome.Done("ok-10")),
            TraceEvent.DecideStarted(1_010, payer, "completion 1.0"),
            TraceEvent.DecideFinished(1_010, payer),
            TraceEvent.Committed(1_010, payer, 2, emptyList(), call, Outcome.Done("ok-10"), listOf(request), 0),
            TraceEvent.Replied(1_010, payer, request, Outcome.Done("paid:ok-10")),
        )
        val truth = GroundTruth().apply {
            effectOf[call] = EffectClass.NonIdempotent
            deliveries[call] = 1
            processed += call
            applied[call] = 1
        }
        val p = """{"type":"payer","key":"p1"}"""
        val c = """{"instance":$p,"key":"1.0"}"""
        val expected = listOf(
            """{"event":"Header","format":"ballarch-trace","version":1,"profile":"Durable"}""",
            """{"event":"Recovered","at":1000,"epoch":0,"incarnation":1,"instances":0}""",
            """{"event":"DecideStarted","at":1000,"epoch":0,"instance":$p,"input":"request ext:1:0"}""",
            """{"event":"DecideFinished","at":1000,"epoch":0,"instance":$p}""",
            """{"event":"Committed","at":1000,"epoch":0,"instance":$p,"revision":1,"newCalls":[$c],"completedCall":null,""" +
                """"completedOutcome":null,"replies":[],"published":0}""",
            """{"event":"CallSent","at":1000,"epoch":0,"call":$c,"port":"payment","attempt":1}""",
            """{"event":"CallResolved","at":1010,"epoch":0,"call":$c,"outcome":{"kind":"Done"}}""",
            """{"event":"DecideStarted","at":1010,"epoch":0,"instance":$p,"input":"completion 1.0"}""",
            """{"event":"DecideFinished","at":1010,"epoch":0,"instance":$p}""",
            """{"event":"Committed","at":1010,"epoch":0,"instance":$p,"revision":2,"newCalls":[],"completedCall":$c,""" +
                """"completedOutcome":{"kind":"Done"},"replies":["ext:1:0"],"published":0}""",
            """{"event":"Replied","at":1010,"epoch":0,"instance":$p,"request":"ext:1:0","outcome":{"kind":"Done"}}""",
            """{"event":"Destination","epoch":0,"call":$c,"effectClass":"NonIdempotent","deliveries":1,"processed":true,"applied":1}""",
            """{"event":"End","quiescent":true}""",
        )
        assertEquals(expected, write(events, truth, Profile.Durable).lines().dropLast(1))
        // The example in the format's documentation is this trace.
        val doc = System.getProperty("ballarch.oracles")?.let { Path.of(it).resolveSibling("TRACE-FORMAT.md") }
        if (doc != null && Files.exists(doc)) {
            val example = Regex("```json\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL).find(Files.readString(doc))!!.groupValues[1]
            assertEquals(expected, example.lines(), "the example in $doc")
        }
    }

    @Test
    fun `transient epochs advance at each later recovery, and strings are escaped`() {
        val odd = BallId("pay\"er", "p\\1\n\u0001 ü")
        val events = listOf(
            TraceEvent.Recovered(1_000, 1, 0),
            TraceEvent.Fault(1_001, odd, "boom"),
            TraceEvent.Recovered(1_050, 2, 0),
            TraceEvent.RequestRejected(1_051, odd, request, Outcome.NotDone(NotDoneReason.NotApplied("refused\tnow"))),
            TraceEvent.Recovered(1_100, 3, 0),
            TraceEvent.LateReportDropped(1_101, CallId(odd, CallKey("1.0")), "Ok"),
        )
        val transient = write(events, GroundTruth(), Profile.Transient).lines()
        assertEquals(listOf(0, 0, 1, 1, 2, 2), transient.drop(1).take(6).map { Regex(""""epoch":(\d+)""").find(it)!!.groupValues[1].toInt() })
        assertTrue(""""instance":{"type":"pay\"er","key":"p\\1\n\u0001 ü"}""" in transient[2], transient[2])
        assertTrue(""""outcome":{"kind":"NotDone","reason":"NotApplied","detail":"refused\tnow"}""" in transient[4], transient[4])
        assertTrue(transient[6].endsWith(""","report":"Ok"}"""), transient[6])
        val durable = write(events, GroundTruth(), Profile.Durable).lines()
        assertTrue(durable.drop(1).take(6).all { """"epoch":0""" in it })
    }

    @Test
    fun `TraceJson refuses ground truth that does not match the calls sent on adapter ports`() {
        val sent = listOf(
            TraceEvent.Committed(1_000, payer, 1, listOf(call), null, null, emptyList(), 0),
            TraceEvent.CallSent(1_000, call, "payment", 1),
        )
        // A call handed to a destination that recorded nothing: the adapter was not simulated.
        val unrecorded = assertFailsWith<IllegalArgumentException> { write(sent, GroundTruth(), Profile.Durable) }
        assertTrue("bind every adapter port to a SimPort" in unrecorded.message!!, unrecorded.message)
        // Ground truth for a call that this epoch never sent on an adapter port.
        val truth = GroundTruth().apply { effectOf[call] = EffectClass.NonIdempotent }
        val toBall = sent.dropLast(1) + TraceEvent.CallSent(1_000, call, "ball:counter", 1)
        val unsent = assertFailsWith<IllegalArgumentException> { write(toBall, truth, Profile.Durable) }
        assertTrue("not sent in that epoch" in unsent.message!!, unsent.message)
        // A call whose every request was lost has ground truth, and is written.
        assertTrue(""""deliveries":0,"processed":false,"applied":0""" in write(sent, truth, Profile.Durable))
    }

    private fun write(events: List<TraceEvent>, truth: GroundTruth, profile: Profile, quiescent: Boolean = true): String =
        StringWriter().also { TraceJson.write(it, events, truth, profile, quiescent) }.toString()

    // ---------------------------------------------------------------- seeded violations

    /** A trace made from [base]'s run; a control if its name starts with `clean-`, otherwise it carries a seeded violation. */
    private class Seeded(val name: String, val base: Harness, val events: List<TraceEvent>) {
        val control: Boolean = name.startsWith("clean-")
        val violations: List<String> = Oracles.check(events, base.truth, quiescent = base.scheduler.pending == 0, profile = base.profile)
    }

    /** One payment; with [payAgain], a second payment once the first run is idle, which after a Transient restart starts a new world. */
    private fun payerRun(seed: Long, profile: Profile, crashWindow: Int?, payAgain: Boolean): Harness {
        val chooser = RandomChooser(seed)
        val h = Harness(chooser, profile) {
            Composition().ball(PayerBall()).adapter(PaymentPort, SimPort(PaymentPort, scheduler, chooser, truth, refuse = { "refused" }) { "ok-$it" })
        }
        crashWindow?.let { h.maybeCrashOnce(window = it) }
        h.start()
        h.send(payer, Pay(10, timeoutMillis = 200))
        h.runUntilIdle()
        if (payAgain) {
            h.send(payer, Pay(20, timeoutMillis = 200))
            h.runUntilIdle()
        }
        return h
    }

    /** The first seeded run that satisfies [accept]; every run must satisfy the oracles. */
    private fun find(
        profile: Profile = Profile.Durable,
        crashWindow: Int? = null,
        payAgain: Boolean = false,
        accept: (Harness) -> Boolean,
    ): Harness = (0L until 2_000L).asSequence()
        .map { payerRun(it, profile, crashWindow, payAgain).also { h -> h.assertInvariants() } }
        .first(accept)

    private fun Harness.finished(prefix: String) = (runtime.inspect(payer) as? PayerState.Finished)?.outcome?.startsWith(prefix) == true

    /**
     * [events] with the first (or, with [last], the last) event matching [at]
     * replaced by [change]'s result (empty: removed; two: duplicated).
     */
    private fun List<TraceEvent>.edit(
        at: (TraceEvent) -> Boolean,
        last: Boolean = false,
        change: (TraceEvent) -> List<TraceEvent>,
    ): List<TraceEvent> {
        val i = if (last) indexOfLast(at) else indexOfFirst(at)
        check(i >= 0) { "no event to edit" }
        return take(i) + change(this[i]) + drop(i + 1)
    }

    private fun isCompletion(e: TraceEvent) = e is TraceEvent.Committed && e.completedCall != null

    private val seeds: List<Seeded> by lazy {
        val paid = find { it.crashes == 0 && it.finished("paid") }
        val lost = find { it.finished("unknown") && it.truth.processed.isEmpty() }
        val transient = find(Profile.Transient, crashWindow = 24) { h ->
            val e = h.tracer.events
            h.crashes == 1 && e.count { it is TraceEvent.Recovered } == 2 &&
                e.indexOfFirst { it is TraceEvent.Replied } in 0 until e.indexOfLast { it is TraceEvent.Recovered }
        }
        // Payer p1's call 1.0 is sent in epoch 0, which the crash ends, and sent and paid again in epoch 1.
        val restart = find(Profile.Transient, crashWindow = 24, payAgain = true) { h ->
            h.crashes == 1 && h.tracer.events.count { it is TraceEvent.Recovered } == 2 &&
                call in h.truth.at(0).effectOf && h.truth.at(1).appliedCount(call) == 1 && h.finished("paid")
        }
        val p = paid.tracer.events
        listOf(
            Seeded("clean-paid", paid, p),
            Seeded("clean-lost", lost, lost.tracer.events),
            Seeded("clean-transient", transient, transient.tracer.events),
            Seeded("clean-transient-restart", restart, restart.tracer.events),
            Seeded("nested-decide", paid, p.edit({ it is TraceEvent.DecideStarted }) { listOf(it, it) }),
            Seeded("send-before-commit", paid, p.edit({ it is TraceEvent.Committed && it.newCalls.isNotEmpty() }) { emptyList() }),
            Seeded("double-completion", paid, p.edit(::isCompletion) { listOf(it, it) }),
            Seeded("never-completed", paid, p.edit(::isCompletion) { emptyList() }),
            Seeded(
                "completion-never-committed", paid,
                p.edit(::isCompletion) { listOf(it, (it as TraceEvent.Committed).copy(completedCall = CallId(payer, CallKey("9.0")))) },
            ),
            Seeded("false-not-done", paid, p.edit(::isCompletion) { listOf((it as TraceEvent.Committed).copy(completedOutcome = Outcome.NotDone(NotDoneReason.NoAnswer))) }),
            Seeded("false-done", lost, lost.tracer.events.edit(::isCompletion) { listOf((it as TraceEvent.Committed).copy(completedOutcome = Outcome.Done("ok"))) }),
            Seeded("non-idempotent-resend", paid, p.edit({ it is TraceEvent.CallSent }) { listOf(it, (it as TraceEvent.CallSent).copy(attempt = 2)) }),
            Seeded("double-reply", paid, p.edit({ it is TraceEvent.Replied }) { listOf(it, it) }),
            Seeded("transient-double-reply", transient, transient.tracer.events.edit({ it is TraceEvent.Replied }) { listOf(it, it) }),
            Seeded(
                "transient-false-not-done-after-restart", restart,
                restart.tracer.events.edit(::isCompletion, last = true) {
                    listOf((it as TraceEvent.Committed).copy(completedOutcome = Outcome.NotDone(NotDoneReason.NoAnswer)))
                },
            ),
        )
    }

    @Test
    fun `Oracles check finds each seeded violation and nothing in the clean runs`() {
        val expected = mapOf(
            "clean-paid" to emptyList(),
            "clean-lost" to emptyList(),
            "clean-transient" to emptyList(),
            "clean-transient-restart" to emptyList(),
            "nested-decide" to listOf("R3"),
            "send-before-commit" to listOf("R5", "R6"),
            "double-completion" to listOf("R6"),
            "never-completed" to listOf("R6"),
            "completion-never-committed" to listOf("R6"),
            "false-not-done" to listOf("R7"),
            "false-done" to listOf("R7"),
            "non-idempotent-resend" to listOf("R8"),
            "double-reply" to listOf("R9"),
            "transient-double-reply" to listOf("[epoch 0] R9"),
            "transient-false-not-done-after-restart" to listOf("[epoch 1] R7"),
        )
        for (s in seeds) {
            assertEquals(expected.getValue(s.name), s.violations.map { it.substringBefore(':') }, "${s.name}: ${s.violations}")
        }
    }

    @Test
    fun `the restart run reuses call identities, so each epoch is judged against its own ground truth`() {
        val restart = seeds.single { it.name == "clean-transient-restart" }.base
        assertEquals(listOf(1, 1), (0..1).map { restart.tracer.events.epochOf(it).count { e -> e is TraceEvent.CallSent && e.callId == call } })
        assertTrue(call in restart.truth.at(0).effectOf && call in restart.truth.at(1).effectOf)
        assertEquals(1, restart.truth.at(1).appliedCount(call))
    }

    private fun List<TraceEvent>.epochOf(n: Int): List<TraceEvent> {
        val starts = indices.filter { this[it] is TraceEvent.Recovered && it > 0 }
        val from = if (n == 0) 0 else starts[n - 1]
        val to = starts.getOrElse(n) { size }
        return subList(from, to)
    }

    @Test
    fun `tools oracles py reports what Oracles check reports on clean and seeded traces`() {
        assumeTrue(ConformanceTraces.unavailable == null) { "skipped: ${ConformanceTraces.unavailable}" }
        val files = seeds.associate { s ->
            // Controls pass, so they go with the samples; every trace in the seeded directory must fail.
            val file = if (s.control) {
                ConformanceTraces.directory.resolve("seeded-controls--${s.name.removePrefix("clean-")}.jsonl")
            } else {
                ConformanceTraces.seeded.resolve("${s.name}.jsonl")
            }
            TraceJson.write(file, s.events, s.base.truth, s.base.profile, quiescent = s.base.scheduler.pending == 0)
            file to s.violations
        }
        ConformanceTraces.assertPythonAgrees(files)
    }
}
