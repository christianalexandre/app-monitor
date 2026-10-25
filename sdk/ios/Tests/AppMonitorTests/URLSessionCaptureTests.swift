import Foundation
import Testing

@testable import AppMonitor

func received(_ envelope: Appmonitor_V1_Envelope) throws -> Appmonitor_V1_ResponseReceived {
    guard envelope.type == "ResponseReceived" else { throw CaptureTestError.wrongEvent(envelope.type) }
    return try Appmonitor_V1_ResponseReceived(serializedBytes: envelope.payload)
}

/// Hands `uploadTask(withStreamedRequest:)` its body.
final class StreamDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    let body: Data
    init(_ body: Data) { self.body = body }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        needNewBodyStream completionHandler: @escaping @Sendable (InputStream?) -> Void
    ) {
        completionHandler(InputStream(data: body))
    }
}

// The hooks are process-wide: one test at a time owns `URLSessionCapture.sink`.
@Suite(.serialized)
final class URLSessionCaptureTests {
    let server = TestHTTPServer()
    let sink = RecordingSink()

    init() async throws {
        try await server.start()
    }

    deinit {
        URLSessionCapture.sink = monitor
        server.stop()
    }

    func capture(_ options: AppMonitorOptions? = AppMonitorOptions()) {
        sink.options = options
        URLSessionCapture.sink = sink
        #expect(URLSessionCapture.install())
    }

    @Test func aSessionCreatedBeforeStartIsCaptured() async throws {
        let session = URLSession(configuration: .ephemeral)
        let early = session.dataTask(with: server.url("/ok"))
        capture()

        early.resume()
        let (data, _) = try await session.data(from: server.url("/ok?second"))

        _ = try await sink.exchange(server.url("/ok"))
        _ = try await sink.exchange(server.url("/ok?second"))
        #expect(String(decoding: data, as: UTF8.self) == "hello")
    }

    @Test func nothingIsCapturedBeforeStart() async throws {
        capture(nil)

        _ = try await URLSession.shared.data(from: server.url("/ok"))

        #expect(sink.all.isEmpty)
    }

    @Test func aRequestMarkedBySdkKtorIsNotCapturedAndAnUnmarkedOneIs() async throws {
        capture()
        var marked = URLRequest(url: server.url("/ok?marked"))
        marked.setValue("ktor", forHTTPHeaderField: "x-appmonitor-capture")

        let (data, _) = try await URLSession.shared.data(for: marked)
        _ = try await URLSession.shared.data(from: server.url("/ok?plain"))

        _ = try await sink.exchange(server.url("/ok?plain"))
        let urls = try sink.all.filter { $0.type == "RequestStarted" }
            .map { try Appmonitor_V1_RequestStarted(serializedBytes: $0.payload).url }
        #expect(urls == [server.url("/ok?plain").absoluteString])
        #expect(String(decoding: data, as: UTF8.self) == "hello")
    }

    @Test func aSuccessIsCapturedWithItsBodyAndMaskedHeaders() async throws {
        capture(AppMonitorOptions(maskedHeaders: ["x-api-key"]))
        var request = URLRequest(url: server.url("/ok"))
        request.setValue("Bearer t0k", forHTTPHeaderField: "Authorization")
        request.setValue("k3y", forHTTPHeaderField: "X-Api-Key")
        request.setValue("1", forHTTPHeaderField: "X-Trace")

        let (data, _) = try await URLSession.shared.data(for: request)

        let (started, end) = try await sink.exchange(server.url("/ok"))
        #expect(started.method == "GET")
        #expect(started.captureLayer == .platform)
        #expect(started.headers.contains { $0.name == "Authorization" && $0.value == mask })
        #expect(started.headers.contains { $0.name == "X-Api-Key" && $0.value == mask })
        #expect(started.headers.contains { $0.name == "X-Trace" && $0.value == "1" })
        #expect(!started.hasBodySize && !started.bodyTruncated)
        let response = try received(end)
        #expect(response.requestID == started.requestID)
        #expect(response.statusCode == 200)
        #expect(response.body == Data("hello".utf8) && response.bodySize == 5 && !response.bodyTruncated)
        #expect(response.headers.contains { $0.name == "Set-Cookie" && $0.value == mask })
        #expect(String(decoding: data, as: UTF8.self) == "hello")
        #expect(server.received.first?.headers["authorization"] == "Bearer t0k")
    }

