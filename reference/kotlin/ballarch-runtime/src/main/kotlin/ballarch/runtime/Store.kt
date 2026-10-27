package ballarch.runtime

import ballarch.kernel.BallId
import ballarch.kernel.CallId
import ballarch.kernel.CallKey
import ballarch.kernel.EffectClass
import ballarch.kernel.Intent
import ballarch.kernel.Outcome
import ballarch.kernel.RequestId
import ballarch.kernel.UnknownReason
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the runtime keeps for one Ball instance. A record is replaced as a
 * whole, atomically, so the business state, the call ledger, the inbound
 * request table and the notice outbox can never disagree after a crash.
 */
public data class InstanceRecord(
    val id: BallId,
    val state: Any?,
    /** Revision of the last committed decision; 0 before the first commit. */
    val revision: Long,
    /** Open outbound calls, keyed by call key. */
    val calls: Map<CallKey, CallRecord> = emptyMap(),
    /** Inbound requests that are still owed a reply, and recently answered ones kept for deduplication. */
    val inbound: Map<RequestId, InboundRecord> = emptyMap(),
    /** Published notices not yet committed by every subscriber. */
    val outbox: List<NoticeRecord> = emptyList(),
    val nextNoticeSequence: Long = 1,
    /** Highest notice sequence applied from each publisher (subscriber-side deduplication). */
    val lastSeen: Map<BallId, Long> = emptyMap(),
    /** [ballarch.kernel.Ball.stateVersion] of the code that committed [state]. */
    val stateVersion: Int = 1,
    /**
     * Prefix of the idempotency keys of this instance's calls, issued when the
     * runtime created the record. A record created again, after a Transient
     * restart or a [Store.delete], gets a new one, so keys never repeat although
     * call keys do. `null` in a record written before keyspaces existed: its
     * calls keep their call identity as idempotency key.
     */
    val keyspace: String? = null,
)

/** Durable phase of an open call (see the write-ahead rule in the specification). */
public enum class Phase {
    /** Committed; never handed to a transport. The runtime can still withdraw it truthfully. */
    Pending,

    /** Recorded as possibly sent before any transport was invoked. */
    Sending,

    /**
     * Every attempt ended without an answer on a port whose binding declares
     * reconciliation, and no attempt remains: the runtime asks the binding's
     * status port about the call once the destination's delivery horizon has
     * passed since the last attempt was sent ([Reconciliation]).
     */
    Reconciling,
}

public data class CallRecord(
    val key: CallKey,
    val portName: String,
    /**
     * Effect class of the port when the call was committed. The runtime
     * classifies the call by it for its whole life, including after a restart
     * under a composition that binds the port differently.
     */
    val effect: EffectClass,
    val target: String?,
    val request: Any?,
    val deadlineAt: Long,
    val phase: Phase,
    val attempts: Int,
    val cancelRequested: Boolean = false,
    /** The intent the call names; with the record's keyspace it gives the call's idempotency key. */
    val intent: Intent? = null,
    /** When the last attempt was sent: set by the write-ahead record of each attempt. */
    val lastSentAt: Long? = null,
    /**
     * In phase [Phase.Reconciling]: the reason the call resolves `Unknown` with
     * if reconciliation settles nothing, which is the reason it would have had
     * without reconciliation.
     */
    val unknownReason: UnknownReason? = null,
)

public sealed interface ReplyTarget {
    /** A caller outside the runtime. Its callback is volatile and does not survive a restart. */
    public data object External : ReplyTarget

    /** Another Ball instance waiting for the completion of [callId]. */
    public data class ToCall(val callId: CallId) : ReplyTarget
}

public data class InboundRecord(
    val requestId: RequestId,
    val replyTo: ReplyTarget,
    /** Kept to detect idempotency-key reuse with a different message. */
    val message: Any?,
    val deadlineAt: Long,
    /** `null` while the reply is owed. */
    val reply: Outcome<Any?>? = null,
    val repliedAt: Long? = null,
)

