package appmonitor.protocol

import appmonitor.v1.BreakpointAbort
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRules
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import appmonitor.v1.MockRule
import appmonitor.v1.MockRules
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import okio.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun mockRules() = assertEquals(Samples.mockRules, MockRules.ADAPTER.decode(Samples.mockRules.encode()))

    @Test
    fun breakpointMessages() {
        assertEquals(Samples.breakpointRules, BreakpointRules.ADAPTER.decode(Samples.breakpointRules.encode()))
        assertEquals(Samples.breakpointHit, BreakpointHit.ADAPTER.decode(Samples.breakpointHit.encode()))
        assertEquals(Samples.breakpointResume, BreakpointResume.ADAPTER.decode(Samples.breakpointResume.encode()))
        assertEquals(Samples.breakpointAbort, BreakpointAbort.ADAPTER.decode(Samples.breakpointAbort.encode()))
    }

    @Test
    fun aResumeKeepsAnUnchangedFieldApartFromAnEmptyOne() {
        // ADR-0111 C.1: an absent body keeps the device's, an empty one clears it.
        val decoded = BreakpointResume.ADAPTER.decode(Samples.breakpointResume.copy(status_code = null).encode())
        assertNull(decoded.status_code)
        assertNull(decoded.method)
        assertEquals(ByteString.EMPTY, decoded.body)
        assertNull(BreakpointResume.ADAPTER.decode(Samples.breakpointAbort.encode()).body)
    }

    @Test
    fun mockRuleWithoutAnAnswerFromANewerPeerHasNeither() {
        // A newer desktop's answer kind lands in an unknown field: the rule reads with no answer.
        val newer =
            Samples.mockRules.rules[0]
                .copy(response = null)
                .encode() + byteArrayOf(0x40, 0x01)
        val decoded = MockRule.ADAPTER.decode(newer)
        assertNull(decoded.response)
        assertNull(decoded.failure)
    }

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
    fun bodyFromAnOlderPeerIsWholeWithUnknownSize() {
        val older = Samples.responseReceived.copy(body_truncated = false, body_size = null).encode()
        val decoded = ResponseReceived.ADAPTER.decode(older)
        assertFalse(decoded.body_truncated)
        assertNull(decoded.body_size)
    }

    @Test
    fun zeroBodySizeIsKeptApartFromUnknown() {
        val empty = Samples.requestStarted.copy(body = ByteString.EMPTY, body_size = 0)
        assertEquals(0L, RequestStarted.ADAPTER.decode(empty.encode()).body_size)
    }

    @Test
    fun captureLayerFromAnOlderPeerIsUnspecified() {
        val older = Samples.requestStarted.copy(capture_layer = CaptureLayer.CAPTURE_LAYER_UNSPECIFIED).encode()
        assertEquals(CaptureLayer.CAPTURE_LAYER_UNSPECIFIED, RequestStarted.ADAPTER.decode(older).capture_layer)
    }

    @Test
    fun everyCaptureLayerRoundTrips() {
        CaptureLayer.entries.forEach { layer ->
            val started = Samples.requestStarted.copy(capture_layer = layer)
            assertEquals(layer, RequestStarted.ADAPTER.decode(started.encode()).capture_layer)
        }
    }

    @Test
    fun captureLayerFromANewerPeerIsUnspecified() {
        // Field 8, varint 9: a layer this schema does not know.
        val newer = Samples.requestStarted.copy(capture_layer = CaptureLayer.CAPTURE_LAYER_UNSPECIFIED).encode() + byteArrayOf(0x40, 0x09)
        val decoded = RequestStarted.ADAPTER.decode(newer)
        assertEquals(CaptureLayer.CAPTURE_LAYER_UNSPECIFIED, decoded.capture_layer)
        assertEquals(Samples.requestStarted.url, decoded.url)
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
