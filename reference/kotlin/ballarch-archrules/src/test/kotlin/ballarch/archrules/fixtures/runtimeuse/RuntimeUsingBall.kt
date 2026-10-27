package ballarch.archrules.fixtures.runtimeuse

import ballarch.kernel.Ball
import ballarch.kernel.RequestContext
import ballarch.kernel.accept
import ballarch.runtime.MemoryStore

/** Violation: a decider reaching into the runtime (and through it, other Balls' records). */
object RuntimeUsingBall : Ball<Int, Unit, Int> {
    override val type = "runtimeuse"
    private val store = MemoryStore()
    override fun initial(key: String) = 0
    override fun decide(state: Int, request: Unit, ctx: RequestContext) = accept(store.ids().size, state)
}
