package appmonitor.desktop.proxy

import appmonitor.desktop.storage.TrafficSink
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyRecordingTest {
    private class CountingSink : TrafficSink {
        val received = AtomicInteger()

        override suspend fun connected(
            sessionId: String,
            hello: Hello,
            peerAddress: String?,
        ) = Unit

        override suspend fun received(
            sessionId: String,
            envelope: Envelope,
        ) {
            received.incrementAndGet()
        }

        override suspend fun disconnected(sessionId: String) = Unit
    }

    @Test
    fun eventsPastThePendingBytesAreDropped() =
        runBlocking {
            val sink = CountingSink()
            val recording = ProxyRecording(sink, this, open = false)
            val payload = ByteArray(1 shl 20).toByteString()

            repeat(80) { recording.record(Envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", payload)) }
            recording.open()
            recording.close()

            assertEquals(64, sink.received.get())
            assertEquals(16, recording.dropped)
        }
}
