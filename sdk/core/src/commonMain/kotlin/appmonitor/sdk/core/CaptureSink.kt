package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import appmonitor.v1.MockRule
import appmonitor.v1.RequestFailed
import com.squareup.wire.Message
import okio.ByteString
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Where a capture module's events go: the running monitor, or a test double. */
@InternalAppMonitorApi
public interface CaptureSink {
    /** Null when nothing should be captured. */
    public val options: AppMonitorOptions?

    public fun send(envelope: Envelope)

    /** The desktop's rule answering this call (ADR-0110 B.1), or null. */
    public fun mockFor(
        method: String,
        url: String,
    ): MockRule? = null

    /** Keeps [replay], holding [bodyBytes] of body, for the desktop's `ReplayRequest` of [requestId] (ADR-0111 D.1); null once sent, else why not. */
    public fun keepForReplay(
        requestId: String,
        bodyBytes: Long,
        replay: () -> String?,
    ): Unit = Unit

    /** The desktop's breakpoint holding [stage] of this call (ADR-0118 A.1), or null. */
    public fun breakpointFor(
        method: String,
        url: String,
        stage: BreakpointStage,
    ): BreakpointRule? = null

    /** Sends [hit] and waits for the desktop's answer, at most its timeout (ADR-0111 B.1). */
    public suspend fun hold(hit: BreakpointHit): BreakpointOutcome = BreakpointOutcome.UNCHANGED
}

@InternalAppMonitorApi
public object MonitorSink : CaptureSink {
    override val options: AppMonitorOptions? get() = MonitorBridge.options

    override fun send(envelope: Envelope): Unit = MonitorBridge.send(envelope)

    override fun mockFor(
        method: String,
        url: String,
    ): MockRule? = MonitorBridge.mockFor(method, url)

    override fun keepForReplay(
        requestId: String,
        bodyBytes: Long,
        replay: () -> String?,
    ): Unit = MonitorBridge.keepForReplay(requestId, bodyBytes, replay)

    override fun breakpointFor(
        method: String,
        url: String,
        stage: BreakpointStage,
    ): BreakpointRule? = MonitorBridge.breakpointFor(method, url, stage)

    override suspend fun hold(hit: BreakpointHit): BreakpointOutcome = MonitorBridge.hold(hit)
}

/** A body as sent on the wire (ADR-0020): [size] null when unknown. */
@InternalAppMonitorApi
public class CapturedBody(
    public val bytes: ByteString,
    public val truncated: Boolean,
    public val size: Long?,
) {
    @InternalAppMonitorApi
    public companion object {
        public val NONE: CapturedBody = CapturedBody(ByteString.EMPTY, truncated = false, size = null)
        public val EMPTY: CapturedBody = CapturedBody(ByteString.EMPTY, truncated = false, size = 0)

        /** A body known whole: a mock's, or one an edit replaced. */
        public fun whole(bytes: ByteString): CapturedBody = CapturedBody(bytes, truncated = false, size = bytes.size.toLong())
    }
}

@InternalAppMonitorApi
public fun newRequestId(): String = Uuid.random().toString()

@InternalAppMonitorApi
public fun requestFailed(
    id: String,
    error: Throwable,
): RequestFailed = RequestFailed(request_id = id, error = error.toString())

/** Wraps [message] for the `network` channel as [type]; capture never throws into the app's call. */
@InternalAppMonitorApi
public fun CaptureSink.emit(
    type: String,
    message: () -> Message<*, *>,
) {
    val envelope =
        try {
            Envelope(
                channel = Protocol.CHANNEL_NETWORK,
                type = type,
                payload = message().encodeByteString(),
                timestamp_ms = Clock.System.now().toEpochMilliseconds(),
            )
        } catch (e: Exception) {
            return
        }
    send(envelope)
}
