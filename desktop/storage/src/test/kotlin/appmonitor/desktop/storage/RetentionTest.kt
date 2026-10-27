package appmonitor.desktop.storage

import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.listDirectoryEntries
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class RetentionTest {
    private val day = 1.days.inWholeMilliseconds

    @Test
    fun ageBoundDeletesOldSessionsAndKeepsNamedRunsAndConnectedOnes() =
        withDataDir { dir ->
            var now = 0L
            val marker = marker(1)
            val storage = Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                session(storage, "old", "d1", body = marker)
                storage.rename(storage.newRun(), "Baseline")
                session(storage, "named", "d2")
                storage.newRun()
                session(storage, "connected", "d3", disconnect = false)
                now = 6 * day
                session(storage, "recent", "d4")
                storage.flush()

                now = 8 * day
                val status = storage.applyRetention()

                assertEquals(1, status.deletedSessions)
                assertEquals(listOf("named", "connected", "recent"), serverSessions(storage))
                assertEquals(0, dir.queryLong("SELECT count(*) FROM exchanges WHERE request_id = 'old-r'"), "with its exchanges")
                assertEquals(0, dir.queryLong("SELECT count(*) FROM bodies"), "and its body, the only one")
                assertEquals(listOf("d2", "d3", "d4"), dir.queryStrings("SELECT device_id FROM devices ORDER BY device_id"))
                assertEquals(2, storage.runs().size, "the launch's run, left empty, is gone")

                storage.setRetention(RetentionSettings(maxAgeDays = null))
                now = 30 * day
                assertEquals(0, storage.applyRetention().deletedSessions, "never keeps every age")
                storage.close(5.seconds)
            }
            assertFalse(dir.contains(marker), "secure_delete zeroed the deleted body")
        }

    @Test
    fun sizeBoundDeletesOldestFirstUntilUnderNinetyPercent() =
        withDataDir { dir ->
            var now = 0L
            val storage = Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                storage.rename(storage.runId, "Baseline")
                session(storage, "named", "d0", body = marker(0))
                storage.newRun()
                session(storage, "connected", "d0", body = marker(0), disconnect = false)
                for (i in 1..6) {
                    now = i * 1_000L
                    session(storage, "s$i", "d$i", body = marker(i))
                }
                storage.flush()
                val full = storage.applyRetention().usedBytes
                assertEquals(8, serverSessions(storage).size, "under the default bound nothing goes")

                val bound = full / 10 * 7
                storage.setRetention(RetentionSettings(maxAgeDays = null, maxBytes = bound))
                val status = storage.retentionStatus.value

                val left = serverSessions(storage)
                assertTrue(status.deletedSessions in 1..5, "deleted ${status.deletedSessions}")
                assertEquals(
                    listOf("named", "connected") + (1..6).map { "s$it" }.drop(status.deletedSessions.toInt()),
                    left,
                    "oldest last envelope first, named and connected kept",
                )
                assertTrue(status.usedBytes <= bound, "file ${status.usedBytes} under $bound")
                assertTrue(liveBytes(dir) <= bound / 10 * 9, "live pages under 90 % of the bound")
                assertFalse(status.keptOverBound)

                storage.setRetention(RetentionSettings(maxAgeDays = null, maxBytes = 4096))
                assertEquals(listOf("named", "connected"), serverSessions(storage))
                assertTrue(storage.retentionStatus.value.keptOverBound, "what is kept alone passes the bound")
                storage.close(5.seconds)
            }
        }

    @Test
    fun settingsKeptAcrossRestartsAndAppliedAtOpen() =
        withDataDir { dir ->
            var now = 0L
            val first = Storage.open(dir) { now }
            runBlocking {
                session(first, "old", "d1")
                first.setRetention(RetentionSettings(maxAgeDays = 30, maxBytes = 2L shl 30))
                first.close(5.seconds)
            }
            now = 8 * day
            val second = Storage.open(dir) { now }
            runBlocking {
                awaitPass(second)
                assertEquals(RetentionSettings(30, 2L shl 30), second.retention.value)
                assertEquals(listOf("old"), serverSessions(second), "30 days keep an 8-day-old session")
                second.close(5.seconds)
            }
            now = 31 * day
            val third = Storage.open(dir) { now }
            runBlocking {
                assertEquals(1, awaitPass(third).deletedSessions, "applied at open")
                assertEquals(emptyList(), serverSessions(third))
                third.close(5.seconds)
            }
        }

    @Test
    fun clearAllDeletesEverythingAndRewritesTheFile() =
        withDataDir { dir ->
            val marker = marker(9)
            val storage = Storage.open(dir, { 1_000 }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) {}
            runBlocking {
                storage.rename(storage.runId, "Baseline")
                session(storage, "ended", "d1", body = marker)
                session(storage, "connected", "d2", disconnect = false)
                storage.decided(hello("d1"), approved = true)
                storage.setRetention(RetentionSettings(maxAgeDays = 3))

                storage.clearAll()
                storage.received("connected", envelope(started("after")))
                storage.flush()

                assertEquals(listOf(StoredRun(storage.currentRun.value, null, 1_000, null)), storage.runs(), "one new run")
                assertEquals(emptyList(), serverSessions(storage), "a connected session is forgotten")
                for (table in listOf("devices", "apps", "exchanges", "bodies", "approvals", "settings")) {
                    assertEquals(0, dir.queryLong("SELECT count(*) FROM $table"), table)
                }
                assertEquals(RetentionSettings(), storage.retention.value)
                assertFalse(dir.contains(marker), "nothing left in the files before close")
                storage.close(5.seconds)
            }
            assertFalse(dir.contains(marker))
        }

    @Test
    fun aRaisedBoundStopsAPassAlreadyRunning() =
        withDataDir { dir ->
            var now = 0L
            var raise: (suspend () -> Unit)? = null
            val storage =
                Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null, afterRetentionChunk = {
                    raise?.invoke()
                    raise = null
                }) {}
            runBlocking {
                repeat(Storage.AGE_CHUNK.toInt()) { session(storage, "old$it", "d1") }
                now = 20 * day
                session(storage, "month", "d2")
                now = 40 * day
                session(storage, "recent", "d3")
                storage.flush()
                val raised = RetentionSettings(maxAgeDays = 30)
                raise = {
                    val job = launch { storage.setRetention(raised) }
                    storage.retention.first { it == raised }
                    job.join()
                }

                storage.applyRetention()

                assertEquals(listOf("month", "recent"), serverSessions(storage), "the first chunk went, no more")
                assertEquals(0, storage.retentionStatus.value.deletedSessions, "the raised pass's status")
                storage.close(5.seconds)
            }
        }

    @Test
    fun aClearAllFailingDuringAPassKeepsItsError() =
        withDataDir { dir ->
            var now = 0L
            var clear: (suspend () -> Unit)? = null
            val storage =
                Storage.open(dir, { now }, Storage.MAX_QUEUE_BYTES, retentionInterval = null, afterRetentionChunk = {
                    clear?.invoke()
                    clear = null
                }) {}
            runBlocking {
                repeat(Storage.AGE_CHUNK.toInt() + 1) { session(storage, "old$it", "d1") }
                storage.flush()
                now = 8 * day
                clear = {
                    clear = null
                    // Another connection's write lock makes clearAll fail once the busy timeout ends.
                    DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { lock ->
                        lock.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                        launch { storage.clearAll() }.join()
                    }
                }

                storage.applyRetention()

                val error = storage.retentionStatus.value.lastError
                assertTrue(error?.contains("SQLITE_BUSY") == true, "clearAll's error kept, was $error")
                assertEquals(1, serverSessions(storage).size, "the pass stopped after its first chunk")
                storage.close(5.seconds)
            }
        }

    private suspend fun session(
        storage: Storage,
        id: String,
        deviceId: String,
        body: ByteString = ByteString.EMPTY,
        disconnect: Boolean = true,
    ) {
        storage.connected(id, hello(deviceId), null)
        storage.received(id, envelope(started("$id-r", body = body)))
        storage.received(id, envelope(ResponseReceived(request_id = "$id-r", status_code = 200)))
        if (disconnect) storage.disconnected(id)
    }

    private suspend fun serverSessions(storage: Storage): List<String> {
        storage.flush()
        return storage.observeSessions().first().map { it.serverSessionId }
    }

    private suspend fun awaitPass(storage: Storage): RetentionStatus =
        withTimeout(10.seconds) { storage.retentionStatus.first { it.lastPassAtMs != null } }

    // Incompressible, so stored raw and findable in the file.
    private fun marker(seed: Int) = ByteArray(100 * 1024).also { Random(seed).nextBytes(it) }.toByteString()

    private fun liveBytes(dir: Path) =
        (dir.queryLong("PRAGMA page_count") - dir.queryLong("PRAGMA freelist_count")) * dir.queryLong("PRAGMA page_size")

    private fun Path.contains(marker: ByteString): Boolean {
        val needle = marker.substring(0, 64)
        return listDirectoryEntries().any { Files.readAllBytes(it).toByteString().indexOf(needle) >= 0 }
    }
}
