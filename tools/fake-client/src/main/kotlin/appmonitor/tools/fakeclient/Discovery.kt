package appmonitor.tools.fakeclient

import appmonitor.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Browses [Protocol.MDNS_SERVICE_TYPE] on every address in [addresses] and returns the first
 * desktop resolved, or `null` after [timeoutMs]. [serviceName] restricts the match.
 */
internal suspend fun discover(
    timeoutMs: Long,
    serviceName: String? = null,
    addresses: () -> List<Inet4Address> = ::lanAddresses,
    browse: (Inet4Address, ServiceListener) -> AutoCloseable = ::browse,
): Target? {
    val found = CompletableDeferred<Target>()
    val listener =
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) = event.dns.requestServiceInfo(event.type, event.name)

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                if (serviceName != null && event.name != serviceName) return
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                // Fires again when the TXT record arrives after SRV/A; path falls back to the default.
                found.complete(Target(host, info.port, info.getPropertyString("path") ?: Protocol.WEBSOCKET_PATH))
            }
        }
    val browsers = mutableListOf<AutoCloseable>()
    try {
        withContext(Dispatchers.IO) { addresses().forEach { browsers += browse(it, listener) } }
        return withTimeoutOrNull(timeoutMs) { found.await() }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { browsers.forEach { runCatching { it.close() } } }
    }
}

// A host name of its own: sharing the desktop's makes the two JmDNS instances conflict.
private fun browse(
    address: Inet4Address,
    listener: ServiceListener,
): JmDNS {
    val jmdns = JmDNS.create(address, "appmonitor-fake-client")
    try {
        jmdns.addServiceListener(Protocol.MDNS_SERVICE_TYPE, listener)
    } catch (e: Exception) {
        runCatching { jmdns.close() }
        throw e
    }
    return jmdns
}

private fun lanAddresses(): List<Inet4Address> =
    NetworkInterface
        .networkInterfaces()
        .toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
