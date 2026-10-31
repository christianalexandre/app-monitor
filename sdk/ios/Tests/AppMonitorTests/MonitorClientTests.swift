import Foundation
import Network
import Testing

@testable import AppMonitor

// SDD 06 against an in-process desktop; bounds shortened, no jitter.
@Suite(.serialized)
struct MonitorClientTests {
    static let config = ClientConfig(
        discoveryTimeout: 0.5,
        connectTimeout: 0.5,
        pingInterval: 0.1,
        pongTimeout: 0.3,
        closeTimeout: 1,
        backoffInitial: 0.05,
        backoffMax: 0.2
    )

    static func hello() -> Appmonitor_V1_Hello {
        var hello = Appmonitor_V1_Hello()
        hello.protocolVersion = ProtocolConstants.version
        hello.platform = .ios
        hello.deviceID = "test-device"
        hello.channels = [ProtocolConstants.channelCore, ProtocolConstants.channelNetwork]
        return hello
    }

    static func client(
        _ discover: @escaping Discover,
        config: ClientConfig = config,
        log: @escaping @Sendable (String) -> Void = { _ in }
    ) -> MonitorClient {
        MonitorClient(hello: hello, discover: discover, config: config, log: log, random: { _ in 0 })
    }

    static func event(_ index: Int, channel: String = ProtocolConstants.channelNetwork) -> Appmonitor_V1_Envelope {
        var envelope = Appmonitor_V1_Envelope()
        envelope.channel = channel
        envelope.type = "RequestStarted"
        envelope.payload = Data("\(index)".utf8)
        envelope.timestampMs = Int64(index)
        return envelope
    }

    /// A port nothing listens on: bound once, then released.
    static func freePort() async throws -> UInt16 {
        let desktop = TestDesktop()
        try await desktop.start()
        desktop.stop()
        return desktop.port
    }

    @Test func anIPv6HostKeepsItsZoneInTheURL() {
        #expect(Endpoint(host: "fe80::1%en0").url?.absoluteString == "ws://[fe80::1%25en0]:47474/v1/ws")
        #expect(Endpoint(host: "fe80::1%en0").url?.host == "fe80::1%en0")
        #expect(Endpoint(host: "10.0.0.2", port: 9).url?.absoluteString == "ws://10.0.0.2:9/v1/ws")
    }

    @Test func handshakeSendsHelloFirstThenConnects() async throws {
        let desktop = TestDesktop()
        try await desktop.start()
        defer { desktop.stop() }
        let client = Self.client(manualDiscovery(desktop.endpoint))
        client.start()
        #expect(await eventually { client.state == .connected })
        let first = try #require(desktop.envelopes.first)
        #expect(first.channel == ProtocolConstants.channelCore && first.type == "Hello")
        #expect(try Appmonitor_V1_Hello(serializedBytes: first.payload) == Self.hello())
        client.stop()
        await client.join()
    }

    @Test func thousandEventsArriveInOrder() async throws {
        let desktop = TestDesktop()
        try await desktop.start()
        defer { desktop.stop() }
        let client = Self.client(manualDiscovery(desktop.endpoint))
        client.start()
        for index in 0..<1000 { client.send(Self.event(index)) }
        #expect(await eventually { desktop.events.count == 1000 })
        #expect(desktop.events.map(\.timestampMs) == (0..<1000).map(Int64.init))
        client.stop()
        await client.join()
    }

    @Test func eventsQueuedBeforeTheDesktopStartsArriveOnceItDoes() async throws {
        let port = try await Self.freePort()
        let client = Self.client(manualDiscovery(Endpoint(host: "127.0.0.1", port: port)))
        client.start()
        for index in 0..<10 { client.send(Self.event(index)) }
        #expect(await eventually { client.state == .backingOff })
        let desktop = TestDesktop()
        try await desktop.start(port: port)
        defer { desktop.stop() }
        #expect(await eventually { desktop.events.count == 10 })
        #expect(desktop.events.map(\.timestampMs) == (0..<10).map(Int64.init))
        client.stop()
        await client.join()
    }

