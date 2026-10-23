import Foundation
import Network
import Testing

@testable import AppMonitor

// SDD 06's discovery and ADR-0024's cases with fake browsers; bounds shortened, real time.
struct DiscoveryTests {
    static let config = DiscoveryConfig(mdnsBeforeSimulatorHost: 0.3, txtGrace: 0.2)

    /// What one round reported, and when, in seconds from its start.
    final class Round: @unchecked Sendable {
        private let lock = NSLock()
        private var found: [(endpoint: Endpoint?, at: Double)] = []
        var reports: [(endpoint: Endpoint?, at: Double)] { lock.withLock { found } }

        fileprivate func record(_ endpoint: Endpoint?, at: Double) { lock.withLock { found.append((endpoint, at)) } }
    }

    /// Runs one round on its own queue for `seconds`, then cancels it as `MonitorClient` would.
    static func run(_ discover: @escaping Discover, for seconds: Double) async -> Round {
        let queue = DispatchQueue(label: "test.discovery")
        let round = Round()
        let started = Date()
        let cancel = queue.sync { discover(queue) { round.record($0, at: Date().timeIntervalSince(started)) } }
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
        queue.sync { cancel() }
        return round
    }

    static func answer(_ name: String, _ host: String, path: String?) -> DesktopAnswer {
        DesktopAnswer(name: name, host: host, path: path)
    }

    @Test func manualHostIsTheOnlySource() async {
        let browser = FakeBrowser()
        let discover = discovery(
            AppMonitorOptions(host: "192.168.1.20", port: 4000),
            mdns: browser,
            onSimulator: true,
            config: Self.config,
            log: { _ in }
        )
        let round = await Self.run(discover, for: 0.1)
        #expect(round.reports.map(\.endpoint) == [Endpoint(host: "192.168.1.20", port: 4000)])
        #expect(browser.browses == 0)
    }

    @Test func mdnsComesBeforeTheSimulatorHost() async {
        let browser = FakeBrowser { $0(Self.answer("AppMonitor", "192.168.1.20", path: "/v1/ws")) }
        let discover = discovery(
            AppMonitorOptions(), mdns: browser, onSimulator: true, config: Self.config, log: { _ in })
        let round = await Self.run(discover, for: 0.5)
        #expect(round.reports.map(\.endpoint) == [Endpoint(host: "192.168.1.20")])
        #expect(browser.stops == 1)
    }

    @Test func simulatorHostFollowsAnMdnsThatFindsNothing() async throws {
        let browser = FakeBrowser()
        let discover = discovery(
            AppMonitorOptions(), mdns: browser, onSimulator: true, config: Self.config, log: { _ in })
        let round = await Self.run(discover, for: 0.6)
        let report = try #require(round.reports.first)
        #expect(round.reports.count == 1)
        #expect(report.endpoint == Endpoint(host: simulatorHost))
        #expect(report.at >= 0.3)
        #expect(browser.stops == 1)
    }

    @Test func simulatorHostFollowsAnMdnsThatFails() async {
        let logs = Logs()
        let discover = discovery(
            AppMonitorOptions(),
            mdns: FakeBrowser(failure: "PolicyDenied"),
            onSimulator: true,
            config: Self.config,
            log: logs.append
        )
        let round = await Self.run(discover, for: 0.1)
        #expect(round.reports.map(\.endpoint) == [Endpoint(host: simulatorHost)])
        #expect(logs.lines.count == 1)
        #expect(logs.lines.first?.contains("PolicyDenied") == true)
        #expect(logs.lines.first?.contains("NSBonjourServices") == true)
    }

    @Test func aFailingBrowserIsLoggedOncePerClient() async {
        let logs = Logs()
        let discover = discovery(
            AppMonitorOptions(),
            mdns: FakeBrowser(failure: "failed"),
            onSimulator: false,
            config: Self.config,
            log: logs.append
        )
        let first = await Self.run(discover, for: 0.1)
        let second = await Self.run(discover, for: 0.1)
        #expect(first.reports.map(\.endpoint) == [nil])
        #expect(second.reports.map(\.endpoint) == [nil])
        #expect(logs.lines.count == 1)
    }

