package appmonitor.desktop.storage

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** How a body or frame payload is stored: deflated when that makes it smaller, else as received (ADR-0085). */
internal class Encoded(
    val codec: String,
    val data: ByteArray,
)

internal object Codec {
    const val RAW = "raw"
    const val DEFLATE = "deflate"

    fun encode(bytes: ByteArray): Encoded {
        val deflated = deflate(bytes)
        return if (deflated.size < bytes.size) Encoded(DEFLATE, deflated) else Encoded(RAW, bytes)
    }

    fun decode(
        codec: String,
        data: ByteArray,
    ): ByteArray =
        when (codec) {
            RAW -> data
            DEFLATE -> inflate(data)
            else -> throw IllegalArgumentException("Unknown codec $codec")
        }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK)
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("Truncated deflate data")
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private const val CHUNK = 64 * 1024
}