    @Test func eventsQueuedWhileTheDesktopIsDownArriveAfterItReturns() async throws {
        let first = TestDesktop()
        try await first.start()
        let port = first.port
        let client = Self.client(manualDiscovery(first.endpoint))
        client.start()
        #expect(await eventually { client.state == .connected })
        first.stop()
        #expect(await eventually { client.state != .connected })
        for index in 0..<10 { client.send(Self.event(index)) }
        let second = TestDesktop()
        try await second.start(port: port)
        defer { second.stop() }
        #expect(await eventually { second.events.count == 10 })
        #expect(second.events.map(\.timestampMs) == (0..<10).map(Int64.init))
        client.stop()
        await client.join()
    }

    // ADR-0166: the buffer's and the size's drops, reported before the first event.
    @Test func dropsAreReportedBeforeTheFirstEvent() async throws {
        let desktop = TestDesktop()
        try await desktop.start()
        defer { desktop.stop() }
        var config = Self.config
        config.bufferCount = 2
        let client = Self.client(manualDiscovery(desktop.endpoint), config: config)
        for index in 0..<5 { client.send(Self.event(index)) }
        var big = Self.event(9)
        big.payload = Data(count: ProtocolConstants.maxFrameBytes)
        client.send(big)
        client.start()
        #expect(await eventually { desktop.events.count == 2 })
        #expect(desktop.events.map(\.timestampMs) == [3, 4])
        #expect(desktop.envelopes.dropFirst().first?.type == "DeliveryReport")
        let report = try #require(desktop.reports.first)
        #expect(report.evicted == 3 && report.oversized == 1 && report.previousSessionID.isEmpty)
        client.stop()
        await client.join()
    }

    // ADR-0166: the next session names the last one and the frames written on it, its report included.
    @Test func aReconnectReportsWhatThePreviousSessionWrote() async throws {
        let first = TestDesktop()
        try await first.start()
        let port = first.port
        let client = Self.client(manualDiscovery(first.endpoint))
        client.start()
        for index in 0..<2 { client.send(Self.event(index)) }
        #expect(await eventually { first.events.count == 2 })
        first.stop()
        #expect(await eventually { client.state != .connected })
        let second = TestDesktop()
        try await second.start(port: port)
        defer { second.stop() }
        #expect(await eventually { !second.reports.isEmpty })
        let report = try #require(second.reports.first)
        #expect(!report.previousSessionID.isEmpty)
        #expect(report.previousSessionWritten == 3)
        #expect(report.evicted == 0 && report.oversized == 0)
        client.stop()
        await client.join()
    }

    @Test func channelsMissingFromHelloAckAreDropped() async throws {
        let desktop = TestDesktop(mode: .ack(channels: [ProtocolConstants.channelCore]))
        try await desktop.start()
        defer { desktop.stop() }
        let client = Self.client(manualDiscovery(desktop.endpoint))
        client.start()
        client.send(Self.event(1))
        client.send(Self.event(2, channel: ProtocolConstants.channelCore))
        #expect(await eventually { desktop.events.count == 1 })
        #expect(desktop.events.first?.timestampMs == 2)
        client.stop()
        await client.join()
    }

    @Test func rejectedBacksOffAtTheLongestDelay() async throws {
        let desktop = TestDesktop(mode: .reject)
        try await desktop.start()
        defer { desktop.stop() }
        let client = Self.client(manualDiscovery(desktop.endpoint))
        client.start()
        #expect(await eventually { client.lastBackoffDelay != nil })
        #expect(client.lastBackoffDelay == Self.config.backoffMax)
        client.stop()
        await client.join()
    }

    @Test func noPongBacksOff() async throws {
        let desktop = TestDesktop(mode: .ackWithoutPongs)
        try await desktop.start()
        defer { desktop.stop() }
        let client = Self.client(manualDiscovery(desktop.endpoint))
        client.start()
        #expect(await eventually { client.state == .connected })
        #expect(await eventually { client.state == .backingOff })
        #expect(client.lastBackoffDelay == Self.config.backoffInitial)
        client.stop()
        await client.join()
    }

