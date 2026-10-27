package ballarch.examples.itinerary

import ballarch.examples.itinerary.api.ItineraryPort
import ballarch.examples.itinerary.api.ItineraryReply
import ballarch.examples.itinerary.api.ItineraryRequest
import ballarch.examples.itinerary.app.itineraryComposition
import ballarch.examples.trip.FlightBook
import ballarch.examples.trip.FlightReply
import ballarch.examples.trip.FlightVoid
import ballarch.examples.trip.HotelBook
import ballarch.examples.trip.HotelCancel
import ballarch.examples.trip.HotelReply
import ballarch.examples.trip.TripFlow
import ballarch.examples.trip.TripRef
import ballarch.examples.trip.TripState
import ballarch.examples.trip.api.Stage
import ballarch.examples.trip.api.TripPort
import ballarch.examples.trip.api.TripReply
import ballarch.examples.trip.api.settledKnowledge
import ballarch.kernel.BallId
import ballarch.kernel.Knowledge
import ballarch.kernel.Outcome
import ballarch.kernel.knowledge
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
import kotlin.test.assertTrue
import kotlin.test.fail

/** A provider that fences a trip's reference once it is voided: later bookings for it have no effect. */
private class Provider {
    private val booked = HashMap<String, Int>()
    private val fenced = HashSet<String>()

    /** Booking requests that reached the provider after the trip's reference was voided. */
    var afterUndo = 0
        private set

    /** Books unless the reference is fenced or [available] says there is no seat or room (a business refusal). */
    fun book(ref: TripRef, available: () -> Boolean): Boolean {
        if (ref.trip in fenced) {
            afterUndo++
            return false
        }
        if (!available()) return false
        booked.merge(ref.trip, 1, Int::plus)
        return true
    }

    fun undo(ref: TripRef) {
        booked.remove(ref.trip)
        fenced += ref.trip
    }

    fun holds(trip: String) = (booked[trip] ?: 0) > 0
}

/** What one exploration found. Counts are of runs unless named otherwise. */
private class Findings {
    /** Distinct runs: each combination of choices, and each crash point the run reaches. */
    var runs = 0
    var crashed = 0

    /** Choice sequences skipped because their crash point lies after the end of the run (it repeats the run without a crash). */
    var repeats = 0

    /** Runs in which the itinerary accepted Book (in the others the crash came first) and settled. */
    var accepted = 0

    /** Settled runs in which the Book reply arrived through the port (in the others, the crash took the request). */
    var replied = 0
    val settled = LinkedHashMap<Knowledge, Int>()

    /** Settled knowledge that does not admit the four real bookings. */
    var falseClaims = 0

    /** Runs in which some status the itinerary held during the run did not admit the final bookings. */
    var falseStatuses = 0

    /** How the itinerary's calls to its trips ended (two legs per settled run), and legs whose lifted knowledge does not admit that trip's bookings. */
    val legs = LinkedHashMap<String, Int>()
    var falseLegs = 0

    /** Control: settled runs in which lifting each trip's reply as a Boolean ("the trip is done") contradicts the bookings. */
    var booleanLiftWrong = 0

    /** Booking requests that reached a provider after the trip's void or cancel (requests, not runs). */
    var afterUndo = 0

    /** Runs in which a SoldOut or Full answer reached a trip. */
    var businessRefusals = 0

    /** Scheduler tasks of the longest run without a crash, and the most choices any run made. */
    var longestRun = 0
    var mostChoices = 0

    override fun toString(): String =
        "$runs runs ($crashed with a crash, ${runs - crashed} without; $repeats repeated choice sequences skipped), " +
            "$accepted accepted and settled, $replied answered through the port; settled knowledge $settled; " +
            "false settled claims $falseClaims, runs with a false status $falseStatuses; " +
            "calls to a trip by outcome $legs, legs whose knowledge a trip's bookings contradict $falseLegs; " +
            "control: Boolean lift of the trips' replies wrong in $booleanLiftWrong; " +
            "bookings after a void or cancel $afterUndo; runs with a SoldOut/Full answer $businessRefusals; " +
            "longest run without a crash $longestRun tasks; most choices in a run $mostChoices"
}

