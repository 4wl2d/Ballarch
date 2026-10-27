package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.BallId
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.RequestContext
import ballarch.kernel.accept
import ballarch.testkit.Fate
import ballarch.testkit.Harness
import ballarch.testkit.Profile
import ballarch.testkit.RandomChooser
import ballarch.testkit.SimPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeTest {
    private val counter = BallId("counter", "c1")
    private val payer = BallId("payer", "p1")

    private fun harness(
        fates: List<Fate> = listOf(Fate.Reply),
        port: AdapterPort<Int, String> = PaymentPort,
        retry: RetryPolicy = RetryPolicy.None,
        maxInFlight: Int = 64,
        limits: Limits = Limits(),
        profile: Profile = Profile.Durable,
    ) = Harness(RandomChooser(1), profile, limits) {
        Composition()
            .ball(CounterBall())
            .ball(PayerBall(port))
            .ball(RelayBall())
            .ball(AuditBall())
            .adapter(port, SimPort(port, scheduler, chooser, truth, fates, refuse = { "refused" }) { "ok-$it" }, retry, maxInFlight)
            .subscribe(CounterChanged, "audit") { "all" }
    }.start()

    // ---------------------------------------------------------------- local decisions

    @Test
    fun `accepted request commits state and replies`() {
        val h = harness()
        h.send(counter, CounterMsg.Increment)
        h.runUntilIdle()
        assertEquals(Outcome.Done(CounterReply.Value(1)), h.externalOutcomes[0])
        assertEquals(1, h.runtime.inspect(counter))
        h.assertInvariants()
    }

    @Test
    fun `rejected request commits nothing and is reported as refused`() {
        val h = harness()
        repeat(4) { h.send(counter, CounterMsg.Increment) }
        h.runUntilIdle()
        assertEquals(Outcome.NotDone(NotDoneReason.Refused(CounterReply.LimitReached)), h.externalOutcomes[3])
        assertEquals(3, h.runtime.inspect(counter))
        assertEquals(3L, h.runtime.record(counter)!!.revision)
        h.assertInvariants()
    }

    @Test
    fun `a throwing decider commits nothing and the instance keeps working`() {
        val h = harness()
        h.send(counter, CounterMsg.Increment)
        h.send(counter, CounterMsg.Explode)
        h.send(counter, CounterMsg.Increment)
        h.runUntilIdle()
        val fault = h.externalOutcomes[1]
        assertIs<Outcome.NotDone<*>>(fault)
        assertIs<NotDoneReason.Fault>(fault.reason)
        assertEquals(2, h.runtime.inspect(counter))
        h.assertInvariants()
    }

    @Test
    fun `inbox bound refuses excess requests before acceptance`() {
        val h = harness(limits = Limits(maxInbox = 2))
        repeat(5) { h.send(counter, CounterMsg.Get) }
        h.runUntilIdle()
        val overloaded = h.externalOutcomes.count { it == Outcome.NotDone(NotDoneReason.Overloaded) }
        assertTrue(overloaded >= 1, "expected at least one Overloaded, got ${h.externalOutcomes}")
        h.assertInvariants()
    }

    // ---------------------------------------------------------------- adapter calls and outcome classification

    private fun payOnce(fates: List<Fate>, port: AdapterPort<Int, String> = PaymentPort, retry: RetryPolicy = RetryPolicy.None): Harness {
        val h = harness(fates = fates, port = port, retry = retry)
        h.send(payer, Pay(10))
        h.runUntilIdle()
        h.assertInvariants()
        return h
    }

    @Test
    fun `answered call completes as Done`() {
        val h = payOnce(listOf(Fate.Reply))
        assertEquals(Outcome.Done("paid:ok-10"), h.externalOutcomes[0])
    }

    @Test
    fun `refused connection completes as NotDone with evidence`() {
        val h = payOnce(listOf(Fate.RefuseConnection))
        assertEquals(Outcome.Done("not-paid:NotApplied"), h.externalOutcomes[0])
    }

    @Test
    fun `destination refusal completes as NotDone Refused`() {
        val h = payOnce(listOf(Fate.Refuse))
        assertEquals(Outcome.Done("not-paid:Refused"), h.externalOutcomes[0])
    }

    @Test
    fun `error after the request left is Unknown for a non-idempotent port`() {
        val h = payOnce(listOf(Fate.ErrorAfterApply))
        assertEquals(Outcome.Done("unknown:LostAfterSend"), h.externalOutcomes[0])
        assertEquals(1, h.truth.applied.values.single())
    }

    @Test
    fun `lost request ends Unknown at the deadline, never NotDone`() {
        val h = payOnce(listOf(Fate.LoseRequest))
        assertEquals(Outcome.Done("unknown:DeadlineAfterSend"), h.externalOutcomes[0])
    }

    @Test
    fun `late answer after the deadline is dropped, not applied twice`() {
        val h = payOnce(listOf(Fate.ReplyLate))
        assertEquals(Outcome.Done("unknown:DeadlineAfterSend"), h.externalOutcomes[0])
        assertEquals(listOf("Ok"), h.tracer.events.filterIsInstance<TraceEvent.LateReportDropped>().map { it.report })
    }

    @Test
    fun `safe port retries and ends NoAnswer without claiming an unknown effect`() {
        val safe = AdapterPort<Int, String>("lookup", EffectClass.Safe)
        val h = payOnce(listOf(Fate.ErrorAfterApply), port = safe, retry = RetryPolicy(maxAttempts = 3))
        assertEquals(Outcome.Done("not-paid:NoAnswer"), h.externalOutcomes[0])
        assertEquals(3, h.tracer.events.count { it is TraceEvent.CallSent })
    }

    @Test
    fun `idempotent port retries with the same identity and the destination applies once`() {
        val idem = AdapterPort<Int, String>("idem-payment", EffectClass.Idempotent)
        val h = payOnce(listOf(Fate.ErrorAfterApply), port = idem, retry = RetryPolicy(maxAttempts = 3))
        assertEquals(Outcome.Done("unknown:RetriesExhausted"), h.externalOutcomes[0])
        assertEquals(1, h.truth.applied.values.single())
        assertEquals(3, h.tracer.events.count { it is TraceEvent.CallSent })
    }

    @Test
    fun `non-idempotent ports cannot be configured with retries`() {
        val ex = runCatching {
            Composition().adapter(PaymentPort, { _, _ -> null }, RetryPolicy(maxAttempts = 2))
        }.exceptionOrNull()
        assertIs<IllegalArgumentException>(ex)
    }

    @Test
    fun `a call still waiting for a slot at its deadline ends NotDone and is never sent`() {
        val h = harness(fates = listOf(Fate.LoseRequest), maxInFlight = 1)
        h.send(BallId("payer", "a"), Pay(1, timeoutMillis = 500))
        h.send(BallId("payer", "b"), Pay(2, timeoutMillis = 100))
        h.runUntilIdle()
        assertEquals(Outcome.Done("not-paid:DeadlineBeforeSend"), h.externalOutcomes[1])
        assertEquals(1, h.tracer.events.count { it is TraceEvent.CallSent })
        h.assertInvariants()
    }

    @Test
    fun `cancelling a call that was not sent yet completes it as Cancelled`() {
        val h = harness(fates = listOf(Fate.LoseRequest), maxInFlight = 1)
        h.send(BallId("payer", "a"), Pay(1, timeoutMillis = 500))
        h.send(BallId("payer", "b"), Pay(2, timeoutMillis = 400))
        h.send(BallId("payer", "b"), CancelPayment)
        h.runUntilIdle()
        assertEquals(Outcome.Done("not-paid:Cancelled"), h.externalOutcomes[1])
        h.assertInvariants()
    }

    // ---------------------------------------------------------------- Ball-to-Ball

    @Test
    fun `a Ball call to another Ball completes with its reply`() {
        val h = harness()
        h.send(BallId("relay", "r"), Forward("c1", CounterMsg.Increment))
        h.runUntilIdle()
        assertEquals(Outcome.Done("done:Value(value=1)"), h.externalOutcomes[0])
        h.assertInvariants()
    }

    @Test
    fun `a rejection by the destination Ball reaches the caller as NotDone Refused`() {
        val h = harness()
        repeat(3) { h.send(counter, CounterMsg.Increment) }
        h.send(BallId("relay", "r"), Forward("c1", CounterMsg.Increment))
        h.runUntilIdle()
        assertEquals(Outcome.Done("not-done:Refused(reply=LimitReached)"), h.externalOutcomes[3])
        h.assertInvariants()
    }

    // ---------------------------------------------------------------- idempotent ingress

    @Test
    fun `repeated external request with the same key replays the original reply`() {
        val h = harness()
        h.send(counter, CounterMsg.Increment, idempotencyKey = "k1")
        h.runUntilIdle()
        h.send(counter, CounterMsg.Increment, idempotencyKey = "k1")
        h.runUntilIdle()
        assertEquals(h.externalOutcomes[0], h.externalOutcomes[1])
        assertEquals(1, h.runtime.inspect(counter))
    }

    @Test
    fun `same key with a different message is an idempotency conflict`() {
        val h = harness()
        h.send(counter, CounterMsg.Increment, idempotencyKey = "k1")
        h.runUntilIdle()
        h.send(counter, CounterMsg.Get, idempotencyKey = "k1")
        h.runUntilIdle()
        assertEquals(Outcome.NotDone(NotDoneReason.IdempotencyConflict), h.externalOutcomes[1])
    }

    @Test
    fun `a repeat while the first request is still open joins it`() {
        val h = harness()
        h.send(payer, Pay(10), idempotencyKey = "pay-1")
        h.send(payer, Pay(10), idempotencyKey = "pay-1")
        h.runUntilIdle()
        assertEquals(Outcome.Done("paid:ok-10"), h.externalOutcomes[0])
        assertEquals(h.externalOutcomes[0], h.externalOutcomes[1])
        assertEquals(1, h.truth.applied.size)
    }

    // ---------------------------------------------------------------- notices

    @Test
    fun `published notices reach subscribers after the publisher commits`() {
        val h = harness()
        repeat(3) { h.send(counter, CounterMsg.Increment) }
        h.runUntilIdle()
        assertEquals(listOf(1, 2, 3), h.runtime.inspect(BallId("audit", "all")))
        assertTrue(h.runtime.record(counter)!!.outbox.isEmpty())
        h.assertInvariants()
    }

    // ---------------------------------------------------------------- reads (R12)

    @Test
    fun `one effectful call answered Done costs three durable writes, and reads cost none`() {
        val h = harness()
        h.send(payer, Pay(10))
        h.runUntilIdle()
        assertEquals(Outcome.Done("paid:ok-10"), h.externalOutcomes[0])
        // The commit that requests the call, its write-ahead record, and the commit of its completion.
        assertEquals(3L, h.store.saves)
        val ids = h.store.ids()
        assertEquals(Snapshot(payer, 2, PayerState.Finished("paid:ok-10")), h.runtime.read(PayerBall(), payer.key))
        val unaddressed = BallId("payer", "p2")
        assertEquals(Snapshot<Any?>(unaddressed, 0, PayerState.Idle), h.runtime.read(unaddressed))
        assertEquals(3L, h.store.saves)
        assertEquals(ids, h.store.ids())
        assertFailsWith<IllegalArgumentException> { h.runtime.read(BallId("ghost", "g")) }
    }

    @Test
    fun `a read during a step sees the state before it`() {
        val reader = BallId("reader", "r")
        val seen = ArrayList<Snapshot<Any?>>()
        lateinit var h: Harness
        h = Harness(RandomChooser(1)) {
            Composition().ball(object : Ball<Int, Unit, Int> {
                override val type = "reader"
                override fun initial(key: String) = 0

                // Not pure, on purpose: it reads its own instance while the runtime decides.
                override fun decide(state: Int, request: Unit, ctx: RequestContext): Decision<Int, Int> {
                    seen += h.runtime.read(ctx.self)
                    return accept(state + 1, state + 1)
                }
            })
        }.start()
        repeat(2) { h.send(reader, Unit) }
        h.runUntilIdle()
        assertEquals(listOf(Snapshot<Any?>(reader, 0, 0), Snapshot<Any?>(reader, 1, 1)), seen)
        assertEquals(Snapshot<Any?>(reader, 2, 2), h.runtime.read(reader))
    }

    @Test
    fun `a typed watch receives the current snapshot, then one for each commit`() {
        val h = harness()
        h.send(counter, CounterMsg.Increment)
        h.runUntilIdle()
        val seen = ArrayList<Snapshot<Int>>()
        h.runtime.watch(CounterBall(), counter.key) { seen += it }
        h.runUntilIdle()
        repeat(2) { h.send(counter, CounterMsg.Increment) }
        // A request that leaves the state as it is still commits; a read would not.
        h.send(counter, CounterMsg.Get)
        h.runUntilIdle()
        assertEquals(listOf(1L, 2L, 3L, 4L), seen.map { it.revision })
        assertEquals(listOf(1, 2, 3, 3), seen.map { it.state })
    }

    // ---------------------------------------------------------------- crashes

    @Test
    fun `durable restart resolves a possibly-sent non-idempotent call as Unknown`() {
        val h = harness(fates = listOf(Fate.LoseReply))
        h.send(payer, Pay(10, timeoutMillis = 10_000))
        h.scheduler.advance(20) // committed and sent; the answer is lost
        h.crashAndRestart()
        h.runUntilIdle()
        val finished = h.runtime.inspect(payer)
        assertEquals(PayerState.Finished("unknown:RecoveredAfterCrash"), finished)
        assertEquals(1, h.truth.applied.values.single())
        h.assertInvariants()
    }

    @Test
    fun `transient restart loses state and open calls`() {
        val h = harness(profile = Profile.Transient)
        h.send(counter, CounterMsg.Increment)
        h.runUntilIdle()
        h.crashAndRestart()
        h.runUntilIdle()
        assertEquals(0, h.runtime.inspect(counter))
    }

    @Test
    fun `a faulty completion handler quarantines only that instance`() {
        val h = Harness(RandomChooser(1)) {
            Composition()
                .ball(object : ballarch.kernel.Ball<Int, Unit, Unit> {
                    override val type = "forgetful"
                    override fun initial(key: String) = 0
                    override fun decide(state: Int, request: Unit, ctx: ballarch.kernel.RequestContext) =
                        ballarch.kernel.accept(state + 1, Unit, ballarch.kernel.Call(ctx.newKey(), PaymentPort, 1, 100))
                    // complete() is not overridden: the first completion is a programming fault
                })
                .ball(CounterBall())
                .adapter(PaymentPort, SimPort(PaymentPort, scheduler, chooser, truth, listOf(Fate.Reply)) { "ok" })
        }.start()
        h.send(BallId("forgetful", "f"), Unit)
        h.send(counter, CounterMsg.Increment)
        h.runUntilIdle()
        h.send(BallId("forgetful", "f"), Unit)
        h.runUntilIdle()
        assertTrue(h.runtime.failure(BallId("forgetful", "f"))!!.contains("does not override complete"))
        val refused = h.externalOutcomes[2]
        assertIs<Outcome.NotDone<*>>(refused)
        assertIs<NotDoneReason.Fault>(refused.reason)
        assertEquals(1, h.runtime.inspect(counter))
    }
}
