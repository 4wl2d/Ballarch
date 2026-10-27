package ballarch.examples.shop.payments.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ballarch.examples.shop.payments.ChargeRequest
import ballarch.examples.shop.payments.ChargeResult
import ballarch.kernel.AdapterPort
import ballarch.kernel.EffectClass
import ballarch.runtime.Adapter
import ballarch.runtime.AdapterResult
import ballarch.testkit.AdapterConformance
import ballarch.testkit.ControllableDestination
import ballarch.testkit.DestinationFate
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fake payment provider on a real local HTTP server, deduplicating by Idempotency-Key. */
class FakeProvider(private val lateMillis: Long) : ControllableDestination {
    private val received = ConcurrentHashMap<String, AtomicInteger>()
    private val applied = ConcurrentHashMap<String, AtomicInteger>()
    private val keys = ConcurrentHashMap<String, String>()
    private val appliedKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var fate = DestinationFate.Answer
    private var server: HttpServer? = null
    val port: Int

    init {
        port = start(0)
    }

    private fun start(onPort: Int): Int {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", onPort), 0)
        s.executor = Executors.newCachedThreadPool()
        s.createContext("/charges") { ex -> handle(ex) }
        s.start()
        server = s
        return s.address.port
    }

    override fun behave(fate: DestinationFate) {
        this.fate = fate
        if (fate == DestinationFate.Unreachable) {
            server?.stop(0)
            server = null
        } else if (server == null) {
            start(port)
        }
    }

    override fun received(key: String) = received[key]?.get() ?: 0
    override fun applied(key: String) = applied[key]?.get() ?: 0
    override fun idempotencyKeySeen(key: String) = keys[key]

    private fun apply(orderId: String, idempotencyKey: String?) {
        if (idempotencyKey != null && !appliedKeys.add(idempotencyKey)) return // deduplicated
        applied.computeIfAbsent(orderId) { AtomicInteger() }.incrementAndGet()
    }

    private fun handle(ex: HttpExchange) {
        val form = ex.requestBody.readAllBytes().decodeToString().split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }
        val orderId = form.getValue("orderId")
        val key = ex.requestHeaders.getFirst("Idempotency-Key")
        received.computeIfAbsent(orderId) { AtomicInteger() }.incrementAndGet()
        key?.let { keys[orderId] = it }
        fun respond(status: Int, body: String) {
            val bytes = body.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        when (fate) {
            DestinationFate.Answer -> { apply(orderId, key); respond(200, "charged:ref-$orderId") }
            DestinationFate.AnswerLate -> { apply(orderId, key); Thread.sleep(lateMillis); respond(200, "charged:ref-$orderId") }
            DestinationFate.ApplyThenDisconnect -> { apply(orderId, key); ex.close() }
            DestinationFate.ApplyThenServerError -> { apply(orderId, key); respond(500, "internal error") }
            DestinationFate.RejectWithoutApplying -> respond(422, "invalid amount")
            DestinationFate.Unreachable -> error("unreachable destination received a request")
        }
    }

    fun stop() {
        server?.stop(0)
    }
}

class HttpAdapterConformanceTest {
    private val provider = FakeProvider(lateMillis = 700)
    private val base = URI("http://127.0.0.1:${provider.port}")

    @AfterTest
    fun stop() {
        provider.stop()
    }

    private fun run(port: AdapterPort<ChargeRequest, ChargeResult>, adapter: Adapter<ChargeRequest, ChargeResult>) =
        AdapterConformance.check(port, adapter, provider, request = { ChargeRequest(it, 100) }, deadlineMillis = 300, settleMillis = 700)

    private val nonIdempotent = AdapterPort<ChargeRequest, ChargeResult>("provider.charge", EffectClass.NonIdempotent)
    private val idempotent = AdapterPort<ChargeRequest, ChargeResult>("provider.charge", EffectClass.Idempotent)

    /** Retries failed requests itself: violates "adapters never retry". */
    private fun retrying(inner: Adapter<ChargeRequest, ChargeResult>) = Adapter<ChargeRequest, ChargeResult> { inv, done ->
        fun attempt(n: Int) {
            inner.execute(inv) { r -> if (r is AdapterResult.Failed && n < 3) attempt(n + 1) else done(r) }
        }
        attempt(1)
        null
    }

