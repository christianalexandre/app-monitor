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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import appmonitor.desktop.app.store.HttpExchange
import appmonitor.desktop.app.store.fittingRules
import appmonitor.desktop.storage.MockChange
import appmonitor.desktop.storage.MockSource
import appmonitor.desktop.storage.MockWrite
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredMock
import appmonitor.protocol.Protocol
import appmonitor.v1.Header
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import kotlinx.coroutines.launch
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.zip.GZIPInputStream

/** What a new rule starts from: an empty form, or an exchange's "Mock this" (ADR-0114 B.1). */
internal data class MockDraft(
    val appId: String,
    val name: String = "",
    val rule: MockRule = MockRule(path = "/", response = MockResponse(status_code = 200)),
)

/** A rule answering what [exchange] got, for [appId]: framing and encoding headers left out (ADR-0114 B.1). */
internal fun mockDraftOf(
    exchange: HttpExchange,
    appId: String,
): MockDraft {
    val uri =
        try {
            exchange.url?.let(::URI)
        } catch (_: URISyntaxException) {
            null
        }
    val path = uri?.rawPath?.ifEmpty { "/" } ?: "/"
    val answer =
        if (exchange.statusCode == null && exchange.error != null) {
            MockRule(failure = MockFailure(exchange.error))
        } else {
            MockRule(
                response =
                    MockResponse(
                        status_code = exchange.statusCode ?: 200,
                        headers = exchange.responseHeaders.filter { it.name.lowercase() !in LEFT_OUT_HEADERS },
                        body = decodedBody(exchange.responseBody.bytes),
                    ),
            )
        }
    return MockDraft(
        appId = appId,
        name = "${exchange.method ?: "Any"} $path",
        rule = answer.copy(method = exchange.method.orEmpty(), host = uri?.host.orEmpty(), path = path),
    )
}

private val LEFT_OUT_HEADERS = setOf("content-length", "transfer-encoding", "content-encoding")

/** [body] gunzipped when it is still gzip, as `sdk/ktor` stores it (SDD 08); else, or cut by the device, as it is. */
private fun decodedBody(body: ByteString): ByteString {
    if (body.size < 2 || body[0] != 0x1f.toByte() || body[1] != 0x8b.toByte()) return body
    return try {
        GZIPInputStream(body.toByteArray().inputStream()).use { input ->
            val out = input.readNBytes(Protocol.MAX_BODY_BYTES.toInt() + 1)
            if (out.size > Protocol.MAX_BODY_BYTES) body else out.toByteString()
        }
    } catch (_: IOException) {
        body
    }
}

/**
 * Every app's rules in match order: pending ones to confirm, active ones past one frame marked (ADR-0114 A.1).
 * [draft] opens a new rule at once; [appIds] are the apps a new rule can be for.
 */
