package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.BallId
import ballarch.kernel.Call
import ballarch.kernel.CallKey
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.Outcome
import ballarch.kernel.Reply
import ballarch.kernel.RequestContext
import ballarch.kernel.RequestId
import ballarch.kernel.Step
import ballarch.kernel.UnhandledInput
import ballarch.kernel.UnknownReason
import ballarch.kernel.accept
import ballarch.kernel.acceptLater
import ballarch.kernel.outcomeOf
import ballarch.kernel.step
import java.io.ObjectStreamClass
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Restarting a Durable store under a different version of the application
 * (spec §6.1): the runtime refuses, before writing anything, a store whose
 * open obligations the new composition cannot serve; it migrates state through
 * the Ball type; and it classifies each call by the effect class recorded at
 * commit, never by the port's current binding.
 */
class EvolutionTest {
    private val directory: Path = createTempDirectory("ballarch-evolution")
    private val s = BallId("script", "s")

    /** File stores opened by a test; [crashProcess] closes them, as the end of a crashed process releases their locks. */
    private val opened = ArrayList<FileStore>()

    private fun fileStore(): FileStore = FileStore(directory).also { opened += it }

    private fun crashProcess(runtime: Runtime) {
        runtime.crash()
        opened.forEach(FileStore::close)
        opened.clear()
    }

    /** Every file of the store directory with its bytes, to prove that a refused start wrote nothing. */
    private fun snapshot(): Map<String, List<Byte>> = Files.list(directory).use { files ->
        files.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
    }

    private fun callAct(port: AdapterPort<String, String>, timeout: Long = 1_000) = Act("call ${port.name}") { st, ctx ->
        val k = ctx.newKey()
        accept(st + k, "ok", Call(k, port, "req", timeout))
    }

    // ---------------------------------------------------------------- refusing to start

    @Test
    fun `a restart without the adapter of a possibly sent non-idempotent call refuses and leaves the ledger untouched`() {
        val scheduler = ManualScheduler()
        val bank = ScriptedAdapter<Int, String>()
        val register = BallId("register", "r1")
        fun runtime(bound: Boolean): Runtime {
            val composition = Composition().ball(RegisterBall())
            if (bound) composition.adapter(Debit, bank)
            return Runtime(composition, scheduler, fileStore()).also { it.start() }
        }

        val first = runtime(bound = true)
        first.request<Sale, String>(register, Sale(42), timeoutMillis = 1_000) {}
        scheduler.runReady()
        assertEquals(1, bank.sent.size)
        crashProcess(first)
        val before = snapshot()

        val refused = assertFailsWith<IncompatibleStoreException> { runtime(bound = false) }
        assertEquals(
            listOf("open call register/r1#1.0 uses port 'charge', which the composition no longer binds; drain the call before removing the port"),
            refused.problems,
        )
        assertTrue("charge" in refused.message!!)
        assertEquals(before, snapshot(), "a refused start wrote to the store")
        val ledger = FileStore(directory).load(register)!!.calls.values.single()
        assertEquals(Phase.Sending, ledger.phase)
        assertEquals(1, ledger.attempts)

        // Restoring the binding lets the old obligation drain as before.
        val second = runtime(bound = true)
        scheduler.runReady()
        assertEquals(1, bank.sent.size)
        assertEquals(Register.Finished("unknown:${UnknownReason.RecoveredAfterCrash}"), second.inspect(register))
        assertTrue(FileStore(directory).load(register)!!.calls.isEmpty())
    }

    @Test
    fun `a restart without a Ball type that has records, or that open calls address, refuses`() {
        val store = MemoryStore()
        val k = CallKey("1.0")
        val request = RequestId("ext:1:0")
        store.save(InstanceRecord(BallId("counter", "c1"), 2, revision = 2))
        store.save(
            InstanceRecord(
                BallId("relay", "r"), RelayState.Waiting(k, request), revision = 1,
                calls = mapOf(k to CallRecord(k, CounterPort.name, EffectClass.Idempotent, "c1", CounterMsg.Increment, 2_000, Phase.Sending, 1)),
                inbound = mapOf(request to InboundRecord(request, ReplyTarget.External, Forward("c1", CounterMsg.Increment), deadlineAt = 2_000)),
            ),
        )
        val saves = store.saves

        val refused = assertFailsWith<IncompatibleStoreException> { Runtime(Composition().ball(RelayBall()), ManualScheduler(), store) }
        assertEquals(
            listOf(
                "record counter/c1 names Ball type 'counter', which the composition no longer registers; drain the record " +
                    "under a composition that registers it, then remove the type's records with Store.delete",
                "open call relay/r#1.0 uses port 'ball:counter', which the composition no longer binds; drain the call before removing the port",
            ),
            refused.problems,
        )
        assertEquals(saves, store.saves)

        // With both types registered, the Ball call is re-sent and answered once.
        val r = Rig(store = store) { Composition().ball(RelayBall()).ball(CounterBall()) }.start()
        assertEquals(RelayState.Done("done:${CounterReply.Value(3)}"), r.runtime.inspect(BallId("relay", "r")))
    }

