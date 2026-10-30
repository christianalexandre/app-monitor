import Foundation
import Testing

@testable import AppMonitor

/// The codec beside SwiftProtobuf's rules the fixtures do not reach (ADR-0160).
struct ProtoWireTests {
    @Test func aNegativeInt32TakesTenBytes() throws {
        let response = Appmonitor_V1_ResponseReceived.with { $0.statusCode = -1 }
        let bytes: [UInt8] = try response.serializedBytes()
        #expect(bytes == [0x10] + [UInt8](repeating: 0xFF, count: 9) + [0x01])
        #expect(try Appmonitor_V1_ResponseReceived(serializedBytes: bytes).statusCode == -1)
    }

    @Test func anOptionalSetToItsDefaultIsWritten() throws {
        var started = Appmonitor_V1_RequestStarted()
        started.bodySize = 0
        let bytes: [UInt8] = try started.serializedBytes()
        #expect(bytes == [0x38, 0x00])
        let decoded = try Appmonitor_V1_RequestStarted(serializedBytes: bytes)
        #expect(decoded.hasBodySize)
        #expect(!Appmonitor_V1_RequestStarted().hasBodySize)
    }

    @Test func anUnknownFieldIsKeptAndWrittenBack() throws {
        // Field 1 "a", then field 99 (varint 7) and field 100 (bytes "x") that this schema does not know.
        let bytes: [UInt8] = [0x0A, 0x01, 0x61, 0x98, 0x06, 0x07, 0xA2, 0x06, 0x01, 0x78]
        let failed = try Appmonitor_V1_RequestFailed(serializedBytes: bytes)
        #expect(failed.requestID == "a")
        #expect(failed.unknownFields.data == Data(bytes[3...]))
        #expect(try failed.serializedBytes() as [UInt8] == bytes)
    }

    @Test func anEnumValueThisSchemaLacksIsKept() throws {
        let frame = try Appmonitor_V1_WebSocketFrame(serializedBytes: [0x10, 0x09])
        #expect(frame.direction == .UNRECOGNIZED(9))
        #expect(try frame.serializedBytes() as [UInt8] == [0x10, 0x09])
    }

    @Test func aOneofKeepsTheLastMemberRead() throws {
        var rule = Appmonitor_V1_MockRule()
        rule.response.statusCode = 200
        rule.failure.message = "down"
        #expect(rule.answer == .failure(.with { $0.message = "down" }))
        #expect(rule.response == Appmonitor_V1_MockResponse())
    }

    @Test func aMessageFieldReadTwiceIsMerged() throws {
        // BreakpointHit.request (3) as {method "GET"}, then as {url "u"}.
        let bytes: [UInt8] = [0x1A, 0x05, 0x12, 0x03, 0x47, 0x45, 0x54, 0x1A, 0x03, 0x1A, 0x01, 0x75]
        let hit = try Appmonitor_V1_BreakpointHit(serializedBytes: bytes)
        #expect(hit.request.method == "GET")
        #expect(hit.request.url == "u")
    }

    @Test func aOneofMemberReadTwiceIsMerged() throws {
        // MockRule.response (6) as {status_code 200}, then as {body "x"}.
        let bytes: [UInt8] = [0x32, 0x03, 0x08, 0xC8, 0x01, 0x32, 0x03, 0x1A, 0x01, 0x78]
        let rule = try Appmonitor_V1_MockRule(serializedBytes: bytes)
        #expect(rule.response.statusCode == 200)
        #expect(rule.response.body == Data("x".utf8))
    }

    @Test func anotherOneofMemberReplacesTheFirst() throws {
        // MockRule.response (6) as {status_code 200}, then failure (7) as {message "x"}.
        let bytes: [UInt8] = [0x32, 0x03, 0x08, 0xC8, 0x01, 0x3A, 0x03, 0x0A, 0x01, 0x78]
        let rule = try Appmonitor_V1_MockRule(serializedBytes: bytes)
        #expect(rule.answer == .failure(.with { $0.message = "x" }))
    }

    @Test(arguments: [
        // Field numbers run from 1 to 2^29 - 1.
        [0x00, 0x01] as [UInt8],
        [0x80, 0x80, 0x80, 0x80, 0x10, 0x01],
        // A varint's tenth byte holds bit 63 only.
        [0x18] + [UInt8](repeating: 0xFF, count: 9) + [0x7F],
        [0x18] + [UInt8](repeating: 0x80, count: 10) + [0x00],
    ])
    func malformedTagsAndVarintsThrow(bytes: [UInt8]) {
        #expect(throws: ProtoDecodingError.self) { try Appmonitor_V1_RequestFailed(serializedBytes: bytes) }
    }

    @Test(arguments: [
        ([0x0A, 0x05, 0x61] as [UInt8], ProtoDecodingError.truncated),
        // Field 3 is not in RequestFailed, so these reach skip.
        ([0x18, 0x80], .truncated),
        ([0x0A, 0x01, 0xFF], .invalidUTF8(field: 1)),
        ([0x08, 0x01], .wireTypeMismatch(field: 1)),
        ([0x1B], .unsupportedWireType(3)),
    ])
    func malformedBytesThrow(bytes: [UInt8], error: ProtoDecodingError) {
        #expect(throws: error) { try Appmonitor_V1_RequestFailed(serializedBytes: bytes) }
    }
}
