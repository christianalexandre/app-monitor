package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.breakpoints.NEW_BREAKPOINT
import appmonitor.desktop.feature.settings.CLEAR_ALL
import appmonitor.desktop.feature.traffic.BREAKPOINTS
import appmonitor.desktop.feature.traffic.ISSUES
import appmonitor.desktop.feature.traffic.SETTINGS
import appmonitor.desktop.feature.traffic.TOOL
import appmonitor.desktop.feature.traffic.TOOLS_MENU
import kotlin.test.Test

// ADR-0210: the title bar's tools open what the palette opens, shown down to the window's minimum width, folded under it.
@OptIn(ExperimentalTestApi::class)
class TitleBarUiTest {
    @Test
    fun `at the window's minimum width the tools show and open their screens`() =
        withStorage { storage ->
            runDesktopComposeUiTest(width = 1000, height = 700) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance) }
                awaitTag(TOOL + SETTINGS)
                onNodeWithTag(TOOLS_MENU).assertDoesNotExist()
                screenshot("title-bar-narrow.png")

                onNodeWithTag(TOOL + SETTINGS).performClick()
                awaitTag(CLEAR_ALL)
                onNodeWithText("Close").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(CLEAR_ALL).fetchSemanticsNodes().isEmpty() }

                onNodeWithTag(TOOL + ISSUES).performClick()
                awaitText("No issues yet", substring = true)
                onNodeWithText("Close").performClick()

                onNodeWithTag(TOOL + BREAKPOINTS).performClick()
                awaitTag(NEW_BREAKPOINT)
            }
        }

    @Test
    fun `under the window's minimum width the tools fold into a menu`() =
        withStorage { storage ->
            runDesktopComposeUiTest(width = 740, height = 600) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance) }
                awaitTag(TOOLS_MENU)
                onNodeWithTag(TOOL + SETTINGS).assertDoesNotExist()
                screenshot("title-bar-folded.png")

                onNodeWithTag(TOOLS_MENU).performClick()
                awaitTag(TOOL + SETTINGS)
                onNodeWithTag(TOOL + SETTINGS).performClick()
                awaitTag(CLEAR_ALL)
            }
        }
}
