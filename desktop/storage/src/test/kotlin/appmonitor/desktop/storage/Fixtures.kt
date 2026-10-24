package appmonitor.desktop.storage

import appmonitor.protocol.Protocol
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import com.squareup.wire.Message
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

internal fun hello(deviceId: String = "device-1") =
    Hello(
        protocol_version = Protocol.VERSION,
        platform = Platform.PLATFORM_ANDROID,
        device_id = deviceId,
        device_name = "Pixel 9",
        os_version = "16",
        app_id = "dev.appmonitor.sample",
        app_version = "1.0",
        sdk_version = "0.1.0",
        channels = listOf(Protocol.CHANNEL_NETWORK),
        sdk_name = "kotlin",
    )

internal fun envelope(
    message: Message<*, *>,
    atMs: Long = 1_000,
    channel: String = Protocol.CHANNEL_NETWORK,
) = Envelope(channel = channel, type = message::class.simpleName!!, payload = message.encodeByteString(), timestamp_ms = atMs)

internal fun started(
    requestId: String,
    url: String = "https://api.example.com/items/$requestId",
    body: ByteString = ByteString.EMPTY,
) = RequestStarted(
    request_id = requestId,
    method = "GET",
    url = url,
    headers = listOf(Header("Accept", "application/json")),
    body = body,
    capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
)

internal fun text(size: Int) = "{\"name\":\"value\"}".repeat(size / 16 + 1).take(size).encodeUtf8()

internal fun random(size: Int) = ByteArray(size).also { kotlin.random.Random(7).nextBytes(it) }.toByteString()

/** A temporary data directory, deleted after [block]. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
internal inline fun withDataDir(block: (Path) -> Unit) {
    val dir = createTempDirectory("storage-test").resolve("data")
    try {
        block(dir)
    } finally {
        dir.parent.deleteRecursively()
    }
}

/** Reads one long through a plain connection, outside the store. */
internal fun Path.queryLong(sql: String): Long =
    DriverManager.getConnection("jdbc:sqlite:${resolve(Storage.FILE_NAME)}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use {
                it.next()
                it.getLong(1)
            }
        }
    }

internal fun Path.queryStrings(sql: String): List<String> =
    DriverManager.getConnection("jdbc:sqlite:${resolve(Storage.FILE_NAME)}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { buildList { while (it.next()) add(it.getString(1)) } }
        }
    }
