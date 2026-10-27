package ballarch.examples.placeorder

import ballarch.examples.placeorder.PlaceOrderState.Checking
import ballarch.examples.placeorder.PlaceOrderState.Idle
import ballarch.examples.placeorder.PlaceOrderState.NotPlaced
import ballarch.examples.placeorder.PlaceOrderState.Placed
import ballarch.examples.placeorder.PlaceOrderState.Placing
import ballarch.examples.placeorder.app.ORDERS_HORIZON_MILLIS
import ballarch.examples.placeorder.app.placeOrder
import ballarch.kernel.Accept
import ballarch.kernel.BallId
import ballarch.kernel.Call
import ballarch.kernel.CallKey
import ballarch.kernel.CallRef
import ballarch.kernel.Completion
import ballarch.kernel.FixedContext
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.kernel.Reject
import ballarch.kernel.ReplyPlan
import ballarch.kernel.UnknownReason
import ballarch.runtime.Adapter
import ballarch.runtime.Composition
import ballarch.runtime.TraceEvent
import ballarch.testkit.Chooser
import ballarch.testkit.ExhaustiveChooser
import ballarch.testkit.Fate
import ballarch.testkit.Harness
import ballarch.testkit.Profile
import ballarch.testkit.SimPort
import ballarch.testkit.explore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class PlaceOrderTest {
    private val id = BallId(PlaceOrder.type, "draft-1")
    private val cart = Cart(listOf("tea", "cups"))
    private val other = Cart(listOf("coffee"))
    private val ctx = FixedContext(id, now = 1_000, revision = 3)
    private val intent = FixedContext(id, now = 500, revision = 1).newIntent()
    private val call = CallRef<Order>(CallKey("2.0"), Orders.name)

    // ---------------------------------------------------------------- the decider as a function

    @Test
    fun `decide places a new order, joins a double tap, and repeats an unknown one with its intent`() {
        val placing = Placing(cart, intent, call, repeat = false)
        val checking = Checking(cart, intent)
        // the stage, the cart placed -> what the decider does
        val rows: List<Triple<PlaceOrderState, Cart, String>> = listOf(
            Triple(Idle, cart, "call, new intent"),
            Triple(NotPlaced("refused"), cart, "call, new intent"),
            Triple(placing, cart, "joined"),
            Triple(placing, other, "rejected"),
            Triple(checking, cart, "call, same intent, repeat"),
            Triple(checking, other, "rejected"),
            Triple(Placed("o-1"), cart, "joined"),
        )
        for ((state, items, expected) in rows) {
            val decision = PlaceOrder.decide(state, Place(items), ctx)
            val actual = when {
                decision is Reject -> "rejected".also { assertEquals(state, decision.reason) }
                decision is Accept && decision.outputs.isEmpty() -> "joined".also { assertEquals(state, decision.state) }
                decision is Accept -> {
                    val next = assertIs<Placing>(decision.state)
                    assertEquals(listOf(Call(next.call.key, Orders, items, 60_000, intent = next.intent)), decision.outputs)
                    assertEquals(ReplyPlan.Now(decision.state), decision.reply)
                    "call, " + (if (next.intent == intent) "same intent" else "new intent") + if (next.repeat) ", repeat" else ""
                }
                else -> fail("unexpected decision $decision")
            }
            assertEquals(expected, actual, "$state, $items")
        }
    }

    @Test
    fun `complete shows NotPlaced only when no earlier call of the order may have placed it`() {
        val order = Order("o-1")
        val refused = Outcome.NotDone(NotDoneReason.NotApplied("connection refused"))
        // whether an earlier call of the order ended Unknown, the outcome -> the next stage
        val rows: List<Triple<Boolean, Outcome<Order>, PlaceOrderState>> = listOf(
            Triple(false, Outcome.Done(order), Placed("o-1")),
            Triple(false, refused, NotPlaced("NotApplied(evidence=connection refused)")),
            Triple(false, Outcome.Unknown(UnknownReason.RetriesExhausted), Checking(cart, intent)),
            Triple(true, Outcome.Done(order), Placed("o-1")),
            Triple(true, refused, Checking(cart, intent)),
            Triple(true, Outcome.Unknown(UnknownReason.LostAfterSend), Checking(cart, intent)),
        )
        for ((repeat, outcome, expected) in rows) {
            val step = PlaceOrder.complete(Placing(cart, intent, call, repeat), Completion(call.key, Orders, outcome), ctx)
            assertEquals(expected, step.state, "repeat=$repeat, $outcome")
            assertEquals(emptyList(), step.outputs)
        }
        val placing = Placing(cart, intent, call, repeat = false)
        assertEquals(placing, PlaceOrder.complete(placing, Completion(CallKey("1.0"), Orders, Outcome.Done(order)), ctx).state)
    }

    // ---------------------------------------------------------------- under faults and crashes

    /**
     * Every fate of up to three attempts and of the status read, one crash point,
     * and a "Try again" whenever the order ends in [Checking]: the server places
     * at most one order, and [NotPlaced] is shown only when it placed none.
     */
    @Test
    fun `under every fault and crash point no order is placed twice, and no Unknown is shown as NotPlaced`() {
        val finals = sortedMapOf<String, Int>()
        val runs = explore(ExhaustiveChooser(maxChoices = 6)) { chooser ->
            val h = Harness(chooser, Profile.Durable) {
                val orders = SimPort(Orders, scheduler, chooser, truth, FATES, horizonMillis = ORDERS_HORIZON_MILLIS) { Order("o-1") }
                Composition().placeOrder(orders, orders.status { it })
            }
            h.maybeCrashOnce(window = 16)
            h.start()
            h.send(id, Place(cart))
            h.runUntilIdle()
            if (h.runtime.inspect(id) is Checking) { // the user taps "Try again"
                h.send(id, Place(cart))
                h.runUntilIdle()
            }
            h.assertInvariants()
            val placed = h.truth.applied.values.sum()
            val state = h.runtime.inspect(id)
            when (state) {
                is Placed -> assertEquals(1, placed, "choices=${chooser.history}")
                is NotPlaced -> assertEquals(0, placed, "choices=${chooser.history}")
                is Checking, Idle -> assertTrue(placed <= 1, "choices=${chooser.history}")
                else -> fail("unfinished: $state; choices=${chooser.history}")
            }
            finals.merge(state::class.simpleName!!, 1, Int::plus)
        }
        println("place order (Idempotent, Durable, 3 attempts, reconciled, crash window 16, one try again): $runs runs, stages $finals")
        assertTrue(finals.keys.containsAll(setOf("Placed", "NotPlaced", "Checking")), "$finals")
    }

    // ---------------------------------------------------------------- the deduplication window

    /** Every attempt's answer is lost after the order was placed, and the status read is lost too: the order ends in [Checking]. */
    private fun checking(keys: MutableList<String>): Harness = Harness(Script(LOSE_REPLY, LOSE_REPLY, LOSE_REPLY, STATUS_LOST)) {
        val orders = SimPort(Orders, scheduler, chooser, truth, FATES, horizonMillis = ORDERS_HORIZON_MILLIS) { Order("o-1") }
        val recording = Adapter<Cart, Order> { inv, done -> keys += inv.idempotencyKey; orders.execute(inv, done) }
        Composition().placeOrder(recording, orders.status { it })
    }.start().apply {
        send(id, Place(cart))
        runUntilIdle()
        assertIs<Checking>(runtime.inspect(id))
        assertEquals(1, truth.applied.values.sum())
    }

    @Test
    fun `trying again after Unknown repeats the order with the same key, which the server does not place twice`() {
        val keys = ArrayList<String>()
        val h = checking(keys)
        h.send(id, Place(cart)) // the next attempt is answered
        h.runUntilIdle()
        h.assertInvariants()
        assertEquals(Placed("o-1"), h.runtime.inspect(id))
        assertEquals(1, h.truth.applied.values.sum())
        assertEquals(4, keys.size)
        assertEquals(1, keys.toSet().size, "the attempts carried different keys: $keys")
    }

    @Test
    fun `a week later the deduplication window refuses to try again, and nothing is sent`() {
        val keys = ArrayList<String>()
        val h = checking(keys)
        h.scheduler.advance(7 * 24 * 60 * 60 * 1_000L)
        h.send(id, Place(cart))
        h.runUntilIdle()
        val refused = assertIs<Outcome.NotDone<*>>(h.externalOutcomes.last())
        assertTrue("deduplication window" in (refused.reason as NotDoneReason.Fault).message, "$refused")
        assertIs<Checking>(h.runtime.inspect(id))
        assertEquals(3, keys.size)
        assertEquals(3, h.tracer.events.count { it is TraceEvent.CallSent })
    }

    /** Answers the choices in [picks], in order, then 0. */
    private class Script(vararg picks: Int) : Chooser {
        private val left = ArrayDeque(picks.toList())
        private val made = ArrayList<String>()

        override fun choose(n: Int, label: String): Int = (left.removeFirstOrNull() ?: 0).also {
            require(it < n) { "choice $it at $label has only $n options" }
            made += "$label=$it"
        }

        override val history: List<String> get() = made.toList()
    }

    private companion object {
        /** An answer, a request lost, an answer lost after the order was placed, and a connection refused before anything was sent. */
        val FATES = listOf(Fate.Reply, Fate.LoseRequest, Fate.LoseReply, Fate.RefuseConnection)
        const val LOSE_REPLY = 2
        const val STATUS_LOST = 1
    }
}
