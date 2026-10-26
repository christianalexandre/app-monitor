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
        background = AppGray.gray50,
        surface = AppGray.gray0,
        onSurface = AppGray.gray800,
        primary = AppBlue.blue500,
        primaryVariant = AppBlue.blue700,
        onPrimary = AppGray.gray0,
        secondary = AppBlue.blue500,
        secondaryVariant = AppBlue.blue600,
        onSecondary = AppGray.gray0,
        error = AppRed.red700,
        onError = AppGray.gray0,
        selected = AppBlue.blue500.copy(alpha = 0.12f),
        scrim = AppGray.gray900.copy(alpha = 0.4f),
        success = AppGreen.green700,
        info = AppBlue.blue600,
        warning = AppAmber.amber700,
        neutral = AppGray.gray600,
    )

internal val DarkAppColors =
    AppColors(
        background = AppGray.gray900,
        surface = AppGray.gray850,
        onSurface = AppGray.gray200,
        primary = AppBlue.blue300,
        primaryVariant = AppBlue.blue500,
        onPrimary = AppGray.gray900,
        secondary = AppBlue.blue300,
        secondaryVariant = AppBlue.blue400,
        onSecondary = AppGray.gray900,
        error = AppRed.red300,
        onError = AppGray.gray900,
        selected = AppBlue.blue300.copy(alpha = 0.2f),
        scrim = AppGray.gray900.copy(alpha = 0.6f),
        success = AppGreen.green300,
        info = AppBlue.blue200,
        warning = AppAmber.amber300,
        neutral = AppGray.gray400,
    )
