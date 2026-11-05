import Foundation
import ObjectiveC

/// The capture state of one `URLSessionWebSocketTask`, kept on the task (ADR-0045): the upgrade as
/// `RequestStarted`, `ResponseReceived` or `RequestFailed`, then a `WebSocketFrame` per message and close.
final class WebSocketCapture: @unchecked Sendable {
    let id = UUID().uuidString.lowercased()
    let sink: CaptureSink
    let masking: Masking
    let limit: Int
    let requestURL: URL?
    private let lock = NSLock()
    private var status: Int?
    private var closed = false

    init(sink: CaptureSink, options: AppMonitorOptions, requestURL: URL?) {
        self.sink = sink
        masking = Masking(options)
        limit = options.maxBodyBytes
        self.requestURL = requestURL
    }

    func didReceive(_ response: URLResponse) {
        guard let response = response as? HTTPURLResponse else { return }
        lock.withLock { status = response.statusCode }
        emit(sink, "ResponseReceived") {
            var event = Appmonitor_V1_ResponseReceived()
            event.requestID = id
            event.statusCode = Int32(clamping: response.statusCode)
            event.headers = masking.headers(sortedHeaders(response.allHeaderFields), base: response.url ?? requestURL)
            return try event.serializedBytes()
        }
    }

    /// Every end of the task (ADR-0190): an upgrade without a response fails; after a `101`, a close code is
    /// the peer's close unless the app's came first, a cancel is the close without a code the task sends, and
    /// any other error fails, as OkHttp's capture does. A rejected upgrade has ended at its response.
    func ended(_ error: NSError?, closeCode: Int, closeReason: Data?) {
        switch lock.withLock({ status }) {
        case nil:
            if let error { failed(error) }
        case 101:
            if closeCode != URLSessionWebSocketTask.CloseCode.invalid.rawValue {
                close(.received, code: closeCode, reason: closeReason)
            } else if let error, error.domain == NSURLErrorDomain, error.code == NSURLErrorCancelled {
                close(.sent, payload: Data())
            } else if let error {
                failed(error)
            }
        default: break
        }
    }

    private func failed(_ error: NSError) {
        emit(sink, "RequestFailed") {
            var event = Appmonitor_V1_RequestFailed()
            event.requestID = id
            event.error = describe(error)
            return try event.serializedBytes()
        }
    }

    /// A `NSURLSessionWebSocketMessage`, read through its public `type`, `data` and `string` properties.
    func message(_ direction: Appmonitor_V1_WebSocketFrame.Direction, _ message: NSObject) {
        if (message.value(forKey: "type") as? Int) == dataMessageType {
            frame(direction, .binary, (message.value(forKey: "data") as? Data) ?? Data())
        } else {
            frame(direction, .text, Data(((message.value(forKey: "string") as? String) ?? "").utf8))
        }
    }

    /// The first close of either side; the payload is the 2-byte code and the reason (RFC 6455).
    func close(_ direction: Appmonitor_V1_WebSocketFrame.Direction, code: Int, reason: Data?) {
        let code = UInt16(clamping: code)
        close(direction, payload: Data([UInt8(code >> 8), UInt8(code & 0xFF)]) + (reason ?? Data()))
    }

    private func close(_ direction: Appmonitor_V1_WebSocketFrame.Direction, payload: Data) {
        let first = lock.withLock {
            defer { closed = true }
            return !closed
        }
        guard first else { return }
        frame(direction, .close, payload)
    }

    private func frame(
        _ direction: Appmonitor_V1_WebSocketFrame.Direction,
        _ opcode: Appmonitor_V1_WebSocketFrame.Opcode,
        _ payload: Data
    ) {
        emit(sink, "WebSocketFrame") {
            var event = Appmonitor_V1_WebSocketFrame()
            event.requestID = id
            event.direction = direction
            event.opcode = opcode
            event.payload = payload.prefix(limit)
            event.payloadTruncated = payload.count > limit
            event.payloadSize = Int64(payload.count)
            return try event.serializedBytes()
        }
    }
}

/// `NSURLSessionWebSocketMessageTypeData`; `…TypeString` is 1.
private let dataMessageType = 0

