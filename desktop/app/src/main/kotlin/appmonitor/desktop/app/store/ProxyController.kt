package appmonitor.desktop.app.store

import appmonitor.desktop.domain.ProxyRepository
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.proxy.ProxyCa
import appmonitor.desktop.proxy.ProxyRecording
import appmonitor.desktop.proxy.ProxyServer
import appmonitor.desktop.proxy.proxyPort
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.TrafficSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.net.ssl.TrustManagerFactory

/**
 * The proxy behind the `proxy.enabled` switch (missing = off, ADR-0004); each start builds a server and a session of
 * its own.
 */
internal class ProxyController(
    storage: Storage,
    private val sink: TrafficSink,
    private val directory: Path,
    override val port: Int = proxyPort(),
    private val upstreamTrust: TrustManagerFactory? = null,
) : ProxyRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ProxyServer? = null
    private var recording: ProxyRecording? = null
    private val switch =
        ServiceSwitch(
            onByDefault = false,
            port = port,
            readStored = { storage.setting(KEY_ENABLED) },
            writeStored = { storage.setSetting(KEY_ENABLED, it) },
            startService = ::startProxy,
            stopService = ::stopProxy,
        )

    override val status: StateFlow<ServiceStatus> get() = switch.status
    override val enabled: StateFlow<Boolean> get() = switch.enabled
    override val host: String get() = ProxyServer.HOST

    /** Where the CA certificate is once the proxy started; what `curl --cacert` and simulators take. */
    val caPath: Path get() = directory.resolve(ProxyCa.CERTIFICATE_FILE)
    override val caFile: String get() = caPath.toString()

    /** Reads the switch and starts the proxy when it is on. */
    suspend fun start() = switch.start()

    /** Persists the switch, then starts or stops the proxy. */
    override suspend fun setEnabled(on: Boolean) = switch.set(on)

    /** Deletes the CA (ADR-0101 C.1); a running proxy restarts with a new one. */
    override suspend fun resetCa() =
        switch.restartAround {
            withContext(Dispatchers.IO) { ProxyCa.delete(directory) }
        }

    /** After "Clear all data": the CA deleted with it, the switch written back, and a new session when running. */
    suspend fun afterClearAll() {
        switch.writeBack()
        resetCa()
    }

    /** Stops the proxy for good; call it before the store closes. */
    suspend fun close() {
        switch.close()
        scope.cancel()
    }

    private suspend fun startProxy(): Int {
        val ca = withContext(Dispatchers.IO) { ProxyCa.loadOrCreate(directory) }
        val nextRecording = ProxyRecording(sink, scope, open = false)
        val nextServer = ProxyServer(ca, nextRecording::record, port, upstreamTrust)
        try {
            val bound = withContext(Dispatchers.IO) { nextServer.start() }
            nextRecording.open()
            server = nextServer
            recording = nextRecording
            return bound
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { nextServer.stop() }
            withContext(NonCancellable) { nextRecording.close() }
            throw e
        }
    }

    private suspend fun stopProxy() {
        withContext(Dispatchers.IO) { server?.stop() }
        recording?.close()
        server = null
        recording = null
    }

    companion object {
        const val KEY_ENABLED = "proxy.enabled"
    }
}
