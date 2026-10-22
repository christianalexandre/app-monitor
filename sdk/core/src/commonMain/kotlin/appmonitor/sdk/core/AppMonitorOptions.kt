package appmonitor.sdk.core

import appmonitor.protocol.Protocol

/**
 * How [AppMonitor] runs (ADR-0017). [host] set: the only desktop tried, on [port]; null: discovered
 * over mDNS, then `10.0.2.2` on the emulator. [enabled] false: `start()` does nothing.
 */
public class AppMonitorOptions
    @JvmOverloads
    constructor(
        public val enabled: Boolean = true,
        public val host: String? = null,
        public val port: Int = Protocol.DEFAULT_PORT,
        public val appName: String? = null,
    ) {
        init {
            require(port in 1..65535) { "port must be in 1..65535, was $port" }
        }
    }
