package appmonitor.sdk.ktor

import appmonitor.protocol.Protocol
import appmonitor.sdk.okhttp.AppMonitorInterceptor
import appmonitor.v1.Header
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ADR-0129: the interceptor's skip of a marked call is AppMonitorInterceptorTest's, with capture on.
class OkHttpEngineDedupeTest {
    private val server = MockWebServer()

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    @Test
    fun aCallOnTheOkHttpEngineWithBothSdksIsOneExchangeAndTheMarkStaysOnTheDevice() =
        runTest {
            val sink = RecordingSink()
            server.enqueue(MockResponse.Builder().body("ok").build())
            val client =
                HttpClient(OkHttp) {
                    engine { addInterceptor(AppMonitorInterceptor()) }
                    install(appMonitorKtor(sink))
                }

            assertEquals("ok", client.get(server.url("/items").toString()).bodyAsText())

            assertContains(sink.started().headers, Header(Protocol.CAPTURE_HEADER, Protocol.CAPTURE_HEADER_KTOR))
            sink.received()
            assertEquals(0, sink.pending)
            assertNull(server.takeRequest().headers[Protocol.CAPTURE_HEADER])
        }
}
