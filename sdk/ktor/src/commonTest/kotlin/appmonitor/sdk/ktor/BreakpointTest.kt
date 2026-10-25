package appmonitor.sdk.ktor

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.sdk.core.BreakpointOutcome
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Header
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0111 A.1, C.1, ADR-0118: a call held at its breakpoint, then resumed edited or not, or aborted. */
class BreakpointTest {
    private val request = BreakpointStage.BREAKPOINT_STAGE_REQUEST
    private val response = BreakpointStage.BREAKPOINT_STAGE_RESPONSE
    private val sent = mutableListOf<HttpRequestData>()
    private val engine =
        MockEngine {
            sent += it
            respond("original", HttpStatusCode.OK, headersOf("X-Server", "1"))
        }

    private fun client(sink: CaptureSink) =
        HttpClient(engine) {
            defaultRequest { header(HttpHeaders.Authorization, "Bearer real") }
            install(appMonitorKtor(sink, marksCaptured = false))
        }

    private fun rule(
        path: String,
        request: Boolean = false,
        response: Boolean = false,
    ) = BreakpointRule(id = 3, path = path, request = request, response = response, timeout_ms = 60_000)

    @Test
    fun aHeldRequestGoesOutWithOnlyItsChangedFields() =
        runTest {
            val sink =
                RecordingSink(breakpoints = listOf(rule("/items", request = true))) { hit ->
                    BreakpointOutcome.Resume(
                        BreakpointResume(
                            request_id = hit.request!!.request_id,
                            stage = request,
                            method = "PUT",
                            url = "https://api.example.com/items/2",
                            set_headers = listOf(Header("X-Edited", "yes")),
                            removed_headers = listOf("x-drop"),
                            body = "edited".encodeUtf8(),
                        ),
                    )
                }

            val body =
                client(sink)
                    .post("https://api.example.com/items") {
                        header("X-Drop", "1")
                        setBody("original")
                    }.bodyAsText()

            assertEquals("original", body)
            val hit = sink.hits.single()
            assertEquals(request, hit.stage)
            assertEquals("original", hit.request!!.body.utf8())
            assertContains(hit.request!!.headers, Header(HttpHeaders.Authorization, MASK))
            val out = sent.single()
            assertEquals("PUT", out.method.value)
            assertEquals("/items/2", out.url.encodedPath)
            assertEquals("Bearer real", out.headers[HttpHeaders.Authorization])
            assertEquals("yes", out.headers["X-Edited"])
            assertNull(out.headers["X-Drop"])
            assertEquals("edited", (out.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            val started = sink.started()
            assertEquals(hit.request!!.request_id, started.request_id)
            assertEquals("https://api.example.com/items/2", started.url)
            assertEquals("edited", started.body.utf8())
            assertEquals("original", sink.received().body.utf8())
        }

    @Test
    fun anAbortedRequestFailsWithoutTheEngine() =
        runTest {
            val sink = RecordingSink(breakpoints = listOf(rule("/items", request = true))) { BreakpointOutcome.Abort("stopped here") }

            val error = assertFailsWith<IOException> { client(sink).get("https://api.example.com/items") }

            assertEquals("stopped here", error.message)
            assertTrue(sent.isEmpty())
            sink.started()
            assertContains(sink.failed().error, "stopped here")
        }

    @Test
    fun aBadEditFailsTheCallWithItsReason() =
        runTest {
            val sink =
                RecordingSink(breakpoints = listOf(rule("/items", request = true))) {
                    BreakpointOutcome.Resume(BreakpointResume(request_id = it.request!!.request_id, stage = request, url = "not a url"))
                }

            val error = assertFailsWith<IOException> { client(sink).get("https://api.example.com/items") }

            assertContains(error.message!!, "not a URL")
            assertTrue(sent.isEmpty())
            sink.started()
            sink.failed()
        }

    @Test
    fun aHeldResponseReachesTheAppEdited() =
        runTest {
            val sink =
                RecordingSink(breakpoints = listOf(rule("/items", response = true))) { hit ->
                    BreakpointOutcome.Resume(
                        BreakpointResume(
                            request_id = hit.request!!.request_id,
                            stage = response,
                            status_code = 503,
                            removed_headers = listOf("X-Server"),
                            body = "edited".encodeUtf8(),
                        ),
                    )
                }

            val answer = client(sink).get("https://api.example.com/items")

            assertEquals(503, answer.status.value)
            assertEquals("edited", answer.bodyAsText())
            assertNull(answer.headers["X-Server"])
            val hit = sink.hits.single()
            assertEquals(response, hit.stage)
            assertEquals(200, hit.response!!.status_code)
            assertEquals("original", hit.response!!.body.utf8())
            sink.started()
            val received = sink.received()
            assertEquals(503, received.status_code)
            assertEquals("edited", received.body.utf8())
        }

    @Test
    fun aResponseWithOnlyItsStatusChangedKeepsItsBody() =
        runTest {
            val sink =
                RecordingSink(AppMonitorOptions(), breakpoints = listOf(rule("/items", response = true))) {
                    BreakpointOutcome.Resume(BreakpointResume(request_id = it.request!!.request_id, stage = response, status_code = 201))
                }

            val answer = client(sink).get("https://api.example.com/items")

            assertEquals(201, answer.status.value)
            assertEquals("original", answer.bodyAsText())
            assertEquals("1", answer.headers["X-Server"])
            sink.started()
            assertEquals(201, sink.received().status_code)
        }

    @Test
    fun aMockedResponseCanBeHeldToo() =
        runTest {
            val mock = MockRule(id = 9, path = "/items", response = MockResponse(status_code = 200, body = "mocked".encodeUtf8()))
            val sink =
                RecordingSink(mocks = listOf(mock), breakpoints = listOf(rule("/items", response = true))) {
                    BreakpointOutcome.Resume(BreakpointResume(request_id = it.request!!.request_id, stage = response, status_code = 404))
                }

            val answer = client(sink).get("https://api.example.com/items")

            assertEquals(404, answer.status.value)
            assertEquals("mocked", answer.bodyAsText())
            assertTrue(sent.isEmpty())
            assertEquals(
                "mocked",
                sink.hits
                    .single()
                    .response!!
                    .body
                    .utf8(),
            )
            assertEquals(9L, sink.started().mock_rule_id)
            assertEquals(404, sink.received().status_code)
        }
}
