import Foundation
import SwiftProtobuf
import Testing

@testable import AppMonitor

func breakpointRule(
    _ id: Int64,
    method: String = "",
    host: String = "127.0.0.1",
    path: String,
    request: Bool = false,
    response: Bool = false,
    timeoutMs: Int64 = 5_000
) -> Appmonitor_V1_BreakpointRule {
    var rule = Appmonitor_V1_BreakpointRule()
    rule.id = id
    rule.method = method
    rule.host = host
    rule.path = path
    rule.request = request
    rule.response = response
    rule.timeoutMs = timeoutMs
    return rule
}

func breakpointHit(_ id: String, _ stage: Appmonitor_V1_BreakpointStage, timeoutMs: Int64 = 5_000)
    -> Appmonitor_V1_BreakpointHit
{
    var hit = Appmonitor_V1_BreakpointHit()
    hit.stage = stage
    hit.request.requestID = id
    hit.timeoutMs = timeoutMs
    return hit
}

func resume(_ id: String, _ stage: Appmonitor_V1_BreakpointStage) -> Appmonitor_V1_BreakpointResume {
    var resume = Appmonitor_V1_BreakpointResume()
    resume.requestID = id
    resume.stage = stage
    return resume
}

func header(_ name: String, _ value: String) -> Appmonitor_V1_Header {
    var header = Appmonitor_V1_Header()
    header.name = name
    header.value = value
    return header
}

/// What `Breakpoints.hold` called `done` with, read from any thread.
final class Outcomes: @unchecked Sendable {
    private let lock = NSLock()
    private var outcomes: [BreakpointOutcome] = []

    var all: [BreakpointOutcome] { lock.withLock { outcomes } }

    func record(_ outcome: BreakpointOutcome) { lock.withLock { outcomes.append(outcome) } }
}

struct BreakpointsTests {
    @Test func aRuleHoldsOnlyTheStagesItNames() {
        let set = Breakpoints()
        set.replace([
            breakpointRule(1, path: "/a", response: true),
            breakpointRule(2, path: "/a", request: true),
            breakpointRule(3, path: "/a", request: true, timeoutMs: 0),
        ])

        #expect(set.match(method: "GET", url: "http://127.0.0.1/a", stage: .request)?.id == 2)
        #expect(set.match(method: "GET", url: "http://127.0.0.1/a?q=1", stage: .response)?.id == 1)
        #expect(set.match(method: "GET", url: "http://127.0.0.1/b", stage: .request) == nil)
    }

    @Test func aHoldOutsideASessionGoesOnAtOnceAndSendsNothing() {
        let outcomes = Outcomes()
        var sent = 0

        _ = Breakpoints().hold(breakpointHit("a", .request), send: { _ in sent += 1 }, done: outcomes.record)

        #expect(sent == 0)
        guard case .resume(nil) = outcomes.all.first else {
            Issue.record("\(outcomes.all)")
            return
        }
    }

    @Test func aResumeEndsTheHoldOnceAndALateOneIsDropped() async {
        let set = Breakpoints()
        set.replace([])
        let outcomes = Outcomes()
        _ = set.hold(breakpointHit("a", .request), send: { _ in }, done: outcomes.record)
        var changed = resume("a", .request)
        changed.method = "PUT"

        set.resume(changed)
        set.resume(changed)
        set.abort(Appmonitor_V1_BreakpointAbort())

        #expect(outcomes.all.count == 1)
        guard case .resume(let changes?) = outcomes.all.first else {
            Issue.record("\(outcomes.all)")
            return
        }
        #expect(changes.method == "PUT")
    }

    @Test func aHoldGoesOnUnchangedAtItsTimeoutOrWhenTheSessionEnds() async {
        let set = Breakpoints()
        set.replace([])
        let timedOut = Outcomes()
        let ended = Outcomes()
        _ = set.hold(breakpointHit("a", .request, timeoutMs: 50), send: { _ in }, done: timedOut.record)
        _ = set.hold(breakpointHit("b", .response), send: { _ in }, done: ended.record)

        #expect(await eventually { timedOut.all.count == 1 })
        #expect(ended.all.isEmpty)
        set.clear()

        guard case .resume(nil) = timedOut.all.first, case .resume(nil) = ended.all.first else {
            Issue.record("\(timedOut.all) \(ended.all)")
            return
        }
        #expect(set.match(method: "GET", url: "http://127.0.0.1/a", stage: .request) == nil)
    }

