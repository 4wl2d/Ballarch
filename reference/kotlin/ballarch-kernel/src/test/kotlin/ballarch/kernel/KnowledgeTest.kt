package ballarch.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive checks of the knowledge algebra (Core §4.2): the connectives are
 * exact, composition is a commutative monoid, composition of honest
 * constituents is honest and coordinate-wise most informative (with one or
 * several compensation attempts per constituent), and a settled composite
 * entered one level up equals the flattened knowledge (associativity).
 */
class KnowledgeTest {
    private val tris = Tri.entries
    private val outcomes: List<Outcome<Unit>> = listOf(
        Outcome.Done(Unit),
        Outcome.NotDone(NotDoneReason.NoAnswer),
        Outcome.Unknown(UnknownReason.DeadlineAfterSend),
    )

    /** The ground truths a three-valued value admits. */
    private fun completions(t: Tri): List<Boolean> = listOf(true, false).filter { t.admits(it) }

    private fun completions(o: Outcome<*>?): List<Boolean> = when (o) {
        null -> listOf(false) // no compensation: nothing was undone
        is Outcome.Done -> listOf(true)
        is Outcome.NotDone -> listOf(false)
        is Outcome.Unknown -> listOf(true, false)
    }

    /** The most informative value that admits every element of [values]. */
    private fun supremum(values: Collection<Boolean>): Tri = when (values.toSet()) {
        setOf(true) -> Tri.Yes
        setOf(false) -> Tri.No
        else -> Tri.Maybe
    }

    @Test
    fun `connectives equal supervaluation over completions`() {
        for (a in tris) {
            assertEquals(supremum(completions(a).map { !it }), !a, "not $a")
            for (b in tris) {
                val ands = completions(a).flatMap { x -> completions(b).map { y -> x && y } }
                val ors = completions(a).flatMap { x -> completions(b).map { y -> x || y } }
                assertEquals(supremum(ands), a and b, "$a and $b")
                assertEquals(supremum(ors), a or b, "$a or $b")
            }
        }
    }

    @Test
    fun `plus is a commutative monoid on the seven values`() {
        val all = Knowledge.values
        for (x in all) {
            assertEquals(x, x + Knowledge.NoEffects)
            assertEquals(x, Knowledge.NoEffects + x)
            for (y in all) {
                assertTrue(x + y in all)
                assertEquals(x + y, y + x, "$x + $y")
                for (z in all) assertEquals((x + y) + z, x + (y + z), "($x + $y) + $z")
            }
        }
    }

    private data class Constituent(val forward: Outcome<Unit>, val undo: Outcome<Unit>?)

    private val constituents: List<Constituent> =
        outcomes.flatMap { f -> (listOf<Outcome<Unit>?>(null) + outcomes).map { u -> Constituent(f, u) } }

    private fun <C> sequences(n: Int, of: List<C>): List<List<C>> =
        if (n == 0) listOf(emptyList()) else sequences(n - 1, of).flatMap { prefix -> of.map { prefix + it } }

    private fun sequences(n: Int): List<List<Constituent>> = sequences(n, constituents)

    /** Every combination of real effects consistent with the constituents' possible net effects. */
    private fun <C> truths(parts: List<C>, nets: (C) -> List<Boolean>): List<List<Boolean>> =
        parts.fold(listOf(emptyList())) { acc, c -> acc.flatMap { prefix -> nets(c).map { prefix + it } } }

    private fun truths(parts: List<Constituent>): List<List<Boolean>> = truths(parts) { c ->
        completions(c.forward).flatMap { applied -> completions(c.undo).map { undone -> applied && !undone } }.distinct()
    }

    @Test
    fun `composition of honest constituents is honest and coordinate-wise exact`() {
        var checked = 0
        for (n in 0..4) {
            for (parts in sequences(n)) {
                val k = parts.map { it.forward.compensatedBy(it.undo) }.combine()
                val worlds = truths(parts)
                for (net in worlds) {
                    assertTrue(k.admits(allApplied = net.all { it }, anyApplied = net.any { it }), "$parts: $k does not admit $net")
                    checked++
                }
                assertEquals(supremum(worlds.map { w -> w.all { it } }), k.all, "all of $parts")
                assertEquals(supremum(worlds.map { w -> w.any { it } }), k.any, "any of $parts")
            }
        }
        assertEquals(69_905, checked, "worlds explored for up to four constituents")
    }

    /** A constituent with zero to two compensation attempts, as a Flow that repeats a failed compensation makes. */
    private data class Retried(val forward: Outcome<Unit>, val undos: List<Outcome<Unit>>)

    private val retried: List<Retried> =
        outcomes.flatMap { f -> (0..2).flatMap { k -> sequences(k, outcomes) }.map { us -> Retried(f, us) } }

    /** The attempts undid the effect if any one of them did. */
    private fun undoneCompletions(undos: List<Outcome<Unit>>): List<Boolean> =
        undos.fold(listOf(false)) { acc, u -> acc.flatMap { a -> completions(u).map { a || it } }.distinct() }

