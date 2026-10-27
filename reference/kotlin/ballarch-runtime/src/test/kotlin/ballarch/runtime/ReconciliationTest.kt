package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.BallId
import ballarch.kernel.EffectClass
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.UnknownReason
import ballarch.testkit.ExhaustiveChooser
import ballarch.testkit.Fate
import ballarch.testkit.Harness
import ballarch.testkit.Profile
import ballarch.testkit.RandomChooser
import ballarch.testkit.SimPort
import ballarch.testkit.TraceJson
import ballarch.testkit.explore
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reconciled ports (Core §3.2, optional): a call left without an answer is
 * settled by a status read once the destination's delivery horizon has passed
 * since its last attempt was sent, also across a Durable restart.
 */
class ReconciliationTest {
    private val payer = BallId("payer", "p1")
    private val charge = AdapterPort<Int, String>("charge", EffectClass.NonIdempotent)

    /** What the destination says about an idempotency key: the answer of the request applied with it, or `null`. */
    private val chargeStatus = AdapterPort<String, String?>("charge.status", EffectClass.Safe)

    private fun reconciliation(horizonMillis: Long = HORIZON, statusTimeoutMillis: Long = STATUS_TIMEOUT) =
        Reconciliation<Int, String, String, String?>(
            chargeStatus, horizonMillis, statusTimeoutMillis,
            query = { it.idempotencyKey },
            verdict = { answer -> if (answer != null) StatusVerdict.Applied(answer) else StatusVerdict.NotApplied },
        )

    /** A payer whose [port] reconciles through [chargeStatus], answered by [sim]'s status endpoint. */
    private fun reconciled(
        sim: SimPort<Int, String>,
        statusFates: List<Fate>,
        port: AdapterPort<Int, String> = charge,
        retry: RetryPolicy = RetryPolicy.None,
        reconcile: Boolean = true,
    ): Composition = Composition()
        .ball(PayerBall(port))
        .adapter(port, sim, retry, reconcile = if (reconcile) reconciliation() else null)
        .adapter(chargeStatus, sim.status(statusFates) { it })

    private fun Harness.outcome(): String = (runtime.inspect(payer) as? PayerState.Finished)?.outcome ?: runtime.inspect(payer)!!::class.simpleName!!

    private fun Harness.resolutions(): List<TraceEvent.CallResolved> = tracer.events.filterIsInstance<TraceEvent.CallResolved>()

    private fun Harness.sends(): List<Long> = tracer.events.filterIsInstance<TraceEvent.CallSent>().map { it.at }

    private fun Harness.traceLines(): List<String> =
        StringWriter().also { TraceJson.write(it, tracer.events, truth, profile, quiescent = scheduler.pending == 0) }.toString().lines()

    // ---------------------------------------------------------------- S10

    private class Explored(val runs: Int, val finals: Map<String, Int>, val settledDone: Int, val settledNotDone: Int, val falseNotDone: Int)

    /**
     * Explores one payment on a NonIdempotent reconciled port: every fate of the
     * charge and of the status query, and one crash point within [crashWindow]
     * tasks (none if 0). Counts the calls the status settled, and the runs in
     * which the oracles find a violation.
     */
    private fun exploreReconciled(fates: List<Fate>, crashWindow: Int, samples: ConformanceTraces.Sampler?, onViolation: (Harness) -> Unit): Explored {
        val finals = sortedMapOf<String, Int>()
        var settledDone = 0
        var settledNotDone = 0
        var falseNotDone = 0
        val runs = explore(ExhaustiveChooser(maxChoices = 8)) { chooser ->
            val h = Harness(chooser, Profile.Durable) {
                val sim = SimPort(charge, scheduler, chooser, truth, fates, refuse = { "refused" }, horizonMillis = HORIZON) { "ok-$it" }
                reconciled(sim, statusFates = listOf(Fate.Reply, Fate.LoseRequest))
            }
            if (crashWindow > 0) h.maybeCrashOnce(window = crashWindow)
            h.start()
            h.send(payer, Pay(10, timeoutMillis = TIMEOUT))
            h.runUntilIdle()
            val label = h.outcome().substringBefore(':')
            finals.merge(label, 1, Int::plus)
            val settled = h.resolutions().filter { it.reconciled }
            settledDone += settled.count { it.outcome is Outcome.Done }
            settledNotDone += settled.count { it.outcome is Outcome.NotDone }
            val violations = h.violations()
            if (violations.isNotEmpty()) {
                assertTrue(violations.all { it.startsWith("R7: ") && "reported NotDone(NotApplied)" in it }, "$violations; choices=${chooser.history}")
                falseNotDone++
                onViolation(h)
                return@explore
            }
            val applied = h.truth.applied.values.sum()
            when (label) {
                "paid" -> assertEquals(1, applied, "choices=${chooser.history}")
                "not-paid" -> assertEquals(0, applied, "choices=${chooser.history}")
                else -> assertTrue(applied <= 1, "choices=${chooser.history}")
            }
            samples?.offer(variant(label, h), h)
        }
        samples?.crossCheck()
        return Explored(runs, finals, settledDone, settledNotDone, falseNotDone)
    }

