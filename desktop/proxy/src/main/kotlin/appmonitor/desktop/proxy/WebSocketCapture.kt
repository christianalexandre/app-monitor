package appmonitor.desktop.proxy

import appmonitor.v1.WebSocketFrame.Opcode
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponse
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads the frames of one WebSocket the proxy relays after its `101` (RFC 6455) without consuming the relayed bytes,
 * and sends [capture] each message, as one frame, and each control frame (ADR-0193). Messages compressed with
 * `permessage-deflate` (RFC 7692) are inflated. A direction that stops parsing is relayed and no longer captured.
 * Event loop only.
 */
internal class WebSocketCapture private constructor(
    private val capture: Capture,
    deflate: Boolean,
) {
    private val fromClient = Direction(sent = true, deflate)
    private val fromOrigin = Direction(sent = false, deflate)
    private var closeSeen = false
    private var over = false

    /** Bytes the client sent toward the origin; [bytes]' indexes are left as they are. */
    fun client(bytes: ByteBuf) = fromClient.read(bytes)

    /** Bytes the origin sent toward the client; [bytes]' indexes are left as they are. */
    fun origin(bytes: ByteBuf) = fromOrigin.read(bytes)

    /** The connection closed: the exchange ends as failed when no close frame crossed either way. Idempotent. */
    fun ended() {
        if (over) return
        over = true
        fromClient.release()
        fromOrigin.release()
        if (closeSeen) capture.closed() else capture.failed(NO_CLOSE)
    }

    private inner class Direction(
        private val sent: Boolean,
        deflate: Boolean,
    ) {
        private var inflater: Inflater? = if (deflate) Inflater(true) else null
        private val header = ByteArray(MAX_HEADER)
        private var headerRead = 0
        private var inPayload = false
        private var remaining = 0L
        private var mask: ByteArray? = null
        private var maskOffset = 0L
        private var opcode = 0
        private var fin = false
        private var stopped = false
        private var control = BodyCapture()
        private var message: BodyCapture? = null
        private var messageOpcode: Opcode? = null
        private var compressed = false

        fun read(bytes: ByteBuf) {
            var at = bytes.readerIndex()
            val end = bytes.writerIndex()
            while (at < end && !stopped && !over) {
                if (!inPayload) {
                    header[headerRead++] = bytes.getByte(at++)
                    if (headerRead >= 2 && headerRead == headerSize()) startFrame()
                } else {
                    val count = minOf(remaining, (end - at).toLong()).toInt()
                    val chunk = ByteArray(count)
                    bytes.getBytes(at, chunk)
                    at += count
                    payload(chunk)
                    remaining -= count
                    if (remaining == 0L) endFrame()
                }
            }
        }

        fun release() {
            inflater?.end()
            inflater = null
        }

        private fun headerSize(): Int {
            val length =
                when (header[1].toInt() and 0x7f) {
                    126 -> 2
                    127 -> 8
                    else -> 0
                }
            return 2 + length + (if (header[1].toInt() and 0x80 != 0) 4 else 0)
        }

        private fun startFrame() {
            val first = header[0].toInt()
            fin = first and 0x80 != 0
            opcode = first and 0x0f
            var at = 2
            remaining =
                when (val length = header[1].toLong() and 0x7f) {
                    126L -> ((header[2].toLong() and 0xff) shl 8 or (header[3].toLong() and 0xff)).also { at = 4 }
                    127L -> (0 until 8).fold(0L) { value, i -> value shl 8 or (header[2 + i].toLong() and 0xff) }.also { at = 10 }
                    else -> length
                }
            mask = if (header[1].toInt() and 0x80 != 0) header.copyOfRange(at, at + 4) else null
            maskOffset = 0
            headerRead = 0
            if (remaining < 0) return stop()
            when {
                opcode >= CLOSE -> {
                    if (opcode > PONG || !fin || remaining > MAX_CONTROL) return stop()
                    control = BodyCapture()
                }

                opcode == CONTINUATION -> {
                    if (messageOpcode == null) return stop()
                }

                opcode == TEXT || opcode == BINARY -> {
                    if (messageOpcode != null) return stop()
                    messageOpcode = if (opcode == TEXT) Opcode.OPCODE_TEXT else Opcode.OPCODE_BINARY
                    message = BodyCapture()
                    compressed = first and RSV1 != 0
                    if (compressed && inflater == null) return stop()
                }

                else -> {
                    return stop()
                }
            }
            if (remaining == 0L) endFrame() else inPayload = true
        }

        private fun payload(chunk: ByteArray) {
            mask?.let { key ->
                for (i in chunk.indices) {
                    chunk[i] =
                        (chunk[i].toInt() xor key[((maskOffset + i) % 4).toInt()].toInt()).toByte()
                }
            }
            maskOffset += chunk.size
            when {
                opcode >= CLOSE -> control.add(chunk)
                compressed -> inflate(chunk)
                else -> message?.add(chunk)
            }
        }

        private fun endFrame() {
            inPayload = false
            if (opcode >= CLOSE) {
                if (opcode == CLOSE) closeSeen = true
                capture.frame(sent, control, CONTROL_OPCODES.getValue(opcode))
                return
            }
            if (!fin) return
            if (compressed) inflate(DEFLATE_TAIL)
            val done = message ?: return
            if (stopped) return
            capture.frame(sent, done, messageOpcode!!)
            message = null
            messageOpcode = null
        }

        private fun inflate(input: ByteArray) {
            val inflating = inflater ?: return stop()
            val out = ByteArray(INFLATE_CHUNK)
            try {
                inflating.setInput(input)
                while (true) {
                    val count = inflating.inflate(out)
                    if (count > 0) message?.add(out, count)
                    if (count == 0 && (inflating.needsInput() || inflating.needsDictionary() || inflating.finished())) break
                }
            } catch (_: DataFormatException) {
                stop()
            }
        }

        private fun stop() {
            stopped = true
            message = null
            release()
        }
    }

    companion object {
        /** The error of a WebSocket whose connection closed with no close frame either way. */
        const val NO_CLOSE = "The WebSocket's connection closed without a close frame"

        private const val CONTINUATION = 0
        private const val TEXT = 1
        private const val BINARY = 2
        private const val CLOSE = 8
        private const val PONG = 10
        private const val RSV1 = 0x40
        private const val MAX_HEADER = 14
        private const val MAX_CONTROL = 125L
        private const val INFLATE_CHUNK = 16 * 1024
        private val DEFLATE_TAIL = byteArrayOf(0, 0, -1, -1)
        private val CONTROL_OPCODES = mapOf(CLOSE to Opcode.OPCODE_CLOSE, 9 to Opcode.OPCODE_PING, PONG to Opcode.OPCODE_PONG)

        /** A capture for [response]'s WebSocket, or null when the `101` upgrades to something else. */
        fun of(
            capture: Capture,
            response: HttpResponse,
        ): WebSocketCapture? {
            val headers = response.headers()
            if (!headers.get(HttpHeaderNames.UPGRADE).orEmpty().equals("websocket", ignoreCase = true)) return null
            val deflate =
                headers.getAll(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS).any { value ->
                    value.split(',').any { it.substringBefore(';').trim().equals("permessage-deflate", ignoreCase = true) }
                }
            capture.keepOpen()
            return WebSocketCapture(capture, deflate)
        }
    }
}