/// The hooks of ADR-0045 on `__NSURLSessionWebSocketTask`, and ADR-0190's on its superclass; each calls the original.
enum WebSocketHooks {
    nonisolated(unsafe) private static var captureKey: UInt8 = 0

    static func capture(of task: AnyObject) -> WebSocketCapture? {
        objc_getAssociatedObject(task, &captureKey) as? WebSocketCapture
    }

    /// False when this OS lacks one of the methods; nothing is hooked then.
    static func install() -> Bool {
        let cls: AnyClass? = NSClassFromString("__NSURLSessionWebSocketTask")
        guard let send = Hooks.own(cls, "sendMessage:completionHandler:"),
            let receive = Hooks.own(cls, "receiveMessageWithCompletionHandler:"),
            let response = Hooks.own(cls, "_onqueue_didReceiveResponse:completion:"),
            let close = Hooks.own(cls, "cancelWithCloseCode:reason:"),
            let finish = Hooks.own(NSClassFromString("__NSCFLocalSessionTask"), "_onqueue_didFinishWithError:")
        else { return false }
        // Each records before the original runs, so a reply is never recorded before the frame it answers.
        MethodHook.before(send) { task, message, _ in
            if let message = message as? NSObject { capture(of: task)?.message(.sent, message) }
        }
        MethodHook.before(response) { task, response, _ in
            if let response = response as? URLResponse { capture(of: task)?.didReceive(response) }
        }
        // The superclass's: an HTTP task passes here too, and has no WebSocket capture.
        MethodHook.before(finish) { object, error in
            guard let capture = capture(of: object), let task = object as? URLSessionWebSocketTask else { return }
            capture.ended(error as? NSError, closeCode: task.closeCode.rawValue, closeReason: task.closeReason)
        }
        hookReceive(receive)
        hookClose(close)
        return true
    }

    /// The first resume of a task while the monitor runs; its URL as Foundation rewrites a `ws` one given as a
    /// URL, the same scheme whether the task was made from a URL or from a `URLRequest`.
    static func started(_ task: URLSessionWebSocketTask, sink: CaptureSink, options: AppMonitorOptions) {
        var request = task.currentRequest ?? task.originalRequest
        if let url = request?.url, var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
            let scheme = ["ws": "http", "wss": "https"][parts.scheme?.lowercased() ?? ""]
        {
            parts.scheme = scheme
            request?.url = parts.url
        }
        let capture = WebSocketCapture(sink: sink, options: options, requestURL: request?.url)
        objc_setAssociatedObject(task, &captureKey, capture, .OBJC_ASSOCIATION_RETAIN)
        emit(sink, "RequestStarted") {
            try requestStarted(request, id: capture.id, masking: capture.masking, limit: capture.limit)
        }
    }

    /// Wraps the app's handler: a message is recorded before the app gets it. The capture is looked up on
    /// completion: Ktor's Darwin engine asks before it resumes the task (ADR-0158).
    private static func hookReceive(_ method: Method) {
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject?) -> Void
        typealias Handler = @convention(block) (AnyObject?, AnyObject?) -> Void
        MethodHook.replace(method, arguments: 1, Original.self) { original, selector in
            let block: @convention(block) (AnyObject, AnyObject?) -> Void = { object, handler in
                guard let handler, let task = object as? URLSessionWebSocketTask
                else { return original(object, selector, handler) }
                let app = unsafeBitCast(handler, to: Handler.self)
                let wrapped: Handler = { message, error in
                    guard let capture = capture(of: task) else { return app(message, error) }
                    if let message = message as? NSObject { capture.message(.received, message) }
                    app(message, error)
                }
                original(object, selector, unsafeBitCast(wrapped, to: AnyObject.self))
            }
            return block
        }
    }

    private static func hookClose(_ method: Method) {
        typealias Original = @convention(c) (AnyObject, Selector, Int, AnyObject?) -> Void
        MethodHook.replace(method, arguments: 2, Original.self) { original, selector in
            let block: @convention(block) (AnyObject, Int, AnyObject?) -> Void = { object, code, reason in
                if let task = object as? URLSessionTask, task.state == .running {
                    capture(of: object)?.close(.sent, code: code, reason: reason as? Data)
                }
                original(object, selector, code, reason)
            }
            return block
        }
    }
}
