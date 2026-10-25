package appmonitor.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Divider
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.storage.EndpointGroups
import appmonitor.desktop.storage.EndpointTally
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.groupByEndpoint
import appmonitor.desktop.ui.formatDuration
import java.util.Locale

/** A sortable column of the benchmarks; [value] is null for an endpoint without the number, sorted last. */
internal enum class BenchmarkColumn(
    val label: String,
    val value: (EndpointTally) -> Comparable<*>?,
) {
    ENDPOINT("Endpoint", { "${it.endpoint.host.orEmpty()}${it.endpoint.path} ${it.endpoint.method.orEmpty()}" }),
    COUNT("Count", { it.count }),
    ERROR_RATE("Errors", { it.errorRate }),
    P50("p50", { it.durationStats?.p50 }),
    P95("p95", { it.durationStats?.p95 }),
    P99("p99", { it.durationStats?.p99 }),
    REQUEST_BYTES("Sent", { it.requestBytes }),
    RESPONSE_BYTES("Received", { it.responseBytes }),
}

/** [endpoints] by [column], nulls last either way; ties keep the grouping's order (most called first). */
@Suppress("UNCHECKED_CAST")
internal fun sortBenchmarks(
    endpoints: List<EndpointTally>,
    column: BenchmarkColumn,
    descending: Boolean,
): List<EndpointTally> {
    val values = compareBy<Comparable<Any>> { it }.let { if (descending) it.reversed() else it }
    return endpoints.sortedWith(compareBy(nullsLast(values)) { column.value(it) as Comparable<Any>? })
}

/** One row as the window shows it; also what the tests compare against `endpoint_stats`. */
internal data class BenchmarkRow(
    val endpoint: String,
    val count: String,
    val status: String,
    val errorRate: String,
    val p50: String,
    val p95: String,
    val p99: String,
    val requestBytes: String,
    val responseBytes: String,
) {
    companion object {
        fun of(tally: EndpointTally): BenchmarkRow {
            val durations = tally.durationStats
            val classes = tally.statusClasses.mapIndexedNotNull { index, n -> if (n == 0L) null else "${index + 1}xx $n" }
            val failed = if (tally.failed == 0L) emptyList() else listOf("failed ${tally.failed}")
            return BenchmarkRow(
                "${tally.endpoint.method ?: "—"} ${tally.endpoint.host.orEmpty()}${tally.endpoint.path}",
                tally.count.toString(),
                (classes + failed).joinToString(" · ").ifEmpty { "—" },
                String.format(Locale.ROOT, "%.1f %%", tally.errorRate * 100),
                durations?.p50?.let(::formatDuration) ?: "—",
                durations?.p95?.let(::formatDuration) ?: "—",
                durations?.p99?.let(::formatDuration) ?: "—",
                bytesOf(tally.requestBytes),
                bytesOf(tally.responseBytes),
            )
        }
    }
}

/**
 * Per endpoint numbers of the exchanges [filter] matches across every device of the run it names, read when opened and
 * on "Refresh" (SDD 01); [scope] says which run and filter in the title.
 */
@Composable
internal fun Benchmarks(
    storage: Storage,
    filter: ExchangeFilter,
    scope: String,
    onClose: () -> Unit,
) {
    var reads by remember { mutableIntStateOf(0) }
    val groups by produceState<EndpointGroups?>(null, storage, filter, reads) { value = storage.groupByEndpoint(filter) }
    var column by remember { mutableStateOf(BenchmarkColumn.COUNT) }
    var descending by remember { mutableStateOf(true) }
    DialogCard(DialogWidth.Table) {
        Text("Benchmarks", role = TextRole.Title)
        Text(scope, role = TextRole.Caption)
        val loaded = groups
        if (loaded == null) {
            Placeholder("Reading requests…")
        } else {
            Text(
                "${loaded.exchangesRead} requests read" +
                    (if (loaded.truncated) ", the first ones only: narrow the filter for the rest" else ""),
                role = TextRole.Caption,
                error = loaded.truncated,
            )
            Row(Modifier.fillMaxWidth()) {
                BenchmarkColumn.entries.forEach { header ->
                    val arrow = if (header == column) (if (descending) " ▼" else " ▲") else ""
                    Cell(
                        header.label + arrow,
                        header,
                        Modifier
                            .clickable {
                                if (header == column) {
                                    descending = !descending
                                } else {
                                    column = header
                                    descending = header != BenchmarkColumn.ENDPOINT
                                }
                            }.testTag(BENCHMARK_COLUMN + header.name),
                        bold = true,
                    )
                    if (header == BenchmarkColumn.COUNT) Cell("Status", null, bold = true)
                }
            }
            Divider()
            if (loaded.endpoints.isEmpty()) Placeholder("No requests match")
            val rows = remember(loaded, column, descending) { sortBenchmarks(loaded.endpoints, column, descending) }
            LazyColumn(Modifier.heightIn(max = 480.dp).testTag(BENCHMARK_LIST)) {
                items(rows, key = { it.endpoint.toString() }) { tally ->
                    val row = BenchmarkRow.of(tally)
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                        Cell(row.endpoint, BenchmarkColumn.ENDPOINT)
                        Cell(row.count, BenchmarkColumn.COUNT)
                        Cell(row.status, null)
                        Cell(row.errorRate, BenchmarkColumn.ERROR_RATE)
                        Cell(row.p50, BenchmarkColumn.P50)
                        Cell(row.p95, BenchmarkColumn.P95)
                        Cell(row.p99, BenchmarkColumn.P99)
                        Cell(row.requestBytes, BenchmarkColumn.REQUEST_BYTES)
                        Cell(row.responseBytes, BenchmarkColumn.RESPONSE_BYTES)
                    }
                }
            }
        }
        DialogActions {
            TextButton("Refresh", onClick = { reads++ })
            TextButton("Close", onClick = onClose)
        }
    }
}

/** [column] null is the status classes' column. */
@Composable
private fun RowScope.Cell(
    text: String,
    column: BenchmarkColumn?,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
) {
    val endpoint = column == BenchmarkColumn.ENDPOINT
    Text(
        text,
        modifier
            .then(
                when (column) {
                    BenchmarkColumn.ENDPOINT -> Modifier.weight(1f)
                    null -> Modifier.width(200.dp)
                    else -> Modifier.width(80.dp)
                },
            ).padding(horizontal = Spacing.xs),
        textAlign = if (endpoint || column == null) TextAlign.Start else TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        role = if (bold) TextRole.SmallStrong else TextRole.Small,
    )
}

private fun bytesOf(bytes: Long): String =
    when {
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KiB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

internal const val BENCHMARK_LIST = "benchmark-list"

internal const val BENCHMARK_COLUMN = "benchmark-column-"
