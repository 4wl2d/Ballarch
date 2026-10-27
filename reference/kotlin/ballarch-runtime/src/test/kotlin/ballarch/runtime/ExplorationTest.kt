package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.BallId
import ballarch.kernel.CallId
import ballarch.kernel.CallKey
import ballarch.kernel.EffectClass
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.accept
import ballarch.kernel.acceptLater
import ballarch.testkit.Chooser
import ballarch.testkit.ExhaustiveChooser
import ballarch.testkit.Fate
import ballarch.testkit.Harness
import ballarch.testkit.Profile
import ballarch.testkit.RandomChooser
import ballarch.testkit.SimPort
import ballarch.testkit.explore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Systematic exploration: every combination of network fates and one crash
 * point (or none) within a bounded window, with every kernel oracle checked on
 * every run. These are bounded checks of the implementation, complementing the
 * Alloy model of the abstract protocol.
 */
class ExplorationTest {
    private val payer = BallId("payer", "p1")

    /** Names a sample trace: the run's result, and whether the runtime crashed before or after it sent a call. */
    private fun variant(result: String, h: Harness): String {
        if (h.crashes == 0) return result.lowercase()
        val events = h.tracer.events
        val restart = events.indexOfLast { it is TraceEvent.Recovered }.coerceAtLeast(0)
        val sentBeforeCrash = events.take(restart).any { it is TraceEvent.CallSent }
        return result.lowercase() + if (sentBeforeCrash) "-crash-after-send" else "-crash"
    }

    private fun exploreCalls(
        scenario: String,
        port: AdapterPort<Int, String>,
        retry: RetryPolicy,
        profile: Profile,
        crashWindow: Int,
    ): Pair<Int, Map<String, Int>> {
        val finals = HashMap<String, Int>()
        val samples = ConformanceTraces.Sampler(scenario)
        val runs = explore(ExhaustiveChooser(maxChoices = 8)) { chooser ->
            val h = Harness(chooser, profile) {
                Composition()
                    .ball(PayerBall(port))
                    .adapter(port, SimPort(port, scheduler, chooser, truth, refuse = { "refused" }) { "ok-$it" }, retry)
            }
            h.maybeCrashOnce(window = crashWindow)
            h.start()
            h.send(payer, Pay(10, timeoutMillis = 200))
            h.runUntilIdle()
            h.assertInvariants()
            val state = h.runtime.inspect(payer)
            val label = when (state) {
                is PayerState.Finished -> state.outcome.substringBefore(':')
                else -> state!!::class.simpleName!!
            }
            finals.merge(label, 1, Int::plus)
            samples.offer(variant(label, h), h)
            // Business-level honesty: the Ball's own conclusion agrees with ground truth.
            if (state is PayerState.Finished) {
                val applied = h.truth.applied.values.sum()
                when {
                    state.outcome.startsWith("paid") && port.effect != EffectClass.Safe && applied == 0 ->
                        fail("paid but nothing applied; choices=${chooser.history}")
                    state.outcome.startsWith("not-paid") && port.effect != EffectClass.Safe && applied > 0 ->
                        fail("not-paid but effect applied; choices=${chooser.history}")
                }
            }
            // Durable liveness: once the request was accepted, the Ball reaches a conclusion
            // even if the runtime crashed. (A crash before acceptance loses the request.)
            if (profile == Profile.Durable && state !is PayerState.Idle) {
                assertTrue(state is PayerState.Finished, "durable run did not finish: $state; choices=${chooser.history}")
            }
        }
        samples.crossCheck()
        return runs to finals
    }

    @Test
    fun `the harness reports an accepted request left unanswered past its deadline`() {
        fun run(act: Act): Harness = Harness(RandomChooser(1)) { Composition().ball(ScriptBall()) }.apply {
            start()
            send(BallId("script", "s"), act, timeoutMillis = 100)
            runUntilIdle()
        }
        val owing = run(Act("owe") { st, ctx -> acceptLater(st + ctx.requestId) })
        owing.assertInvariants() // at most one reply: R9's MUST half holds
        assertEquals(1, owing.replyLeaks().size)
        assertFailsWith<AssertionError> { owing.assertNoReplyLeaks() }
        run(Act("answer") { st, _ -> accept(st, "ok") }).assertNoReplyLeaks()
    }

