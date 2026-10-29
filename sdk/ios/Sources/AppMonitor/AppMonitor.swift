import Foundation
import os

/// Streams the app's traffic to the desktop; call `start(_:)` in `App.init` or
/// `application(_:didFinishLaunchingWithOptions:)` (ADR-0035), under `#if DEBUG`: release builds exclude the SDK (ADR-0155).
public enum AppMonitor {
    /// Starts the monitor; ignored while it runs or when `AppMonitorOptions.enabled` is false. Any thread.
    public static func start(_ options: AppMonitorOptions = AppMonitorOptions()) {
        options.check()
        if options.enabled {
            _ = URLSessionCapture.install()
            if options.captureConnections { _ = ConnectionCapture.install() }
        }
        monitor.start(options) { options in
            let log: @Sendable (String) -> Void = { Logger(subsystem: "AppMonitor", category: "sdk").info("\($0)") }
            return MonitorClient(
                hello: { appleHello() },
                discover: discovery(options, mdns: BonjourBrowser(), onSimulator: isSimulator, log: log),
                log: log
            )
        }
    }

    /// Stops the monitor and drops what it had not sent; `start(_:)` runs a new one. Any thread.
    public static func stop() {
        monitor.stop()
    }
}

/// How `AppMonitor` runs (ADR-0035 B). `host` set: the only desktop tried, on `port`; nil: discovered
/// over Bonjour, then the Mac's loopback on the simulator. `enabled` false: `start(_:)` does nothing.
///
/// Capture (ADR-0036): `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` are always
/// masked; `maskedHeaders` and `maskedQueryParameters` add names, compared case-insensitively. `masking`
/// false sends every value in clear. Each body is captured up to `maxBodyBytes`, at most 1 MiB.
/// `captureConnections` false: the sockets `URLSession` does not open are not reported (ADR-0100).
public struct AppMonitorOptions: Sendable {
    public var enabled: Bool
    public var host: String?
    public var port: Int
    public var appName: String?
    public var maskedHeaders: Set<String>
    public var maskedQueryParameters: Set<String>
    public var maxBodyBytes: Int
    public var masking: Bool
    public var captureConnections: Bool

    /// Stops the app when `port` is outside 1...65535 or `maxBodyBytes` outside 0...1 MiB.
    public init(
        enabled: Bool = true,
        host: String? = nil,
        port: Int = 47474,
        appName: String? = nil,
        maskedHeaders: Set<String> = [],
        maskedQueryParameters: Set<String> = [],
        maxBodyBytes: Int = 1_048_576,
        masking: Bool = true,
        captureConnections: Bool = true
    ) {
        self.enabled = enabled
        self.host = host
        self.port = port
        self.appName = appName
        self.maskedHeaders = maskedHeaders
        self.maskedQueryParameters = maskedQueryParameters
        self.maxBodyBytes = maxBodyBytes
        self.masking = masking
        self.captureConnections = captureConnections
        check()
    }

    /// Run again by `start(_:)`: a `var` member may have changed since `init`.
    func check() {
        precondition((1...65535).contains(port), "port must be in 1...65535, was \(port)")
        precondition(
            (0...ProtocolConstants.maxBodyBytes).contains(maxBodyBytes),
            "maxBodyBytes must be in 0...\(ProtocolConstants.maxBodyBytes), was \(maxBodyBytes)"
        )
    }
}

/// The `start` / `stop` of SDD 06 around one `MonitorClient` at a time: idle until started, a second
/// `start` ignored while a client runs, a new client after `stop`.
final class MonitorHolder: @unchecked Sendable {
    private let lock = NSLock()
    private var current: (options: AppMonitorOptions, client: MonitorClient)?

    var client: MonitorClient? { lock.withLock { current?.client } }

    /// The options of the running client; nil when idle or stopped.
    var options: AppMonitorOptions? { lock.withLock { current?.options } }

    func start(_ options: AppMonitorOptions, newClient: (AppMonitorOptions) -> MonitorClient) {
        lock.withLock {
            guard options.enabled, current == nil else { return }
            let client = newClient(options)
            client.start()
            current = (options, client)
        }
    }

    func stop() {
        let stopping = lock.withLock {
            defer { current = nil }
            return current
        }
        stopping?.client.stop()
    }
}

/// The process-wide monitor behind `AppMonitor`.
let monitor = MonitorHolder()
