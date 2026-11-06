package appmonitor.desktop.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.Text as MaterialText

/** The [CommandPalette]'s search field and its list. */
const val PALETTE_FIELD = "palette-field"

const val PALETTE_LIST = "palette-list"

/** One row of a [CommandPalette]: a [glyph], its [label] and [sub] line, the [shortcut] that runs it without the palette. */
class PaletteItem(
    val label: String,
    val tag: String,
    val glyph: String,
    val onRun: () -> Unit,
    val sub: String? = null,
    val shortcut: String? = null,
)

/** A titled run of [items]: "Requests", "Actions". */
class PaletteGroup(
    val title: String,
    val items: List<PaletteItem>,
)

/**
 * A search field over [groups], near the window's top on a scrim: ↑ and ↓ move, ↵ closes it and runs the lit item, Esc or
 * a click outside closes it. The caller filters [groups] by [query]; the lit item goes back to the first as it changes.
 */
@Composable
fun CommandPalette(
    query: String,
    onQueryChange: (String) -> Unit,
    groups: List<PaletteGroup>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search requests, actions, screens…",
    closeShortcut: String = "⌘K",
) {
    val colors = AppMonitorTheme.colors
    val shape = RoundedCornerShape(Radius.large)
    val items = groups.flatMap { it.items }
    var lit by remember(query) { mutableIntStateOf(0) }
    val shown = lit.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val field = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { field.requestFocus() }
    // The list holds a group's title before its items: the lit item's place in it counts them.
    val rowOf =
        buildList {
            var row = 0
            groups.forEach { group ->
                row++
                group.items.forEach { _ -> add(row++) }
            }
        }
    LaunchedEffect(shown, items.size) {
        val row = rowOf.getOrNull(shown) ?: return@LaunchedEffect
        val info = list.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == row }
        when {
            shown == 0 -> list.scrollToItem(0)
            seen == null -> list.scrollToItem(row)
            seen.offset + seen.size > info.viewportEndOffset -> list.scrollBy((seen.offset + seen.size - info.viewportEndOffset).toFloat())
            seen.offset < info.viewportStartOffset -> list.scrollBy((seen.offset - info.viewportStartOffset).toFloat())
        }
    }
    val run = { item: PaletteItem ->
        onClose()
        item.onRun()
    }
    Box(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(onClose) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = PALETTE_TOP)
                .width(PALETTE_WIDTH)
                .shadow(Elevation.dialog, shape)
                .clip(shape)
                .border(1.dp, colors.border, shape)
                .background(colors.surface)
                // A tap the rows leave is the card's, not the scrim's; `clickable` here would merge the rows' semantics.
                .pointerInput(Unit) { detectTapGestures {} }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> true.also { onClose() }
                        Key.DirectionDown -> true.also { lit = (shown + 1).coerceAtMost((items.size - 1).coerceAtLeast(0)) }
                        Key.DirectionUp -> true.also { lit = (shown - 1).coerceAtLeast(0) }
                        Key.Enter, Key.NumPadEnter -> true.also { items.getOrNull(shown)?.let(run) }
                        else -> false
                    }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaterialText("⌕", color = colors.muted, style = styleOf(TextRole.Title))
                BasicTextField(
                    query,
                    onQueryChange,
                    Modifier.weight(1f).focusRequester(field).testTag(PALETTE_FIELD),
                    singleLine = true,
                    textStyle = styleOf(TextRole.Title).copy(color = colors.onSurface, fontWeight = null),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                MaterialText(
                                    placeholder,
                                    color = colors.neutral,
                                    style = styleOf(TextRole.Title).copy(fontWeight = null),
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
                KeyHint("esc")
            }
            Divider()
            LazyColumn(Modifier.heightIn(max = PALETTE_LIST_HEIGHT).padding(vertical = Spacing.xs).testTag(PALETTE_LIST), state = list) {
                var index = 0
                groups.forEach { group ->
                    item(key = "group-${group.title}") {
                        Text(
                            group.title,
                            Modifier.padding(start = Spacing.lg, top = Spacing.md, bottom = Spacing.xxs),
                            role = TextRole.Label,
                        )
                    }
                    group.items.forEach { entry ->
                        val at = index++
                        item(key = "item-${entry.tag}") {
                            PaletteRow(entry, at == shown, onHover = { lit = at }, onClick = { run(entry) })
                        }
                    }
                }
                if (items.isEmpty()) {
                    item(key = "nothing") {
                        Text(
                            "Nothing matches “$query”",
                            Modifier.fillMaxWidth().padding(Spacing.xl),
                            role = TextRole.Small,
                            secondary = true,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("↵" to "run", "↑↓" to "move", closeShortcut to "close").forEach { (key, what) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        KeyHint(key)
                        Text(what, role = TextRole.Small, secondary = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(
    item: PaletteItem,
    lit: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = AppMonitorTheme.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = lit }
            .background(if (lit) colors.selected else Color.Transparent)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .testTag(item.tag),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(GLYPH_BOX).clip(RoundedCornerShape(Radius.medium)).background(colors.border.copy(alpha = CHIP_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            MaterialText(item.glyph, color = colors.neutral, style = styleOf(TextRole.Small), maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(item.label, role = if (lit) TextRole.BodyStrong else TextRole.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.sub?.let { Text(it, role = TextRole.Caption, secondary = true, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        item.shortcut?.let { KeyHint(it) }
    }
}

/** A key or chord on a small chip: "⌘K", "esc", "↵". */
@Composable
fun KeyHint(
    keys: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppMonitorTheme.colors
    MaterialText(
        keys,
        modifier
            .clip(RoundedCornerShape(Radius.small))
            .background(colors.border.copy(alpha = CHIP_ALPHA))
            .padding(horizontal = Spacing.xs, vertical = Spacing.none),
        color = colors.neutral,
        style = styleOf(TextRole.Caption),
        maxLines = 1,
    )
}

private val PALETTE_TOP = 90.dp
private val PALETTE_WIDTH = 560.dp
private val PALETTE_LIST_HEIGHT = 380.dp
private val GLYPH_BOX = 24.dp
private const val CHIP_ALPHA = 0.6f