    @Test
    fun `non-idempotent call under every fate and crash point`() {
        val (runs, finals) = exploreCalls("non-idempotent-durable", PaymentPort, RetryPolicy.None, Profile.Durable, crashWindow = 24)
        println("non-idempotent durable: $runs runs, outcomes $finals")
        assertTrue(finals.keys.containsAll(setOf("paid", "not-paid", "unknown")))
    }

    @Test
    fun `idempotent call with retries under every fate and crash point`() {
        val port = AdapterPort<Int, String>("idem", EffectClass.Idempotent)
        val (runs, finals) = exploreCalls("idempotent-durable", port, RetryPolicy(maxAttempts = 3), Profile.Durable, crashWindow = 16)
        println("idempotent durable: $runs runs, outcomes $finals")
        assertTrue(finals.keys.containsAll(setOf("paid", "not-paid", "unknown")))
    }

    @Test
    fun `safe call with retries never reports an unknown effect`() {
        val port = AdapterPort<Int, String>("safe", EffectClass.Safe)
        for (profile in listOf(Profile.Durable, Profile.Transient)) {
            val name = profile.name.lowercase()
            val (runs, finals) = exploreCalls("safe-$name", port, RetryPolicy(maxAttempts = 2), profile, crashWindow = 16)
            println("safe $name: $runs runs, outcomes $finals")
            assertTrue("unknown" !in finals, "a Safe port must never end Unknown ($name): $finals")
        }
    }

    @Test
    fun `transient profile keeps the safety rules under every fate and crash point`() {
        val (runs, finals) = exploreCalls("non-idempotent-transient", PaymentPort, RetryPolicy.None, Profile.Transient, crashWindow = 24)
        println("non-idempotent transient: $runs runs, outcomes $finals")
    }

    @Test
    fun `after a transient restart a new payment reuses the call identities of the lost world`() {
        val samples = ConformanceTraces.Sampler("non-idempotent-transient-pay-again")
        val call = CallId(payer, CallKey("1.0"))
        var reused = 0
        val runs = explore(ExhaustiveChooser(maxChoices = 8)) { chooser ->
            val h = Harness(chooser, Profile.Transient) {
                Composition()
                    .ball(PayerBall())
                    .adapter(PaymentPort, SimPort(PaymentPort, scheduler, chooser, truth, refuse = { "refused" }) { "ok-$it" })
            }
            h.maybeCrashOnce(window = 24)
            h.start()
            h.send(payer, Pay(10, timeoutMillis = 200))
            h.runUntilIdle()
            // A restarted Transient runtime starts from an empty store, so this payment is call 1.0 again.
            h.send(payer, Pay(20, timeoutMillis = 200))
            h.runUntilIdle()
            h.assertInvariants()
            val again = call in h.truth.at(0).effectOf && call in h.truth.at(1).effectOf
            if (again) reused++
            val state = h.runtime.inspect(payer)
            val label = (state as? PayerState.Finished)?.outcome?.substringBefore(':') ?: state!!::class.simpleName!!
            samples.offer(variant(label, h) + if (again) "-sent-again" else "", h)
        }
        samples.crossCheck()
        println("non-idempotent transient, paying again: $runs runs, $reused send call 1.0 in two epochs")
        assertTrue(reused > 0)
    }

    /**
     * S4b: the restarted runtime repeats call identities, but not idempotency
     * keys. The destination remembers every key it has seen, across restarts,
     * so a payment answered with another payment's reply shows a repeated key.
     */
    @Test
    fun `after a transient restart a new idempotent payment never receives the lost world's reply`() {
        val port = AdapterPort<Int, String>("idem", EffectClass.Idempotent)
        val samples = ConformanceTraces.Sampler("idempotent-transient-pay-again")
        val call = CallId(payer, CallKey("1.0"))
        var reused = 0
        var paidAgain = 0
        val runs = explore(ExhaustiveChooser(maxChoices = 8)) { chooser ->
            val applied = ArrayList<Int>()
            val h = Harness(chooser, Profile.Transient) {
                Composition()
                    .ball(PayerBall(port))
                    .adapter(port, SimPort(port, scheduler, chooser, truth, refuse = { "refused" }) { applied += it; "ok-$it" })
            }
            h.maybeCrashOnce(window = 24)
            h.start()
            h.send(payer, Pay(10, timeoutMillis = 200))
            h.runUntilIdle()
            h.send(payer, Pay(20, timeoutMillis = 200))
            h.runUntilIdle()
            h.assertInvariants()
            val again = call in h.truth.at(0).effectOf && call in h.truth.at(1).effectOf
            if (again) reused++
            val second = (h.externalOutcomes[1] as? Outcome.Done)?.value as? String
            if (second != null && second.startsWith("paid")) {
                paidAgain++
                assertEquals("paid:ok-20", second, "the second payment got another payment's answer; choices=${chooser.history}")
                assertTrue(20 in applied, "the second payment is Done, but 20 was never applied; choices=${chooser.history}")
            }
            val state = h.runtime.inspect(payer)
            val label = (state as? PayerState.Finished)?.outcome?.substringBefore(':') ?: state!!::class.simpleName!!
            samples.offer(variant(label, h) + if (again) "-sent-again" else "", h)
        }
        samples.crossCheck()
        println("idempotent transient, paying again: $runs runs, $reused send call 1.0 in two epochs, $paidAgain pay 20 Done")
        assertTrue(reused > 0 && paidAgain > 0)
    }

