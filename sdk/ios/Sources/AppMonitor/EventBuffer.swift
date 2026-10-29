import Foundation

/// One encoded envelope waiting to be written, with its channel for the `HelloAck` filter.
struct BufferedFrame: Sendable {
    let channel: String
    let bytes: Data
}

/// Offline ring buffer (SDD 04, SDD 06): `offer` never blocks beyond the lock and drops the
/// oldest frame once `maxCount` or `maxBytes` is reached; `poll` serves a single consumer.
final class EventBuffer: @unchecked Sendable {
    static let maxCount = 4096
    static let maxBytes = 8 << 20

    private let maxCount: Int
    private let maxBytes: Int
    private let lock = NSLock()
    private var frames: [BufferedFrame] = []
    private var head = 0
    private var bytes = 0
    private var droppedCount = 0
    private var closed = false
    private var onAvailable: (@Sendable () -> Void)?

    init(maxCount: Int = EventBuffer.maxCount, maxBytes: Int = EventBuffer.maxBytes) {
        self.maxCount = maxCount
        self.maxBytes = maxBytes
    }

    /// Frames dropped to make room, for tests until DEBT-0023 reports them.
    var dropped: Int { lock.withLock { droppedCount } }

    var count: Int { lock.withLock { frames.count - head } }

    func offer(_ frame: BufferedFrame) {
        let wake: (@Sendable () -> Void)? = lock.withLock {
            guard !closed else { return nil }
            frames.append(frame)
            bytes += frame.bytes.count
            while frames.count - head > maxCount || bytes > maxBytes {
                bytes -= removeFirst().bytes.count
                droppedCount += 1
            }
            defer { onAvailable = nil }
            return onAvailable
        }
        wake?()
    }

    /// The oldest frame, removed; nil when empty, and then `onAvailable` runs once at the next `offer`.
    func poll(onAvailable: @escaping @Sendable () -> Void) -> BufferedFrame? {
        lock.withLock {
            guard frames.count > head else {
                self.onAvailable = closed ? nil : onAvailable
                return nil
            }
            let frame = removeFirst()
            bytes -= frame.bytes.count
            return frame
        }
    }

    /// Discards every frame; later offers are dropped.
    func close() {
        lock.withLock {
            closed = true
            frames = []
            head = 0
            bytes = 0
            onAvailable = nil
        }
    }

    private func removeFirst() -> BufferedFrame {
        let frame = frames[head]
        head += 1
        if head == frames.count {
            frames.removeAll(keepingCapacity: true)
            head = 0
        } else if head >= 1024 && head * 2 >= frames.count {
            frames.removeFirst(head)
            head = 0
        }
        return frame
    }
}
