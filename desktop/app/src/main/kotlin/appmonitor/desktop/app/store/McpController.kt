package appmonitor.desktop.app.store

import appmonitor.desktop.data.benchmarkRepository
import appmonitor.desktop.data.issueRepository
import appmonitor.desktop.data.mockRepository
import appmonitor.desktop.data.runRepository
import appmonitor.desktop.data.trafficQueryRepository
import appmonitor.desktop.domain.McpRepository
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.mcp.McpAccess
import appmonitor.desktop.mcp.McpRepositories
import appmonitor.desktop.mcp.McpServer
import appmonitor.desktop.mcp.ReplayAsks
import appmonitor.desktop.mcp.mcpPort
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The MCP server behind the `mcp.enabled` switch (missing = on, ADR-0090 C.1); a server is single use, so each start
 * builds one.
 */
internal class McpController(
    private val storage: Storage,
    live: LiveTraffic,
    private val access: McpAccess,
    override val port: Int = mcpPort(),
    private val replays: ReplayAsks? = null,
    private val repositories: McpRepositories =
        McpRepositories(
            storage.runRepository(),
            storage.trafficQueryRepository(live),
            storage.benchmarkRepository(live),
            storage.issueRepository(live),
            storage.mockRepository(),
        ),
    private val version: String = desktopVersion(),
) : McpRepository {
    private var server: McpServer? = null
    private val switch =
        ServiceSwitch(
            onByDefault = true,
            port = port,
            readStored = { storage.setting(KEY_ENABLED) },
            writeStored = { storage.setSetting(KEY_ENABLED, it) },
            startService = ::startServer,
            stopService = ::stopServer,
        )

    override val status: StateFlow<ServiceStatus> get() = switch.status
    override val enabled: StateFlow<Boolean> get() = switch.enabled
    override val token: StateFlow<String> get() = access.token
    override val path: String get() = McpServer.PATH

    /** Reads the switch and starts the server when it is on. */
    suspend fun start() = switch.start()

    /** Persists the switch, then starts or stops the server. */
    override suspend fun setEnabled(on: Boolean) = switch.set(on)

    /** A new token at once; clients with the old one get `401`. */
    override suspend fun regenerate() {
        access.regenerate()
    }

    /** After "Clear all data" deleted every setting: a new token, and the switch written back as it was. */
    suspend fun afterClearAll() {
        access.regenerate()
        switch.writeBack()
    }

    /** Stops the server for good; call it before the store closes. */
    suspend fun close() = switch.close()

    private suspend fun startServer(): Int {
        val next = McpServer(repositories, access, port, version = version, replays = replays)
        try {
            return next.start().also { server = next }
        } catch (e: Exception) {
            withContext(NonCancellable) { next.stop() }
            throw e
        }
    }

    private suspend fun stopServer() {
        server?.stop()
        server = null
    }

    companion object {
        const val KEY_ENABLED = "mcp.enabled"
    }
}

/** The version the build gave the app; `dev` from a test or an IDE. */
internal fun desktopVersion(): String = System.getProperty(VERSION_PROPERTY) ?: "dev"

private const val VERSION_PROPERTY = "appmonitor.version"
