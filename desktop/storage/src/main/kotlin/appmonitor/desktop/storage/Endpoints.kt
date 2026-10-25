package appmonitor.desktop.storage

import kotlin.math.ceil

/** The exchanges read by [groupByEndpoint], most called first; [truncated] when a matching one was left past the cap. */
class EndpointGroups(
    val endpoints: List<EndpointTally>,
    val exchangesRead: Long,
    val truncated: Boolean,
)

/**
 * Method, lower-cased host and the URL's path without scheme, query or fragment; `/` when it has none, `:port` for a
 * connection (SDD 09).
 */
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
            val path =
                when {
                    exchange.isConnection -> ":" + hostAndPath.substringAfterLast(':', "")
                    slash < 0 -> "/"
                    else -> hostAndPath.substring(slash)
                }
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

/** One endpoint's numbers: an exchange in flight counts in [count] only; an error is [StoredExchange.isError]. */
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
        if (exchange.isError) errors++
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
): EndpointGroups = groupByEndpoint(maxExchanges) { afterId, limit -> searchExchangeSizes(filter, afterId, limit) }

/** [Storage.groupByEndpoint] over the live list. */
suspend fun LiveTraffic.groupByEndpoint(
    filter: ExchangeFilter,
    maxExchanges: Long = MAX_GROUPED_EXCHANGES,
): EndpointGroups = groupByEndpoint(maxExchanges) { afterId, limit -> searchExchangeSizes(filter, afterId, limit) }

private suspend fun groupByEndpoint(
    maxExchanges: Long,
    page: suspend (afterId: Long, limit: Long) -> List<ExchangeSizes>,
): EndpointGroups {
    val endpoints = LinkedHashMap<Endpoint, EndpointTally>()
    val (read, truncated) =
        readPages(maxExchanges, page) { row -> Endpoint.of(row.exchange).let { endpoints.getOrPut(it) { EndpointTally(it) }.add(row) } }
    return EndpointGroups(endpoints.values.sortedWith(MOST_CALLED), read, truncated)
}

/** Reads up to [maxExchanges] rows oldest first by page, each through [each]; the rows read, and whether one was left. */
private suspend fun readPages(
    maxExchanges: Long,
    page: suspend (afterId: Long, limit: Long) -> List<ExchangeSizes>,
    each: suspend (ExchangeSizes) -> Unit,
): Pair<Long, Boolean> {
    var read = 0L
    var afterId = 0L
    while (true) {
        if (read == maxExchanges) return read to page(afterId, 1).isNotEmpty()
        val rows = page(afterId, minOf(Storage.PAGE_SIZE, maxExchanges - read))
        rows.forEach { each(it) }
        read += rows.size
        if (rows.isEmpty()) return read to false
        afterId = rows.last().exchange.id
    }
}

private val MOST_CALLED = compareByDescending<EndpointTally> { it.count }.thenBy { it.endpoint.toString() }

/** One device of a run, named as its last `Hello` named it. */
data class RunDevice(
    val deviceId: String,
    val name: String,
)

/** One endpoint's numbers per device id; a device that never called it has no entry. */
class DeviceTallies(
    val endpoint: Endpoint,
    val byDevice: Map<String, EndpointTally>,
) {
    val count: Long get() = byDevice.values.sumOf { it.count }
}

/** A run's exchanges per endpoint and device; [devices] in the order they first joined the run. */
class DeviceGroups(
    val devices: List<RunDevice>,
    val endpoints: List<DeviceTallies>,
    val exchangesRead: Long,
    val truncated: Boolean,
)

/** [groupByEndpoint] of run [runId] split per device (ADR-0140 D); [filter]'s own run id is ignored. */
suspend fun Storage.groupByEndpointAndDevice(
    runId: Long,
    filter: ExchangeFilter,
    maxExchanges: Long = MAX_GROUPED_EXCHANGES,
): DeviceGroups {
    val inRun = filter.copy(runId = runId)
    var sessions = sessionDevices(runId)
    val endpoints = LinkedHashMap<Endpoint, LinkedHashMap<String, EndpointTally>>()
    val (read, truncated) =
        readPages(maxExchanges, { afterId, limit -> searchExchangeSizes(inRun, afterId, limit) }) { row ->
            // A session that joined the run after the devices were read.
            val device =
                sessions[row.exchange.sessionId]
                    ?: sessionDevices(runId).also { sessions = it }[row.exchange.sessionId]
                    ?: return@readPages
            val endpoint = Endpoint.of(row.exchange)
            endpoints.getOrPut(endpoint) { LinkedHashMap() }.getOrPut(device.deviceId) { EndpointTally(endpoint) }.add(row)
        }
    val tallies =
        endpoints
            .map { (endpoint, byDevice) -> DeviceTallies(endpoint, byDevice) }
            .sortedWith(compareByDescending<DeviceTallies> { it.count }.thenBy { it.endpoint.toString() })
    return DeviceGroups(sessions.values.distinct(), tallies, read, truncated)
}

/** Nearest rank: the smallest value with at least [percent] % of the list at or below it; the list is sorted. */
fun List<Long>.percentile(percent: Int): Long = this[(ceil(size * percent / 100.0).toInt() - 1).coerceIn(indices)]