/**
 * Two levels of composition: an itinerary books two trips through the trip's
 * port, and each trip books a flight and a room. The itinerary derives its
 * knowledge only from its trips' replies. Explored under faults at the
 * providers, with one crash of the runtime that hosts all three Balls, with
 * every fate the trip's own exploration uses, and with calls to a trip that
 * time out before the trip settles.
 */
class ItineraryFlowTest {
    private val id = BallId(ItineraryPort.ballType, "i1")
    private val trips = listOf("out", "back").map { ItineraryFlow.tripKey(id.key, it) }
    private val trip = TripFlow()

    // The trip's own exploration: six fates per booking attempt, three per compensation attempt.
    private val tripForward = listOf(Fate.Reply, Fate.LoseRequest, Fate.LoseReply, Fate.ErrorAfterApply, Fate.RefuseConnection, Fate.DeliverLate)
    private val tripUndo = listOf(Fate.Reply, Fate.LoseReply, Fate.RefuseConnection)

    // Fewer fates, so that two trips and a crash before every task stay small enough to explore exhaustively.
    // A booking is answered, applied with its answer lost, refused before it was written, or delivered after
    // its deadline (and so after a compensation). A compensation is answered, or lost before it was applied.
    private val forward = listOf(Fate.Reply, Fate.LoseReply, Fate.RefuseConnection, Fate.DeliverLate)
    private val undo = listOf(Fate.Reply, Fate.LoseRequest)

    private fun harness(
        chooser: Chooser,
        airline: Provider,
        hotel: Provider,
        itinerary: ItineraryFlow,
        forward: List<Fate>,
        undo: List<Fate>,
        choose: Boolean,
    ) = Harness(chooser, Profile.Durable) {
        itineraryComposition(
            flightBook = SimPort(FlightBook, scheduler, chooser, truth, forward) {
                if (airline.book(it) { !choose || chooser.choose(2, "airline:seat") == 0 }) FlightReply.Booked("12A") else FlightReply.SoldOut
            },
            flightVoid = SimPort(FlightVoid, scheduler, chooser, truth, undo) { airline.undo(it) },
            hotelBook = SimPort(HotelBook, scheduler, chooser, truth, forward) {
                if (hotel.book(it) { !choose || chooser.choose(2, "hotel:room") == 0 }) HotelReply.Booked else HotelReply.Full
            },
            hotelCancel = SimPort(HotelCancel, scheduler, chooser, truth, undo) { hotel.undo(it) },
            trip = trip,
            itinerary = itinerary,
        )
    }

