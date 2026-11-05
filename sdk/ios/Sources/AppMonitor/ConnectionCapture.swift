internal import AppMonitorConnections
import Foundation
import os

/// Reports each IPv4 and IPv6 stream socket the app's own images connect (ADR-0100) as a `CONNECTION`
/// exchange (ADR-0103): `RequestStarted` at `connect`, then `ResponseReceived` with its byte counts or
/// `RequestFailed` at `close`. Rebinding stays installed after `stop()`; each `connect` reads `sink.options`.
enum ConnectionCapture {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var current: CaptureSink = monitor
    nonisolated(unsafe) private static var loopback = false
    nonisolated(unsafe) private static var names: [String: String] = [:]
    nonisolated(unsafe) private static var desktops: Set<String> = []
    nonisolated(unsafe) private static var open: [Int32: (id: String, sink: CaptureSink)] = [:]

    /// Resolved names kept for `connect`; the table starts over past this many addresses.
    private static let maxNames = 512

    static var sink: CaptureSink {
        get { lock.withLock { current } }
        set { lock.withLock { current = newValue } }
    }

    /// False in the app: its desktop on the simulator is the Mac's loopback (ADR-0100 C.1). Tests set it.
    static var reportsLoopback: Bool {
        get { lock.withLock { loopback } }
        set { lock.withLock { loopback = newValue } }
    }

    /// Marks `host:port` as a desktop: a socket to it, another SDK's in this app included, is not reported
    /// (ADR-0100 C.1). `host` is matched against the peer's address and the name it was resolved from.
    static func desktopFound(host: String, port: UInt16) {
        lock.withLock { _ = desktops.insert("\(host.lowercased()):\(port)") }
    }

    /// Rebinds once per process; false when a socket call cannot be found, and nothing is reported.
    static func install() -> Bool { installed }

    private static let installed: Bool = {
        let ok = appmonitor_connections_install(
            { fd, address, _, started, error in
                guard let address else { return false }
                return ConnectionCapture.connected(fd, address, started, error)
            },
            { fd, sent, received, error in ConnectionCapture.closing(fd, sent, received, error) },
            { node, result in
                guard let node else { return }
                ConnectionCapture.resolved(String(cString: node), result)
            }
        )
        if !ok { Logger(subsystem: "AppMonitor", category: "capture").error("connection capture is off (ADR-0100)") }
        return ok
    }()

    private static func connected(
        _ fd: Int32,
        _ address: UnsafePointer<sockaddr>,
        _ started: Int64,
        _ error: Int32
    ) -> Bool {
        let sink = self.sink
        guard sink.options?.captureConnections == true, let peer = Peer(address) else { return false }
        if peer.isLoopback && !reportsLoopback { return false }
        let host: String? = lock.withLock {
            let host = names[peer.address] ?? peer.address
            let desktop = [peer.address, host.lowercased()].contains { desktops.contains("\($0):\(peer.port)") }
            return desktop ? nil : host
        }
        guard let host else { return false }
        let id = UUID().uuidString.lowercased()
        emit(sink, "RequestStarted", at: started) {
            var event = Appmonitor_V1_RequestStarted()
            event.requestID = id
            event.method = "CONNECT"
            event.url = "tcp://\(host.contains(":") ? "[\(host)]" : host):\(peer.port)"
            event.captureLayer = .connection
            return try event.serializedBytes()
        }
        guard error == 0 || error == EINPROGRESS || error == EINTR else {
            fail(sink, id, error)
            return false
        }
        lock.withLock { open[fd] = (id, sink) }
        return true
    }

    private static func closing(_ fd: Int32, _ sent: Int64, _ received: Int64, _ error: Int32) {
        guard let (id, sink) = lock.withLock({ open.removeValue(forKey: fd) }) else { return }
        guard error == 0 else { return fail(sink, id, error) }
        emit(sink, "ResponseReceived") {
            var event = Appmonitor_V1_ResponseReceived()
            event.requestID = id
            event.bodySize = received
            event.sentSize = sent
            return try event.serializedBytes()
        }
    }

    private static func fail(_ sink: CaptureSink, _ id: String, _ error: Int32) {
        emit(sink, "RequestFailed") {
            var event = Appmonitor_V1_RequestFailed()
            event.requestID = id
            event.error = "\(NSPOSIXErrorDomain) \(error): \(String(cString: strerror(error)))"
            return try event.serializedBytes()
        }
    }

    private static func resolved(_ node: String, _ result: UnsafePointer<addrinfo>?) {
        var entry = result
        var addresses: [String] = []
        while let info = entry?.pointee {
            if let address = info.ai_addr, let peer = Peer(address), peer.address != node {
                addresses.append(peer.address)
            }
            entry = UnsafePointer(info.ai_next)
        }
        guard !addresses.isEmpty else { return }
        lock.withLock {
            if names.count + addresses.count > maxNames { names.removeAll() }
            for address in addresses { names[address] = node }
        }
    }
}

/// The numeric address and port of an IPv4 or IPv6 socket address; nil for any other family.
private struct Peer {
    let address: String
    let port: UInt16
    let isLoopback: Bool

    init?(_ address: UnsafePointer<sockaddr>) {
        var text = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        switch Int32(address.pointee.sa_family) {
        case AF_INET:
            var ipv4 = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
            guard inet_ntop(AF_INET, &ipv4.sin_addr, &text, socklen_t(text.count)) != nil else { return nil }
            port = UInt16(bigEndian: ipv4.sin_port)
            isLoopback = UInt32(bigEndian: ipv4.sin_addr.s_addr) >> 24 == 127
        case AF_INET6:
            var ipv6 = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee }
            guard inet_ntop(AF_INET6, &ipv6.sin6_addr, &text, socklen_t(text.count)) != nil else { return nil }
            port = UInt16(bigEndian: ipv6.sin6_port)
            let bytes = withUnsafeBytes(of: ipv6.sin6_addr) { Array($0) }
            let mapped = bytes[0..<10].allSatisfy { $0 == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
            isLoopback = bytes == [UInt8](repeating: 0, count: 15) + [1] || (mapped && bytes[12] == 127)
        default:
            return nil
        }
        self.address = String(decoding: text.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }
}
