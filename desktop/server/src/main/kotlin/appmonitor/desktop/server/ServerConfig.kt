package appmonitor.desktop.server

import appmonitor.protocol.Protocol

/**
 * How [AppMonitorServer] listens and advertises itself.
 *
 * @param port `0` binds an ephemeral port; [AppMonitorServer.start] returns the real one.
 * @param advertise publish the server over mDNS as [Protocol.MDNS_SERVICE_TYPE].
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = Protocol.DEFAULT_PORT,
    val advertise: Boolean = true,
    val serviceName: String = "AppMonitor",
)
