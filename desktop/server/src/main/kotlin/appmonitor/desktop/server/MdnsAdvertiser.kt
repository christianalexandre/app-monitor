package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** Publishes the server as [Protocol.MDNS_SERVICE_TYPE] on every multicast-capable LAN address. */
internal class MdnsAdvertiser private constructor(
    private val instances: List<JmDNS>,
) : AutoCloseable {
    val addresses: List<InetAddress> get() = instances.map { it.inetAddress }

    override fun close() {
        instances.forEach { it.close() }
    }

    companion object {
        fun start(
            serviceName: String,
            port: Int,
            addresses: List<InetAddress> = lanAddresses(),
        ): MdnsAdvertiser {
            val instances =
                addresses.map { address ->
                    JmDNS.create(address).apply { registerService(serviceInfo(serviceName, port)) }
                }
            return MdnsAdvertiser(instances)
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
