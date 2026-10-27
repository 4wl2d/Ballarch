package ballarch.archrules.fixtures.mutablecollection

import ballarch.kernel.Ball
import ballarch.kernel.RequestContext
import ballarch.kernel.accept

/** Violation: a mutable collection in the committed state. */
data class Basket(val items: MutableList<String>)

object MutableCollectionBall : Ball<Basket, String, Int> {
    override val type = "mutablecollection"
    override fun initial(key: String) = Basket(mutableListOf())
    override fun decide(state: Basket, request: String, ctx: RequestContext) =
        accept(Basket((state.items + request).toMutableList()), state.items.size)
}
