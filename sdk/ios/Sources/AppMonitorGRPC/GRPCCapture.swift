import AppMonitor
import Foundation
import GRPCCore
import SwiftProtobuf
import Synchronization

/// One RPC as one `ADAPTER` exchange (ADR-0099 C.1, ADR-0102): a single request or response message as the
/// body, streamed ones as frames, the end as `ResponseReceived` with `grpc-status`, or `RequestFailed`.
@available(macOS 15.0, iOS 18.0, watchOS 11.0, tvOS 18.0, visionOS 2.0, *)
enum GRPCCapture {
    static func intercept<Input: Sendable, Output: Sendable>(
        request: StreamingClientRequest<Input>,
        context: ClientContext,
        next: (StreamingClientRequest<Input>, ClientContext) async throws -> StreamingClientResponse<Output>
    ) async throws -> StreamingClientResponse<Output> {
        guard AdapterExchange.capturing else { return try await next(request, context) }
        // A descriptor without a type is taken as bidirectional.
        let requestStreams = context.descriptor.type?.isRequestStreaming ?? true
        let responseStreams = context.descriptor.type?.isResponseStreaming ?? true
        var request = request
        var body: AdapterPayload?
        if !requestStreams {
            // A non-streaming producer writes one message: run once here, replayed to the transport.
            let collector = Collector<Input>()
            do {
                try await request.producer(RPCWriter(wrapping: collector))
                let messages = collector.messages
                body = messages.first.map(payload)
                request.producer = { try await $0.write(contentsOf: messages) }
            } catch {
                request.producer = { _ in throw error }
            }
        }
        guard
            let exchange = AdapterExchange.start(
                method: "POST", url: url(context), headers: headers(request.metadata), body: body)
        else { return try await next(request, context) }
        if requestStreams {
            let producer = request.producer
            request.producer = { try await producer(RPCWriter(wrapping: Recorder(base: $0, exchange: exchange))) }
        }
        let response: StreamingClientResponse<Output>
        do {
            response = try await next(request, context)
        } catch {
            exchange.failed(describe(error))
            throw error
        }
        switch response.accepted {
        case .failure(let error):
            exchange.failed(describe(error))
            return response
        case .success(var contents):
            let call = ResponseCapture(exchange: exchange, metadata: contents.metadata, streams: responseStreams)
            contents.bodyParts = RPCAsyncSequence(wrapping: CapturedParts(base: contents.bodyParts, call: call))
            return StreamingClientResponse(accepted: .success(contents))
        }
    }

    /// `ipv4:` and `ipv6:` peers keep their address and port; any other (`unix:`, `in-process:`) its kind.
    static func url(_ context: ClientContext) -> String {
        let peer = context.remotePeer
        let authority =
            if peer.hasPrefix("ipv4:") || peer.hasPrefix("ipv6:") {
                String(peer.dropFirst(5))
            } else {
                String(peer.prefix { $0 != ":" })
            }
        return "grpc://\(authority)/\(context.descriptor.fullyQualifiedMethod)"
    }

    /// Binary (`-bin`) values in base64, as on the wire.
    static func headers(_ metadata: Metadata) -> [(name: String, value: String)] {
        metadata.map { ($0.key, $0.value.encoded()) }
    }

    /// A `SwiftProtobuf.Message` serialized again; any other message type is `unread`.
    static func payload(_ message: some Sendable) -> AdapterPayload {
        guard let message = message as? any SwiftProtobuf.Message,
            let bytes: Data = try? message.serializedBytes()
        else { return .unread }
        return .bytes(bytes)
    }

    /// A call the server never accepted, or an error that is not a status.
    static func describe(_ error: any Error) -> String {
        guard let error = error as? RPCError else { return String(describing: error) }
        return "grpc-status \(error.code.rawValue) (\(error.code)): \(error.message)"
    }
}

/// Keeps what a non-streaming producer writes.
@available(macOS 15.0, iOS 18.0, watchOS 11.0, tvOS 18.0, visionOS 2.0, *)
final class Collector<Element: Sendable>: RPCWriterProtocol {
    private let kept = Mutex<[Element]>([])

