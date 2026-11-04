package appmonitor.desktop.server

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The IPv4 addresses a LAN device can reach: interfaces up, multicast-capable, not loopback, virtual or point-to-point
 * (a VPN's `utun`). The desktop advertises and the fake client browses on them; both must pick the same (ADR-0164).
 */
object LanAddresses {
    fun current(): List<Inet4Address> =
        NetworkInterface
            .networkInterfaces()
            .toList()
            .filter { isLan(it.flags()) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()

    internal fun isLan(flags: InterfaceFlags): Boolean =
        flags.up && flags.multicast && !flags.loopback && !flags.virtual && !flags.pointToPoint

    private fun NetworkInterface.flags() =
        InterfaceFlags(
            up = isUp,
            loopback = isLoopback,
            virtual = isVirtual,
            pointToPoint = isPointToPoint,
            multicast = supportsMulticast(),
        )
}

internal data class InterfaceFlags(
    val up: Boolean,
    val loopback: Boolean,
    val virtual: Boolean,
    val pointToPoint: Boolean,
    val multicast: Boolean,
)
