package appmonitor.desktop.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

// ADR-0209: each icon's paths parse and draw inside its 16 × 16 box, in the tint asked.
@OptIn(ExperimentalTestApi::class)
class IconsTest {
    @Test
    fun `every icon draws in its tint`() =
        runDesktopComposeUiTest(width = 1200, height = 100) {
            setContent {
                AppMonitorTheme(dark = false) {
                    Row {
                        AppIcons.all.forEach { (name, icon) -> Icon(icon, Modifier.testTag(name), size = 32.dp, tint = Color.Red) }
                    }
                }
            }
            AppIcons.all.forEach { (name, _) ->
                val image = onNodeWithTag(name).captureToImage().toAwtImage()
                var red = 0
                for (x in 0 until image.width) {
                    for (y in 0 until image.height) {
                        val rgb = image.getRGB(x, y)
                        val alpha = rgb ushr 24
                        if (alpha > 0 && (rgb shr 16 and 0xFF) > 200 && (rgb shr 8 and 0xFF) < 80) red++
                    }
                }
                assertTrue(red > image.width, "$name draws $red red pixels")
                val inked = (0 until image.height).any { y -> image.getRGB(0, y) ushr 24 != 0 }
                assertTrue(!inked, "$name draws over its left edge")
            }
        }
}
