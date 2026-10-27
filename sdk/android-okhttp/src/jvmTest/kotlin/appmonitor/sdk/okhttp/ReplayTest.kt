package appmonitor.sdk.okhttp

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** ADR-0111 D.1, ADR-0122 B.1: a captured call sent again through the app's own client, marked as a replay. */
class ReplayTest {
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
    fun aCallIsSentAgainThroughTheAppsInterceptorsAndCapturedAsAReplay() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().body("first").build())
        server.enqueue(MockResponse.Builder().body("second").build())
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor {
                    it.proceed(
                        it
                            .request()
                            .newBuilder()
                            .header("X-App", "token-1")
                            .build(),
                    )
                }.addInterceptor(AppMonitorInterceptor(sink))
                .build()
        val request =
            Request
                .Builder()
                .url(server.url("/orders"))
                .post("""{"n":1}""".toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().use { it.body.string() }
        val original = sink.started()
        assertNull(original.replay_of)
        sink.received()

        assertNull(assertNotNull(sink.replays[original.request_id]).invoke())

        val replay = sink.started()
        assertEquals(original.request_id, replay.replay_of)
        assertEquals("POST", replay.method)
        assertEquals(original.url, replay.url)
        assertEquals(original.body, replay.body)
        val received = sink.received()
        assertEquals(replay.request_id, received.request_id)
        assertEquals("second", received.body.utf8())
        server.takeRequest()
        val sent = server.takeRequest()
        assertEquals("token-1", sent.headers["X-App"])
        assertEquals("""{"n":1}""", sent.body?.utf8())
        assertNotNull(sink.replays[replay.request_id], "a replay can be sent again too")
    }

    @Test
    fun aOneShotBodyIsRefused() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().build())
        val oneShot =
            object : RequestBody() {
                override fun contentType() = null

                override fun isOneShot() = true

                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8("once")
                }
            }
        val client = OkHttpClient.Builder().addInterceptor(AppMonitorInterceptor(sink)).build()
        client
            .newCall(
                Request
                    .Builder()
                    .url(server.url("/upload"))
                    .post(oneShot)
                    .build(),
            ).execute()
            .close()
        val original = sink.started()

        assertEquals("Its body could be sent only once", assertNotNull(sink.replays[original.request_id]).invoke())
        assertEquals(1, server.requestCount)
    }
}