    @Test func aDeviceWaitsForMdnsUntilTheClientCancels() async {
        let browser = FakeBrowser()
        let discover = discovery(
            AppMonitorOptions(), mdns: browser, onSimulator: false, config: Self.config, log: { _ in })
        let round = await Self.run(discover, for: 0.6)
        #expect(round.reports.isEmpty)
        #expect(browser.stops == 1)
    }

    @Test func aLateTxtRecordGivesThePath() async {
        let browser = FakeBrowser { send in
            send(Self.answer("AppMonitor", "192.168.1.20", path: nil))
            send(Self.answer("AppMonitor", "192.168.1.20", path: "/v2/ws"), after: 0.1)
        }
        let discover = discovery(
            AppMonitorOptions(), mdns: browser, onSimulator: false, config: Self.config, log: { _ in })
        let round = await Self.run(discover, for: 0.4)
        #expect(round.reports.map(\.endpoint) == [Endpoint(host: "192.168.1.20", path: "/v2/ws")])
    }

    @Test func noTxtRecordWithinTheGraceUsesTheDefaultPath() async throws {
        let browser = FakeBrowser { send in
            send(Self.answer("AppMonitor", "192.168.1.20", path: nil))
            send(Self.answer("AppMonitor", "192.168.1.20", path: "/v2/ws"), after: 0.35)
        }
        let discover = discovery(
            AppMonitorOptions(), mdns: browser, onSimulator: false, config: Self.config, log: { _ in })
        let round = await Self.run(discover, for: 0.5)
        let report = try #require(round.reports.first)
        #expect(round.reports.count == 1)
        #expect(report.endpoint == Endpoint(host: "192.168.1.20", path: ProtocolConstants.websocketPath))
        #expect(report.at >= 0.2)
    }

    @Test func theFirstDesktopAnsweredIsKeptOverAnotherName() async {
        let browser = FakeBrowser { send in
            send(Self.answer("Desk A", "192.168.1.20", path: nil))
            send(Self.answer("Desk B", "192.168.1.30", path: "/v1/ws"))
        }
        let discover = discovery(
            AppMonitorOptions(), mdns: browser, onSimulator: false, config: Self.config, log: { _ in })
        let round = await Self.run(discover, for: 0.4)
        #expect(round.reports.map(\.endpoint) == [Endpoint(host: "192.168.1.20")])
    }

    @Test func aNameInAnotherCaseIsTheSameDesktop() async {
        let browser = FakeBrowser { send in
            send(Self.answer("AppMonitor", "192.168.1.20", path: nil))
            send(Self.answer("APPMONITOR", "192.168.1.20", path: "/v2/ws"))
        }
        let discover = discovery(
            AppMonitorOptions(), mdns: browser, onSimulator: false, config: Self.config, log: { _ in })
        let round = await Self.run(discover, for: 0.1)
        #expect(round.reports.map(\.endpoint) == [Endpoint(host: "192.168.1.20", path: "/v2/ws")])
    }
}

/// `DesktopSelectorTest` of the `protocol` module, case for case.
struct DesktopSelectorTests {
    static func answer(_ name: String, host: String = "10.0.0.1", path: String?) -> DesktopAnswer {
        DesktopAnswer(name: name, host: host, path: path)
    }

    @Test func answerWithTxtIsChosenAtOnce() {
        var selector = DesktopSelector()
        #expect(selector.offer(Self.answer("AppMonitor", path: "/v2/ws")) == Self.answer("AppMonitor", path: "/v2/ws"))
    }

    @Test func lateTxtCompletesTheKeptDesktop() {
        var selector = DesktopSelector()
        #expect(selector.offer(Self.answer("AppMonitor", path: nil)) == nil)
        #expect(selector.hasCandidate)
        #expect(selector.offer(Self.answer("AppMonitor", path: "/v2/ws"))?.path == "/v2/ws")
    }

    @Test func noTxtSettlesOnTheDefaultPath() {
        var selector = DesktopSelector()
        _ = selector.offer(Self.answer("AppMonitor", path: nil))
        #expect(selector.settle()?.path == ProtocolConstants.websocketPath)
    }

