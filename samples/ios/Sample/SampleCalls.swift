import Foundation

struct SampleButton: Sendable {
    let label: String
    let call: @Sendable () async -> String
}

/// The calls behind the sample's buttons, against public services the sample does not control;
/// `-target URL` at launch replaces them, as `make e2e-ios` does (ADR-0074).
enum SampleCalls {
    static let buttons = [
        SampleButton(label: "GET JSON") { await data(URLRequest(url: httpbin("json"))) },
        SampleButton(label: "POST") { await post() },
        SampleButton(label: "404") { await data(URLRequest(url: httpbin("status/404"))) },
        SampleButton(label: "Timeout") { await timeout() },
        SampleButton(label: "Upload") { await upload() },
        SampleButton(label: "WebSocket echo") { await webSocketEcho() },
        SampleButton(label: "Background download") { await BackgroundDownload.shared.run() },
    ]

    private static let target = UserDefaults.standard.string(forKey: "target")

    static func httpbin(_ path: String) -> URL {
        URL(string: "\(target ?? "https://httpbin.org")/\(path)")!
    }

    private static var echo: URL {
        URL(string: target.map { "ws\($0.dropFirst(4))/ws" } ?? "wss://echo.websocket.org")!
    }

    private static func data(_ request: URLRequest, session: URLSession = .shared) async -> String {
        do {
            let (body, response) = try await session.data(for: request)
            return "\((response as? HTTPURLResponse)?.statusCode ?? 0), \(body.count) bytes"
        } catch {
            return "failed: \(error.localizedDescription)"
        }
    }

    private static func post() async -> String {
        var request = URLRequest(url: httpbin("post"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(#"{"hello":"desktop"}"#.utf8)
        return await data(request)
    }

    private static func timeout() async -> String {
        var request = URLRequest(url: httpbin("delay/10"))
        request.timeoutInterval = 2
        return await data(request)
    }

    private static func upload() async -> String {
        var request = URLRequest(url: httpbin("put"))
        request.httpMethod = "PUT"
        request.setValue("text/plain", forHTTPHeaderField: "Content-Type")
        do {
            let (body, response) = try await URLSession.shared.upload(
                for: request, from: Data(String(repeating: "upload from the sample\n", count: 100).utf8))
            return "\((response as? HTTPURLResponse)?.statusCode ?? 0), \(body.count) bytes"
        } catch {
            return "failed: \(error.localizedDescription)"
        }
    }

    /// echo.websocket.org greets first, so frames are read until the echo comes back.
    private static func webSocketEcho() async -> String {
        let message = "hello from the sample"
        let task = URLSession.shared.webSocketTask(with: echo)
        task.resume()
        defer { task.cancel(with: .normalClosure, reason: nil) }
        do {
            try await task.send(.string(message))
            for _ in 0..<5 {
                if case .string(message) = try await task.receive() { return "echoed \"\(message)\"" }
            }
            return "no echo"
        } catch {
            return "WebSocket failed: \(error.localizedDescription)"
        }
    }
}

/// One background session for the process: iOS allows a single session per identifier.
final class BackgroundDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    static let shared = BackgroundDownload()

    private let lock = NSLock()
    private var waiting: CheckedContinuation<String, Never>?
    private var bytes = 0
    private lazy var session = URLSession(
        configuration: .background(withIdentifier: "dev.appmonitor.sample.background"),
        delegate: self, delegateQueue: nil)

    func run() async -> String {
        await withCheckedContinuation { continuation in
            lock.withLock {
                waiting?.resume(returning: "superseded")
                waiting = continuation
                bytes = 0
            }
            session.downloadTask(with: SampleCalls.httpbin("bytes/4096")).resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let size = (try? FileManager.default.attributesOfItem(atPath: location.path)[.size] as? Int) ?? 0
        lock.withLock { bytes = size }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        lock.withLock {
            let result = error.map { "failed: \($0.localizedDescription)" } ?? "\(status), \(bytes) bytes"
            waiting?.resume(returning: result)
            waiting = nil
        }
    }
}
