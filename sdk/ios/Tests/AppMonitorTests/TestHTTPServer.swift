import Foundation
import Network

/// HTTP/1.1 on 127.0.0.1 for capture tests; records each request as it arrived. Routes: `/ok` 200 with
/// a cookie, `/error` 500, `/big/N` N bytes, `/echo` the request body, `/redirect` 302 to `/ok`,
/// `/auth` 401 asking for Basic credentials unless the request has some, `/hang` never answers, `/slow` is
/// `/ok` one second later.
final class TestHTTPServer: @unchecked Sendable {
    struct Request: Sendable {
        let method: String
        let path: String
        let headers: [String: String]
        let body: Data
    }

    private let queue = DispatchQueue(label: "test.http")
    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private var requests: [Request] = []

    private(set) var port: UInt16 = 0

    var received: [Request] { queue.sync { requests } }

    func url(_ path: String) -> URL { URL(string: "http://127.0.0.1:\(port)\(path)")! }

    func start() async throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    listener.stateUpdateHandler = nil
                    continuation.resume()
                case .failed(let error):
                    listener.stateUpdateHandler = nil
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
        port = listener.port!.rawValue
    }

    func stop() {
        queue.sync {
            listener?.cancel()
            for connection in connections { connection.cancel() }
            connections = []
        }
    }

    private func accept(_ connection: NWConnection) {
        connections.append(connection)
        connection.start(queue: queue)
        read(connection, Data())
    }

    private func read(_ connection: NWConnection, _ buffered: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, done, error in
            guard let self, error == nil else { return }
            var buffered = buffered + (data ?? Data())
            while let (request, rest) = Self.parse(buffered) {
                buffered = rest
                requests.append(request)
                if let response = Self.response(to: request) {
                    let delay: TimeInterval = request.path.hasPrefix("/slow") ? 1 : 0
                    queue.asyncAfter(deadline: .now() + delay) {
                        connection.send(content: response, completion: .idempotent)
                    }
                }
            }
            if !done { read(connection, buffered) }
        }
    }

    private static func response(to request: Request) -> Data? {
        let path = String(request.path.prefix { $0 != "?" })
        let (status, headers, body): (String, [String], Data)
        switch path {
        case "/ok", "/slow":
            (status, headers, body) = ("200 OK", ["Content-Type: text/plain", "Set-Cookie: s=1"], Data("hello".utf8))
        case "/error": (status, headers, body) = ("500 Internal Server Error", [], Data("boom".utf8))
        case "/echo": (status, headers, body) = ("200 OK", [], request.body)
        case "/redirect": (status, headers, body) = ("302 Found", ["Location: /ok"], Data("moved".utf8))
        case "/auth" where request.headers["authorization"] == nil:
            (status, headers, body) = ("401 Unauthorized", ["WWW-Authenticate: Basic realm=\"test\""], Data())
        case "/auth": (status, headers, body) = ("200 OK", [], Data("hello".utf8))
        case "/hang": return nil
        case _ where path.hasPrefix("/partial/"):
            let count = Int(path.dropFirst(9))!
            return Data("HTTP/1.1 200 OK\r\nContent-Length: \(count * 2)\r\n\r\n".utf8)
                + Data(repeating: UInt8(ascii: "a"), count: count)
        case _ where path.hasPrefix("/big/"):
            (status, headers, body) = ("200 OK", [], Data(repeating: UInt8(ascii: "a"), count: Int(path.dropFirst(5))!))
        default: (status, headers, body) = ("404 Not Found", [], Data())
        }
        let head = (["HTTP/1.1 \(status)", "Content-Length: \(body.count)"] + headers).joined(separator: "\r\n")
        return Data((head + "\r\n\r\n").utf8) + body
    }

    /// One complete request off the front of `data`, with `Content-Length` or chunked body; nil until whole.
    private static func parse(_ data: Data) -> (Request, Data)? {
        guard let end = data.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let lines = String(decoding: data[..<end.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
        let start = lines[0].split(separator: " ")
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(
                in: .whitespaces)
        }
        var rest = Data(data[end.upperBound...])
        var body = Data()
        if headers["transfer-encoding"]?.lowercased() == "chunked" {
            while true {
                guard let lineEnd = rest.range(of: Data("\r\n".utf8)) else { return nil }
                let size = Int(String(decoding: rest[..<lineEnd.lowerBound], as: UTF8.self), radix: 16) ?? 0
                guard rest.count >= lineEnd.upperBound - rest.startIndex + size + 2 else { return nil }
                let chunk = rest[lineEnd.upperBound..<lineEnd.upperBound + size]
                rest = Data(rest[(lineEnd.upperBound + size + 2)...])
                if size == 0 { break }
                body += chunk
            }
        } else {
            let length = Int(headers["content-length"] ?? "0") ?? 0
            guard rest.count >= length else { return nil }
            body = rest.prefix(length)
            rest = Data(rest.dropFirst(length))
        }
        let request = Request(method: String(start[0]), path: String(start[1]), headers: headers, body: body)
        return (request, rest)
    }
}
