import Foundation
import ObjectiveC

/// The tasks of one background session that are being captured, by task identifier: `nsurlsessiond`
/// reports each task to its session by that number (ADR-0046). Kept on the session object.
final class BackgroundTasks: @unchecked Sendable {
    private final class Entry {
        weak var task: URLSessionTask?
        let capture: TaskCapture
        /// No `resume` seen: `RequestStarted` waits for the task's metrics at completion (ADR-0131).
        let adopted: Bool
        init(_ task: URLSessionTask?, _ capture: TaskCapture, adopted: Bool) {
            self.task = task
            self.capture = capture
            self.adopted = adopted
        }
    }

    private let lock = NSLock()
    private var entries: [Int: Entry] = [:]

    func add(_ task: URLSessionTask, _ capture: TaskCapture) {
        lock.withLock { entries[task.taskIdentifier] = Entry(task, capture, adopted: false) }
    }

    /// The task's capture, made at its first callback when the `resume` hook never saw it.
    func capture(_ identifier: UInt, adopting make: () -> TaskCapture?) -> TaskCapture? {
        lock.withLock {
            if let entry = entries[Int(identifier)] { return entry.capture }
            guard let capture = make() else { return nil }
            entries[Int(identifier)] = Entry(nil, capture, adopted: true)
            return capture
        }
    }

    func remove(_ identifier: UInt) -> (task: URLSessionTask?, capture: TaskCapture, adopted: Bool)? {
        lock.withLock { entries.removeValue(forKey: Int(identifier)).map { ($0.task, $0.capture, $0.adopted) } }
    }
}

/// The hooks of ADR-0046 on `__NSURLBackgroundSession`, the in-process end of a background session; each
/// calls the original with the same arguments. `RequestStarted` comes from the `resume` hook of ADR-0043.
enum BackgroundHooks {
    nonisolated(unsafe) private static var tasksKey: UInt8 = 0
    nonisolated(unsafe) private static var sessionClass: AnyClass?
    private static let session = NSSelectorFromString("session")
    nonisolated(unsafe) private static let metricsClass: AnyClass? = NSClassFromString("NSURLSessionTaskMetrics")
    nonisolated(unsafe) private static let metricsIvar = class_getInstanceVariable(metricsClass, "__metrics")

    /// False when this OS lacks one of the methods; nothing is hooked then.
    static func install() -> Bool {
        let cls: AnyClass? = NSClassFromString("__NSURLBackgroundSession")
        guard let background = cls, class_getInstanceMethod(NSClassFromString("NSURLSessionTask"), session) != nil,
            let data = Hooks.own(cls, "backgroundDataTask:didReceiveData:withReply:"),
            let download = Hooks.own(cls, "backgroundDownloadTask:didFinishDownloadingToURL:response:reply:"),
            let complete = Hooks.own(cls, "backgroundTask:didCompleteWithError:taskMetrics:info:reply:")
        else { return false }
        hookData(data)
        hookDownload(download)
        hookComplete(complete)
        sessionClass = background
        return true
    }

    /// Called by `Hooks.started` for every captured task: registers it when its session is a background one.
    static func started(_ task: URLSessionTask, _ capture: TaskCapture) {
        guard let owner = backgroundSession(of: task) else { return }
        tasks(of: owner).add(task, capture)
    }

    /// True when `task` runs in a background session; false too when the hooks are not installed.
    static func isBackground(_ task: URLSessionTask) -> Bool { backgroundSession(of: task) != nil }

    private static func backgroundSession(of task: URLSessionTask) -> AnyObject? {
        guard let sessionClass, task.responds(to: session),
            let owner = task.perform(session)?.takeUnretainedValue(), owner.isKind(of: sessionClass)
        else { return nil }
        return owner
    }

    private static func tasks(of session: AnyObject) -> BackgroundTasks {
        objc_sync_enter(session)
        defer { objc_sync_exit(session) }
        if let tasks = objc_getAssociatedObject(session, &tasksKey) as? BackgroundTasks { return tasks }
        let tasks = BackgroundTasks()
        objc_setAssociatedObject(session, &tasksKey, tasks, .OBJC_ASSOCIATION_RETAIN)
        return tasks
    }

