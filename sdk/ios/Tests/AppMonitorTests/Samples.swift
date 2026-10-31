import Foundation

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
        $0.appName = "Sample"
    }

    static let helloAck = Appmonitor_V1_HelloAck.with {
        $0.protocolVersion = ProtocolConstants.version
        $0.sessionID = "session-0001"
        $0.channels = [ProtocolConstants.channelCore, ProtocolConstants.channelNetwork]
    }

    static let deliveryReport = Appmonitor_V1_DeliveryReport.with {
        $0.evicted = 3
        $0.oversized = 1
        $0.previousSessionID = "session-0000"
        $0.previousSessionWritten = 42
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

    static let connectionStarted = Appmonitor_V1_RequestStarted.with {
        $0.requestID = "conn-0001"
        $0.method = "CONNECT"
        $0.url = "tcp://push.example.com:5223"
        $0.captureLayer = .connection
    }

    static let connectionClosed = Appmonitor_V1_ResponseReceived.with {
        $0.requestID = "conn-0001"
        $0.bodySize = 2_048
        $0.sentSize = 512
    }

    static let mockRules = Appmonitor_V1_MockRules.with {
        $0.rules = [
            .with {
                $0.id = 7
                $0.host = "api.example.com"
                $0.path = "/v1/items/*"
                $0.response = .with {
                    $0.statusCode = 503
                    $0.headers = [header("Retry-After", "30")]
                    $0.body = Data(#"{"error":"maintenance"}"#.utf8)
                }
            },
            .with {
                $0.id = 9
                $0.method = "POST"
                $0.host = "api.example.com"
                $0.path = "/v1/orders"
                $0.delayMs = 2_000
                $0.failure = .with { $0.message = "connection reset" }
            },
        ]
    }

    static let breakpointRules = Appmonitor_V1_BreakpointRules.with {
        $0.rules = [
            .with {
                $0.id = 3
                $0.host = "api.example.com"
                $0.path = "/v1/items/*"
                $0.request = true
                $0.response = true
                $0.timeoutMs = 60_000
            },
            .with {
                $0.id = 4
                $0.method = "POST"
                $0.host = "api.example.com"
                $0.path = "/v1/orders"
                $0.request = true
                $0.timeoutMs = 5_000
            },
        ]
    }

    static let breakpointHit = Appmonitor_V1_BreakpointHit.with {
        $0.ruleID = 3
        $0.stage = .response
        $0.request = .with {
            $0.requestID = "req-0004"
            $0.method = "GET"
            $0.url = "https://api.example.com/v1/items/42"
            $0.headers = [header("Authorization", "██")]
            $0.bodySize = 0
            $0.captureLayer = .adapter
        }
        $0.response = .with {
            $0.requestID = "req-0004"
            $0.statusCode = 200
            $0.headers = [header("Content-Type", "application/json")]
            $0.body = Data(#"{"id":42}"#.utf8)
            $0.bodySize = 9
        }
        $0.timeoutMs = 60_000
    }

    static let breakpointResume = Appmonitor_V1_BreakpointResume.with {
        $0.requestID = "req-0004"
        $0.stage = .response
        $0.statusCode = 404
        $0.setHeaders = [header("Cache-Control", "no-store")]
        $0.removedHeaders = ["Content-Type"]
        $0.body = Data()
    }

    static let breakpointAbort = Appmonitor_V1_BreakpointAbort.with {
        $0.requestID = "req-0005"
        $0.stage = .request
        $0.message = "aborted on the desktop"
    }

    static let replayStarted = Appmonitor_V1_RequestStarted.with {
        $0 = requestStarted
        $0.requestID = "req-0006"
        $0.replayOf = requestId
    }

    static let replayRequest = Appmonitor_V1_ReplayRequest.with { $0.requestID = requestId }

    static let replayRefused = Appmonitor_V1_ReplayRefused.with {
        $0.requestID = "req-0007"
        $0.reason = "no longer kept by the SDK"
    }

    /// Fixture file name to the message its envelope carries.
    static let messages: [String: (channel: String, type: String, message: any ProtoMessage)] = [
        "hello.bin": (ProtocolConstants.channelCore, "Hello", hello),
        "hello_ack.bin": (ProtocolConstants.channelCore, "HelloAck", helloAck),
        "delivery_report.bin": (ProtocolConstants.channelCore, "DeliveryReport", deliveryReport),
        "request_started.bin": (ProtocolConstants.channelNetwork, "RequestStarted", requestStarted),
        "response_received.bin": (ProtocolConstants.channelNetwork, "ResponseReceived", responseReceived),
        "request_failed.bin": (ProtocolConstants.channelNetwork, "RequestFailed", requestFailed),
        "websocket_frame.bin": (ProtocolConstants.channelNetwork, "WebSocketFrame", webSocketFrame),
        "connection_started.bin": (ProtocolConstants.channelNetwork, "RequestStarted", connectionStarted),
        "connection_closed.bin": (ProtocolConstants.channelNetwork, "ResponseReceived", connectionClosed),
        "mock_rules.bin": (ProtocolConstants.channelInteraction, "MockRules", mockRules),
        "breakpoint_rules.bin": (ProtocolConstants.channelInteraction, "BreakpointRules", breakpointRules),
        "breakpoint_hit.bin": (ProtocolConstants.channelInteraction, "BreakpointHit", breakpointHit),
        "breakpoint_resume.bin": (ProtocolConstants.channelInteraction, "BreakpointResume", breakpointResume),
        "breakpoint_abort.bin": (ProtocolConstants.channelInteraction, "BreakpointAbort", breakpointAbort),
        "replay_started.bin": (ProtocolConstants.channelNetwork, "RequestStarted", replayStarted),
        "replay_request.bin": (ProtocolConstants.channelInteraction, "ReplayRequest", replayRequest),
        "replay_refused.bin": (ProtocolConstants.channelInteraction, "ReplayRefused", replayRefused),
    ]

    /// Envelopes stamped later than `timestampMs`, by file name.
    static let laterBy: [String: Int64] = ["connection_closed.bin": 1_500]

    /// Fixture file name to the envelope it holds.
    static func envelopes() throws -> [String: Appmonitor_V1_Envelope] {
        try Dictionary(
            uniqueKeysWithValues: messages.map { name, entry in
                (
                    name,
                    try Appmonitor_V1_Envelope.with {
                        $0.channel = entry.channel
                        $0.type = entry.type
                        $0.payload = try entry.message.serializedBytes()
                        $0.timestampMs = timestampMs + (laterBy[name] ?? 0)
                    }
                )
            })
    }

    private static func header(_ name: String, _ value: String) -> Appmonitor_V1_Header {
        .with {
            $0.name = name
            $0.value = value
        }
    }
}
