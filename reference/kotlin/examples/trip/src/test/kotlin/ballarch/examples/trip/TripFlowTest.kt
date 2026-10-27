package ballarch.examples.trip

import ballarch.examples.trip.api.Stage
import ballarch.examples.trip.api.TripReply
import ballarch.examples.trip.api.TripRequest
import ballarch.examples.trip.api.settledKnowledge
import ballarch.examples.trip.app.tripComposition
import ballarch.kernel.BallId
import ballarch.kernel.Knowledge
import ballarch.kernel.Outcome
import ballarch.kernel.Tri
import ballarch.kernel.reportedKnowledge
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

/**
 * A provider that fences a trip's reference once it is voided: later bookings
 * for it have no effect. Without the fence, a voided booking can still land,
 * so a void is not an absorbing compensation.
 */
private class Provider(private val fence: Boolean = true) {
    val booked = HashMap<String, Int>()
    private val fenced = HashSet<String>()

    /** Booking requests that reached the provider after the trip's reference was voided. */
    var afterUndo = 0
        private set

    /** Books unless the reference is fenced or [available] says there is no seat or room (a business refusal). */
    fun book(ref: TripRef, available: () -> Boolean): Boolean {
        if (ref.trip in fenced) {
            afterUndo++
            if (fence) return false
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

    /** Runs in which the trip accepted Book (in the others the crash came first) and settled. */
    var accepted = 0
    val settled = LinkedHashMap<Knowledge, Int>()

    /** Settled knowledge that does not admit the providers' real bookings. */
    var falseClaims = 0

    /** Runs in which some status the trip held during the run did not admit the final bookings. */
    var falseStatuses = 0

    /** Control: runs in which a status of completions so far did not admit the final bookings. */
    var falseProgress = 0

    /** Settled runs in which the Book reply arrived through the port (in the others, the crash took the request). */
    var replied = 0
    var twoValuedWrong = 0
    var sagaWrong = 0

    /** Settled runs in which the two-valued status is wrong although the Flow did not give up compensating. */
    var twoValuedWrongElsewhere = 0

    /** Settled runs in which the Flow stopped compensating a booking that may be held, and their knowledge. */
    var gaveUp = 0
    val gaveUpKnowledge = LinkedHashMap<Knowledge, Int>()

    /** Settled runs whose bookings alone left the knowledge uncertain. */
    var uncertainBeforeCompensation = 0

    /** Settled runs that compensation made definite, and runs it made uncertain. */
    var madeDefinite = 0
    var madeUncertain = 0

    /** Booking requests that reached a provider after the trip's void or cancel (requests, not runs). */
    var afterUndo = 0
    var runsWithAfterUndo = 0

    /** Runs in which a SoldOut or Full answer reached the trip. */
    var businessRefusals = 0

    /** Runs in which the flight was booked more than once. */
    var doubleBookings = 0

    /** Runs that ended with a trip in [Stage.New] (a Transient restart erased it) while a provider holds a booking. */
    var forgotten = 0

    /** Scheduler tasks of the longest run without a crash, and the most choices any run made. */
    var longestRun = 0
    var mostChoices = 0

    val uncertainSettled: Int get() = settled.filterKeys { it.isUncertain }.values.sum()

    override fun toString(): String =
        "$runs runs ($crashed with a crash, ${runs - crashed} without; $repeats repeated choice sequences skipped), " +
            "$accepted accepted and settled, $replied answered through the port; settled knowledge $settled; " +
            "false settled claims $falseClaims, runs with a false status $falseStatuses; " +
            "control: runs in which a completions-so-far status was contradicted $falseProgress; " +
            "of the $accepted settled runs: two-valued status wrong in $twoValuedWrong ($twoValuedWrongElsewhere where the Flow did not give up), " +
            "booked/failed/needs-attention status wrong in $sagaWrong, Flow gave up compensating in $gaveUp (knowledge $gaveUpKnowledge), " +
            "bookings alone uncertain in $uncertainBeforeCompensation, settled knowledge uncertain in $uncertainSettled " +
            "(compensation made $madeDefinite definite and $madeUncertain uncertain); " +
            "bookings after a void or cancel $afterUndo (in $runsWithAfterUndo runs); runs with a SoldOut/Full answer $businessRefusals; " +
            "runs with a double flight booking $doubleBookings; runs ending New while a booking is held $forgotten; " +
            "longest run without a crash $longestRun tasks; most choices in a run $mostChoices"
}

class TripFlowTest {
    private val id = BallId("trip", "t1")
    private val forward = listOf(Fate.Reply, Fate.LoseRequest, Fate.LoseReply, Fate.ErrorAfterApply, Fate.RefuseConnection, Fate.DeliverLate)
    private val undo = listOf(Fate.Reply, Fate.LoseReply, Fate.RefuseConnection)

    private fun harness(chooser: Chooser, profile: Profile, airline: Provider, hotel: Provider, flow: TripFlow, choose: Boolean) =
        Harness(chooser, profile) {
            // After a Transient restart (a new epoch) the network behaves, so that only the restart and the
            // providers' availability are explored there. A Durable run stays in epoch 0.
            val fwd = if (truth.epoch > 0) listOf(Fate.Reply) else forward
            val und = if (truth.epoch > 0) listOf(Fate.Reply) else undo
            tripComposition(
                flightBook = SimPort(FlightBook, scheduler, chooser, truth, fwd) {
                    if (airline.book(it) { !choose || chooser.choose(2, "airline:seat") == 0 }) FlightReply.Booked("12A") else FlightReply.SoldOut
                },
                flightVoid = SimPort(FlightVoid, scheduler, chooser, truth, und) { airline.undo(it) },
                hotelBook = SimPort(HotelBook, scheduler, chooser, truth, fwd) {
                    if (hotel.book(it) { !choose || chooser.choose(2, "hotel:room") == 0 }) HotelReply.Booked else HotelReply.Full
                },
                hotelCancel = SimPort(HotelCancel, scheduler, chooser, truth, und) { hotel.undo(it) },
                flow = flow,
            )
        }

    /**
     * Explores every combination of fates (six per booking attempt, three per
     * compensation attempt), seat and room availability, and one crash before
     * any of the first [window] scheduler tasks. With [rebook], a client whose
     * request a crash left unanswered books again with the same idempotency key,
     * as it would once its request timed out.
     */
    private fun exploreTrip(
        profile: Profile = Profile.Durable,
        fence: Boolean = true,
        maxCompensations: Int = 2,
        window: Int = CRASH_WINDOW,
        rebook: Boolean = false,
    ): Findings {
        val f = Findings()
        val flow = TripFlow(maxCompensations = maxCompensations)
        val timeout = flow.worstCaseMillis + 1_000
        explore(ExhaustiveChooser(maxChoices = MAX_CHOICES)) { chooser ->
            val airline = Provider(fence)
            val hotel = Provider(fence)
            val h = harness(chooser, profile, airline, hotel, flow, choose = true)
            h.maybeCrashOnce(window)
            // Every status the trip held: what GetStatus would have answered at that moment.
            val statuses = LinkedHashSet<Knowledge>()
            val progress = LinkedHashSet<Knowledge>()
            fun probe() {
                val s = h.runtime.inspect(id) as TripState
                statuses += s.knowledge
                progress += Knowledge.single(s.flight.net) + Knowledge.single(s.hotel.net)
            }
            val crash = h.scheduler.beforeTask
            h.scheduler.beforeTask = {
                probe()
                crash?.invoke()
            }
            val key = if (rebook) "book-${id.key}" else null
            h.start()
            h.send(id, TripRequest.Book, timeoutMillis = timeout, idempotencyKey = key)
            h.runUntilIdle()
            if (h.crashes == 0 && chooser.history.first() != "crashBeforeTask=0") {
                f.repeats++ // the crash point lies after the run's end: the same run as without a crash
                return@explore
            }
            f.runs++
            if (rebook && h.externalOutcomes.single() == null) {
                h.send(id, TripRequest.Book, timeoutMillis = timeout, idempotencyKey = key)
                h.runUntilIdle()
            }
            probe()
            h.assertInvariants()
            if (profile == Profile.Durable) h.assertNoReplyLeaks()
            assertTrue(chooser.history.size < MAX_CHOICES, "the choice bound cut the run short: ${chooser.history}")
            if (h.crashes > 0) f.crashed++ else f.longestRun = maxOf(f.longestRun, h.scheduler.executed.toInt())
            f.mostChoices = maxOf(f.mostChoices, chooser.history.size)
            if ((airline.booked["t1"] ?: 0) > 1) f.doubleBookings++
            f.afterUndo += airline.afterUndo + hotel.afterUndo
            if (airline.afterUndo + hotel.afterUndo > 0) f.runsWithAfterUndo++
            val delivered = h.tracer.events.filterIsInstance<TraceEvent.Committed>().map { (it.completedOutcome as? Outcome.Done<*>)?.value }
            if (FlightReply.SoldOut in delivered || HotelReply.Full in delivered) f.businessRefusals++

            val flightHeld = airline.holds("t1")
            val hotelHeld = hotel.holds("t1")
            val all = flightHeld && hotelHeld
            val any = flightHeld || hotelHeld
            if (statuses.any { !it.admits(all, any) }) f.falseStatuses++
            if (progress.any { !it.admits(all, any) }) f.falseProgress++
            val state = h.runtime.inspect(id) as TripState
            if (state.stage == Stage.New) {
                // Durable: the crash came before Book was accepted, so no call was ever made.
                if (profile == Profile.Durable) assertTrue(!any && h.truth.deliveries.isEmpty(), "New, but calls were made: ${chooser.history}")
                // Transient: a restart erased a trip whose bookings the providers keep. Every call may still be made, so it claims nothing.
                if (any) {
                    f.forgotten++
                    assertEquals(Knowledge.Unknown, state.knowledge, "a forgotten trip must claim nothing: ${chooser.history}")
                }
                return@explore
            }
            f.accepted++
            assertEquals(Stage.Settled, state.stage, "unfinished: ${chooser.history}")
            when (val reply = h.externalOutcomes.last()) {
                null -> assertTrue(h.crashes > 0, "Book was never answered: ${chooser.history}")
                is Outcome.Done -> {
                    assertEquals(TripReply.Status(Stage.Settled, state.knowledge), reply.value, "the reply is the settled knowledge")
                    f.replied++
                }
                else -> fail("Book ended $reply: ${chooser.history}")
            }
            if (!state.knowledge.admits(allApplied = all, anyApplied = any)) f.falseClaims++
            f.settled.merge(state.knowledge, 1, Int::plus)

            // Control: the usual two-valued status ("booked" only if both bookings answered Booked,
            // otherwise "failed", meaning nothing is held), on the same runs.
            val claimsBooked = state.flight.effect == Tri.Yes && state.hotel.effect == Tri.Yes
            val twoValuedWrong = claimsBooked != all || (!claimsBooked && any)
            if (twoValuedWrong) f.twoValuedWrong++
            // Fair control: a saga status with a third value for a failed compensation, from the same
            // outcomes and without the algebra: booked, failed (each booking refused or undone), or needs attention.
            fun clear(p: Part) = p.effect == Tri.No || p.undone == Tri.Yes
            val saga = when {
                claimsBooked -> "booked"
                clear(state.flight) && clear(state.hotel) -> "failed"
                else -> "needs attention" // the Flow stopped compensating a booking that may be held
            }
            if ((saga == "booked" && !all) || (saga == "failed" && any)) f.sagaWrong++
            if (saga == "needs attention") {
                f.gaveUp++
                f.gaveUpKnowledge.merge(state.knowledge, 1, Int::plus)
            } else if (twoValuedWrong) {
                f.twoValuedWrongElsewhere++
            }
            // What compensation changed: knowledge of the bookings alone against the settled knowledge.
            val before = (Knowledge.single(state.flight.effect) + Knowledge.single(state.hotel.effect)).isUncertain
            if (before) f.uncertainBeforeCompensation++
            if (before && !state.knowledge.isUncertain) f.madeDefinite++
            if (!before && state.knowledge.isUncertain) f.madeUncertain++
        }
        return f
    }

    /**
     * Composition closure (rule R14) under faults, Durable profile: whatever the
     * network, the providers and one crash do, every status the trip holds
     * admits the final bookings, its answer to Book is its settled knowledge and
     * admits the real bookings, and the flight is never booked twice.
     */
    @Test
    fun `the trip's knowledge admits the real bookings under every fate and crash point`() {
        val f = exploreTrip()
        println("trip exploration (Durable, maxCompensations=2, crash window $CRASH_WINDOW): $f")
        assertEquals(0, f.falseClaims, "settled claims contradicted by the bookings")
        assertEquals(0, f.falseStatuses, "statuses contradicted by the final bookings")
        assertEquals(0, f.doubleBookings)
        // Exhaustive: the crash window covers every task of the longest run (and no run reached the choice bound).
        assertTrue(f.longestRun <= CRASH_WINDOW, "the longest run has ${f.longestRun} tasks; widen the crash window")
        // The hypotheses are exercised: late bookings reach a voided reference, and business refusals reach the trip.
        assertTrue(f.afterUndo > 0, "no booking arrived after a void")
        assertTrue(f.businessRefusals > 0, "no SoldOut or Full answer reached the trip")
        // Control: a status of completions so far is contradicted later; that is why R14 counts
        // a call the trip may still make as Maybe.
        assertTrue(f.falseProgress > 0, "the completions-so-far control should be contradicted somewhere")
        // Control: a two-valued status is wrong where the Flow gave up compensating; a status with a third value never is.
        assertTrue(f.twoValuedWrong > 0, "the two-valued control should be wrong somewhere")
        assertEquals(0, f.sagaWrong, "a status with a third value should never be wrong")
        assertEquals(0, f.twoValuedWrongElsewhere, "the two-valued status is wrong only where the Flow gave up compensating")
        // Every honest end is reachable, and compensation narrows ambiguity; it does not remove it.
        assertTrue(f.settled.keys.containsAll(listOf(Knowledge.Done, Knowledge.NotDone, Knowledge.Partial)), "${f.settled}")
        assertTrue(f.uncertainSettled < f.uncertainBeforeCompensation, "${f.uncertainSettled} uncertain after compensation, ${f.uncertainBeforeCompensation} before")
        assertTrue(f.madeUncertain > 0, "a compensation whose answer is lost can make a held booking uncertain")
        // Published totals: a change to the exploration must change them deliberately.
        assertEquals(6_438, f.runs)
        assertEquals(6_435, f.accepted)
        assertEquals(22, f.longestRun)
        assertEquals(822, f.afterUndo)
        assertEquals(5_920, f.falseProgress)
        assertEquals(1_190, f.twoValuedWrong)
        assertEquals(5_565, f.gaveUp)
        assertEquals(
            mapOf(Knowledge.NotAllDone to 2_977, Knowledge.Partial to 172, Knowledge.Unknown to 2_037, Knowledge.SomeDone to 379),
            f.gaveUpKnowledge.toMap(),
        )
    }

    /**
     * Negative control for the absorbing-compensation hypothesis: without the
     * providers' fence a voided booking can still land, and the same
     * exploration finds settled claims that the bookings contradict.
     */
    @Test
    fun `without the providers' fence the same exploration finds false claims`() {
        val f = exploreTrip(fence = false)
        println("trip exploration without the fence (Durable, maxCompensations=2, crash window $CRASH_WINDOW): $f")
        assertTrue(f.falseClaims > 0, "removing the fence should break the claims")
        // Published totals.
        assertEquals(7_257, f.accepted)
        assertEquals(115, f.falseClaims)
    }

    /**
     * Transient profile, first half: a crash erases the trip's ledger while the
     * providers keep their bookings. The restarted trip is back in [Stage.New],
     * where every call may still be made, so it claims nothing (`Unknown`); no
     * status it holds is contradicted, but with no new Book it never settles.
     */
    @Test
    fun `a trip restarted under Transient claims nothing about the bookings it forgot`() {
        val f = exploreTrip(profile = Profile.Transient)
        println("trip exploration (Transient, no new Book after a crash, crash window $CRASH_WINDOW): $f")
        assertEquals(0, f.falseClaims, "settled claims contradicted by the bookings")
        assertEquals(0, f.falseStatuses, "statuses contradicted by the final bookings")
        assertTrue(f.forgotten > 0, "a Transient restart should erase a trip whose bookings are held")
        assertEquals(f.runs - f.crashed, f.accepted, "only runs without a crash settle")
        // Published totals.
        assertEquals(2_358, f.runs)
        assertEquals(635, f.forgotten)
    }

    /**
     * Transient profile, second half, and the documented limit of rule R14: the
     * crash also erases the idempotency records. A client whose request went
     * unanswered books again with the same key and starts a fresh trip, which
     * knows only the calls it made since the restart, so its settled claims can
     * be false and the flight can be booked twice.
     */
    @Test
    fun `the trip requires the Durable profile`() {
        val f = exploreTrip(profile = Profile.Transient, rebook = true)
        println("trip exploration (Transient, book again after a crash, crash window $CRASH_WINDOW): $f")
        assertTrue(f.falseClaims > 0, "a Transient restart should lead to claims the bookings contradict")
        assertTrue(f.doubleBookings > 0, "a Transient restart should lead to a second flight booking")
        // Published totals.
        assertEquals(3_073, f.accepted)
        assertEquals(608, f.falseClaims)
        assertEquals(152, f.doubleBookings)
    }

    /**
     * The two-valued control is wrong only in runs where the Flow gave up
     * compensating, so its error count is set by the compensation budget, not
     * by the algebra; the knowledge and the saga status stay honest at every
     * budget. Explored without a crash to keep it small.
     */
    @Test
    fun `the two-valued control is wrong only where the Flow gave up compensating`() {
        for (budget in 1..3) {
            val f = exploreTrip(maxCompensations = budget, window = 0)
            println("trip exploration (Durable, no crash, maxCompensations=$budget): $f")
            assertEquals(0, f.falseClaims)
            assertEquals(0, f.sagaWrong)
            assertTrue(f.twoValuedWrong > 0, "$f")
            assertEquals(0, f.twoValuedWrongElsewhere, "$f")
        }
    }

    @Test
    fun `a repeated Book joins the booking in progress and gets the same settled answer`() {
        val chooser = ExhaustiveChooser()
        val airline = Provider()
        val hotel = Provider()
        val flow = TripFlow()
        val h = harness(chooser, Profile.Durable, airline, hotel, flow, choose = false).start()
        h.send(id, TripRequest.Book, timeoutMillis = flow.worstCaseMillis + 1_000)
        h.send(id, TripRequest.Book, timeoutMillis = flow.worstCaseMillis + 1_000)
        h.send(id, TripRequest.GetStatus)
        h.runUntilIdle()
        h.send(id, TripRequest.Book)
        h.runUntilIdle()
        val settled: Outcome<Any?> = Outcome.Done(TripReply.Status(Stage.Settled, Knowledge.Done))
        val interim: Outcome<Any?> = Outcome.Done(TripReply.Status(Stage.BookingFlight, Knowledge.Unknown))
        assertEquals(listOf(settled, settled, interim, settled), h.externalOutcomes.toList())
        assertEquals(1, airline.booked["t1"])
    }

    /** The waiting list is bounded: a Book beyond it gets an unsettled status at once, which a caller lifts as Unknown. */
    @Test
    fun `beyond the waiting list a Book is answered at once with an unsettled status`() {
        val chooser = ExhaustiveChooser()
        val airline = Provider()
        val hotel = Provider()
        val flow = TripFlow()
        val h = harness(chooser, Profile.Durable, airline, hotel, flow, choose = false).start()
        repeat(TripFlow.MAX_AWAITING + 2) { h.send(id, TripRequest.Book, timeoutMillis = flow.worstCaseMillis + 1_000) }
        h.runUntilIdle()
        val replies = h.externalOutcomes.map { (it as Outcome.Done).value as TripReply.Status }
        assertEquals(List(TripFlow.MAX_AWAITING) { TripReply.Status(Stage.Settled, Knowledge.Done) }, replies.take(TripFlow.MAX_AWAITING))
        for (reply in replies.drop(TripFlow.MAX_AWAITING)) {
            assertTrue(reply.stage != Stage.Settled, "$reply")
            assertEquals(Knowledge.Unknown, Outcome.Done(reply).reportedKnowledge(onlyRequest = false, settled = ::settledKnowledge))
        }
        assertEquals(1, airline.booked["t1"])
    }

    /** The trip's view of one booking is the most informative value admitting every way the booking can still end. */
    @Test
    fun `a part's view equals supervaluation over the ways it can still end`() {
        fun completions(t: Tri) = listOf(true, false).filter { t.admits(it) }
        var checked = 0
        for (effect in Tri.entries) for (undone in Tri.entries) for (bookingPending in listOf(false, true)) for (undoPending in listOf(false, true)) {
            val effects = if (bookingPending) listOf(true, false) else completions(effect)
            val undos = if (undoPending) (completions(undone) + true).distinct() else completions(undone)
            val nets = effects.flatMap { a -> undos.map { u -> a && !u } }.toSet()
            val expected = if (nets.size == 2) Tri.Maybe else Tri.of(nets.single())
            assertEquals(expected, Part(effect, undone).view(bookingPending, undoPending), "$effect $undone $bookingPending $undoPending")
            checked++
        }
        assertEquals(36, checked)
    }

    private companion object {
        /** The scheduler tasks of the longest run, so a crash is tried before each of them; checked by the main exploration. */
        const val CRASH_WINDOW = 22

        /** Above the most choices any run makes, so the bound never cuts a run short; checked in every run. */
        const val MAX_CHOICES = 16
    }
}
