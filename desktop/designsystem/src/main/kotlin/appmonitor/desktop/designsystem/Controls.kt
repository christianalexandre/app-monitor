package appmonitor.desktop.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.Text as MaterialText

/**
 * Choices side by side in one bordered box, the [selected] one filled in the primary color (ADR-0204): a detail's tabs
 * ([role] `Tab`), a body's formats ([small], `RadioButton`). [tags] names each choice for tests.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    role: Role = Role.Tab,
    tags: List<String>? = null,
) {
    val colors = AppMonitorTheme.colors
    val shape = RoundedCornerShape(Radius.medium)
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val chosen = index == selected
            val tag = tags?.getOrNull(index)
            MaterialText(
                option,
                Modifier
                    .let { if (tag != null) it.testTag(tag) else it }
                    .selectable(chosen, role = role, onClick = { onSelect(index) })
                    .background(if (chosen) colors.primary else Color.Transparent)
                    .padding(horizontal = if (small) Spacing.sm else Spacing.md, vertical = Spacing.xxs),
                color = if (chosen) colors.onPrimary else colors.neutral,
                style = styleOf(if (small) TextRole.Caption else TextRole.Small),
                maxLines = 1,
            )
        }
    }
}

/** One entry of a [MenuButton]'s menu: its [label], the [tag] tests find it by, what it does [onClick]. */
class MenuAction(
    val label: String,
    val tag: String,
    val onClick: () -> Unit,
)

/** An [IconButton] that opens a menu of [actions], as "⋯" for what does not fit a bar. */
@Composable
fun MenuButton(
    icon: ImageVector,
    description: String,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(icon, description, onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    onClick = {
                        open = false
                        action.onClick()
                    },
                    Modifier.testTag(action.tag),
                ) { MaterialText(action.label, style = styleOf(TextRole.Small)) }
            }
        }
    }
}

/**
 * Key and value rows in monospace, one under the other in a bordered box, every other row on the code background; each
 * [query] match highlighted (ADR-0204). Selectable inside a `SelectionContainer`.
 */
@Composable
fun KeyValueTable(
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    query: String = "",
    keyWidth: Dp = KEY_WIDTH,
) {
    val colors = AppMonitorTheme.colors
    val shape = RoundedCornerShape(Radius.medium)
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border, shape)) {
        rows.forEachIndexed { index, (key, value) ->
            if (index > 0) Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (index % 2 == 0) colors.code else Color.Transparent)
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.Top,
            ) {
                CodeText(key, Modifier.width(keyWidth).padding(end = Spacing.md), query = query, muted = true)
                CodeText(value, Modifier.weight(1f), query = query)
            }
        }
    }
}

private val KEY_WIDTH = 170.dp
