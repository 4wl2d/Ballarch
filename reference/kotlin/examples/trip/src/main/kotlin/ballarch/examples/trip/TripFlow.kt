package ballarch.examples.trip

import ballarch.examples.trip.api.Stage
import ballarch.examples.trip.api.TripPort
import ballarch.examples.trip.api.TripReply
import ballarch.examples.trip.api.TripRequest
import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.Call
import ballarch.kernel.CallKey
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.Knowledge
import ballarch.kernel.Outcome
import ballarch.kernel.Reply
import ballarch.kernel.RequestContext
import ballarch.kernel.RequestId
import ballarch.kernel.Step
import ballarch.kernel.Tri
import ballarch.kernel.accept
import ballarch.kernel.acceptLater
import ballarch.kernel.effect
import ballarch.kernel.outcomeOf
import ballarch.kernel.step

// ---- external ports

/** Every request is about one trip; the trip id is the reference the providers know. */
data class TripRef(val trip: String)

sealed interface FlightReply {
    data class Booked(val seat: String) : FlightReply
    data object SoldOut : FlightReply
}

/** The airline has no idempotency keys: every delivered booking request books a seat. */
val FlightBook = AdapterPort<TripRef, FlightReply>("airline.book", EffectClass.NonIdempotent)

/**
 * Voids every booking for the trip and refuses later booking requests for it.
 * Repeating it changes nothing. Its `Done` means that no seat is booked for the
 * trip and none can be booked later: an absorbing compensation (Core §4.2).
 */
val FlightVoid = AdapterPort<TripRef, Unit>("airline.void", EffectClass.Idempotent)

sealed interface HotelReply {
    data object Booked : HotelReply
    data object Full : HotelReply
}

/** The hotel deduplicates requests by call identity, so the runtime may repeat them. */
val HotelBook = AdapterPort<TripRef, HotelReply>("hotel.book", EffectClass.Idempotent)

/** Cancels the trip's room and refuses later bookings for it: absorbing, like [FlightVoid]. */
val HotelCancel = AdapterPort<TripRef, Unit>("hotel.cancel", EffectClass.Idempotent)

// ---- state

/**
 * What is known about one booking: whether the request took effect, and
 * whether one of its compensations took effect. Compensations are absorbing,
 * so the booking's net effect is `effect ∧ ¬undone`. Several compensation
 * attempts combine with `or`: if any of them took effect, the booking is undone.
 */
data class Part(val effect: Tri = Tri.No, val undone: Tri = Tri.No) {
    /** Net effect according to the completions received so far. */
    val net: Tri get() = effect and !undone

    /**
     * Net effect as the trip may claim it: a booking that is open or that the
     * trip may still make ([bookingPending]) may take effect, and a compensation
     * that is open or may still be made ([undoPending]) may undo it.
     */
    fun view(bookingPending: Boolean, undoPending: Boolean): Tri =
        (if (bookingPending) Tri.Maybe else effect) and !(if (undoPending) undone or Tri.Maybe else undone)
}

data class TripState(
    val stage: Stage = Stage.New,
    val flight: Part = Part(),
    val hotel: Part = Part(),
    /** The call whose completion the current stage waits for. */
    val call: CallKey? = null,
    /** Compensation calls made in the current stage. */
    val attempts: Int = 0,
    /** Book requests to answer once the trip is settled. */
    val awaiting: List<RequestId> = emptyList(),
) {
    /**
     * The trip's two bookings, combined by the knowledge algebra (rule R14).
     * A call counts as `Maybe` from the moment the trip may still make it until
     * its completion arrives, and a booking the trip may still compensate
     * counts as possibly undone. Stages only move forward, so the flight can be
     * requested only up to [Stage.BookingFlight] and voided up to
     * [Stage.VoidingFlight], and the room can be requested up to
     * [Stage.BookingHotel] and cancelled up to [Stage.CancellingHotel]. Before
     * Book arrives every call may still be made, so a new trip claims nothing.
     * Once settled, this is the knowledge of the completions alone.
     */
    val knowledge: Knowledge
        get() = Knowledge.single(flight.view(bookingPending = stage <= Stage.BookingFlight, undoPending = stage <= Stage.VoidingFlight)) +
            Knowledge.single(hotel.view(bookingPending = stage <= Stage.BookingHotel, undoPending = stage <= Stage.CancellingHotel))
}

/**
 * A trip is a flight plus a hotel room, or nothing. This Flow books both and
 * compensates when it cannot have both. It never claims more than the outcomes
 * of its calls establish: its status is the knowledge algebra applied to them,
 * counting every call it may still make as `Maybe`, so every status admits the
 * final bookings. It answers Book once the trip is settled, with its final
 * knowledge, so a caller one level up can enter that answer as one summand of
 * its own knowledge and treat the whole trip as one honest operation. That
 * caller's timeout must cover [worstCaseMillis]; a call that times out first
 * ends `Unknown`, which is still honest. A repeated Book joins the one in
 * progress and gets the same answer, up to [MAX_AWAITING] waiting requests;
 * beyond that, a Book is answered at once with an unsettled status, which the
 * caller counts as `Unknown`.
 *
 * The flight request is never repeated (its port is NonIdempotent). An
 * `Unknown` booking is voided; a void that completes `Done` turns it into a
 * definite `NotDone`, while a void whose answer is lost leaves it `Unknown`.
 * After [maxCompensations] attempts per booking the trip stops compensating and
 * settles with what it knows.
 *
 * Requires the Durable profile: the knowledge is derived from the trip's call
 * ledger, and the providers' bookings outlive a Transient crash that erases it
 * (Core R14). Under Transient, a restarted trip is back in [Stage.New] and
 * claims nothing (`Unknown`), but it never settles; a Book sent after the
 * restart starts a fresh trip that knows nothing of the earlier bookings, so its
 * settled claims can be false and the flight can be booked twice.
 */
