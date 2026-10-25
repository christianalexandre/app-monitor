package appmonitor.desktop.feature.traffic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.domain.AskedReplay
import appmonitor.desktop.domain.PendingApproval
import org.koin.compose.viewmodel.koinViewModel

/** The oldest replay an agent asked, then the oldest device to admit on top of it, each with how many more wait. */
@Composable
fun PendingDecisions(viewModel: DecisionsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    state.replays.firstOrNull()?.let { replay ->
        AskedReplayDialog(replay, state.replays.size - 1) { send -> viewModel.answer(replay, send) }
    }
    state.devices.firstOrNull()?.let { device ->
        ApprovalDialog(device, state.devices.size - 1) { approved -> viewModel.decide(device, approved) }
    }
}

@Composable
private fun ApprovalDialog(
    device: PendingApproval,
    waiting: Int,
    onDecide: (approved: Boolean) -> Unit,
) {
    DialogCard(DialogWidth.Narrow) {
        Text(
            "${device.hello.device_name.ifEmpty { "Unnamed device" }} — ${device.appId} wants to connect",
            role = TextRole.BodyStrong,
        )
        Text(
            listOf(device.hello.os_version, device.hello.app_version, "device ${device.deviceId}")
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            role = TextRole.Caption,
        )
        if (waiting > 0) Text("$waiting more waiting", role = TextRole.Caption)
        DialogActions {
            TextButton("Reject", onClick = { onDecide(false) })
            TextButton("Approve", onClick = { onDecide(true) })
        }
    }
}

/** ADR-0005, ADR-0121 B.1: nothing an agent asks reaches the device before "Send again". */
@Composable
private fun AskedReplayDialog(
    replay: AskedReplay,
    waiting: Int,
    onAnswer: (send: Boolean) -> Unit,
) {
    DialogCard(DialogWidth.Narrow) {
        Text("An agent asks to send this request again", role = TextRole.BodyStrong)
        Text("${replay.method ?: "—"} ${replay.url ?: replay.requestId}")
        Text(
            "${replay.deviceName.ifEmpty { "Unnamed device" }} — ${replay.appId} · exchange ${replay.exchangeId}",
            role = TextRole.Caption,
        )
        if (waiting > 0) Text("$waiting more waiting", role = TextRole.Caption)
        DialogActions {
            TextButton("Refuse", onClick = { onAnswer(false) }, Modifier.testTag(REFUSE_REPLAY))
            TextButton(replayLabelOf(replay.method), onClick = { onAnswer(true) }, Modifier.testTag(CONFIRM_REPLAY))
        }
    }
}
