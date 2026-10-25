package appmonitor.desktop.domain

import java.io.File

/** Method, lower-cased host and the URL's path without scheme, query or fragment (SDD 09). */
data class Endpoint(
    val method: String?,
    val host: String?,
    val path: String,
)

/** Duration percentiles by nearest rank, in ms, over the exchanges with both times. */
data class Durations(
    val min: Long,
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val max: Long,
)

/** One endpoint's numbers; [statusClasses] counts exchanges per status class, `1xx` first. */
data class EndpointStats(
    val endpoint: Endpoint,
    val count: Long,
    val failed: Long,
    val errors: Long,
    val requestBytes: Long,
    val responseBytes: Long,
    val statusClasses: List<Long>,
    /** Null when no exchange has both times. */
    val durations: Durations?,
) {
    val errorRate: Double get() = if (count == 0L) 0.0 else errors.toDouble() / count
}

/** The exchanges read, most called endpoint first; [truncated] when a matching one was left past the cap. */
data class EndpointGroups(
    val endpoints: List<EndpointStats>,
    val exchangesRead: Long,
    val truncated: Boolean,
)

/** One endpoint in both runs. */
data class EndpointChange(
    val endpoint: Endpoint,
    val base: EndpointStats,
    val run: EndpointStats,
)

/** Two runs compared per endpoint, as `diff_runs` does (SDD 09). */
data class EndpointDiff(
    val added: List<EndpointStats>,
    val gone: List<EndpointStats>,
    val changed: List<EndpointChange>,
    val unchanged: Int,
    val truncated: Boolean,
)

/** One device of a run, named as its last `Hello` named it. */
data class RunDevice(
    val deviceId: String,
    val name: String,
)

/** One endpoint's numbers per device id; a device that never called it has no entry. */
data class DeviceEndpoint(
    val endpoint: Endpoint,
    val byDevice: Map<String, EndpointStats>,
)

/** A run's endpoints with one column per device (ADR-0140 D); [devices] in the order they joined the run. */
data class DeviceComparison(
    val devices: List<RunDevice>,
    val endpoints: List<DeviceEndpoint>,
    val exchangesRead: Long,
    val truncated: Boolean,
)

/** The exchanges one grouping reads at most, oldest first; `desktop/data`'s tests hold it to the store's. */
const val MAX_GROUPED_EXCHANGES = 100_000L

/** A p50 or p95 known on both runs moved when it changed by at least this many ms and [MIN_MOVE_PERCENT] of the base. */
const val MIN_MOVE_MS = 10L

/** See [MIN_MOVE_MS]. */
const val MIN_MOVE_PERCENT = 20L

/** Per endpoint numbers of the stored exchanges, as `endpoint_stats` and `diff_runs` read them (SDD 10). */
interface BenchmarkRepository {
    /** The exchanges [filter] matches, at most [maxExchanges] of them, grouped per endpoint. */
    suspend fun endpoints(
        filter: ExchangeFilter,
        maxExchanges: Long = MAX_GROUPED_EXCHANGES,
    ): EndpointGroups

    /** Run [runId] against run [baseRunId], [filter] applied to both; its own run id is ignored. */
    suspend fun diff(
        filter: ExchangeFilter,
        baseRunId: Long,
        runId: Long,
        maxExchanges: Long = MAX_GROUPED_EXCHANGES,
    ): EndpointDiff

    /** Run [runId]'s exchanges [filter] matches per endpoint and device; its own run id is ignored. */
    suspend fun byDevice(
        filter: ExchangeFilter,
        runId: Long,
        maxExchanges: Long = MAX_GROUPED_EXCHANGES,
    ): DeviceComparison
}

/** What a HAR export wrote, or why it wrote nothing. */
sealed interface HarWrite {
    /** [more] when matching exchanges were left past the first [maxEntries]. */
    data class Written(
        val entries: Int,
        val more: Boolean,
        val maxEntries: Long,
        val connections: Int,
    ) : HarWrite

    data class NotWritten(
        val reason: String,
    ) : HarWrite
}

/** `export_har`'s log of what a filter matches, written to a file the developer picked (SDD 10). */
interface HarRepository {
    suspend fun write(
        filter: ExchangeFilter,
        file: File,
    ): HarWrite
}
