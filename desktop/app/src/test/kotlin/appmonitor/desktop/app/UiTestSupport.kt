package appmonitor.desktop.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import java.io.File
import javax.imageio.ImageIO

internal const val URL = "https://api.example.com/v1/items?page=2"

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitText(
    text: String,
    substring: Boolean = false,
) = waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.screenshot(name: String) {
    val file = File(System.getProperty("screenshotDir"), name)
    file.parentFile.mkdirs()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
}