class TripFlow(
    private val timeoutMillis: Long = 1_000,
    private val maxCompensations: Int = 2,
) : Ball<TripState, TripRequest, TripReply> {
    override val type = TripPort.ballType

    /** The longest a trip can take: two bookings and every compensation attempt, each until its deadline. */
    val worstCaseMillis: Long get() = (2L + 2L * maxCompensations) * timeoutMillis

    override fun initial(key: String) = TripState()

    override fun decide(state: TripState, request: TripRequest, ctx: RequestContext): Decision<TripState, TripReply> =
        when (request) {
            TripRequest.Book -> when {
                state.stage == Stage.New -> {
                    val next = call(state.copy(stage = Stage.BookingFlight, awaiting = listOf(ctx.requestId)), FlightBook, ctx)
                    acceptLater(next.state, *next.outputs.toTypedArray())
                }
                state.stage == Stage.Settled -> accept(state, status(state))
                // A full waiting list gets the status now; its caller counts it as Unknown (it is not settled).
                state.awaiting.size >= MAX_AWAITING -> accept(state, status(state))
                else -> acceptLater(state.copy(awaiting = state.awaiting + ctx.requestId))
            }
            TripRequest.GetStatus -> accept(state, status(state))
        }

    override fun complete(state: TripState, completion: Completion<*>, ctx: Context): Step<TripState, TripReply> {
        if (completion.key != state.call) return step(state) // stale
        return when (state.stage) {
            Stage.BookingFlight -> {
                val booked = completion.outcomeOf(FlightBook)!!.effect { it is FlightReply.Booked }
                val next = state.copy(flight = Part(effect = booked))
                when (booked) {
                    Tri.Yes -> call(next.copy(stage = Stage.BookingHotel), HotelBook, ctx)
                    Tri.No -> settle(next)
                    Tri.Maybe -> compensate(next, Stage.VoidingFlight, ctx)
                }
            }
            Stage.BookingHotel -> {
                val booked = completion.outcomeOf(HotelBook)!!.effect { it == HotelReply.Booked }
                val next = state.copy(hotel = Part(effect = booked))
                when (booked) {
                    Tri.Yes -> settle(next)
                    Tri.No -> compensate(next, Stage.VoidingFlight, ctx)
                    Tri.Maybe -> compensate(next, Stage.CancellingHotel, ctx)
                }
            }
            Stage.CancellingHotel -> {
                val next = state.copy(hotel = undo(state.hotel, completion.outcomeOf(HotelCancel)!!))
                when {
                    next.hotel.undone == Tri.Yes || state.attempts >= maxCompensations -> compensate(next, Stage.VoidingFlight, ctx)
                    else -> compensate(next, Stage.CancellingHotel, ctx)
                }
            }
            Stage.VoidingFlight -> {
                val next = state.copy(flight = undo(state.flight, completion.outcomeOf(FlightVoid)!!))
                when {
                    next.flight.undone == Tri.Yes || state.attempts >= maxCompensations -> settle(next)
                    else -> compensate(next, Stage.VoidingFlight, ctx)
                }
            }
            Stage.New, Stage.Settled -> step(state)
        }
    }

    private fun undo(part: Part, outcome: Outcome<Unit>): Part = part.copy(undone = part.undone or outcome.effect())

    /** Starts (or repeats) a compensation stage; a booking with no possible effect needs none. */
    private fun compensate(state: TripState, stage: Stage, ctx: Context): Step<TripState, TripReply> {
        val part = if (stage == Stage.VoidingFlight) state.flight else state.hotel
        if (part.net == Tri.No) return if (stage == Stage.CancellingHotel) compensate(state, Stage.VoidingFlight, ctx) else settle(state)
        val attempts = if (stage == state.stage) state.attempts + 1 else 1
        val port = if (stage == Stage.VoidingFlight) FlightVoid else HotelCancel
        return call(state.copy(stage = stage, attempts = attempts), port, ctx)
    }

    private fun call(state: TripState, port: AdapterPort<TripRef, *>, ctx: Context): Step<TripState, TripReply> {
        val key = ctx.newKey()
        return step(state.copy(call = key), Call(key, port, TripRef(ctx.self.key), timeoutMillis))
    }

    /** Ends the trip: nothing can change any more, so its knowledge is final and every waiting Book gets it. */
    private fun settle(state: TripState): Step<TripState, TripReply> {
        val settled = state.copy(stage = Stage.Settled, call = null, attempts = 0, awaiting = emptyList())
        val answer = status(settled)
        return Step(settled, state.awaiting.map { Reply(it, answer) })
    }

    private fun status(state: TripState) = TripReply.Status(state.stage, state.knowledge)

    companion object {
        /** Book requests that may wait for one trip's settlement. */
        const val MAX_AWAITING = 16
    }
}
