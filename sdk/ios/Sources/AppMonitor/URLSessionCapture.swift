#if DEBUG
import Foundation
import ObjectiveC
import os

/// Where captured events go: the running monitor, or a test double.
protocol CaptureSink: AnyObject, Sendable {
    /// Nil when nothing should be captured.
    var options: AppMonitorOptions? { get }

    func send(_ envelope: Appmonitor_V1_Envelope)

    /// The desktop's first rule for this call, nil outside a session (ADR-0110).
    func mock(method: String, url: String) -> Appmonitor_V1_MockRule?
}

extension MonitorHolder: CaptureSink {
    func send(_ envelope: Appmonitor_V1_Envelope) {
        client?.send(envelope)
    }

    func mock(method: String, url: String) -> Appmonitor_V1_MockRule? {
        client?.mocks.match(method: method, url: url)
    }
}

/// Captures every `URLSession` task of the process (ADR-0035 C, ADR-0043): `RequestStarted` when a task
/// first resumes, `ResponseReceived` or `RequestFailed` when its connection finishes. Never changes
/// what the task sends or delivers; hooks stay installed after `stop()` and read `sink.options` per task.
enum URLSessionCapture {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var current: CaptureSink = monitor

    static var sink: CaptureSink {
        get { lock.withLock { current } }
        set { lock.withLock { current = newValue } }
    }

    /// Installs the hooks once per process; false when this OS lacks one of them, and nothing is captured.
    static func install() -> Bool { installed }

    private static let installed: Bool = {
        let log = Logger(subsystem: "AppMonitor", category: "capture")
        guard Hooks.install() else {
            log.error("URLSession capture is off: this OS lacks the hooks of ADR-0043")
            return false
        }
        return true
    }()
}

/// What a task uploads when the body is not in its request: set by the upload task constructors.
enum UploadBody {
    case data(Data)
    case file(URL)
}

/// The capture state of one task, kept on the task itself; hooks run on CFNetwork's queues.
final class TaskCapture: @unchecked Sendable {
    let id = UUID().uuidString.lowercased()
    let sink: CaptureSink
    let masking: Masking
    let limit: Int
    let requestURL: URL?
    /// Set at the first resume; `MockProtocol` answers the task with it.
    let mock: Appmonitor_V1_MockRule?
    private let lock = NSLock()
    private var response: URLResponse?
    private var body = Data()
    private var received: Int64 = 0
    private var finished = false

    init(sink: CaptureSink, options: AppMonitorOptions, requestURL: URL?, mock: Appmonitor_V1_MockRule? = nil) {
        self.sink = sink
        masking = Masking(options)
        limit = options.maxBodyBytes
        self.requestURL = requestURL
        self.mock = mock
    }

    func didReceive(_ response: URLResponse) {
        lock.withLock { self.response = response }
    }

    func didReceive(_ data: NSData) {
        lock.withLock {
            let kept = max(0, min(data.length, limit - body.count))
            if kept > 0 { body.append(data.subdata(with: NSRange(location: 0, length: kept))) }
            received += Int64(data.length)
        }
    }

    /// A background download's body: the file `nsurlsessiond` wrote, read up to the limit.
    func didDownload(_ file: URL) {
        let (head, size) = fileHead(file, limit: limit)
        lock.withLock {
            body = head ?? Data()
            received = Int64(size ?? body.count)
        }
    }

    /// A response seen ends as `ResponseReceived`, cut when `error` stopped its body (SDD 05's rule).
    func didFinish(_ error: NSError?) {
        let state: (URLResponse?, Data, Int64)? = lock.withLock {
            guard !finished else { return nil }
            finished = true
            return (response, body, received)
        }
        guard let (response, body, received) = state else { return }
        if let response = response as? HTTPURLResponse {
            emit(sink, "ResponseReceived") {
                var event = Appmonitor_V1_ResponseReceived()
                event.requestID = id
                event.statusCode = Int32(clamping: response.statusCode)
                event.headers = masking.headers(
                    sortedHeaders(response.allHeaderFields),
                    base: response.url ?? requestURL
                )
                event.body = body
                event.bodyTruncated = error != nil || received > Int64(limit)
                if error == nil { event.bodySize = received }
                return try event.serializedBytes()
            }
        } else {
            emit(sink, "RequestFailed") {
                var event = Appmonitor_V1_RequestFailed()
                event.requestID = id
                event.error = error.map(describe) ?? "no HTTP response"
                return try event.serializedBytes()
            }
        }
    }

}

