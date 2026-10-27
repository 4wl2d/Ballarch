package ballarch.runtime

import ballarch.kernel.CallId

/**
 * Executes calls made on one [ballarch.kernel.AdapterPort].
 *
 * Contract (checked by the adapter conformance kit in `ballarch-testkit`):
 * - report through [done] at most once per invocation; later reports are ignored;
 * - never retry internally; retries belong to the runtime (see [RetryPolicy]);
 * - report [AdapterResult.NotApplied] only with evidence that the request had
 *   no effect; when in doubt report [AdapterResult.Failed], which the runtime
 *   classifies by the port's effect class;
 * - pass [Invocation.idempotencyKey] to a destination that accepts one, and
 *   never create a key of its own: only the runtime creates keys.
 */
public fun interface Adapter<Req, Res> {
    public fun execute(invocation: Invocation<Req>, done: (AdapterResult<Res>) -> Unit): Cancellable?
}

/**
 * One attempt to perform a call.
 *
 * @property idempotencyKey the key the runtime gives every attempt of the call;
 *   calls that name the same [ballarch.kernel.Intent] share it. It never repeats
 *   for another effect, also after a Transient restart, in which call identities
 *   repeat. A derived key is at most 76 printable ASCII characters, safe in an
 *   HTTP header; a call of a record written before keyspaces existed keeps its
 *   call identity as key.
 */
public data class Invocation<Req>(
    val callId: CallId,
    val request: Req,
    val attempt: Int,
    val deadlineAt: Long,
    val idempotencyKey: String,
)

public sealed interface AdapterResult<out Res> {
    /** The destination processed the request. */
    public data class Ok<out Res>(val value: Res) : AdapterResult<Res>

    /** The destination refused the request without applying anything. */
    public data class Refused<out Res>(val value: Res) : AdapterResult<Res>

    /** Evidence that nothing was applied, for example a connection refused before the request was written. */
    public data class NotApplied(val evidence: String) : AdapterResult<Nothing>

    /** No answer, or an error after the request may have left. The runtime classifies it. */
    public data class Failed(val error: String) : AdapterResult<Nothing>
}

/**
 * Runtime-owned retry budget for a port. Only [ballarch.kernel.EffectClass.Safe] and Idempotent ports retry.
 *
 * @property attemptTimeoutMillis how long an attempt may go without a report. When it passes, the
 *   runtime asks the adapter to stop the attempt, and the attempt ends without an answer: the call is
 *   retried if its budget and deadline allow, and resolved otherwise. An answer that the stopped attempt
 *   still delivers while the call is unresolved resolves it `Done`. `null`, the default, lets an attempt
 *   last until the call's deadline, or on a reconciled port until its cutoff ([Reconciliation]). Only Safe
 *   and Idempotent ports take one; declare it when [maxAttempts] exceeds 1, or a lost answer uses up the
 *   whole deadline.
 */
public data class RetryPolicy(val maxAttempts: Int = 1, val backoffMillis: Long = 0, val attemptTimeoutMillis: Long? = null) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(backoffMillis >= 0) { "backoffMillis must not be negative" }
        require(attemptTimeoutMillis == null || attemptTimeoutMillis > 0) { "attemptTimeoutMillis must be positive" }
    }

    public companion object {
        public val None: RetryPolicy = RetryPolicy()
    }
}
