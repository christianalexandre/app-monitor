package appmonitor.desktop.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RunsTest {
    @Test
    fun sessionsJoinTheRunCurrentWhenTheyConnect() =
        withDataDir { dir ->
            var now = 1_000L
            val storage = Storage.open(dir) { now }
            runBlocking {
                storage.connected("s1", hello("d1"), null)
                storage.received("s1", envelope(started("r1")))
                now = 2_000
                val second = storage.newRun()
                assertEquals(second, storage.currentRun.value)
                // A session spanning "New run" stays in the run it started in (ADR-0084).
                storage.received("s1", envelope(started("r2")))
                storage.connected("s2", hello("d2"), null)
                storage.received("s2", envelope(started("r3")))
                storage.flush()

                assertEquals(listOf("s1"), storage.sessions(storage.runId).map { it.serverSessionId })
                assertEquals(listOf("s2"), storage.sessions(second).map { it.serverSessionId })
                assertEquals(listOf("r1", "r2"), storage.exchangesOfRun(storage.runId).map { it.requestId })
                assertEquals(listOf("r3"), storage.exchangesOfRun(second).map { it.requestId })
                assertEquals(
                    listOf(StoredRun(second, null, 2_000, null), StoredRun(storage.runId, null, 1_000, 2_000)),
                    storage.observeRuns().first(),
                    "newest first, the earlier one ended",
                )
                assertEquals(listOf("s2"), storage.observeSessions(second).first().map { it.serverSessionId })
                assertEquals(2, storage.observeSessions().first().size, "null is every run")
                storage.close(5.seconds)
            }
        }

    @Test
    fun runsAreNamedAndUnnamed() =
        withDataDir { dir ->
            val storage = Storage.open(dir) { 1_000 }
            runBlocking {
                val second = storage.newRun()
                storage.rename(storage.runId, "  Checkout on Pixel ")
                storage.rename(second, "Login")
                storage.flush()
                assertEquals(listOf("Login", "Checkout on Pixel"), storage.observeRuns().first().map { it.name })

                storage.rename(second, " ")
                storage.flush()
                assertNull(storage.runs().first { it.id == second }.name, "blank is unnamed again")
                storage.close(5.seconds)
            }
            val reopened = Storage.open(dir) { 2_000 }
            runBlocking {
                assertEquals(listOf("Checkout on Pixel", null, null), reopened.runs().map { it.name }, "names kept across a restart")
                reopened.close(5.seconds)
            }
        }

    @Test
    fun aLostNewRunKeepsTheEarlierRun() =
        withDataDir { dir ->
            var failNext = true
            val storage =
                Storage.open(dir, { 0L }, Storage.MAX_QUEUE_BYTES) { batch ->
                    if (failNext && batch.any { it is StorageItem.NewRun }) {
                        failNext = false
                        throw SQLException("disk I/O error")
                    }
                }
            runBlocking {
                val held = holdWriter(storage)
                val opening = async { storage.newRun() }
                // Queued ahead of the session, in the batch that fails.
                yield()
                storage.connected("s1", hello(), null)
                held.release()
                assertEquals(storage.runId, opening.await(), "the earlier run stays current")
                storage.received("s1", envelope(started("r1")))
                storage.flush()
                assertEquals(storage.runId, storage.currentRun.value)
                assertEquals(listOf(storage.runId), storage.runs().map { it.id })
                assertEquals(1, storage.sessions(storage.runId).size, "written again into the run that is left")
                storage.close(5.seconds)
            }
        }
}
