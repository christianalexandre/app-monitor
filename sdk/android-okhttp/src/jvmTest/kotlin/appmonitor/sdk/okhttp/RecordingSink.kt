package appmonitor.sdk.okhttp

import appmonitor.protocol.Protocol
import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.Envelope
import appmonitor.v1.MockRule
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Records envelopes as the running monitor would buffer them. */
internal class RecordingSink(
    override val options: AppMonitorOptions? = AppMonitorOptions(),
    private val mocks: List<MockRule> = emptyList(),
) : CaptureSink {
    private val envelopes = LinkedBlockingQueue<Envelope>()

    override fun send(envelope: Envelope) {
        assertEquals(Protocol.CHANNEL_NETWORK, envelope.channel)
        envelopes.put(envelope)
    }

    // ADR-0110 B.1's matching is sdk/core's; a rule here answers the path it names on any host.
    override fun mockFor(
        method: String,
        url: String,
    ): MockRule? = mocks.firstOrNull { url.substringAfter("://").substringAfter('/').substringBefore('?') == it.path.removePrefix("/") }

    /** What [keepForReplay] kept, by request id. */
    val replays: MutableMap<String, () -> String?> = java.util.concurrent.ConcurrentHashMap()

    override fun keepForReplay(
        requestId: String,
        replay: () -> String?,
    ) {
        replays[requestId] = replay
    }

    val pending: Int get() = envelopes.size

    fun next(): Envelope = assertNotNull(envelopes.poll(5, TimeUnit.SECONDS), "no envelope within 5 s")

    fun started(): RequestStarted = next().decoded("RequestStarted", RequestStarted.ADAPTER::decode)

    fun received(): ResponseReceived = next().decoded("ResponseReceived", ResponseReceived.ADAPTER::decode)

    fun failed(): RequestFailed = next().decoded("RequestFailed", RequestFailed.ADAPTER::decode)

    fun frame(): WebSocketFrame = next().decoded("WebSocketFrame", WebSocketFrame.ADAPTER::decode)

    private fun <T> Envelope.decoded(
        type: String,
        decode: (okio.ByteString) -> T,
    ): T {
        assertEquals(type, this.type)
        return decode(payload)
    }
}
