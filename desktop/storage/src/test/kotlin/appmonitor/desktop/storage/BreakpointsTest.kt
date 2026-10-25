package appmonitor.desktop.storage

import appmonitor.v1.BreakpointRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class BreakpointsTest {
    @Test
    fun aDeviceIsSentItsAppsEnabledBreakpointsInOrder() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                val first = saved(storage.createBreakpoint(APP, "items", items))
                val second = saved(storage.createBreakpoint(APP, "orders", orders))
                saved(storage.createBreakpoint("other.app", "elsewhere", items))

                assertEquals(listOf(items.copy(id = first), orders.copy(id = second)), storage.observeActiveBreakpoints(APP).first())

                saved(storage.updateBreakpoint(first, BreakpointChange(enabled = false)))
                assertEquals(listOf(orders.copy(id = second)), storage.observeActiveBreakpoints(APP).first())

                storage.deleteBreakpoint(second)
                storage.flush()
                assertEquals(emptyList(), storage.observeActiveBreakpoints(APP).first())
                assertEquals(BreakpointWrite.UnknownBreakpoint, storage.updateBreakpoint(second, BreakpointChange(name = "gone")))
                assertEquals(listOf("items", "elsewhere"), storage.breakpoints().map { it.name }.sortedDescending())
                storage.close(5.seconds)
            }
        }

    @Test
    fun theEnabledBreakpointsAreObservedAgainOnlyWhenTheyChange() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                val id = saved(storage.createBreakpoint(APP, "items", items))
                val seen = mutableListOf<List<BreakpointRule>>()
                val observer = launch { storage.observeActiveBreakpoints(APP).collect { seen += it } }
                withTimeout(10.seconds) {
                    awaitLast(seen, listOf(items.copy(id = id)))
                    // Neither changes what the device is sent: an emission for one would come before the next change's.
                    saved(storage.createBreakpoint("other.app", "elsewhere", orders))
                    saved(storage.updateBreakpoint(id, BreakpointChange(name = "renamed")))
                    saved(storage.updateBreakpoint(id, BreakpointChange(rule = orders)))
                    awaitLast(seen, listOf(orders.copy(id = id)))
                    saved(storage.updateBreakpoint(id, BreakpointChange(enabled = false)))
                    awaitLast(seen, emptyList())
                }
                observer.cancel()
                assertEquals(listOf(listOf(items.copy(id = id)), listOf(orders.copy(id = id)), emptyList()), seen)
                storage.close(5.seconds)
            }
        }

    @Test
    fun aVersionFiveFileIsMigrated() =
        withDataDir { dir ->
            Storage.open(dir) { 1_000 }.let { runBlocking { it.close(5.seconds) } }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { connection ->
                connection.createStatement().use {
                    it.execute("DROP TABLE breakpoint_rules")
                    it.execute("ALTER TABLE exchanges DROP COLUMN replay_of")
                    it.execute("ALTER TABLE runs DROP COLUMN saved")
                    it.execute("PRAGMA user_version = 5")
                }
            }
            val storage = Storage.open(dir) { 2_000 }
            runBlocking {
                saved(storage.createBreakpoint(APP, "items", items))
                assertEquals(1, storage.observeActiveBreakpoints(APP).first().size)
                storage.close(5.seconds)
            }
            assertEquals(8, dir.queryLong("PRAGMA user_version"))
        }

    @Test
    fun breakpointsOutliveARestartAndRetentionButNotClearAll() =
        withDataDir { dir ->
            var now = 0L
            Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}.run {
                runBlocking {
                    saved(createBreakpoint(APP, "items", items))
                    close(5.seconds)
                }
            }
            now = 30.days.inWholeMilliseconds
            val reopened = Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                reopened.applyRetention()
                val kept = reopened.breakpoints().single()
                assertEquals("items", kept.name)
                assertEquals(items.copy(id = kept.id), kept.rule)

                reopened.clearAll()
                assertEquals(emptyList(), reopened.breakpoints())
                val next = saved(reopened.createBreakpoint(APP, "again", items))
                assertTrue(next > kept.id, "an id is never reused")
                reopened.close(5.seconds)
            }
        }

    @Test
    fun aBreakpointNoSdkCanHoldWithIsRefused() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                listOf(
                    items.copy(host = " "),
                    items.copy(path = "v1/items"),
                    items.copy(request = false, response = false),
                    items.copy(timeout_ms = 0),
                    items.copy(timeout_ms = MAX_BREAKPOINT_TIMEOUT_MS + 1),
                ).forEach { rule ->
                    assertFailsWith<IllegalArgumentException>(rule.toString()) { storage.createBreakpoint(APP, "bad", rule) }
                }
                assertFailsWith<IllegalArgumentException> { storage.createBreakpoint(APP, " ", items) }
                assertEquals(emptyList(), storage.breakpoints())
                storage.close(5.seconds)
            }
        }

    private fun saved(write: BreakpointWrite): Long = assertIs<BreakpointWrite.Saved>(write).id

    private suspend fun awaitLast(
        seen: List<List<BreakpointRule>>,
        expected: List<BreakpointRule>,
    ) {
        while (seen.lastOrNull() != expected) delay(10)
    }

    private companion object {
        const val APP = "dev.appmonitor.sample"

        val items = BreakpointRule(host = "api.example.com", path = "/v1/items/*", request = true, response = true, timeout_ms = 60_000)

        val orders = BreakpointRule(method = "POST", host = "api.example.com", path = "/v1/orders", request = true, timeout_ms = 5_000)
    }
}
