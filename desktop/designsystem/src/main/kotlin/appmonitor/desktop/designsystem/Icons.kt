package appmonitor.desktop.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The window's icons: 16 × 16 strokes, 1.25 wide, round ends, drawn from the mockup's SVGs and tinted where shown (ADR-0209). */
object AppIcons {
    val Sidebar by icon {
        stroke(rect(1.5f, 2.5f, 13f, 11f, 2f))
        stroke(line(6f, 2.5f, 6f, 13.5f))
    }
    val Live by icon {
        fill(circle(8f, 8f, 2f))
        stroke("M4.5 4.5 a5 5 0 0 0 0 7 M11.5 4.5 a5 5 0 0 1 0 7")
    }
    val Record by icon {
        stroke(circle(8f, 8f, 5.5f))
        fill(circle(8f, 8f, 2.6f))
    }
    val RecordOutline by icon {
        stroke(circle(8f, 8f, 5.5f))
        stroke(circle(8f, 8f, 2.6f))
    }
    val Stop by icon { stroke(rect(4f, 4f, 8f, 8f, 1.5f)) }
    val Star by icon { stroke("M8 2 l1.8 3.8 l4.1 0.5 l-3 2.8 l0.8 4.1 L8 11.2 L4.3 13.2 l0.8 -4.1 l-3 -2.8 l4.1 -0.5 z") }
    val Globe by icon {
        stroke(circle(8f, 8f, 6f))
        stroke("M2 8 h12 M8 2 c2 2 2 10 0 12 M8 2 c-2 2 -2 10 0 12")
    }
    val Check by icon { stroke("M3.5 8.5 l3 3 l6 -7", width = 1.6f) }
    val Search by icon {
        stroke(circle(6.5f, 6.5f, 4f))
        stroke(line(9.5f, 9.5f, 14f, 14f))
    }
    val Copy by icon {
        stroke(rect(5f, 5f, 8.5f, 8.5f, 1.5f))
        stroke("M11 5 V3.5 A1 1 0 0 0 10 2.5 H3.5 a1 1 0 0 0 -1 1 V10 a1 1 0 0 0 1 1 H5")
    }
    val Gear by icon {
        stroke(circle(8f, 8f, 2.5f))
        stroke(
            "M8 1.5 v2 M8 12.5 v2 M1.5 8 h2 M12.5 8 h2 M3.6 3.6 l1.4 1.4 M11 11 l1.4 1.4 M3.6 12.4 l1.4 -1.4 M11 5 l1.4 -1.4",
        )
    }
    val Trash by icon { stroke("M3 4.5 h10 M6.5 4.5 V3 h3 v1.5 M4.5 4.5 l0.6 9 h5.8 l0.6 -9") }
    val Save by icon { stroke("M4 2.5 h8 v11 L8 10.5 l-4 3 z") }
    val Issue by icon {
        stroke(rect(5f, 5f, 6f, 8f, 3f))
        stroke(
            "M6 5 a2 2 0 0 1 4 0 M2.5 8.5 H5 M11 8.5 h2.5 M3 5 l2 1.5 M13 5 l-2 1.5 M3 12.5 l2 -1.5 M13 12.5 l-2 -1.5",
        )
    }
    val Chart by icon { stroke("M2.5 13.5 h11 M4.5 11 V8 M8 11 V4.5 M11.5 11 V6.5") }
    val Mask by icon {
        stroke("M2 5.5 c2 -1 10 -1 12 0 c0 4 -2 6 -6 6 s-6 -2 -6 -6 z")
        fill(circle(5.7f, 7.3f, 0.8f))
        fill(circle(10.3f, 7.3f, 0.8f))
    }
    val Pause by icon {
        stroke(circle(8f, 8f, 6f))
        stroke(line(6.5f, 5.8f, 6.5f, 10.2f))
        stroke(line(9.5f, 5.8f, 9.5f, 10.2f))
    }
    val Export by icon { stroke("M8 10 V2.5 M5 5.5 l3 -3 l3 3 M3 9.5 v3 a1 1 0 0 0 1 1 h8 a1 1 0 0 0 1 -1 v-3") }
    val Replay by icon { stroke("M3 8 a5 5 0 1 0 9 -3 M12 2 v3 h-3") }
    val Ellipsis by icon {
        fill(circle(3.5f, 8f, 1.2f))
        fill(circle(8f, 8f, 1.2f))
        fill(circle(12.5f, 8f, 1.2f))
    }
    val Bolt by icon { stroke("M9.5 2 L5 9 h4 l-2.5 5 L14 7 H9.5 L12 2 z") }
    val Phone by icon {
        stroke(rect(4.5f, 1.5f, 7f, 13f, 1.8f))
        stroke(line(7f, 12.3f, 9f, 12.3f))
    }
    val Android by icon {
        stroke("M3.5 11 V7.5 a4.5 4.5 0 0 1 9 0 V11 z")
        stroke(line(5f, 3f, 6f, 4.5f))
        stroke(line(11f, 3f, 10f, 4.5f))
        fill(circle(6.3f, 8f, 0.6f))
        fill(circle(9.7f, 8f, 0.6f))
    }
    val Filter by icon { stroke("M2.5 3.5 h11 l-4.2 5 v4 l-2.6 1 v-5 z") }
    val ChevronUp by icon { stroke("M4 10 l4 -4 l4 4") }
    val ChevronDown by icon { stroke("M4 6 l4 4 l4 -4") }
    val Pencil by icon { stroke("M10.5 2.5 l3 3 l-8 8 H2.5 v-3 z M9 4 l3 3") }
    val Compare by icon { stroke("M2.5 5.5 h10 M10 3 l2.5 2.5 L10 8 M13.5 10.5 h-10 M6 8 l-2.5 2.5 L6 13") }

