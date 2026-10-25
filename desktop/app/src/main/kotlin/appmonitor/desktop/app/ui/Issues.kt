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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.data.toDomain
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
import appmonitor.desktop.feature.traffic.statusOf
import appmonitor.desktop.storage.IssueChange
import appmonitor.desktop.storage.IssueFilter
import appmonitor.desktop.storage.IssueSource
import appmonitor.desktop.storage.IssueStatus
import appmonitor.desktop.storage.IssueWrite
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredExchange
import appmonitor.desktop.storage.StoredIssue
import kotlinx.coroutines.launch
import java.time.Instant

/** Title, note and tags for a new issue linking [exchangeIds] (ADR-0095 A.1); [onSaved] once the writer stored it. */
@Composable
internal fun SaveIssueDialog(
    storage: Storage,
    exchangeIds: Set<Long>,
    defaultTitle: String,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(defaultTitle) }
    var note by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    DialogCard(DialogWidth.Medium) {
        Text("Save as issue", role = TextRole.Title)
        Text(requestsOf(exchangeIds.size.toLong()), role = TextRole.Caption)
        IssueFields(title, { title = it }, note, { note = it }, tags, { tags = it })
        error?.let { Text(it, error = true, role = TextRole.Caption) }
        DialogActions {
            TextButton("Cancel", onClick = onClose)
            TextButton(
                "Save",
                onClick = {
                    scope.launch {
                        val write = storage.createIssue(title, note, tagsOf(tags), exchangeIds, IssueSource.WINDOW)
                        if (write is IssueWrite.Saved) onSaved() else error = failureOf(write)
                    }
                },
                Modifier.testTag(SAVE_ISSUE),
                enabled = title.isNotBlank(),
            )
        }
    }
}

/** Every issue under a status and a tag filter, newest first; one opens to edit its title, note, tags and status. */
@Composable
internal fun IssueList(
    storage: Storage,
    onClose: () -> Unit,
) {
    var filter by remember { mutableStateOf(IssueFilter()) }
    val issues by remember(storage, filter) { storage.observeIssues(filter) }.collectAsState(emptyList())
    val tags by remember(storage) { storage.observeIssueTags() }.collectAsState(emptyList())
    // The open issue stays open when an edit moves it out of the filter.
    val all by remember(storage) { storage.observeIssues() }.collectAsState(emptyList())
    var open by remember { mutableStateOf<Long?>(null) }
    val issue = all.firstOrNull { it.id == open }
    DialogCard(DialogWidth.Large) {
        if (issue != null) {
            // Drafts belong to one issue.
            key(issue.id) { IssueEditor(storage, issue) { open = null } }
            return@DialogCard
        }
        Text("Issues", role = TextRole.Title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Choice("Status", filter.status, IssueStatus.entries, ::labelOf) { filter = filter.copy(status = it) }
            Choice("Tag", filter.tag, tags, { it }) { filter = filter.copy(tag = it) }
        }
        if (issues.isEmpty()) {
            Placeholder(if (filter == IssueFilter()) "No issues yet: select requests and \"Save as issue\"" else "No issues match")
        }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(issues, key = { it.id }) { issue ->
                Column(Modifier.fillMaxWidth().selectableItem(false) { open = issue.id }.padding(vertical = Spacing.sm)) {
                    Text(issue.title, role = TextRole.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(summaryOf(issue), role = TextRole.Caption)
                }
            }
        }
        DialogActions {
            TextButton("Close", onClick = onClose)
        }
    }
}

