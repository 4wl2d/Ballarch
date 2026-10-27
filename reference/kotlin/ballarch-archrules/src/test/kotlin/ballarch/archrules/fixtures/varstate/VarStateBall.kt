package ballarch.archrules.fixtures.varstate

import ballarch.kernel.Ball
import ballarch.kernel.RequestContext
import ballarch.kernel.accept

/** Violation: a mutable property in the committed state. */
data class Counter(var value: Int)

object VarStateBall : Ball<Counter, Unit, Int> {
    override val type = "varstate"
    override fun initial(key: String) = Counter(0)
    override fun decide(state: Counter, request: Unit, ctx: RequestContext) = accept(Counter(state.value + 1), state.value)
}
