package ballarch.kernel

/**
 * The pure behaviour of a Ball type.
 *
 * A Ball instance owns state of type [S]. It is changed only by the runtime,
 * which commits what these functions return. Every function must be
 * deterministic and free of side effects: no I/O, clocks, randomness,
 * threads, or mutable state outside its arguments. Values that would otherwise
 * be ambient (time, identity, new keys) come from the [Context].
 *
 * @param S committed state
 * @param M request messages the Ball accepts
 * @param R reply values, including descriptions of refusals
 */
public interface Ball<S, M, R> {
    /** Stable type name; part of every [BallId] and of durable records. */
    public val type: String

    /** State of a newly addressed instance. */
    public fun initial(key: String): S

    /** Decide on a new request. May [reject] it, which commits nothing. */
    public fun decide(state: S, request: M, ctx: RequestContext): Decision<S, R>

    /**
     * Handle the single terminal completion of a call this instance made.
     * A completion cannot be refused: the obligation is closed either way.
     *
     * The default fails fast, so a Ball that makes calls but forgets to handle
     * their completions is detected by its first test. Balls that never call
     * need not override it.
     */
    public fun complete(state: S, completion: Completion<*>, ctx: Context): Step<S, R> =
        throw UnhandledInput("Ball type '$type' made a call but does not override complete()")

    /** Handle a notice from a subscribed topic. The default fails fast like [complete]. */
    public fun observe(state: S, notice: Notice<*>, ctx: Context): Step<S, R> =
        throw UnhandledInput("Ball type '$type' is subscribed to ${notice.topic} but does not override observe()")

    /**
     * Version of the shape of [S] that this code commits, at least 1. A Durable
     * runtime stores it with every committed state. Increase it when states
     * committed by earlier code can no longer be read as [S], and override
     * [migrate] to convert them.
     */
    public val stateVersion: Int get() = 1

    /**
     * Converts a state committed under an earlier [stateVersion] into the
     * current shape. A Durable runtime calls it when it loads a record whose
     * version differs from [stateVersion]. It must be pure, like the deciders.
     *
     * The default accepts no other version, so a Ball type that changes its
     * version without overriding this method makes the runtime refuse to start
     * instead of misreading committed state.
     */
    public fun migrate(fromVersion: Int, state: Any?): S {
        if (fromVersion != stateVersion) {
            throw UnhandledInput("Ball type '$type' commits state version $stateVersion and cannot read version $fromVersion; override migrate()")
        }
        @Suppress("UNCHECKED_CAST")
        return state as S
    }
}

/**
 * Raised by the default [Ball.complete], [Ball.observe] and [Ball.migrate]; the
 * runtime treats it as a programming fault.
 */
public class UnhandledInput(message: String) : RuntimeException(message)

/** The result of [Ball.decide]. */
public sealed interface Decision<out S, out R>

/**
 * The request is accepted. [state] and [outputs] are committed atomically;
 * outputs are dispatched only after the commit.
 */
public data class Accept<out S, out R>(
    val state: S,
    val reply: ReplyPlan<R>,
    val outputs: List<Output<R>> = emptyList(),
) : Decision<S, R>

/** The request is refused. Nothing is committed; the caller receives [NotDoneReason.Refused]. */
public data class Reject<out R>(val reason: R) : Decision<Nothing, R>

/** When an accepted request is answered. */
public sealed interface ReplyPlan<out R> {
    /** Answer as part of this decision. */
    public data class Now<out R>(val value: R) : ReplyPlan<R>

    /** Answer later with a [Reply] output addressed to [RequestContext.requestId]. */
    public data object Later : ReplyPlan<Nothing>
}

/** The result of [Ball.complete] and [Ball.observe]: always committed. */
public data class Step<out S, out R>(
    val state: S,
    val outputs: List<Output<R>> = emptyList(),
)

public fun <S, R> accept(state: S, reply: R, vararg outputs: Output<R>): Accept<S, R> =
    Accept(state, ReplyPlan.Now(reply), outputs.toList())

public fun <S, R> acceptLater(state: S, vararg outputs: Output<R>): Accept<S, R> =
    Accept(state, ReplyPlan.Later, outputs.toList())

public fun <R> reject(reason: R): Reject<R> = Reject(reason)

public fun <S, R> step(state: S, vararg outputs: Output<R>): Step<S, R> = Step(state, outputs.toList())

/**
 * Trusted values supplied by the runtime to a decider invocation. These replace
 * ambient sources (clocks, random identifiers) so that deciders stay pure.
 */
public interface Context {
    /** The instance being decided. */
    public val self: BallId

    /** Runtime clock reading taken when the input was dequeued (epoch milliseconds). */
    public val now: Long

    /** Revision the decision will have if committed. Revisions start at 1 and increase by 1. */
    public val revision: Long

    /** A fresh call key, unique within this instance and deterministic for a given revision. */
    public fun newKey(): CallKey

    /**
     * A new [Intent], issued at [now], for an effect this Ball may request more
     * than once. Keep it in state and name it in every call that requests that
     * effect, for example a repeat after an `Unknown` outcome.
     */
    public fun newIntent(): Intent = Intent(newKey().value, now)
}

/** Context of a request decision. */
public interface RequestContext : Context {
    public val requestId: RequestId
    public val caller: Caller
}

/**
 * Who sent a request. [principal] is an identity authenticated by the inbound
 * adapter; the decider interprets it but never verifies credentials itself.
 */
public data class Caller(val ball: BallId? = null, val principal: String? = null) {
    public companion object {
        public val Anonymous: Caller = Caller()
    }
}
