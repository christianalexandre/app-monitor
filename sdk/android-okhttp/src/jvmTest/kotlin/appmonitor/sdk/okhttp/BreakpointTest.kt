package appmonitor.sdk.okhttp

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.sdk.core.BreakpointOutcome
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Header
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import kotlinx.coroutines.awaitCancellation
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0111 A.1, C.1, ADR-0118: a call held at its breakpoint, then resumed edited or not, or aborted. */
class BreakpointTest {
    private val server = MockWebServer()
    private val request = BreakpointStage.BREAKPOINT_STAGE_REQUEST
    private val response = BreakpointStage.BREAKPOINT_STAGE_RESPONSE

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    private fun client(sink: CaptureSink) = OkHttpClient.Builder().addInterceptor(AppMonitorInterceptor(sink)).build()

    private fun rule(
        path: String,
        request: Boolean = false,
        response: Boolean = false,
    ) = BreakpointRule(id = 3, path = path, request = request, response = response, timeout_ms = 60_000)

    private fun reply(body: String) =
        mockwebserver3.MockResponse
            .Builder()
            .addHeader("X-Server", "1")
            .body(body)
            .build()

    @Test
    fun aHeldRequestGoesOutWithOnlyItsChangedFields() {
        val sink =
            RecordingSink(breakpoints = listOf(rule("/items", request = true))) { hit ->
                BreakpointOutcome.Resume(
                    BreakpointResume(
                        request_id = hit.request!!.request_id,
                        stage = request,
                        url = server.url("/items/2").toString(),
                        set_headers = listOf(Header("X-Edited", "yes")),
                        removed_headers = listOf("x-drop"),
                        body = "edited".encodeUtf8(),
                    ),
                )
            }
        server.enqueue(reply("ok"))
        val call =
            Request
                .Builder()
                .url(server.url("/items"))
                .header("Authorization", "Bearer real")
                .header("X-Drop", "1")
                .post("original".toRequestBody())
                .build()

        client(sink).newCall(call).execute().use { assertEquals("ok", it.body.string()) }

        val hit = sink.hits.poll(5, TimeUnit.SECONDS)!!
        assertEquals(3L, hit.rule_id)
        assertEquals(request, hit.stage)
        assertEquals("original", hit.request!!.body.utf8())
        assertContains(hit.request!!.headers, Header("Authorization", MASK))
        val sent = server.takeRequest()
        assertEquals("/items/2", sent.url.encodedPath)
        assertEquals("Bearer real", sent.headers["Authorization"])
        assertEquals("yes", sent.headers["X-Edited"])
        assertNull(sent.headers["X-Drop"])
        assertEquals("edited", sent.body?.utf8())
        val started = sink.started()
        assertEquals(hit.request!!.request_id, started.request_id)
        assertTrue(started.url.endsWith("/items/2"))
        assertEquals("edited", started.body.utf8())
        assertEquals("ok", sink.received().body.utf8())
    }

    @Test
    fun anAbortedRequestFailsWithoutTheNetwork() {
        val sink = RecordingSink(breakpoints = listOf(rule("/items", request = true))) { BreakpointOutcome.Abort("stopped here") }

        val error = assertFailsWith<IOException> { client(sink).newCall(Request.Builder().url(server.url("/items")).build()).execute() }

        assertEquals("stopped here", error.message)
        assertEquals(0, server.requestCount)
        val started = sink.started()
        assertContains(sink.failed().error, "stopped here")
        assertEquals(
            started.request_id,
            sink.hits
                .poll()!!
                .request!!
                .request_id,
        )
    }

    @Test
    fun aBadEditFailsTheCallWithItsReason() {
        val sink =
            RecordingSink(breakpoints = listOf(rule("/items", request = true))) {
                BreakpointOutcome.Resume(BreakpointResume(request_id = it.request!!.request_id, stage = request, url = "not a url"))
            }

        val error = assertFailsWith<IOException> { client(sink).newCall(Request.Builder().url(server.url("/items")).build()).execute() }

        assertContains(error.message!!, "not a URL")
        assertEquals(0, server.requestCount)
        sink.started()
        sink.failed()
    }

    @Test
    fun aBodyHeldAtBothStagesIsCapturedOnce() {
        var writes = 0
        val body =
            object : RequestBody() {
                override fun contentType() = null

                override fun writeTo(sink: BufferedSink) {
                    writes++
                    sink.writeUtf8("sent")
                }
            }
        val sink = RecordingSink(breakpoints = listOf(rule("/items", request = true, response = true)))
        server.enqueue(reply("ok"))

        client(sink)
            .newCall(
                Request
                    .Builder()
                    .url(server.url("/items"))
                    .post(body)
                    .build(),
            ).execute()
            .use { it.body.string() }

        assertEquals(2, writes, "one capture and the network")
        assertEquals(
            "sent",
            sink.hits
                .poll(5, TimeUnit.SECONDS)!!
                .request!!
                .body
                .utf8(),
        )
        assertEquals(
            "sent",
            sink.hits
                .poll(5, TimeUnit.SECONDS)!!
                .request!!
                .body
                .utf8(),
        )
        assertEquals("sent", sink.started().body.utf8())
    }

