package appmonitor.sdk.ktor

import appmonitor.protocol.Protocol
import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.Envelope
import appmonitor.v1.MockRule
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import okio.ByteString
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records envelopes as the running monitor would buffer them; every event is sent before the call returns. */
internal class RecordingSink(
    override var options: AppMonitorOptions? = AppMonitorOptions(),
    private val mocks: List<MockRule> = emptyList(),
) : CaptureSink {
    private val envelopes = ArrayDeque<Envelope>()

    override fun send(envelope: Envelope) {
        assertEquals(Protocol.CHANNEL_NETWORK, envelope.channel)
        envelopes.addLast(envelope)
    }

    // ADR-0110 B.1's matching is sdk/core's; a rule here answers the path it names on any host.
    override fun mockFor(
        method: String,
        url: String,
    ): MockRule? = mocks.firstOrNull { url.substringAfter("://").substringAfter('/').substringBefore('?') == it.path.removePrefix("/") }

    /** What [keepForReplay] kept, by request id. */
    val replays: MutableMap<String, () -> String?> = mutableMapOf()

    override fun keepForReplay(
        requestId: String,
        replay: () -> String?,
    ) {
        replays[requestId] = replay
    }

    val pending: Int get() = envelopes.size

    fun next(): Envelope {
        assertTrue(envelopes.isNotEmpty(), "no envelope recorded")
        return envelopes.removeFirst()
    }

    fun started(): RequestStarted = next().decoded("RequestStarted", RequestStarted.ADAPTER::decode)

    fun received(): ResponseReceived = next().decoded("ResponseReceived", ResponseReceived.ADAPTER::decode)

    fun failed(): RequestFailed = next().decoded("RequestFailed", RequestFailed.ADAPTER::decode)

    private fun <T> Envelope.decoded(
        type: String,
        decode: (ByteString) -> T,
    ): T {
        assertEquals(type, this.type)
        return decode(payload)
    }
}
