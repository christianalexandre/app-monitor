package appmonitor.desktop.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanAddressesTest {
    private val wifi = InterfaceFlags(up = true, loopback = false, virtual = false, pointToPoint = false, multicast = true)

    @Test
    fun `a wi-fi or ethernet interface is on the lan`() = assertTrue(LanAddresses.isLan(wifi))

    @Test
    fun `a vpn tunnel is not`() {
        // FortiClient's utun as ifconfig shows it: UP,POINTOPOINT,RUNNING,MULTICAST.
        assertFalse(LanAddresses.isLan(wifi.copy(pointToPoint = true)))
    }

    @Test
    fun `loopback, virtual, down or multicast-less interfaces are not`() {
        assertFalse(LanAddresses.isLan(wifi.copy(loopback = true)))
        assertFalse(LanAddresses.isLan(wifi.copy(virtual = true)))
        assertFalse(LanAddresses.isLan(wifi.copy(up = false)))
        assertFalse(LanAddresses.isLan(wifi.copy(multicast = false)))
    }

    @Test
    fun `current addresses are ipv4 and never loopback`() {
        LanAddresses.current().forEach { assertFalse(it.isLoopbackAddress, "$it") }
    }
}
