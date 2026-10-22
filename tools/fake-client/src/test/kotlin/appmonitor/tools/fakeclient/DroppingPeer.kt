package appmonitor.tools.fakeclient

import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/** Accepts one WebSocket upgrade, optionally reads the Hello and sends [reply], then drops the TCP connection with no close frame. */
internal class DroppingPeer(
    readHello: Boolean,
    reply: ByteArray? = null,
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
                    if (reply != null) {
                        connection.getInputStream().skipFrame()
                        connection.getOutputStream().write(binaryFrame(reply))
                        connection.getOutputStream().flush()
                    } else if (readHello) {
                        connection.getInputStream().read()
                    }
                }
            }
        }

    fun join() = peer.join()

    private fun java.io.BufferedReader.readUpgradeRequest(): List<String> =
        generateSequence { readLine() }.takeWhile { it.isNotEmpty() }.toList()

    // A client frame is masked: header, extended length, 4-byte mask, payload (RFC 6455 section 5.2).
    private fun InputStream.skipFrame() {
        read()
        val length =
            when (val short = read() and 0x7F) {
                126 -> DataInputStream(this).readUnsignedShort().toLong()
                127 -> DataInputStream(this).readLong()
                else -> short.toLong()
            }
        skipNBytes(MASK_BYTES + length)
    }

    private fun binaryFrame(payload: ByteArray): ByteArray {
        require(payload.size < 126)
        return byteArrayOf(0x82.toByte(), payload.size.toByte()) + payload
    }

    private fun upgradeResponse(key: String): String {
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest("$key$WEBSOCKET_GUID".toByteArray()))
        return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
    }

    private companion object {
        // RFC 6455 section 1.3.
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val MASK_BYTES = 4L
    }
}
