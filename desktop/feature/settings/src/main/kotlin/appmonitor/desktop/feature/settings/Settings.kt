package appmonitor.desktop.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import appmonitor.desktop.designsystem.AppMonitorTheme
import appmonitor.desktop.designsystem.AppSurface
import appmonitor.desktop.designsystem.CheckboxRow
import appmonitor.desktop.designsystem.Choice
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Divider
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.domain.Retention
import appmonitor.desktop.domain.RetentionState
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.domain.ThemeMode
import appmonitor.desktop.ui.formatDateTime
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

/**
 * What the window shows when the store did not open: no server runs behind it (SDD 09). [onClearAll] deletes the files
 * after a confirmation and returns what to tell the developer.
 */
@Composable
fun StorageUnavailable(
    file: String,
    error: String,
    onClearAll: () -> String = { "" },
) {
    var confirming by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf<String?>(null) }
    AppMonitorTheme(isSystemInDarkTheme()) {
        AppSurface {
            Column(Modifier.padding(Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("App Monitor could not open its data", role = TextRole.Title)
                Text(error, error = true)
                Text(file, role = TextRole.Code)
                Text("No device is admitted until the file opens.", role = TextRole.Caption)
                val result = cleared
                when {
                    result != null -> {
                        Text(result)
                    }

                    confirming -> {
                        Text("Delete this file and everything in it? This cannot be undone.", error = true)
                        Row {
                            TextButton("Cancel", onClick = { confirming = false })
                            TextButton("Delete everything", onClick = { cleared = onClearAll() }, Modifier.testTag(CONFIRM_CLEAR))
                        }
                    }

                    else -> {
                        TextButton("Clear all data", onClick = { confirming = true }, Modifier.testTag(CLEAR_ALL))
                    }
                }
            }
        }
    }
}

/** The command the settings show; [path] and the header are what the server checks (ADR-0090). */
fun claudeMcpAdd(
    port: Int,
    path: String,
    token: String,
): String = "claude mcp add --transport http app-monitor http://127.0.0.1:$port$path --header \"Authorization: Bearer $token\""

/**
 * The theme (ADR-0145), retention with "Clear all data" behind a confirmation (ADR-0082), the MCP server's switch, state and command
 * (ADR-0090), the proxy's (ADR-0101), and every answered pair with "Forget" and "Forget all" (ADR-0083 C.1).
 */
@Composable
fun Settings(
    onClearAll: () -> Unit,
    onClose: () -> Unit,
) {
    val viewModel = koinViewModel<SettingsViewModel>()
    val state by viewModel.state.collectAsState()
    val retention = state.retention
    val answered = state.answered
    var confirming by remember { mutableStateOf(false) }
    DialogCard(DialogWidth.Medium, scrollable = true) {
        Text("Settings", role = TextRole.Title)
        state.theme?.let { theme ->
            Text("Appearance", role = TextRole.Heading)
            Choice("Theme", theme, ThemeMode.entries, { it.name }, any = null) { if (it != null) viewModel.setTheme(it) }
            Divider()
        }
        Text("Retention", role = TextRole.Heading)
        if (retention != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Choice("Keep", retention.maxAgeDays, AGE_CHOICES, ::ageOf, any = "Forever") {
                    viewModel.changeRetention { r -> r.copy(maxAgeDays = it) }
                }
                Choice("Up to", retention.maxBytes, SIZE_CHOICES, ::bytesOf, any = null) {
                    if (it != null) viewModel.changeRetention { r -> r.copy(maxBytes = it) }
                }
            }
        }
        Text(
            "${bytesOf(state.retentionState.usedBytes)} on disk. Named runs and the requests of issues are kept past both limits.",
            role = TextRole.Caption,
        )
        retentionWarning(
            state.retentionState,
            retention,
        )?.let { Text(it, error = true, role = TextRole.Caption) }
        if (confirming) {
            Text("Delete every run, request, issue and device answer? This cannot be undone.", error = true)
            DialogActions {
                TextButton("Cancel", onClick = { confirming = false })
                TextButton("Delete everything", onClick = {
                    confirming = false
                    onClearAll()
                }, Modifier.testTag(CONFIRM_CLEAR))
            }
        } else {
            TextButton("Clear all data", onClick = { confirming = true }, Modifier.testTag(CLEAR_ALL))
        }
        state.mcp?.let { mcp ->
            Divider()
            McpSection(mcp, viewModel::setMcpEnabled, viewModel::regenerateToken)
        }
        state.proxy?.let { proxy ->
            Divider()
            ProxySection(proxy, viewModel::setProxyEnabled, viewModel::resetProxyCa)
        }
        Divider()
        Text("Devices", role = TextRole.Heading)
        if (answered.isEmpty()) Text("No device answered yet", role = TextRole.Caption)
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(answered, key = { "${it.deviceId}/${it.appId}" }) { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${pair.deviceName.ifEmpty { "Unnamed device" }} — ${pair.appId}", role = TextRole.BodyStrong)
                        Text(
                            listOf(
                                if (pair.approved) "Approved" else "Rejected",
                                formatDateTime(pair.decidedAtMs),
                                pair.osVersion,
                                pair.appVersion,
                            ).filter { it.isNotEmpty() }.joinToString(" · "),
                            role = TextRole.Caption,
                        )
                    }
                    TextButton("Forget", onClick = { viewModel.forget(pair) })
                }
            }
        }
        DialogActions {
            TextButton("Forget all", onClick = { viewModel.forget(null) }, enabled = answered.isNotEmpty())
            TextButton("Close", onClick = onClose)
        }
    }
}

