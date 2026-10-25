package appmonitor.desktop.storage

import kotlin.math.ceil

/** The exchanges read by [groupByEndpoint], most called first; [truncated] when a matching one was left past the cap. */
class EndpointGroups(
    val endpoints: List<EndpointTally>,
    val exchangesRead: Long,
    val truncated: Boolean,
)

/** Method, lower-cased host and the URL's path without scheme, query or fragment; `/` when it has none (SDD 09). */
data class Endpoint(
    val method: String?,
    val host: String?,
    val path: String,
) {
    companion object {
        fun of(exchange: StoredExchange): Endpoint {
            val url = exchange.url.orEmpty()
            val hostAndPath = url.substringAfter("://", url).substringBefore('?').substringBefore('#')
            val slash = hostAndPath.indexOf('/')
            val path = if (slash < 0) "/" else hostAndPath.substring(slash)
            return Endpoint(exchange.method, exchange.host?.lowercase(), path)
        }
    }
}

/** Duration percentiles by nearest rank, in ms, over the exchanges with both times. */
data class Durations(
    val min: Long,
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val max: Long,
)

/** One endpoint's numbers: an exchange in flight counts in [count] only; an error is status 400 and up or failed. */
class EndpointTally(
    val endpoint: Endpoint,
) {
    var count = 0L
        private set
    var failed = 0L
        private set
    var errors = 0L
        private set
    var requestBytes = 0L
        private set
    var responseBytes = 0L
        private set
    private val classes = LongArray(5)
    private val durations = ArrayList<Long>()

    val errorRate: Double get() = if (count == 0L) 0.0 else errors.toDouble() / count

    /** Exchanges per status class, `1xx` first. */
    val statusClasses: List<Long> get() = classes.toList()

    /** Null when no exchange has both times. */
    val durationStats: Durations? by lazy {
        val sorted = durations.sorted()
        if (sorted.isEmpty()) {
            null
        } else {
            Durations(sorted.first(), sorted.percentile(50), sorted.percentile(95), sorted.percentile(99), sorted.last())
        }
    }

    internal fun add(row: ExchangeSizes) {
        val exchange = row.exchange
        count++
        exchange.statusCode?.let { status -> if (status in 100..599) classes[status / 100 - 1]++ }
        if (exchange.failed) failed++
        if (exchange.failed || (exchange.statusCode ?: 0) >= 400) errors++
        val start = exchange.startedAtMs
        val end = exchange.endedAtMs
        if (start != null && end != null) durations += end - start
        requestBytes += row.requestBytes
        responseBytes += row.responseBytes
    }
}

/** At most this many exchanges are read by one [groupByEndpoint]. */
const val MAX_GROUPED_EXCHANGES = 100_000L

/** Reads up to [maxExchanges] exchanges [filter] matches, oldest first by page, and groups them per [Endpoint]. */
suspend fun Storage.groupByEndpoint(
    filter: ExchangeFilter,
    maxExchanges: Long = MAX_GROUPED_EXCHANGES,
): EndpointGroups {
    val endpoints = LinkedHashMap<Endpoint, EndpointTally>()
    var read = 0L
    var afterId = 0L
    var truncated = false
    while (true) {
        if (read == maxExchanges) {
            truncated = searchExchangeSizes(filter, afterId, 1).isNotEmpty()
            break
        }
        val page = searchExchangeSizes(filter, afterId, minOf(Storage.PAGE_SIZE, maxExchanges - read))
        page.forEach { row -> Endpoint.of(row.exchange).let { endpoints.getOrPut(it) { EndpointTally(it) }.add(row) } }
        read += page.size
        if (page.isEmpty()) break
        afterId = page.last().exchange.id
    }
    val sorted = endpoints.values.sortedWith(compareByDescending<EndpointTally> { it.count }.thenBy { it.endpoint.toString() })
    return EndpointGroups(sorted, read, truncated)
}

/** Nearest rank: the smallest value with at least [percent] % of the list at or below it; the list is sorted. */
fun List<Long>.percentile(percent: Int): Long = this[(ceil(size * percent / 100.0).toInt() - 1).coerceIn(indices)]
