import Foundation
import ObjectiveC

/// How a held task goes on (ADR-0111 A.1, B.1, C.1).
enum BreakpointOutcome: Sendable {
    /// Only these fields change; nil when unchanged: no resume, its timeout, or its session's end.
    case resume(Appmonitor_V1_BreakpointResume?)
    /// Fails the task as a mock failure does, with this message.
    case abort(String)

    static let unchanged = BreakpointOutcome.resume(nil)
}

/// The session's last `BreakpointRules` and the tasks they hold (ADR-0118); empty outside a session.
final class Breakpoints: @unchecked Sendable {
    private struct Key: Hashable {
        let id: String
        let stage: Appmonitor_V1_BreakpointStage
    }

    private final class Waiter: Sendable {
        let done: @Sendable (BreakpointOutcome) -> Void
        init(_ done: @escaping @Sendable (BreakpointOutcome) -> Void) { self.done = done }
    }

    private let lock = NSLock()
    private var rules: [Appmonitor_V1_BreakpointRule] = []
    private var held: [Key: Waiter] = [:]
    private var live = false

    func replace(_ rules: [Appmonitor_V1_BreakpointRule]) {
        let kept = rules.filter { ($0.request || $0.response) && $0.timeoutMs > 0 }
        lock.withLock {
            live = true
            self.rules = kept
        }
    }

    /// The session ended: every held task goes on unchanged (ADR-0111 B.1).
    func clear() {
        let waiting = lock.withLock {
            live = false
            rules = []
            defer { held = [:] }
            return Array(held.values)
        }
        for waiter in waiting { waiter.done(.unchanged) }
    }

    /// The first rule matching `method` and `url` that holds `stage`, or nil.
    func match(method: String, url: String, stage: Appmonitor_V1_BreakpointStage) -> Appmonitor_V1_BreakpointRule? {
        let current = lock.withLock { rules }
        guard !current.isEmpty, let (host, path) = hostAndPath(url) else { return nil }
        return current.first {
            (stage == .request ? $0.request : $0.response)
                && ruleMatches($0.method, $0.host, $0.path, method: method, host: host, path: path)
        }
    }

    /// Sends `hit` by `send`, then calls `done` once: with the resume, the abort, or unchanged after the hit's
    /// timeout or outside a session. The returned closure drops the hold without calling `done`.
    func hold(
        _ hit: Appmonitor_V1_BreakpointHit,
        send: (Appmonitor_V1_BreakpointHit) -> Void,
        done: @escaping @Sendable (BreakpointOutcome) -> Void
    ) -> @Sendable () -> Void {
        let key = Key(id: hit.request.requestID, stage: hit.stage)
        let waiter = Waiter(done)
        let (replaced, live) = lock.withLock {
            guard self.live else { return (nil as Waiter?, false) }
            defer { held[key] = waiter }
            return (held[key], true)
        }
        replaced?.done(.unchanged)
        guard live else {
            done(.unchanged)
            return {}
        }
        send(hit)
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(clamping: hit.timeoutMs))) {
            [weak self] in
            self?.take(key, waiter)?.done(.unchanged)
        }
        return { [weak self] in _ = self?.take(key, waiter) }
    }

    /// A resume for a task no longer held, past its timeout, is dropped (ADR-0118).
    func resume(_ resume: Appmonitor_V1_BreakpointResume) {
        take(Key(id: resume.requestID, stage: resume.stage), nil)?.done(.resume(resume))
    }

    func abort(_ abort: Appmonitor_V1_BreakpointAbort) {
        take(Key(id: abort.requestID, stage: abort.stage), nil)?.done(.abort(abort.message))
    }

    /// The waiter held under `key`, removed; only when it is `waiter`, if one is given.
    private func take(_ key: Key, _ waiter: Waiter?) -> Waiter? {
        lock.withLock {
            guard let current = held[key], waiter == nil || current === waiter else { return nil }
            held[key] = nil
            return current
        }
    }
}

extension Appmonitor_V1_BreakpointResume {
    /// `headers` with `set_headers` and `removed_headers` applied, names ignoring case (ADR-0118 C.1).
    func applied(to headers: [(name: String, value: String)]) -> [(name: String, value: String)] {
        let replaced = Set(setHeaders.map { $0.name.lowercased() } + removedHeaders.map { $0.lowercased() })
        return headers.filter { !replaced.contains($0.name.lowercased()) } + setHeaders.map { ($0.name, $0.value) }
    }

    var changesHeaders: Bool { !setHeaders.isEmpty || !removedHeaders.isEmpty }
}

/// Foundation keeps one value per name: a name listed twice is joined with `, `.
func headerFields(_ headers: [(name: String, value: String)]) -> [String: String] {
    Dictionary(headers.map { ($0.name, $0.value) }) { "\($0), \($1)" }
}

