package ballarch.examples.orderdraft

import ballarch.examples.orderdraft.plain.PlainOrderDraft
import ballarch.kernel.Accept
import ballarch.kernel.BallId
import ballarch.kernel.FixedContext
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.ReplyPlan
import ballarch.kernel.Reject
import ballarch.runtime.Composition
import ballarch.testkit.Harness
import ballarch.testkit.Profile
import ballarch.testkit.RandomChooser
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderDraftTest {
    private val ctx = FixedContext(BallId("order-draft", "d"))

    // Deciders are plain functions: test them without a runtime.
    @Test
    fun `accepts the boundaries and refuses values outside`() {
        val s = Draft(1)
        assertEquals(Accept(Draft(20), ReplyPlan.Now(DraftReply.Quantity(20))), OrderDraftBall.decide(s, DraftRequest.SetQuantity(20), ctx))
        assertEquals(Reject(DraftReply.OutOfRange), OrderDraftBall.decide(s, DraftRequest.SetQuantity(21), ctx))
        assertEquals(Reject(DraftReply.OutOfRange), OrderDraftBall.decide(s, DraftRequest.SetQuantity(0), ctx))
    }

    // Through the runtime, a refusal commits nothing.
    @Test
    fun `a refused change leaves the committed quantity unchanged`() {
        val h = Harness(RandomChooser(0), Profile.Transient) { Composition().ball(OrderDraftBall) }.start()
        val id = BallId("order-draft", "d")
        h.send(id, DraftRequest.SetQuantity(20))
        h.send(id, DraftRequest.SetQuantity(21))
        h.runUntilIdle()
        assertEquals(Outcome.Done(DraftReply.Quantity(20)), h.externalOutcomes[0])
        assertEquals(Outcome.NotDone(NotDoneReason.Refused(DraftReply.OutOfRange)), h.externalOutcomes[1])
        assertEquals(Draft(20), h.runtime.inspect(id))
        h.assertInvariants()
    }

    @Test
    fun `plain baseline has the same behaviour`() {
        val d = PlainOrderDraft()
        assertEquals(true, d.setQuantity(20))
        assertEquals(false, d.setQuantity(21))
        assertEquals(20, d.quantity)
    }
}