    @Test
    fun `several compensation attempts per constituent compose honestly and exactly`() {
        assertEquals(39, retried.size)
        var checked = 0
        for (n in 0..3) {
            for (parts in sequences(n, retried)) {
                val k = parts.map { it.forward.compensatedBy(it.undos) }.combine()
                val worlds = truths(parts) { c ->
                    completions(c.forward).flatMap { applied -> undoneCompletions(c.undos).map { undone -> applied && !undone } }.distinct()
                }
                for (net in worlds) {
                    assertTrue(k.admits(allApplied = net.all { it }, anyApplied = net.any { it }), "$parts: $k does not admit $net")
                    checked++
                }
                assertEquals(supremum(worlds.map { w -> w.all { it } }), k.all, "all of $parts")
                assertEquals(supremum(worlds.map { w -> w.any { it } }), k.any, "any of $parts")
            }
        }
        assertEquals(127_551, checked, "worlds explored for up to three constituents with up to two compensation attempts each")
    }

    /**
     * Two-level composition, algebraically: a composite that reports its settled
     * knowledge through its port enters the caller's knowledge as one summand.
     * For every split of every sequence of up to four constituents into a nested
     * composite and the caller's own calls, the caller's knowledge equals the
     * flattened knowledge. With a settled reply this is the associativity of
     * `plus` (checked on all triples above), not independent evidence; the
     * itinerary example explores two levels of real Balls. A reply that is
     * interim, or a call that ended `Unknown`, counts as `Unknown`, and the
     * caller's knowledge then still admits every world of the flattened operation.
     */
    @Test
    fun `a settled composite entered one level up equals the flattened knowledge`() {
        var checked = 0
        for (n in 0..4) {
            for (parts in sequences(n)) {
                val leaves = parts.map { it.forward.compensatedBy(it.undo) }
                val flat = leaves.combine()
                val worlds = truths(parts)
                for (split in 0..n) {
                    val inner = leaves.take(split).combine()
                    val rest = leaves.drop(split).combine()
                    assertEquals(flat, lift(Outcome.Done(Report(settled = true, knowledge = inner))) + rest, "$parts split at $split")
                    val interim = lift(Outcome.Done(Report(settled = false, knowledge = inner))) + rest
                    val lost = lift(Outcome.Unknown(UnknownReason.DeadlineAfterSend)) + rest
                    for (net in worlds) {
                        assertTrue(interim.admits(net.all { it }, net.any { it }), "$parts split at $split, interim")
                        assertTrue(lost.admits(net.all { it }, net.any { it }), "$parts split at $split, lost")
                    }
                    checked++
                }
            }
        }
        assertEquals(1 + 2 * 12 + 3 * 144 + 4 * 1_728 + 5 * 20_736, checked, "splits checked")
    }

    @Test
    fun `only a settled reply is lifted, an interim one counts as Unknown`() {
        for (only in listOf(true, false)) {
            for (k in Knowledge.values) {
                assertEquals(k, lift(Outcome.Done(Report(settled = true, knowledge = k)), only))
                assertEquals(Knowledge.Unknown, lift(Outcome.Done(Report(settled = false, knowledge = k)), only))
            }
            assertEquals(Knowledge.Unknown, lift(Outcome.Unknown(UnknownReason.DeadlineAfterSend), only))
        }
        // NotDone: the composite refused the request, or the runtime never handed it over. Only for the one
        // request that could start the operation does it mean that the composite made no call.
        val refusals: List<NotDoneReason<Report>> = listOf(
            NotDoneReason.Refused(Report(settled = false, knowledge = Knowledge.NoEffects)),
            NotDoneReason.DeadlineBeforeSend,
            NotDoneReason.Overloaded,
            NotDoneReason.Fault("instance is quarantined"),
            NotDoneReason.Cancelled,
        )
        for (reason in refusals) {
            assertEquals(Knowledge.NotDone, lift(Outcome.NotDone(reason), onlyRequest = true), "$reason")
            assertEquals(Knowledge.Unknown, lift(Outcome.NotDone(reason), onlyRequest = false), "$reason")
        }
        // A first request timed out after the composite accepted it and booked everything; a repeated
        // request found the inbox full. Read as the only request, the repeat would claim that nothing
        // happened; read as a repeat, it claims nothing.
        val repeat: Outcome<Report> = Outcome.NotDone(NotDoneReason.Overloaded)
        assertFalse(lift(repeat, onlyRequest = true).admits(allApplied = true, anyApplied = true))
        assertTrue(lift(repeat, onlyRequest = false).admits(allApplied = true, anyApplied = true))
        // A Boolean reading of a settled reply turns a settled Unknown composite into a false NotDone.
        val settledUnknown: Outcome<Report> = Outcome.Done(Report(settled = true, knowledge = Knowledge.Unknown))
        assertEquals(Knowledge.NotDone, settledUnknown.knowledge { it.knowledge.isDone })
        assertEquals(Knowledge.Unknown, lift(settledUnknown))
    }

    private data class Report(val settled: Boolean, val knowledge: Knowledge)

