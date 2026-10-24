package appmonitor.sdk.ktor

import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.content.OutgoingContent
import kotlin.coroutines.cancellation.CancellationException

/**
 * `install(AppMonitorKtor)`: each request sent, redirect hops included, its response or failure go to the
 * running `AppMonitor` (ADR-0062, ADR-0063); nothing is captured until `AppMonitor.start` runs.
 */
public val AppMonitorKtor: ClientPlugin<Unit> = appMonitorKtor(MonitorSink)

internal fun appMonitorKtor(sink: CaptureSink): ClientPlugin<Unit> =
    createClientPlugin("AppMonitorKtor") {
        // ADR-0069: after HttpSendPipeline.State, where HttpCookies adds Cookie; once per hop.
        client.sendPipeline.intercept(HttpSendPipeline.Monitoring) { content ->
            val options = sink.options
            if (options == null || content !is OutgoingContent) return@intercept
            val request = context
            val id = newRequestId()
            val masking = Masking(options)
            sink.emit("RequestStarted") { requestStarted(id, request, content, masking, content.capture(options.maxBodyBytes)) }
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
                    throw e
                } catch (e: Exception) {
                    return@intercept
                }
            sink.emit("ResponseReceived") { responseReceived(id, response, masking, body) }
        }
    }
