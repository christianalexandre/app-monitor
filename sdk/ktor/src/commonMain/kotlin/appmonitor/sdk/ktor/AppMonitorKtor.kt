package appmonitor.sdk.ktor

import appmonitor.protocol.Protocol
import appmonitor.sdk.core.CallCapture
import appmonitor.sdk.core.CaptureSink
import appmonitor.sdk.core.CapturedBody
import appmonitor.sdk.core.Masking
import appmonitor.sdk.core.MonitorSink
import appmonitor.sdk.core.emit
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.statement.HttpResponseContainer
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isWebsocket
import io.ktor.websocket.WebSocketSession
import kotlin.coroutines.cancellation.CancellationException

/**
 * `install(AppMonitorKtor)`: each request sent, redirect hops included, its response or failure go to the
 * running `AppMonitor` (ADR-0062, ADR-0063), with the frames of a WebSocket it opens; a call is held at a breakpoint
 * and sent again when the desktop asks (ADR-0111). Nothing is captured until `AppMonitor.start` runs.
 */
public val AppMonitorKtor: ClientPlugin<Unit> = appMonitorKtor(MonitorSink)

internal fun appMonitorKtor(
    sink: CaptureSink,
    marksCaptured: Boolean? = null,
): ClientPlugin<Unit> =
    createClientPlugin("AppMonitorKtor") {
        val httpClient = client
        val marks = marksCaptured ?: (MARKS_CAPTURED_REQUESTS && passesAnotherCapture(client.engine))
        // ADR-0122 C.1: before DefaultRequest, HttpCookies or any other plugin changes the request.
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            if (sink.options != null) context.attributes.put(APP_REQUEST, HttpRequestBuilder().takeFrom(context))
        }
        // ADR-0069: after HttpSendPipeline.State, where HttpCookies adds Cookie; once per hop.
        client.sendPipeline.intercept(HttpSendPipeline.Monitoring) { sent ->
            val options = sink.options
            context.attributes.remove(REQUEST_ID)
            // ADR-0064: the Swift SDK skips a task carrying it; a redirect hop copies the previous hop's headers.
            if (marks) context.headers.remove(Protocol.CAPTURE_HEADER)
            if (options == null || sent !is OutgoingContent) return@intercept
            val request = context
            if (marks) request.headers[Protocol.CAPTURE_HEADER] = Protocol.CAPTURE_HEADER_KTOR
            val capture = CallCapture(sink)
            request.attributes.put(REQUEST_ID, capture.id)
            val masking = Masking(options)
            val replayOf = request.attributes.getOrNull(REPLAY_OF)
            val webSocket = request.url.protocol.isWebsocket()
            val content = if (webSocket) sent else capture.holdRequest(request, sent, masking, options.maxBodyBytes, replayOf)
            if (content !== sent) subject = content
            val mock = if (webSocket) null else sink.mockFor(request.method.value, request.url.buildString())
            capture.started {
                requestStarted(capture.id, request, content, masking, content.capture(options.maxBodyBytes), mock?.id, replayOf)
            }
            val app = request.attributes.getOrNull(APP_REQUEST)
            sink.keepForReplay(capture.id, content.contentLength ?: -1) { replay(httpClient, app, content, capture.id) }
            if (mock != null) {
                // ADR-0115: the engine and the receive pipeline are skipped; the Send hooks still see the call.
                val answered = answer(httpClient, capture, request, content, mock)
                val known = CapturedBody.whole(checkNotNull(mock.response).body)
                val (call, body) = capture.holdResponse(httpClient, request, content, answered, known, masking, options.maxBodyBytes)
                capture.received { responseReceived(capture.id, call.response, masking, body) }
                subject = call
                finish()
                return@intercept
            }
            val call =
                try {
                    proceed()
                } catch (e: Throwable) {
                    capture.failed(e)
                    throw e
                }
            if (call !is HttpClientCall) return@intercept
            val captured =
                try {
                    call.response.capture(options.maxBodyBytes)
                } catch (e: CancellationException) {
                    capture.failed(e)
                    throw e
                } catch (e: Exception) {
                    call.response.unread()
                }
            if (webSocket) {
                capture.received { responseReceived(capture.id, call.response, masking, captured) }
                return@intercept
            }
            // Read by the pipeline once this interceptor returns, as Monitoring's mock answer is.
            val (held, body) = capture.holdResponse(httpClient, request, content, call, captured, masking, options.maxBodyBytes)
            if (held !== call) subject = held
            capture.received { responseReceived(capture.id, held.response, masking, body) }
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
