package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DeviceApprovalTest {
    private val client = HttpClient(CIO) { install(WebSockets) }
    private val approvals = DeviceApprovals()
    private var server: AppMonitorServer? = null

    @AfterTest
    fun tearDown() {
        client.close()
        runBlocking { server?.stop() }
    }

    @Test
    fun `a pending device gets no HelloAck until approved`() =
        runBlocking {
            val port = start()
            withTimeout(TIMEOUT) {
                val session = async { server!!.sessions.first() }
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    assertEquals(listOf(DeviceKey("device-1", "app.a")), approvals.pending.first { it.isNotEmpty() }.map { it.key })
                    assertNull(withTimeoutOrNull(300.milliseconds) { incoming.receive() })

                    approvals.decide(DeviceKey("device-1", "app.a"), approved = true)
                    val ack = Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes())
                    assertEquals("HelloAck", ack.type)
                    assertEquals(emptyList(), approvals.pending.value)
                }
                assertEquals("device-1", session.await().hello.device_id)
            }
        }

    @Test
    fun `an approved device and app reconnect without asking again`() =
        runBlocking {
            val port = start()
            approvals.decide(DeviceKey("device-1", "app.a"), approved = true)
            withTimeout(TIMEOUT) {
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    assertEquals("HelloAck", Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes()).type)
                }
            }
            assertEquals(emptyList(), approvals.pending.value)
        }

    @Test
    fun `a rejected device is closed with 1008, and at once when it comes back`() =
        runBlocking {
            val port = start()
            withTimeout(TIMEOUT) {
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    approvals.pending.first { it.isNotEmpty() }
                    approvals.decide(DeviceKey("device-1", "app.a"), approved = false)
                    assertNull(incoming.receiveCatching().getOrNull())
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY, closeReason.await()?.knownReason)
                }
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    assertNull(incoming.receiveCatching().getOrNull())
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY, closeReason.await()?.knownReason)
                }
            }
            assertEquals(emptyList(), approvals.pending.value)
        }

    @Test
    fun `remembered answers are given without asking`() =
        runBlocking {
            val remembered =
                DeviceApprovals(mapOf(DeviceKey("device-1", "app.a") to true, DeviceKey("device-1", "app.b") to false))
            withTimeout(TIMEOUT) {
                assertTrue(remembered.approve(hello.toHello()))
                assertFalse(remembered.approve(hello(appId = "app.b").toHello()))
            }
            assertEquals(emptyList(), remembered.pending.value)
        }

    @Test
    fun `a forgotten device is closed with 1008 and asked again`() =
        runBlocking<Unit> {
            val port = start()
            val key = DeviceKey("device-1", "app.a")
            approvals.decide(key, approved = true)
            withTimeout(TIMEOUT) {
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    assertEquals("HelloAck", Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes()).type)
                    assertEquals(true, approvals.forget(key))
                    server!!.disconnect(key)
                    assertNull(incoming.receiveCatching().getOrNull())
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY, closeReason.await()?.knownReason)
                }
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    assertEquals(
                        key,
                        approvals.pending
                            .first { it.isNotEmpty() }
                            .single()
                            .key,
                    )
                }
            }
        }

    @Test
    fun `a pair forgotten between its answer and HelloAck is closed with 1008 and never published`() =
        runBlocking {
            val key = DeviceKey("device-1", "app.a")
            val forgetting =
                DeviceApproval {
                    approvals.decide(key, approved = true)
                    approvals.forget(key)
                    server!!.disconnect(key)
                    true
                }
            val port = start(forgetting)
            withTimeout(TIMEOUT) {
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    assertNull(incoming.receiveCatching().getOrNull())
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY, closeReason.await()?.knownReason)
                }
            }
            server!!.stop()
            assertNull(server!!.sessions.firstOrNull())
        }

    @Test
    fun `forgetAll drops every answer`() {
        approvals.decide(DeviceKey("device-1", "app.a"), approved = true)
        approvals.decide(DeviceKey("device-2", "app.a"), approved = false)
        assertEquals(
            mapOf(DeviceKey("device-1", "app.a") to true, DeviceKey("device-2", "app.a") to false),
            approvals.forgetAll(),
        )
        assertNull(approvals.forget(DeviceKey("device-1", "app.a")))
    }

    @Test
    fun `another app on an approved device still asks`() =
        runBlocking {
            val port = start()
            approvals.decide(DeviceKey("device-1", "app.a"), approved = true)
            withTimeout(TIMEOUT) {
                connect(port) {
                    send(Frame.Binary(true, hello(appId = "app.b").encode()))
                    assertEquals(
                        DeviceKey("device-1", "app.b"),
                        approvals.pending
                            .first { it.isNotEmpty() }
                            .single()
                            .key,
                    )
                }
            }
        }

    @Test
    fun `a device that leaves while pending is withdrawn`() =
        runBlocking<Unit> {
            val port = start()
            withTimeout(TIMEOUT) {
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    approvals.pending.first { it.isNotEmpty() }
                }
                approvals.pending.first { it.isEmpty() }
            }
        }

    @Test
    fun `two connections of one pending device share the request and both get HelloAck`() =
        runBlocking {
            val port = start()
            withTimeout(TIMEOUT) {
                val first =
                    async {
                        connect(port) {
                            send(Frame.Binary(true, hello.encode()))
                            Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes()).type
                        }
                    }
                val second =
                    async {
                        connect(port) {
                            send(Frame.Binary(true, hello.encode()))
                            Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes()).type
                        }
                    }
                approvals.pending.first { it.isNotEmpty() }
                delay(200.milliseconds)
                assertEquals(1, approvals.pending.value.size)
                approvals.decide(DeviceKey("device-1", "app.a"), approved = true)
                assertEquals(listOf("HelloAck", "HelloAck"), listOf(first.await(), second.await()))
            }
        }

    @Test
    fun `an envelope before HelloAck closes with 1002 and withdraws the request`() =
        runBlocking<Unit> {
            val port = start()
            withTimeout(TIMEOUT) {
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    approvals.pending.first { it.isNotEmpty() }
                    send(Frame.Binary(true, hello.encode()))
                    assertNull(incoming.receiveCatching().getOrNull())
                    assertEquals(CloseReason.Codes.PROTOCOL_ERROR, closeReason.await()?.knownReason)
                }
                approvals.pending.first { it.isEmpty() }
            }
        }

    @Test
    fun `stop while a device is pending returns and closes it`() =
        runBlocking {
            val port = start()
            withTimeout(TIMEOUT) {
                connect(port) {
                    send(Frame.Binary(true, hello.encode()))
                    approvals.pending.first { it.isNotEmpty() }
                    server!!.stop()
                    assertNull(incoming.receiveCatching().getOrNull())
                }
                approvals.pending.first { it.isEmpty() }
            }
            assertTrue(approvals.pending.value.isEmpty())
        }

    private suspend fun start(approval: DeviceApproval = approvals): Int =
        AppMonitorServer(
            ServerConfig(host = HOST, port = 0, advertise = false),
            approval = approval,
        ) { _, _ -> error("not advertising") }.also { server = it }.start()

    private suspend fun <T> connect(
        port: Int,
        block: suspend DefaultClientWebSocketSession.() -> T,
    ): T {
        var result: Result<T>? = null
        client.webSocket(host = HOST, port = port, path = Protocol.WEBSOCKET_PATH) {
            result = runCatching { block() }
        }
        return result!!.getOrThrow()
    }

    private companion object {
        const val HOST = "127.0.0.1"
        val TIMEOUT = 10.seconds

        val hello = hello()

        fun hello(appId: String = "app.a") =
            Envelope(
                channel = Protocol.CHANNEL_CORE,
                type = "Hello",
                payload =
                    Hello(
                        protocol_version = Protocol.VERSION,
                        platform = Platform.PLATFORM_JVM,
                        device_id = "device-1",
                        device_name = "Pixel 9",
                        app_id = appId,
                        channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
                    ).encode().toByteString(),
                timestamp_ms = 1L,
            )

        fun Envelope.toHello(): Hello = Hello.ADAPTER.decode(payload)
    }
}