/// Wraps the event for the `network` channel; a failure drops it and never reaches the app's call.
func emit(_ sink: CaptureSink, _ type: String, at timestampMs: Int64? = nil, _ payload: () throws -> Data) {
    guard let payload = try? payload() else { return }
    var envelope = Appmonitor_V1_Envelope()
    envelope.channel = ProtocolConstants.channelNetwork
    envelope.type = type
    envelope.payload = payload
    envelope.timestampMs = timestampMs ?? Int64(Date().timeIntervalSince1970 * 1000)
    sink.send(envelope)
}

/// Domain, code and description only: `NSError`'s `userInfo` holds the URL unmasked. The connection
/// reports `kCFErrorDomainCFNetwork`; the app gets the `NSURLErrorDomain` error of the same code.
func describe(_ error: NSError) -> String {
    let error = error.domain == "kCFErrorDomainCFNetwork" ? NSError(domain: NSURLErrorDomain, code: error.code) : error
    return "\(error.domain) \(error.code): \(error.localizedDescription)"
}

/// Foundation keeps headers in a dictionary: sorted by name so the order is stable.
func sortedHeaders(_ fields: [AnyHashable: Any]) -> [(name: String, value: String)] {
    fields.compactMap { key, value in (key as? String).map { ($0, "\(value)") } }
        .sorted { $0.name.lowercased() < $1.name.lowercased() }
}

/// The first `limit` bytes of `file` and its size, each nil when it cannot be read.
func fileHead(_ file: URL, limit: Int) -> (head: Data?, size: Int?) {
    let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }
    let head = (try? FileHandle(forReadingFrom: file)).flatMap { handle in
        defer { try? handle.close() }
        return try? handle.read(upToCount: limit)
    }
    return (head, size)
}

/// The request as the task holds it at resume, body cut at the limit (ADR-0036 C); `streamed` marks a
/// body sent from a stream the capture never reads.
func requestStarted(
    _ request: URLRequest?,
    id: String,
    masking: Masking,
    limit: Int,
    upload: UploadBody? = nil,
    streamed: Bool = false,
    mock: Appmonitor_V1_MockRule? = nil
) throws -> Data {
    var event = Appmonitor_V1_RequestStarted()
    event.requestID = id
    event.method = request?.httpMethod ?? "GET"
    event.url = masking.url(request?.url?.absoluteString ?? "")
    event.headers = masking.headers(sortedHeaders(request?.allHTTPHeaderFields ?? [:]))
    event.captureLayer = .platform
    if let mock { event.mockRuleID = mock.id }
    switch (upload, request?.httpBody, request?.httpBodyStream) {
    case (.data(let data), _, _), (nil, .some(let data), _):
        event.body = data.prefix(limit)
        event.bodyTruncated = data.count > limit
        event.bodySize = Int64(data.count)
    case (.file(let file), _, _):
        let (head, size) = fileHead(file, limit: limit)
        if let head, let size {
            event.body = head
            event.bodyTruncated = size > limit
            event.bodySize = Int64(size)
        } else {
            event.bodyTruncated = true
            if let size { event.bodySize = Int64(size) }
        }
    case (nil, nil, let stream) where stream != nil || streamed:
        event.bodyTruncated = true  // a stream is never consumed by the capture
    default:
        break
    }
    return try event.serializedBytes()
}

/// The Objective-C runtime hooks of ADR-0043; every replaced method calls the original unchanged.
enum Hooks {
    nonisolated(unsafe) private static var captureKey: UInt8 = 0
    nonisolated(unsafe) private static var uploadKey: UInt8 = 0

    static func capture(of task: AnyObject) -> TaskCapture? {
        objc_getAssociatedObject(task, &captureKey) as? TaskCapture
    }

