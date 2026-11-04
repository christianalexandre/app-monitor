import Foundation

/// The value a masked header, query or fragment parameter, URL user name or password is sent with (ADR-0018).
let mask = "██"

let defaultMaskedHeaders: Set<String> = ["Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"]

/// Response headers whose value is a URL, masked as one (ADR-0033).
let urlResponseHeaders: Set<String> = ["Location", "Content-Location"]

/// ADR-0036 A: Android's rule, names compared case-insensitively, the name kept and the value
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
    /// `uri(_:)` when it is not an `http` URL or Foundation does not parse it (ADR-0044). Below iOS 17 `URL`
    /// takes what `URLComponents` rejects, so both must parse it (ADR-0170).
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
                    ["http", "https"].contains(resolved.scheme?.lowercased()),
                    URLComponents(string: resolved.absoluteString) != nil
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
        guard enabled else { return string }
        guard let components = URLComponents(string: string) else { return uri(string) }
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

    /// ADR-0185: `edited` with a user name, password or field left as `mask` back to `own`'s; a name's nth to its nth.
    func restoredURL(_ edited: String, own: String) -> String {
        guard enabled, edited.contains(mask) else { return edited }
        let parts = uriParts(edited)
        let ownParts = uriParts(own)
        var restored = restoredUserInfo(parts.hierarchy, ownParts.hierarchy)
        if let query = parts.query { restored += "?" + restoredFields(query, ownParts.query) }
        if let fragment = parts.fragment { restored += "#" + restoredFields(fragment, ownParts.fragment) }
        return restored
    }

    /// ADR-0185: `edited` with a masked header left as `mask`, or a URL one's masked parts, back to `own`'s nth of
    /// its name.
    func restoredHeaders(_ edited: [Appmonitor_V1_Header], own: [(name: String, value: String)])
        -> [(name: String, value: String)]
    {
        var ownValues: [String: [String]] = [:]
        for header in own { ownValues[header.name.lowercased(), default: []].append(header.value) }
        var seen: [String: Int] = [:]
        return edited.map { header in
            let name = header.name.lowercased()
            let at = seen[name, default: 0]
            seen[name] = at + 1
            guard enabled, let values = ownValues[name], at < values.count else { return (header.name, header.value) }
            if headerNames.contains(name) { return (header.name, header.value == mask ? values[at] : header.value) }
            if urlHeaderNames.contains(name) { return (header.name, restoredURL(header.value, own: values[at])) }
            return (header.name, header.value)
        }
    }

    /// What precedes the query, the query and the fragment of `value`; nil for a part it does not have.
    private func uriParts(_ value: String) -> (hierarchy: Substring, query: String?, fragment: String?) {
        let fragmentAt = value.firstIndex(of: "#") ?? value.endIndex
        let queryAt = value[..<fragmentAt].firstIndex(of: "?") ?? fragmentAt
        return (
            value[..<queryAt],
            queryAt < fragmentAt ? String(value[value.index(after: queryAt)..<fragmentAt]) : nil,
            fragmentAt < value.endIndex ? String(value[value.index(after: fragmentAt)...]) : nil
        )
    }

    private func restoredUserInfo(_ edited: Substring, _ own: Substring) -> String {
        guard let range = userInfoRange(edited) else { return String(edited) }
        let ownParts = userInfoRange(own).map { userAndPassword(own[$0]) }
        let parts = userAndPassword(edited[range])
        let restored = parts.enumerated().map { i, part -> String in
            guard part == mask, let ownParts, i < ownParts.count else { return String(part) }
            return String(ownParts[i])
        }
        return String(edited[..<range.lowerBound]) + restored.joined(separator: ":")
            + String(edited[range.upperBound...])
    }

    private func userAndPassword(_ userInfo: Substring) -> [Substring] {
        userInfo.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
    }

    private func restoredFields(_ edited: String, _ own: String?) -> String {
        var ownFields: [Substring: [Substring]] = [:]
        for field in own?.split(separator: "&", omittingEmptySubsequences: false) ?? [] {
            ownFields[fieldName(field), default: []].append(field)
        }
        var seen: [Substring: Int] = [:]
        return edited.split(separator: "&", omittingEmptySubsequences: false).map { field -> String in
            let name = fieldName(field)
            let at = seen[name, default: 0]
            seen[name] = at + 1
            guard field == "\(name)=\(mask)", queryNames.contains(formDecoded(name).lowercased()),
                let values = ownFields[name], at < values.count
            else { return String(field) }
            return String(values[at])
        }.joined(separator: "&")
    }

    private func fieldName(_ field: Substring) -> Substring {
        field.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)[0]
    }

    private func formDecoded(_ name: Substring) -> String {
        name.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? String(name)
    }

    /// The URL rule by RFC 3986 generic syntax, only the masked spans changed, as OkHttp's `uri`; a value
    /// without a scheme gets it too.
    private func uri(_ value: String) -> String {
        let parts = uriParts(value)
        var masked = userInfo(parts.hierarchy)
        if let query = parts.query { masked += "?" + fields(query, maskBare: true) }
        if let fragment = parts.fragment { masked += "#" + fields(fragment, maskBare: false) }
        return masked
    }

    /// `scheme://user:password@…` or `//user:password@…` with a non-empty user name and password replaced
    /// by `mask`.
    private func userInfo(_ hierarchy: Substring) -> String {
        guard let range = userInfoRange(hierarchy) else { return String(hierarchy) }
        let user = userAndPassword(hierarchy[range])
        return String(hierarchy[..<range.lowerBound]) + user.map { $0.isEmpty ? "" : mask }.joined(separator: ":")
            + String(hierarchy[range.upperBound...])
    }

    /// Where `hierarchy`'s `user:password` is, or nil when it has none.
    private func userInfoRange(_ hierarchy: Substring) -> Range<Substring.Index>? {
        var start = hierarchy.startIndex
        if let colon = hierarchy.firstIndex(of: ":"), hierarchy.first?.isASCII == true,
            hierarchy.first?.isLetter == true,
            hierarchy[..<colon].allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "+.-".contains($0)) })
        {
            start = hierarchy.index(after: colon)
        }
        guard hierarchy[start...].hasPrefix("//") else { return nil }
        let authority = hierarchy.index(start, offsetBy: 2)
        let end = hierarchy[authority...].firstIndex(of: "/") ?? hierarchy.endIndex
        guard let at = hierarchy[authority..<end].lastIndex(of: "@") else { return nil }
        return authority..<at
    }

    /// `&`-separated fields whose form-decoded name is masked become `name=██`; a query field without `=`
    /// is masked too (`maskBare`), as OkHttp's rebuilt query does; other fields stay as written.
    private func fields(_ encoded: String, maskBare: Bool) -> String {
        guard !queryNames.isEmpty else { return encoded }
        return encoded.split(separator: "&", omittingEmptySubsequences: false).map { field in
            let parts = field.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let name = String(parts[0])
            guard parts.count == 2 || maskBare else { return String(field) }
            return queryNames.contains(formDecoded(parts[0]).lowercased()) ? "\(name)=\(mask)" : String(field)
        }.joined(separator: "&")
    }
}
