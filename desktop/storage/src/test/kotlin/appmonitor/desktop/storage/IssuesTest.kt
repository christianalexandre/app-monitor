package appmonitor.desktop.storage

import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.ByteString
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class IssuesTest {
    private val day = 1.days.inWholeMilliseconds

    @Test
    fun aLinkedExchangeIsKeptPastBothRetentionBounds() =
        withDataDir { dir ->
            var now = 0L
            val storage = Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                storage.connected("kept", hello("d1"), null)
                storage.received("kept", envelope(started("linked", body = text(4096))))
                storage.received("kept", envelope(ResponseReceived(request_id = "linked", status_code = 500)))
                storage.received("kept", envelope(started("other", body = text(4096))))
                storage.received("kept", envelope(started("log"), channel = "logs"))
                storage.disconnected("kept")
                storage.connected("gone", hello("d2"), null)
                storage.received("gone", envelope(started("gone-r")))
                storage.disconnected("gone")
                storage.flush()
                val linked = idOf(storage, "linked")
                val issue =
                    assertIs<IssueWrite.Saved>(storage.createIssue("500 on items", "", emptyList(), listOf(linked), IssueSource.WINDOW))

                now = 8 * day
                val status = storage.applyRetention()

                assertEquals(1, status.deletedSessions, "the session without an issue")
                assertEquals(listOf("kept"), storage.observeSessions().first().map { it.serverSessionId })
                assertEquals(listOf("linked"), storage.issueExchanges(issue.id).map { it.requestId })
                assertEquals(listOf("linked"), dir.queryStrings("SELECT request_id FROM exchanges"), "the rest of its session goes")
                assertEquals(0, dir.queryLong("SELECT count(*) FROM raw_envelopes"))
                assertEquals(text(4096), storage.exchange(linked)?.request?.body, "with its body")
                assertEquals(500, storage.exchange(linked)?.response?.status_code)
                assertEquals(listOf("d1"), dir.queryStrings("SELECT device_id FROM devices"))

                storage.setRetention(RetentionSettings(maxAgeDays = null, maxBytes = 4096))
                assertEquals(listOf("linked"), dir.queryStrings("SELECT request_id FROM exchanges"), "past the size bound too")
                assertTrue(storage.retentionStatus.value.keptOverBound, "what is kept alone passes the bound")

                storage.deleteIssue(issue.id)
                storage.setRetention(RetentionSettings(maxAgeDays = 7))
                assertEquals(emptyList(), storage.observeSessions().first(), "a deleted issue releases its session")
                assertEquals(0, dir.queryLong("SELECT count(*) FROM devices"))
                storage.close(5.seconds)
            }
        }

    @Test
    fun anIssueSurvivesAReopen() =
        withDataDir { dir ->
            val first = Storage.open(dir) { 1_000 }
            runBlocking {
                exchanges(first, "s1", "r1", "r2")
                val ids = listOf(idOf(first, "r2"), idOf(first, "r1"))
                assertIs<IssueWrite.Saved>(
                    first.createIssue("Checkout fails", "Twice in a row", listOf("checkout", " flaky ", "", "flaky"), ids, IssueSource.MCP),
                )
                first.close(5.seconds)
            }
            val second = Storage.open(dir) { 2_000 }
            runBlocking {
                val issue = second.observeIssues().first().single()
                assertEquals(
                    StoredIssue(
                        issue.id,
                        "Checkout fails",
                        "Twice in a row",
                        listOf("checkout", "flaky"),
                        IssueStatus.OPEN,
                        IssueSource.MCP,
                        1_000,
                        1_000,
                        2,
                    ),
                    issue,
                )
                assertEquals(listOf("r1", "r2"), second.issueExchanges(issue.id).map { it.requestId }, "in arrival order")
                second.close(5.seconds)
            }
        }

    @Test
    fun editsChangeOnlyTheFieldsTheyCarryInQueueOrder() =
        withDataDir { dir ->
            var now = 1_000L
            val storage = Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                exchanges(storage, "s1", "r1", "r2")
                val r1 = idOf(storage, "r1")
                val r2 = idOf(storage, "r2")
                val a = assertIs<IssueWrite.Saved>(storage.createIssue("A", "note", listOf("api"), listOf(r1), IssueSource.MCP)).id
                val b = assertIs<IssueWrite.Saved>(storage.createIssue("B", "", listOf("ui"), listOf(r2), IssueSource.WINDOW)).id

                now = 2_000
                val held = holdWriter(storage)
                val title = queued { storage.updateIssue(a, IssueChange(title = "A, renamed")) }
                val status = queued { storage.updateIssue(a, IssueChange(status = IssueStatus.RESOLVED, exchangeIds = listOf(r1, r2))) }
                held.release()
                assertEquals(IssueWrite.Saved(a), title.await())
                assertEquals(IssueWrite.Saved(a), status.await())

                val stored = storage.observeIssues().first().first { it.id == a }
                assertEquals(
                    StoredIssue(a, "A, renamed", "note", listOf("api"), IssueStatus.RESOLVED, IssueSource.MCP, 1_000, 2_000, 2),
                    stored,
                )
                assertEquals(listOf(b, a), storage.observeIssues().first().map { it.id }, "newest first")
                assertEquals(listOf(b), storage.observeIssues(IssueFilter(status = IssueStatus.OPEN)).first().map { it.id })
                assertEquals(listOf(a), storage.observeIssues(IssueFilter(tag = "api")).first().map { it.id })
                assertEquals(emptyList(), storage.observeIssues(IssueFilter(IssueStatus.OPEN, "api")).first())
                assertEquals(listOf("api", "ui"), storage.observeIssueTags().first())

                storage.updateIssue(b, IssueChange(tags = emptyList()))
                assertEquals(listOf("api"), storage.observeIssueTags().first())
                storage.close(5.seconds)
            }
        }

    @Test
    fun unknownExchangesAndIssuesWriteNothing() =
        withDataDir { dir ->
            val storage = Storage.open(dir, { 1_000 }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                exchanges(storage, "s1", "r1")
                val r1 = idOf(storage, "r1")
                assertEquals(
                    IssueWrite.UnknownExchanges(listOf(r1 + 1)),
                    storage.createIssue("A", "", emptyList(), listOf(r1, r1 + 1), IssueSource.MCP),
                )
                assertEquals(0, dir.queryLong("SELECT count(*) FROM issues"))
                val id = assertIs<IssueWrite.Saved>(storage.createIssue("A", "", emptyList(), listOf(r1), IssueSource.MCP)).id
                assertEquals(
                    IssueWrite.UnknownExchanges(listOf(r1 + 1)),
                    storage.updateIssue(id, IssueChange(title = "B", exchangeIds = listOf(r1 + 1))),
                )
                assertEquals(
                    "A",
                    storage
                        .observeIssues()
                        .first()
                        .single()
                        .title,
                    "nothing of the edit written",
                )
                assertEquals(IssueWrite.UnknownIssue, storage.updateIssue(id + 1, IssueChange(title = "B")))

                storage.deleteIssue(id)
                assertEquals(IssueWrite.UnknownIssue, storage.updateIssue(id, IssueChange(title = "B")), "deleted meanwhile")
                assertEquals(0, dir.queryLong("SELECT count(*) FROM issue_exchanges"))
                storage.close(5.seconds)
            }
        }

    @Test
    fun aLostBatchAndAClosedStoreSaveNothing() =
        withDataDir { dir ->
            var failing = false
            val storage =
                Storage.open(dir, { 1_000 }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {
                    if (failing) throw SQLException("disk I/O error")
                }
            runBlocking {
                exchanges(storage, "s1", "r1")
                val r1 = idOf(storage, "r1")
                failing = true
                assertEquals(IssueWrite.NotSaved, storage.createIssue("A", "", emptyList(), listOf(r1), IssueSource.WINDOW))
                failing = false
                assertEquals(0, dir.queryLong("SELECT count(*) FROM issues"))
                assertEquals(1, storage.health.value.notSaved)

                storage.close(5.seconds)
                assertEquals(IssueWrite.NotSaved, storage.createIssue("A", "", emptyList(), listOf(r1), IssueSource.WINDOW))
                assertEquals(emptyList(), storage.observeIssues().toList(), "an observed list ends once closing")
            }
        }

    @Test
    fun clearAllDeletesIssues() =
        withDataDir { dir ->
            val storage = Storage.open(dir, { 1_000 }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                exchanges(storage, "s1", "r1")
                storage.createIssue("A", "", listOf("api"), listOf(idOf(storage, "r1")), IssueSource.WINDOW)
                storage.clearAll()
                for (table in listOf("issues", "issue_tags", "issue_exchanges")) {
                    assertEquals(0, dir.queryLong("SELECT count(*) FROM $table"), table)
                }
                storage.close(5.seconds)
            }
        }

    @Test
    fun aVersionOneFileIsMigrated() =
        withDataDir { dir ->
            Storage.open(dir) { 1_000 }.let { runBlocking { it.close(5.seconds) } }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { connection ->
                connection.createStatement().use {
                    it.execute("DROP TABLE issue_exchanges")
                    it.execute("DROP TABLE issue_tags")
                    it.execute("DROP TABLE issues")
                    it.execute("PRAGMA user_version = 1")
                }
            }
            val storage = Storage.open(dir) { 2_000 }
            runBlocking {
                exchanges(storage, "s1", "r1")
                assertIs<IssueWrite.Saved>(storage.createIssue("A", "", emptyList(), listOf(idOf(storage, "r1")), IssueSource.WINDOW))
                storage.close(5.seconds)
            }
            assertEquals(2, dir.queryLong("PRAGMA user_version"))
        }

    private suspend fun exchanges(
        storage: Storage,
        session: String,
        vararg requestIds: String,
    ) {
        storage.connected(session, hello(), null)
        requestIds.forEach { storage.received(session, envelope(started(it, body = ByteString.EMPTY))) }
        storage.flush()
    }

    private suspend fun idOf(
        storage: Storage,
        requestId: String,
    ): Long = storage.searchExchanges(ExchangeFilter()).single { it.requestId == requestId }.id

    private fun <T> queued(block: suspend () -> T) = CoroutineScope(Dispatchers.Default).async { block() }
}
