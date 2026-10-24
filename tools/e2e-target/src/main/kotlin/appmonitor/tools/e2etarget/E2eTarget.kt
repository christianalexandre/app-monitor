package appmonitor.tools.e2etarget

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The HTTP and WebSocket target of the end to ends (ADR-0074): the httpbin.org paths the samples call,
 * and a WebSocket echo on [WEBSOCKET_PATH]. Bound to loopback, which the emulator reaches as `10.0.2.2`.
 */
internal fun e2eTarget(
    port: Int,
    log: (String) -> Unit = {},
): EmbeddedServer<*, *> = embeddedServer(CIO, host = "127.0.0.1", port = port) { routes(log) }

// The samples build these paths on the `target` launch parameter; renaming one breaks `make e2e-*`.
internal const val WEBSOCKET_PATH = "/ws"
private const val MAX_DELAY_SECONDS = 10
private const val MAX_BYTES = 100_000

private fun Application.routes(log: (String) -> Unit) {
    install(WebSockets)
    intercept(ApplicationCallPipeline.Monitoring) {
        log("${call.request.httpMethod.value} ${call.request.uri}")
    }
    routing {
        get("/json") { call.respondText("""{"slideshow":{"title":"app-monitor e2e"}}""", ContentType.Application.Json) }
        post("/post") { call.respondText(echo(call.receive<ByteArray>()), ContentType.Application.Json) }
        put("/put") { call.respondText(echo(call.receive<ByteArray>()), ContentType.Application.Json) }
        get("/status/{code}") {
            val code = call.parameters["code"]?.toIntOrNull()?.takeIf { it in 100..599 } ?: 400
            call.respondText("", status = HttpStatusCode.fromValue(code))
        }
        get("/delay/{seconds}") {
            val seconds = call.parameters["seconds"]?.toIntOrNull()?.coerceIn(0, MAX_DELAY_SECONDS) ?: 0
            delay(seconds.seconds)
            call.respondText("""{"delayed":$seconds}""", ContentType.Application.Json)
        }
        get("/bytes/{count}") {
            val count = call.parameters["count"]?.toIntOrNull()?.coerceIn(0, MAX_BYTES) ?: 0
            call.respondBytes(Random.nextBytes(count), ContentType.Application.OctetStream)
        }
        webSocket(WEBSOCKET_PATH) {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> send(Frame.Text(frame.readText()))
                    is Frame.Binary -> send(Frame.Binary(true, frame.data))
                    else -> Unit
                }
            }
        }
    }
}

private fun echo(body: ByteArray) = """{"received":${body.size}}"""
