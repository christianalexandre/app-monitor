package appmonitor.tools.fakeclient

import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/** Accepts one WebSocket upgrade, optionally reads the first byte of the Hello, then drops the TCP connection with no close frame. */
internal class DroppingPeer(
    readHello: Boolean,
) {
    private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort

    private val peer =
        thread {
            socket.use { listening ->
                listening.accept().use { connection ->
                    val request = connection.getInputStream().bufferedReader().readUpgradeRequest()
                    val key = request.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                    connection.getOutputStream().write(upgradeResponse(key).toByteArray())
                    connection.getOutputStream().flush()
                    if (readHello) connection.getInputStream().read()
                }
            }
        }

    fun join() = peer.join()

    private fun java.io.BufferedReader.readUpgradeRequest(): List<String> =
        generateSequence { readLine() }.takeWhile { it.isNotEmpty() }.toList()

    private fun upgradeResponse(key: String): String {
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest("$key$WEBSOCKET_GUID".toByteArray()))
        return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
    }

    private companion object {
        // RFC 6455 section 1.3.
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
