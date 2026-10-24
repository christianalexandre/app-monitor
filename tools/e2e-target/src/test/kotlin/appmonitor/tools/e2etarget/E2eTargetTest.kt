package appmonitor.tools.e2etarget

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class E2eTargetTest {
    private val requests = mutableListOf<String>()
    private val server = e2eTarget(port = 0) { synchronized(requests) { requests += it } }.start(wait = false)
    private val base = "http://127.0.0.1:${runBlocking {
        server.engine
            .resolvedConnectors()
            .first()
            .port
    }}"
    private val client =
        HttpClient(CIO) {
            install(WebSockets)
            install(HttpTimeout)
        }

    @AfterTest
    fun tearDown() {
        client.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun `serves the paths the samples call`() =
        runBlocking {
            assertEquals(200, client.get("$base/json").status.value)
            assertEquals("""{"received":5}""", client.post("$base/post") { setBody("hello") }.bodyAsText())
            assertEquals("""{"received":3}""", client.put("$base/put") { setBody("abc") }.bodyAsText())
            assertEquals(404, client.get("$base/status/404").status.value)
            assertEquals(4096, client.get("$base/bytes/4096").bodyAsBytes().size)
            assertEquals(
                listOf("GET /json", "POST /post", "PUT /put", "GET /status/404", "GET /bytes/4096"),
                synchronized(requests) {
                    requests.toList()
                },
            )
        }

    @Test
    fun `delays the response by the seconds in the path`() =
        runBlocking {
            val start = TimeSource.Monotonic.markNow()
            assertEquals(200, client.get("$base/delay/1").status.value)
            assertTrue(start.elapsedNow().inWholeMilliseconds >= 1_000)
        }

    @Test
    fun `echoes websocket text frames`() =
        runBlocking {
            var echoed: String? = null
            client.webSocket("${base.replace("http", "ws")}$WEBSOCKET_PATH") {
                send(Frame.Text("hello"))
                echoed = (incoming.receive() as Frame.Text).readText()
            }
            assertEquals("hello", echoed)
        }
}