// Sends only the fields changed here, so an MCP edit of another field since the issue opened is kept.
@Composable
private fun IssueEditor(
    storage: Storage,
    issue: StoredIssue,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val opened = remember { issue }
    var title by remember { mutableStateOf(opened.title) }
    var note by remember { mutableStateOf(opened.note) }
    val openedTags = remember { opened.tags.joinToString(", ") }
    var tags by remember { mutableStateOf(openedTags) }
    var status by remember { mutableStateOf(opened.status) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val exchanges by produceState(emptyList<StoredExchange>(), issue.exchangeCount) { value = storage.issueExchanges(issue.id) }
    val change =
        IssueChange(
            title = title.takeIf { it != opened.title },
            note = note.takeIf { it != opened.note },
            tags = tagsOf(tags).takeIf { tags != openedTags && it != opened.tags },
            status = status.takeIf { it != opened.status },
        )
    Text("Issue", role = TextRole.Title)
    Text(summaryOf(issue), role = TextRole.Caption)
    IssueFields(title, { title = it }, note, { note = it }, tags, { tags = it })
    Choice("Status", status, IssueStatus.entries, ::labelOf, any = null) { if (it != null) status = it }
    Text("Requests", role = TextRole.Heading)
    LazyColumn(Modifier.heightIn(max = 160.dp)) {
        items(exchanges, key = { it.id }) { exchange ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text(exchange.method ?: "—", Modifier.width(96.dp), role = TextRole.BodyStrong, maxLines = 1)
                Text(exchange.url ?: exchange.requestId, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusOf(exchange.toDomain()), Modifier.width(56.dp))
            }
        }
    }
    error?.let { Text(it, error = true, role = TextRole.Caption) }
    if (confirmingDelete) {
        Text("Delete this issue? Its requests then follow retention again.", error = true)
    }
    DialogActions {
        if (confirmingDelete) {
            TextButton("Cancel", onClick = { confirmingDelete = false })
            TextButton("Delete issue", onClick = {
                scope.launch { storage.deleteIssue(issue.id) }
                onBack()
            }, Modifier.testTag(CONFIRM_DELETE_ISSUE))
        } else {
            TextButton("Delete", onClick = { confirmingDelete = true })
            TextButton("Back", onClick = onBack)
            TextButton(
                "Save",
                onClick = {
                    scope.launch {
                        val write = storage.updateIssue(issue.id, change)
                        if (write is IssueWrite.Saved) onBack() else error = failureOf(write)
                    }
                },
                Modifier.testTag(SAVE_ISSUE),
                enabled = change != IssueChange() && title.isNotBlank(),
            )
        }
    }
}

@Composable
private fun IssueFields(
    title: String,
    onTitle: (String) -> Unit,
    note: String,
    onNote: (String) -> Unit,
    tags: String,
    onTags: (String) -> Unit,
) {
    TextField(title, onTitle, Modifier.fillMaxWidth().testTag(ISSUE_TITLE), label = "Title", singleLine = true)
    TextField(note, onNote, Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag(ISSUE_NOTE), label = "Note")
    TextField(
        tags,
        onTags,
        Modifier.fillMaxWidth().testTag(ISSUE_TAGS),
        label = "Tags, comma separated",
        singleLine = true,
    )
}

private fun tagsOf(text: String): List<String> =
    text
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

private fun failureOf(write: IssueWrite): String =
    when (write) {
        is IssueWrite.Saved -> ""
        is IssueWrite.UnknownExchanges -> "Not saved: ${requestsOf(write.ids.size.toLong())} no longer stored"
        IssueWrite.UnknownIssue -> "Not saved: the issue was deleted"
        IssueWrite.NotSaved -> "Not saved: the store could not write it"
    }

private fun summaryOf(issue: StoredIssue): String =
    listOf(
        labelOf(issue.status),
        issue.tags.joinToString(", "),
        requestsOf(issue.exchangeCount),
        if (issue.source == IssueSource.MCP) "by MCP" else "in the window",
        DATE_FORMAT.format(Instant.ofEpochMilli(issue.updatedAtMs)),
    ).filter { it.isNotEmpty() }.joinToString(" · ")

private fun requestsOf(count: Long): String = if (count == 1L) "1 request" else "$count requests"

private fun labelOf(status: IssueStatus): String =
    when (status) {
        IssueStatus.OPEN -> "Open"
        IssueStatus.RESOLVED -> "Resolved"
    }

internal const val SAVE_ISSUE = "save-issue"

internal const val ISSUE_TITLE = "issue-title"

internal const val ISSUE_NOTE = "issue-note"

internal const val ISSUE_TAGS = "issue-tags"

internal const val CONFIRM_DELETE_ISSUE = "confirm-delete-issue"