/// Holds a task a breakpoint matched at its first resume, then sends it itself through a copy of the task's
/// session configuration, or answers its mock (ADR-0111, ADR-0125). The connection hooks of ADR-0043 see
/// what this hands the task, so `ResponseReceived` and `RequestFailed` are what the app got.
final class HoldProtocol: URLProtocol, URLSessionDataDelegate, @unchecked Sendable {
    // Confined to the loading thread: every callback below hops to `loop` first.
    private var loop: CFRunLoop?
    private var capture: TaskCapture?
    private var stopped = false
    private var startSent = false
    private var heldStart: Appmonitor_V1_RequestStarted?
    private var release: (@Sendable () -> Void)?
    private var timer: Timer?
    private var inner: URLSessionTask?
    private var sent: URLRequest?
    private var sentBody: UploadBody?
    private var response: URLResponse?
    private var body = Data()
    private var buffering = false
    // Set once the body passed the capture limit: the inner task is suspended and its end waits for the hold.
    private var heldEarly = false
    private var ended: Error?? = nil

    override class func canInit(with request: URLRequest) -> Bool { false }

    override class func canInit(with task: URLSessionTask) -> Bool {
        Hooks.capture(of: task)?.held == true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        loop = CFRunLoopGetCurrent()
        guard let task, let capture = Hooks.capture(of: task) else { return fail(URLError(.unknown)) }
        self.capture = capture
        let upload = Hooks.upload(of: task)
        let request = capture.heldRequest ?? self.request
        guard let rule = capture.requestHold else { return send(request, upload, mock: capture.mock) }
        let started = startedEvent(request, upload, mock: nil)
        heldStart = started
        var hit = Appmonitor_V1_BreakpointHit()
        hit.ruleID = rule.id
        hit.stage = .request
        hit.request = started
        hit.timeoutMs = rule.timeoutMs
        hold(hit) { [self] outcome in
            switch outcome {
            case .abort(let message):
                emitStart(started)
                fail(connectionLost(message))
            case .resume(let changes):
                do {
                    let (edited, body) = try edit(request, upload, changes)
                    let mock = capture.sink.mock(method: edited.httpMethod ?? "GET", url: edited.url!.absoluteString)
                    emitStart(changes == nil ? started : startedEvent(edited, body, mock: mock), mock: mock)
                    send(edited, body, mock: mock)
                } catch {
                    emitStart(started)
                    fail(error)
                }
            }
        }
    }

    override func stopLoading() {
        stopped = true
        release?()
        release = nil
        timer?.invalidate()
        timer = nil
        inner?.cancel()
        inner = nil
        if let heldStart { emitStart(heldStart) }
    }

    // MARK: - The request

    private func startedEvent(_ request: URLRequest, _ upload: UploadBody?, mock: Appmonitor_V1_MockRule?)
        -> Appmonitor_V1_RequestStarted
    {
        guard let capture, let task else { return Appmonitor_V1_RequestStarted() }
        return requestStartedEvent(
            request, id: capture.id, masking: capture.masking, limit: capture.limit, upload: upload, mock: mock,
            replayOf: Replay.original(of: task))
    }

    /// `RequestStarted` deferred by the request hold, sent once, before the task can end (ADR-0123 C.1).
    private func emitStart(_ started: Appmonitor_V1_RequestStarted, mock: Appmonitor_V1_MockRule? = nil) {
        guard !startSent, let capture else { return }
        startSent = true
        heldStart = nil
        var started = started
        if let mock { started.mockRuleID = mock.id }
        emit(capture.sink, "RequestStarted") { try started.serializedBytes() }
    }

    /// `request` with the resume's fields; throws for an edit `URLSession` cannot send (ADR-0123 D.1).
    private func edit(_ request: URLRequest, _ upload: UploadBody?, _ changes: Appmonitor_V1_BreakpointResume?)
        throws -> (URLRequest, UploadBody?)
    {
        guard let changes else { return (request, upload) }
        var edited = request
        if changes.hasURL {
            guard let url = URL(string: changes.url), let scheme = url.scheme?.lowercased(),
                scheme == "http" || scheme == "https", url.host != nil
            else { throw refused("not an http or https URL: \(changes.url)") }
            edited.url = url
        }
        if changes.hasMethod {
            guard !changes.method.isEmpty else { throw refused("an empty method") }
            edited.httpMethod = changes.method
        }
        if changes.changesHeaders {
            edited.allHTTPHeaderFields = headerFields(
                changes.applied(to: sortedHeaders(request.allHTTPHeaderFields ?? [:])))
        }
        guard changes.hasBody else { return (edited, upload) }
        edited.httpBodyStream = nil
        edited.httpBody = nil
        return (edited, .data(changes.body))
    }

    // MARK: - Sending

