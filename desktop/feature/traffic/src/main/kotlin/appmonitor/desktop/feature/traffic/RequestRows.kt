package appmonitor.desktop.feature.traffic

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.StatusClass
import appmonitor.v1.Platform

/** A URL's host, its path ("/" when it has none) and its query with its "?", as a request row shows them. */
internal data class UrlParts(
    val host: String,
    val path: String,
    val query: String,
)

internal fun urlPartsOf(url: String): UrlParts {
    val rest = url.substringAfter("://", url)
    val pathAt = rest.indexOfFirst { it == '/' || it == '?' }.takeIf { it >= 0 } ?: rest.length
    val host = rest.take(pathAt)
    val tail = rest.drop(pathAt)
    val path = tail.substringBefore('?').ifEmpty { "/" }
    val query = if ('?' in tail) "?" + tail.substringAfter('?') else ""
    return UrlParts(host, path, query)
}

/** The list header's status choices (ADR-0203); one at a time, set as the filter's classes or its "failed only". */
internal enum class StatusChoice(
    val label: String,
    val tag: String,
    private val status: StatusClass?,
    private val failed: Boolean,
) {
    All("All", "all", null, false),
    Success("2xx", "200", StatusClass.SUCCESS, false),
    Redirection("3xx", "300", StatusClass.REDIRECTION, false),
    ClientError("4xx", "400", StatusClass.CLIENT_ERROR, false),
    ServerError("5xx", "500", StatusClass.SERVER_ERROR, false),
    Failed("Failed", "failed", null, true),
    ;

    fun applyTo(filter: ExchangeFilter): ExchangeFilter = filter.copy(statusClasses = setOfNotNull(status), failedOnly = failed)

    companion object {
        /** The choice [filter] holds; null for a mix no choice sets. */
        fun of(filter: ExchangeFilter): StatusChoice? = entries.firstOrNull { it.applyTo(filter) == filter }
    }
}

internal fun statusShown(filter: ExchangeFilter): StatusChoice? = StatusChoice.of(filter)

/** "12 requests", or "200+ requests" while the store may hold more than the pages read. */
internal fun countLabel(
    count: Int,
    more: Boolean,
): String = "$count${if (more) "+" else ""} ${if (count == 1 && !more) "request" else "requests"}"

internal fun platformLabel(platform: Platform): String =
    when (platform) {
        Platform.PLATFORM_ANDROID -> "Android"
        Platform.PLATFORM_IOS -> "iOS"
        Platform.PLATFORM_JVM -> "JVM"
        else -> ""
    }

// ⌘ on macOS, Ctrl elsewhere; either works on each, as the label shows the host's.
internal val KeyEvent.isShortcut: Boolean get() = isMetaPressed || isCtrlPressed

internal val SHORTCUT_PREFIX: String = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "⌘" else "Ctrl+"
