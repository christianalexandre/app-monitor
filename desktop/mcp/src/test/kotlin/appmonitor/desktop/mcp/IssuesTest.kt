package appmonitor.desktop.mcp

import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.IssueChange
import appmonitor.desktop.storage.IssueSource
import appmonitor.desktop.storage.IssueStatus
import appmonitor.desktop.storage.Storage
import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IssuesTest {
    @Test
    fun aClientSavesEditsAndListsAnIssueThatTheAppsStoreShows() =
        withServer { running ->
            val (r1, r2) = exchanges(running.storage, "r1", "r2")
            running.client { client ->
                val created =
                    client
                        .callTool(
                            SaveIssue.NAME,
                            mapOf(
                                "title" to " Checkout 500 ",
                                "note" to "after login",
                                "tags" to listOf("checkout", " flaky "),
                                "exchange_ids" to listOf(r1),
                            ),
                        ).json
                assertEquals(true, created["created"]!!.jsonPrimitive.boolean)
                val issue = created["issue"]!!.jsonObject
                val id = issue.long("id")
                assertEquals("Checkout 500", issue.string("title"))
                assertEquals("open", issue.string("status"))
                assertEquals("mcp", issue.string("source"))
                assertEquals(listOf("checkout", "flaky"), issue.list("tags"))
                assertEquals(listOf(r1.toString()), issue.list("exchange_ids"))
                assertEquals("1970-01-01T00:00:01.000Z", issue.string("created_at"))

                val edited =
                    client.callTool(SaveIssue.NAME, mapOf("id" to id, "status" to "resolved", "exchange_ids" to listOf(r2, r1))).json
                assertEquals(false, edited["created"]!!.jsonPrimitive.boolean)
                val after = edited["issue"]!!.jsonObject
                assertEquals("resolved", after.string("status"))
                assertEquals("after login", after.string("note"), "an omitted field is kept")
                assertEquals(listOf(r1, r2).map(Long::toString), after.list("exchange_ids"))

                val listed = client.callTool(ListIssues.NAME, mapOf("status" to "resolved", "tag" to "flaky")).json
                assertEquals(listOf(after), listed["issues"]!!.jsonArray.map { it.jsonObject })
                assertEquals(
                    0,
                    client
                        .callTool(ListIssues.NAME, mapOf("status" to "open"))
                        .json["issues"]!!
                        .jsonArray.size,
                )
                assertEquals(
                    0,
                    client
                        .callTool(ListIssues.NAME, mapOf("tag" to "Flaky"))
                        .json["issues"]!!
                        .jsonArray.size,
                )
            }
            val shown =
                running.storage
                    .observeIssues()
                    .first()
                    .single()
            assertEquals(IssueStatus.RESOLVED, shown.status)
            assertEquals(IssueSource.MCP, shown.source)
            assertEquals(2, shown.exchangeCount)
        }

    @Test
    fun anEditInTheWindowMeanwhileIsKept() =
        withServer { running ->
            val (r1) = exchanges(running.storage, "r1")
            running.client { client ->
                val id = client.save(mapOf("title" to "A", "exchange_ids" to listOf(r1)))["id"]!!.jsonPrimitive.long
                running.storage.updateIssue(id, IssueChange(note = "from the window", status = IssueStatus.RESOLVED))
                val issue = client.save(mapOf("id" to id, "title" to "B", "status" to "open"))
                assertEquals("from the window", issue.string("note"), "a field only the window changed is kept")
                assertEquals("B", issue.string("title"))
                assertEquals("open", issue.string("status"), "the same field goes to the later write")
            }
        }

    @Test
    fun unknownExchangesAndIssuesAreRefusedAndWriteNothing() =
        withServer { running ->
            val (r1) = exchanges(running.storage, "r1")
            running.client { client ->
                val unknown = client.callTool(SaveIssue.NAME, mapOf("title" to "A", "exchange_ids" to listOf(r1, 999, 998)))
                assertEquals(true, unknown.isError)
                assertEquals("no exchange with id 999, 998", unknown.text)
                assertEquals(
                    0,
                    client
                        .callTool(ListIssues.NAME, emptyMap())
                        .json["issues"]!!
                        .jsonArray.size,
                )

                val id = client.save(mapOf("title" to "A", "exchange_ids" to listOf(r1)))["id"]!!.jsonPrimitive.long
                val edit = client.callTool(SaveIssue.NAME, mapOf("id" to id, "exchange_ids" to listOf(999)))
                assertEquals("no exchange with id 999", edit.text)
                assertEquals(listOf(r1.toString()), client.listed().single().list("exchange_ids"))

                val missing = client.callTool(SaveIssue.NAME, mapOf("id" to id + 1, "note" to "x"))
                assertEquals(true, missing.isError)
                assertEquals("no issue with id ${id + 1}", missing.text)
            }
        }

    @Test
    fun badArgumentsAreErrors() =
        withServer { running ->
            val (r1) = exchanges(running.storage, "r1")
            running.client { client ->
                val cases =
                    mapOf(
                        mapOf("exchange_ids" to listOf(r1)) to "title is required to create an issue",
                        mapOf("title" to "A") to "exchange_ids is required to create an issue",
                        mapOf("title" to "  ", "exchange_ids" to listOf(r1)) to "title must not be blank",
                        mapOf("title" to "A", "exchange_ids" to emptyList<Long>()) to
                            "exchange_ids must be an array of 1 to 1000 integers from 1 to ${Long.MAX_VALUE}",
                        mapOf("title" to "A", "exchange_ids" to listOf("1")) to
                            "exchange_ids must be an array of integers from 1 to ${Long.MAX_VALUE}",
                        mapOf("title" to "A", "exchange_ids" to listOf(r1), "tags" to listOf(1)) to "tags must be an array of strings",
                        mapOf("title" to "A", "exchange_ids" to listOf(r1), "status" to "wont_fix") to
                            "status must be one of open, resolved",
                        mapOf("id" to 1) to "nothing to change: give title, note, tags, status or exchange_ids",
                    )
                for ((arguments, message) in cases) {
                    val result = client.callTool(SaveIssue.NAME, arguments)
                    assertEquals(true, result.isError, "$arguments")
                    assertEquals(message, result.text, "$arguments")
                }
                assertEquals("limit must be an integer from 1 to 100", client.callTool(ListIssues.NAME, mapOf("limit" to 0)).text)
                assertEquals(0, client.listed().size)
            }
        }

    @Test
    fun listPagesNewestFirst() =
        withServer { running ->
            val (r1) = exchanges(running.storage, "r1")
            running.client { client ->
                val ids = List(5) { client.save(mapOf("title" to "issue $it", "exchange_ids" to listOf(r1)))["id"]!!.jsonPrimitive.long }
                val first = client.callTool(ListIssues.NAME, mapOf("limit" to 3)).json
                assertEquals(ids.reversed().take(3), first.ids())
                val next = first["next_before_id"]!!.jsonPrimitive.long
                val second = client.callTool(ListIssues.NAME, mapOf("limit" to 3, "before_id" to next)).json
                assertEquals(ids.take(2).reversed(), second.ids())
                assertEquals(JsonNull, second["next_before_id"])
            }
        }

    @Test
    fun aSaveWhileTheStoreClosesIsAnError() =
        withServer { running ->
            val (r1) = exchanges(running.storage, "r1")
            running.storage.close(5.seconds)
            val result = running.client { it.callTool(SaveIssue.NAME, mapOf("title" to "A", "exchange_ids" to listOf(r1))) }
            assertEquals(true, result.isError)
            assertEquals(CLOSING, result.text)
            val list = running.client { it.callTool(ListIssues.NAME, emptyMap()) }
            assertEquals(CLOSING, list.text)
        }

    @Test
    fun aLostWriteIsAnErrorThatSaysWhy() =
        withServerOnDir { dir, running ->
            val (r1) = exchanges(running.storage, "r1")
            // A second connection holding the write lock makes the writer's batch fail after its busy timeout.
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { lock ->
                lock.createStatement().execute("BEGIN EXCLUSIVE")
                val result = running.client { it.callTool(SaveIssue.NAME, mapOf("title" to "A", "exchange_ids" to listOf(r1))) }
                assertEquals(true, result.isError)
                assertTrue(result.text.startsWith("the issue was not saved: "), result.text)
                assertTrue("locked" in result.text || "busy" in result.text.lowercase(), result.text)
                lock.createStatement().execute("ROLLBACK")
            }
            val listed = running.client { it.callTool(ListIssues.NAME, emptyMap()) }.json
            assertEquals(0, listed["issues"]!!.jsonArray.size)
            assertNotEquals(null, listed["warnings"], "the lost write stays a warning on later calls")
        }

    @Test
    fun onlySaveIssueAndCreateMockAreMarkedAsWriting() =
        withServer { running ->
            val tools = running.client { it.listTools().tools }
            val writing = tools.filter { it.annotations?.readOnlyHint != true }.map { it.name }
            assertEquals(listOf(SaveIssue.NAME, CreateMock.NAME), writing)
            assertEquals(false, tools.single { it.name == SaveIssue.NAME }.annotations?.destructiveHint)
            assertTrue(tools.any { it.name == ListIssues.NAME })
        }

    private suspend fun exchanges(
        storage: Storage,
        vararg requestIds: String,
    ): List<Long> {
        storage.connected("s1", hello(), null)
        requestIds.forEach { storage.received("s1", started(it)) }
        storage.flush()
        return storage.searchExchanges(ExchangeFilter(), 0, 50).map { it.id }
    }

    private suspend fun Client.save(arguments: Map<String, Any?>): JsonObject {
        val result = callTool(SaveIssue.NAME, arguments)
        assertNotEquals(true, result.isError, result.text)
        return result.json["issue"]!!.jsonObject
    }

    private suspend fun Client.listed() = callTool(ListIssues.NAME, emptyMap()).json["issues"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.long(name: String) = this[name]!!.jsonPrimitive.long

    private fun JsonObject.string(name: String) = this[name]!!.jsonPrimitive.content

    private fun JsonObject.list(name: String) = this[name]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.ids() = this["issues"]!!.jsonArray.map { it.jsonObject.long("id") }

    /** As [withServer], with the data directory, for a test that opens the file beside the store. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun withServerOnDir(block: suspend (Path, Running) -> Unit) {
        val dir = createTempDirectory("mcp-test")
        try {
            val data = dir.resolve("data")
            val storage = Storage.open(data) { 1_000 }
            runBlocking {
                val access = McpAccess.open(storage)
                val server = McpServer(storage, access, 0, "test")
                val port = server.start()
                try {
                    block(data, Running(storage, access, server, port))
                } finally {
                    server.stop()
                    storage.close(5.seconds)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
