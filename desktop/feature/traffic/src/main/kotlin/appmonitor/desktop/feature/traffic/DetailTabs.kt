package appmonitor.desktop.feature.traffic

import appmonitor.desktop.designsystem.matchesOf
import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.ui.formatDuration
import appmonitor.desktop.ui.headersText
import appmonitor.desktop.ui.utf8OrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The request detail's tabs (ADR-0140 G); the picked one stays while another request is picked. */
enum class DetailTab(
    val label: String,
) {
    Summary("Summary"),
    Headers("Headers"),
    Body("Body"),
    Timing("Timing"),
    Curl("cURL"),
}

/** What a copy action of the detail puts on the clipboard. */
enum class DetailPart {
    Url,
    RequestHeaders,
    ResponseHeaders,
    RequestBody,
    ResponseBody,
    Timing,
    Curl,
}

/** A connection layer exchange has no headers, body or request to rebuild: only its summary and timing. */
internal fun tabsOf(exchange: HttpExchange): List<DetailTab> =
    if (exchange.connection != null) listOf(DetailTab.Summary, DetailTab.Timing) else DetailTab.entries

/** The Timing tab's rows, in [zone]; an end or a duration not known yet is left out. */
internal fun timingOf(
    exchange: HttpExchange,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<String, String>> {
    val format = TIMESTAMP.withZone(zone)
    return listOfNotNull(
        "Started" to (exchange.startedAtMs?.let { format.format(Instant.ofEpochMilli(it)) } ?: "unknown"),
        exchange.endedAtMs?.let { "Ended" to format.format(Instant.ofEpochMilli(it)) },
        exchange.durationMs?.let { "Duration" to formatDuration(it) },
        exchange.frameCount.takeIf { it > 0 }?.let { "Frames" to it.toString() },
    )
}

private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

/** [part] of [exchange] as text, null when there is none; a body is copied as the device sent it, hex when not UTF-8. */
internal fun textOf(
    exchange: HttpExchange,
    part: DetailPart,
    zone: ZoneId = ZoneId.systemDefault(),
): String? =
    when (part) {
        DetailPart.Url -> exchange.url
        DetailPart.RequestHeaders -> exchange.requestHeaders.takeIf { it.isNotEmpty() }?.let(::headersText)
        DetailPart.ResponseHeaders -> exchange.responseHeaders.takeIf { it.isNotEmpty() }?.let(::headersText)
        DetailPart.RequestBody -> bodyText(exchange.requestBody)
        DetailPart.ResponseBody -> bodyText(exchange.responseBody)
        DetailPart.Timing -> timingOf(exchange, zone).joinToString("\n") { (name, value) -> "$name: $value" }
        DetailPart.Curl -> null
    }

private fun bodyText(body: CapturedBody): String? = body.bytes.takeIf { it.size > 0 }?.let { utf8OrNull(it) ?: hexDump(it) }

/** What a search inside the request counts in each tab (ADR-0152); cURL repeats the others, so it is highlighted, not counted. */
internal fun searchedTexts(
    exchange: HttpExchange,
    bodies: List<String>,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<DetailTab, List<String>> =
    tabsOf(exchange).associateWith { tab ->
        when (tab) {
            DetailTab.Summary -> listOfNotNull(exchange.url, exchange.error)
            DetailTab.Headers -> (exchange.requestHeaders + exchange.responseHeaders).flatMap { listOf(it.name, it.value_) }
            DetailTab.Body -> bodies
            DetailTab.Timing -> timingOf(exchange, zone).map { it.second }
            DetailTab.Curl -> emptyList()
        }
    }

/** How many times [query] shows in each tab's [texts]; empty for a blank one. */
internal fun matchCounts(
    texts: Map<DetailTab, List<String>>,
    query: String,
): Map<DetailTab, Int> = if (query.isBlank()) emptyMap() else texts.mapValues { (_, shown) -> shown.sumOf { matchesOf(it, query).size } }