    private fun lift(o: Outcome<Report>, onlyRequest: Boolean = true): Knowledge =
        o.reportedKnowledge(onlyRequest) { r -> r.knowledge.takeIf { r.settled } }

    /**
     * Rule R14 counts a call the Ball may still make as Maybe. Counting it as No
     * (a report of completions so far) makes a claim that a later completion
     * contradicts: here the first call is done and the second is still to be made.
     */
    @Test
    fun `a call the Ball may still make counts as Maybe, not No`() {
        val first = Outcome.Done(Unit).knowledge()
        val asNo = first + Knowledge.NotDone
        val asMaybe = first + Knowledge.Unknown
        assertEquals(Knowledge.Partial, asNo)
        assertFalse(asNo.admits(allApplied = true, anyApplied = true), "the second call may still succeed")
        assertEquals(Knowledge.SomeDone, asMaybe)
        assertTrue(asMaybe.admits(allApplied = true, anyApplied = true) && asMaybe.admits(allApplied = false, anyApplied = true))
    }

    @Test
    fun `an absorbing compensation turns an unknown effect into a definite one`() {
        val unknown = Outcome.Unknown(UnknownReason.LostAfterSend)
        val notDone = Outcome.NotDone<Unit>(NotDoneReason.NoAnswer)
        assertEquals(Knowledge.NotDone, unknown.compensatedBy(Outcome.Done(Unit)))
        assertEquals(Knowledge.Unknown, unknown.compensatedBy(notDone))
        assertEquals(Knowledge.Unknown, unknown.compensatedBy(Outcome.Unknown(UnknownReason.DeadlineAfterSend)))
        // Several attempts: one that took effect is enough; attempts that did not leave the effect as it was.
        assertEquals(Knowledge.NotDone, unknown.compensatedBy(listOf(notDone, Outcome.Done(Unit))))
        assertEquals(Knowledge.Unknown, unknown.compensatedBy(listOf(notDone, notDone)))
        assertEquals(Knowledge.Unknown, unknown.compensatedBy(emptyList()))
        assertEquals(Knowledge.Done, Outcome.Done(Unit).compensatedBy(listOf(notDone)))
        // A compensation the Ball may still make enters as Unknown (R14): leaving it out would claim Done.
        assertEquals(Knowledge.Unknown, Outcome.Done(Unit).compensatedBy(listOf(notDone, Outcome.Unknown(UnknownReason.LostAfterSend))))
    }

    @Test
    fun `business refusals inside Done are not effects`() {
        val declined: Outcome<String> = Outcome.Done("declined")
        assertEquals(Knowledge.NotDone, declined.knowledge { it == "charged" })
        assertEquals(Knowledge.Done, Outcome.Done("charged").knowledge { it == "charged" })
        assertEquals(
            Knowledge.NotDone,
            Outcome.Done("charged").compensatedBy(Outcome.Done("voided"), effective = { it == "charged" }, undone = { it == "voided" }),
        )
        // Only a Unit port reads Done without a predicate: `declined.knowledge()` does not compile.
        assertEquals(Knowledge.Done, Outcome.Done(Unit).knowledge())
        assertEquals(Tri.Yes, Outcome.Done(Unit).effect())
    }

    @Test
    fun `a composite projects to an honest three-valued outcome`() {
        val expected = mapOf(
            Knowledge.Done to "Done",
            Knowledge.NotDone to "NotDone",
            Knowledge.NoEffects to "NotDone",
            Knowledge.Partial to "Unknown",
            Knowledge.NotAllDone to "Unknown",
            Knowledge.SomeDone to "Unknown",
            Knowledge.Unknown to "Unknown",
        )
        for ((k, kind) in expected) {
            val o = k.toOutcome(Unit, NotDoneReason.NoAnswer, UnknownReason.LostAfterSend)
            assertEquals(kind, o::class.simpleName, "$k")
        }
        assertEquals(Knowledge.Partial, Outcome.Done(Unit).knowledge() + Outcome.NotDone<Unit>(NotDoneReason.NoAnswer).knowledge())
    }

    /**
     * The projection is honest one level up: read as the effect of one call, it
     * admits "some effect happened" in every world, and "every effect happened"
     * whenever the operation has a constituent.
     */
    @Test
    fun `the projected outcome admits the truth in every world`() {
        for (n in 0..4) {
            for (parts in sequences(n)) {
                val effect = parts.map { it.forward.compensatedBy(it.undo) }.combine()
                    .toOutcome(Unit, NotDoneReason.NoAnswer, UnknownReason.LostAfterSend).effect()
                for (net in truths(parts)) {
                    assertTrue(effect.admits(net.any { it }), "$parts: $effect vs $net")
                    if (n > 0) assertTrue(effect.admits(net.all { it }), "$parts: $effect vs $net")
                }
            }
        }
    }

    @Test
    fun `pairs no operation can produce are rejected`() {
        assertFailsWith<IllegalArgumentException> { Knowledge.of(Tri.Yes, Tri.Maybe) }
        assertFailsWith<IllegalArgumentException> { Knowledge.of(Tri.Maybe, Tri.No) }
        assertEquals(7, Knowledge.values.toSet().size)
    }
}
