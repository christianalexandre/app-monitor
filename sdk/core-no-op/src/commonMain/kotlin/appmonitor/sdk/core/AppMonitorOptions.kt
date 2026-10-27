package appmonitor.sdk.core

import kotlin.jvm.JvmOverloads

/** Same as the real SDK's options (ADR-0017); [AppMonitor] ignores them in this artifact. */
public class AppMonitorOptions
    @JvmOverloads
    constructor(
        public val enabled: Boolean = true,
        public val host: String? = null,
        public val port: Int = DEFAULT_PORT,
        public val appName: String? = null,
        public val maskedHeaders: Set<String> = emptySet(),
        public val maskedQueryParameters: Set<String> = emptySet(),
        public val maxBodyBytes: Int = MAX_BODY_BYTES,
        public val masking: Boolean = true,
    ) {
        init {
            require(port in 1..65535) { "port must be in 1..65535, was $port" }
            require(maxBodyBytes in 0..MAX_BODY_BYTES) {
                "maxBodyBytes must be in 0..$MAX_BODY_BYTES, was $maxBodyBytes"
            }
        }

        // ADR-0072: equal to Protocol's, checked by OptionsDefaultsTest.
        private companion object {
            const val DEFAULT_PORT = 47474
            const val MAX_BODY_BYTES = 1 shl 20
        }
    }