    private func send(_ request: URLRequest, _ upload: UploadBody?, mock: Appmonitor_V1_MockRule?) {
        guard !stopped, let capture, let url = request.url else { return }
        sent = request
        sentBody = upload
        buffering =
            capture.sink.breakpoint(method: request.httpMethod ?? "GET", url: url.absoluteString, stage: .response)
            != nil
        if let mock { return answer(mock, url) }
        let configuration = task.flatMap(Hooks.session(of:))?.configuration ?? .default
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        let task: URLSessionTask =
            switch upload {
            case .data(let data): session.uploadTask(with: request, from: data)
            case .file(let file): session.uploadTask(with: request, fromFile: file)
            case nil: session.dataTask(with: request)
            }
        Hooks.skip(task)
        inner = task
        task.resume()
        session.finishTasksAndInvalidate()
    }

    private func answer(_ rule: Appmonitor_V1_MockRule, _ url: URL) {
        guard rule.delayMs > 0 else { return reply(rule, url) }
        let timer = Timer(timeInterval: Double(rule.delayMs) / 1000, repeats: false) { [weak self] _ in
            self?.reply(rule, url)
        }
        self.timer = timer
        RunLoop.current.add(timer, forMode: .common)
    }

    private func reply(_ rule: Appmonitor_V1_MockRule, _ url: URL) {
        timer = nil
        switch MockProtocol.answer(rule, url) {
        case .success(let (response, data)): received(response, data, whole: true)
        case .failure(let error): fail(error)
        }
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void
    ) {
        completionHandler(.allow)
        onLoop { [self] in
            guard !stopped else { return }
            self.response = response
            if !buffering { client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed) }
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        onLoop { [self] in
            guard !stopped else { return }
            guard buffering else {
                client?.urlProtocol(self, didLoad: data)
                return
            }
            body.append(data)
            guard !heldEarly, let capture, body.count > capture.limit, let response else { return }
            heldEarly = true
            inner?.suspend()
            received(response, body, whole: false)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        onLoop { [self] in
            guard !stopped else { return }
            inner = nil
            if heldEarly {
                ended = .some(error)
                return
            }
            if let error { return fail(error) }
            guard buffering, let response else { return finish() }
            received(response, body, whole: true)
        }
    }

    // MARK: - The app's delegate

    /// Answered by the app's delegate as its own task's would be, or by default handling (ADR-0130 A.1).
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let sessionWide = Self.sessionWideMethods.contains(challenge.protectionSpace.authenticationMethod)
        askApp(
            otherwise: { completionHandler(.performDefaultHandling, nil) },
            ask: { app, outer, delegate in
                if let delegate = outer.delegate,
                    delegate.urlSession?(app, task: outer, didReceive: challenge, completionHandler: completionHandler)
                        != nil
                {
                    return true
                }
                if sessionWide,
                    app.delegate?.urlSession?(app, didReceive: challenge, completionHandler: completionHandler) != nil
                {
                    return true
                }
                return delegate?.urlSession?(
                    app, task: outer, didReceive: challenge, completionHandler: completionHandler)
                    != nil
            })
    }