    /// A task resumed before `start` or in an earlier launch is adopted while the monitor runs (ADR-0131).
    private static func capture(_ session: AnyObject, _ id: UInt) -> TaskCapture? {
        tasks(of: session).capture(id) {
            let sink = URLSessionCapture.sink
            return sink.options.map { TaskCapture(sink: sink, options: $0, requestURL: nil) }
        }
    }

    /// `URLSessionTaskMetrics` around the `__CFN_TaskMetrics` the completion gets, as the app's delegate would see
    /// them; nil when this OS keeps them otherwise (ADR-0131).
    private static func publicMetrics(_ daemon: AnyObject?) -> URLSessionTaskMetrics? {
        guard let daemon, let metricsIvar, let encoding = ivar_getTypeEncoding(metricsIvar),
            String(cString: encoding) == "@\"\(String(cString: object_getClassName(daemon)))\"",
            let metrics = (metricsClass as? NSObject.Type)?.init() as? URLSessionTaskMetrics
        else { return nil }
        object_setIvar(metrics, metricsIvar, daemon)
        return metrics
    }

    /// `RequestStarted` of an adopted task, at the start its metrics give; none without metrics, or for a task
    /// `started` would skip.
    private static func adoptedStarted(_ capture: TaskCapture, _ metrics: URLSessionTaskMetrics?) -> Bool {
        guard let metrics, let request = metrics.transactionMetrics.first?.request,
            let scheme = request.url?.scheme?.lowercased(), scheme == "http" || scheme == "https",
            request.value(forHTTPHeaderField: ProtocolConstants.captureHeader) == nil
        else { return false }
        let start = Int64(metrics.taskInterval.start.timeIntervalSince1970 * 1000)
        capture.sink.keepForReplay(capture.id) { "A background session's task is not sent again" }
        let unread = request.httpBody == nil && !["GET", "HEAD"].contains(request.httpMethod ?? "GET")
        emit(capture.sink, "RequestStarted", at: start) {
            try requestStarted(
                request, id: capture.id, masking: capture.masking, limit: capture.limit, streamed: unread)
        }
        return true
    }

    /// The response body of a data or upload task, chunk by chunk.
    private static func hookData(_ method: Method) {
        typealias Original = @convention(c) (AnyObject, Selector, UInt, AnyObject?, AnyObject?) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject, UInt, AnyObject?, AnyObject?) -> Void = { object, id, data, reply in
            if let data = data as? NSData { capture(object, id)?.didReceive(data) }
            original(object, selector, id, data, reply)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }

    /// The downloaded file, read before the original hands it to the app's delegate, which may move it.
    private static func hookDownload(_ method: Method) {
        typealias Original = @convention(c) (AnyObject, Selector, UInt, AnyObject?, AnyObject?, AnyObject?) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject, UInt, AnyObject?, AnyObject?, AnyObject?) -> Void = {
            object, id, file, response, reply in
            if let capture = capture(object, id) {
                if let response = response as? URLResponse { capture.didReceive(response) }
                if let file = file as? URL { capture.didDownload(file) }
            }
            original(object, selector, id, file, response, reply)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }

    private static func hookComplete(_ method: Method) {
        typealias Original =
            @convention(c) (AnyObject, Selector, UInt, AnyObject?, AnyObject?, AnyObject?, AnyObject?) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject, UInt, AnyObject?, AnyObject?, AnyObject?, AnyObject?) -> Void = {
            object, id, error, metrics, info, reply in
            _ = capture(object, id)
            if let (task, capture, adopted) = tasks(of: object).remove(id) {
                let metrics = publicMetrics(metrics)
                if let response = task?.response ?? metrics?.transactionMetrics.last?.response {
                    capture.didReceive(response)
                }
                if !adopted || adoptedStarted(capture, metrics) { capture.didFinish(error as? NSError) }
            }
            original(object, selector, id, error, metrics, info, reply)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }
}
