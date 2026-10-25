package appmonitor.desktop.domain

import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived

/** One run with its exchange count and the sessions that joined it, as `list_runs` pages them (SDD 10). */
data class RunSummary(
    val run: Run,
    val exchangeCount: Long,
    val sessions: List<SessionSummary>,
)

/** A session with its device and app as the store last saw them: the last `Hello` of each, not [session]'s own. */
data class SessionSummary(
    val session: Session,
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val osVersion: String,
    val appId: String,
    val appVersion: String,
    val sdkName: String,
)

/**
 * One exchange with its events as received, bodies restored; [request] and [response] are null until that event is
 * stored. [mockRuleName] is null once its rule is gone, [replayOfId] once its original is (ADR-0121 D.1).
 */
data class ExchangeDetail(
    val exchange: Exchange,
    val request: RequestStarted?,
    val response: ResponseReceived?,
    val mockRuleName: String? = null,
    val replayOfId: Long? = null,
) {
    /** The bytes a connection moved, null for any other layer (ADR-0103). */
    val connectionBytes: ConnectionBytes?
        get() = if (exchange.isConnection) ConnectionBytes(sent = response?.sent_size, received = response?.body_size) else null
}

/** The stored traffic a page at a time, as the MCP tools read it (SDD 10, ADR-0138). */
interface TrafficQueryRepository {
    /** True once the store began closing: a read since may have stopped early or read nothing. */
    val isClosing: Boolean

    /** Every run, oldest first. */
    suspend fun runs(): List<Run>

    /** Runs newest first, below [beforeId]; the next page passes the last run's id. */
    suspend fun runSummaries(
        beforeId: Long,
        limit: Long,
    ): List<RunSummary>

    /** The exchanges [filter] matches in arrival order, after [afterId]. */
    suspend fun search(
        filter: ExchangeFilter,
        afterId: Long,
        limit: Long,
    ): List<Exchange>

    /** The original's exchange id of each replay in [exchanges] whose original is still stored, by the replay's id. */
    suspend fun replayOriginals(exchanges: List<Exchange>): Map<Long, Long>

    /** Null when exchange [id] is not stored. */
    suspend fun detail(id: Long): ExchangeDetail?

    /** An exchange's frames in the order the device sent them, after frame [afterId]. */
    suspend fun frames(
        exchangeId: Long,
        afterId: Long,
        limit: Long,
    ): List<CapturedFrame>
}
