package appmonitor.desktop.app.store

import appmonitor.desktop.domain.ProxyRepository
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.proxy.ProxyCa
import appmonitor.desktop.proxy.ProxyRecording
import appmonitor.desktop.proxy.ProxyServer
import appmonitor.desktop.proxy.proxyPort
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.BindException
import java.nio.file.Path
import javax.net.ssl.TrustManagerFactory

/**
 * Starts and stops the proxy with the `proxy.enabled` switch (missing = off, ADR-0004); each start builds a server and
 * a session of its own. A taken port or any start failure is a [status], never a throw. Once [close]d it stays off.
 */
internal class ProxyController(
    private val storage: Storage,
    private val directory: Path,
    override val port: Int = proxyPort(),
    private val upstreamTrust: TrustManagerFactory? = null,
) : ProxyRepository {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ProxyServer? = null
    private var recording: ProxyRecording? = null
    private var closed = false
    private val switchLock = Any()
    private var switched = false
    private val mutableStatus = MutableStateFlow<ServiceStatus>(ServiceStatus.Off)
    private val mutableEnabled = MutableStateFlow(false)

    override val status: StateFlow<ServiceStatus> = mutableStatus.asStateFlow()
    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    override val host: String get() = ProxyServer.HOST

    /** Where the CA certificate is once the proxy started; what `curl --cacert` and simulators take. */
    val caPath: Path get() = directory.resolve(ProxyCa.CERTIFICATE_FILE)
    override val caFile: String get() = caPath.toString()

    /** Reads the switch and starts the proxy when it is on. */
    suspend fun start() {
        val stored = storage.setting(KEY_ENABLED) == true.toString()
        lock.withLock {
            val on =
                synchronized(switchLock) {
                    if (!switched) mutableEnabled.value = stored
                    mutableEnabled.value
                }
            if (on) startLocked()
        }
    }

    /** Persists the switch, then starts or stops the proxy. */
    override suspend fun setEnabled(on: Boolean) {
        synchronized(switchLock) {
            switched = true
            mutableEnabled.value = on
        }
        storage.setSetting(KEY_ENABLED, on.toString())
        lock.withLock { if (on) startLocked() else stopLocked() }
    }

    /** Deletes the CA (ADR-0101 C.1); a running proxy restarts with a new one. */
    override suspend fun resetCa() =
        lock.withLock {
            val running = server != null
            stopLocked()
            withContext(Dispatchers.IO) { ProxyCa.delete(directory) }
            if (running) startLocked()
        }

    /** After "Clear all data": the CA deleted with it, the switch written back, and a new session when running. */
    suspend fun afterClearAll() {
        if (mutableEnabled.value) storage.setSetting(KEY_ENABLED, true.toString())
        resetCa()
    }

    /** Stops the proxy for good; call it before the store closes. */
    suspend fun close() =
        withContext(NonCancellable) {
            lock.withLock {
                closed = true
                stopLocked()
            }
            scope.cancel()
        }

    private suspend fun startLocked() {
        if (closed || server != null) return
        mutableStatus.value = ServiceStatus.Starting
        val next =
            try {
                val ca = withContext(Dispatchers.IO) { ProxyCa.loadOrCreate(directory) }
                val nextRecording = ProxyRecording(storage, scope, open = false)
                ProxyServer(ca, nextRecording::record, port, upstreamTrust) to nextRecording
            } catch (e: CancellationException) {
                mutableStatus.value = ServiceStatus.Off
                throw e
            } catch (e: Exception) {
                mutableStatus.value = ServiceStatus.Failed(e.message ?: e::class.simpleName ?: "unknown error")
                return
            }
        val (nextServer, nextRecording) = next
        mutableStatus.value =
            try {
                ServiceStatus.Running(withContext(Dispatchers.IO) { nextServer.start() }).also {
                    nextRecording.open()
                    server = nextServer
                    recording = nextRecording
                }
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { nextServer.stop() }
                withContext(NonCancellable) { nextRecording.close() }
                if (e is CancellationException) {
                    mutableStatus.value = ServiceStatus.Off
                    throw e
                }
                if (e is BindException) {
                    ServiceStatus.PortTaken(port)
                } else {
                    ServiceStatus.Failed(e.message ?: e::class.simpleName ?: "unknown error")
                }
            }
    }

    private suspend fun stopLocked() {
        val stopping = server
        val written = recording
        server = null
        recording = null
        withContext(NonCancellable + Dispatchers.IO) { stopping?.stop() }
        withContext(NonCancellable) { written?.close() }
        mutableStatus.value = ServiceStatus.Off
    }

    companion object {
        const val KEY_ENABLED = "proxy.enabled"
    }
}
