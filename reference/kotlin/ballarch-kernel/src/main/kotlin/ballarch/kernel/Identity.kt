package ballarch.kernel

import java.io.Serializable

/** Address of one Ball instance: its type name plus an instance key. */
public data class BallId(val type: String, val key: String) {
    init {
        require(type.isNotBlank()) { "Ball type must not be blank" }
    }

    override fun toString(): String = "$type/$key"
}

/**
 * Identifies an outbound call within the calling instance.
 *
 * Keys are created with [Context.newKey], which derives them from the
 * revision being committed, so they are deterministic and unique among the
 * instance's calls without reading a clock or a random source.
 */
@JvmInline
public value class CallKey(public val value: String) {
    override fun toString(): String = value
}

/** Globally unique identity of an outbound call: caller instance plus key. */
public data class CallId(val caller: BallId, val key: CallKey) {
    override fun toString(): String = "$caller#$key"
}

/**
 * Names one call of this instance, typed by the reply type [Res] of its port,
 * so that the call's completion is read without a cast ([outcomeFor]). Obtain
 * it with the call from [Context.call] or [Timer.after], and keep it in state
 * while the call is open: a completion it does not match belongs to another
 * call.
 */
public data class CallRef<out Res>(val key: CallKey, val portName: String) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * A runtime-issued identity for one effect that a Ball may request more than
 * once. Every call that names it carries the same idempotency key, so a
 * destination that deduplicates keys applies at most one of those calls.
 *
 * Only [Context.newIntent] issues intents. [issuedAt] is the time of the
 * decision that issued it; a port's deduplication window is measured from it.
 * Name an intent only on an Idempotent adapter port, and only for calls that
 * request the same effect.
 */
@ConsistentCopyVisibility
public data class Intent internal constructor(val id: String, val issuedAt: Long) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Identity of an inbound request, unique within the receiving instance. */
@JvmInline
public value class RequestId(public val value: String) {
    override fun toString(): String = value
}
