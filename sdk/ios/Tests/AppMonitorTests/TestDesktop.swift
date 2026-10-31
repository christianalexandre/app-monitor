import Foundation
import Network

@testable import AppMonitor

/// In-process desktop on Network.framework: answers `Hello` per `mode` and records what arrives.
final class TestDesktop: @unchecked Sendable {
    enum Mode: Sendable {
        /// `HelloAck` with these channels.
        case ack(channels: [String])
        /// Never answers `Hello`.
        case silent
        /// Closes 1008 after `Hello`.
        case reject
        /// `HelloAck`, then never answers a ping.
        case ackWithoutPongs
        /// Accepts TCP and never completes the WebSocket upgrade.
        case noUpgrade
    }

    private let queue = DispatchQueue(label: "test.desktop")
    private let mode: Mode
    private let desktopID: String
    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private var received: [Appmonitor_V1_Envelope] = []
    private var closeCodes: [NWProtocolWebSocket.CloseCode] = []
    private var accepted = 0

    private(set) var port: UInt16 = 0

    init(
        mode: Mode = .ack(channels: [ProtocolConstants.channelCore, ProtocolConstants.channelNetwork]),
        desktopID: String = ""
    ) {
        self.mode = mode
        self.desktopID = desktopID
    }

    var endpoint: Endpoint { Endpoint(host: "127.0.0.1", port: port) }

    var envelopes: [Appmonitor_V1_Envelope] { queue.sync { received } }

    /// What the SDK captured: neither `Hello` nor its own `DeliveryReport` (ADR-0166).
    var events: [Appmonitor_V1_Envelope] { envelopes.filter { $0.type != "Hello" && $0.type != "DeliveryReport" } }

    var reports: [Appmonitor_V1_DeliveryReport] {
        envelopes.filter { $0.type == "DeliveryReport" }.compactMap {
            try? Appmonitor_V1_DeliveryReport(serializedBytes: $0.payload)
        }
    }

    var closes: [NWProtocolWebSocket.CloseCode] { queue.sync { closeCodes } }

    var connectionCount: Int { queue.sync { accepted } }

    /// Starts listening on `port`, or on a free one when 0.
    func start(port: UInt16 = 0) async throws {
        let parameters: NWParameters
        if case .noUpgrade = mode {
            parameters = .tcp
        } else {
            let webSocket = NWProtocolWebSocket.Options()
            webSocket.autoReplyPing = !isWithoutPongs
            parameters = .tcp
            parameters.defaultProtocolStack.applicationProtocols.insert(webSocket, at: 0)
        }
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        let ready: UInt16 = try await withCheckedThrowingContinuation { continuation in
            let once = Once()
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready: once.run { continuation.resume(returning: listener.port?.rawValue ?? 0) }
                case .failed(let error): once.run { continuation.resume(throwing: error) }
                default: break
                }
            }
            listener.start(queue: queue)
        }
        self.port = ready
    }

    /// Stops listening and drops every connection, as a desktop that quit.
    func stop() {
        queue.sync {
            listener?.cancel()
            listener = nil
            for connection in connections { connection.forceCancel() }
            connections = []
        }
    }

    /// Sends `envelope` to every device connected now, as the desktop pushes `MockRules`.
    func push(_ envelope: Appmonitor_V1_Envelope) {
        let bytes = try! envelope.serializedBytes() as Data
        queue.async {
            for connection in self.connections { self.send(connection, bytes, opcode: .binary) }
        }
    }

    private var isWithoutPongs: Bool {
        if case .ackWithoutPongs = mode { return true }
        return false
    }

    private func accept(_ connection: NWConnection) {
        accepted += 1
        connections.append(connection)
        connection.start(queue: queue)
        if case .noUpgrade = mode { return }
        receive(connection)
    }

    private func receive(_ connection: NWConnection) {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard let self, error == nil else { return }
            let metadata =
                context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
            if let metadata, metadata.opcode == .close {
                self.closeCodes.append(metadata.closeCode)
                return
            }
            if metadata?.opcode == .binary, let data, let envelope = try? Appmonitor_V1_Envelope(serializedBytes: data)
            {
                self.received.append(envelope)
                if envelope.type == "Hello" { self.answerHello(connection) }
            }
            self.receive(connection)
        }
    }

    private func answerHello(_ connection: NWConnection) {
        switch mode {
        case .ack(let channels): sendAck(connection, channels: channels)
        case .ackWithoutPongs: sendAck(connection, channels: [ProtocolConstants.channelCore])
        case .reject: send(connection, nil, opcode: .close, code: .protocolCode(.policyViolation))
        case .silent, .noUpgrade: break
        }
    }

    private func sendAck(_ connection: NWConnection, channels: [String]) {
        var ack = Appmonitor_V1_HelloAck()
        ack.protocolVersion = ProtocolConstants.version
        ack.sessionID = UUID().uuidString
        ack.channels = channels
        ack.desktopID = desktopID
        var envelope = Appmonitor_V1_Envelope()
        envelope.channel = ProtocolConstants.channelCore
        envelope.type = "HelloAck"
        envelope.payload = try! ack.serializedBytes()
        send(connection, try! envelope.serializedBytes(), opcode: .binary)
    }

    private func send(
        _ connection: NWConnection,
        _ data: Data?,
        opcode: NWProtocolWebSocket.Opcode,
        code: NWProtocolWebSocket.CloseCode? = nil
    ) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: opcode)
        if let code { metadata.closeCode = code }
        connection.send(
            content: data,
            contentContext: NWConnection.ContentContext(identifier: "test", metadata: [metadata]),
            isComplete: true,
            completion: .idempotent
        )
    }
}

/// Runs a closure at most once, from any thread.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func run(_ body: () -> Void) {
        let first = lock.withLock {
            defer { done = true }
            return !done
        }
        if first { body() }
    }
}

/// Polls `condition` every 10 ms until it holds or `timeout` seconds pass.
func eventually(timeout: Double = 5, _ condition: () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if condition() { return true }
        try? await Task.sleep(nanoseconds: 10_000_000)
    }
    return condition()
}
