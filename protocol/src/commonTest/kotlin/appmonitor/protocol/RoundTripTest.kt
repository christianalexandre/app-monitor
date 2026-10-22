package appmonitor.protocol

import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class RoundTripTest {
    @Test
    fun hello() = assertEquals(Samples.hello, Hello.ADAPTER.decode(Samples.hello.encode()))

    @Test
    fun helloAck() = assertEquals(Samples.helloAck, HelloAck.ADAPTER.decode(Samples.helloAck.encode()))

    @Test
    fun requestStarted() = assertEquals(Samples.requestStarted, RequestStarted.ADAPTER.decode(Samples.requestStarted.encode()))

    @Test
    fun responseReceived() = assertEquals(Samples.responseReceived, ResponseReceived.ADAPTER.decode(Samples.responseReceived.encode()))

    @Test
    fun requestFailed() = assertEquals(Samples.requestFailed, RequestFailed.ADAPTER.decode(Samples.requestFailed.encode()))

    @Test
    fun webSocketFrame() = assertEquals(Samples.webSocketFrame, WebSocketFrame.ADAPTER.decode(Samples.webSocketFrame.encode()))

    @Test
    fun envelopes() {
        Samples.envelopes.values.forEach { envelope ->
            assertEquals(envelope, Envelope.ADAPTER.decode(envelope.encode()))
        }
    }

    @Test
    fun envelopePayloadDecodesToItsMessage() {
        val envelope = Samples.envelopes.getValue("hello.bin")
        assertEquals(Samples.hello, Hello.ADAPTER.decode(envelope.payload))
    }

    @Test
    fun unknownFieldsFromANewerPeerAreIgnored() {
        // Field 99, varint 7: what a newer schema could add.
        val newer = Samples.requestFailed.encode() + byteArrayOf(0x98.toByte(), 0x06, 0x07)
        val decoded = RequestFailed.ADAPTER.decode(newer)
        assertEquals(Samples.requestFailed.request_id, decoded.request_id)
        assertEquals(Samples.requestFailed.error, decoded.error)
        assertEquals(3, decoded.unknownFields.size)
    }
}
