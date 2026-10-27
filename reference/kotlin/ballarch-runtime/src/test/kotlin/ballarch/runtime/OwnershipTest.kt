package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.BallId
import ballarch.kernel.Call
import ballarch.kernel.Completion
import ballarch.kernel.EffectClass
import ballarch.kernel.Outcome
import ballarch.kernel.UnknownReason
import ballarch.kernel.accept
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S11: one runtime commits to a store at a time (R16). A memory store fences:
 * once a second runtime starts on it, the first one's next write is refused,
 * and the first stops without dispatching anything. A file store excludes: a
 * second runtime, in this process or another, refuses to start until the
 * first one's store is closed or its process ends.
 */
class OwnershipTest {
    private val directory: Path = createTempDirectory("ballarch-ownership")
    private val s = BallId("script", "s")
    private val charge = AdapterPort<String, String>("charge", EffectClass.NonIdempotent)

    private fun callAct(label: String) = Act(label) { st, ctx ->
        val k = ctx.newKey()
        accept(st + k, "ok", Call(k, charge, label, 1_000))
    }

    private fun ok(label: String) = Act(label) { st, _ -> accept(st + label, "ok") }

    /** Every file of the store directory with its bytes, to prove that a refused start wrote nothing. */
    private fun files(): Map<String, List<Byte>> = Files.list(directory).use { files ->
        files.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
    }

    @Test
    fun `a runtime replaced on a memory store sends nothing more, so a waiting call is sent once`() {
        val scheduler = ManualScheduler()
        val store = MemoryStore()
        val oldBank = ScriptedAdapter<String, String>()
        val newBank = ScriptedAdapter<String, String>()
        val oldTrace = RecordingTracer()
        fun composition(bank: ScriptedAdapter<String, String>) = Composition().ball(ScriptBall()).adapter(charge, bank, maxInFlight = 1)

        val old = Runtime(composition(oldBank), scheduler, store, tracer = oldTrace).also { it.start() }
        old.request<Act, Any?>(s, callAct("a"), 1_000) {}
        old.request<Act, Any?>(s, callAct("b"), 1_000) {} // committed, waits in phase Pending for the only slot
        scheduler.runReady()
        assertEquals(listOf("a"), oldBank.sent.map { it.invocation.request })

        // A second runtime starts on the store while the first still runs, as in a careless rolling deploy.
        val new = Runtime(composition(newBank), scheduler, store).also { it.start() }
        scheduler.runReady()
        assertEquals(listOf("b"), newBank.sent.map { it.invocation.request })
        val saves = store.saves

        // The first runtime's adapter answers a. That frees its slot for b, whose write-ahead record the store refuses.
        oldBank.sent[0].report(AdapterResult.Ok("a done"))
        scheduler.runReady()
        assertIs<FencedException>(old.fenced)
        assertEquals(listOf("a"), oldBank.sent.map { it.invocation.request })
        assertEquals(1, oldTrace.events.filterIsInstance<TraceEvent.CallSent>().size)
        assertTrue(oldTrace.events.none { it is TraceEvent.CallResolved })
        assertEquals(saves, store.saves)

        // It answers nothing more; the store holds what the second runtime committed.
        val answers = ArrayList<Outcome<Any?>>()
        old.request<Act, Any?>(s, ok("late"), 1_000) { answers += it }
        scheduler.runReady()
        assertTrue(answers.isEmpty())
        assertNull(new.fenced)
        assertEquals(new.record(s), store.load(s))
        val completion = (store.load(s)!!.state as List<*>).filterIsInstance<Completion<*>>().single()
        assertEquals(Outcome.Unknown(UnknownReason.RecoveredAfterCrash), completion.outcome) // a, as the second runtime classified it
    }

    @Test
    fun `a decision of a replaced runtime is not committed, and nothing of it is dispatched`() {
        val scheduler = ManualScheduler()
        val store = MemoryStore()
        val bank = ScriptedAdapter<String, String>()
        val old = Runtime(Composition().ball(ScriptBall()).adapter(charge, bank), scheduler, store).also { it.start() }
        val watched = ArrayList<Any?>()
        old.watch(s) { watched += it }
        scheduler.runReady()
        val new = Runtime(Composition().ball(ScriptBall()).adapter(charge, ScriptedAdapter()), scheduler, store).also { it.start() }
        scheduler.runReady()

        val answers = ArrayList<Outcome<Any?>>()
        old.request<Act, Any?>(s, callAct("a"), 1_000) { answers += it }
        scheduler.runReady()
        assertIs<FencedException>(old.fenced)
        assertTrue(answers.isEmpty() && watched.isEmpty() && bank.sent.isEmpty(), "$answers $watched")
        assertNull(store.load(s))

        new.request<Act, Any?>(s, ok("b"), 1_000) { answers += it }
        scheduler.runReady()
        assertEquals(listOf<Outcome<Any?>>(Outcome.Done("ok")), answers)
        assertEquals(listOf<Any?>("b"), store.load(s)!!.state)
    }

    @Test
    fun `a file store excludes a second runtime until the first one's store is closed`() {
        val scheduler = ManualScheduler()
        fun composition() = Composition().ball(ScriptBall())
        val firstStore = FileStore(directory)
        val first = Runtime(composition(), scheduler, firstStore).also { it.start() }
        first.request<Act, Any?>(s, ok("a"), 1_000) {}
        scheduler.runReady()

        val before = files()
        val secondStore = FileStore(directory)
        assertFailsWith<StoreLockedException> { Runtime(composition(), scheduler, secondStore) }
        assertEquals(before, files(), "a refused start wrote to the store")

        // The first runtime's store is closed, as at the end of its process: whatever still runs there writes nothing.
        firstStore.close()
        val answers = ArrayList<Outcome<Any?>>()
        first.request<Act, Any?>(s, ok("b"), 1_000) { answers += it }
        scheduler.runReady()
        assertIs<FencedException>(first.fenced)
        assertTrue(answers.isEmpty())

        secondStore.use { store ->
            val second = Runtime(composition(), scheduler, store).also { it.start() }
            scheduler.runReady()
            assertEquals(listOf<Any?>("a"), second.inspect(s))
            assertTrue(second.incarnation > first.incarnation)

            // Two runtimes on one FileStore object: the one that started first is fenced.
            val third = Runtime(composition(), scheduler, store).also { it.start() }
            scheduler.runReady()
            second.request<Act, Any?>(s, ok("c"), 1_000) { answers += it }
            third.request<Act, Any?>(s, ok("d"), 1_000) { answers += it }
            scheduler.runReady()
            assertIs<FencedException>(second.fenced)
            assertNull(third.fenced)
            assertEquals(listOf<Outcome<Any?>>(Outcome.Done("ok")), answers)
            assertEquals(listOf<Any?>("a", "d"), store.load(s)!!.state)
        }
    }

    @Test
    fun `a file store held by another process refuses a runtime until that process ends`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val holder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), StoreHolder::class.java.name, directory.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            assertEquals("holding", holder.inputStream.bufferedReader().readLine())
            assertFailsWith<StoreLockedException> { Runtime(Composition().ball(ScriptBall()), ManualScheduler(), FileStore(directory)) }
        } finally {
            holder.destroyForcibly()
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS))
        }
        FileStore(directory).use { store ->
            assertEquals(2, Runtime(Composition().ball(ScriptBall()), ManualScheduler(), store).incarnation)
        }
    }
}

/** Runs in a child JVM for the ownership test: takes a file store's lock, says so, and holds it until it is killed. */
object StoreHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        FileStore(Path.of(args[0])).nextIncarnation()
        println("holding")
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
