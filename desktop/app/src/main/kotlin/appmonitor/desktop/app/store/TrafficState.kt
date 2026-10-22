package appmonitor.desktop.app.store

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import okio.ByteString
import java.io.IOException

internal data class Device(
    val sessionId: String,
    val name: String,
    val platform: Platform,
    val osVersion: String,
    val appId: String,
    val appVersion: String,
    val connected: Boolean = true,
)

/** A body as the device sent it: [bytes] may hold fewer than [originalSize] bytes (ADR-0020). */
internal data class CapturedBody(
    val bytes: ByteString = ByteString.EMPTY,
    val truncated: Boolean = false,
    val originalSize: Long? = null,
)

/** One HTTP request and its outcome. Fields stay null until the matching event arrives. */
internal data class HttpExchange(
    val sessionId: String,
    val requestId: String,
    val method: String? = null,
    val url: String? = null,
    val requestHeaders: List<Header> = emptyList(),
    val requestBody: CapturedBody = CapturedBody(),
    val startedAtMs: Long? = null,
    val statusCode: Int? = null,
    val responseHeaders: List<Header> = emptyList(),
    val responseBody: CapturedBody = CapturedBody(),
    val error: String? = null,
    val endedAtMs: Long? = null,
) {
    val durationMs: Long?
        get() = if (startedAtMs != null && endedAtMs != null) endedAtMs - startedAtMs else null
}

/** Everything the desktop has received, as an immutable snapshot. */
internal data class TrafficState(
    val devices: List<Device> = emptyList(),
    val exchanges: List<HttpExchange> = emptyList(),
) {
    fun exchangesOf(sessionId: String): List<HttpExchange> = exchanges.filter { it.sessionId == sessionId }

    fun connected(
        sessionId: String,
        hello: Hello,
    ): TrafficState =
        copy(
            devices =
                devices +
                    Device(
                        sessionId = sessionId,
                        name = hello.device_name,
                        platform = hello.platform,
                        osVersion = hello.os_version,
                        appId = hello.app_id,
                        appVersion = hello.app_version,
                    ),
        )

    fun disconnected(sessionId: String): TrafficState =
        copy(devices = devices.map { if (it.sessionId == sessionId) it.copy(connected = false) else it })

    /** Applies a `network` envelope; anything else, or an undecodable payload, leaves the state as is. */
    fun received(
        sessionId: String,
        envelope: Envelope,
    ): TrafficState {
        if (envelope.channel != Protocol.CHANNEL_NETWORK) return this
        val at = envelope.timestamp_ms
        return try {
            when (envelope.type) {
                "RequestStarted" -> {
                    RequestStarted.ADAPTER.decode(envelope.payload).let { event ->
                        update(sessionId, event.request_id) {
                            it.copy(
                                method = event.method,
                                url = event.url,
                                requestHeaders = event.headers,
                                requestBody = CapturedBody(event.body, event.body_truncated, event.body_size),
                                startedAtMs = at,
                            )
                        }
                    }
                }

                "ResponseReceived" -> {
                    ResponseReceived.ADAPTER.decode(envelope.payload).let { event ->
                        update(sessionId, event.request_id) {
                            it.copy(
                                statusCode = event.status_code,
                                responseHeaders = event.headers,
                                responseBody = CapturedBody(event.body, event.body_truncated, event.body_size),
                                endedAtMs = at,
                            )
                        }
                    }
                }

                "RequestFailed" -> {
                    RequestFailed.ADAPTER.decode(envelope.payload).let { event ->
                        update(sessionId, event.request_id) { it.copy(error = event.error, endedAtMs = at) }
                    }
                }

                // DEBT-0003
                else -> {
                    this
                }
            }
        } catch (_: IOException) {
            this
        }
    }

    // An event for an unknown request still creates it, so nothing the device sent is hidden.
    private fun update(
        sessionId: String,
        requestId: String,
        change: (HttpExchange) -> HttpExchange,
    ): TrafficState {
        val index = exchanges.indexOfFirst { it.sessionId == sessionId && it.requestId == requestId }
        if (index < 0) return copy(exchanges = exchanges + change(HttpExchange(sessionId, requestId)))
        return copy(exchanges = exchanges.toMutableList().also { it[index] = change(it[index]) })
    }
}