    static func install() -> Bool {
        guard let connection = NSClassFromString("__NSCFURLLocalSessionConnection"),
            let base = NSClassFromString("__NSCFURLSessionConnection"),
            let taskIvar = class_getInstanceVariable(base, "_task"),
            let resume = own(NSClassFromString("NSURLSessionTask"), "resume"),
            let response = own(connection, "_didReceiveResponse:sniff:"),
            let data = own(connection, "_didReceiveData:"),
            let finish = own(connection, "_didFinishWithError:")
        else { return false }
        hookResume(resume)
        let task: (AnyObject) -> TaskCapture? = {
            object_getIvar($0, taskIvar).flatMap { capture(of: $0 as AnyObject) }
        }
        hook(response) { (connection: AnyObject, response: AnyObject?) in
            if let response = response as? URLResponse { task(connection)?.didReceive(response) }
        }
        hookObject(data, reentrant: false) { connection, data in
            if let data = data as? NSData { task(connection)?.didReceive(data) }
        }
        hookObject(finish) { connection, error in
            task(connection)?.didFinish(error as? NSError)
        }
        let session: AnyClass? = NSClassFromString("NSURLSession")
        for selector in [
            "uploadTaskWithRequest:fromData:", "uploadTaskWithRequest:fromFile:",
            "uploadTaskWithRequest:fromData:completionHandler:", "uploadTaskWithRequest:fromFile:completionHandler:",
            "_uploadTaskWithRequest:fromData:delegate:completionHandler:",
            "_uploadTaskWithRequest:fromFile:delegate:completionHandler:",
        ] {
            if let method = own(session, selector) { hookUpload(method) }
        }
        if !BackgroundHooks.install() {
            Logger(subsystem: "AppMonitor", category: "capture").error(
                "Background session capture is off: this OS lacks the hooks of ADR-0046")
        }
        if !MockHooks.install() {
            Logger(subsystem: "AppMonitor", category: "capture").error(
                "Mocks are off: this OS lacks the hook of ADR-0117")
        }
        if !WebSocketHooks.install() {
            Logger(subsystem: "AppMonitor", category: "capture").error(
                "WebSocket capture is off: this OS lacks the hooks of ADR-0045")
        }
        return true
    }

    /// The method `cls` itself defines, never an inherited one: replacing that would hook the superclass.
    static func own(_ cls: AnyClass?, _ name: String) -> Method? {
        guard let cls else { return nil }
        var count: UInt32 = 0
        guard let methods = class_copyMethodList(cls, &count) else { return nil }
        defer { free(methods) }
        let selector = NSSelectorFromString(name)
        return (0..<Int(count)).map { methods[$0] }.first { method_getName($0) == selector }
    }

    private static func hookResume(_ method: Method) {
        typealias Original = @convention(c) (AnyObject, Selector) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject) -> Void = { object in
            if let task = object as? URLSessionTask { started(task) }
            original(object, selector)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }

    /// The first resume of an HTTP or WebSocket task while the monitor runs; stream tasks and tasks `sdk/ktor`
    /// marks as captured (ADR-0064) are not. Only a local data or upload task is mocked (ADR-0110 D.1).
    private static func started(_ task: URLSessionTask) {
        let sink = URLSessionCapture.sink
        guard capture(of: task) == nil, WebSocketHooks.capture(of: task) == nil,
            let options = sink.options, !(task is URLSessionStreamTask)
        else { return }
        let request = task.currentRequest ?? task.originalRequest
        guard let scheme = request?.url?.scheme?.lowercased(),
            request?.value(forHTTPHeaderField: ProtocolConstants.captureHeader) == nil
        else { return }
        if let task = task as? URLSessionWebSocketTask {
            guard ["http", "https", "ws", "wss"].contains(scheme) else { return }
            return WebSocketHooks.started(task, sink: sink, options: options)
        }
        guard scheme == "http" || scheme == "https" else { return }
        let mock =
            MockHooks.installed && task is URLSessionDataTask && !BackgroundHooks.isBackground(task)
            ? sink.mock(method: request?.httpMethod ?? "GET", url: request?.url?.absoluteString ?? "") : nil
        let capture = TaskCapture(sink: sink, options: options, requestURL: request?.url, mock: mock)
        objc_setAssociatedObject(task, &captureKey, capture, .OBJC_ASSOCIATION_RETAIN)
        BackgroundHooks.started(task, capture)
        let upload = objc_getAssociatedObject(task, &uploadKey) as? UploadBox
        emit(sink, "RequestStarted") {
            try requestStarted(
                request, id: capture.id, masking: capture.masking, limit: capture.limit, upload: upload?.body,
                streamed: task is URLSessionUploadTask, mock: mock)
        }
    }

