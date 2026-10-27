package ballarch.kernel

/**
 * The single terminal result of a call or of an external request.
 *
 * The three cases answer the question a caller must act on: did the requested
 * work take effect at the destination?
 *
 * - [Done]: the destination processed the request and answered with [Done.value].
 *   The value may itself describe a business refusal that the destination recorded.
 * - [NotDone]: there is evidence that the request had no effect.
 * - [Unknown]: the request may or may not have taken effect. Only reconciliation
 *   (for example a status query) can tell.
 */
public sealed interface Outcome<out R> {
    public data class Done<out R>(val value: R) : SafeOutcome<R>

    public data class NotDone<out R>(val reason: NotDoneReason<R>) : SafeOutcome<R>

    public data class Unknown(val reason: UnknownReason) : Outcome<Nothing>
}

/**
 * An outcome that is [Outcome.Done] or [Outcome.NotDone], never
 * [Outcome.Unknown]: the outcome of a call on a Safe port, which nothing can
 * leave uncertain. A `when` over it needs no `Unknown` branch
 * ([safeOutcomeFor]).
 */
public sealed interface SafeOutcome<out R> : Outcome<R>

/** Why a request had no effect. Every case carries or implies evidence of non-application. */
public sealed interface NotDoneReason<out R> {
    /** The destination refused before accepting anything. [reply] explains the refusal. */
    public data class Refused<out R>(val reply: R) : NotDoneReason<R>

    /** A bound was reached before the request was accepted (admission control). */
    public data object Overloaded : NotDoneReason<Nothing>

    /** The call was cancelled before it was sent, or the destination confirmed it never started. */
    public data object Cancelled : NotDoneReason<Nothing>

    /** The deadline passed before the request was sent or decided. */
    public data object DeadlineBeforeSend : NotDoneReason<Nothing>

    /** A [EffectClass.Safe] request got no answer; nothing can have taken effect. */
    public data object NoAnswer : NotDoneReason<Nothing>

    /** The adapter has specific evidence that nothing was applied (for example, connection refused). */
    public data class NotApplied(val evidence: String) : NotDoneReason<Nothing>

    /** An external request reused an idempotency key with a different message. */
    public data object IdempotencyConflict : NotDoneReason<Nothing>

    /** The destination failed while deciding; nothing was committed. */
    public data class Fault(val message: String) : NotDoneReason<Nothing>
}

/** Why the effect of a request cannot be known yet. */
public enum class UnknownReason {
    /** The deadline passed after the request was possibly sent. */
    DeadlineAfterSend,

    /** The transport failed after the request was possibly sent. */
    LostAfterSend,

    /** The runtime restarted while a non-idempotent request was possibly in flight. */
    RecoveredAfterCrash,

    /** Retries of a repeatable request were exhausted without an answer. */
    RetriesExhausted,
}

public fun <R> Outcome<R>.valueOrNull(): R? = (this as? Outcome.Done<R>)?.value

public val Outcome<*>.isDone: Boolean get() = this is Outcome.Done
public val Outcome<*>.isNotDone: Boolean get() = this is Outcome.NotDone
public val Outcome<*>.isUnknown: Boolean get() = this is Outcome.Unknown
