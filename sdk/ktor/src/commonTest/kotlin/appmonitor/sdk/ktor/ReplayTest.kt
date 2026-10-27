package appmonitor.sdk.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.utils.EmptyContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** ADR-0111 D.1, ADR-0122 C.1: a captured call sent again through the app's own client, marked as a replay. */
class ReplayTest {
    private val sink = RecordingSink()
    private var answers = 0
    private val engine = MockEngine { respond("answer ${++answers}", HttpStatusCode.OK) }

    private val stamp =
        createClientPlugin("Stamp") {
            onRequest { request, _ -> request.headers.append("X-Stamp", "1") }
        }

    private fun client() =
        HttpClient(engine) {
            defaultRequest { headers.append("X-Default", "1") }
            install(stamp)
            install(appMonitorKtor(sink, marksCaptured = false))
        }

    @Test
    fun aCallIsSentAgainThroughEveryPluginOnceAndCapturedAsAReplay() =
        runTest {
            val client = client()
            client.post("https://api.example.com/orders") {
                contentType(ContentType.Application.Json)
                setBody("""{"n":1}""")
            }
            val original = sink.started()
            assertNull(original.replay_of)
            sink.received()

            assertNull(assertNotNull(sink.replays[original.request_id]).invoke())
            client.coroutineContext[Job]!!.children.forEach { it.join() }

            val replay = sink.started()
            assertEquals(original.request_id, replay.replay_of)
            assertEquals(original.method, replay.method)
            assertEquals(original.url, replay.url)
            assertEquals(original.headers, replay.headers)
            assertEquals(original.body, replay.body)
            val received = sink.received()
            assertEquals(replay.request_id, received.request_id)
            assertEquals("answer 2", received.body.utf8())
            val sent = engine.requestHistory.last()
            assertEquals(listOf("1"), sent.headers.getAll("X-Stamp"))
            assertEquals(listOf("1"), sent.headers.getAll("X-Default"))
            assertEquals("""{"n":1}""", (sent.body as TextContent).text)
            assertNotNull(sink.replays[replay.request_id], "a replay can be sent again too")
        }

    @Test
    fun aStreamedBodyAWebSocketAndAClosedClientAreRefused() {
        val client = client()
        val app = HttpRequestBuilder().apply { url("https://api.example.com/upload") }
        val bytes = ByteArrayContent(ByteArray(0))

        assertEquals("Its body could be sent only once", replay(client, app, ChannelReadContent(), "id"))
        assertEquals(
            "A WebSocket is not opened again",
            replay(client, HttpRequestBuilder().apply { url("ws://api.example.com/socket") }, EmptyContent, "id"),
        )
        client.close()
        assertEquals("The app closed its HTTP client", replay(client, app, bytes, "id"))
    }

    private class ChannelReadContent : OutgoingContent.ReadChannelContent() {
        override fun readFrom() = ByteReadChannel("once")
    }
}