@Composable
private fun McpSection(
    mcp: McpSettings,
    onEnabled: (Boolean) -> Unit,
    onRegenerateToken: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val command = claudeMcpAdd((mcp.status as? ServiceStatus.Running)?.port ?: mcp.port, mcp.path, mcp.token)
    Text("MCP server", role = TextRole.Heading)
    CheckboxRow("Serve the stored traffic to AI agents over MCP", mcp.enabled, onEnabled, Modifier.testTag(MCP_SWITCH))
    val (state, error) = mcpStateOf(mcp.status, mcp.path)
    Text(
        state,
        Modifier.testTag(MCP_STATUS),
        error = error,
        role = TextRole.Caption,
    )
    if (mcp.enabled) {
        Text("Add it to Claude Code:", role = TextRole.Caption)
        SelectionContainer {
            Text(command, role = TextRole.CodeCaption)
        }
        Row {
            TextButton("Copy command", onClick = { clipboard.setText(AnnotatedString(command)) })
            TextButton("Regenerate token", onClick = onRegenerateToken)
        }
    }
}

private fun mcpStateOf(
    status: ServiceStatus,
    path: String,
): Pair<String, Boolean> =
    when (status) {
        ServiceStatus.Off -> "Off" to false
        ServiceStatus.Starting -> "Starting…" to false
        is ServiceStatus.Running -> "Listening on http://127.0.0.1:${status.port}$path" to false
        is ServiceStatus.PortTaken -> "Port ${status.port} in use: quit what holds it, or set APPMONITOR_MCP_PORT" to true
        is ServiceStatus.Failed -> "Failed: ${status.message}" to true
    }

/** The launch command of ADR-0108: gRPC-Core in the app reads `grpc_proxy`, and its TLS roots from [caFile]. */
fun simulatorLaunch(
    host: String,
    port: Int,
    caFile: String,
): String =
    "SIMCTL_CHILD_grpc_proxy=http://$host:$port SIMCTL_CHILD_GRPC_DEFAULT_SSL_ROOTS_FILE_PATH=\"$caFile\" " +
        "xcrun simctl launch booted <bundle id>"

fun curlThroughProxy(
    host: String,
    port: Int,
    caFile: String,
): String = "curl --proxy http://$host:$port --cacert \"$caFile\" https://example.com"

@Composable
private fun ProxySection(
    proxy: ProxySettings,
    onEnabled: (Boolean) -> Unit,
    onResetCa: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val port = (proxy.status as? ServiceStatus.Running)?.port ?: proxy.port
    Text("Proxy", role = TextRole.Heading)
    CheckboxRow("Record what apps send through a proxy on 127.0.0.1", proxy.enabled, onEnabled, Modifier.testTag(PROXY_SWITCH))
    val (state, error) = proxyStateOf(proxy.status, proxy.host)
    Text(
        state,
        Modifier.testTag(PROXY_STATUS),
        error = error,
        role = TextRole.Caption,
    )
    if (proxy.enabled) {
        Text("Launch a simulator app through it, or try it with curl:", role = TextRole.Caption)
        listOf(simulatorLaunch(proxy.host, port, proxy.caFile), curlThroughProxy(proxy.host, port, proxy.caFile)).forEach { command ->
            SelectionContainer {
                Text(command, role = TextRole.CodeCaption)
            }
        }
        Row {
            TextButton(
                "Copy launch command",
                onClick = { clipboard.setText(AnnotatedString(simulatorLaunch(proxy.host, port, proxy.caFile))) },
            )
            TextButton("Reset proxy CA", onClick = onResetCa, Modifier.testTag(PROXY_RESET_CA))
        }
        Text(
            "After a reset, relaunch the apps started with the old CA.",
            role = TextRole.Caption,
        )
    }
}

private fun proxyStateOf(
    status: ServiceStatus,
    host: String,
): Pair<String, Boolean> =
    when (status) {
        ServiceStatus.Off -> "Off" to false
        ServiceStatus.Starting -> "Starting…" to false
        is ServiceStatus.Running -> "Listening on http://$host:${status.port}" to false
        is ServiceStatus.PortTaken -> "Port ${status.port} in use: quit what holds it, or set APPMONITOR_PROXY_PORT" to true
        is ServiceStatus.Failed -> "Failed: ${status.message}" to true
    }

private val AGE_CHOICES = listOf(1, 3, 7, 14, 30, 90)

private val SIZE_CHOICES = listOf(100L shl 20, 500L shl 20, 1L shl 30, 2L shl 30, 5L shl 30, 10L shl 30, 20L shl 30)

private fun ageOf(days: Int): String = if (days == 1) "1 day" else "$days days"

private fun bytesOf(bytes: Long): String =
    when {
        bytes >= 1L shl 30 -> "%.1f GiB".format(Locale.ENGLISH, bytes / (1L shl 30).toDouble()).replace(".0 ", " ")
        bytes >= 1L shl 20 -> "${bytes shr 20} MiB"
        else -> "${bytes shr 10} KiB"
    }

/** Null while retention keeps the file within its bounds and its last pass did not fail. */
fun retentionWarning(
    status: RetentionState,
    retention: Retention? = null,
): String? =
    when {
        status.lastError != null -> {
            "Retention failed: ${status.lastError}"
        }

        status.keptOverBound -> {
            "Named runs, issues and connected devices pass the size limit" + (retention?.let { " of ${bytesOf(it.maxBytes)}" } ?: "")
        }

        else -> {
            null
        }
    }

const val CLEAR_ALL = "clear-all"

const val CONFIRM_CLEAR = "confirm-clear"

const val MCP_SWITCH = "mcp-switch"

const val PROXY_SWITCH = "proxy-switch"

const val PROXY_STATUS = "proxy-status"

const val PROXY_RESET_CA = "proxy-reset-ca"

const val MCP_STATUS = "mcp-status"
