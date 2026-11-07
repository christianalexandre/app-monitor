package appmonitor.desktop.app

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

// ADR-0211: rewrites the committed icon files from `icon/app-monitor.svg`. Run only by `appIcons`.
class AppIconExport {
    @Test
    fun export() {
        val dir = File(System.getProperty("appIconDir"))
        val svg = File(dir, "app-monitor.svg").readBytes()
        File(System.getProperty("appIconPng")).writeBytes(render(svg, 1024))
        File(dir, "app-monitor.icns").writeBytes(icns(ICNS_SIZES.mapValues { render(svg, it.value) }))
        File(dir, "app-monitor.ico").writeBytes(ico(ICO_SIZES.map { it to render(svg, it) }))
    }

    private fun render(
        svg: ByteArray,
        size: Int,
    ): ByteArray {
        val dom = SVGDOM(Data.makeFromBytes(svg))
        val surface = Surface.makeRasterN32Premul(size, size)
        surface.canvas.scale(size / SOURCE_SIZE, size / SOURCE_SIZE)
        dom.setContainerSize(SOURCE_SIZE, SOURCE_SIZE)
        dom.render(surface.canvas)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private companion object {
        const val SOURCE_SIZE = 1024f
        val ICNS_SIZES =
            linkedMapOf(
                "ic11" to 32,
                "ic12" to 64,
                "ic07" to 128,
                "ic13" to 256,
                "ic08" to 256,
                "ic14" to 512,
                "ic09" to 512,
                "ic10" to 1024,
            )
        val ICO_SIZES = listOf(16, 24, 32, 48, 64, 128, 256)
    }
}

/** An ICNS file holding each PNG under its OSType. */
internal fun icns(entries: Map<String, ByteArray>): ByteArray {
    val body = ByteArrayOutputStream()
    entries.forEach { (type, png) ->
        body.write(type.toByteArray(Charsets.US_ASCII))
        body.write(ByteBuffer.allocate(4).putInt(png.size + 8).array())
        body.write(png)
    }
    val head =
        ByteBuffer
            .allocate(8)
            .put("icns".toByteArray(Charsets.US_ASCII))
            .putInt(body.size() + 8)
            .array()
    return head + body.toByteArray()
}

/** An ICO file holding each square PNG, as Windows reads them since Vista. */
internal fun ico(images: List<Pair<Int, ByteArray>>): ByteArray {
    val header = ByteBuffer.allocate(6 + 16 * images.size).order(ByteOrder.LITTLE_ENDIAN)
    header.putShort(0).putShort(1).putShort(images.size.toShort())
    var offset = header.capacity()
    images.forEach { (size, png) ->
        val side = if (size >= 256) 0 else size
        header
            .put(side.toByte())
            .put(side.toByte())
            .put(0)
            .put(0)
        header
            .putShort(1)
            .putShort(32)
            .putInt(png.size)
            .putInt(offset)
        offset += png.size
    }
    return images.fold(header.array()) { bytes, (_, png) -> bytes + png }
}
