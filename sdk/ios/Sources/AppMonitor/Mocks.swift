#if DEBUG
import Foundation
import ObjectiveC

/// The rules of the session's last `MockRules` (ADR-0113 A.1); empty outside a session (ADR-0110 A.1).
final class MockRuleSet: @unchecked Sendable {
    private let lock = NSLock()
    private var rules: [Appmonitor_V1_MockRule] = []

    /// A rule with neither answer, from a newer desktop, is ignored (ADR-0115).
    func replace(_ rules: [Appmonitor_V1_MockRule]) {
        let served = rules.filter { $0.answer != nil }
        lock.withLock { self.rules = served }
    }

    func clear() {
        lock.withLock { rules = [] }
    }

    /// The first rule matching `method` and `url` (ADR-0110 B.1), or nil.
    func match(method: String, url: String) -> Appmonitor_V1_MockRule? {
        let current = lock.withLock { rules }
        guard !current.isEmpty, let (host, path) = hostAndPath(url) else { return nil }
        return current.first { $0.matches(method: method, host: host, path: path) }
    }
}

extension Appmonitor_V1_MockRule {
    func matches(method: String, host: String, path: String) -> Bool {
        ruleMatches(self.method, self.host, self.path, method: method, host: host, path: path)
    }
}

/// ADR-0110 B.1, for mock rules and breakpoints alike (ADR-0118 A.1).
func ruleMatches(
    _ ruleMethod: String, _ ruleHost: String, _ rulePath: String, method: String, host: String, path: String
) -> Bool {
    if !ruleMethod.isEmpty && ruleMethod != method { return false }
    if ruleHost.lowercased() != host.lowercased() { return false }
    let wanted = rulePath.split(separator: "/", omittingEmptySubsequences: false)
    let actual = path.split(separator: "/", omittingEmptySubsequences: false)
    return wanted.count == actual.count && zip(wanted, actual).allSatisfy { $0 == "*" || $0 == $1 }
}

/// RFC 3986 appendix B, as `sdk/core` reads it (ADR-0115 B.1): the host without user info or port, and the
/// path as written ("/" when empty); nil when `url` has no authority.
func hostAndPath(_ url: String) -> (host: String, path: String)? {
    guard let separator = url.range(of: "://") else { return nil }
    let scheme = url[..<separator.lowerBound]
    guard !scheme.isEmpty, !scheme.contains(where: { ":/?#".contains($0) }) else { return nil }
    let rest = url[separator.upperBound...]
    let authorityEnd = rest.firstIndex(where: { "/?#".contains($0) }) ?? rest.endIndex
    let afterAuthority = rest[authorityEnd...]
    let path = afterAuthority[..<(afterAuthority.firstIndex(where: { "?#".contains($0) }) ?? afterAuthority.endIndex)]
    let authority = rest[..<authorityEnd]
    let hostPort = authority.lastIndex(of: "@").map { authority[authority.index(after: $0)...] } ?? authority
    let host =
        hostPort.hasPrefix("[")
        ? hostPort.firstIndex(of: "]").map { String(hostPort[...$0]) } ?? String(hostPort) + "]"
        : String(hostPort.prefix { $0 != ":" })
    return (host, path.isEmpty ? "/" : String(path))
}

/// Answers a task its `TaskCapture` holds a rule for, without the network (ADR-0110 C.1, ADR-0117). The
/// connection hooks of ADR-0043 still see the task, so its events are sent as for any other task.
final class MockProtocol: URLProtocol, @unchecked Sendable {
    // Confined to the loading thread: URLSession calls startLoading and stopLoading on it.
    private var timer: Timer?

    override class func canInit(with request: URLRequest) -> Bool { false }

    override class func canInit(with task: URLSessionTask) -> Bool {
        Hooks.capture(of: task)?.mock != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let task, let rule = Hooks.capture(of: task)?.mock else {
            return client?.urlProtocol(self, didFailWithError: URLError(.unknown)) ?? ()
        }
        guard rule.delayMs > 0 else { return answer(rule) }
        let timer = Timer(timeInterval: Double(rule.delayMs) / 1000, repeats: false) { [weak self] _ in
            self?.answer(rule)
        }
        self.timer = timer
        RunLoop.current.add(timer, forMode: .common)
    }

    override func stopLoading() {
        timer?.invalidate()
        timer = nil
    }

    private func answer(_ rule: Appmonitor_V1_MockRule) {
        timer = nil
        guard let url = request.url else { return }
        switch Self.answer(rule, url) {
        case .success(let (response, data)):
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            if !data.isEmpty { client?.urlProtocol(self, didLoad: data) }
            client?.urlProtocolDidFinishLoading(self)
        case .failure(let error):
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    /// The rule's response or failure for a task loading `url`; `HoldProtocol` answers a held task with it.
    static func answer(_ rule: Appmonitor_V1_MockRule, _ url: URL) -> Result<(HTTPURLResponse, Data), NSError> {
        switch rule.answer {
        case .response(let mocked):
            let response = HTTPURLResponse(
                url: url, statusCode: Int(mocked.statusCode), httpVersion: "HTTP/1.1",
                headerFields: headerFields(mocked.headers.map { ($0.name, $0.value) }))!
            return .success((response, mocked.body))
        case .failure(let failure):
            return .failure(
                NSError(
                    domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost,
                    userInfo: [NSLocalizedDescriptionKey: failure.message, NSURLErrorFailingURLErrorKey: url]))
        case nil:
            return .failure(URLError(.unknown) as NSError)
        }
    }
}

/// Puts `HoldProtocol` and `MockProtocol` first in every configuration's `protocolClasses` but a background
/// one's, read by every session when a task starts, `URLSession.shared` and sessions made before `start` included.
enum MockHooks {
    nonisolated(unsafe) private(set) static var installed = false

    static func install() -> Bool {
        guard let method = Hooks.own(NSClassFromString("NSURLSessionConfiguration"), "protocolClasses") else {
            return false
        }
        typealias Original = @convention(c) (AnyObject, Selector) -> NSArray?
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject) -> NSArray? = { object in
            let classes = original(object, selector)
            guard (object as? URLSessionConfiguration)?.identifier == nil else { return classes }
            let others = (classes as? [AnyClass] ?? []).filter { $0 != HoldProtocol.self && $0 != MockProtocol.self }
            return ([HoldProtocol.self, MockProtocol.self] + others) as NSArray
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
        installed = true
        return true
    }
}
#endif
