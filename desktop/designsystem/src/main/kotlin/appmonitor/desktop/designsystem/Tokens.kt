package appmonitor.desktop.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Every gap and padding a screen uses; a value missing here is added here, not written in the screen. */
object Spacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 12.dp
    val xl: Dp = 16.dp
    val xxl: Dp = 24.dp
}

/** Corner radii; the theme's shapes are built from them. */
object Radius {
    val none: Dp = 0.dp
    val small: Dp = 4.dp
}

object Elevation {
    val dialog: Dp = 8.dp
}

/** Widths of [DialogCard], from a two-button question to a table of endpoints. */
enum class DialogWidth(
    val width: Dp,
) {
    Narrow(420.dp),
    Medium(520.dp),
    Large(640.dp),
    Wide(720.dp),
    Table(1180.dp),
}

/** The colors the theme adds to Material's: the row a list shows as selected, and what dims the window under a dialog. */
data class AppColors(
    val selected: Color,
    val scrim: Color,
)

internal val LightAppColors = AppColors(selected = Color(0x1F2196F3), scrim = Color(0x66000000))

internal val DarkAppColors = AppColors(selected = Color(0x3390CAF9), scrim = Color(0x99000000))
