package appmonitor.desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.app.store.HeldCall
import appmonitor.desktop.app.store.HeldCalls
import appmonitor.desktop.app.store.HeldEdit
import appmonitor.desktop.designsystem.CheckboxRow
import appmonitor.desktop.designsystem.Choice
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextField
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.designsystem.selectableItem
import appmonitor.desktop.feature.traffic.METHODS
import appmonitor.desktop.feature.traffic.formatDuration
import appmonitor.desktop.feature.traffic.utf8OrNull
import appmonitor.desktop.storage.BreakpointChange
import appmonitor.desktop.storage.BreakpointWrite
import appmonitor.desktop.storage.MAX_BREAKPOINT_TIMEOUT_MS
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredBreakpoint
import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import kotlinx.coroutines.launch
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every app's breakpoints in match order, each turned on and off here; [appIds] are the apps a new one can be for (ADR-0119). */
@Composable
internal fun BreakpointList(
    storage: Storage,
    appIds: List<String>,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val breakpoints by remember(storage) { storage.observeBreakpoints() }.collectAsState(emptyList())
    // Null: the list; a stored id, or 0 for a new one, opens the form.
    var editing by remember { mutableStateOf<Long?>(null) }
    DialogCard(DialogWidth.Wide) {
        editing?.let { id ->
            val stored = breakpoints.firstOrNull { it.id == id }
            if (id == NEW || stored != null) {
                key(id) { BreakpointEditor(storage, appIds, stored) { editing = null } }
                return@DialogCard
            }
        }
        Text("Breakpoints", role = TextRole.Title)
        Text(
            "The first breakpoint on that matches a call holds it on the device until you resume it, or its timeout passes.",
            role = TextRole.Caption,
        )
        if (breakpoints.isEmpty()) Placeholder("No breakpoints yet: \"New breakpoint\"")
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(breakpoints, key = { it.id }) { breakpoint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).selectableItem(false) { editing = breakpoint.id }.padding(vertical = Spacing.sm)) {
                        Text(breakpoint.name, role = TextRole.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${breakpoint.appId} · ${summaryOf(breakpoint.rule)}",
                            role = TextRole.Caption,
                            maxLines = 1,
                        )
                        if (!breakpoint.enabled) Text("Off", role = TextRole.Caption)
                    }
                    TextButton(
                        if (breakpoint.enabled) "Turn off" else "Turn on",
                        onClick = {
                            scope.launch {
                                storage.updateBreakpoint(
                                    breakpoint.id,
                                    BreakpointChange(enabled = !breakpoint.enabled),
                                )
                            }
                        },
                        Modifier.testTag(TOGGLE_BREAKPOINT + breakpoint.id),
                    )
                }
            }
        }
        DialogActions {
            TextButton("New breakpoint", onClick = { editing = NEW }, Modifier.testTag(NEW_BREAKPOINT))
            TextButton("Close", onClick = onClose)
        }
    }
}

private const val NEW = 0L

