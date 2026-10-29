import Foundation
import ObjectiveC

/// The session's last `capacity` requests the capture can send again (ADR-0111 D.1, ADR-0122 A.1); emptied
/// when the session ends.
final class RecentRequests: @unchecked Sendable {
    static let capacity = 200
    static let notKept =
        "The app no longer keeps this request: it keeps its last \(capacity), for this connection only"

    private let lock = NSLock()
    private let capacity: Int
    private var order: [String] = []
    private var kept: [String: @Sendable () -> String?] = [:]

    init(capacity: Int = RecentRequests.capacity) { self.capacity = capacity }

    func keep(_ requestID: String, _ replay: @escaping @Sendable () -> String?) {
        lock.withLock {
            if kept.updateValue(replay, forKey: requestID) != nil { order.removeAll { $0 == requestID } }
            order.append(requestID)
            if order.count > capacity { kept[order.removeFirst()] = nil }
        }
    }

    func clear() {
        lock.withLock {
            order = []
            kept = [:]
        }
    }

    /// Sends `requestID`'s task again: nil once resumed, else the reason `ReplayRefused` carries.
    func replay(_ requestID: String) -> String? {
        guard let replay = lock.withLock({ kept[requestID] }) else { return Self.notKept }
        return replay()
    }
}

/// How a captured task is sent again through its own session (ADR-0124): a task with a completion
/// handler, so the app's delegate never gets data for a task it did not make.
enum Replay {
    nonisolated(unsafe) private static var replayKey: UInt8 = 0
    private static let sessionSelector = NSSelectorFromString("session")
    nonisolated(unsafe) private static let invalid = class_getInstanceVariable(
        NSClassFromString("NSURLSession"), "_invalid_ivar")

    /// The request id `task` sends again, set before its first resume.
    static func original(of task: URLSessionTask) -> String? {
        objc_getAssociatedObject(task, &replayKey) as? String
    }

    /// What sends `task` again later; it holds the session weakly and the request and upload body strongly.
    static func replayer(
        of task: URLSessionTask,
        id: String,
        request: URLRequest?,
        upload: UploadBody?
    ) -> @Sendable () -> String? {
        let refusal: String? =
            if BackgroundHooks.isBackground(task) {
                "A background session's task is not sent again"
            } else if upload == nil && (task is URLSessionUploadTask || request?.httpBodyStream != nil) {
                "Its body was a stream, which the app reads once"
            } else if request == nil || !task.responds(to: sessionSelector) {
                "The app's task has no request to send again"
            } else {
                nil
            }
        if let refusal { return { refusal } }
        let session = Weak(task.perform(sessionSelector)?.takeUnretainedValue() as? URLSession)
        let request = request!
        return {
            guard let session = session.value else { return "The app's session is gone" }
            guard let invalidated = isInvalidated(session) else {
                return "The SDK cannot tell whether the app's session is still valid on this OS"
            }
            guard !invalidated else { return "The app's session was invalidated" }
            let ignore: @Sendable (Data?, URLResponse?, Error?) -> Void = { _, _, _ in }
            let again: URLSessionTask =
                switch upload {
                case .data(let data): session.uploadTask(with: request, from: data, completionHandler: ignore)
                case .file(let file): session.uploadTask(with: request, fromFile: file, completionHandler: ignore)
                case nil: session.dataTask(with: request, completionHandler: ignore)
                }
            objc_setAssociatedObject(again, &replayKey, id, .OBJC_ASSOCIATION_RETAIN)
            again.resume()
            return nil
        }
    }

    /// A task made on an invalidated session raises an Objective-C exception the SDK cannot catch (ADR-0124).
    private static func isInvalidated(_ session: URLSession) -> Bool? {
        guard let invalid else { return nil }
        return Unmanaged.passUnretained(session).toOpaque().load(fromByteOffset: ivar_getOffset(invalid), as: Bool.self)
    }
}

/// A weak reference a `@Sendable` closure can hold.
private final class Weak<Value: AnyObject>: @unchecked Sendable {
    weak var value: Value?
    init(_ value: Value?) { self.value = value }
}
