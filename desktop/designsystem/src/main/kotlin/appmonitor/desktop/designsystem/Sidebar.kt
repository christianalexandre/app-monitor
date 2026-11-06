package appmonitor.desktop.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A sidebar section's label: "DEVICES", "TRAFFIC", "HOSTS" (ADR-0205). */
@Composable
fun SidebarSection(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(label, modifier.padding(start = Spacing.md, top = Spacing.lg, bottom = Spacing.xs), role = TextRole.Label)
}

/**
 * A sidebar row: an optional [dot] in a tone, the [label] and its [sub] line, then [trailing]; painted as selected while
 * [selected], faded while [dimmed] (a hidden host), lit while hovered (ADR-0205).
 */
@Composable
fun SidebarItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    sub: String? = null,
    dot: BadgeTone? = null,
    dimmed: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = AppMonitorTheme.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val background =
        when {
            selected -> colors.selected
            hovered -> colors.border.copy(alpha = HOVER_ALPHA)
            else -> Color.Transparent
        }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.medium))
            .hoverable(hover)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .background(background)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .alpha(if (dimmed) DIMMED_ALPHA else 1f),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = if (sub == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (dot != null) Box(Modifier.padding(top = if (sub == null) Spacing.none else Spacing.xs)) { StatusDot(dot) }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                role = if (selected) TextRole.BodyStrong else TextRole.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            sub?.let { Text(it, role = TextRole.Caption, secondary = true, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

/** A 6 dp dot in a [tone]'s color: a connected device, the recording, a running server (ADR-0205). */
@Composable
fun StatusDot(
    tone: BadgeTone,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(DOT_SIZE).clip(CircleShape).background(colorOf(tone)))
}

private val DOT_SIZE = 6.dp
private const val HOVER_ALPHA = 0.45f
private const val DIMMED_ALPHA = 0.45f
