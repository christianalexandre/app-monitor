package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import appmonitor.v1.MockFailure
import appmonitor.v1.MockRule
import appmonitor.v1.MockRules
import appmonitor.v1.Platform
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The `interaction` channel as the device reads it (ADR-0110 A.1, ADR-0113 A.1). */
class MonitorClientMocksTest {
    private val hello =
        Hello(
            protocol_version = Protocol.VERSION,
            platform = Platform.PLATFORM_JVM,
            device_id = "device-1",
            channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION),
        )
    private val config = ClientConfig(backoffInitial = 50.milliseconds, backoffMax = 200.milliseconds)
    private val cleanup = mutableListOf<suspend () -> Unit>()

    @AfterTest
    fun tearDown() =
        runBlocking {
            cleanup.asReversed().forEach { it() }
        }

    @Test
    fun theLastRuleSetReceivedAnswersUntilTheSessionEnds() =
        runBlocking {
            val sets = Channel<MockRules>(Channel.UNLIMITED)
            val desktop = desktop(sets)
            val client = client(desktop.port)
            sets.send(MockRules(listOf(rule(1, "/a"))))
            client.awaitMock("/a", 1)
            sets.send(MockRules(listOf(rule(2, "/b"))))
            client.awaitMock("/b", 2)
            assertNull(client.mocks.match("GET", "https://api.example.com/a"))
            desktop.stop()
            client.awaitState(ClientState.BackingOff)
            assertNull(client.mocks.match("GET", "https://api.example.com/b"))
        }

    @Test
    fun anEmptySetClearsEveryRule() =
        runBlocking {
            val sets = Channel<MockRules>(Channel.UNLIMITED)
            val client = client(desktop(sets).port)
            sets.send(MockRules(listOf(rule(1, "/a"))))
            client.awaitMock("/a", 1)
            sets.send(MockRules())
            withTimeout(5.seconds) { while (client.mocks.match("GET", "https://api.example.com/a") != null) delay(10) }
        }

    @Test
    fun rulesOnAChannelTheDesktopDidNotAcceptAreIgnored() =
        runBlocking {
            val sets = Channel<MockRules>(Channel.UNLIMITED)
            val client = client(desktop(sets, accepted = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK)).port)
            client.awaitState(ClientState.Connected)
            sets.send(MockRules(listOf(rule(1, "/a"))))
            sets.send(MockRules(listOf(rule(2, "/b"))))
            delay(300)
            assertNull(client.mocks.match("GET", "https://api.example.com/a"))
            assertEquals(ClientState.Connected, client.state.value)
        }

    @Test
    fun aMalformedRuleSetIsIgnored() =
        runBlocking {
            val sets = Channel<MockRules>(Channel.UNLIMITED)
            val desktop =
                desktop(sets) {
                    send(
                        Frame.Binary(
                            true,
                            Envelope(
                                channel = Protocol.CHANNEL_INTERACTION,
                                type = "MockRules",
                                payload = okio.ByteString.of(0x0A, 0x7F),
                            ).encode(),
                        ),
                    )
                    send(Frame.Binary(true, byteArrayOf(0x01)))
                }
            val client = client(desktop.port)
            sets.send(MockRules(listOf(rule(1, "/a"))))
            client.awaitMock("/a", 1)
        }

    private suspend fun MonitorClient.awaitMock(
        path: String,
        id: Long,
    ) {
        withTimeout(5.seconds) { while (mocks.match("GET", "https://api.example.com$path")?.id != id) delay(10) }
    }

    private fun rule(
        id: Long,
        path: String,
    ) = MockRule(id = id, host = "api.example.com", path = path, failure = MockFailure("mocked"))

    /** Accepts [accepted], then sends each set of [sets] as one `MockRules` envelope, after [before]. */
    private suspend fun desktop(
        sets: Channel<MockRules>,
        accepted: List<String>? = null,
        before: suspend WebSocketSession.() -> Unit = {},
    ): StandInDesktop {
        val desktop =
            StandInDesktop.start {
                var hello: Hello? = null
                while (hello == null) {
                    val frame = incoming.receive() as? Frame.Binary ?: continue
                    hello = Hello.ADAPTER.decode(Envelope.ADAPTER.decode(frame.readBytes()).payload)
                }
                val ack = HelloAck(protocol_version = Protocol.VERSION, session_id = "stand-in", channels = accepted ?: hello.channels)
                send(
                    Frame.Binary(
                        true,
                        Envelope(channel = Protocol.CHANNEL_CORE, type = "HelloAck", payload = ack.encodeByteString()).encode(),
                    ),
                )
                before()
                for (set in sets) {
                    val envelope = Envelope(channel = Protocol.CHANNEL_INTERACTION, type = "MockRules", payload = set.encodeByteString())
                    send(Frame.Binary(true, envelope.encode()))
                }
                awaitCancellation()
            }
        cleanup += { runCatching { desktop.stop() } }
        return desktop
    }

    private fun client(port: Int): MonitorClient {
        val client = MonitorClient({ hello }, { Endpoint(LOOPBACK, port) }, config, {})
        cleanup += { client.stopAndJoin() }
        client.start()
        return client
    }
}
