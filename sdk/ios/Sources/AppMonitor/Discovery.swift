import Foundation

/// Where the simulator reaches its Mac's loopback: it shares the Mac's network stack (ADR-0002).
let simulatorHost = "127.0.0.1"

/// One mDNS answer for a desktop; `path` is nil until its TXT record is seen, `id` nil without one in it
/// (ADR-0168).
struct DesktopAnswer: Equatable, Sendable {
    var name: String
    var host: String
    var port: UInt16 = ProtocolConstants.defaultPort
    var path: String?
    var id: String?
}

/// `DesktopSelector` of the `protocol` module in Swift (ADR-0024): names match case-insensitively, the
/// first desktop answered with an address is kept and every other name ignored, and its path waits for
/// the TXT record up to `DiscoveryConfig.txtGrace`. With `pinnedID`, only a desktop whose TXT record carries
/// that id is kept (ADR-0168). Not thread-safe: fed from the client's queue.
struct DesktopSelector {
    var pinnedID: String?
    private var kept: DesktopAnswer?

    init(pinnedID: String? = nil) { self.pinnedID = pinnedID }

    /// True once a desktop is kept: the caller then waits at most the TXT grace for its TXT record.
    var hasCandidate: Bool { kept != nil }

    /// Takes `answer`; returns the kept desktop once its path is known.
    mutating func offer(_ answer: DesktopAnswer) -> DesktopAnswer? {
        if let pinnedID, answer.path == nil ? kept == nil : answer.id != pinnedID { return nil }
        if let kept, kept.name.caseInsensitiveCompare(answer.name) != .orderedSame { return nil }
        var merged = answer
        merged.path = answer.path ?? kept?.path
        merged.id = answer.path == nil ? kept?.id : answer.id
        kept = merged
        return merged.path == nil ? nil : merged
    }

    /// The kept desktop when the grace period ends, with the default path if no TXT came.
    func settle() -> DesktopAnswer? {
        guard var kept else { return nil }
        kept.path = kept.path ?? ProtocolConstants.websocketPath
        return kept
    }
}

/// A platform mDNS browser for `_appmonitor._tcp`. Calls back on `queue` only; answers may repeat per
/// name. `onFailure` means the browser cannot run. The returned closure, called on `queue`, stops it.
protocol MdnsBrowser: Sendable {
    func browse(
        on queue: DispatchQueue,
        onAnswer: @escaping @Sendable (DesktopAnswer) -> Void,
        onFailure: @escaping @Sendable (String) -> Void
    ) -> @Sendable () -> Void
}

/// Bounds of SDD 06's discovering state, in seconds; tests shorten them.
struct DiscoveryConfig: Sendable {
    var mdnsBeforeSimulatorHost: Double = 3
    var txtGrace: Double = 0.5
}

/// The failure `BonjourBrowser` reports when the user denied the app local network access.
let localNetworkDenied = "local network access denied"

/// The keys `info` lacks for mDNS on a device (ADR-0162): the usage text, and `_appmonitor._tcp` in
/// `NSBonjourServices`.
func missingLocalNetworkKeys(_ info: [String: Any]?) -> [String] {
    var missing: [String] = []
    if (info?["NSLocalNetworkUsageDescription"] as? String)?.isEmpty ?? true {
        missing.append("NSLocalNetworkUsageDescription")
    }
    if !((info?["NSBonjourServices"] as? [String])?.contains(ProtocolConstants.mdnsServiceType) ?? false) {
        missing.append("NSBonjourServices (\(ProtocolConstants.mdnsServiceType))")
    }
    return missing
}

