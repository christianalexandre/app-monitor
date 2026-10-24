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

    /** Completed once the batch holding it is committed or counted as lost. */
    class Barrier : StorageItem {
        val done = CompletableDeferred<Unit>()
        override val sessionId: String? get() = null
    }
}

/** Turns [StorageItem]s into rows. Writer thread only; [write] is one transaction. */
internal class Ingest(
    private val database: AppMonitorDatabase,
    private val runId: Long,
    private val beforeCommit: (List<StorageItem>) -> Unit = {},
) {
    // `connections` outlive a rolled back batch, `rows` do not: a lost session row is inserted again.
    private val connections = mutableMapOf<String, StorageItem.Connected>()
    private var rows = mapOf<String, Long>()

    fun write(batch: List<StorageItem>) {
        val before = rows
        try {
            database.transaction {
                batch.forEach(::apply)
                beforeCommit(batch)
            }
        } catch (e: Exception) {
            rows = before.filterKeys { it in connections }
            throw e
        }
    }

    private fun apply(item: StorageItem) {
        when (item) {
            is StorageItem.Connected -> {
                connections[item.sessionId] = item
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
                database.runQueries.end(item.atMs, runId)
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

            is StorageItem.Barrier -> {
                Unit
            }
        }
    }

    private fun sessionRow(sessionId: String): Long? {
        rows[sessionId]?.let { return it }
        val connected = connections[sessionId] ?: return null
        val hello = connected.hello
        database.deviceQueries.upsert(hello.device_id, hello.platform.value.toLong(), hello.device_name, hello.os_version)
        val device = database.deviceQueries.idOf(hello.device_id).executeAsOne()
        database.appQueries.insertIfAbsent(hello.app_id)
        val app = database.appQueries.idOf(hello.app_id).executeAsOne()
        database.sessionQueries.insert(
            run_id = runId,
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
                queries.started(event.method, event.url, hostOf(event.url), at, event.capture_layer.value.toLong(), meta, id)
                putBody(id, PART_REQUEST, event.body)
            }

            "ResponseReceived" -> {
                val event = ResponseReceived.ADAPTER.decode(envelope.payload)
                val id = exchange(session, event.request_id)
                val meta = event.copy(body = ByteString.EMPTY).encode()
                queries.responded(event.status_code.toLong(), at, meta, id)
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

    private fun hostOf(url: String): String? =
        try {
            URI(url).host
        } catch (_: URISyntaxException) {
            null
        }

    companion object {
        const val PART_REQUEST = 0L
        const val PART_RESPONSE = 1L
    }
}
