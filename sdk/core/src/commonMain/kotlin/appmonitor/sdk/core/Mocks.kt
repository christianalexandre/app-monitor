package appmonitor.sdk.core

import appmonitor.v1.MockRule
import kotlin.concurrent.Volatile

/** The rules of the session's last `MockRules` (ADR-0113 A.1); empty outside a session (ADR-0110 A.1). */
internal class MockRuleSet {
    @Volatile
    private var rules: List<MockRule> = emptyList()

    fun replace(rules: List<MockRule>) {
        this.rules = rules.filter { it.response != null || it.failure != null }
    }

    fun clear() {
        rules = emptyList()
    }

    /** The first rule matching [method] and [url] (ADR-0110 B.1), or null. */
    fun match(
        method: String,
        url: String,
    ): MockRule? {
        val current = rules
        if (current.isEmpty()) return null
        val (host, path) = hostAndPath(url) ?: return null
        return current.firstOrNull { it.matches(method, host, path) }
    }
}

internal fun MockRule.matches(
    method: String,
    host: String,
    path: String,
): Boolean = ruleMatches(this.method, this.host, this.path, method, host, path)

/** ADR-0110 B.1, the matcher a breakpoint shares (ADR-0118 A.1). */
internal fun ruleMatches(
    ruleMethod: String,
    ruleHost: String,
    rulePath: String,
    method: String,
    host: String,
    path: String,
): Boolean {
    if (ruleMethod.isNotEmpty() && ruleMethod != method) return false
    if (!ruleHost.equals(host, ignoreCase = true)) return false
    val wanted = rulePath.split('/')
    val actual = path.split('/')
    return wanted.size == actual.size && wanted.indices.all { wanted[it] == "*" || wanted[it] == actual[it] }
}

/** RFC 3986 appendix B: authority and path of an absolute URL. */
private val URL = Regex("^[^:/?#]+://([^/?#]*)([^?#]*)")

/** The host without user info or port, and the path as written ("/" when empty); null when [url] has no authority. */
internal fun hostAndPath(url: String): Pair<String, String>? {
    val match = URL.find(url) ?: return null
    val authority = match.groupValues[1].substringAfterLast('@')
    val host =
        if (authority.startsWith("[")) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }
    return host to match.groupValues[2].ifEmpty { "/" }
}
