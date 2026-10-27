package ballarch.archrules.fixtures.random

import ballarch.kernel.Ball
import ballarch.kernel.RequestContext
import ballarch.kernel.accept
import kotlin.random.Random

/** Violation: ambient randomness instead of ctx.newKey() or an input. */
object RandomBall : Ball<Int, Unit, Int> {
    override val type = "random"
    override fun initial(key: String) = 0
    override fun decide(state: Int, request: Unit, ctx: RequestContext) = accept(Random.nextInt(), state)
}