    @Test func aReleasedHoldIsNeverAnswered() {
        let set = Breakpoints()
        set.replace([])
        let outcomes = Outcomes()
        let release = set.hold(breakpointHit("a", .request), send: { _ in }, done: outcomes.record)

        release()
        set.resume(resume("a", .request))
        set.clear()

        #expect(outcomes.all.isEmpty)
    }

    @Test func headersAreSetAndRemovedByNameIgnoringCase() {
        var changes = resume("a", .request)
        changes.setHeaders = [header("x-one", "new")]
        changes.removedHeaders = ["X-TWO"]

        let headers = changes.applied(to: [("X-One", "old"), ("x-two", "gone"), ("Accept", "*/*")])

        #expect(headers.map(\.name) == ["Accept", "x-one"])
        #expect(headers.map(\.value) == ["*/*", "new"])
    }
}

@Suite(.serialized)
struct MonitorClientBreakpointsTests {
    static func envelope(_ type: String, _ message: some SwiftProtobuf.Message) -> Appmonitor_V1_Envelope {
        interactionEnvelope(type, message)
    }

    @Test func theClientHoldsUntilTheDesktopResumesAndLetsGoWhenTheSessionEnds() async throws {
        let desktop = TestDesktop(
            mode: .ack(channels: [ProtocolConstants.channelCore, ProtocolConstants.channelInteraction]))
        try await desktop.start()
        let client = MonitorClientTests.client(manualDiscovery(desktop.endpoint))
        client.start()
        defer { client.stop() }
        #expect(await eventually { client.state == .connected })
        var rules = Appmonitor_V1_BreakpointRules()
        rules.rules = [breakpointRule(4, path: "/a", request: true)]

        desktop.push(Self.envelope("BreakpointRules", rules))
        #expect(
            await eventually {
                client.breakpoints.match(method: "GET", url: "http://127.0.0.1/a", stage: .request) != nil
            })
        let resumed = Outcomes()
        _ = client.breakpoints.hold(
            breakpointHit("a", .request), send: { client.send(interactionEnvelope("BreakpointHit", $0)) },
            done: resumed.record)
        #expect(await eventually { desktop.events.contains { $0.type == "BreakpointHit" } })
        var changed = resume("a", .request)
        changed.url = "http://127.0.0.1/b"
        desktop.push(Self.envelope("BreakpointResume", changed))
        #expect(await eventually { resumed.all.count == 1 })
        guard case .resume(let changes?) = resumed.all.first else {
            Issue.record("\(resumed.all)")
            return
        }
        #expect(changes.url == "http://127.0.0.1/b")

        let ended = Outcomes()
        _ = client.breakpoints.hold(breakpointHit("b", .response), send: { _ in }, done: ended.record)
        desktop.stop()

        #expect(await eventually { ended.all.count == 1 })
        #expect(client.breakpoints.match(method: "GET", url: "http://127.0.0.1/a", stage: .request) == nil)
    }
}

// Inside the capture suite: the hooks are process-wide, one test at a time owns `URLSessionCapture.sink`.
extension URLSessionCaptureTests {
    func holding(_ rules: Appmonitor_V1_BreakpointRule...) {
        capture()
        sink.breakpoints.replace(rules)
    }