public data class NoticeRecord(
    val sequence: Long,
    val topic: String,
    val payload: Any?,
    val pendingSubscribers: Set<BallId>,
)

/**
 * Where instance records live. [save] must replace the record atomically.
 *
 * Profiles differ only in what survives a crash: a Transient runtime starts with
 * an empty store after a restart; a Durable runtime is restarted on the same
 * store.
 *
 * Only one runtime may commit to a store at a time (R16). A store ensures it in
 * one of two ways: it excludes a second runtime while the first holds it
 * ([nextIncarnation] throws [StoreLockedException]), or it refuses every write
 * from a runtime that started on it before the most recent one did
 * ([save] with an incarnation throws [FencedException]). A store that does
 * neither runs one runtime at a time only if its deployment ensures that.
 */
public interface Store {
    /**
     * The record of [id], or `null`. The runtime calls it on its loop and, for
     * reads ([Runtime.read]), from any thread, so it must be safe to call while
     * [save] replaces a record: it returns the previous or the new record.
     */
    public fun load(id: BallId): InstanceRecord?

    public fun save(record: InstanceRecord)

    /**
     * [save] for the runtime that started with [incarnation]. A store that fences
     * throws [FencedException], and writes nothing, when a later runtime took a
     * newer incarnation. The default does not fence and calls [save].
     */
    public fun save(record: InstanceRecord, incarnation: Long): Unit = save(record)

    /**
     * The ids of every stored record. A store whose records hold the id apart
     * from the encoded state lists them even when the state cannot be decoded.
     */
    public fun ids(): Set<BallId>

    /**
     * Removes the record of [id] atomically, if there is one. This retires an
     * instance for good: an operator removes the records of a Ball type that a
     * new composition no longer registers, after draining their open calls,
     * owed replies and undelivered notices under one that does. Removing a
     * record that still holds obligations loses them. A runtime never calls it.
     */
    public fun delete(id: BallId)

    /**
     * Increments and returns the incarnation counter; called once per runtime
     * start, before the runtime recovers. A store that excludes a second runtime
     * takes hold of the store here, and throws [StoreLockedException] if another
     * runtime holds it.
     */
    public fun nextIncarnation(): Long
}

/**
 * A store refused a write because a later runtime took it over (R16). The
 * runtime that tried to write stops at once and dispatches nothing from that
 * write ([Runtime.fenced]).
 */
public class FencedException(message: String) : IllegalStateException(message)

/**
 * A store is held by another runtime, which it excludes until that runtime's
 * process ends or it closes the store (R16). The runtime refused to start and
 * wrote nothing.
 */
public class StoreLockedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * An in-memory store. Records are immutable values, so sharing the same
 * [MemoryStore] between a crashed runtime and its successor simulates a durable
 * database; giving the successor a new instance simulates the Transient profile.
 *
 * It fences (R16): once a runtime has started on it, a write of a runtime that
 * started earlier is refused with [FencedException].
 */
public class MemoryStore : Store {
    private val records = ConcurrentHashMap<BallId, InstanceRecord>()
    private var incarnation = 0L

    /** Number of successful [save] calls; used by tests. */
    @Volatile public var saves: Long = 0
        private set

    override fun load(id: BallId): InstanceRecord? = records[id]

    @Synchronized
    override fun save(record: InstanceRecord) {
        records[record.id] = record
        saves++
    }

    @Synchronized
    override fun save(record: InstanceRecord, incarnation: Long) {
        if (incarnation < this.incarnation) {
            throw FencedException("incarnation $incarnation may not write to this store: incarnation ${this.incarnation} started on it")
        }
        save(record)
    }

    override fun ids(): Set<BallId> = records.keys.toSet()

    override fun delete(id: BallId) {
        records.remove(id)
    }

    @Synchronized
    override fun nextIncarnation(): Long = ++incarnation
}
