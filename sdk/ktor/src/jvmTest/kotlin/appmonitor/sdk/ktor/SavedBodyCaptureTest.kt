package appmonitor.sdk.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavedBodyCaptureTest {
    @Test
    fun aSavedBodyIsCutWithoutCopyingItIntoAnArray() =
        runBlocking {
            val size = 32 shl 20
            val response = HttpClient(MockEngine { respond(ByteArray(size)) }).get("http://api.example.com/big")
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val thread = Thread.currentThread().threadId()
            val before = threads.getThreadAllocatedBytes(thread)

            val body = response.capture(1024)

            val allocated = threads.getThreadAllocatedBytes(thread) - before
            assertEquals(1024, body.bytes.size)
            assertTrue(body.truncated)
            assertEquals(size.toLong(), body.size)
            // Ktor's saved response copies its array into a Buffer per read; a second whole copy is the bug.
            assertTrue(allocated < size * 3L / 2, "allocated $allocated bytes for a $size-byte body")
        }
}
