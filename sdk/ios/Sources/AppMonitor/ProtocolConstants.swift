/// Values both ends must agree on before decoding an `Appmonitor_V1_Envelope`;
/// mirrors `appmonitor.protocol.Protocol` in Kotlin.
enum ProtocolConstants {
    static let version: UInt32 = 1
    static let channelCore = "core"
    static let channelNetwork = "network"
    static let defaultPort: UInt16 = 47474
    static let websocketPath = "/v1/ws"
    static let maxBodyBytes = 1 << 20
    static let maxFrameBytes = maxBodyBytes + (256 << 10)
}
