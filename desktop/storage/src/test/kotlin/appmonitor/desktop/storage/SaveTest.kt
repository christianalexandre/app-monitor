package appmonitor.desktop.storage

import appmonitor.v1.RequestFailed
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SaveTest {
    @Test
    fun savedExchangesGoToOneSavedRunAndSurviveARestart() =
        withDataDir { dir ->
            var now = 1_000L
            val live = LiveTraffic { now }
            val ids =
                runBlocking {
                    live.connected("s1", hello("d1"), "10.0.0.2")
                    live.received("s1", envelope(started("ok", body = "{\"a\":1}".encodeUtf8()), atMs = 1_000))
                    live.received(
                        "s1",
                        envelope(ResponseReceived(request_id = "ok", status_code = 200, body = "done".encodeUtf8()), atMs = 1_200),
                    )
                    live.received("s1", envelope(started("ws"), atMs = 1_300))
                    live.received(
                        "s1",
                        envelope(WebSocketFrame(request_id = "ws", payload = "hi".encodeUtf8(), payload_size = 2), atMs = 1_400),
                    )
                    live.connected("s2", hello("d2"), null)
                    live.received("s2", envelope(started("bad"), atMs = 1_500))
                    live.received("s2", envelope(RequestFailed(request_id = "bad", error = "timeout"), atMs = 1_600))
                    live.received("s2", envelope(started("skipped"), atMs = 1_700))
                    live.searchExchanges(ExchangeFilter()).filter { it.requestId != "skipped" }.map { it.id }
                }

            val storage = Storage.open(dir, startRun = false) { now }
            val saved =
                runBlocking {
                    now = 2_000
                    val saved = storage.save(live.copies(ids))
                    assertEquals(ids.toSet(), saved.keys)
                    // Saving again writes nothing more, and gives the same rows.
                    assertEquals(saved, storage.save(live.copies(ids)))
                    storage.close(5.seconds)
                    saved
                }

            val reopened = Storage.open(dir, startRun = false)
            runBlocking {
                val run = reopened.runs().single()
                assertTrue(run.saved)
                assertEquals("Saved", run.name)
                assertEquals(listOf("s1", "s2"), reopened.sessions(run.id).map { it.serverSessionId })
                val stored = reopened.exchangesOfRun(run.id)
                assertEquals(listOf("ok", "ws", "bad"), stored.map { it.requestId })
                assertEquals(1_000L, stored[0].startedAtMs)
                assertEquals(200, stored[0].statusCode)
                assertEquals("timeout", stored[2].error)
                val ok = assertNotNull(reopened.exchange(saved.getValue(ids[0])))
                assertEquals("{\"a\":1}", ok.request?.body?.utf8())
                assertEquals("done", ok.response?.body?.utf8())
                assertEquals(listOf("hi"), reopened.frames(saved.getValue(ids[1])).map { it.payload.utf8() })
                reopened.close(5.seconds)
            }
        }

    @Test
    fun anExchangeGoneFromTheListIsNotSaved() =
        withDataDir { dir ->
            val live = LiveTraffic(maxExchanges = 1)
            val storage = Storage.open(dir, startRun = false)
            runBlocking {
                live.connected("s1", hello(), null)
                live.received("s1", envelope(started("old")))
                val old = live.searchExchanges(ExchangeFilter()).single().id
                live.received("s1", envelope(started("new")))

                assertEquals(emptyMap(), storage.save(live.copies(listOf(old))))
                assertEquals(emptyList(), storage.runs())
                storage.close(5.seconds)
            }
        }

    @Test
    fun retentionKeepsSaved() =
        withDataDir { dir ->
            var now = 1_000L
            val live = LiveTraffic { now }
            val storage = Storage.open(dir, startRun = false) { now }
            runBlocking {
                live.connected("s1", hello(), null)
                live.received("s1", envelope(started("a")))
                live.disconnected("s1")
                storage.save(live.copies(live.searchExchanges(ExchangeFilter()).map { it.id }))
                // Unnamed, it is still not a recording retention may prune.
                storage.rename(storage.runs().single().id, "")
                now += 400L * 24 * 60 * 60 * 1_000
                storage.setRetention(RetentionSettings(maxAgeDays = 1))
                storage.applyRetention()

                assertEquals(1, dir.queryLong("SELECT count(*) FROM exchanges"))
                assertEquals(1, dir.queryLong("SELECT count(*) FROM runs WHERE saved = 1"))
                storage.close(5.seconds)
            }
        }

    @Test
    fun aVersionSevenFileIsMigratedWithItsRunsAsRecordings() =
        withDataDir { dir ->
            Storage.open(dir) { 1_000 }.let { runBlocking { it.close(5.seconds) } }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { connection ->
                connection.createStatement().use {
                    it.execute("ALTER TABLE runs DROP COLUMN saved")
                    it.execute("ALTER TABLE sessions DROP COLUMN drops_evicted")
                    it.execute("ALTER TABLE sessions DROP COLUMN drops_oversized")
                    it.execute("ALTER TABLE sessions DROP COLUMN drops_lost")
                    it.execute("PRAGMA user_version = 7")
                }
            }
            // The clock of the first open: retention at open would prune the empty run.
            val storage = Storage.open(dir, startRun = false) { 1_000 }
            runBlocking {
                assertEquals(listOf(false), storage.runs().map { it.saved })
                storage.close(5.seconds)
            }
            assertEquals(9, dir.queryLong("PRAGMA user_version"))
        }
}
