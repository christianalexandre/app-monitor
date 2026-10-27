package appmonitor.desktop.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
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

/** Keeps the last text copied, in place of the system clipboard. */
internal class FakeClipboard : ClipboardManager {
    var copied: AnnotatedString? = null

    override fun setText(annotatedString: AnnotatedString) {
        copied = annotatedString
    }

    override fun getText() = copied
}

/**
 * Opens [anchor]'s menu and picks [item]; clicks [anchor] again when the menu does not show, as a dialog that grows
 * while its content loads moves the anchor from under a click aimed before.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.pick(
    anchor: String,
    item: String,
) {
    repeat(MENU_TRIES) {
        onNodeWithText(anchor).performClick()
        val shown = runCatching { waitUntil(timeoutMillis = MENU_WAIT_MS) { onAllNodesWithText(item).fetchSemanticsNodes().isNotEmpty() } }
        if (shown.isSuccess) return onNodeWithText(item).performClick().let {}
    }
    error("$anchor never showed $item")
}

private const val MENU_TRIES = 3
private const val MENU_WAIT_MS = 4_000L

/** Opens the request detail's tab named [label]; the pick keeps it for the next request. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.showTab(label: String) {
    val tab = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
    onNode(tab).performClick()
}

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
