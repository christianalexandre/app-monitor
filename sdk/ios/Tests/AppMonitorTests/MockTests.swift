import Foundation
import Testing

@testable import AppMonitor

func mockRule(
    _ id: Int64,
    method: String = "",
    host: String = "127.0.0.1",
    path: String,
    delayMs: Int64 = 0,
    failure: String? = nil
) -> Appmonitor_V1_MockRule {
    var rule = Appmonitor_V1_MockRule()
    rule.id = id
    rule.method = method
    rule.host = host
    rule.path = path
    rule.delayMs = delayMs
    if let failure {
        var answer = Appmonitor_V1_MockFailure()
        answer.message = failure
        rule.failure = answer
    } else {
        var answer = Appmonitor_V1_MockResponse()
        answer.statusCode = 201
        var header = Appmonitor_V1_Header()
        header.name = "X-Mocked"
        header.value = "yes"
        answer.headers = [header]
        answer.body = Data("mocked".utf8)
        rule.response = answer
    }
    return rule
}

// `protocol/fixtures/mock_matching.json`, the vector `sdk/core` runs too (ADR-0115 B.1).
struct MockMatchingVectorTests {
    struct Vector: Decodable {
        struct Rule: Decodable {
            let id: Int64
            let method: String
            let host: String
            let path: String
        }
        struct Case: Decodable {
            let name: String
            let method: String
            let url: String
            let match: Int64?
        }
        let rules: [Rule]
        let cases: [Case]
    }

    @Test func everyCaseMatchesItsRule() throws {
        let data = try Data(contentsOf: FixtureTests.fixtures.appendingPathComponent("mock_matching.json"))
        let vector = try JSONDecoder().decode(Vector.self, from: data)
        let set = MockRuleSet()
        set.replace(vector.rules.map { mockRule($0.id, method: $0.method, host: $0.host, path: $0.path) })

        for test in vector.cases {
            #expect(set.match(method: test.method, url: test.url)?.id == test.match, "\(test.name)")
        }
    }

    @Test func aRuleWithNeitherAnswerIsIgnored() {
        var rule = mockRule(1, path: "/a")
        rule.answer = nil
        let set = MockRuleSet()
        set.replace([rule, mockRule(2, path: "/a")])

        #expect(set.match(method: "GET", url: "http://127.0.0.1/a")?.id == 2)
    }
}

@Suite(.serialized)
struct MonitorClientMocksTests {
    static func rules(_ rules: Appmonitor_V1_MockRule...) -> Appmonitor_V1_Envelope {
        var set = Appmonitor_V1_MockRules()
        set.rules = rules
        var envelope = Appmonitor_V1_Envelope()
        envelope.channel = ProtocolConstants.channelInteraction
        envelope.type = "MockRules"
        envelope.payload = try! set.serializedBytes()
        return envelope
    }

    @Test func helloListsTheInteractionChannel() {
        #expect(appleHello().channels.contains(ProtocolConstants.channelInteraction))
    }

    @Test func theClientKeepsTheLastSetAndEmptiesItWhenTheSessionEnds() async throws {
        let desktop = TestDesktop(
            mode: .ack(channels: [ProtocolConstants.channelCore, ProtocolConstants.channelInteraction]))
        try await desktop.start()
        let client = MonitorClientTests.client(manualDiscovery(desktop.endpoint))
        client.start()
        defer { client.stop() }
        #expect(await eventually { client.state == .connected })

        desktop.push(Self.rules(mockRule(1, path: "/a")))
        #expect(await eventually { client.mocks.match(method: "GET", url: "http://127.0.0.1/a")?.id == 1 })
        desktop.push(Self.rules(mockRule(2, path: "/b")))
        #expect(await eventually { client.mocks.match(method: "GET", url: "http://127.0.0.1/b")?.id == 2 })
        #expect(client.mocks.match(method: "GET", url: "http://127.0.0.1/a") == nil)

        desktop.stop()
        #expect(await eventually { client.mocks.match(method: "GET", url: "http://127.0.0.1/b") == nil })
    }
}

// Inside the capture suite: the hooks are process-wide, one test at a time owns `URLSessionCapture.sink`.
extension URLSessionCaptureTests {
    func started(_ url: URL) async throws -> (Appmonitor_V1_RequestStarted, Appmonitor_V1_Envelope) {
        try await sink.exchange(url)
    }

