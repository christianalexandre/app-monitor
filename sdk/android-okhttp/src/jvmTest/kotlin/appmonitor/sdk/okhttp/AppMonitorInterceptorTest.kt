package appmonitor.sdk.okhttp

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.CaptureLayer
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
        assertEquals(CaptureLayer.CAPTURE_LAYER_ADAPTER, started.capture_layer)
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
        assertEquals("http://${server.hostName}:${server.port}/me?token=██&page=1", started.url)
        assertContains(started.headers, Header("Authorization", MASK))
        assertContains(started.headers, Header("X-Api-Key", MASK))
        assertContains(started.headers, Header("Accept", "text/plain"))
        assertContains(sink.received().headers, Header("Set-Cookie", MASK))
    }

    @Test
    fun aUrlUserNameAndPasswordAreMasked() {
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
        assertEquals("http://██:██@${server.hostName}:${server.port}/me?page=1", started.url)
        assertFalse("s3cret" in started.url)
        assertFalse("user" in started.url)
        val captured = started.url.toHttpUrl()
        assertEquals(MASK, captured.username)
        assertEquals(MASK, captured.password)
        assertEquals("1", captured.queryParameter("page"))
    }

    @Test
    fun aUrlUserNameWithoutPasswordIsMasked() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().body("ok").build())
        val url =
            server
                .url("/repo")
                .newBuilder()
                .username("ghp_TOKEN")
                .build()

        client(sink).newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

        val started = sink.started()
        assertFalse("ghp_TOKEN" in started.url)
        val captured = started.url.toHttpUrl()
        assertEquals(MASK, captured.username)
        assertEquals("", captured.password)
    }

    @Test
    fun aUrlPasswordWithoutUserNameIsMasked() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().body("ok").build())
        val url =
            server
                .url("/repo")
                .newBuilder()
                .password("s3cret")
                .build()

        client(sink).newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

        val captured = sink.started().url.toHttpUrl()
        assertEquals("", captured.username)
        assertEquals(MASK, captured.password)
    }

    @Test
    fun aFormEncodedFragmentGetsTheQueryRule() {
        val sink = RecordingSink(AppMonitorOptions(maskedQueryParameters = setOf("access_token")))
        server.enqueue(MockResponse.Builder().body("ok").build())
        val url = server.url("/cb#access_token=abc&state=xyz")

        client(sink).newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

        assertEquals("access_token=██&state=xyz", sink.started().url.substringAfter('#'))
    }

    @Test
    fun aFragmentThatIsNotFormEncodedIsKept() {
        val sink = RecordingSink(AppMonitorOptions(maskedQueryParameters = setOf("access_token")))
        server.enqueue(MockResponse.Builder().body("ok").build())
        val url = server.url("/docs#access_token")

        client(sink).newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

        assertEquals(url.toString(), sink.started().url)
    }

    @Test
    fun aRedirectLocationGetsTheUrlRule() {
        val sink = RecordingSink(AppMonitorOptions(maskedQueryParameters = setOf("code", "access_token")))
        val location = "https://user:s3cret@client.example.com/cb?code=abc&state=1#access_token=tok&token_type=bearer"
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .setHeader("Location", location)
                .setHeader("Content-Location", "/landing?code=def")
                .build(),
        )
        val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .addInterceptor(AppMonitorInterceptor(sink))
                .build()

        client.newCall(Request.Builder().url(server.url("/authorize")).build()).execute().close()

        sink.started()
        val headers = sink.received().headers
        assertContains(
            headers,
            Header("Location", "https://██:██@client.example.com/cb?code=██&state=1#access_token=██&token_type=bearer"),
        )
        assertContains(headers, Header("Content-Location", server.url("/landing").toString() + "?code=██"))
    }

    @Test
    fun aLocationNamedInMaskedHeadersIsMaskedWhole() {
        val sink = RecordingSink(AppMonitorOptions(maskedHeaders = setOf("location")))
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .setHeader("Location", "/next")
                .build(),
        )
        val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .addInterceptor(AppMonitorInterceptor(sink))
                .build()

        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()

        sink.started()
        assertContains(sink.received().headers, Header("Location", MASK))
    }

    @Test
    fun aCustomSchemeLocationIsMasked() {
        val sink = RecordingSink(AppMonitorOptions(maskedQueryParameters = setOf("code")))
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .setHeader("Location", "com.example.app:/oauth?code=abc")
                .build(),
        )
        val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .addInterceptor(AppMonitorInterceptor(sink))
                .build()

        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()

        sink.started()
        assertContains(sink.received().headers, Header("Location", "com.example.app:/oauth?code=$MASK"))
    }

    @Test
    fun maskingOffSendsEveryValueInClear() {
        val sink =
            RecordingSink(
                AppMonitorOptions(maskedHeaders = setOf("x-api-key"), maskedQueryParameters = setOf("token"), masking = false),
            )
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .setHeader("Set-Cookie", "session=s3cret")
                .setHeader("Location", "https://a:b@example.com/cb?token=abc#token=def")
                .build(),
        )
        val url =
            server
                .url("/me?token=abc&page=1#token=def")
                .newBuilder()
                .username("user")
                .password("s3cret")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer abc")
                .header("X-Api-Key", "key")
                .build()
        val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .addInterceptor(AppMonitorInterceptor(sink))
                .build()

        client.newCall(request).execute().close()

        val started = sink.started()
        assertEquals(url.toString(), started.url)
        assertContains(started.headers, Header("Authorization", "Bearer abc"))
        assertContains(started.headers, Header("X-Api-Key", "key"))
        val received = sink.received()
        assertContains(received.headers, Header("Set-Cookie", "session=s3cret"))
        assertContains(received.headers, Header("Location", "https://a:b@example.com/cb?token=abc#token=def"))
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

    // ADR-0129: what the Ktor plugin captured on Ktor's OkHttp engine.
    @Test
    fun aRequestMarkedByTheKtorPluginIsNotCapturedAndLosesTheMark() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().body("ok").build())
        val request =
            Request
                .Builder()
                .url(server.url("/"))
                .header("X-AppMonitor-Capture", "ktor")
                .build()

        val body = client(sink).newCall(request).execute().use { it.body.string() }

        assertEquals("ok", body)
        assertEquals(0, sink.pending)
        assertNull(server.takeRequest().headers["X-AppMonitor-Capture"])
    }

    @Test
    fun aRequestMarkedByTheKtorPluginLosesTheMarkInANetworkInterceptor() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().body("ok").build())
        val client = OkHttpClient.Builder().addNetworkInterceptor(AppMonitorInterceptor(sink)).build()
        val request =
            Request
                .Builder()
                .url(server.url("/"))
                .header("X-AppMonitor-Capture", "ktor")
                .build()

        client.newCall(request).execute().use { it.body.string() }

        assertEquals(0, sink.pending)
        assertNull(server.takeRequest().headers["X-AppMonitor-Capture"])
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
