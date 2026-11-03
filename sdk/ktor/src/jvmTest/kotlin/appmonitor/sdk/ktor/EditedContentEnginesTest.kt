package appmonitor.sdk.ktor

import appmonitor.sdk.core.BreakpointOutcome
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Header
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An edited request's wrapper against what real engines put on the wire, its body streamed. */
class EditedContentEnginesTest {
    private val server = MockWebServer()

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    private fun assertEditReachesTheServer(engine: HttpClientEngineFactory<*>) =
        runBlocking {
            val body =
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Text.Plain
                    override val headers = headersOf("X-Own", "kept")

                    override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeStringUtf8("streamed")
                }
            val sink =
                RecordingSink(breakpoints = listOf(BreakpointRule(id = 3, path = "/items", request = true, timeout_ms = 60_000))) {
                    BreakpointOutcome.Resume(
                        BreakpointResume(
                            request_id = it.request!!.request_id,
                            stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST,
                            set_headers = listOf(Header("X-Own", "edited"), Header("Content-Type", "application/json")),
                        ),
                    )
                }
            server.enqueue(MockResponse.Builder().body("ok").build())

            HttpClient(engine) { install(appMonitorKtor(sink, marksCaptured = false)) }.use { client ->
                assertEquals("ok", client.post(server.url("/items").toString()) { setBody(body) }.bodyAsText())
            }

            val received = server.takeRequest()
            assertEquals("edited", received.headers["X-Own"])
            assertEquals("application/json", received.headers["Content-Type"])
            assertEquals("streamed", received.body?.utf8())
        }

    @Test
    fun onCio() = assertEditReachesTheServer(CIO)

    @Test
    fun onOkHttp() = assertEditReachesTheServer(OkHttp)
}