    /** Names a sample trace by the run's result and whether the runtime crashed before or after it sent the call. */
    private fun variant(result: String, h: Harness): String {
        if (h.crashes == 0) return result
        val events = h.tracer.events
        val restart = events.indexOfLast { it is TraceEvent.Recovered }.coerceAtLeast(0)
        return result + if (events.take(restart).any { it is TraceEvent.CallSent }) "-crash-after-send" else "-crash"
    }

    /**
     * S10: on a reconciled port, a destination that keeps its delivery horizon
     * passes every oracle under every fate of the charge and the status query
     * and every crash point, and the status settles calls both ways. A
     * destination that applies a request after its horizon makes the status
     * report `NotDone` for an effect that then takes place, which T7 catches
     * (the negative control, written as a seeded trace).
     */
    @Test
    fun `an honest destination passes every oracle, and one that applies after its horizon is caught`() {
        val honest = exploreReconciled(Fate.classic, crashWindow = 16, ConformanceTraces.Sampler("non-idempotent-reconciled-durable")) {
            throw AssertionError("an honest destination gave a violation: ${it.violations()}; choices=${it.chooser.history}")
        }
        println(
            "reconciled (NonIdempotent, Durable, horizon $HORIZON ms, status timeout $STATUS_TIMEOUT ms, call timeout $TIMEOUT ms, " +
                "crash window 16): ${honest.runs} runs, outcomes ${honest.finals}; settled by the status: ${honest.settledDone} Done, " +
                "${honest.settledNotDone} NotDone",
        )
        assertEquals(0, honest.falseNotDone)
        assertTrue(honest.settledDone > 0 && honest.settledNotDone > 0)
        assertTrue(honest.finals.keys.containsAll(setOf("paid", "not-paid", "unknown")), "${honest.finals}")

        var seeded: Harness? = null
        val control = exploreReconciled(Fate.classic + Fate.ApplyAfterHorizon, crashWindow = 0, samples = null) { if (seeded == null) seeded = it }
        println("applying after the horizon (negative control, no crash): ${control.runs} runs, ${control.falseNotDone} with a false NotDone")
        assertTrue(control.falseNotDone > 0)
        val h = seeded!!
        val file = ConformanceTraces.seeded.resolve("reconciled-apply-after-horizon.jsonl")
        h.writeTrace(file)
        ConformanceTraces.assertPythonAgrees(mapOf(file to h.violations()))
    }

    // ---------------------------------------------------------------- the cutoff and the horizon

