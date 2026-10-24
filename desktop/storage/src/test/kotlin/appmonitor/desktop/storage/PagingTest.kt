package appmonitor.desktop.storage

import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PagingTest {
    @Test
    fun pagesByKeyWhileIngesting() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello(), null)
                repeat(450) { storage.received("s1", envelope(started("r$it"))) }
                storage.flush()

                val first = storage.exchangesOfRun(storage.runId)
                assertEquals(Storage.PAGE_SIZE.toInt(), first.size)
                // Rows added between two pages land after the key, never shift a page already read.
                repeat(100) { storage.received("s1", envelope(started("late$it"))) }
                storage.flush()
                val seen = first.toMutableList()
                while (true) {
                    val page = storage.exchangesOfRun(storage.runId, afterId = seen.last().id)
                    if (page.isEmpty()) break
                    seen += page
                }
                assertEquals((0 until 450).map { "r$it" } + (0 until 100).map { "late$it" }, seen.map { it.requestId })
                assertEquals(seen.map { it.id }.sorted(), seen.map { it.id })

                val session = storage.sessions(storage.runId).single().id
                assertEquals(seen.takeLast(50), storage.exchangesOfSession(session, afterId = seen[499].id))
                storage.close(5.seconds)
            }
        }

    @Test
    fun observedPagesFollowCommits() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                withTimeout(5.seconds) {
                    storage.connected("s1", hello(), null)
                    repeat(3) { storage.received("s1", envelope(started("r$it"))) }
                    storage.flush()
                    val session = storage.observeSessions().first { it.isNotEmpty() }.single()
                    val page = storage.observeExchangesOfSession(session.id, limit = 2)
                    assertEquals(listOf("r0", "r1"), page.first().map { it.requestId })

                    storage.received("s1", envelope(ResponseReceived(request_id = "r1", status_code = 204)))
                    storage.disconnected("s1")
                    assertEquals(204, page.first { rows -> rows[1].statusCode != null }[1].statusCode)
                    storage.observeSessions().first { it.single().disconnectedAtMs != null }
                }
                storage.close(5.seconds)
            }
        }
}
