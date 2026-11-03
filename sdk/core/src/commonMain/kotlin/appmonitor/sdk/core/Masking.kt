package appmonitor.sdk.core

import appmonitor.v1.Header
import io.ktor.http.decodeURLQueryComponent

/** The value a masked header, query or fragment parameter, URL user name or password is sent with (ADR-0018). */
@InternalAppMonitorApi
public const val MASK: String = "██"

private val DEFAULT_MASKED_HEADERS: Set<String> = setOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")

/** Response headers whose value is a URL, masked as one (ADR-0033). */
private val URL_RESPONSE_HEADERS: Set<String> = setOf("Location", "Content-Location")

/** An RFC 3986 scheme at the start of a URI reference. */
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** RFC 3986 appendix B: scheme, authority, path, query and fragment of any URI reference. */
private val REFERENCE = Regex("^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$")

/** A `[user-info@]host[:port]` HttpUrl accepts: a host, and a port that is a number in range or empty. */
private val AUTHORITY = Regex("^(?:.*@)?(\\[[^]]*]|[^:@]+)(?::(\\d*))?$")

/**
 * ADR-0018 B, ADR-0181: the rule by RFC 3986 on strings, the same on every target. [resolveReference] resolves a
 * [URL_RESPONSE_HEADERS] value against the request's URL, null when it does not.
 */
@InternalAppMonitorApi
public class Masking(
    options: AppMonitorOptions,
    private val resolveReference: (base: String, reference: String) -> String? = ::resolve,
) {
    private val enabled = options.masking // ADR-0034
    private val headerNames = lowercase(DEFAULT_MASKED_HEADERS + options.maskedHeaders)
    private val queryNames = lowercase(options.maskedQueryParameters)
    private val urlHeaderNames = lowercase(URL_RESPONSE_HEADERS)

    /** With [base], a [URL_RESPONSE_HEADERS] value is resolved against it when relative, then masked as a URL. */
    public fun headers(
        headers: List<Pair<String, String>>,
        base: String? = null,
    ): List<Header> =
        headers.map { (name, value) ->
            val masked =
                when {
                    !enabled -> value
                    name.lowercase() in headerNames -> MASK
                    base != null && name.lowercase() in urlHeaderNames -> uri(resolveReference(base, value) ?: value)
                    else -> value
                }
            Header(name, masked)
        }

    public fun url(url: String): String = if (enabled) uri(url) else url

    /** ADR-0044: user info, query and fragment of [value] masked as written; everything else unchanged. */
    private fun uri(value: String): String {
        val fragmentAt = value.indexOf('#').takeIf { it >= 0 } ?: value.length
        val queryAt = value.indexOf('?').takeIf { it in 0 until fragmentAt } ?: fragmentAt
        val query = if (queryAt < fragmentAt) "?" + fields(value.substring(queryAt + 1, fragmentAt), maskBare = true) else ""
        val fragment = if (fragmentAt < value.length) "#" + fields(value.substring(fragmentAt + 1), maskBare = false) else ""
        return userInfo(value.substring(0, queryAt)) + query + fragment
    }

    /** `scheme://user:password@…` or `//user:password@…` with a non-empty user name and password replaced by [MASK]. */
    private fun userInfo(hierarchy: String): String {
        val start = SCHEME.find(hierarchy)?.value?.length ?: 0
        if (!hierarchy.startsWith("//", start)) return hierarchy
        val end = hierarchy.indexOf('/', start + 2).takeIf { it >= 0 } ?: hierarchy.length
        val at = hierarchy.lastIndexOf('@', end - 1).takeIf { it >= start + 2 } ?: return hierarchy
        val masked =
            hierarchy.substring(start + 2, at).split(':', limit = 2).joinToString(":") { if (it.isEmpty()) it else MASK }
        return hierarchy.substring(0, start + 2) + masked + hierarchy.substring(at)
    }

    /**
     * ADR-0033: each `name=value` field whose name is masked becomes `name=`[MASK]; a field without `=`
     * too when [maskBare], as in a query; other fields stay as written.
     */
    private fun fields(
        encoded: String,
        maskBare: Boolean,
    ): String {
        if (queryNames.isEmpty()) return encoded
        return encoded.split('&').joinToString("&") { field ->
            val name = field.substringBefore('=')
            if ((maskBare || '=' in field) && formDecoded(name).lowercase() in queryNames) "$name=$MASK" else field
        }
    }

    private fun formDecoded(name: String): String =
        try {
            name.decodeURLQueryComponent(plusIsSpace = true)
        } catch (e: Exception) {
            name
        }

    private fun lowercase(names: Set<String>) = names.mapTo(HashSet()) { it.lowercase() }
}

/**
 * RFC 3986 5.2 for a [reference] without a scheme against the absolute [base]; null when it has a
 * scheme, or an authority with no host or a port that is not a number in range.
 */
internal fun resolve(
    base: String,
    reference: String,
): String? {
    if (SCHEME.containsMatchIn(reference)) return null
    val b = REFERENCE.matchEntire(base) ?: return null
    val r = REFERENCE.matchEntire(reference) ?: return null
    val bAuthority = b.groupValues[2]
    val bPath = b.groupValues[3]
    val authority = r.groups[2]?.value
    val path = r.groupValues[3]
    val query = r.groups[4]?.value
    if (authority != null) {
        val port = AUTHORITY.matchEntire(authority)?.groupValues?.get(2) ?: return null
        if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) return null
    }
    val resolved = StringBuilder(b.groupValues[1]).append(':')
    val (targetAuthority, targetPath, targetQuery) =
        when {
            authority != null -> {
                Triple(authority, removeDotSegments(path), query)
            }

            path.isEmpty() -> {
                Triple(bAuthority, bPath, query ?: b.groups[4]?.value)
            }

            path.startsWith("/") -> {
                Triple(bAuthority, removeDotSegments(path), query)
            }

            else -> {
                val merged = if (b.groups[2] != null && bPath.isEmpty()) "/$path" else bPath.substringBeforeLast('/', "") + "/" + path
                Triple(bAuthority, removeDotSegments(merged), query)
            }
        }
    if (b.groups[2] != null || authority != null) resolved.append("//").append(targetAuthority)
    resolved.append(targetPath)
    if (targetQuery != null) resolved.append('?').append(targetQuery)
    r.groups[5]?.let { resolved.append('#').append(it.value) }
    return resolved.toString()
}

/** RFC 3986 5.2.4. */
private fun removeDotSegments(path: String): String {
    val output = ArrayList<String>()
    val segments = path.split('/')
    for ((i, segment) in segments.withIndex()) {
        val last = i == segments.lastIndex
        when (segment) {
            "." -> {
                if (last) output.add("")
            }

            ".." -> {
                if (output.size > 1 || (output.size == 1 && output[0].isNotEmpty())) output.removeAt(output.lastIndex)
                if (last) output.add("")
            }

            else -> {
                output.add(segment)
            }
        }
    }
    return output.joinToString("/")
}