// Sends the name and the whole rule on save: `enabled` is left to the list.
@Composable
private fun BreakpointEditor(
    storage: Storage,
    appIds: List<String>,
    stored: StoredBreakpoint?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rule = stored?.rule ?: BreakpointRule(path = "/", request = true, response = true, timeout_ms = DEFAULT_TIMEOUT_MS)
    var appId by remember { mutableStateOf(stored?.appId ?: appIds.firstOrNull().orEmpty()) }
    var name by remember { mutableStateOf(stored?.name.orEmpty()) }
    var method by remember { mutableStateOf(rule.method.ifEmpty { null }) }
    var host by remember { mutableStateOf(rule.host) }
    var path by remember { mutableStateOf(rule.path) }
    var request by remember { mutableStateOf(rule.request) }
    var response by remember { mutableStateOf(rule.response) }
    var timeout by remember { mutableStateOf((rule.timeout_ms / 1_000).coerceAtLeast(1).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val built = buildBreakpoint(name, appId, method, host, path, request, response, timeout)

    Text(if (stored == null) "New breakpoint" else "Breakpoint", role = TextRole.Title)
    TextField(name, { name = it }, Modifier.fillMaxWidth().testTag(BREAKPOINT_NAME), label = "Name", singleLine = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (stored == null) {
            Choice("App", appId.ifEmpty { null }, appIds, { it }, any = "none") { if (it != null) appId = it }
        } else {
            Text("App: $appId", Modifier.padding(horizontal = Spacing.md))
        }
        Choice("Method", method, METHODS, { it }) { method = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        TextField(host, { host = it }, Modifier.weight(1f).testTag(BREAKPOINT_HOST), label = "Host", singleLine = true)
        TextField(
            path,
            { path = it },
            Modifier.weight(1f).testTag(BREAKPOINT_PATH),
            label = "Path, * for one segment",
            singleLine = true,
        )
        TextField(timeout, { timeout = it }, Modifier.width(120.dp), label = "Timeout, s", singleLine = true)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CheckboxRow("Hold the request", request, { request = it }, Modifier.padding(end = Spacing.xl))
        CheckboxRow("Hold the response", response, { response = it })
    }
    (error ?: built.exceptionOrNull()?.message)?.let {
        Text(it, error = true, role = TextRole.Caption)
    }
    if (confirmingDelete) Text("Delete this breakpoint? A device stops getting it at once.", error = true)
    DialogActions {
        if (confirmingDelete && stored != null) {
            TextButton("Cancel", onClick = { confirmingDelete = false })
            TextButton("Delete breakpoint", onClick = {
                scope.launch { storage.deleteBreakpoint(stored.id) }
                onBack()
            })
            return@DialogActions
        }
        if (stored != null) TextButton("Delete", onClick = { confirmingDelete = true })
        TextButton(if (stored == null) "Cancel" else "Back", onClick = onBack)
        TextButton(
            "Save",
            onClick = {
                val made = built.getOrNull() ?: return@TextButton
                scope.launch {
                    val write =
                        if (stored == null) {
                            storage.createBreakpoint(appId, name, made)
                        } else {
                            storage.updateBreakpoint(stored.id, BreakpointChange(name = name, rule = made))
                        }
                    if (write is BreakpointWrite.Saved) onBack() else error = failureOf(write)
                }
            },
            Modifier.testTag(SAVE_BREAKPOINT),
            enabled = built.isSuccess,
        )
    }
}

/** The breakpoint the form describes, or why it is not one yet (the checks `Storage` makes, said first). */
private fun buildBreakpoint(
    name: String,
    appId: String,
    method: String?,
    host: String,
    path: String,
    request: Boolean,
    response: Boolean,
    timeout: String,
): Result<BreakpointRule> =
    runCatching {
        require(name.isNotBlank()) { "A breakpoint needs a name" }
        require(appId.isNotBlank()) { "A breakpoint needs an app" }
        require(host.isNotBlank()) { "A breakpoint needs a host" }
        require(path.trim().startsWith("/")) { "The path starts with /" }
        require(request || response) { "Hold the request, the response or both" }
        val maxSeconds = MAX_BREAKPOINT_TIMEOUT_MS / 1_000
        val seconds =
            timeout.trim().toLongOrNull()?.takeIf { it in 1..maxSeconds }
                ?: throw IllegalArgumentException("The timeout is 1 to $maxSeconds s")
        BreakpointRule(
            method = method.orEmpty(),
            host = host.trim(),
            path = path.trim(),
            request = request,
            response = response,
            timeout_ms = seconds * 1_000,
        )
    }

private fun summaryOf(rule: BreakpointRule): String {
    val match = "${rule.method.ifEmpty { "Any" }} ${rule.host}${rule.path}"
    val holds =
        when {
            rule.request && rule.response -> "request and response"
            rule.request -> "request"
            else -> "response"
        }
    return "$match · holds the $holds · ${formatDuration(rule.timeout_ms)}"
}

private fun failureOf(write: BreakpointWrite): String =
    when (write) {
        is BreakpointWrite.Saved -> ""
        BreakpointWrite.UnknownBreakpoint -> "Not saved: the breakpoint was deleted"
        BreakpointWrite.NotSaved -> "Not saved: the store could not write it"
    }

/**
 * The oldest call a device holds, to edit and resume or to abort; [waiting] more are held behind it (ADR-0119 A.1).
 * Only what the developer changes is sent back (ADR-0111 C.1).
 */
@Composable
internal fun HeldCallDialog(
    call: HeldCall,
    waiting: Int,
    heldCalls: HeldCalls,
) {
    val before = call.unedited
    val atRequest = call.stage == BreakpointStage.BREAKPOINT_STAGE_REQUEST
    val truncated = if (atRequest) call.hit.request?.body_truncated == true else call.hit.response?.body_truncated == true
    var method by remember { mutableStateOf(before.method) }
    var url by remember { mutableStateOf(before.url) }
    var status by remember { mutableStateOf(before.statusCode.toString()) }
    var headers by remember { mutableStateOf(before.headers.joinToString("\n") { "${it.name}: ${it.value_}" }) }
    // A body that is not UTF-8 is kept as it came, or cleared.
    var binary by remember { mutableStateOf(before.body.takeIf { utf8OrNull(it) == null }) }
    var text by remember { mutableStateOf(utf8OrNull(before.body).orEmpty()) }
    var abortMessage by remember { mutableStateOf("Aborted from App Monitor") }
    val edit = buildEdit(before, atRequest, method, url, status, headers, binary ?: text.encodeUtf8())

    DialogCard(DialogWidth.Wide) {
        Text(if (atRequest) "Request held" else "Response held", role = TextRole.Title)
        Text(
            "${call.appId} · ${before.method} ${before.url} · goes on unchanged at ${TIME_FORMAT.format(
                Instant.ofEpochMilli(call.deadlineMs),
            )}",
            role = TextRole.Caption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (waiting > 0) Text("$waiting more held", role = TextRole.Caption)
        Column(
            Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (atRequest) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Choice("Method", method, (METHODS + before.method).distinct(), { it }, any = null) {
                        if (it !=
                            null
                        ) {
                            method = it
                        }
                    }
                    TextField(
                        url,
                        { url = it },
                        Modifier.weight(1f).testTag(HELD_URL),
                        label = "URL",
                        singleLine = true,
                    )
                }
            } else {
                TextField(
                    status,
                    { status = it },
                    Modifier.width(120.dp).testTag(HELD_STATUS),
                    label = "Status",
                    singleLine = true,
                )
            }
            TextField(
                headers,
                { headers = it },
                Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(HELD_HEADERS),
                label = "Headers, one \"Name: value\" per line; ██ left as is keeps the device's value",
            )
            val kept = binary
            if (kept != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Binary body, ${kept.size} bytes, kept as captured", Modifier.weight(1f))
                    TextButton("Clear", onClick = { binary = null })
                }
            } else {
                TextField(
                    text,
                    { text = it },
                    Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp).testTag(HELD_BODY),
                    label = "Body",
                )
            }
            if (truncated) {
                Text(
                    "The device cut this body: left alone it goes on whole, edited it is replaced by what is shown",
                    role = TextRole.Caption,
                )
            }
            TextField(
                abortMessage,
                { abortMessage = it },
                Modifier.fillMaxWidth(),
                label = "Error the app gets on abort",
                singleLine = true,
            )
        }
        edit.exceptionOrNull()?.message?.let {
            Text(it, error = true, role = TextRole.Caption)
        }
        DialogActions {
            TextButton("Abort", onClick = { heldCalls.abort(call, abortMessage) }, Modifier.testTag(ABORT_HELD))
            TextButton(
                "Resume",
                onClick = { edit.getOrNull()?.let { heldCalls.resume(call, it) } },
                Modifier.testTag(RESUME_HELD),
                enabled = edit.isSuccess,
            )
        }
    }
}

