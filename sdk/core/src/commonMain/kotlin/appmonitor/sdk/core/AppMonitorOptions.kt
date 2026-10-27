package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import kotlin.jvm.JvmOverloads

/**
 * How [AppMonitor] runs (ADR-0017). [host] set: the only desktop tried, on [port]; null: discovered
 * over mDNS, then `10.0.2.2` on the emulator. [enabled] false: `start()` does nothing.
 *
 * Capture (ADR-0018): `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` are always
 * masked; [maskedHeaders] and [maskedQueryParameters] add names, compared case-insensitively. [masking]
 * false sends every value in clear (ADR-0034). Each body is captured up to [maxBodyBytes], at most
 * [Protocol.MAX_BODY_BYTES] (ADR-0025).
 */
public class AppMonitorOptions
    @JvmOverloads
    constructor(
        public val enabled: Boolean = true,
        public val host: String? = null,
        public val port: Int = Protocol.DEFAULT_PORT,
        public val appName: String? = null,
        public val maskedHeaders: Set<String> = emptySet(),
        public val maskedQueryParameters: Set<String> = emptySet(),
        public val maxBodyBytes: Int = Protocol.MAX_BODY_BYTES,
        public val masking: Boolean = true,
    ) {
        init {
            require(port in 1..65535) { "port must be in 1..65535, was $port" }
            require(maxBodyBytes in 0..Protocol.MAX_BODY_BYTES) {
                "maxBodyBytes must be in 0..${Protocol.MAX_BODY_BYTES}, was $maxBodyBytes"
            }
        }
    }
