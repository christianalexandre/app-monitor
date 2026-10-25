package appmonitor.desktop.data

import appmonitor.desktop.domain.Breakpoint
import appmonitor.desktop.domain.BreakpointChange
import appmonitor.desktop.domain.BreakpointRepository
import appmonitor.desktop.domain.BreakpointWrite
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Mock
import appmonitor.desktop.domain.MockChange
import appmonitor.desktop.domain.MockRepository
import appmonitor.desktop.domain.MockSource
import appmonitor.desktop.domain.MockWrite
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.storage.MAX_BREAKPOINT_TIMEOUT_MS
import appmonitor.desktop.storage.Storage
import appmonitor.v1.BreakpointRule
import appmonitor.v1.MockRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** The runs of this store. */
fun Storage.runRepository(): RunRepository = StoredRunRepository(this)

/** The traffic of this store. */
fun Storage.trafficRepository(): TrafficRepository = StoredTrafficRepository(this)

/** The mock rules of this store. */
fun Storage.mockRepository(): MockRepository = StoredMockRepository(this)

/** The breakpoints of this store. */
fun Storage.breakpointRepository(): BreakpointRepository = StoredBreakpointRepository(this)

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

internal class StoredMockRepository(
    private val storage: Storage,
) : MockRepository {
    override fun observeMocks(): Flow<List<Mock>> = storage.observeMocks().map { mocks -> mocks.map { it.toDomain() } }

    override suspend fun create(
        appId: String,
        name: String,
        rule: MockRule,
        source: MockSource,
    ): MockWrite = storage.createMock(appId, name, rule, source.toStored()).toDomain()

    override suspend fun update(
        id: Long,
        change: MockChange,
    ): MockWrite = storage.updateMock(id, change.toStored()).toDomain()

    override suspend fun confirm(id: Long): MockWrite = storage.confirmMock(id).toDomain()

    override suspend fun delete(id: Long) = storage.deleteMock(id)
}

internal class StoredBreakpointRepository(
    private val storage: Storage,
) : BreakpointRepository {
    override val maxTimeoutMs: Long = MAX_BREAKPOINT_TIMEOUT_MS

    override fun observeBreakpoints(): Flow<List<Breakpoint>> =
        storage.observeBreakpoints().map { breakpoints -> breakpoints.map { it.toDomain() } }

    override suspend fun create(
        appId: String,
        name: String,
        rule: BreakpointRule,
    ): BreakpointWrite = storage.createBreakpoint(appId, name, rule).toDomain()

    override suspend fun update(
        id: Long,
        change: BreakpointChange,
    ): BreakpointWrite = storage.updateBreakpoint(id, change.toStored()).toDomain()

    override suspend fun delete(id: Long) = storage.deleteBreakpoint(id)
}
