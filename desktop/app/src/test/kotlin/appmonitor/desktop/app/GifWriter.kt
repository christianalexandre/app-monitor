package appmonitor.desktop.app

import org.w3c.dom.Node
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/**
 * An animated GIF that loops, written with the JDK's `ImageIO` only. After the first, each frame keeps just the
 * rectangle that changed, drawn over the previous one; a frame equal to the last lengthens it instead.
 */
internal class GifWriter(
    private val file: File,
) {
    private class Frame(
        val image: BufferedImage,
        val x: Int,
        val y: Int,
        var delayMs: Int,
    )

    private val frames = mutableListOf<Frame>()
    private var last: BufferedImage? = null

    fun add(
        image: BufferedImage,
        delayMs: Int,
    ) {
        val previous = last
        if (previous == null) {
            frames += Frame(rgb(image, 0, 0, image.width, image.height), 0, 0, delayMs)
        } else {
            require(image.width == previous.width && image.height == previous.height) { "every frame has the first one's size" }
            val box = changed(previous, image)
            if (box == null) {
                frames.last().delayMs += delayMs
            } else {
                frames += Frame(rgb(image, box[0], box[1], box[2], box[3]), box[0], box[1], delayMs)
            }
        }
        last = rgb(image, 0, 0, image.width, image.height)
    }

    fun write() {
        check(frames.isNotEmpty()) { "no frame added" }
        file.parentFile?.mkdirs()
        file.delete()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(file).use { out ->
            writer.output = out
            writer.prepareWriteSequence(null)
            frames.forEachIndexed { i, frame ->
                val type = ImageTypeSpecifier.createFromRenderedImage(frame.image)
                val metadata = writer.getDefaultImageMetadata(type, null)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                root.child("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "doNotDispose")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", (frame.delayMs / 10).coerceAtLeast(1).toString())
                    setAttribute("transparentColorIndex", "0")
                }
                root.child("ImageDescriptor").apply {
                    setAttribute("imageLeftPosition", frame.x.toString())
                    setAttribute("imageTopPosition", frame.y.toString())
                    setAttribute("imageWidth", frame.image.width.toString())
                    setAttribute("imageHeight", frame.image.height.toString())
                    setAttribute("interlaceFlag", "FALSE")
                }
                if (i == 0) {
                    val loop =
                        IIOMetadataNode("ApplicationExtension").apply {
                            setAttribute("applicationID", "NETSCAPE")
                            setAttribute("authenticationCode", "2.0")
                            userObject = byteArrayOf(1, 0, 0)
                        }
                    root.child("ApplicationExtensions").appendChild(loop)
                }
                metadata.setFromTree(format, root)
                writer.writeToSequence(IIOImage(frame.image, null, metadata), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    private fun IIOMetadataNode.child(name: String): IIOMetadataNode {
        var node: Node? = firstChild
        while (node != null) {
            if (node.nodeName == name) return node as IIOMetadataNode
            node = node.nextSibling
        }
        return IIOMetadataNode(name).also { appendChild(it) }
    }

    private companion object {
        fun rgb(
            source: BufferedImage,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ): BufferedImage {
            val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            copy.createGraphics().apply {
                drawImage(source.getSubimage(x, y, width, height), 0, 0, null)
                dispose()
            }
            return copy
        }

        /** The smallest `[x, y, width, height]` holding every pixel that differs, or null when none does. */
        fun changed(
            a: BufferedImage,
            b: BufferedImage,
        ): IntArray? {
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = -1
            var bottom = -1
            for (y in 0 until a.height) {
                for (x in 0 until a.width) {
                    if (a.getRGB(x, y) != b.getRGB(x, y)) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            return if (right < 0) null else intArrayOf(left, top, right - left + 1, bottom - top + 1)
        }
    }
}
