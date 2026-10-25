package appmonitor.desktop.storage

import appmonitor.desktop.storage.db.AppMonitorDatabase
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.CompletableDeferred
import okio.ByteString
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** What the store is asked to write, in the order a session produced it. */
internal sealed interface StorageItem {
    val sessionId: String?

    data class Connected(
        override val sessionId: String,
        val hello: Hello,
        val peerAddress: String?,
        val atMs: Long,
    ) : StorageItem

    data class Received(
        override val sessionId: String,
        val envelope: Envelope,
        val atMs: Long,
    ) : StorageItem

    data class Disconnected(
        override val sessionId: String,
        val atMs: Long,
    ) : StorageItem

    data class EndRun(
        val atMs: Long,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    /** Ends the current run and opens a new unnamed one; [done] gets the run current after its batch. */
    data class NewRun(
        val atMs: Long,
    ) : StorageItem {
        val done = CompletableDeferred<Long>()
        override val sessionId: String? get() = null
    }

    /** Names a run, or makes it unnamed again when [name] is null (ADR-0084 B.1). */
    data class Renamed(
        val runId: Long,
        val name: String?,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    data class Decided(
        val hello: Hello,
        val approved: Boolean,
        val atMs: Long,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    /** Forgets one (`device_id`, `app_id`) pair, or every pair when [deviceId] is null. */
    data class Forgot(
        val deviceId: String?,
        val appId: String?,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    data class SetRetention(
        val settings: RetentionSettings,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    data class SetSetting(
        val key: String,
        val value: String,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    /** Creates an issue when [id] is null, else changes it (ADR-0095 C.1); [done] gets [outcome], or not saved. */
    class SaveIssue(
        val id: Long?,
        val change: IssueChange,
        val source: IssueSource,
        val atMs: Long,
    ) : StorageItem {
        val done = CompletableDeferred<IssueWrite>()

        // Set by the writer as it applies the item, cleared when its batch rolls back.
        var outcome: IssueWrite? = null
        override val sessionId: String? get() = null
    }

    /** Creates a rule for [appId] when [id] is null, else changes or [confirm]s it; [done] gets [outcome], or not saved. */
    class SaveMock(
        val id: Long?,
        val appId: String?,
        val change: MockChange,
        val confirm: Boolean,
        val source: MockSource,
        val atMs: Long,
    ) : StorageItem {
        val done = CompletableDeferred<MockWrite>()

        // Set by the writer as it applies the item, cleared when its batch rolls back.
        var outcome: MockWrite? = null
        override val sessionId: String? get() = null
    }

    data class DeleteMock(
        val id: Long,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    /** Creates a breakpoint for [appId] when [id] is null, else changes it; [done] gets [outcome], or not saved. */
    class SaveBreakpoint(
        val id: Long?,
        val appId: String?,
        val change: BreakpointChange,
        val atMs: Long,
    ) : StorageItem {
        val done = CompletableDeferred<BreakpointWrite>()

        // Set by the writer as it applies the item, cleared when its batch rolls back.
        var outcome: BreakpointWrite? = null
        override val sessionId: String? get() = null
    }

    data class DeleteBreakpoint(
        val id: Long,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    data class DeleteIssue(
        val id: Long,
    ) : StorageItem {
        override val sessionId: String? get() = null
    }

    /** Completed once the batch holding it is committed or counted as lost. */
    class Barrier : StorageItem {
        val done = CompletableDeferred<Unit>()
        override val sessionId: String? get() = null
    }
}

/** Turns [StorageItem]s into rows. Writer thread only; [write] is one transaction. */
internal class Ingest(
    private val database: AppMonitorDatabase,
    runId: Long,
    private val beforeCommit: (List<StorageItem>) -> Unit = {},
) {
    /** The run new sessions join: the launch's, then each [StorageItem.NewRun]'s. */
    var currentRun: Long = runId
        private set

    /** The stored retention settings, as of the last batch written. */
    var retention: RetentionSettings = database.retentionSettings()
        private set

    // `connections` outlive a rolled back batch, `rows` do not: a lost session row is inserted again.
    // Each connection keeps the run current when it arrived (ADR-0084 A.1).
    private val connections = mutableMapOf<String, Pair<StorageItem.Connected, Long>>()
    private var rows = mapOf<String, Long>()

    // Each rolled back disconnect is retried before each next batch, in its own transaction: its session sends nothing more.
    private var lostDisconnects = listOf<StorageItem.Disconnected>()

    /** Writes each lost disconnect alone; returns how many were written, the others are kept for the next retry. */
    fun retryLost(): Int {
        val retried = lostDisconnects
        if (retried.isEmpty()) return 0
        lostDisconnects = retried.filterNot(::retry)
        return retried.size - lostDisconnects.size
    }

    private fun retry(item: StorageItem.Disconnected): Boolean {
        val before = rows
        val beforeConnections = connections.toMap()
        return try {
            database.transaction {
                apply(item)
                beforeCommit(listOf(item))
            }
            true
        } catch (_: Exception) {
            rows = before
            connections.putAll(beforeConnections)
            false
        }
    }

    fun write(batch: List<StorageItem>) {
        val before = rows
        val beforeRun = currentRun
        val beforeRetention = retention
        val beforeConnections = connections.toMap()
        try {
            database.transaction {
                batch.forEach(::apply)
                beforeCommit(batch)
            }
        } catch (e: Exception) {
            batch.forEach {
                if (it is StorageItem.SaveIssue) it.outcome = null
                if (it is StorageItem.SaveMock) it.outcome = null
                if (it is StorageItem.SaveBreakpoint) it.outcome = null
            }
            val disconnects = batch.filterIsInstance<StorageItem.Disconnected>()
            disconnects.forEach { item ->
                val connected = batch.filterIsInstance<StorageItem.Connected>().lastOrNull { it.sessionId == item.sessionId }
                (beforeConnections[item.sessionId] ?: connected?.let { it to beforeRun })?.let { connections[item.sessionId] = it }
            }
            lostDisconnects = lostDisconnects + disconnects.filter { it.sessionId in connections }
            rows = before.filterKeys { it in connections }
            // Runs opened by the lost batch are gone; the current run is always the highest id.
            currentRun = beforeRun
            retention = beforeRetention
            connections.replaceAll { _, (connected, run) -> connected to minOf(run, beforeRun) }
            throw e
        }
    }

    private fun apply(item: StorageItem) {
        when (item) {
            is StorageItem.Connected -> {
                connections[item.sessionId] = item to currentRun
                sessionRow(item.sessionId)
            }

            is StorageItem.Received -> {
                val session = sessionRow(item.sessionId) ?: return
                database.sessionQueries.touch(item.atMs, session)
                received(session, item.envelope)
            }

            is StorageItem.Disconnected -> {
                sessionRow(item.sessionId)?.let { database.sessionQueries.disconnect(item.atMs, it) }
                connections.remove(item.sessionId)
                rows = rows - item.sessionId
            }

            is StorageItem.EndRun -> {
                database.runQueries.end(item.atMs, currentRun)
            }

            is StorageItem.NewRun -> {
                database.runQueries.end(item.atMs, currentRun)
                database.runQueries.insert(item.atMs)
                currentRun = database.runQueries.lastId().executeAsOne()
            }

            is StorageItem.Renamed -> {
                database.runQueries.rename(item.name, item.runId)
            }

            is StorageItem.Decided -> {
                val hello = item.hello
                database.approvalQueries.upsert(
                    deviceId = hello.device_id,
                    appId = hello.app_id,
                    approved = if (item.approved) 1 else 0,
                    decidedAtMs = item.atMs,
                    deviceName = hello.device_name,
                    osVersion = hello.os_version,
                    appVersion = hello.app_version,
                )
            }

            is StorageItem.Forgot -> {
                if (item.deviceId == null || item.appId == null) {
                    database.approvalQueries.forgetAll()
                } else {
                    database.approvalQueries.forget(item.deviceId, item.appId)
                }
            }

            is StorageItem.SetRetention -> {
                database.putRetentionSettings(item.settings)
                retention = item.settings
            }

            is StorageItem.SetSetting -> {
                database.settingQueries.put(item.key, item.value)
            }

            is StorageItem.SaveIssue -> {
                item.outcome = saveIssue(item)
            }

            is StorageItem.DeleteIssue -> {
                database.issueQueries.delete(item.id)
            }

            is StorageItem.SaveMock -> {
                item.outcome = saveMock(item)
            }

            is StorageItem.DeleteMock -> {
                database.mockQueries.delete(item.id)
            }

            is StorageItem.SaveBreakpoint -> {
                item.outcome = saveBreakpoint(item)
            }

            is StorageItem.DeleteBreakpoint -> {
                database.breakpointQueries.delete(item.id)
            }

            is StorageItem.Barrier -> {
                Unit
            }
        }
    }

    // Checked on the writer, in the batch's transaction: retention cannot delete an exchange between check and link.
    private fun saveIssue(item: StorageItem.SaveIssue): IssueWrite {
        val queries = database.issueQueries
        val change = item.change
        change.exchangeIds?.let { ids ->
            val unknown = ids.distinct().filter { database.exchangeQueries.exists(it).executeAsOne() == 0L }
            if (unknown.isNotEmpty()) return IssueWrite.UnknownExchanges(unknown)
        }
        val id =
            if (item.id == null) {
                queries.insert(
                    title = change.title.orEmpty(),
                    note = change.note.orEmpty(),
                    status = (change.status ?: IssueStatus.OPEN).stored,
                    source = item.source.stored,
                    created_at_ms = item.atMs,
                    updated_at_ms = item.atMs,
                )
                queries.lastId().executeAsOne()
            } else {
                if (queries.exists(item.id).executeAsOne() == 0L) return IssueWrite.UnknownIssue
                queries.update(change.title, change.note, change.status?.stored, item.atMs, item.id)
                item.id
            }
        change.tags?.let { tags ->
            queries.deleteTags(id)
            normalizedTags(tags).forEach { queries.addTag(id, it) }
        }
        change.exchangeIds?.let { ids ->
            queries.deleteExchanges(id)
            ids.forEach { queries.addExchange(id, it) }
        }
        return IssueWrite.Saved(id)
    }

    private fun saveMock(item: StorageItem.SaveMock): MockWrite {
        val queries = database.mockQueries
        val change = item.change
        if (item.id == null) {
            queries.insert(
                appId = checkNotNull(item.appId),
                name = change.name.orEmpty(),
                pending = if (item.source == MockSource.MCP) 1 else 0,
                source = item.source.stored,
                rule = checkNotNull(change.rule).stored(),
                atMs = item.atMs,
            )
            return MockWrite.Saved(queries.lastId().executeAsOne())
        }
        if (queries.exists(item.id).executeAsOne() == 0L) return MockWrite.UnknownMock
        queries.update(change.name, change.enabled?.let { if (it) 1L else 0L }, change.rule?.stored(), item.atMs, item.id)
        if (item.confirm) queries.confirm(item.atMs, item.id)
        return MockWrite.Saved(item.id)
    }

    private fun saveBreakpoint(item: StorageItem.SaveBreakpoint): BreakpointWrite {
        val queries = database.breakpointQueries
        val change = item.change
        if (item.id == null) {
            queries.insert(
                appId = checkNotNull(item.appId),
                name = change.name.orEmpty(),
                rule = checkNotNull(change.rule).stored(),
                atMs = item.atMs,
            )
            return BreakpointWrite.Saved(queries.lastId().executeAsOne())
        }
        if (queries.exists(item.id).executeAsOne() == 0L) return BreakpointWrite.UnknownBreakpoint
        queries.update(change.name, change.enabled?.let { if (it) 1L else 0L }, change.rule?.stored(), item.atMs, item.id)
        return BreakpointWrite.Saved(item.id)
    }

    /**
     * Deletes every row, issues, mock and breakpoint rules, approvals and settings included, and opens a new run (ADR-0082 D.1). Sessions still
     * connected are forgotten: what they send next is dropped.
     */
    fun clear(atMs: Long) {
        currentRun =
            database.transactionWithResult {
                database.issueQueries.deleteAll()
                database.mockQueries.deleteAll()
                database.breakpointQueries.deleteAll()
                database.exchangeQueries.deleteAll()
                database.sessionQueries.deleteAll()
                database.runQueries.deleteAll()
                database.deviceQueries.deleteUnused()
                database.appQueries.deleteUnused()
                database.approvalQueries.forgetAll()
                database.settingQueries.deleteAll()
                database.runQueries.insert(atMs)
                database.runQueries.lastId().executeAsOne()
            }
        retention = RetentionSettings()
        connections.clear()
        rows = emptyMap()
        lostDisconnects = emptyList()
    }

    private fun sessionRow(sessionId: String): Long? {
        rows[sessionId]?.let { return it }
        val (connected, run) = connections[sessionId] ?: return null
        val hello = connected.hello
        database.deviceQueries.upsert(hello.device_id, hello.platform.value.toLong(), hello.device_name, hello.os_version)
        val device = database.deviceQueries.idOf(hello.device_id).executeAsOne()
        database.appQueries.insertIfAbsent(hello.app_id)
        val app = database.appQueries.idOf(hello.app_id).executeAsOne()
        database.sessionQueries.insert(
            run_id = run,
            device_ref = device,
            app_ref = app,
            server_session_id = sessionId,
            hello = hello.encode(),
            app_version = hello.app_version,
            sdk_name = hello.sdk_name,
            peer_address = connected.peerAddress,
            connected_at_ms = connected.atMs,
        )
        val id = database.sessionQueries.lastId().executeAsOne()
        rows = rows + (sessionId to id)
        return id
    }

    private fun received(
        session: Long,
        envelope: Envelope,
    ) {
        val stored =
            envelope.channel == Protocol.CHANNEL_NETWORK &&
                try {
                    network(session, envelope)
                } catch (_: IOException) {
                    false
                }
        if (!stored) {
            database.rawEnvelopeQueries.insert(
                session,
                envelope.channel,
                envelope.type,
                envelope.timestamp_ms,
                envelope.payload.toByteArray(),
            )
        }
    }

    // False for a type this desktop does not know; IOException for a payload that does not decode.
    private fun network(
        session: Long,
        envelope: Envelope,
    ): Boolean {
        val at = envelope.timestamp_ms
        val queries = database.exchangeQueries
        when (envelope.type) {
            "RequestStarted" -> {
                val event = RequestStarted.ADAPTER.decode(envelope.payload)
                val id = exchange(session, event.request_id)
                val meta = event.copy(body = ByteString.EMPTY).encode()
                queries.started(
                    event.method,
                    event.url,
                    hostOf(event.url),
                    at,
                    event.capture_layer.value.toLong(),
                    meta,
                    event.mock_rule_id,
                    event.replay_of,
                    id,
                )
                putBody(id, PART_REQUEST, event.body)
            }

            "ResponseReceived" -> {
                val event = ResponseReceived.ADAPTER.decode(envelope.payload)
                val id = exchange(session, event.request_id)
                val meta = event.copy(body = ByteString.EMPTY).encode()
                // ADR-0104: status 0 is no status, as a closed `CONNECTION` sends.
                queries.responded(event.status_code.takeIf { it != 0 }?.toLong(), grpcStatusOf(event), at, meta, id)
                putBody(id, PART_RESPONSE, event.body)
            }

            "RequestFailed" -> {
                val event = RequestFailed.ADAPTER.decode(envelope.payload)
                queries.failed(event.error, at, exchange(session, event.request_id))
            }

            "WebSocketFrame" -> {
                val event = WebSocketFrame.ADAPTER.decode(envelope.payload)
                val id = exchange(session, event.request_id)
                database.frameQueries.insert(
                    exchange_ref = id,
                    at_ms = at,
                    direction = event.direction.value.toLong(),
                    opcode = event.opcode.value.toLong(),
                    payload_truncated = if (event.payload_truncated) 1 else 0,
                    payload_size = event.payload_size,
                    stored_size = event.payload.size.toLong(),
                )
                if (event.payload.size > 0) {
                    val frame = database.frameQueries.lastId().executeAsOne()
                    val encoded = Codec.encode(event.payload.toByteArray())
                    database.frameQueries.putPayload(frame, encoded.codec, encoded.data)
                }
                queries.frameAdded(id)
            }

            else -> {
                return false
            }
        }
        return true
    }

    // An event for a request not stored yet creates it, as the in-memory store does.
    private fun exchange(
        session: Long,
        requestId: String,
    ): Long {
        database.exchangeQueries.insertIfAbsent(session, requestId)
        return database.exchangeQueries.idOf(session, requestId).executeAsOne()
    }

    private fun putBody(
        exchange: Long,
        part: Long,
        body: ByteString,
    ) {
        if (body.size == 0) {
            database.bodyQueries.remove(exchange, part)
            return
        }
        val encoded = Codec.encode(body.toByteArray())
        database.bodyQueries.put(exchange, part, encoded.codec, encoded.data, body.size.toLong())
    }

    companion object {
        const val PART_REQUEST = 0L
        const val PART_RESPONSE = 1L
    }
}

/** The URL's host, null when it does not parse. */
internal fun hostOf(url: String): String? =
    try {
        URI(url).host
    } catch (_: URISyntaxException) {
        null
    }

// ADR-0107: the last one, as trailers come after the headers (ADR-0102, ADR-0106).
internal fun grpcStatusOf(event: ResponseReceived): Long? =
    event.headers
        .lastOrNull { it.name.equals("grpc-status", ignoreCase = true) }
        ?.value_
        ?.trim()
        ?.toLongOrNull()
