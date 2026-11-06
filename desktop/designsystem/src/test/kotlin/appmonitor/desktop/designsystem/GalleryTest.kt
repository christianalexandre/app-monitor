package appmonitor.desktop.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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
            runDesktopComposeUiTest(width = 1680, height = 960) {
                setContent {
                    AppMonitorTheme(dark) {
                        AppSurface { Gallery({ tab = it }) { chosen = it } }
                    }
                }
                TextRole.entries.forEach {
                    val shown = "Text in ${it.name}".let { text -> if (it == TextRole.Label) text.uppercase() else text }
                    onNodeWithText(shown).assertExists()
                }
                LayerNames.forEach { onNodeWithText(it).assertExists() }
                BadgeTone.entries.forEach { onNodeWithText(it.name).assertExists() }
                onNodeWithText("2xx on").assertIsOn()
                onNodeWithText("4xx off").assertIsOff()
                onNodeWithText("Method: Any ▾").performClick()
                onNodeWithText("POST").performClick()
                assertEquals("POST", chosen)
                onNodeWithText("Headers").performClick()
                assertEquals(1, tab)
                onNodeWithText("Command palette").assertExists()
                onNodeWithText("Search requests, actions, screens…").assertExists()
                val corner = onRoot().captureToImage().toAwtImage().getRGB(1, 1) and 0xFFFFFF
                val surface = (if (dark) DarkAppColors else LightAppColors).surface.toArgb() and 0xFFFFFF
                assertEquals(surface, corner, "the surface's color")
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
        PaneDivider(onDrag = {})
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            TitleBar {
                GlyphButton("◧", "Sidebar", onClick = {})
                Text("Title bar", Modifier.weight(1f), role = TextRole.SmallStrong, secondary = true)
                TextButton("Record", onClick = {})
                TextButton("Clear", onClick = {})
            }
            Column(Modifier.width(220.dp).background(AppMonitorTheme.colors.sidebar).padding(horizontal = Spacing.sm)) {
                SidebarSection("Devices")
                SidebarItem("All devices", onClick = {}, selected = true) { Text("12", role = TextRole.Caption, secondary = true) }
                SidebarItem("Pixel 9", onClick = {}, sub = "Sample 1.0 · Android 16", dot = BadgeTone.Success)
                SidebarItem("api.example.com", onClick = {}, dimmed = true)
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
            Column(Modifier.width(220.dp).padding(horizontal = Spacing.lg)) {
                PathText("/v1/items/42", "?include=tags")
                PathText("/v1/orders/42/items/7/lines", "?page=2&sort=price")
            }
            Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                SegmentButton("All", selected = true, onClick = {})
                SegmentButton("2xx", selected = false, onClick = {})
                SegmentButton("Failed", selected = false, onClick = {})
            }
            Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Segmented(listOf("Request", "Response", "Timing"), selected = 1, onSelect = {})
                Segmented(listOf("Text", "JSON", "Hex"), selected = 1, onSelect = {}, small = true)
                MenuButton("⋯", "More actions", listOf(MenuAction("Copy as cURL", "copy") {}))
            }
            Box(Modifier.width(360.dp).padding(horizontal = Spacing.lg)) {
                KeyValueTable(
                    listOf("Content-Type" to "application/json", "X-Request-Id" to "42", "Cache-Control" to "no-store"),
                    query = "json",
                )
            }
            Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("Secondary text", role = TextRole.Small, secondary = true)
                Text("Warning text", role = TextRole.Small, warning = true)
                Text("Error text", role = TextRole.Small, error = true)
            }
            Column(Modifier.padding(horizontal = Spacing.lg)) {
                CodeText("content-type", role = TextRole.CodeCaption, muted = true)
                CodeBlock {
                    CodeText(
                        "{\"key\": \"value\", \"n\": 1}",
                        query = "VALUE",
                        syntax =
                            listOf(
                                SyntaxSpan(1, 6, SyntaxKind.Key),
                                SyntaxSpan(8, 15, SyntaxKind.String),
                                SyntaxSpan(17, 20, SyntaxKind.Key),
                                SyntaxSpan(22, 23, SyntaxKind.Value),
                            ),
                    )
                }
            }
            Box(Modifier.height(160.dp)) { EmptyState("Empty state", "What to do next") }
            Row(Modifier.padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                SearchField("", {}, "Search field", Modifier.weight(1f), shortcut = "⌘F")
                SearchField("query", {}, "Search field", Modifier.weight(1f))
                GlyphButton("↓", "Next", onClick = {})
            }
            Toast("Toast", Modifier.padding(horizontal = Spacing.lg))
            StatusBar {
                StatusDot(BadgeTone.Success)
                Text("Listening :47474", role = TextRole.Caption, secondary = true)
                StatusDot(BadgeTone.Error)
                Text("Recording · 12 requests", role = TextRole.Caption, secondary = true)
            }
        }
        VerticalDivider()
        Tokens()
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DialogCard(DialogWidth.Narrow) {
                    Text("Dialog card", role = TextRole.BodyStrong)
                    Text("Over an overlay that dims the window", role = TextRole.Caption)
                    DialogActions {
                        TextButton("Cancel", onClick = {})
                        TextButton("Confirm", onClick = {})
                    }
                }
            }
            Box(Modifier.weight(1.4f).fillMaxWidth()) {
                CommandPalette(
                    "",
                    {},
                    listOf(
                        PaletteGroup(
                            "Requests",
                            listOf(PaletteItem("/v1/events/batch", "gallery-request", "↗", {}, sub = "POST · telemetry.example.net")),
                        ),
                        PaletteGroup(
                            "Actions",
                            listOf(
                                PaletteItem("Command palette", "gallery-record", "●", {}, shortcut = "⌘R"),
                                PaletteItem("Copy as cURL", "gallery-curl", "$", {}),
                            ),
                        ),
                        PaletteGroup("Open", listOf(PaletteItem("Settings", "gallery-settings", "⚙", {}, shortcut = "⌘,"))),
                    ),
                    onClose = {},
                )
            }
        }
    }
}

private val LayerNames = listOf("titleBar", "sidebar", "list", "surface", "statusBar", "border", "selected")

/** Each layer's color with its name, and the bars' heights (ADR-0202). */
@Composable
private fun Tokens() {
    val colors = AppMonitorTheme.colors
    val layers =
        listOf(colors.titleBar, colors.sidebar, colors.list, colors.surface, colors.statusBar, colors.border, colors.selected)
    Column(Modifier.width(200.dp).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Layers", role = TextRole.Label)
        LayerNames.zip(layers).forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Box(
                    Modifier
                        .size(
                            28.dp,
                        ).border(1.dp, colors.border, RoundedCornerShape(Radius.medium))
                        .background(color, RoundedCornerShape(Radius.medium)),
                )
                Text(name, role = TextRole.Small)
            }
        }
        Text("Bars", Modifier.padding(top = Spacing.lg), role = TextRole.Label)
        listOf("title" to BarHeight.title, "detail" to BarHeight.detail, "status" to BarHeight.status).forEach { (name, height) ->
            Box(Modifier.fillMaxWidth().height(height).background(colors.statusBar), contentAlignment = Alignment.CenterStart) {
                Text("$name ${height.value.toInt()} dp", Modifier.padding(horizontal = Spacing.md), role = TextRole.Caption)
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
