package ballarch.archrules.fixtures.globalstate

import ballarch.kernel.Ball
import ballarch.kernel.RequestContext
import ballarch.kernel.accept

/** Violation: state outside the Ball's committed state, shared by every instance. */
var decisions = 0

object GlobalStateBall : Ball<Int, Unit, Int> {
    override val type = "globalstate"
    override fun initial(key: String) = 0
    override fun decide(state: Int, request: Unit, ctx: RequestContext) = accept(state + 1, decisions++)
}
