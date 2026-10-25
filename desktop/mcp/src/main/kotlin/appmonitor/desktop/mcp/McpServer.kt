package appmonitor.desktop.mcp

import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StorageHealth
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The MCP server, read-only but `save_issue` and `create_mock`, over stateless Streamable HTTP on `127.0.0.1:<port>/mcp` (SDD 10, ADR-0092). Single use:
 * [start] once, [stop] any number of times.
 */
class McpServer internal constructor(
    private val storage: Storage,
    private val access: McpAccess,
    private val port: Int,
    private val version: String,
    private val health: () -> StorageHealth,
    private val beforeTool: suspend () -> Unit,
) {
    constructor(
        storage: Storage,
        access: McpAccess,
        port: Int = mcpPort(),
        version: String = "dev",
    ) : this(storage, access, port, version, { storage.health.value }, {})

    private val mcp =
        Server(
            Implementation(name = "app-monitor", version = version, title = "App Monitor"),
            ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        ).apply {
            tool(ListRuns.NAME, ListRuns.description, ListRuns.inputSchema) { ListRuns.call(storage, it) }
            tool(SearchRequests.NAME, SearchRequests.description, SearchRequests.inputSchema) { SearchRequests.call(storage, it) }
            tool(GetRequest.NAME, GetRequest.description, GetRequest.inputSchema) { GetRequest.call(storage, it) }
            tool(EndpointStats.NAME, EndpointStats.description, EndpointStats.inputSchema) { EndpointStats.call(storage, it) }
            tool(DiffRuns.NAME, DiffRuns.description, DiffRuns.inputSchema) { DiffRuns.call(storage, it) }
            result(
                ExportHar.NAME,
                ExportHar.description,
                ExportHar.inputSchema,
            ) { harResult(ExportHar.call(storage, it, version), health()) }
            textTool(ToCurl.NAME, ToCurl.description, ToCurl.inputSchema) { ToCurl.call(storage, it) }
            tool(ListIssues.NAME, ListIssues.description, ListIssues.inputSchema) { ListIssues.call(storage, it) }
            result(SaveIssue.NAME, SaveIssue.description, SaveIssue.inputSchema, WRITES) {
                jsonResult(SaveIssue.call(storage, it, health), health())
            }
            result(CreateMock.NAME, CreateMock.description, CreateMock.inputSchema, WRITES) {
                jsonResult(CreateMock.call(storage, it, health), health())
            }
        }

    private val lock = Mutex()
    private var engine: EmbeddedServer<*, *>? = null
    private var started = false
    private var stopped = false

    /** Binds `127.0.0.1` and returns the port; throws `java.net.BindException` when it is taken. */
    suspend fun start(): Int =
        lock.withLock {
            check(!stopped) { "MCP server is single use: create a new instance" }
            check(!started) { "MCP server already started" }
            started = true
            // CIO also reports a taken port as an uncaught exception on Dispatchers.IO: refuse it before CIO sees it.
            if (port != 0) withContext(Dispatchers.IO) { ServerSocket().use { it.bind(InetSocketAddress(LOOPBACK, port)) } }
            val server = embeddedServer(CIO, host = LOOPBACK, port = port) { module() }
            try {
                server.startSuspend(wait = false)
                engine = server
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            } catch (e: Throwable) {
                withContext(NonCancellable) { server.stopSuspend(0, STOP_TIMEOUT.inWholeMilliseconds) }
                engine = null
                // Ktor's CIO reports a taken port as a cancellation caused by the BindException.
                throw generateSequence(e) { it.cause }.firstOrNull { it is BindException } ?: e
            }
        }

    /** Waits for a [start] in flight, lets calls in flight finish within [GRACE], and frees the port within [STOP_TIMEOUT]. */
    suspend fun stop() {
        withContext(NonCancellable) {
            lock.withLock {
                stopped = true
                engine?.stopSuspend(GRACE.inWholeMilliseconds, STOP_TIMEOUT.inWholeMilliseconds)
                engine = null
            }
        }
    }

    private fun Application.module() {
        install(accessCheck { access.token.value })
        mcpStatelessStreamableHttp(path = PATH) { mcp }
    }

    private fun Server.tool(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        call: suspend (JsonObject?) -> JsonObject,
    ) = result(name, description, inputSchema) { jsonResult(call(it), health()) }

    private fun Server.textTool(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        call: suspend (JsonObject?) -> String,
    ) = result(name, description, inputSchema) { textResult(call(it), health()) }

    private fun Server.result(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        annotations: ToolAnnotations = READS,
        call: suspend (JsonObject?) -> CallToolResult,
    ) {
        addTool(
            name = name,
            description = description,
            inputSchema = inputSchema,
            toolAnnotations = annotations,
        ) { request ->
            beforeTool()
            // A closing store reads as empty and refuses writes: checked after the call, whatever it answered.
            try {
                call(request.arguments).takeUnless { storage.isClosing } ?: errorResult(CLOSING)
            } catch (e: ToolError) {
                errorResult(if (storage.isClosing) CLOSING else e.message!!)
            }
        }
    }

    companion object {
        const val PATH = "/mcp"
        internal const val LOOPBACK = "127.0.0.1"
        val GRACE: Duration = 500.milliseconds
        val STOP_TIMEOUT: Duration = 5.seconds
        private val READS = ToolAnnotations(readOnlyHint = true, openWorldHint = false)

        // ADR-0095 C.1, ADR-0114 C.1: creates and edits, never deletes.
        private val WRITES = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false)
    }
}
