package ballarch.examples.tutorial

import ballarch.examples.tutorial.app.reminderComposition
import ballarch.kernel.Accept
import ballarch.kernel.BallId
import ballarch.kernel.FixedContext
import ballarch.kernel.Outcome
import ballarch.kernel.Reject
import ballarch.kernel.Timer
import ballarch.testkit.ExhaustiveChooser
import ballarch.testkit.Harness
import ballarch.testkit.Profile
import ballarch.testkit.SimPort
import ballarch.testkit.explore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReminderTest {
    private val id = BallId("reminder", "r1")

    @Test
    fun `scheduling accepts once and asks for a timer`() {
        val ctx = FixedContext(id)
        val decision = ReminderBall.decide(ReminderState.Empty, ReminderRequest.Schedule("ann@example.com", "Call Bob", 60_000), ctx)
        assertIs<Accept<ReminderState, ReminderReply>>(decision)
        assertEquals(listOf(Timer.after(ballarch.kernel.CallKey("1.0"), 60_000)), decision.outputs)
        val again = ReminderBall.decide(decision.state, ReminderRequest.Schedule("ann@example.com", "Call Bob", 60_000), ctx)
        assertEquals(Reject(ReminderReply.AlreadyScheduled), again)
    }

    @Test
    fun `under every network fate and crash point the email is sent at most once and the state tells the truth`() {
        val runs = explore(ExhaustiveChooser()) { chooser ->
            val h = Harness(chooser, Profile.Durable) {
                reminderComposition(SimPort(Mailer, scheduler, chooser, truth) { })
            }
            h.maybeCrashOnce(window = 12)
            h.start()
            h.send(id, ReminderRequest.Schedule("ann@example.com", "Call Bob", delayMillis = 100))
            h.runUntilIdle()
            h.assertInvariants() // the kernel's rules, checked on the trace
            val sent = h.truth.applied.values.sum()
            assertTrue(sent <= 1, "email sent $sent times")
            when (h.runtime.inspect(id)) {
                ReminderState.Sent -> assertEquals(1, sent)
                is ReminderState.NotSent -> assertEquals(0, sent)
                ReminderState.MaybeSent, ReminderState.Empty -> Unit // Empty: the crash came before the request was accepted
                else -> throw AssertionError("unfinished: ${h.runtime.inspect(id)}")
            }
        }
        assertTrue(runs > 10)
    }
}
