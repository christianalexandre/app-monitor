package appmonitor.desktop.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIdentityTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    @Test
    fun `the window icon is the 1024 px render`() {
        val image = ImageIO.read(checkNotNull(javaClass.classLoader.getResourceAsStream(APP_ICON)))
        assertEquals(1024 to 1024, image.width to image.height)
    }

    @Test
    fun `the ICNS holds a PNG for every size macOS asks`() {
        val bytes = File("icon/app-monitor.icns").readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        assertEquals("icns", String(bytes, 0, 4))
        assertEquals(bytes.size, buffer.getInt(4))
        val types = mutableListOf<String>()
        var at = 8
        while (at < bytes.size) {
            types += String(bytes, at, 4)
            assertEquals(png.toList(), bytes.copyOfRange(at + 8, at + 12).toList())
            at += buffer.getInt(at + 4)
        }
        assertEquals(bytes.size, at)
        assertEquals(setOf("ic07", "ic08", "ic09", "ic10", "ic11", "ic12", "ic13", "ic14"), types.toSet())
    }

    @Test
    fun `the ICO holds a PNG from 16 to 256 px`() {
        val bytes = File("icon/app-monitor.ico").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getShort(4).toInt()
        val sides =
            (0 until count).map { i ->
                val entry = 6 + 16 * i
                val offset = buffer.getInt(entry + 12)
                assertEquals(png.toList(), bytes.copyOfRange(offset, offset + 4).toList())
                (bytes[entry].toInt() and 0xFF).let { if (it == 0) 256 else it }
            }
        assertEquals(listOf(16, 24, 32, 48, 64, 128, 256), sides)
    }
}