    @Test
    fun `a restart that drops the subscription of an undelivered notice refuses`() {
        val store = MemoryStore()
        val audit = BallId("audit", "all")
        store.save(
            InstanceRecord(
                BallId("counter", "c1"), 1, revision = 1,
                outbox = listOf(NoticeRecord(1, CounterChanged.name, 1, setOf(audit))), nextNoticeSequence = 2,
            ),
        )
        val refused = assertFailsWith<IncompatibleStoreException> {
            Runtime(Composition().ball(CounterBall()).ball(AuditBall()), ManualScheduler(), store)
        }
        assertEquals(
            listOf(
                "notice counter.changed#1 of counter/c1 is still owed to audit/all, but the composition no longer subscribes " +
                    "Ball type 'audit' to topic 'counter.changed'; deliver it before removing the subscription",
            ),
            refused.problems,
        )
        val r = Rig(store = store) { Composition().ball(CounterBall()).ball(AuditBall()).subscribe(CounterChanged, "audit") { "all" } }.start()
        assertEquals(listOf(1), r.runtime.inspect(audit))
        assertTrue(store.load(BallId("counter", "c1"))!!.outbox.isEmpty())
    }

    @Test
    fun `a Ball type is retired by deleting its drained records, even ones whose state no longer decodes`() {
        val scheduler = ManualScheduler()
        val cart = BallId("cart", "c")
        val v1 = Runtime(Composition().ball(CartV1Ball()).ball(RegisterBall()), scheduler, fileStore())
        v1.start()
        v1.request<String, String>(cart, "apple", timeoutMillis = 1_000) {}
        scheduler.runReady()
        assertEquals(CartV1(listOf("apple")), v1.inspect(cart))
        crashProcess(v1)

        // The next version drops the cart type. Its record holds no obligation, but the runtime cannot know that
        // the type was retired on purpose.
        fun withoutCart() = Runtime(Composition().ball(RegisterBall()), scheduler, FileStore(directory))
        val refused = assertFailsWith<IncompatibleStoreException> { withoutCart() }
        assertTrue(refused.problems.single().endsWith("then remove the type's records with Store.delete"), refused.problems.single())

        // The operator removes the type's records. The file store lists them without decoding them, so this works
        // after the state classes are gone.
        val classesGone = FileStore(directory, UndecodableCodec)
        classesGone.ids().filter { it.type == "cart" }.forEach(classesGone::delete)
        assertEquals(emptySet(), FileStore(directory).ids())
        val v2 = withoutCart().also { it.start() }
        scheduler.runReady()
        assertEquals(null, v2.inspect(cart))

        val memory = MemoryStore().apply { save(InstanceRecord(cart, CartV1(emptyList()), revision = 1)) }
        memory.delete(cart)
        memory.delete(cart)
        assertEquals(emptySet(), memory.ids())
    }

    @Test
    fun `a record that cannot be decoded refuses the start, naming the record and its file`() {
        val register = BallId("register", "r1")
        FileStore(directory).save(InstanceRecord(register, Register.Idle, revision = 1))
        val before = snapshot()
        val refused = assertFailsWith<IncompatibleStoreException> {
            Runtime(Composition().ball(RegisterBall()), ManualScheduler(), FileStore(directory, UndecodableCodec))
        }
        val problem = refused.problems.single()
        assertTrue(problem.startsWith("record register/r1 cannot be read: $directory/"), problem)
        assertTrue(problem.endsWith(".record cannot be decoded: java.lang.ClassNotFoundException: Register\$Old"), problem)
        assertEquals(before, snapshot(), "a refused start wrote to the store")
    }

