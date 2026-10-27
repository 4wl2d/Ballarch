package ballarch.examples.shop.app

import ballarch.examples.shop.checkout.CheckoutReply
import ballarch.examples.shop.checkout.CheckoutRequest
import ballarch.examples.shop.checkout.OrderSpec
import ballarch.examples.shop.checkout.Phase
import ballarch.examples.shop.payments.ChargeResult
import ballarch.examples.shop.payments.ChargeStatus
import ballarch.examples.shop.payments.ProviderCharge
import ballarch.examples.shop.payments.ProviderStatus
import ballarch.kernel.BallId
import ballarch.kernel.Outcome
import ballarch.runtime.Adapter
import ballarch.runtime.AdapterResult
import ballarch.runtime.TraceEvent
import ballarch.testkit.Fate
import ballarch.testkit.Harness
import ballarch.testkit.RandomChooser
import ballarch.testkit.SimPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShopAppTest {
    @Test
    fun `a screen reads the checkout status, which commits nothing`() {
        val h = Harness(RandomChooser(1)) {
            val provider = ProviderAdapters(
                charge = SimPort(ProviderCharge, scheduler, chooser, truth, listOf(Fate.Reply)) { ChargeResult.Charged("ref-${it.orderId}") },
                status = SimPort(ProviderStatus, scheduler, chooser, truth, listOf(Fate.Reply)) { ChargeStatus.NoCharge },
                horizonMillis = 1_000,
            )
            shopComposition(provider, initialStock = 5)
        }.start()
        assertEquals(CheckoutReply.Status(Phase.New), checkoutStatus(h.runtime, "o1"))
        h.send(BallId(Checkout.type, "o1"), CheckoutRequest.Start(OrderSpec("sku-1", quantity = 1, amount = 100)), timeoutMillis = 5_000)
        h.runUntilIdle()
        h.assertInvariants()
        val saves = h.store.saves
        repeat(3) { assertEquals(CheckoutReply.Status(Phase.Completed, "ref-o1"), checkoutStatus(h.runtime, "o1")) }
        assertEquals(saves, h.store.saves)
    }

    @Test
    fun `a charge whose answer is lost is captured through the provider's status, and charged once`() {
        val h = lostChargeAnswer(horizonMillis = 1_000)
        assertEquals(CheckoutReply.Status(Phase.Completed, "ref-o1"), checkoutStatus(h.runtime, "o1"))
        assertEquals(1, h.truth.applied.values.sum())
        val charge = h.tracer.events.filterIsInstance<TraceEvent.CallResolved>().single { it.callId.caller.type == "payment" }
        assertTrue(charge.reconciled && charge.outcome is Outcome.Done, "$charge")
    }

    @Test
    fun `a Capture refused while an earlier one may have started the charge does not fail the order`() {
        // With a 20 s horizon the checkout's Captures time out and fill the payment's
        // waiting list; the later ones are refused Busy while the charge is still settling.
        val h = lostChargeAnswer(horizonMillis = 20_000)
        assertEquals(CheckoutReply.Status(Phase.Completed, "ref-o1"), checkoutStatus(h.runtime, "o1"))
        assertEquals(1, h.truth.applied.values.sum())
    }

    /** One checkout whose charge answer is lost; the provider's status reads the real charges. */
    private fun lostChargeAnswer(horizonMillis: Long): Harness {
        val charges = HashMap<String, String>()
        val h = Harness(RandomChooser(1)) {
            val provider = ProviderAdapters(
                charge = SimPort(ProviderCharge, scheduler, chooser, truth, listOf(Fate.LoseReply)) { request ->
                    ChargeResult.Charged(charges.getOrPut(request.orderId) { "ref-${request.orderId}" })
                },
                status = Adapter { inv, done ->
                    scheduler.schedule(10) { done(AdapterResult.Ok(charges[inv.request.orderId]?.let(ChargeStatus::Charged) ?: ChargeStatus.NoCharge)) }
                    null
                },
                horizonMillis = horizonMillis,
            )
            shopComposition(provider, initialStock = 5)
        }.start()
        h.send(BallId(Checkout.type, "o1"), CheckoutRequest.Start(OrderSpec("sku-1", quantity = 1, amount = 100)), timeoutMillis = 5_000)
        h.runUntilIdle()
        h.assertInvariants()
        return h
    }
}