    /// A capture whose sink answers from `rules`, as a session holding them.
    func mocking(_ rules: Appmonitor_V1_MockRule...) {
        capture()
        sink.rules.replace(rules)
    }

    @Test func aMatchingTaskIsAnsweredWithoutTheNetworkAndCapturedWithItsRule() async throws {
        mocking(mockRule(7, method: "GET", path: "/ok"))

        let (data, response) = try await URLSession.shared.data(from: server.url("/ok?page=2"))

        let http = try #require(response as? HTTPURLResponse)
        #expect(http.statusCode == 201)
        #expect(http.value(forHTTPHeaderField: "X-Mocked") == "yes")
        #expect(String(decoding: data, as: UTF8.self) == "mocked")
        #expect(server.received.isEmpty)
        let (started, end) = try await started(server.url("/ok?page=2"))
        #expect(started.mockRuleID == 7)
        let event = try received(end)
        #expect(event.statusCode == 201)
        #expect(event.body == Data("mocked".utf8))
        #expect(event.headers.map(\.name).contains("X-Mocked"))
    }

    @Test func aTaskNoRuleMatchesGoesToTheNetwork() async throws {
        mocking(mockRule(7, method: "POST", path: "/ok"))

        let (data, _) = try await URLSession(configuration: .ephemeral).data(from: server.url("/ok"))

        #expect(String(decoding: data, as: UTF8.self) == "hello")
        let (started, _) = try await started(server.url("/ok"))
        #expect(!started.hasMockRuleID)
    }

    @Test func anUploadIsAnswered() async throws {
        mocking(mockRule(3, method: "POST", path: "/echo"))
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "POST"

        let (data, _) = try await URLSession.shared.upload(for: request, from: Data("sent".utf8))

        #expect(String(decoding: data, as: UTF8.self) == "mocked")
        let (started, _) = try await started(server.url("/echo"))
        #expect(started.mockRuleID == 3)
        #expect(started.body == Data("sent".utf8))
    }

    @Test func aFailureRuleFailsTheTaskAfterItsDelay() async throws {
        mocking(mockRule(4, path: "/error", delayMs: 300, failure: "offline on purpose"))
        let begin = Date()

        let error = await #expect(throws: URLError.self) {
            try await URLSession.shared.data(from: self.server.url("/error"))
        }

        #expect(Date().timeIntervalSince(begin) >= 0.3)
        #expect(error?.code == .networkConnectionLost)
        #expect(error?.localizedDescription == "offline on purpose")
        let (started, end) = try await started(server.url("/error"))
        #expect(started.mockRuleID == 4)
        let failed = try Appmonitor_V1_RequestFailed(serializedBytes: end.payload)
        #expect(failed.error == "NSURLErrorDomain \(NSURLErrorNetworkConnectionLost): offline on purpose")
        #expect(server.received.isEmpty)
    }

    @Test func aTaskCancelledDuringTheDelayFailsAsCancelled() async throws {
        mocking(mockRule(5, path: "/ok", delayMs: 5000))
        let task = URLSession.shared.dataTask(with: server.url("/ok"))

        task.resume()
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()

        let (_, end) = try await started(server.url("/ok"))
        #expect(end.type == "RequestFailed")
        let failed = try Appmonitor_V1_RequestFailed(serializedBytes: end.payload)
        #expect(failed.error.hasPrefix("NSURLErrorDomain \(NSURLErrorCancelled)"))
    }

    @Test func nothingIsMockedWhileTheMonitorIsIdle() async throws {
        mocking(mockRule(6, path: "/ok"))
        sink.options = nil

        let (data, _) = try await URLSession.shared.data(from: server.url("/ok"))

        #expect(String(decoding: data, as: UTF8.self) == "hello")
    }

    @Test func theProtocolGoesFirstInALocalConfigurationOnlyAndOnce() {
        capture()
        let configuration = URLSessionConfiguration.default
        configuration.protocolClasses = [MockProtocol.self] + (configuration.protocolClasses ?? [])
        let background = URLSessionConfiguration.background(withIdentifier: "appmonitor.mock-test")

        let classes = configuration.protocolClasses ?? []
        #expect(classes.first == MockProtocol.self)
        #expect(classes.filter { $0 == MockProtocol.self }.count == 1)
        #expect(!(background.protocolClasses ?? []).contains { $0 == MockProtocol.self })
    }
}