    @Test
    fun `a store that cannot list its records refuses the start with the store's error`() {
        FileStore(directory).save(InstanceRecord(BallId("register", "r1"), Register.Idle, revision = 1))
        val file = Files.list(directory).use { files -> files.filter { it.toString().endsWith(".record") }.toList() }.single()
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        Files.write(file, bytes)
        val before = snapshot()
        val refused = assertFailsWith<IncompatibleStoreException> {
            Runtime(Composition().ball(RegisterBall()), ManualScheduler(), FileStore(directory))
        }
        assertEquals(listOf("the store cannot list its records: $file failed its checksum"), refused.problems)
        assertTrue(refused.cause is java.io.IOException)
        assertEquals(before, snapshot())
    }

    @Test
    fun `a record that a replaced runtime commits while the new one takes the store refuses the start too`() {
        val scheduler = ManualScheduler()
        val memory = MemoryStore()
        val charge = AdapterPort<String, String>("charge", EffectClass.NonIdempotent)
        val bank = ScriptedAdapter<String, String>()
        val old = Runtime(Composition().ball(ScriptBall()).adapter(charge, bank, maxInFlight = 1), scheduler, memory).also { it.start() }
        scheduler.runReady()
        // Until the new runtime takes the store, the old one still commits: here two calls on a port the new one does not bind.
        val store = object : Store by memory {
            override fun nextIncarnation(): Long {
                old.request<Act, Any?>(s, callAct(charge), 1_000) {}
                old.request<Act, Any?>(s, callAct(charge), 1_000) {}
                scheduler.runReady()
                return memory.nextIncarnation()
            }
        }

        val refused = assertFailsWith<IncompatibleStoreException> { Runtime(Composition().ball(ScriptBall()), scheduler, store) }
        assertEquals(
            listOf("s#1.0", "s#2.0").map { "open call script/$it uses port 'charge', which the composition no longer binds; drain the call before removing the port" },
            refused.problems,
        )
        assertEquals(2, memory.load(s)!!.calls.size)
        // The refusal came after the new runtime took the store: the old one's next write, the second call's write-ahead record, is refused.
        bank.sent.single().report(AdapterResult.Ok("done"))
        scheduler.runReady()
        assertIs<FencedException>(old.fenced)
        assertEquals(1, bank.sent.size)
    }

    // ---------------------------------------------------------------- state versions

    @Test
    fun `a state version bump with a migration round-trips through the file store`() {
        val scheduler = ManualScheduler()
        val cart = BallId("cart", "c")
        fun start(ball: Ball<*, String, String>): Runtime =
            Runtime(Composition().ball(ball), scheduler, fileStore()).also { it.start(); scheduler.runReady() }
        fun Runtime.add(item: String) {
            request<String, String>(cart, item, timeoutMillis = 1_000) {}
            scheduler.runReady()
        }

        val v1 = start(CartV1Ball())
        v1.add("apple")
        v1.add("pear")
        assertEquals(1, FileStore(directory).load(cart)!!.stateVersion)
        crashProcess(v1)

        val v2 = start(CartV2Ball())
        assertEquals(CartV2(listOf("apple", "pear"), count = 2), v2.inspect(cart))
        // Migration happens on load; the store keeps the old shape until the next commit.
        assertEquals(CartV1(listOf("apple", "pear")), FileStore(directory).load(cart)!!.state)
        v2.add("plum")
        val stored = FileStore(directory).load(cart)!!
        assertEquals(2, stored.stateVersion)
        assertEquals(CartV2(listOf("apple", "pear", "plum"), count = 3), stored.state)
        crashProcess(v2)

        // Older code cannot read the newer state, and says so before it writes anything.
        val before = snapshot()
        val refused = assertFailsWith<IncompatibleStoreException> { start(CartV1Ball()) }
        val problem = refused.problems.single()
        assertTrue(problem.startsWith("record cart/c holds state version 2, which Ball type 'cart' (state version 1) cannot migrate"), problem)
        assertEquals(before, snapshot())
    }

