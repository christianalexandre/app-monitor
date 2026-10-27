package appmonitor.desktop.app

import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredExchange
import appmonitor.protocol.Protocol
import appmonitor.tools.fakeclient.Fixtures
import appmonitor.tools.fakeclient.Target
import appmonitor.tools.fakeclient.replay
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import com.squareup.wire.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.FileSystemException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.seconds

internal const val TIMEOUT_MS = 10_000L

internal val HELLO =
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

/** Collects sessions without storing them, for tests about the server's lifecycle only. */
internal val drain: suspend (Flow<DeviceSession>) -> Unit = { it.collect() }

/** A temporary data directory, deleted after [block]. */
internal fun <T> withDataDir(block: (Path) -> T): T {
    val dir = createTempDirectory("app-test").resolve("data")
    try {
        return block(dir)
    } finally {
        deleteWhenReleased(dir.parent)
    }
}

// Windows keeps a file just closed locked for a moment (a slow close, its scanner): try again before failing.
@OptIn(ExperimentalPathApi::class)
private fun deleteWhenReleased(dir: Path) {
    repeat(DELETE_TRIES - 1) {
        try {
            return dir.deleteRecursively()
        } catch (_: FileSystemException) {
            Thread.sleep(DELETE_PAUSE_MS)
        }
    }
    dir.deleteRecursively()
}

private const val DELETE_TRIES = 20
private const val DELETE_PAUSE_MS = 500L

/** A store in a temporary data directory, closed after [block]. */
internal fun <T> withStorage(block: (Storage) -> T): T =
    withDataDir { dir ->
        val storage = Storage.open(dir)
        try {
            block(storage)
        } finally {
            runBlocking { storage.close(5.seconds) }
        }
    }

internal fun envelope(
    message: Message<*, *>,
    atMs: Long = 1_000,
) = Envelope(
    channel = Protocol.CHANNEL_NETWORK,
    type = message::class.simpleName!!,
    payload = message.encodeByteString(),
    timestamp_ms = atMs,
)

/** Puts one finished session holding [envelopes] in the live list, as a device would have sent them. */
internal fun LiveTraffic.record(
    vararg envelopes: Envelope,
    sessionId: String = "s1",
    hello: Hello = HELLO,
) = runBlocking {
    connected(sessionId, hello, "127.0.0.1")
    envelopes.forEach { received(sessionId, it) }
    disconnected(sessionId)
}

/** Stores one finished session holding [envelopes], as a recording would have. */
internal fun Storage.record(
    vararg envelopes: Envelope,
    sessionId: String = "s1",
    hello: Hello = HELLO,
) = runBlocking {
    connected(sessionId, hello, "127.0.0.1")
    envelopes.forEach { received(sessionId, it) }
    disconnected(sessionId)
    flush()
}

// Real server and fake client: what is listed is what actually crossed the wire.
internal fun LiveTraffic.receive(
    storage: Storage,
    traffic: List<Envelope> = Fixtures.traffic,
) = runBlocking {
    val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
    try {
        val port = server.start()
        val collecting = launch(Dispatchers.Default) { TrafficStore(storage, this@receive).collect(server.sessions) }
        withTimeout(TIMEOUT_MS * 6) {
            replay(Target("127.0.0.1", port), traffic = traffic, closeTimeoutMs = TIMEOUT_MS * 6)
            while (sessions().let { it.isEmpty() || it.any { session -> session.disconnectedAtMs == null } }) delay(20)
        }
        collecting.cancel()
    } finally {
        server.stop()
    }
}

/** Every exchange of the run, read page by page. */
internal suspend fun Storage.allExchanges(runId: Long = this.runId): List<StoredExchange> {
    val all = mutableListOf<StoredExchange>()
    while (true) {
        val page = exchangesOfRun(runId, afterId = all.lastOrNull()?.id ?: 0)
        if (page.isEmpty()) return all
        all += page
    }
}
