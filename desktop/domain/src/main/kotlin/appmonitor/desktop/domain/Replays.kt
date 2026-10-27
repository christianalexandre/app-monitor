package appmonitor.desktop.domain

import kotlinx.coroutines.flow.StateFlow

/** What "Send again" did; the device runs the call later, or answers with a refusal (ADR-0111 D.1, ADR-0120). */
enum class ReplaySent {
    SENT,

    /** The exchange's session is over: a replay goes through the app, so only a live one can take it. */
    SESSION_ENDED,

    /** The device did not accept `interaction`. */
    NOT_TAKEN,
}

/** A request of [sessionId] (the server's session id) the desktop asked a device to send again. */
data class ReplayKey(
    val sessionId: String,
    val requestId: String,
)

/** Asks a connected device to send a request again, and keeps what it refused until its session ends. */
interface ReplayRepository {
    /** The device's reason, by the request it did not send again; a new [send] of the same request clears it. */
    val refused: StateFlow<Map<ReplayKey, String>>

    /** Asks [sessionId]'s device to send [requestId] again. */
    fun send(
        sessionId: String,
        requestId: String,
    ): ReplaySent
}

/** One stored request as something to run elsewhere. */
interface ExportRepository {
    /** `to_curl`'s command for exchange [id], or why there is none (SDD 10). */
    suspend fun curl(id: Long): Result<String>
}

/** A replay an agent asked, for the developer to send or refuse (ADR-0121). [sessionId] is the server's session id. */
data class AskedReplay(
    val exchangeId: Long,
    val sessionId: String,
    val requestId: String,
    val method: String?,
    val url: String?,
    val deviceName: String,
    val appId: String,
)

/** The replays agents asked that wait for the developer, until answered or their session ends (ADR-0121 A.1). */
interface AskedReplayRepository {
    /** Oldest first. */
    val asked: StateFlow<List<AskedReplay>>

    /** Sends [replay] when [send], else drops it; null when it no longer waits. */
    fun answer(
        replay: AskedReplay,
        send: Boolean,
    ): ReplaySent?
}
