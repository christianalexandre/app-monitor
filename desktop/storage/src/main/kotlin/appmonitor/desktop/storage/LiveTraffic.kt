package appmonitor.desktop.storage

import appmonitor.protocol.Protocol
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okio.ByteString
import java.io.IOException

/** Where a device's session and its envelopes go, in the order the session produced them (SDD 09). */
interface TrafficSink {
    /** A session's start; call before its first [received]. */
    suspend fun connected(
        sessionId: String,
        hello: Hello,
        peerAddress: String?,
    )

    suspend fun received(
        sessionId: String,
        envelope: Envelope,
    )

    /** The session's latest [StoredDrops], in order with its envelopes; a sink without sessions ignores it (ADR-0166). */
    suspend fun dropped(
        sessionId: String,
        drops: StoredDrops,
    ) = Unit

    suspend fun disconnected(sessionId: String)
}

/**
 * The live list in memory, as the store's rows (ADR-0140 A, ADR-0141): the oldest exchange goes past [maxExchanges] or
 * [maxBytes] of bodies and frames; an exchange keeps its last [maxFramesPerExchange] frames, at most a quarter of
 * [maxBytes]; ids from [FIRST_ID]; only `network` envelopes it decodes are kept.
 */
class LiveTraffic(
    private val maxExchanges: Int = MAX_EXCHANGES,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFramesPerExchange: Int = MAX_FRAMES_PER_EXCHANGE,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrafficSink {
    private class LiveSession(
        var row: StoredSession,
        var exchanges: Int = 0,
    )

    private class LiveExchange(
        var row: StoredExchange,
        var request: RequestStarted? = null,
        var response: ResponseReceived? = null,
        val frames: ArrayDeque<StoredFrame> = ArrayDeque(),
        var bodyBytes: Long = 0,
        var frameBytes: Long = 0,
    ) {
        val bytes: Long get() = bodyBytes + frameBytes
    }

    private val lock = Any()
    private var nextId = FIRST_ID
    private val sessions = LinkedHashMap<Long, LiveSession>()
    private val sessionIds = HashMap<String, Long>()

    // Ordered by id, the order they arrived in.
    private val exchanges = LinkedHashMap<Long, LiveExchange>()
    private val exchangeIds = HashMap<Pair<Long, String>, Long>()
    private var bytes = 0L

    // Bumped after each change; the observed reads run again on it. Sessions' too on any envelope.
    private val changes = MutableStateFlow(0L)
    private val sessionChanges = MutableStateFlow(0L)

    /** How many times the exchanges changed, for tests. */
    internal val exchangesVersion: Long get() = changes.value

    override suspend fun connected(
        sessionId: String,
        hello: Hello,
        peerAddress: String?,
    ) {
        change {
            val id = nextId++
            sessions[id] = LiveSession(StoredSession(id, NO_RUN, sessionId, hello, peerAddress, clock(), null, null))
            sessionIds[sessionId] = id
        }
    }

    override suspend fun received(
        sessionId: String,
        envelope: Envelope,
    ) {
        synchronized(lock) {
            val session = sessionIds[sessionId]?.let { sessions[it] } ?: return
            session.row = session.row.copy(lastEnvelopeAtMs = clock())
            sessionChanges.value += 1
            if (envelope.channel != Protocol.CHANNEL_NETWORK) return
            try {
                network(session, envelope)
            } catch (_: IOException) {
                return
            }
            trim()
            changes.value += 1
        }
    }

    override suspend fun dropped(
        sessionId: String,
        drops: StoredDrops,
    ) {
        change {
            val session = sessionIds[sessionId]?.let { sessions[it] } ?: return@change
            session.row = session.row.copy(drops = drops)
        }
    }

    override suspend fun disconnected(sessionId: String) {
        change {
            val id = sessionIds.remove(sessionId) ?: return@change
            val session = sessions[id] ?: return@change
            session.row = session.row.copy(disconnectedAtMs = clock())
            if (session.exchanges == 0) sessions.remove(id)
        }
    }

    /** Empties the list; sessions still connected stay, without their exchanges. */
    fun clear() {
        change {
            exchanges.clear()
            exchangeIds.clear()
            bytes = 0
            sessions.values.removeAll { it.row.disconnectedAtMs != null }
            sessions.values.forEach { it.exchanges = 0 }
        }
    }

    /** The live sessions, oldest first. */
    fun sessions(): List<StoredSession> = synchronized(lock) { sessions.values.map { it.row } }

    /** The live sessions, oldest first, again after each change. */
    fun observeSessions(): Flow<List<StoredSession>> = observe(sessionChanges) { sessions() }

    fun session(id: Long): StoredSession? = synchronized(lock) { sessions[id]?.row }

    /** A page of the exchanges [filter] matches in arrival order; none when it names a run, which is stored. */
    fun searchExchanges(
        filter: ExchangeFilter,
        afterId: Long = 0,
        limit: Long = Storage.PAGE_SIZE,
    ): List<StoredExchange> = search(filter, afterId, limit) { it.row }

    /** [searchExchanges] with each exchange's body sizes, or the bytes a connection moved, as the store gives them. */
    fun searchExchangeSizes(
        filter: ExchangeFilter,
        afterId: Long = 0,
        limit: Long = Storage.PAGE_SIZE,
    ): List<ExchangeSizes> = search(filter, afterId, limit) { it.sizes() }

    /** The first [limit] exchanges [filter] matches, again after each change. */
    fun observeExchanges(
        filter: ExchangeFilter,
        limit: Long = Storage.PAGE_SIZE,
    ): Flow<List<StoredExchange>> = observe { searchExchanges(filter, 0, limit) }

    /** The hosts of session [sessionId]'s exchanges, or of every one when null, sorted, again after each change. */
    fun observeHosts(sessionId: Long? = null): Flow<List<String>> =
        observe {
            synchronized(lock) {
                exchanges.values
                    .mapNotNullTo(sortedSetOf()) { exchange ->
                        exchange.row.host.takeIf {
                            sessionId == null ||
                                exchange.row.sessionId == sessionId
                        }
                    }.toList()
            }
        }

    /** One exchange with its events as the device sent them; its rule's name is the store's to give. */
    fun exchange(id: Long): ExchangeDetail? =
        synchronized(lock) {
            val exchange = exchanges[id] ?: return null
            val row = exchange.row
            ExchangeDetail(
                exchange = row,
                request = exchange.request,
                response = exchange.response,
                replayOfId = row.replayOf?.let { exchangeIds[row.sessionId to it] },
                framesKept = exchange.frames.size.toLong(),
            )
        }

    /** The original's exchange id of each replay in [exchanges] still in the list, by the replay's id. */
    fun replayOriginals(exchanges: List<StoredExchange>): Map<Long, Long> =
        synchronized(lock) {
            exchanges
                .mapNotNull { replay -> replay.replayOf?.let { exchangeIds[replay.sessionId to it] }?.let { replay.id to it } }
                .toMap()
        }

    /** What [Storage.save] needs of each exchange of [ids] still in the list, in arrival order; the others are left out. */
    fun copies(ids: Collection<Long>): List<LiveCopy> =
        synchronized(lock) {
            ids.distinct().sorted().mapNotNull { id ->
                val exchange = exchanges[id] ?: return@mapNotNull null
                val session = sessions[exchange.row.sessionId]?.row ?: return@mapNotNull null
                LiveCopy(id, exchange.row.requestId, session, exchange.envelopes())
            }
        }

    /** A page of an exchange's WebSocket frames, in the order the device sent them. */
    fun frames(
        exchangeId: Long,
        afterId: Long = 0,
        limit: Long = Storage.PAGE_SIZE,
    ): List<StoredFrame> =
        synchronized(lock) {
            exchanges[exchangeId]
                ?.frames
                ?.asSequence()
                ?.filter { it.id > afterId }
                ?.take(limit.toInt())
                ?.toList()
                .orEmpty()
        }

    private fun <T> search(
        filter: ExchangeFilter,
        afterId: Long,
        limit: Long,
        map: (LiveExchange) -> T,
    ): List<T> {
        if (filter.runId != null) return emptyList()
        val text = filter.text?.takeIf { it.isNotEmpty() }?.asciiLowercase()
        return synchronized(lock) {
            exchanges.values
                .asSequence()
                .filter { exchange ->
                    val row = exchange.row
                    val hello = sessions[row.sessionId]?.row?.hello
                    row.id > afterId &&
                        (filter.sessionId == null || row.sessionId == filter.sessionId) &&
                        (filter.deviceId == null || hello?.device_id == filter.deviceId) &&
                        (filter.appId == null || hello?.app_id == filter.appId) &&
                        (filter.method == null || row.method == filter.method) &&
                        (filter.statusClasses.isEmpty() || filter.statusClasses.any { row.statusCode?.minus(it.from) in 0..99 }) &&
                        (filter.hosts.isEmpty() || row.host in filter.hosts) &&
                        (!filter.failedOnly || row.failed) &&
                        (text == null || row.url?.asciiLowercase()?.contains(text) == true)
                }.take(limit.toInt())
                .map(map)
                .toList()
        }
    }

    private fun <T> observe(
        source: MutableStateFlow<Long> = changes,
        read: () -> T,
    ): Flow<T> = source.map { read() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private inline fun change(block: () -> Unit) {
        synchronized(lock) {
            block()
            changes.value += 1
            sessionChanges.value += 1
        }
    }

    // IOException for a payload that does not decode; a type it does not know is dropped.
    private fun network(
        session: LiveSession,
        envelope: Envelope,
    ) {
        val at = envelope.timestamp_ms
        when (envelope.type) {
            "RequestStarted" -> {
                val event = RequestStarted.ADAPTER.decode(envelope.payload)
                val exchange = exchange(session, event.request_id)
                exchange.row =
                    exchange.row.copy(
                        method = event.method,
                        url = event.url,
                        host = hostOf(event.url),
                        startedAtMs = at,
                        captureLayer = event.capture_layer,
                        mockRuleId = event.mock_rule_id,
                        replayOf = event.replay_of,
                    )
                exchange.request = event
                exchange.resize(event.body, exchange.response?.body)
            }

            "ResponseReceived" -> {
                val event = ResponseReceived.ADAPTER.decode(envelope.payload)
                val exchange = exchange(session, event.request_id)
                // ADR-0104: status 0 is no status, as a closed `CONNECTION` sends.
                exchange.row =
                    exchange.row.copy(
                        statusCode = event.status_code.takeIf { it != 0 },
                        grpcStatus = grpcStatusOf(event)?.toInt(),
                        endedAtMs = at,
                    )
                exchange.response = event
                exchange.resize(exchange.request?.body, event.body)
            }

            "RequestFailed" -> {
                val event = RequestFailed.ADAPTER.decode(envelope.payload)
                val exchange = exchange(session, event.request_id)
                exchange.row = exchange.row.copy(failed = true, error = event.error, endedAtMs = at)
            }

            "WebSocketFrame" -> {
                val event = WebSocketFrame.ADAPTER.decode(envelope.payload)
                val exchange = exchange(session, event.request_id)
                exchange.frames +=
                    StoredFrame(
                        id = nextId++,
                        atMs = at,
                        direction = event.direction,
                        opcode = event.opcode,
                        payload = event.payload,
                        payloadTruncated = event.payload_truncated,
                        payloadSize = event.payload_size,
                    )
                exchange.row = exchange.row.copy(frameCount = exchange.row.frameCount + 1)
                exchange.addFrameBytes(event.payload.size + FRAME_OVERHEAD_BYTES)
                while (exchange.frames.size > maxFramesPerExchange || exchange.frameBytes > maxBytes / 4) {
                    exchange.addFrameBytes(
                        -(
                            exchange.frames
                                .removeFirst()
                                .payload.size + FRAME_OVERHEAD_BYTES
                        ),
                    )
                }
            }
        }
    }

    // An event for a request not in the list yet creates it, as the store does.
    private fun exchange(
        session: LiveSession,
        requestId: String,
    ): LiveExchange {
        val key = session.row.id to requestId
        exchangeIds[key]?.let { id -> exchanges[id]?.let { return it } }
        val id = nextId++
        val exchange =
            LiveExchange(
                StoredExchange(
                    id = id,
                    sessionId = session.row.id,
                    requestId = requestId,
                    method = null,
                    url = null,
                    host = null,
                    statusCode = null,
                    failed = false,
                    startedAtMs = null,
                    endedAtMs = null,
                    captureLayer = CaptureLayer.CAPTURE_LAYER_UNSPECIFIED,
                    error = null,
                    frameCount = 0,
                ),
            )
        exchanges[id] = exchange
        exchangeIds[key] = id
        session.exchanges++
        return exchange
    }

    private fun LiveExchange.resize(
        request: ByteString?,
        response: ByteString?,
    ) {
        val bodies = (request?.size ?: 0).toLong() + (response?.size ?: 0)
        this@LiveTraffic.bytes += bodies - bodyBytes
        bodyBytes = bodies
    }

    private fun LiveExchange.addFrameBytes(delta: Long) {
        frameBytes += delta
        this@LiveTraffic.bytes += delta
    }

    // The events the store would have been given, as far as the list kept them.
    private fun LiveExchange.envelopes(): List<Envelope> {
        fun envelope(
            type: String,
            payload: ByteString,
            atMs: Long?,
        ) = Envelope(Protocol.CHANNEL_NETWORK, type, payload, atMs ?: 0)
        val requestId = row.requestId
        return buildList {
            request?.let { add(envelope("RequestStarted", it.encodeByteString(), row.startedAtMs)) }
            frames.forEach {
                val frame = WebSocketFrame(requestId, it.direction, it.opcode, it.payload, it.payloadTruncated, it.payloadSize)
                add(envelope("WebSocketFrame", frame.encodeByteString(), it.atMs))
            }
            response?.let { add(envelope("ResponseReceived", it.encodeByteString(), row.endedAtMs)) }
            if (row.failed) add(envelope("RequestFailed", RequestFailed(requestId, row.error.orEmpty()).encodeByteString(), row.endedAtMs))
        }
    }

    private fun LiveExchange.sizes(): ExchangeSizes =
        if (row.isConnection) {
            ExchangeSizes(row, requestBytes = response?.sent_size ?: 0, responseBytes = response?.body_size ?: 0)
        } else {
            ExchangeSizes(row, requestBytes = request?.body?.size?.toLong() ?: 0, responseBytes = response?.body?.size?.toLong() ?: 0)
        }

    // The oldest first, until both bounds hold; a session that ended goes with its last exchange.
    private fun trim() {
        while (exchanges.size > maxExchanges || (bytes > maxBytes && exchanges.isNotEmpty())) {
            val oldest = exchanges.values.first()
            exchanges.remove(oldest.row.id)
            exchangeIds.remove(oldest.row.sessionId to oldest.row.requestId)
            bytes -= oldest.bytes
            val session = sessions[oldest.row.sessionId] ?: continue
            session.exchanges--
            if (session.exchanges == 0 && session.row.disconnectedAtMs != null) sessions.remove(session.row.id)
        }
    }

    companion object {
        /** The first live id: far above any store row's, still exact as a JSON number. */
        const val FIRST_ID = 1L shl 50

        /** The run a live session reports: none. */
        const val NO_RUN = 0L

        const val MAX_EXCHANGES = 10_000
        const val MAX_BYTES = 128L * 1024 * 1024
        const val MAX_FRAMES_PER_EXCHANGE = 10_000

        /** What a kept frame costs beside its payload, so empty frames count toward [MAX_BYTES]. */
        const val FRAME_OVERHEAD_BYTES = 128L

        /** True when [id] is a live session's, exchange's or frame's. */
        fun isLive(id: Long): Boolean = id >= FIRST_ID
    }
}

/** One live exchange as [LiveTraffic.copies] gives it: its live [id], its [session] and the events that make it. */
class LiveCopy(
    val id: Long,
    val requestId: String,
    val session: StoredSession,
    val envelopes: List<Envelope>,
)

private fun String.asciiLowercase(): String = String(CharArray(length) { this[it].let { c -> if (c in 'A'..'Z') c + 32 else c } })
