#if DEBUG
import Foundation
import ObjectiveC

/// The tasks of one background session that are being captured, by task identifier: `nsurlsessiond`
/// reports each task to its session by that number (ADR-0046). Kept on the session object.
final class BackgroundTasks: @unchecked Sendable {
    private final class Entry {
        weak var task: URLSessionTask?
        let capture: TaskCapture
        init(_ task: URLSessionTask, _ capture: TaskCapture) {
            self.task = task
            self.capture = capture
        }
    }

    private let lock = NSLock()
    private var entries: [Int: Entry] = [:]

    func add(_ task: URLSessionTask, _ capture: TaskCapture) {
        lock.withLock { entries[task.taskIdentifier] = Entry(task, capture) }
    }

    func capture(_ identifier: UInt) -> TaskCapture? {
        lock.withLock { entries[Int(identifier)]?.capture }
    }

    func remove(_ identifier: UInt) -> (task: URLSessionTask?, capture: TaskCapture)? {
        lock.withLock { entries.removeValue(forKey: Int(identifier)).map { ($0.task, $0.capture) } }
    }
}

/// The hooks of ADR-0046 on `__NSURLBackgroundSession`, the in-process end of a background session; each
/// calls the original with the same arguments. `RequestStarted` comes from the `resume` hook of ADR-0043.
enum BackgroundHooks {
    nonisolated(unsafe) private static var tasksKey: UInt8 = 0
    nonisolated(unsafe) private static var sessionClass: AnyClass?
    private static let session = NSSelectorFromString("session")

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
        guard let sessionClass, task.responds(to: session),
            let owner = task.perform(session)?.takeUnretainedValue(), owner.isKind(of: sessionClass)
        else { return }
        tasks(of: owner).add(task, capture)
    }

    private static func tasks(of session: AnyObject) -> BackgroundTasks {
        objc_sync_enter(session)
        defer { objc_sync_exit(session) }
        if let tasks = objc_getAssociatedObject(session, &tasksKey) as? BackgroundTasks { return tasks }
        let tasks = BackgroundTasks()
        objc_setAssociatedObject(session, &tasksKey, tasks, .OBJC_ASSOCIATION_RETAIN)
        return tasks
    }

    private static func registered(_ session: AnyObject) -> BackgroundTasks? {
        objc_getAssociatedObject(session, &tasksKey) as? BackgroundTasks
    }

    /// The response body of a data or upload task, chunk by chunk.
    private static func hookData(_ method: Method) {
        typealias Original = @convention(c) (AnyObject, Selector, UInt, AnyObject?, AnyObject?) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject, UInt, AnyObject?, AnyObject?) -> Void = { object, id, data, reply in
            if let data = data as? NSData { registered(object)?.capture(id)?.didReceive(data) }
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
            if let capture = registered(object)?.capture(id) {
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
            if let (task, capture) = registered(object)?.remove(id) {
                if let response = task?.response { capture.didReceive(response) }
                capture.didFinish(error as? NSError)
            }
            original(object, selector, id, error, metrics, info, reply)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }
}
#endif
