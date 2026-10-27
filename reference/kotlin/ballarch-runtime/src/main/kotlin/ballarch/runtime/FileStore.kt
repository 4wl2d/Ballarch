package ballarch.runtime

import ballarch.kernel.BallId
import ballarch.kernel.CallId
import ballarch.kernel.CallKey
import ballarch.kernel.EffectClass
import ballarch.kernel.Intent
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.RequestId
import ballarch.kernel.UnknownReason
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Turns an [InstanceRecord] into bytes and back. The runtime's own types are
 * encoded by the store; application values (state, messages, replies, notice
 * payloads) are encoded by the codec.
 */
public interface RecordCodec {
    public fun encode(record: InstanceRecord): ByteArray

    public fun decode(bytes: ByteArray): InstanceRecord
}

/**
 * A durable [Store] on the local file system: one file per instance.
 *
 * [save] is the commit point (rule R4). It writes the encoded record to a
 * temporary file in the same directory and forces it to the device. It then
 * renames the file over the previous one with an atomic move, and forces the
 * directory. After a crash of the process or of the machine, each file holds
 * either the previous or the new record, never a mixture. A CRC-32 over the
 * contents detects media corruption on load.
 *
 * Durability across power loss relies on the operating system and the device
 * honouring `fsync`; the tests cover crashes of the process (a JVM killed
 * mid-write), not power loss.
 *
 * A directory belongs to one runtime at a time (R16). [nextIncarnation], which a
 * runtime calls when it is constructed, takes an exclusive lock on the file
 * `.lock` in the directory, or throws [StoreLockedException] if another process,
 * or another FileStore of this process, holds it. Only then does it remove the
 * temporary files of writes that a crash interrupted. The lock is held until
 * [close] or the end of the process. It excludes runtimes on one host; a file
 * system shared between hosts may not honour it. A runtime whose FileStore was
 * closed, or that started on this FileStore before another runtime did, has its
 * writes refused with [FencedException].
 *
 * Records outlive the code that wrote them. With the default
 * [JavaSerializationCodec], every application class stored in a record must
 * declare a fixed `serialVersionUID` and change only in ways Java
 * serialization accepts; otherwise supply your own [RecordCodec]. If neither
 * holds, committed records become unreadable after a class changes. A state
 * class that must change shape keeps its old class readable, and its Ball type
 * raises [ballarch.kernel.Ball.stateVersion] and converts old states in
 * [ballarch.kernel.Ball.migrate].
 *
 * Each file holds the record's id ahead of the codec's bytes, so [ids] and
 * [delete] work even when a record cannot be decoded, and a decoding error
 * names the file. A file in another record format, such as one written by an
 * earlier FileStore that stored the codec's bytes alone, is refused with an
 * error that says so.
 */