    private static func hook(_ method: Method, _ body: @escaping (AnyObject, AnyObject?) -> Void) {
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject?, Bool) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject, AnyObject?, Bool) -> Void = { object, argument, flag in
            body(object, argument)
            original(object, selector, argument, flag)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }

    /// `reentrant` false: a call the original makes to itself on the same thread is not seen again; CFNetwork
    /// hands `_didReceiveData:` the same data twice that way.
    private static func hookObject(
        _ method: Method,
        reentrant: Bool = true,
        _ body: @escaping (AnyObject, AnyObject?) -> Void
    ) {
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject?) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let key = "appmonitor.\(NSStringFromSelector(selector))"
        let block: @convention(block) (AnyObject, AnyObject?) -> Void = { object, argument in
            let thread = Thread.current.threadDictionary
            if reentrant || thread[key] == nil { body(object, argument) }
            if reentrant { return original(object, selector, argument) }
            let outer = thread[key] == nil
            if outer { thread[key] = true }
            original(object, selector, argument)
            if outer { thread[key] = nil }
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }

    /// Upload constructors: the body (second argument) is kept on the returned task for `started`.
    private static func hookUpload(_ method: Method) {
        let selector = method_getName(method)
        let implementation = method_getImplementation(method)
        let keep: (AnyObject?, AnyObject?) -> Void = { task, body in
            guard let task else { return }
            let upload: UploadBody? =
                if let data = body as? Data { .data(data) } else if let file = body as? URL { .file(file) } else { nil }
            if let upload { objc_setAssociatedObject(task, &uploadKey, UploadBox(upload), .OBJC_ASSOCIATION_RETAIN) }
        }
        let block: Any
        switch method_getNumberOfArguments(method) {
        case 4:
            typealias Original = @convention(c) (AnyObject, Selector, AnyObject?, AnyObject?) -> AnyObject?
            let original = unsafeBitCast(implementation, to: Original.self)
            block =
                { (object, request, body) in
                    let task = original(object, selector, request, body)
                    keep(task, body)
                    return task
                } as @convention(block) (AnyObject, AnyObject?, AnyObject?) -> AnyObject?
        case 5:
            typealias Original = @convention(c) (AnyObject, Selector, AnyObject?, AnyObject?, AnyObject?) -> AnyObject?
            let original = unsafeBitCast(implementation, to: Original.self)
            block =
                { (object, request, body, handler) in
                    let task = original(object, selector, request, body, handler)
                    keep(task, body)
                    return task
                } as @convention(block) (AnyObject, AnyObject?, AnyObject?, AnyObject?) -> AnyObject?
        case 6:
            typealias Original =
                @convention(c) (
                    AnyObject, Selector, AnyObject?, AnyObject?, AnyObject?, AnyObject?
                ) -> AnyObject?
            let original = unsafeBitCast(implementation, to: Original.self)
            block =
                { (object, request, body, delegate, handler) in
                    let task = original(object, selector, request, body, delegate, handler)
                    keep(task, body)
                    return task
                } as @convention(block) (AnyObject, AnyObject?, AnyObject?, AnyObject?, AnyObject?) -> AnyObject?
        default:
            return
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }
}

/// `UploadBody` as an Objective-C associated object.
final class UploadBox: @unchecked Sendable {
    let body: UploadBody
    init(_ body: UploadBody) { self.body = body }
}
#endif
