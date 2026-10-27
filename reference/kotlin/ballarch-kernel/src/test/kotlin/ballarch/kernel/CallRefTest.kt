package ballarch.kernel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Typed completions: a call is issued with the [CallRef] that reads its completion without a cast. */
class CallRefTest {
    private val self = BallId("payer", "p1")
    private val charge = AdapterPort<Int, String>("charge", EffectClass.NonIdempotent)
    private val lookup = AdapterPort<String, Int>("lookup", EffectClass.Safe)

    @Test
    fun `a call is issued with a ref that names its key and port`() {
        val ctx = FixedContext(self)
        val (ref, call) = ctx.call(charge, 10, timeoutMillis = 500)
        assertEquals(Call(CallKey("1.0"), charge, 10, 500), call)
        assertEquals(CallRef<String>(CallKey("1.0"), "charge"), ref)
        val (timer, wait) = Timer.after(ctx, 100)
        assertEquals(Timer.after(CallKey("1.1"), 100), wait)
        assertEquals(CallRef<Unit>(CallKey("1.1"), Timer.port.name), timer)
        val intent = ctx.newIntent()
        val idem = AdapterPort<Int, String>("idem", EffectClass.Idempotent)
        assertEquals(Call(CallKey("1.3"), idem, 5, 500, intent = intent), ctx.call(idem, 5, 500, intent = intent).call)
    }

    @Test
    fun `outcomeFor reads only the completion of the call its ref names`() {
        val (ref, call) = FixedContext(self).call(charge, 10, timeoutMillis = 500)
        val done = Completion(call.key, charge, Outcome.Done("ok"))
        val text: String? = done.outcomeFor(ref)?.valueOrNull()
        assertEquals("ok", text)
        assertNull(Completion(CallKey("1.1"), charge, Outcome.Done("other")).outcomeFor(ref))
        assertNull(Completion(call.key, lookup, Outcome.Done(1)).outcomeFor(ref))
    }

    @Test
    fun `safeOutcomeFor needs a Safe port and admits no Unknown`() {
        val ctx = FixedContext(self)
        val (ref, call) = ctx.call(lookup, "sku", timeoutMillis = 500)
        val safe: SafeOutcome<Int>? = Completion(call.key, lookup, Outcome.Done(3)).safeOutcomeFor(ref)
        val stock = when (safe) {
            is Outcome.Done -> safe.value
            is Outcome.NotDone -> -1
            null -> -2
        }
        assertEquals(3, stock)
        assertEquals(Outcome.NotDone(NotDoneReason.NoAnswer), Completion(call.key, lookup, Outcome.NotDone(NotDoneReason.NoAnswer)).safeOutcomeFor(ref))
        assertNull(Completion(CallKey("9.9"), lookup, Outcome.Done(1)).safeOutcomeFor(ref))
        assertFailsWith<IllegalStateException> {
            Completion(call.key, lookup, Outcome.Unknown(UnknownReason.LostAfterSend)).safeOutcomeFor(ref)
        }
        val (chargeRef, chargeCall) = ctx.call(charge, 10, timeoutMillis = 500)
        assertFailsWith<IllegalArgumentException> { Completion(chargeCall.key, charge, Outcome.Done("ok")).safeOutcomeFor(chargeRef) }
    }

    @Test
    fun `a ref kept in durable state survives Java serialization`() {
        val ref = CallRef<String>(CallKey("4.2"), "charge")
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(ref) } }.toByteArray()
        assertEquals(ref, ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() })
    }
}
