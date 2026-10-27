package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.BallId
import ballarch.kernel.Call
import ballarch.kernel.CallId
import ballarch.kernel.CallKey
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.Reply
import ballarch.kernel.RequestContext
import ballarch.kernel.RequestId
import ballarch.kernel.Step
import ballarch.kernel.UnknownReason
import ballarch.kernel.acceptLater
import ballarch.kernel.outcomeOf
import ballarch.kernel.step
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStoreTest {
    private val directory: Path = createTempDirectory("ballarch-store")

    @Test
    fun `a record with every kind of entry reads back equal from a new store`() {
        val record = everything()
        FileStore(directory).save(record)
        assertEquals(record, FileStore(directory).load(record.id))
        assertEquals(setOf(record.id), FileStore(directory).ids())
        assertNull(FileStore(directory).load(BallId("payer", "other")))
    }

    @Test
    fun `incarnations keep increasing across stores on the same directory`() {
        assertEquals(1, FileStore(directory).use { it.nextIncarnation() })
        assertEquals(2, FileStore(directory).use { it.nextIncarnation() })
        assertEquals(3, FileStore(directory).use { it.nextIncarnation() })
    }

    @Test
    fun `a damaged or truncated record is reported, never misread`() {
        val record = everything()
        FileStore(directory).save(record)
        val file = recordFiles().single()
        val bytes = Files.readAllBytes(file)

        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        Files.write(file, bytes)
        assertTrue(assertFailsWith<IOException> { FileStore(directory).load(record.id) }.message!!.contains("checksum"))

        Files.write(file, bytes.copyOf(bytes.size - 3))
        assertTrue(assertFailsWith<IOException> { FileStore(directory).load(record.id) }.message!!.contains("truncated"))
    }

    @Test
    fun `delete removes a record for good, and deleting a missing record does nothing`() {
        val record = everything()
        FileStore(directory).save(record)
        FileStore(directory).delete(record.id)
        assertNull(FileStore(directory).load(record.id))
        assertEquals(emptySet(), FileStore(directory).ids())
        FileStore(directory).delete(record.id)
        assertTrue(recordFiles().isEmpty())
    }

    @Test
    fun `a file of the earlier record format, which held the codec's bytes alone, is refused with an explanation`() {
        val payload = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject("an old record") } }.toByteArray()
        val framed = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(0x42414C4C)
                data.writeInt(payload.size)
                data.writeLong(CRC32().apply { update(payload) }.value)
                data.write(payload)
            }
        }.toByteArray()
        Files.write(directory.resolve("old.record"), framed)
        val e = assertFailsWith<IOException> { FileStore(directory).ids() }
        assertTrue("old.record was written by an earlier FileStore (record format 1)" in e.message!!, e.message)
    }

    @Test
    fun `a possibly sent non-idempotent call ends Unknown after a restart from disk and is not sent again`() {
        val scheduler = ManualScheduler()
        val bank = ScriptedAdapter<Int, String>()
        fun runtime(store: FileStore) = Runtime(Composition().ball(TillBall()).adapter(Charge, bank), scheduler, store)
        val till = BallId("till", "t1")

        val firstStore = FileStore(directory)
        val first = runtime(firstStore).also { it.start() }
        first.request<Ring, String>(till, Ring(42), timeoutMillis = 1_000) {}
        scheduler.runReady()
        assertEquals(1, bank.sent.size)
        first.crash()
        firstStore.close() // the crashed process's lock goes with it

        val second = runtime(FileStore(directory)).also { it.start() }
        scheduler.runReady()
        assertEquals(1, bank.sent.size)
        assertEquals(Till.Finished("unknown:${UnknownReason.RecoveredAfterCrash}"), second.inspect(till))
        assertTrue(second.incarnation > first.incarnation)
    }

    @Test
    fun `a process killed while saving leaves every record whole, either old or new`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val rounds = 6
        var lastRevision = 0L
        repeat(rounds) { round ->
            val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), FileStoreWriter::class.java.name, directory.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val ready = process.inputStream.bufferedReader().readLine()
            assertEquals("ready", ready, "writer did not start")
            // Kill at a different point of the write loop in each round.
            Thread.sleep(40L + 37L * round)
            process.destroyForcibly()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))

            FileStore(directory).use { store ->
                val records = FileStoreWriter.ids.map { assertNotNullRecord(store.load(it), it) }
                for (r in records) assertEquals(FileStoreWriter.stateFor(r.id, r.revision), r.state, "record ${r.id} is a mixture")
                val revisions = records.map { it.revision }
                // Ids are written in order with the same revision, so they differ by at most one round of the loop.
                assertTrue(revisions.max() - revisions.min() <= 1, "revisions $revisions")
                assertTrue(revisions.min() >= lastRevision, "a committed revision was lost: $revisions after $lastRevision")
                lastRevision = revisions.min()
                // A runtime taking the store over removes the temporary files of the interrupted write.
                store.nextIncarnation()
                assertTrue(Files.list(directory).use { files -> files.noneMatch { it.fileName.toString().endsWith(".part") } })
            }
        }
        assertTrue(lastRevision >= rounds, "each writer completes at least one revision before it is killed: $lastRevision")
    }

    private fun assertNotNullRecord(record: InstanceRecord?, id: BallId): InstanceRecord =
        record ?: throw AssertionError("record $id is missing")

    private fun recordFiles(): List<Path> = Files.list(directory).use { files -> files.filter { it.toString().endsWith(".record") }.toList() }

    private fun everything(): InstanceRecord {
        val id = BallId("payer", "p/1 ü")
        val caller = CallId(BallId("order", "o-7"), CallKey("3.0"))
        return InstanceRecord(
            id = id,
            state = Till.Charging(CallKey("1.0"), RequestId("ext:1:0")),
            revision = 9,
            calls = mapOf(
                CallKey("1.0") to CallRecord(CallKey("1.0"), "charge", EffectClass.NonIdempotent, null, 42, deadlineAt = 2_000, phase = Phase.Sending, attempts = 1),
                CallKey("1.1") to CallRecord(
                    CallKey("1.1"), "lookup", EffectClass.Safe, "k", "q", deadlineAt = 2_500, phase = Phase.Pending, attempts = 0, cancelRequested = true,
                ),
                CallKey("1.2") to CallRecord(CallKey("1.2"), "ball:order", EffectClass.Idempotent, "o-7", "q", deadlineAt = 2_600, phase = Phase.Sending, attempts = 2),
                CallKey("1.3") to CallRecord(
                    CallKey("1.3"), "transfer", EffectClass.NonIdempotent, null, 7, deadlineAt = 2_700, phase = Phase.Reconciling, attempts = 1,
                    lastSentAt = 1_500, unknownReason = UnknownReason.RecoveredAfterCrash,
                ),
            ),
            inbound = listOf<Pair<ReplyTarget, Outcome<Any?>?>>(
                ReplyTarget.External to null,
                ReplyTarget.ToCall(caller) to Outcome.Done("ok"),
                ReplyTarget.External to Outcome.Unknown(UnknownReason.DeadlineAfterSend),
                ReplyTarget.External to Outcome.NotDone(NotDoneReason.Refused("no stock")),
                ReplyTarget.External to Outcome.NotDone(NotDoneReason.Overloaded),
                ReplyTarget.External to Outcome.NotDone(NotDoneReason.Cancelled),
                ReplyTarget.External to Outcome.NotDone(NotDoneReason.DeadlineBeforeSend),
                ReplyTarget.External to Outcome.NotDone(NotDoneReason.NoAnswer),
                ReplyTarget.External to Outcome.NotDone(NotDoneReason.NotApplied("provider says never seen")),
                ReplyTarget.External to Outcome.NotDone(NotDoneReason.IdempotencyConflict),
                ReplyTarget.External to Outcome.NotDone(NotDoneReason.Fault("decider threw")),
            ).mapIndexed { i, (target, reply) ->
                RequestId("r$i") to InboundRecord(RequestId("r$i"), target, Ring(i), 3_000L + i, reply, reply?.let { 2_900L })
            }.toMap(),
            outbox = listOf(NoticeRecord(4, "till.closed", Ring(7), linkedSetOf(BallId("audit", "a"), BallId("audit", "b")))),
            nextNoticeSequence = 5,
            lastSeen = mapOf(BallId("counter", "c") to 12L),
            stateVersion = 3,
        )
    }
}

