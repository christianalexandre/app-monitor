package appmonitor.desktop.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.domain.AvailableUpdate
import appmonitor.desktop.domain.UpdateRepository
import appmonitor.desktop.feature.settings.UPDATES_SWITCH
import appmonitor.desktop.feature.traffic.SETTINGS_GEAR
import appmonitor.desktop.feature.traffic.UPDATE_NOTICE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// ADR-0228: a newer release shows in the status bar and opens its page; Settings switches the check.
@OptIn(ExperimentalTestApi::class)
class UpdateNoticeUiTest {
    private class StandInUpdates : UpdateRepository {
        override val checking = MutableStateFlow(true)
        override val available = MutableStateFlow<AvailableUpdate?>(AvailableUpdate("1.1.0", "https://example.com/releases/1.1.0"))

        override suspend fun setChecking(on: Boolean) {
            checking.value = on
            if (!on) available.value = null
        }
    }

    @Test
    fun `a newer version shows, opens its page, and goes when the check is switched off`() =
        withStorage { storage ->
            val updates = StandInUpdates()
            val opened = mutableListOf<String>()
            val uris =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        opened += uri
                    }
                }
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    CompositionLocalProvider(LocalUriHandler provides uris) {
                        AppMonitorApp(storage, "Listening", appearance = TestAppearance, updates = updates)
                    }
                }
                awaitText("Version 1.1.0 available")
                screenshot("update.png")
                onNodeWithTag(UPDATE_NOTICE).performClick()
                assertEquals(listOf("https://example.com/releases/1.1.0"), opened)

                onNodeWithTag(SETTINGS_GEAR).performClick()
                onNodeWithTag(UPDATES_SWITCH).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { !updates.checking.value }
                assertFalse(updates.checking.value)
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(UPDATE_NOTICE).fetchSemanticsNodes().isEmpty() }
            }
        }

    @Test
    fun `a page that cannot be opened leaves the window up`() =
        withStorage { storage ->
            val throwing =
                object : UriHandler {
                    override fun openUri(uri: String): Unit = throw UnsupportedOperationException("no browser")
                }
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    CompositionLocalProvider(LocalUriHandler provides throwing) {
                        AppMonitorApp(storage, "Listening", appearance = TestAppearance, updates = StandInUpdates())
                    }
                }
                awaitText("Version 1.1.0 available")
                onNodeWithTag(UPDATE_NOTICE).performClick()
                onNodeWithTag(SETTINGS_GEAR).performClick()
                onNodeWithTag(UPDATES_SWITCH).assertExists()
            }
        }
}
