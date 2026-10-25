package appmonitor.desktop.data

import appmonitor.desktop.domain.BenchmarkRepository
import appmonitor.desktop.domain.Breakpoint
import appmonitor.desktop.domain.BreakpointChange
import appmonitor.desktop.domain.BreakpointRepository
import appmonitor.desktop.domain.BreakpointWrite
import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.EndpointDiff
import appmonitor.desktop.domain.EndpointGroups
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeDetail
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Issue
import appmonitor.desktop.domain.IssueChange
import appmonitor.desktop.domain.IssueDetail
import appmonitor.desktop.domain.IssueFilter
import appmonitor.desktop.domain.IssueRepository
import appmonitor.desktop.domain.IssueSource
import appmonitor.desktop.domain.IssueStatus
import appmonitor.desktop.domain.IssueWrite
import appmonitor.desktop.domain.Mock
import appmonitor.desktop.domain.MockChange
import appmonitor.desktop.domain.MockRepository
import appmonitor.desktop.domain.MockSource
import appmonitor.desktop.domain.MockWrite
import appmonitor.desktop.domain.Retention
import appmonitor.desktop.domain.RetentionRepository
import appmonitor.desktop.domain.RetentionState
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.RunSummary
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.SettingRepository
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.domain.TrafficQueryRepository
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.MAX_BREAKPOINT_TIMEOUT_MS
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.diffEndpoints
import appmonitor.desktop.storage.groupByEndpoint
import appmonitor.v1.BreakpointRule
import appmonitor.v1.MockRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** The runs of this store. */
fun Storage.runRepository(): RunRepository = StoredRunRepository(this)

/** The traffic of this store. */
fun Storage.trafficRepository(): TrafficRepository = StoredTrafficRepository(this)

/** The live list when a read names no run or stored session or exchange, else this store (SDD 09, ADR-0141). */
fun Storage.trafficRepository(live: LiveTraffic): TrafficRepository = LiveTrafficRepository(this, live)

/** The mock rules of this store. */
fun Storage.mockRepository(): MockRepository = StoredMockRepository(this)

/** The breakpoints of this store. */
fun Storage.breakpointRepository(): BreakpointRepository = StoredBreakpointRepository(this)

/** The issues of this store. */
fun Storage.issueRepository(): IssueRepository = StoredIssueRepository(this)

/** [issueRepository] saving each live exchange an issue links first (ADR-0140 C). */
fun Storage.issueRepository(live: LiveTraffic): IssueRepository = LiveIssueRepository(this, live)

/** The paged reads of this store, as the MCP tools make them. */
fun Storage.trafficQueryRepository(): TrafficQueryRepository = StoredTrafficQueryRepository(this)

/** [trafficQueryRepository] reading [live] as [trafficRepository] does. */
fun Storage.trafficQueryRepository(live: LiveTraffic): TrafficQueryRepository = LiveTrafficQueryRepository(this, live)

/** The per endpoint numbers of this store. */
fun Storage.benchmarkRepository(): BenchmarkRepository = StoredBenchmarkRepository(this)

/** [benchmarkRepository] reading [live] as [trafficRepository] does. */
fun Storage.benchmarkRepository(live: LiveTraffic): BenchmarkRepository = LiveBenchmarkRepository(this, live)

/** The `settings` keys of this store. */
fun Storage.settingRepository(): SettingRepository = StoredSettingRepository(this)

/** The retention of this store. */
fun Storage.retentionRepository(): RetentionRepository = StoredRetentionRepository(this)

internal class StoredRunRepository(
    private val storage: Storage,
) : RunRepository {
    override val recording: StateFlow<Long?> get() = storage.recording

    override fun observeRuns(): Flow<List<Run>> = storage.observeRuns().map { runs -> runs.map { it.toDomain() } }

    override suspend fun record(): Long? = storage.record()

    override suspend fun stop() = storage.stop()

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

    // The store holds no live list.
    override fun clear() = Unit

    override suspend fun save(exchangeIds: Collection<Long>): Map<Long, Long> = emptyMap()
}

