#if DEBUG
/// Retry delays doubling from `initial` to `max`, each with ±`jitter` (ADR-0022). Not thread-safe.
struct Backoff {
    private let initial: Double
    private let max: Double
    private let jitter: Double
    private let random: @Sendable (ClosedRange<Double>) -> Double
    private var next: Double

    init(
        initial: Double = 1,
        max: Double = 30,
        jitter: Double = 0.2,
        random: @escaping @Sendable (ClosedRange<Double>) -> Double = { Double.random(in: $0) }
    ) {
        self.initial = initial
        self.max = max
        self.jitter = jitter
        self.random = random
        self.next = initial
    }

    /// Seconds to wait before the next attempt.
    mutating func nextDelay() -> Double {
        let base = next
        next = Swift.min(next * 2, max)
        return jitter == 0 ? base : base * (1 + random(-jitter...jitter))
    }

    mutating func reset() {
        next = initial
    }

    /// The next delay is the longest one, e.g. after the desktop rejected the device.
    mutating func exhaust() {
        next = max
    }
}
#endif
