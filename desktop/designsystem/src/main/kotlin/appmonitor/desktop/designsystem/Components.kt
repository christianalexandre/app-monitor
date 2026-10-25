package appmonitor.desktop.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.Checkbox as MaterialCheckbox
import androidx.compose.material.Divider as MaterialDivider
import androidx.compose.material.Text as MaterialText
import androidx.compose.material.TextButton as MaterialTextButton

/** Text in a [role]; [error] paints it in the theme's error color. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    role: TextRole = TextRole.Body,
    error: Boolean = false,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    MaterialText(
        text,
        modifier,
        color = if (error) MaterialTheme.colors.error else Color.Unspecified,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        style = styleOf(role),
    )
}

/** A flat button showing [label]; [singleLine] cuts a long one with an ellipsis. */
@Composable
fun TextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
) {
    MaterialTextButton(onClick = onClick, modifier, enabled = enabled) {
        if (singleLine) MaterialText(label, maxLines = 1, overflow = TextOverflow.Ellipsis) else MaterialText(label)
    }
}

/** An outlined field; [label] floats above it, [placeholder] shows while it is empty. */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
) {
    OutlinedTextField(
        value,
        onValueChange,
        modifier,
        label = label?.let { { MaterialText(it) } },
        placeholder = placeholder?.let { { MaterialText(it) } },
        singleLine = singleLine,
    )
}

/** [onCheckedChange] null leaves the click to a parent, as [CheckboxRow] does. */
@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    MaterialCheckbox(checked, onCheckedChange, modifier)
}

/** A checkbox and its [label], toggled by a click anywhere on the row; [modifier] goes inside the toggle. */
@Composable
fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(Modifier.toggleable(checked, onValueChange = onCheckedChange).then(modifier), verticalAlignment = Alignment.CenterVertically) {
        MaterialCheckbox(checked, onCheckedChange = null)
        Text(label)
    }
}

/** A horizontal rule across its parent. */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    MaterialDivider(modifier)
}

/** A one-pixel rule between two panes of a row. */
@Composable
fun VerticalDivider() {
    MaterialDivider(Modifier.fillMaxHeight().width(1.dp))
}

/** Dims the window and takes its clicks; [content] is centred. */
@Composable
fun Overlay(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AppMonitorTheme.colors.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A card over an [Overlay], its [content] in a column; [scrollable] when it may outgrow the window. */
@Composable
fun DialogCard(
    width: DialogWidth,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Overlay {
        Card(Modifier.width(width.width), elevation = Elevation.dialog) {
            Column(
                (if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

/** A dialog's buttons, at its end. */
@Composable
fun DialogActions(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = content)
}

/** A button showing [label] and the [chosen] option, opening a menu of [any] (null when unset) and [options]. */
@Composable
fun <T : Any> Choice(
    label: String,
    chosen: T?,
    options: List<T>,
    nameOf: (T) -> String,
    any: String? = "Any",
    onChoose: (T?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton("$label: ${chosen?.let(nameOf) ?: any.orEmpty()} ▾", onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            (if (any == null) options else listOf(null) + options).forEach { option ->
                DropdownMenuItem(onClick = {
                    onChoose(option)
                    open = false
                }) { MaterialText(option?.let(nameOf) ?: any.orEmpty()) }
            }
        }
    }
}

/** A pane's heading, padded like its rows. */
@Composable
fun PaneTitle(title: String) {
    Text(title, Modifier.padding(Spacing.lg), role = TextRole.Title)
}

/** What a pane shows instead of rows: nothing yet, nothing matching, still loading. */
@Composable
fun Placeholder(text: String) {
    Text(text, Modifier.padding(Spacing.lg), role = TextRole.Caption)
}

/** A titled block of a detail pane, spaced from the one above. */
@Composable
fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(Spacing.xl))
    Text(title, role = TextRole.Heading)
    content()
}

/** A list row: clicked with [onClick], painted in the theme's selected color while [selected]. */
fun Modifier.selectableItem(
    selected: Boolean,
    onClick: () -> Unit,
): Modifier =
    composed {
        val color = AppMonitorTheme.colors.selected
        clickable(onClick = onClick).then(if (selected) Modifier.background(color) else Modifier)
    }
