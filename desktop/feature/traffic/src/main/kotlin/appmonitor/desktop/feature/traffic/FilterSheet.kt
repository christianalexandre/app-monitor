package appmonitor.desktop.feature.traffic

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import appmonitor.desktop.designsystem.CheckboxRow
import appmonitor.desktop.designsystem.Choice
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.ui.METHODS

/**
 * The method and hosts the list keeps, as `app-network-monitor`'s sheet (ADR-0148); nothing picked in a group keeps all
 * of it. The search and the status classes are over the list (ADR-0203), the device on the left.
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
                onClick = { onFilter(filter.copy(method = null, hosts = emptySet())) },
                Modifier.testTag(FILTER_RESET),
                enabled = filter.count > 0,
            )
        }
        Choice("Method", filter.method, METHODS, { it }) { onFilter(filter.copy(method = it)) }
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
    get() = listOf(method != null, hosts.isNotEmpty()).count { it }

private fun <T> Set<T>.toggled(
    item: T,
    on: Boolean,
): Set<T> = if (on) this + item else this - item
