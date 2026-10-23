import Foundation
import SwiftProtobuf
import Testing

@testable import AppMonitor

// Same checks as the Kotlin FixtureTest, on the files Kotlin wrote (ADR-0008).
struct FixtureTests {
    static let fixtures = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .appendingPathComponent("../../../../protocol/fixtures")
        .standardizedFileURL

    static func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: fixtures.appendingPathComponent(name))
    }

    @Test func everyFixtureHasASample() throws {
        let files = try FileManager.default.contentsOfDirectory(atPath: Self.fixtures.path)
            .filter { $0.hasSuffix(".bin") }
        #expect(Set(files) == Set(try Samples.envelopes().keys))
    }

    @Test func samplesEncodeToFixtureBytes() throws {
        for (name, envelope) in try Samples.envelopes() {
            let bytes: Data = try envelope.serializedBytes()
            #expect(bytes == (try Self.fixture(name)), "\(name)")
        }
    }

    @Test func fixturesDecodeToSamples() throws {
        for (name, expected) in try Samples.envelopes() {
            let decoded = try Appmonitor_V1_Envelope(serializedBytes: try Self.fixture(name))
            #expect(decoded == expected, "\(name)")
            #expect(decoded.unknownFields.data.isEmpty, "\(name)")
        }
    }

    @Test(arguments: Samples.messages.keys.sorted())
    func payloadDecodesToItsMessage(name: String) throws {
        let expected = try #require(Samples.messages[name]).message
        let envelope = try Appmonitor_V1_Envelope(serializedBytes: try Self.fixture(name))
        let decoded = try type(of: expected).init(serializedBytes: envelope.payload)
        #expect(decoded.isEqualTo(message: expected), "\(name)")
        #expect(decoded.unknownFields.data.isEmpty, "\(name)")
        let reencoded: Data = try decoded.serializedBytes()
        #expect(reencoded == envelope.payload, "\(name)")
    }
}
