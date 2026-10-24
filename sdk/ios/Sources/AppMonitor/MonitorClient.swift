#if DEBUG
import Foundation
import Network
import SwiftProtobuf
import os

/// Where a desktop listens.
struct Endpoint: Equatable, Sendable {
    var host: String
    var port: UInt16 = ProtocolConstants.defaultPort
    var path: String = ProtocolConstants.websocketPath

    var url: URL? {
        let bracketed = host.contains(":") ? "[\(host)]" : host
        return URL(string: "ws://\(bracketed):\(port)\(path)")
    }
}

/// The states of SDD 04 and SDD 06; `idle` until `MonitorClient.start()`.
enum ClientState: Sendable {
    case idle, discovering, connecting, handshaking, connected, backingOff, stopped
}

/// Bounds of SDD 06, in seconds; tests shorten them.
struct ClientConfig: Sendable {
    var discoveryTimeout: Double = 5
    var connectTimeout: Double = 10
    var pingInterval: Double = 15
    var pongTimeout: Double = 30
    var closeTimeout: Double = 1
    var backoffInitial: Double = 1
    var backoffMax: Double = 30
    var bufferCount: Int = EventBuffer.maxCount
    var bufferBytes: Int = EventBuffer.maxBytes
}

/// One round of discovering: runs on the client's queue, calls `found` at most once, and stops
/// looking when the returned closure is called.
typealias Discover = @Sendable (DispatchQueue, @escaping @Sendable (Endpoint?) -> Void) -> @Sendable () -> Void

/// A manual host: the endpoint at once, nothing else tried (SDD 06).
func manualDiscovery(_ endpoint: Endpoint) -> Discover {
    { queue, found in
        queue.async { found(endpoint) }
        return {}
    }
}

/// One connection to the desktop as the state machine of SDD 06 (ADR-0040). Single use: `start()`
/// once, `stop()` once; `send(_:)` is safe from any thread and never blocks beyond the buffer's lock.
final class MonitorClient: @unchecked Sendable {
    // ADR-0040: every member below `buffer` is confined to `queue`.
    private let queue = DispatchQueue(label: "appmonitor.client")
    private let config: ClientConfig
    private let makeHello: @Sendable () -> Appmonitor_V1_Hello
    private let discover: Discover
    private let log: @Sendable (String) -> Void
    private let buffer: EventBuffer
    private let oversized = LockedCount()

    private var current: ClientState = .idle
    private var started = false
    private var stopping = false
    private var released = false
    private var joiners: [CheckedContinuation<Void, Never>] = []
    private var backoff: Backoff
    private var hello: Appmonitor_V1_Hello?
    private var connectFailureLogged = false
    private var round = 0
    private var cancelDiscovery: (@Sendable () -> Void)?
    private var connection: NWConnection?
    private var accepted: Set<String> = []
    private var sending = false
    private var discoveryTimer: DispatchSourceTimer?
    private var connectTimer: DispatchSourceTimer?
    private var pingTimer: DispatchSourceTimer?
    private var pongTimer: DispatchSourceTimer?
    private var backoffTimer: DispatchSourceTimer?
    private var closeTimer: DispatchSourceTimer?
    private var stateObservers: [@Sendable (ClientState) -> Void] = []
    private var lastDelay: Double?

    init(
        hello: @escaping @Sendable () -> Appmonitor_V1_Hello,
        discover: @escaping Discover,
        config: ClientConfig = ClientConfig(),
        log: @escaping @Sendable (String) -> Void = { Logger(subsystem: "AppMonitor", category: "sdk").info("\($0)") },
        random: @escaping @Sendable (ClosedRange<Double>) -> Double = { Double.random(in: $0) }
    ) {
        self.makeHello = hello
        self.discover = discover
        self.config = config
        self.log = log
        self.buffer = EventBuffer(maxCount: config.bufferCount, maxBytes: config.bufferBytes)
        self.backoff = Backoff(initial: config.backoffInitial, max: config.backoffMax, random: random)
    }