    /**
     * Explores every combination of [forward] fates per booking attempt, [undo]
     * fates per compensation attempt, seat and room availability if [choose],
     * and one crash before any of the first [window] scheduler tasks.
     */
    private fun exploreItinerary(
        forward: List<Fate>,
        undo: List<Fate>,
        window: Int,
        tripTimeoutMillis: Long = trip.worstCaseMillis + 1_000,
        choose: Boolean = false,
    ): Findings {
        val f = Findings()
        val itinerary = ItineraryFlow(tripTimeoutMillis)
        // A call that times out before the trip settles leaves the trip's reply overdue (R9); that is the case explored.
        val coversTrip = tripTimeoutMillis > trip.worstCaseMillis
        explore(ExhaustiveChooser(maxChoices = MAX_CHOICES), maxRuns = MAX_RUNS) { chooser ->
            val airline = Provider()
            val hotel = Provider()
            val h = harness(chooser, airline, hotel, itinerary, forward, undo, choose)
            h.maybeCrashOnce(window)
            // Every status the itinerary held: what GetStatus would have answered at that moment.
            val statuses = LinkedHashSet<Knowledge>()
            fun probe() {
                statuses += (h.runtime.inspect(id) as ItineraryState).knowledge
            }
            val crash = h.scheduler.beforeTask
            h.scheduler.beforeTask = {
                probe()
                crash?.invoke()
            }
            h.start()
            h.send(id, ItineraryRequest.Book, timeoutMillis = itinerary.worstCaseMillis + 1_000)
            h.runUntilIdle()
            if (h.crashes == 0 && chooser.history.first() != "crashBeforeTask=0") {
                f.repeats++ // the crash point lies after the run's end: the same run as without a crash
                return@explore
            }
            f.runs++
            probe()
            h.assertInvariants()
            if (coversTrip) h.assertNoReplyLeaks()
            assertTrue(chooser.history.size < MAX_CHOICES, "the choice bound cut the run short: ${chooser.history}")
            if (h.crashes > 0) f.crashed++ else f.longestRun = maxOf(f.longestRun, h.scheduler.executed.toInt())
            f.mostChoices = maxOf(f.mostChoices, chooser.history.size)
            f.afterUndo += airline.afterUndo + hotel.afterUndo
            val delivered = h.tracer.events.filterIsInstance<TraceEvent.Committed>().map { (it.completedOutcome as? Outcome.Done<*>)?.value }
            if (FlightReply.SoldOut in delivered || HotelReply.Full in delivered) f.businessRefusals++

            // Ground truth: the four bookings at the providers.
            val held = trips.flatMap { listOf(airline.holds(it), hotel.holds(it)) }
            val all = held.all { it }
            val any = held.any { it }
            if (statuses.any { !it.admits(all, any) }) f.falseStatuses++
            val state = h.runtime.inspect(id) as ItineraryState
            if (!state.started) {
                // The crash came before Book was accepted, so no trip was ever asked.
                assertTrue(!any && h.truth.deliveries.isEmpty(), "not started, but calls were made: ${chooser.history}")
                return@explore
            }
            f.accepted++
            assertTrue(state.settled, "unfinished: ${chooser.history}")
            for ((leg, key) in listOf(state.out, state.back).zip(trips)) {
                val tripState = h.runtime.inspect(BallId(TripPort.ballType, key)) as TripState
                assertEquals(Stage.Settled, tripState.stage, "trip $key unfinished: ${chooser.history}")
                val outcome = leg.outcome!!
                val kind = when (outcome) {
                    is Outcome.Done -> if (settledKnowledge(outcome.value) != null) "settled" else "interim"
                    is Outcome.NotDone -> "NotDone"
                    is Outcome.Unknown -> "Unknown"
                }
                f.legs.merge(kind, 1, Int::plus)
                // A settled reply is the trip's final knowledge, and the itinerary enters it as is.
                if (kind == "settled") {
                    assertEquals(Outcome.Done(TripReply.Status(Stage.Settled, tripState.knowledge)), outcome, "leg $key: ${chooser.history}")
                    assertEquals(tripState.knowledge, leg.knowledge)
                }
                val flight = airline.holds(key)
                val room = hotel.holds(key)
                if (!leg.knowledge.admits(flight && room, flight || room)) f.falseLegs++
            }
            when (val reply = h.externalOutcomes.single()) {
                null -> assertTrue(h.crashes > 0, "Book was never answered: ${chooser.history}")
                is Outcome.Done -> {
                    assertEquals(ItineraryReply.Status(settled = true, knowledge = state.knowledge), reply.value)
                    f.replied++
                }
                else -> fail("Book ended $reply: ${chooser.history}")
            }
            if (!state.knowledge.admits(allApplied = all, anyApplied = any)) f.falseClaims++
            f.settled.merge(state.knowledge, 1, Int::plus)
            // Control: lifting each trip's reply with the Boolean helper ("the trip is done") instead of
            // taking its knowledge turns a settled Unknown or Partial trip into a definite NotDone.
            val booleanLift = listOf(state.out, state.back)
                .map { leg -> leg.outcome!!.knowledge { settledKnowledge(it)?.isDone == true } }
                .reduce(Knowledge::plus)
            if (!booleanLift.admits(all, any)) f.booleanLiftWrong++
        }
        return f
    }

    @Test
    fun `the itinerary's knowledge admits the real bookings of both trips under faults and a crash`() {
        val f = exploreItinerary(forward, undo, window = CRASH_WINDOW)
        println("itinerary exploration (two trips, Durable, 4 booking and 2 compensation fates, crash window $CRASH_WINDOW): $f")
        assertEquals(0, f.falseClaims, "settled claims contradicted by the four bookings")
        assertEquals(0, f.falseStatuses, "statuses contradicted by the final bookings")
        assertEquals(0, f.falseLegs, "a trip's lifted reply contradicted by its bookings")
        assertTrue(f.longestRun <= CRASH_WINDOW, "the longest run has ${f.longestRun} tasks; widen the crash window")
        assertTrue(f.booleanLiftWrong > 0, "the Boolean lift should be wrong somewhere")
        assertTrue(f.afterUndo > 0, "no booking arrived after a void")
        assertTrue(f.settled.keys.containsAll(listOf(Knowledge.Done, Knowledge.NotDone, Knowledge.Partial, Knowledge.Unknown)), "${f.settled}")
        // Published totals: a change to the exploration must change them deliberately.
        assertEquals(29_774, f.runs)
        assertEquals(28_933, f.crashed)
        assertEquals(29_771, f.accepted)
        assertEquals(42, f.longestRun)
        assertEquals(20_167, f.booleanLiftWrong)
    }

