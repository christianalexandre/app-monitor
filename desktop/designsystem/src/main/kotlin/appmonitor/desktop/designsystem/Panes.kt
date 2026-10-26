package appmonitor.desktop.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import androidx.compose.material.Text as MaterialText

/** What a [Badge] says of its subject; each tone has its own color in light and dark. */
enum class BadgeTone { Neutral, Success, Info, Warning, Error }

/** A short label on a faded background of its [tone]'s color: a status, "mocked", "replay". */
@Composable
fun Badge(
    text: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
) {
    val color = colorOf(tone)
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.small))
            .background(color.copy(alpha = BADGE_ALPHA))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        contentAlignment = Alignment.Center,
    ) {
        MaterialText(text, color = color, style = styleOf(TextRole.SmallStrong), maxLines = 1)
    }
}

@Composable
private fun colorOf(tone: BadgeTone): Color {
    val colors = AppMonitorTheme.colors
    return when (tone) {
        BadgeTone.Neutral -> colors.neutral
        BadgeTone.Success -> colors.success
        BadgeTone.Info -> colors.info
        BadgeTone.Warning -> colors.warning
        BadgeTone.Error -> colors.error
    }
}

private const val BADGE_ALPHA = 0.16f

/** A [Badge] that turns on and off: in its [tone]'s color while [selected], neutral and faded while not. */
@Composable
fun ToggleChip(
    text: String,
    tone: BadgeTone,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) colorOf(tone) else colorOf(BadgeTone.Neutral)
    val shape = RoundedCornerShape(Radius.small)
    Box(
        modifier
            .clip(shape)
            .toggleable(selected, onValueChange = onToggle, role = Role.Checkbox)
            .background(color.copy(alpha = if (selected) BADGE_ALPHA else BADGE_ALPHA / 2))
            .border(1.dp, if (selected) color else Color.Transparent, shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        MaterialText(text, color = color, style = styleOf(if (selected) TextRole.SmallStrong else TextRole.Small), maxLines = 1)
    }
}

/** How wide a [PaneDivider] is, for a row that shares its width between panes. */
val PaneDividerWidth: Dp = Spacing.sm

/** The rule between two panes of a row, dragged sideways to move it: [onDrag] gets each move, in dp (ADR-0151). */
@Composable
fun PaneDivider(
    onDrag: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val drag by rememberUpdatedState(onDrag)
    val state = rememberDraggableState { px -> drag(with(density) { px.toDp() }) }
    Box(
        modifier
            .fillMaxHeight()
            .width(PaneDividerWidth)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(state, Orientation.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider()
    }
}

/** The window's actions over its panes, in one row ruled below. */
@Composable
fun Toolbar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Divider()
    }
}

/** Text tabs in one row ruled below, the [selected] one strong and underlined in the primary color. */
@Composable
fun Tabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            tabs.forEachIndexed { index, label -> Tab(label, index == selected) { onSelect(index) } }
        }
        Divider()
    }
}

@Composable
private fun Tab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = AppMonitorTheme.colors.primary
    Text(
        label,
        Modifier
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                val height = TAB_INDICATOR.toPx()
                if (selected) drawRect(color, Offset(0f, size.height - height), Size(size.width, height))
            }.padding(horizontal = Spacing.md, vertical = Spacing.md),
        role = if (selected) TextRole.SmallStrong else TextRole.Small,
        maxLines = 1,
    )
}

private val TAB_INDICATOR = 2.dp

/** What a whole pane shows when it has nothing: [title], and the [hint] of what to do next, centred in it. */
@Composable
fun EmptyState(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, role = TextRole.BodyStrong, textAlign = TextAlign.Center)
        hint?.let { Text(it, role = TextRole.Caption, textAlign = TextAlign.Center) }
    }
}

/**
 * One line of [text] cut in its middle to fit, as a URL keeps its host and its last path segment; the whole text stays
 * its semantics. Compose Desktop draws `TextOverflow.MiddleEllipsis` as an end ellipsis (ADR-0146).
 */
@Composable
fun MiddleEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
    role: TextRole = TextRole.Body,
) {
    val measurer = rememberTextMeasurer()
    val style = styleOf(role)
    BoxWithConstraints(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        val width = constraints.maxWidth
        val shown =
            remember(text, width, style) {
                middleCut(text, width) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
            }
        Text(shown, role = role, maxLines = 1, softWrap = false)
    }
}

/** The longest cut of [text] whose [widthOf] fits [maxWidth]: its start, "…", its end; [text] itself when it fits. */
internal fun middleCut(
    text: String,
    maxWidth: Int,
    widthOf: (String) -> Int,
): String {
    if (widthOf(text) <= maxWidth) return text
    var low = 0
    var high = text.length - 1
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (widthOf(keep(text, mid)) <= maxWidth) low = mid else high = mid - 1
    }
    return keep(text, low)
}

private fun keep(
    text: String,
    count: Int,
): String = text.take(count - count / 2) + "…" + text.takeLast(count / 2)