    /** Explores one payment of [RepeaterBall] under every fate of both calls and one crash point; returns runs and runs by applications. */
    private fun exploreRepeats(sameIntent: Boolean, samples: ConformanceTraces.Sampler?, profile: Profile = Profile.Durable): Pair<Int, Map<Int, Int>> {
        val port = AdapterPort<Int, String>("idem", EffectClass.Idempotent)
        val repeater = BallId("repeater", "r1")
        val byApplications = sortedMapOf<Int, Int>()
        val runs = explore(ExhaustiveChooser(maxChoices = 8)) { chooser ->
            val h = Harness(chooser, profile) {
                Composition()
                    .ball(RepeaterBall(port, sameIntent))
                    .adapter(port, SimPort(port, scheduler, chooser, truth) { "ok-$it" })
            }
            h.maybeCrashOnce(window = 30)
            h.start()
            h.send(repeater, Pay(10, timeoutMillis = 200))
            h.runUntilIdle()
            h.assertInvariants()
            val applied = h.truth.applied.values.sum()
            byApplications.merge(applied, 1, Int::plus)
            val state = h.runtime.inspect(repeater)
            if (state is RepeatState.Finished) {
                if (state.outcome.startsWith("paid")) assertTrue(applied > 0, "paid but nothing applied; choices=${chooser.history}")
                if (state.outcome.startsWith("not-paid")) assertEquals(0, applied, "not-paid but applied; choices=${chooser.history}")
            } else {
                // Under Durable an accepted payment always finishes; under Transient a crash forgets it.
                assertEquals(RepeatState.Idle, state, "run did not finish; choices=${chooser.history}")
            }
            samples?.offer(variant((state as? RepeatState.Finished)?.outcome?.substringBefore(':') ?: "idle", h), h)
        }
        samples?.crossCheck()
        return runs to byApplications
    }

    /**
     * S8: after `Unknown`, a payment made again with the first call's intent is
     * applied at most once, across a crash anywhere, under both profiles. Made
     * again without an intent, it can be applied twice (the negative control).
     */
    @Test
    fun `a payment repeated with its intent after Unknown is applied at most once, and twice without it`() {
        val (runs, byApplications) = exploreRepeats(sameIntent = true, ConformanceTraces.Sampler("idempotent-intent-durable"))
        println("repeat by intent (Idempotent, Durable, two calls, crash window 30): $runs runs, runs by applications $byApplications")
        assertTrue(byApplications.keys.all { it <= 1 }, "$byApplications")
        val (controlRuns, control) = exploreRepeats(sameIntent = false, samples = null)
        println("repeat without intent (negative control): $controlRuns runs, runs by applications $control")
        assertTrue((control[2] ?: 0) > 0, "$control")
        val (transientRuns, transient) = exploreRepeats(sameIntent = true, ConformanceTraces.Sampler("idempotent-intent-transient"), Profile.Transient)
        println("repeat by intent (Idempotent, Transient, two calls, crash window 30): $transientRuns runs, runs by applications $transient")
        assertTrue(transient.keys.all { it <= 1 }, "$transient")
        val (transientControlRuns, transientControl) = exploreRepeats(sameIntent = false, samples = null, Profile.Transient)
        println("repeat without intent (Transient, negative control): $transientControlRuns runs, runs by applications $transientControl")
        assertTrue((transientControl[2] ?: 0) > 0, "$transientControl")
    }