@Composable
internal fun MockList(
    storage: Storage,
    appIds: List<String>,
    draft: MockDraft?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val mocks by remember(storage) { storage.observeMocks() }.collectAsState(emptyList())
    var editing by remember { mutableStateOf<Editing?>(draft?.let(Editing::New)) }
    val cut = remember(mocks) { cutOf(mocks) }
    Overlay {
        Card(Modifier.width(720.dp), elevation = 8.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val shown = editing) {
                    is Editing.New -> {
                        key(shown) { MockEditor(storage, appIds, shown.draft, null) { editing = null } }
                        return@Column
                    }

                    is Editing.Stored -> {
                        val mock = mocks.firstOrNull { it.id == shown.id }
                        if (mock != null) {
                            key(
                                mock.id,
                            ) { MockEditor(storage, appIds, MockDraft(mock.appId, mock.name, mock.rule), mock) { editing = null } }
                            return@Column
                        }
                    }

                    null -> {}
                }
                Text("Mocks", style = MaterialTheme.typography.h6)
                Text(
                    "The first active rule that matches a call answers it on the device, without the network.",
                    style = MaterialTheme.typography.caption,
                )
                if (mocks.isEmpty()) Placeholder("No rules yet: \"New rule\", or \"Mock this\" on a request")
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(mocks, key = { it.id }) { mock ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).selectable(false) { editing = Editing.Stored(mock.id) }.padding(vertical = 6.dp)) {
                                Text(mock.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${mock.appId} · ${summaryOf(mock.rule)}", style = MaterialTheme.typography.caption, maxLines = 1)
                                stateOf(mock, mock.id in cut)?.let { (text, warn) ->
                                    Text(
                                        text,
                                        style = MaterialTheme.typography.caption,
                                        color = if (warn) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
                                    )
                                }
                            }
                            if (mock.pending) {
                                TextButton(
                                    onClick = { scope.launch { storage.confirmMock(mock.id) } },
                                    Modifier.testTag(CONFIRM_MOCK + mock.id),
                                ) { Text("Confirm") }
                            }
                            TextButton(onClick = { scope.launch { storage.updateMock(mock.id, MockChange(enabled = !mock.enabled)) } }) {
                                Text(if (mock.enabled) "Turn off" else "Turn on")
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { editing = Editing.New(MockDraft(appIds.firstOrNull().orEmpty())) },
                        Modifier.testTag(NEW_MOCK),
                    ) { Text("New rule") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

private sealed interface Editing {
    data class New(
        val draft: MockDraft,
    ) : Editing

    data class Stored(
        val id: Long,
    ) : Editing
}

// Sends the whole rule on save: name and rule are the editor's, `enabled` is left to the list.
@Composable
private fun MockEditor(
    storage: Storage,
    appIds: List<String>,
    draft: MockDraft,
    stored: StoredMock?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rule = draft.rule
    var appId by remember { mutableStateOf(draft.appId) }
    var name by remember { mutableStateOf(draft.name) }
    var method by remember { mutableStateOf(rule.method.ifEmpty { null }) }
    var host by remember { mutableStateOf(rule.host) }
    var path by remember { mutableStateOf(rule.path) }
    var delay by remember { mutableStateOf(rule.delay_ms.toString()) }
    var fails by remember { mutableStateOf(rule.failure != null) }
    var status by remember { mutableStateOf((rule.response?.status_code ?: 200).toString()) }
    var headers by remember {
        mutableStateOf(
            rule.response
                ?.headers
                .orEmpty()
                .joinToString("\n") { "${it.name}: ${it.value_}" },
        )
    }
    val body = rule.response?.body ?: ByteString.EMPTY
    // A body that is not UTF-8 is kept as it came, or cleared.
    var binary by remember { mutableStateOf(body.takeIf { utf8OrNull(it) == null }) }
    var text by remember { mutableStateOf(utf8OrNull(body).orEmpty()) }
    var failure by remember { mutableStateOf(rule.failure?.message.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val built =
        buildRule(
            name,
            appId,
            method,
            host,
            path,
            delay,
            fails,
            status,
            headers,
            binary ?: text.encodeUtf8(),
            failure,
        )

    Text(if (stored == null) "New mock rule" else "Mock rule", style = MaterialTheme.typography.h6)
    stored?.let { mock -> stateOf(mock, cut = false)?.let { Text(it.first, style = MaterialTheme.typography.caption) } }
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag(MOCK_NAME), label = { Text("Name") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stored == null) {
                Choice("App", appId.ifEmpty { null }, (appIds + draft.appId).filter { it.isNotEmpty() }.distinct(), { it }, any = "none") {
                    if (it != null) appId = it
                }
            } else {
                Text("App: $appId", Modifier.padding(horizontal = 8.dp))
            }
            Choice("Method", method, METHODS, { it }) { method = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, Modifier.weight(1f).testTag(MOCK_HOST), label = { Text("Host") }, singleLine = true)
            OutlinedTextField(
                path,
                { path = it },
                Modifier.weight(1f).testTag(MOCK_PATH),
                label = { Text("Path, * for one segment") },
                singleLine = true,
            )
            OutlinedTextField(delay, { delay = it }, Modifier.width(120.dp), label = { Text("Delay, ms") }, singleLine = true)
        }
        Choice("Answer", fails, listOf(false, true), { if (it) "Failure" else "Response" }, any = null) { if (it != null) fails = it }
        if (fails) {
            OutlinedTextField(
                failure,
                { failure = it },
                Modifier.fillMaxWidth().testTag(MOCK_FAILURE),
                label = { Text("Error message") },
                singleLine = true,
            )
        } else {
            OutlinedTextField(
                status,
                { status = it },
                Modifier.width(120.dp).testTag(MOCK_STATUS),
                label = { Text("Status") },
                singleLine = true,
            )
            OutlinedTextField(
                headers,
                { headers = it },
                Modifier.fillMaxWidth().heightIn(min = 72.dp),
                label = { Text("Headers, one \"Name: value\" per line") },
            )
            val kept = binary
            if (kept != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Binary body, ${kept.size} bytes, kept as captured", Modifier.weight(1f))
                    TextButton(onClick = { binary = null }) { Text("Clear") }
                }
            } else {
                OutlinedTextField(
                    text,
                    { text = it },
                    Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp).testTag(MOCK_BODY),
                    label = { Text("Body") },
                )
            }
        }
    }
    (error ?: built.exceptionOrNull()?.message)?.let {
        Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
    }
    if (confirmingDelete) Text("Delete this rule? A device stops getting it at once.", color = MaterialTheme.colors.error)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (confirmingDelete && stored != null) {
            TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            TextButton(onClick = {
                scope.launch { storage.deleteMock(stored.id) }
                onBack()
            }, Modifier.testTag(CONFIRM_DELETE_MOCK)) { Text("Delete rule") }
            return@Row
        }
        if (stored != null) TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
        TextButton(onClick = onBack) { Text(if (stored == null) "Cancel" else "Back") }
        TextButton(
            onClick = {
                val made = built.getOrNull() ?: return@TextButton
                scope.launch {
                    val write =
                        if (stored == null) {
                            storage.createMock(appId, name, made, MockSource.WINDOW)
                        } else {
                            storage.updateMock(stored.id, MockChange(name = name, rule = made))
                        }
                    if (write is MockWrite.Saved) onBack() else error = failureOf(write)
                }
            },
            Modifier.testTag(SAVE_MOCK),
            enabled = built.isSuccess,
        ) { Text("Save") }
    }
}

