package appmonitor.desktop.mcp

import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StorageHealth
import appmonitor.protocol.Protocol
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.ByteString
import java.net.Socket
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.seconds

internal fun hello(deviceId: String = "device-1") =
    Hello(
        protocol_version = Protocol.VERSION,
        platform = Platform.PLATFORM_IOS,
        device_id = deviceId,
        device_name = "iPhone 17",
        os_version = "26.0",
        app_id = "dev.appmonitor.sample",
        app_version = "1.2",
        sdk_version = "0.1.0",
        channels = listOf(Protocol.CHANNEL_NETWORK),
        sdk_name = "swift",
    )

internal fun started(requestId: String) =
    RequestStarted(
        request_id = requestId,
        method = "GET",
        url = "https://api.example.com/items/$requestId",
        capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
    ).let { Envelope(channel = Protocol.CHANNEL_NETWORK, type = "RequestStarted", payload = it.encodeByteString(), timestamp_ms = 1_000) }

/** A store on a temporary data directory, closed and deleted after [block]. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
internal fun withStorage(
    clock: () -> Long = { 1_000 },
    block: suspend (Storage) -> Unit,
) {
    val dir: Path = createTempDirectory("mcp-test")
    try {
        val storage = Storage.open(dir.resolve("data"), clock)
        try {
            runBlocking { block(storage) }
        } finally {
            // A second close is not supported: tests may close the store themselves.
            if (!storage.isClosing) runBlocking { storage.close(5.seconds) }
        }
    } finally {
        dir.deleteRecursively()
    }
}

/** A started server on a free port over a fresh store, stopped after [block]. */
internal fun withServer(
    health: () -> StorageHealth? = { null },
    beforeTool: suspend () -> Unit = {},
    block: suspend (Running) -> Unit,
) = withStorage { storage ->
    val access = McpAccess.open(storage)
    val server = McpServer(storage, access, 0, "test", { health() ?: storage.health.value }, beforeTool)
    val port = server.start()
    try {
        block(Running(storage, access, server, port))
    } finally {
        server.stop()
    }
}

internal class Running(
    val storage: Storage,
    val access: McpAccess,
    val server: McpServer,
    val port: Int,
) {
    val url get() = "http://127.0.0.1:$port${McpServer.PATH}"

    /** An MCP SDK client over HTTP with [token], closed after [block]. */
    suspend fun <R> client(
        token: String = access.token.value,
        block: suspend (Client) -> R,
    ): R {
        val http = HttpClient(CIO) { install(SSE) }
        try {
            val client = http.mcpStreamableHttp(url) { header(HttpHeaders.Authorization, "Bearer $token") }
            try {
                return block(client)
            } finally {
                client.close()
            }
        } finally {
            http.close()
        }
    }

    fun post(
        body: String = INITIALIZE,
        headers: Map<String, String?> = mapOf(),
    ): RawResponse {
        val all =
            linkedMapOf<String, String?>(
                "Host" to "127.0.0.1:$port",
                "Authorization" to "Bearer ${access.token.value}",
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/event-stream",
            )
        all.putAll(headers)
        return raw(port, "POST", McpServer.PATH, all.filterValues { it != null }.mapValues { it.value!! }, body)
    }
}

internal const val INITIALIZE =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
        """"clientInfo":{"name":"raw","version":"1"}}}"""

internal class RawResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** One HTTP/1.1 request on a plain socket, so `Host` and `Origin` are exactly what the test says. */
internal fun raw(
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String>,
    body: String = "",
): RawResponse =
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val bytes = body.toByteArray()
        val request =
            buildString {
                append("$method $path HTTP/1.1\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
        socket.getOutputStream().apply {
            write(request.toByteArray())
            write(bytes)
            flush()
        }
        val text = socket.getInputStream().readBytes().decodeToString()
        val (head, rest) = text.split("\r\n\r\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val lines = head.split("\r\n")
        RawResponse(
            status = lines[0].split(" ")[1].toInt(),
            headers = lines.drop(1).associate { it.substringBefore(":").lowercase() to it.substringAfter(":").trim() },
            body = rest,
        )
    }

internal val CallToolResult.text: String get() = (content.single() as TextContent).text

internal val CallToolResult.json: JsonObject get() = Json.parseToJsonElement(text).jsonObject

internal fun envelope(
    message: com.squareup.wire.Message<*, *>,
    atMs: Long = 1_000,
) = Envelope(
    channel = Protocol.CHANNEL_NETWORK,
    type = message::class.simpleName!!,
    payload = message.encodeByteString(),
    timestamp_ms = atMs,
)

internal fun request(
    requestId: String,
    method: String = "GET",
    url: String = "https://api.example.com/items/$requestId",
    headers: List<Header> = listOf(Header("Accept", "application/json")),
    body: ByteString = ByteString.EMPTY,
    bodyTruncated: Boolean = false,
    bodySize: Long? = null,
) = RequestStarted(
    request_id = requestId,
    method = method,
    url = url,
    headers = headers,
    body = body,
    body_truncated = bodyTruncated,
    body_size = bodySize,
    capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
)

internal fun response(
    requestId: String,
    status: Int = 200,
    headers: List<Header> = listOf(Header("Content-Type", "application/json")),
    body: ByteString = ByteString.EMPTY,
    bodyTruncated: Boolean = false,
    bodySize: Long? = null,
) = ResponseReceived(
    request_id = requestId,
    status_code = status,
    headers = headers,
    body = body,
    body_truncated = bodyTruncated,
    body_size = bodySize,
)
