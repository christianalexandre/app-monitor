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
}
