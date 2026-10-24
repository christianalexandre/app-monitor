package appmonitor.desktop.storage

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import appmonitor.desktop.storage.db.AppMonitorDatabase
import appmonitor.desktop.storage.db.Sessions
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The desktop's SQLite store (SDD 09). Writes go through one queue to one writer thread;
 * reads run on [Dispatchers.IO], each in its own transaction.
 */
class Storage private constructor(
    /** The database file. */
    val file: Path,
    /** The run this launch opened (ADR-0084); [clearAll] deletes it, [currentRun] follows. */
    val runId: Long,
    private val driver: JdbcSqliteDriver,
    private val database: AppMonitorDatabase,
    private val clock: () -> Long,
    maxQueueBytes: Long,
    retentionInterval: Duration?,
    beforeCommit: (List<StorageItem>) -> Unit,
) {
    internal val writerDispatcher: ExecutorCoroutineDispatcher =
        Executors
            .newSingleThreadExecutor { Thread(it, WRITER_THREAD).apply { isDaemon = true } }
            .asCoroutineDispatcher()

    private val ingest = Ingest(database, runId, beforeCommit)

    private val mutableCurrentRun = MutableStateFlow(runId)

    /** The run new sessions join (ADR-0084 A.1): [runId], then the one each [newRun] or [clearAll] opened. */
    val currentRun: StateFlow<Long> = mutableCurrentRun.asStateFlow()

    private val mutableRetention = MutableStateFlow(ingest.retention)

    /** The stored retention settings (ADR-0082 A.1), defaults until [setRetention] is written. */
    val retention: StateFlow<RetentionSettings> = mutableRetention.asStateFlow()

    private val mutableRetentionStatus = MutableStateFlow(RetentionStatus())

    /** What the last [applyRetention] left. */
    val retentionStatus: StateFlow<RetentionStatus> = mutableRetentionStatus.asStateFlow()

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
            written = { batch ->
                mutableCurrentRun.value = ingest.currentRun
                mutableRetention.value = ingest.retention
                batch.forEach {
                    if (it is StorageItem.Barrier) it.done.complete(Unit)
                    if (it is StorageItem.NewRun) it.done.complete(ingest.currentRun)
                }
            },
        )

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
    suspend fun connected(
        sessionId: String,
        hello: Hello,
        peerAddress: String?,
    ) {
        queue.enqueue(StorageItem.Connected(sessionId, hello, peerAddress, clock()))
    }

    suspend fun received(
        sessionId: String,
        envelope: Envelope,
    ) {
        queue.enqueue(StorageItem.Received(sessionId, envelope, clock()))
    }

    suspend fun disconnected(sessionId: String) {
        queue.enqueue(StorageItem.Disconnected(sessionId, clock()))
    }

    /**
     * Ends the current run and opens a new unnamed one; sessions already connected stay in theirs.
     * Returns the run current once it is written, the earlier one if its batch was lost.
     */
    suspend fun newRun(): Long {
        val item = StorageItem.NewRun(clock())
        queue.enqueue(item)
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

    /** Queues [settings], then applies them at once. */
    suspend fun setRetention(settings: RetentionSettings) {
        queue.enqueue(StorageItem.SetRetention(settings))
        flush()
        applyRetention()
    }

    /**
     * Deletes ended sessions outside named runs, oldest last envelope first: older than the age bound, then while the
     * file passes the size bound, until it is under 90 % of it (ADR-0082). Runs on the writer thread between batches,
     * one transaction per chunk; a failure is kept in [retentionStatus], not thrown.
     */
    suspend fun applyRetention(): RetentionStatus =
        withContext(writerDispatcher) {
            val now = clock()
            val settings = ingest.retention
            var deleted = 0L
            val status =
                try {
                    settings.maxAgeDays?.let { days ->
                        val before = now - days.days.inWholeMilliseconds
                        while (true) {
                            val ids = database.sessionQueries.prunable(before, AGE_CHUNK).executeAsList()
                            if (ids.isEmpty()) break
                            database.transaction { ids.forEach(database.sessionQueries::delete) }
                            deleted += ids.size
                            yield()
                        }
                        database.runQueries.deleteEmptyBefore(before)
                    }
                    if (liveBytes() > settings.maxBytes) {
                        val target = settings.maxBytes / 10 * 9
                        while (liveBytes() > target) {
                            val id = database.sessionQueries.prunable(Long.MAX_VALUE, 1).executeAsOneOrNull() ?: break
                            database.sessionQueries.delete(id)
                            deleted++
                            yield()
                        }
                        database.runQueries.deleteEmptyBefore(Long.MAX_VALUE)
                    }
                    if (deleted > 0) {
                        database.transaction {
                            database.deviceQueries.deleteUnused()
                            database.appQueries.deleteUnused()
                        }
                        releaseFreePages()
                    }
                    RetentionStatus(fileBytes(), liveBytes() > settings.maxBytes, now, deleted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RetentionStatus(mutableRetentionStatus.value.usedBytes, false, now, deleted, e.toString())
                }
            mutableRetentionStatus.value = status
            status
        }

    /**
     * Deletes everything, named runs, approvals and settings included, rewrites the file and opens a new run (ADR-0082
     * D.1, ADR-0088). Connected sessions are forgotten, so close them first; what they send next is dropped. A failure
     * is kept in [retentionStatus], not thrown.
     */
    suspend fun clearAll() {
        flush()
        withContext(writerDispatcher) {
            val error =
                try {
                    ingest.clear(clock())
                    driver.execute(null, "VACUUM", 0)
                    null
                } catch (e: Exception) {
                    e.toString()
                }
            mutableCurrentRun.value = ingest.currentRun
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
     * Ends the run, then writes what is queued for up to [timeout]. Returns how many items were
     * not written; they are lost, and the next open closes what they left open.
     */
    suspend fun close(timeout: Duration): Long {
        val start = TimeSource.Monotonic.markNow()
        retentionJob?.cancelAndJoin()
        withTimeoutOrNull(timeout) { queue.enqueue(StorageItem.EndRun(clock())) }
        val lost = queue.close(timeout - start.elapsedNow())
        if (lost > 0) System.err.println("Storage closed with $lost items not saved")
        if (queue.fullWaits > 0) System.err.println("Storage queue was full ${queue.fullWaits} times: sessions waited for the disk")
        writerDispatcher.close()
        driver.close()
        return lost
    }

    suspend fun runs(): List<StoredRun> = read { runQueries.all(::StoredRun).executeAsList() }

    /** Every run, newest first, again after each commit that changes one. */
    fun observeRuns(): Flow<List<StoredRun>> =
        database.runQueries
            .newestFirst(::StoredRun)
            .asFlow()
            .mapToList(Dispatchers.IO)

    /** Every stored answer, oldest first. */
    suspend fun approvals(): List<StoredApproval> = read { approvalQueries.all(::StoredApproval).executeAsList() }

    /** [approvals], again after each commit that changes one. */
    fun observeApprovals(): Flow<List<StoredApproval>> =
        database.approvalQueries
            .all(::StoredApproval)
            .asFlow()
            .mapToList(Dispatchers.IO)

    /** The sessions of [runId], or every stored one when null, oldest first, again after each commit that changes one. */
    fun observeSessions(runId: Long? = null): Flow<List<StoredSession>> =
        (if (runId == null) database.sessionQueries.all() else database.sessionQueries.inRun(runId))
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toStored() } }

    /** The first [limit] exchanges of a session in arrival order, again after each commit that changes one. */
    fun observeExchangesOfSession(
        sessionId: Long,
        limit: Long = PAGE_SIZE,
    ): Flow<List<StoredExchange>> =
        database.exchangeQueries
            .pageOfSession(sessionId, 0, limit, ::StoredExchange)
            .asFlow()
            .mapToList(Dispatchers.IO)

    /** A page of the exchanges [filter] matches in arrival order; the next page passes the last id as [afterId]. */
    suspend fun searchExchanges(
        filter: ExchangeFilter,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<StoredExchange> = read { SearchQuery(driver, filter, afterId, limit).executeAsList() }

    /** The first [limit] exchanges [filter] matches, again after each commit that changes an exchange or a session. */
    fun observeExchanges(
        filter: ExchangeFilter,
        limit: Long = PAGE_SIZE,
    ): Flow<List<StoredExchange>> =
        SearchQuery(driver, filter, 0, limit)
            .asFlow()
            .mapToList(Dispatchers.IO)

    internal fun searchPlan(filter: ExchangeFilter): List<String> = SearchQuery(driver, filter, 0, PAGE_SIZE).plan()

    suspend fun sessions(runId: Long): List<StoredSession> =
        read {
            sessionQueries.ofRun(runId).executeAsList().map {
                StoredSession(
                    id = it.id,
                    runId = it.run_id,
                    serverSessionId = it.server_session_id,
                    hello = Hello.ADAPTER.decode(it.hello),
                    peerAddress = it.peer_address,
                    connectedAtMs = it.connected_at_ms,
                    disconnectedAtMs = it.disconnected_at_ms,
                    lastEnvelopeAtMs = it.last_envelope_at_ms,
                )
            }
        }

    /** A page of a run's exchanges in arrival order; the next page passes the last [StoredExchange.id] as [afterId]. */
    suspend fun exchangesOfRun(
        runId: Long,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<StoredExchange> = read { exchangeQueries.pageOfRun(runId, afterId, limit, ::StoredExchange).executeAsList() }

    suspend fun exchangesOfSession(
        sessionId: Long,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<StoredExchange> = read { exchangeQueries.pageOfSession(sessionId, afterId, limit, ::StoredExchange).executeAsList() }

    /** One exchange with its events as the device sent them, bodies included; null when it is not stored. */
    suspend fun exchange(id: Long): ExchangeDetail? =
        read {
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
                    ),
                request = row.request_meta?.let { RequestStarted.ADAPTER.decode(it).copy(body = body(Ingest.PART_REQUEST)) },
                response = row.response_meta?.let { ResponseReceived.ADAPTER.decode(it).copy(body = body(Ingest.PART_RESPONSE)) },
            )
        }

    /** A page of an exchange's WebSocket frames, in the order the device sent them. */
    suspend fun frames(
        exchangeId: Long,
        afterId: Long = 0,
        limit: Long = PAGE_SIZE,
    ): List<StoredFrame> =
        read {
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

    private suspend fun <R> read(block: AppMonitorDatabase.() -> R): R =
        withContext(Dispatchers.IO) { database.transactionWithResult { database.block() } }

    /** Runs [block] on the writer thread, between two batches; tests use it to hold the writer. */
    internal suspend fun <R> onWriter(block: () -> R): R = withContext(writerDispatcher) { block() }

    internal fun pageQuery(sessionId: Long) = database.exchangeQueries.pageOfSession(sessionId, 0, PAGE_SIZE)

    /** Reads a PRAGMA on a connection of the store's driver, opened for it. */
    internal fun pragma(name: String): Long? = driver.longQuery("PRAGMA $name")

    /** Returns once everything queued before it is written or counted as lost. */
    suspend fun flush() {
        val barrier = StorageItem.Barrier()
        queue.enqueue(barrier)
        barrier.done.await()
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
         * Opens or creates `appmonitor.db` in [directory] and opens a new run (SDD 09, ADR-0085).
         * Blocking; throws [NewerDatabaseException] for a file a newer App Monitor wrote, untouched.
         */
        fun open(
            directory: Path,
            clock: () -> Long = System::currentTimeMillis,
        ): Storage = open(directory, clock, MAX_QUEUE_BYTES) {}

        internal fun open(
            directory: Path,
            clock: () -> Long,
            maxQueueBytes: Long,
            retentionInterval: Duration? = RETENTION_INTERVAL,
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
                        database.runQueries.insert(clock())
                        database.runQueries.lastId().executeAsOne()
                    }
                return Storage(file, runId, driver, database, clock, maxQueueBytes, retentionInterval, beforeCommit)
            } catch (e: Throwable) {
                driver.close()
                throw e
            }
        }

        private fun createOwnerOnly(directory: Path): Path {
            Files.createDirectories(directory)
            val posix = "posix" in directory.fileSystem.supportedFileAttributeViews()
            if (posix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
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
    )

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
)

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
) {
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
    )
}

/** [request] and [response] are the events as received, bodies restored; null until that event is stored. */
data class ExchangeDetail(
    val exchange: StoredExchange,
    val request: RequestStarted?,
    val response: ResponseReceived?,
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
