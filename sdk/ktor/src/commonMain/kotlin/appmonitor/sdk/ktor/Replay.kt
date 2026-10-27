package appmonitor.sdk.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isWebsocket
import io.ktor.util.AttributeKey
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The request as the app built it, before any plugin ran (ADR-0122 C.1); a replay starts from it. */
internal val APP_REQUEST: AttributeKey<HttpRequestBuilder> = AttributeKey("AppMonitorAppRequest")

/** On a replay's builder: the request id it sends again, for `RequestStarted.replay_of`. */
internal val REPLAY_OF: AttributeKey<String> = AttributeKey("AppMonitorReplayOf")

/**
 * ADR-0122 C.1: [app] sent again through [client], every plugin run as for the app's own call, its response read
 * so it is captured whole; null once launched, else the reason the desktop shows.
 */
internal fun replay(
    client: HttpClient,
    app: HttpRequestBuilder?,
    content: OutgoingContent,
    id: String,
): String? {
    if (app == null) return "The app no longer keeps this request"
    if (app.url.protocol.isWebsocket()) return "A WebSocket is not opened again"
    if (content is OutgoingContent.ReadChannelContent) return "Its body could be sent only once"
    if (!client.coroutineContext.isActive) return "The app closed its HTTP client"
    val builder = HttpRequestBuilder().takeFrom(app).apply { attributes.put(REPLAY_OF, id) }
    client.launch {
        try {
            client.request(builder)
        } catch (e: Exception) {
            // Captured as RequestFailed by the send pipeline; a throw here would cancel the app's client.
        }
    }
    return null
}
