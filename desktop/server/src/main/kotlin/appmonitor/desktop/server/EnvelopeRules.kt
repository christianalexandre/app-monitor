package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import com.squareup.wire.ProtoAdapter
import java.io.IOException

/** Why [this] breaks the session's protocol (ADR-0021), or `null` when it may be delivered. */
internal fun Envelope.violation(acceptedChannels: List<String>): String? {
    if (channel !in acceptedChannels) return "Envelope on a channel not accepted in HelloAck"
    if (channel != Protocol.CHANNEL_NETWORK) return null
    val requestId = NETWORK_REQUEST_IDS[type] ?: return null
    val id =
        try {
            requestId(payload.toByteArray())
        } catch (_: IOException) {
            return "Undecodable $type payload"
        }
    return if (id.isEmpty()) "$type without request_id" else null
}

private fun <T> ProtoAdapter<T>.requestId(id: (T) -> String): (ByteArray) -> String = { id(decode(it)) }

private val NETWORK_REQUEST_IDS: Map<String, (ByteArray) -> String> =
    mapOf(
        "RequestStarted" to RequestStarted.ADAPTER.requestId { it.request_id },
        "ResponseReceived" to ResponseReceived.ADAPTER.requestId { it.request_id },
        "RequestFailed" to RequestFailed.ADAPTER.requestId { it.request_id },
        "WebSocketFrame" to WebSocketFrame.ADAPTER.requestId { it.request_id },
    )
