package appmonitor.desktop.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.designsystem.PALETTE_FIELD
import appmonitor.desktop.designsystem.PALETTE_LIST
import appmonitor.desktop.feature.settings.CLEAR_ALL
import appmonitor.desktop.feature.traffic.ACTIONS
import appmonitor.desktop.feature.traffic.DETAIL_URL
import appmonitor.desktop.feature.traffic.PALETTE_OPEN
import appmonitor.desktop.feature.traffic.RECORD
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.v1.RequestStarted
import kotlin.test.Test

// ADR-0206: ⌘K opens the palette; typed text narrows it, ↑ ↓ move, ↵ runs what a button runs, and its shortcuts work alone.
@OptIn(ExperimentalTestApi::class)
class PaletteUiTest {
    @Test
    fun `typing narrows the palette, the arrows move and enter runs`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(
                request("r1", "GET", "https://api.example.com/v1/items?page=1"),
                request("r2", "GET", "https://api.example.com/v1/items?page=2"),
                request("r3", "POST", "https://auth.example.com/oauth/token"),
                request("r4", "GET", "https://api.example.com/v1/me"),
            )
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }
                awaitText("https://api.example.com/v1/me")
                command(Key.K)
                awaitTag(PALETTE_OPEN)
                screenshot("palette.png")

                onNodeWithTag(PALETTE_FIELD).performTextInput("v1/")
                awaitLit("/v1/items")
                onNodeWithTag(PALETTE_FIELD).performKeyInput { pressKey(Key.DirectionDown) }
                awaitLit("/v1/me")
                onNodeWithTag(PALETTE_FIELD).performKeyInput { pressKey(Key.Enter) }
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(PALETTE_OPEN).fetchSemanticsNodes().isEmpty() }
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("Select a request").fetchSemanticsNodes().isEmpty() }
                awaitTag(DETAIL_URL)
                onNodeWithTag(DETAIL_URL, useUnmergedTree = true).assertTextEquals("https://api.example.com/v1/me")

                onNodeWithTag(ACTIONS).performClick()
                onNodeWithTag(PALETTE_FIELD).performTextInput("record")
                awaitLit("Record")
                onNodeWithTag(PALETTE_FIELD).performKeyInput { pressKey(Key.Enter) }
                waitUntil(timeoutMillis = TIMEOUT_MS) { storage.recording.value != null }
                awaitText("Stop")

                command(Key.R)
                waitUntil(timeoutMillis = TIMEOUT_MS) { storage.recording.value == null }
                onNodeWithTag(RECORD).assertExists()
            }
        }

    @Test
    fun `the palette opens a screen, and its shortcut opens it without the palette`() =
        withStorage { storage ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance) }
                awaitTag(ACTIONS)
                command(Key.K)
                onNodeWithTag(PALETTE_FIELD).performTextInput("sett")
                awaitLit("Settings")
                onNodeWithTag(PALETTE_FIELD).performKeyInput { pressKey(Key.Enter) }
                awaitTag(CLEAR_ALL)
                onNodeWithTag(PALETTE_OPEN).assertDoesNotExist()
                onNodeWithText("Close").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(CLEAR_ALL).fetchSemanticsNodes().isEmpty() }

                command(Key.Comma)
                awaitTag(CLEAR_ALL)
            }
        }

    @Test
    fun `escape and the shortcut close it, and nothing matching says so`() =
        withStorage { storage ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance) }
                awaitTag(ACTIONS)
                command(Key.K)
                onNodeWithTag(PALETTE_FIELD).performTextInput("zzz")
                awaitText("Nothing matches “zzz”")
                onNodeWithTag(PALETTE_FIELD).performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(PALETTE_OPEN).fetchSemanticsNodes().isEmpty() }
                command(Key.K)
                awaitTag(PALETTE_OPEN)
                command(Key.K)
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(PALETTE_OPEN).fetchSemanticsNodes().isEmpty() }
            }
        }

    private fun request(
        id: String,
        method: String,
        url: String,
    ) = envelope(RequestStarted(request_id = id, method = method, url = url))
}

/** Presses ⌘ (Ctrl off macOS works the same: the window takes either) with [key] on the window's root. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.command(key: Key) {
    onRoot().performKeyInput {
        keyDown(Key.MetaLeft)
        pressKey(key)
        keyUp(Key.MetaLeft)
    }
}

/** Waits until the palette's lit entry shows [label]. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.awaitLit(label: String) =
    waitUntil(timeoutMillis = TIMEOUT_MS) {
        onAllNodes(isSelected() and hasText(label, substring = true) and hasAnyAncestor(hasTestTag(PALETTE_LIST)))
            .fetchSemanticsNodes()
            .isNotEmpty()
    }