internal class StoredMockRepository(
    private val storage: Storage,
) : MockRepository {
    override fun observeMocks(): Flow<List<Mock>> = storage.observeMocks().map { mocks -> mocks.map { it.toDomain() } }

    override suspend fun mocks(): List<Mock> = storage.mocks().map { it.toDomain() }

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

internal class StoredBenchmarkRepository(
    private val storage: Storage,
) : BenchmarkRepository {
    override suspend fun endpoints(
        filter: ExchangeFilter,
        maxExchanges: Long,
    ): EndpointGroups = storage.groupByEndpoint(filter.toStored(), maxExchanges).toDomain()

    override suspend fun diff(
        filter: ExchangeFilter,
        baseRunId: Long,
        runId: Long,
        maxExchanges: Long,
    ): EndpointDiff {
        val stored = filter.toStored()
        return diffEndpoints(
            storage.groupByEndpoint(stored.copy(runId = baseRunId), maxExchanges),
            storage.groupByEndpoint(stored.copy(runId = runId), maxExchanges),
        ).toDomain()
    }
}

internal class StoredTrafficQueryRepository(
    private val storage: Storage,
) : TrafficQueryRepository {
    override val isClosing: Boolean get() = storage.isClosing

    override val health: StoreHealth get() = storage.health.value.toDomain()

    override suspend fun runs(): List<Run> = storage.runs().map { it.toDomain() }

    override suspend fun runSummaries(
        beforeId: Long,
        limit: Long,
    ): List<RunSummary> = storage.runSummaries(beforeId, limit).map { it.toDomain() }

    override suspend fun search(
        filter: ExchangeFilter,
        afterId: Long,
        limit: Long,
    ): List<Exchange> = storage.searchExchanges(filter.toStored(), afterId, limit).map { it.toDomain() }

    override suspend fun replayOriginals(exchanges: List<Exchange>): Map<Long, Long> =
        storage.replayOriginals(exchanges.map { it.toStored() })

    override suspend fun detail(id: Long): ExchangeDetail? = storage.exchange(id)?.toDomain()

    override suspend fun session(id: Long): Session? = storage.session(id)?.toDomain()

    override suspend fun frames(
        exchangeId: Long,
        afterId: Long,
        limit: Long,
    ): List<CapturedFrame> = storage.frames(exchangeId, afterId, limit).map { it.toDomain() }
}

internal class StoredIssueRepository(
    private val storage: Storage,
) : IssueRepository {
    override fun observeIssues(): Flow<List<Issue>> = storage.observeIssues().map { issues -> issues.map { it.toDomain() } }

    override fun observeTags(): Flow<List<String>> = storage.observeIssueTags()

    override suspend fun exchanges(id: Long): List<Exchange> = storage.issueExchanges(id).map { it.toDomain() }

    override suspend fun page(
        filter: IssueFilter,
        beforeId: Long,
        limit: Long,
    ): List<IssueDetail> = storage.issuePage(filter.toStored(), beforeId, limit).map { it.toDomain() }

    override suspend fun create(
        title: String,
        note: String,
        tags: Collection<String>,
        exchangeIds: Collection<Long>,
        source: IssueSource,
        status: IssueStatus,
    ): IssueWrite = storage.createIssue(title, note, tags, exchangeIds, source.toStored(), status.toStored()).toDomain()

    override suspend fun update(
        id: Long,
        change: IssueChange,
    ): IssueWrite = storage.updateIssue(id, change.toStored()).toDomain()

    override suspend fun delete(id: Long) = storage.deleteIssue(id)
}

internal class StoredSettingRepository(
    private val storage: Storage,
) : SettingRepository {
    override suspend fun setting(key: String): String? = storage.setting(key)

    override suspend fun setSetting(
        key: String,
        value: String,
    ) = storage.setSetting(key, value)
}

internal class StoredRetentionRepository(
    private val storage: Storage,
) : RetentionRepository {
    override val retention: Flow<Retention> = storage.retention.map { it.toDomain() }

    override val state: Flow<RetentionState> = storage.retentionStatus.map { it.toDomain() }

    override suspend fun setRetention(retention: Retention) = storage.setRetention(retention.toStored())
}
