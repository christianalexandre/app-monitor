package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointRules
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A hit the buffer drops while the desktop stops reading its socket (ADR-0111 B.1). */
@OptIn(InternalAppMonitorApi::class)
class StalledDesktopBreakpointTest {
    private val server =
        ServerSocket().apply {
            receiveBufferSize = 4096
            bind(java.net.InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))
        }
    private val accepted = mutableListOf<Socket>()

    @AfterTest
    fun tearDown() {
        accepted.forEach { runCatching { it.close() } }
        server.close()
    }

    @Test
    fun aHitDroppedFromAStalledBufferGoesOnUnchanged() =
        runBlocking {
            thread(isDaemon = true) { stallAfterRules(server.accept().also { accepted += it }) }
            val hello =
                Hello(
                    protocol_version = Protocol.VERSION,
                    platform = Platform.PLATFORM_JVM,
                    device_id = "device-1",
                    channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION),
                )
            val config = ClientConfig(pingInterval = 60.seconds, bufferCount = 2)
            val client = MonitorClient({ hello }, { Endpoint(LOOPBACK, server.localPort) }, config, {})
            client.start()
            try {
                withTimeout(5.seconds) {
                    while (client.breakpoints.match("GET", "https://api.example.com/a", REQUEST) == null) delay(10)
                }
                val noise = Envelope(Protocol.CHANNEL_NETWORK, "Noise", ByteArray(64 shl 10).toByteString())
                withTimeout(30.seconds) {
                    while (client.dropped == 0L) {
                        client.send(noise)
                        delay(1.milliseconds)
                    }
                }
                val hit = BreakpointHit(rule_id = 1, stage = REQUEST, request = RequestStarted(request_id = "r1"), timeout_ms = 60_000)

                val outcome = async(start = CoroutineStart.UNDISPATCHED) { client.hold(hit) }
                repeat(2) { client.send(noise) }

                assertSame(BreakpointOutcome.UNCHANGED, withTimeout(5.seconds) { outcome.await() })
            } finally {
                client.stopAndJoin()
            }
        }

    // The upgrade, `HelloAck` and one breakpoint; then the socket is never read again.
    private fun stallAfterRules(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
        val key = Regex("(?i)sec-websocket-key: *(\\S+)").find(head)!!.groupValues[1]
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()))
        val out = socket.getOutputStream()
        out.write(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                .toByteArray(),
        )
        skipFrame(input)
        val ack =
            HelloAck(
                protocol_version = Protocol.VERSION,
                session_id = "s1",
                channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION),
            )
        out.frame(Envelope(Protocol.CHANNEL_CORE, "HelloAck", ack.encodeByteString()).encode())
        val rule = BreakpointRule(id = 1, host = "api.example.com", path = "/a", request = true, timeout_ms = 60_000)
        out.frame(Envelope(Protocol.CHANNEL_INTERACTION, "BreakpointRules", BreakpointRules(listOf(rule)).encodeByteString()).encode())
        out.flush()
    }

    private fun skipFrame(input: DataInputStream) {
        input.readUnsignedByte()
        val length =
            when (val short = input.readUnsignedByte() and 0x7f) {
                126 -> input.readUnsignedShort().toLong()
                127 -> input.readLong()
                else -> short.toLong()
            }
        input.skipNBytes(4 + length)
    }

    private fun OutputStream.frame(payload: ByteArray) {
        write(0x82)
        if (payload.size < 126) {
            write(payload.size)
        } else {
            write(126)
            write(payload.size shr 8)
            write(payload.size and 0xff)
        }
        write(payload)
    }

    private companion object {
        val REQUEST = BreakpointStage.BREAKPOINT_STAGE_REQUEST
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC11B85"
    }
}
