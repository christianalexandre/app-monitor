import Foundation
import Testing

@testable import AppMonitor

/// Records envelopes as the running monitor would buffer them; `options` nil plays "not started".
final class Sink: CaptureSink, @unchecked Sendable {
    private let lock = NSLock()
    private var envelopes: [Appmonitor_V1_Envelope] = []
    let options: AppMonitorOptions?

    init(_ options: AppMonitorOptions?) { self.options = options }

    func send(_ envelope: Appmonitor_V1_Envelope) {
        lock.withLock { envelopes.append(envelope) }
    }

    var all: [Appmonitor_V1_Envelope] { lock.withLock { envelopes } }

    /// The rules a session would hold; a background task must never be answered from them (ADR-0110 D.1).
    let rules = MockRuleSet()

    func mock(method: String, url: String) -> Appmonitor_V1_MockRule? {
        rules.match(method: method, url: url)
    }

    /// What `keepForReplay` got; a background task is never sent again (ADR-0124 C.1).
    let replays = RecentRequests()

    func keepForReplay(_ requestID: String, _ replay: @escaping @Sendable () -> String?) {
        replays.keep(requestID, replay)
    }

    /// The breakpoints a session would hold; a background task is never held (ADR-0125).
    let breakpoints = Breakpoints()

    func breakpoint(method: String, url: String, stage: Appmonitor_V1_BreakpointStage) -> Appmonitor_V1_BreakpointRule?
    {
        breakpoints.match(method: method, url: url, stage: stage)
    }

    func hold(_ hit: Appmonitor_V1_BreakpointHit, _ done: @escaping @Sendable (BreakpointOutcome) -> Void)
        -> @Sendable () -> Void
    {
        breakpoints.hold(hit, send: { send(interactionEnvelope("BreakpointHit", $0)) }, done: done)
    }

