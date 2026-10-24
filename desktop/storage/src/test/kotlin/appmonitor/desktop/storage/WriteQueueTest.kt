package appmonitor.desktop.storage

import app.cash.sqldelight.Query
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WriteQueueTest {
    @Test
    fun oneTransactionPerBatch() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello(), null)
                storage.flush()
                val session = storage.sessions(storage.runId).single().id
                val notifications = AtomicInteger()
                val listener = Query.Listener { notifications.incrementAndGet() }
                val page = storage.pageQuery(session)
                page.addListener(listener)
                val held = holdWriter(storage)
                repeat(100) { storage.received("s1", envelope(started("r$it"))) }
                held.release()
                storage.flush()
                page.removeListener(listener)
                assertEquals(1, notifications.get(), "100 envelopes, one batch, one commit")
                assertEquals(100, storage.exchangesOfSession(session).size)
                storage.close(5.seconds)
            }
        }

    @Test
    fun slowWriterDoesNotBlockEnqueue() =
        withDataDir { dir ->
            val storage = Storage.open(dir, { 0L }, maxQueueBytes = 1_000_000) {}
            runBlocking {
                storage.connected("s1", hello(), null)
                val held = holdWriter(storage)
                // About 500 KB queued, under the bound: the collector never waits for SQLite.
                withTimeout(2.seconds) { repeat(100) { storage.received("s1", envelope(started("r$it", body = random(5_000)))) } }
                assertTrue(storage.queueHighWaterBytes in 500_000..1_000_000)
                held.release()
                storage.flush()
                assertEquals(100, storage.exchangesOfRun(storage.runId).size)
                storage.close(5.seconds)
            }
        }

    @Test
    fun fullQueueSuspends() =
        withDataDir { dir ->
            val storage = Storage.open(dir, { 0L }, maxQueueBytes = 20_000) {}
            runBlocking {
                storage.connected("s1", hello(), null)
                val held = holdWriter(storage)
                repeat(3) { storage.received("s1", envelope(started("r$it", body = random(6_000)))) }
                val fourth = launch { storage.received("s1", envelope(started("r3", body = random(6_000)))) }
                delay(200.milliseconds)
                assertFalse(fourth.isCompleted, "the bound is reached, enqueue waits")
                assertEquals(1, storage.queueFullWaits)
                held.release()
                withTimeout(5.seconds) { fourth.join() }
                storage.flush()
                assertEquals(4, storage.exchangesOfRun(storage.runId).size, "nothing dropped")
                storage.close(5.seconds)
            }
        }

    @Test
    fun failedBatchIsCountedAndSkipped() =
        withDataDir { dir ->
            var failNext = true
            val storage =
                Storage.open(dir, { 0L }, Storage.MAX_QUEUE_BYTES) { batch ->
                    if (failNext && batch.any { it is StorageItem.Received }) {
                        failNext = false
                        throw SQLException("disk I/O error")
                    }
                }
            runBlocking {
                val held = holdWriter(storage)
                storage.connected("s1", hello(), null)
                storage.received("s1", envelope(started("r1")))
                held.release()
                // The flush's barrier then rides alone: a batch with nothing to save must not clear the error.
                withTimeout(5.seconds) { storage.health.first { it.notSaved > 0 } }
                storage.flush()
                assertEquals(StorageHealth(notSaved = 2, lastError = "java.sql.SQLException: disk I/O error"), storage.health.value)
                assertTrue(storage.sessions(storage.runId).isEmpty(), "rolled back with the batch")

                storage.received("s1", envelope(ResponseReceived(request_id = "r1", status_code = 200)))
                storage.flush()
                assertNull(storage.health.value.lastError)
                assertEquals(2, storage.health.value.notSaved)
                assertEquals(1, storage.sessions(storage.runId).size, "the session is written again from its Hello")
                assertEquals(200, storage.exchangesOfRun(storage.runId).single().statusCode, "created by its next event")
                storage.close(5.seconds)
            }
        }

    @Test
    fun aLostDisconnectIsWrittenWithTheNextBatch() =
        withDataDir { dir ->
            var failNext = true
            val storage =
                Storage.open(dir, { 0L }, Storage.MAX_QUEUE_BYTES) { batch ->
                    if (failNext && batch.any { it is StorageItem.Disconnected }) {
                        failNext = false
                        throw SQLException("disk I/O error")
                    }
                }
            runBlocking {
                storage.connected("s1", hello(), null)
                storage.flush()
                storage.disconnected("s1")
                withTimeout(5.seconds) { storage.health.first { it.notSaved > 0 } }
                assertNull(storage.sessions(storage.runId).single().disconnectedAtMs, "rolled back with the batch")

                storage.connected("s2", hello("device-2"), null)
                storage.flush()
                assertNull(storage.health.value.lastError)
                val sessions = storage.sessions(storage.runId).associateBy { it.serverSessionId }
                assertNotNull(sessions.getValue("s1").disconnectedAtMs, "written again by the next batch")
                assertNull(sessions.getValue("s2").disconnectedAtMs)
                assertEquals(StorageHealth(), storage.health.value, "written after all, no longer counted")
                storage.close(5.seconds)
            }
        }

    @Test
    fun aLostDisconnectRetriedByALoneFlushClearsTheError() =
        withDataDir { dir ->
            var failNext = true
            val storage =
                Storage.open(dir, { 0L }, Storage.MAX_QUEUE_BYTES) { batch ->
                    if (failNext && batch.any { it is StorageItem.Disconnected }) {
                        failNext = false
                        throw SQLException("disk I/O error")
                    }
                }
            runBlocking {
                storage.connected("s1", hello(), null)
                storage.flush()
                storage.disconnected("s1")
                withTimeout(5.seconds) { storage.health.first { it.notSaved > 0 } }

                storage.flush()
                assertEquals(StorageHealth(), storage.health.value)
                assertNotNull(storage.sessions(storage.runId).single().disconnectedAtMs)
                storage.close(5.seconds)
            }
        }

    @Test
    fun aDisconnectThatAlwaysFailsDoesNotBlockLaterBatches() =
        withDataDir { dir ->
            val storage =
                Storage.open(dir, { 0L }, Storage.MAX_QUEUE_BYTES) { batch ->
                    if (batch.any { it is StorageItem.Disconnected && it.sessionId == "s1" }) throw SQLException("constraint failed")
                }
            runBlocking {
                storage.connected("s1", hello(), null)
                storage.flush()
                storage.disconnected("s1")
                withTimeout(5.seconds) { storage.health.first { it.notSaved > 0 } }

                storage.connected("s2", hello("device-2"), null)
                repeat(3) {
                    storage.received("s2", envelope(started("r$it")))
                    storage.flush()
                }
                storage.disconnected("s2")
                storage.flush()
                val sessions = storage.sessions(storage.runId).associateBy { it.serverSessionId }
                assertNull(sessions.getValue("s1").disconnectedAtMs, "still lost")
                assertNotNull(sessions.getValue("s2").disconnectedAtMs)
                assertEquals(3, storage.exchangesOfSession(sessions.getValue("s2").id).size)
                assertEquals(StorageHealth(notSaved = 1), storage.health.value, "the later batches cleared the error")
                storage.close(5.seconds)
            }
        }

    @Test
    fun closeDrainsQueue() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello(), null)
                val held = holdWriter(storage)
                repeat(1_200) { storage.received("s1", envelope(started("r$it"))) }
                storage.disconnected("s1")
                val closing = async { storage.close(10.seconds) }
                held.release()
                assertEquals(0, closing.await())
            }
            assertEquals(1_200, dir.queryLong("SELECT count(*) FROM exchanges"))
            assertEquals(1, dir.queryLong("SELECT count(*) FROM runs WHERE ended_at_ms IS NOT NULL"))
            assertEquals(1, dir.queryLong("SELECT count(*) FROM sessions WHERE disconnected_at_ms IS NOT NULL"))
        }

    @Test
    fun closeGivesUpAfterTimeout() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                val held = holdWriter(storage)
                storage.connected("s1", hello(), null)
                repeat(10) { storage.received("s1", envelope(started("r$it"))) }
                val lost = storage.close(300.milliseconds)
                held.release()
                assertEquals(12, lost, "Connected, ten envelopes and the run's end")
            }
        }

    @Test
    fun callsAfterCloseReturn() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.close(5.seconds)
                withTimeout(2.seconds) {
                    storage.flush()
                    assertEquals(storage.runId, storage.newRun(), "nothing written, the run stays")
                    storage.clearAll()
                    storage.applyRetention()
                    storage.setRetention(RetentionSettings(maxAgeDays = 1))
                }
                assertEquals(storage.runId, storage.currentRun.value)
                assertNull(storage.retentionStatus.value.lastError)
            }
        }

    @Test
    fun timedOutCloseLeavesTheFileToTheWriter() =
        withDataDir { dir ->
            val entered = CountDownLatch(1)
            val stall = CountDownLatch(1)
            val storage =
                Storage.open(dir, { 0L }, Storage.MAX_QUEUE_BYTES) { batch ->
                    if (batch.any { it is StorageItem.Received }) {
                        entered.countDown()
                        stall.await()
                    }
                }
            runBlocking {
                val held = holdWriter(storage)
                storage.connected("s1", hello(), null)
                repeat(3) { storage.received("s1", envelope(started("r$it"))) }
                held.release()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val queued = async(start = CoroutineStart.UNDISPATCHED) { storage.flush() }
                val queuedRun = async(start = CoroutineStart.UNDISPATCHED) { storage.newRun() }
                assertEquals(6, storage.close(300.milliseconds), "the stalled batch, the new run and the run's end")
                withTimeout(2.seconds) {
                    storage.flush()
                    storage.newRun()
                }
                stall.countDown()
                withTimeout(5.seconds) {
                    queued.await()
                    assertEquals(storage.runId, queuedRun.await(), "dropped, the run stays")
                }
                val executor = storage.writerDispatcher.executor as ExecutorService
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "closed once the writer left its batch")
            }
            assertEquals(3, dir.queryLong("SELECT count(*) FROM exchanges"), "the batch committed on an open file")
        }

    @Test
    fun callsQueuedOnTheWriterDuringATimedOutCloseDoNothing() =
        withDataDir { dir ->
            val entered = CountDownLatch(1)
            val stall = CountDownLatch(1)
            val storage =
                Storage.open(dir, { 0L }, Storage.MAX_QUEUE_BYTES, retentionInterval = null) { batch ->
                    if (batch.any { it is StorageItem.Received }) {
                        entered.countDown()
                        stall.await()
                    }
                }
            runBlocking {
                val held = holdWriter(storage)
                storage.connected("s1", hello(), null)
                repeat(3) { storage.received("s1", envelope(started("r$it"))) }
                held.release()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val pass = async(start = CoroutineStart.UNDISPATCHED) { storage.applyRetention() }
                val cleared = async(start = CoroutineStart.UNDISPATCHED) { storage.clearAll() }
                val set = async(start = CoroutineStart.UNDISPATCHED) { storage.setRetention(RetentionSettings(maxAgeDays = 1)) }
                storage.close(300.milliseconds)
                stall.countDown()
                withTimeout(5.seconds) {
                    assertEquals(RetentionStatus(), pass.await(), "no pass on a closing store")
                    cleared.await()
                    set.await()
                }
                assertEquals(RetentionStatus(), storage.retentionStatus.value, "the closed driver was never touched")
                val executor = storage.writerDispatcher.executor as ExecutorService
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
            assertEquals(3, dir.queryLong("SELECT count(*) FROM exchanges"), "not cleared")
            assertEquals(0, dir.queryLong("SELECT count(*) FROM settings"), "dropped with the queue")
        }
}