/** The held side as the form leaves it, or why it cannot be sent; the request stage keeps [before]'s status. */
private fun buildEdit(
    before: HeldEdit,
    atRequest: Boolean,
    method: String,
    url: String,
    status: String,
    headers: String,
    body: ByteString,
): Result<HeldEdit> =
    runCatching {
        require(!atRequest || url.isNotBlank()) { "The request needs a URL" }
        val code =
            if (atRequest) {
                before.statusCode
            } else {
                status.trim().toIntOrNull()?.takeIf { it in 100..599 } ?: throw IllegalArgumentException("The status is 100 to 599")
            }
        require(body.size <= Protocol.MAX_BODY_BYTES) { "The body is over ${Protocol.MAX_BODY_BYTES} bytes" }
        HeldEdit(method, url.trim(), code, headersOf(headers), body)
    }

/** "Breakpoints", with the calls held now counted. */
internal fun breakpointsLabel(held: Int): String = if (held == 0) "Breakpoints" else "Breakpoints ($held held)"

private const val DEFAULT_TIMEOUT_MS = 60_000L

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

internal const val NEW_BREAKPOINT = "new-breakpoint"

internal const val BREAKPOINT_NAME = "breakpoint-name"

internal const val BREAKPOINT_HOST = "breakpoint-host"

internal const val BREAKPOINT_PATH = "breakpoint-path"

internal const val SAVE_BREAKPOINT = "save-breakpoint"

internal const val TOGGLE_BREAKPOINT = "toggle-breakpoint-"

internal const val HELD_URL = "held-url"

internal const val HELD_STATUS = "held-status"

internal const val HELD_HEADERS = "held-headers"

internal const val HELD_BODY = "held-body"

internal const val RESUME_HELD = "resume-held"

internal const val ABORT_HELD = "abort-held"
