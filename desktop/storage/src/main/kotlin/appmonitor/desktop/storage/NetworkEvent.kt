package appmonitor.desktop.storage

import appmonitor.v1.Envelope
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import java.net.URI
import java.net.URISyntaxException

/** A `network` envelope as the store and the live list both read it. */
internal sealed interface NetworkEvent {
    class Started(
        val event: RequestStarted,
    ) : NetworkEvent {
        val host: String? = hostOf(event.url)
    }

    class Responded(
        val event: ResponseReceived,
    ) : NetworkEvent {
        // ADR-0104: status 0 is no status, as a closed `CONNECTION` sends.
        val statusCode: Int? = event.status_code.takeIf { it != 0 }
        val grpcStatus: Long? = grpcStatusOf(event)
    }

    class Failed(
        val event: RequestFailed,
    ) : NetworkEvent

    class Frame(
        val event: WebSocketFrame,
    ) : NetworkEvent
}

/** Null for a type this desktop does not know; IOException for a payload that does not decode. */
internal fun Envelope.networkEvent(): NetworkEvent? =
    when (type) {
        "RequestStarted" -> NetworkEvent.Started(RequestStarted.ADAPTER.decode(payload))
        "ResponseReceived" -> NetworkEvent.Responded(ResponseReceived.ADAPTER.decode(payload))
        "RequestFailed" -> NetworkEvent.Failed(RequestFailed.ADAPTER.decode(payload))
        "WebSocketFrame" -> NetworkEvent.Frame(WebSocketFrame.ADAPTER.decode(payload))
        else -> null
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
