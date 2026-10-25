package appmonitor.desktop.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import appmonitor.desktop.domain.AppearanceRepository
import appmonitor.desktop.domain.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.imageio.ImageIO

internal const val URL = "https://api.example.com/v1/items?page=2"

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitText(
    text: String,
    substring: Boolean = false,
) = waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitTag(tag: String) =
    waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

/** The window's theme in these tests: light, dark only while [screenshot] takes its second picture. */
internal object TestAppearance : AppearanceRepository {
    override val theme = MutableStateFlow(ThemeMode.Light)

    override suspend fun setTheme(theme: ThemeMode) {
        this.theme.value = theme
    }
}

/** Writes [name] in light, then its `-dark` twin; a window not given [TestAppearance] writes the same picture twice. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.screenshot(name: String) {
    capture(name)
    TestAppearance.theme.value = ThemeMode.Dark
    try {
        waitForIdle()
        capture(name.removeSuffix(".png") + "-dark.png")
    } finally {
        TestAppearance.theme.value = ThemeMode.Light
        waitForIdle()
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.capture(name: String) {
    val file = File(System.getProperty("screenshotDir"), name)
    file.parentFile.mkdirs()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
}

/** The window's top left pixel, 0 black to 1 white. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.cornerLuminance(): Double {
    val rgb = onRoot().captureToImage().toAwtImage().getRGB(1, 1)
    return ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) / (3 * 255.0)
}
