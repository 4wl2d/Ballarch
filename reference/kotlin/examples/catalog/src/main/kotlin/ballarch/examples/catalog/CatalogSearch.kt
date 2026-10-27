package ballarch.examples.catalog

import ballarch.kernel.AdapterPort
import ballarch.kernel.Ball
import ballarch.kernel.CallRef
import ballarch.kernel.Cancel
import ballarch.kernel.Completion
import ballarch.kernel.Context
import ballarch.kernel.Decision
import ballarch.kernel.EffectClass
import ballarch.kernel.Outcome
import ballarch.kernel.RequestContext
import ballarch.kernel.Step
import ballarch.kernel.accept
import ballarch.kernel.call
import ballarch.kernel.safeOutcomeFor
import ballarch.kernel.step

/**
 * S1: a search screen shows results for the most recent query only.
 *
 * The search service has no side effects, so its port is [EffectClass.Safe]:
 * an unanswered search ends `NotDone(NoAnswer)`, and the typed outcome of a
 * search ([safeOutcomeFor]) has no "unknown" case to handle.
 */
data class Product(val name: String)

data class SearchQuery(val text: String)

val CatalogSearch = AdapterPort<SearchQuery, List<Product>>("catalog.search", EffectClass.Safe)

sealed interface Display {
    data object Idle : Display
    data class Loading(val query: String) : Display
    data class Results(val query: String, val products: List<Product>) : Display
    data class Failed(val query: String) : Display
}

/** A search whose answer may change the display: the latest one. */
data class LatestSearch(val query: String, val call: CallRef<List<Product>>)

data class SearchState(val pending: LatestSearch?, val display: Display)

sealed interface SearchRequest {
    data class Search(val query: String) : SearchRequest
}

object CatalogSearchBall : Ball<SearchState, SearchRequest, Display> {
    const val SEARCH_TIMEOUT_MS = 2_000L

    override val type = "catalog-search"

    override fun initial(key: String) = SearchState(pending = null, display = Display.Idle)

    override fun decide(state: SearchState, request: SearchRequest, ctx: RequestContext): Decision<SearchState, Display> =
        when (request) {
            is SearchRequest.Search -> {
                val (call, search) = ctx.call(CatalogSearch, SearchQuery(request.query), SEARCH_TIMEOUT_MS)
                val loading = Display.Loading(request.query)
                val outputs = listOfNotNull(
                    state.pending?.let { Cancel(it.call.key) }, // saves work; staleness is handled by the ref below
                    search,
                )
                accept(SearchState(LatestSearch(request.query, call), loading), loading, *outputs.toTypedArray())
            }
        }

    override fun complete(state: SearchState, completion: Completion<*>, ctx: Context): Step<SearchState, Display> {
        val latest = state.pending ?: return step(state)
        val outcome = completion.safeOutcomeFor(latest.call) ?: return step(state) // an older search: never replaces the display
        val display = when (outcome) {
            is Outcome.Done -> Display.Results(latest.query, outcome.value)
            is Outcome.NotDone -> Display.Failed(latest.query)
        }
        return step(SearchState(pending = null, display = display))
    }
}
