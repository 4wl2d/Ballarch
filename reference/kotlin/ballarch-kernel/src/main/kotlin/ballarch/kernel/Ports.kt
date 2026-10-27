package ballarch.kernel

/**
 * What repeating a request does at its destination. The class decides,
 * mechanically, whether the runtime may retry and whether an unanswered
 * request can end as [Outcome.Unknown].
 *
 * The vocabulary follows HTTP method semantics (RFC 9110, sections 9.2.1–9.2.2).
 */
public enum class EffectClass {
    /** No effect at the destination. Unanswered requests end as [NotDoneReason.NoAnswer]. */
    Safe,

    /**
     * Repeats are deduplicated by the destination: an adapter's destination by
     * the idempotency key the runtime supplies with every attempt, a Ball by
     * call identity.
     */
    Idempotent,

    /** Each delivery may take effect. The runtime never sends such a request twice. */
    NonIdempotent,
}

/**
 * A typed request/response contract owned by its destination. A port is either
 * implemented by an adapter ([AdapterPort]) or served by another Ball ([BallPort]).
 *
 * `Res` is the reply algebra of the destination. It describes both results and
 * refusals; whether anything was applied is expressed by the [Outcome] wrapper.
 */
public sealed interface Port<Req, Res> {
    public val name: String
    public val effect: EffectClass
}

/** A port implemented outside the Ball model, for example an HTTP client or a database. */
public class AdapterPort<Req, Res>(
    override val name: String,
    override val effect: EffectClass,
) : Port<Req, Res> {
    override fun toString(): String = "AdapterPort($name, $effect)"
}

/**
 * The request/response contract of a Ball type, declared in that Ball's public
 * API. Deliveries are deduplicated by call identity at the destination, so Ball
 * ports are idempotent by construction.
 */
public class BallPort<Req, Res>(public val ballType: String) : Port<Req, Res> {
    override val name: String get() = "ball:$ballType"
    override val effect: EffectClass get() = EffectClass.Idempotent

    /** The address of one instance of the destination type. */
    public fun at(key: String): BallId = BallId(ballType, key)

    override fun toString(): String = "BallPort($ballType)"
}

/** A named stream of notices published by Balls and delivered to declared subscribers. */
public class Topic<T>(public val name: String) {
    override fun toString(): String = "Topic($name)"
}
