package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.Exchange

/** The palette's Requests (ADR-0206): rows whose `method url` holds [query], case aside, one per method and path, [limit] at most. */
internal fun endpointsOf(
    rows: List<Exchange>,
    query: String,
    limit: Int = ENDPOINTS_SHOWN,
): List<Exchange> {
    val text = query.trim()
    return rows
        .asSequence()
        .filter { it.url != null }
        .filter { text.isEmpty() || "${it.method.orEmpty()} ${it.url}".contains(text, ignoreCase = true) }
        .distinctBy { it.method to urlPartsOf(it.url.orEmpty()).path }
        .take(limit)
        .toList()
}

/** Whether a palette entry's [label] holds [query], case aside; an empty query keeps every entry. */
internal fun matches(
    label: String,
    query: String,
): Boolean = query.isBlank() || label.contains(query.trim(), ignoreCase = true)

private const val ENDPOINTS_SHOWN = 5