    @Test func aHeldRequestGoesOutEditedAndIsCapturedAsSent() async throws {
        holding(breakpointRule(3, method: "POST", path: "/ok", request: true))
        var request = URLRequest(url: server.url("/ok"))
        request.httpMethod = "POST"
        request.httpBody = Data("first".utf8)
        request.setValue("Bearer secret", forHTTPHeaderField: "Authorization")
        request.setValue("old", forHTTPHeaderField: "X-Edit")

        let answer = Task { try await URLSession.shared.data(for: request) }
        let hit = try await sink.hit(.request)
        #expect(hit.ruleID == 3)
        #expect(hit.request.url == server.url("/ok").absoluteString)
        #expect(hit.request.body == Data("first".utf8))
        #expect(hit.request.headers.contains { $0.name == "Authorization" && $0.value == "██" })
        #expect(try sink.all.filter { $0.type == "RequestStarted" }.isEmpty)
        #expect(server.received.isEmpty)
        var changes = resume(hit.request.requestID, .request)
        changes.url = server.url("/echo").absoluteString
        changes.body = Data("edited".utf8)
        changes.setHeaders = [header("X-Edit", "new")]
        sink.breakpoints.resume(changes)

        let (data, response) = try await answer.value
        #expect((response as? HTTPURLResponse)?.statusCode == 200)
        #expect(String(decoding: data, as: UTF8.self) == "edited")
        let sent = try #require(server.received.first)
        #expect(sent.path == "/echo")
        #expect(sent.headers["authorization"] == "Bearer secret")
        #expect(sent.headers["x-edit"] == "new")
        let (started, end) = try await sink.exchange(server.url("/echo"))
        #expect(started.requestID == hit.request.requestID)
        #expect(started.body == Data("edited".utf8))
        #expect(try received(end).body == Data("edited".utf8))
        #expect(try sink.all.filter { $0.type == "RequestStarted" }.count == 1)
    }

    @Test func aHeldResponseReachesTheAppEditedAndIsCapturedAsRead() async throws {
        holding(breakpointRule(5, path: "/ok", response: true))

        let url = server.url("/ok")
        let answer = Task { try await URLSession.shared.data(from: url) }
        let hit = try await sink.hit(.response)
        #expect(hit.ruleID == 5)
        #expect(hit.response.statusCode == 200)
        #expect(hit.response.body == Data("hello".utf8))
        var changes = resume(hit.request.requestID, .response)
        changes.statusCode = 418
        changes.body = Data("teapot".utf8)
        changes.setHeaders = [header("X-Held", "yes")]
        sink.breakpoints.resume(changes)

        let (data, response) = try await answer.value
        let http = try #require(response as? HTTPURLResponse)
        #expect(http.statusCode == 418)
        #expect(http.value(forHTTPHeaderField: "X-Held") == "yes")
        #expect(String(decoding: data, as: UTF8.self) == "teapot")
        let (_, end) = try await sink.exchange(server.url("/ok"))
        let event = try received(end)
        #expect(event.statusCode == 418)
        #expect(event.body == Data("teapot".utf8))
    }

    @Test func anAbortedRequestFailsWithItsMessageAndIsNeverSent() async throws {
        holding(breakpointRule(6, path: "/ok", request: true))

        let url = server.url("/ok")
        let answer = Task { try await URLSession.shared.data(from: url) }
        let hit = try await sink.hit(.request)
        var abort = Appmonitor_V1_BreakpointAbort()
        abort.requestID = hit.request.requestID
        abort.stage = .request
        abort.message = "stopped at the breakpoint"
        sink.breakpoints.abort(abort)

        let error = try #require(await answer.result.failure as? URLError)
        #expect(error.code == .networkConnectionLost)
        #expect(error.localizedDescription == "stopped at the breakpoint")
        #expect(server.received.isEmpty)
        let (_, end) = try await sink.exchange(server.url("/ok"))
        #expect(end.type == "RequestFailed")
    }

    @Test func aHeldTaskGoesOnUnchangedAtItsTimeoutAndItsDelegateGetsTheData() async throws {
        holding(breakpointRule(7, path: "/ok", request: true, response: true, timeoutMs: 100))
        let delegate = DataDelegate()
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }

        session.dataTask(with: server.url("/ok")).resume()

