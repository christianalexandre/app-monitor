import Foundation
import Testing

@testable import AppMonitor

struct RecentRequestsTests {
    @Test func theOldestIsDroppedPastTheCapacity() {
        let kept = RecentRequests(capacity: 2)
        for id in ["a", "b", "c"] { kept.keep(id) { nil } }

        #expect(kept.replay("a") == RecentRequests.notKept)
        #expect(kept.replay("b") == nil)
        #expect(kept.replay("c") == nil)
    }

    @Test func bodiesPastTheByteBoundLetTheOldestGo() {
        let kept = RecentRequests(maxBytes: 10)
        kept.keep("a", bodyBytes: 6) { nil }
        kept.keep("b", bodyBytes: 4) { nil }
        kept.keep("a", bodyBytes: 1) { nil }
        kept.keep("c", bodyBytes: 5) { nil }
        #expect(kept.replay("b") == nil)

        kept.keep("d", bodyBytes: 1) { nil }

        #expect(kept.replay("b") == RecentRequests.notKept)
        #expect(kept.replay("a") == nil)
        #expect(kept.replay("c") == nil)
        #expect(kept.replay("d") == nil)
    }

    @Test func theNewestStaysHoweverLargeItsBody() {
        let kept = RecentRequests(maxBytes: 10)
        kept.keep("a", bodyBytes: 1) { nil }

        kept.keep("large", bodyBytes: 100) { nil }

        #expect(kept.replay("a") == RecentRequests.notKept)
        #expect(kept.replay("large") == nil)
    }

    @Test func onlyABodyInMemoryCounts() {
        let request = URLRequest(url: URL(string: "http://h/")!)
        var withBody = request
        withBody.httpBody = Data(count: 3)
        #expect(Replay.bodyBytes(request: withBody, upload: nil) == 3)
        #expect(Replay.bodyBytes(request: request, upload: .data(Data(count: 5))) == 5)
        #expect(Replay.bodyBytes(request: request, upload: .file(URL(fileURLWithPath: "/tmp/x"))) == 0)
    }

    @Test func aClearedSetKeepsNothing() {
        let kept = RecentRequests()
        kept.keep("a") { nil }

        kept.clear()

        #expect(kept.replay("a") == RecentRequests.notKept)
    }

    @Test func aRequestNotKeptIsRefusedOnTheWire() async throws {
        let desktop = TestDesktop(
            mode: .ack(channels: [ProtocolConstants.channelCore, ProtocolConstants.channelInteraction]))
        try await desktop.start()
        let client = MonitorClientTests.client(manualDiscovery(desktop.endpoint))
        client.start()
        defer { client.stop() }
        #expect(await eventually { client.state == .connected })
        var request = Appmonitor_V1_ReplayRequest()
        request.requestID = "gone"
        var envelope = Appmonitor_V1_Envelope()
        envelope.channel = ProtocolConstants.channelInteraction
        envelope.type = "ReplayRequest"
        envelope.payload = try request.serializedBytes()

        desktop.push(envelope)

        #expect(await eventually { desktop.events.contains { $0.type == "ReplayRefused" } })
        let refused = try Appmonitor_V1_ReplayRefused(
            serializedBytes: try #require(desktop.events.first { $0.type == "ReplayRefused" }).payload)
        #expect(refused.requestID == "gone")
        #expect(refused.reason == RecentRequests.notKept)
    }
}

// Inside the capture suite: the hooks are process-wide, one test at a time owns `URLSessionCapture.sink`.
extension ProcessWideHooks.URLSessionCaptureTests {
    /// The `RequestStarted` events sent so far for `url`.
    func starts(_ url: URL) throws -> [Appmonitor_V1_RequestStarted] {
        try sink.all.filter { $0.type == "RequestStarted" }
            .map { try Appmonitor_V1_RequestStarted(serializedBytes: $0.payload) }
            .filter { $0.url == url.absoluteString }
    }