    @Test
    fun `no attempt is sent after the cutoff, and the status is read the horizon after the last send`() {
        val idem = AdapterPort<Int, String>("charge.idem", EffectClass.Idempotent)
        fun run(attemptTimeoutMillis: Long?): Harness = Harness(RandomChooser(1)) {
            val sim = SimPort(idem, scheduler, chooser, truth, listOf(Fate.LoseRequest)) { "ok-$it" }
            Composition()
                .ball(PayerBall(idem))
                .adapter(idem, sim, RetryPolicy(maxAttempts = 3, attemptTimeoutMillis = attemptTimeoutMillis), reconcile = reconciliation(50, 30))
                .adapter(chargeStatus, sim.status(listOf(Fate.Reply)) { it })
        }.start().apply {
            send(payer, Pay(10, timeoutMillis = 200)) // committed at 1000; cutoff 1000 + 200 - 50 - 30 = 1120
            runUntilIdle()
            assertInvariants()
        }
        // Attempts every 40 ms; the third ends at the cutoff. The status is read 50 ms after it was sent, 10 ms later answered.
        val timed = run(attemptTimeoutMillis = 40)
        assertEquals(listOf(1_000L, 1_040L, 1_080L), timed.sends())
        val settled = timed.resolutions().single()
        assertEquals(1_140, settled.at)
        assertTrue(settled.reconciled)
        assertIs<NotDoneReason.NotApplied>((settled.outcome as Outcome.NotDone<*>).reason)
        assertEquals("not-paid:NotApplied", timed.outcome())
        // Without an attempt timeout, the first attempt ends at the cutoff, too late for a retry.
        val untimed = run(attemptTimeoutMillis = null)
        assertEquals(listOf(1_000L), untimed.sends())
        assertEquals(1_130, untimed.resolutions().single().at)
    }

    @Test
    fun `a waiting call past its cutoff is not sent, and its slot goes to the next waiting call`() {
        val h = Harness(RandomChooser(1)) {
            val sim = SimPort(charge, scheduler, chooser, truth, listOf(Fate.Reply), latencyMillis = 30) { "ok-$it" }
            Composition()
                .ball(PayerBall(charge))
                .adapter(charge, sim, maxInFlight = 1, reconcile = reconciliation(50, 30))
                .adapter(chargeStatus, sim.status(listOf(Fate.Reply)) { it })
        }.start()
        h.send(BallId("payer", "a"), Pay(10, timeoutMillis = 1_000)) // holds the slot until its answer at 1030
        h.send(BallId("payer", "b"), Pay(10, timeoutMillis = 100)) // waits; its cutoff is 1000 + 100 - 50 - 30 = 1020
        h.send(BallId("payer", "c"), Pay(10, timeoutMillis = 1_000)) // waits behind b
        h.runUntilIdle()
        h.assertInvariants()
        assertEquals(listOf(1_000L, 1_030L), h.sends())
        assertEquals(listOf("payer/a", "payer/c"), h.tracer.events.filterIsInstance<TraceEvent.CallSent>().map { it.callId.caller.toString() })
        assertEquals(listOf<Outcome<Any?>?>(Outcome.Done("paid:ok-10"), Outcome.Done("not-paid:DeadlineBeforeSend"), Outcome.Done("paid:ok-10")), h.externalOutcomes)
        assertEquals(1_060, h.resolutions().single { it.callId.caller.key == "c" }.at)
    }

    @Test
    fun `a retry that still waits for a slot at its cutoff is reconciled`() {
        val idem = AdapterPort<Int, String>("charge.idem", EffectClass.Idempotent)
        val statusReads = ArrayList<Long>()
        val b = BallId("payer", "b")
        val h = Harness(RandomChooser(1)) {
            Composition()
                .ball(PayerBall(idem))
                .adapter(
                    idem,
                    // Every attempt of b fails 5 ms after its send; a's attempt never reports, so it keeps the only slot.
                    Adapter<Int, String> { inv, done -> if (inv.callId.caller == b) scheduler.schedule(5) { done(AdapterResult.Failed("reset")) }; null },
                    RetryPolicy(maxAttempts = 3),
                    maxInFlight = 1,
                    reconcile = reconciliation(50, 30),
                )
                .adapter(
                    chargeStatus,
                    Adapter<String, String?> { _, done -> statusReads += scheduler.now(); scheduler.schedule(10) { done(AdapterResult.Ok(null)) }; null },
                )
        }.start()
        h.send(b, Pay(10, timeoutMillis = 200)) // sent at 1000, fails at 1005; cutoff 1000 + 200 - 50 - 30 = 1120
        h.send(BallId("payer", "a"), Pay(10, timeoutMillis = 1_000)) // takes the slot at 1005 and keeps it until its cutoff, 1920
        h.runUntilIdle()
        // b's retry gets no slot before its cutoff, so no attempt remains: the status is read then, not left Unknown at the deadline.
        assertEquals(listOf(1_000L), h.tracer.events.filterIsInstance<TraceEvent.CallSent>().filter { it.callId.caller == b }.map { it.at })
        val settled = h.resolutions().single { it.callId.caller == b }
        assertEquals(1_130, settled.at)
        assertTrue(settled.reconciled)
        assertIs<NotDoneReason.NotApplied>((settled.outcome as Outcome.NotDone<*>).reason)
        assertEquals(listOf(1_120L, 1_920L), statusReads)
    }

