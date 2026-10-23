#if DEBUG
import Foundation

/// The value a masked header, query or fragment parameter, URL user name or password is sent with (ADR-0018).
let mask = "██"

let defaultMaskedHeaders: Set<String> = ["Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"]

/// Response headers whose value is a URL, masked as one (ADR-0033).
let urlResponseHeaders: Set<String> = ["Location", "Content-Location"]

/// ADR-0036 A: Android's rule (SDD 05), names compared case-insensitively, the name kept and the value
/// replaced by `mask`; `protocol/fixtures/masking.json` pins both SDKs to it.
struct Masking: Sendable {
    private let enabled: Bool
    private let headerNames: Set<String>
    private let queryNames: Set<String>
    private let urlHeaderNames = Set(urlResponseHeaders.map { $0.lowercased() })

    init(_ options: AppMonitorOptions) {
        enabled = options.masking  // ADR-0034
        headerNames = Set(defaultMaskedHeaders.union(options.maskedHeaders).map { $0.lowercased() })
        queryNames = Set(options.maskedQueryParameters.map { $0.lowercased() })
    }

    /// With `base`, a `urlResponseHeaders` value is resolved against it and masked by `url(_:)`; a value
    /// with another scheme is masked as written (ADR-0044).
    func headers(_ headers: [(name: String, value: String)], base: URL? = nil) -> [Appmonitor_V1_Header] {
        headers.map { name, value in
            var header = Appmonitor_V1_Header()
            header.name = name
            header.value = value
            guard enabled else { return header }
            if headerNames.contains(name.lowercased()) {
                header.value = mask
            } else if let base, urlHeaderNames.contains(name.lowercased()),
                let resolved = URL(string: value, relativeTo: base)?.absoluteURL
            {
                let http = ["http", "https"].contains(resolved.scheme?.lowercased())
                header.value = url(http ? resolved.absoluteString : value)
            }
            return header
        }
    }

    /// The URL as the app wrote it, with a non-empty user name and password, each `maskedQueryParameters`
    /// value in the query and each `name=value` field of the fragment named there replaced by `mask`.
    func url(_ string: String) -> String {
        guard enabled, let components = URLComponents(string: string) else { return string }
        var replacements: [(Range<String.Index>, String)] = []
        if let range = components.rangeOfUser, !range.isEmpty { replacements.append((range, mask)) }
        if let range = components.rangeOfPassword, !range.isEmpty { replacements.append((range, mask)) }
        if let range = components.rangeOfQuery, !queryNames.isEmpty {
            replacements.append((range, fields(String(string[range]), maskBare: true)))
        }
        if let range = components.rangeOfFragment, !queryNames.isEmpty {
            replacements.append((range, fields(String(string[range]), maskBare: false)))
        }
        var masked = string
        for (range, value) in replacements.sorted(by: { $0.0.lowerBound > $1.0.lowerBound }) {
            masked.replaceSubrange(range, with: value)
        }
        return masked
    }

    /// `&`-separated fields whose form-decoded name is masked become `name=██`; a query field without `=`
    /// is masked too (`maskBare`), as OkHttp's rebuilt query does; other fields stay as written.
    private func fields(_ encoded: String, maskBare: Bool) -> String {
        encoded.split(separator: "&", omittingEmptySubsequences: false).map { field in
            let parts = field.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let name = String(parts[0])
            guard parts.count == 2 || maskBare else { return String(field) }
            let decoded = name.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? name
            return queryNames.contains(decoded.lowercased()) ? "\(name)=\(mask)" : String(field)
        }.joined(separator: "&")
    }
}
#endif
