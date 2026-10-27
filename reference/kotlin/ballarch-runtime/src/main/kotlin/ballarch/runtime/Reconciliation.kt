package ballarch.runtime

import ballarch.kernel.AdapterPort
import ballarch.kernel.EffectClass

/**
 * Reconciliation of an Idempotent or NonIdempotent adapter port (Core §3.2,
 * an optional clause). A call whose attempts all end without an answer is not
 * resolved `Unknown` at once: the runtime waits until the destination can no
 * longer apply it, then asks [status] about it.
 *
 * - An attempt with no report by the call's deadline minus [horizonMillis]
 *   minus [statusTimeoutMillis] ends without an answer, and a retry is sent
 *   only before that cutoff.
 * - When no answer has arrived and no attempt remains, the call enters phase
 *   [Phase.Reconciling]. Once [horizonMillis] has passed since its last
 *   attempt was sent, the runtime makes one attempt on [status] with the
 *   request [query] makes from the call's invocation, and gives it
 *   [statusTimeoutMillis] to answer.
 * - [verdict] reads the answer. [StatusVerdict.Applied] resolves the call
 *   `Done` with its value, and [StatusVerdict.NotApplied] resolves it
 *   `NotDone(NotApplied)`. [StatusVerdict.Inconclusive], or a status attempt
 *   that ends without an answer, resolves it `Unknown` with the reason it
 *   would have had without reconciliation.
 * - An answer to the call's own request that arrives while it is reconciling
 *   resolves it `Done`.
 * - Under the Durable profile the phase is recorded. A call recovered in it
 *   resumes its status query, and one recovered while possibly sent enters it
 *   instead of resolving `Unknown(RecoveredAfterCrash)`.
 *
 * The status attempt is not a call: it emits no `CallSent`, is not retried,
 * and does not wait for a slot of the status port. Its invocation carries the
 * call's identity and idempotency key, attempt 1, and a deadline
 * [statusTimeoutMillis] after it starts.
 *
 * A report of non-application is evidence only as far as the destination keeps
 * its horizon: every request that reaches it does so within [horizonMillis] of
 * its send. A call on a reconciled port needs a timeout greater than
 * [horizonMillis] plus [statusTimeoutMillis]; preflight refuses a shorter one
 * as a fault.
 *
 * @param status a Safe adapter port, bound in the same composition, that reports what became of a call.
 * @param horizonMillis the destination's delivery horizon.
 * @param statusTimeoutMillis how long the status attempt may take.
 * @param query the status request about the call that its invocation describes. Pure.
 * @param verdict what an answer of [status] says about the call. Pure.
 */
public class Reconciliation<Req, Res, SReq, SRes>(
    public val status: AdapterPort<SReq, SRes>,
    public val horizonMillis: Long,
    public val statusTimeoutMillis: Long,
    public val query: (Invocation<Req>) -> SReq,
    public val verdict: (SRes) -> StatusVerdict<Res>,
) {
    init {
        require(status.effect == EffectClass.Safe) { "status port '${status.name}' is ${status.effect}; it must be Safe" }
        require(horizonMillis > 0) { "horizonMillis must be positive" }
        require(statusTimeoutMillis > 0) { "statusTimeoutMillis must be positive" }
    }
}

/** What a status port says about one call ([Reconciliation.verdict]). */
public sealed interface StatusVerdict<out Res> {
    /** The call's effect was applied, and [value] is the answer to its request. */
    public data class Applied<out Res>(val value: Res) : StatusVerdict<Res>

    /** The call's effect was not applied, and since the destination's horizon has passed, it will not be. */
    public data object NotApplied : StatusVerdict<Nothing>

    /** The status does not tell. */
    public data object Inconclusive : StatusVerdict<Nothing>
}
