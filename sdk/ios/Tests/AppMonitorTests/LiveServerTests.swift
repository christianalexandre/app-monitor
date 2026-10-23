import Foundation
import Testing

@testable import AppMonitor

// Against a running `make server`: APPMONITOR_LIVE_HOST=127.0.0.1 swift test --filter LiveServerTests
struct LiveServerTests {
    static let host = ProcessInfo.processInfo.environment["APPMONITOR_LIVE_HOST"]

    @Test(.enabled(if: host != nil))
    func samplesReachARunningServer() async throws {
        let client = MonitorClient(
            hello: MonitorClientTests.hello,
            discover: manualDiscovery(Endpoint(host: try #require(Self.host))),
            log: { print($0) }
        )
        client.start()
        for (name, envelope) in try Samples.envelopes().sorted(by: { $0.key < $1.key })
        where envelope.channel == ProtocolConstants.channelNetwork {
            print("sending \(name)")
            client.send(envelope)
        }
        #expect(await eventually(timeout: 10) { client.state == .connected })
        try await Task.sleep(nanoseconds: 1_000_000_000)
        client.stop()
        await client.join()
        #expect(client.isReleased)
    }
}
