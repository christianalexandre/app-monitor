package appmonitor.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.designsystem.AppMonitorTheme
import appmonitor.desktop.designsystem.LocalWindowBar
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.WindowBar
import appmonitor.desktop.feature.traffic.SETTINGS
import appmonitor.desktop.feature.traffic.TOOL
import appmonitor.desktop.feature.traffic.TOOLS_MENU
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ADR-0213: on macOS the window draws its own title bar; elsewhere the system's stays.
@OptIn(ExperimentalTestApi::class)
class MacTitleBarTest {
    private val properties = listOf(MAC_FULL_WINDOW_CONTENT, MAC_TRANSPARENT_TITLE_BAR, MAC_TITLE_VISIBLE)

    @Test
    fun `the title bar's properties are set on macOS only`() {
        val mac = JRootPane()
        assertEquals(true, unifyTitleBar(mac, "Mac OS X"))
        assertEquals(listOf<Any?>(true, true, false), properties.map(mac::getClientProperty))

        for (os in listOf("Windows 11", "Linux")) {
            val other = JRootPane()
            assertEquals(false, unifyTitleBar(other, os))
            properties.forEach { assertNull(other.getClientProperty(it), "$it on $os") }
        }
    }

    @Test
    fun `a double click zooms from the bar's empty part, not from a button`() =
        runDesktopComposeUiTest(width = 400, height = 40) {
            var zooms = 0
            var clicks = 0
            setContent {
                AppMonitorTheme(dark = false) {
                    Row(Modifier.fillMaxWidth().zoomOnDoubleClick { zooms++ }) {
                        TextButton("Clear", onClick = { clicks++ }, Modifier.testTag("button"))
                        Box(Modifier.weight(1f).fillMaxHeight().testTag("empty"))
                    }
                }
            }
            onNodeWithTag("button").performMouseInput { doubleClick() }
            waitForIdle()
            assertEquals(0 to 2, zooms to clicks)

            onNodeWithTag("empty").performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = TIMEOUT_MS) { zooms == 1 }
            assertEquals(2, clicks)
        }

    @Test
    fun `past the window's buttons the tools still show at the window's minimum width`() =
        withStorage { storage ->
            runDesktopComposeUiTest(width = 1000, height = 700) {
                setContent {
                    CompositionLocalProvider(LocalWindowBar provides WindowBar(WindowBar.MacButtonsInset, WindowBar.Plain.area)) {
                        AppMonitorApp(storage, "Listening", appearance = TestAppearance)
                    }
                }
                awaitTag(TOOL + SETTINGS)
                onNodeWithTag(TOOLS_MENU).assertDoesNotExist()
                screenshot("title-bar-mac.png")
            }
        }
}
