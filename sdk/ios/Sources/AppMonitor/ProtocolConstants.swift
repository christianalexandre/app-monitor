/// Values both ends must agree on before decoding an `Appmonitor_V1_Envelope`;
/// mirrors `appmonitor.protocol.Protocol` in Kotlin.
enum ProtocolConstants {
    static let version: UInt32 = 1
    static let channelCore = "core"
    static let channelNetwork = "network"
    static let channelInteraction = "interaction"
    static let defaultPort: UInt16 = 47474
    static let websocketPath = "/v1/ws"
    /// `MDNS_SERVICE_TYPE` without its `.local.` domain, as `NWBrowser` takes it.
    static let mdnsServiceType = "_appmonitor._tcp"
    static let mdnsTxtPath = "path"
    static let maxBodyBytes = 1 << 20
    static let maxFrameBytes = maxBodyBytes + (256 << 10)
    /// Set by `sdk/ktor` on iOS on what it captures; a task carrying it is not captured again (ADR-0064).
    static let captureHeader = "X-AppMonitor-Capture"
}
