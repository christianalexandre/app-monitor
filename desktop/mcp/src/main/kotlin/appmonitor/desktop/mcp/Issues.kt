package appmonitor.desktop.mcp

import appmonitor.desktop.domain.IssueChange
import appmonitor.desktop.domain.IssueDetail
import appmonitor.desktop.domain.IssueFilter
import appmonitor.desktop.domain.IssueRepository
import appmonitor.desktop.domain.IssueSource
import appmonitor.desktop.domain.IssueStatus
import appmonitor.desktop.domain.IssueWrite
import appmonitor.desktop.domain.StoreHealth
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val STATUSES = IssueStatus.entries.map { it.json }

private val IssueStatus.json get() = name.lowercase()

private fun JsonObject?.status(name: String): IssueStatus? =
    choice(name, STATUSES, null)?.let { value -> IssueStatus.entries.first { it.json == value } }

private fun JsonObjectBuilder.putStatus(description: String) =
    putJsonObject("status") {
        put("type", "string")
        put("enum", JsonArray(STATUSES.map(::JsonPrimitive)))
        put("description", description)
    }

private fun IssueDetail.toJson() =
    buildJsonObject {
        put("id", issue.id)
        put("title", issue.title)
        put("note", issue.note)
        put("tags", JsonArray(issue.tags.map(::JsonPrimitive)))
        put("status", issue.status.json)
        put("source", issue.source.name.lowercase())
        put("created_at", time(issue.createdAtMs))
        put("updated_at", time(issue.updatedAtMs))
        put("exchange_ids", JsonArray(exchangeIds.map(::JsonPrimitive)))
    }

/** `list_issues` (SDD 10): saved issues newest first, with their exchange ids. */
internal object ListIssues {
    const val NAME = "list_issues"
    private const val MAX_LIMIT = 100L
    private const val DEFAULT_LIMIT = 20L

    val description =
        "Lists the saved issues, newest first: title, note, tags, status (open or resolved), who created it (window or " +
            "mcp), times and the ids of the exchanges it links (get_request reads them). Filter by status and by one tag. " +
            "Page with next_before_id. $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putStatus("Only issues with this status.")
                    putJsonObject("tag") {
                        put("type", "string")
                        put("description", "Only issues with this tag, exact.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_LIMIT)
                        put("default", DEFAULT_LIMIT)
                        put("description", "Issues per page.")
                    }
                    putJsonObject("before_id") {
                        put("type", "integer")
                        put("description", "The next_before_id of the previous page.")
                    }
                },
        )

    suspend fun call(
        issues: IssueRepository,
        arguments: JsonObject?,
    ): JsonObject {
        val filter = IssueFilter(status = arguments.status("status"), tag = arguments.string("tag"))
        val limit = arguments.long("limit", 1..MAX_LIMIT, DEFAULT_LIMIT)!!
        val beforeId = arguments.long("before_id", 1..Long.MAX_VALUE, null) ?: Long.MAX_VALUE
        val page = issues.page(filter, beforeId, limit)
        return buildJsonObject {
            put("issues", JsonArray(page.map { it.toJson() }))
            put("next_before_id", (if (page.size.toLong() == limit) page.last().issue.id else null).json())
        }
    }
}

/** `save_issue` (SDD 10): creates an issue, or changes the fields given of one (ADR-0095 C.1, ADR-0096 B.1). */
internal object SaveIssue {
    const val NAME = "save_issue"
    private const val MAX_TAGS = 50
    private const val MAX_EXCHANGES = 1_000

    val description =
        "Saves an issue: exchanges that show a problem, with a title, a note, tags and a status. Without id it creates " +
            "one (title and exchange_ids required, status open by default); with id it changes only the fields given, " +
            "tags and exchange_ids replacing the stored ones, so the developer's edits of other fields in the window are " +
            "kept. Exchange ids come from search_requests. Returns the issue as list_issues does. No tool deletes an " +
            "issue. $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "The issue to change; omit to create one.")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "A short summary, not blank.")
                    }
                    putJsonObject("note") {
                        put("type", "string")
                        put("description", "Free text: what is wrong, what was tried.")
                    }
                    putJsonObject("tags") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("maxItems", MAX_TAGS)
                        put("description", "Free-text tags, trimmed, each once; replace the stored ones.")
                    }
                    putStatus("open or resolved.")
                    putJsonObject("exchange_ids") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                        put("minItems", 1)
                        put("maxItems", MAX_EXCHANGES)
                        put(
                            "description",
                            "The exchanges, live or from any run; a live one is saved to \"Saved\" first. Replace the stored ones.",
                        )
                    }
                },
        )

    suspend fun call(
        issues: IssueRepository,
        arguments: JsonObject?,
        health: () -> StoreHealth,
    ): JsonObject {
        val id = arguments.long("id", 1..Long.MAX_VALUE, null)
        val title = arguments.string("title")?.also { if (it.isBlank()) throw ToolError("title must not be blank") }
        val note = arguments.string("note")
        val tags = arguments.strings("tags", 0..MAX_TAGS)
        val status = arguments.status("status")
        val exchangeIds = arguments.longs("exchange_ids", 1..MAX_EXCHANGES, 1..Long.MAX_VALUE)
        val write =
            if (id == null) {
                if (title == null) throw ToolError("title is required to create an issue")
                if (exchangeIds == null) throw ToolError("exchange_ids is required to create an issue")
                issues.create(title, note.orEmpty(), tags.orEmpty(), exchangeIds, IssueSource.MCP, status ?: IssueStatus.OPEN)
            } else {
                val change = IssueChange(title, note, tags, status, exchangeIds)
                if (change == IssueChange()) throw ToolError("nothing to change: give title, note, tags, status or exchange_ids")
                issues.update(id, change)
            }
        val saved =
            when (write) {
                is IssueWrite.Saved -> {
                    write.id
                }

                is IssueWrite.UnknownExchanges -> {
                    throw ToolError("no exchange with id ${write.ids.joinToString()}")
                }

                IssueWrite.UnknownIssue -> {
                    throw ToolError("no issue with id $id")
                }

                IssueWrite.NotSaved -> {
                    throw ToolError("the issue was not saved" + (health().lastError?.let { ": $it" } ?: ""))
                }
            }
        val issue = issues.page(IssueFilter(), beforeId = saved + 1, limit = 1).firstOrNull { it.issue.id == saved }
        // Deleted in the window between the write and this read.
        if (issue == null) throw ToolError("no issue with id $saved")
        return buildJsonObject {
            put("created", id == null)
            put("issue", issue.toJson())
        }
    }
}