    /**
     * S9: a destination that forgets a key 50 ms after its first use applies a
     * repeat by intent again when the call's deadline lies beyond that memory
     * (negative control). Declaring the port's deduplication window (50 ms less
     * 10 ms in transit) makes the runtime refuse such a call before it is sent.
     */
    @Test
    fun `a call that ends beyond the deduplication window is refused, and without the window is applied twice`() {
        val port = AdapterPort<Int, String>("idem", EffectClass.Idempotent)
        val repeater = BallId("repeater", "r1")
        fun harness(chooser: Chooser, window: Long?) = Harness(chooser, Profile.Durable) {
            Composition()
                .ball(RepeaterBall(port))
                .adapter(port, SimPort(port, scheduler, chooser, truth, forgetKeysAfterMillis = 50) { "ok-$it" }, dedupWindowMillis = window)
        }
        var twice = 0
        val runs = explore(ExhaustiveChooser(maxChoices = 8)) { chooser ->
            val h = harness(chooser, window = null).start()
            h.send(repeater, Pay(10, timeoutMillis = 100))
            h.runUntilIdle()
            h.assertInvariants()
            if (h.truth.applied.values.sum() > 1) twice++
        }
        println("repeat by intent, keys forgotten after 50 ms, call timeout 100 ms, no window declared: $runs runs, $twice apply twice")
        assertTrue(twice > 0)
        val h = harness(RandomChooser(1), window = 40).start()
        h.send(repeater, Pay(10, timeoutMillis = 100))
        h.runUntilIdle()
        h.assertInvariants()
        val refused = assertIs<Outcome.NotDone<*>>(h.externalOutcomes.single())
        assertTrue("deduplication window" in assertIs<NotDoneReason.Fault>(refused.reason).message, "$refused")
        assertTrue(h.truth.deliveries.isEmpty())
    }

    /** Passes calls to [inner] and notes which attempts reported, and whether the first answer came from an attempt that a later one had replaced. */
    private class Watched<Req, Res>(private val inner: Adapter<Req, Res>) : Adapter<Req, Res> {
        val reported = HashSet<Int>()
        private var latestSent = 0
        var firstAnswerReplaced: Boolean? = null
            private set

        override fun execute(invocation: Invocation<Req>, done: (AdapterResult<Res>) -> Unit): Cancellable? {
            latestSent = maxOf(latestSent, invocation.attempt)
            return inner.execute(invocation) { result ->
                reported += invocation.attempt
                if (result is AdapterResult.Ok && firstAnswerReplaced == null) firstAnswerReplaced = invocation.attempt < latestSent
                done(result)
            }
        }
    }

    private class AttemptRuns(val runs: Int, val finals: Map<String, Int>, val doneAfterLoss: Int, val doneByReplacedAttempt: Int)

    /**
     * Explores one payment on an Idempotent port with three attempts, under every fate of each attempt, an
     * answer latency of 10 or 40 ms, and one crash point. Counts, among runs without a crash that end paid,
     * those whose first attempt never reported, and those answered by an attempt that a later one had replaced.
     */
    private fun exploreAttemptTimeout(attemptTimeoutMillis: Long?, samples: ConformanceTraces.Sampler?): AttemptRuns {
        val port = AdapterPort<Int, String>("idem", EffectClass.Idempotent)
        val retry = RetryPolicy(maxAttempts = 3, attemptTimeoutMillis = attemptTimeoutMillis)
        val finals = sortedMapOf<String, Int>()
        var doneAfterLoss = 0
        var doneByReplacedAttempt = 0
        val runs = explore(ExhaustiveChooser(maxChoices = 8)) { chooser ->
            val latency = listOf(10L, 40L)[chooser.choose(2, "latency")]
            var watched: Watched<Int, String>? = null
            val h = Harness(chooser, Profile.Durable) {
                val adapter = Watched(SimPort(port, scheduler, chooser, truth, refuse = { "refused" }, latencyMillis = latency) { "ok-$it" })
                watched = adapter
                Composition().ball(PayerBall(port)).adapter(port, adapter, retry)
            }
            h.maybeCrashOnce(window = 12)
            h.start()
            h.send(payer, Pay(10, timeoutMillis = 200))
            h.runUntilIdle()
            h.assertInvariants()
            val applied = h.truth.applied.values.sum()
            val state = h.runtime.inspect(payer)
            val label = (state as? PayerState.Finished)?.outcome?.substringBefore(':') ?: state!!::class.simpleName!!
            finals.merge(label, 1, Int::plus)
            when (label) {
                "paid" -> assertEquals(1, applied, "choices=${chooser.history}")
                "not-paid" -> assertEquals(0, applied, "choices=${chooser.history}")
                else -> assertTrue(applied <= 1, "choices=${chooser.history}")
            }
            val adapter = watched!!
            if (label == "paid" && h.crashes == 0) {
                if (1 !in adapter.reported) doneAfterLoss++
                if (adapter.firstAnswerReplaced == true) doneByReplacedAttempt++
            }
            samples?.offer(variant(label, h), h)
        }
        samples?.crossCheck()
        return AttemptRuns(runs, finals, doneAfterLoss, doneByReplacedAttempt)
    }