    /** Treats every failure as "not applied": violates honest outcomes. */
    private fun optimistic(inner: Adapter<ChargeRequest, ChargeResult>) = Adapter<ChargeRequest, ChargeResult> { inv, done ->
        inner.execute(inv) { r -> done(if (r is AdapterResult.Failed) AdapterResult.NotApplied("assumed not applied") else r) }
    }

    /** Sends a key of its own on every attempt instead of the supplied one: violates "pass the supplied key". */
    private fun ownKey(inner: Adapter<ChargeRequest, ChargeResult>) = Adapter<ChargeRequest, ChargeResult> { inv, done ->
        inner.execute(inv.copy(idempotencyKey = UUID.randomUUID().toString()), done)
    }

    /** Reports a provisional failure before the real result: violates "report once". */
    private fun chatty(inner: Adapter<ChargeRequest, ChargeResult>) = Adapter<ChargeRequest, ChargeResult> { inv, done ->
        done(AdapterResult.Failed("provisional"))
        inner.execute(inv, done)
    }

    @Test
    fun `the HTTP adapter satisfies the adapter contract, and broken adapters are caught`() {
        val cases = linkedMapOf(
            "HttpProviderAdapters, NonIdempotent port, no key" to run(nonIdempotent, HttpProviderAdapters(base, sendIdempotencyKey = false).charge),
            "HttpProviderAdapters, Idempotent port, supplied key" to run(idempotent, HttpProviderAdapters(base, sendIdempotencyKey = true).charge),
            "broken: retries internally" to run(nonIdempotent, retrying(HttpProviderAdapters(base, sendIdempotencyKey = false).charge)),
            "broken: failure reported as not applied" to run(nonIdempotent, optimistic(HttpProviderAdapters(base, sendIdempotencyKey = false).charge)),
            "broken: reports twice" to run(nonIdempotent, chatty(HttpProviderAdapters(base, sendIdempotencyKey = false).charge)),
            "broken: Idempotent port without key" to run(idempotent, HttpProviderAdapters(base, sendIdempotencyKey = false).charge),
            "broken: a key of its own per attempt" to run(idempotent, ownKey(HttpProviderAdapters(base, sendIdempotencyKey = true).charge)),
        )
        val rows = cases.map { (name, reports) ->
            val violated = reports.flatMap { r -> r.violations.map { it.substringBefore(':') } }.toSortedSet()
            "| $name | " + reports.joinToString(" | ") { r -> r.results.joinToString("+").ifEmpty { "—" } + if (r.violations.isEmpty()) "" else " ✗" } +
                " | ${violated.joinToString(", ").ifEmpty { "none" }} |"
        }
        val header = "| Adapter | " + DestinationFate.entries.joinToString(" | ") + " | Violated checks |\n|---|" + "---|".repeat(DestinationFate.entries.size) + "---|"
        val text = "# Adapter conformance\n\nGenerated by `HttpAdapterConformanceTest` (`./gradlew :examples:shop:payments-http:test`). " +
            "Cells show what the adapter reported per destination fate; ✗ marks a violated check. Checks: C1 one report, C2 no retry, " +
            "C3 non-application only when nothing was applied, C4 Ok only when received, C5 the supplied idempotency key, and no second application with it.\n\n" +
            header + "\n" + rows.joinToString("\n") + "\n"
        System.getProperty("ballarch.results")?.let { File(it).mkdirs(); File(it, "adapter-conformance.md").writeText(text) }
        println(text)

        fun violated(name: String) = cases.getValue(name).flatMap { r -> r.violations.map { it.substringBefore(':') } }.toSet()
        assertEquals(emptySet(), violated("HttpProviderAdapters, NonIdempotent port, no key"))
        assertEquals(emptySet(), violated("HttpProviderAdapters, Idempotent port, supplied key"))
        assertTrue("C2" in violated("broken: retries internally"))
        assertTrue("C3" in violated("broken: failure reported as not applied"))
        assertTrue("C1" in violated("broken: reports twice"))
        assertTrue("C5" in violated("broken: Idempotent port without key"))
        assertTrue("C5" in violated("broken: a key of its own per attempt"))
    }
}
