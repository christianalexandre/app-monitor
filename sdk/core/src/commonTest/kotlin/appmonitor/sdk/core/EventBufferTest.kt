package appmonitor.sdk.core

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EventBufferTest {
    private fun frame(
        id: Int,
        size: Int = 1,
    ) = BufferedFrame("network", ByteArray(size) { id.toByte() })

    @Test
    fun takesInOfferOrder() =
        runTest {
            val buffer = EventBuffer()
            repeat(3) { buffer.offer(frame(it)) }
            assertEquals(listOf<Byte>(0, 1, 2), List(3) { buffer.take().bytes[0] })
        }

    @Test
    fun dropsTheOldestWhenTheCountIsReached() =
        runTest {
            val buffer = EventBuffer(maxCount = 2, maxBytes = 1_000)
            repeat(3) { buffer.offer(frame(it)) }
            assertEquals(1, buffer.dropped)
            assertEquals(listOf<Byte>(1, 2), List(2) { buffer.take().bytes[0] })
        }

    @Test
    fun dropsTheOldestWholeFramesWhenTheBytesAreReached() =
        runTest {
            val buffer = EventBuffer(maxCount = 100, maxBytes = 10)
            buffer.offer(frame(0, size = 4))
            buffer.offer(frame(1, size = 4))
            buffer.offer(frame(2, size = 4))
            assertEquals(1, buffer.dropped)
            assertEquals(2, buffer.size)
            assertEquals(1, buffer.take().bytes[0])
        }

    @Test
    fun aFrameDroppedOrClearedReportsIt() {
        val buffer = EventBuffer(maxCount = 1, maxBytes = 1_000)
        val dropped = mutableListOf<Int>()
        buffer.offer(BufferedFrame("interaction", ByteArray(1)) { dropped += 0 })
        buffer.offer(BufferedFrame("interaction", ByteArray(1)) { dropped += 1 })
        buffer.clear()
        assertEquals(listOf(0, 1), dropped)
    }

    @Test
    fun takeWaitsForTheNextOffer() =
        runTest {
            val buffer = EventBuffer()
            val taken = async { buffer.take() }
            yield()
            assertFalse(taken.isCompleted)
            buffer.offer(frame(7))
            assertEquals(7, taken.await().bytes[0])
        }

    @Test
    fun clearEmptiesTheBuffer() {
        val buffer = EventBuffer()
        buffer.offer(frame(0))
        buffer.clear()
        assertEquals(0, buffer.size)
    }
}