    /**
     * S12: with an attempt timeout, an attempt whose request or answer is lost
     * ends when the timeout passes, and the call is retried within its
     * deadline; an answer from an attempt that a retry replaced still resolves
     * the call `Done`. Without the timeout, a lost attempt holds the call until
     * its deadline (the negative control).
     */
    @Test
    fun `with an attempt timeout a lost answer is retried within the deadline, and a late answer resolves Done`() {
        val timed = exploreAttemptTimeout(attemptTimeoutMillis = 30, ConformanceTraces.Sampler("idempotent-attempt-timeout-durable"))
        println(
            "attempt timeout 30 ms (Idempotent, Durable, 3 attempts, call timeout 200 ms, latency 10 or 40 ms, crash window 12): " +
                "${timed.runs} runs, outcomes ${timed.finals}; paid without a crash: ${timed.doneAfterLoss} after a lost first attempt, " +
                "${timed.doneByReplacedAttempt} by the answer of a replaced attempt",
        )
        assertTrue(timed.doneAfterLoss > 0 && timed.doneByReplacedAttempt > 0)
        val control = exploreAttemptTimeout(attemptTimeoutMillis = null, samples = null)
        println(
            "no attempt timeout (negative control): ${control.runs} runs, outcomes ${control.finals}; paid without a crash: " +
                "${control.doneAfterLoss} after a lost first attempt, ${control.doneByReplacedAttempt} by the answer of a replaced attempt",
        )
        assertEquals(0, control.doneAfterLoss)
        assertEquals(0, control.doneByReplacedAttempt)
    }

    @Test
    fun `Ball-to-Ball request is applied at most once across a crash anywhere`() {
        var runs = 0
        val samples = ConformanceTraces.Sampler("ball-to-ball-durable")
        explore(ExhaustiveChooser()) { chooser ->
            val h = Harness(chooser, Profile.Durable) {
                Composition().ball(CounterBall()).ball(RelayBall())
            }
            h.maybeCrashOnce(window = 20)
            h.start()
            h.send(BallId("relay", "r"), Forward("c1", CounterMsg.Increment), timeoutMillis = 5_000)
            h.runUntilIdle()
            h.assertInvariants()
            val count = h.runtime.inspect(BallId("counter", "c1")) as Int
            val relay = h.runtime.inspect(BallId("relay", "r"))
            assertTrue(count <= 1, "counter applied twice; choices=${chooser.history}")
            if (relay is RelayState.Done && relay.text.startsWith("done")) assertEquals(1, count)
            samples.offer(variant(if (relay is RelayState.Done) relay.text.substringBefore(':') else relay!!::class.simpleName!!, h), h)
            runs++
        }
        samples.crossCheck()
        println("ball-to-ball durable: $runs runs")
    }

    @Test
    fun `notices are delivered exactly once in order across a crash anywhere`() {
        var runs = 0
        val samples = ConformanceTraces.Sampler("notices-durable")
        explore(ExhaustiveChooser()) { chooser ->
            val h = Harness(chooser, Profile.Durable) {
                Composition().ball(CounterBall()).ball(AuditBall()).subscribe(CounterChanged, "audit") { "all" }
            }
            h.maybeCrashOnce(window = 30)
            h.start()
            repeat(3) { h.send(BallId("counter", "c1"), CounterMsg.Increment, timeoutMillis = 5_000) }
            h.runUntilIdle()
            h.assertInvariants()
            val committed = h.runtime.inspect(BallId("counter", "c1")) as Int
            val audit = h.runtime.inspect(BallId("audit", "all"))
            assertEquals((1..committed).toList(), audit, "choices=${chooser.history}")
            samples.offer(variant("committed-$committed", h), h)
            runs++
        }
        samples.crossCheck()
        println("notices durable: $runs runs")
    }
}
