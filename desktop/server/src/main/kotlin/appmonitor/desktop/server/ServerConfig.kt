package appmonitor.desktop.server

import appmonitor.protocol.Protocol

/**
 * How [AppMonitorServer] listens and advertises itself over mDNS ([Protocol.MDNS_SERVICE_TYPE]).
 * @param port `0` binds an ephemeral port; [AppMonitorServer.start] returns the real one.
 * @param desktopId the id devices pin, in the TXT record and each `HelloAck`; null sends none (ADR-0168).
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = Protocol.DEFAULT_PORT,
    val advertise: Boolean = true,
    val serviceName: String = "AppMonitor",
    val desktopId: String? = null,
)
