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

/** Every color a component paints, with a light and a dark value; Material's colors are built from them. */
data class AppColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val primary: Color,
    val primaryVariant: Color,
    val onPrimary: Color,
    val secondary: Color,
    val secondaryVariant: Color,
    val onSecondary: Color,
    val error: Color,
    val onError: Color,
    // The row a list shows as selected, and what dims the window under a dialog.
    val selected: Color,
    val scrim: Color,
    // A badge's text in each tone; its background is the same color, faded ([Badge]).
    val success: Color,
    val info: Color,
    val warning: Color,
    val neutral: Color,
)

internal val LightAppColors =
    AppColors(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        primary = Color(0xFF6200EE),
        primaryVariant = Color(0xFF3700B3),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF03DAC6),
        secondaryVariant = Color(0xFF018786),
        onSecondary = Color(0xFF000000),
        error = Color(0xFFB00020),
        onError = Color(0xFFFFFFFF),
        selected = Color(0x1F2196F3),
        scrim = Color(0x66000000),
        success = Color(0xFF1B7F3B),
        info = Color(0xFF1565C0),
        warning = Color(0xFFA15C00),
        neutral = Color(0xFF5F6368),
    )

internal val DarkAppColors =
    AppColors(
        background = Color(0xFF121212),
        surface = Color(0xFF121212),
        onSurface = Color(0xFFFFFFFF),
        primary = Color(0xFFBB86FC),
        primaryVariant = Color(0xFF3700B3),
        onPrimary = Color(0xFF000000),
        secondary = Color(0xFF03DAC6),
        secondaryVariant = Color(0xFF03DAC6),
        onSecondary = Color(0xFF000000),
        error = Color(0xFFCF6679),
        onError = Color(0xFF000000),
        selected = Color(0x3390CAF9),
        scrim = Color(0x99000000),
        success = Color(0xFF7DD99A),
        info = Color(0xFF90CAF9),
        warning = Color(0xFFFFB74D),
        neutral = Color(0xFFB0B4BA),
    )
