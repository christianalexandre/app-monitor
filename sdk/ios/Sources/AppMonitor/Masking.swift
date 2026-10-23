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

    /// With `base`, a `urlResponseHeaders` value is resolved against it and masked by `url(_:)`, or by
    /// `uri(_:)` when it is not an `http` URL or Foundation does not parse it (ADR-0044).
    func headers(_ headers: [(name: String, value: String)], base: URL? = nil) -> [Appmonitor_V1_Header] {
        headers.map { name, value in
            var header = Appmonitor_V1_Header()
            header.name = name
            header.value = value
            guard enabled else { return header }
            if headerNames.contains(name.lowercased()) {
                header.value = mask
            } else if let base, urlHeaderNames.contains(name.lowercased()) {
                if let resolved = URL(string: value, relativeTo: base)?.absoluteURL,
                    ["http", "https"].contains(resolved.scheme?.lowercased())
                {
                    header.value = url(resolved.absoluteString)
                } else {
                    header.value = uri(value)
                }
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

    /// The URL rule by RFC 3986 generic syntax, only the masked spans changed, as OkHttp's `uri`; a value
    /// without a scheme gets it too.
    private func uri(_ value: String) -> String {
        let fragmentAt = value.firstIndex(of: "#") ?? value.endIndex
        let queryAt = value[..<fragmentAt].firstIndex(of: "?") ?? fragmentAt
        var masked = userInfo(value[..<queryAt])
        if queryAt < fragmentAt {
            masked += "?" + fields(String(value[value.index(after: queryAt)..<fragmentAt]), maskBare: true)
        }
        if fragmentAt < value.endIndex {
            masked += "#" + fields(String(value[value.index(after: fragmentAt)...]), maskBare: false)
        }
        return masked
    }

    /// `scheme://user:password@…` or `//user:password@…` with a non-empty user name and password replaced
    /// by `mask`.
    private func userInfo(_ hierarchy: Substring) -> String {
        var start = hierarchy.startIndex
        if let colon = hierarchy.firstIndex(of: ":"), hierarchy.first?.isASCII == true,
            hierarchy.first?.isLetter == true,
            hierarchy[..<colon].allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "+.-".contains($0)) })
        {
            start = hierarchy.index(after: colon)
        }
        guard hierarchy[start...].hasPrefix("//") else { return String(hierarchy) }
        let authority = hierarchy.index(start, offsetBy: 2)
        let end = hierarchy[authority...].firstIndex(of: "/") ?? hierarchy.endIndex
        guard let at = hierarchy[authority..<end].lastIndex(of: "@") else { return String(hierarchy) }
        let user = hierarchy[authority..<at].split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        return String(hierarchy[..<authority]) + user.map { $0.isEmpty ? "" : mask }.joined(separator: ":")
            + String(hierarchy[at...])
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
