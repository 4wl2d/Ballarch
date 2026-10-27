package ballarch.kernel

/**
 * Something a decision asks the runtime to do after the decision is committed.
 * `R` is the reply type of the deciding Ball (used only by [Reply]).
 */
public sealed interface Output<out R>

/**
 * Ask a port to perform a request. Committing a decision with a call creates an
 * obligation: the runtime later delivers exactly one [Completion] with this [key].
 * [Context.call] makes a call together with the [CallRef] of its completion.
 *
 * @property target instance key when [port] is a [BallPort]; ignored for adapters.
 * @property timeoutMillis finite deadline, measured from commit.
 * @property intent the effect this call requests, when the Ball may request it
 *   again: calls that name the same intent carry the same idempotency key. Only
 *   a call on an Idempotent adapter port may name one.
 */
public data class Call<Req, Res>(
    val key: CallKey,
    val port: Port<Req, Res>,
    val request: Req,
    val timeoutMillis: Long,
    val target: String? = null,
    val intent: Intent? = null,
) : Output<Nothing> {
    init {
        require(timeoutMillis > 0) { "a call needs a positive, finite timeout" }
        require(port !is BallPort<*, *> || target != null) { "a call to ${port.name} needs a target instance key" }
    }
}

/**
 * A call made with [Context.call] or [Timer.after], and the [ref] that
 * recognises its completion. Keep [ref] in state and return [call] among the
 * outputs of the decision: `val (ref, call) = ctx.call(port, request, timeout)`.
 */
public data class Issued<Req, Res>(val ref: CallRef<Res>, val call: Call<Req, Res>)

/**
 * A [Call] on [port] with a new key from this context, issued with its
 * [CallRef]. The parameters are those of [Call].
 */
public fun <Req, Res> Context.call(
    port: Port<Req, Res>,
    request: Req,
    timeoutMillis: Long,
    target: String? = null,
    intent: Intent? = null,
): Issued<Req, Res> {
    val key = newKey()
    return Issued(CallRef(key, port.name), Call(key, port, request, timeoutMillis, target, intent))
}

/** Ask the runtime to cancel an open call. Its completion still arrives and may be [Outcome.Done]. */
public data class Cancel(val key: CallKey) : Output<Nothing>

/** Answer an inbound request that was accepted earlier with a deferred reply. */
public data class Reply<out R>(val to: RequestId, val value: R) : Output<R>

/** Publish a notice to the subscribers declared for [topic]. */
public data class Publish<T>(val topic: Topic<T>, val payload: T) : Output<Nothing>

/** The terminal outcome of a call made by this instance. Delivered exactly once per committed call. */
public data class Completion<Res>(
    val key: CallKey,
    val port: Port<*, Res>,
    val outcome: Outcome<Res>,
)

/**
 * Returns the outcome typed by [expected] when this completion belongs to a call
 * made on that port, or `null` otherwise. Prefer [outcomeFor], which also tells
 * the call whose completion matters from other calls on the same port.
 */
@Suppress("UNCHECKED_CAST")
public fun <Res> Completion<*>.outcomeOf(expected: Port<*, Res>): Outcome<Res>? =
    if (port === expected || port.name == expected.name) outcome as Outcome<Res> else null

/**
 * Returns the outcome of the call [ref] names, typed by its port, when this
 * completion belongs to that call, or `null` when it belongs to another call.
 */
@Suppress("UNCHECKED_CAST")
public fun <Res> Completion<*>.outcomeFor(ref: CallRef<Res>): Outcome<Res>? =
    if (key == ref.key && port.name == ref.portName) outcome as Outcome<Res> else null

/**
 * [outcomeFor] a call on a Safe port, typed as a [SafeOutcome]: a `when` over
 * it has no `Unknown` branch.
 *
 * @throws IllegalArgumentException if this completion belongs to the call [ref]
 *   names, and its port is not Safe.
 * @throws IllegalStateException if the call ended `Unknown` all the same, which
 *   happens only to a call committed while its port was bound with another
 *   effect class: the runtime classifies a call by the class recorded at commit.
 */
public fun <Res> Completion<*>.safeOutcomeFor(ref: CallRef<Res>): SafeOutcome<Res>? {
    val outcome = outcomeFor(ref) ?: return null
    require(port.effect == EffectClass.Safe) { "${port.name} is ${port.effect}, so its calls may end Unknown; use outcomeFor" }
    return outcome as? SafeOutcome<Res>
        ?: throw IllegalStateException("call $key on ${port.name} ended $outcome; it was committed while the port had another effect class")
}

/** A published notice delivered to a subscribing instance. */
public data class Notice<T>(
    val topic: Topic<T>,
    val source: BallId,
    val sequence: Long,
    val payload: T,
)

/** Returns the payload typed by [expected] when this notice belongs to that topic, or `null`. */
@Suppress("UNCHECKED_CAST")
public fun <T> Notice<*>.payloadOf(expected: Topic<T>): T? =
    if (topic === expected || topic.name == expected.name) payload as T else null