        #expect(await eventually { delegate.completed })
        #expect(delegate.error == nil)
        #expect(String(decoding: delegate.data, as: UTF8.self) == "hello")
        #expect(server.received.count == 1)
        _ = try await sink.hit(.request)
        _ = try await sink.hit(.response)
        let (_, end) = try await sink.exchange(server.url("/ok"))
        #expect(try received(end).body == Data("hello".utf8))
    }

    @Test func aHeldUploadGoesOutWithItsBody() async throws {
        holding(breakpointRule(12, method: "PUT", path: "/echo", request: true))
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "PUT"
        let body = Data("upload".utf8)

        let answer = Task { [request] in try await URLSession.shared.upload(for: request, from: body) }
        let hit = try await sink.hit(.request)
        #expect(hit.request.body == body)
        sink.breakpoints.resume(resume(hit.request.requestID, .request))

        let (data, _) = try await answer.value
        #expect(data == body)
        #expect(server.received.first?.body == body)
        let (started, _) = try await sink.exchange(server.url("/echo"))
        #expect(started.body == body)
    }

    @Test func aRequestResumedUnchangedIsAnsweredByItsMockAndCapturedWithIt() async throws {
        holding(breakpointRule(8, path: "/ok", request: true))
        sink.rules.replace([mockRule(9, path: "/ok")])

        let url = server.url("/ok")
        let answer = Task { try await URLSession.shared.data(from: url) }
        let hit = try await sink.hit(.request)
        #expect(!hit.request.hasMockRuleID)
        sink.breakpoints.resume(resume(hit.request.requestID, .request))

        let (data, _) = try await answer.value
        #expect(String(decoding: data, as: UTF8.self) == "mocked")
        #expect(server.received.isEmpty)
        let (started, end) = try await sink.exchange(server.url("/ok"))
        #expect(started.mockRuleID == 9)
        #expect(try received(end).statusCode == 201)
    }

    @Test func anEditTheSessionCannotSendFailsTheTask() async throws {
        holding(breakpointRule(10, path: "/ok", request: true))

        let url = server.url("/ok")
        let answer = Task { try await URLSession.shared.data(from: url) }
        let hit = try await sink.hit(.request)
        var changes = resume(hit.request.requestID, .request)
        changes.url = "not a url"
        sink.breakpoints.resume(changes)

        let error = try #require(await answer.result.failure as? URLError)
        #expect(error.localizedDescription.hasPrefix("Breakpoint edit refused"))
        let (started, end) = try await sink.exchange(server.url("/ok"))
        #expect(started.requestID == hit.request.requestID)
        #expect(end.type == "RequestFailed")
    }

    @Test func aHeldTaskTheAppCancelsIsCapturedStartedThenFailed() async throws {
        holding(breakpointRule(11, path: "/ok", request: true))
        let task = URLSession.shared.dataTask(with: server.url("/ok"))

        task.resume()
        let hit = try await sink.hit(.request)
        task.cancel()

        let (started, end) = try await sink.exchange(server.url("/ok"))
        #expect(started.requestID == hit.request.requestID)
        #expect(end.type == "RequestFailed")
        let types = sink.all.map(\.type).filter { $0 != "BreakpointHit" }
        #expect(types == ["RequestStarted", "RequestFailed"])
        #expect(server.received.isEmpty)
    }

    @Test func aHeldTaskWhoseDelegateRejectsAChallengeFailsAsWithoutTheBreakpoint() async throws {
        capture()
        let unheld = AskingDelegate(challenge: .cancelAuthenticationChallenge)
        let plain = URLSession(configuration: .ephemeral, delegate: unheld, delegateQueue: nil)
        defer { plain.invalidateAndCancel() }
        plain.dataTask(with: server.url("/auth")).resume()
        #expect(await eventually { unheld.completed })

        holding(breakpointRule(13, path: "/auth", request: true, timeoutMs: 100))
        let delegate = AskingDelegate(challenge: .cancelAuthenticationChallenge)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: server.url("/auth"))
        task.resume()

        #expect(await eventually { delegate.completed })
        #expect(delegate.challenges == ["NSURLAuthenticationMethodHTTPBasic"])
        #expect(delegate.askedTask === task)
        let error = try #require(delegate.error as? URLError)
        #expect(error.code == (unheld.error as? URLError)?.code)
        _ = try await sink.hit(.request)
    }

    @Test func aHeldTaskSendsTheCredentialItsDelegateGives() async throws {
        holding(breakpointRule(14, path: "/auth", request: true, timeoutMs: 100))
        let delegate = AskingDelegate(challenge: .useCredential)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }

        session.dataTask(with: server.url("/auth")).resume()

        #expect(await eventually { delegate.completed })
        #expect(delegate.error == nil)
        #expect(String(decoding: delegate.data, as: UTF8.self) == "hello")
        #expect(server.received.last?.headers["authorization"] == "Basic dXNlcjpzZWNyZXQ=")
    }

    @Test func aRedirectTheHeldTasksDelegateRefusesIsNotFollowed() async throws {
        holding(breakpointRule(15, path: "/redirect", request: true, timeoutMs: 100))
        let delegate = AskingDelegate(follows: false)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: server.url("/redirect"))

        task.resume()

        #expect(await eventually { delegate.completed })
        #expect(delegate.error == nil)
        #expect(delegate.redirects == [server.url("/ok")])
        #expect(delegate.askedTask === task)
        #expect((delegate.response as? HTTPURLResponse)?.statusCode == 302)
        #expect(String(decoding: delegate.data, as: UTF8.self) == "moved")
        #expect(server.received.map(\.path) == ["/redirect"])
        let (_, end) = try await sink.exchange(server.url("/redirect"))
        #expect(try received(end).statusCode == 302)
    }

    @Test func aRedirectTheHeldTasksDelegateFollowsEndsWithTheFinalResponse() async throws {
        holding(breakpointRule(16, path: "/redirect", request: true, timeoutMs: 100))
        let delegate = AskingDelegate(follows: true)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }

        session.dataTask(with: server.url("/redirect")).resume()

        #expect(await eventually { delegate.completed })
        #expect(delegate.redirects == [server.url("/ok")])
        #expect(String(decoding: delegate.data, as: UTF8.self) == "hello")
        #expect(server.received.map(\.path) == ["/redirect", "/ok"])
    }
}

