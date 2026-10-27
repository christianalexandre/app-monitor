import Foundation
import Testing

@testable import AppMonitor

extension RecordingSink {
    /// The first `count` frames of `requestID`, waited for up to 5 s.
    func frames(_ requestID: String, count: Int) async throws -> [Appmonitor_V1_WebSocketFrame] {
        for _ in 0..<100 {
            let frames = try all.filter { $0.type == "WebSocketFrame" }
                .map { try Appmonitor_V1_WebSocketFrame(serializedBytes: $0.payload) }
                .filter { $0.requestID == requestID }
            if frames.count >= count { return Array(frames.prefix(count)) }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw CaptureTestError.noExchange(requestID)
    }
}

func frame(
    _ direction: Appmonitor_V1_WebSocketFrame.Direction,
    _ opcode: Appmonitor_V1_WebSocketFrame.Opcode,
    _ payload: Data
) -> [String] {
    ["\(direction)", "\(opcode)", payload.base64EncodedString()]
}

func flat(_ frames: [Appmonitor_V1_WebSocketFrame]) -> [[String]] {
    frames.map { frame($0.direction, $0.opcode, $0.payload) }
}

// In the serialized suite of `URLSessionCaptureTests`: the hooks are process-wide.
extension ProcessWideHooks.URLSessionCaptureTests {
    @Test func textAndBinaryFramesAreCapturedBothWays() async throws {
        let echo = TestEchoServer()
        try await echo.start()
        defer { echo.stop() }
        capture(AppMonitorOptions(maskedQueryParameters: ["token"]))
        let task = URLSession.shared.webSocketTask(with: echo.url("/socket?token=s3cret"))
        task.resume()

        try await task.send(.string("hi"))
        let text = try await task.receive()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            task.send(.data(Data([1, 2, 3]))) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
        let binary = await withCheckedContinuation { continuation in
            task.receive { continuation.resume(returning: try? $0.get()) }
        }
        task.cancel(with: .goingAway, reason: Data("done".utf8))

        let (started, end) = try await sink.exchange("http://127.0.0.1:\(echo.port)/socket?token=\(mask)")
        #expect(started.method == "GET" && started.captureLayer == .platform)
        #expect(try received(end).statusCode == 101)
        let frames = try await sink.frames(started.requestID, count: 5)
        #expect(
            flat(frames) == [
                frame(.sent, .text, Data("hi".utf8)), frame(.received, .text, Data("hi".utf8)),
                frame(.sent, .binary, Data([1, 2, 3])), frame(.received, .binary, Data([1, 2, 3])),
                frame(.sent, .close, Data([0x03, 0xE9]) + Data("done".utf8)),
            ])
        #expect(frames.allSatisfy { !$0.payloadTruncated && $0.payloadSize == Int64($0.payload.count) })
        guard case .string("hi") = text, case .data(let bytes) = binary else {
            Issue.record("the app got \(text) and \(String(describing: binary))")
            return
        }
        #expect(bytes == Data([1, 2, 3]))
        #expect(echo.received == [Data("hi".utf8), Data([1, 2, 3])])
    }

    @Test func aWebSocketMarkedBySdkKtorIsNotCapturedAndAnUnmarkedRequestIs() async throws {
        let echo = TestEchoServer()
        try await echo.start()
        defer { echo.stop() }
        capture()
        var request = URLRequest(url: echo.url("/marked"))
        request.setValue("ktor", forHTTPHeaderField: "X-AppMonitor-Capture")
        for request in [request, URLRequest(url: echo.url("/plain"))] {
            let task = URLSession.shared.webSocketTask(with: request)
            task.resume()
            try await task.send(.string("hi"))
            _ = try await task.receive()
            task.cancel(with: .goingAway, reason: nil)
        }

        let (started, _) = try await sink.exchange(echo.httpURL("/plain"))
        let captured = try sink.all.filter { $0.type == "WebSocketFrame" }
            .map { try Appmonitor_V1_WebSocketFrame(serializedBytes: $0.payload).requestID }
        #expect(Set(captured) == [started.requestID])
        #expect(sink.all.filter { $0.type == "RequestStarted" }.count == 1)
    }

    @Test func aFrameOverTheLimitIsCutAndSentWhole() async throws {
        let echo = TestEchoServer()
        try await echo.start()
        defer { echo.stop() }
        capture(AppMonitorOptions(maxBodyBytes: 4))
        let task = URLSession.shared.webSocketTask(with: echo.url("/big"))
        task.resume()
        defer { task.cancel() }

        try await task.send(.string("0123456789"))
        let reply = try await task.receive()

        let (started, _) = try await sink.exchange(echo.httpURL("/big"))
        let frames = try await sink.frames(started.requestID, count: 2)
        #expect(flat(frames) == [frame(.sent, .text, Data("0123".utf8)), frame(.received, .text, Data("0123".utf8))])
        #expect(frames.allSatisfy { $0.payloadTruncated && $0.payloadSize == 10 })
        guard case .string(let whole) = reply else {
            Issue.record("the app got \(reply)")
            return
        }
        #expect(whole == "0123456789")
        #expect(echo.received == [Data("0123456789".utf8)])
    }

    @Test func thePeersCloseIsAReceivedCloseFrame() async throws {
        let echo = TestEchoServer()
        try await echo.start()
        defer { echo.stop() }
        capture()
        let task = URLSession.shared.webSocketTask(with: echo.url("/close"))
        task.resume()

        try await task.send(.string("close"))
        await #expect(throws: (any Error).self) { _ = try await task.receive() }

        let (started, _) = try await sink.exchange(echo.httpURL("/close"))
        let frames = try await sink.frames(started.requestID, count: 2)
        #expect(
            flat(frames) == [
                frame(.sent, .text, Data("close".utf8)),
                frame(.received, .close, Data([0x0F, 0xA0]) + Data("bye".utf8)),
            ])
        #expect(task.closeCode.rawValue == 4000)
    }

    @Test func aRefusedConnectionIsAFailedUpgrade() async throws {
        capture()
        let url = URL(string: "ws://127.0.0.1:1/refused")!
        let task = URLSession.shared.webSocketTask(with: url)
        task.resume()

        await #expect(throws: (any Error).self) { _ = try await task.receive() }

        let (_, end) = try await sink.exchange("http://127.0.0.1:1/refused")
        #expect(end.type == "RequestFailed")
        #expect(try Appmonitor_V1_RequestFailed(serializedBytes: end.payload).error.hasPrefix("NSURLErrorDomain -1004"))
    }

    @Test func aRejectedUpgradeIsItsResponse() async throws {
        capture()
        let url = URL(string: "ws://127.0.0.1:\(server.port)/missing")!
        let task = URLSession.shared.webSocketTask(with: url)
        task.resume()

        await #expect(throws: (any Error).self) { _ = try await task.receive() }

        let (_, end) = try await sink.exchange(server.url("/missing"))
        #expect(try received(end).statusCode == 404)
        #expect(sink.all.filter { $0.type == "RequestFailed" }.isEmpty)
    }
}
