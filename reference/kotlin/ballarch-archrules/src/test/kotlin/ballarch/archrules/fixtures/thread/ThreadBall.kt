package ballarch.archrules.fixtures.thread

import ballarch.kernel.Ball
import ballarch.kernel.RequestContext
import ballarch.kernel.accept

/** Violation: blocks the loop and depends on scheduling. */
object ThreadBall : Ball<Int, Unit, Int> {
    override val type = "thread"
    override fun initial(key: String) = 0
    override fun decide(state: Int, request: Unit, ctx: RequestContext): ballarch.kernel.Decision<Int, Int> {
        Thread.sleep(1)
        return accept(state + 1, state)
    }
}
