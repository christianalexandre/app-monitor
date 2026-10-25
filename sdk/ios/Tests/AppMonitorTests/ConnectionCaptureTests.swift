import Foundation
import Testing

@testable import AppMonitor

/// One accepted connection on `127.0.0.1`: reads `expected` bytes, answers `reply`, closes.
final class TestSocketServer: @unchecked Sendable {
    private let listener: Int32
    let port: UInt16

    init(expected: Int, reply: Data) throws {
        let listener = socket(AF_INET, SOCK_STREAM, 0)
        var address = loopback(port: 0)
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(listener, $0, length) == 0 && getsockname(listener, $0, &length) == 0
            }
        }
        guard bound, listen(listener, 1) == 0 else {
            Darwin.close(listener)
            throw POSIXError(.EADDRINUSE)
        }
        self.listener = listener
        port = UInt16(bigEndian: address.sin_port)
        Thread.detachNewThread {
            let client = accept(listener, nil, nil)
            var buffer = [UInt8](repeating: 0, count: 64)
            var read = 0
            while read < expected {
                let n = recv(client, &buffer, buffer.count, 0)
                if n <= 0 { break }
                read += n
            }
            _ = reply.withUnsafeBytes { send(client, $0.baseAddress, $0.count, 0) }
            Darwin.close(client)
        }
    }

    deinit { Darwin.close(listener) }
}

func loopback(port: UInt16) -> sockaddr_in {
    var address = sockaddr_in()
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    address.sin_family = sa_family_t(AF_INET)
    address.sin_port = port.bigEndian
    address.sin_addr.s_addr = inet_addr("127.0.0.1")
    return address
}

/// `connect` to `address` from this image, as a third-party SDK's own socket code does; errno or 0.
func connect(_ fd: Int32, _ address: sockaddr_in) -> Int32 {
    var address = address
    let result = withUnsafePointer(to: &address) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    return result == 0 ? 0 : errno
}

/// A port nothing listens on: bound once, then released.
func freePort() throws -> UInt16 {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    defer { Darwin.close(fd) }
    var address = loopback(port: 0)
    var length = socklen_t(MemoryLayout<sockaddr_in>.size)
    let bound = withUnsafeMutablePointer(to: &address) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(fd, $0, length) == 0 && getsockname(fd, $0, &length) == 0
        }
    }
    guard bound else { throw POSIXError(.EADDRINUSE) }
    return UInt16(bigEndian: address.sin_port)
}

// `ConnectionCapture.sink` is process-wide: one test at a time owns it.
@Suite(.serialized)
final class ConnectionCaptureTests {
    let sink = RecordingSink()

    init() {
        #expect(ConnectionCapture.install())
        ConnectionCapture.sink = sink
        ConnectionCapture.reportsLoopback = true
    }

    deinit {
        ConnectionCapture.sink = monitor
        ConnectionCapture.reportsLoopback = false
    }

    /// The `RequestStarted` to `port` and the event that ended it, waited for up to 5 s; the host is the
    /// name a `getaddrinfo` of any test resolved last.
    func connection(_ port: UInt16) async throws -> (Appmonitor_V1_Envelope, Appmonitor_V1_Envelope) {
        for _ in 0..<100 {
            let events = sink.all
            let toPort = { (envelope: Appmonitor_V1_Envelope) in
                envelope.type == "RequestStarted" && self.startedURL(envelope)?.hasSuffix(":\(port)") == true
            }
            if let started = events.first(where: toPort) {
                let id = try Appmonitor_V1_RequestStarted(serializedBytes: started.payload).requestID
                if let end = events.first(where: { $0.type != "RequestStarted" && requestID($0) == id }) {
                    return (started, end)
                }
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw POSIXError(.ETIMEDOUT)
    }

    func startedURL(_ envelope: Appmonitor_V1_Envelope) -> String? {
        try? Appmonitor_V1_RequestStarted(serializedBytes: envelope.payload).url
    }

    func requestID(_ envelope: Appmonitor_V1_Envelope) -> String? {
        switch envelope.type {
        case "ResponseReceived": try? Appmonitor_V1_ResponseReceived(serializedBytes: envelope.payload).requestID
        case "RequestFailed": try? Appmonitor_V1_RequestFailed(serializedBytes: envelope.payload).requestID
        default: nil
        }
    }

    @Test func rawSocketClientIsOneConnectionWithItsNameBytesAndDuration() async throws {
        let server = try TestSocketServer(expected: 8, reply: Data("world!!".utf8))
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        var resolved: UnsafeMutablePointer<addrinfo>?
        #expect(getaddrinfo("localhost", "\(server.port)", &hints, &resolved) == 0)
        let address = try #require(resolved).pointee.ai_addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
            $0.pointee
        }
        freeaddrinfo(resolved)
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        #expect(connect(fd, address) == 0)
        _ = send(fd, "hello", 5, 0)
        _ = Darwin.write(fd, "abc", 3)
        var buffer = [UInt8](repeating: 0, count: 16)
        var received = 0
        while case let n = recv(fd, &buffer, buffer.count, 0), n > 0 { received += n }
        try await Task.sleep(nanoseconds: 20_000_000)
        Darwin.close(fd)

        let (startedEnvelope, end) = try await connection(server.port)
        let started = try Appmonitor_V1_RequestStarted(serializedBytes: startedEnvelope.payload)
        #expect(started.url == "tcp://localhost:\(server.port)")
        #expect(started.method == "CONNECT")
        #expect(started.captureLayer == .connection)
        #expect(started.body.isEmpty && !started.hasBodySize)
        #expect(end.type == "ResponseReceived")
        let response = try Appmonitor_V1_ResponseReceived(serializedBytes: end.payload)
        #expect(response.statusCode == 0)
        #expect(response.body.isEmpty && response.bodyTruncated)
        #expect(response.sentSize == 8)
        #expect(response.bodySize == Int64(received) && received == 7)
        #expect(end.timestampMs - startedEnvelope.timestampMs >= 20)
        #expect(sink.all.count == 2)
    }

    @Test func nonBlockingConnectIsCountedOnceFromInProgress() async throws {
        let server = try TestSocketServer(expected: 4, reply: Data("ok".utf8))
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
        #expect([0, EINPROGRESS].contains(connect(fd, loopback(port: server.port))))
        var ready = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
        _ = poll(&ready, 1, 2000)
        #expect([EISCONN, 0].contains(connect(fd, loopback(port: server.port))))
        _ = send(fd, "ping", 4, 0)
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) & ~O_NONBLOCK)
        var buffer = [UInt8](repeating: 0, count: 16)
        while recv(fd, &buffer, buffer.count, 0) > 0 {}
        Darwin.close(fd)

