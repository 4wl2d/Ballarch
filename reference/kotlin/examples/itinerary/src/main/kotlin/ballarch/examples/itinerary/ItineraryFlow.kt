package ballarch.examples.itinerary

import ballarch.examples.itinerary.api.ItineraryPort
import ballarch.examples.itinerary.api.ItineraryReply
import ballarch.examples.itinerary.api.ItineraryRequest
import ballarch.examples.trip.api.TripPort
import ballarch.examples.trip.api.TripReply
import ballarch.examples.trip.api.TripRequest
import ballarch.examples.trip.api.settledKnowledge
import ballarch.kernel.Ball
import ballarch.kernel.Call
import ballarch.kernel.CallKey
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.Knowledge
import ballarch.kernel.Outcome
import ballarch.kernel.Reply
import ballarch.kernel.RequestContext
import ballarch.kernel.RequestId
import ballarch.kernel.Step
import ballarch.kernel.accept
import ballarch.kernel.acceptLater
import ballarch.kernel.outcomeOf
import ballarch.kernel.reportedKnowledge
import ballarch.kernel.step

// ---- state

/** One trip of the itinerary: the call to the trip's port, and its completion once it arrived. */
data class Leg(val call: CallKey? = null, val outcome: Outcome<TripReply>? = null) {
    /**
     * What this trip contributes to the itinerary's knowledge. A settled reply
     * enters as the trip's own knowledge; an interim status, an `Unknown`
     * outcome, and a call that is open or still to be made count as `Unknown`.
     * The itinerary sends each of its trips one Book, under a key no other
     * caller uses, so a `NotDone` means the trip made no call.
     */
    val knowledge: Knowledge get() = outcome?.reportedKnowledge(onlyRequest = true, settled = ::settledKnowledge) ?: Knowledge.Unknown
}

data class ItineraryState(
    val started: Boolean = false,
    val out: Leg = Leg(),
    val back: Leg = Leg(),
    /** Book requests to answer once both trips have answered. */
    val awaiting: List<RequestId> = emptyList(),
) {
    /** Both trips have answered: nothing the itinerary knows can change any more. */
    val settled: Boolean get() = out.outcome != null && back.outcome != null

    /** The itinerary's knowledge: one summand per trip, each a composite itself (Core §4.2). */
    val knowledge: Knowledge get() = out.knowledge + back.knowledge
}

/**
 * An itinerary is two trips, out and back, each booked through the trip's
 * port: a composite of composites. It derives its knowledge from its trips'
 * replies alone, entering each settled reply as one summand with `plus`, so its
 * knowledge equals that of the four bookings taken together. It does not
 * compensate a trip; its knowledge says what may be held. Like a trip, it
 * answers Book once it is settled; beyond [MAX_AWAITING] waiting Book requests
 * it answers at once with an unsettled status.
 *
 * @param tripTimeoutMillis timeout of each call to a trip. It must cover the
 *   trip's worst case (`TripFlow.worstCaseMillis`); a call that times out first
 *   ends `Unknown`, which is honest but uninformative.
 */
class ItineraryFlow(private val tripTimeoutMillis: Long) : Ball<ItineraryState, ItineraryRequest, ItineraryReply> {
    override val type = ItineraryPort.ballType

    /** The longest an itinerary can take: its two trips run side by side. */
    val worstCaseMillis: Long get() = tripTimeoutMillis

    override fun initial(key: String) = ItineraryState()

    override fun decide(state: ItineraryState, request: ItineraryRequest, ctx: RequestContext): Decision<ItineraryState, ItineraryReply> =
        when (request) {
            ItineraryRequest.Book -> when {
                !state.started -> {
                    val out = ctx.newKey()
                    val back = ctx.newKey()
                    acceptLater(
                        ItineraryState(started = true, out = Leg(out), back = Leg(back), awaiting = listOf(ctx.requestId)),
                        Call(out, TripPort, TripRequest.Book, tripTimeoutMillis, target = tripKey(ctx.self.key, "out")),
                        Call(back, TripPort, TripRequest.Book, tripTimeoutMillis, target = tripKey(ctx.self.key, "back")),
                    )
                }
                state.settled -> accept(state, status(state))
                // A full waiting list gets the status now; its caller counts it as Unknown (it is not settled).
                state.awaiting.size >= MAX_AWAITING -> accept(state, status(state))
                else -> acceptLater(state.copy(awaiting = state.awaiting + ctx.requestId))
            }
            ItineraryRequest.GetStatus -> accept(state, status(state))
        }

    override fun complete(state: ItineraryState, completion: Completion<*>, ctx: Context): Step<ItineraryState, ItineraryReply> {
        val outcome = completion.outcomeOf(TripPort) ?: return step(state)
        val next = when (completion.key) {
            state.out.call -> state.copy(out = state.out.copy(outcome = outcome))
            state.back.call -> state.copy(back = state.back.copy(outcome = outcome))
            else -> return step(state) // stale
        }
        if (!next.settled) return step(next)
        val answer = status(next)
        return Step(next.copy(awaiting = emptyList()), next.awaiting.map { Reply(it, answer) })
    }

    private fun status(state: ItineraryState) = ItineraryReply.Status(state.settled, state.knowledge)

    companion object {
        /** The key of one of an itinerary's trips: also the reference its providers know. */
        fun tripKey(itinerary: String, leg: String): String = "$itinerary-$leg"

        /** Book requests that may wait for one itinerary's settlement. */
        const val MAX_AWAITING = 16
    }
}