    /// The replay of `original`'s request, and the event that ended it, waited for up to 5 s.
    func replayed(_ original: String) async throws -> (Appmonitor_V1_RequestStarted, Appmonitor_V1_Envelope) {
        for _ in 0..<100 {
            let events = sink.all
            if let started = try events.filter({ $0.type == "RequestStarted" })
                .map({ try Appmonitor_V1_RequestStarted(serializedBytes: $0.payload) })
                .first(where: { $0.replayOf == original }),
                let end = try events.filter({ $0.type == "ResponseReceived" }).first(where: {
                    try Appmonitor_V1_ResponseReceived(serializedBytes: $0.payload).requestID == started.requestID
                })
            {
                return (started, end)
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw CaptureTestError.noExchange("replay of \(original)")
    }

    @Test func aTaskIsSentAgainThroughItsSessionAndCapturedAsAReplay() async throws {
        capture()
        let delegate = CountingDelegate()
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "POST"
        request.httpBody = Data("again".utf8)
        request.setValue("Bearer secret", forHTTPHeaderField: "Authorization")
        session.dataTask(with: request).resume()
        let (original, _) = try await sink.exchange(server.url("/echo"))
        #expect(await eventually { delegate.tasks == 1 })

        #expect(sink.replays.replay(original.requestID) == nil)

        let (started, end) = try await replayed(original.requestID)
        #expect(started.method == "POST")
        #expect(started.body == Data("again".utf8))
        #expect(try received(end).body == Data("again".utf8))
        #expect(server.received.count == 2)
        #expect(server.received.last?.headers["authorization"] == "Bearer secret")
        #expect(server.received.last?.body == Data("again".utf8))
        #expect(!original.hasReplayOf)
        try await Task.sleep(nanoseconds: 100_000_000)
        #expect(delegate.tasks == 1)
    }

    @Test func anUploadIsSentAgainWithItsBody() async throws {
        capture()
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "PUT"
        _ = try await URLSession.shared.upload(for: request, from: Data("uploaded".utf8))
        let (original, _) = try await sink.exchange(server.url("/echo"))

        #expect(sink.replays.replay(original.requestID) == nil)

        let (started, _) = try await replayed(original.requestID)
        #expect(started.body == Data("uploaded".utf8))
        #expect(server.received.last?.method == "PUT")
        #expect(server.received.last?.body == Data("uploaded".utf8))
    }

    @Test func aTaskOfAnInvalidatedSessionIsRefused() async throws {
        capture()
        let session = URLSession(configuration: .ephemeral)
        _ = try await session.data(from: server.url("/ok"))
        let (original, _) = try await sink.exchange(server.url("/ok"))

        session.finishTasksAndInvalidate()

        #expect(sink.replays.replay(original.requestID) == "The app's session was invalidated")
        #expect(server.received.count == 1)
    }

    // ADR-0186: a raise past the catch ends the test process.
    @Test func aReplayRacingTheAppInvalidatingItsSessionEndsTheAppNever() async throws {
        capture()
        for _ in 0..<100 {
            let session = URLSession(configuration: .ephemeral)
            let task = session.dataTask(with: server.url("/ok"))
            let replay = Replay.replayer(of: task, id: "a", request: task.originalRequest, upload: nil)
            DispatchQueue.concurrentPerform(iterations: 2) { side in
                if side == 0 {
                    session.invalidateAndCancel()
                } else {
                    let refusal = replay()
                    #expect(refusal == nil || refusal == "The app's session was invalidated")
                }
            }
        }
    }

    @Test func aStreamedUploadIsRefused() async throws {
        capture()
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "POST"
        request.httpBodyStream = InputStream(data: Data("streamed".utf8))
        _ = try await URLSession.shared.data(for: request)
        let (original, _) = try await sink.exchange(server.url("/echo"))

        #expect(sink.replays.replay(original.requestID) == "Its body was a stream, which the app reads once")
        #expect(try starts(server.url("/echo")).count == 1)
    }
}

/// Counts the tasks whose completion reaches the session's delegate.
final class CountingDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var completed = 0

    var tasks: Int { lock.withLock { completed } }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.withLock { completed += 1 }
    }
}
