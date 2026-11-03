package appmonitor.desktop.app

import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.protocol.Protocol
import appmonitor.v1.DeliveryReport
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

// ADR-0172: the previous socket closes with the app idle, so no envelope carries the settled count.
class LateLostCountTest {
    @Test
    fun `a lost count settled while the device is idle reaches the live list`() =
        withStorage { storage ->
            runBlocking {
                val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
                val live = LiveTraffic()
                val client = HttpClient(CIO) { install(WebSockets) }
                try {
                    val port = server.start()
                    val collecting = launch(Dispatchers.Default) { TrafficStore(storage, live).collect(server.sessions) }
                    withTimeout(TIMEOUT_MS) {
                        val firstId = CompletableDeferred<String>()
                        val leaveFirst = CompletableDeferred<Unit>()
                        val leaveSecond = CompletableDeferred<Unit>()
                        val socket = {
                            previous: String,
                            written: Long,
                            sessionId: CompletableDeferred<String>?,
                            leave: CompletableDeferred<Unit>,
                            ->
                            launch {
                                client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
                                    val hello =
                                        Hello(
                                            protocol_version = Protocol.VERSION,
                                            device_id = "d",
                                            channels = listOf(Protocol.CHANNEL_CORE),
                                        )
                                    send(Frame.Binary(true, Envelope(Protocol.CHANNEL_CORE, "Hello", hello.encodeByteString(), 1).encode()))
                                    val ack = Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes())
                                    sessionId?.complete(HelloAck.ADAPTER.decode(ack.payload).session_id)
                                    val report = DeliveryReport(previous_session_id = previous, previous_session_written = written)
                                    send(
                                        Frame.Binary(
                                            true,
                                            Envelope(Protocol.CHANNEL_CORE, "DeliveryReport", report.encodeByteString(), 2).encode(),
                                        ),
                                    )
                                    leave.await()
                                }
                            }
                        }
                        val first = socket("", 0, firstId, leaveFirst)
                        val previous = firstId.await()
                        live.observeSessions().first { sessions -> sessions.any { it.drops?.lost == 0L } }
                        val second = socket(previous, 3, null, leaveSecond)
                        live.observeSessions().first { sessions -> sessions.count { it.drops != null } == 2 }

                        leaveFirst.complete(Unit)
                        first.join()

                        // The first report's frame was read, so two of the three were lost.
                        assertEquals(
                            2L,
                            live
                                .observeSessions()
                                .first { sessions ->
                                    sessions.any { it.drops?.lost == 2L }
                                }.mapNotNull { it.drops?.lost }
                                .max(),
                        )
                        leaveSecond.complete(Unit)
                        second.join()
                    }
                    collecting.cancel()
                } finally {
                    client.close()
                    server.stop()
                }
            }
        }
}