    @Test func connectTimeoutBacksOffAndLogsOnce() async throws {
        let desktop = TestDesktop(mode: .noUpgrade)
        try await desktop.start()
        defer { desktop.stop() }
        let lines = LockedLines()
        let client = Self.client(manualDiscovery(desktop.endpoint), log: lines.append)
        client.start()
        #expect(await eventually { desktop.connectionCount >= 2 })
        #expect(lines.all.count == 1)
        #expect(lines.all.first?.contains("NSLocalNetworkUsageDescription") == true)
        client.stop()
        await client.join()
    }

    @Test func aConnectFailureAfterAGoodSessionIsLoggedAgain() async throws {
        let unreachable = TestDesktop(mode: .noUpgrade)
        try await unreachable.start()
        defer { unreachable.stop() }
        let good = TestDesktop(mode: .ack(channels: [ProtocolConstants.channelCore]))
        try await good.start()
        let target = LockedLines()
        target.append("unreachable")
        let endpoints = ["unreachable": unreachable.endpoint, "good": good.endpoint]
        let lines = LockedLines()
        let client = Self.client(
            { queue, found in
                let endpoint = endpoints[target.all.last ?? ""]
                queue.async { found(endpoint) }
                return {}
            }, log: lines.append)
        client.start()
        #expect(await eventually { lines.all.count == 1 })
        target.append("good")
        #expect(await eventually { client.state == .connected })
        target.append("unreachable")
        good.stop()

        #expect(await eventually { lines.all.count == 2 })
        client.stop()
        await client.join()
    }

    @Test func discoveryThatFindsNothingBacksOff() async throws {
        let client = Self.client({ _, _ in {} })
        client.start()
        #expect(await eventually { client.state == .backingOff })
        client.stop()
        await client.join()
    }

    enum StopCase: String, CaseIterable, Sendable {
        case idle, discovering, connecting, handshaking, connected, backingOff
    }

    @Test(arguments: StopCase.allCases)
    func stopReleasesEverything(in stopCase: StopCase) async throws {
        let mode: TestDesktop.Mode =
            switch stopCase {
            case .connecting: .noUpgrade
            case .handshaking: .silent
            default: .ack(channels: [ProtocolConstants.channelCore, ProtocolConstants.channelNetwork])
            }
        let desktop = TestDesktop(mode: mode)
        try await desktop.start()
        defer { desktop.stop() }
        let discover: Discover =
            switch stopCase {
            case .discovering: { _, _ in {} }
            case .backingOff: manualDiscovery(Endpoint(host: "127.0.0.1", port: try await Self.freePort()))
            default: manualDiscovery(desktop.endpoint)
            }
        var config = Self.config
        config.discoveryTimeout = 30
        // A freed port another socket took drops the SYN instead of refusing it: back off on the timeout too.
        config.connectTimeout = stopCase == .backingOff ? Self.config.connectTimeout : 30
        config.backoffInitial = 30
        var client: MonitorClient? = Self.client(discover, config: config)
        weak let weakClient = client
        let expected: ClientState =
            switch stopCase {
            case .idle: .idle
            case .discovering: .discovering
            case .connecting: .connecting
            case .handshaking: .handshaking
            case .connected: .connected
            case .backingOff: .backingOff
            }
        if stopCase != .idle { client?.start() }
        #expect(await eventually { client?.state == expected })
        client?.send(Self.event(1))
        let started = Date()
        client?.stop()
        await client?.join()
        #expect(Date().timeIntervalSince(started) < 1.5)
        #expect(client?.state == .stopped)
        #expect(client?.isReleased == true)
        if stopCase == .connected {
            #expect(await eventually { desktop.closes == [.protocolCode(.normalClosure)] })
        }
        client = nil
        #expect(await eventually { weakClient == nil })
    }

    @Test func startAfterStopIsIgnored() async throws {
        let client = Self.client({ _, _ in {} })
        client.stop()
        client.start()
        await client.join()
        #expect(client.state == .stopped)
        #expect(client.isReleased)
    }
}

final class LockedLines: @unchecked Sendable {
    private let lock = NSLock()
    private var lines: [String] = []

    var all: [String] { lock.withLock { lines } }

    func append(_ line: String) { lock.withLock { lines.append(line) } }
}