/// The `Discover` of `MonitorClient` for `options` (SDD 06): a manual host is the only source, its pin
/// unchecked; else `mdns`, skipping desktops other than `pinned`'s (ADR-0168), then `simulatorHost` when
/// `onSimulator`. Missing `infoPlist` keys on a device, and a failing browser, are each logged once; the
/// failing browser finds nothing.
func discovery(
    _ options: AppMonitorOptions,
    mdns: MdnsBrowser,
    onSimulator: Bool,
    infoPlist: [String: Any]? = Bundle.main.infoDictionary,
    config: DiscoveryConfig = DiscoveryConfig(),
    log: @escaping @Sendable (String) -> Void,
    pinned: @escaping @Sendable () -> String? = { nil }
) -> Discover {
    if let host = options.host {
        ConnectionCapture.desktopFound(host: host, port: UInt16(options.port))
        return manualDiscovery(Endpoint(host: host, port: UInt16(options.port), checksPin: false))
    }
    let missing = missingLocalNetworkKeys(infoPlist)
    if !onSimulator, !missing.isEmpty {
        log(
            "Info.plist lacks \(missing.joined(separator: " and ")), so no desktop can be found: add the "
                + "README's Run Script (Installation, iOS) as the app target's last build phase"
        )
    }
    let failureLogged = QueueFlag()
    return { queue, found in
        let round = MdnsRound(
            queue: queue, onSimulator: onSimulator, config: config, pinnedID: pinned(), found: found)
        round.start(mdns) { reason in
            guard !failureLogged.value else { return }
            failureLogged.value = true
            log(
                reason == localNetworkDenied
                    ? "mDNS discovery failed (\(reason)): allow this app in Settings, Privacy & Security, "
                        + "Local Network, or set AppMonitorOptions.host"
                    : "mDNS discovery failed (\(reason)); on a device check NSLocalNetworkUsageDescription and "
                        + "NSBonjourServices in Info.plist, or set AppMonitorOptions.host"
            )
        }
        return { round.cancel() }
    }
}

/// One round of mDNS discovery, confined to the client's queue; reports at most one endpoint.
private final class MdnsRound: @unchecked Sendable {
    private let queue: DispatchQueue
    private let onSimulator: Bool
    private let config: DiscoveryConfig
    private var found: (@Sendable (Endpoint?) -> Void)?
    private var selector = DesktopSelector()
    private var stopBrowsing: (@Sendable () -> Void)?
    private var timers: [DispatchSourceTimer] = []

    init(
        queue: DispatchQueue, onSimulator: Bool, config: DiscoveryConfig, pinnedID: String? = nil,
        found: @escaping @Sendable (Endpoint?) -> Void
    ) {
        self.queue = queue
        self.onSimulator = onSimulator
        self.config = config
        self.selector = DesktopSelector(pinnedID: pinnedID)
        self.found = found
    }

    func start(_ mdns: MdnsBrowser, logFailure: @escaping @Sendable (String) -> Void) {
        dispatchPrecondition(condition: .onQueue(queue))
        if onSimulator {
            // Bounded so the simulator host is tried inside MonitorClient's discovery timeout.
            schedule(after: config.mdnsBeforeSimulatorHost) { $0.finish(nil) }
        }
        stopBrowsing = mdns.browse(
            on: queue,
            onAnswer: { [weak self] answer in self?.offer(answer) },
            onFailure: { [weak self] reason in
                guard let self, self.found != nil else { return }
                logFailure(reason)
                self.finish(nil)
            }
        )
        if found == nil { cancel() }
    }

    func cancel() {
        dispatchPrecondition(condition: .onQueue(queue))
        found = nil
        for timer in timers { timer.cancel() }
        timers = []
        let stop = stopBrowsing
        stopBrowsing = nil
        stop?()
    }

    private func offer(_ answer: DesktopAnswer) {
        guard found != nil else { return }
        ConnectionCapture.desktopFound(host: answer.host, port: answer.port)
        let hadCandidate = selector.hasCandidate
        if let chosen = selector.offer(answer) {
            finish(chosen)
        } else if !hadCandidate, selector.hasCandidate {
            schedule(after: config.txtGrace) { $0.finish($0.selector.settle()) }
        }
    }

    /// Reports `answer`, or the simulator host when there is none and the round runs on the simulator.
    private func finish(_ answer: DesktopAnswer?) {
        guard let found else { return }
        cancel()
        if let answer {
            found(Endpoint(host: answer.host, port: answer.port, path: answer.path ?? ProtocolConstants.websocketPath))
        } else {
            found(onSimulator ? Endpoint(host: simulatorHost) : nil)
        }
    }

    private func schedule(after seconds: Double, _ handler: @escaping @Sendable (MdnsRound) -> Void) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + seconds)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            handler(self)
        }
        timer.resume()
        timers.append(timer)
    }
}

/// A flag read and written only on the client's queue.
private final class QueueFlag: @unchecked Sendable {
    var value = false
}
