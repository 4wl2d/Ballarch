package ballarch.archrules.fixtures.featurea

import ballarch.archrules.fixtures.featureb.BPricing
import ballarch.archrules.fixtures.featureb.api.BRequest
import ballarch.archrules.fixtures.featurea.api.ARequest
import ballarch.kernel.Ball
import ballarch.kernel.RequestContext
import ballarch.kernel.accept

/** Uses B's API (allowed) and B's internal pricing (violation of R10). */
object ABall : Ball<Int, ARequest, BRequest> {
    override val type = "a"
    override fun initial(key: String) = 0
    override fun decide(state: Int, request: ARequest, ctx: RequestContext) = accept(BPricing.price(request.n), BRequest(state))
}
