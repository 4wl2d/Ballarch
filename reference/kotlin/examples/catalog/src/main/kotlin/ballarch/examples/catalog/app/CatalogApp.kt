package ballarch.examples.catalog.app

import ballarch.examples.catalog.CatalogSearch
import ballarch.examples.catalog.CatalogSearchBall
import ballarch.examples.catalog.Display
import ballarch.examples.catalog.Product
import ballarch.examples.catalog.SearchQuery
import ballarch.runtime.Adapter
import ballarch.runtime.Composition
import ballarch.runtime.RetryPolicy
import ballarch.runtime.Runtime

/**
 * Composition root of the search screen. Searches have no effect, so the runtime
 * may retry them: an attempt with no answer after 500 ms is stopped and, within
 * the search's deadline, made again.
 */
fun catalogComposition(search: Adapter<SearchQuery, List<Product>>): Composition =
    Composition()
        .ball(CatalogSearchBall)
        .adapter(CatalogSearch, search, RetryPolicy(maxAttempts = 3, backoffMillis = 100, attemptTimeoutMillis = 500))

/**
 * Shows the display of the search screen [screen]: [show] receives the display
 * committed now, then the one of each later commit, on the runtime's loop. It
 * reads committed state, typed by the Ball, and commits nothing (R12).
 */
fun showSearch(runtime: Runtime, screen: String, show: (Display) -> Unit) {
    runtime.watch(CatalogSearchBall, screen) { show(it.state.display) }
}
