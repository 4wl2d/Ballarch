package ballarch.examples.trip.api

import ballarch.kernel.BallPort
import ballarch.kernel.Knowledge

/**
 * Public contract of the trip: what a caller one level up may use. One trip
 * instance exists per trip; its instance key is also the reference the
 * providers know.
 */
sealed interface TripRequest {
    /**
     * Book the trip. Answered once the trip is settled, with its final
     * knowledge. A repeated Book joins the booking in progress; once 16 Book
     * requests wait for the same trip, a further one is answered at once with an
     * unsettled status, which a caller counts as `Unknown` ([settledKnowledge]).
     */
    data object Book : TripRequest

    /** The trip's status now. */
    data object GetStatus : TripRequest
}

sealed interface TripReply {
    /**
     * What the trip knows about its bookings, derived only from call outcomes
     * (rule R14). [knowledge] is a claim about the final effect: a booking or a
     * compensation that is open, or that the trip may still make, counts as
     * `Maybe`. Only a status whose [stage] is [Stage.Settled] is the trip's
     * outcome; a caller one level up counts an earlier one as `Unknown`.
     */
    data class Status(val stage: Stage, val knowledge: Knowledge) : TripReply
}

/** The trip's port, for a caller one level up. */
val TripPort = BallPort<TripRequest, TripReply>("trip")

/** The trip's stages. A trip only moves forward through them, in this order. */
enum class Stage { New, BookingFlight, BookingHotel, CancellingHotel, VoidingFlight, Settled }

/**
 * The trip's final knowledge if [reply] describes a settled trip; `null` for an
 * interim status. A caller lifts a reply with
 * `reportedKnowledge(onlyRequest, ::settledKnowledge)`.
 */
fun settledKnowledge(reply: TripReply): Knowledge? = when (reply) {
    is TripReply.Status -> reply.knowledge.takeIf { reply.stage == Stage.Settled }
}
