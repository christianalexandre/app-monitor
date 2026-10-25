import AppMonitorGRPC
import Foundation
import GRPCCore
import GRPCInProcessTransport
import SwiftProtobuf
import Testing

@testable import AppMonitor

typealias Text = Google_Protobuf_StringValue

@available(macOS 15.0, iOS 18.0, *)
struct ProtoCoder<Message: SwiftProtobuf.Message & Sendable>: MessageSerializer, MessageDeserializer {
    func serialize<Bytes: GRPCContiguousBytes>(_ message: Message) throws -> Bytes {
        Bytes(try message.serializedBytes() as [UInt8])
    }

    func deserialize<Bytes: GRPCContiguousBytes>(_ serializedMessageBytes: Bytes) throws -> Message {
        try serializedMessageBytes.withUnsafeBytes { try Message(serializedBytes: Array($0)) }
    }
}

/// `echo.Echo`: `Get` echoes, `Expand` streams each word, `Missing` rejects the call, `Break` fails after a word.
@available(macOS 15.0, iOS 18.0, *)
struct EchoService: RegistrableRPCService {
    static let get = MethodDescriptor(fullyQualifiedService: "echo.Echo", method: "Get", type: .unary)
    static let expand = MethodDescriptor(fullyQualifiedService: "echo.Echo", method: "Expand", type: .serverStreaming)
    static let missing = MethodDescriptor(fullyQualifiedService: "echo.Echo", method: "Missing", type: .unary)
    static let broken = MethodDescriptor(fullyQualifiedService: "echo.Echo", method: "Break", type: .serverStreaming)

    func registerMethods<Transport: ServerTransport>(with router: inout RPCRouter<Transport>) {
        let coder = ProtoCoder<Text>()
        router.registerHandler(forMethod: Self.get, deserializer: coder, serializer: coder) { request, _ in
            let message = try await ServerRequest(stream: request).message
            return StreamingServerResponse(
                single: ServerResponse(message: message, metadata: ["echo": "yes"], trailingMetadata: ["done": "1"]))
        }
        router.registerHandler(forMethod: Self.expand, deserializer: coder, serializer: coder) { request, _ in
            let message = try await ServerRequest(stream: request).message
            return StreamingServerResponse { writer in
                for word in message.value.split(separator: " ") { try await writer.write(Text(String(word))) }
                return [:]
            }
        }
        router.registerHandler(forMethod: Self.missing, deserializer: coder, serializer: coder) { _, _ in
            StreamingServerResponse(error: RPCError(code: .notFound, message: "no such thing"))
        }
        router.registerHandler(forMethod: Self.broken, deserializer: coder, serializer: coder) { _, _ in
            StreamingServerResponse { writer in
                try await writer.write(Text("first"))
                throw RPCError(code: .internalError, message: "broke")
            }
        }
    }
}

extension Text {
    init(_ value: String) {
        self.init()
        self.value = value
    }
}

extension ProcessWideHooks {
    // `AdapterCapture.sink` is process-wide: one test at a time owns it.
    @Suite(.serialized)
    final class GRPCCaptureTests {
        let sink = RecordingSink()

        init() {
            AdapterCapture.sink = sink
        }

        deinit {
            AdapterCapture.sink = monitor
        }

        /// Runs `body` against `EchoService` in process, the adapter on the client.
        @available(macOS 15.0, iOS 18.0, *)
        func withEcho(_ body: @Sendable (GRPCClient<InProcessTransport.Client>) async throws -> Void) async throws {
            let transport = InProcessTransport()
            try await withGRPCServer(transport: transport.server, services: [EchoService()]) { _ in
                try await withGRPCClient(transport: transport.client, interceptors: [AppMonitorGRPCInterceptor()]) {
                    try await body($0)
                }
            }
        }

        func events() throws -> [(type: String, message: any SwiftProtobuf.Message)] {
            try sink.all.map { envelope in
                let message: any SwiftProtobuf.Message =
                    switch envelope.type {
                    case "RequestStarted": try Appmonitor_V1_RequestStarted(serializedBytes: envelope.payload)
                    case "ResponseReceived": try Appmonitor_V1_ResponseReceived(serializedBytes: envelope.payload)
                    case "RequestFailed": try Appmonitor_V1_RequestFailed(serializedBytes: envelope.payload)
                    default: try Appmonitor_V1_WebSocketFrame(serializedBytes: envelope.payload)
                    }
                return (envelope.type, message)
            }
        }

        func bytes(_ value: String) throws -> Data { try Text(value).serializedBytes() }

