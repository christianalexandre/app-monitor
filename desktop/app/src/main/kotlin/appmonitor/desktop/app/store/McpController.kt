package appmonitor.desktop.app.store

import appmonitor.desktop.domain.McpRepository
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.mcp.McpAccess
import appmonitor.desktop.mcp.McpServer
import appmonitor.desktop.mcp.ReplayAsks
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

/**
 * Starts and stops the MCP server with the `mcp.enabled` switch (missing = on, ADR-0090 C.1); a server is single use,
 * so each start builds one. A taken port or any start failure is a [status], never a throw. Once [close]d it stays off.
 * A [setEnabled] before [start] writes the stored switch wins over it.
 */
internal class McpController(
    private val storage: Storage,
    private val access: McpAccess,
    override val port: Int = mcpPort(),
    private val replays: ReplayAsks? = null,
    private val readSwitch: suspend () -> String? = { storage.setting(KEY_ENABLED) },
) : McpRepository {
    private val lock = Mutex()
    private var server: McpServer? = null
    private var closed = false
    private val switchLock = Any()
    private var switched = false
    private val mutableStatus = MutableStateFlow<ServiceStatus>(ServiceStatus.Off)
    private val mutableEnabled = MutableStateFlow(true)

    override val status: StateFlow<ServiceStatus> = mutableStatus.asStateFlow()
    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    override val token: StateFlow<String> get() = access.token
    override val path: String get() = McpServer.PATH

    /** Reads the switch and starts the server when it is on. */
    suspend fun start() {
        val stored = readSwitch() != false.toString()
        lock.withLock {
            val on =
                synchronized(switchLock) {
                    if (!switched) mutableEnabled.value = stored
                    mutableEnabled.value
                }
            if (on) startLocked()
        }
    }

    /** Persists the switch, then starts or stops the server. */
    override suspend fun setEnabled(on: Boolean) {
        synchronized(switchLock) {
            switched = true
            mutableEnabled.value = on
        }
        storage.setSetting(KEY_ENABLED, on.toString())
        // DEBT-0073: the switch as it is now, so an earlier call that takes the lock last does not undo a later one.
        lock.withLock { if (mutableEnabled.value) startLocked() else stopLocked() }
    }

    /** A new token at once; clients with the old one get `401`. */
    override suspend fun regenerate() {
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
        mutableStatus.value = ServiceStatus.Starting
        val next = McpServer(storage, access, port, replays = replays)
        mutableStatus.value =
            try {
                ServiceStatus.Running(next.start()).also { server = next }
            } catch (e: CancellationException) {
                next.stop()
                mutableStatus.value = ServiceStatus.Off
                throw e
            } catch (e: BindException) {
                next.stop()
                ServiceStatus.PortTaken(port)
            } catch (e: Exception) {
                next.stop()
                ServiceStatus.Failed(e.message ?: e::class.simpleName ?: "unknown error")
            }
    }

    private suspend fun stopLocked() {
        server?.stop()
        server = null
        mutableStatus.value = ServiceStatus.Off
    }

    companion object {
        const val KEY_ENABLED = "mcp.enabled"
    }
}
