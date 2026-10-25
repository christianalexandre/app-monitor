package appmonitor.desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
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
import appmonitor.desktop.storage.EndpointChange
import appmonitor.desktop.storage.EndpointDiff
import appmonitor.desktop.storage.EndpointTally
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredRun
import appmonitor.desktop.storage.diffEndpoints
import appmonitor.desktop.storage.groupByEndpoint
import java.util.Locale

/** One endpoint as the diff shows it: `base → run` for a change, one side's value for an added or gone one. */
internal data class DiffRow(
    val endpoint: String,
    val count: String,
    val errorRate: String,
    val p50: String,
    val p95: String,
) {
    companion object {
        fun of(tally: EndpointTally): DiffRow =
            DiffRow(
                endpointOf(tally),
                tally.count.toString(),
                rateOf(tally),
                tally.durationStats?.p50.text(),
                tally.durationStats?.p95.text(),
            )

        fun of(change: EndpointChange): DiffRow {
            val (base, run) = change.base to change.run
            return DiffRow(
                endpointOf(run),
                "${base.count} → ${run.count}",
                "${rateOf(base)} → ${rateOf(run)}",
                "${base.durationStats?.p50.text()} → ${run.durationStats?.p50.text()}",
                "${base.durationStats?.p95.text()} → ${run.durationStats?.p95.text()}",
            )
        }

        private fun endpointOf(tally: EndpointTally) =
            "${tally.endpoint.method ?: "—"} ${tally.endpoint.host.orEmpty()}${tally.endpoint.path}"

        private fun rateOf(tally: EndpointTally) = String.format(Locale.ROOT, "%.1f %%", tally.errorRate * 100)

        private fun Long?.text() = this?.let(::formatDuration) ?: "—"
    }
}

/**
 * Two runs picked from [runs] (newest first), [filter] applied to both, compared per endpoint with `diff_runs`' code
 * (SDD 01); read again on a pick and on "Refresh".
 */
@Composable
internal fun RunDiff(
    storage: Storage,
    runs: List<StoredRun>,
    filter: ExchangeFilter,
    scope: String,
    onClose: () -> Unit,
) {
    var baseId by remember { mutableStateOf(runs.getOrNull(1)?.id ?: runs.firstOrNull()?.id) }
    var runId by remember { mutableStateOf(runs.firstOrNull()?.id) }
    var reads by remember { mutableIntStateOf(0) }
    val diff by produceState<EndpointDiff?>(null, storage, filter, baseId, runId, reads) {
        value = null
        val base = baseId ?: return@produceState
        val run = runId ?: return@produceState
        value = diffEndpoints(storage.groupByEndpoint(filter.copy(runId = base)), storage.groupByEndpoint(filter.copy(runId = run)))
    }
    DialogCard(DialogWidth.Table) {
        Text("Compare runs", role = TextRole.Title)
        Text(scope, role = TextRole.Caption)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            RunPicker("Base", runs, baseId, DIFF_BASE, Modifier.weight(1f)) { baseId = it }
            RunPicker("Run", runs, runId, DIFF_RUN, Modifier.weight(1f)) { runId = it }
        }
        Divider()
        val loaded = diff
        if (loaded == null) {
            Placeholder(if (baseId == null) "No runs to compare" else "Reading requests…")
        } else {
            Text(
                "${loaded.added.size} added · ${loaded.gone.size} gone · ${loaded.changed.size} changed · " +
                    "${loaded.unchanged} unchanged" +
                    (if (loaded.truncated) " — the first requests only: narrow the filter for the rest" else ""),
                Modifier.testTag(DIFF_SUMMARY),
                role = TextRole.Caption,
                error = loaded.truncated,
            )
            DiffHeader()
            LazyColumn(Modifier.heightIn(max = 440.dp).testTag(DIFF_LIST)) {
                section("Changed", loaded.changed.map(DiffRow::of))
                section("Added", loaded.added.map(DiffRow::of))
                section("Gone", loaded.gone.map(DiffRow::of))
            }
        }
        DialogActions {
            TextButton("Refresh", onClick = { reads++ })
            TextButton("Close", onClick = onClose)
        }
    }
}

@Composable
private fun RunPicker(
    label: String,
    runs: List<StoredRun>,
    selected: Long?,
    tag: String,
    modifier: Modifier,
    onPick: (Long) -> Unit,
) {
    Column(modifier) {
        Text(label, role = TextRole.BodyStrong)
        LazyColumn(Modifier.heightIn(max = 140.dp)) {
            items(runs, key = { it.id }) { run ->
                val picked = run.id == selected
                Text(
                    (if (picked) "● " else "○ ") + titleOf(run) + " · #${run.id}",
                    Modifier
                        .fillMaxWidth()
                        .selectable(picked) { onPick(run.id) }
                        .padding(vertical = Spacing.xs)
                        .testTag(tag + run.id),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DiffHeader() {
    Row(Modifier.fillMaxWidth()) {
        DiffCell("Endpoint", wide = true, bold = true)
        listOf("Count", "Errors", "p50", "p95").forEach { DiffCell(it, bold = true) }
    }
}

private fun LazyListScope.section(
    title: String,
    rows: List<DiffRow>,
) {
    if (rows.isEmpty()) return
    item(key = title) { Text(title, Modifier.padding(top = Spacing.md), role = TextRole.Heading) }
    items(rows, key = { title + it.endpoint }) { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
            DiffCell(row.endpoint, wide = true)
            DiffCell(row.count)
            DiffCell(row.errorRate)
            DiffCell(row.p50)
            DiffCell(row.p95)
        }
    }
}

@Composable
private fun RowScope.DiffCell(
    text: String,
    wide: Boolean = false,
    bold: Boolean = false,
) {
    Text(
        text,
        (if (wide) Modifier.weight(1f) else Modifier.width(150.dp)).padding(horizontal = Spacing.xs),
        textAlign = if (wide) TextAlign.Start else TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        role = if (bold) TextRole.SmallStrong else TextRole.Small,
    )
}

internal const val COMPARE_RUNS = "compare-runs"

internal const val DIFF_BASE = "diff-base-"

internal const val DIFF_RUN = "diff-run-"

internal const val DIFF_SUMMARY = "diff-summary"

internal const val DIFF_LIST = "diff-list"
