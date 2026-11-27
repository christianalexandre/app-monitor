package appmonitor.desktop.app

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class GifWriterTest {
    private fun frame(square: Boolean) =
        BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                color = Color.WHITE
                fillRect(0, 0, 100, 60)
                if (square) {
                    color = Color.RED
                    fillRect(20, 10, 30, 15)
                }
                dispose()
            }
        }

    @Test
    fun `keeps only what changed, merges an equal frame and loops`() {
        val file = createTempDirectory("gif").resolve("out.gif").toFile()
        GifWriter(file).apply {
            add(frame(square = false), 500)
            add(frame(square = true), 300)
            add(frame(square = true), 200)
            write()
        }

        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(file).use { input ->
            reader.input = input
            assertEquals(2, reader.getNumImages(true))
            assertEquals(100, reader.read(0).width)
            val second = reader.read(1)
            assertEquals(30 to 15, second.width to second.height)
            assertEquals(Color.RED.rgb, second.getRGB(5, 5))
            val tree = reader.getImageMetadata(1).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
            val descriptor = tree.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
            assertEquals("20" to "10", descriptor.getAttribute("imageLeftPosition") to descriptor.getAttribute("imageTopPosition"))
            val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
            assertEquals("50", control.getAttribute("delayTime"))
            val first = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
            assertEquals(
                "NETSCAPE",
                (first.getElementsByTagName("ApplicationExtension").item(0) as IIOMetadataNode).getAttribute("applicationID"),
            )
        }
        reader.dispose()
    }
}