    @Test
    fun `a call on a reconciled port needs a timeout above the horizon and the status timeout together`() {
        val h = Harness(RandomChooser(1)) {
            reconciled(SimPort(charge, scheduler, chooser, truth, listOf(Fate.Reply)) { "ok-$it" }, listOf(Fate.Reply))
        }.start()
        h.send(payer, Pay(10, timeoutMillis = HORIZON + STATUS_TIMEOUT))
        h.send(BallId("payer", "p2"), Pay(10, timeoutMillis = HORIZON + STATUS_TIMEOUT + 1))
        h.runUntilIdle()
        val refused = assertIs<Outcome.NotDone<*>>(h.externalOutcomes[0])
        assertTrue("must exceed the port's delivery horizon and status timeout together" in (refused.reason as NotDoneReason.Fault).message, "$refused")
        assertEquals(Outcome.Done("paid:ok-10"), h.externalOutcomes[1])
        assertEquals(1, h.sends().size)
    }

    // ---------------------------------------------------------------- outcomes

    @Test
    fun `a status that settles nothing leaves the reason the call would have had without reconciliation`() {
        val h = Harness(RandomChooser(1)) {
            reconciled(SimPort(charge, scheduler, chooser, truth, listOf(Fate.LoseRequest)) { "ok-$it" }, listOf(Fate.LoseRequest))
        }.start()
        h.send(payer, Pay(10, timeoutMillis = TIMEOUT))
        h.runUntilIdle()
        h.assertInvariants()
        val resolved = h.resolutions().single()
        assertEquals(Outcome.Unknown(UnknownReason.LostAfterSend), resolved.outcome)
        assertEquals(1_000 + HORIZON + STATUS_TIMEOUT, resolved.at) // the status query timed out
        assertTrue(!resolved.reconciled)
        val line = h.traceLines().single { "\"CallResolved\"" in it }
        assertTrue("reconciled" !in line, line)
    }

    @Test
    fun `an answer that arrives while the call is reconciling resolves it Done`() {
        var answer: ((AdapterResult<String>) -> Unit)? = null
        val statusQueries = ArrayList<String>()
        val h = Harness(RandomChooser(1)) {
            Composition()
                .ball(PayerBall(charge))
                .adapter(charge, Adapter { _, done -> answer = done; null }, reconcile = reconciliation())
                .adapter(chargeStatus, Adapter<String, String?> { inv, _ -> statusQueries += inv.request; null })
        }.start()
        h.send(payer, Pay(10, timeoutMillis = TIMEOUT))
        h.scheduler.runUntilIdle(until = 1_070) // the cutoff, at 1050, ended the attempt
        assertEquals(Phase.Reconciling, h.runtime.record(payer)!!.calls.values.single().phase)
        answer!!(AdapterResult.Ok("late"))
        h.runUntilIdle()
        assertEquals("paid:late", h.outcome())
        assertTrue(!h.resolutions().single().reconciled)
        assertEquals(emptyList(), statusQueries, "the status was asked although the call was answered")
    }

    // ---------------------------------------------------------------- Durable restarts

    @Test
    fun `a call recovered while reconciling resumes its status query the horizon after its last send`() {
        val h = Harness(RandomChooser(1)) {
            reconciled(SimPort(charge, scheduler, chooser, truth, listOf(Fate.LoseRequest)) { "ok-$it" }, listOf(Fate.Reply))
        }.start()
        h.send(payer, Pay(10, timeoutMillis = TIMEOUT)) // sent at 1000; cutoff 1050; status due 1100
        h.scheduler.runUntilIdle(until = 1_070)
        val call = h.runtime.record(payer)!!.calls.values.single()
        assertEquals(Phase.Reconciling, call.phase)
        assertEquals(1_000L, call.lastSentAt)
        assertEquals(UnknownReason.LostAfterSend, call.unknownReason)
        h.crashAndRestart(delay = 10)
        h.runUntilIdle()
        h.assertInvariants()
        val settled = h.resolutions().single()
        assertEquals(1_110, settled.at) // read at 1100, not a horizon after the restart
        assertTrue(settled.reconciled)
        assertEquals("not-paid:NotApplied", h.outcome())
        assertEquals(1, h.sends().size)
        assertTrue(h.traceLines().single { "\"CallResolved\"" in it }.endsWith(""","reconciled":true}"""))
    }

