package appmonitor.desktop.feature.insights

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import appmonitor.desktop.designsystem.Divider
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.domain.DeviceEndpoint
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.RunDevice

/** One device's numbers for one endpoint, as the window shows them. */
data class DeviceCells(
    val count: String,
    val status: String,
    val p50: String,
    val p95: String,
    val received: String,
)

/** One endpoint of the comparison: a cell per device of the run, null where that device never called it. */
data class ByDeviceRow(
    val endpoint: String,
    val devices: List<DeviceCells?>,
) {
    companion object {
        fun of(
            endpoint: DeviceEndpoint,
            devices: List<RunDevice>,
        ): ByDeviceRow =
            ByDeviceRow(
                endpointLabel(endpoint.endpoint),
                devices.map { device ->
                    endpoint.byDevice[device.deviceId]?.let(BenchmarkRow::of)?.let {
                        DeviceCells(it.count, it.status, it.p50, it.p95, it.responseBytes)
                    }
                },
            )
    }
}

/** Run [runId]'s endpoints under [filter] with one group of columns per device (ADR-0140 D), read on [reads]. */
@Composable
internal fun ByDevice(
    filter: ExchangeFilter,
    runId: Long,
    reads: Int,
    viewModel: InsightsViewModel,
) {
    LaunchedEffect(filter, runId, reads) { viewModel.readByDevice(filter, runId) }
    DisposableEffect(viewModel) { onDispose { viewModel.closeByDevice() } }
    val comparison =
        viewModel.state
            .collectAsState()
            .value.byDevice
    if (comparison == null) {
        Placeholder("Reading requests…")
        return
    }
    Text(
        "${comparison.exchangesRead} requests read from ${comparison.devices.size} devices" +
            (if (comparison.truncated) ", the first ones only: narrow the filter for the rest" else ""),
        role = TextRole.Caption,
        error = comparison.truncated,
    )
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Cell("", ENDPOINT_WIDTH)
            comparison.devices.forEach { Cell(it.name, DEVICE_WIDTH, Modifier.testTag(BY_DEVICE_COLUMN + it.deviceId), bold = true) }
        }
        Row {
            Cell("Endpoint", ENDPOINT_WIDTH, bold = true)
            comparison.devices.forEach { DEVICE_COLUMNS.forEach { (label, width) -> Cell(label, width, bold = true, end = true) } }
        }
        Divider(Modifier.width(ENDPOINT_WIDTH + DEVICE_WIDTH * comparison.devices.size))
        if (comparison.endpoints.isEmpty()) Placeholder("No requests match")
        LazyColumn(Modifier.heightIn(max = 480.dp).testTag(BY_DEVICE_LIST)) {
            items(comparison.endpoints, key = { it.endpoint.toString() }) { endpoint ->
                val row = ByDeviceRow.of(endpoint, comparison.devices)
                Row(Modifier.padding(vertical = Spacing.xs)) {
                    Cell(row.endpoint, ENDPOINT_WIDTH)
                    row.devices.forEach { cells ->
                        val values = cells?.let { listOf(it.count, it.status, it.p50, it.p95, it.received) } ?: listOf("—", "", "", "", "")
                        values.zip(DEVICE_COLUMNS).forEach { (value, column) -> Cell(value, column.second, end = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    end: Boolean = false,
) = Text(
    text,
    modifier.width(width).padding(horizontal = Spacing.xs),
    textAlign = if (end) TextAlign.End else TextAlign.Start,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    role = if (bold) TextRole.SmallStrong else TextRole.Small,
)

private val ENDPOINT_WIDTH = 280.dp

private val DEVICE_COLUMNS = listOf("Count" to 56.dp, "Status" to 150.dp, "p50" to 64.dp, "p95" to 64.dp, "Received" to 80.dp)

private val DEVICE_WIDTH = DEVICE_COLUMNS.fold(0.dp) { sum, (_, width) -> sum + width }

const val BY_DEVICE_LIST = "by-device-list"

const val BY_DEVICE_COLUMN = "by-device-column-"

const val BY_DEVICE = "by-device"