    var state: ClientState { queue.sync { current } }

    /// Envelopes dropped by the buffer or for their size (DEBT-0023).
    var dropped: Int { buffer.dropped + oversized.value }

    /// The delay of the latest backing off, for tests.
    var lastBackoffDelay: Double? { queue.sync { lastDelay } }

    /// Calls `observer` on the client's queue with the current state, then with every change.
    func observe(_ observer: @escaping @Sendable (ClientState) -> Void) {
        queue.async {
            self.stateObservers.append(observer)
            observer(self.current)
        }
    }

    /// True once `stop()` released everything: no connection, browser round or timer left.
    var isReleased: Bool {
        queue.sync {
            released && connection == nil && cancelDiscovery == nil
                && [discoveryTimer, connectTimer, pingTimer, pongTimer, backoffTimer, closeTimer].allSatisfy {
                    $0 == nil
                }
        }
    }

    func start() {
        queue.async {
            guard !self.started else { return }
            self.started = true
            self.discoverRound()
        }
    }

    /// Buffers `envelope` for the desktop; dropped when stopped or larger than a frame (ADR-0022).
    func send(_ envelope: Appmonitor_V1_Envelope) {
        guard let bytes: Data = try? envelope.serializedBytes() else { return }
        guard bytes.count <= ProtocolConstants.maxFrameBytes else {
            oversized.increment()
            return
        }
        buffer.offer(BufferedFrame(channel: envelope.channel, bytes: bytes))
    }

    /// Returns at once; the release runs on the client's queue, bounded by `ClientConfig.closeTimeout`.
    func stop() {
        buffer.close()
        queue.async { self.shutdown() }
    }

    /// Waits for the release started by `stop()`.
    func join() async {
        await withCheckedContinuation { continuation in
            queue.async {
                if self.released { continuation.resume() } else { self.joiners.append(continuation) }
            }
        }
    }

    // MARK: - States, all on `queue`

    private func setState(_ state: ClientState) {
        dispatchPrecondition(condition: .onQueue(queue))
        current = state
        for observer in stateObservers { observer(state) }
    }

    private func discoverRound() {
        guard !stopping else { return }
        setState(.discovering)
        round += 1
        let thisRound = round
        discoveryTimer = timer(after: config.discoveryTimeout) { [weak self] in
            guard let self, self.round == thisRound, self.current == .discovering else { return }
            self.endDiscovery()
            self.backOff()
        }
        cancelDiscovery = discover(queue) { [weak self] endpoint in
            guard let self else { return }
            self.queue.async {
                guard !self.stopping, self.round == thisRound, self.current == .discovering else { return }
                self.endDiscovery()
                if let endpoint { self.connect(endpoint) } else { self.backOff() }
            }
        }
    }

    private func endDiscovery() {
        discoveryTimer?.cancel()
        discoveryTimer = nil
        cancelDiscovery?()
        cancelDiscovery = nil
    }

