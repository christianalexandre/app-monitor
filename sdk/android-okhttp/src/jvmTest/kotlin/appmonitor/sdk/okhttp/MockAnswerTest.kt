package appmonitor.sdk.okhttp

import appmonitor.v1.Header
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0110 C.1, E.1: a matching call answered by the interceptor, captured with its rule. */
class MockAnswerTest {
    private val server = MockWebServer()
    private val rules =
        listOf(
            MockRule(
                id = 7,
                path = "/items",
                delay_ms = 300,
                response =
                    MockResponse(
                        status_code = 503,
                        headers = listOf(Header("Content-Type", "application/json"), Header("Retry-After", "5")),
                        body = """{"error":"down"}""".encodeUtf8(),
                    ),
            ),
            MockRule(id = 8, path = "/orders", failure = MockFailure("mocked reset")),
        )

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    private fun client(sink: CaptureSink) = OkHttpClient.Builder().addInterceptor(AppMonitorInterceptor(sink)).build()

    @Test
    fun aResponseRuleAnswersAfterItsDelayWithoutTheNetwork() {
        val sink = RecordingSink(mocks = rules)
        val startedAt = System.nanoTime()

        val (code, body, retryAfter) =
            client(sink).newCall(Request.Builder().url(server.url("/items?page=2")).build()).execute().use {
                Triple(it.code, it.body.string(), it.header("Retry-After"))
            }

        assertTrue(System.nanoTime() - startedAt >= 300_000_000, "the delay was not applied")
        assertEquals(503, code)
        assertEquals("""{"error":"down"}""", body)
        assertEquals("5", retryAfter)
        assertEquals(0, server.requestCount)
        val started = sink.started()
        assertEquals(7L, started.mock_rule_id)
        val received = sink.received()
        assertEquals(started.request_id, received.request_id)
        assertEquals(503, received.status_code)
        assertEquals("""{"error":"down"}""", received.body.utf8())
        assertEquals(16L, received.body_size)
        assertContains(received.headers, Header("Retry-After", "5"))
    }

    @Test
    fun aFailureRuleThrowsAnIOExceptionWithItsMessage() {
        val sink = RecordingSink(mocks = rules)

        val error = assertFailsWith<IOException> { client(sink).newCall(Request.Builder().url(server.url("/orders")).build()).execute() }

        assertEquals("mocked reset", error.message)
        assertEquals(0, server.requestCount)
        val started = sink.started()
        assertEquals(8L, started.mock_rule_id)
        val failed = sink.failed()
        assertEquals(started.request_id, failed.request_id)
        assertContains(failed.error, "mocked reset")
    }

    @Test
    fun aCallCancelledDuringTheDelayFailsAtOnce() {
        val sink = RecordingSink(mocks = listOf(MockRule(id = 9, path = "/slow", delay_ms = 10_000, failure = MockFailure("late"))))
        val call = client(sink).newCall(Request.Builder().url(server.url("/slow")).build())
        val startedAt = System.nanoTime()
        Thread {
            Thread.sleep(200)
            call.cancel()
        }.start()

        val error = assertFailsWith<IOException> { call.execute() }

        assertTrue(System.nanoTime() - startedAt < 2_000_000_000, "the cancel waited for the delay")
        assertEquals("Canceled", error.message)
        assertEquals(sink.started().request_id, sink.failed().request_id)
    }

    @Test
    fun aCallNoRuleMatchesGoesToTheNetwork() {
        val sink = RecordingSink(mocks = rules)
        server.enqueue(
            mockwebserver3.MockResponse
                .Builder()
                .body("real")
                .build(),
        )

        val body = client(sink).newCall(Request.Builder().url(server.url("/other")).build()).execute().use { it.body.string() }

        assertEquals("real", body)
        assertEquals(1, server.requestCount)
        assertNull(sink.started().mock_rule_id)
        assertEquals("real", sink.received().body.utf8())
    }

    @Test
    fun asANetworkInterceptorAMatchingCallGoesToTheNetwork() {
        val sink = RecordingSink(mocks = rules)
        server.enqueue(
            mockwebserver3.MockResponse
                .Builder()
                .body("real")
                .build(),
        )
        val client = OkHttpClient.Builder().addNetworkInterceptor(AppMonitorInterceptor(sink)).build()

        val body = client.newCall(Request.Builder().url(server.url("/items")).build()).execute().use { it.body.string() }

        assertEquals("real", body)
        assertEquals(1, server.requestCount)
        assertNull(sink.started().mock_rule_id)
        assertEquals("real", sink.received().body.utf8())
    }
}
