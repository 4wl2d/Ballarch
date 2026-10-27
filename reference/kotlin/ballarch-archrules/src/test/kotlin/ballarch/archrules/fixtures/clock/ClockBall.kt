package ballarch.archrules.fixtures.clock

import ballarch.kernel.Ball
import ballarch.kernel.RequestContext
import ballarch.kernel.accept

/** Violation: reads the system clock instead of ctx.now. */
object ClockBall : Ball<Long, Unit, Long> {
    override val type = "clock"
    override fun initial(key: String) = 0L
    override fun decide(state: Long, request: Unit, ctx: RequestContext) = accept(System.currentTimeMillis(), state)
}
