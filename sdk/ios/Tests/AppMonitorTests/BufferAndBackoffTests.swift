import Foundation
import Testing

@testable import AppMonitor

struct EventBufferTests {
    static func frame(_ byte: UInt8, size: Int = 1) -> BufferedFrame {
        BufferedFrame(channel: "network", bytes: Data(repeating: byte, count: size))
    }

    @Test func dropsTheOldestAtTheCountBound() {
        let buffer = EventBuffer(maxCount: 2, maxBytes: 100)
        for byte: UInt8 in 1...3 { buffer.offer(Self.frame(byte)) }
        #expect(buffer.dropped == 1)
        #expect(buffer.poll(onAvailable: {})?.bytes.first == 2)
        #expect(buffer.poll(onAvailable: {})?.bytes.first == 3)
        #expect(buffer.poll(onAvailable: {}) == nil)
    }

    @Test func dropsTheOldestAtTheByteBound() {
        let buffer = EventBuffer(maxCount: 100, maxBytes: 10)
        buffer.offer(Self.frame(1, size: 6))
        buffer.offer(Self.frame(2, size: 6))
        #expect(buffer.dropped == 1)
        #expect(buffer.count == 1)
        #expect(buffer.poll(onAvailable: {})?.bytes.first == 2)
    }

    @Test func emptyPollWakesOnTheNextOfferOnly() {
        let buffer = EventBuffer()
        let wakes = LockedLines()
        #expect(buffer.poll(onAvailable: { wakes.append("woken") }) == nil)
        buffer.offer(Self.frame(1))
        buffer.offer(Self.frame(2))
        #expect(wakes.all.count == 1)
    }

    @Test func closeDiscardsAndDropsLaterOffers() {
        let buffer = EventBuffer()
        buffer.offer(Self.frame(1))
        buffer.close()
        buffer.offer(Self.frame(2))
        #expect(buffer.count == 0)
    }

    @Test func keepsOrderAcrossManyFrames() {
        let buffer = EventBuffer()
        for index in 0..<5000 { buffer.offer(BufferedFrame(channel: "\(index)", bytes: Data())) }
        var seen: [String] = []
        while let frame = buffer.poll(onAvailable: {}) { seen.append(frame.channel) }
        #expect(seen == (904..<5000).map { "\($0)" })
    }

    @Test func oversizedEnvelopeIsDroppedBeforeTheBuffer() {
        let client = MonitorClient(hello: { .init() }, discover: { _, _ in {} })
        var envelope = Appmonitor_V1_Envelope()
        envelope.payload = Data(count: ProtocolConstants.maxFrameBytes)
        client.send(envelope)
        #expect(client.dropped == 1)
    }
}

struct BackoffTests {
    @Test func doublesUpToTheMaximumAndResets() {
        var backoff = Backoff(initial: 1, max: 30, jitter: 0)
        #expect((0..<7).map { _ in backoff.nextDelay() } == [1, 2, 4, 8, 16, 30, 30])
        backoff.reset()
        #expect(backoff.nextDelay() == 1)
        backoff.exhaust()
        #expect(backoff.nextDelay() == 30)
    }

    @Test func jitterStaysWithinTwentyPercent() {
        var backoff = Backoff(initial: 10, max: 10)
        for _ in 0..<100 { #expect((8...12).contains(backoff.nextDelay())) }
    }
}