    @Test func aLaterAnswerWithoutTxtKeepsThePathAlreadySeen() {
        var selector = DesktopSelector()
        _ = selector.offer(Self.answer("AppMonitor", host: "10.0.0.1", path: "/v2/ws"))
        let updated = selector.offer(Self.answer("AppMonitor", host: "10.0.0.2", path: nil))
        #expect(updated == Self.answer("AppMonitor", host: "10.0.0.2", path: "/v2/ws"))
    }

    @Test func theFirstDesktopIsKeptAndOtherNamesAreIgnored() {
        var selector = DesktopSelector()
        _ = selector.offer(Self.answer("First", host: "10.0.0.1", path: nil))
        #expect(selector.offer(Self.answer("Second", host: "10.0.0.2", path: "/v1/ws")) == nil)
        #expect(selector.offer(Self.answer("FIRST", host: "10.0.0.1", path: "/v1/ws"))?.host == "10.0.0.1")
    }

    @Test func nothingKeptSettlesOnNil() {
        #expect(DesktopSelector().settle() == nil)
    }
}

// The real browser against a service this process advertises; needs a multicast-capable interface.
struct BonjourBrowserTests {
    @Test func findsAnAdvertisedDesktopWithItsPathAndIPv4Address() async throws {
        let queue = DispatchQueue(label: "test.bonjour")
        let name = "AppMonitor test \(UUID().uuidString.prefix(8))"
        let listener = try NWListener(using: .tcp)
        listener.service = NWListener.Service(
            name: name,
            type: ProtocolConstants.mdnsServiceType,
            txtRecord: NWTXTRecord([ProtocolConstants.mdnsTxtPath: "/v9/ws"])
        )
        listener.newConnectionHandler = { $0.cancel() }
        listener.start(queue: queue)
        defer { listener.cancel() }
        #expect(await eventually { listener.port != nil })

        let answers = Answers()
        let stop = queue.sync { BonjourBrowser().browse(on: queue, onAnswer: answers.append, onFailure: { _ in }) }
        defer { queue.sync { stop() } }
        #expect(await eventually(timeout: 10) { answers.all.contains { $0.name == name } })
        let answer = try #require(answers.all.first { $0.name == name })
        #expect(answer.path == "/v9/ws")
        #expect(answer.port == listener.port?.rawValue)
        #expect(IPv4Address(answer.host) != nil)
    }
}

/// Answers from `onBrowse` when browsing starts, delivered on the round's queue; or one failure.
final class FakeBrowser: MdnsBrowser, @unchecked Sendable {
    struct Send: Sendable {
        let queue: DispatchQueue
        let onAnswer: @Sendable (DesktopAnswer) -> Void

        func callAsFunction(_ answer: DesktopAnswer, after delay: Double = 0) {
            queue.asyncAfter(deadline: .now() + delay) { onAnswer(answer) }
        }
    }

    private let lock = NSLock()
    private let onBrowse: @Sendable (Send) -> Void
    private let failure: String?
    private var browseCount = 0
    private var stopCount = 0

    init(failure: String? = nil, onBrowse: @escaping @Sendable (Send) -> Void = { _ in }) {
        self.failure = failure
        self.onBrowse = onBrowse
    }

    var browses: Int { lock.withLock { browseCount } }
    var stops: Int { lock.withLock { stopCount } }

    func browse(
        on queue: DispatchQueue,
        onAnswer: @escaping @Sendable (DesktopAnswer) -> Void,
        onFailure: @escaping @Sendable (String) -> Void
    ) -> @Sendable () -> Void {
        lock.withLock { browseCount += 1 }
        if let failure { queue.async { onFailure(failure) } }
        onBrowse(Send(queue: queue, onAnswer: onAnswer))
        return { [self] in lock.withLock { stopCount += 1 } }
    }
}

final class Logs: @unchecked Sendable {
    private let lock = NSLock()
    private var all: [String] = []
    var lines: [String] { lock.withLock { all } }
    func append(_ line: String) { lock.withLock { all.append(line) } }
}

final class Answers: @unchecked Sendable {
    private let lock = NSLock()
    private var answers: [DesktopAnswer] = []
    var all: [DesktopAnswer] { lock.withLock { answers } }
    func append(_ answer: DesktopAnswer) { lock.withLock { answers.append(answer) } }
}
