package appmonitor.sdk.ktor

import appmonitor.protocol.Protocol
import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.cookies.ConstantCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ChannelWriterContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppMonitorKtorTest {
    private val sink = RecordingSink(AppMonitorOptions(maskedQueryParameters = setOf("code")))

    private fun client(
        sink: CaptureSink = this.sink,
        marksCaptured: Boolean = false,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = HttpClient(MockEngine(handler)) { install(appMonitorKtor(sink, marksCaptured)) }

    @Test
    fun getSendsRequestAndResponseAsAnAdapter() =
        runTest {
            val client =
                client {
                    respond("""{"id":1}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            assertEquals("""{"id":1}""", client.get("https://api.example.com/items?code=abc&id=1").bodyAsText())

            val started = sink.started()
            assertEquals("GET", started.method)
            assertEquals("https://api.example.com/items?code=██&id=1", started.url)
            assertEquals(CaptureLayer.CAPTURE_LAYER_ADAPTER, started.capture_layer)
            assertEquals(0, started.body.size)
            assertNull(started.body_size)
            val received = sink.received()
            assertEquals(started.request_id, received.request_id)
            assertEquals(200, received.status_code)
            assertTrue(Header("Content-Type", "application/json") in received.headers)
            assertEquals("""{"id":1}""".encodeUtf8(), received.body)
            assertEquals(8L, received.body_size)
            assertEquals(false, received.body_truncated)
            assertEquals(0, sink.pending)
        }

    @Test
    fun postSendsTheBodyAndItsContentType() =
        runTest {
            val client = client { request -> respond(request.body.toByteArray().decodeToString()) }
            val echoed =
                client
                    .post("https://api.example.com/items") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"a"}""")
                    }.bodyAsText()
            assertEquals("""{"name":"a"}""", echoed)

            val started = sink.started()
            assertEquals("POST", started.method)
            assertEquals("""{"name":"a"}""".encodeUtf8(), started.body)
            assertEquals(12L, started.body_size)
            assertTrue(started.headers.any { it.name == "Content-Type" && it.value_.startsWith("application/json") })
            assertEquals("""{"name":"a"}""".encodeUtf8(), sink.received().body)
        }

    @Test
    fun bodiesAreCutAtMaxBodyBytes() =
        runTest {
            val sink = RecordingSink(AppMonitorOptions(maxBodyBytes = 4))
            val client =
                client(sink) { request ->
                    respond(
                        request.body
                            .toByteArray()
                            .decodeToString()
                            .reversed(),
                    )
                }
            assertEquals("fedcba", client.post("https://api.example.com/x") { setBody("abcdef") }.bodyAsText())

            val started = sink.started()
            assertEquals("abcd".encodeUtf8(), started.body)
            assertEquals(true, started.body_truncated)
            assertEquals(6L, started.body_size)
            val received = sink.received()
            assertEquals("fedc".encodeUtf8(), received.body)
            assertEquals(true, received.body_truncated)
            assertEquals(6L, received.body_size)
        }

    @Test
    fun aStreamedRequestBodyIsSentUnreadAndTruncated() =
        runTest {
            val client = client { request -> respond(request.body.toByteArray().decodeToString()) }
            val body = ChannelWriterContent({ writeStringUtf8("streamed") }, ContentType.Text.Plain, contentLength = 8)
            assertEquals("streamed", client.post("https://api.example.com/x") { setBody(body) }.bodyAsText())

            val started = sink.started()
            assertEquals(0, started.body.size)
            assertEquals(true, started.body_truncated)
            assertEquals(8L, started.body_size)
        }

    @Test
    fun aStreamedResponseIsSentUnreadAndTruncated() =
        runTest {
            val client = client { respond("streamed", headers = headersOf(HttpHeaders.ContentLength, "8")) }
            assertEquals("streamed", client.prepareGet("https://api.example.com/x").execute { it.bodyAsText() })

            sink.started()
            val received = sink.received()
            assertEquals(0, received.body.size)
            assertEquals(true, received.body_truncated)
            assertEquals(8L, received.body_size)
        }

    @Test
    fun aFailureSendsRequestFailedAndReachesTheApp() =
        runTest {
            val client = client { throw Unreachable("no route to host") }
            val thrown = assertFailsWith<Unreachable> { client.get("https://api.example.com/x") }
            assertEquals("no route to host", thrown.message)

            val started = sink.started()
            val failed = sink.failed()
            assertEquals(started.request_id, failed.request_id)
            assertTrue("no route to host" in failed.error, failed.error)
            assertEquals(0, sink.pending)
        }

    @Test
    fun aBodyThatFailsToReadSendsTheResponseUnread() =
        runTest {
            val client = client { respond("lost", headers = headersOf(HttpHeaders.ContentLength, "4")) }
            client.receivePipeline.intercept(HttpReceivePipeline.After) { response -> proceedWith(Unreadable(response)) }
            assertFailsWith<ClosedByteChannelException> { client.get("https://api.example.com/x") }

            val started = sink.started()
            val received = sink.received()
            assertEquals(started.request_id, received.request_id)
            assertEquals(200, received.status_code)
            assertEquals(0, received.body.size)
            assertEquals(true, received.body_truncated)
            assertEquals(4L, received.body_size)
            assertEquals(0, sink.pending)
        }

    @Test
    fun nothingIsSentWhileTheMonitorIsStopped() =
        runTest {
            val sink = RecordingSink(options = null)
            val client = client(sink) { respond("ok") }
            assertEquals("ok", client.get("https://api.example.com/x").bodyAsText())
            assertEquals(0, sink.pending)
        }

    @Test
    fun headersFromOtherPluginsAreSeenAndMasked() =
        runTest {
            val client =
                HttpClient(MockEngine { respond("ok") }) {
                    install(HttpCookies) {
                        storage =
                            ConstantCookiesStorage(Cookie("session", "s3cret", domain = "api.example.com", path = "/"))
                    }
                    defaultRequest { header(HttpHeaders.Authorization, "Bearer t0k") }
                    install(appMonitorKtor(sink))
                }
            client.get("https://api.example.com/x") { header("X-Trace", "1") }

            val headers = sink.started().headers
            assertTrue(Header("Cookie", "██") in headers, headers.toString())
            assertTrue(Header("Authorization", "██") in headers, headers.toString())
            assertTrue(Header("X-Trace", "1") in headers, headers.toString())
        }

    @Test
    fun eachRedirectHopIsItsOwnExchange() =
        runTest {
            val client =
                client { request ->
                    if (request.url.encodedPath == "/login") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/home?code=abc"))
                    } else {
                        respond("home")
                    }
                }
            assertEquals("home", client.get("https://auth.example.com/login").bodyAsText())

            val first = sink.started()
            assertEquals("https://auth.example.com/login", first.url)
            val redirect = sink.received()
            assertEquals(302, redirect.status_code)
            assertTrue(Header("Location", "https://auth.example.com/home?code=██") in redirect.headers, redirect.headers.toString())
            assertEquals("https://auth.example.com/home?code=██", sink.started().url)
            assertEquals(200, sink.received().status_code)
            assertEquals(0, sink.pending)
        }

    @Test
    fun aMarkedCaptureSendsTheCaptureHeaderAndShowsIt() =
        runTest {
            val client = client(marksCaptured = true) { request -> respond(request.headers[Protocol.CAPTURE_HEADER].orEmpty()) }
            assertEquals(Protocol.CAPTURE_HEADER_KTOR, client.get("https://api.example.com/x").bodyAsText())
            assertTrue(Header(Protocol.CAPTURE_HEADER, Protocol.CAPTURE_HEADER_KTOR) in sink.started().headers)
        }

    @Test
    fun anUnmarkedCaptureSendsNoCaptureHeader() =
        runTest {
            val client = client { request -> respond(request.headers.contains(Protocol.CAPTURE_HEADER).toString()) }
            assertEquals("false", client.get("https://api.example.com/x").bodyAsText())
            assertTrue(sink.started().headers.none { it.name == Protocol.CAPTURE_HEADER })
        }

    @Test
    fun nothingIsMarkedWhileTheMonitorIsStopped() =
        runTest {
            val sink = RecordingSink(options = null)
            val client =
                client(sink, marksCaptured = true) { request -> respond(request.headers.contains(Protocol.CAPTURE_HEADER).toString()) }
            assertEquals("false", client.get("https://api.example.com/x").bodyAsText())
        }

    @Test
    fun aHopSentAfterStopLosesTheCaptureHeader() =
        runTest {
            val seen = mutableListOf<String?>()
            val client =
                client(marksCaptured = true) { request ->
                    seen += request.headers[Protocol.CAPTURE_HEADER]
                    if (request.url.encodedPath == "/login") {
                        sink.options = null
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/home"))
                    } else {
                        respond("home")
                    }
                }
            assertEquals("home", client.get("https://auth.example.com/login").bodyAsText())
            assertEquals(listOf(Protocol.CAPTURE_HEADER_KTOR, null), seen)
        }

    /** [response] whose body throws on every read, as a plugin that breaks the saved body leaves it. */
    @OptIn(InternalAPI::class)
    private class Unreadable(
        response: HttpResponse,
    ) : HttpResponse() {
        override val call = response.call
        override val status = response.status
        override val version = response.version
        override val requestTime = response.requestTime
        override val responseTime = response.responseTime
        override val headers = response.headers
        override val coroutineContext = response.coroutineContext
        override val rawContent: ByteReadChannel get() = ByteChannel().apply { cancel(IOException("body lost")) }
    }

    private class Unreachable(
        message: String,
    ) : Exception(message)
}
