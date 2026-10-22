package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** Publishes the server as [Protocol.MDNS_SERVICE_TYPE] on every multicast-capable LAN address. */
internal class MdnsAdvertiser private constructor(
    private val registrations: List<Pair<InetAddress, AutoCloseable>>,
) : AutoCloseable {
    val addresses: List<InetAddress> get() = registrations.map { it.first }

    override fun close() {
        registrations.forEach { it.second.close() }
    }

    companion object {
        /** All or nothing: if one address fails, the ones already registered are closed and it rethrows. */
        fun start(
            serviceName: String,
            port: Int,
            addresses: List<InetAddress> = lanAddresses(),
            register: (InetAddress) -> AutoCloseable = { register(it, serviceName, port) },
        ): MdnsAdvertiser {
            val registrations = mutableListOf<Pair<InetAddress, AutoCloseable>>()
            try {
                addresses.forEach { registrations += it to register(it) }
            } catch (e: Exception) {
                registrations.forEach { (_, registration) -> runCatching { registration.close() } }
                throw e
            }
            return MdnsAdvertiser(registrations)
        }

        private fun register(
            address: InetAddress,
            serviceName: String,
            port: Int,
        ): JmDNS {
            val jmdns = JmDNS.create(address)
            try {
                jmdns.registerService(serviceInfo(serviceName, port))
            } catch (e: Exception) {
                runCatching { jmdns.close() }
                throw e
            }
            return jmdns
        }

        private fun serviceInfo(
            serviceName: String,
            port: Int,
        ): ServiceInfo =
            ServiceInfo.create(
                Protocol.MDNS_SERVICE_TYPE,
                serviceName,
                port,
                0,
                0,
                mapOf("v" to Protocol.VERSION.toString(), "path" to Protocol.WEBSOCKET_PATH),
            )

        fun lanAddresses(): List<InetAddress> =
            NetworkInterface
                .networkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
    }
}
