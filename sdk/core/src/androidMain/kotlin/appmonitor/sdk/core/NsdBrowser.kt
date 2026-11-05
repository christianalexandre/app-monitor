package appmonitor.sdk.core

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import appmonitor.protocol.DesktopAnswer
import appmonitor.protocol.Protocol
import java.net.Inet4Address
import java.util.concurrent.Executor

/** [MdnsBrowser] on `NsdManager`: one browse per call, every resolve stopped on close. */
internal class NsdBrowser(
    private val nsd: NsdManager,
) : MdnsBrowser {
    override fun browse(onAnswer: (DesktopAnswer) -> Unit): AutoCloseable = Browse(nsd, onAnswer).also { it.start() }

    private class Browse(
        private val nsd: NsdManager,
        private val onAnswer: (DesktopAnswer) -> Unit,
    ) : NsdManager.DiscoveryListener,
        AutoCloseable {
        // Inline: answers only enqueue, and there is no thread to outlive close().
        private val executor = Executor(Runnable::run)
        private val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
        private val pending = ArrayDeque<NsdServiceInfo>()
        private var resolving = false
        private var closed = false

        fun start() = nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this)

        override fun onServiceFound(service: NsdServiceInfo) {
            synchronized(this) {
                if (closed) return
                if (Build.VERSION.SDK_INT >= 34) {
                    val callback = UpdateCallback()
                    callbacks += callback
                    nsd.registerServiceInfoCallback(service, executor, callback)
                } else {
                    pending.addLast(service)
                    resolveNext()
                }
            }
        }

        // Before API 34 NsdManager runs one resolve at a time; FAILURE_ALREADY_ACTIVE otherwise.
        @Suppress("DEPRECATION")
        private fun resolveNext() {
            if (resolving || closed) return
            val service = pending.removeFirstOrNull() ?: return
            resolving = true
            nsd.resolveService(service, ResolveCallback())
        }

        override fun close() {
            val registered =
                synchronized(this) {
                    closed = true
                    pending.clear()
                    callbacks.toList().also { callbacks.clear() }
                }
            runCatching { nsd.stopServiceDiscovery(this) }
            if (Build.VERSION.SDK_INT >= 34) registered.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }

        private fun answer(service: NsdServiceInfo) {
            val host = addresses(service).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: return
            val path = service.attributes[Protocol.MDNS_TXT_PATH]?.decodeToString()
            val id = service.attributes[Protocol.MDNS_TXT_ID]?.decodeToString()
            if (synchronized(this) { !closed }) onAnswer(DesktopAnswer(service.serviceName, host, service.port, path, id))
        }

        @Suppress("DEPRECATION")
        private fun addresses(service: NsdServiceInfo) =
            if (Build.VERSION.SDK_INT >=
                34
            ) {
                service.hostAddresses
            } else {
                listOfNotNull(service.host)
            }

        private inner class UpdateCallback : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = answer(serviceInfo)

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

            override fun onServiceLost() = Unit

            override fun onServiceInfoCallbackUnregistered() = Unit
        }

        private inner class ResolveCallback : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                answer(serviceInfo)
                done()
            }

            override fun onResolveFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) = done()

            private fun done() {
                synchronized(this@Browse) {
                    resolving = false
                    resolveNext()
                }
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) = Unit

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) = Unit

        override fun onStopDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) = Unit
    }

    private companion object {
        // NsdManager takes the type without the `.local.` domain.
        val SERVICE_TYPE = Protocol.MDNS_SERVICE_TYPE.removeSuffix(".local.")
    }
}