public class FileStore(
    private val directory: Path,
    private val codec: RecordCodec = JavaSerializationCodec,
) : Store, AutoCloseable {
    init {
        Files.createDirectories(directory)
    }

    /** The directory's lock while this store holds it, and the entry of [HELD] that records it. */
    private var lock: FileLock? = null
    private var held: Path? = null

    /** The latest incarnation this store issued; writes of earlier ones are refused. */
    private var issued = 0L

    private fun fileOf(id: BallId): Path = directory.resolve(nameOf(id) + EXTENSION)

    override fun load(id: BallId): InstanceRecord? {
        val file = fileOf(id)
        if (!Files.exists(file)) return null
        val body = read(file)
        if (body.id != id) throw IOException("$file holds record ${body.id}, not $id")
        val record = try {
            codec.decode(body.encoded)
        } catch (e: Exception) {
            throw IOException("$file cannot be decoded: $e", e)
        }
        if (record.id != id) throw IOException("$file holds record $id, but its encoded record is ${record.id}")
        return record
    }

    override fun save(record: InstanceRecord) {
        writeAtomically(fileOf(record.id), frame(body(record.id, codec.encode(record))))
    }

    @Synchronized
    override fun save(record: InstanceRecord, incarnation: Long) {
        if (lock == null) throw FencedException("this FileStore of $directory does not hold its lock: it was closed, or no runtime started on it")
        if (incarnation < issued) throw FencedException("incarnation $incarnation may not write to $directory: incarnation $issued started on it")
        save(record)
    }

    /** Reads the id stored ahead of each record, so it never calls the codec. */
    override fun ids(): Set<BallId> = Files.list(directory).use { files ->
        files.filter { it.fileName.toString().endsWith(EXTENSION) }.toList()
    }.mapTo(LinkedHashSet()) { read(it).id }

    override fun delete(id: BallId) {
        if (Files.deleteIfExists(fileOf(id))) forceDirectory()
    }

    private fun read(file: Path): Body = parseBody(unframe(Files.readAllBytes(file), file), file)

    /** Takes the directory's lock unless this store holds it already, then increments the incarnation counter. */
    @Synchronized
    override fun nextIncarnation(): Long {
        if (lock == null) acquire()
        val file = directory.resolve(INCARNATION)
        val next = (if (Files.exists(file)) String(unframe(Files.readAllBytes(file), file)).toLong() else 0L) + 1
        writeAtomically(file, frame(next.toString().toByteArray()))
        issued = next
        return next
    }

    /**
     * Releases the directory's lock, so that another runtime may take the store
     * over. A runtime still using this store stops at its next write.
     */
    @Synchronized
    override fun close() {
        val taken = lock ?: return
        lock = null
        try {
            taken.channel().close() // releases the lock
        } finally {
            held?.let { HELD.remove(it) }
            held = null
        }
    }

    private fun acquire() {
        // One channel per lock file in a process: closing any channel on a file may release the process's lock on it.
        val key = directory.toRealPath()
        if (!HELD.add(key)) throw StoreLockedException("$directory is held by another FileStore of this process; close it first")
        try {
            val channel = FileChannel.open(directory.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val taken = try {
                channel.tryLock()
            } catch (e: IOException) {
                channel.close()
                throw e
            }
            if (taken == null) {
                channel.close()
                throw StoreLockedException("$directory is held by another process")
            }
            lock = taken
            held = key
        } catch (e: Throwable) {
            HELD.remove(key)
            throw e
        }
        // Temporary files left by a crash were never renamed, so they were never committed.
        Files.list(directory).use { files -> files.filter { it.fileName.toString().startsWith(TEMPORARY) }.toList() }
            .forEach { Files.deleteIfExists(it) }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(directory, TEMPORARY, ".part")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        forceDirectory()
    }

    /** Makes the rename itself durable. Some platforms cannot open a directory; there the rename is left to the file system. */
    private fun forceDirectory() {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // not supported on this platform
        }
    }

    /** A record file's contents inside the frame: the record's id and the codec's bytes. */
    private class Body(val id: BallId, val encoded: ByteArray)

    private companion object {
        const val EXTENSION = ".record"
        const val INCARNATION = "incarnation"
        const val LOCK = ".lock"
        const val TEMPORARY = ".tmp-"
        const val MAGIC = 0x42414C4C // "BALL"

        /** Format of a [Body]. Format 1 had no number: the frame held the codec's bytes alone. */
        const val RECORD_FORMAT = 2

        /** The first four bytes of a Java serialization stream, which is how a format-1 body begins. */
        const val JAVA_STREAM = 0xACED0005.toInt()

        /** The directories, by real path, whose lock a FileStore of this process holds. */
        val HELD: MutableSet<Path> = ConcurrentHashMap.newKeySet()

        fun body(id: BallId, encoded: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(encoded.size + 64)
            DataOutputStream(out).use { data ->
                data.writeInt(RECORD_FORMAT)
                for (text in listOf(id.type, id.key)) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
                data.write(encoded)
            }
            return out.toByteArray()
        }

        fun parseBody(bytes: ByteArray, file: Path): Body {
            val data = ByteBuffer.wrap(bytes)
            val format = if (data.remaining() >= 4) data.int else -1
            if (format != RECORD_FORMAT) {
                throw IOException(
                    if (format == JAVA_STREAM) {
                        "$file was written by an earlier FileStore (record format 1), which this version cannot read; " +
                            "drain the store with the version that wrote it, or start from an empty directory"
                    } else {
                        "$file has record format $format; this FileStore reads format $RECORD_FORMAT"
                    },
                )
            }
            fun text(): String {
                val size = if (data.remaining() >= 4) data.int else -1
                if (size < 0 || size > data.remaining()) throw IOException("$file holds a malformed record id")
                return String(ByteArray(size).also { data.get(it) }, Charsets.UTF_8)
            }
            val id = BallId(text(), text())
            return Body(id, ByteArray(data.remaining()).also { data.get(it) })
        }

        /** File names are digests, so any type or key is safe on any file system. */
        fun nameOf(id: BallId): String {
            val digest = MessageDigest.getInstance("SHA-256").digest((id.type + "\u0000" + id.key).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun frame(payload: ByteArray): ByteArray {
            val crc = CRC32().apply { update(payload) }.value
            val out = ByteArrayOutputStream(payload.size + 16)
            DataOutputStream(out).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(payload.size)
                data.writeLong(crc)
                data.write(payload)
            }
            return out.toByteArray()
        }

        fun unframe(bytes: ByteArray, file: Path): ByteArray {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (bytes.size < 16 || data.readInt() != MAGIC) throw IOException("$file is not a Ballarch record")
                val size = data.readInt()
                val crc = data.readLong()
                if (size != bytes.size - 16) throw IOException("$file is truncated")
                val payload = ByteArray(size).also { data.readFully(it) }
                if (CRC32().apply { update(payload) }.value != crc) throw IOException("$file failed its checksum")
                return payload
            }
        }
    }
}