    private func connect(_ endpoint: Endpoint) {
        setState(.connecting)
        guard let url = endpoint.url else {
            logConnectFailure(endpoint, reason: "not a valid host")
            backOff()
            return
        }
        let webSocket = NWProtocolWebSocket.Options()
        webSocket.autoReplyPing = true
        webSocket.maximumMessageSize = ProtocolConstants.maxFrameBytes
        let parameters = NWParameters.tcp
        parameters.defaultProtocolStack.applicationProtocols.insert(webSocket, at: 0)
        let connection = NWConnection(to: .url(url), using: parameters)
        self.connection = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            self.connectionChanged(connection, endpoint: endpoint, to: state)
        }
        connectTimer = timer(after: config.connectTimeout) { [weak self, weak connection] in
            guard let self, let connection, connection === self.connection, self.current == .connecting else { return }
            self.logConnectFailure(endpoint, reason: "no WebSocket upgrade within \(self.config.connectTimeout) s")
            self.lose(.lost)
        }
        connection.start(queue: queue)
    }

    private func connectionChanged(_ connection: NWConnection, endpoint: Endpoint, to state: NWConnection.State) {
        guard !stopping, connection === self.connection else { return }
        switch state {
        case .ready where current == .connecting:
            connectTimer?.cancel()
            connectTimer = nil
            handshake(connection)
        case .failed(let error):
            if current == .connecting { logConnectFailure(endpoint, reason: "\(error)") }
            lose(.lost)
        case .waiting(.posix(.ECONNREFUSED)) where current == .connecting:
            // ADR-0041: nothing listens there; other waits may be the local network prompt (ADR-0040).
            logConnectFailure(endpoint, reason: "connection refused")
            lose(.lost)
        case .cancelled:
            lose(.lost)
        default:
            break
        }
    }

    private func handshake(_ connection: NWConnection) {
        setState(.handshaking)
        startPinger(connection)
        if hello == nil { hello = makeHello() }
        var envelope = Appmonitor_V1_Envelope()
        envelope.channel = ProtocolConstants.channelCore
        envelope.type = "Hello"
        envelope.payload = (try? hello?.serializedBytes()) ?? Data()
        envelope.timestampMs = Int64(Date().timeIntervalSince1970 * 1000)
        // A failed write is noticed by the receive or the state handler: a 1008 may already be queued.
        connection.send(
            content: try? envelope.serializedBytes() as Data,
            contentContext: Self.context(.binary),
            isComplete: true,
            completion: .contentProcessed { _ in }
        )
        receive(connection)
    }

    private func receive(_ connection: NWConnection) {
        connection.receiveMessage { [weak self, weak connection] data, context, _, error in
            guard let self, let connection, !self.stopping, connection === self.connection else { return }
            let metadata =
                context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
            if let metadata, metadata.opcode == .close {
                self.lose(metadata.closeCode == .protocolCode(.policyViolation) ? .rejected : .lost)
                return
            }
            if error != nil {
                self.lose(.lost)
                return
            }
            if metadata?.opcode == .ping || metadata?.opcode == .pong {
                self.receive(connection)
                return
            }
            if self.current == .handshaking {
                self.handleAck(data, on: connection)
            }
            if self.current == .connected { self.receive(connection) }
        }
    }

    private func handleAck(_ data: Data?, on connection: NWConnection) {
        guard let data,
            let envelope = try? Appmonitor_V1_Envelope(serializedBytes: data),
            envelope.channel == ProtocolConstants.channelCore, envelope.type == "HelloAck",
            let ack = try? Appmonitor_V1_HelloAck(serializedBytes: envelope.payload)
        else {
            connection.send(
                content: nil,
                contentContext: Self.context(.close, code: .protocolCode(.protocolError)),
                isComplete: true,
                completion: .contentProcessed { _ in }
            )
            lose(.lost)
            return
        }
        backoff.reset()
        accepted = Set(ack.channels)
        setState(.connected)
        pump()
    }

    /// Writes the next accepted frame; one write in flight, the next from its completion (SDD 06).
    private func pump() {
        guard !stopping, current == .connected, !sending, let connection else { return }
        while let frame = buffer.poll(onAvailable: { [weak self] in self?.queue.async { self?.pump() } }) {
            guard accepted.contains(frame.channel) else { continue }
            sending = true
            connection.send(
                content: frame.bytes,
                contentContext: Self.context(.binary),
                isComplete: true,
                completion: .contentProcessed { [weak self, weak connection] error in
                    guard let self, let connection, connection === self.connection else { return }
                    self.sending = false
                    if error != nil { self.lose(.lost) } else { self.pump() }
                }
            )
            return
        }
    }

    private func startPinger(_ connection: NWConnection) {
        armPongDeadline(connection)
        pingTimer = timer(after: config.pingInterval, repeating: config.pingInterval) { [weak self, weak connection] in
            guard let self, let connection, connection === self.connection else { return }
            let metadata = NWProtocolWebSocket.Metadata(opcode: .ping)
            metadata.setPongHandler(self.queue) { [weak self, weak connection] error in
                guard let self, let connection, error == nil, connection === self.connection else { return }
                self.armPongDeadline(connection)
            }
            connection.send(
                content: nil,
                contentContext: NWConnection.ContentContext(identifier: "ping", metadata: [metadata]),
                isComplete: true,
                completion: .contentProcessed { _ in }
            )
        }
    }

    private func armPongDeadline(_ connection: NWConnection) {
        pongTimer?.cancel()
        pongTimer = timer(after: config.pongTimeout) { [weak self, weak connection] in
            guard let self, let connection, !self.stopping, connection === self.connection else { return }
            self.lose(.lost)
        }
    }

    private enum Outcome { case lost, rejected }

    /// Exit from connecting, handshaking or connected to backing off.
    private func lose(_ outcome: Outcome) {
        guard !stopping else { return }
        releaseConnection()?.cancel()
        if outcome == .rejected { backoff.exhaust() }
        backOff()
    }

    /// Cancels the connection's timers and hands it back, no longer current.
    private func releaseConnection() -> NWConnection? {
        for timer in [connectTimer, pingTimer, pongTimer] { timer?.cancel() }
        connectTimer = nil
        pingTimer = nil
        pongTimer = nil
        sending = false
        defer { connection = nil }
        return connection
    }

    private func backOff() {
        guard !stopping else { return }
        setState(.backingOff)
        let delay = backoff.nextDelay()
        lastDelay = delay
        backoffTimer = timer(after: delay) { [weak self] in
            guard let self, !self.stopping else { return }
            self.backoffTimer = nil
            self.discoverRound()
        }
    }

    private func logConnectFailure(_ endpoint: Endpoint, reason: String) {
        guard !connectFailureLogged else { return }
        connectFailureLogged = true
        log(
            "cannot reach the desktop at \(endpoint.host):\(endpoint.port) (\(reason)); retrying with backoff. "
                + "On a device check NSLocalNetworkUsageDescription and NSBonjourServices in Info.plist"
        )
    }

    // MARK: - stop()

    private func shutdown() {
        guard !stopping else { return }
        stopping = true
        started = true
        endDiscovery()
        backoffTimer?.cancel()
        backoffTimer = nil
        let inSession = current == .handshaking || current == .connected
        guard let connection = releaseConnection() else { return finish() }
        guard inSession else {
            connection.cancel()
            return finish()
        }
        closeTimer = timer(after: config.closeTimeout) { [weak self] in
            connection.forceCancel()
            self?.finish()
        }
        connection.send(
            content: nil,
            contentContext: Self.context(.close, code: .protocolCode(.normalClosure)),
            isComplete: true,
            completion: .contentProcessed { [weak self] _ in
                connection.cancel()
                self?.finish()
            }
        )
    }

    private func finish() {
        guard !released else { return }
        released = true
        closeTimer?.cancel()
        closeTimer = nil
        buffer.close()
        setState(.stopped)
        stateObservers = []
        let waiting = joiners
        joiners = []
        for joiner in waiting { joiner.resume() }
    }

    // MARK: - Helpers

    private func timer(
        after seconds: Double,
        repeating interval: Double? = nil,
        _ handler: @escaping @Sendable () -> Void
    ) -> DispatchSourceTimer {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(
            deadline: .now() + seconds,
            repeating: interval.map { DispatchTimeInterval.nanoseconds(Int($0 * 1e9)) } ?? .never
        )
        timer.setEventHandler(handler: handler)
        timer.resume()
        return timer
    }

    private static func context(
        _ opcode: NWProtocolWebSocket.Opcode,
        code: NWProtocolWebSocket.CloseCode? = nil
    ) -> NWConnection.ContentContext {
        let metadata = NWProtocolWebSocket.Metadata(opcode: opcode)
        if let code { metadata.closeCode = code }
        return NWConnection.ContentContext(identifier: "\(opcode)", metadata: [metadata])
    }
}

/// A counter safe from any thread.
private final class LockedCount: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int { lock.withLock { count } }

    func increment() { lock.withLock { count += 1 } }
}
#endif