    /// Followed only if the app's delegate follows it; refused, the redirect is the task's response (ADR-0130 B.1).
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        askApp(
            otherwise: { completionHandler(request) },
            ask: { app, outer, delegate in
                if let delegate = outer.delegate,
                    delegate.urlSession?(
                        app, task: outer, willPerformHTTPRedirection: response, newRequest: request,
                        completionHandler: completionHandler) != nil
                {
                    return true
                }
                return delegate?.urlSession?(
                    app, task: outer, willPerformHTTPRedirection: response, newRequest: request,
                    completionHandler: completionHandler) != nil
            })
    }

    /// URLSession's session-level challenges, which go to `urlSession(_:didReceive:completionHandler:)`.
    private static let sessionWideMethods: Set<String> = [
        NSURLAuthenticationMethodServerTrust, NSURLAuthenticationMethodClientCertificate,
        NSURLAuthenticationMethodNTLM, NSURLAuthenticationMethodNegotiate,
    ]

    /// Runs `ask` on the app's delegate queue with the app's session, the held task and the session's
    /// delegate; `otherwise` when no delegate implements the method `ask` calls.
    private func askApp(
        otherwise: @escaping @Sendable () -> Void,
        ask: @escaping (URLSession, URLSessionTask, URLSessionTaskDelegate?) -> Bool
    ) {
        guard let outer = task, let app = Hooks.session(of: outer), app.delegate != nil || outer.delegate != nil
        else { return otherwise() }
        let call = Asked { if !ask(app, outer, app.delegate as? URLSessionTaskDelegate) { otherwise() } }
        app.delegateQueue.addOperation { call.run() }
    }

    // MARK: - The response

    /// The response, held first when a breakpoint matches the request as sent; not `whole` once its body
    /// passed the capture limit, the rest then read after the hold unless the resume replaces the body.
    private func received(_ response: URLResponse, _ data: Data, whole: Bool) {
        guard let capture, let sent, let http = response as? HTTPURLResponse,
            let rule = capture.sink.breakpoint(
                method: sent.httpMethod ?? "GET", url: sent.url?.absoluteString ?? "", stage: .response)
        else { return deliver(response, data, whole: whole) }
        var hit = Appmonitor_V1_BreakpointHit()
        hit.ruleID = rule.id
        hit.stage = .response
        hit.request = startedEvent(sent, sentBody, mock: nil)
        hit.response = responseReceivedEvent(http, data, id: capture.id, masking: capture.masking, limit: capture.limit)
        if !whole {
            if http.expectedContentLength >= 0 {
                hit.response.bodySize = http.expectedContentLength
            } else {
                hit.response.clearBodySize()
            }
        }
        hit.timeoutMs = rule.timeoutMs
        hold(hit) { [self] outcome in
            switch outcome {
            case .abort(let message):
                inner?.cancel()
                fail(connectionLost(message))
            case .resume(nil): deliver(response, whole ? data : body, whole: whole)
            case .resume(let changes?):
                let status = changes.hasStatusCode ? Int(changes.statusCode) : http.statusCode
                let fields = changes.applied(to: sortedHeaders(http.allHeaderFields))
                let edited = HTTPURLResponse(
                    url: http.url ?? sent.url!, statusCode: status, httpVersion: "HTTP/1.1",
                    headerFields: headerFields(fields))!
                if changes.hasBody { inner?.cancel() }
                deliver(edited, changes.hasBody ? changes.body : whole ? data : body, whole: whole || changes.hasBody)
            }
        }
    }

    /// Hands `response` and `data` to the task; not `whole`, the rest of the body follows as it comes.
    private func deliver(_ response: URLResponse, _ data: Data, whole: Bool = true) {
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !data.isEmpty { client?.urlProtocol(self, didLoad: data) }
        if whole { return finish() }
        buffering = false
        heldEarly = false
        body = Data()
        switch ended {
        case .some(.some(let error)): fail(error)
        case .some(.none): finish()
        case .none: inner?.resume()
        }
    }

    // MARK: - Helpers

    private func hold(_ hit: Appmonitor_V1_BreakpointHit, _ go: @escaping (BreakpointOutcome) -> Void) {
        guard let capture else { return }
        let resumed = Resumed(go)
        release = capture.sink.hold(hit) { [weak self] outcome in
            self?.onLoop { [weak self] in
                guard let self, !self.stopped else { return }
                self.release = nil
                resumed.go(outcome)
            }
        }
    }

    private func finish() {
        guard !stopped else { return }
        client?.urlProtocolDidFinishLoading(self)
    }

    private func fail(_ error: Error) {
        guard !stopped else { return }
        client?.urlProtocol(self, didFailWithError: error)
    }

    private func onLoop(_ block: @escaping () -> Void) {
        guard let loop else { return }
        CFRunLoopPerformBlock(loop, CFRunLoopMode.commonModes.rawValue, block)
        CFRunLoopWakeUp(loop)
    }

    private func connectionLost(_ message: String) -> NSError {
        NSError(
            domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost,
            userInfo: [NSLocalizedDescriptionKey: message, NSURLErrorFailingURLErrorKey: request.url as Any])
    }

    private func refused(_ reason: String) -> NSError {
        NSError(
            domain: NSURLErrorDomain, code: NSURLErrorBadURL,
            userInfo: [NSLocalizedDescriptionKey: "Breakpoint edit refused: \(reason)"])
    }
}

/// A closure of the loading thread carried through the sink's `@Sendable` callback.
private final class Resumed: @unchecked Sendable {
    let go: (BreakpointOutcome) -> Void
    init(_ go: @escaping (BreakpointOutcome) -> Void) { self.go = go }
}

/// A call to the app's delegate carried to its delegate queue; the challenge and request it holds are only read.
private final class Asked: @unchecked Sendable {
    let run: () -> Void
    init(_ run: @escaping () -> Void) { self.run = run }
}

/// The whole of `data` as `ResponseReceived` reports it, masked and cut at `limit`.
func responseReceivedEvent(_ response: HTTPURLResponse, _ data: Data, id: String, masking: Masking, limit: Int)
    -> Appmonitor_V1_ResponseReceived
{
    var event = Appmonitor_V1_ResponseReceived()
    event.requestID = id
    event.statusCode = Int32(clamping: response.statusCode)
    event.headers = masking.headers(sortedHeaders(response.allHeaderFields), base: response.url)
    event.body = data.prefix(limit)
    event.bodyTruncated = data.count > limit
    event.bodySize = Int64(data.count)
    return event
}
