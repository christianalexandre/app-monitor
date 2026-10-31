package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** Publishes the server as [Protocol.MDNS_SERVICE_TYPE] on every multicast-capable LAN address. */
internal class MdnsAdvertiser private constructor(
    private val registrations: List<Pair<InetAddress, AutoCloseable>>,
) : AutoCloseable {
    val addresses: List<InetAddress> get() = registrations.map { it.first }

    /** Closes every registration, then rethrows the first failure with the others suppressed. */
    override fun close() {
        var failure: Throwable? = null
        for ((_, registration) in registrations) {
            try {
                registration.close()
            } catch (e: Throwable) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        if (failure != null) throw failure
    }

    companion object {
        /** All or nothing: if one address fails, the ones already registered are closed and it rethrows. */
        fun start(
            serviceName: String,
            port: Int,
            desktopId: String? = null,
            addresses: List<InetAddress> = LanAddresses.current(),
            register: (InetAddress) -> AutoCloseable = { register(it, serviceName, port, desktopId) },
        ): MdnsAdvertiser {
            val registrations = mutableListOf<Pair<InetAddress, AutoCloseable>>()
            try {
                addresses.forEach { registrations += it to register(it) }
            } catch (e: Throwable) {
                registrations.forEach { (_, registration) -> runCatching { registration.close() } }
                throw e
            }
            return MdnsAdvertiser(registrations)
        }

        private fun register(
            address: InetAddress,
            serviceName: String,
            port: Int,
            desktopId: String?,
        ): JmDNS {
            val jmdns = JmDNS.create(address)
            try {
                jmdns.registerService(serviceInfo(serviceName, port, desktopId))
            } catch (e: Exception) {
                runCatching { jmdns.close() }
                throw e
            }
            return jmdns
        }

        /** TXT `v`, `path`, and `id` when there is one (ADR-0168). */
        internal fun serviceInfo(
            serviceName: String,
            port: Int,
            desktopId: String?,
        ): ServiceInfo =
            ServiceInfo.create(
                Protocol.MDNS_SERVICE_TYPE,
                serviceName,
                port,
                0,
                0,
                mapOf("v" to Protocol.VERSION.toString(), Protocol.MDNS_TXT_PATH to Protocol.WEBSOCKET_PATH) +
                    listOfNotNull(desktopId?.let { Protocol.MDNS_TXT_ID to it }),
            )
    }
}
