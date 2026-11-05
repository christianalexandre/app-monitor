import Foundation
import Network

/// The `MdnsBrowser` of a real app: `NWBrowser` with TXT records, each new name resolved to an IPv4
/// address by a TCP probe to its service endpoint (ADR-0042).
struct BonjourBrowser: MdnsBrowser {
    func browse(
        on queue: DispatchQueue,
        onAnswer: @escaping @Sendable (DesktopAnswer) -> Void,
        onFailure: @escaping @Sendable (String) -> Void
    ) -> @Sendable () -> Void {
        let session = BonjourSession(queue: queue, onAnswer: onAnswer, onFailure: onFailure)
        session.start()
        return { session.cancel() }
    }
}

/// What a browser state reports as a failure, nil for none. iOS 16 fails a denied browse, later
/// versions wait on it: both are the denial.
func browseFailure(_ state: NWBrowser.State) -> String? {
    switch state {
    case .waiting(.dns(DNSServiceErrorType(kDNSServiceErr_PolicyDenied))),
        .failed(.dns(DNSServiceErrorType(kDNSServiceErr_PolicyDenied))):
        localNetworkDenied
    case .failed(let error):
        "\(error)"
    default:
        nil
    }
}

/// One browse, confined to `queue`. Names are keyed lowercased (RFC 4343).
private final class BonjourSession: @unchecked Sendable {
    private struct Address {
        var host: String
        var port: UInt16
    }

    private let queue: DispatchQueue
    private let onAnswer: @Sendable (DesktopAnswer) -> Void
    private let onFailure: @Sendable (String) -> Void
    private var browser: NWBrowser?
    private var probes: [String: NWConnection] = [:]
    private var addresses: [String: Address] = [:]
    private var paths: [String: String] = [:]
    private var ids: [String: String] = [:]

    init(
        queue: DispatchQueue,
        onAnswer: @escaping @Sendable (DesktopAnswer) -> Void,
        onFailure: @escaping @Sendable (String) -> Void
    ) {
        self.queue = queue
        self.onAnswer = onAnswer
        self.onFailure = onFailure
    }

    func start() {
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: ProtocolConstants.mdnsServiceType, domain: nil),
            using: NWParameters()
        )
        self.browser = browser
        browser.stateUpdateHandler = { [weak self, weak browser] state in
            guard let self, let browser, browser === self.browser else { return }
            if let failure = browseFailure(state) { self.onFailure(failure) }
        }
        browser.browseResultsChangedHandler = { [weak self, weak browser] _, changes in
            guard let self, let browser, browser === self.browser else { return }
            for change in changes {
                switch change {
                case .added(let result), .changed(_, let result, _): self.seen(result)
                default: break
                }
            }
        }
        browser.start(queue: queue)
    }

    func cancel() {
        dispatchPrecondition(condition: .onQueue(queue))
        browser?.cancel()
        browser = nil
        for probe in probes.values { probe.cancel() }
        probes = [:]
    }

    private func seen(_ result: NWBrowser.Result) {
        guard case .service(let name, _, _, _) = result.endpoint else { return }
        let key = name.lowercased()
        if case .bonjour(let txt) = result.metadata, let path = txt[ProtocolConstants.mdnsTxtPath] {
            paths[key] = path
            ids[key] = txt[ProtocolConstants.mdnsTxtID]
        }
        if let address = addresses[key] {
            onAnswer(DesktopAnswer(name: name, host: address.host, port: address.port, path: paths[key], id: ids[key]))
        } else if probes[key] == nil {
            probe(result.endpoint, name: name, key: key)
        }
    }

    /// Opens and closes a TCP connection to learn the address Network.framework resolves (ADR-0042).
    private func probe(_ endpoint: NWEndpoint, name: String, key: String) {
        let parameters = NWParameters.tcp
        (parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let probe = NWConnection(to: endpoint, using: parameters)
        probes[key] = probe
        probe.stateUpdateHandler = { [weak self, weak probe] state in
            guard let self, let probe, probe === self.probes[key] else { return }
            switch state {
            case .ready:
                probe.cancel()
                guard case .hostPort(.ipv4(let ip), let port)? = probe.currentPath?.remoteEndpoint else { return }
                let address = Address(host: ip.rawValue.map(String.init).joined(separator: "."), port: port.rawValue)
                self.addresses[key] = address
                self.onAnswer(
                    DesktopAnswer(
                        name: name, host: address.host, port: address.port, path: self.paths[key], id: self.ids[key]))
            case .failed:
                probe.cancel()
                self.probes[key] = nil
            default:
                break
            }
        }
        probe.start(queue: queue)
    }
}