    @Test
    fun `a possibly sent call recovered on a reconciled port is reconciled instead of left Unknown`() {
        fun run(reconcile: Boolean): Harness = Harness(RandomChooser(1)) {
            reconciled(SimPort(charge, scheduler, chooser, truth, listOf(Fate.LoseReply)) { "ok-$it" }, listOf(Fate.Reply), reconcile = reconcile)
        }.start().apply {
            send(payer, Pay(10, timeoutMillis = TIMEOUT))
            scheduler.runUntilIdle(until = 1_020) // the charge was applied at 1010; its answer is lost
            assertEquals(Phase.Sending, runtime.record(payer)!!.calls.values.single().phase)
            crashAndRestart(delay = 10)
            runUntilIdle()
            assertInvariants()
            assertEquals(1, sends().size)
            assertEquals(1, truth.applied.values.sum())
        }
        val reconciled = run(reconcile = true)
        assertEquals("paid:ok-10", reconciled.outcome())
        assertEquals(1_110, reconciled.resolutions().single().at)
        assertEquals("unknown:RecoveredAfterCrash", run(reconcile = false).outcome())
    }

    @Test
    fun `a reconciling call recovered under a binding without reconciliation resolves Unknown with its recorded reason`() {
        var reconcile = true
        val h = Harness(RandomChooser(1)) {
            reconciled(SimPort(charge, scheduler, chooser, truth, listOf(Fate.LoseRequest)) { "ok-$it" }, listOf(Fate.Reply), reconcile = reconcile)
        }.start()
        h.send(payer, Pay(10, timeoutMillis = TIMEOUT))
        h.scheduler.runUntilIdle(until = 1_070)
        reconcile = false
        h.crashAndRestart(delay = 10)
        h.runUntilIdle()
        h.assertInvariants()
        assertEquals("unknown:LostAfterSend", h.outcome())
        assertEquals(1_080, h.resolutions().single().at)
    }

    // ---------------------------------------------------------------- configuration

    @Test
    fun `reconciliation is refused on a Safe port, through a port that is not Safe or not bound, and without positive times`() {
        val safe = AdapterPort<Int, String>("lookup", EffectClass.Safe)
        val noop = Adapter<Int, String> { _, _ -> null }
        val safeReconciliation = Reconciliation<Int, String, String, String?>(chargeStatus, HORIZON, STATUS_TIMEOUT, { it.idempotencyKey }, { StatusVerdict.Inconclusive })
        assertFailsWith<IllegalArgumentException> { Composition().adapter(safe, noop, reconcile = safeReconciliation) }
        val idempotentStatus = AdapterPort<String, String?>("charge.status", EffectClass.Idempotent)
        assertFailsWith<IllegalArgumentException> {
            Reconciliation<Int, String, String, String?>(idempotentStatus, HORIZON, STATUS_TIMEOUT, { it.idempotencyKey }, { StatusVerdict.Inconclusive })
        }
        assertFailsWith<IllegalArgumentException> { reconciliation(horizonMillis = 0) }
        assertFailsWith<IllegalArgumentException> { reconciliation(statusTimeoutMillis = 0) }
        val unbound = Composition().ball(PayerBall(charge)).adapter(charge, noop, reconcile = reconciliation())
        val e = assertFailsWith<IllegalArgumentException> { Runtime(unbound, ManualScheduler(), MemoryStore()) }
        assertTrue("does not bind as a Safe port" in e.message!!, e.message)
    }

    private companion object {
        const val HORIZON = 100L
        const val STATUS_TIMEOUT = 50L
        const val TIMEOUT = 200L
    }
}
