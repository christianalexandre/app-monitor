import Foundation
import Network

/// WebSocket echo on 127.0.0.1 for capture tests: echoes text and binary frames; the text `close`
/// is answered with a close frame, code 4000, reason `bye`.
final class TestEchoServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "test.echo")
    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private var frames: [Data] = []

    private(set) var port: UInt16 = 0

    /// Text and binary payloads as they arrived.
    var received: [Data] { queue.sync { frames } }

    func url(_ path: String) -> URL { URL(string: "ws://127.0.0.1:\(port)\(path)")! }

    /// `url(path)` as Foundation rewrites it, and so as it is captured.
    func httpURL(_ path: String) -> URL { URL(string: "http://127.0.0.1:\(port)\(path)")! }

    func start() async throws {
        let parameters = NWParameters.tcp
        parameters.defaultProtocolStack.applicationProtocols.insert(NWProtocolWebSocket.Options(), at: 0)
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            connections.append(connection)
            connection.start(queue: queue)
            receive(connection)
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let once = Once()
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready: once.run { continuation.resume() }
                case .failed(let error): once.run { continuation.resume(throwing: error) }
                default: break
                }
            }
            listener.start(queue: queue)
        }
        port = listener.port!.rawValue
    }

    func stop() {
        queue.sync {
            listener?.cancel()
            for connection in connections { connection.cancel() }
            connections = []
        }
    }

    private func receive(_ connection: NWConnection) {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard let self, error == nil,
                let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                    as? NWProtocolWebSocket.Metadata,
                metadata.opcode == .text || metadata.opcode == .binary
            else { return }
            let data = data ?? Data()
            frames.append(data)
            if metadata.opcode == .text, data == Data("close".utf8) {
                let close = NWProtocolWebSocket.Metadata(opcode: .close)
                close.closeCode = .privateCode(4000)
                send(connection, Data("bye".utf8), close)
                return
            }
            send(connection, data, NWProtocolWebSocket.Metadata(opcode: metadata.opcode))
            receive(connection)
        }
    }

    private func send(_ connection: NWConnection, _ data: Data, _ metadata: NWProtocolWebSocket.Metadata) {
        let context = NWConnection.ContentContext(identifier: "echo", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .idempotent)
    }
}
