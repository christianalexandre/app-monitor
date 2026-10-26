package appmonitor.desktop.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.DEVICES_DIVIDER
import appmonitor.desktop.feature.traffic.DEVICES_TOGGLE
import appmonitor.desktop.feature.traffic.REQUESTS_DIVIDER
import appmonitor.desktop.feature.traffic.REQUEST_LIST
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.tools.fakeclient.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR-0151: the devices pane hides and comes back, each divider moves and stops at its bound.
@OptIn(ExperimentalTestApi::class)
class PanesUiTest {
    @Test
    fun `the devices pane hides and the dividers resize the panes`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(*Fixtures.traffic.toTypedArray())
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }
                awaitTag(REQUEST_LIST)

                fun requests() = onNodeWithTag(REQUEST_LIST).getBoundsInRoot()
                assertEquals(480.dp, requests().right - requests().left)

                onNodeWithTag(REQUESTS_DIVIDER).performMouseInput { drag(-100f) }
                assertTrue(requests().right - requests().left < 480.dp, "narrower")
                onNodeWithTag(REQUESTS_DIVIDER).performMouseInput { drag(-400f) }
                assertEquals(360.dp, requests().right - requests().left, "the request list's minimum")

                val before = requests().left
                onNodeWithTag(DEVICES_DIVIDER).performMouseInput { drag(60f) }
                assertTrue(requests().left > before, "the devices pane wider")
                screenshot("panes.png")

                onNodeWithTag(DEVICES_TOGGLE).performClick()
                onNodeWithText("Devices").assertDoesNotExist()
                onNodeWithTag(DEVICES_DIVIDER).assertDoesNotExist()
                assertEquals(0.dp, requests().left)
                screenshot("devices-hidden.png")

                onNodeWithTag(DEVICES_TOGGLE).performClick()
                onNodeWithText("Devices").assertExists()
            }
        }
}

private fun MouseInjectionScope.drag(dx: Float) {
    moveTo(center)
    press()
    repeat(DRAG_STEPS) { moveBy(Offset(dx / DRAG_STEPS, 0f)) }
    release()
}

private const val DRAG_STEPS = 10