        let (_, end) = try await connection(server.port)
        let response = try Appmonitor_V1_ResponseReceived(serializedBytes: end.payload)
        #expect(response.sentSize == 4 && response.bodySize == 2)
        #expect(sink.all.count == 2)
    }

    @Test func refusedPortFailsWithItsErrno() async throws {
        let refused = try freePort()
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        defer { Darwin.close(fd) }
        #expect(connect(fd, loopback(port: refused)) == ECONNREFUSED)

        let (_, end) = try await connection(refused)
        #expect(end.type == "RequestFailed")
        let failed = try Appmonitor_V1_RequestFailed(serializedBytes: end.payload)
        #expect(failed.error == "NSPOSIXErrorDomain 61: Connection refused")
    }

    @Test func loopbackIsNotReportedInTheApp() async throws {
        ConnectionCapture.reportsLoopback = false
        let server = try TestSocketServer(expected: 0, reply: Data())
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        #expect(connect(fd, loopback(port: server.port)) == 0)
        Darwin.close(fd)
        try await Task.sleep(nanoseconds: 100_000_000)
        #expect(sink.all.isEmpty)
    }

    @Test func nothingIsReportedWhenTurnedOffOrIdle() async throws {
        for options in [AppMonitorOptions(captureConnections: false), nil] {
            sink.options = options
            let server = try TestSocketServer(expected: 0, reply: Data())
            let fd = socket(AF_INET, SOCK_STREAM, 0)
            #expect(connect(fd, loopback(port: server.port)) == 0)
            Darwin.close(fd)
        }
        try await Task.sleep(nanoseconds: 100_000_000)
        #expect(sink.all.isEmpty)
    }

    @Test func theSDKsOwnDesktopConnectionIsNotReported() async throws {
        let desktop = TestDesktop()
        try await desktop.start()
        defer { desktop.stop() }
        let client = MonitorClient(hello: { appleHello() }, discover: manualDiscovery(desktop.endpoint), log: { _ in })
        client.start()
        defer { client.stop() }
        #expect(await eventually { client.state == .connected })
        var envelope = Appmonitor_V1_Envelope()
        envelope.channel = ProtocolConstants.channelNetwork
        envelope.type = "RequestStarted"
        client.send(envelope)
        #expect(await eventually { desktop.events.count == 1 })
        #expect(sink.all.isEmpty)
    }

    @Test func urlSessionTrafficIsLeftToItsOwnCapture() async throws {
        let server = TestHTTPServer()
        try await server.start()
        defer { server.stop() }
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let (_, response) = try await session.data(from: server.url("/ok"))
        #expect((response as? HTTPURLResponse)?.statusCode == 200)
        session.finishTasksAndInvalidate()
        try await Task.sleep(nanoseconds: 100_000_000)
        #expect(sink.all.isEmpty)
    }

    /// Prints the cost per `write` of the rebound call against the original, on an uncounted descriptor;
    /// the best of five rounds each.
    @Test func reboundWriteCost() throws {
        let fd = open("/dev/null", O_WRONLY)
        defer { Darwin.close(fd) }
        typealias Write = @convention(c) (Int32, UnsafeRawPointer?, Int) -> Int
        let original = unsafeBitCast(dlsym(UnsafeMutableRawPointer(bitPattern: -2), "write"), to: Write.self)
        let calls = 200_000
        func time(_ body: () -> Void) -> Double {
            let start = DispatchTime.now().uptimeNanoseconds
            body()
            return Double(DispatchTime.now().uptimeNanoseconds - start) / Double(calls)
        }
        let byte: [UInt8] = [0]
        var direct = Double.infinity
        var rebound = Double.infinity
        for _ in 0..<5 {
            direct = min(direct, time { for _ in 0..<calls { _ = original(fd, byte, 1) } })
            rebound = min(rebound, time { for _ in 0..<calls { _ = Darwin.write(fd, byte, 1) } })
        }
        print("connection capture: write \(Int(rebound)) ns rebound, \(Int(direct)) ns original")
        #expect(rebound > 0 && direct > 0)
    }
}
