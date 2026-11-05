internal import AppMonitorConnections
import Foundation
import ObjectiveC

/// The session's last `capacity` requests the capture can send again, their bodies within `maxBytes`
/// (ADR-0111 D.1, ADR-0122 A.1); emptied when the session ends.
final class RecentRequests: @unchecked Sendable {
    static let capacity = 200
    static let maxBytes: Int64 = 32 << 20
    static let notKept =
        "The app no longer keeps this request: it keeps its last \(capacity), fewer with large bodies, for this connection only"

    private struct Kept {
        let bytes: Int64
        let replay: @Sendable () -> String?
    }

    private let lock = NSLock()
    private let capacity: Int
    private let maxBytes: Int64
    private var order: [String] = []
    private var kept: [String: Kept] = [:]
    private var bytes: Int64 = 0

    init(capacity: Int = RecentRequests.capacity, maxBytes: Int64 = RecentRequests.maxBytes) {
        self.capacity = capacity
        self.maxBytes = maxBytes
    }

    /// `bodyBytes` is what `replay` holds on to; the newest stays whatever its size.
    func keep(_ requestID: String, bodyBytes: Int64 = 0, _ replay: @escaping @Sendable () -> String?) {
        lock.withLock {
            if let previous = kept[requestID] {
                bytes -= previous.bytes
                order.removeAll { $0 == requestID }
            }
            kept[requestID] = Kept(bytes: bodyBytes, replay: replay)
            order.append(requestID)
            bytes += bodyBytes
            while order.count > capacity || (bytes > maxBytes && order.count > 1) {
                bytes -= kept.removeValue(forKey: order.removeFirst())?.bytes ?? 0
            }
        }
    }

    func clear() {
        lock.withLock {
            order = []
            kept = [:]
            bytes = 0
        }
    }

    /// Sends `requestID`'s task again: nil once resumed, else the reason `ReplayRefused` carries.
    func replay(_ requestID: String) -> String? {
        guard let replay = lock.withLock({ kept[requestID]?.replay }) else { return Self.notKept }
        return replay()
    }
}

/// How a captured task is sent again through its own session (ADR-0124): a task with a completion
/// handler, so the app's delegate never gets data for a task it did not make.
enum Replay {
    nonisolated(unsafe) private static var replayKey: UInt8 = 0
    private static let invalidated = "The app's session was invalidated"
    private static let sessionSelector = NSSelectorFromString("session")
    nonisolated(unsafe) private static let invalid = class_getInstanceVariable(
        NSClassFromString("NSURLSession"), "_invalid_ivar")

    /// The request id `task` sends again, set before its first resume.
    static func original(of task: URLSessionTask) -> String? {
        objc_getAssociatedObject(task, &replayKey) as? String
    }

    /// The body bytes `replayer` holds in memory; an upload file is read by the new task.
    static func bodyBytes(request: URLRequest?, upload: UploadBody?) -> Int64 {
        var bytes = Int64(request?.httpBody?.count ?? 0)
        if case .data(let data) = upload { bytes += Int64(data.count) }
        return bytes
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
            guard !isInvalidated(session) else { return invalidated }
            var (data, file): (Data?, URL?) = (nil, nil)
            switch upload {
            case .data(let body): data = body
            case .file(let url): file = url
            case nil: break
            }
            guard let again = appmonitor_replay_task(session, request, data, file, { _, _, _ in }) else {
                return invalidated
            }
            objc_setAssociatedObject(again, &replayKey, id, .OBJC_ASSOCIATION_RETAIN)
            again.resume()
            return nil
        }
    }

    /// Set as soon as the app's call returns, before the session starts raising (ADR-0186).
    private static func isInvalidated(_ session: URLSession) -> Bool {
        guard let invalid else { return false }
        return Unmanaged.passUnretained(session).toOpaque().load(fromByteOffset: ivar_getOffset(invalid), as: Bool.self)
    }
}

/// A weak reference a `@Sendable` closure can hold.
private final class Weak<Value: AnyObject>: @unchecked Sendable {
    weak var value: Value?
    init(_ value: Value?) { self.value = value }
}
