package appmonitor.desktop.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GalleryTest {
    @Test
    fun `every component renders in light and dark`() {
        listOf(false, true).forEach { dark ->
            var chosen: String? = null
            var tab = 0
            runDesktopComposeUiTest(width = 1280, height = 960) {
                setContent {
                    AppMonitorTheme(dark) {
                        AppSurface { Gallery({ tab = it }) { chosen = it } }
                    }
                }
                TextRole.entries.forEach { onNodeWithText("Text in ${it.name}").assertExists() }
                BadgeTone.entries.forEach { onNodeWithText(it.name).assertExists() }
                onNodeWithText("2xx on").assertIsOn()
                onNodeWithText("4xx off").assertIsOff()
                onNodeWithText("Method: Any ▾").performClick()
                onNodeWithText("POST").performClick()
                assertEquals("POST", chosen)
                onNodeWithText("Headers").performClick()
                assertEquals(1, tab)
                val corner = onRoot().captureToImage().toAwtImage().getRGB(1, 1) and 0xFFFFFF
                assertEquals(if (dark) 0x121212 else 0xFFFFFF, corner, "the surface's color")
                screenshot(if (dark) "gallery-dark.png" else "gallery-light.png")
            }
        }
    }
}

@Composable
private fun Gallery(
    onTab: (Int) -> Unit,
    onChoose: (String?) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(420.dp).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PaneTitle("Pane title")
            TextRole.entries.forEach { Text("Text in ${it.name}", role = it) }
            Text("Text in error", role = TextRole.Caption, error = true)
            Placeholder("Placeholder")
            Section("Section") { Text("Section content") }
            Divider()
            Row {
                TextButton("Text button", onClick = {})
                TextButton("Disabled", onClick = {}, enabled = false)
            }
            Box(Modifier.width(160.dp)) { TextButton("A label too long for one line", onClick = {}, singleLine = true) }
            Choice("Method", null, listOf("GET", "POST"), { it }, onChoose = onChoose)
            CheckboxRow("Checkbox row", checked = true, onCheckedChange = {})
            Checkbox(checked = false, onCheckedChange = {})
            TextField("Value", {}, Modifier.fillMaxWidth(), label = "Label", singleLine = true)
            TextField("", {}, Modifier.fillMaxWidth(), placeholder = "Placeholder")
            Text("Selected item", Modifier.fillMaxWidth().selectableItem(selected = true) {}.padding(Spacing.md))
            Text("Item", Modifier.fillMaxWidth().selectableItem(selected = false) {}.padding(Spacing.md))
        }
        VerticalDivider()
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Toolbar {
                TextButton("Record", onClick = {})
                TextButton("Clear", onClick = {})
            }
            Tabs(listOf("Summary", "Headers", "Body"), selected = 0, onSelect = onTab, Modifier.padding(horizontal = Spacing.lg))
            Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                BadgeTone.entries.forEach { Badge(it.name, it) }
            }
            Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ToggleChip("2xx on", BadgeTone.Success, selected = true, onToggle = {})
                ToggleChip("4xx off", BadgeTone.Warning, selected = false, onToggle = {})
            }
            Box(Modifier.width(220.dp).padding(horizontal = Spacing.lg)) {
                MiddleEllipsisText("https://api.example.com/v1/orders/42/items?page=2")
            }
            Box(Modifier.height(160.dp)) { EmptyState("Empty state", "What to do next") }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxSize()) {
            DialogCard(DialogWidth.Narrow) {
                Text("Dialog card", role = TextRole.BodyStrong)
                Text("Over an overlay that dims the window", role = TextRole.Caption)
                DialogActions {
                    TextButton("Cancel", onClick = {})
                    TextButton("Confirm", onClick = {})
                }
            }
        }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.screenshot(name: String) {
    val file = File(System.getProperty("screenshotDir"), name)
    file.parentFile.mkdirs()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
}