    var messages: [Element] { kept.withLock { $0 } }

    func write(_ element: Element) async throws {
        kept.withLock { $0.append(element) }
    }

    func write(contentsOf elements: some Sequence<Element>) async throws {
        kept.withLock { $0.append(contentsOf: elements) }
    }
}

/// A streamed request message becomes a sent frame before the transport gets it.
@available(macOS 15.0, iOS 18.0, watchOS 11.0, tvOS 18.0, visionOS 2.0, *)
struct Recorder<Element: Sendable>: RPCWriterProtocol {
    let base: RPCWriter<Element>
    let exchange: AdapterExchange

    func write(_ element: Element) async throws {
        exchange.frame(sent: true, GRPCCapture.payload(element))
        try await base.write(element)
    }

    func write(contentsOf elements: some Sequence<Element>) async throws {
        for element in elements { try await write(element) }
    }
}

/// The response side of one accepted call; a call dropped before its status (cancelled, stream left
/// unread) fails when this goes away.
@available(macOS 15.0, iOS 18.0, watchOS 11.0, tvOS 18.0, visionOS 2.0, *)
final class ResponseCapture: Sendable {
    private struct State: Sendable {
        var body: AdapterPayload?
        var trailers = Metadata()
    }

    private let exchange: AdapterExchange
    private let metadata: Metadata
    private let streams: Bool
    private let state = Mutex(State())

    init(exchange: AdapterExchange, metadata: Metadata, streams: Bool) {
        self.exchange = exchange
        self.metadata = metadata
        self.streams = streams
    }

    deinit {
        exchange.failed("call ended before its status")
    }

    func message(_ payload: AdapterPayload) {
        if streams {
            exchange.frame(sent: false, payload)
        } else {
            state.withLock { if $0.body == nil { $0.body = payload } }
        }
    }

    func trailers(_ trailers: Metadata) {
        state.withLock { $0.trailers.add(contentsOf: trailers) }
    }

    /// Nil at the end of the stream: status 0. A status error ends it with its code, message and metadata.
    func finished(_ error: (any Error)?) {
        let status: RPCError?
        switch error {
        case nil: status = nil
        case let error as RPCError: status = error
        case let error?: return exchange.failed(GRPCCapture.describe(error))
        }
        let state = state.withLock { $0 }
        var trailers = status?.metadata ?? state.trailers
        trailers.removeAllValues(forKey: "grpc-status")
        trailers.removeAllValues(forKey: "grpc-message")
        var headers = GRPCCapture.headers(metadata) + GRPCCapture.headers(trailers)
        headers.append(("grpc-status", "\(status?.code.rawValue ?? 0)"))
        if let message = status?.message, !message.isEmpty { headers.append(("grpc-message", message)) }
        exchange.responded(status: 200, headers: headers, body: streams ? nil : state.body)
    }
}

/// The response's body parts, each told to `call` before the app sees it.
@available(macOS 15.0, iOS 18.0, watchOS 11.0, tvOS 18.0, visionOS 2.0, *)
struct CapturedParts<Output: Sendable>: AsyncSequence, Sendable {
    typealias Element = StreamingClientResponse<Output>.Contents.BodyPart
    typealias Failure = any Error

    let base: RPCAsyncSequence<Element, any Error>
    let call: ResponseCapture

    func makeAsyncIterator() -> Iterator {
        Iterator(base: base.makeAsyncIterator(), call: call)
    }

    struct Iterator: AsyncIteratorProtocol {
        var base: RPCAsyncSequence<Element, any Error>.AsyncIterator
        let call: ResponseCapture

        mutating func next() async throws -> Element? {
            let part: Element?
            do {
                part = try await base.next()
            } catch {
                call.finished(error)
                throw error
            }
            switch part {
            case .message(let message): call.message(GRPCCapture.payload(message))
            case .trailingMetadata(let trailers): call.trailers(trailers)
            case nil: call.finished(nil)
            }
            return part
        }
    }
}
