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
}
