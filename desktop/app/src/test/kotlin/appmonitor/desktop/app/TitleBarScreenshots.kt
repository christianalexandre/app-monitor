package appmonitor.desktop.app

import appmonitor.desktop.designsystem.WindowBar
import appmonitor.desktop.domain.ThemeMode
import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR-0178: a real window's appearance and title bar, what no offscreen test sees. Run only by `titleBarScreenshots`.
class TitleBarScreenshots {
    private val objc = NativeLibrary.getInstance("objc")
    private val msgSend = objc.getFunction("objc_msgSend")

    @Structure.FieldOrder("x", "y", "width", "height")
    open class Rect : Structure() {
        @JvmField var x = 0.0

        @JvmField var y = 0.0

        @JvmField var width = 0.0

        @JvmField var height = 0.0

        class ByValue :
            Rect(),
            Structure.ByValue
    }

    fun interface Work : Callback {
        fun invoke(context: Pointer?)
    }

    @Test
    fun thePickedThemeRepaintsTheTitleBar() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64")
        val out = File(System.getProperty("titleBarScreenshotDir")).apply { mkdirs() }
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            frame =
                JFrame("App Monitor").apply {
                    setSize(640, 200)
                    isVisible = true
                }
        }
        try {
            Thread.sleep(SETTLE_MS)
            val dark = titleBarAfterPicking(ThemeMode.Dark, File(out, "title-bar-dark.png"))
            val light = titleBarAfterPicking(ThemeMode.Light, File(out, "title-bar-light.png"))
            if (dark != null && light != null) assertTrue(dark + MIN_MOVE < light, "dark title bar at $dark, light at $light")
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
            applyMacAppearance(ThemeMode.System)
        }
    }

    // ADR-0213: AppKit takes AWT's properties, and the window's buttons end inside the room the bar keeps for them.
    @Test
    fun theUnifiedTitleBarLeavesRoomForTheButtons() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64")
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            frame =
                JFrame("App Monitor").apply {
                    check(unifyTitleBar(rootPane))
                    setSize(640, 200)
                    isVisible = true
                }
        }
        try {
            Thread.sleep(SETTLE_MS)
            onMainThread {
                val window = windowOf(width = 640.0)
                val mask = msgSend.invokeLong(arrayOf(window, selector("styleMask")))
                assertTrue(mask and FULL_SIZE_CONTENT_VIEW != 0L, "style mask $mask")
                assertEquals(1L, msgSend.invokeLong(arrayOf(window, selector("titleVisibility"))) and 0xff)
                assertEquals(1, msgSend.invokeInt(arrayOf(window, selector("titlebarAppearsTransparent"))) and 0xff)
                val zoomButton = msgSend.invokePointer(arrayOf(window, selector("standardWindowButton:"), ZOOM_BUTTON))
                val zoomFrame = msgSend.invoke(Rect.ByValue::class.java, arrayOf(zoomButton, selector("frame"))) as Rect
                val buttonsEnd = zoomFrame.x + zoomFrame.width
                println("Zoom button frame: x=${zoomFrame.x} y=${zoomFrame.y} w=${zoomFrame.width} h=${zoomFrame.height}")
                assertTrue(buttonsEnd < WindowBar.MacButtonsInset.value, "buttons end at $buttonsEnd")
            }
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    /** Picks [theme], checks the window took it; with Screen Recording allowed, the bar goes to [file] and its mean luminance, 0 to 255, returns. */
    private fun titleBarAfterPicking(
        theme: ThemeMode,
        file: File,
    ): Int? {
        applyMacAppearance(theme)
        Thread.sleep(SETTLE_MS)
        val whole = File(file.parentFile, "whole-${file.name}")
        var written = false
        onMainThread {
            assertEquals(macAppearanceOf(theme), effectiveAppearanceName())
            written = captureWindow(whole)
        }
        if (!written) {
            println("No picture of the title bar: Screen Recording is not allowed for this process")
            return null
        }
        val image = ImageIO.read(whole)
        val bar = image.getSubimage(0, 0, image.width, TITLE_BAR_POINTS * image.width / WINDOW_POINTS)
        ImageIO.write(bar, "png", file)
        whole.delete()
        return luminanceOf(bar)
    }

    private fun window() = send(send(send(cls("NSApplication"), "sharedApplication"), "windows"), "lastObject")

    private fun windowOf(width: Double): Pointer? {
        val windows = send(send(cls("NSApplication"), "sharedApplication"), "windows")
        return (0L until msgSend.invokeLong(arrayOf(windows, selector("count"))))
            .map { msgSend.invokePointer(arrayOf(windows, selector("objectAtIndex:"), it)) }
            .single { (msgSend.invoke(Rect.ByValue::class.java, arrayOf(it, selector("frame"))) as Rect).width == width }
    }

    private fun effectiveAppearanceName() = send(send(send(window(), "effectiveAppearance"), "name"), "UTF8String")?.getString(0)

    private fun captureWindow(file: File): Boolean {
        val number = msgSend.invokeLong(arrayOf(window(), selector("windowNumber")))
        val everything =
            Rect.ByValue().apply {
                x = Double.POSITIVE_INFINITY
                y = Double.POSITIVE_INFINITY
            }
        val image =
            NativeLibrary
                .getInstance("CoreGraphics")
                .getFunction("CGWindowListCreateImage")
                .invokePointer(arrayOf(everything, INCLUDING_WINDOW, number.toInt(), IGNORE_FRAMING)) ?: return false
        val bitmap = msgSend.invokePointer(arrayOf(send(cls("NSBitmapImageRep"), "alloc"), selector("initWithCGImage:"), image))
        val png =
            msgSend.invokePointer(
                arrayOf(bitmap, selector("representationUsingType:properties:"), PNG_TYPE, send(cls("NSDictionary"), "dictionary")),
            )
        val path = msgSend.invokePointer(arrayOf(cls("NSString"), selector("stringWithUTF8String:"), file.path))
        msgSend.invokeVoid(arrayOf(png, selector("writeToFile:atomically:"), path, false))
        return true
    }

    private fun luminanceOf(image: BufferedImage): Int {
        var sum = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                sum += ((rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff)) / 3
            }
        }
        return (sum / (image.width * image.height)).toInt()
    }

    private fun onMainThread(block: () -> Unit) {
        val system = NativeLibrary.getInstance("System")
        val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
        var failure: Throwable? = null
        val work =
            Work {
                try {
                    block()
                } catch (e: Throwable) {
                    failure = e
                }
            }
        system.getFunction("dispatch_sync_f").invokeVoid(arrayOf(mainQueue, null, work))
        failure?.let { throw it }
    }

    private fun send(
        receiver: Pointer?,
        selector: String,
    ): Pointer? = msgSend.invokePointer(arrayOf(receiver, selector(selector)))

    private fun cls(name: String) = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))

    private fun selector(name: String) = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))

    private companion object {
        const val SETTLE_MS = 1_000L
        const val WINDOW_POINTS = 640
        const val TITLE_BAR_POINTS = 28
        const val MIN_MOVE = 60
        const val PNG_TYPE = 4L
        const val INCLUDING_WINDOW = 8
        const val IGNORE_FRAMING = 1
        const val FULL_SIZE_CONTENT_VIEW = 1L shl 15
        const val ZOOM_BUTTON = 2L
    }
}