    /** Every icon with its name, in the order above: what the gallery draws. */
    val all: List<Pair<String, ImageVector>>
        get() =
            listOf(
                "Sidebar" to Sidebar,
                "Live" to Live,
                "Record" to Record,
                "RecordOutline" to RecordOutline,
                "Stop" to Stop,
                "Star" to Star,
                "Globe" to Globe,
                "Check" to Check,
                "Search" to Search,
                "Copy" to Copy,
                "Gear" to Gear,
                "Trash" to Trash,
                "Save" to Save,
                "Issue" to Issue,
                "Chart" to Chart,
                "Mask" to Mask,
                "Pause" to Pause,
                "Export" to Export,
                "Replay" to Replay,
                "Ellipsis" to Ellipsis,
                "Bolt" to Bolt,
                "Phone" to Phone,
                "Android" to Android,
                "Filter" to Filter,
                "ChevronUp" to ChevronUp,
                "ChevronDown" to ChevronDown,
                "Pencil" to Pencil,
                "Compare" to Compare,
            )
}

/** An [icon] [size] wide in [tint], the screen's secondary text color unless told; decorative, no description. */
@Composable
fun Icon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = IconSize.medium,
    tint: Color = AppMonitorTheme.colors.neutral,
) {
    Image(rememberVectorPainter(icon), contentDescription = null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/**
 * A button that is only an [icon]: the bars' sidebar toggle, search, copy, "⋯"; [description] is what a screen reader says,
 * and what its [Tooltip] shows when [tooltip] is set (ADR-0210); dimmed and inert unless [enabled].
 */
@Composable
fun IconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AppMonitorTheme.colors.neutral,
    tooltip: Boolean = false,
    enabled: Boolean = true,
) {
    val button =
        @Composable {
            Box(
                modifier
                    .clip(RoundedCornerShape(Radius.medium))
                    .alpha(if (enabled) 1f else DISABLED_ALPHA)
                    .clickable(enabled = enabled, onClick = onClick)
                    .semantics {
                        role = Role.Button
                        contentDescription = description
                    }.padding(Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, tint = tint)
            }
        }
    if (tooltip) Tooltip(description, content = button) else button()
}

/** An icon's side: [small] in a sidebar row, [medium] in a bar and on a button, [large] in the palette's field. */
object IconSize {
    val small = 13.dp
    val medium = 14.dp
    val large = 16.dp
}

private const val VIEWPORT = 16f
private const val DISABLED_ALPHA = 0.4f
private const val STROKE = 1.25f

private class IconScope(
    private val builder: ImageVector.Builder,
) {
    fun stroke(
        path: String,
        width: Float = STROKE,
    ) {
        builder.addPath(
            addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun fill(path: String) {
        builder.addPath(addPathNodes(path), fill = SolidColor(Color.Black))
    }
}

private fun icon(draw: IconScope.() -> Unit): Lazy<ImageVector> =
    lazy {
        val builder =
            ImageVector.Builder(
                defaultWidth = VIEWPORT.dp,
                defaultHeight = VIEWPORT.dp,
                viewportWidth = VIEWPORT,
                viewportHeight = VIEWPORT,
            )
        IconScope(builder).draw()
        builder.build()
    }

private fun circle(
    cx: Float,
    cy: Float,
    r: Float,
): String = "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0 z"

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: Float,
): String =
    "M${x + r} $y h${w - 2 * r} a$r $r 0 0 1 $r $r v${h - 2 * r} a$r $r 0 0 1 ${-r} $r " +
        "h${-(w - 2 * r)} a$r $r 0 0 1 ${-r} ${-r} v${-(h - 2 * r)} a$r $r 0 0 1 $r ${-r} z"

private fun line(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): String = "M$x1 $y1 L$x2 $y2"
