package appmonitor.sdk.ktor

import appmonitor.v1.WebSocketFrame.Direction
import appmonitor.v1.WebSocketFrame.Direction.DIRECTION_RECEIVED
import io.ktor.utils.io.InternalAPI
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** An engine's `incoming` closed with a cause after the peer's close: the reader still records that close once. */
class PeerCloseTest {
    private class Broken : Exception("connection reset")

    private val reason = CloseReason(1000, "bye")
    private val text = Frame.Text("hello")

    private open class RawSession(
        override val incoming: ReceiveChannel<Frame>,
    ) : WebSocketSession {
        override val coroutineContext: CoroutineContext = Job()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()

        override suspend fun flush() = Unit

        @Deprecated("Use cancel() instead.", level = DeprecationLevel.ERROR)
        override fun terminate() = Unit
    }

    private class DefaultSession(
        incoming: ReceiveChannel<Frame>,
        override val closeReason: Deferred<CloseReason?>,
    ) : RawSession(incoming),
        DefaultWebSocketSession {
        override var pingIntervalMillis = 0L
        override var timeoutMillis = 0L

        @InternalAPI
        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
    }

    /** `incoming` holds [frames], then is closed with [Broken]. */
    private fun incoming(vararg frames: Frame): ReceiveChannel<Frame> =
        Channel<Frame>(Channel.UNLIMITED).apply {
            frames.forEach { trySend(it) }
            close(Broken())
        }

    private fun recorded(
        session: () -> WebSocketSession,
        read: suspend (ReceiveChannel<Frame>) -> Unit,
    ): List<Frame> {
        val frames = mutableListOf<Frame>()
        val record: FrameRecorder = { frame: Frame, direction: Direction ->
            assertEquals(DIRECTION_RECEIVED, direction)
            frames += frame
        }
        val capturing = session().capturing(record)
        runBlocking {
            assertFailsWith<Broken> { read(capturing.incoming) }
            assertFailsWith<Broken> { capturing.incoming.receive() }
        }
        return frames
    }

    private val byReceive: suspend (ReceiveChannel<Frame>) -> Unit = { while (true) it.receive() }
    private val byFor: suspend (ReceiveChannel<Frame>) -> Unit = { for (frame in it) Unit }

    private fun defaultSession(): () -> WebSocketSession = { DefaultSession(incoming(text), CompletableDeferred<CloseReason?>(reason)) }

    private fun rawSession(): () -> WebSocketSession = { RawSession(incoming(text, Frame.Close(reason))) }

    private fun assertOneClose(frames: List<Frame>) {
        assertEquals(listOf(text.frameType, Frame.Close(reason).frameType), frames.map { it.frameType })
        assertEquals(reason, (frames[1] as Frame.Close).readReason())
    }

    @Test
    fun aDefaultSessionRecordsThePeersCloseByReceive() = assertOneClose(recorded(defaultSession(), byReceive))

    @Test
    fun aDefaultSessionRecordsThePeersCloseByFor() = assertOneClose(recorded(defaultSession(), byFor))

    @Test
    fun aRawSessionRecordsThePeersCloseByReceive() = assertOneClose(recorded(rawSession(), byReceive))

    @Test
    fun aRawSessionRecordsThePeersCloseByFor() = assertOneClose(recorded(rawSession(), byFor))
}
