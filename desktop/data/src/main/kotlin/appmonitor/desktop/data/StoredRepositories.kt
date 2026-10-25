package appmonitor.desktop.data

import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** The runs of this store. */
fun Storage.runRepository(): RunRepository = StoredRunRepository(this)

/** The traffic of this store. */
fun Storage.trafficRepository(): TrafficRepository = StoredTrafficRepository(this)

internal class StoredRunRepository(
    private val storage: Storage,
) : RunRepository {
    override val currentRun: StateFlow<Long> get() = storage.currentRun

    override fun observeRuns(): Flow<List<Run>> = storage.observeRuns().map { runs -> runs.map { it.toDomain() } }

    override suspend fun newRun(): Long = storage.newRun()

    override suspend fun rename(
        id: Long,
        name: String,
    ) = storage.rename(id, name)
}

internal class StoredTrafficRepository(
    private val storage: Storage,
) : TrafficRepository {
    override val health: Flow<StoreHealth> = storage.health.map { it.toDomain() }

    override fun observeSessions(runId: Long?): Flow<List<Session>> =
        storage.observeSessions(runId).map { sessions -> sessions.map { it.toDomain() } }

    override fun observeExchanges(
        filter: ExchangeFilter,
        limit: Long,
    ): Flow<List<Exchange>> = storage.observeExchanges(filter.toStored(), limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun exchange(
        id: Long,
        frameLimit: Long,
    ): HttpExchange? = storage.exchange(id)?.toDomain(storage.frames(id, limit = frameLimit))
}
