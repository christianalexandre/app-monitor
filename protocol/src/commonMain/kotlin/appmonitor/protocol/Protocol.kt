package appmonitor.protocol

/** Values both ends must agree on before decoding an [appmonitor.v1.Envelope]. */
object Protocol {
    const val VERSION: Int = 1
    const val CHANNEL_CORE: String = "core"
    const val CHANNEL_NETWORK: String = "network"

    /** Port the desktop listens on unless configured otherwise (ADR-0009). */
    const val DEFAULT_PORT: Int = 47474

    /** WebSocket endpoint path on the desktop. */
    const val WEBSOCKET_PATH: String = "/v1/ws"

    /** DNS-SD service type the desktop advertises (ADR-0002). */
    const val MDNS_SERVICE_TYPE: String = "_appmonitor._tcp.local."

    /** Most bytes of one body a sender puts in an envelope (ADR-0018). */
    const val MAX_BODY_BYTES: Int = 1 shl 20

    /** Largest frame the desktop reads: one body plus URL, headers and envelope (ADR-0021). */
    const val MAX_FRAME_BYTES: Long = MAX_BODY_BYTES + (256L shl 10)
}