    @Test
    fun aHeldResponseReachesTheAppEdited() {
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
        server.enqueue(reply("original"))

        val (code, body, header) =
            client(sink).newCall(Request.Builder().url(server.url("/items")).build()).execute().use {
                Triple(it.code, it.body.string(), it.header("X-Server"))
            }

        assertEquals(503, code)
        assertEquals("edited", body)
        assertNull(header)
        val hit = sink.hits.poll(5, TimeUnit.SECONDS)!!
        assertEquals(response, hit.stage)
        assertEquals(200, hit.response!!.status_code)
        assertEquals("original", hit.response!!.body.utf8())
        sink.started()
        val received = sink.received()
        assertEquals(503, received.status_code)
        assertEquals("edited", received.body.utf8())
    }

    @Test
    fun anUnchangedResponseIsReadWholeAfterItsHeldPart() {
        val sink = RecordingSink(AppMonitorOptions(maxBodyBytes = 4), breakpoints = listOf(rule("/items", response = true)))
        server.enqueue(reply("0123456789"))

        val body = client(sink).newCall(Request.Builder().url(server.url("/items")).build()).execute().use { it.body.string() }

        assertEquals("0123456789", body)
        val held = sink.hits.poll(5, TimeUnit.SECONDS)!!.response!!
        assertEquals("0123", held.body.utf8())
        assertTrue(held.body_truncated)
        assertEquals(10L, held.body_size)
        sink.started()
        assertEquals(10L, sink.received().body_size)
    }

    @Test
    fun anEmptyResponseIsHeldToo() {
        val sink =
            RecordingSink(breakpoints = listOf(rule("/items", response = true))) {
                BreakpointOutcome.Resume(BreakpointResume(request_id = it.request!!.request_id, stage = response, status_code = 500))
            }
        server.enqueue(
            mockwebserver3.MockResponse
                .Builder()
                .code(204)
                .build(),
        )

        val code = client(sink).newCall(Request.Builder().url(server.url("/items")).build()).execute().use { it.code }

        assertEquals(500, code)
        assertEquals(
            204,
            sink.hits
                .poll(5, TimeUnit.SECONDS)!!
                .response!!
                .status_code,
        )
        sink.started()
        val received = sink.received()
        assertEquals(500, received.status_code)
        assertEquals(0L, received.body_size)
    }

    @Test
    fun aMockedResponseCanBeHeldToo() {
        val mock = MockRule(id = 9, path = "/items", response = MockResponse(status_code = 200, body = "mocked".encodeUtf8()))
        val sink =
            RecordingSink(mocks = listOf(mock), breakpoints = listOf(rule("/items", response = true))) {
                BreakpointOutcome.Resume(BreakpointResume(request_id = it.request!!.request_id, stage = response, status_code = 404))
            }

        val code = client(sink).newCall(Request.Builder().url(server.url("/items")).build()).execute().use { it.code }

        assertEquals(404, code)
        assertEquals(0, server.requestCount)
        assertEquals(
            "mocked",
            sink.hits
                .poll(5, TimeUnit.SECONDS)!!
                .response!!
                .body
                .utf8(),
        )
        assertEquals(9L, sink.started().mock_rule_id)
        val received = sink.received()
        assertEquals(404, received.status_code)
        assertEquals("mocked", received.body.utf8())
    }

    @Test
    fun asANetworkInterceptorAResumeToAnotherHostFailsTheCall() {
        MockWebServer().use { other ->
            other.start()
            val sink =
                RecordingSink(breakpoints = listOf(rule("/items", request = true))) {
                    BreakpointOutcome.Resume(
                        BreakpointResume(request_id = it.request!!.request_id, stage = request, url = other.url("/items").toString()),
                    )
                }
            val client = OkHttpClient.Builder().addNetworkInterceptor(AppMonitorInterceptor(sink)).build()

            val error = assertFailsWith<IOException> { client.newCall(Request.Builder().url(server.url("/items")).build()).execute() }

            assertContains(error.message!!, "keeps the host and port")
            assertEquals(0, other.requestCount)
            assertEquals(1, sink.hits.size)
            sink.started()
            assertContains(sink.failed().error, "keeps the host and port")
        }
    }

    @Test
    fun asANetworkInterceptorAResumeOnTheSameHostGoesOut() {
        val sink =
            RecordingSink(breakpoints = listOf(rule("/items", request = true))) {
                BreakpointOutcome.Resume(
                    BreakpointResume(request_id = it.request!!.request_id, stage = request, url = server.url("/items/2").toString()),
                )
            }
        server.enqueue(reply("ok"))
        val client = OkHttpClient.Builder().addNetworkInterceptor(AppMonitorInterceptor(sink)).build()

        val body = client.newCall(Request.Builder().url(server.url("/items")).build()).execute().use { it.body.string() }

        assertEquals("ok", body)
        assertEquals("/items/2", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aHeldCallTheAppCancelsFails() {
        val sink = RecordingSink(breakpoints = listOf(rule("/items", request = true))) { awaitCancellation() }
        val call = client(sink).newCall(Request.Builder().url(server.url("/items")).build())
        Thread {
            sink.hits.poll(5, TimeUnit.SECONDS)
            call.cancel()
        }.start()

        val error = assertFailsWith<IOException> { call.execute() }

        assertEquals("Canceled", error.message)
        assertEquals(0, server.requestCount)
        sink.started()
        sink.failed()
    }
}
