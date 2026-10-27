package ballarch.kernel

/**
 * A plain [RequestContext] for calling deciders directly in unit tests and for
 * runtimes. Keys are `"<revision>.<n>"`, which makes them unique per instance
 * as long as revisions are unique.
 */
public class FixedContext(
    override val self: BallId,
    override val now: Long = 0L,
    override val revision: Long = 1L,
    override val requestId: RequestId = RequestId("test"),
    override val caller: Caller = Caller.Anonymous,
) : RequestContext {
    private var issued = 0

    override fun newKey(): CallKey = CallKey("$revision.${issued++}")

    /** Number of keys issued so far; runtimes use it to check key uniqueness. */
    public val keysIssued: Int get() = issued
}