    @Test
    fun `the default migration accepts only the current version, and versions start at 1`() {
        val ball = CartV1Ball()
        val state = CartV1(listOf("x"))
        assertEquals(1, ball.stateVersion)
        assertEquals(state, ball.migrate(1, state))
        assertFailsWith<UnhandledInput> { ball.migrate(2, state) }
        val zero = object : Ball<Int, Unit, Unit> {
            override val type = "zero"
            override val stateVersion = 0
            override fun initial(key: String) = 0
            override fun decide(state: Int, request: Unit, ctx: RequestContext): Decision<Int, Unit> = accept(state, Unit)
        }
        assertFailsWith<IllegalArgumentException> { Composition().ball(zero) }
    }

    // ---------------------------------------------------------------- effect classes

    @Test
    fun `a call is classified by the effect class recorded at commit, not by the port's binding after a restart`() {
        val nonIdem = AdapterPort<String, String>("charge", EffectClass.NonIdempotent)
        val nowIdem = AdapterPort<String, String>("charge", EffectClass.Idempotent)
        val first = ScriptedAdapter<String, String>()
        val second = ScriptedAdapter<String, String>()
        var bind: Composition.() -> Unit = { adapter(nonIdem, first) }
        val r = Rig { Composition().ball(ScriptBall()).apply(bind) }.start()
        val t0 = r.scheduler.now()
        r.send(s, callAct(nonIdem))
        val key = r.log(s).filterIsInstance<CallKey>().single()
        assertEquals(EffectClass.NonIdempotent, r.runtime.record(s)!!.calls.getValue(key).effect)
        assertEquals(1, first.sent.size)

        // The port is now declared idempotent and retryable; the call committed before stays non-idempotent.
        bind = { adapter(nowIdem, second, RetryPolicy(maxAttempts = 3)) }
        r.restartAt(t0 + 10)
        assertEquals(Outcome.Unknown(UnknownReason.RecoveredAfterCrash), r.completions(s).single().outcome)
        assertTrue(second.sent.isEmpty())

        // A call committed after the change is idempotent, and is retried.
        r.send(s, callAct(nowIdem, timeout = 1_000))
        val next = r.log(s).filterIsInstance<CallKey>().last()
        assertEquals(EffectClass.Idempotent, r.runtime.record(s)!!.calls.getValue(next).effect)
        second.sent.single().report(AdapterResult.Failed("reset"))
        r.scheduler.advanceBy(0) // the retry is a timer with no backoff
        assertEquals(listOf(1, 2), second.sent.map { it.invocation.attempt })
    }

    @Test
    fun `a call committed non-idempotent takes no attempt timeout from a later binding of its port`() {
        val nonIdem = AdapterPort<String, String>("charge", EffectClass.NonIdempotent)
        val nowIdem = AdapterPort<String, String>("charge", EffectClass.Idempotent)
        val second = ScriptedAdapter<String, String>()
        var bind: Composition.() -> Unit = { adapter(nonIdem, ScriptedAdapter(), maxInFlight = 1) }
        val r = Rig { Composition().ball(ScriptBall()).apply(bind) }.start()
        val t0 = r.scheduler.now()
        r.send(s, callAct(nonIdem))
        r.send(s, callAct(nonIdem)) // waits for the only slot: committed, never sent
        val waiting = r.log(s).filterIsInstance<CallKey>().last()
        assertEquals(Phase.Pending, r.runtime.record(s)!!.calls.getValue(waiting).phase)

        // The port is now Idempotent with an attempt timeout. The waiting call is sent, and stays NonIdempotent.
        bind = { adapter(nowIdem, second, RetryPolicy(maxAttempts = 3, attemptTimeoutMillis = 100)) }
        r.restartAt(t0 + 10)
        val sent = second.sent.single()
        r.scheduler.advanceTo(t0 + 160)
        assertTrue(!sent.cancelled, "an attempt of a call committed NonIdempotent was stopped at an attempt timeout")
        sent.report(AdapterResult.Ok("answer"))
        r.scheduler.runReady()
        assertEquals(Outcome.Done("answer"), r.completions(s).single { it.key == waiting }.outcome)
        assertEquals(1, second.sent.size)
    }

    @Test
    fun `a repeatable call is not re-sent after a restart that rebinds its port as non-idempotent`() {
        val idem = AdapterPort<String, String>("charge", EffectClass.Idempotent)
        val nowNonIdem = AdapterPort<String, String>("charge", EffectClass.NonIdempotent)
        val first = ScriptedAdapter<String, String>()
        val second = ScriptedAdapter<String, String>()
        var bind: Composition.() -> Unit = { adapter(idem, first, RetryPolicy(maxAttempts = 3)) }
        val r = Rig { Composition().ball(ScriptBall()).apply(bind) }.start()
        val t0 = r.scheduler.now()
        r.send(s, callAct(idem))
        bind = { adapter(nowNonIdem, second) }
        r.restartAt(t0 + 10)
        // Classified as the Idempotent call it was committed as; the new binding's budget of one attempt is spent.
        assertEquals(Outcome.Unknown(UnknownReason.RetriesExhausted), r.completions(s).single().outcome)
        assertTrue(second.sent.isEmpty())
    }

