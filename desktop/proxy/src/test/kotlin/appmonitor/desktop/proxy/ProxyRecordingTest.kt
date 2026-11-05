package appmonitor.desktop.proxy

import appmonitor.desktop.storage.TrafficSink
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.RequestFailed
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
            val lines = mutableListOf<String>()
            val recording = ProxyRecording(sink, this, open = false, log = { lines += it })
            val payload = ByteArray(1 shl 20).toByteString()

            repeat(80) { recording.record(Envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", payload)) }
            recording.open()
            recording.close()

            assertEquals(64, sink.received.get())
            assertEquals(16, recording.dropped)
            assertEquals(listOf("Proxy recording dropped 16 events: more than 64 MiB waited for the store"), lines)
        }

    @Test
    fun aRequestFailedPassesTheBound() =
        runBlocking {
            val sink = CountingSink()
            val recording = ProxyRecording(sink, this, open = false, maxPendingBytes = 1, log = {})
            val payload = ByteArray(16).toByteString()

            assertEquals(false, recording.record(Envelope(Protocol.CHANNEL_NETWORK, "ResponseReceived", payload)))
            assertEquals(true, recording.record(Envelope(Protocol.CHANNEL_NETWORK, "RequestFailed", payload)))
            recording.open()
            recording.close()

            assertEquals(1, sink.received.get())
        }

    @Test
    fun aDroppedStartLeavesNothingOfItsExchange() {
        val kept = mutableListOf<String>()
        val capture =
            Capture("GET", "http://x/", emptyList(), {
                kept += it.type
                it.type != "RequestStarted"
            }, { 0L })

        capture.requestDone()
        capture.frame(sent = true, message = BodyCapture())
        capture.responded(200, emptyList(), BodyCapture())

        assertEquals(listOf("RequestStarted"), kept)
    }

    @Test
    fun aDroppedEventAfterTheStartEndsTheExchangeAsCut() {
        val kept = mutableListOf<Envelope>()
        val capture =
            Capture("GET", "http://x/", emptyList(), {
                kept += it
                it.type != "WebSocketFrame"
            }, { 0L })

        capture.requestDone()
        capture.frame(sent = true, message = BodyCapture())
        // Not ended: the relay goes on, and still ends the exchange on the wire.
        assertFalse(capture.ended)
        capture.frame(sent = false, message = BodyCapture())
        capture.responded(200, emptyList(), BodyCapture())

        assertEquals(listOf("RequestStarted", "WebSocketFrame", "RequestFailed"), kept.map { it.type })
        assertEquals(Capture.CUT, RequestFailed.ADAPTER.decode(kept.last().payload).error)
    }
}
