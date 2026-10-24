package appmonitor.desktop.storage

import kotlinx.coroutines.runBlocking
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
}