/** The rule the form describes, or why it is not one yet (the checks `Storage` makes, said first). */
private fun buildRule(
    name: String,
    appId: String,
    method: String?,
    host: String,
    path: String,
    delay: String,
    fails: Boolean,
    status: String,
    headers: String,
    body: ByteString,
    failure: String,
): Result<MockRule> =
    runCatching {
        require(name.isNotBlank()) { "A rule needs a name" }
        require(appId.isNotBlank()) { "A rule needs an app" }
        require(host.isNotBlank()) { "A rule needs a host" }
        require(path.startsWith("/")) { "The path starts with /" }
        val delayMs = delay.trim().toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("The delay is a number of ms")
        val answer =
            if (fails) {
                MockRule(failure = MockFailure(failure))
            } else {
                val code =
                    status.trim().toIntOrNull()?.takeIf { it in 100..599 } ?: throw IllegalArgumentException("The status is 100 to 599")
                require(body.size <= Protocol.MAX_BODY_BYTES) { "The body is over ${Protocol.MAX_BODY_BYTES} bytes" }
                MockRule(response = MockResponse(status_code = code, headers = headersOf(headers), body = body))
            }
        answer.copy(method = method.orEmpty(), host = host.trim(), path = path.trim(), delay_ms = delayMs)
    }

private fun headersOf(text: String): List<Header> =
    text.lines().filter { it.isNotBlank() }.map { line ->
        val colon = line.indexOf(':')
        require(colon > 0) { "A header line is \"Name: value\"" }
        Header(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
    }

/** Ids of active rules a device is not sent, as they come after the first that does not fit one frame (ADR-0113 C.1). */
private fun cutOf(mocks: List<StoredMock>): Set<Long> =
    mocks
        .filter { it.active }
        .groupBy { it.appId }
        .values
        .flatMap { active -> active.drop(fittingRules(active.map { it.rule })) }
        .map { it.id }
        .toSet()

/** What a device gets of [mock], and whether that needs the developer: null when it is sent. */
private fun stateOf(
    mock: StoredMock,
    cut: Boolean,
): Pair<String, Boolean>? =
    when {
        mock.pending -> "From MCP: not sent until you confirm it" to true
        !mock.enabled -> "Off" to false
        cut -> "Not sent: past one frame, with the rules before it" to true
        else -> null
    }

private fun summaryOf(rule: MockRule): String {
    val match = "${rule.method.ifEmpty { "Any" }} ${rule.host}${rule.path}"
    val answer = rule.failure?.let { "fails: ${it.message}" } ?: "${rule.response?.status_code}"
    val delay = if (rule.delay_ms > 0) " · ${formatDuration(rule.delay_ms)}" else ""
    return "$match → $answer$delay"
}

private fun failureOf(write: MockWrite): String =
    when (write) {
        is MockWrite.Saved -> ""
        MockWrite.UnknownMock -> "Not saved: the rule was deleted"
        MockWrite.NotSaved -> "Not saved: the store could not write it"
    }

/** "Mocks", with the rules waiting for the developer's confirmation counted. */
internal fun mocksLabel(pending: Int): String = if (pending == 0) "Mocks" else "Mocks ($pending to confirm)"

internal const val MOCKS = "mocks"

internal const val MOCK_THIS = "mock-this"

internal const val NEW_MOCK = "new-mock"

internal const val MOCK_NAME = "mock-name"

internal const val MOCK_HOST = "mock-host"

internal const val MOCK_PATH = "mock-path"

internal const val MOCK_STATUS = "mock-status"

internal const val MOCK_BODY = "mock-body"

internal const val MOCK_FAILURE = "mock-failure"

internal const val SAVE_MOCK = "save-mock"

internal const val CONFIRM_MOCK = "confirm-mock-"

internal const val CONFIRM_DELETE_MOCK = "confirm-delete-mock"
