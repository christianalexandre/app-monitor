package appmonitor.sdk.ktor

import appmonitor.v1.WebSocketFrame.Direction
import io.ktor.util.AttributeKey
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

/** The request id of the hop a WebSocket upgrade was captured under; its frames carry it. */
internal val REQUEST_ID: AttributeKey<String> = AttributeKey("AppMonitorRequestId")

/** Records a frame as it crosses the session. */
internal typealias FrameRecorder = (Frame, Direction) -> Unit

/**
 * The engine's session with each frame recorded as it crosses (ADR-0070): a raw one (CIO, Darwin) before Ktor's
 * `DefaultWebSocketSession` wraps it, sees every frame; OkHttp's, already a default one, sees what the app does.
 */
internal fun WebSocketSession.capturing(record: FrameRecorder): WebSocketSession =
    if (this is DefaultWebSocketSession) {
        CapturingDefaultSession(this, SessionRecorder(record, engineAnswersClose = true))
    } else {
        CapturingRawSession(this, SessionRecorder(record, engineAnswersClose = false))
    }

/**
 * RFC 6455 5.5.1: one close is sent per session, so a later one (`webSocket { }` closes on exit) never reaches
 * the wire and is not recorded; with [engineAnswersClose], the engine sends the answer to the peer's close.
 */
private class SessionRecorder(
    private val record: FrameRecorder,
    private val engineAnswersClose: Boolean,
) {
    private val closeSent = atomic(false)

    fun received(frame: Frame) {
        if (frame is Frame.Close && engineAnswersClose) closeSent.value = true
        record(frame, Direction.DIRECTION_RECEIVED)
    }

    fun sent(frame: Frame) {
        if (frame is Frame.Close && closeSent.getAndSet(true)) return
        record(frame, Direction.DIRECTION_SENT)
    }
}

private class CapturingRawSession(
    session: WebSocketSession,
    recorder: SessionRecorder,
) : WebSocketSession by session {
    override val incoming: ReceiveChannel<Frame> = RecordingReceiveChannel(session.incoming, recorder) { null }
    override val outgoing: SendChannel<Frame> = RecordingSendChannel(session.outgoing, recorder)

    // Delegated, `send` would write to the engine's channel, past the recording one.
    override suspend fun send(frame: Frame) = outgoing.send(frame)
}

private class CapturingDefaultSession(
    session: DefaultWebSocketSession,
    recorder: SessionRecorder,
) : DefaultWebSocketSession by session {
    // The peer's close never reaches `incoming` here; `closeReason`, complete before `incoming` ends, is what it said.
    @OptIn(ExperimentalCoroutinesApi::class)
    override val incoming: ReceiveChannel<Frame> =
        RecordingReceiveChannel(session.incoming, recorder) {
            if (session.closeReason.isCompleted) {
                runCatching { session.closeReason.getCompleted() }.getOrNull()?.let {
                    Frame.Close(
                        it,
                    )
                }
            } else {
                null
            }
        }
    override val outgoing: SendChannel<Frame> = RecordingSendChannel(session.outgoing, recorder)

    override suspend fun send(frame: Frame) = outgoing.send(frame)
}

/**
 * A frame is recorded as the reader takes it, before it is handed on, so an answer is never recorded before its
 * question; [peerClose] once, when the reader first sees the end. `onReceive` in a `select` is not recorded.
 */
private class RecordingReceiveChannel(
    private val channel: ReceiveChannel<Frame>,
    private val recorder: SessionRecorder,
    private val peerClose: () -> Frame.Close?,
) : ReceiveChannel<Frame> by channel {
    private val ended = atomic(false)

    private fun taken(frame: Frame): Frame = frame.also { recorder.received(it) }

    private fun ended() {
        if (!ended.getAndSet(true)) peerClose()?.let { recorder.received(it) }
    }

    private fun taken(result: ChannelResult<Frame>): ChannelResult<Frame> =
        result.also {
            val frame = it.getOrNull()
            if (frame != null) {
                taken(frame)
            } else if (it.isClosed) {
                ended()
            }
        }

    override suspend fun receive(): Frame =
        try {
            taken(channel.receive())
        } catch (e: ClosedReceiveChannelException) {
            ended()
            throw e
        }

    override suspend fun receiveCatching(): ChannelResult<Frame> = taken(channel.receiveCatching())

    override fun tryReceive(): ChannelResult<Frame> = taken(channel.tryReceive())

    override fun iterator(): ChannelIterator<Frame> {
        val iterator = channel.iterator()
        return object : ChannelIterator<Frame> {
            override suspend fun hasNext(): Boolean = iterator.hasNext().also { if (!it) ended() }

            override fun next(): Frame = taken(iterator.next())
        }
    }
}

/** A frame the engine's channel no longer takes is not recorded. */
private class RecordingSendChannel(
    private val channel: SendChannel<Frame>,
    private val recorder: SessionRecorder,
) : SendChannel<Frame> by channel {
    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun send(element: Frame) {
        if (!channel.isClosedForSend) recorder.sent(element)
        channel.send(element)
    }

    override fun trySend(element: Frame): ChannelResult<Unit> = channel.trySend(element).also { if (it.isSuccess) recorder.sent(element) }
}
