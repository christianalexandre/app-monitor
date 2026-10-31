package appmonitor.desktop.storage

import appmonitor.v1.Envelope
import appmonitor.v1.RequestFailed
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RecorderTest {
    @Test
    fun nothingIsStoredWithoutARecording() =
        withDataDir { dir ->
            val storage = Storage.open(dir, startRun = false)
            runBlocking {
                val recorder = Recorder(LiveTraffic(), storage)
                recorder.connected("s1", hello("d1"), null)
                recorder.received("s1", envelope(started("r1")))
                recorder.received("s1", Envelope(channel = "log", type = "Line"))
                recorder.disconnected("s1")
                storage.flush()

                assertEquals(LiveTraffic.NO_RUN, storage.runId)
                assertNull(storage.recording.value)
                assertEquals(emptyList(), storage.runs())
                assertEquals(0, dir.queryLong("SELECT count(*) FROM sessions"))
                assertEquals(0, dir.queryLong("SELECT count(*) FROM raw_envelopes"))
                storage.close(5.seconds)
            }
        }

    @Test
    fun aRecordingHoldsEveryDeviceWhateverWhenItConnected() =
        withDataDir { dir ->
            var now = 1_000L
            val storage = Storage.open(dir, startRun = false) { now }
            runBlocking {
                val live = LiveTraffic { now }
                val recorder = Recorder(live, storage) { now }
                recorder.connected("before", hello("d1"), "10.0.0.2")
                recorder.received("before", envelope(started("old")))
                now = 2_000
                val run = assertNotNull(storage.record())
                assertEquals(run, storage.recording.value)
                recorder.received("before", envelope(started("a")))
                recorder.received("before", envelope(ResponseReceived(request_id = "old", status_code = 200)))
                // A device that reconnects during the recording is in it too.
                recorder.disconnected("before")
                recorder.connected("again", hello("d1"), "10.0.0.2")
                recorder.received("again", envelope(started("b")))
                recorder.received("again", Envelope(channel = "log", type = "Line"))
                recorder.connected("other", hello("d2"), null)
                recorder.received("other", envelope(started("c")))
                recorder.received("other", envelope(started("ws")))
                now = 3_000
                storage.stop()
                assertNull(storage.recording.value)
                // What started while recording ends in the run; what starts after stays in memory.
                recorder.received("again", envelope(ResponseReceived(request_id = "b", status_code = 201)))
                recorder.received("other", envelope(RequestFailed(request_id = "c", error = "timeout")))
                recorder.received("other", envelope(WebSocketFrame(request_id = "ws", payload = "hi".encodeUtf8(), payload_size = 2)))
                recorder.received("other", envelope(started("d")))
                storage.flush()

                val sessions = storage.sessions(run)
                assertEquals(listOf("before", "again", "other"), sessions.map { it.serverSessionId })
                assertEquals(1_000, sessions[0].connectedAtMs, "the session's own connection time")
                assertNotNull(sessions[0].disconnectedAtMs)
                assertNull(sessions[1].disconnectedAtMs)
                val stored = storage.exchangesOfRun(run)
                assertEquals(listOf("a", "b", "c", "ws"), stored.map { it.requestId })
                assertEquals(201, stored[1].statusCode)
                assertEquals("timeout", stored[2].error)
                assertEquals(1, stored[3].frameCount)
                assertEquals(1, dir.queryLong("SELECT count(*) FROM raw_envelopes"))
                assertEquals(listOf(StoredRun(run, null, 2_000, 3_000)), storage.runs())
                assertEquals(listOf("old", "a", "b", "c", "ws", "d"), live.searchExchanges(ExchangeFilter()).map { it.requestId })
                storage.close(5.seconds)
            }
        }

    @Test
    fun aStoppedRecordingKeepsItsEndThroughTheNextRecordingAndClose() =
        withDataDir { dir ->
            var now = 1_000L
            val storage = Storage.open(dir, startRun = false) { now }
            val (first, second) =
                runBlocking {
                    val first = assertNotNull(storage.record())
                    now = 2_000
                    storage.stop()
                    now = 3_000
                    val second = assertNotNull(storage.record())
                    now = 4_000
                    storage.stop()
                    now = 5_000
                    storage.close(5.seconds)
                    first to second
                }
            Storage.open(dir, startRun = false) { now }.let { reopened ->
                runBlocking {
                    val ends = reopened.runs().associate { it.id to it.endedAtMs }
                    assertEquals(mapOf(first to 2_000L, second to 4_000L), ends)
                    reopened.close(5.seconds)
                }
            }
        }

    @Test
    fun aSessionSpanningTwoRecordingsHasARowInEach() =
        withDataDir { dir ->
            val storage = Storage.open(dir, startRun = false)
            runBlocking {
                val recorder = Recorder(LiveTraffic(), storage)
                recorder.connected("s1", hello(), null)
                val first = assertNotNull(storage.record())
                recorder.received("s1", envelope(started("a")))
                storage.stop()
                val second = assertNotNull(storage.record())
                recorder.received("s1", envelope(started("b")))
                recorder.received("s1", envelope(ResponseReceived(request_id = "a", status_code = 200)))
                storage.flush()

                assertEquals(listOf("a"), storage.exchangesOfRun(first).map { it.requestId })
                assertEquals(200, storage.exchangesOfRun(first).single().statusCode)
                assertEquals(listOf("b"), storage.exchangesOfRun(second).map { it.requestId })
                assertEquals(listOf("s1"), storage.sessions(second).map { it.serverSessionId })
                storage.close(5.seconds)
            }
        }

    @Test
    fun clearAllEndsTheRecordingAndForgetsWhatItHeld() =
        withDataDir { dir ->
            val storage = Storage.open(dir, startRun = false)
            runBlocking {
                val recorder = Recorder(LiveTraffic(), storage)
                recorder.connected("s1", hello(), null)
                assertNotNull(storage.record())
                recorder.received("s1", envelope(started("a")))
                storage.clearAll()
                recorder.forget()
                recorder.received("s1", envelope(ResponseReceived(request_id = "a", status_code = 200)))
                recorder.received("s1", envelope(started("b")))
                storage.flush()

                assertNull(storage.recording.value)
                assertEquals(emptyList(), storage.runs(), "no run opens on clear either")
                assertEquals(0, dir.queryLong("SELECT count(*) FROM exchanges"))
                storage.close(5.seconds)
            }
        }

    @Test
    fun eachSessionKeepsItsLatestDropsLiveRecordedAndSaved() =
        withDataDir { dir ->
            val storage = Storage.open(dir, startRun = false)
            runBlocking {
                val live = LiveTraffic()
                val recorder = Recorder(live, storage)
                recorder.connected("s1", hello(), null)
                recorder.received("s1", envelope(started("before")))
                val first = StoredDrops(evicted = 3, oversized = 1, lost = null)
                recorder.dropped("s1", first)
                assertEquals(first, live.sessions().single().drops)

                // Reported before the recording: the run's row gets them when it is written.
                val run = assertNotNull(storage.record())
                recorder.received("s1", envelope(started("a")))
                storage.flush()
                assertEquals(first, storage.sessions(run).single().drops)

                val latest = StoredDrops(evicted = 5, oversized = 1, lost = 2)
                recorder.dropped("s1", latest)
                storage.flush()
                assertEquals(latest, storage.sessions(run).single().drops)
                assertEquals(latest, live.sessions().single().drops)

                storage.stop()
                storage.save(live.copies(live.searchExchanges(ExchangeFilter()).map { it.id }))
                val saved = storage.runs().single { it.saved }
                assertEquals(latest, storage.sessions(saved.id).single().drops)
                storage.close(5.seconds)
            }
            val reopened = Storage.open(dir, startRun = false)
            runBlocking {
                val run = reopened.runs().single { !it.saved }
                assertEquals(StoredDrops(5, 1, 2), reopened.sessions(run.id).single().drops, "kept across a restart")
                reopened.close(5.seconds)
            }
        }

    @Test
    fun aSessionWithoutAReportHasNoDrops() =
        withDataDir { dir ->
            val storage = Storage.open(dir, startRun = false)
            runBlocking {
                val live = LiveTraffic()
                val recorder = Recorder(live, storage)
                recorder.connected("s1", hello(), null)
                val run = assertNotNull(storage.record())
                recorder.received("s1", envelope(started("a")))
                storage.flush()

                assertNull(live.sessions().single().drops)
                assertNull(storage.sessions(run).single().drops)
                storage.close(5.seconds)
            }
        }
}
