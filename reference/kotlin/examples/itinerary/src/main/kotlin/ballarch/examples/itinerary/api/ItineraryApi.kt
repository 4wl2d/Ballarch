package ballarch.examples.itinerary.api

import ballarch.kernel.BallPort
import ballarch.kernel.Knowledge

/** Public contract of the itinerary, for a caller one level further up. */
sealed interface ItineraryRequest {
    /**
     * Book both trips. Answered once both trips have answered, with the
     * itinerary's final knowledge. Once 16 Book requests wait for the same
     * itinerary, a further one is answered at once with an unsettled status.
     */
    data object Book : ItineraryRequest

    /** The itinerary's status now. */
    data object GetStatus : ItineraryRequest
}

sealed interface ItineraryReply {
    /**
     * What the itinerary knows about its four bookings, derived only from its
     * trips' replies (rule R14). Only a status with [settled] set is the
     * itinerary's outcome; before that, a trip still to answer counts as `Unknown`.
     */
    data class Status(val settled: Boolean, val knowledge: Knowledge) : ItineraryReply
}

/** The itinerary's port. */
val ItineraryPort = BallPort<ItineraryRequest, ItineraryReply>("itinerary")