extension Result {
    var failure: Failure? {
        if case .failure(let error) = self { error } else { nil }
    }
}

/// What a session's delegate got for its one task.
final class DataDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var received = Data()
    private var finished = false
    private var failure: Error?

    var data: Data { lock.withLock { received } }
    var completed: Bool { lock.withLock { finished } }
    var error: Error? { lock.withLock { failure } }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.withLock { received.append(data) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.withLock {
            finished = true
            failure = error
        }
    }
}

/// A session delegate that answers challenges with `challenge` and follows redirects only if `follows`.
final class AskingDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let answer: URLSession.AuthChallengeDisposition
    private let follows: Bool
    private var methods: [String] = []
    private var locations: [URL] = []
    private var asked: URLSessionTask?
    private var received = Data()
    private var answered: URLResponse?
    private var finished = false
    private var failure: Error?

    init(challenge: URLSession.AuthChallengeDisposition = .performDefaultHandling, follows: Bool = true) {
        answer = challenge
        self.follows = follows
    }

    var challenges: [String] { lock.withLock { methods } }
    var redirects: [URL] { lock.withLock { locations } }
    var askedTask: URLSessionTask? { lock.withLock { asked } }
    var data: Data { lock.withLock { received } }
    var response: URLResponse? { lock.withLock { answered } }
    var completed: Bool { lock.withLock { finished } }
    var error: Error? { lock.withLock { failure } }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        lock.withLock {
            methods.append(challenge.protectionSpace.authenticationMethod)
            asked = task
        }
        let credential = URLCredential(user: "user", password: "secret", persistence: .none)
        completionHandler(answer, answer == .useCredential ? credential : nil)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        lock.withLock {
            locations.append(request.url!)
            asked = task
        }
        completionHandler(follows ? request : nil)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void
    ) {
        lock.withLock { answered = response }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.withLock { received.append(data) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.withLock {
            finished = true
            failure = error
        }
    }
}
