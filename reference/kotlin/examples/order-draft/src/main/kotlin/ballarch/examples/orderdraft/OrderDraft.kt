package ballarch.examples.orderdraft

import ballarch.kernel.Ball
import ballarch.kernel.Decision
import ballarch.kernel.RequestContext
import ballarch.kernel.accept
import ballarch.kernel.reject

/** S0: a draft owns the quantity a customer intends to buy (1–20). No external effects. */
data class Draft(val quantity: Int)

sealed interface DraftRequest {
    data class SetQuantity(val value: Int) : DraftRequest
    data object GetQuantity : DraftRequest
}

sealed interface DraftReply {
    data class Quantity(val value: Int) : DraftReply
    data object OutOfRange : DraftReply
}

object OrderDraftBall : Ball<Draft, DraftRequest, DraftReply> {
    val allowed = 1..20

    override val type = "order-draft"

    override fun initial(key: String) = Draft(quantity = 1)

    override fun decide(state: Draft, request: DraftRequest, ctx: RequestContext): Decision<Draft, DraftReply> =
        when (request) {
            is DraftRequest.SetQuantity ->
                if (request.value in allowed) accept(Draft(request.value), DraftReply.Quantity(request.value))
                else reject(DraftReply.OutOfRange)
            DraftRequest.GetQuantity -> accept(state, DraftReply.Quantity(state.quantity))
        }
}
