package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.MOCKING_SWITCH
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

// ADR-0223: the title bar's switch turns mocking off and on, and the store keeps the choice.
@OptIn(ExperimentalTestApi::class)
class MockingSwitchUiTest {
    @Test
    fun `the title bar switches mocking off and back on`() =
        withStorage { storage ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance) }
                awaitText("Mocking")
                onNodeWithTag(MOCKING_SWITCH).performClick()
                awaitText("Not mocking")
                waitUntil(timeoutMillis = TIMEOUT_MS) { runBlocking { !storage.observeMocking().first() } }
                screenshot("mocking-off.png")
                onNodeWithTag(MOCKING_SWITCH).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { runBlocking { storage.observeMocking().first() } }
                awaitText("Mocking")
            }
        }
}
