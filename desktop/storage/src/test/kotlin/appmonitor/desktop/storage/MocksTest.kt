package appmonitor.desktop.storage

import appmonitor.protocol.Protocol
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class MocksTest {
    @Test
    fun aDeviceIsSentItsAppsActiveRulesInOrder() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                val first = saved(storage.createMock(APP, "items down", unavailable, MockSource.WINDOW))
                val agent = saved(storage.createMock(APP, "orders reset", reset, MockSource.MCP))
                saved(storage.createMock("other.app", "elsewhere", unavailable, MockSource.WINDOW))

                assertEquals(listOf(unavailable.copy(id = first)), storage.observeActiveMocks(APP).first(), "an MCP rule waits")
                assertTrue(storage.mocks().single { it.id == agent }.pending)

                saved(storage.confirmMock(agent))
                assertEquals(listOf(first, agent), storage.observeActiveMocks(APP).first().map { it.id })

                saved(storage.updateMock(first, MockChange(enabled = false)))
                assertEquals(listOf(reset.copy(id = agent)), storage.observeActiveMocks(APP).first())

                storage.deleteMock(agent)
                storage.flush()
                assertEquals(emptyList(), storage.observeActiveMocks(APP).first())
                assertEquals(MockWrite.UnknownMock, storage.updateMock(agent, MockChange(name = "gone")))
                storage.close(5.seconds)
            }
        }

    @Test
    fun theActiveRulesAreObservedAgainOnlyWhenTheyChange() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                val id = saved(storage.createMock(APP, "items down", unavailable, MockSource.WINDOW))
                val seen = mutableListOf<List<MockRule>>()
                val observer = launch { storage.observeActiveMocks(APP).collect { seen += it } }
                withTimeout(10.seconds) {
                    awaitLast(seen, listOf(unavailable.copy(id = id)))
                    // Neither changes what the device is sent: an emission for one would come before the next change's.
                    saved(storage.createMock("other.app", "elsewhere", reset, MockSource.WINDOW))
                    saved(storage.updateMock(id, MockChange(name = "renamed")))
                    saved(storage.updateMock(id, MockChange(rule = reset)))
                    awaitLast(seen, listOf(reset.copy(id = id)))
                    saved(storage.createMock(APP, "agent", unavailable, MockSource.MCP))
                    saved(storage.updateMock(id, MockChange(enabled = false)))
                    awaitLast(seen, emptyList())
                }
                observer.cancel()
                assertEquals(listOf(listOf(unavailable.copy(id = id)), listOf(reset.copy(id = id)), emptyList()), seen)
                storage.close(5.seconds)
            }
        }

    @Test
    fun aVersionThreeFileIsMigrated() =
        withDataDir { dir ->
            Storage.open(dir) { 1_000 }.let { runBlocking { it.close(5.seconds) } }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { connection ->
                connection.createStatement().use {
                    it.execute("DROP TABLE mock_rules")
                    it.execute("ALTER TABLE exchanges DROP COLUMN mock_rule_id")
                    it.execute("DROP TABLE breakpoint_rules")
                    it.execute("ALTER TABLE exchanges DROP COLUMN replay_of")
                    it.execute("ALTER TABLE runs DROP COLUMN saved")
                    it.execute("PRAGMA user_version = 3")
                }
            }
            val storage = Storage.open(dir) { 2_000 }
            runBlocking {
                saved(storage.createMock(APP, "items down", unavailable, MockSource.WINDOW))
                assertEquals(1, storage.observeActiveMocks(APP).first().size)
                storage.close(5.seconds)
            }
            assertEquals(8, dir.queryLong("PRAGMA user_version"))
        }

    @Test
    fun rulesOutliveARestartAndRetentionButNotClearAll() =
        withDataDir { dir ->
            var now = 0L
            Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}.run {
                runBlocking {
                    saved(createMock(APP, "items down", unavailable, MockSource.WINDOW))
                    close(5.seconds)
                }
            }
            now = 30.days.inWholeMilliseconds
            val reopened = Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                reopened.applyRetention()
                val kept = reopened.mocks().single()
                assertEquals("items down", kept.name)
                assertEquals(unavailable.copy(id = kept.id), kept.rule)

                reopened.clearAll()
                assertEquals(emptyList(), reopened.mocks())
                val next = saved(reopened.createMock(APP, "again", unavailable, MockSource.WINDOW))
                assertTrue(next > kept.id, "an id is never reused")
                reopened.close(5.seconds)
            }
        }

    @Test
    fun aRuleNoSdkCanServeIsRefused() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                listOf(
                    unavailable.copy(host = " "),
                    unavailable.copy(path = "v1/items"),
                    unavailable.copy(response = null),
                    unavailable.copy(delay_ms = -1),
                    unavailable.copy(response = MockResponse(200, body = ByteString.of(*ByteArray(Protocol.MAX_BODY_BYTES + 1)))),
                ).forEach { rule ->
                    assertFailsWith<IllegalArgumentException>(rule.toString()) {
                        storage.createMock(APP, "bad", rule, MockSource.WINDOW)
                    }
                }
                assertFailsWith<IllegalArgumentException> { storage.createMock(APP, " ", unavailable, MockSource.WINDOW) }
                assertEquals(emptyList(), storage.mocks())
                storage.close(5.seconds)
            }
        }

    private fun saved(write: MockWrite): Long = assertIs<MockWrite.Saved>(write).id

    private suspend fun awaitLast(
        seen: List<List<MockRule>>,
        expected: List<MockRule>,
    ) {
        while (seen.lastOrNull() != expected) delay(10)
    }

    private companion object {
        const val APP = "dev.appmonitor.sample"

        val unavailable =
            MockRule(
                host = "api.example.com",
                path = "/v1/items/*",
                response = MockResponse(status_code = 503, body = """{"error":"maintenance"}""".encodeUtf8()),
            )

        val reset =
            MockRule(method = "POST", host = "api.example.com", path = "/v1/orders", delay_ms = 2_000, failure = MockFailure("reset"))
    }
}
