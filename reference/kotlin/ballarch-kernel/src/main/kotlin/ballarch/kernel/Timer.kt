package ballarch.kernel

/**
 * Time as a call. A timer is a [Call] on [Timer.port], made with [after]; its
 * completion is `Done(Unit)` when the timer is due. Cancelled before it fires,
 * it completes `NotDone(NotApplied)` (the timer reports that it did not fire);
 * cancelled while still waiting to start, `NotDone(Cancelled)`. A timer never
 * completes before its due time unless cancelled: under the Durable profile an
 * open timer is re-armed after a restart, and if the restart comes after its
 * deadline (due time plus [MARGIN_MILLIS]) it completes `NotDone(NoAnswer)`.
 * Treat a completion as a wake-up and re-check conditions kept in state.
 * Every conforming runtime binds this port.
 */
public object Timer {
    public val port: AdapterPort<Long, Unit> = AdapterPort("ballarch.timer", EffectClass.Safe)

    /** Margin between the delay and the call's deadline. */
    public const val MARGIN_MILLIS: Long = 1_000

    /** A call that completes after [delayMillis]. */
    public fun after(key: CallKey, delayMillis: Long): Call<Long, Unit> {
        require(delayMillis >= 0) { "delay must not be negative" }
        return Call(key, port, delayMillis, timeoutMillis = delayMillis + MARGIN_MILLIS)
    }

    /** A call that completes after [delayMillis], with a new key from [ctx], issued with its [CallRef] like [Context.call]. */
    public fun after(ctx: Context, delayMillis: Long): Issued<Long, Unit> {
        val key = ctx.newKey()
        return Issued(CallRef(key, port.name), after(key, delayMillis))
    }
}
