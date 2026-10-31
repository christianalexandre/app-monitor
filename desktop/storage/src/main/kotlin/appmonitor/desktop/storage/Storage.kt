package appmonitor.desktop.storage

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import appmonitor.desktop.storage.db.AppMonitorDatabase
import appmonitor.desktop.storage.db.OfRun
import appmonitor.desktop.storage.db.Prunable
import appmonitor.desktop.storage.db.Sessions
import appmonitor.v1.BreakpointRule
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.MockRule
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The desktop's SQLite store (SDD 09). Writes go through one queue to one writer thread;
 * reads run on [Dispatchers.IO], each in its own transaction. Once [close] starts, reads return nothing and observed
 * queries end.
 */
class Storage private constructor(
    /** The database file. */
    val file: Path,
    /** The run this launch opened (ADR-0084), [LiveTraffic.NO_RUN] when none; [clearAll] deletes it, [currentRun] follows. */
    val runId: Long,
    private val driver: JdbcSqliteDriver,
    private val database: AppMonitorDatabase,
    private val clock: () -> Long,
    maxQueueBytes: Long,
    retentionInterval: Duration?,
    private val afterRetentionChunk: suspend () -> Unit,
    beforeCommit: (List<StorageItem>) -> Unit,
) : TrafficSink {
    internal val writerDispatcher: ExecutorCoroutineDispatcher =
        Executors
            .newSingleThreadExecutor { Thread(it, WRITER_THREAD).apply { isDaemon = true } }
            .asCoroutineDispatcher()

    private val ingest = Ingest(database, runId, beforeCommit)

    private val mutableCurrentRun = MutableStateFlow(runId)

    /** The run new sessions join (ADR-0084 A.1): [runId], then the one each [newRun], [record] or [clearAll] opened. */
    val currentRun: StateFlow<Long> = mutableCurrentRun.asStateFlow()

    private val mutableRecording = MutableStateFlow<Long?>(null)

    /** The run being recorded (ADR-0140 B), null when none: from [record] until [stop], [clearAll] or [close]. */
    val recording: StateFlow<Long?> = mutableRecording.asStateFlow()

    private val mutableRetention = MutableStateFlow(ingest.retention)

    /** The stored retention settings (ADR-0082 A.1), defaults until [setRetention] is written. */
    val retention: StateFlow<RetentionSettings> = mutableRetention.asStateFlow()

    private val mutableRetentionStatus = MutableStateFlow(RetentionStatus())

    /** What the last [applyRetention] left. */
    val retentionStatus: StateFlow<RetentionStatus> = mutableRetentionStatus.asStateFlow()

    // Writer thread only: a retention pass that sees it change was overtaken by a clearAll.
    private var clears = 0L

    private val closing = MutableStateFlow(false)

    /** True once [close] started: reads then answer empty, which is not "nothing stored". */
    val isClosing: Boolean get() = closing.value

    // Reads hold it shared, close takes it to close the driver: never under a read in flight.
    private val driverLock = ReentrantReadWriteLock()

    private val queue =
        WriteQueue(
            maxBytes = maxQueueBytes,
            maxBatch = MAX_BATCH,
            dispatcher = writerDispatcher,
            sizeOf = { item: StorageItem ->
                if (item is StorageItem.Received) Envelope.ADAPTER.encodedSize(item.envelope).toLong() else 0L
            },
            counted = { it !is StorageItem.Barrier },
            write = ingest::write,
            retry = ingest::retryLost,
            written = ::settle,
            dropped = ::settle,
        )

    private fun settle(batch: List<StorageItem>) {
        mutableCurrentRun.value = ingest.currentRun
        mutableRecording.value = ingest.recording
        mutableRetention.value = ingest.retention
        batch.forEach {
            if (it is StorageItem.Barrier) it.done.complete(Unit)
            if (it is StorageItem.NewRun) it.done.complete(ingest.currentRun)
            if (it is StorageItem.SaveIssue) it.done.complete(it.outcome ?: IssueWrite.NotSaved)
            if (it is StorageItem.SaveMock) it.done.complete(it.outcome ?: MockWrite.NotSaved)
            if (it is StorageItem.SaveBreakpoint) it.done.complete(it.outcome ?: BreakpointWrite.NotSaved)
            if (it is StorageItem.Save) it.done.complete(it.outcome.orEmpty())
        }
    }

    // ADR-0082 D.1: at open, then every interval; close cancels it.
    private val retentionJob: Job? =
        retentionInterval?.let { interval ->
            CoroutineScope(SupervisorJob()).launch {
                while (true) {
                    applyRetention()
                    delay(interval)
                }
            }
        }

    /** Items lost to write failures, and the last one's error while it lasts. */
    val health: StateFlow<StorageHealth> = queue.health

    /** The most encoded envelope bytes waiting at once since the store opened (DEBT-0022). */
    val queueHighWaterBytes: Long get() = queue.highWaterBytes

    /** How many times a session waited for room in the full queue since the store opened (DEBT-0022). */
    val queueFullWaits: Long get() = queue.fullWaits

    /** Queues a session's start; call before its first [received]. Suspends only while the queue is full. */
    override suspend fun connected(
        sessionId: String,
        hello: Hello,
        peerAddress: String?,
    ) {
        queue.enqueue(StorageItem.Connected(sessionId, hello, peerAddress, clock()))
    }

    override suspend fun received(
        sessionId: String,
        envelope: Envelope,
    ) {
        queue.enqueue(StorageItem.Received(sessionId, envelope, clock()))
    }

    override suspend fun dropped(
        sessionId: String,
        drops: StoredDrops,
    ) {
        queue.enqueue(StorageItem.Dropped(sessionId, drops))
    }

    override suspend fun disconnected(sessionId: String) {
        queue.enqueue(StorageItem.Disconnected(sessionId, clock()))
    }

    /**
     * Opens a new unnamed run and records into it (ADR-0140 B); what [Recorder] stores goes there until [stop].
     * Returns its id once written, null if its batch was lost or the store is closed.
     */
    suspend fun record(): Long? {
        val before = mutableCurrentRun.value
        val item = StorageItem.NewRun(clock(), record = true)
        if (!queue.enqueue(item)) return null
        return item.done.await().takeIf { it != before }
    }

    /** Ends the recording, once written; nothing when none is. */
    suspend fun stop() {
        if (mutableRecording.value == null) return
        queue.enqueue(StorageItem.EndRun(clock()))
        flush()
    }

    // Recorder's calls: [key] names one session in one run, the run fixed when it connects (ADR-0142).
    internal suspend fun connected(
        key: String,
        sessionId: String,
        hello: Hello,
        peerAddress: String?,
        run: Long,
        atMs: Long,
    ) {
        queue.enqueue(StorageItem.Connected(key, hello, peerAddress, atMs, serverSessionId = sessionId, run = run))
    }

    /**
     * Stores [copies] in the saved run, which the first save opens (ADR-0143); one saved before is not written again.
     * Returns each copy's stored exchange id by its live id once written, nothing if its batch was lost or the store is closed.
     */
    suspend fun save(copies: List<LiveCopy>): Map<Long, Long> {
        if (copies.isEmpty()) return emptyMap()
        val item = StorageItem.Save(copies, clock())
        if (!queue.enqueue(item)) return emptyMap()
        return item.done.await()
    }

    /**
     * Ends the current run and opens a new unnamed one; sessions already connected stay in theirs.
     * Returns the run current once it is written, the earlier one if its batch was lost or the store is closed.
     */
    suspend fun newRun(): Long {
        val item = StorageItem.NewRun(clock())
        if (!queue.enqueue(item)) return mutableCurrentRun.value
        return item.done.await()
    }

    /** Queues naming [runId]; a blank [name] makes it unnamed again (ADR-0084 B.1). */
    suspend fun rename(
        runId: Long,
        name: String,
    ) {
        queue.enqueue(StorageItem.Renamed(runId, name.trim().ifEmpty { null }))
    }

    /** Queues the developer's answer for [hello]'s (`device_id`, `app_id`), replacing an earlier one (ADR-0083). */
    suspend fun decided(
        hello: Hello,
        approved: Boolean,
    ) {
        queue.enqueue(StorageItem.Decided(hello, approved, clock()))
    }

    /** Queues forgetting the answer for (`deviceId`, `appId`). */
    suspend fun forget(
        deviceId: String,
        appId: String,
    ) {
        queue.enqueue(StorageItem.Forgot(deviceId, appId))
    }

    /** Queues forgetting every answer. */
    suspend fun forgetAll() {
        queue.enqueue(StorageItem.Forgot(null, null))
    }

    /**
     * Queues a new issue linking [exchangeIds] (ADR-0095 A.1) and returns what the writer did with it: nothing when one
     * of them is not stored by then.
     */
    suspend fun createIssue(
        title: String,
        note: String,
        tags: Collection<String>,
        exchangeIds: Collection<Long>,
        source: IssueSource,
        status: IssueStatus = IssueStatus.OPEN,
    ): IssueWrite {
        require(title.isNotBlank()) { "An issue needs a title" }
        require(exchangeIds.isNotEmpty()) { "An issue needs an exchange" }
        return saveIssue(StorageItem.SaveIssue(null, IssueChange(title.trim(), note, tags, status, exchangeIds), source, clock()))
    }

    /** Queues [change] to issue [id]; writes queued earlier, from the window or MCP, land first. */
    suspend fun updateIssue(
        id: Long,
        change: IssueChange,
    ): IssueWrite {
        require(change.title == null || change.title.isNotBlank()) { "An issue needs a title" }
        require(change.exchangeIds == null || change.exchangeIds.isNotEmpty()) { "An issue needs an exchange" }
        return saveIssue(StorageItem.SaveIssue(id, change.copy(title = change.title?.trim()), IssueSource.WINDOW, clock()))
    }

    private suspend fun saveIssue(item: StorageItem.SaveIssue): IssueWrite {
        if (!queue.enqueue(item)) return IssueWrite.NotSaved
        return item.done.await()
    }

    /** Queues deleting issue [id]; its exchanges go back to retention (ADR-0095 D.1). */
    suspend fun deleteIssue(id: Long) {
        queue.enqueue(StorageItem.DeleteIssue(id))
    }

    /** Queues a rule for [appId], last in its match order; one from [MockSource.MCP] starts pending (ADR-0110 F.1). */
    suspend fun createMock(
        appId: String,
        name: String,
        rule: MockRule,
        source: MockSource,
    ): MockWrite {
        require(name.isNotBlank()) { "A mock rule needs a name" }
        requireValid(rule)
        return saveMock(StorageItem.SaveMock(null, appId, MockChange(name.trim(), rule = rule), false, source, clock()))
    }

    /** Queues [change] to rule [id]. */
    suspend fun updateMock(
        id: Long,
        change: MockChange,
    ): MockWrite {
        require(change.name == null || change.name.isNotBlank()) { "A mock rule needs a name" }
        change.rule?.let(::requireValid)
        return saveMock(StorageItem.SaveMock(id, null, change.copy(name = change.name?.trim()), false, MockSource.WINDOW, clock()))
    }

    /** Queues the owner's confirmation of a pending rule (ADR-0005); a rule not pending stays as it is. */
    suspend fun confirmMock(id: Long): MockWrite = saveMock(StorageItem.SaveMock(id, null, MockChange(), true, MockSource.WINDOW, clock()))

    private suspend fun saveMock(item: StorageItem.SaveMock): MockWrite {
        if (!queue.enqueue(item)) return MockWrite.NotSaved
        return item.done.await()
    }

    /** Queues deleting rule [id]. */
    suspend fun deleteMock(id: Long) {
        queue.enqueue(StorageItem.DeleteMock(id))
    }

    /** Queues a breakpoint for [appId], last in its match order and on (ADR-0118). */
    suspend fun createBreakpoint(
        appId: String,
        name: String,
        rule: BreakpointRule,
    ): BreakpointWrite {
        require(name.isNotBlank()) { "A breakpoint needs a name" }
        requireValid(rule)
        return saveBreakpoint(StorageItem.SaveBreakpoint(null, appId, BreakpointChange(name.trim(), rule = rule), clock()))
    }

    /** Queues [change] to breakpoint [id]. */
    suspend fun updateBreakpoint(
        id: Long,
        change: BreakpointChange,
    ): BreakpointWrite {
        require(change.name == null || change.name.isNotBlank()) { "A breakpoint needs a name" }
        change.rule?.let(::requireValid)
        return saveBreakpoint(StorageItem.SaveBreakpoint(id, null, change.copy(name = change.name?.trim()), clock()))
    }

    private suspend fun saveBreakpoint(item: StorageItem.SaveBreakpoint): BreakpointWrite {
        if (!queue.enqueue(item)) return BreakpointWrite.NotSaved
        return item.done.await()
    }

    /** Queues deleting breakpoint [id]. */
    suspend fun deleteBreakpoint(id: Long) {
        queue.enqueue(StorageItem.DeleteBreakpoint(id))
    }

    /** Queues [settings], then applies them at once. */
    suspend fun setRetention(settings: RetentionSettings) {
        queue.enqueue(StorageItem.SetRetention(settings))
        flush()
        applyRetention()
    }

    /** The stored value of a `settings` key other than retention's (SDD 09); null when unset or the store is closing. */
    suspend fun setting(key: String): String? = read(null) { settingQueries.valueOf(key).executeAsOneOrNull() }

    /** Queues writing a `settings` key; [clearAll] deletes it with the rest. */
    suspend fun setSetting(
        key: String,
        value: String,
    ) {
        queue.enqueue(StorageItem.SetSetting(key, value))
    }

    /**
     * Deletes ended sessions outside named runs, oldest first, past the age bound, then down to 90 % of the size bound
     * (ADR-0082), on the writer thread between batches; failures go to [retentionStatus]. Closing: the last status.
     * A session holding an issue's exchange keeps it and its row, and loses the rest (ADR-0095 B.1).
     */
    suspend fun applyRetention(): RetentionStatus =
        whileOpen {
            val now = clock()
            val settings = ingest.retention
            val clearsAtStart = clears

            // Between chunks a setRetention (which runs its own pass) or a clearAll ends this one, status untouched.
            fun superseded() = ingest.retention != settings || clears != clearsAtStart
            var deleted = 0L
            val status =
                try {
                    settings.maxAgeDays?.let { days ->
                        val before = now - days.days.inWholeMilliseconds
                        while (true) {
                            val sessions = database.sessionQueries.prunable(before, AGE_CHUNK).executeAsList()
                            if (sessions.isEmpty()) break
                            database.transaction { sessions.forEach(::prune) }
                            deleted += sessions.count { !it.kept }
                            afterRetentionChunk()
                            yield()
                            if (superseded()) return@whileOpen mutableRetentionStatus.value
                        }
                        database.runQueries.deleteEmptyBefore(before)
                    }
                    if (liveBytes() > settings.maxBytes) {
                        val target = settings.maxBytes / 10 * 9
                        while (liveBytes() > target) {
                            val session = database.sessionQueries.prunable(Long.MAX_VALUE, 1).executeAsOneOrNull() ?: break
                            database.transaction { prune(session) }
                            if (!session.kept) deleted++
                            afterRetentionChunk()
                            yield()
                            if (superseded()) return@whileOpen mutableRetentionStatus.value
                        }
                        database.runQueries.deleteEmptyBefore(Long.MAX_VALUE)
                    }
                    // Also after nothing deleted: a superseded pass leaves its cleanup to the next one.
                    database.transaction {
                        database.deviceQueries.deleteUnused()
                        database.appQueries.deleteUnused()
                    }
                    releaseFreePages()
                    RetentionStatus(fileBytes(), liveBytes() > settings.maxBytes, now, deleted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RetentionStatus(mutableRetentionStatus.value.usedBytes, false, now, deleted, e.toString())
                }
            if (superseded()) return@whileOpen mutableRetentionStatus.value
            mutableRetentionStatus.value = status
            status
        } ?: mutableRetentionStatus.value

    private fun prune(session: Prunable) {
        if (session.kept) {
            database.exchangeQueries.deleteUnlinkedOfSession(session.id)
            database.rawEnvelopeQueries.deleteOfSession(session.id)
        } else {
            database.sessionQueries.delete(session.id)
        }
    }

    /**
     * Deletes everything, issues and settings included, rewrites the file, opens a new run when [open] did and ends the recording
     * (ADR-0088). Connected sessions are forgotten: close them first. Failures go to [retentionStatus]; none once closing.
     */
    suspend fun clearAll() {
        flush()
        whileOpen {
            clears++
            val error =
                try {
                    ingest.clear(clock())
                    driver.execute(null, "VACUUM", 0)
                    null
                } catch (e: Exception) {
                    e.toString()
                }
            mutableCurrentRun.value = ingest.currentRun
            mutableRecording.value = ingest.recording
            mutableRetention.value = ingest.retention
            mutableRetentionStatus.value = RetentionStatus(usedBytes = fileBytes(), lastPassAtMs = clock(), lastError = error)
        }
    }

    // ADR-0088: one page per statement, the driver steps each once; chunked so batches pass between.
    private suspend fun releaseFreePages() {
        var free = pragma("freelist_count") ?: 0
        while (free > 0) {
            database.transaction { repeat(minOf(free, VACUUM_CHUNK).toInt()) { driver.execute(null, "PRAGMA incremental_vacuum", 0) } }
            val left = pragma("freelist_count") ?: 0
            if (left >= free) break
            free = left
            yield()
        }
    }

    private fun liveBytes(): Long = ((pragma("page_count") ?: 0) - (pragma("freelist_count") ?: 0)) * (pragma("page_size") ?: 0)

    private fun fileBytes(): Long = (pragma("page_count") ?: 0) * (pragma("page_size") ?: 0)

    /**
     * Ends the run, writes what is queued for up to [timeout] and returns how many items were lost (SDD 09); a batch
     * still being written keeps the file open until it ends.
     */
    suspend fun close(timeout: Duration): Long {
        val start = TimeSource.Monotonic.markNow()
        closing.value = true
        // A pass queued on the writer behind a long batch ends only when the writer reaches it.
        retentionJob?.cancel()
        withTimeoutOrNull(timeout) { retentionJob?.join() }
        withTimeoutOrNull(timeout) { queue.enqueue(StorageItem.EndRun(clock())) }
        val lost = queue.close(timeout - start.elapsedNow())
        if (lost > 0) System.err.println("Storage closed with $lost items not saved")
        if (queue.fullWaits > 0) System.err.println("Storage queue was full ${queue.fullWaits} times: sessions waited for the disk")
        // On the writer thread, behind what is queued there: no call runs on a closed driver.
        val driverClosed = CompletableDeferred<Unit>()
        queue.afterWriter {
            writerDispatcher.executor.execute {
                try {
                    driverLock.write { driver.close() }
                } finally {
                    driverClosed.complete(Unit)
                }
            }
            writerDispatcher.close()
        }
        withTimeoutOrNull(timeout - start.elapsedNow()) { driverClosed.await() }
        return lost
    }

    // Null once the store is closing, even for a call already queued on the writer; a shut writer rejects it.
    private suspend fun <R> whileOpen(block: suspend () -> R): R? =
        try {
            withContext(writerDispatcher) { if (closing.value) null else block() }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }

    suspend fun runs(): List<StoredRun> = read(emptyList()) { runQueries.all(::storedRun).executeAsList() }

    /**
     * A page of runs, newest first, each with its sessions and exchange count, in one transaction; the next page passes
     * the last run's id as [beforeId].
     */
    suspend fun runSummaries(
        beforeId: Long = Long.MAX_VALUE,
        limit: Long = PAGE_SIZE,
    ): List<RunSummary> =
        read(emptyList()) {
            runQueries.pageNewestFirst(beforeId, limit, ::storedRun).executeAsList().map { run ->
                RunSummary(
                    run = run,
                    exchangeCount = exchangeQueries.countOfRun(run.id).executeAsOne(),
                    sessions =
                        sessionQueries.ofRun(run.id).executeAsList().map {
                            SessionSummary(
                                session =
                                    it.toStored(),
                                deviceId = it.device_id,
                                deviceName = it.device_name,
                                platform = Platform.fromValue(it.platform.toInt()) ?: Platform.PLATFORM_UNSPECIFIED,
                                osVersion = it.os_version,
                                appId = it.app_id,
                                appVersion = it.app_version,
                                sdkName = it.sdk_name,
                            )
                        },
                )
            }
        }

    /** Every run, newest first, again after each commit that changes one. */
    fun observeRuns(): Flow<List<StoredRun>> = observe(database.runQueries.newestFirst(::storedRun))

    /** The issues [filter] matches, newest first, again after each commit that changes an issue, its tags or exchanges. */
    fun observeIssues(filter: IssueFilter = IssueFilter()): Flow<List<StoredIssue>> =
        observe(database.issueQueries.filtered(filter.status?.stored, filter.tag, Long.MAX_VALUE, -1)).map { rows ->
            rows.map { it.toStored() }
        }

    /**
     * A page of the issues [filter] matches, newest first, each with the ids of the exchanges it links, in one
     * transaction; the next page passes the last issue's id as [beforeId].
     */
    suspend fun issuePage(
        filter: IssueFilter = IssueFilter(),
        beforeId: Long = Long.MAX_VALUE,
        limit: Long = PAGE_SIZE,
    ): List<IssueDetail> =
        read(emptyList()) {
            issueQueries.filtered(filter.status?.stored, filter.tag, beforeId, limit).executeAsList().map {
                IssueDetail(it.toStored(), issueQueries.exchangeIds(it.id).executeAsList())
            }
        }

    /** Every tag on an issue, sorted, again after each commit that changes one. */
    fun observeIssueTags(): Flow<List<String>> = observe(database.issueQueries.allTags())

    /** The exchanges issue [id] links, in arrival order. */
    suspend fun issueExchanges(id: Long): List<StoredExchange> =
        read(emptyList()) { exchangeQueries.ofIssue(id, ::StoredExchange).executeAsList() }

    /** Every rule, by app id, then in match order. */
    suspend fun mocks(): List<StoredMock> = read(emptyList()) { mockQueries.all().executeAsList().map { it.toStored() } }

    /** [mocks], again after each commit that changes one. */
    fun observeMocks(): Flow<List<StoredMock>> = observe(database.mockQueries.all()).map { rows -> rows.map { it.toStored() } }

    /** The rules a device of [appId] is sent, in match order (ADR-0113); a new list only when it changes. */
    fun observeActiveMocks(appId: String): Flow<List<MockRule>> =
        observe(database.mockQueries.activeOf(appId) { id, rule -> MockRule.ADAPTER.decode(rule).copy(id = id) }).distinctUntilChanged()

    /** Every breakpoint, by app id, then in match order. */
    suspend fun breakpoints(): List<StoredBreakpoint> = read(emptyList()) { breakpointQueries.all().executeAsList().map { it.toStored() } }

    /** [breakpoints], again after each commit that changes one. */
    fun observeBreakpoints(): Flow<List<StoredBreakpoint>> =
        observe(database.breakpointQueries.all()).map { rows ->
            rows.map { it.toStored() }
        }

    /** The breakpoints a device of [appId] is sent, in match order (ADR-0118); a new list only when it changes. */
    fun observeActiveBreakpoints(appId: String): Flow<List<BreakpointRule>> =
        observe(database.breakpointQueries.activeOf(appId) { id, rule -> BreakpointRule.ADAPTER.decode(rule).copy(id = id) })
            .distinctUntilChanged()

    /** Every stored answer, oldest first. */
    suspend fun approvals(): List<StoredApproval> = read(emptyList()) { approvalQueries.all(::StoredApproval).executeAsList() }

    /** [approvals], again after each commit that changes one. */
    fun observeApprovals(): Flow<List<StoredApproval>> = observe(database.approvalQueries.all(::StoredApproval))

    /** The sessions of [runId], or every stored one when null, oldest first, again after each commit that changes one. */
    fun observeSessions(runId: Long? = null): Flow<List<StoredSession>> =
        observe(if (runId == null) database.sessionQueries.all() else database.sessionQueries.inRun(runId))
            .map { rows -> rows.map { it.toStored() } }

    /** The first [limit] exchanges of a session in arrival order, again after each commit that changes one. */
    fun observeExchangesOfSession(
        sessionId: Long,
        limit: Long = PAGE_SIZE,
    ): Flow<List<StoredExchange>> = observe(database.exchangeQueries.pageOfSession(sessionId, 0, limit, ::StoredExchange))

    /** A page of the exchanges [filter] matches in arrival order; the next page passes the last id as [afterId]. */
    suspend fun searchExchanges(
        filter: ExchangeFilter,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<StoredExchange> = read(emptyList()) { exchangeSearch(driver, filter, afterId, limit).executeAsList() }

    /** [searchExchanges] with the stored size of each exchange's bodies (`endpoint_stats`, SDD 10). */
    suspend fun searchExchangeSizes(
        filter: ExchangeFilter,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<ExchangeSizes> = read(emptyList()) { exchangeSizeSearch(driver, filter, afterId, limit).executeAsList() }

    /** The first [limit] exchanges [filter] matches, again after each commit that changes an exchange or a session. */
    fun observeExchanges(
        filter: ExchangeFilter,
        limit: Long = PAGE_SIZE,
    ): Flow<List<StoredExchange>> = observe(exchangeSearch(driver, filter, 0, limit))

    /** The hosts of session [sessionId], else of run [runId], else of every stored exchange, sorted, again after each commit. */
    fun observeHosts(
        runId: Long?,
        sessionId: Long? = null,
    ): Flow<List<String>> =
        when {
            sessionId != null -> observe(database.exchangeQueries.hostsOfSession(sessionId))
            runId != null -> observe(database.exchangeQueries.hostsOfRun(runId))
            else -> observe(database.exchangeQueries.hosts())
        }

    internal fun searchPlan(filter: ExchangeFilter): List<String> = exchangeSearch(driver, filter, 0, PAGE_SIZE).plan()

    suspend fun sessions(runId: Long): List<StoredSession> =
        read(emptyList()) { sessionQueries.ofRun(runId).executeAsList().map { it.toStored() } }

    /** Each session of run [runId] with its device, in the order the sessions joined it. */
    internal suspend fun sessionDevices(runId: Long): Map<Long, RunDevice> =
        read(emptyMap()) { sessionQueries.ofRun(runId).executeAsList().associate { it.id to RunDevice(it.device_id, it.device_name) } }

    /** A page of a run's exchanges in arrival order; the next page passes the last [StoredExchange.id] as [afterId]. */
    suspend fun exchangesOfRun(
        runId: Long,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<StoredExchange> = read(emptyList()) { exchangeQueries.pageOfRun(runId, afterId, limit, ::StoredExchange).executeAsList() }

    suspend fun exchangesOfSession(
        sessionId: Long,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<StoredExchange> =
        read(emptyList()) { exchangeQueries.pageOfSession(sessionId, afterId, limit, ::StoredExchange).executeAsList() }

    /** One exchange with its events as the device sent them, bodies included; null when it is not stored. */
    suspend fun exchange(id: Long): ExchangeDetail? =
        read(null) {
            val row = exchangeQueries.byId(id).executeAsOneOrNull() ?: return@read null

            fun body(part: Long): ByteString =
                bodyQueries.bodyOf(id, part).executeAsOneOrNull()?.let { Codec.decode(it.codec, it.data_).toByteString() }
                    ?: ByteString.EMPTY
            ExchangeDetail(
                exchange =
                    StoredExchange(
                        row.id,
                        row.session_ref,
                        row.request_id,
                        row.method,
                        row.url,
                        row.host,
                        row.status_code,
                        row.failed,
                        row.started_at_ms,
                        row.ended_at_ms,
                        row.capture_layer,
                        row.error,
                        row.frame_count,
                        row.grpc_status,
                        row.mock_rule_id,
                        row.replay_of,
                    ),
                request = row.request_meta?.let { RequestStarted.ADAPTER.decode(it).copy(body = body(Ingest.PART_REQUEST)) },
                response = row.response_meta?.let { ResponseReceived.ADAPTER.decode(it).copy(body = body(Ingest.PART_RESPONSE)) },
                mockRuleName = row.mock_rule_id?.let { mockQueries.nameOf(it).executeAsOneOrNull() },
                replayOfId = row.replay_of?.let { exchangeQueries.idOf(row.session_ref, it).executeAsOneOrNull() },
            )
        }

    /** The original's exchange id of each replay in [exchanges] whose original is still stored, by the replay's id. */
    suspend fun replayOriginals(exchanges: List<StoredExchange>): Map<Long, Long> {
        val replays = exchanges.filter { it.replayOf != null }.map { it.id }
        if (replays.isEmpty()) return emptyMap()
        return read(emptyMap()) { exchangeQueries.originalsOf(replays).executeAsList().associate { it.replay to it.original } }
    }

    suspend fun session(id: Long): StoredSession? = read(null) { sessionQueries.byId(id).executeAsOneOrNull()?.toStored() }

    /** A page of an exchange's WebSocket frames, in the order the device sent them. */
    suspend fun frames(
        exchangeId: Long,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<StoredFrame> =
        read(emptyList()) {
            frameQueries.pageOfExchange(exchangeId, afterId, limit).executeAsList().map {
                StoredFrame(
                    id = it.id,
                    atMs = it.at_ms,
                    direction = WebSocketFrame.Direction.fromValue(it.direction.toInt()) ?: WebSocketFrame.Direction.DIRECTION_UNSPECIFIED,
                    opcode = WebSocketFrame.Opcode.fromValue(it.opcode.toInt()) ?: WebSocketFrame.Opcode.OPCODE_UNSPECIFIED,
                    payload =
                        if (it.codec == null ||
                            it.data_ == null
                        ) {
                            ByteString.EMPTY
                        } else {
                            Codec.decode(it.codec, it.data_).toByteString()
                        },
                    payloadTruncated = it.payload_truncated != 0L,
                    payloadSize = it.payload_size,
                )
            }
        }

    // [closed] once the store is closing.
    private suspend fun <R> read(
        closed: R,
        block: AppMonitorDatabase.() -> R,
    ): R = readOrNull { database.transactionWithResult { database.block() } } ?: closed

    /** Runs [block] on [Dispatchers.IO] unless the store is closing; the driver stays open until it returns. */
    internal suspend fun <R> readOrNull(block: () -> R): R? =
        withContext(Dispatchers.IO) { driverLock.read { if (closing.value) null else block() } }

    // Ends once the store is closing, at the next commit or before.
    private fun <T : Any> observe(query: Query<T>): Flow<List<T>> =
        merge(query.asFlow(), closing.filter { it }.map<Boolean, Query<T>?> { null })
            .transformWhile { fresh ->
                val rows = fresh?.let { readOrNull { it.executeAsList() } }
                if (rows != null) emit(rows)
                rows != null
            }

    /** Runs [block] on the writer thread, between two batches; tests use it to hold the writer. */
    internal suspend fun <R> onWriter(block: () -> R): R = withContext(writerDispatcher) { block() }

    internal fun pageQuery(sessionId: Long) = database.exchangeQueries.pageOfSession(sessionId, 0, PAGE_SIZE)

    /** Reads a PRAGMA on a connection of the store's driver, opened for it. */
    internal fun pragma(name: String): Long? = driver.longQuery("PRAGMA $name")

    /** Returns once everything queued before it is written or counted as lost, at once when the store is closed. */
    suspend fun flush() {
        val barrier = StorageItem.Barrier()
        if (queue.enqueue(barrier)) barrier.done.await()
    }

    companion object {
        const val FILE_NAME = "appmonitor.db"
        const val PAGE_SIZE = 200L
        const val MAX_QUEUE_BYTES = 64L * 1024 * 1024
        internal const val MAX_BATCH = 500
        internal const val WRITER_THREAD = "appmonitor-storage"
        val RETENTION_INTERVAL = 10.minutes
        internal const val AGE_CHUNK = 50L
        private const val VACUUM_CHUNK = 1_000L

        /**
         * Opens or creates `appmonitor.db` in [directory] and opens a new run unless not [startRun] (SDD 09, ADR-0142).
         * Blocking; throws [NewerDatabaseException] for a file a newer App Monitor wrote, untouched.
         */
        fun open(
            directory: Path,
            startRun: Boolean = true,
            clock: () -> Long = System::currentTimeMillis,
        ): Storage = open(directory, clock, MAX_QUEUE_BYTES, startRun = startRun) {}

        internal fun open(
            directory: Path,
            clock: () -> Long,
            maxQueueBytes: Long,
            retentionInterval: Duration? = RETENTION_INTERVAL,
            afterRetentionChunk: suspend () -> Unit = {},
            startRun: Boolean = true,
            beforeCommit: (List<StorageItem>) -> Unit,
        ): Storage {
            val file = createOwnerOnly(directory)
            val url = "jdbc:sqlite:$file"
            prepare(url)
            val driver = JdbcSqliteDriver(url, connectionProperties())
            try {
                val database = AppMonitorDatabase(driver)
                val runId =
                    database.transactionWithResult {
                        val version = driver.longQuery("PRAGMA user_version") ?: 0L
                        val schema = AppMonitorDatabase.Schema
                        when {
                            version == 0L -> schema.create(driver)
                            version < schema.version -> schema.migrate(driver, version, schema.version)
                            version > schema.version -> throw NewerDatabaseException(version, schema.version)
                        }
                        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                        database.sessionQueries.closeOpen()
                        database.runQueries.closeOpen()
                        if (startRun) {
                            database.runQueries.insert(clock())
                            database.runQueries.lastId().executeAsOne()
                        } else {
                            LiveTraffic.NO_RUN
                        }
                    }
                return Storage(file, runId, driver, database, clock, maxQueueBytes, retentionInterval, afterRetentionChunk, beforeCommit)
            } catch (e: Throwable) {
                driver.close()
                throw e
            }
        }

        private fun createOwnerOnly(directory: Path): Path {
            val posix = "posix" in directory.fileSystem.supportedFileAttributeViews()
            // A directory the developer chose keeps its mode: it may be ~/Documents or not theirs to chmod.
            if (Files.notExists(directory)) {
                Files.createDirectories(directory)
                if (posix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            }
            val file = directory.resolve(FILE_NAME)
            if (Files.notExists(file)) {
                Files.createFile(file)
                // SQLite gives the WAL and shared-memory files the database's mode.
                if (posix) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            }
            return file
        }

        // ADR-0086: before the driver's properties touch the file; VACUUM is what makes auto_vacuum hold.
        private fun prepare(url: String) {
            DriverManager.getConnection(url).use { connection ->
                val version =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA user_version").use { if (it.next()) it.getLong(1) else 0L }
                    }
                val schemaVersion = AppMonitorDatabase.Schema.version
                if (version > schemaVersion) throw NewerDatabaseException(version, schemaVersion)
                if (version == 0L) {
                    connection.createStatement().use {
                        it.execute("PRAGMA auto_vacuum = INCREMENTAL")
                        it.execute("VACUUM")
                    }
                }
            }
        }

        // ADR-0085: per-connection settings must be properties, a PRAGMA statement dies with its connection.
        private fun connectionProperties() =
            Properties().apply {
                setProperty("journal_mode", "WAL")
                setProperty("synchronous", "NORMAL")
                setProperty("foreign_keys", "true")
                setProperty("secure_delete", "true")
                setProperty("busy_timeout", "5000")
            }

        private fun JdbcSqliteDriver.longQuery(sql: String): Long? =
            executeQuery(
                null,
                sql,
                { cursor: SqlCursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                0,
            ).value
    }
}

private fun Sessions.toStored() =
    StoredSession(
        id = id,
        runId = run_id,
        serverSessionId = server_session_id,
        hello = Hello.ADAPTER.decode(hello),
        peerAddress = peer_address,
        connectedAtMs = connected_at_ms,
        disconnectedAtMs = disconnected_at_ms,
        lastEnvelopeAtMs = last_envelope_at_ms,
        drops = storedDrops(drops_evicted, drops_oversized, drops_lost),
    )

private fun OfRun.toStored() =
    StoredSession(
        id = id,
        runId = run_id,
        serverSessionId = server_session_id,
        hello = Hello.ADAPTER.decode(hello),
        peerAddress = peer_address,
        connectedAtMs = connected_at_ms,
        disconnectedAtMs = disconnected_at_ms,
        lastEnvelopeAtMs = last_envelope_at_ms,
        drops = storedDrops(drops_evicted, drops_oversized, drops_lost),
    )

private fun storedDrops(
    evicted: Long?,
    oversized: Long?,
    lost: Long?,
) = if (evicted == null || oversized == null) null else StoredDrops(evicted, oversized, lost)

/** `appmonitor.db` was written by a newer App Monitor; nothing was written to it. */
class NewerDatabaseException(
    val found: Long,
    val supported: Long,
) : Exception("Database schema version $found is newer than this App Monitor's ($supported)")

data class StoredRun(
    val id: Long,
    val name: String?,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    /** True for the collection [Storage.save] writes to, false for a recording (ADR-0143). */
    val saved: Boolean = false,
)

private fun storedRun(
    id: Long,
    name: String?,
    startedAtMs: Long,
    endedAtMs: Long?,
    saved: Long,
) = StoredRun(id, name, startedAtMs, endedAtMs, saved != 0L)

data class RunSummary(
    val run: StoredRun,
    val exchangeCount: Long,
    val sessions: List<SessionSummary>,
)

/** A session with its device and app as the `devices` and `apps` rows hold them: the last `Hello` seen. */
data class SessionSummary(
    val session: StoredSession,
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val osVersion: String,
    val appId: String,
    val appVersion: String,
    val sdkName: String,
)

/** One answered (`device_id`, `app_id`) pair, with the device as its last `Hello` described it (ADR-0083). */
data class StoredApproval(
    val deviceId: String,
    val appId: String,
    val approved: Boolean,
    val decidedAtMs: Long,
    val deviceName: String,
    val osVersion: String,
    val appVersion: String,
) {
    internal constructor(
        deviceId: String,
        appId: String,
        approved: Long,
        decidedAtMs: Long,
        deviceName: String,
        osVersion: String,
        appVersion: String,
    ) : this(deviceId, appId, approved != 0L, decidedAtMs, deviceName, osVersion, appVersion)
}

data class StoredSession(
    val id: Long,
    val runId: Long,
    val serverSessionId: String,
    val hello: Hello,
    val peerAddress: String?,
    val connectedAtMs: Long,
    val disconnectedAtMs: Long?,
    val lastEnvelopeAtMs: Long?,
    /** Its latest `DeliveryReport`'s counts; null before one, or from an SDK that sends none (ADR-0166). */
    val drops: StoredDrops? = null,
)

/** What a device's SDK dropped since it started: full buffer, too large, written and not read; [lost] null when unknown (ADR-0166). */
data class StoredDrops(
    val evicted: Long,
    val oversized: Long,
    val lost: Long?,
) {
    /** True when any count is above zero; an unknown [lost] alone is not. */
    val any: Boolean get() = evicted > 0 || oversized > 0 || (lost ?: 0) > 0
}

/** The columns lists and filters read; no body, no headers. */
data class StoredExchange(
    val id: Long,
    val sessionId: Long,
    val requestId: String,
    val method: String?,
    val url: String?,
    val host: String?,
    val statusCode: Int?,
    val failed: Boolean,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val captureLayer: CaptureLayer,
    val error: String?,
    val frameCount: Long,
    val grpcStatus: Int? = null,
    /** The rule that answered on the device instead of the network (ADR-0110 E.1). */
    val mockRuleId: Long? = null,
    /** The request id, in the same session, of the request this one sent again (ADR-0111 D.1). */
    val replayOf: String? = null,
) {
    /** A socket the connection layer saw (ADR-0103): no status, no headers, no body. */
    val isConnection: Boolean get() = captureLayer == CaptureLayer.CAPTURE_LAYER_CONNECTION

    /** Failed, status 400 and up, or a `grpc-status` other than OK (ADR-0107). */
    val isError: Boolean get() = failed || (statusCode ?: 0) >= 400 || (grpcStatus ?: 0) != 0

    internal constructor(
        id: Long,
        sessionRef: Long,
        requestId: String,
        method: String?,
        url: String?,
        host: String?,
        statusCode: Long?,
        failed: Long,
        startedAtMs: Long?,
        endedAtMs: Long?,
        captureLayer: Long?,
        error: String?,
        frameCount: Long,
        grpcStatus: Long?,
        mockRuleId: Long?,
        replayOf: String?,
    ) : this(
        id,
        sessionRef,
        requestId,
        method,
        url,
        host,
        statusCode?.toInt(),
        failed != 0L,
        startedAtMs,
        endedAtMs,
        captureLayer?.let { CaptureLayer.fromValue(it.toInt()) } ?: CaptureLayer.CAPTURE_LAYER_UNSPECIFIED,
        error,
        frameCount,
        grpcStatus?.toInt(),
        mockRuleId,
        replayOf,
    )
}

/**
 * [request] and [response] are the events as received, bodies restored; null until that event is stored.
 * [mockRuleName] is null when the exchange was not mocked or its rule is no longer stored; [replayOfId] when it is
 * not a replay or its original is no longer stored.
 */
data class ExchangeDetail(
    val exchange: StoredExchange,
    val request: RequestStarted?,
    val response: ResponseReceived?,
    val mockRuleName: String? = null,
    val replayOfId: Long? = null,
    /** Frames the source still holds; null when it holds all of `frame_count` (the store). */
    val framesKept: Long? = null,
) {
    /** The bytes a connection moved, null for any other layer; each count null until it closed (ADR-0103). */
    val connectionBytes: ConnectionBytes?
        get() = if (exchange.isConnection) ConnectionBytes(sent = response?.sent_size, received = response?.body_size) else null
}

/** What the app wrote to and read from one socket. */
data class ConnectionBytes(
    val sent: Long?,
    val received: Long?,
)

data class StoredFrame(
    val id: Long,
    val atMs: Long,
    val direction: WebSocketFrame.Direction,
    val opcode: WebSocketFrame.Opcode,
    val payload: ByteString,
    val payloadTruncated: Boolean,
    val payloadSize: Long?,
)
