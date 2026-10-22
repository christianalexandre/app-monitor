package appmonitor.protocol

/** Values both ends must agree on before decoding an [appmonitor.v1.Envelope]. */
object Protocol {
    const val VERSION: Int = 1
    const val CHANNEL_CORE: String = "core"
    const val CHANNEL_NETWORK: String = "network"
}
