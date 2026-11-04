package appmonitor.desktop.server

import appmonitor.protocol.Protocol

/**
 * How [AppMonitorServer] listens and advertises over mDNS. [port] `0` binds an ephemeral one, [AppMonitorServer.start]
 * returns it; [desktopId] is the id devices pin, in the TXT record and each `HelloAck`, none when null (ADR-0168).
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = Protocol.DEFAULT_PORT,
    val advertise: Boolean = true,
    val serviceName: String = "AppMonitor",
    val desktopId: String? = null,
)
