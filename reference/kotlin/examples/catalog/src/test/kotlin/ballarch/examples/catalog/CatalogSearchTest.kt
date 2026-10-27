package ballarch.examples.catalog

import ballarch.examples.catalog.app.catalogComposition
import ballarch.examples.catalog.app.showSearch
import ballarch.kernel.BallId
import ballarch.kernel.NotDoneReason
import ballarch.kernel.Outcome
import ballarch.runtime.TraceEvent
import ballarch.testkit.Fate
import ballarch.testkit.Harness
import ballarch.testkit.RandomChooser
import ballarch.testkit.SimPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogSearchTest {
    private val screen = BallId(CatalogSearchBall.type, "s1")

    private fun harness(fate: Fate) = Harness(RandomChooser(1)) {
        catalogComposition(SimPort(CatalogSearch, scheduler, chooser, truth, listOf(fate)) { listOf(Product("${it.text}-1")) })
    }.start()

    @Test
    fun `only the latest search changes the display, which a typed watch shows`() {
        val h = harness(Fate.Reply)
        val shown = ArrayList<Display>()
        showSearch(h.runtime, screen.key) { shown += it }
        h.send(screen, SearchRequest.Search("a"))
        h.send(screen, SearchRequest.Search("b"))
        h.runUntilIdle()
        h.assertInvariants()
        assertEquals(Display.Idle, shown.first())
        assertEquals(Display.Results("b", listOf(Product("b-1"))), shown.last())
        assertTrue(shown.none { it is Display.Results && it.query == "a" }, "$shown")
    }

    @Test
    fun `a search that gets no answer is retried after each attempt timeout and fails before its deadline`() {
        val h = harness(Fate.LoseRequest)
        val t0 = h.scheduler.now()
        h.send(screen, SearchRequest.Search("a"))
        h.runUntilIdle()
        h.assertInvariants()
        assertEquals(Display.Failed("a"), h.runtime.read(CatalogSearchBall, screen.key).state.display)
        // Attempts at 0, 600 and 1,200 ms, each stopped after 500 ms; the deadline is 2,000 ms.
        assertEquals(listOf(t0, t0 + 600, t0 + 1_200), h.tracer.events.filterIsInstance<TraceEvent.CallSent>().map { it.at })
        val resolved = h.tracer.events.filterIsInstance<TraceEvent.CallResolved>().single()
        assertEquals(t0 + 1_700, resolved.at)
        assertEquals(Outcome.NotDone(NotDoneReason.NoAnswer), resolved.outcome)
    }
}
