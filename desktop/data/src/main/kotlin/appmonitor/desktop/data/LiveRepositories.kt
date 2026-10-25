package appmonitor.desktop.data

import appmonitor.desktop.domain.BenchmarkRepository
import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.EndpointGroups
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeDetail
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.IssueChange
import appmonitor.desktop.domain.IssueRepository
import appmonitor.desktop.domain.IssueSource
import appmonitor.desktop.domain.IssueStatus
import appmonitor.desktop.domain.IssueWrite
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.domain.TrafficQueryRepository
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.groupByEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import appmonitor.desktop.storage.ExchangeDetail as StoredExchangeDetail

// ADR-0141: a session id says where it lives; else a run is stored, no run is live.
private val ExchangeFilter.isLive: Boolean get() = sessionId?.let(LiveTraffic::isLive) ?: (runId == null)

// The live list keeps the device's rule id only; its name is the store's.
private suspend fun Storage.named(detail: StoredExchangeDetail): StoredExchangeDetail {
    val ruleId = detail.exchange.mockRuleId ?: return detail
    return detail.copy(mockRuleName = mocks().firstOrNull { it.id == ruleId }?.name)
}

internal class LiveTrafficRepository(
    private val storage: Storage,
    private val live: LiveTraffic,
    private val stored: TrafficRepository = StoredTrafficRepository(storage),
) : TrafficRepository {
    override val health: Flow<StoreHealth> get() = stored.health

    override fun observeSessions(runId: Long?): Flow<List<Session>> =
        if (runId == null) live.observeSessions().map { sessions -> sessions.map { it.toDomain() } } else stored.observeSessions(runId)

    override fun observeExchanges(
        filter: ExchangeFilter,
        limit: Long,
    ): Flow<List<Exchange>> =
        if (filter.isLive) {
            live.observeExchanges(filter.toStored(), limit).map { rows -> rows.map { it.toDomain() } }
        } else {
            stored.observeExchanges(filter, limit)
        }

    override fun observeHosts(
        runId: Long?,
        sessionId: Long?,
    ): Flow<List<String>> =
        if (ExchangeFilter(
                runId = runId,
                sessionId = sessionId,
            ).isLive
        ) {
            live.observeHosts(sessionId)
        } else {
            stored.observeHosts(runId, sessionId)
        }

    override suspend fun exchange(
        id: Long,
        frameLimit: Long,
    ): HttpExchange? =
        if (LiveTraffic.isLive(id)) {
            live.exchange(id)?.let { storage.named(it) }?.toDomain(live.frames(id, limit = frameLimit))
        } else {
            stored.exchange(id, frameLimit)
        }

    override fun clear() = live.clear()

    override suspend fun save(exchangeIds: Collection<Long>): Map<Long, Long> =
        storage.save(live.copies(exchangeIds.filter(LiveTraffic::isLive)))
}

// A live id still in the list is saved first; one that is not reaches the store as is and comes back unknown.
internal class LiveIssueRepository(
    private val storage: Storage,
    private val live: LiveTraffic,
    private val stored: IssueRepository = StoredIssueRepository(storage),
) : IssueRepository by stored {
    override suspend fun create(
        title: String,
        note: String,
        tags: Collection<String>,
        exchangeIds: Collection<Long>,
        source: IssueSource,
        status: IssueStatus,
    ): IssueWrite = stored.create(title, note, tags, stored(exchangeIds), source, status)

    override suspend fun update(
        id: Long,
        change: IssueChange,
    ): IssueWrite = stored.update(id, change.copy(exchangeIds = change.exchangeIds?.let { stored(it) }))

    private suspend fun stored(ids: Collection<Long>): List<Long> {
        val saved = storage.save(live.copies(ids.filter(LiveTraffic::isLive)))
        return ids.map { saved[it] ?: it }.distinct()
    }
}

internal class LiveTrafficQueryRepository(
    private val storage: Storage,
    private val live: LiveTraffic,
    private val stored: TrafficQueryRepository = StoredTrafficQueryRepository(storage),
) : TrafficQueryRepository by stored {
    override suspend fun search(
        filter: ExchangeFilter,
        afterId: Long,
        limit: Long,
    ): List<Exchange> =
        if (filter.isLive) {
            live
                .searchExchanges(
                    filter.toStored(),
                    afterId,
                    limit,
                ).map { it.toDomain() }
        } else {
            stored.search(filter, afterId, limit)
        }

    override suspend fun replayOriginals(exchanges: List<Exchange>): Map<Long, Long> {
        val (fromLive, fromStore) = exchanges.partition { LiveTraffic.isLive(it.id) }
        return live.replayOriginals(fromLive.map { it.toStored() }) + stored.replayOriginals(fromStore)
    }

    override suspend fun detail(id: Long): ExchangeDetail? =
        if (LiveTraffic.isLive(id)) live.exchange(id)?.let { storage.named(it) }?.toDomain() else stored.detail(id)

    override suspend fun session(id: Long): Session? = if (LiveTraffic.isLive(id)) live.session(id)?.toDomain() else stored.session(id)

    override suspend fun frames(
        exchangeId: Long,
        afterId: Long,
        limit: Long,
    ): List<CapturedFrame> =
        if (LiveTraffic.isLive(exchangeId)) {
            live.frames(exchangeId, afterId, limit).map { it.toDomain() }
        } else {
            stored.frames(exchangeId, afterId, limit)
        }
}

internal class LiveBenchmarkRepository(
    storage: Storage,
    private val live: LiveTraffic,
    private val stored: BenchmarkRepository = StoredBenchmarkRepository(storage),
) : BenchmarkRepository by stored {
    override suspend fun endpoints(
        filter: ExchangeFilter,
        maxExchanges: Long,
    ): EndpointGroups =
        if (filter.isLive) live.groupByEndpoint(filter.toStored(), maxExchanges).toDomain() else stored.endpoints(filter, maxExchanges)
}