    func started() throws -> Appmonitor_V1_RequestStarted {
        try Appmonitor_V1_RequestStarted(serializedBytes: #require(all.first { $0.type == "RequestStarted" }).payload)
    }

    func response() throws -> Appmonitor_V1_ResponseReceived {
        try Appmonitor_V1_ResponseReceived(
            serializedBytes: #require(all.first { $0.type == "ResponseReceived" }).payload)
    }
}

/// The app's delegate: records every callback, and the downloaded file as the app reads it.
final class AppDelegate: NSObject, URLSessionDownloadDelegate, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var names: [String] = []
    private var finished: CheckedContinuation<Void, Never>?
    private(set) var file = Data()
    private(set) var received = Data()

    var callbacks: [String] { lock.withLock { names } }

    private func record(_ name: String) { lock.withLock { names.append(name) } }

    func wait(_ start: () -> Void) async {
        await withCheckedContinuation { continuation in
            lock.withLock { finished = continuation }
            start()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        file = (try? Data(contentsOf: location)) ?? Data()
        record("didFinishDownloadingTo")
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        record("didWriteData")
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        received += data
        record("didReceiveData")
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        record("didSendBodyData")
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        record("didFinishCollecting")
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        record(error == nil ? "didComplete" : "didCompleteWithError")
        lock.withLock { finished }?.resume()
    }
}

// The hooks are process-wide: one test at a time owns `URLSessionCapture.sink`.
@Suite(.serialized)
final class BackgroundSessionTests {
    let server = TestHTTPServer()
    let delegate = AppDelegate()
    let session: URLSession

    init() async throws {
        try await server.start()
        let configuration = URLSessionConfiguration.background(withIdentifier: "test.\(UUID().uuidString)")
        session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    deinit {
        session.invalidateAndCancel()
        URLSessionCapture.sink = monitor
        server.stop()
    }

    func capture(_ options: AppMonitorOptions? = AppMonitorOptions()) -> Sink {
        let sink = Sink(options)
        URLSessionCapture.sink = sink
        #expect(URLSessionCapture.install())
        return sink
    }

    func run(_ task: URLSessionTask) async {
        await delegate.wait { task.resume() }
    }

    @Test func aDownloadIsCapturedAndTheAppGetsItsFile() async throws {
        let sink = capture()
        var request = URLRequest(url: server.url("/ok"))
        request.setValue("Bearer t0k", forHTTPHeaderField: "Authorization")

        await run(session.downloadTask(with: request))

        let started = try sink.started()
        #expect(started.url == server.url("/ok").absoluteString)
        #expect(started.captureLayer == .platform)
        #expect(sink.replays.replay(started.requestID) == "A background session's task is not sent again")
        #expect(started.headers.contains { $0.name == "Authorization" && $0.value == "██" })
        let response = try sink.response()
        #expect(response.requestID == started.requestID)
        #expect(response.statusCode == 200)
        #expect(String(decoding: response.body, as: UTF8.self) == "hello")
        #expect(response.bodySize == 5 && !response.bodyTruncated)
        #expect(response.headers.contains { $0.name == "Set-Cookie" && $0.value == "██" })
        #expect(String(decoding: delegate.file, as: UTF8.self) == "hello")
        #expect(delegate.callbacks == ["didWriteData", "didFinishDownloadingTo", "didFinishCollecting", "didComplete"])
    }

    @Test func aDownloadOverTheLimitIsCut() async throws {
        let sink = capture(AppMonitorOptions(maxBodyBytes: 100))

        await run(session.downloadTask(with: server.url("/big/3000")))

        let response = try sink.response()
        #expect(response.body.count == 100)
        #expect(response.bodyTruncated && response.bodySize == 3000)
        #expect(delegate.file.count == 3000)
    }

    @Test func aServerErrorIsAResponse() async throws {
        let sink = capture()

        await run(session.downloadTask(with: server.url("/error")))

        #expect(try sink.response().statusCode == 500)
        #expect(String(decoding: try sink.response().body, as: UTF8.self) == "boom")
    }

    @Test func aFileUploadIsCapturedBothWays() async throws {
        let sink = capture()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("payload".utf8).write(to: file)
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "POST"

        await run(session.uploadTask(with: request, fromFile: file))

        let started = try sink.started()
        #expect(started.method == "POST")
        #expect(String(decoding: started.body, as: UTF8.self) == "payload" && started.bodySize == 7)
        let response = try sink.response()
        #expect(String(decoding: response.body, as: UTF8.self) == "payload" && response.bodySize == 7)
        #expect(String(decoding: delegate.received, as: UTF8.self) == "payload")
        #expect(delegate.callbacks.suffix(3) == ["didReceiveData", "didFinishCollecting", "didComplete"])
    }

    @Test func aFileUploadAMockRuleOrBreakpointMatchesStillGoesToTheNetwork() async throws {
        let sink = capture()
        var rule = Appmonitor_V1_MockRule()
        rule.id = 1
        rule.host = "127.0.0.1"
        rule.path = "/echo"
        rule.failure = Appmonitor_V1_MockFailure()
        sink.rules.replace([rule])
        var breakpoint = Appmonitor_V1_BreakpointRule()
        breakpoint.host = "127.0.0.1"
        breakpoint.path = "/echo"
        breakpoint.request = true
        breakpoint.response = true
        breakpoint.timeoutMs = 60_000
        sink.breakpoints.replace([breakpoint])
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("payload".utf8).write(to: file)
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "POST"

        await run(session.uploadTask(with: request, fromFile: file))

        #expect(try !sink.started().hasMockRuleID)
        #expect(!sink.all.contains { $0.type == "BreakpointHit" })
        #expect(String(decoding: delegate.received, as: UTF8.self) == "payload")
        #expect(delegate.callbacks.last == "didComplete")
    }

    @Test func nothingIsCapturedBeforeStart() async throws {
        let sink = capture(nil)

        await run(session.downloadTask(with: server.url("/ok")))

        #expect(sink.all.isEmpty)
        #expect(String(decoding: delegate.file, as: UTF8.self) == "hello")
    }
}