    @Test func sessionHeadersAreCapturedAndMasked() async throws {
        capture()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpAdditionalHeaders = ["Authorization": "Bearer t0k", "X-Extra": "1"]
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }

        _ = try await session.data(from: server.url("/ok"))

        let (started, _) = try await sink.exchange(server.url("/ok"))
        #expect(started.headers.map { [$0.name, $0.value] } == [["Authorization", mask], ["X-Extra", "1"]])
    }

    @Test func aFollowedRedirectEndsWithTheFinalResponse() async throws {
        capture()

        _ = try await URLSession.shared.data(from: server.url("/redirect"))

        let response = try received(try await sink.exchange(server.url("/redirect")).1)
        #expect(response.statusCode == 200 && response.body == Data("hello".utf8) && response.bodySize == 5)
        #expect(sink.all.filter { $0.type == "ResponseReceived" }.count == 1)
    }

    @Test func aServerErrorIsAResponse() async throws {
        capture()

        let completed = await withCheckedContinuation { continuation in
            URLSession.shared.dataTask(with: server.url("/error")) { data, response, _ in
                continuation.resume(returning: (data, (response as? HTTPURLResponse)?.statusCode))
            }.resume()
        }

        let response = try received(try await sink.exchange(server.url("/error")).1)
        #expect(response.statusCode == 500)
        #expect(response.body == Data("boom".utf8))
        #expect(completed.0 == Data("boom".utf8) && completed.1 == 500)
    }

    @Test func aTimeoutIsAFailureWithoutTheURL() async throws {
        capture(AppMonitorOptions(maskedQueryParameters: ["token"]))
        var request = URLRequest(url: server.url("/hang?token=s3cret"))
        request.timeoutInterval = 0.5

        await #expect(throws: URLError.self) { _ = try await URLSession.shared.data(for: request) }

        let (started, end) = try await sink.exchange("\(server.url("/hang").absoluteString)?token=\(mask)")
        #expect(end.type == "RequestFailed")
        let failed = try Appmonitor_V1_RequestFailed(serializedBytes: end.payload)
        #expect(failed.requestID == started.requestID)
        #expect(failed.error.hasPrefix("NSURLErrorDomain -1001"))
        #expect(!failed.error.contains("s3cret"))
    }

    @Test func aBodyOverTheLimitIsCutAndTheAppGetsItWhole() async throws {
        capture(AppMonitorOptions(maxBodyBytes: 1000))

        let (data, _) = try await URLSession.shared.data(from: server.url("/big/300000"))

        let response = try received(try await sink.exchange(server.url("/big/300000")).1)
        #expect(response.body.count == 1000 && response.bodyTruncated)
        #expect(response.bodySize == 300_000)
        #expect(data.count == 300_000)
    }

    @Test func aDataUploadIsCaptured() async throws {
        capture()
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "POST"
        let body = Data("payload".utf8)

        let (data, _) = try await URLSession.shared.upload(for: request, from: body)
        let viaHandler = await withCheckedContinuation { continuation in
            URLSession.shared.uploadTask(with: request, from: body) { data, _, _ in continuation.resume(returning: data)
            }
            .resume()
        }

        let starts = try sink.all.filter { $0.type == "RequestStarted" }
            .map { try Appmonitor_V1_RequestStarted(serializedBytes: $0.payload) }
        #expect(starts.count == 2)
        #expect(starts.allSatisfy { $0.method == "POST" && $0.body == body && $0.bodySize == 7 && !$0.bodyTruncated })
        #expect(data == body && viaHandler == body)
        #expect(server.received.map(\.body) == [body, body])
    }

    @Test func aRequestBodyOverTheLimitIsCutAndSentWhole() async throws {
        capture(AppMonitorOptions(maxBodyBytes: 4))
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "PUT"
        request.httpBody = Data("payload".utf8)

        _ = try await URLSession.shared.data(for: request)

        let (started, _) = try await sink.exchange(server.url("/echo"))
        #expect(started.body == Data("payl".utf8) && started.bodyTruncated && started.bodySize == 7)
        #expect(server.received.first?.body == Data("payload".utf8))
    }

    @Test func aFileUploadIsReadUpToTheLimit() async throws {
        capture(AppMonitorOptions(maxBodyBytes: 4))
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("appmonitor-\(UUID().uuidString)")
        try Data("file body".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "POST"

        let (data, _) = try await URLSession.shared.upload(for: request, fromFile: file)

        let (started, _) = try await sink.exchange(server.url("/echo"))
        #expect(started.body == Data("file".utf8) && started.bodyTruncated && started.bodySize == 9)
        #expect(data == Data("file body".utf8))
    }

    @Test func aStreamUploadIsNeverRead() async throws {
        capture()
        let body = Data("streamed".utf8)
        let session = URLSession(configuration: .ephemeral, delegate: StreamDelegate(body), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: server.url("/echo"))
        request.httpMethod = "POST"
        var viaStream = request
        viaStream.url = server.url("/echo?stream")
        viaStream.httpBodyStream = InputStream(data: body)

        let streamed = await withCheckedContinuation { continuation in
            let task = session.uploadTask(withStreamedRequest: request)
            Task {
                while task.state != .completed { try? await Task.sleep(for: .milliseconds(20)) }
                continuation.resume(returning: task.error == nil)
            }
            task.resume()
        }
        let (echoed, _) = try await session.data(for: viaStream)

        for url in [server.url("/echo"), server.url("/echo?stream")] {
            let (started, end) = try await sink.exchange(url)
            #expect(started.body.isEmpty && started.bodyTruncated && !started.hasBodySize, "\(url)")
            #expect(try received(end).body == body, "\(url)")
        }
        #expect(streamed && echoed == body)
        #expect(server.received.map(\.body) == [body, body])
    }
}

