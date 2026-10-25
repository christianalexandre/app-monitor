package appmonitor.desktop.feature.traffic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import appmonitor.desktop.designsystem.BadgeTone
import appmonitor.desktop.designsystem.CheckboxRow
import appmonitor.desktop.designsystem.Choice
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.designsystem.ToggleChip
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.StatusClass
import appmonitor.desktop.ui.METHODS

/**
 * The method, status classes, failures and hosts the list keeps, as `app-network-monitor`'s sheet (ADR-0148); nothing
 * picked in a group keeps all of it. The search and the device stay outside, in the toolbar and on the left.
 */
@Composable
internal fun FilterSheet(
    filter: ExchangeFilter,
    hosts: List<String>,
    onFilter: (ExchangeFilter) -> Unit,
    onDone: () -> Unit,
) {
    DialogCard(DialogWidth.Narrow, scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filters", Modifier.weight(1f), role = TextRole.Title)
            TextButton(
                "Reset",
                onClick = { onFilter(ExchangeFilter(text = filter.text)) },
                Modifier.testTag(FILTER_RESET),
                enabled = filter.count > 0,
            )
        }
        Choice("Method", filter.method, METHODS, { it }) { onFilter(filter.copy(method = it)) }
        Text("Status", role = TextRole.Heading)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            StatusClass.entries.forEach { status ->
                ToggleChip(
                    labelOf(status),
                    toneOf(status),
                    selected = status in filter.statusClasses,
                    onToggle = { on -> onFilter(filter.copy(statusClasses = filter.statusClasses.toggled(status, on))) },
                    Modifier.testTag(STATUS_FILTER + status.from),
                )
            }
        }
        CheckboxRow("Failed only", filter.failedOnly, { onFilter(filter.copy(failedOnly = it)) }, Modifier.testTag(FAILED_ONLY))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Host", Modifier.weight(1f), role = TextRole.Heading)
            TextButton("All", onClick = { onFilter(filter.copy(hosts = emptySet())) }, enabled = filter.hosts.isNotEmpty())
        }
        if (hosts.isEmpty()) {
            Text("No host in this list yet", role = TextRole.Caption)
        } else {
            hosts.forEach { host ->
                CheckboxRow(
                    host,
                    host in filter.hosts,
                    { on -> onFilter(filter.copy(hosts = filter.hosts.toggled(host, on))) },
                    Modifier.fillMaxWidth().testTag(HOST_FILTER + host),
                )
            }
        }
        Text("Nothing picked in a group keeps all of it", role = TextRole.Caption)
        DialogActions { TextButton("Done", onClick = onDone, Modifier.testTag(FILTERS_DONE)) }
    }
}

/** How many of the sheet's filters are set, for the toolbar's "Filters" button. */
internal val ExchangeFilter.count: Int
    get() = listOf(method != null, statusClasses.isNotEmpty(), failedOnly, hosts.isNotEmpty()).count { it }

internal fun labelOf(status: StatusClass): String = "${status.from / 100}xx"

private fun toneOf(status: StatusClass): BadgeTone =
    when (status) {
        StatusClass.INFORMATIONAL -> BadgeTone.Neutral
        StatusClass.SUCCESS -> BadgeTone.Success
        StatusClass.REDIRECTION -> BadgeTone.Info
        StatusClass.CLIENT_ERROR -> BadgeTone.Warning
        StatusClass.SERVER_ERROR -> BadgeTone.Error
    }

private fun <T> Set<T>.toggled(
    item: T,
    on: Boolean,
): Set<T> = if (on) this + item else this - item
