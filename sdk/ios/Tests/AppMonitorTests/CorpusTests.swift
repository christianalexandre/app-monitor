import Foundation
import Testing

@testable import AppMonitor

/// Wire's random messages of `protocol/fixtures/corpus/` (`CorpusTest` in `protocol`), read back byte for
/// byte, and the same records mutated: every input decodes or throws `ProtoDecodingError` (ADR-0160).
struct CorpusTests {
    /// What the tests ask of one message type, by its Wire name.
    struct Codec: Sendable {
        /// The bytes written back and the unknown fields kept.
        let reencode: @Sendable ([UInt8]) throws -> (bytes: [UInt8], unknown: Int)
        /// Whether a decoded message, written and read again, is the same message.
        let stable: @Sendable ([UInt8]) throws -> Bool
    }

    static func codec<M: ProtoMessage>(_: M.Type) -> Codec {
        Codec(
            reencode: { bytes in
                let message = try M(serializedBytes: bytes)
                return (try message.serializedBytes(), message.unknownFields.data.count)
            },
            stable: { bytes in
                let message = try M(serializedBytes: bytes)
                return try M(serializedBytes: try message.serializedBytes() as [UInt8]) == message
            }
        )
    }

    static let codecs: [String: Codec] = [
        "Envelope": codec(Appmonitor_V1_Envelope.self),
        "Hello": codec(Appmonitor_V1_Hello.self),
        "HelloAck": codec(Appmonitor_V1_HelloAck.self),
        "Header": codec(Appmonitor_V1_Header.self),
        "RequestStarted": codec(Appmonitor_V1_RequestStarted.self),
        "ResponseReceived": codec(Appmonitor_V1_ResponseReceived.self),
        "RequestFailed": codec(Appmonitor_V1_RequestFailed.self),
        "WebSocketFrame": codec(Appmonitor_V1_WebSocketFrame.self),
        "MockRules": codec(Appmonitor_V1_MockRules.self),
        "MockRule": codec(Appmonitor_V1_MockRule.self),
        "MockResponse": codec(Appmonitor_V1_MockResponse.self),
        "MockFailure": codec(Appmonitor_V1_MockFailure.self),
        "BreakpointRules": codec(Appmonitor_V1_BreakpointRules.self),
        "BreakpointRule": codec(Appmonitor_V1_BreakpointRule.self),
        "BreakpointHit": codec(Appmonitor_V1_BreakpointHit.self),
        "BreakpointResume": codec(Appmonitor_V1_BreakpointResume.self),
        "BreakpointAbort": codec(Appmonitor_V1_BreakpointAbort.self),
        "ReplayRequest": codec(Appmonitor_V1_ReplayRequest.self),
        "ReplayRefused": codec(Appmonitor_V1_ReplayRefused.self),
    ]

    static let corpus = FixtureTests.fixtures.appendingPathComponent("corpus")

    /// The varint-length-prefixed records of one corpus file.
    static func records(_ name: String) throws -> [[UInt8]] {
        let data = [UInt8](try Data(contentsOf: corpus.appendingPathComponent("\(name).bin")))
        var records: [[UInt8]] = []
        var index = 0
        while index < data.count {
            var length = 0
            var shift = 0
            while true {
                let byte = data[index]
                index += 1
                length |= Int(byte & 0x7F) << shift
                if byte & 0x80 == 0 { break }
                shift += 7
            }
            records.append(Array(data[index..<index + length]))
            index += length
        }
        return records
    }

    @Test func everyMessageHasACorpus() throws {
        let files = try FileManager.default.contentsOfDirectory(atPath: Self.corpus.path)
        #expect(Set(files) == Set(Self.codecs.keys.map { "\($0).bin" }))
    }

    @Test(arguments: codecs.keys.sorted())
    func wiresRecordsComeBackByteForByte(name: String) throws {
        let codec = try #require(Self.codecs[name])
        let records = try Self.records(name)
        #expect(!records.isEmpty)
        for (index, record) in records.enumerated() {
            let (bytes, unknown) = try codec.reencode(record)
            #expect(bytes == record, "\(name) #\(index)")
            #expect(unknown == 0, "\(name) #\(index)")
        }
    }

    /// SplitMix64: the same mutations on every run and every machine.
    struct Seeded: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    static func mutate(_ record: [UInt8], _ random: inout Seeded) -> [UInt8] {
        var bytes = record
        for _ in 0..<Int.random(in: 1...3, using: &random) {
            switch Int.random(in: 0..<4, using: &random) {
            case 0 where !bytes.isEmpty:
                let index = Int.random(in: 0..<bytes.count, using: &random)
                bytes[index] ^= UInt8(1) << UInt8.random(in: 0..<8, using: &random)
            case 1 where !bytes.isEmpty:
                bytes.removeLast(Int.random(in: 1...bytes.count, using: &random))
            case 2:
                let at = Int.random(in: 0...bytes.count, using: &random)
                bytes.insert(UInt8.random(in: 0...255, using: &random), at: at)
            default:
                bytes[Int.random(in: 0...bytes.count, using: &random)..<bytes.count] = []
                let count = Int.random(in: 0..<12, using: &random)
                bytes += (0..<count).map { _ in UInt8.random(in: 0...255, using: &random) }
            }
        }
        return bytes
    }

    @Test(arguments: codecs.keys.sorted())
    func mutatedRecordsDecodeOrThrow(name: String) throws {
        let codec = try #require(Self.codecs[name])
        var random = Seeded(state: name.utf8.reduce(UInt64(2026)) { $0 &* 31 &+ UInt64($1) })
        for record in try Self.records(name) {
            for _ in 0..<200 {
                let bytes = Self.mutate(record, &random)
                do {
                    #expect(try codec.stable(bytes), "\(name) \(bytes)")
                } catch is ProtoDecodingError {
                    continue
                }
            }
        }
    }
}
