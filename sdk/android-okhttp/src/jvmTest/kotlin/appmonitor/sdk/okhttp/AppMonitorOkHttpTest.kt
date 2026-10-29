package appmonitor.sdk.okhttp

import appmonitor.protocol.Protocol.CAPTURE_HEADER
import appmonitor.sdk.core.AppMonitorOptions
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocketListener
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppMonitorOkHttpTest {
    private fun OkHttpClient.Builder.installed(): OkHttpClient = apply { AppMonitorOkHttp.install(this) }.build()

    private fun OkHttpClient.monitors(): Int = (interceptors + networkInterceptors).count { it is AppMonitorInterceptor }

    @Test
    fun `adds the interceptor to an empty builder`() {
        val client = OkHttpClient.Builder().installed()

        assertEquals(1, client.monitors())
        assertEquals(1, client.interceptors.size)
    }

    @Test
    fun `adds none to a client made with newBuilder`() {
        val client = OkHttpClient.Builder().installed()

        assertEquals(1, client.newBuilder().installed().monitors())
    }

    @Test
    fun `adds none beside a hand-written line`() {
        val application = OkHttpClient.Builder().addInterceptor(AppMonitorInterceptor()).installed()
        val network = OkHttpClient.Builder().addNetworkInterceptor(AppMonitorInterceptor()).installed()

        assertEquals(1, application.monitors())
        assertEquals(1, network.monitors())
        assertEquals(0, network.interceptors.size)
    }
}

/** `AppMonitorOkHttp.newWebSocket`, which the plugin calls first in `OkHttpClient.newWebSocket` (ADR-0159). */
class AppMonitorOkHttpWebSocketTest {
    private val server = MockWebServer().apply { start() }
    private val listener = object : WebSocketListener() {}

    @AfterTest
    fun stopServer() {
        server.close()
    }

    private fun request(build: Request.Builder.() -> Unit = {}) =
        Request
            .Builder()
            .url(server.url("/ws"))
            .apply(build)
            .build()

    @Test
    fun `captures a socket while the monitor runs`() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
        val sink = RecordingSink()

        val socket = AppMonitorOkHttp.newWebSocket(OkHttpClient(), request(), listener, sink)

        assertNotNull(socket)
        assertEquals(server.url("/ws").toString(), sink.started().url)
        socket.cancel()
    }

    @Test
    fun `lets OkHttp open it while the monitor is idle`() {
        assertNull(AppMonitorOkHttp.newWebSocket(OkHttpClient(), request(), listener, RecordingSink(options = null)))
    }

    @Test
    fun `lets OkHttp open the factory's own socket`() {
        val tagged = request { tag(WebSocketCapture::class.java, WebSocketCapture("id", RecordingSink(), Masking(AppMonitorOptions()), 0)) }

        assertNull(AppMonitorOkHttp.newWebSocket(OkHttpClient(), tagged, listener, RecordingSink()))
    }

    @Test
    fun `lets OkHttp open a socket the Ktor plugin captures`() {
        val marked = request { header(CAPTURE_HEADER, "ktor") }

        assertNull(AppMonitorOkHttp.newWebSocket(OkHttpClient(), marked, listener, RecordingSink()))
    }
}
