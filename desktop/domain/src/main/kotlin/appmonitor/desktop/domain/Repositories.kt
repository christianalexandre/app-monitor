package appmonitor.desktop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The runs, and the one new sessions join (SDD 09). */
interface RunRepository {
    /** The id of the run being recorded, null when none (ADR-0140 B). */
    val recording: StateFlow<Long?>

    /** Every run, newest first, again after each change. */
    fun observeRuns(): Flow<List<Run>>

    /** Starts recording into a new run; returns its id, null when it could not be written. */
    suspend fun record(): Long?

    /** Ends the recording; what started during it is still stored until it ends. */
    suspend fun stop()

    /** A blank [name] clears it. */
    suspend fun rename(
        id: Long,
        name: String,
    )
}

/** The sessions and requests the devices sent: the live list, and the runs as stored (ADR-0140). */
interface TrafficRepository {
    /** Writes lost since the store opened, again after each change; starts at the current value. */
    val health: Flow<StoreHealth>

    /** The sessions of run [runId], or of every run when null, again after each change. */
    fun observeSessions(runId: Long?): Flow<List<Session>>

    /** The first [limit] exchanges [filter] matches in arrival order, again after each change. */
    fun observeExchanges(
        filter: ExchangeFilter,
        limit: Long = PAGE_SIZE,
    ): Flow<List<Exchange>>

    /** One exchange with its bodies and its first [frameLimit] frames; null when it is no longer stored. */
    suspend fun exchange(
        id: Long,
        frameLimit: Long = PAGE_SIZE,
    ): HttpExchange?

    /** Empties the live list (ADR-0140 A); the sessions still connected stay, stored runs are untouched. */
    fun clear()

    /**
     * Stores the live exchanges among [exchangeIds] in "Saved" (ADR-0140 C); returns each one's stored id by its live id.
     * Stored ids, and live ones gone from the list or not written, are left out.
     */
    suspend fun save(exchangeIds: Collection<Long>): Map<Long, Long>

    companion object {
        const val PAGE_SIZE = 200L
    }
}