/**
 * Runs in a child JVM for the kill test: saves the same revision to each of
 * [ids] in turn, forever. Each state is large, so a save takes several writes
 * and a kill can land in the middle of one.
 */
object FileStoreWriter {
    val ids = (1..4).map { BallId("w", "k$it") }

    fun stateFor(id: BallId, revision: Long): String = "${id.key}:$revision:" + "x".repeat(256 * 1024)

    @JvmStatic
    fun main(args: Array<String>) {
        val store = FileStore(Path.of(args[0]))
        var revision = (ids.mapNotNull { store.load(it)?.revision }.minOrNull() ?: 0L) + 1
        var announced = false
        while (true) {
            for (id in ids) store.save(InstanceRecord(id, stateFor(id, revision), revision))
            if (!announced) {
                println("ready")
                System.out.flush()
                announced = true
            }
            revision++
        }
    }
}

// ---- a Serializable Ball for the restart test

private val Charge = AdapterPort<Int, String>("charge", EffectClass.NonIdempotent)

private data class Ring(val amount: Int) : Serializable

private sealed interface Till : Serializable {
    data object Idle : Till

    data class Charging(val key: CallKey, val requestId: RequestId) : Till

    data class Finished(val text: String) : Till
}

private class TillBall : Ball<Till, Ring, String> {
    override val type = "till"

    override fun initial(key: String): Till = Till.Idle

    override fun decide(state: Till, request: Ring, ctx: RequestContext): Decision<Till, String> {
        val key = ctx.newKey()
        return acceptLater(Till.Charging(key, ctx.requestId), Call(key, Charge, request.amount, 1_000))
    }

    override fun complete(state: Till, completion: Completion<*>, ctx: Context): Step<Till, String> {
        val charging = state as? Till.Charging ?: return step(state)
        val text = when (val o = completion.outcomeOf(Charge)!!) {
            is Outcome.Done -> "charged:${o.value}"
            is Outcome.NotDone -> "not-charged:${o.reason}"
            is Outcome.Unknown -> "unknown:${o.reason}"
        }
        return step(Till.Finished(text), Reply(charging.requestId, text))
    }
}
