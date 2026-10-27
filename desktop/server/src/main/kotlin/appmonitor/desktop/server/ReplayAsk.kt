package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.ReplayRequest
import appmonitor.v1.RequestStarted
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** The request id of [envelope] when it starts an app's own request to [path], so `--replay` asks it again; else null. */
internal fun replayTarget(
    envelope: Envelope,
    path: String,
): String? {
    if (envelope.channel != Protocol.CHANNEL_NETWORK || envelope.type != "RequestStarted") return null
    val started =
        try {
            RequestStarted.ADAPTER.decode(envelope.payload)
        } catch (_: IOException) {
            return null
        }
    if (started.replay_of != null) return null
    val requestPath =
        try {
            URI(started.url).rawPath
        } catch (_: URISyntaxException) {
            return null
        }
    return started.request_id.takeIf { requestPath == path }
}

internal fun replayRequestEnvelope(
    requestId: String,
    atMs: Long,
) = Envelope(
    channel = Protocol.CHANNEL_INTERACTION,
    type = "ReplayRequest",
    payload = ReplayRequest(request_id = requestId).encodeByteString(),
    timestamp_ms = atMs,
)
