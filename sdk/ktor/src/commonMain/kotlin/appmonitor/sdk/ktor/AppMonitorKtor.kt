package appmonitor.sdk.ktor

import appmonitor.protocol.Protocol
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.statement.HttpResponseContainer
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.content.OutgoingContent
import io.ktor.websocket.WebSocketSession
import kotlin.coroutines.cancellation.CancellationException

/**
 * `install(AppMonitorKtor)`: each request sent, redirect hops included, its response or failure go to the
 * running `AppMonitor` (ADR-0062, ADR-0063), with the frames of a WebSocket it opens; a call is sent again when the
 * desktop asks (ADR-0111). Nothing is captured until `AppMonitor.start` runs.
 */
public val AppMonitorKtor: ClientPlugin<Unit> = appMonitorKtor(MonitorSink)

internal fun appMonitorKtor(
    sink: CaptureSink,
    marksCaptured: Boolean = MARKS_CAPTURED_REQUESTS,
): ClientPlugin<Unit> =
    createClientPlugin("AppMonitorKtor") {
        val httpClient = client
        // ADR-0122 C.1: before DefaultRequest, HttpCookies or any other plugin changes the request.
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            if (sink.options != null) context.attributes.put(APP_REQUEST, HttpRequestBuilder().takeFrom(context))
        }
        // ADR-0069: after HttpSendPipeline.State, where HttpCookies adds Cookie; once per hop.
        client.sendPipeline.intercept(HttpSendPipeline.Monitoring) { content ->
            val options = sink.options
            context.attributes.remove(REQUEST_ID)
            // ADR-0064: the Swift SDK skips a task carrying it; a redirect hop copies the previous hop's headers.
            if (marksCaptured) context.headers.remove(Protocol.CAPTURE_HEADER)
            if (options == null || content !is OutgoingContent) return@intercept
            val request = context
            if (marksCaptured) request.headers[Protocol.CAPTURE_HEADER] = Protocol.CAPTURE_HEADER_KTOR
            val id = newRequestId()
            request.attributes.put(REQUEST_ID, id)
            val masking = Masking(options)
            val mock = sink.mockFor(request.method.value, request.url.buildString())
            val replayOf = request.attributes.getOrNull(REPLAY_OF)
            sink.emit("RequestStarted") {
                requestStarted(id, request, content, masking, content.capture(options.maxBodyBytes), mock?.id, replayOf)
            }
            val app = request.attributes.getOrNull(APP_REQUEST)
            sink.keepForReplay(id) { replay(httpClient, app, content, id) }
            if (mock != null) {
                // ADR-0115: the engine and the receive pipeline are skipped; the Send hooks still see the call.
                subject = answer(httpClient, sink, request, content, mock, id, masking)
                finish()
                return@intercept
            }
            val call =
                try {
                    proceed()
                } catch (e: Throwable) {
                    sink.emit("RequestFailed") { requestFailed(id, e) }
                    throw e
                }
            if (call !is HttpClientCall) return@intercept
            val response = call.response
            val body =
                try {
                    response.capture(options.maxBodyBytes)
                } catch (e: CancellationException) {
                    sink.emit("RequestFailed") { requestFailed(id, e) }
                    throw e
                } catch (e: Exception) {
                    response.unread()
                }
            sink.emit("ResponseReceived") { responseReceived(id, response, masking, body) }
        }

        // ADR-0070: before HttpResponsePipeline.Transform, where WebSockets wraps the engine's session.
        client.responsePipeline.intercept(HttpResponsePipeline.Parse) { (info, body) ->
            if (body !is WebSocketSession) return@intercept
            val id = context.request.attributes.getOrNull(REQUEST_ID) ?: return@intercept
            val session =
                body.capturing { frame, direction ->
                    val limit = sink.options?.maxBodyBytes
                    if (limit != null) sink.emit("WebSocketFrame") { webSocketFrame(id, direction, frame, limit) }
                }
            proceedWith(HttpResponseContainer(info, session))
        }
    }
