package appmonitor.desktop.storage

import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class MockedExchangeTest {
    @Test
    fun aMockedExchangeKeepsItsRuleInEveryListReader() =
        withDataDir { dir ->
            val storage = Storage.open(dir) { 1_000 }
            runBlocking {
                val rule = assertIs<MockWrite.Saved>(storage.createMock(APP, "items down", RULE, MockSource.WINDOW)).id
                storage.connected("s", hello(), null)
                storage.received("s", envelope(started("mocked").copy(mock_rule_id = rule)))
                storage.received("s", envelope(ResponseReceived(request_id = "mocked", status_code = 503)))
                storage.received("s", envelope(started("network")))
                storage.flush()
                val id = storage.searchExchanges(ExchangeFilter()).first { it.requestId == "mocked" }.id
                assertIs<IssueWrite.Saved>(storage.createIssue("A", "", emptyList(), listOf(id), IssueSource.WINDOW))
                val session = storage.sessions(storage.runId).single().id
                val rows =
                    listOf(
                        storage.searchExchanges(ExchangeFilter()),
                        storage.exchangesOfSession(session),
                        storage.exchangesOfRun(storage.runId),
                        storage.searchExchangeSizes(ExchangeFilter()).map { it.exchange },
                    ).map { list -> list.associateBy { it.requestId } }
                rows.forEach {
                    assertEquals(rule, it.getValue("mocked").mockRuleId)
                    assertNull(it.getValue("network").mockRuleId)
                }
                val issue =
                    storage
                        .issuePage(IssueFilter())
                        .single()
                        .issue.id
                assertEquals(rule, storage.issueExchanges(issue).single().mockRuleId)
                val detail = storage.exchange(id)!!
                assertEquals(rule, detail.exchange.mockRuleId)
                assertEquals("items down", detail.mockRuleName)
                storage.close(5.seconds)
            }
        }

    @Test
    fun aDeletedRuleKeepsItsIdWithoutAName() =
        withDataDir { dir ->
            val storage = Storage.open(dir) { 1_000 }
            runBlocking {
                val rule = assertIs<MockWrite.Saved>(storage.createMock(APP, "items down", RULE, MockSource.WINDOW)).id
                storage.deleteMock(rule)
                storage.connected("s", hello(), null)
                storage.received("s", envelope(started("mocked").copy(mock_rule_id = rule)))
                storage.flush()
                val detail = storage.exchange(storage.searchExchanges(ExchangeFilter()).single().id)!!
                assertEquals(rule, detail.exchange.mockRuleId)
                assertNull(detail.mockRuleName)
                storage.close(5.seconds)
            }
        }

    @Test
    fun aVersionFourFileIsMigrated() =
        withDataDir { dir ->
            Storage.open(dir) { 1_000 }.let { runBlocking { it.close(5.seconds) } }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { connection ->
                connection.createStatement().use {
                    it.execute("ALTER TABLE exchanges DROP COLUMN mock_rule_id")
                    it.execute("DROP TABLE breakpoint_rules")
                    it.execute("ALTER TABLE exchanges DROP COLUMN replay_of")
                    it.execute("PRAGMA user_version = 4")
                }
            }
            val storage = Storage.open(dir) { 2_000 }
            runBlocking {
                storage.connected("s", hello(), null)
                storage.received("s", envelope(started("mocked").copy(mock_rule_id = 7)))
                storage.flush()
                assertEquals(7L, storage.searchExchanges(ExchangeFilter()).single().mockRuleId)
                storage.close(5.seconds)
            }
            assertEquals(7, dir.queryLong("PRAGMA user_version"))
        }

    private companion object {
        const val APP = "dev.appmonitor.sample"

        val RULE = MockRule(host = "api.example.com", path = "/items/*", response = MockResponse(status_code = 503))
    }
}