/**
 * Encodes records with Java serialization. Application values (state,
 * messages, replies and notice payloads) must be [Serializable]. Application
 * classes must also declare a fixed `serialVersionUID` and change only in
 * serialization-compatible ways, or the application must supply its own
 * [RecordCodec]; otherwise committed records become unreadable after a class
 * changes. Kernel values inside records, such as outcomes, are converted by
 * the codec. An [Intent] or a [ballarch.kernel.CallRef], which application
 * state may also hold, is serializable itself and stored as it is. The codec's
 * own record classes have fixed `serialVersionUID`s.
 */
public object JavaSerializationCodec : RecordCodec {
    override fun encode(record: InstanceRecord): ByteArray {
        val out = ByteArrayOutputStream()
        ObjectOutputStream(out).use { it.writeObject(record.toDto()) }
        return out.toByteArray()
    }

    override fun decode(bytes: ByteArray): InstanceRecord =
        ObjectInputStream(ByteArrayInputStream(bytes)).use { (it.readObject() as RecordDto).toRecord() }
}

// ---- serializable mirrors of the runtime's records
//
// Each class fixes its serialVersionUID, so a later version of this file that only
// adds fields still reads records written by this one (added fields read as null,
// 0 or false). Change a UID only together with a deliberate format break.

private class RecordDto(
    val type: String,
    val key: String,
    val state: Any?,
    val revision: Long,
    val calls: List<CallDto>,
    val inbound: List<InboundDto>,
    val outbox: List<NoticeDto>,
    val nextNoticeSequence: Long,
    val lastSeen: List<SeenDto>,
    val stateVersion: Int,
    val keyspace: String?,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private class CallDto(
    val key: String,
    val portName: String,
    val effect: String,
    val target: String?,
    val request: Any?,
    val deadlineAt: Long,
    val phase: String,
    val attempts: Int,
    val cancelRequested: Boolean,
    val intent: Intent?,
    val lastSentAt: Long?,
    val unknownReason: String?,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private class InboundDto(
    val requestId: String,
    val external: Boolean,
    val callerType: String?,
    val callerKey: String?,
    val callKey: String?,
    val message: Any?,
    val deadlineAt: Long,
    val reply: OutcomeDto?,
    val repliedAt: Long?,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private class OutcomeDto(val kind: String, val value: Any?, val reason: String?, val detail: String?) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private class NoticeDto(val sequence: Long, val topic: String, val payload: Any?, val subscribers: List<IdDto>) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private class IdDto(val type: String, val key: String) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private class SeenDto(val type: String, val key: String, val sequence: Long) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private fun InstanceRecord.toDto() = RecordDto(
    type = id.type,
    key = id.key,
    state = state,
    revision = revision,
    stateVersion = stateVersion,
    calls = calls.values.map {
        CallDto(
            it.key.value, it.portName, it.effect.name, it.target, it.request, it.deadlineAt, it.phase.name, it.attempts, it.cancelRequested,
            it.intent, it.lastSentAt, it.unknownReason?.name,
        )
    },
    inbound = inbound.values.map { r ->
        val call = (r.replyTo as? ReplyTarget.ToCall)?.callId
        InboundDto(r.requestId.value, call == null, call?.caller?.type, call?.caller?.key, call?.key?.value, r.message, r.deadlineAt, r.reply?.toDto(), r.repliedAt)
    },
    outbox = outbox.map { n -> NoticeDto(n.sequence, n.topic, n.payload, n.pendingSubscribers.map { IdDto(it.type, it.key) }) },
    nextNoticeSequence = nextNoticeSequence,
    lastSeen = lastSeen.map { (source, sequence) -> SeenDto(source.type, source.key, sequence) },
    keyspace = keyspace,
)

private fun RecordDto.toRecord() = InstanceRecord(
    id = BallId(type, key),
    state = state,
    revision = revision,
    stateVersion = stateVersion,
    calls = calls.associate { c ->
        CallKey(c.key) to CallRecord(
            CallKey(c.key), c.portName, EffectClass.valueOf(c.effect), c.target, c.request, c.deadlineAt,
            Phase.valueOf(c.phase), c.attempts, c.cancelRequested, c.intent, c.lastSentAt, c.unknownReason?.let(UnknownReason::valueOf),
        )
    },
    inbound = inbound.associate { r ->
        val replyTo = if (r.external) ReplyTarget.External else ReplyTarget.ToCall(CallId(BallId(r.callerType!!, r.callerKey!!), CallKey(r.callKey!!)))
        RequestId(r.requestId) to InboundRecord(RequestId(r.requestId), replyTo, r.message, r.deadlineAt, r.reply?.toOutcome(), r.repliedAt)
    },
    outbox = outbox.map { n -> NoticeRecord(n.sequence, n.topic, n.payload, n.subscribers.mapTo(LinkedHashSet()) { BallId(it.type, it.key) }) },
    nextNoticeSequence = nextNoticeSequence,
    lastSeen = lastSeen.associate { BallId(it.type, it.key) to it.sequence },
    keyspace = keyspace,
)

private fun Outcome<Any?>.toDto(): OutcomeDto = when (this) {
    is Outcome.Done -> OutcomeDto("Done", value, null, null)
    is Outcome.Unknown -> OutcomeDto("Unknown", null, reason.name, null)
    is Outcome.NotDone -> when (val r = reason) {
        is NotDoneReason.Refused -> OutcomeDto("NotDone", r.reply, "Refused", null)
        NotDoneReason.Overloaded -> OutcomeDto("NotDone", null, "Overloaded", null)
        NotDoneReason.Cancelled -> OutcomeDto("NotDone", null, "Cancelled", null)
        NotDoneReason.DeadlineBeforeSend -> OutcomeDto("NotDone", null, "DeadlineBeforeSend", null)
        NotDoneReason.NoAnswer -> OutcomeDto("NotDone", null, "NoAnswer", null)
        is NotDoneReason.NotApplied -> OutcomeDto("NotDone", null, "NotApplied", r.evidence)
        NotDoneReason.IdempotencyConflict -> OutcomeDto("NotDone", null, "IdempotencyConflict", null)
        is NotDoneReason.Fault -> OutcomeDto("NotDone", null, "Fault", r.message)
    }
}

private fun OutcomeDto.toOutcome(): Outcome<Any?> = when (kind) {
    "Done" -> Outcome.Done(value)
    "Unknown" -> Outcome.Unknown(UnknownReason.valueOf(reason!!))
    else -> Outcome.NotDone(
        when (reason) {
            "Refused" -> NotDoneReason.Refused(value)
            "Overloaded" -> NotDoneReason.Overloaded
            "Cancelled" -> NotDoneReason.Cancelled
            "DeadlineBeforeSend" -> NotDoneReason.DeadlineBeforeSend
            "NoAnswer" -> NotDoneReason.NoAnswer
            "NotApplied" -> NotDoneReason.NotApplied(detail!!)
            "IdempotencyConflict" -> NotDoneReason.IdempotencyConflict
            "Fault" -> NotDoneReason.Fault(detail!!)
            else -> throw IOException("unknown outcome reason $reason")
        },
    )
}
