import Foundation

@testable import AppMonitor

/// Records envelopes as the running monitor would buffer them; `options` nil plays "not started".
final class RecordingSink: CaptureSink, @unchecked Sendable {
    private let lock = NSLock()
    private var current: AppMonitorOptions?
    private var envelopes: [Appmonitor_V1_Envelope] = []

    init(_ options: AppMonitorOptions? = AppMonitorOptions()) { current = options }

    var options: AppMonitorOptions? {
        get { lock.withLock { current } }
        set { lock.withLock { current = newValue } }
    }

    func send(_ envelope: Appmonitor_V1_Envelope) {
        lock.withLock { envelopes.append(envelope) }
    }

    var all: [Appmonitor_V1_Envelope] { lock.withLock { envelopes } }

    /// The `RequestStarted` whose URL is `url`, and the event that ended it, waited for up to 5 s.
    func exchange(_ url: URL) async throws -> (Appmonitor_V1_RequestStarted, Appmonitor_V1_Envelope) {
        try await exchange(url.absoluteString)
    }

    func exchange(_ url: String) async throws -> (Appmonitor_V1_RequestStarted, Appmonitor_V1_Envelope) {
        for _ in 0..<100 {
            let events = all
            if let started = try events.filter({ $0.type == "RequestStarted" })
                .map({ try Appmonitor_V1_RequestStarted(serializedBytes: $0.payload) })
                .first(where: { $0.url == url }),
                let end = try events.first(where: {
                    try $0.type != "RequestStarted" && Self.requestID(of: $0) == started.requestID
                })
            {
                return (started, end)
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw CaptureTestError.noExchange(url)
    }

    private static func requestID(of envelope: Appmonitor_V1_Envelope) throws -> String {
        switch envelope.type {
        case "ResponseReceived": try Appmonitor_V1_ResponseReceived(serializedBytes: envelope.payload).requestID
        case "RequestFailed": try Appmonitor_V1_RequestFailed(serializedBytes: envelope.payload).requestID
        default: ""
        }
    }
}

enum CaptureTestError: Error {
    case noExchange(String)
    case wrongEvent(String)
}
