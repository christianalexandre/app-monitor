package appmonitor.desktop.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material.Text as MaterialText

/** The "×" of a [SearchField] that empties it. */
const val SEARCH_CLEAR = "search-clear"

/** A one-line search box as tall as a button row: [placeholder] while empty, "×" to empty it (ADR-0153). */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppMonitorTheme.colors
    val shape = RoundedCornerShape(Radius.small)
    val style = styleOf(TextRole.Small).copy(color = colors.onSurface)
    BasicTextField(
        value,
        onValueChange,
        modifier
            .height(SEARCH_HEIGHT)
            .border(1.dp, colors.onSurface.copy(alpha = OUTLINE_ALPHA), shape)
            .background(colors.surface, shape),
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Row(Modifier.padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) MaterialText(placeholder, color = colors.neutral, style = styleOf(TextRole.Small), maxLines = 1)
                    field()
                }
                if (value.isNotEmpty()) {
                    MaterialText(
                        "×",
                        Modifier
                            .clickable { onValueChange("") }
                            .semantics {
                                role = Role.Button
                                contentDescription = "Clear search"
                            }.padding(start = Spacing.xs)
                            .testTag(SEARCH_CLEAR),
                        color = colors.neutral,
                        style = styleOf(TextRole.Body),
                    )
                }
            }
        },
    )
}

/** A one-character button, as small as its [glyph]: "↑", "↓"; [description] is what a screen reader says. */
@Composable
fun GlyphButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialText(
        glyph,
        modifier
            .clip(RoundedCornerShape(Radius.small))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            }.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        color = AppMonitorTheme.colors.primary,
        style = styleOf(TextRole.BodyStrong),
    )
}

/** A short note over the content, in the inverse colors, for a moment: "Copied" (ADR-0153). */
@Composable
fun Toast(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppMonitorTheme.colors
    Box(
        modifier
            .background(colors.onSurface.copy(alpha = TOAST_ALPHA), RoundedCornerShape(Radius.small))
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        MaterialText(text, color = colors.surface, style = styleOf(TextRole.SmallStrong))
    }
}

private val SEARCH_HEIGHT = 32.dp
private const val OUTLINE_ALPHA = 0.24f
private const val TOAST_ALPHA = 0.9f
