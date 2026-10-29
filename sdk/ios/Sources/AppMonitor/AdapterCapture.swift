import Foundation

/// Where adapter exchanges go: the running monitor, or a test double.
enum AdapterCapture {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var current: CaptureSink = monitor

    static var sink: CaptureSink {
        get { lock.withLock { current } }
        set { lock.withLock { current = newValue } }
    }
}

/// A message of an adapter call: its bytes, or `unread` when the adapter cannot serialize it.
package enum AdapterPayload: Sendable {
    case bytes(Data)
    case unread
}

/// One call seen by an adapter in another target of this package (ADR-0099): the events of SDD 07 with
/// `capture_layer = ADAPTER`, masked and cut at `maxBodyBytes` like `URLSession`'s. Ends once.
package final class AdapterExchange: @unchecked Sendable {
    private let id = UUID().uuidString.lowercased()
    private let sink: CaptureSink
    private let masking: Masking
    private let limit: Int
    private let url: URL?
    private let lock = NSLock()
    private var ended = false

    /// False while the monitor is idle: `start` would return nil.
    package static var capturing: Bool { AdapterCapture.sink.options != nil }

    /// Sends `RequestStarted`; nil, and nothing sent, while the monitor is idle.
    package static func start(
        method: String,
        url: String,
        headers: [(name: String, value: String)],
        body: AdapterPayload?
    ) -> AdapterExchange? {
        let sink = AdapterCapture.sink
        guard let options = sink.options else { return nil }
        let exchange = AdapterExchange(sink: sink, options: options, url: URL(string: url))
        emit(sink, "RequestStarted") {
            var event = Appmonitor_V1_RequestStarted()
            event.requestID = exchange.id
            event.method = method
            event.url = exchange.masking.url(url)
            event.headers = exchange.masking.headers(headers)
            event.captureLayer = .adapter
            if let body {
                let (bytes, truncated) = exchange.cut(body)
                event.body = bytes
                event.bodyTruncated = truncated
                if case .bytes(let data) = body { event.bodySize = Int64(data.count) }
            }
            return try event.serializedBytes()
        }
        return exchange
    }

    private init(sink: CaptureSink, options: AppMonitorOptions, url: URL?) {
        self.sink = sink
        masking = Masking(options)
        limit = options.maxBodyBytes
        self.url = url
    }

    /// A streamed message as a binary `WebSocketFrame`, `sent` by the app or received.
    package func frame(sent: Bool, _ payload: AdapterPayload) {
        emit(sink, "WebSocketFrame") {
            var event = Appmonitor_V1_WebSocketFrame()
            event.requestID = id
            event.direction = sent ? .sent : .received
            event.opcode = .binary
            let (bytes, truncated) = cut(payload)
            event.payload = bytes
            event.payloadTruncated = truncated
            if case .bytes(let data) = payload { event.payloadSize = Int64(data.count) }
            return try event.serializedBytes()
        }
    }

    package func responded(status: Int, headers: [(name: String, value: String)], body: AdapterPayload?) {
        guard end() else { return }
        emit(sink, "ResponseReceived") {
            var event = Appmonitor_V1_ResponseReceived()
            event.requestID = id
            event.statusCode = Int32(clamping: status)
            event.headers = masking.headers(headers, base: url)
            if let body {
                let (bytes, truncated) = cut(body)
                event.body = bytes
                event.bodyTruncated = truncated
                if case .bytes(let data) = body { event.bodySize = Int64(data.count) }
            }
            return try event.serializedBytes()
        }
    }

    package func failed(_ error: String) {
        guard end() else { return }
        emit(sink, "RequestFailed") {
            var event = Appmonitor_V1_RequestFailed()
            event.requestID = id
            event.error = error
            return try event.serializedBytes()
        }
    }

    /// True the first time only.
    private func end() -> Bool {
        lock.withLock {
            defer { ended = true }
            return !ended
        }
    }

    /// The bytes kept and whether some were left out; an unread message keeps none.
    private func cut(_ payload: AdapterPayload) -> (Data, Bool) {
        switch payload {
        case .bytes(let data): (data.prefix(limit), data.count > limit)
        case .unread: (Data(), true)
        }
    }
}
