package appmonitor.sdk.ktor

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.Header
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.readString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0110 C.1, E.1, ADR-0115: a matching call answered by the plugin, captured with its rule. */
class MockAnswerTest {
    private val rules =
        listOf(
            MockRule(
                id = 7,
                path = "/items",
                delay_ms = 50,
                response =
                    MockResponse(
                        status_code = 503,
                        headers = listOf(Header("Content-Type", "application/json"), Header("Retry-After", "5")),
                        body = """{"error":"down"}""".encodeUtf8(),
                    ),
            ),
            MockRule(id = 8, path = "/orders", failure = MockFailure("mocked reset")),
        )
    private val sink = RecordingSink(AppMonitorOptions(), rules)
    private val engine = MockEngine { respond("real", HttpStatusCode.OK) }

    private fun client(expectSuccess: Boolean = false) =
        HttpClient(engine) {
            this.expectSuccess = expectSuccess
            install(appMonitorKtor(sink, marksCaptured = false))
        }

    @Test
    fun aResponseRuleAnswersWithoutTheEngine() =
        runTest {
            val response = client().get("https://api.example.com/items?page=2")

            assertEquals(503, response.status.value)
            assertEquals("5", response.headers["Retry-After"])
            assertEquals("""{"error":"down"}""", response.bodyAsText())
            assertTrue(engine.requestHistory.isEmpty())
            val started = sink.started()
            assertEquals(7L, started.mock_rule_id)
            assertEquals("https://api.example.com/items?page=2", started.url)
            val received = sink.received()
            assertEquals(started.request_id, received.request_id)
            assertEquals(503, received.status_code)
            assertEquals("""{"error":"down"}""", received.body.utf8())
            assertEquals(16L, received.body_size)
            assertContains(received.headers, Header("Retry-After", "5"))
            assertEquals(0, sink.pending)
        }

    @Test
    fun aStreamedReadGetsTheMockedBody() =
        runTest {
            val body = client().prepareGet("https://api.example.com/items").execute { it.bodyAsChannel().readRemaining().readString() }

            assertEquals("""{"error":"down"}""", body)
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun expectSuccessStillSeesAMockedError() =
        runTest {
            assertFailsWith<ServerResponseException> { client(expectSuccess = true).get("https://api.example.com/items") }
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun aFailureRuleThrowsAnIOExceptionWithItsMessage() =
        runTest {
            val error =
                assertFailsWith<IOException> {
                    client().post("https://api.example.com/orders") { setBody("{}") }
                }

            assertEquals("mocked reset", error.message)
            assertTrue(engine.requestHistory.isEmpty())
            val started = sink.started()
            assertEquals(8L, started.mock_rule_id)
            assertEquals("{}", started.body.utf8())
            val failed = sink.failed()
            assertEquals(started.request_id, failed.request_id)
            assertContains(failed.error, "mocked reset")
        }

    @Test
    fun aCallNoRuleMatchesGoesToTheEngine() =
        runTest {
            assertEquals("real", client().get("https://api.example.com/other").bodyAsText())

            assertEquals(1, engine.requestHistory.size)
            assertNull(sink.started().mock_rule_id)
            assertEquals("real", sink.received().body.utf8())
        }
}
