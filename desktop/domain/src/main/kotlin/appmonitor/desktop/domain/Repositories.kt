package appmonitor.desktop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The runs, and the one new sessions join (SDD 09). */
interface RunRepository {
    /** The id of the run new sessions join. */
    val currentRun: StateFlow<Long>

    /** Every run, newest first, again after each change. */
    fun observeRuns(): Flow<List<Run>>

    /** Ends the current run and starts a new one; returns its id. */
    suspend fun newRun(): Long

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

    companion object {
        const val PAGE_SIZE = 200L
    }
}
