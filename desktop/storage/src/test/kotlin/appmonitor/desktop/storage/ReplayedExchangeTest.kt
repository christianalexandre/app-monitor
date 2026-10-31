package appmonitor.desktop.storage

import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ReplayedExchangeTest {
    @Test
    fun aReplayKeepsItsOriginalInEveryListReaderAndLinksToIt() =
        withDataDir { dir ->
            val storage = Storage.open(dir) { 1_000 }
            runBlocking {
                storage.connected("s", hello(), null)
                storage.received("s", envelope(started("first")))
                storage.received("s", envelope(started("again").copy(replay_of = "first")))
                storage.flush()
                val session = storage.sessions(storage.runId).single().id
                val rows =
                    listOf(
                        storage.searchExchanges(ExchangeFilter()),
                        storage.exchangesOfSession(session),
                        storage.exchangesOfRun(storage.runId),
                        storage.searchExchangeSizes(ExchangeFilter()).map { it.exchange },
                    ).map { list -> list.associateBy { it.requestId } }
                rows.forEach {
                    assertEquals("first", it.getValue("again").replayOf)
                    assertNull(it.getValue("first").replayOf)
                }
                val byRequest = rows.first()
                val detail = storage.exchange(byRequest.getValue("again").id)!!
                assertEquals(byRequest.getValue("first").id, detail.replayOfId)
                assertNull(storage.exchange(byRequest.getValue("first").id)!!.replayOfId)
                assertEquals(
                    mapOf(byRequest.getValue("again").id to byRequest.getValue("first").id),
                    storage.replayOriginals(byRequest.values.toList()),
                )
                assertEquals("s", storage.session(session)!!.serverSessionId)
                assertNull(storage.session(session + 1))
                storage.close(5.seconds)
            }
        }

    @Test
    fun anOriginalOfAnotherSessionOrNotStoredIsNotLinked() =
        withDataDir { dir ->
            val storage = Storage.open(dir) { 1_000 }
            runBlocking {
                storage.connected("a", hello(), null)
                storage.received("a", envelope(started("first")))
                storage.connected("b", hello(), null)
                storage.received("b", envelope(started("again").copy(replay_of = "first")))
                storage.received("b", envelope(started("lost").copy(replay_of = "gone")))
                storage.flush()
                val rows = storage.searchExchanges(ExchangeFilter()).associateBy { it.requestId }
                assertNull(storage.exchange(rows.getValue("again").id)!!.replayOfId)
                val lost = storage.exchange(rows.getValue("lost").id)!!
                assertEquals("gone", lost.exchange.replayOf)
                assertNull(lost.replayOfId)
                assertEquals(emptyMap(), storage.replayOriginals(rows.values.toList()))
                storage.close(5.seconds)
            }
        }

    @Test
    fun aVersionSixFileIsMigrated() =
        withDataDir { dir ->
            Storage.open(dir) { 1_000 }.let { runBlocking { it.close(5.seconds) } }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { connection ->
                connection.createStatement().use {
                    it.execute("ALTER TABLE exchanges DROP COLUMN replay_of")
                    it.execute("ALTER TABLE runs DROP COLUMN saved")
                    it.execute("ALTER TABLE sessions DROP COLUMN drops_evicted")
                    it.execute("ALTER TABLE sessions DROP COLUMN drops_oversized")
                    it.execute("ALTER TABLE sessions DROP COLUMN drops_lost")
                    it.execute("PRAGMA user_version = 6")
                }
            }
            val storage = Storage.open(dir) { 2_000 }
            runBlocking {
                storage.connected("s", hello(), null)
                storage.received("s", envelope(started("again").copy(replay_of = "first")))
                storage.flush()
                assertEquals("first", storage.searchExchanges(ExchangeFilter()).single().replayOf)
                storage.close(5.seconds)
            }
            assertEquals(9, dir.queryLong("PRAGMA user_version"))
        }
}
