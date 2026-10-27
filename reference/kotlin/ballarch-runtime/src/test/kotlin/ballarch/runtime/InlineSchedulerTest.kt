package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.BallId
import ballarch.kernel.Call
import ballarch.kernel.Completion
import ballarch.kernel.EffectClass
import ballarch.kernel.Outcome
import ballarch.kernel.accept
import ballarch.kernel.step
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The inline scheduler (Core §3.1): a step runs on the thread that submits its
 * input, and an input submitted during a step or its dispatch is processed
 * only after that step.
 */
class InlineSchedulerTest {
    /** Tasks handed to `post` by other threads; the test runs them on its own thread, the owning one. */
    private val posted = LinkedBlockingQueue<Runnable>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val s = BallId("script", "s")
    private val lookup = AdapterPort<String, String>("lookup", EffectClass.Safe)

    private fun scheduler() = InlineScheduler(post = posted::add, timer = timer)

    private fun lookUp() = Act("look up") { st, ctx ->
        val k = ctx.newKey()
        accept(st + k, "asked", Call(k, lookup, "sku", 60_000))
    }

    @AfterTest
    fun stopTimer() {
        timer.shutdownNow()
    }

    @Test
    fun `a request on the owning thread is decided, committed and answered before request returns`() {
        val store = MemoryStore()
        val runtime = Runtime(Composition().ball(CounterBall()), scheduler(), store)
        runtime.start()
        var outcome: Outcome<Any?>? = null
        runtime.request<CounterMsg, Any?>(BallId("counter", "c"), CounterMsg.Increment, 1_000) { outcome = it }
        assertEquals(Outcome.Done(CounterReply.Value(1)), outcome)
        assertEquals(1L, store.saves)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `a request that a watcher submits during a step is decided after that step`() {
        val tracer = RecordingTracer()
        val runtime = Runtime(Composition().ball(CounterBall()), scheduler(), MemoryStore(), tracer = tracer)
        runtime.start()
        val counter = BallId("counter", "c")
        val events = ArrayList<String>()
        runtime.watch(CounterBall(), counter.key) { snapshot ->
            events += "watch ${snapshot.revision}"
            if (snapshot.revision == 1L) {
                runtime.request<CounterMsg, Any?>(counter, CounterMsg.Increment, 1_000) { events += "answer ${(it as Outcome.Done<*>).value}" }
            }
        }
        runtime.request<CounterMsg, Any?>(counter, CounterMsg.Increment, 1_000) { events += "answer ${(it as Outcome.Done<*>).value}" }
        events += "returned"
        assertEquals(
            listOf("watch 0", "watch 1", "answer ${CounterReply.Value(1)}", "watch 2", "answer ${CounterReply.Value(2)}", "returned"),
            events,
        )
        val decisions = tracer.events.filter { it is TraceEvent.DecideStarted || it is TraceEvent.DecideFinished }
        assertEquals(List(2) { listOf("DecideStarted", "DecideFinished") }.flatten(), decisions.map { it::class.simpleName })
    }

    @Test
    fun `an adapter that reports inside execute is answered after the step that sent the call`() {
        var executing = false
        val adapter = Adapter<String, String> { _, done ->
            executing = true
            done(AdapterResult.Ok("found"))
            executing = false
            null
        }
        val nested = ArrayList<Boolean>()
        val ball = ScriptBall(onComplete = { st, c, _ -> nested += executing; step(st + c.outcome) })
        val runtime = Runtime(Composition().ball(ball).adapter(lookup, adapter), scheduler(), MemoryStore())
        runtime.start()
        var answer: Outcome<Any?>? = null
        runtime.request<Act, Any?>(s, lookUp(), 1_000) { answer = it }
        assertEquals(Outcome.Done("asked"), answer)
        assertEquals(listOf(false), nested)
        assertEquals(Outcome.Done("found"), runtime.read(s).state.let { it as List<*> }.last())
    }

    @Test
    fun `a report from another thread is posted and handled on the owning thread`() {
        val reporter = Executors.newSingleThreadExecutor()
        try {
            val adapter = Adapter<String, String> { _, done ->
                reporter.execute { done(AdapterResult.Ok("found")) }
                null
            }
            val runtime = Runtime(Composition().ball(ScriptBall()).adapter(lookup, adapter), scheduler(), MemoryStore())
            runtime.start()
            runtime.request<Act, Any?>(s, lookUp(), 1_000) {}
            val report = assertNotNull(posted.poll(5, TimeUnit.SECONDS))
            assertTrue((runtime.inspect(s) as List<*>).none { it is Completion<*> })
            // A posted task must run on the owning thread; elsewhere it is refused and changes nothing.
            val elsewhere = reporter.submit<Result<Unit>> { runCatching { report.run() } }.get(5, TimeUnit.SECONDS)
            assertIs<IllegalStateException>(elsewhere.exceptionOrNull())
            report.run()
            assertEquals(Outcome.Done("found"), (runtime.inspect(s) as List<*>).filterIsInstance<Completion<*>>().single().outcome)
        } finally {
            reporter.shutdownNow()
        }
    }

    @Test
    fun `a delayed task is posted when it is due, and a cancelled one never runs`() {
        val scheduler = InlineScheduler(post = posted::add, timer = timer, clock = { 42 })
        assertEquals(42, scheduler.now())
        val ran = ArrayList<String>()
        scheduler.schedule(20) { ran += "due" }
        scheduler.schedule(10) { ran += "cancelled" }.cancel()
        assertNotNull(posted.poll(5, TimeUnit.SECONDS)).run()
        Thread.sleep(50)
        while (true) (posted.poll() ?: break).run()
        assertEquals(listOf("due"), ran)
    }
}