    /** The same composition with every fate of the trip's own exploration and with seat and room availability, without a crash. */
    @Test
    fun `the itinerary's knowledge admits the real bookings under every fate of the trip's exploration`() {
        val f = exploreItinerary(tripForward, tripUndo, window = 0, choose = true)
        println("itinerary exploration (two trips, Durable, 6 booking and 3 compensation fates, availability, no crash): $f")
        assertEquals(0, f.falseClaims, "settled claims contradicted by the four bookings")
        assertEquals(0, f.falseStatuses, "statuses contradicted by the final bookings")
        assertEquals(0, f.falseLegs, "a trip's lifted reply contradicted by its bookings")
        assertTrue(f.businessRefusals > 0, "no SoldOut or Full answer reached a trip")
        assertTrue(f.afterUndo > 0, "no booking arrived after a void")
        // Published totals.
        assertEquals(130_321, f.runs)
        assertEquals(43_170, f.booleanLiftWrong)
    }

    /**
     * Calls to a trip that time out before the trip settles end `Unknown`, and
     * the itinerary settles with that leg unknown while the trip carries on.
     * Its knowledge is less informative, and still admits the real bookings.
     */
    @Test
    fun `a call to a trip that times out before the trip settles leaves that leg Unknown and the claims honest`() {
        val timeout = 2_500L
        assertTrue(timeout < trip.worstCaseMillis)
        val f = exploreItinerary(forward, undo, window = 0, tripTimeoutMillis = timeout)
        println("itinerary exploration (two trips, Durable, trip timeout $timeout ms of a ${trip.worstCaseMillis} ms worst case, no crash): $f")
        assertEquals(0, f.falseClaims, "settled claims contradicted by the four bookings")
        assertEquals(0, f.falseStatuses, "statuses contradicted by the final bookings")
        assertEquals(0, f.falseLegs, "a trip's lifted reply contradicted by its bookings")
        assertTrue((f.legs["Unknown"] ?: 0) > 0, "no call to a trip timed out: ${f.legs}")
        assertTrue((f.legs["settled"] ?: 0) > 0, "no trip settled in time: ${f.legs}")
        // Published totals: of 1,682 calls to a trip, 812 ended Unknown.
        assertEquals(mapOf("settled" to 870, "Unknown" to 812), f.legs.toMap())
    }

    /** The waiting list is bounded: a Book beyond it gets an unsettled status at once. */
    @Test
    fun `beyond the waiting list a Book is answered at once with an unsettled status`() {
        val itinerary = ItineraryFlow(tripTimeoutMillis = trip.worstCaseMillis + 1_000)
        val airline = Provider()
        val hotel = Provider()
        val h = harness(ExhaustiveChooser(), airline, hotel, itinerary, forward, undo, choose = false).start()
        repeat(ItineraryFlow.MAX_AWAITING + 2) { h.send(id, ItineraryRequest.Book, timeoutMillis = itinerary.worstCaseMillis + 1_000) }
        h.runUntilIdle()
        val replies = h.externalOutcomes.map { (it as Outcome.Done).value as ItineraryReply.Status }
        val settled = ItineraryReply.Status(settled = true, knowledge = Knowledge.Done)
        assertEquals(List(ItineraryFlow.MAX_AWAITING) { settled }, replies.take(ItineraryFlow.MAX_AWAITING))
        assertTrue(replies.drop(ItineraryFlow.MAX_AWAITING).none { it.settled }, "$replies")
        assertTrue(trips.all { airline.holds(it) && hotel.holds(it) })
    }

    private companion object {
        /** The scheduler tasks of the longest run, so a crash is tried before each of them; checked by the crash exploration. */
        const val CRASH_WINDOW = 42

        /** Above the most choices any run makes, so the bound never cuts a run short; checked in every run. */
        const val MAX_CHOICES = 24

        /** Above the largest exploration (every fate, availability, no crash: 130,321 runs). */
        const val MAX_RUNS = 200_000
    }
}