    @Test
    fun `a Ball-port call records the Idempotent class of Ball ports`() {
        val r = Rig { Composition().ball(RelayBall()).ball(CounterBall()) }
        r.start()
        r.send(BallId("relay", "r"), Forward("c1", CounterMsg.Get), run = false)
        r.scheduler.runReady(max = 2) // admit and decide the request; the counter has not answered yet
        val call = r.runtime.record(BallId("relay", "r"))!!.calls.values.single()
        assertEquals(CounterPort.name, call.portName)
        assertEquals(EffectClass.Idempotent, call.effect)
        r.scheduler.runReady()
        assertEquals(RelayState.Done("done:${CounterReply.Value(0)}"), r.runtime.inspect(BallId("relay", "r")))
    }

    @Test
    fun `the file store's own record classes have fixed serialVersionUIDs`() {
        for (name in listOf("RecordDto", "CallDto", "InboundDto", "OutcomeDto", "NoticeDto", "IdDto", "SeenDto")) {
            assertEquals(1L, ObjectStreamClass.lookup(Class.forName("ballarch.runtime.$name")).serialVersionUID, name)
        }
    }
}

// ---- Serializable Balls for the file-store restarts

/** Reads no record, as after the classes of a record's state were removed from the class path. */
private object UndecodableCodec : RecordCodec {
    override fun encode(record: InstanceRecord): ByteArray = JavaSerializationCodec.encode(record)

    override fun decode(bytes: ByteArray): InstanceRecord = throw ClassNotFoundException("Register\$Old")
}

private val Debit = AdapterPort<Int, String>("charge", EffectClass.NonIdempotent)

private data class Sale(val amount: Int) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private sealed interface Register : Serializable {
    data object Idle : Register

    data class Charging(val key: CallKey, val requestId: RequestId) : Register

    data class Finished(val text: String) : Register
}

private class RegisterBall : Ball<Register, Sale, String> {
    override val type = "register"

    override fun initial(key: String): Register = Register.Idle

    override fun decide(state: Register, request: Sale, ctx: RequestContext): Decision<Register, String> {
        val key = ctx.newKey()
        return acceptLater(Register.Charging(key, ctx.requestId), Call(key, Debit, request.amount, 1_000))
    }

    override fun complete(state: Register, completion: Completion<*>, ctx: Context): Step<Register, String> {
        val charging = state as? Register.Charging ?: return step(state)
        val text = when (val o = completion.outcomeOf(Debit)!!) {
            is Outcome.Done -> "charged:${o.value}"
            is Outcome.NotDone -> "not-charged:${o.reason}"
            is Outcome.Unknown -> "unknown:${o.reason}"
        }
        return step(Register.Finished(text), Reply(charging.requestId, text))
    }
}

/** Version 1 of a cart's state. It stays on the class path so that version 2 can read it. */
private data class CartV1(val items: List<String>) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Version 2 adds a count. */
private data class CartV2(val items: List<String>, val count: Int) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private class CartV1Ball : Ball<CartV1, String, String> {
    override val type = "cart"
    override fun initial(key: String) = CartV1(emptyList())
    override fun decide(state: CartV1, request: String, ctx: RequestContext): Decision<CartV1, String> =
        accept(CartV1(state.items + request), "added")
}

private class CartV2Ball : Ball<CartV2, String, String> {
    override val type = "cart"
    override val stateVersion = 2
    override fun initial(key: String) = CartV2(emptyList(), 0)
    override fun decide(state: CartV2, request: String, ctx: RequestContext): Decision<CartV2, String> =
        accept(CartV2(state.items + request, state.count + 1), "added")

    override fun migrate(fromVersion: Int, state: Any?): CartV2 = when (fromVersion) {
        1 -> (state as CartV1).let { CartV2(it.items, it.items.size) }
        else -> super.migrate(fromVersion, state)
    }
}
