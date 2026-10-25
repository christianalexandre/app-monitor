package appmonitor.desktop.app.store

import appmonitor.desktop.mcp.McpAccess
import appmonitor.desktop.mcp.McpServer
import appmonitor.desktop.mcp.mcpPort
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.BindException

/** What the settings show about the MCP server (SDD 10). */
internal sealed interface McpStatus {
    data object Off : McpStatus

    data object Starting : McpStatus

    data class Running(
        val port: Int,
    ) : McpStatus

    data class PortTaken(
        val port: Int,
    ) : McpStatus

    data class Failed(
        val message: String,
    ) : McpStatus
}

/**
 * Starts and stops the MCP server with the `mcp.enabled` switch (missing = on, ADR-0090 C.1); a server is single use,
 * so each start builds one. A taken port or any start failure is a [status], never a throw. Once [close]d it stays off.
 */
internal class McpController(
    private val storage: Storage,
    private val access: McpAccess,
    val port: Int = mcpPort(),
) {
    private val lock = Mutex()
    private var server: McpServer? = null
    private var closed = false
    private val mutableStatus = MutableStateFlow<McpStatus>(McpStatus.Off)
    private val mutableEnabled = MutableStateFlow(true)

    val status: StateFlow<McpStatus> = mutableStatus.asStateFlow()
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    val token: StateFlow<String> get() = access.token

    /** Reads the switch and starts the server when it is on. */
    suspend fun start() {
        mutableEnabled.value = storage.setting(KEY_ENABLED) != false.toString()
        lock.withLock { if (mutableEnabled.value) startLocked() }
    }

    /** Persists the switch, then starts or stops the server. */
    suspend fun setEnabled(on: Boolean) {
        mutableEnabled.value = on
        storage.setSetting(KEY_ENABLED, on.toString())
        lock.withLock { if (on) startLocked() else stopLocked() }
    }

    /** A new token at once; clients with the old one get `401`. */
    suspend fun regenerate() {
        access.regenerate()
    }

    /** After "Clear all data" deleted every setting: a new token, and the switch written back as it was. */
    suspend fun afterClearAll() {
        access.regenerate()
        if (!mutableEnabled.value) storage.setSetting(KEY_ENABLED, false.toString())
    }

    /** Stops the server for good; call it before the store closes. */
    suspend fun close() =
        withContext(NonCancellable) {
            lock.withLock {
                closed = true
                stopLocked()
            }
        }

    private suspend fun startLocked() {
        if (closed || server != null) return
        mutableStatus.value = McpStatus.Starting
        val next = McpServer(storage, access, port)
        mutableStatus.value =
            try {
                McpStatus.Running(next.start()).also { server = next }
            } catch (e: CancellationException) {
                next.stop()
                mutableStatus.value = McpStatus.Off
                throw e
            } catch (e: BindException) {
                next.stop()
                McpStatus.PortTaken(port)
            } catch (e: Exception) {
                next.stop()
                McpStatus.Failed(e.message ?: e::class.simpleName ?: "unknown error")
            }
    }

    private suspend fun stopLocked() {
        server?.stop()
        server = null
        mutableStatus.value = McpStatus.Off
    }

    companion object {
        const val KEY_ENABLED = "mcp.enabled"
    }
}