        @available(macOS 15.0, iOS 18.0, *)
        @Test func unaryCallIsOneExchangeWithBodiesAndStatus() async throws {
            try await withEcho { client in
                let reply = try await client.unary(
                    request: ClientRequest(message: Text("hello"), metadata: ["authorization": "secret", "tag": "a"]),
                    descriptor: EchoService.get, serializer: ProtoCoder<Text>(), deserializer: ProtoCoder<Text>(),
                    options: .defaults
                ) { try $0.message }
                #expect(reply.value == "hello")
            }
            let events = try events()
            #expect(events.map(\.type) == ["RequestStarted", "ResponseReceived"])
            let started = try #require(events[0].message as? Appmonitor_V1_RequestStarted)
            #expect(started.method == "POST")
            #expect(started.url == "grpc://in-process/echo.Echo/Get")
            #expect(started.captureLayer == .adapter)
            #expect(started.headers.map { "\($0.name)=\($0.value)" } == ["authorization=\(mask)", "tag=a"])
            #expect(started.body == (try bytes("hello")))
            #expect(started.bodySize == Int64(try bytes("hello").count))
            let response = try #require(events[1].message as? Appmonitor_V1_ResponseReceived)
            #expect(response.requestID == started.requestID)
            #expect(response.statusCode == 200)
            #expect(response.body == (try bytes("hello")))
            let headers = response.headers.map { "\($0.name)=\($0.value)" }
            #expect(headers.contains("echo=yes"))
            #expect(headers.contains("done=1"))
            #expect(headers.last == "grpc-status=0")
        }

        @available(macOS 15.0, iOS 18.0, *)
        @Test func serverStreamingMessagesAreReceivedFrames() async throws {
            try await withEcho { client in
                let words = try await client.serverStreaming(
                    request: ClientRequest(message: Text("one two three")),
                    descriptor: EchoService.expand, serializer: ProtoCoder<Text>(), deserializer: ProtoCoder<Text>(),
                    options: .defaults
                ) { response in try await response.messages.reduce(into: []) { $0.append($1.value) } }
                #expect(words == ["one", "two", "three"])
            }
            let events = try events()
            #expect(
                events.map(\.type) == [
                    "RequestStarted", "WebSocketFrame", "WebSocketFrame", "WebSocketFrame", "ResponseReceived",
                ])
            let started = try #require(events[0].message as? Appmonitor_V1_RequestStarted)
            #expect(started.body == (try bytes("one two three")))
            let frames = events[1...3].compactMap { $0.message as? Appmonitor_V1_WebSocketFrame }
            #expect(
                frames.allSatisfy {
                    $0.requestID == started.requestID && $0.direction == .received && $0.opcode == .binary
                }
            )
            #expect(frames.map(\.payload) == [try bytes("one"), try bytes("two"), try bytes("three")])
            let response = try #require(events[4].message as? Appmonitor_V1_ResponseReceived)
            #expect(response.body.isEmpty && !response.hasBodySize)
            #expect(response.headers.last.map { "\($0.name)=\($0.value)" } == "grpc-status=0")
        }

        @available(macOS 15.0, iOS 18.0, *)
        @Test func rejectedCallFailsWithItsStatus() async throws {
            try await withEcho { client in
                await #expect(throws: RPCError.self) {
                    try await client.unary(
                        request: ClientRequest(message: Text("x")),
                        descriptor: EchoService.missing, serializer: ProtoCoder<Text>(),
                        deserializer: ProtoCoder<Text>(),
                        options: .defaults
                    ) { try $0.message }
                }
            }
            let events = try events()
            #expect(events.map(\.type) == ["RequestStarted", "RequestFailed"])
            let failed = try #require(events[1].message as? Appmonitor_V1_RequestFailed)
            #expect(failed.error == "grpc-status 5 (notFound): no such thing")
        }

        @available(macOS 15.0, iOS 18.0, *)
        @Test func statusAfterMessagesEndsTheResponse() async throws {
            try await withEcho { client in
                await #expect(throws: RPCError.self) {
                    try await client.serverStreaming(
                        request: ClientRequest(message: Text("x")),
                        descriptor: EchoService.broken, serializer: ProtoCoder<Text>(),
                        deserializer: ProtoCoder<Text>(),
                        options: .defaults
                    ) { response in for try await _ in response.messages {} }
                }
            }
            let events = try events()
            #expect(events.map(\.type) == ["RequestStarted", "WebSocketFrame", "ResponseReceived"])
            let response = try #require(events[2].message as? Appmonitor_V1_ResponseReceived)
            #expect(response.statusCode == 200)
            #expect(
                Array(response.headers.suffix(2)).map { "\($0.name)=\($0.value)" } == [
                    "grpc-status=13", "grpc-message=broke",
                ])
        }

        @available(macOS 15.0, iOS 18.0, *)
        @Test func idleMonitorSendsNothing() async throws {
            sink.options = nil
            try await withEcho { client in
                let reply = try await client.unary(
                    request: ClientRequest(message: Text("quiet")),
                    descriptor: EchoService.get, serializer: ProtoCoder<Text>(), deserializer: ProtoCoder<Text>(),
                    options: .defaults
                ) { try $0.message }
                #expect(reply.value == "quiet")
            }
            #expect(sink.all.isEmpty)
        }
    }
}
