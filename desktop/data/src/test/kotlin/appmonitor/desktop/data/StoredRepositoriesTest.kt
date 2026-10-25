package appmonitor.desktop.data

import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.StatusClass
import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import com.squareup.wire.Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class StoredRepositoriesTest {
    @Test
    fun `runs are read, renamed and started through the repository`() =
        withStorage { storage ->
            val runs = storage.runRepository()
            runs.rename(runs.currentRun.value, "  login  ")
            storage.flush()
            assertEquals(
                "login",
                runs
                    .observeRuns()
                    .first()
                    .single()
                    .name,
            )

            val next = runs.newRun()
            storage.flush()
            assertEquals(next, runs.currentRun.value)
            assertEquals(listOf(next, next - 1), runs.observeRuns().first().map { it.id })
        }

    @Test
    fun `sessions, filtered exchanges and one exchange read as the store holds them`() =
        withStorage { storage ->
            storage.record(
                envelope(RequestStarted("r1", "GET", "https://api.test/users", listOf(Header("Accept", "*/*")))),
                envelope(ResponseReceived("r1", 200, body = "[]".encodeUtf8(), body_size = 2)),
                envelope(RequestStarted("r2", "POST", "https://api.test/login")),
                envelope(RequestFailed("r2", "timeout")),
            )
            val traffic = storage.trafficRepository()

            val session = traffic.observeSessions(storage.runId).first().single()
            assertEquals("app", session.hello.app_id)
            assertEquals(listOf(session), traffic.observeSessions(null).first())

            val all = traffic.observeExchanges(ExchangeFilter(sessionId = session.id)).first()
            assertEquals(listOf("r1", "r2"), all.map { it.requestId })
            assertEquals(listOf(false, true), all.map { it.isError })
            val ok = traffic.observeExchanges(ExchangeFilter(sessionId = session.id, statusClass = StatusClass.SUCCESS)).first()
            assertEquals(listOf("r1"), ok.map { it.requestId })
            assertEquals(listOf("r2"), traffic.observeExchanges(ExchangeFilter(text = "login")).first().map { it.requestId })

            val detail = traffic.exchange(all.first().id)!!
            assertEquals(listOf(Header("Accept", "*/*")), detail.requestHeaders)
            assertEquals(CapturedBody("[]".encodeUtf8(), originalSize = 2), detail.responseBody)
            assertEquals(200, detail.statusCode)
            assertNull(detail.connection)
            assertEquals("timeout", traffic.exchange(all.last().id)!!.error)
            assertNull(traffic.exchange(all.last().id + 1))
        }

    @Test
    fun `every status class maps to the store's`() {
        StatusClass.entries.forEach { assertEquals(it.name, it.toStored().name) }
        assertEquals(StatusClass.entries.map { it.from }, StatusClass.entries.map { it.toStored().from })
    }

    @Test
    fun `health starts at the store's`() =
        withStorage { storage ->
            assertEquals(
                0,
                storage
                    .trafficRepository()
                    .health
                    .first()
                    .notSaved,
            )
        }
}

private val HELLO =
    Hello(
        protocol_version = Protocol.VERSION,
        platform = Platform.PLATFORM_ANDROID,
        device_id = "d1",
        device_name = "Pixel",
        os_version = "Android 16",
        app_id = "app",
        app_version = "1.0",
        channels = listOf(Protocol.CHANNEL_NETWORK),
    )

private fun envelope(message: Message<*, *>) =
    Envelope(
        channel = Protocol.CHANNEL_NETWORK,
        type = message::class.simpleName!!,
        payload = message.encodeByteString(),
        timestamp_ms = 1_000,
    )

private suspend fun Storage.record(vararg envelopes: Envelope) {
    connected("s1", HELLO, "127.0.0.1")
    envelopes.forEach { received("s1", it) }
    disconnected("s1")
    flush()
}

@OptIn(ExperimentalPathApi::class)
private fun withStorage(block: suspend (Storage) -> Unit) {
    val dir = createTempDirectory("data-test")
    val storage = Storage.open(dir.resolve("data"))
    try {
        runBlocking { block(storage) }
    } finally {
        runBlocking { storage.close(5.seconds) }
        dir.deleteRecursively()
    }
}
