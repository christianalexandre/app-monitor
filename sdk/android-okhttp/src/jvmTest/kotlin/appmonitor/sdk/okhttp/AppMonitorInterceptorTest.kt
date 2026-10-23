package appmonitor.sdk.okhttp

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.Header
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppMonitorInterceptorTest {
    private val server = MockWebServer()

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
    fun successIsCapturedWhole() {
        val sink = RecordingSink()
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"ok":true}""")
                .build(),
        )

        val body = client(sink).newCall(Request.Builder().url(server.url("/items?page=2")).build()).execute().use { it.body.string() }

        assertEquals("""{"ok":true}""", body)
        val started = sink.started()
        assertEquals("GET", started.method)
        assertEquals(server.url("/items?page=2").toString(), started.url)
        assertFalse(started.body_truncated)
        val received = sink.received()
        assertEquals(started.request_id, received.request_id)
        assertEquals(200, received.status_code)
        assertEquals("""{"ok":true}""", received.body.utf8())
        assertFalse(received.body_truncated)
        assertEquals(11L, received.body_size)
        assertContains(received.headers, Header("Content-Type", "application/json"))
    }

    @Test
    fun serverErrorAndRequestBodyAreCaptured() {
        val sink = RecordingSink()
        server.enqueue(
            MockResponse
                .Builder()
                .code(500)
                .body("boom")
                .build(),
        )
        val request =
            Request
                .Builder()
                .url(
                    server.url("/orders"),
                ).post("""{"id":1}""".toRequestBody("application/json".toMediaType()))
                .build()

        client(sink).newCall(request).execute().use { assertEquals("boom", it.body.string()) }

        val started = sink.started()
        assertEquals("POST", started.method)
        assertEquals("""{"id":1}""", started.body.utf8())
        assertEquals(8L, started.body_size)
        assertContains(started.headers, Header("Content-Type", "application/json; charset=utf-8"))
        val received = sink.received()
        assertEquals(500, received.status_code)
        assertEquals("boom", received.body.utf8())
        assertEquals("""{"id":1}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun failureIsCapturedAndReachesTheApp() {
        val sink = RecordingSink()
        val url = server.url("/gone")
        server.close()

        assertFailsWith<IOException> { client(sink).newCall(Request.Builder().url(url).build()).execute() }

        val started = sink.started()
        val failed = sink.failed()
        assertEquals(started.request_id, failed.request_id)
        assertContains(failed.error, "Exception")
    }

    @Test
    fun bodiesOverTheLimitAreCutWithTheirSize() {
        val sink = RecordingSink(AppMonitorOptions(maxBodyBytes = 16))
        val large = "x".repeat(100_000)
        server.enqueue(MockResponse.Builder().body(large).build())
        val request =
            Request
                .Builder()
                .url(server.url("/upload"))
                .post(large.toRequestBody())
                .build()

        val body = client(sink).newCall(request).execute().use { it.body.string() }

        assertEquals(large, body)
        assertEquals(large, server.takeRequest().body?.utf8())
        val started = sink.started()
        assertEquals("x".repeat(16), started.body.utf8())
        assertTrue(started.body_truncated)
        assertEquals(100_000L, started.body_size)
        val received = sink.received()
        assertEquals("x".repeat(16), received.body.utf8())
        assertTrue(received.body_truncated)
        assertEquals(100_000L, received.body_size)
    }

    @Test
    fun sensitiveHeadersAndQueryParametersAreMasked() {
        val sink = RecordingSink(AppMonitorOptions(maskedHeaders = setOf("x-api-key"), maskedQueryParameters = setOf("TOKEN")))
        server.enqueue(
            MockResponse
                .Builder()
                .setHeader("Set-Cookie", "session=s3cret")
                .body("ok")
                .build(),
        )
        val request =
            Request
                .Builder()
                .url(server.url("/me?token=abc&page=1"))
                .header("Authorization", "Bearer abc")
                .header("X-Api-Key", "key")
                .header("Accept", "text/plain")
                .build()

        client(sink).newCall(request).execute().use { it.body.string() }

        val sent = server.takeRequest()
        assertEquals("Bearer abc", sent.headers["Authorization"])
        assertEquals("abc", sent.url.queryParameter("token"))
        val started = sink.started()
        assertEquals(
            server
                .url("/me")
                .newBuilder()
                .addQueryParameter("token", MASK)
                .addQueryParameter("page", "1")
                .toString(),
            started.url,
        )
        assertContains(started.headers, Header("Authorization", MASK))
        assertContains(started.headers, Header("X-Api-Key", MASK))
        assertContains(started.headers, Header("Accept", "text/plain"))
        assertContains(sink.received().headers, Header("Set-Cookie", MASK))
    }

    @Test
    fun aUrlPasswordIsMaskedAndTheUserNameKept() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().body("ok").build())
        val url =
            server
                .url("/me?page=1")
                .newBuilder()
                .username("user")
                .password("s3cret")
                .build()

        client(sink).newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

        val started = sink.started()
        assertEquals(url.newBuilder().password(MASK).toString(), started.url)
        assertFalse("s3cret" in started.url)
        val captured = started.url.toHttpUrl()
        assertEquals("user", captured.username)
        assertEquals(MASK, captured.password)
        assertEquals("1", captured.queryParameter("page"))
    }

    @Test
    fun aStreamedBodyIsCopiedAsTheAppReadsIt() {
        val sink = RecordingSink(AppMonitorOptions(maxBodyBytes = 1024))
        val streamed = "0123456789".repeat(20_000)
        server.enqueue(MockResponse.Builder().chunkedBody(streamed, maxChunkSize = 4096).build())

        val response = client(sink).newCall(Request.Builder().url(server.url("/stream")).build()).execute()
        sink.started()
        val beforeRead = sink.pending
        val read = response.use { it.body.source().readUtf8() }

        assertEquals(0, beforeRead)
        assertEquals(streamed, read)
        val received = sink.received()
        assertEquals(streamed.take(1024), received.body.utf8())
        assertTrue(received.body_truncated)
        assertEquals(200_000L, received.body_size)
    }

    @Test
    fun aBodyClosedUnreadIsSentWithItsKnownSize() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().body("never read").build())

        client(sink).newCall(Request.Builder().url(server.url("/")).build()).execute().close()

        sink.started()
        val received = sink.received()
        assertTrue(received.body_truncated)
        assertEquals(0, received.body.size)
        assertEquals(10L, received.body_size)
    }

    @Test
    fun aOneShotRequestBodyIsNotRead() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().build())
        val oneShot =
            object : RequestBody() {
                override fun contentType() = null

                override fun contentLength() = 5L

                override fun isOneShot() = true

                override fun writeTo(sink: BufferedSink) {
                    sink.write("once!".encodeUtf8())
                }
            }

        client(sink)
            .newCall(
                Request
                    .Builder()
                    .url(server.url("/"))
                    .post(oneShot)
                    .build(),
            ).execute()
            .close()

        assertEquals("once!", server.takeRequest().body?.utf8())
        val started = sink.started()
        assertTrue(started.body_truncated)
        assertEquals(0, started.body.size)
        assertEquals(5L, started.body_size)
    }

    @Test
    fun nothingIsCapturedWhileTheMonitorIsNotRunning() {
        val sink = RecordingSink(options = null)
        server.enqueue(MockResponse.Builder().body("ok").build())

        val body = client(sink).newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body.string() }

        assertEquals("ok", body)
        assertEquals(0, sink.pending)
    }

    @Test
    fun aRequestWithoutBodyHasNoSize() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().code(204).build())

        client(sink).newCall(Request.Builder().url(server.url("/")).build()).execute().close()

        val started = sink.started()
        assertFalse(started.body_truncated)
        assertNull(started.body_size)
        val received = sink.received()
        assertEquals(204, received.status_code)
        assertFalse(received.body_truncated)
        assertEquals(Buffer().readByteString(), received.body)
    }
}
