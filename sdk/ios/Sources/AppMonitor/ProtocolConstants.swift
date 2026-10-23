/// Values both ends must agree on before decoding an `Appmonitor_V1_Envelope`;
/// mirrors `appmonitor.protocol.Protocol` in Kotlin.
enum ProtocolConstants {
    static let version: UInt32 = 1
    static let channelCore = "core"
    static let channelNetwork = "network"
}
