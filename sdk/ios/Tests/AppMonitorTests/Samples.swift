import Foundation
import SwiftProtobuf

@testable import AppMonitor

// Golden values behind protocol/fixtures, field for field the Kotlin
// `appmonitor.protocol.Samples`: a mismatch is a contract break.
enum Samples {
    static let timestampMs: Int64 = 1_790_000_000_000
    static let requestId = "req-0001"

    static let hello = Appmonitor_V1_Hello.with {
        $0.protocolVersion = ProtocolConstants.version
        $0.platform = .android
        $0.deviceID = "device-0001"
        $0.deviceName = "Pixel 9 (emulator)"
        $0.osVersion = "Android 16"
        $0.appID = "dev.example.sample"
        $0.appVersion = "1.0.0"
        $0.sdkVersion = "0.1.0"
        $0.channels = [ProtocolConstants.channelCore, ProtocolConstants.channelNetwork]
        $0.sdkName = "kotlin"
    }

    static let helloAck = Appmonitor_V1_HelloAck.with {
        $0.protocolVersion = ProtocolConstants.version
        $0.sessionID = "session-0001"
        $0.channels = [ProtocolConstants.channelCore, ProtocolConstants.channelNetwork]
    }

    static let requestStarted = Appmonitor_V1_RequestStarted.with {
        $0.requestID = requestId
        $0.method = "POST"
        $0.url = "https://api.example.com/v1/items?page=2"
        $0.headers = [
            header("Content-Type", "application/json"),
            header("Accept", "application/json"),
        ]
        $0.body = Data(#"{"name":"café"}"#.utf8)
        $0.bodySize = 16
        $0.captureLayer = .adapter
    }

    static let responseReceived = Appmonitor_V1_ResponseReceived.with {
        $0.requestID = requestId
        $0.statusCode = 201
        $0.headers = [header("Content-Type", "application/octet-stream")]
        $0.body = Data([0x00, 0x7F, 0x80, 0xFF])
        $0.bodyTruncated = true
        $0.bodySize = 2_097_152
    }

    static let requestFailed = Appmonitor_V1_RequestFailed.with {
        $0.requestID = "req-0002"
        $0.error = "java.net.SocketTimeoutException: timeout"
    }

    static let webSocketFrame = Appmonitor_V1_WebSocketFrame.with {
        $0.requestID = "req-0003"
        $0.direction = .received
        $0.opcode = .text
        $0.payload = Data("ping".utf8)
        $0.payloadSize = 4
    }

    /// Fixture file name to the message its envelope carries.
    static let messages: [String: (channel: String, type: String, message: any SwiftProtobuf.Message)] = [
        "hello.bin": (ProtocolConstants.channelCore, "Hello", hello),
        "hello_ack.bin": (ProtocolConstants.channelCore, "HelloAck", helloAck),
        "request_started.bin": (ProtocolConstants.channelNetwork, "RequestStarted", requestStarted),
        "response_received.bin": (ProtocolConstants.channelNetwork, "ResponseReceived", responseReceived),
        "request_failed.bin": (ProtocolConstants.channelNetwork, "RequestFailed", requestFailed),
        "websocket_frame.bin": (ProtocolConstants.channelNetwork, "WebSocketFrame", webSocketFrame),
    ]

    /// Fixture file name to the envelope it holds.
    static func envelopes() throws -> [String: Appmonitor_V1_Envelope] {
        try messages.mapValues { entry in
            try .with {
                $0.channel = entry.channel
                $0.type = entry.type
                $0.payload = try entry.message.serializedBytes()
                $0.timestampMs = timestampMs
            }
        }
    }

    private static func header(_ name: String, _ value: String) -> Appmonitor_V1_Header {
        .with {
            $0.name = name
            $0.value = value
        }
    }
}
