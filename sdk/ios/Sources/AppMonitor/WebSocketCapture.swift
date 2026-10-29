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
    private var responded = false
    private var closed = false

    init(sink: CaptureSink, options: AppMonitorOptions, requestURL: URL?) {
        self.sink = sink
        masking = Masking(options)
        limit = options.maxBodyBytes
        self.requestURL = requestURL
    }

    func didReceive(_ response: URLResponse) {
        guard let response = response as? HTTPURLResponse else { return }
        lock.withLock { responded = true }
        emit(sink, "ResponseReceived") {
            var event = Appmonitor_V1_ResponseReceived()
            event.requestID = id
            event.statusCode = Int32(clamping: response.statusCode)
            event.headers = masking.headers(sortedHeaders(response.allHeaderFields), base: response.url ?? requestURL)
            return try event.serializedBytes()
        }
    }

    /// Only an upgrade that never got a response fails; a socket that drops later just stops sending frames.
    func didFinish(_ error: NSError?) {
        guard let error, !lock.withLock({ responded }) else { return }
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
        let first = lock.withLock {
            defer { closed = true }
            return !closed
        }
        guard first else { return }
        let code = UInt16(clamping: code)
        frame(direction, .close, Data([UInt8(code >> 8), UInt8(code & 0xFF)]) + (reason ?? Data()))
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

/// The hooks of ADR-0045 on `__NSURLSessionWebSocketTask`; each calls the original with the same arguments.
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
            let finish = Hooks.own(cls, "connection:didFinishLoadingWithError:"),
            let close = Hooks.own(cls, "cancelWithCloseCode:reason:")
        else { return false }
        hookTwo(send) { task, message, _ in
            if let message = message as? NSObject { capture(of: task)?.message(.sent, message) }
        }
        hookTwo(response) { task, response, _ in
            if let response = response as? URLResponse { capture(of: task)?.didReceive(response) }
        }
        hookTwo(finish) { task, _, error in capture(of: task)?.didFinish(error as? NSError) }
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

    /// Records before the original runs, so a reply is never recorded before the frame it answers.
    private static func hookTwo(_ method: Method, _ body: @escaping (AnyObject, AnyObject?, AnyObject?) -> Void) {
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject?, AnyObject?) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject, AnyObject?, AnyObject?) -> Void = { object, first, second in
            body(object, first, second)
            original(object, selector, first, second)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }

    /// Wraps the app's handler: a message is recorded before the app gets it; an error after the peer's
    /// close frame records that frame, read from the task's public `closeCode` and `closeReason`. The capture
    /// is looked up on completion: Ktor's Darwin engine asks before it resumes the task (ADR-0158).
    private static func hookReceive(_ method: Method) {
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject?) -> Void
        typealias Handler = @convention(block) (AnyObject?, AnyObject?) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject, AnyObject?) -> Void = { object, handler in
            guard let handler, let task = object as? URLSessionWebSocketTask
            else { return original(object, selector, handler) }
            let app = unsafeBitCast(handler, to: Handler.self)
            let wrapped: Handler = { message, error in
                guard let capture = capture(of: task) else { return app(message, error) }
                if let message = message as? NSObject {
                    capture.message(.received, message)
                } else if error != nil, task.closeCode != .invalid {
                    capture.close(.received, code: task.closeCode.rawValue, reason: task.closeReason)
                }
                app(message, error)
            }
            original(object, selector, unsafeBitCast(wrapped, to: AnyObject.self))
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }

    private static func hookClose(_ method: Method) {
        typealias Original = @convention(c) (AnyObject, Selector, Int, AnyObject?) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let selector = method_getName(method)
        let block: @convention(block) (AnyObject, Int, AnyObject?) -> Void = { object, code, reason in
            if let task = object as? URLSessionTask, task.state == .running {
                capture(of: object)?.close(.sent, code: code, reason: reason as? Data)
            }
            original(object, selector, code, reason)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }
}