struct MaskingTests {
    struct Vector: Decodable {
        struct Case: Decodable {
            let name: String
            let maskedHeaders: Set<String>?
            let maskedQueryParameters: Set<String>?
            let masking: Bool?
            let url: String
            let maskedUrl: String
            let requestHeaders: [[String]]?
            let maskedRequestHeaders: [[String]]?
            let responseHeaders: [[String]]?
            let maskedResponseHeaders: [[String]]?
        }

        let cases: [Case]
    }

    static let cases: [Vector.Case] = {
        let data = try! FixtureTests.fixture("masking.json")
        return try! JSONDecoder().decode(Vector.self, from: data).cases
    }()

    // The same file is read by the OkHttp MaskingVectorTest (ADR-0036 A).
    @Test(arguments: cases.map(\.name))
    func caseMasksAsTheVectorSays(name: String) throws {
        let vector = try #require(Self.cases.first { $0.name == name })
        let masking = Masking(
            AppMonitorOptions(
                maskedHeaders: vector.maskedHeaders ?? [],
                maskedQueryParameters: vector.maskedQueryParameters ?? [],
                masking: vector.masking ?? true
            )
        )
        let pairs = { (headers: [[String]]?) in (headers ?? []).map { (name: $0[0], value: $0[1]) } }
        let flat = { (headers: [Appmonitor_V1_Header]) in headers.map { [$0.name, $0.value] } }
        #expect(masking.url(vector.url) == vector.maskedUrl)
        #expect(flat(masking.headers(pairs(vector.requestHeaders))) == vector.maskedRequestHeaders ?? [])
        let base = URL(string: vector.url)
        #expect(flat(masking.headers(pairs(vector.responseHeaders), base: base)) == vector.maskedResponseHeaders ?? [])
    }
}
