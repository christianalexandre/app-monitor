package appmonitor.sample.kmp

import appmonitor.sdk.ktor.AppMonitorKtor
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException

private const val HTTPBIN = "https://httpbin.org"
private const val ECHO = "wss://echo.websocket.org"
private const val ECHO_MESSAGE = "hello from the kmp sample"

/** The engine of each platform: OkHttp on Android, Darwin on iOS (ADR-0063). */
internal expect val engine: HttpClientEngineFactory<*>

/** The calls behind the sample's buttons, against [target] when set, else public services the sample does not control. */
internal class SampleCalls(
    target: String?,
) {
    private val http = target ?: HTTPBIN
    private val echo = target?.let { "ws${it.removePrefix("http")}/ws" } ?: ECHO
    private val client =
        HttpClient(engine) {
            install(AppMonitorKtor)
            install(WebSockets)
            install(HttpTimeout) { requestTimeoutMillis = 10_000 }
        }

    suspend fun getJson(): String = call { client.get("$http/json") }

    suspend fun postJson(): String =
        call {
            client.post("$http/post") {
                contentType(ContentType.Application.Json)
                setBody("""{"hello":"desktop"}""")
            }
        }

    suspend fun notFound(): String = call { client.get("$http/status/404") }

    suspend fun timeout(): String = call { client.get("$http/delay/10") { timeout { requestTimeoutMillis = 2_000 } } }

    /** echo.websocket.org greets first, so frames are read until the echo comes back. */
    suspend fun webSocketEcho(): String =
        attempt("WebSocket failed") {
            var result = "no echo"
            client.webSocket(echo) {
                send(Frame.Text(ECHO_MESSAGE))
                for (frame in incoming) {
                    if (frame is Frame.Text && frame.readText() == ECHO_MESSAGE) {
                        result = "echoed \"$ECHO_MESSAGE\""
                        break
                    }
                }
                close(CloseReason(CloseReason.Codes.NORMAL, "done"))
            }
            result
        }

    private suspend fun call(request: suspend () -> HttpResponse): String =
        attempt("failed") {
            val response = request()
            "${response.status.value}, ${response.bodyAsBytes().size} bytes"
        }

    private suspend fun attempt(
        failure: String,
        block: suspend () -> String,
    ): String =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "$failure: $e"
        }
}
