package appmonitor.desktop.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material.Text as MaterialText

/** [content] with [text] in a [TooltipBubble] under the pointer while it rests there (ADR-0210). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = { TooltipBubble(text) },
        modifier = modifier,
        delayMillis = TOOLTIP_DELAY_MS,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
        content = content,
    )
}

/** A tooltip's box: [text] in small type, the surface's color on the text's, so it reads over any layer. */
@Composable
fun TooltipBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppMonitorTheme.colors
    MaterialText(
        text,
        modifier
            .background(colors.onSurface, RoundedCornerShape(Radius.small))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        color = colors.surface,
        style = styleOf(TextRole.Caption),
        maxLines = 1,
    )
}

private const val TOOLTIP_DELAY_MS = 500
